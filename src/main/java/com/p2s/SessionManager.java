package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.network.S2CChatResponsePayload;
import com.p2s.network.S2CPatchPreviewPayload;
import com.p2s.network.S2CSessionSyncPayload;
import com.p2s.network.S2CToolBridgePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SessionManager {
    private static final Gson GSON = new Gson();
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 40;
    private static final int MAX_AGENT_LOOPS = 6;
    private static final int MAX_TOOL_JSON_CHARS = 12000;
    private static final int MAX_SUMMARY_LINES = 20;
    private static final int MAX_SUMMARY_CHARS = 4000;
    private static final int MAX_PREVIEW_SUMMARY_CHARS = 512;
    private static final int MAX_PREVIEW_DETAIL_CHARS = 12000;
    private static final int MAX_PREVIEW_OPERATION_LINES = 40;
    private static final int MAX_PREVIEW_WARNING_LINES = 20;

    private static final String SESSION_TOOL_CONTRACT = """
            ## IDE Session Contract
            - Use read_workspace_state first when you need current structure/revision/bounds.
              - No arguments: returns full state with complete (or truncated) script JSON.
              - part="name": returns palette + only that named part (use when script is large).
              - line_start=N, line_end=M: returns pretty-printed script lines N..M (1-based, inclusive).
              - part and line_start/line_end are mutually exclusive.
            - Propose all edits with propose_patch; do not directly build blocks.
            - The user reviews a preview and confirms apply/discard.
            - If a patch is pending, read_workspace_state returns a staged revision that includes pending changes.
              Use that staged revision for subsequent propose_patch calls (changes stay un-applied until confirm).
            - Use search_block_ids when unsure about a block id.
            - If propose_patch returns errors or warnings, adjust the patch before asking user to apply.
            - Keep patches minimal and focused on requested changes.
            - Only call read_workspace_state/propose_patch/explain_plan/search_block_ids in session mode.
            ## Verification Model
            - propose_patch uses strict verification: for modify/delete operations you MUST provide old_actions
              matching the current state exactly. If verification fails, you get verification_failed with the
              actual state — call read_workspace_state then retry with corrected old_actions.
            - Operations: insert_part, delete_part, replace_part, insert_actions, delete_actions,
              replace_actions, move_actions, update_palette.
            - For update_palette, each entry requires old_value for modify/delete, old_value=null for add-new.
            """;

    private SessionManager() {
    }

    public static void handleChatMessage(ServerPlayer player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }

        Session session = ensureSession(player);
        String playerName = player.getGameProfile().getName();
        String sessionId = session == null ? "-" : session.id;
        int msgLen = safeLength(message);
        boolean inFlight = session != null && session.inFlight;
        P2SMod.LOGGER.info("AgentLoop receive -> player={}, session={}, msgLen={}, inFlight={}", playerName, sessionId, msgLen, inFlight);

        if (session.inFlight) {
            P2SMod.LOGGER.warn("AgentLoop reject busy -> player={}, session={}", playerName, sessionId);
            sendChatResponse(player, "Busy with previous request.", false, "error");
            return;
        }

        if (session.pendingPatch != null && session.runtimeState == RuntimeState.AWAITING_CONFIRM) {
            sendChatResponse(player, "Pending patch awaiting decision. Use Apply or Discard first.", false, "awaiting_confirm");
            sendSessionSync(player, session);
            sendPatchPreview(player, session.pendingPatch.preview);
            return;
        }

        session.inFlight = true;
        session.runtimeState = RuntimeState.PLANNING;

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", message.trim());
        int historyBefore = session.history == null ? 0 : session.history.size();
        session.history.add(userMsg);
        trimHistory(session);
        int historyAfter = session.history == null ? 0 : session.history.size();
        P2SMod.LOGGER.info("AgentLoop history -> player={}, session={}, size {}->{}", playerName, sessionId, historyBefore, historyAfter);

        sendChatResponse(player, "", false, "planning");
        sendSessionSync(player, session);

        runAgentLoop(player, session, MAX_AGENT_LOOPS);
    }

    private static void runAgentLoop(ServerPlayer player, Session session, int remaining) {
        if (player == null || session == null) {
            return;
        }

        String playerName = player.getGameProfile().getName();
        String sessionId = session.id;
        List<JsonObject> historySnapshot = deepCopyMessages(session.history);
        int snapshotSize = historySnapshot.size();
        session.runtimeState = RuntimeState.PLANNING;
        P2SMod.LOGGER.info("AgentLoop llm request -> player={}, session={}, snapshotSize={}, remaining={}", playerName, sessionId, snapshotSize, remaining);
        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] AgentLoop history snapshot: {}", GSON.toJson(historySnapshot));
        }
        long llmStartMs = System.currentTimeMillis();
        long timeoutSeconds = Math.max(
                1,
                Math.max(ModConfig.SESSION_JOB_TIMEOUT_SECONDS, ModConfig.HTTP_TIMEOUT_SECONDS + 5)
        );

        LLMService.requestWithHistory(historySnapshot)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .thenAccept(result -> {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            server.execute(() -> handleAgentResult(player, session, result, remaining, llmStartMs));
        }).exceptionally(ex -> {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return null;
            }
            server.execute(() -> {
                session.inFlight = false;
                session.runtimeState = RuntimeState.FAILED;
                P2SMod.LOGGER.error("AgentLoop failed -> player={}, session={}", playerName, sessionId, ex);
                sendChatResponse(player, "Request failed: " + formatAgentError(ex, timeoutSeconds), false, "error");
                sendSessionSync(player, session);
            });
            return null;
        });
    }

    private static void handleAgentResult(ServerPlayer player, Session session, LLMService.SessionResult result, int remaining, long llmStartMs) {
        if (player == null || session == null) {
            return;
        }

        String playerName = player.getGameProfile().getName();
        String sessionId = session.id;

        if (result == null) {
            session.inFlight = false;
            session.runtimeState = RuntimeState.FAILED;
            sendChatResponse(player, "Request failed: empty response", false, "error");
            sendSessionSync(player, session);
            return;
        }

        long llmMs = System.currentTimeMillis() - llmStartMs;
        int textLen = result.textContent() == null ? 0 : result.textContent().length();
        int toolCallCount = result.toolCalls() == null ? 0 : result.toolCalls().size();
        boolean hasScript = result.script() != null;
        P2SMod.LOGGER.info("AgentLoop llm response -> player={}, session={}, ms={}, textLen={}, toolCalls={}, hasScript={}",
                playerName, sessionId, llmMs, textLen, toolCallCount, hasScript);
        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] AgentLoop full text content: {}", result.textContent());
            if (result.toolCalls() != null) {
                for (LLMService.ToolCall tc : result.toolCalls()) {
                    P2SMod.LOGGER.info("[DEBUG] AgentLoop tool call: name={}, id={}, args={}", tc.name(), tc.id(), tc.arguments());
                }
            }
            P2SMod.LOGGER.info("[DEBUG] AgentLoop rawAssistant: {}", result.rawAssistantMessage());
        }

        JsonObject assistant = result.rawAssistantMessage() == null ? null : result.rawAssistantMessage().deepCopy();
        if (assistant != null) {
            session.history.add(assistant);
            trimHistory(session);
        }

        if (toolCallCount > 0) {
            ToolCallProcessingResult toolResult = processToolCalls(session, result.toolCalls(), playerName, sessionId);
            for (JsonObject toolMsg : toolResult.toolMessages) {
                session.history.add(toolMsg);
                trimHistory(session);
            }

            if (toolResult.autoApplyRequested) {
                toolResult.autoApplied = commitPendingPatch(player, session, true);
            }

            if (result.textContent() != null && !result.textContent().isBlank()) {
                String status = session.pendingPatch != null
                        ? "awaiting_confirm"
                        : (toolResult.autoApplied ? "committed" : "thinking");
                sendChatResponse(player, result.textContent(), false, status);
            }

            sendSessionSync(player, session);
            if (toolResult.previewUpdated) {
                sendPatchPreview(player, session.pendingPatch == null ? null : session.pendingPatch.preview);
            }

            if (remaining <= 0) {
                session.inFlight = false;
                if (P2SMod.DEBUG) {
                    P2SMod.LOGGER.info("[DEBUG] AgentLoop exhausted remaining iterations -> player={}, session={}, pendingPatch={}", playerName, sessionId, session.pendingPatch != null);
                }
                if (session.pendingPatch != null) {
                    session.runtimeState = RuntimeState.AWAITING_CONFIRM;
                    sendChatResponse(player, "Patch prepared. Please review and confirm.", false, "awaiting_confirm");
                } else if (toolResult.autoApplied) {
                    session.runtimeState = RuntimeState.IDLE;
                } else {
                    session.runtimeState = RuntimeState.FAILED;
                    sendChatResponse(player, "Tool calls exceeded max iterations.", false, "error");
                }
                sendSessionSync(player, session);
                return;
            }

            session.runtimeState = session.pendingPatch == null ? RuntimeState.PLANNING : RuntimeState.AWAITING_CONFIRM;
            sendChatResponse(player, "", false, session.pendingPatch == null ? "thinking" : "awaiting_confirm");
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] AgentLoop continuing -> player={}, session={}, remaining={}, runtimeState={}", playerName, sessionId, remaining - 1, session.runtimeState);
            }
            runAgentLoop(player, session, remaining - 1);
            return;
        }

        if (hasScript) {
            applyLegacyScriptAsCommit(player, session, result.script(), result.textContent());
            session.inFlight = false;
            return;
        }

        if (result.textContent() != null && !result.textContent().isBlank()) {
            sendChatResponse(player, result.textContent(), false, session.pendingPatch != null ? "awaiting_confirm" : "done");
        } else {
            sendChatResponse(player, "", false, session.pendingPatch != null ? "awaiting_confirm" : "done");
        }

        if (session.pendingPatch != null) {
            session.runtimeState = RuntimeState.AWAITING_CONFIRM;
            sendPatchPreview(player, session.pendingPatch.preview);
        } else {
            session.runtimeState = RuntimeState.IDLE;
            session.turnCount += 1;
        }

        sendSessionSync(player, session);
        session.inFlight = false;
    }

    private static ToolCallProcessingResult processToolCalls(Session session, List<LLMService.ToolCall> toolCalls, String playerName, String sessionId) {
        ToolCallProcessingResult result = new ToolCallProcessingResult();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return result;
        }

        for (LLMService.ToolCall call : toolCalls) {
            if (call == null || call.name() == null) {
                continue;
            }

            String toolName = call.name();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] processToolCalls -> tool={}, id={}, args={}", toolName, call.id(), call.arguments());
            }
            switch (toolName) {
                case "read_workspace_state" -> {
                    ReadWorkspaceArgs wsArgs = parseReadWorkspaceArgs(call.arguments());
                    JsonObject payload = handleReadWorkspaceState(session, toolName, wsArgs);
                    result.toolMessages.add(buildToolMessage(call, payload));
                    P2SMod.LOGGER.info("AgentLoop tool read_workspace_state -> player={}, session={}", playerName, sessionId);
                    if (P2SMod.DEBUG) {
                        P2SMod.LOGGER.info("[DEBUG] read_workspace_state result: {}", GSON.toJson(payload));
                    }
                }
                case "propose_patch" -> {
                    session.runtimeState = RuntimeState.VALIDATING;
                    PatchModels.StructurePatch patch = parsePatchArguments(call.arguments());
                    if (P2SMod.DEBUG) {
                        P2SMod.LOGGER.info("[DEBUG] propose_patch raw args: {}", call.arguments());
                        P2SMod.LOGGER.info("[DEBUG] propose_patch parsed patch: {}", patch == null ? "null" : GSON.toJson(patch));
                    }
                    if (patch == null) {
                        result.toolMessages.add(buildToolMessage(call, buildToolError(toolName, "Invalid patch arguments")));
                        P2SMod.LOGGER.warn("AgentLoop tool propose_patch failed -> player={}, session={}, reason=parse_failed", playerName, sessionId);
                        break;
                    }

                    String committedRevision = session.revision == null ? "" : session.revision;
                    String stagedRevision = committedRevision;
                    StructureBuilder.VbsScriptV2 stagedBase = session.current;
                    if (session.pendingPatch != null && session.pendingPatch.nextScript != null) {
                        stagedBase = session.pendingPatch.nextScript;
                        if (session.pendingPatch.revisionAfter != null && !session.pendingPatch.revisionAfter.isBlank()) {
                            stagedRevision = session.pendingPatch.revisionAfter;
                        }
                    }

                    String patchBase = patch.baseRevision == null ? "" : patch.baseRevision.trim();
                    if (!patchBase.isBlank()
                            && !patchBase.equals(committedRevision)
                            && !patchBase.equals(stagedRevision)) {
                        result.toolMessages.add(buildToolMessage(call, buildToolError(toolName, "Patch base_revision mismatch")));
                        session.runtimeState = RuntimeState.FAILED;
                        break;
                    }

                    StructureBuilder.VbsScriptV2 committedBase = copyScript(session.current);
                    StructurePatchEngine.PatchApplyResult applyResult = StructurePatchEngine.applyPatchToModel(stagedBase, patch);

                    // Handle verification failure — stay in VALIDATING, let agent retry
                    if (!applyResult.ok && applyResult.error != null) {
                        JsonObject payload = new JsonObject();
                        payload.addProperty("ok", false);
                        payload.addProperty("verification_failed", true);
                        payload.addProperty("operation_index", applyResult.error.operationIndex);
                        payload.addProperty("op", applyResult.error.op == null ? "" : applyResult.error.op);
                        payload.addProperty("part", applyResult.error.part == null ? "" : applyResult.error.part);
                        payload.addProperty("error", applyResult.error.error == null ? "" : applyResult.error.error);
                        if (applyResult.error.expected != null) {
                            payload.add("expected", GSON.toJsonTree(applyResult.error.expected));
                        }
                        if (applyResult.error.actual != null) {
                            payload.add("actual", GSON.toJsonTree(applyResult.error.actual));
                        }
                        payload.addProperty("hint", applyResult.error.hint == null ? "" : applyResult.error.hint);
                        result.toolMessages.add(buildToolMessage(call, payload));
                        // Keep runtimeState as VALIDATING — agent should retry
                        P2SMod.LOGGER.info("AgentLoop tool propose_patch verification_failed -> player={}, session={}, op={}, part={}, error={}",
                                playerName, sessionId, applyResult.error.op, applyResult.error.part, applyResult.error.error);
                        break;
                    }

                    StructureBuilder.VbsScriptV2 next = applyResult.result;
                    StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(committedBase, next);

                    PatchModels.StructurePatch mergedPatch = mergePatches(
                            session.pendingPatch == null ? null : session.pendingPatch.patch,
                            patch,
                            committedRevision
                    );
                    PatchModels.ValidationResult validation = PatchValidator.validate(
                            mergedPatch,
                            committedRevision,
                            session.size,
                            next,
                            diff,
                            ModConfig.MAX_PATCH_OPS,
                            ModConfig.MAX_BLOCKS_PER_COMMIT
                    );

                    if (!validation.ok) {
                        JsonObject payload = buildToolError(toolName, String.join("; ", validation.errors));
                        JsonArray errorArray = new JsonArray();
                        for (String error : validation.errors) {
                            errorArray.add(error);
                        }
                        payload.add("errors", errorArray);
                        payload.addProperty("risk", validation.riskLevel);
                        payload.addProperty("changed", validation.estimatedChangedBlocks);
                        result.toolMessages.add(buildToolMessage(call, payload));
                        session.runtimeState = RuntimeState.FAILED;
                        break;
                    }

                    PendingPatch pending = new PendingPatch();
                    pending.patch = mergedPatch;
                    pending.baseScript = committedBase;
                    pending.nextScript = next;
                    pending.diff = diff;
                    pending.validation = validation;
                    pending.preview = buildPatchPreview(mergedPatch, validation, diff, committedRevision);
                    pending.revisionBefore = committedRevision;
                    pending.revisionAfter = nextRevision();
                    validation.requiresConfirm = requiresConfirm(pending.preview.changedBlocks);
                    session.pendingPatch = pending;
                    session.runtimeState = RuntimeState.PATCH_GENERATED;

                    JsonObject payload = buildToolSuccess(toolName);
                    payload.addProperty("preview", pending.preview.summary);
                    payload.addProperty("changed", pending.preview.changedBlocks);
                    payload.addProperty("risk", pending.preview.riskLevel);
                    payload.addProperty("requires_confirm", validation.requiresConfirm);
                    if (!validation.warnings.isEmpty()) {
                        JsonArray warningArray = new JsonArray();
                        for (String warning : validation.warnings) {
                            warningArray.add(warning);
                        }
                        payload.add("warnings", warningArray);
                        payload.addProperty("warning_count", validation.warnings.size());
                    }
                    result.toolMessages.add(buildToolMessage(call, payload));

                    result.previewUpdated = true;
                    result.autoApplyRequested = !validation.requiresConfirm;
                    P2SMod.LOGGER.info("AgentLoop tool propose_patch ok -> player={}, session={}, changed={}, risk={}",
                            playerName, sessionId, pending.preview.changedBlocks, pending.preview.riskLevel);
                }
                case "explain_plan" -> {
                    JsonObject payload = buildToolSuccess(toolName);
                    payload.addProperty("accepted", true);
                    result.toolMessages.add(buildToolMessage(call, payload));
                }
                case "search_block_ids" -> {
                    SearchBlockArgs args = parseSearchBlockArgs(call.arguments());
                    if (args == null || args.query == null || args.query.isBlank()) {
                        result.toolMessages.add(buildToolMessage(call, buildToolError(toolName, "Missing query")));
                        break;
                    }
                    List<String> matches = StructureBuilder.searchBlockIds(args.query, args.limit);
                    String closest = StructureBuilder.closestBlockId(args.query);
                    JsonObject payload = buildToolSuccess(toolName);
                    payload.addProperty("query", args.query);
                    payload.addProperty("limit", args.limit);
                    JsonArray matchArray = new JsonArray();
                    for (String match : matches) {
                        matchArray.add(match);
                    }
                    payload.add("matches", matchArray);
                    if (closest != null && !closest.isBlank()) {
                        payload.addProperty("closest", closest);
                    }
                    if (matches.isEmpty()) {
                        payload.addProperty("warning", "No matches");
                    }
                    result.toolMessages.add(buildToolMessage(call, payload));
                }
                default -> {
                    result.toolMessages.add(buildToolMessage(call, buildToolError(toolName, "Unknown tool")));
                    P2SMod.LOGGER.warn("AgentLoop tool unknown -> player={}, session={}, tool={}", playerName, sessionId, toolName);
                }
            }
        }

        if (session.pendingPatch != null) {
            session.runtimeState = RuntimeState.AWAITING_CONFIRM;
        }
        return result;
    }

    static boolean hasActiveSession(UUID playerId) {
        return playerId != null && sessions.containsKey(playerId);
    }

    public static void handleSessionAction(ServerPlayer player, String action, String payload) {
        if (player == null || action == null) {
            return;
        }

        switch (action) {
            case "start" -> startSession(player, payload);
            case "end" -> endSession(player);
            case "undo" -> undo(player);
            case "redo" -> redo(player);
            case "save" -> save(player, payload);
            case "apply" -> applyPendingPatch(player);
            case "discard" -> discardPendingPatch(player, payload);
            default -> player.displayClientMessage(Component.literal("Unknown session action: " + action), false);
        }
    }

    public static void handleToolBridgeRequest(ServerPlayer player, String requestId, String toolName, String argumentsJson) {
        if (player == null) {
            return;
        }
        String rid = requestId == null ? "" : requestId;
        String normalizedTool = toolName == null ? "" : toolName.trim();
        if (normalizedTool.isBlank()) {
            sendToolBridgeResponse(player, rid, false, null, "Missing tool name");
            return;
        }

        JsonElement arguments = null;
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                arguments = JsonParser.parseString(argumentsJson);
            } catch (Exception e) {
                sendToolBridgeResponse(player, rid, false, null, "Invalid arguments JSON: " + e.getMessage());
                return;
            }
        }

        Session session = ensureSession(player);
        String playerName = player.getGameProfile().getName();
        String sessionId = session == null ? "-" : session.id;
        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] ToolBridge request -> player={}, session={}, requestId={}, tool={}, argsJson={}", playerName, sessionId, rid, normalizedTool, argumentsJson);
        }
        try {
            LLMService.ToolCall call = new LLMService.ToolCall(rid, normalizedTool, arguments);
            ToolCallProcessingResult toolResult = processToolCalls(session, List.of(call), playerName, sessionId);
            if (toolResult.autoApplyRequested) {
                toolResult.autoApplied = commitPendingPatch(player, session, true);
            }

            JsonObject payload = extractToolBridgePayload(toolResult, normalizedTool);
            if (toolResult.autoApplied) {
                payload.addProperty("auto_applied", true);
            }

            sendSessionSync(player, session);
            if (toolResult.previewUpdated) {
                sendPatchPreview(player, session.pendingPatch == null ? null : session.pendingPatch.preview);
            }
            sendToolBridgeResponse(player, rid, true, payload, null);
        } catch (Exception e) {
            P2SMod.LOGGER.error("Tool bridge failed -> player={}, session={}, tool={}", playerName, sessionId, normalizedTool, e);
            sendToolBridgeResponse(player, rid, false, null, "Tool bridge failed: " + e.getMessage());
        }
    }

    public static Session startSession(ServerPlayer player, String payload) {
        if (player == null) {
            return null;
        }

        BlockPos restoredOrigin = null;
        Vec3i restoredSize = null;
        if (payload != null && !payload.isBlank()) {
            try {
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                if (json.has("originX") && json.has("originY") && json.has("originZ")) {
                    restoredOrigin = new BlockPos(
                            json.get("originX").getAsInt(),
                            json.get("originY").getAsInt(),
                            json.get("originZ").getAsInt()
                    );
                }
                if (json.has("hasSize") && json.get("hasSize").getAsBoolean()
                        && json.has("sizeX") && json.has("sizeY") && json.has("sizeZ")) {
                    restoredSize = new Vec3i(
                            json.get("sizeX").getAsInt(),
                            json.get("sizeY").getAsInt(),
                            json.get("sizeZ").getAsInt()
                    );
                }
            } catch (Exception e) {
                P2SMod.LOGGER.debug("Could not parse start payload as origin/size: {}", e.getMessage());
            }
        }

        Session session;
        if (restoredOrigin != null) {
            session = createSession(player, restoredOrigin, restoredSize);
        } else {
            session = createSession(player);
        }
        sessions.put(player.getUUID(), session);
        sendSessionSync(player, session);
        sendPatchPreview(player, null);
        P2SMod.LOGGER.info("Session start -> player={}, session={}, origin={}, size={}",
                player.getGameProfile().getName(), session.id, posString(session.origin), sizeString(session.size));
        player.displayClientMessage(Component.literal("Session started: " + session.id), false);
        return session;
    }

    public static void endSession(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Session removed = sessions.remove(player.getUUID());
        sendSessionSync(player, null);
        sendPatchPreview(player, null);
        P2SMod.LOGGER.info("Session end -> player={}, session={}, turns={}, parts={}, blocks={}",
                player.getGameProfile().getName(),
                removed == null ? "-" : removed.id,
                removed == null ? 0 : removed.turnCount,
                removed == null || removed.current == null || removed.current.structures == null ? 0 : removed.current.structures.size(),
                removed == null ? 0 : countBlocks(removed.current));
        player.displayClientMessage(Component.literal("Session ended"), false);
    }

    public static void undo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null || session.undoStack.isEmpty()) {
            player.displayClientMessage(Component.literal("Nothing to undo"), false);
            return;
        }

        CommitEntry commit = session.undoStack.pop();
        StructurePatchEngine.applyBlockOps(player.serverLevel(), session.origin, commit.inverseOps);

        session.current = copyScript(commit.beforeScript);
        session.revision = commit.revisionBefore;
        session.redoStack.push(commit);
        session.pendingPatch = null;
        session.runtimeState = RuntimeState.IDLE;

        sendPatchPreview(player, null);
        sendSessionSync(player, session);
        player.displayClientMessage(Component.literal("Undo applied: " + commit.summary), false);
    }

    public static void redo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null || session.redoStack.isEmpty()) {
            player.displayClientMessage(Component.literal("Nothing to redo"), false);
            return;
        }

        CommitEntry commit = session.redoStack.pop();
        StructurePatchEngine.applyBlockOps(player.serverLevel(), session.origin, commit.forwardOps);

        session.current = copyScript(commit.afterScript);
        session.revision = commit.revisionAfter;
        session.undoStack.push(commit);
        session.pendingPatch = null;
        session.runtimeState = RuntimeState.IDLE;

        sendPatchPreview(player, null);
        sendSessionSync(player, session);
        player.displayClientMessage(Component.literal("Redo applied: " + commit.summary), false);
    }

    public static void save(ServerPlayer player, String name) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null || session.current == null) {
            player.displayClientMessage(Component.literal("No active session or empty structure"), false);
            return;
        }
        String saved = ScriptStorage.saveV2("session", session.current, "session", name);
        P2SMod.LOGGER.info("Session save -> player={}, session={}, name={}",
                player.getGameProfile().getName(), session.id, saved);
        player.displayClientMessage(Component.literal("Saved session as " + saved), false);
    }

    public static Session getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    private static void applyPendingPatch(ServerPlayer player) {
        if (player == null) {
            return;
        }

        Session session = sessions.get(player.getUUID());
        if (session == null) {
            player.displayClientMessage(Component.literal("No active session"), false);
            return;
        }
        if (session.inFlight) {
            player.displayClientMessage(Component.literal("Busy with current request"), false);
            return;
        }
        if (session.pendingPatch == null) {
            player.displayClientMessage(Component.literal("No pending patch to apply"), false);
            return;
        }

        PendingPatch pending = session.pendingPatch;
        if (pending.validation == null || !pending.validation.ok) {
            player.displayClientMessage(Component.literal("Pending patch failed validation"), false);
            return;
        }

        if (!commitPendingPatch(player, session, false)) {
            player.displayClientMessage(Component.literal("Failed to apply patch"), false);
        }
    }

    private static boolean commitPendingPatch(ServerPlayer player, Session session, boolean autoApply) {
        if (player == null || session == null || session.pendingPatch == null) {
            return false;
        }

        PendingPatch pending = session.pendingPatch;
        if (pending.validation == null || !pending.validation.ok || pending.diff == null) {
            session.runtimeState = RuntimeState.FAILED;
            sendChatResponse(player, "Pending patch failed validation", false, "error");
            sendSessionSync(player, session);
            return false;
        }
        if (player.serverLevel() == null || session.origin == null) {
            session.runtimeState = RuntimeState.FAILED;
            sendChatResponse(player, "Cannot apply patch in current world context", false, "error");
            sendSessionSync(player, session);
            return false;
        }

        session.runtimeState = RuntimeState.APPLYING;
        sendChatResponse(player, "", true, "applying");

        StructurePatchEngine.applyBlockOps(player.serverLevel(), session.origin, pending.diff.forwardOps);

        CommitEntry commit = new CommitEntry();
        commit.id = UUID.randomUUID().toString();
        commit.revisionBefore = pending.revisionBefore;
        commit.revisionAfter = pending.revisionAfter;
        commit.beforeScript = copyScript(pending.baseScript);
        commit.afterScript = copyScript(pending.nextScript);
        commit.forwardOps = new ArrayList<>(pending.diff.forwardOps);
        commit.inverseOps = new ArrayList<>(pending.diff.inverseOps);
        commit.summary = pending.preview == null ? "patch" : pending.preview.summary;
        commit.patch = pending.patch;

        session.current = copyScript(pending.nextScript);
        session.revision = pending.revisionAfter;
        session.pendingPatch = null;
        session.undoStack.push(commit);
        session.redoStack.clear();
        session.turnCount += 1;
        session.runtimeState = RuntimeState.COMMITTED;

        sendPatchPreview(player, null);
        String prefix = autoApply ? "Patch auto-applied: " : "Patch applied: ";
        sendChatResponse(player, prefix + commit.summary, true, "committed");
        sendSessionSync(player, session);

        session.runtimeState = RuntimeState.IDLE;
        sendSessionSync(player, session);
        return true;
    }

    private static void discardPendingPatch(ServerPlayer player, String reason) {
        if (player == null) {
            return;
        }

        Session session = sessions.get(player.getUUID());
        if (session == null || session.pendingPatch == null) {
            player.displayClientMessage(Component.literal("No pending patch"), false);
            return;
        }

        session.pendingPatch = null;
        session.runtimeState = RuntimeState.CANCELLED;
        sendPatchPreview(player, null);
        String safeReason = reason == null ? "" : reason.trim();
        String message = safeReason.isEmpty() ? "Patch discarded" : "Patch discarded: " + safeReason;
        sendChatResponse(player, message, false, "cancelled");
        sendSessionSync(player, session);

        session.runtimeState = RuntimeState.IDLE;
        sendSessionSync(player, session);
    }

    private static Session ensureSession(ServerPlayer player) {
        Session existing = sessions.get(player.getUUID());
        if (existing != null) {
            return existing;
        }
        return startSession(player, "");
    }

    private static Session createSession(ServerPlayer player) {
        SelectionManager.Selection sel = SelectionManager.get(player.getUUID());
        BlockPos origin = player.blockPosition();
        Vec3i size = null;
        if (sel != null && sel.isComplete()) {
            origin = sel.min();
            size = sel.size();
        }
        return createSession(player, origin, size);
    }

    private static Session createSession(ServerPlayer player, BlockPos origin, Vec3i size) {

        StringBuilder prompt = new StringBuilder(ModConfig.currentSystemPrompt());
        prompt.append("\n\n").append(SESSION_TOOL_CONTRACT);
        if (size != null) {
            prompt.append("\n\n").append(buildAreaConstraint(size));
        }

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", prompt.toString());

        Session session = new Session();
        session.id = UUID.randomUUID().toString();
        session.origin = origin;
        session.size = size;
        session.history = new ArrayList<>();
        session.history.add(systemMsg);
        session.revision = "rev-0";
        session.runtimeState = RuntimeState.IDLE;
        return session;
    }

    private static void applyLegacyScriptAsCommit(ServerPlayer player, Session session, StructureBuilder.VbsScriptV2 script, String text) {
        StructureBuilder.VbsScriptV2 before = copyScript(session.current);
        StructureBuilder.VbsScriptV2 merged = StructureBuilder.mergeScripts(session.current, script);
        StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(before, merged);
        StructurePatchEngine.applyBlockOps(player.serverLevel(), session.origin, diff.forwardOps);

        CommitEntry commit = new CommitEntry();
        commit.id = UUID.randomUUID().toString();
        commit.revisionBefore = session.revision;
        commit.revisionAfter = nextRevision();
        commit.beforeScript = before;
        commit.afterScript = copyScript(merged);
        commit.forwardOps = new ArrayList<>(diff.forwardOps);
        commit.inverseOps = new ArrayList<>(diff.inverseOps);
        commit.summary = "legacy script merge";

        session.current = copyScript(merged);
        session.revision = commit.revisionAfter;
        session.undoStack.push(commit);
        session.redoStack.clear();
        session.turnCount += 1;
        session.runtimeState = RuntimeState.COMMITTED;

        sendChatResponse(player, text == null ? "Applied generated structure." : text, true, "committed");
        sendSessionSync(player, session);

        session.runtimeState = RuntimeState.IDLE;
        sendSessionSync(player, session);
    }

    private static void trimHistory(Session session) {
        while (session.history.size() > MAX_HISTORY && session.history.size() > 1) {
            int end = findTurnGroupEnd(session.history);
            if (end <= 0) break;
            session.history.subList(1, end + 1).clear();
        }
    }

    /**
     * Find the end index (inclusive) of the first complete turn group starting at index 1.
     * Returns -1 if no complete group can be safely removed.
     */
    private static int findTurnGroupEnd(List<JsonObject> history) {
        int size = history.size();
        if (size <= 2) return -1;

        int i = 1;

        // 1) Skip orphan tool messages (compat with corrupted old history)
        if ("tool".equals(role(history, i))) {
            while (i < size && "tool".equals(role(history, i))) i++;
            return (i < size) ? i - 1 : -1;
        }

        // 2) Consume user messages
        while (i < size && "user".equals(role(history, i))) i++;

        // 3) Expect assistant message
        if (i >= size || !"assistant".equals(role(history, i))) {
            return (i > 1 && i < size) ? i - 1 : -1;
        }

        // 4) Check if assistant has tool_calls
        JsonObject asst = history.get(i);
        boolean hasTools = asst.has("tool_calls")
                && asst.get("tool_calls").isJsonArray()
                && asst.getAsJsonArray("tool_calls").size() > 0;
        i++;

        // 5) Consume tool messages
        if (hasTools) {
            int before = i;
            while (i < size && "tool".equals(role(history, i))) i++;
            if (i == before) {
                // assistant has tool_calls but no following tool messages → incomplete, skip
                return -1;
            }
        }

        // 6) Ensure we don't remove everything (keep system + at least 1 message)
        return (i < size) ? i - 1 : -1;
    }

    private static String role(List<JsonObject> history, int index) {
        JsonObject msg = history.get(index);
        return msg != null && msg.has("role") ? msg.get("role").getAsString() : "";
    }

    private static List<JsonObject> deepCopyMessages(List<JsonObject> source) {
        List<JsonObject> copy = new ArrayList<>(source.size());
        for (JsonObject msg : source) {
            copy.add(msg.deepCopy());
        }
        return copy;
    }

    private static StructureBuilder.VbsScriptV2 copyScript(StructureBuilder.VbsScriptV2 script) {
        return script == null ? null : GSON.fromJson(GSON.toJson(script), StructureBuilder.VbsScriptV2.class);
    }

    private static JsonObject buildWorkspaceStatePayload(Session session) {
        JsonObject payload = new JsonObject();
        String committedRevision = session.revision == null ? "" : session.revision;
        boolean hasPending = session.pendingPatch != null && session.pendingPatch.nextScript != null;
        String stagedRevision = committedRevision;
        if (hasPending && session.pendingPatch.revisionAfter != null && !session.pendingPatch.revisionAfter.isBlank()) {
            stagedRevision = session.pendingPatch.revisionAfter;
        }
        StructureBuilder.VbsScriptV2 effectiveScript = hasPending ? session.pendingPatch.nextScript : session.current;

        payload.addProperty("revision", stagedRevision);
        if (hasPending) {
            payload.addProperty("staged", true);
            payload.addProperty("committed_revision", committedRevision);
            if (session.pendingPatch.preview != null) {
                payload.addProperty("pending_summary", session.pendingPatch.preview.summary == null ? "" : session.pendingPatch.preview.summary);
                payload.addProperty("pending_changed_blocks", session.pendingPatch.preview.changedBlocks);
                payload.addProperty("pending_risk", session.pendingPatch.preview.riskLevel == null ? "" : session.pendingPatch.preview.riskLevel);
            }
        }

        JsonObject origin = new JsonObject();
        origin.addProperty("x", session.origin == null ? 0 : session.origin.getX());
        origin.addProperty("y", session.origin == null ? 0 : session.origin.getY());
        origin.addProperty("z", session.origin == null ? 0 : session.origin.getZ());
        payload.add("origin", origin);

        if (session.size != null) {
            JsonObject size = new JsonObject();
            size.addProperty("x", session.size.getX());
            size.addProperty("y", session.size.getY());
            size.addProperty("z", session.size.getZ());
            payload.add("size", size);
        }

        payload.addProperty("part_count", countParts(effectiveScript));
        payload.addProperty("total_blocks", countBlocks(effectiveScript));
        payload.addProperty("summary", buildStructureSummary(effectiveScript));

        if (effectiveScript == null) {
            payload.addProperty("empty", true);
            return payload;
        }

        JsonElement scriptJson = GSON.toJsonTree(effectiveScript);
        String jsonText = GSON.toJson(scriptJson);
        if (jsonText.length() <= MAX_TOOL_JSON_CHARS) {
            payload.add("script", scriptJson);
        } else {
            payload.addProperty("truncated", true);
            payload.addProperty("script_json", jsonText.substring(0, MAX_TOOL_JSON_CHARS));
        }
        return payload;
    }

    private static JsonObject handleReadWorkspaceState(Session session, String toolName, ReadWorkspaceArgs args) {
        // Validate: part and line range are mutually exclusive
        if (args.hasPart() && (args.lineStart > 0 || args.lineEnd > 0)) {
            return buildToolError(toolName, "Cannot combine 'part' filter with line range. Use one or the other.");
        }

        // Validate: both line_start and line_end are required together
        if ((args.lineStart > 0 && args.lineEnd <= 0) || (args.lineEnd > 0 && args.lineStart <= 0)) {
            return buildToolError(toolName, "Both 'line_start' and 'line_end' are required for line range reading.");
        }

        // Validate: line_start >= 1
        if (args.lineStart < 0 || (args.hasLineRange() && args.lineStart < 1)) {
            return buildToolError(toolName, "line_start must be >= 1.");
        }

        // Validate: line_end >= line_start
        if (args.hasLineRange() && args.lineEnd < args.lineStart) {
            return buildToolError(toolName, "line_end must be >= line_start.");
        }

        // Default mode: return full state as before
        if (args.isDefault()) {
            JsonObject payload = buildToolSuccess(toolName);
            payload.add("state", buildWorkspaceStatePayload(session));
            return payload;
        }

        // Part filter mode
        if (args.hasPart()) {
            return buildPartFilterPayload(session, toolName, args.part);
        }

        // Line range mode
        return buildLineRangePayload(session, toolName, args.lineStart, args.lineEnd);
    }

    private static JsonObject buildPartFilterPayload(Session session, String toolName, String partName) {
        boolean hasPending = session.pendingPatch != null && session.pendingPatch.nextScript != null;
        StructureBuilder.VbsScriptV2 effectiveScript = hasPending ? session.pendingPatch.nextScript : session.current;

        if (effectiveScript == null || effectiveScript.structures == null || effectiveScript.structures.isEmpty()) {
            return buildToolError(toolName, "Workspace is empty, no script to read.");
        }

        StructureBuilder.StructurePart matched = null;
        List<String> available = new ArrayList<>();
        for (StructureBuilder.StructurePart p : effectiveScript.structures) {
            if (p == null || p.name == null) continue;
            available.add(p.name);
            if (p.name.equals(partName)) {
                matched = p;
            }
        }

        if (matched == null) {
            return buildToolError(toolName, "Part '" + partName + "' not found. Available parts: " + available + ".");
        }

        // Build a filtered script containing palette + only the matched part
        StructureBuilder.VbsScriptV2 filtered = new StructureBuilder.VbsScriptV2();
        filtered.palette = effectiveScript.palette;
        filtered.structures = new ArrayList<>();
        filtered.structures.add(matched);

        String committedRevision = session.revision == null ? "" : session.revision;
        String stagedRevision = committedRevision;
        if (hasPending && session.pendingPatch.revisionAfter != null && !session.pendingPatch.revisionAfter.isBlank()) {
            stagedRevision = session.pendingPatch.revisionAfter;
        }

        JsonObject payload = buildToolSuccess(toolName);
        JsonObject state = new JsonObject();
        state.addProperty("revision", stagedRevision);
        state.addProperty("filter", "part");
        state.addProperty("part_name", partName);
        state.addProperty("part_count", effectiveScript.structures.size());
        state.addProperty("total_blocks", countBlocks(effectiveScript));
        state.add("script", GSON.toJsonTree(filtered));
        payload.add("state", state);
        return payload;
    }

    private static JsonObject buildLineRangePayload(Session session, String toolName, int lineStart, int lineEnd) {
        boolean hasPending = session.pendingPatch != null && session.pendingPatch.nextScript != null;
        StructureBuilder.VbsScriptV2 effectiveScript = hasPending ? session.pendingPatch.nextScript : session.current;

        if (effectiveScript == null) {
            return buildToolError(toolName, "Workspace is empty, no script to read.");
        }

        String prettyJson = GSON_PRETTY.toJson(effectiveScript);
        String[] lines = prettyJson.split("\n", -1);
        int totalLines = lines.length;

        if (lineStart > totalLines) {
            return buildToolError(toolName, "line_start (" + lineStart + ") exceeds total lines (" + totalLines + "). Use line_start=1 to read from the beginning.");
        }

        // Clamp line_end to totalLines
        int clampedEnd = Math.min(lineEnd, totalLines);

        StringBuilder sb = new StringBuilder();
        for (int i = lineStart - 1; i < clampedEnd; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }

        String committedRevision = session.revision == null ? "" : session.revision;
        String stagedRevision = committedRevision;
        if (hasPending && session.pendingPatch.revisionAfter != null && !session.pendingPatch.revisionAfter.isBlank()) {
            stagedRevision = session.pendingPatch.revisionAfter;
        }

        JsonObject payload = buildToolSuccess(toolName);
        JsonObject state = new JsonObject();
        state.addProperty("revision", stagedRevision);
        state.addProperty("filter", "lines");
        state.addProperty("total_lines", totalLines);
        state.addProperty("line_start", lineStart);
        state.addProperty("line_end", clampedEnd);
        state.addProperty("script_lines", sb.toString());
        payload.add("state", state);
        return payload;
    }

    private static PatchModels.StructurePatch parsePatchArguments(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return null;
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                parsed = JsonParser.parseString(raw);
            }

            PatchModels.StructurePatch patch = GSON.fromJson(parsed, PatchModels.StructurePatch.class);
            normalizePatch(patch);
            return patch;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to parse patch arguments: {}", e.getMessage());
            return null;
        }
    }

    private static SearchBlockArgs parseSearchBlockArgs(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return null;
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                parsed = JsonParser.parseString(raw);
            }
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();
            String query = obj.has("query") && obj.get("query").isJsonPrimitive()
                    ? obj.get("query").getAsString()
                    : "";
            int limit = obj.has("limit") && obj.get("limit").isJsonPrimitive()
                    ? obj.get("limit").getAsInt()
                    : 10;
            if (limit <= 0) {
                limit = 10;
            }
            if (limit > 50) {
                limit = 50;
            }
            return new SearchBlockArgs(query, limit);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to parse search_block_ids arguments: {}", e.getMessage());
            return null;
        }
    }

    private static ReadWorkspaceArgs parseReadWorkspaceArgs(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return new ReadWorkspaceArgs(null, 0, 0);
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return new ReadWorkspaceArgs(null, 0, 0);
                }
                parsed = JsonParser.parseString(raw);
            }
            if (!parsed.isJsonObject()) {
                return new ReadWorkspaceArgs(null, 0, 0);
            }
            JsonObject obj = parsed.getAsJsonObject();
            String part = obj.has("part") && obj.get("part").isJsonPrimitive()
                    ? obj.get("part").getAsString()
                    : null;
            int lineStart = obj.has("line_start") && obj.get("line_start").isJsonPrimitive()
                    ? obj.get("line_start").getAsInt()
                    : 0;
            int lineEnd = obj.has("line_end") && obj.get("line_end").isJsonPrimitive()
                    ? obj.get("line_end").getAsInt()
                    : 0;
            return new ReadWorkspaceArgs(part, lineStart, lineEnd);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to parse read_workspace_state arguments: {}", e.getMessage());
            return new ReadWorkspaceArgs(null, 0, 0);
        }
    }

    private static void normalizePatch(PatchModels.StructurePatch patch) {
        if (patch == null) {
            return;
        }
        if (patch.baseRevision == null) {
            patch.baseRevision = "";
        }
        if (patch.intent == null) {
            patch.intent = "";
        }
        if (patch.messageToUser == null) {
            patch.messageToUser = "";
        }
        if (patch.operations == null) {
            patch.operations = new ArrayList<>();
        }
        for (PatchModels.PatchOperation op : patch.operations) {
            if (op == null) {
                continue;
            }
            if (op.actionsAdd == null) {
                op.actionsAdd = new ArrayList<>();
            }
            if (op.oldActions == null) {
                op.oldActions = new ArrayList<>();
            }
            if (op.newActions == null) {
                op.newActions = new ArrayList<>();
            }
        }
    }

    private static PatchModels.StructurePatch mergePatches(
            PatchModels.StructurePatch existing,
            PatchModels.StructurePatch incoming,
            String committedRevision
    ) {
        PatchModels.StructurePatch merged = new PatchModels.StructurePatch();
        merged.baseRevision = committedRevision == null ? "" : committedRevision;

        String nextIntent = incoming == null ? "" : incoming.intent;
        String prevIntent = existing == null ? "" : existing.intent;
        merged.intent = !isBlank(nextIntent) ? nextIntent : prevIntent;

        String nextMessage = incoming == null ? "" : incoming.messageToUser;
        String prevMessage = existing == null ? "" : existing.messageToUser;
        merged.messageToUser = !isBlank(nextMessage) ? nextMessage : prevMessage;

        merged.operations = new ArrayList<>();
        if (existing != null && existing.operations != null && !existing.operations.isEmpty()) {
            merged.operations.addAll(existing.operations);
        }
        if (incoming != null && incoming.operations != null && !incoming.operations.isEmpty()) {
            merged.operations.addAll(incoming.operations);
        }
        return merged;
    }

    private static boolean requiresConfirm(int changedBlocks) {
        if (ModConfig.CONFIRM_REQUIRED) {
            return true;
        }
        int threshold = ModConfig.RISK_AUTO_APPLY_THRESHOLD;
        if (threshold < 0) {
            return true;
        }
        return changedBlocks > threshold;
    }

    private static PatchModels.Preview buildPatchPreview(
            PatchModels.StructurePatch patch,
            PatchModels.ValidationResult validation,
            StructurePatchEngine.DiffResult diff,
            String currentRevision
    ) {
        PatchModels.Preview preview = new PatchModels.Preview();
        preview.changedBlocks = diff == null ? 0 : diff.changedBlocks;
        preview.riskLevel = validation == null ? "low" : validation.riskLevel;

        String summary;
        if (patch != null && patch.messageToUser != null && !patch.messageToUser.isBlank()) {
            summary = patch.messageToUser.trim();
        } else if (validation != null && validation.summary != null && !validation.summary.isBlank()) {
            summary = validation.summary;
        } else {
            summary = "Patch proposal";
        }
        preview.summary = truncateText(summary, MAX_PREVIEW_SUMMARY_CHARS);

        StringBuilder detail = new StringBuilder();
        detail.append("Revision: ").append(currentRevision == null ? "" : currentRevision).append("\n");
        detail.append("Changed blocks: ").append(preview.changedBlocks).append("\n");
        detail.append("Risk: ").append(preview.riskLevel).append("\n");

        if (patch != null && patch.operations != null && !patch.operations.isEmpty()) {
            detail.append("Operations:\n");
            int limit = Math.min(MAX_PREVIEW_OPERATION_LINES, patch.operations.size());
            for (int i = 0; i < limit; i++) {
                PatchModels.PatchOperation op = patch.operations.get(i);
                detail.append("- ").append(formatPatchOperation(op)).append("\n");
            }
            int more = patch.operations.size() - limit;
            if (more > 0) {
                detail.append("- ...(+").append(more).append(" more operations)\n");
            }
        }

        if (validation != null && !validation.warnings.isEmpty()) {
            detail.append("Warnings:\n");
            int limit = Math.min(MAX_PREVIEW_WARNING_LINES, validation.warnings.size());
            for (int i = 0; i < limit; i++) {
                String warning = validation.warnings.get(i);
                detail.append("- ").append(warning).append("\n");
                preview.warnings.add(warning);
            }
            int more = validation.warnings.size() - limit;
            if (more > 0) {
                detail.append("- ...(+").append(more).append(" more warnings)\n");
            }
        }

        preview.detail = truncateText(detail.toString().trim(), MAX_PREVIEW_DETAIL_CHARS);
        return preview;
    }

    private static String formatPatchOperation(PatchModels.PatchOperation op) {
        if (op == null) {
            return "unknown";
        }
        String name = op.op == null ? "unknown" : op.op;
        String part = op.part == null ? "" : op.part;
        int add = op.actionsAdd == null ? 0 : op.actionsAdd.size();
        int old = op.oldActions == null ? 0 : op.oldActions.size();
        int nw = op.newActions == null ? 0 : op.newActions.size();
        int entries = op.entries == null ? 0 : op.entries.size();
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (!part.isBlank()) sb.append("(").append(part).append(")");
        if (add > 0) sb.append(" add=").append(add);
        if (old > 0) sb.append(" old=").append(old);
        if (nw > 0) sb.append(" new=").append(nw);
        if (entries > 0) sb.append(" entries=").append(entries);
        if (op.offset != null) sb.append(" offset=").append(op.offset);
        if (op.targetPart != null && !op.targetPart.isBlank()) sb.append(" target=").append(op.targetPart);
        return sb.toString();
    }

    private static int countParts(StructureBuilder.VbsScriptV2 script) {
        return script == null || script.structures == null ? 0 : script.structures.size();
    }

    private static String buildStructureSummary(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        boolean truncated = false;
        Map<String, String> palette = script.palette == null ? Map.of() : script.palette;

        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.name == null) {
                continue;
            }
            if (lines.size() >= MAX_SUMMARY_LINES) {
                truncated = true;
                break;
            }
            lines.add("[" + part.name + "] priority=" + part.priority);
            if (part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (lines.size() >= MAX_SUMMARY_LINES) {
                    truncated = true;
                    break;
                }
                lines.add("- " + formatActionSummary(action, palette));
            }
            if (truncated) {
                break;
            }
        }

        if (truncated) {
            if (lines.isEmpty()) {
                lines.add("...");
            } else {
                lines.set(lines.size() - 1, "...");
            }
        }

        String text = String.join("\n", lines);
        if (text.length() > MAX_SUMMARY_CHARS) {
            int end = Math.max(0, MAX_SUMMARY_CHARS - 3);
            text = text.substring(0, end) + "...";
        }
        return text;
    }

    private static String formatActionSummary(StructureBuilder.VbsAction action, Map<String, String> palette) {
        if (action == null || action.type == null) {
            return "unknown";
        }
        String type = action.type.trim().toLowerCase();
        String block = formatBlock(action.block, palette);
        StringBuilder sb = new StringBuilder();
        sb.append(type);
        if (!block.isBlank()) {
            sb.append(" block=").append(block);
        }
        switch (type) {
            case "box", "plane", "line" -> {
                String range = formatRange(action.from, action.to);
                if (!range.isBlank()) {
                    sb.append(" ").append(range);
                }
            }
            case "points" -> {
                String at = formatAt(action.at);
                if (!at.isBlank()) {
                    sb.append(" ").append(at);
                }
            }
            default -> {
            }
        }
        if (action.mode != null && !action.mode.isBlank()) {
            sb.append(" mode=").append(action.mode.trim().toLowerCase());
        }
        if (action.axis != null && !action.axis.isBlank()) {
            sb.append(" axis=").append(action.axis.trim().toLowerCase());
        }
        if (action.facing != null && !action.facing.isBlank()) {
            sb.append(" facing=").append(action.facing.trim().toLowerCase());
        }
        return sb.toString();
    }

    private static String formatBlock(String block, Map<String, String> palette) {
        if (block == null || block.isBlank()) {
            return "";
        }
        if (palette == null || palette.isEmpty()) {
            return block;
        }
        String resolved = palette.get(block);
        if (resolved == null || resolved.isBlank() || resolved.equals(block)) {
            return block;
        }
        return block + " (" + resolved + ")";
    }

    private static String formatRange(List<Integer> from, List<Integer> to) {
        if (from == null || to == null || from.size() < 3 || to.size() < 3) {
            return "";
        }
        return "from " + formatVec(from) + " to " + formatVec(to);
    }

    private static String formatAt(List<List<Integer>> at) {
        if (at == null || at.isEmpty()) {
            return "";
        }
        int count = at.size();
        StringBuilder sb = new StringBuilder();
        sb.append("at ").append(count).append(" [");
        int sample = Math.min(3, count);
        for (int i = 0; i < sample; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(formatVec(at.get(i)));
        }
        if (count > sample) {
            sb.append("; ...");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatVec(List<Integer> vec) {
        if (vec == null || vec.size() < 3) {
            return "?";
        }
        return vec.get(0) + "," + vec.get(1) + "," + vec.get(2);
    }

    private static JsonObject buildToolSuccess(String tool) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", tool == null ? "" : tool);
        return payload;
    }

    private static JsonObject buildToolError(String tool, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", tool == null ? "" : tool);
        payload.addProperty("error", error == null ? "" : error);
        return payload;
    }

    private static JsonObject buildToolMessage(LLMService.ToolCall call, JsonObject payload) {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        if (call != null && call.id() != null && !call.id().isBlank()) {
            toolMsg.addProperty("tool_call_id", call.id());
        }
        toolMsg.addProperty("content", payload == null ? "" : GSON.toJson(payload));
        return toolMsg;
    }

    private static JsonObject extractToolBridgePayload(ToolCallProcessingResult toolResult, String toolName) {
        if (toolResult == null || toolResult.toolMessages == null || toolResult.toolMessages.isEmpty()) {
            return buildToolError(toolName, "No tool response");
        }
        JsonObject toolMessage = toolResult.toolMessages.get(0);
        if (toolMessage == null || !toolMessage.has("content")) {
            return buildToolError(toolName, "Tool response missing content");
        }
        try {
            String content = toolMessage.get("content").getAsString();
            if (content == null || content.isBlank()) {
                return buildToolError(toolName, "Tool response empty");
            }
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            JsonObject wrapped = buildToolError(toolName, "Tool response is not object");
            wrapped.add("raw", parsed);
            return wrapped;
        } catch (Exception e) {
            return buildToolError(toolName, "Tool response parse failed: " + e.getMessage());
        }
    }

    private static void sendToolBridgeResponse(ServerPlayer player, String requestId, boolean ok, JsonObject payload, String error) {
        if (player == null) {
            return;
        }
        String json = payload == null ? "{}" : GSON.toJson(payload);
        ServerNetworkHandler.sendToClient(player, new S2CToolBridgePayload(
                requestId == null ? "" : requestId,
                ok,
                json,
                error == null ? "" : error
        ));
    }

    private static String formatAgentError(Throwable ex, long timeoutSeconds) {
        Throwable cause = ex;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "Timed out after " + timeoutSeconds + "s";
        }
        String message = cause == null ? null : cause.getMessage();
        if (message == null || message.isBlank()) {
            message = ex == null ? "" : ex.getMessage();
        }
        return message == null || message.isBlank() ? "Unknown error" : message;
    }

    private static String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    private static int safeLength(String text) {
        if (text == null) {
            return 0;
        }
        return text.trim().length();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String posString(BlockPos pos) {
        if (pos == null) {
            return "-";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String sizeString(Vec3i size) {
        if (size == null) {
            return "-";
        }
        return size.getX() + "x" + size.getY() + "x" + size.getZ();
    }

    private static int countBlocks(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null) {
            return 0;
        }
        int total = 0;
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (action == null || action.type == null) {
                    continue;
                }
                switch (action.type.toLowerCase()) {
                    case "box" -> total += countBox(action);
                    case "plane" -> total += countPlane(action);
                    case "line" -> total += countLine(action);
                    case "points" -> total += action.at == null ? 0 : action.at.size();
                    default -> {
                    }
                }
            }
        }
        return total;
    }

    private static int countBox(StructureBuilder.VbsAction action) {
        if (action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        int dx = Math.abs(action.from.get(0) - action.to.get(0)) + 1;
        int dy = Math.abs(action.from.get(1) - action.to.get(1)) + 1;
        int dz = Math.abs(action.from.get(2) - action.to.get(2)) + 1;
        String mode = normalizeActionValue(action.mode);
        if (mode.isBlank() || "solid".equals(mode)) {
            return dx * dy * dz;
        }
        if ("shell".equals(mode)) {
            if (dx <= 2 || dy <= 2 || dz <= 2) {
                return dx * dy * dz;
            }
            int surface = 2 * (dx * dy + dx * dz + dy * dz) - 4 * (dx + dy + dz) + 8;
            return Math.max(surface, 0);
        }
        if ("walls".equals(mode)) {
            int perimeterXZ;
            if (dx == 1 || dz == 1) {
                perimeterXZ = dx * dz;
            } else {
                perimeterXZ = 2 * dx + 2 * dz - 4;
            }
            return perimeterXZ * dy;
        }
        return 0;
    }

    private static int countPlane(StructureBuilder.VbsAction action) {
        if (action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        String axis = normalizeActionValue(action.axis);
        int a;
        int b;
        if ("x".equals(axis)) {
            a = Math.abs(action.from.get(1) - action.to.get(1)) + 1;
            b = Math.abs(action.from.get(2) - action.to.get(2)) + 1;
        } else if ("y".equals(axis)) {
            a = Math.abs(action.from.get(0) - action.to.get(0)) + 1;
            b = Math.abs(action.from.get(2) - action.to.get(2)) + 1;
        } else if ("z".equals(axis)) {
            a = Math.abs(action.from.get(0) - action.to.get(0)) + 1;
            b = Math.abs(action.from.get(1) - action.to.get(1)) + 1;
        } else {
            return 0;
        }
        String mode = normalizeActionValue(action.mode);
        if (mode.isBlank() || "solid".equals(mode)) {
            return a * b;
        }
        if ("outline".equals(mode)) {
            if (a == 1 || b == 1) {
                return a * b;
            }
            return 2 * a + 2 * b - 4;
        }
        return 0;
    }

    private static int countLine(StructureBuilder.VbsAction action) {
        if (action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        int dx = Math.abs(action.from.get(0) - action.to.get(0));
        int dy = Math.abs(action.from.get(1) - action.to.get(1));
        int dz = Math.abs(action.from.get(2) - action.to.get(2));
        return Math.max(dx, Math.max(dy, dz)) + 1;
    }

    private static String normalizeActionValue(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private static void sendSessionSync(ServerPlayer player, Session session) {
        if (player == null) {
            return;
        }

        boolean active = session != null;
        String sessionId = active ? session.id : "";
        int turns = active ? session.turnCount : 0;
        int partCount = active ? countParts(session.current) : 0;
        int totalBlocks = active ? countBlocks(session.current) : 0;
        String summary = active ? partsSummary(session.current) : "";
        String structureSummary = active ? buildStructureSummary(session.current) : "";

        String runtimeState = active ? toRuntimeStateName(session.runtimeState) : "";
        String revision = active ? (session.revision == null ? "" : session.revision) : "";
        boolean hasPending = active && session.pendingPatch != null;
        String pendingSummary = hasPending && session.pendingPatch.preview != null ? session.pendingPatch.preview.summary : "";
        String pendingRisk = hasPending && session.pendingPatch.preview != null ? session.pendingPatch.preview.riskLevel : "";
        int pendingChanged = hasPending && session.pendingPatch.preview != null ? session.pendingPatch.preview.changedBlocks : 0;

        int ox = active && session.origin != null ? session.origin.getX() : 0;
        int oy = active && session.origin != null ? session.origin.getY() : 0;
        int oz = active && session.origin != null ? session.origin.getZ() : 0;
        boolean hasSz = active && session.size != null;
        int sx = hasSz ? session.size.getX() : 0;
        int sy = hasSz ? session.size.getY() : 0;
        int sz = hasSz ? session.size.getZ() : 0;

        ServerNetworkHandler.sendToClient(player, new S2CSessionSyncPayload(
                active,
                sessionId,
                turns,
                partCount,
                totalBlocks,
                summary,
                structureSummary,
                runtimeState,
                revision,
                hasPending,
                pendingSummary,
                pendingRisk,
                pendingChanged,
                ox, oy, oz,
                hasSz,
                sx, sy, sz
        ));
    }

    private static String toRuntimeStateName(RuntimeState state) {
        if (state == null) {
            return "";
        }
        return state.name().toLowerCase();
    }

    private static String partsSummary(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.name == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.name);
        }
        return sb.toString();
    }

    private static void sendPatchPreview(ServerPlayer player, PatchModels.Preview preview) {
        if (player == null) {
            return;
        }
        if (preview == null) {
            ServerNetworkHandler.sendToClient(player, new S2CPatchPreviewPayload(false, "", "", 0, ""));
            return;
        }
        ServerNetworkHandler.sendToClient(player, new S2CPatchPreviewPayload(
                true,
                preview.summary == null ? "" : preview.summary,
                preview.detail == null ? "" : preview.detail,
                preview.changedBlocks,
                preview.riskLevel == null ? "" : preview.riskLevel
        ));
    }

    private static void sendChatResponse(ServerPlayer player, String text, boolean hasStructure, String status) {
        ServerNetworkHandler.sendToClient(player, new S2CChatResponsePayload(
                text == null ? "" : text,
                hasStructure,
                status == null ? "" : status
        ));
    }

    private static String nextRevision() {
        return "rev-" + UUID.randomUUID();
    }

    static String buildAreaConstraint(Vec3i size) {
        int sizeX = size.getX();
        int sizeY = size.getY();
        int sizeZ = size.getZ();
        return "## Build Area\n" +
                "The structure must fit within a " + sizeX + "x" + sizeY + "x" + sizeZ + " region.\n" +
                "Max coordinates: (" + (sizeX - 1) + ", " + (sizeY - 1) + ", " + (sizeZ - 1) + ").";
    }

    private static final class ToolCallProcessingResult {
        private boolean previewUpdated = false;
        private boolean autoApplyRequested = false;
        private boolean autoApplied = false;
        private final List<JsonObject> toolMessages = new ArrayList<>();
    }

    private enum RuntimeState {
        IDLE,
        PLANNING,
        PATCH_GENERATED,
        VALIDATING,
        AWAITING_CONFIRM,
        APPLYING,
        COMMITTED,
        FAILED,
        CANCELLED
    }

    private static final class PendingPatch {
        private PatchModels.StructurePatch patch;
        private StructureBuilder.VbsScriptV2 baseScript;
        private StructureBuilder.VbsScriptV2 nextScript;
        private StructurePatchEngine.DiffResult diff;
        private PatchModels.ValidationResult validation;
        private PatchModels.Preview preview;
        private String revisionBefore;
        private String revisionAfter;
    }

    private static final class SearchBlockArgs {
        private final String query;
        private final int limit;

        private SearchBlockArgs(String query, int limit) {
            this.query = query;
            this.limit = limit;
        }
    }

    private static final class ReadWorkspaceArgs {
        final String part;
        final int lineStart;
        final int lineEnd;

        ReadWorkspaceArgs(String part, int lineStart, int lineEnd) {
            this.part = part;
            this.lineStart = lineStart;
            this.lineEnd = lineEnd;
        }

        boolean hasLineRange() { return lineStart > 0 && lineEnd > 0; }
        boolean hasPart() { return part != null && !part.isBlank(); }
        boolean isDefault() { return !hasLineRange() && !hasPart(); }
    }

    private static final class CommitEntry {
        private String id;
        private String revisionBefore;
        private String revisionAfter;
        private StructureBuilder.VbsScriptV2 beforeScript;
        private StructureBuilder.VbsScriptV2 afterScript;
        private List<PatchModels.BlockOp> forwardOps = new ArrayList<>();
        private List<PatchModels.BlockOp> inverseOps = new ArrayList<>();
        private String summary;
        private PatchModels.StructurePatch patch;
    }

    public static class Session {
        String id;
        BlockPos origin;
        Vec3i size;
        List<JsonObject> history;
        StructureBuilder.VbsScriptV2 current;
        int turnCount = 0;
        boolean inFlight = false;

        String revision;
        RuntimeState runtimeState = RuntimeState.IDLE;
        PendingPatch pendingPatch;
        Deque<CommitEntry> undoStack = new ArrayDeque<>();
        Deque<CommitEntry> redoStack = new ArrayDeque<>();
    }
}
