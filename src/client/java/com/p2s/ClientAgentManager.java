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
import java.util.List;
import java.util.UUID;
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
    private static final int MAX_AGENT_LOOPS = 6;
    private static final String CLIENT_TOOL_CONTRACT = """
            ## Client Agent Contract
            - Use list_skills to inspect available player skills by name/description.
            - Read full skill text only when needed via read_skill.
            - Use search_skill to locate relevant snippets quickly before reading full body.
            - Use read_workspace_state first when structure/revision context is required.
            - Propose edits with propose_patch and wait for user apply/discard decision.
            - Use search_block_ids when unsure about block id names.
            """;

    private static final Object LOCK = new Object();
    private static LocalSession currentSession;

    private ClientAgentManager() {
    }

    public static void submitUserMessage(String text) {
        String msg = text == null ? "" : text.trim();
        if (msg.isBlank()) {
            return;
        }
        if (ClientSessionState.hasPendingPatch()) {
            postToClient(() -> ClientSessionState.onChatResponse(
                    "Pending patch awaiting decision. Use Apply or Discard first.",
                    false,
                    "awaiting_confirm"
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

        postToClient(() -> {
            ClientSessionState.addUserMessage(msg);
            ClientSessionState.setStatus("planning");
        });

        AGENT_EXECUTOR.execute(() -> runAgentLoop(session));
    }

    private static void runAgentLoop(LocalSession session) {
        int remaining = MAX_AGENT_LOOPS;
        try {
            while (remaining > 0) {
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
                LLMService.SessionResult result = LLMService.requestWithHistoryWithSkills(
                                snapshot,
                                P2SClientConfig.llmRequestConfig()
                        )
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .join();

                boolean continueLoop = handleResult(session, result, remaining);
                if (!continueLoop) {
                    break;
                }
                remaining -= 1;
            }
            if (remaining <= 0) {
                postToClient(() -> ClientSessionState.setStatus("error"));
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
        }
    }

    private static boolean handleResult(LocalSession session, LLMService.SessionResult result, int remaining) {
        if (result == null) {
            postToClient(() -> ClientSessionState.onChatResponse("Request failed: empty response", false, "error"));
            return false;
        }

        synchronized (LOCK) {
            if (result.rawAssistantMessage() != null) {
                session.history.add(result.rawAssistantMessage().deepCopy());
                trimHistoryLocked(session);
            }
        }

        List<LLMService.ToolCall> toolCalls = result.toolCalls() == null ? List.of() : result.toolCalls();
        if (!toolCalls.isEmpty()) {
            for (LLMService.ToolCall call : toolCalls) {
                JsonObject payload = executeToolCall(call);
                JsonObject toolMsg = buildToolMessage(call, payload);
                synchronized (LOCK) {
                    session.history.add(toolMsg);
                    trimHistoryLocked(session);
                }
            }

            String text = result.textContent();
            if (text != null && !text.isBlank()) {
                postToClient(() -> ClientSessionState.onChatResponse(
                        text,
                        false,
                        ClientSessionState.hasPendingPatch() ? "awaiting_confirm" : "thinking"
                ));
            } else {
                postToClient(() -> ClientSessionState.setStatus(
                        ClientSessionState.hasPendingPatch() ? "awaiting_confirm" : "thinking"
                ));
            }
            return remaining > 1;
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

    private static JsonObject executeToolCall(LLMService.ToolCall call) {
        if (call == null || call.name() == null) {
            return toolError("", "Invalid tool call");
        }

        String toolName = call.name();
        return switch (toolName) {
            case "list_skills" -> listSkillsPayload();
            case "read_skill" -> readSkillPayload(call.arguments());
            case "search_skill" -> searchSkillPayload(call.arguments());
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

    private static LocalSession ensureSessionLocked() {
        if (currentSession != null) {
            return currentSession;
        }
        LocalSession session = new LocalSession();
        session.id = UUID.randomUUID().toString();
        session.history = new ArrayList<>();
        session.inFlight = false;

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", P2SClientConfig.getSystemPrompt() + "\n\n" + CLIENT_TOOL_CONTRACT);
        session.history.add(system);

        currentSession = session;
        if (!session.serverSessionStarted) {
            ClientPlayNetworking.send(new C2SSessionActionPayload("start", ""));
            session.serverSessionStarted = true;
        }
        return session;
    }

    private static void trimHistoryLocked(LocalSession session) {
        while (session.history.size() > MAX_HISTORY) {
            if (session.history.size() <= 1) {
                break;
            }
            session.history.remove(1);
        }
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
        List<JsonObject> history;
    }
}
