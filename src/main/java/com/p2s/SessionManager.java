package com.p2s;

import com.google.gson.Gson;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SessionManager {
    private static final Gson GSON = new Gson();
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
    private static final int MAX_CHECKPOINTS = 24;
    private static final int MAX_DOCS_PER_SESSION = 64;
    private static final String DOC_ROOT = "workspace";
    private static final String DEFAULT_DOC_ID = "doc-main";
    private static final String DEFAULT_DOC_NAME = DOC_ROOT + "/main.json";

    private static final String SESSION_TOOL_CONTRACT = """
            ## IDE Session Contract
            - Workspace contains multiple files (documents). Use read_workspace_state first when you need file size and existing blocks.
              - Optional doc_id selects file; if omitted, reads current active file.
              - Returns only size + current script (or truncated script_json).
              - Default reads committed script; committed=false can include staged script for UI diff.
            - Propose all edits with propose_patch; do not directly build blocks.
              - Optional doc_id selects file; if omitted, patch current active file.
            - The user reviews a preview and confirms apply/discard for the active file.
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
        persistActiveDocument(session);
        if (session != null && session.activeDocId != null && !session.activeDocId.isBlank()) {
            loadActiveDocument(session, session.activeDocId);
        }
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
                toolResult.autoApplied = commitPendingPatch(player, session, true, toolResult.autoApplyDocId);
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
        persistActiveDocument(session);

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
                    String targetDocId = parseOptionalDocId(call.arguments());
                    if (!targetDocId.isBlank() && !targetDocId.equals(session.activeDocId)) {
                        if (!switchActiveDocument(session, targetDocId)) {
                            result.toolMessages.add(buildToolMessage(call, buildToolError(toolName, "Unknown doc_id: " + targetDocId)));
                            session.runtimeState = RuntimeState.FAILED;
                            break;
                        }
                    }
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
                    payload.addProperty("doc_id", session.activeDocId == null ? "" : session.activeDocId);
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
                    result.autoApplyDocId = session.activeDocId == null ? "" : session.activeDocId;
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
        persistActiveDocument(session);
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
            case "doc_create" -> createDocument(player, payload);
            case "doc_switch" -> switchDocument(player, payload);
            case "doc_rename" -> renameDocument(player, payload);
            case "create_checkpoint" -> createCheckpoint(player, payload);
            case "rollback_checkpoint" -> rollbackCheckpoint(player, payload);
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
                toolResult.autoApplied = commitPendingPatch(player, session, true, toolResult.autoApplyDocId);
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

        JsonObject startJson = null;
        BlockPos restoredOrigin = null;
        Vec3i restoredSize = null;
        if (payload != null && !payload.isBlank()) {
            try {
                startJson = JsonParser.parseString(payload).getAsJsonObject();
                JsonObject json = startJson;
                if (json.has("originX") && json.has("originY") && json.has("originZ")
                        && json.get("originX").isJsonPrimitive()
                        && json.get("originY").isJsonPrimitive()
                        && json.get("originZ").isJsonPrimitive()) {
                    restoredOrigin = new BlockPos(
                            json.get("originX").getAsInt(),
                            json.get("originY").getAsInt(),
                            json.get("originZ").getAsInt()
                    );
                }
                if (json.has("hasSize") && json.get("hasSize").isJsonPrimitive() && json.get("hasSize").getAsBoolean()
                        && json.has("sizeX") && json.get("sizeX").isJsonPrimitive()
                        && json.has("sizeY") && json.get("sizeY").isJsonPrimitive()
                        && json.has("sizeZ") && json.get("sizeZ").isJsonPrimitive()) {
                    restoredSize = new Vec3i(
                            json.get("sizeX").getAsInt(),
                            json.get("sizeY").getAsInt(),
                            json.get("sizeZ").getAsInt()
                    );
                }
            } catch (Exception e) {
                P2SMod.LOGGER.debug("Could not parse start payload as origin/size: {}", e.getMessage());
                startJson = null;
            }
        }

        Session session;
        if (restoredOrigin != null) {
            session = createSession(player, restoredOrigin, restoredSize);
        } else {
            session = createSession(player);
        }

        if (startJson != null) {
            try {
                if (startJson.has("workspaceDocs") && startJson.get("workspaceDocs").isJsonArray()) {
                    Map<String, DocumentState> restoredDocs = new LinkedHashMap<>();
                    for (JsonElement docElem : startJson.getAsJsonArray("workspaceDocs")) {
                        if (docElem == null || !docElem.isJsonObject()) {
                            continue;
                        }
                        JsonObject docJson = docElem.getAsJsonObject();
                        String docId = getString(docJson, "id");
                        String docName = getString(docJson, "name");
                        if (docId.isBlank()) {
                            continue;
                        }

                        BlockPos docOrigin = restoredOrigin;
                        if (docJson.has("originX") && docJson.has("originY") && docJson.has("originZ")
                                && docJson.get("originX").isJsonPrimitive()
                                && docJson.get("originY").isJsonPrimitive()
                                && docJson.get("originZ").isJsonPrimitive()) {
                            docOrigin = new BlockPos(
                                    docJson.get("originX").getAsInt(),
                                    docJson.get("originY").getAsInt(),
                                    docJson.get("originZ").getAsInt()
                            );
                        }

                        Vec3i docSize = restoredSize;
                        if (docJson.has("hasSize") && docJson.get("hasSize").isJsonPrimitive() && docJson.get("hasSize").getAsBoolean()
                                && docJson.has("sizeX") && docJson.get("sizeX").isJsonPrimitive()
                                && docJson.has("sizeY") && docJson.get("sizeY").isJsonPrimitive()
                                && docJson.has("sizeZ") && docJson.get("sizeZ").isJsonPrimitive()) {
                            docSize = new Vec3i(
                                    docJson.get("sizeX").getAsInt(),
                                    docJson.get("sizeY").getAsInt(),
                                    docJson.get("sizeZ").getAsInt()
                            );
                        }

                        StructureBuilder.VbsScriptV2 restoredScript = null;
                        String docScriptJson = getString(docJson, "currentScriptJson");
                        if (!docScriptJson.isBlank()) {
                            try {
                                restoredScript = GSON.fromJson(docScriptJson, StructureBuilder.VbsScriptV2.class);
                            } catch (Exception ignored) {
                            }
                        }

                        String docRevision = getString(docJson, "revision");
                        if (docRevision.isBlank()) {
                            docRevision = restoredScript == null ? "rev-0" : "rev-restored";
                        }

                        String normalizedId = normalizeDocId(docId, restoredDocs);
                        String normalizedName = normalizeDocName(docName);
                        if (normalizedName.isBlank()) {
                            normalizedName = buildDefaultDocName(restoredDocs.size() + 1);
                        }
                        DocumentState doc = newDocumentState(normalizedId, normalizedName, docOrigin, docSize, restoredScript, docRevision);
                        restoredDocs.put(doc.id, doc);
                    }

                    if (!restoredDocs.isEmpty()) {
                        session.docs.clear();
                        session.docs.putAll(restoredDocs);
                        session.nextDocIndex = Math.max(1, restoredDocs.size() + 1);

                        String activeDocId = getString(startJson, "activeDocId");
                        String selectedDocId = activeDocId;
                        if (selectedDocId == null || selectedDocId.isBlank() || !session.docs.containsKey(selectedDocId)) {
                            selectedDocId = restoredDocs.keySet().iterator().next();
                        }
                        session.activeDocId = selectedDocId;
                        loadActiveDocument(session, selectedDocId);
                    }
                } else if (startJson.has("currentScriptJson")) {
                    String scriptJson = getString(startJson, "currentScriptJson");
                    if (!scriptJson.isBlank()) {
                        StructureBuilder.VbsScriptV2 restored = GSON.fromJson(scriptJson, StructureBuilder.VbsScriptV2.class);
                        if (restored != null) {
                            session.current = restored;
                            session.revision = "rev-restored";
                        }
                    }
                }
            } catch (Exception e) {
                P2SMod.LOGGER.debug("Could not restore script from start payload: {}", e.getMessage());
            }
        }

        persistActiveDocument(session);
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
        persistActiveDocument(removed);
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
        persistActiveDocument(session);
        loadActiveDocument(session, session.activeDocId);
        if (session.undoStack.isEmpty()) {
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
        addCheckpoint(session, "undo:" + (commit.summary == null ? "patch" : commit.summary));

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
        persistActiveDocument(session);
        loadActiveDocument(session, session.activeDocId);
        if (session.redoStack.isEmpty()) {
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
        addCheckpoint(session, "redo:" + (commit.summary == null ? "patch" : commit.summary));

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
        persistActiveDocument(session);
        loadActiveDocument(session, session.activeDocId);
        if (session.current == null) {
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

    private static void createDocument(ServerPlayer player, String payload) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            player.displayClientMessage(Component.literal("No active session"), false);
            return;
        }
        persistActiveDocument(session);
        loadActiveDocument(session, session.activeDocId);
        if (session.inFlight) {
            player.displayClientMessage(Component.literal("Busy with current request"), false);
            return;
        }

        String requestedName = "";
        String requestedId = "";
        boolean switchToNew = true;
        if (payload != null && !payload.isBlank()) {
            try {
                JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
                requestedName = getString(obj, "name");
                requestedId = getString(obj, "id");
                if (obj.has("switchToNew") && obj.get("switchToNew").isJsonPrimitive()) {
                    switchToNew = obj.get("switchToNew").getAsBoolean();
                }
            } catch (Exception e) {
                requestedName = payload.trim();
            }
        }

        persistActiveDocument(session);
        DocumentState activeDoc = getActiveDocument(session);
        if (session.docs.size() >= MAX_DOCS_PER_SESSION) {
            player.displayClientMessage(Component.literal("Maximum documents reached: " + MAX_DOCS_PER_SESSION), false);
            return;
        }

        String docId = normalizeDocId(requestedId, session.docs);
        if (docId.isBlank()) {
            docId = normalizeDocId("doc-" + (session.nextDocIndex++), session.docs);
        }
        String normalizedName = normalizeDocName(requestedName);
        if (normalizedName.isBlank()) {
            normalizedName = buildDefaultDocName(session.nextDocIndex++);
        }
        normalizedName = ensureUniqueDocName(session, normalizedName, null);

        BlockPos origin = activeDoc == null ? session.origin : activeDoc.origin;
        Vec3i size = activeDoc == null ? session.size : activeDoc.size;
        DocumentState doc = newDocumentState(docId, normalizedName, origin, size, null, "rev-0");
        session.docs.put(doc.id, doc);

        if (switchToNew) {
            switchActiveDocument(session, doc.id);
        }
        sendPatchPreview(player, session.pendingPatch == null ? null : session.pendingPatch.preview);
        sendSessionSync(player, session);
        player.displayClientMessage(Component.literal("Document created: " + doc.name), false);
    }

    private static void switchDocument(ServerPlayer player, String payload) {
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

        String docId = "";
        if (payload != null && !payload.isBlank()) {
            try {
                JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
                docId = getString(obj, "id");
            } catch (Exception ignored) {
                docId = payload.trim();
            }
        }
        if (docId.isBlank()) {
            player.displayClientMessage(Component.literal("Missing doc id"), false);
            return;
        }

        persistActiveDocument(session);
        DocumentState before = getActiveDocument(session);
        boolean hadPending = before != null && before.pendingPatch != null;
        if (!switchActiveDocument(session, docId)) {
            player.displayClientMessage(Component.literal("Document not found: " + docId), false);
            return;
        }

        sendPatchPreview(player, session.pendingPatch == null ? null : session.pendingPatch.preview);
        sendSessionSync(player, session);
        if (hadPending) {
            player.displayClientMessage(Component.literal("Switched document. Previous pending patch was stashed."), false);
        } else {
            player.displayClientMessage(Component.literal("Switched document: " + session.activeDocId), false);
        }
    }

    private static void renameDocument(ServerPlayer player, String payload) {
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
        if (payload == null || payload.isBlank()) {
            player.displayClientMessage(Component.literal("Missing rename payload"), false);
            return;
        }

        String docId = "";
        String name = "";
        try {
            JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
            docId = getString(obj, "id");
            name = getString(obj, "name");
        } catch (Exception ignored) {
            player.displayClientMessage(Component.literal("Invalid rename payload"), false);
            return;
        }
        if (docId.isBlank() || name.isBlank()) {
            player.displayClientMessage(Component.literal("Rename requires id and name"), false);
            return;
        }

        persistActiveDocument(session);
        DocumentState doc = session.docs.get(docId.trim());
        if (doc == null) {
            player.displayClientMessage(Component.literal("Document not found: " + docId), false);
            return;
        }

        String normalized = normalizeDocName(name);
        if (normalized.isBlank()) {
            player.displayClientMessage(Component.literal("Invalid document name"), false);
            return;
        }
        normalized = ensureUniqueDocName(session, normalized, doc.id);
        doc.name = normalized;

        if (doc.id.equals(session.activeDocId)) {
            loadActiveDocument(session, doc.id);
        }
        sendSessionSync(player, session);
        player.displayClientMessage(Component.literal("Renamed document: " + normalized), false);
    }

    private static DocumentState getActiveDocument(Session session) {
        if (session == null) {
            return null;
        }
        if (session.docs == null || session.docs.isEmpty()) {
            session.docs = new LinkedHashMap<>();
            DocumentState doc = newDocumentState(DEFAULT_DOC_ID, DEFAULT_DOC_NAME, session.origin, session.size, session.current, session.revision);
            session.docs.put(doc.id, doc);
            session.activeDocId = doc.id;
        }
        if (session.activeDocId == null || session.activeDocId.isBlank() || !session.docs.containsKey(session.activeDocId)) {
            session.activeDocId = session.docs.keySet().iterator().next();
        }
        return session.docs.get(session.activeDocId);
    }

    private static void persistActiveDocument(Session session) {
        if (session == null) {
            return;
        }
        DocumentState active = getActiveDocument(session);
        if (active == null) {
            return;
        }
        active.origin = session.origin;
        active.size = session.size;
        active.current = copyScript(session.current);
        active.revision = session.revision == null || session.revision.isBlank() ? "rev-0" : session.revision;
        active.pendingPatch = session.pendingPatch;
        active.undoStack = session.undoStack == null ? new ArrayDeque<>() : session.undoStack;
        active.redoStack = session.redoStack == null ? new ArrayDeque<>() : session.redoStack;
        active.checkpoints = session.checkpoints == null ? new ArrayList<>() : session.checkpoints;
    }

    private static void loadActiveDocument(Session session, String docId) {
        if (session == null) {
            return;
        }
        if (session.docs == null || session.docs.isEmpty()) {
            DocumentState doc = newDocumentState(DEFAULT_DOC_ID, DEFAULT_DOC_NAME, session.origin, session.size, session.current, session.revision);
            session.docs = new LinkedHashMap<>();
            session.docs.put(doc.id, doc);
            session.activeDocId = doc.id;
        }

        DocumentState doc = session.docs.get(docId);
        if (doc == null) {
            doc = getActiveDocument(session);
            if (doc == null) {
                return;
            }
        } else {
            session.activeDocId = doc.id;
        }

        session.origin = doc.origin;
        session.size = doc.size;
        session.current = copyScript(doc.current);
        session.revision = doc.revision == null || doc.revision.isBlank() ? "rev-0" : doc.revision;
        session.pendingPatch = doc.pendingPatch;
        session.undoStack = doc.undoStack == null ? new ArrayDeque<>() : doc.undoStack;
        session.redoStack = doc.redoStack == null ? new ArrayDeque<>() : doc.redoStack;
        session.checkpoints = doc.checkpoints == null ? new ArrayList<>() : doc.checkpoints;
    }

    private static boolean switchActiveDocument(Session session, String docId) {
        if (session == null || docId == null || docId.isBlank()) {
            return false;
        }
        if (session.docs == null || session.docs.isEmpty()) {
            return false;
        }
        String normalizedId = docId.trim();
        if (!session.docs.containsKey(normalizedId)) {
            return false;
        }
        persistActiveDocument(session);
        loadActiveDocument(session, normalizedId);
        if (!session.inFlight) {
            session.runtimeState = session.pendingPatch == null ? RuntimeState.IDLE : RuntimeState.AWAITING_CONFIRM;
        }
        return true;
    }

    private static DocumentState newDocumentState(
            String id,
            String name,
            BlockPos origin,
            Vec3i size,
            StructureBuilder.VbsScriptV2 script,
            String revision
    ) {
        DocumentState doc = new DocumentState();
        doc.id = id == null || id.isBlank() ? DEFAULT_DOC_ID : id.trim();
        doc.name = normalizeDocName(name);
        if (doc.name.isBlank()) {
            doc.name = DEFAULT_DOC_NAME;
        }
        doc.origin = origin;
        doc.size = size;
        doc.current = copyScript(script);
        doc.revision = revision == null || revision.isBlank() ? "rev-0" : revision.trim();
        doc.pendingPatch = null;
        doc.undoStack = new ArrayDeque<>();
        doc.redoStack = new ArrayDeque<>();
        doc.checkpoints = new ArrayList<>();
        return doc;
    }

    private static String normalizeDocName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return "";
        }
        if (!normalized.startsWith(DOC_ROOT + "/")) {
            normalized = DOC_ROOT + "/" + normalized;
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (!normalized.endsWith(".json")) {
            normalized = normalized + ".json";
        }
        return normalized;
    }

    private static String buildDefaultDocName(int index) {
        if (index <= 1) {
            return DEFAULT_DOC_NAME;
        }
        return DOC_ROOT + "/file-" + index + ".json";
    }

    private static String normalizeDocId(String id, Map<String, DocumentState> existing) {
        String candidate = id == null ? "" : id.trim();
        if (!candidate.isBlank()) {
            candidate = candidate.replaceAll("[^a-zA-Z0-9._-]", "-");
        }
        if (candidate.isBlank()) {
            candidate = "doc-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (existing == null) {
            return candidate;
        }
        if (!existing.containsKey(candidate)) {
            return candidate;
        }
        String base = candidate;
        int i = 2;
        while (existing.containsKey(base + "-" + i)) {
            i++;
        }
        return base + "-" + i;
    }

    private static String ensureUniqueDocName(Session session, String baseName, String excludeId) {
        if (session == null) {
            return baseName;
        }
        String candidate = baseName;
        int dot = baseName.lastIndexOf('.');
        String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
        String ext = dot > 0 ? baseName.substring(dot) : "";
        int i = 2;
        while (hasDocName(session, candidate, excludeId)) {
            candidate = stem + "-" + i + ext;
            i++;
        }
        return candidate;
    }

    private static boolean hasDocName(Session session, String name, String excludeId) {
        if (session == null || session.docs == null || name == null) {
            return false;
        }
        for (DocumentState doc : session.docs.values()) {
            if (doc == null) {
                continue;
            }
            if (excludeId != null && excludeId.equals(doc.id)) {
                continue;
            }
            if (name.equals(doc.name)) {
                return true;
            }
        }
        return false;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
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

        if (!commitPendingPatch(player, session, false, session.activeDocId)) {
            player.displayClientMessage(Component.literal("Failed to apply patch"), false);
        }
    }

    private static boolean commitPendingPatch(ServerPlayer player, Session session, boolean autoApply, String targetDocId) {
        if (player == null || session == null) {
            return false;
        }
        if (targetDocId != null && !targetDocId.isBlank() && !targetDocId.equals(session.activeDocId)) {
            if (!switchActiveDocument(session, targetDocId)) {
                return false;
            }
        }
        if (session.pendingPatch == null) {
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
        addCheckpoint(session, autoApply ? "auto-apply" : "apply");

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
        persistActiveDocument(session);
        loadActiveDocument(session, session.activeDocId);
        if (session.pendingPatch == null) {
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
        session.history = new ArrayList<>();
        session.history.add(systemMsg);
        session.docs = new LinkedHashMap<>();
        session.nextDocIndex = 2;
        DocumentState doc = newDocumentState(DEFAULT_DOC_ID, DEFAULT_DOC_NAME, origin, size, null, "rev-0");
        session.docs.put(doc.id, doc);
        session.activeDocId = doc.id;
        loadActiveDocument(session, doc.id);
        session.runtimeState = RuntimeState.IDLE;
        addCheckpoint(session, "session-start");
        persistActiveDocument(session);
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
        addCheckpoint(session, "legacy-commit");

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

    private static JsonObject buildWorkspaceStatePayload(DocumentState doc, boolean committedOnly) {
        JsonObject payload = new JsonObject();
        if (doc == null) {
            payload.addProperty("empty", true);
            return payload;
        }

        payload.addProperty("doc_id", doc.id == null ? "" : doc.id);
        payload.addProperty("doc_name", doc.name == null ? "" : doc.name);

        boolean hasPending = doc.pendingPatch != null && doc.pendingPatch.nextScript != null;
        StructureBuilder.VbsScriptV2 effectiveScript = committedOnly ? doc.current : (hasPending ? doc.pendingPatch.nextScript : doc.current);

        if (doc.size != null) {
            JsonObject size = new JsonObject();
            size.addProperty("x", doc.size.getX());
            size.addProperty("y", doc.size.getY());
            size.addProperty("z", doc.size.getZ());
            payload.add("size", size);
        }
        if (doc.origin != null) {
            JsonObject origin = new JsonObject();
            origin.addProperty("x", doc.origin.getX());
            origin.addProperty("y", doc.origin.getY());
            origin.addProperty("z", doc.origin.getZ());
            payload.add("origin", origin);
        }

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
        JsonObject payload = buildToolSuccess(toolName);
        persistActiveDocument(session);
        DocumentState target = null;
        if (args != null && args.docId != null && !args.docId.isBlank()) {
            target = session.docs == null ? null : session.docs.get(args.docId.trim());
            if (target == null) {
                return buildToolError(toolName, "Unknown doc_id: " + args.docId);
            }
        }
        if (target == null) {
            target = getActiveDocument(session);
        }
        if (target == null) {
            return buildToolError(toolName, "No document available");
        }
        payload.addProperty("doc_id", target.id == null ? "" : target.id);
        payload.addProperty("doc_name", target.name == null ? "" : target.name);
        payload.add("state", buildWorkspaceStatePayload(target, args == null || args.committed));
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
            return new ReadWorkspaceArgs(true, "");
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return new ReadWorkspaceArgs(true, "");
                }
                parsed = JsonParser.parseString(raw);
            }
            if (!parsed.isJsonObject()) {
                return new ReadWorkspaceArgs(true, "");
            }
            JsonObject obj = parsed.getAsJsonObject();
            boolean committed = !obj.has("committed")
                    || !obj.get("committed").isJsonPrimitive()
                    || obj.get("committed").getAsBoolean();
            String docId = getString(obj, "doc_id");
            return new ReadWorkspaceArgs(committed, docId);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to parse read_workspace_state arguments: {}", e.getMessage());
            return new ReadWorkspaceArgs(true, "");
        }
    }

    private static String parseOptionalDocId(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return "";
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return "";
                }
                parsed = JsonParser.parseString(raw);
            }
            if (!parsed.isJsonObject()) {
                return "";
            }
            return getString(parsed.getAsJsonObject(), "doc_id");
        } catch (Exception ignored) {
            return "";
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


    private static void createCheckpoint(ServerPlayer player, String payload) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            player.displayClientMessage(Component.literal("No active session"), false);
            return;
        }
        persistActiveDocument(session);
        String label = "";
        if (payload != null && !payload.isBlank()) {
            try {
                JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
                if (obj.has("label") && obj.get("label").isJsonPrimitive()) {
                    label = obj.get("label").getAsString();
                }
            } catch (Exception ignored) {
                label = payload.trim();
            }
        }
        if (label == null || label.isBlank()) {
            label = "turn-" + session.turnCount;
        }
        addCheckpoint(session, label);
        sendSessionSync(player, session);
        player.displayClientMessage(Component.literal("Checkpoint created: " + label), false);
    }

    private static void rollbackCheckpoint(ServerPlayer player, String payload) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            player.displayClientMessage(Component.literal("No active session"), false);
            return;
        }
        persistActiveDocument(session);

        String targetId = "";
        String mode = "workspace_and_session";
        if (payload != null && !payload.isBlank()) {
            try {
                JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
                if (obj.has("id") && obj.get("id").isJsonPrimitive()) {
                    targetId = obj.get("id").getAsString();
                }
                if (obj.has("mode") && obj.get("mode").isJsonPrimitive()) {
                    mode = obj.get("mode").getAsString();
                }
            } catch (Exception e) {
                targetId = payload.trim();
            }
        }

        CheckpointEntry cp = findCheckpoint(session, targetId);
        if (cp == null) {
            player.displayClientMessage(Component.literal("Checkpoint not found"), false);
            return;
        }

        boolean sessionOnly = "session_only".equalsIgnoreCase(mode);
        if (!sessionOnly) {
            StructureBuilder.VbsScriptV2 from = copyScript(session.current);
            StructureBuilder.VbsScriptV2 to = copyScript(cp.script);
            StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(from, to);
            if (player.serverLevel() != null && session.origin != null) {
                StructurePatchEngine.applyBlockOps(player.serverLevel(), session.origin, diff.forwardOps);
            }
            session.current = to;
            session.revision = cp.revision;
            session.undoStack.clear();
            session.redoStack.clear();
            session.pendingPatch = null;
            sendPatchPreview(player, null);
        }

        session.history = deepCopyMessages(cp.history);
        session.turnCount = cp.turnCount;
        session.runtimeState = RuntimeState.IDLE;
        session.inFlight = false;
        persistActiveDocument(session);

        sendSessionSync(player, session);
        player.displayClientMessage(Component.literal("Rolled back to checkpoint: " + cp.label + (sessionOnly ? " (session only)" : "")), false);
    }

    private static void addCheckpoint(Session session, String label) {
        if (session == null) {
            return;
        }
        CheckpointEntry cp = new CheckpointEntry();
        cp.id = UUID.randomUUID().toString();
        cp.label = label == null || label.isBlank() ? "checkpoint" : label.trim();
        cp.revision = session.revision == null ? "" : session.revision;
        cp.script = copyScript(session.current);
        cp.history = deepCopyMessages(session.history == null ? List.of() : session.history);
        cp.turnCount = session.turnCount;
        cp.createdAt = System.currentTimeMillis();
        session.checkpoints.add(cp);
        while (session.checkpoints.size() > MAX_CHECKPOINTS) {
            session.checkpoints.remove(0);
        }
    }

    private static CheckpointEntry findCheckpoint(Session session, String id) {
        if (session == null || session.checkpoints == null || session.checkpoints.isEmpty()) {
            return null;
        }
        if (id == null || id.isBlank()) {
            return session.checkpoints.get(session.checkpoints.size() - 1);
        }
        String target = id.trim();
        for (CheckpointEntry cp : session.checkpoints) {
            if (cp != null && target.equals(cp.id)) {
                return cp;
            }
        }
        return null;
    }

    private static String checkpointsJson(Session session) {
        JsonArray arr = new JsonArray();
        if (session == null || session.checkpoints == null) {
            return "[]";
        }
        int start = Math.max(0, session.checkpoints.size() - 12);
        for (int i = start; i < session.checkpoints.size(); i++) {
            CheckpointEntry cp = session.checkpoints.get(i);
            if (cp == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", cp.id == null ? "" : cp.id);
            item.addProperty("label", cp.label == null ? "" : cp.label);
            item.addProperty("revision", cp.revision == null ? "" : cp.revision);
            arr.add(item);
        }
        return GSON.toJson(arr);
    }

    private static String docsSummaryJson(Session session) {
        JsonArray arr = new JsonArray();
        if (session == null) {
            return "[]";
        }
        persistActiveDocument(session);
        if (session.docs == null || session.docs.isEmpty()) {
            return "[]";
        }
        for (DocumentState doc : session.docs.values()) {
            if (doc == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", doc.id == null ? "" : doc.id);
            item.addProperty("name", doc.name == null ? "" : doc.name);
            item.addProperty("active", session.activeDocId != null && session.activeDocId.equals(doc.id));
            item.addProperty("revision", doc.revision == null ? "" : doc.revision);
            boolean hasPending = doc.pendingPatch != null;
            item.addProperty("hasPendingPatch", hasPending);
            int pendingChanged = hasPending && doc.pendingPatch.preview != null ? doc.pendingPatch.preview.changedBlocks : 0;
            item.addProperty("pendingChangedBlocks", pendingChanged);

            boolean hasSize = doc.size != null;
            item.addProperty("hasSize", hasSize);
            item.addProperty("sizeX", hasSize ? doc.size.getX() : 0);
            item.addProperty("sizeY", hasSize ? doc.size.getY() : 0);
            item.addProperty("sizeZ", hasSize ? doc.size.getZ() : 0);
            if (doc.origin != null) {
                item.addProperty("originX", doc.origin.getX());
                item.addProperty("originY", doc.origin.getY());
                item.addProperty("originZ", doc.origin.getZ());
            } else {
                item.addProperty("originX", 0);
                item.addProperty("originY", 0);
                item.addProperty("originZ", 0);
            }
            arr.add(item);
        }
        return GSON.toJson(arr);
    }

    private static void sendSessionSync(ServerPlayer player, Session session) {
        if (player == null) {
            return;
        }

        persistActiveDocument(session);
        if (session != null && session.activeDocId != null && !session.activeDocId.isBlank()) {
            loadActiveDocument(session, session.activeDocId);
        }
        boolean active = session != null;
        String sessionId = active ? session.id : "";
        String activeDocId = active && session.activeDocId != null ? session.activeDocId : "";
        DocumentState activeDoc = active ? getActiveDocument(session) : null;
        String activeDocName = activeDoc == null || activeDoc.name == null ? "" : activeDoc.name;
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

        String currentScriptJson = "";
        if (active && session.current != null) {
            try {
                currentScriptJson = GSON.toJson(GSON.toJsonTree(session.current));
            } catch (Exception e) {
                P2SMod.LOGGER.debug("Failed to serialize current script for sync: {}", e.getMessage());
            }
        }

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
                sx, sy, sz,
                checkpointsJson(session),
                currentScriptJson,
                activeDocId,
                activeDocName,
                active ? docsSummaryJson(session) : "[]"
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
        private String autoApplyDocId = "";
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
        final boolean committed;
        final String docId;

        ReadWorkspaceArgs(boolean committed, String docId) {
            this.committed = committed;
            this.docId = docId == null ? "" : docId.trim();
        }
    }

    private static final class DocumentState {
        private String id;
        private String name;
        private BlockPos origin;
        private Vec3i size;
        private StructureBuilder.VbsScriptV2 current;
        private String revision = "rev-0";
        private PendingPatch pendingPatch;
        private Deque<CommitEntry> undoStack = new ArrayDeque<>();
        private Deque<CommitEntry> redoStack = new ArrayDeque<>();
        private List<CheckpointEntry> checkpoints = new ArrayList<>();
    }

    private static final class CheckpointEntry {
        private String id;
        private String label;
        private String revision;
        private StructureBuilder.VbsScriptV2 script;
        private List<JsonObject> history = new ArrayList<>();
        private int turnCount;
        private long createdAt;
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
        Map<String, DocumentState> docs = new LinkedHashMap<>();
        String activeDocId = DEFAULT_DOC_ID;
        int nextDocIndex = 2;

        String revision;
        RuntimeState runtimeState = RuntimeState.IDLE;
        PendingPatch pendingPatch;
        Deque<CommitEntry> undoStack = new ArrayDeque<>();
        Deque<CommitEntry> redoStack = new ArrayDeque<>();
        List<CheckpointEntry> checkpoints = new ArrayList<>();
    }
}
