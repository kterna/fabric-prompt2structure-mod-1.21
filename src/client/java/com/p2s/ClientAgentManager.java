package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.network.C2SSessionActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
    private static final int MAX_TODO_ITEMS = 40;
    private static final int MAX_CHOICE_OPTIONS = 3;
    private static final String CLIENT_TOOL_CONTRACT = """
            ## Client Agent Contract
            - Use list_skills to inspect available player skills by name/description.
            - Read full skill text only when needed via read_skill.
            - Read focused skill sub-documents via read_subdoc when read_skill exposes subdocs.
            - Use search_skill to locate relevant snippets quickly before reading full body.
            - Keep an explicit todo list with the todo tool (actions: get/set/upsert/delete/clear).
            - Before asking the user to choose among alternatives, call request_user_choice.
            - After request_user_choice, wait for user selection before continuing execution.
            - Use read_workspace_state first when workspace size/current blocks context is required.
            - Propose edits with propose_patch and wait for user apply/discard decision.
            - Use search_block_ids when unsure about block id names.
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
            "todo", "get_todo",
            "read_workspace_state", "search_block_ids", "explain_plan",
            "list_subagents", "get_subagent", "list_profiles", "get_profile"
    );

    private static final Object LOCK = new Object();
    private static LocalSession currentSession;

    private ClientAgentManager() {
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
            if (session.inFlight) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            session.inFlight = true;
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", msg);
            session.history.add(user);
            trimHistoryLocked(session);
        }

        String finalVisible = visible;
        postToClient(() -> {
            ClientSessionState.addUserMessage(finalVisible);
            ClientSessionState.setStatus("planning");
        });

        AGENT_EXECUTOR.execute(() -> runAgentLoop(session));
    }

    public static void submitPatchApply() {
        if (!ClientSessionState.hasPendingPatch()) {
            return;
        }
        String summary = ClientSessionState.getPendingSummary();
        ClientSessionState.clearPendingPatch();

        ClientPlayNetworking.send(new C2SSessionActionPayload("apply", ""));

        String historyContent = "User APPLIED the proposed patch. Summary: " + summary + " Continue with the next step.";
        String userMessageText = "Applied patch: " + summary;

        LocalSession session;
        synchronized (LOCK) {
            session = ensureSessionLocked();
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
        ClientSessionState.clearPendingPatch();

        String safeReason = reason == null ? "" : reason.trim();
        ClientPlayNetworking.send(new C2SSessionActionPayload("discard", safeReason));

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
            postToClient(() -> ClientSessionState.setStatus("Invalid choice option"));
            return;
        }
        ClientSessionState.clearPendingChoice();

        String userMessageText = "Choice selected [" + selected.id() + "]: " + selected.label();
        LocalSession session;
        synchronized (LOCK) {
            session = ensureSessionLocked();
            if (session.inFlight) {
                postToClient(() -> ClientSessionState.setStatus("busy"));
                return;
            }
            session.inFlight = true;

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "User selected option " + selected.id()
                    + " (" + selected.label() + ") for request: " + choice.prompt());
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
                        ModConfig.SESSION_JOB_TIMEOUT_SECONDS,
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
            List<ToolCallResult> results = executeToolCallsBatch(session, toolCalls);
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

            String text = result.textContent();
            if (text != null && !text.isBlank()) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        text,
                        false,
                        statusAfterToolCalls()
                ));
            } else {
                postToClient(() -> ClientSessionState.setStatus(statusAfterToolCalls()));
            }

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
            case "todo" -> todoPayload(call.arguments());
            case "get_todo" -> todoPayload(buildTodoActionArgs("get", call.arguments()));
            case "set_todo" -> todoPayload(buildTodoActionArgs("set", call.arguments()));
            case "edit_todo_item" -> todoPayload(buildTodoActionArgs("upsert", call.arguments()));
            case "delete_todo_item" -> todoPayload(buildTodoActionArgs("delete", call.arguments()));
            case "clear_todo" -> todoPayload(buildTodoActionArgs("clear", call.arguments()));
            case "request_user_choice" -> requestUserChoicePayload(call.arguments());
            case "clear_user_choice" -> clearUserChoicePayload();
            case "list_subagents" -> listSubagentsPayload(session, call.arguments());
            case "create_subagent" -> createSubagentPayload(session, call.arguments());
            case "continue_subagent" -> continueSubagentPayload(session, call.arguments());
            case "get_subagent" -> getSubagentPayload(session, call.arguments());
            case "delete_subagent" -> deleteSubagentPayload(session, call.arguments());
            case "list_profiles" -> SubagentManager.listProfiles();
            case "get_profile" -> getProfilePayload(call.arguments());
            case "explain_plan" -> {
                JsonObject ok = toolOk(toolName);
                ok.addProperty("accepted", true);
                yield ok;
            }
            case "read_workspace_state", "propose_patch", "search_block_ids" ->
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

    private static JsonObject todoPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String action = normalizeTodoAction(asString(args, "action"));
        if (action.isBlank()) {
            action = inferLegacyTodoAction(args);
        }
        if (action.isBlank()) {
            return toolError("todo", "Missing action (get/set/upsert/delete/clear)");
        }

        return switch (action) {
            case "get" -> todoGetPayload();
            case "set" -> todoSetPayload(args);
            case "upsert" -> todoUpsertPayload(args);
            case "delete" -> todoDeletePayload(args);
            case "clear" -> todoClearPayload();
            default -> toolError("todo", "Unsupported action: " + action);
        };
    }

    private static JsonObject todoGetPayload() {
        JsonObject payload = toolOk("todo");
        payload.addProperty("action", "get");
        payload.addProperty("title", ClientSessionState.getTodoTitle());
        JsonArray arr = new JsonArray();
        for (ClientSessionState.TodoItem item : ClientSessionState.getTodoItems()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", item.id());
            entry.addProperty("content", item.content());
            entry.addProperty("status", item.status());
            arr.add(entry);
        }
        payload.add("items", arr);
        payload.addProperty("count", arr.size());
        return payload;
    }

    private static JsonObject todoSetPayload(JsonObject args) {
        if (!args.has("items") || !args.get("items").isJsonArray()) {
            return toolError("todo", "Missing items array for action=set");
        }
        String title = asString(args, "title");
        JsonArray itemsArg = args.getAsJsonArray("items");
        List<ClientSessionState.TodoItem> items = new ArrayList<>();
        int index = 1;
        for (JsonElement element : itemsArg) {
            if (items.size() >= MAX_TODO_ITEMS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String id = normalizeTodoId(asString(obj, "id"));
            if (id.isBlank()) {
                id = "todo-" + index;
            }
            String content = asString(obj, "content");
            if (content.isBlank()) {
                content = asString(obj, "text");
            }
            if (content.isBlank()) {
                index += 1;
                continue;
            }
            String status = normalizeTodoStatus(asString(obj, "status"));
            items.add(new ClientSessionState.TodoItem(id, content.trim(), status));
            index += 1;
        }

        ClientSessionState.setTodo(title, items);
        JsonObject payload = toolOk("todo");
        payload.addProperty("action", "set");
        payload.addProperty("title", title == null ? "" : title.trim());
        payload.addProperty("count", items.size());
        return payload;
    }

    private static JsonObject todoUpsertPayload(JsonObject args) {
        String id = normalizeTodoId(asString(args, "id"));
        if (id.isBlank()) {
            return toolError("todo", "Missing id for action=upsert");
        }
        String content = asString(args, "content");
        if (content.isBlank()) {
            content = asString(args, "text");
        }
        String status = asString(args, "status");
        boolean exists = ClientSessionState.getTodoItems().stream().anyMatch(item -> id.equals(item.id()));

        boolean ok = ClientSessionState.upsertTodoItem(id, content, status);
        if (!ok) {
            return toolError("todo", "Cannot create item without content");
        }

        JsonObject payload = toolOk("todo");
        payload.addProperty("action", "upsert");
        payload.addProperty("id", id);
        payload.addProperty("result", exists ? "updated" : "created");
        return payload;
    }

    private static JsonObject todoDeletePayload(JsonObject args) {
        String id = normalizeTodoId(asString(args, "id"));
        if (id.isBlank()) {
            return toolError("todo", "Missing id for action=delete");
        }
        boolean removed = ClientSessionState.removeTodoItem(id);
        if (!removed) {
            return toolError("todo", "Todo item not found: " + id);
        }
        JsonObject payload = toolOk("todo");
        payload.addProperty("action", "delete");
        payload.addProperty("id", id);
        return payload;
    }

    private static JsonObject todoClearPayload() {
        ClientSessionState.clearTodo();
        JsonObject payload = toolOk("todo");
        payload.addProperty("action", "clear");
        payload.addProperty("cleared", true);
        return payload;
    }

    private static String normalizeTodoAction(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String action = value.trim().toLowerCase();
        return switch (action) {
            case "get", "set", "upsert", "delete", "clear" -> action;
            case "edit", "update", "create" -> "upsert";
            case "remove" -> "delete";
            default -> "";
        };
    }

    private static String inferLegacyTodoAction(JsonObject args) {
        if (args == null) {
            return "";
        }
        if (args.has("items") && args.get("items").isJsonArray()) {
            return "set";
        }
        if (args.has("id") && args.get("id").isJsonPrimitive()) {
            if (args.has("content") || args.has("text") || args.has("status")) {
                return "upsert";
            }
            return "delete";
        }
        return "get";
    }

    private static JsonElement buildTodoActionArgs(String action, JsonElement originalArgs) {
        JsonObject args = normalizeArgsObject(originalArgs);
        args.addProperty("action", action == null ? "" : action.trim().toLowerCase());
        return args;
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
                id = normalizeTodoId(asString(obj, "id"));
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

    private static String normalizeTodoId(String raw) {
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

    private static String normalizeTodoStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "pending";
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "pending", "in_progress", "done", "blocked" -> value;
            default -> "pending";
        };
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

    private static JsonObject buildToolMessage(LLMService.ToolCall call, JsonObject payload) {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        if (call != null && call.id() != null && !call.id().isBlank()) {
            toolMsg.addProperty("tool_call_id", call.id());
        }
        toolMsg.addProperty("content", payload == null ? "{}" : GSON.toJson(payload));
        return toolMsg;
    }

    private static String sessionId(LocalSession session) {
        if (session == null || session.id == null || session.id.isBlank()) {
            return "default";
        }
        return session.id;
    }

    private static LocalSession ensureSessionLocked() {
        if (currentSession != null) {
            return currentSession;
        }
        LocalSession session = new LocalSession();
        session.id = UUID.randomUUID().toString();
        session.history = new ArrayList<>();
        session.inFlight = false;
        session.createdAt = System.currentTimeMillis();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", P2SClientConfig.getSystemPrompt() + "\n\n" + CLIENT_TOOL_CONTRACT);
        session.history.add(system);

        currentSession = session;
        if (!session.serverSessionStarted) {
            String payload = buildStartPayload(session);
            ClientPlayNetworking.send(new C2SSessionActionPayload("start", payload));
            session.serverSessionStarted = true;
        }
        return session;
    }

    private static String buildStartPayload(LocalSession session) {
        boolean hasSpatial = session.originX != 0 || session.originY != 0 || session.originZ != 0 || session.hasSize;
        boolean hasScript = session.currentScriptJson != null && !session.currentScriptJson.isBlank();
        if (!hasSpatial && !hasScript) {
            return "";
        }
        JsonObject json = new JsonObject();
        json.addProperty("originX", session.originX);
        json.addProperty("originY", session.originY);
        json.addProperty("originZ", session.originZ);
        json.addProperty("hasSize", session.hasSize);
        json.addProperty("sizeX", session.sizeX);
        json.addProperty("sizeY", session.sizeY);
        json.addProperty("sizeZ", session.sizeZ);
        if (hasScript) {
            json.addProperty("currentScriptJson", session.currentScriptJson);
        }
        return GSON.toJson(json);
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
        List<JsonObject> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (JsonObject msg : source) {
            copy.add(msg == null ? new JsonObject() : msg.deepCopy());
        }
        return copy;
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

    private static void syncSpatialFromClientState(LocalSession session) {
        if (session == null) {
            return;
        }
        if (ClientSessionState.isActive()) {
            session.originX = ClientSessionState.getOriginX();
            session.originY = ClientSessionState.getOriginY();
            session.originZ = ClientSessionState.getOriginZ();
            session.hasSize = ClientSessionState.hasSize();
            session.sizeX = ClientSessionState.getSizeX();
            session.sizeY = ClientSessionState.getSizeY();
            session.sizeZ = ClientSessionState.getSizeZ();
        }
    }

    private static void autoSaveSession(LocalSession session) {
        if (session == null || session.id == null || session.id.isBlank()) {
            return;
        }
        syncSpatialFromClientState(session);
        try {
            List<JsonObject> historyCopy;
            synchronized (LOCK) {
                historyCopy = deepCopyMessages(session.history);
            }

            List<ClientSessionState.ChatMessage> chatMessages = ClientSessionState.getMessages();
            List<SessionPersistence.ChatMessageEntry> chatLog = new ArrayList<>();
            for (ClientSessionState.ChatMessage msg : chatMessages) {
                chatLog.add(new SessionPersistence.ChatMessageEntry(msg.role(), msg.text()));
            }

            List<ClientSessionState.TodoItem> todoItems = ClientSessionState.getTodoItems();
            List<SessionPersistence.TodoItemEntry> todoEntries = new ArrayList<>();
            for (ClientSessionState.TodoItem item : todoItems) {
                todoEntries.add(new SessionPersistence.TodoItemEntry(item.id(), item.content(), item.status()));
            }

            String title = "";
            for (ClientSessionState.ChatMessage msg : chatMessages) {
                if ("You".equals(msg.role()) && msg.text() != null && !msg.text().isBlank()) {
                    title = msg.text().trim();
                    if (title.length() > 60) {
                        title = title.substring(0, 57) + "...";
                    }
                    break;
                }
            }
            if (title.isBlank()) {
                title = "Session " + session.id.substring(0, 8);
            }

            SessionPersistence.SavedSession saved = new SessionPersistence.SavedSession(
                    session.id,
                    title,
                    session.createdAt,
                    System.currentTimeMillis(),
                    chatLog.size(),
                    historyCopy,
                    chatLog,
                    todoEntries,
                    ClientSessionState.getTodoTitle(),
                    session.originX,
                    session.originY,
                    session.originZ,
                    session.hasSize,
                    session.sizeX,
                    session.sizeY,
                    session.sizeZ,
                    ClientSessionState.getCurrentScriptJson()
            );

            CompletableFuture.runAsync(() -> SessionPersistence.saveSession(saved));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Auto-save session failed: {}", e.getMessage());
        }
    }

    public static void restoreSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
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

        synchronized (LOCK) {
            LocalSession session = new LocalSession();
            session.id = saved.id();
            session.history = new ArrayList<>(saved.llmHistory());
            session.inFlight = false;
            session.createdAt = saved.createdAt();
            session.serverSessionStarted = false;
            session.originX = saved.originX();
            session.originY = saved.originY();
            session.originZ = saved.originZ();
            session.hasSize = saved.hasSize();
            session.sizeX = saved.sizeX();
            session.sizeY = saved.sizeY();
            session.sizeZ = saved.sizeZ();
            session.currentScriptJson = saved.currentScriptJson() == null ? "" : saved.currentScriptJson();
            currentSession = session;
        }

        postToClient(() -> {
            ClientSessionState.clearMessages();
            ClientSessionState.clearTodo();
            ClientSessionState.clearPendingChoice();
            ClientSessionState.endStreaming();

            if (saved.chatLog() != null) {
                for (SessionPersistence.ChatMessageEntry entry : saved.chatLog()) {
                    ClientSessionState.addMessage(entry.role(), entry.text());
                }
            }

            if (saved.todoItems() != null && !saved.todoItems().isEmpty()) {
                List<ClientSessionState.TodoItem> items = new ArrayList<>();
                for (SessionPersistence.TodoItemEntry entry : saved.todoItems()) {
                    items.add(new ClientSessionState.TodoItem(entry.id(), entry.content(), entry.status()));
                }
                ClientSessionState.setTodo(saved.todoTitle(), items);
            }

            ClientSessionState.setStatus("restored");
        });
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
            if (currentSession != null) {
                autoSaveSession(currentSession);
            }
            currentSession = null;
        }

        postToClient(() -> {
            ClientSessionState.clearMessages();
            ClientSessionState.clearTodo();
            ClientSessionState.clearPendingChoice();
            ClientSessionState.endStreaming();
            ClientSessionState.setStatus("");
        });
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
        int originX, originY, originZ;
        boolean hasSize;
        int sizeX, sizeY, sizeZ;
        String currentScriptJson = "";
    }
}
