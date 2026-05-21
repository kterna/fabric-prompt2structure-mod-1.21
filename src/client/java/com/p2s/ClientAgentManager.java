package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.store.SessionPersistence;
import com.p2s.store.SkillStore;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ClientAgentManager {
    private static final Gson GSON = new Gson();
    private static final ExecutorService AGENT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "p2s-client-agent");
        t.setDaemon(true);
        return t;
    });
    private static final int MAX_HISTORY = 40;
    private static final int SAFETY_LOOP_LIMIT = 50;
    private static final int MAX_PLAN_ITEMS = 40;
    private static final int MAX_CHOICE_OPTIONS = 3;
    private static final String CLIENT_TOOL_CONTRACT = """
            ## Client Agent Contract
            - Start with get_project_state for current-project context before editing files.
            - Use list_skills to inspect available player skills by name/description.
            - Read full skill text only when needed via read_skill.
            - Read focused skill sub-documents via read_subdoc when read_skill exposes subdocs.
            - Use search_skill to locate relevant snippets quickly before reading full body.
            - Use update_plan to record user-visible progress with an optional explanation and ordered steps.
            - Each update_plan step must use one of: pending, in_progress, completed.
            - update_plan is for progress tracking only; it does not replace actually doing the work.
            - Before asking the user to choose among alternatives, call request_user_choice.
            - After request_user_choice, wait for user selection before continuing execution.
            - Tool calls operate on the current project implicitly; never send project_id.
            - File tools target workspace files by path only. Always pass path explicitly to read_workspace_file and propose_patch.
            - `read_workspace_file` returns the editable workspace body in `state.workspace_toml`.
            - `propose_patch` must send `{path, patch_toml}`; write actual patch operations as TOML inside `patch_toml`.
            - Use `insert_part` when creating a brand-new part; `insert_actions` is only for appending actions to an existing part.
            - Use create_workspace_file / rename_workspace_file / delete_workspace_file for file management.
            - Propose edits with propose_patch and wait for user apply/discard decision.
            - Use search_block_ids when unsure about block id names.
            - Use describe_block_state before inventing block state properties or enum values.
            - Use describe_block_entity_template before writing action block_entity fields for sign text or banner patterns.
            - When DEBUG mode exposes debug_stage_blocks, use it to stage block-state experiments inside the current selection.
            - debug_stage_blocks uses relative coordinates from selection min; call it with inspect_only=true first if bounds are unknown.
            - After using debug_stage_blocks, ask the user to visually verify the generated variants before parsing logs.
            - Use list_profiles/get_profile before creating subagents when profile choice matters.
            - Use create_subagent for delegated tasks, then poll with get_subagent.
            - Use continue_subagent to continue a failed/completed/cancelled subagent with new instructions.
            - Use list_subagents to inspect current subagent states, and delete_subagent to stop/remove one.
            - Do not request recursive subagent creation from subagents.
            """;

    private static final ExecutorService TOOL_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "p2s-tool-exec");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> PARALLEL_SAFE_TOOLS = Set.of(
            "list_skills", "read_skill", "read_subdoc", "search_skill",
            "update_plan",
            "get_project_state", "read_workspace_file", "search_block_ids", "describe_block_state", "describe_block_entity_template",
            "list_subagents", "get_subagent", "list_profiles", "get_profile"
    );

    private static final Object LOCK = new Object();
    private static LocalSession currentSession;
    private static boolean autoRestoreAttempted = false;

    private ClientAgentManager() {
    }

    public static boolean canManualCompact() {
        synchronized (LOCK) {
            return canManualCompactLocked();
        }
    }

    public static boolean isBusy() {
        synchronized (LOCK) {
            return currentSession != null && currentSession.inFlight;
        }
    }

    public static String debugCurrentSessionId() {
        synchronized (LOCK) {
            if (currentSession != null && currentSession.id != null && !currentSession.id.isBlank()) {
                return currentSession.id;
            }
        }
        return ClientSessionState.getSessionId();
    }

    public record SessionStartResult(boolean ok, String sessionId, String error) {
    }

    public static SessionStartResult ensureSessionStartedForHttp() {
        synchronized (LOCK) {
            if (currentSession != null && currentSession.inFlight) {
                return new SessionStartResult(false, "", "Agent is running.");
            }
            LocalSession session = ensureSessionLocked();
            if (session == null || session.id == null || session.id.isBlank()) {
                return new SessionStartResult(false, "", "Failed to start session.");
            }
            return new SessionStartResult(true, session.id, "");
        }
    }

    public static boolean selectWorkspacePath(String workspacePath) {
        String normalized = workspacePath == null ? "" : workspacePath.trim();
        if (!ClientSessionState.setSelectedWorkspacePath(normalized)) {
            return false;
        }

        synchronized (LOCK) {
            if (currentSession != null) {
                currentSession.selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
            }
        }

        if (ClientSessionState.isActive()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("path", ClientSessionState.getSelectedWorkspacePath());
            return ClientServerBridge.sendSessionAction("session_select_workspace", payload.toString());
        }
        return true;
    }

    public static void submitManualCompact() {
        LocalSession session;
        synchronized (LOCK) {
            if (currentSession == null) {
                postToClient(() -> ClientSessionState.addSystemMessage(P2SI18n.tr("screen.p2s.chat.no_active_session").getString()));
                return;
            }
            if (currentSession.inFlight) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            if (!ClientHistoryCompactor.hasCompactableHistory(currentSession.history)) {
                postToClient(() -> {
                    ClientSessionState.addSystemMessage(P2SI18n.tr("message.p2s.compact.nothing_to_compact").getString());
                    ClientSessionState.setStatus("done");
                });
                return;
            }
            currentSession.inFlight = true;
            session = currentSession;
        }

        postToClient(() -> ClientSessionState.setStatus("compacting"));
        AGENT_EXECUTOR.execute(() -> runManualCompact(session));
    }

    public static void submitUserMessage(String text) {
        submitUserMessage(text, text);
    }

    public static void submitUserMessage(String text, String displayText) {
        String msg = text == null ? "" : text.trim();
        String visible = displayText == null ? "" : displayText.trim();
        if (msg.isBlank()) {
            return;
        }
        if (visible.isBlank()) {
            visible = msg;
        }
        if (ClientSessionState.hasPendingPatch()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "Pending patch awaiting decision. Use Apply or Discard first.",
                    false,
                    "awaiting_confirm"
            ));
            return;
        }
        if (ClientSessionState.hasPendingChoice()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "Pending choice awaiting selection. Pick one option first.",
                    false,
                    "awaiting_choice"
            ));
            return;
        }

        LocalSession session;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session == null) {
                return;
            }
            if (session.inFlight) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            session.inFlight = true;
        }

        String finalVisible = visible;
        postToClient(() -> {
            ClientSessionState.addUserMessage(finalVisible);
            ClientSessionState.setStatus("planning");
        });

        AGENT_EXECUTOR.execute(() -> runUserTurn(session, msg));
    }

    public static void submitPatchApply() {
        if (!ClientSessionState.hasPendingPatch()) {
            return;
        }
        String summary = ClientSessionState.getPendingSummary();
        String pendingPath = ClientSessionState.getPendingPath();

        JsonObject payload = new JsonObject();
        if (pendingPath != null && !pendingPath.isBlank()) {
            payload.addProperty("path", pendingPath);
        }
        if (!ClientServerBridge.sendSessionAction("apply", payload.toString())) {
            return;
        }
        ClientSessionState.clearPendingPatch();

        String historyContent = "User APPLIED the proposed patch. Summary: " + summary + " Continue with the next step.";
        String userMessageText = "Applied patch: " + summary;

        LocalSession session;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session == null) {
                return;
            }
            if (session.inFlight) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            session.inFlight = true;

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", historyContent);
            session.history.add(user);
            trimHistoryLocked(session);
        }

        postToClient(() -> {
            ClientSessionState.addUserMessage(userMessageText);
            ClientSessionState.setStatus("planning");
        });

        AGENT_EXECUTOR.execute(() -> runAgentLoop(session));
    }

    public static void submitPatchDiscard(String reason) {
        if (!ClientSessionState.hasPendingPatch()) {
            return;
        }
        String summary = ClientSessionState.getPendingSummary();
        String pendingPath = ClientSessionState.getPendingPath();

        String safeReason = reason == null ? "" : reason.trim();
        JsonObject payload = new JsonObject();
        if (pendingPath != null && !pendingPath.isBlank()) {
            payload.addProperty("path", pendingPath);
        }
        if (!safeReason.isBlank()) {
            payload.addProperty("reason", safeReason);
        }
        if (!ClientServerBridge.sendSessionAction("discard", payload.toString())) {
            return;
        }
        ClientSessionState.clearPendingPatch();

        String historyContent = "User DISCARDED the proposed patch. Summary: " + summary;
        if (!safeReason.isEmpty()) {
            historyContent += " Reason: " + safeReason;
        }
        historyContent += " Please revise your approach.";

        String userMessageText = safeReason.isEmpty()
                ? "Discarded patch: " + summary
                : "Discarded patch: " + summary + " (Reason: " + safeReason + ")";

        String finalHistoryContent = historyContent;
        LocalSession session;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session == null) {
                return;
            }
            if (session.inFlight) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            session.inFlight = true;

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", finalHistoryContent);
            session.history.add(user);
            trimHistoryLocked(session);
        }

        postToClient(() -> {
            ClientSessionState.addUserMessage(userMessageText);
            ClientSessionState.setStatus("planning");
        });

        AGENT_EXECUTOR.execute(() -> runAgentLoop(session));
    }

    public static void submitChoiceSelection(String optionId) {
        String selectedId = optionId == null ? "" : optionId.trim();
        if (selectedId.isBlank()) {
            return;
        }

        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null || choice.options() == null || choice.options().isEmpty()) {
            postToClient(() -> ClientSessionState.setStatus("No pending choice"));
            return;
        }

        ClientSessionState.ChoiceOption selected = null;
        for (ClientSessionState.ChoiceOption option : choice.options()) {
            if (option != null && selectedId.equals(option.id())) {
                selected = option;
                break;
            }
        }
        if (selected == null) {
            postToClient(() -> ClientSessionState.setStatus("error"));
            return;
        }

        submitChoiceResponse(
                choice,
                "Choice selected [" + selected.id() + "]: " + selected.label(),
                "User selected option " + selected.id() + " (" + selected.label() + ") for request: " + choice.prompt()
        );
    }

    public static void submitCustomChoice(String customText) {
        String custom = customText == null ? "" : customText.trim();
        if (custom.isBlank()) {
            return;
        }

        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null || choice.options() == null || choice.options().isEmpty()) {
            postToClient(() -> ClientSessionState.setStatus("No pending choice"));
            return;
        }

        submitChoiceResponse(
                choice,
                "Custom choice: " + custom,
                "User provided a custom response for request: " + choice.prompt() + "\nCustom response: " + custom
        );
    }

    private static void submitChoiceResponse(
            ClientSessionState.ChoiceRequest choice,
            String userMessageText,
            String historyContent
    ) {
        if (choice == null) {
            return;
        }
        ClientSessionState.clearPendingChoice();

        LocalSession session;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session == null) {
                return;
            }
            if (session.inFlight) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            session.inFlight = true;

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", historyContent == null ? "" : historyContent);
            session.history.add(user);
            trimHistoryLocked(session);
        }

        postToClient(() -> {
            ClientSessionState.addUserMessage(userMessageText);
            ClientSessionState.setStatus("planning");
        });

        AGENT_EXECUTOR.execute(() -> runAgentLoop(session));
    }

    private static void runAgentLoop(LocalSession session) {
        int iterations = 0;
        try {
            while (iterations < SAFETY_LOOP_LIMIT) {
                if (P2SMod.DEBUG) {
                    int historySize;
                    synchronized (LOCK) {
                        historySize = session.history == null ? 0 : session.history.size();
                    }
                    P2SMod.LOGGER.info("[DEBUG] ClientAgent loop start -> iteration={}, sessionId={}, historySize={}, inFlight={}", iterations, session.id, historySize, session.inFlight);
                }
                List<JsonObject> snapshot;
                synchronized (LOCK) {
                    if (session != currentSession) {
                        return;
                    }
                    snapshot = deepCopyMessages(session.history);
                }

                long timeoutSeconds = Math.max(1, Math.max(
                        P2SClientConfig.getSessionJobTimeoutSeconds(),
                        P2SClientConfig.getHttpTimeoutSeconds() + 5
                ));

                LLMService.SessionResult result;
                if (P2SClientConfig.getUseStreaming()) {
                    if (P2SMod.DEBUG) {
                        P2SMod.LOGGER.info("[DEBUG] ClientAgent using streaming path");
                    }
                    postToClient(ClientSessionState::beginStreaming);
                    try {
                        result = LLMService.requestWithHistoryStreaming(
                                        snapshot,
                                        P2SClientConfig.llmRequestConfig(),
                                        token -> postToClient(() -> ClientSessionState.appendStreamingToken(token))
                                )
                                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                                .join();
                    } finally {
                        postToClient(ClientSessionState::endStreaming);
                    }
                } else {
                    if (P2SMod.DEBUG) {
                        P2SMod.LOGGER.info("[DEBUG] ClientAgent using non-streaming path");
                    }
                    result = LLMService.requestWithHistoryWithSkills(
                                    snapshot,
                                    P2SClientConfig.llmRequestConfig()
                            )
                            .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                            .join();
                }

                boolean continueLoop = handleResult(session, result);
                if (!continueLoop) {
                    break;
                }
                maybeRunAutomaticCompaction(session, ClientHistoryCompactor.CompactTrigger.AUTO_MID_TURN);
                iterations++;
            }
            if (iterations >= SAFETY_LOOP_LIMIT) {
                P2SMod.LOGGER.warn("Agent loop reached safety limit of {} iterations", SAFETY_LOOP_LIMIT);
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Agent loop reached safety limit (" + SAFETY_LOOP_LIMIT + " iterations). Stopping.",
                        false,
                        "error"
                ));
            }
        } catch (Exception ex) {
            String error = formatAgentError(ex);
            postToClient(() -> ClientSessionState.onChatResponse("Request failed: " + error, false, "error"));
        } finally {
            synchronized (LOCK) {
                if (session == currentSession) {
                    session.inFlight = false;
                }
            }
            postToClient(ClientAgentManager::maybeAutoApplyPendingPatch);
            autoSaveSession(session);
        }
    }

    private static boolean handleResult(LocalSession session, LLMService.SessionResult result) {
        if (result == null) {
            postToClient(() -> ClientSessionState.onChatResponse("Request failed: empty response", false, "error"));
            return false;
        }

        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] ClientAgent handleResult -> text={}, toolCalls={}, rawAssistant={}",
                    result.textContent() == null ? "null" : result.textContent().length() + " chars",
                    result.toolCalls() == null ? 0 : result.toolCalls().size(),
                    result.rawAssistantMessage());
        }

        synchronized (LOCK) {
            if (result.rawAssistantMessage() != null) {
                session.history.add(result.rawAssistantMessage().deepCopy());
                trimHistoryLocked(session);
            }
        }

        List<LLMService.ToolCall> toolCalls = result.toolCalls() == null ? List.of() : result.toolCalls();
        if (!toolCalls.isEmpty()) {
            String text = result.textContent();
            if (text != null && !text.isBlank()) {
                postToClient(() -> ClientSessionState.onChatResponse(text, false, null));
            }

            List<ToolCallResult> results = executeToolCallsBatch(session, toolCalls);
            postVisibleToolResults(results);
            for (ToolCallResult tcr : results) {
                if (P2SMod.DEBUG) {
                    P2SMod.LOGGER.info("[DEBUG] ClientAgent tool call -> name={}, args={}, result={}", tcr.call().name(), tcr.call().arguments(), GSON.toJson(tcr.payload()));
                }
                JsonObject toolMsg = buildToolMessage(tcr.call(), tcr.payload());
                synchronized (LOCK) {
                    session.history.add(toolMsg);
                    trimHistoryLocked(session);
                    if (P2SMod.DEBUG) {
                        P2SMod.LOGGER.info("[DEBUG] ClientAgent history size after tool msg: {}", session.history.size());
                    }
                }
            }

            postToClient(() -> ClientSessionState.setStatus(statusAfterToolCalls()));

            if (ClientSessionState.hasPendingChoice()) {
                return false;
            }
            if (ClientSessionState.hasPendingPatch()) {
                return false;
            }
            return true;
        }

        String text = result.textContent();
        if (text != null && !text.isBlank()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    text,
                    false,
                    ClientSessionState.hasPendingPatch() ? "awaiting_confirm" : "done"
            ));
        } else {
            postToClient(() -> ClientSessionState.setStatus(
                    ClientSessionState.hasPendingPatch() ? "awaiting_confirm" : "done"
            ));
        }
        return false;
    }

    private record ToolCallResult(LLMService.ToolCall call, JsonObject payload) {
    }

    private record ToolLogPresentation(String summary, String detail, boolean error) {
    }

    private static void runUserTurn(LocalSession session, String message) {
        boolean delegated = false;
        try {
            maybeRunAutomaticCompaction(session, ClientHistoryCompactor.CompactTrigger.AUTO_PRE_TURN);
            synchronized (LOCK) {
                if (session != currentSession) {
                    return;
                }
                appendUserMessageLocked(session, message);
            }
            delegated = true;
            runAgentLoop(session);
        } catch (Exception ex) {
            String error = formatAgentError(ex);
            postToClient(() -> ClientSessionState.onChatResponse("Request failed: " + error, false, "error"));
        } finally {
            if (!delegated) {
                synchronized (LOCK) {
                    if (session == currentSession) {
                        session.inFlight = false;
                    }
                }
                autoSaveSession(session);
            }
        }
    }

    private static void runManualCompact(LocalSession session) {
        try {
            List<JsonObject> snapshot;
            synchronized (LOCK) {
                if (session != currentSession) {
                    return;
                }
                snapshot = deepCopyMessages(session.history);
            }
            if (!ClientHistoryCompactor.hasCompactableHistory(snapshot)) {
                postToClient(() -> ClientSessionState.addSystemMessage(P2SI18n.tr("message.p2s.compact.nothing_to_compact").getString()));
                return;
            }
            boolean compacted = performCompaction(session, snapshot, ClientHistoryCompactor.CompactTrigger.MANUAL, false);
            if (!compacted) {
                postToClient(() -> ClientSessionState.setStatus("done"));
            }
        } finally {
            synchronized (LOCK) {
                if (session == currentSession) {
                    session.inFlight = false;
                }
            }
            autoSaveSession(session);
        }
    }

    private static boolean maybeRunAutomaticCompaction(
            LocalSession session,
            ClientHistoryCompactor.CompactTrigger trigger
    ) {
        if (!P2SClientConfig.isAutoCompactEnabled()) {
            return false;
        }

        List<JsonObject> snapshot;
        synchronized (LOCK) {
            if (session != currentSession) {
                return false;
            }
            snapshot = deepCopyMessages(session.history);
        }

        if (!ClientHistoryCompactor.hasCompactableHistory(snapshot)) {
            return false;
        }
        if (!ClientHistoryCompactor.hasNonSummaryNonSystemMessages(snapshot)) {
            return false;
        }
        if (ClientHistoryCompactor.estimateHistoryTokens(snapshot) < P2SClientConfig.getAutoCompactTokenLimit()) {
            return false;
        }
        return performCompaction(session, snapshot, trigger, true);
    }

    private static boolean performCompaction(
            LocalSession session,
            List<JsonObject> snapshot,
            ClientHistoryCompactor.CompactTrigger trigger,
            boolean requireReduction
    ) {
        long timeoutSeconds = Math.max(1, Math.max(
                P2SClientConfig.getSessionJobTimeoutSeconds(),
                P2SClientConfig.getHttpTimeoutSeconds() + 5L
        ));
        postToClient(() -> ClientSessionState.setStatus("compacting"));
        try {
            ClientHistoryCompactor.CompactionResult result = ClientHistoryCompactor.compactHistory(
                    snapshot,
                    trigger,
                    P2SClientConfig.llmRequestConfig(),
                    timeoutSeconds
            );
            if (requireReduction && result.estimatedTokensAfter() >= result.estimatedTokensBefore()) {
                return false;
            }

            synchronized (LOCK) {
                if (session != currentSession) {
                    return false;
                }
                session.history = deepCopyMessages(result.replacementHistory());
                trimHistoryLocked(session);
            }

            postToClient(() -> {
                ClientSessionState.addSystemMessage(P2SI18n.tr("message.p2s.compact.done").getString() + "\n" + result.summaryBody());
                if (trigger == ClientHistoryCompactor.CompactTrigger.MANUAL) {
                    ClientSessionState.setStatus("done");
                }
            });
            return true;
        } catch (Exception ex) {
            String error = formatAgentError(ex);
            P2SMod.LOGGER.warn("History compaction failed ({}): {}", trigger, error);
            synchronized (LOCK) {
                if (session == currentSession) {
                    trimHistoryLocked(session);
                }
            }
            postToClient(() -> ClientSessionState.addSystemMessage(
                    P2SI18n.tr("message.p2s.compact.failed_fallback").getString() + " " + error
            ));
            return false;
        }
    }

    private static List<ToolCallResult> executeToolCallsBatch(LocalSession session, List<LLMService.ToolCall> toolCalls) {
        if (toolCalls.size() == 1) {
            LLMService.ToolCall call = toolCalls.get(0);
            JsonObject payload = executeToolCall(session, call);
            return List.of(new ToolCallResult(call, payload));
        }

        List<LLMService.ToolCall> parallelCalls = new ArrayList<>();
        List<LLMService.ToolCall> serialCalls = new ArrayList<>();
        for (LLMService.ToolCall call : toolCalls) {
            if (call != null && call.name() != null && PARALLEL_SAFE_TOOLS.contains(call.name())) {
                parallelCalls.add(call);
            } else {
                serialCalls.add(call);
            }
        }

        Map<LLMService.ToolCall, JsonObject> resultMap = new LinkedHashMap<>();

        if (!parallelCalls.isEmpty()) {
            List<CompletableFuture<ToolCallResult>> futures = new ArrayList<>();
            for (LLMService.ToolCall call : parallelCalls) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> new ToolCallResult(call, executeToolCall(session, call)),
                        TOOL_EXECUTOR
                ));
            }
            for (CompletableFuture<ToolCallResult> future : futures) {
                try {
                    ToolCallResult tcr = future.join();
                    resultMap.put(tcr.call(), tcr.payload());
                } catch (Exception e) {
                    P2SMod.LOGGER.warn("Parallel tool call failed: {}", e.getMessage());
                }
            }
        }

        for (LLMService.ToolCall call : serialCalls) {
            JsonObject payload = executeToolCall(session, call);
            resultMap.put(call, payload);
        }

        List<ToolCallResult> ordered = new ArrayList<>();
        for (LLMService.ToolCall call : toolCalls) {
            JsonObject payload = resultMap.get(call);
            if (payload == null) {
                payload = toolError(call == null ? "" : call.name(), "Execution failed");
            }
            ordered.add(new ToolCallResult(call, payload));
        }
        return ordered;
    }

    private static JsonObject executeToolCall(LocalSession session, LLMService.ToolCall call) {
        if (call == null || call.name() == null) {
            return toolError("", "Invalid tool call");
        }

        String toolName = call.name();
        return switch (toolName) {
            case "list_skills" -> listSkillsPayload();
            case "read_skill" -> readSkillPayload(call.arguments());
            case "read_subdoc" -> readSubdocPayload(call.arguments());
            case "search_skill" -> searchSkillPayload(call.arguments());
            case "update_plan" -> updatePlanPayload(call.arguments());
            case "request_user_choice" -> requestUserChoicePayload(call.arguments());
            case "clear_user_choice" -> clearUserChoicePayload();
            case "list_subagents" -> listSubagentsPayload(session, call.arguments());
            case "create_subagent" -> createSubagentPayload(session, call.arguments());
            case "continue_subagent" -> continueSubagentPayload(session, call.arguments());
            case "get_subagent" -> getSubagentPayload(session, call.arguments());
            case "delete_subagent" -> deleteSubagentPayload(session, call.arguments());
            case "list_profiles" -> SubagentManager.listProfiles();
            case "get_profile" -> getProfilePayload(call.arguments());
            case "get_project_state", "read_workspace_file",
                    "create_workspace_file", "rename_workspace_file", "delete_workspace_file",
                    "propose_patch", "search_block_ids", "describe_block_state", "describe_block_entity_template", "debug_stage_blocks" ->
                    callServerTool(toolName, normalizeArgsObject(call.arguments()));
            default -> toolError(toolName, "Unknown tool");
        };
    }

    private static JsonObject listSubagentsPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        boolean includeDeleted = asBoolean(args, "include_deleted", false);
        return SubagentManager.listSubagents(sessionId(session), includeDeleted);
    }

    private static JsonObject createSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        return SubagentManager.createSubagent(sessionId(session), args);
    }

    private static JsonObject continueSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("continue_subagent", "Missing id");
        }
        return SubagentManager.continueSubagent(sessionId(session), args);
    }

    private static JsonObject getSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("get_subagent", "Missing id");
        }
        return SubagentManager.getSubagent(sessionId(session), id);
    }

    private static JsonObject deleteSubagentPayload(LocalSession session, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("delete_subagent", "Missing id");
        }
        return SubagentManager.deleteSubagent(sessionId(session), id);
    }

    private static JsonObject getProfilePayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            return toolError("get_profile", "Missing id");
        }
        return SubagentManager.getProfile(id);
    }

    private static JsonObject listSkillsPayload() {
        JsonObject payload = toolOk("list_skills");
        String active = SkillStore.activeSkillId();
        payload.addProperty("active_skill_id", active == null ? "" : active);
        JsonArray items = new JsonArray();
        for (SkillStore.SkillMeta meta : SkillStore.listSkills()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", meta.id());
            item.addProperty("name", meta.name());
            item.addProperty("description", meta.description());
            items.add(item);
        }
        payload.add("skills", items);
        payload.addProperty("count", items.size());
        return payload;
    }

    private static JsonObject readSkillPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        if (id.isBlank()) {
            id = SkillStore.activeSkillId();
        }
        if (id.isBlank()) {
            return toolError("read_skill", "No skill id and no active skill");
        }
        SkillStore.SkillDocument doc = SkillStore.readSkill(id);
        if (doc == null) {
            return toolError("read_skill", "Skill not found: " + id);
        }
        JsonObject payload = toolOk("read_skill");
        payload.addProperty("id", doc.meta().id());
        payload.addProperty("name", doc.meta().name());
        payload.addProperty("description", doc.meta().description());
        payload.addProperty("body", doc.body() == null ? "" : doc.body());
        JsonArray subdocs = new JsonArray();
        for (SkillStore.SubdocMeta subdoc : SkillStore.listSubdocs(doc.meta().id())) {
            JsonObject item = new JsonObject();
            item.addProperty("path", subdoc.path());
            item.addProperty("updated_at", subdoc.updatedAt());
            subdocs.add(item);
        }
        payload.add("subdocs", subdocs);
        payload.addProperty("subdoc_count", subdocs.size());
        return payload;
    }

    private static JsonObject readSubdocPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String path = asString(args, "path");
        if (id.isBlank()) {
            id = SkillStore.activeSkillId();
        }
        if (id.isBlank()) {
            return toolError("read_subdoc", "No skill id and no active skill");
        }
        if (path.isBlank()) {
            return toolError("read_subdoc", "Missing path");
        }
        SkillStore.SubdocDocument doc = SkillStore.readSubdoc(id, path);
        if (doc == null) {
            return toolError("read_subdoc", "Subdoc not found: " + id + "/" + path);
        }
        JsonObject payload = toolOk("read_subdoc");
        payload.addProperty("id", doc.skillId());
        payload.addProperty("path", doc.path());
        payload.addProperty("body", doc.body() == null ? "" : doc.body());
        payload.addProperty("updated_at", doc.updatedAt());
        return payload;
    }

    private static JsonObject searchSkillPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String query = asString(args, "query");
        int limit = asInt(args, "limit", 10, 1, 50);
        if (query.isBlank()) {
            return toolError("search_skill", "Missing query");
        }
        List<SkillStore.SearchHit> hits = SkillStore.searchSkill(id, query, limit);
        JsonObject payload = toolOk("search_skill");
        payload.addProperty("query", query);
        payload.addProperty("limit", limit);
        JsonArray arr = new JsonArray();
        for (SkillStore.SearchHit hit : hits) {
            JsonObject item = new JsonObject();
            item.addProperty("id", hit.skillId());
            item.addProperty("line", hit.line());
            item.addProperty("text", hit.text());
            arr.add(item);
        }
        payload.add("matches", arr);
        payload.addProperty("count", arr.size());
        if (arr.isEmpty()) {
            payload.addProperty("warning", "No matches");
        }
        return payload;
    }

    private static JsonObject updatePlanPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        if (!args.has("plan") || !args.get("plan").isJsonArray()) {
            return toolError("update_plan", "Missing plan array");
        }

        String explanation = asString(args, "explanation");
        List<ClientSessionState.PlanItem> items = parsePlanItems(args.getAsJsonArray("plan"));
        ClientSessionState.setPlan(explanation, items);

        JsonObject payload = toolOk("update_plan");
        payload.addProperty("explanation", explanation == null ? "" : explanation.trim());
        payload.addProperty("count", items.size());
        return payload;
    }

    private static List<ClientSessionState.PlanItem> parsePlanItems(JsonArray itemsArg) {
        List<ClientSessionState.PlanItem> items = new ArrayList<>();
        for (JsonElement element : itemsArg) {
            if (items.size() >= MAX_PLAN_ITEMS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String content = asString(obj, "step");
            if (content.isBlank()) {
                continue;
            }
            String status = normalizePlanStatus(asString(obj, "status"));
            items.add(new ClientSessionState.PlanItem(content.trim(), status));
        }
        return items;
    }

    private static JsonObject requestUserChoicePayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String prompt = asString(args, "prompt");
        if (prompt.isBlank()) {
            prompt = asString(args, "question");
        }
        if (prompt.isBlank()) {
            return toolError("request_user_choice", "Missing prompt");
        }
        if (!args.has("options") || !args.get("options").isJsonArray()) {
            return toolError("request_user_choice", "Missing options array");
        }

        JsonArray optionsArg = args.getAsJsonArray("options");
        List<ClientSessionState.ChoiceOption> options = new ArrayList<>();
        int index = 1;
        for (JsonElement element : optionsArg) {
            if (options.size() >= MAX_CHOICE_OPTIONS) {
                break;
            }
            String id = "";
            String label = "";
            String description = "";
            if (element != null && element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                id = normalizeStableId(asString(obj, "id"));
                label = asString(obj, "label");
                description = asString(obj, "description");
            } else if (element != null && element.isJsonPrimitive()) {
                label = element.getAsString();
            }
            if (label == null || label.isBlank()) {
                index += 1;
                continue;
            }
            if (id.isBlank()) {
                id = "opt-" + index;
            }
            options.add(new ClientSessionState.ChoiceOption(id, label.trim(), description == null ? "" : description.trim()));
            index += 1;
        }
        if (options.size() < 2) {
            return toolError("request_user_choice", "Need at least 2 valid options");
        }

        String requestId = asString(args, "request_id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "choice-" + Long.toString(System.currentTimeMillis(), 36);
        }
        String finalRequestId = requestId.trim();
        String finalPrompt = prompt.trim();
        List<ClientSessionState.ChoiceOption> finalOptions = List.copyOf(options);
        ClientSessionState.setPendingChoice(finalRequestId, finalPrompt, finalOptions);
        postToClient(() -> ClientSessionState.setStatus("awaiting_choice"));

        JsonObject payload = toolOk("request_user_choice");
        payload.addProperty("request_id", finalRequestId);
        payload.addProperty("prompt", finalPrompt);
        payload.addProperty("count", finalOptions.size());
        return payload;
    }

    private static JsonObject clearUserChoicePayload() {
        ClientSessionState.clearPendingChoice();
        JsonObject payload = toolOk("clear_user_choice");
        payload.addProperty("cleared", true);
        return payload;
    }

    private static JsonObject callServerTool(String toolName, JsonObject args) {
        try {
            JsonObject payload = ClientToolBridge.call(toolName, args).join();
            if (payload == null) {
                return toolError(toolName, "Empty server response");
            }
            return payload;
        } catch (Exception e) {
            return toolError(toolName, "Server tool failed: " + formatAgentError(e));
        }
    }

    private static String statusAfterToolCalls() {
        if (ClientSessionState.hasPendingChoice()) {
            return "awaiting_choice";
        }
        if (ClientSessionState.hasPendingPatch()) {
            return "awaiting_confirm";
        }
        return "thinking";
    }

    private static String normalizePlanStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "pending";
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "completed" -> "completed";
            case "in_progress" -> "in_progress";
            case "pending" -> "pending";
            default -> "pending";
        };
    }

    private static String normalizeStableId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase();
        value = value.replaceAll("[^a-z0-9_-]+", "-");
        value = value.replaceAll("-{2,}", "-");
        value = value.replaceAll("^-+", "");
        value = value.replaceAll("-+$", "");
        return value;
    }

    private static JsonObject normalizeArgsObject(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return new JsonObject();
                }
                parsed = JsonParser.parseString(raw);
            }
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            return new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static int asInt(JsonObject obj, String key, int fallback, int min, int max) {
        if (obj == null || key == null || !obj.has(key)) {
            return fallback;
        }
        try {
            int value = obj.get(key).getAsInt();
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean asBoolean(JsonObject obj, String key, boolean fallback) {
        if (obj == null || key == null || !obj.has(key)) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String asString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonObject toolOk(String toolName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        return payload;
    }

    private static JsonObject toolError(String toolName, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        payload.addProperty("error", error == null ? "" : error);
        return payload;
    }

    private static String formatAgentError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "Timed out";
        }
        String message = cause == null ? null : cause.getMessage();
        if (message == null || message.isBlank()) {
            message = ex == null ? "" : ex.getMessage();
        }
        return message == null || message.isBlank() ? "Unknown error" : message;
    }


    private static void postVisibleToolResults(List<ToolCallResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        postToClient(() -> {
            for (ToolCallResult result : results) {
                ToolLogPresentation presentation = buildToolLogPresentation(
                        result == null ? null : result.call(),
                        result == null ? null : result.payload()
                );
                if (presentation == null || presentation.summary() == null || presentation.summary().isBlank()) {
                    continue;
                }
                if (presentation.error()) {
                    ClientSessionState.addToolErrorMessage(presentation.summary(), presentation.detail());
                } else {
                    ClientSessionState.addToolCallMessage(presentation.summary(), presentation.detail());
                }
            }
        });
    }

    private static ToolLogPresentation buildToolLogPresentation(LLMService.ToolCall call, JsonObject payload) {
        boolean error = payload == null || !asBoolean(payload, "ok", true);
        String summary = error
                ? P2SI18n.tr("message.p2s.agent.tool.failed", toolSummaryText(call)).getString()
                : toolSummaryText(call);
        String detail = buildToolDetail(call, payload);
        return new ToolLogPresentation(summary, detail, error);
    }

    private static String toolSummaryText(LLMService.ToolCall call) {
        String key = toolSummaryKey(call);
        if (key == null || key.isBlank()) {
            return rawToolName(call);
        }
        return P2SI18n.tr(key).getString();
    }

    private static String toolSummaryKey(LLMService.ToolCall call) {
        String toolName = rawToolName(call);
        return switch (toolName) {
            case "list_skills" -> "message.p2s.agent.tool.summary.list_skills";
            case "read_skill" -> "message.p2s.agent.tool.summary.read_skill";
            case "read_subdoc" -> "message.p2s.agent.tool.summary.read_subdoc";
            case "search_skill" -> "message.p2s.agent.tool.summary.search_skill";
            case "update_plan" -> "message.p2s.agent.tool.summary.update_plan";
            case "request_user_choice" -> "message.p2s.agent.tool.summary.request_user_choice";
            case "clear_user_choice" -> "message.p2s.agent.tool.summary.clear_user_choice";
            case "list_subagents" -> "message.p2s.agent.tool.summary.list_subagents";
            case "create_subagent" -> "message.p2s.agent.tool.summary.create_subagent";
            case "continue_subagent" -> "message.p2s.agent.tool.summary.continue_subagent";
            case "get_subagent" -> "message.p2s.agent.tool.summary.get_subagent";
            case "delete_subagent" -> "message.p2s.agent.tool.summary.delete_subagent";
            case "list_profiles" -> "message.p2s.agent.tool.summary.list_profiles";
            case "get_profile" -> "message.p2s.agent.tool.summary.get_profile";
            case "get_project_state" -> "message.p2s.agent.tool.summary.get_project_state";
            case "read_workspace_file" -> "message.p2s.agent.tool.summary.read_workspace_file";
            case "create_workspace_file" -> "message.p2s.agent.tool.summary.create_workspace_file";
            case "rename_workspace_file" -> "message.p2s.agent.tool.summary.rename_workspace_file";
            case "delete_workspace_file" -> "message.p2s.agent.tool.summary.delete_workspace_file";
            case "propose_patch" -> "message.p2s.agent.tool.summary.propose_patch";
            case "search_block_ids" -> "message.p2s.agent.tool.summary.search_block_ids";
            case "describe_block_state" -> "message.p2s.agent.tool.summary.describe_block_state";
            case "describe_block_entity_template" -> "message.p2s.agent.tool.summary.describe_block_entity_template";
            case "debug_stage_blocks" -> "message.p2s.agent.tool.summary.debug_stage_blocks";
            default -> "";
        };
    }

    private static String rawToolName(LLMService.ToolCall call) {
        if (call == null || call.name() == null) {
            return "unknown";
        }
        String name = call.name().trim();
        return name.isBlank() ? "unknown" : name;
    }

    private static String buildToolDetail(LLMService.ToolCall call, JsonObject payload) {
        List<String> lines = new ArrayList<>();
        lines.add(P2SI18n.tr("message.p2s.agent.tool.detail.name", rawToolName(call)).getString());
        appendToolArgumentDetails(lines, normalizeArgsObject(call == null ? null : call.arguments()));
        if (payload == null || !asBoolean(payload, "ok", true)) {
            String error = payload == null ? "Execution failed" : asString(payload, "error");
            if (error.isBlank()) {
                error = "Execution failed";
            }
            lines.add(P2SI18n.tr("message.p2s.agent.tool.detail.error", error).getString());
        }
        return String.join("\n", lines);
    }

    private static void appendToolArgumentDetails(List<String> lines, JsonObject args) {
        if (args == null || args.entrySet().isEmpty()) {
            return;
        }
        List<String> handledKeys = new ArrayList<>();
        String[] preferredKeys = {"path", "query", "name", "id", "action", "request_id", "profile_id", "instructions", "prompt", "content"};
        for (String key : preferredKeys) {
            if (args.has(key)) {
                appendToolArgumentLine(lines, args, key);
                handledKeys.add(key);
            }
        }
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || handledKeys.contains(key)) {
                continue;
            }
            appendToolArgumentLine(lines, args, key);
        }
    }

    private static void appendToolArgumentLine(List<String> lines, JsonObject args, String key) {
        if (args == null || key == null || key.isBlank() || !args.has(key)) {
            return;
        }
        String value = formatToolArgumentValue(args.get(key));
        if (value.isBlank()) {
            return;
        }
        lines.add(key + ": " + value);
    }

    private static String formatToolArgumentValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        String formatted;
        if (value.isJsonPrimitive()) {
            try {
                formatted = value.getAsString();
            } catch (Exception ignored) {
                formatted = value.toString();
            }
        } else {
            formatted = GSON.toJson(value);
        }
        String normalized = formatted.replace('\n', ' ').replace('\r', ' ').trim();
        int maxLength = 180;
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength - 3) + "...";
        }
        return normalized;
    }

    private static JsonObject buildToolMessage(LLMService.ToolCall call, JsonObject payload) {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        if (call != null && call.id() != null && !call.id().isBlank()) {
            toolMsg.addProperty("tool_call_id", call.id());
        }
        toolMsg.addProperty("content", payload == null ? "{}" : GSON.toJson(payload));
        return toolMsg;
    }

    private static boolean canManualCompactLocked() {
        return currentSession != null
                && !currentSession.inFlight
                && ClientHistoryCompactor.hasCompactableHistory(currentSession.history);
    }

    private static void appendUserMessageLocked(LocalSession session, String content) {
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", content == null ? "" : content);
        session.history.add(user);
        trimHistoryLocked(session);
    }

    private static String sessionId(LocalSession session) {
        if (session == null || session.id == null || session.id.isBlank()) {
            return "default";
        }
        return session.id;
    }

    private static void trimHistoryLocked(LocalSession session) {
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
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<JsonObject> copy = new ArrayList<>(source.size());
        for (JsonObject message : source) {
            copy.add(message == null ? null : message.deepCopy());
        }
        return copy;
    }

    private static void autoSaveSession(LocalSession session) {
        if (session == null || session.id == null || session.id.isBlank()) {
            return;
        }
        try {
            List<JsonObject> historyCopy;
            synchronized (LOCK) {
                historyCopy = deepCopyMessages(session.history);
            }

            List<ClientSessionState.ChatMessage> chatMessages = ClientSessionState.getMessages();
            List<SessionPersistence.ChatMessageEntry> chatLog = new ArrayList<>();
            for (ClientSessionState.ChatMessage message : chatMessages) {
                chatLog.add(new SessionPersistence.ChatMessageEntry(
                        message.id(),
                        message.role(),
                        message.text(),
                        message.kind(),
                        message.detail()
                ));
            }

            List<SessionPersistence.PlanItemEntry> planEntries = new ArrayList<>();
            for (ClientSessionState.PlanItem item : ClientSessionState.getPlanItems()) {
                planEntries.add(new SessionPersistence.PlanItemEntry(item.step(), item.status()));
            }

            SessionPersistence.ChoiceRequestEntry pendingChoiceEntry = null;
            ClientSessionState.ChoiceRequest pendingChoice = ClientSessionState.getPendingChoice();
            if (pendingChoice != null && pendingChoice.options() != null && !pendingChoice.options().isEmpty()) {
                List<SessionPersistence.ChoiceOptionEntry> optionEntries = new ArrayList<>();
                for (ClientSessionState.ChoiceOption option : pendingChoice.options()) {
                    if (option == null) {
                        continue;
                    }
                    optionEntries.add(new SessionPersistence.ChoiceOptionEntry(option.id(), option.label(), option.description()));
                }
                pendingChoiceEntry = new SessionPersistence.ChoiceRequestEntry(
                        pendingChoice.requestId(),
                        pendingChoice.prompt(),
                        optionEntries
                );
            }

            String title = "";
            for (ClientSessionState.ChatMessage message : chatMessages) {
                if (!P2SI18n.isUserRole(message.role()) || message.text() == null || message.text().isBlank()) {
                    continue;
                }
                title = message.text().trim();
                if (title.length() > 60) {
                    title = title.substring(0, 57) + "...";
                }
                break;
            }
            if (title.isBlank()) {
                title = P2SI18n.tr("screen.p2s.sessions.default_title", session.id.substring(0, Math.min(8, session.id.length()))).getString();
            }

            String projectId = session.projectId == null || session.projectId.isBlank()
                    ? ClientSessionState.getProjectId()
                    : session.projectId;
            String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
            if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
                selectedWorkspacePath = session.selectedWorkspacePath == null ? "" : session.selectedWorkspacePath;
            }
            session.projectId = projectId == null ? "" : projectId;
            session.selectedWorkspacePath = selectedWorkspacePath == null ? "" : selectedWorkspacePath;

            SessionPersistence.SavedSession saved = new SessionPersistence.SavedSession(
                    session.id,
                    session.projectId,
                    title,
                    session.createdAt,
                    System.currentTimeMillis(),
                    chatLog.size(),
                    historyCopy,
                    chatLog,
                    planEntries,
                    ClientSessionState.getPlanExplanation(),
                    session.selectedWorkspacePath,
                    pendingChoiceEntry
            );
            CompletableFuture.runAsync(() -> SessionPersistence.saveSession(saved));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Auto-save session failed: {}", e.getMessage());
        }
    }

    public static void onClientJoin() {
        synchronized (LOCK) {
            autoRestoreAttempted = false;
        }
        if (!ClientServerBridge.hasRequiredServer()) {
            postToClient(() -> {
                clearLocalSessionView();
                ClientServerBridge.notifyMissingServer();
            });
            return;
        }
        postToClient(() -> {
            if (!autoRestoreAttempted) {
                restoreLatestSession(null, false);
            }
        });
    }

    public static void onClientDisconnect() {
        LocalSession previous;
        synchronized (LOCK) {
            previous = currentSession;
            currentSession = null;
            autoRestoreAttempted = false;
        }
        if (previous != null) {
            autoSaveSession(previous);
        }
        postToClient(() -> {
            clearLocalSessionView();
            ClientSessionState.resetAll();
        });
    }

    public static void restoreLatestSession(String projectId, boolean notifyOnFailure) {
        if (!ClientServerBridge.hasRequiredServer()) {
            return;
        }
        synchronized (LOCK) {
            autoRestoreAttempted = true;
            if (currentSession != null && currentSession.inFlight) {
                return;
            }
            if (currentSession != null) {
                return;
            }
        }

        List<SessionPersistence.SessionIndexEntry> sessions = (projectId == null || projectId.isBlank())
                ? SessionPersistence.listSessions()
                : SessionPersistence.listSessions(projectId);
        if (sessions == null || sessions.isEmpty()) {
            if (notifyOnFailure) {
                postToClient(() -> ClientSessionState.setStatus(""));
            }
            return;
        }
        SessionPersistence.SessionIndexEntry latest = sessions.get(0);
        if (latest == null || latest.id() == null || latest.id().isBlank()) {
            return;
        }
        restoreSession(latest.id());
    }

    public static void maybeAutoApplyPendingPatch() {
        if (!P2SClientConfig.getAutoApplyPatch() || !ClientSessionState.hasPendingPatch()) {
            return;
        }
        synchronized (LOCK) {
            if (currentSession == null || currentSession.inFlight) {
                return;
            }
        }
        submitPatchApply();
    }

    public static void restoreSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (!ClientServerBridge.hasRequiredServer()) {
            ClientServerBridge.notifyMissingServer();
            return;
        }

        synchronized (LOCK) {
            if (currentSession != null && currentSession.inFlight) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Cannot restore session while agent is running.",
                        false,
                        "busy"
                ));
                return;
            }
        }

        SessionPersistence.SavedSession saved = SessionPersistence.loadSession(sessionId);
        if (saved == null) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "Failed to load session.",
                    false,
                    "error"
            ));
            return;
        }

        closeCurrentSessionInternal(true);

        LocalSession session = new LocalSession();
        session.id = saved.id();
        session.history = new ArrayList<>(saved.llmHistory());
        session.inFlight = false;
        session.createdAt = saved.createdAt();
        session.serverSessionStarted = false;
        session.projectId = saved.projectId() == null ? "" : saved.projectId();
        session.selectedWorkspacePath = saved.selectedWorkspacePath() == null ? "" : saved.selectedWorkspacePath();

        synchronized (LOCK) {
            currentSession = session;
        }

        postToClient(() -> {
            clearLocalSessionView();
            if (saved.chatLog() != null) {
                for (SessionPersistence.ChatMessageEntry entry : saved.chatLog()) {
                    ClientSessionState.addMessage(entry.id(), entry.role(), entry.text(), entry.kind(), entry.detail());
                }
            }
            if ((saved.planItems() != null && !saved.planItems().isEmpty())
                    || (saved.planExplanation() != null && !saved.planExplanation().isBlank())) {
                List<ClientSessionState.PlanItem> items = new ArrayList<>();
                if (saved.planItems() != null) {
                    for (SessionPersistence.PlanItemEntry entry : saved.planItems()) {
                        items.add(new ClientSessionState.PlanItem(entry.step(), entry.status()));
                    }
                }
                ClientSessionState.setPlan(saved.planExplanation(), items);
            }
            if (saved.pendingChoice() != null && saved.pendingChoice().options() != null && !saved.pendingChoice().options().isEmpty()) {
                List<ClientSessionState.ChoiceOption> options = new ArrayList<>();
                for (SessionPersistence.ChoiceOptionEntry entry : saved.pendingChoice().options()) {
                    if (entry == null) {
                        continue;
                    }
                    options.add(new ClientSessionState.ChoiceOption(entry.id(), entry.label(), entry.description()));
                }
                ClientSessionState.setPendingChoice(saved.pendingChoice().requestId(), saved.pendingChoice().prompt(), options);
            }
            ClientSessionState.setStatus("restored");
        });

        String payload = buildStartPayload(session);
        session.serverSessionStarted = ClientServerBridge.sendSessionAction("start", payload);
        if (!session.serverSessionStarted) {
            synchronized (LOCK) {
                if (currentSession == session) {
                    currentSession = null;
                }
            }
            postToClient(ClientAgentManager::clearLocalSessionView);
        }
    }

    public static void newSession() {
        synchronized (LOCK) {
            if (currentSession != null && currentSession.inFlight) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Cannot start new session while agent is running.",
                        false,
                        "busy"
                ));
                return;
            }
        }
        closeCurrentSessionInternal(true);
    }

    public static void closeCurrentSession() {
        synchronized (LOCK) {
            if (currentSession != null && currentSession.inFlight) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Cannot close session while agent is running.",
                        false,
                        "busy"
                ));
                return;
            }
        }
        closeCurrentSessionInternal(true);
    }

    public static void onProjectChanged() {
        synchronized (LOCK) {
            if (currentSession != null && currentSession.inFlight) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        "Cannot switch project while agent is running.",
                        false,
                        "busy"
                ));
                return;
            }
        }
        closeCurrentSessionInternal(false);
        String projectId = ClientSessionState.getProjectId();
        if (projectId != null && !projectId.isBlank()) {
            restoreLatestSession(projectId, false);
        }
    }

    private static void closeCurrentSessionInternal(boolean clearStatus) {
        LocalSession previous;
        synchronized (LOCK) {
            previous = currentSession;
            currentSession = null;
        }
        if (previous != null) {
            autoSaveSession(previous);
            if (previous.serverSessionStarted) {
                ClientServerBridge.sendSessionAction("end", "");
            }
        }
        postToClient(() -> {
            clearLocalSessionView();
            if (clearStatus) {
                ClientSessionState.setStatus("");
            }
        });
    }

    private static void clearLocalSessionView() {
        ClientSessionState.clearMessages();
        ClientSessionState.clearPlan();
        ClientSessionState.clearPendingChoice();
        ClientSessionState.clearPendingPatch();
        ClientSessionState.endStreaming();
    }

    private static String buildStartPayload(LocalSession session) {
        JsonObject json = new JsonObject();
        if (session != null && session.id != null && !session.id.isBlank()) {
            json.addProperty("sessionId", session.id);
        }
        String projectId = session == null ? "" : session.projectId;
        if ((projectId == null || projectId.isBlank()) && ClientSessionState.hasProject()) {
            projectId = ClientSessionState.getProjectId();
        }
        if (projectId != null && !projectId.isBlank()) {
            json.addProperty("projectId", projectId);
        }
        String selectedWorkspacePath = session == null ? "" : session.selectedWorkspacePath;
        if ((selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) && ClientSessionState.isActive()) {
            selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        }
        if (selectedWorkspacePath != null && !selectedWorkspacePath.isBlank()) {
            json.addProperty("selectedWorkspacePath", selectedWorkspacePath);
        }
        return GSON.toJson(json);
    }

    private static LocalSession ensureSessionLocked() {
        if (!ClientServerBridge.hasRequiredServer()) {
            ClientServerBridge.notifyMissingServer();
            return null;
        }
        if (currentSession != null) {
            return currentSession;
        }
        String projectId = ClientSessionState.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "No project is open. Open or create a project first.",
                    false,
                    "error"
            ));
            return null;
        }

        LocalSession session = new LocalSession();
        String existingSessionId = ClientSessionState.isActive() ? ClientSessionState.getSessionId() : "";
        session.id = existingSessionId == null || existingSessionId.isBlank() ? UUID.randomUUID().toString() : existingSessionId;
        session.history = new ArrayList<>();
        session.inFlight = false;
        session.createdAt = System.currentTimeMillis();
        session.projectId = projectId;
        session.selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", P2SClientConfig.getSystemPrompt() + "\n\n" + CLIENT_TOOL_CONTRACT);
        session.history.add(system);

        currentSession = session;
        String payload = buildStartPayload(session);
        session.serverSessionStarted = ClientServerBridge.sendSessionAction("start", payload);
        if (!session.serverSessionStarted) {
            currentSession = null;
            return null;
        }
        return session;
    }

    private static void postToClient(Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || action == null) {
            return;
        }
        mc.execute(action);
    }

    private static final class LocalSession {
        String id;
        boolean inFlight;
        boolean serverSessionStarted;
        long createdAt;
        List<JsonObject> history;
        String projectId = "";
        String selectedWorkspacePath = "";
    }
}
