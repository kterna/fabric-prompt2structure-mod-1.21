package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LLMService {
    private static final Gson GSON = new Gson();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static volatile OkHttpClient CLIENT = buildClient(ModConfig.HTTP_TIMEOUT_SECONDS);
    private static volatile int CLIENT_TIMEOUT_SECONDS = ModConfig.HTTP_TIMEOUT_SECONDS;

    private LLMService() {
    }

    public static CompletableFuture<Result> requestStructure(String userPrompt) {
        return CompletableFuture.supplyAsync(() -> {
            String bodyJson = buildBodyForPrompt(userPrompt, ModConfig.MODEL, ModConfig.USE_TOOL_CALL, true);
            P2SMod.LOGGER.info("LLM request -> url={}, model={}, timeout={}s", ModConfig.API_URL, ModConfig.MODEL, ModConfig.HTTP_TIMEOUT_SECONDS);
            P2SMod.LOGGER.info("Active prompt preset: {}", ModConfig.activePromptName());
            P2SMod.LOGGER.info("LLM prompt: {}", userPrompt);
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] LLM full request body: {}", bodyJson);
            }
            Request request = new Request.Builder()
                    .url(ModConfig.API_URL)
                    .post(RequestBody.create(bodyJson, JSON))
                    .header("Authorization", "Bearer " + ModConfig.API_KEY)
                    .build();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] LLM request headers: method={}, url={}, headers={}", request.method(), request.url(), request.headers());
            }

            try (Response response = getClient(ModConfig.HTTP_TIMEOUT_SECONDS).newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() == null ? "" : response.body().string();
                    P2SMod.LOGGER.error("LLM failed status={}, body={}", response.code(), truncate(errBody));
                    throw new IOException("请求失败，状态码: " + response.code());
                }
                String respBody = response.body() == null ? "" : response.body().string();
                P2SMod.LOGGER.info("LLM raw response (truncated): {}", truncate(respBody));
                if (P2SMod.DEBUG) {
                    P2SMod.LOGGER.info("[DEBUG] LLM full response body: {}", respBody);
                }
                return parseResponse(respBody);
            } catch (Exception e) {
                throw new RuntimeException("LLM 请求异常: " + e.getMessage(), e);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<SessionResult> requestWithHistory(java.util.List<JsonObject> messages) {
        return requestWithHistoryInternal(messages, false, null, null);
    }

    public static CompletableFuture<SessionResult> requestWithHistoryWithSkills(java.util.List<JsonObject> messages) {
        return requestWithHistoryInternal(messages, true, null, null);
    }

    public static CompletableFuture<SessionResult> requestWithHistoryWithSkills(
            java.util.List<JsonObject> messages,
            RequestConfig config
    ) {
        return requestWithHistoryInternal(messages, true, config, null);
    }

    public static CompletableFuture<SessionResult> requestWithHistoryWithSkills(
            java.util.List<JsonObject> messages,
            RequestConfig config,
            Collection<String> allowedTools
    ) {
        return requestWithHistoryInternal(messages, true, config, allowedTools);
    }

    public interface StreamCallback {
        void onToken(String token);
        default void onComplete(SessionResult result) {}
        default void onError(String message) {}
    }

    public static CompletableFuture<SessionResult> requestWithHistoryStreaming(
            java.util.List<JsonObject> messages,
            RequestConfig overrideConfig,
            StreamCallback callback
    ) {
        return CompletableFuture.supplyAsync(() -> {
            RequestConfig cfg = resolveConfig(overrideConfig);
            Set<String> normalizedAllowedTools = null;
            String bodyJsonBase = buildBodyForMessages(messages, cfg.model(), cfg.useToolCall(), true, normalizedAllowedTools);

            JsonObject bodyObj = JsonParser.parseString(bodyJsonBase).getAsJsonObject();
            bodyObj.addProperty("stream", true);
            String bodyJson = GSON.toJson(bodyObj);

            int messageCount = messages == null ? 0 : messages.size();
            P2SMod.LOGGER.info("LLM streaming request -> url={}, model={}, timeout={}s, messages={}",
                    cfg.apiUrl(), cfg.model(), cfg.httpTimeoutSeconds(), messageCount);
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] LLM streaming full request body: {}", bodyJson);
            }

            Request request = new Request.Builder()
                    .url(cfg.apiUrl())
                    .post(RequestBody.create(bodyJson, JSON))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .build();

            try {
                Response response = getClient(cfg.httpTimeoutSeconds()).newCall(request).execute();
                try {
                    if (!response.isSuccessful()) {
                        String errBody = response.body() == null ? "" : response.body().string();
                        P2SMod.LOGGER.error("LLM streaming failed status={}, body={}", response.code(), truncate(errBody));
                        throw new IOException("请求失败，状态码: " + response.code());
                    }

                    String contentType = response.header("Content-Type", "");
                    if (!contentType.contains("text/event-stream") && !contentType.contains("text/plain")) {
                        P2SMod.LOGGER.info("LLM streaming fallback to non-stream (Content-Type: {})", contentType);
                        String respBody = response.body() == null ? "" : response.body().string();
                        SessionResult result = parseSessionResponse(respBody);
                        if (callback != null) {
                            callback.onComplete(result);
                        }
                        return result;
                    }

                    return readSSEStream(response, callback);
                } finally {
                    response.close();
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Unknown streaming error" : e.getMessage();
                if (callback != null) {
                    callback.onError(msg);
                }
                throw new RuntimeException("LLM 流式请求异常: " + msg, e);
            }
        }, EXECUTOR);
    }

    private static final class ToolCallAccumulator {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    private static SessionResult readSSEStream(Response response, StreamCallback callback) throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolAccumulators = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    if (P2SMod.DEBUG) {
                        P2SMod.LOGGER.info("[DEBUG] SSE stream received [DONE]");
                    }
                    break;
                }
                if (P2SMod.DEBUG) {
                    P2SMod.LOGGER.info("[DEBUG] SSE chunk: {}", data);
                }

                try {
                    JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }
                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    JsonObject delta = firstChoice.has("delta") ? firstChoice.getAsJsonObject("delta") : null;
                    if (delta == null) {
                        continue;
                    }

                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String token = delta.get("content").getAsString();
                        if (token != null && !token.isEmpty()) {
                            contentBuilder.append(token);
                            if (callback != null) {
                                callback.onToken(token);
                            }
                        }
                    }

                    if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                        JsonArray toolCallDeltas = delta.getAsJsonArray("tool_calls");
                        for (JsonElement tcElem : toolCallDeltas) {
                            if (!tcElem.isJsonObject()) {
                                continue;
                            }
                            JsonObject tcDelta = tcElem.getAsJsonObject();
                            int index = tcDelta.has("index") ? tcDelta.get("index").getAsInt() : 0;
                            ToolCallAccumulator acc = toolAccumulators.computeIfAbsent(index, k -> new ToolCallAccumulator());
                            if (tcDelta.has("id") && !tcDelta.get("id").isJsonNull()) {
                                acc.id = tcDelta.get("id").getAsString();
                            }
                            if (tcDelta.has("function") && tcDelta.get("function").isJsonObject()) {
                                JsonObject fnDelta = tcDelta.getAsJsonObject("function");
                                if (fnDelta.has("name") && !fnDelta.get("name").isJsonNull()) {
                                    acc.name = fnDelta.get("name").getAsString();
                                }
                                if (fnDelta.has("arguments") && !fnDelta.get("arguments").isJsonNull()) {
                                    acc.arguments.append(fnDelta.get("arguments").getAsString());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    P2SMod.LOGGER.debug("SSE chunk parse error: {}", e.getMessage());
                }
            }
        }

        String textContent = contentBuilder.toString();
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonObject assembledMessage = new JsonObject();
        assembledMessage.addProperty("role", "assistant");

        if (!textContent.isEmpty()) {
            assembledMessage.addProperty("content", textContent);
        } else {
            assembledMessage.add("content", com.google.gson.JsonNull.INSTANCE);
        }

        if (!toolAccumulators.isEmpty()) {
            JsonArray toolCallsArray = new JsonArray();
            List<Integer> indices = new ArrayList<>(toolAccumulators.keySet());
            indices.sort(Integer::compareTo);
            for (int idx : indices) {
                ToolCallAccumulator acc = toolAccumulators.get(idx);
                JsonElement argsElem;
                try {
                    argsElem = JsonParser.parseString(acc.arguments.toString());
                } catch (Exception e) {
                    argsElem = new com.google.gson.JsonPrimitive(acc.arguments.toString());
                }
                toolCalls.add(new ToolCall(acc.id, acc.name, argsElem));

                JsonObject tcObj = new JsonObject();
                tcObj.addProperty("id", acc.id);
                tcObj.addProperty("type", "function");
                JsonObject fnObj = new JsonObject();
                fnObj.addProperty("name", acc.name);
                fnObj.addProperty("arguments", acc.arguments.toString());
                tcObj.add("function", fnObj);
                toolCallsArray.add(tcObj);
            }
            assembledMessage.add("tool_calls", toolCallsArray);
        }

        int textLen = textContent.length();
        P2SMod.LOGGER.info("LLM streaming complete -> toolCalls={}, textLen={}", toolCalls.size(), textLen);

        SessionResult result = new SessionResult(
                textContent.isEmpty() ? null : textContent,
                null,
                assembledMessage,
                toolCalls
        );
        if (callback != null) {
            callback.onComplete(result);
        }
        return result;
    }

    private static CompletableFuture<SessionResult> requestWithHistoryInternal(
            java.util.List<JsonObject> messages,
            boolean includeSkillTools,
            RequestConfig overrideConfig,
            Collection<String> allowedTools
    ) {
        return CompletableFuture.supplyAsync(() -> {
            RequestConfig cfg = resolveConfig(overrideConfig);
            Set<String> normalizedAllowedTools = normalizeAllowedToolNames(allowedTools);
            String bodyJson = buildBodyForMessages(messages, cfg.model(), cfg.useToolCall(), includeSkillTools, normalizedAllowedTools);
            int messageCount = messages == null ? 0 : messages.size();
            int payloadBytes = bodyJson == null ? 0 : bodyJson.length();
            P2SMod.LOGGER.info("LLM session request -> url={}, model={}, timeout={}s", cfg.apiUrl(), cfg.model(), cfg.httpTimeoutSeconds());
            P2SMod.LOGGER.info("LLM session payload -> messages={}, bytes={}, toolCall={}, toolWhitelist={}",
                    messageCount,
                    payloadBytes,
                    cfg.useToolCall(),
                    normalizedAllowedTools == null ? "all" : normalizedAllowedTools.size());
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] LLM session full request body: {}", bodyJson);
                P2SMod.LOGGER.info("[DEBUG] LLM session messages array: {}", GSON.toJson(messages));
            }
            Request request = new Request.Builder()
                    .url(cfg.apiUrl())
                    .post(RequestBody.create(bodyJson, JSON))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .build();

            try (Response response = getClient(cfg.httpTimeoutSeconds()).newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() == null ? "" : response.body().string();
                    P2SMod.LOGGER.error("LLM failed status={}, body={}", response.code(), truncate(errBody));
                    throw new IOException("请求失败，状态码: " + response.code());
                }
                String respBody = response.body() == null ? "" : response.body().string();
                P2SMod.LOGGER.info("LLM raw response (truncated): {}", truncate(respBody));
                if (P2SMod.DEBUG) {
                    P2SMod.LOGGER.info("[DEBUG] LLM session full response body: {}", respBody);
                }
                return parseSessionResponse(respBody);
            } catch (Exception e) {
                throw new RuntimeException("LLM 请求异常: " + e.getMessage(), e);
            }
        }, EXECUTOR);
    }

    private static String buildBodyForPrompt(String userPrompt, String model, boolean useToolCall, boolean forceTool) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", ModConfig.currentSystemPrompt());
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        body.add("messages", messages);
        body.addProperty("temperature", 0.4);
        applyLegacyToolOrResponseFormat(body, useToolCall, forceTool);
        return GSON.toJson(body);
    }

    private static String buildBodyForMessages(
            java.util.List<JsonObject> messages,
            String model,
            boolean useToolCall,
            boolean includeSkillTools,
            Set<String> allowedTools
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", GSON.toJsonTree(messages));
        body.addProperty("temperature", 0.4);
        applySessionToolOrResponseFormat(body, useToolCall, includeSkillTools, allowedTools);
        return GSON.toJson(body);
    }

    private static void applyLegacyToolOrResponseFormat(JsonObject body, boolean useToolCall, boolean forceTool) {
        if (useToolCall) {
            body.add("tools", buildLegacyToolDefinitions());
            if (forceTool) {
                JsonObject toolChoice = new JsonObject();
                toolChoice.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", "apply_structure");
                toolChoice.add("function", function);
                body.add("tool_choice", toolChoice);
            } else {
                body.addProperty("tool_choice", "auto");
            }
        } else {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }
    }

    private static void applySessionToolOrResponseFormat(
            JsonObject body,
            boolean useToolCall,
            boolean includeSkillTools,
            Set<String> allowedTools
    ) {
        if (useToolCall) {
            body.add("tools", buildSessionToolDefinitions(includeSkillTools, allowedTools));
            body.addProperty("tool_choice", "auto");
            return;
        }
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
    }

    private static JsonArray buildLegacyToolDefinitions() {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");

        JsonObject function = new JsonObject();
        function.addProperty("name", "apply_structure");
        function.addProperty("description", "Apply a structure definition to the Minecraft world. Call this tool to build or modify blocks in the selected area.");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject palette = new JsonObject();
        palette.addProperty("type", "object");
        palette.addProperty("description", "Block palette mapping short names to Minecraft block IDs");
        JsonObject paletteAdditional = new JsonObject();
        paletteAdditional.addProperty("type", "string");
        palette.add("additionalProperties", paletteAdditional);
        properties.add("palette", palette);

        JsonObject structures = new JsonObject();
        structures.addProperty("type", "array");
        structures.addProperty("description", "Array of structure parts, each with a name, priority, and actions");
        JsonObject structuresItems = new JsonObject();
        structuresItems.addProperty("type", "object");
        JsonObject structuresProps = new JsonObject();
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Name of this structure part (e.g. 'foundation', 'walls', 'roof')");
        structuresProps.add("name", name);
        JsonObject priority = new JsonObject();
        priority.addProperty("type", "integer");
        priority.addProperty("description", "Build priority (lower = built first). Parts with lower priority are built first, higher priority parts can overwrite lower ones.");
        structuresProps.add("priority", priority);

        JsonObject actions = new JsonObject();
        actions.addProperty("type", "array");
        JsonObject actionsItems = new JsonObject();
        actionsItems.addProperty("type", "object");
        JsonObject actionProps = new JsonObject();

        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        JsonArray typeEnum = new JsonArray();
        typeEnum.add("box");
        typeEnum.add("plane");
        typeEnum.add("line");
        typeEnum.add("points");
        type.add("enum", typeEnum);
        actionProps.add("type", type);

        JsonObject block = new JsonObject();
        block.addProperty("type", "string");
        actionProps.add("block", block);

        JsonObject from = new JsonObject();
        from.addProperty("type", "array");
        JsonObject fromItems = new JsonObject();
        fromItems.addProperty("type", "integer");
        from.add("items", fromItems);
        from.addProperty("minItems", 3);
        from.addProperty("maxItems", 3);
        actionProps.add("from", from);

        JsonObject to = new JsonObject();
        to.addProperty("type", "array");
        JsonObject toItems = new JsonObject();
        toItems.addProperty("type", "integer");
        to.add("items", toItems);
        to.addProperty("minItems", 3);
        to.addProperty("maxItems", 3);
        actionProps.add("to", to);

        JsonObject at = new JsonObject();
        at.addProperty("type", "array");
        JsonObject atItems = new JsonObject();
        atItems.addProperty("type", "array");
        JsonObject atItemItems = new JsonObject();
        atItemItems.addProperty("type", "integer");
        atItems.add("items", atItemItems);
        atItems.addProperty("minItems", 3);
        atItems.addProperty("maxItems", 3);
        at.add("items", atItems);
        actionProps.add("at", at);

        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        JsonArray modeEnum = new JsonArray();
        modeEnum.add("solid");
        modeEnum.add("shell");
        modeEnum.add("walls");
        modeEnum.add("outline");
        mode.add("enum", modeEnum);
        actionProps.add("mode", mode);

        JsonObject axis = new JsonObject();
        axis.addProperty("type", "string");
        JsonArray axisEnum = new JsonArray();
        axisEnum.add("x");
        axisEnum.add("y");
        axisEnum.add("z");
        axis.add("enum", axisEnum);
        actionProps.add("axis", axis);

        JsonObject facing = new JsonObject();
        facing.addProperty("type", "string");
        JsonArray facingEnum = new JsonArray();
        facingEnum.add("north");
        facingEnum.add("south");
        facingEnum.add("east");
        facingEnum.add("west");
        facingEnum.add("up");
        facingEnum.add("down");
        facing.add("enum", facingEnum);
        actionProps.add("facing", facing);

        actionsItems.add("properties", actionProps);
        JsonArray actionRequired = new JsonArray();
        actionRequired.add("type");
        actionRequired.add("block");
        actionsItems.add("required", actionRequired);
        actionsItems.addProperty("additionalProperties", false);
        actions.add("items", actionsItems);
        structuresProps.add("actions", actions);

        structuresItems.add("properties", structuresProps);
        JsonArray structuresRequired = new JsonArray();
        structuresRequired.add("name");
        structuresRequired.add("priority");
        structuresRequired.add("actions");
        structuresItems.add("required", structuresRequired);
        structures.add("items", structuresItems);
        properties.add("structures", structures);

        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("palette");
        required.add("structures");
        parameters.add("required", required);
        function.add("parameters", parameters);

        tool.add("function", function);

        JsonObject getTool = new JsonObject();
        getTool.addProperty("type", "function");
        JsonObject getFunction = new JsonObject();
        getFunction.addProperty("name", "get_current_structure");
        getFunction.addProperty("description", "Read the current structure JSON for the active session.");
        JsonObject getParameters = new JsonObject();
        getParameters.addProperty("type", "object");
        getParameters.add("properties", new JsonObject());
        getParameters.addProperty("additionalProperties", false);
        getFunction.add("parameters", getParameters);
        getTool.add("function", getFunction);

        JsonArray tools = new JsonArray();
        tools.add(tool);
        tools.add(getTool);
        return tools;
    }

    private static JsonArray buildSessionToolDefinitions(boolean includeSkillTools, Set<String> allowedTools) {
        JsonArray tools = new JsonArray();

        if (includeSkillTools && allowsTool(allowedTools, "list_skills")) {
            JsonObject listSkillTool = new JsonObject();
            listSkillTool.addProperty("type", "function");
            JsonObject listSkillFn = new JsonObject();
            listSkillFn.addProperty("name", "list_skills");
            listSkillFn.addProperty("description", "List available player skills with short metadata only.");
            JsonObject listSkillParams = new JsonObject();
            listSkillParams.addProperty("type", "object");
            listSkillParams.add("properties", new JsonObject());
            listSkillParams.addProperty("additionalProperties", false);
            listSkillFn.add("parameters", listSkillParams);
            listSkillTool.add("function", listSkillFn);
            tools.add(listSkillTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "read_skill")) {
            JsonObject readSkillTool = new JsonObject();
            readSkillTool.addProperty("type", "function");
            JsonObject readSkillFn = new JsonObject();
            readSkillFn.addProperty("name", "read_skill");
            readSkillFn.addProperty("description", "Read one skill document by id. If id omitted, the active skill may be used.");
            JsonObject readSkillParams = new JsonObject();
            readSkillParams.addProperty("type", "object");
            JsonObject readSkillProps = new JsonObject();
            JsonObject skillId = new JsonObject();
            skillId.addProperty("type", "string");
            skillId.addProperty("description", "Skill id from list_skills.");
            readSkillProps.add("id", skillId);
            readSkillParams.add("properties", readSkillProps);
            readSkillParams.addProperty("additionalProperties", false);
            readSkillFn.add("parameters", readSkillParams);
            readSkillTool.add("function", readSkillFn);
            tools.add(readSkillTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "read_subdoc")) {
            JsonObject readSubdocTool = new JsonObject();
            readSubdocTool.addProperty("type", "function");
            JsonObject readSubdocFn = new JsonObject();
            readSubdocFn.addProperty("name", "read_subdoc");
            readSubdocFn.addProperty("description", "Read one markdown sub-document inside a skill directory by relative path.");
            JsonObject readSubdocParams = new JsonObject();
            readSubdocParams.addProperty("type", "object");
            JsonObject readSubdocProps = new JsonObject();
            JsonObject readSubdocId = new JsonObject();
            readSubdocId.addProperty("type", "string");
            readSubdocId.addProperty("description", "Optional skill id from list_skills. Defaults to active skill.");
            readSubdocProps.add("id", readSubdocId);
            JsonObject readSubdocPath = new JsonObject();
            readSubdocPath.addProperty("type", "string");
            readSubdocPath.addProperty("description", "Relative markdown path, for example subdocs/roof-gable.md.");
            readSubdocProps.add("path", readSubdocPath);
            readSubdocParams.add("properties", readSubdocProps);
            JsonArray readSubdocRequired = new JsonArray();
            readSubdocRequired.add("path");
            readSubdocParams.add("required", readSubdocRequired);
            readSubdocParams.addProperty("additionalProperties", false);
            readSubdocFn.add("parameters", readSubdocParams);
            readSubdocTool.add("function", readSubdocFn);
            tools.add(readSubdocTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "search_skill")) {
            JsonObject searchSkillTool = new JsonObject();
            searchSkillTool.addProperty("type", "function");
            JsonObject searchSkillFn = new JsonObject();
            searchSkillFn.addProperty("name", "search_skill");
            searchSkillFn.addProperty("description", "Search text snippets in a skill by query.");
            JsonObject searchSkillParams = new JsonObject();
            searchSkillParams.addProperty("type", "object");
            JsonObject searchSkillProps = new JsonObject();
            JsonObject searchSkillId = new JsonObject();
            searchSkillId.addProperty("type", "string");
            searchSkillId.addProperty("description", "Optional skill id. Defaults to active skill.");
            searchSkillProps.add("id", searchSkillId);
            JsonObject searchSkillQuery = new JsonObject();
            searchSkillQuery.addProperty("type", "string");
            searchSkillQuery.addProperty("description", "Keyword to search.");
            searchSkillProps.add("query", searchSkillQuery);
            JsonObject searchSkillLimit = new JsonObject();
            searchSkillLimit.addProperty("type", "integer");
            searchSkillLimit.addProperty("description", "Max snippets to return (1-50).");
            searchSkillProps.add("limit", searchSkillLimit);
            searchSkillParams.add("properties", searchSkillProps);
            JsonArray searchSkillRequired = new JsonArray();
            searchSkillRequired.add("query");
            searchSkillParams.add("required", searchSkillRequired);
            searchSkillParams.addProperty("additionalProperties", false);
            searchSkillFn.add("parameters", searchSkillParams);
            searchSkillTool.add("function", searchSkillFn);
            tools.add(searchSkillTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "todo")) {
            JsonObject todoTool = new JsonObject();
            todoTool.addProperty("type", "function");
            JsonObject todoFn = new JsonObject();
            todoFn.addProperty("name", "todo");
            todoFn.addProperty("description", "Manage explicit todo list. Supported actions: get, set, upsert, delete, clear.");
            JsonObject todoParams = new JsonObject();
            todoParams.addProperty("type", "object");
            JsonObject todoProps = new JsonObject();

            JsonObject action = new JsonObject();
            action.addProperty("type", "string");
            JsonArray actionEnum = new JsonArray();
            actionEnum.add("get");
            actionEnum.add("set");
            actionEnum.add("upsert");
            actionEnum.add("delete");
            actionEnum.add("clear");
            action.add("enum", actionEnum);
            action.addProperty("description", "Todo operation.");
            todoProps.add("action", action);

            JsonObject title = new JsonObject();
            title.addProperty("type", "string");
            title.addProperty("description", "Optional todo title (used by action=set).");
            todoProps.add("title", title);

            JsonObject id = new JsonObject();
            id.addProperty("type", "string");
            id.addProperty("description", "Todo item id (used by action=upsert/delete).");
            todoProps.add("id", id);

            JsonObject content = new JsonObject();
            content.addProperty("type", "string");
            content.addProperty("description", "Todo text (used by action=upsert). Optional when updating only status.");
            todoProps.add("content", content);

            JsonObject status = new JsonObject();
            status.addProperty("type", "string");
            JsonArray statusEnum = new JsonArray();
            statusEnum.add("pending");
            statusEnum.add("in_progress");
            statusEnum.add("done");
            statusEnum.add("blocked");
            status.add("enum", statusEnum);
            status.addProperty("description", "Todo status (used by action=set/upsert).");
            todoProps.add("status", status);

            JsonObject items = new JsonObject();
            items.addProperty("type", "array");
            JsonObject item = new JsonObject();
            item.addProperty("type", "object");
            JsonObject itemProps = new JsonObject();
            JsonObject itemId = new JsonObject();
            itemId.addProperty("type", "string");
            itemId.addProperty("description", "Stable todo item id.");
            itemProps.add("id", itemId);
            JsonObject itemContent = new JsonObject();
            itemContent.addProperty("type", "string");
            itemContent.addProperty("description", "Todo text.");
            itemProps.add("content", itemContent);
            JsonObject itemStatus = new JsonObject();
            itemStatus.addProperty("type", "string");
            itemStatus.add("enum", statusEnum.deepCopy());
            itemProps.add("status", itemStatus);
            item.add("properties", itemProps);
            JsonArray itemRequired = new JsonArray();
            itemRequired.add("content");
            item.add("required", itemRequired);
            items.add("items", item);
            items.addProperty("description", "Todo items array (used by action=set).");
            todoProps.add("items", items);

            todoParams.add("properties", todoProps);
            JsonArray todoRequired = new JsonArray();
            todoRequired.add("action");
            todoParams.add("required", todoRequired);
            todoParams.addProperty("additionalProperties", false);
            todoFn.add("parameters", todoParams);
            todoTool.add("function", todoFn);
            tools.add(todoTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "request_user_choice")) {
            JsonObject requestChoiceTool = new JsonObject();
            requestChoiceTool.addProperty("type", "function");
            JsonObject requestChoiceFn = new JsonObject();
            requestChoiceFn.addProperty("name", "request_user_choice");
            requestChoiceFn.addProperty("description", "Request explicit user approval/selection from 2-3 options, then wait for user choice.");
            JsonObject requestChoiceParams = new JsonObject();
            requestChoiceParams.addProperty("type", "object");
            JsonObject requestChoiceProps = new JsonObject();
            JsonObject choicePrompt = new JsonObject();
            choicePrompt.addProperty("type", "string");
            choicePrompt.addProperty("description", "Question shown to user.");
            requestChoiceProps.add("prompt", choicePrompt);
            JsonObject choiceRequestId = new JsonObject();
            choiceRequestId.addProperty("type", "string");
            choiceRequestId.addProperty("description", "Optional stable choice request id.");
            requestChoiceProps.add("request_id", choiceRequestId);
            JsonObject choiceOptions = new JsonObject();
            choiceOptions.addProperty("type", "array");
            JsonObject choiceOptionItem = new JsonObject();
            choiceOptionItem.addProperty("type", "object");
            JsonObject choiceOptionProps = new JsonObject();
            JsonObject choiceOptionId = new JsonObject();
            choiceOptionId.addProperty("type", "string");
            choiceOptionId.addProperty("description", "Option id.");
            choiceOptionProps.add("id", choiceOptionId);
            JsonObject choiceLabel = new JsonObject();
            choiceLabel.addProperty("type", "string");
            choiceLabel.addProperty("description", "Short label shown on button.");
            choiceOptionProps.add("label", choiceLabel);
            JsonObject choiceDescription = new JsonObject();
            choiceDescription.addProperty("type", "string");
            choiceDescription.addProperty("description", "Optional option detail.");
            choiceOptionProps.add("description", choiceDescription);
            choiceOptionItem.add("properties", choiceOptionProps);
            JsonArray choiceOptionRequired = new JsonArray();
            choiceOptionRequired.add("label");
            choiceOptionItem.add("required", choiceOptionRequired);
            choiceOptions.add("items", choiceOptionItem);
            requestChoiceProps.add("options", choiceOptions);
            requestChoiceParams.add("properties", requestChoiceProps);
            JsonArray requestChoiceRequired = new JsonArray();
            requestChoiceRequired.add("prompt");
            requestChoiceRequired.add("options");
            requestChoiceParams.add("required", requestChoiceRequired);
            requestChoiceParams.addProperty("additionalProperties", false);
            requestChoiceFn.add("parameters", requestChoiceParams);
            requestChoiceTool.add("function", requestChoiceFn);
            tools.add(requestChoiceTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "clear_user_choice")) {
            JsonObject clearChoiceTool = new JsonObject();
            clearChoiceTool.addProperty("type", "function");
            JsonObject clearChoiceFn = new JsonObject();
            clearChoiceFn.addProperty("name", "clear_user_choice");
            clearChoiceFn.addProperty("description", "Clear pending user choice request.");
            JsonObject clearChoiceParams = new JsonObject();
            clearChoiceParams.addProperty("type", "object");
            clearChoiceParams.add("properties", new JsonObject());
            clearChoiceParams.addProperty("additionalProperties", false);
            clearChoiceFn.add("parameters", clearChoiceParams);
            clearChoiceTool.add("function", clearChoiceFn);
            tools.add(clearChoiceTool);
        }

        if (allowsTool(allowedTools, "read_workspace_state")) {
            JsonObject readTool = new JsonObject();
            readTool.addProperty("type", "function");
            JsonObject readFn = new JsonObject();
            readFn.addProperty("name", "read_workspace_state");
            readFn.addProperty("description",
                    "Read current (staged) workspace structure, revision, bounds and summary. " +
                    "With no arguments returns full state. Use 'part' to read a single named structure part, " +
                    "or 'line_start'+'line_end' to read a range of lines from the pretty-printed script JSON. " +
                    "These two modes are mutually exclusive.");
            JsonObject readParams = new JsonObject();
            readParams.addProperty("type", "object");
            JsonObject readProps = new JsonObject();

            JsonObject partProp = new JsonObject();
            partProp.addProperty("type", "string");
            partProp.addProperty("description", "Only read a single structure part by name. Returns palette + that part.");
            readProps.add("part", partProp);

            JsonObject lineStartProp = new JsonObject();
            lineStartProp.addProperty("type", "integer");
            lineStartProp.addProperty("description", "Start line number (1-based) of pretty-printed script JSON to read.");
            readProps.add("line_start", lineStartProp);

            JsonObject lineEndProp = new JsonObject();
            lineEndProp.addProperty("type", "integer");
            lineEndProp.addProperty("description", "End line number (1-based, inclusive) of pretty-printed script JSON to read.");
            readProps.add("line_end", lineEndProp);

            JsonObject committedProp = new JsonObject();
            committedProp.addProperty("type", "boolean");
            committedProp.addProperty("description", "When true, force reading committed revision even if a patch is staged.");
            readProps.add("committed", committedProp);

            readParams.add("properties", readProps);
            readParams.addProperty("additionalProperties", false);
            readFn.add("parameters", readParams);
            readTool.add("function", readFn);
            tools.add(readTool);
        }

        if (allowsTool(allowedTools, "search_block_ids")) {
            JsonObject searchTool = new JsonObject();
            searchTool.addProperty("type", "function");
            JsonObject searchFn = new JsonObject();
            searchFn.addProperty("name", "search_block_ids");
            searchFn.addProperty("description", "Search valid block IDs by keyword or partial id.");
            JsonObject searchParams = new JsonObject();
            searchParams.addProperty("type", "object");
            JsonObject searchProps = new JsonObject();

            JsonObject searchQuery = new JsonObject();
            searchQuery.addProperty("type", "string");
            searchQuery.addProperty("description", "Keyword or partial block id to search.");
            searchProps.add("query", searchQuery);

            JsonObject searchLimit = new JsonObject();
            searchLimit.addProperty("type", "integer");
            searchLimit.addProperty("description", "Max results (1-50).");
            searchProps.add("limit", searchLimit);

            searchParams.add("properties", searchProps);
            JsonArray searchRequired = new JsonArray();
            searchRequired.add("query");
            searchParams.add("required", searchRequired);
            searchParams.addProperty("additionalProperties", false);
            searchFn.add("parameters", searchParams);
            searchTool.add("function", searchFn);
            tools.add(searchTool);
        }

        if (allowsTool(allowedTools, "propose_patch")) {
            JsonObject patchTool = new JsonObject();
            patchTool.addProperty("type", "function");
            JsonObject patchFn = new JsonObject();
            patchFn.addProperty("name", "propose_patch");
            patchFn.addProperty("description",
                    "Propose an editable structure patch with strict verification. " +
                    "For modify/delete operations, provide old_actions matching current state exactly. " +
                    "If verification fails, you receive the actual state and should retry with corrected old_actions. " +
                    "Operations: insert_part, delete_part, replace_part, insert_actions, delete_actions, " +
                    "replace_actions, move_actions, update_palette.");

            JsonObject params = new JsonObject();
            params.addProperty("type", "object");
            JsonObject properties = new JsonObject();

            JsonObject baseRevision = new JsonObject();
            baseRevision.addProperty("type", "string");
            baseRevision.addProperty("description", "Workspace revision this patch is based on.");
            properties.add("base_revision", baseRevision);

            JsonObject intent = new JsonObject();
            intent.addProperty("type", "string");
            intent.addProperty("description", "Short user intent summary.");
            properties.add("intent", intent);

            JsonObject messageToUser = new JsonObject();
            messageToUser.addProperty("type", "string");
            messageToUser.addProperty("description", "One-line message shown to the user in preview.");
            properties.add("message_to_user", messageToUser);

            JsonObject operations = new JsonObject();
            operations.addProperty("type", "array");
            operations.addProperty("description", "Patch operations to apply to the structure model.");
            JsonObject operationItem = new JsonObject();
            operationItem.addProperty("type", "object");
            JsonObject operationProps = new JsonObject();

            JsonObject op = new JsonObject();
            op.addProperty("type", "string");
            JsonArray opEnum = new JsonArray();
            opEnum.add("insert_part");
            opEnum.add("delete_part");
            opEnum.add("replace_part");
            opEnum.add("insert_actions");
            opEnum.add("delete_actions");
            opEnum.add("replace_actions");
            opEnum.add("move_actions");
            opEnum.add("update_palette");
            op.add("enum", opEnum);
            op.addProperty("description",
                    "insert_part: create new part (must not exist). " +
                    "delete_part: remove part (old_actions must match all actions). " +
                    "replace_part: replace entire part (old_actions must match all). " +
                    "insert_actions: append actions to existing part. " +
                    "delete_actions: remove specific actions (old_actions subset match). " +
                    "replace_actions: replace specific actions 1:1 (old_actions subset match, same length as new_actions). " +
                    "move_actions: translate action coordinates by offset (old_actions subset match). " +
                    "update_palette: add/modify/delete palette entries with old_value verification.");
            operationProps.add("op", op);

            JsonObject part = new JsonObject();
            part.addProperty("type", "string");
            part.addProperty("description", "Part name (required for all ops except update_palette).");
            operationProps.add("part", part);

            JsonObject priority = new JsonObject();
            priority.addProperty("type", "integer");
            priority.addProperty("description", "Part priority (lower = built first).");
            operationProps.add("priority", priority);

            JsonObject actionsAdd = new JsonObject();
            actionsAdd.addProperty("type", "array");
            actionsAdd.addProperty("description", "Actions to add (for insert_part, insert_actions).");
            actionsAdd.add("items", buildActionSchema());
            operationProps.add("actions_add", actionsAdd);

            JsonObject oldActions = new JsonObject();
            oldActions.addProperty("type", "array");
            oldActions.addProperty("description", "Current actions to verify against before modifying. Must match exactly.");
            oldActions.add("items", buildActionSchema());
            operationProps.add("old_actions", oldActions);

            JsonObject newActions = new JsonObject();
            newActions.addProperty("type", "array");
            newActions.addProperty("description", "Replacement actions (for replace_part, replace_actions).");
            newActions.add("items", buildActionSchema());
            operationProps.add("new_actions", newActions);

            JsonObject offset = new JsonObject();
            offset.addProperty("type", "array");
            offset.addProperty("description", "Coordinate offset [dx, dy, dz] for move_actions.");
            JsonObject offsetItem = new JsonObject();
            offsetItem.addProperty("type", "integer");
            offset.add("items", offsetItem);
            offset.addProperty("minItems", 3);
            offset.addProperty("maxItems", 3);
            operationProps.add("offset", offset);

            JsonObject targetPart = new JsonObject();
            targetPart.addProperty("type", "string");
            targetPart.addProperty("description", "Target part name for cross-part move_actions. If omitted, actions stay in same part.");
            operationProps.add("target_part", targetPart);

            JsonObject entries = new JsonObject();
            entries.addProperty("type", "array");
            entries.addProperty("description", "Palette entries for update_palette. Each entry: old_value=null to add new, new_value=null to delete, both present to modify.");
            JsonObject entryItem = new JsonObject();
            entryItem.addProperty("type", "object");
            JsonObject entryProps = new JsonObject();
            JsonObject entryKey = new JsonObject();
            entryKey.addProperty("type", "string");
            entryKey.addProperty("description", "Palette key name.");
            entryProps.add("key", entryKey);
            JsonObject entryOldValue = new JsonObject();
            entryOldValue.addProperty("type", "string");
            entryOldValue.addProperty("description", "Current value (null for add-new). Must match exactly for modify/delete.");
            entryProps.add("old_value", entryOldValue);
            JsonObject entryNewValue = new JsonObject();
            entryNewValue.addProperty("type", "string");
            entryNewValue.addProperty("description", "New value (null for delete).");
            entryProps.add("new_value", entryNewValue);
            entryItem.add("properties", entryProps);
            JsonArray entryRequired = new JsonArray();
            entryRequired.add("key");
            entryItem.add("required", entryRequired);
            entries.add("items", entryItem);
            operationProps.add("entries", entries);

            operationItem.add("properties", operationProps);
            JsonArray operationRequired = new JsonArray();
            operationRequired.add("op");
            operationItem.add("required", operationRequired);
            operations.add("items", operationItem);
            properties.add("operations", operations);

            params.add("properties", properties);
            JsonArray required = new JsonArray();
            required.add("operations");
            params.add("required", required);
            patchFn.add("parameters", params);
            patchTool.add("function", patchFn);
            tools.add(patchTool);
        }

        if (allowsTool(allowedTools, "explain_plan")) {
            JsonObject explainTool = new JsonObject();
            explainTool.addProperty("type", "function");
            JsonObject explainFn = new JsonObject();
            explainFn.addProperty("name", "explain_plan");
            explainFn.addProperty("description", "Optional: provide short plan rationale to show users before patch confirmation.");
            JsonObject explainParams = new JsonObject();
            explainParams.addProperty("type", "object");
            JsonObject explainProps = new JsonObject();
            JsonObject plan = new JsonObject();
            plan.addProperty("type", "string");
            explainProps.add("plan", plan);
            explainParams.add("properties", explainProps);
            explainFn.add("parameters", explainParams);
            explainTool.add("function", explainFn);
            tools.add(explainTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "list_subagents")) {
            JsonObject listSubagentsTool = new JsonObject();
            listSubagentsTool.addProperty("type", "function");
            JsonObject listSubagentsFn = new JsonObject();
            listSubagentsFn.addProperty("name", "list_subagents");
            listSubagentsFn.addProperty("description", "List all subagents created in this session.");
            JsonObject listSubagentsParams = new JsonObject();
            listSubagentsParams.addProperty("type", "object");
            JsonObject listSubagentsProps = new JsonObject();
            JsonObject includeDeleted = new JsonObject();
            includeDeleted.addProperty("type", "boolean");
            includeDeleted.addProperty("description", "Whether deleted subagents should be included.");
            listSubagentsProps.add("include_deleted", includeDeleted);
            listSubagentsParams.add("properties", listSubagentsProps);
            listSubagentsParams.addProperty("additionalProperties", false);
            listSubagentsFn.add("parameters", listSubagentsParams);
            listSubagentsTool.add("function", listSubagentsFn);
            tools.add(listSubagentsTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "create_subagent")) {
            JsonObject createSubagentTool = new JsonObject();
            createSubagentTool.addProperty("type", "function");
            JsonObject createSubagentFn = new JsonObject();
            createSubagentFn.addProperty("name", "create_subagent");
            createSubagentFn.addProperty("description", "Create and run an asynchronous subagent task using a profile.");
            JsonObject createSubagentParams = new JsonObject();
            createSubagentParams.addProperty("type", "object");
            JsonObject createSubagentProps = new JsonObject();
            JsonObject task = new JsonObject();
            task.addProperty("type", "string");
            task.addProperty("description", "Task description passed to the subagent.");
            createSubagentProps.add("task", task);
            JsonObject profileId = new JsonObject();
            profileId.addProperty("type", "string");
            profileId.addProperty("description", "Optional profile id. Defaults to general-planner.");
            createSubagentProps.add("profile_id", profileId);
            JsonObject skillIds = new JsonObject();
            skillIds.addProperty("type", "array");
            JsonObject skillIdItem = new JsonObject();
            skillIdItem.addProperty("type", "string");
            skillIds.add("items", skillIdItem);
            skillIds.addProperty("description", "Optional scoped skill ids.");
            createSubagentProps.add("skill_ids", skillIds);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("type", "object");
            metadata.addProperty("description", "Optional metadata forwarded to subagent context.");
            createSubagentProps.add("metadata", metadata);
            createSubagentParams.add("properties", createSubagentProps);
            JsonArray createSubagentRequired = new JsonArray();
            createSubagentRequired.add("task");
            createSubagentParams.add("required", createSubagentRequired);
            createSubagentFn.add("parameters", createSubagentParams);
            createSubagentTool.add("function", createSubagentFn);
            tools.add(createSubagentTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "continue_subagent")) {
            JsonObject continueSubagentTool = new JsonObject();
            continueSubagentTool.addProperty("type", "function");
            JsonObject continueSubagentFn = new JsonObject();
            continueSubagentFn.addProperty("name", "continue_subagent");
            continueSubagentFn.addProperty("description", "Continue a failed/completed/cancelled subagent with optional new instruction.");
            JsonObject continueSubagentParams = new JsonObject();
            continueSubagentParams.addProperty("type", "object");
            JsonObject continueSubagentProps = new JsonObject();
            JsonObject subagentId = new JsonObject();
            subagentId.addProperty("type", "string");
            subagentId.addProperty("description", "Subagent id to continue.");
            continueSubagentProps.add("id", subagentId);
            JsonObject instruction = new JsonObject();
            instruction.addProperty("type", "string");
            instruction.addProperty("description", "Optional follow-up instruction for the continued run.");
            continueSubagentProps.add("instruction", instruction);
            continueSubagentParams.add("properties", continueSubagentProps);
            JsonArray continueSubagentRequired = new JsonArray();
            continueSubagentRequired.add("id");
            continueSubagentParams.add("required", continueSubagentRequired);
            continueSubagentParams.addProperty("additionalProperties", false);
            continueSubagentFn.add("parameters", continueSubagentParams);
            continueSubagentTool.add("function", continueSubagentFn);
            tools.add(continueSubagentTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "get_subagent")) {
            JsonObject getSubagentTool = new JsonObject();
            getSubagentTool.addProperty("type", "function");
            JsonObject getSubagentFn = new JsonObject();
            getSubagentFn.addProperty("name", "get_subagent");
            getSubagentFn.addProperty("description", "Get full status and result of a subagent by id.");
            JsonObject getSubagentParams = new JsonObject();
            getSubagentParams.addProperty("type", "object");
            JsonObject getSubagentProps = new JsonObject();
            JsonObject subagentId = new JsonObject();
            subagentId.addProperty("type", "string");
            subagentId.addProperty("description", "Subagent id returned by create_subagent.");
            getSubagentProps.add("id", subagentId);
            getSubagentParams.add("properties", getSubagentProps);
            JsonArray getSubagentRequired = new JsonArray();
            getSubagentRequired.add("id");
            getSubagentParams.add("required", getSubagentRequired);
            getSubagentFn.add("parameters", getSubagentParams);
            getSubagentTool.add("function", getSubagentFn);
            tools.add(getSubagentTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "delete_subagent")) {
            JsonObject deleteSubagentTool = new JsonObject();
            deleteSubagentTool.addProperty("type", "function");
            JsonObject deleteSubagentFn = new JsonObject();
            deleteSubagentFn.addProperty("name", "delete_subagent");
            deleteSubagentFn.addProperty("description", "Delete a subagent by id.");
            JsonObject deleteSubagentParams = new JsonObject();
            deleteSubagentParams.addProperty("type", "object");
            JsonObject deleteSubagentProps = new JsonObject();
            JsonObject subagentId = new JsonObject();
            subagentId.addProperty("type", "string");
            subagentId.addProperty("description", "Subagent id returned by create_subagent.");
            deleteSubagentProps.add("id", subagentId);
            deleteSubagentParams.add("properties", deleteSubagentProps);
            JsonArray deleteSubagentRequired = new JsonArray();
            deleteSubagentRequired.add("id");
            deleteSubagentParams.add("required", deleteSubagentRequired);
            deleteSubagentFn.add("parameters", deleteSubagentParams);
            deleteSubagentTool.add("function", deleteSubagentFn);
            tools.add(deleteSubagentTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "list_profiles")) {
            JsonObject listProfilesTool = new JsonObject();
            listProfilesTool.addProperty("type", "function");
            JsonObject listProfilesFn = new JsonObject();
            listProfilesFn.addProperty("name", "list_profiles");
            listProfilesFn.addProperty("description", "List available subagent profiles.");
            JsonObject listProfilesParams = new JsonObject();
            listProfilesParams.addProperty("type", "object");
            listProfilesParams.add("properties", new JsonObject());
            listProfilesParams.addProperty("additionalProperties", false);
            listProfilesFn.add("parameters", listProfilesParams);
            listProfilesTool.add("function", listProfilesFn);
            tools.add(listProfilesTool);
        }

        if (includeSkillTools && allowsTool(allowedTools, "get_profile")) {
            JsonObject getProfileTool = new JsonObject();
            getProfileTool.addProperty("type", "function");
            JsonObject getProfileFn = new JsonObject();
            getProfileFn.addProperty("name", "get_profile");
            getProfileFn.addProperty("description", "Read one subagent profile by id.");
            JsonObject getProfileParams = new JsonObject();
            getProfileParams.addProperty("type", "object");
            JsonObject getProfileProps = new JsonObject();
            JsonObject profileId = new JsonObject();
            profileId.addProperty("type", "string");
            profileId.addProperty("description", "Subagent profile id.");
            getProfileProps.add("id", profileId);
            getProfileParams.add("properties", getProfileProps);
            JsonArray getProfileRequired = new JsonArray();
            getProfileRequired.add("id");
            getProfileParams.add("required", getProfileRequired);
            getProfileFn.add("parameters", getProfileParams);
            getProfileTool.add("function", getProfileFn);
            tools.add(getProfileTool);
        }

        return tools;
    }

    private static Set<String> normalizeAllowedToolNames(Collection<String> allowedTools) {
        if (allowedTools == null) {
            return null;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : allowedTools) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            names.add(raw.trim());
        }
        return names;
    }

    private static boolean allowsTool(Set<String> allowedTools, String toolName) {
        return allowedTools == null || allowedTools.contains(toolName);
    }

    private static JsonObject buildActionSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        JsonArray typeEnum = new JsonArray();
        typeEnum.add("box");
        typeEnum.add("plane");
        typeEnum.add("line");
        typeEnum.add("points");
        type.add("enum", typeEnum);
        props.add("type", type);

        JsonObject block = new JsonObject();
        block.addProperty("type", "string");
        props.add("block", block);

        JsonObject from = new JsonObject();
        from.addProperty("type", "array");
        JsonObject intItem = new JsonObject();
        intItem.addProperty("type", "integer");
        from.add("items", intItem);
        from.addProperty("minItems", 3);
        from.addProperty("maxItems", 3);
        props.add("from", from);

        JsonObject to = new JsonObject();
        to.addProperty("type", "array");
        to.add("items", intItem.deepCopy());
        to.addProperty("minItems", 3);
        to.addProperty("maxItems", 3);
        props.add("to", to);

        JsonObject at = new JsonObject();
        at.addProperty("type", "array");
        JsonObject vec3 = new JsonObject();
        vec3.addProperty("type", "array");
        vec3.add("items", intItem.deepCopy());
        vec3.addProperty("minItems", 3);
        vec3.addProperty("maxItems", 3);
        at.add("items", vec3);
        props.add("at", at);

        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        JsonArray modeEnum = new JsonArray();
        modeEnum.add("solid");
        modeEnum.add("shell");
        modeEnum.add("walls");
        modeEnum.add("outline");
        mode.add("enum", modeEnum);
        props.add("mode", mode);

        JsonObject axis = new JsonObject();
        axis.addProperty("type", "string");
        JsonArray axisEnum = new JsonArray();
        axisEnum.add("x");
        axisEnum.add("y");
        axisEnum.add("z");
        axis.add("enum", axisEnum);
        props.add("axis", axis);

        JsonObject facing = new JsonObject();
        facing.addProperty("type", "string");
        JsonArray facingEnum = new JsonArray();
        facingEnum.add("north");
        facingEnum.add("south");
        facingEnum.add("east");
        facingEnum.add("west");
        facingEnum.add("up");
        facingEnum.add("down");
        facing.add("enum", facingEnum);
        props.add("facing", facing);

        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("type");
        required.add("block");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static Result parseResponse(String responseBody) throws IOException {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("LLM 未返回内容");
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            throw new IOException("响应缺少 message 字段");
        }

        String textContent = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        String fullMessage = message.toString();

        ToolCallParseResult toolResult = parseToolCall(message);
        if (toolResult != null && toolResult.script != null) {
            return new Result(textContent, fullMessage, toolResult.script);
        }

        if (textContent == null || textContent.isBlank()) {
            throw new IOException("响应缺少可解析内容");
        }
        String content = cleanContent(textContent);
        P2SMod.LOGGER.info("LLM cleaned content (truncated): {}", truncate(content));
        try {
            StructureBuilder.VbsScriptV2 script = parseScriptFromContent(content);
            return new Result(content, fullMessage, script);
        } catch (Exception e) {
            P2SMod.LOGGER.error("LLM content parse failed, content snippet: {}", truncate(content));
            throw e;
        }
    }

    private static SessionResult parseSessionResponse(String responseBody) throws IOException {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("LLM 未返回内容");
        }
        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            throw new IOException("响应缺少 message 字段");
        }

        String textContent = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        int textLen = textContent == null ? 0 : textContent.length();

        List<ToolCall> toolCalls = extractToolCalls(message);
        if (!toolCalls.isEmpty()) {
            P2SMod.LOGGER.info("LLM session parse -> toolCalls={}, textLen={}, hasScript=false", toolCalls.size(), textLen);
            return new SessionResult(textContent, null, message, toolCalls);
        }
        if (!ModConfig.USE_TOOL_CALL && textContent != null && !textContent.isBlank()) {
            try {
                String content = cleanContent(textContent);
                StructureBuilder.VbsScriptV2 script = parseScriptFromContent(content);
                P2SMod.LOGGER.info("LLM session parse -> toolCalls=0, textLen={}, hasScript=true (json content)", textLen);
                return new SessionResult(textContent, script, message, toolCalls);
            } catch (Exception ignored) {
                // fall through to text-only response
            }
        }
        P2SMod.LOGGER.info("LLM session parse -> toolCalls=0, textLen={}, hasScript=false", textLen);
        return new SessionResult(textContent, null, message, toolCalls);
    }

    private static StructureBuilder.VbsScriptV2 parseScriptFromContent(String content) {
        JsonElement elem = JsonParser.parseString(content);
        return parseScriptFromJson(elem);
    }

    private static List<ToolCall> extractToolCalls(JsonObject message) {
        List<ToolCall> result = new ArrayList<>();
        if (message == null) {
            return result;
        }

        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            JsonArray toolCalls = message.getAsJsonArray("tool_calls");
            for (JsonElement elem : toolCalls) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject call = elem.getAsJsonObject();
                if (!call.has("function")) {
                    continue;
                }
                JsonObject fn = call.getAsJsonObject("function");
                if (fn == null || !fn.has("name")) {
                    continue;
                }
                String name = fn.get("name").getAsString();
                String callId = call.has("id") ? call.get("id").getAsString() : "";
                JsonElement argsElem = fn.get("arguments");
                result.add(new ToolCall(callId, name, argsElem));
            }
        }

        if (result.isEmpty() && message.has("function_call") && message.get("function_call").isJsonObject()) {
            JsonObject fn = message.getAsJsonObject("function_call");
            if (fn.has("name")) {
                String name = fn.get("name").getAsString();
                JsonElement argsElem = fn.get("arguments");
                result.add(new ToolCall("", name, argsElem));
            }
        }

        return result;
    }

    private static StructureBuilder.VbsScriptV2 parseScriptFromJson(JsonElement elem) {
        if (elem == null || elem.isJsonNull()) {
            return null;
        }
        if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            if (obj.has("structures")) {
                return GSON.fromJson(obj, StructureBuilder.VbsScriptV2.class);
            }
            if (obj.has("structure")) {
                StructureBuilder.VbsScript v1 = GSON.fromJson(obj, StructureBuilder.VbsScript.class);
                return StructureBuilder.fromV1(v1);
            }
        }
        StructureBuilder.VbsScriptV2 v2 = GSON.fromJson(elem, StructureBuilder.VbsScriptV2.class);
        if (v2 != null && v2.structures != null) {
            return v2;
        }
        StructureBuilder.VbsScript v1 = GSON.fromJson(elem, StructureBuilder.VbsScript.class);
        return StructureBuilder.fromV1(v1);
    }

    private static ToolCallParseResult parseToolCall(JsonObject message) {
        if (message == null) {
            return null;
        }

        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            JsonArray toolCalls = message.getAsJsonArray("tool_calls");
            for (JsonElement elem : toolCalls) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject call = elem.getAsJsonObject();
                if (!call.has("function")) {
                    continue;
                }
                JsonObject fn = call.getAsJsonObject("function");
                if (fn == null || !fn.has("name")) {
                    continue;
                }
                String name = fn.get("name").getAsString();
                if (!"apply_structure".equals(name)) {
                    continue;
                }
                String callId = call.has("id") ? call.get("id").getAsString() : null;
                JsonElement argsElem = fn.get("arguments");
                StructureBuilder.VbsScriptV2 script = parseToolArguments(argsElem);
                if (script != null) {
                    return new ToolCallParseResult(script, callId);
                }
            }
        }

        if (message.has("function_call") && message.get("function_call").isJsonObject()) {
            JsonObject fn = message.getAsJsonObject("function_call");
            if (fn.has("name") && "apply_structure".equals(fn.get("name").getAsString())) {
                JsonElement argsElem = fn.get("arguments");
                StructureBuilder.VbsScriptV2 script = parseToolArguments(argsElem);
                if (script != null) {
                    return new ToolCallParseResult(script, null);
                }
            }
        }
        return null;
    }

    static StructureBuilder.VbsScriptV2 parseToolArguments(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return null;
        }
        try {
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                JsonElement parsed = JsonParser.parseString(raw);
                return parseScriptFromJson(parsed);
            }
            if (argsElem.isJsonObject()) {
                return parseScriptFromJson(argsElem);
            }
            return null;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to parse tool arguments: {}", e.getMessage());
            return null;
        }
    }

    private static String cleanContent(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        String block = extractCodeBlock(trimmed);
        if (block != null) {
            return block.trim();
        }
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak > 0) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private static String extractCodeBlock(String text) {
        int start = text.indexOf("```json");
        if (start < 0) {
            start = text.indexOf("```");
        }
        if (start < 0) {
            return null;
        }
        int end = text.indexOf("```", start + 3);
        if (end < 0) {
            return null;
        }
        return text.substring(start + 3 + (text.startsWith("```json", start) ? 4 : 0), end);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        int limit = 800;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...(truncated, len=" + text.length() + ")";
    }

    private static RequestConfig resolveConfig(RequestConfig override) {
        if (override == null) {
            return new RequestConfig(
                    ModConfig.API_URL,
                    ModConfig.API_KEY,
                    ModConfig.MODEL,
                    ModConfig.HTTP_TIMEOUT_SECONDS,
                    ModConfig.USE_TOOL_CALL
            );
        }
        String apiUrl = override.apiUrl() == null || override.apiUrl().isBlank() ? ModConfig.API_URL : override.apiUrl().trim();
        String apiKey = override.apiKey() == null || override.apiKey().isBlank() ? ModConfig.API_KEY : override.apiKey().trim();
        String model = override.model() == null || override.model().isBlank() ? ModConfig.MODEL : override.model().trim();
        int timeout = override.httpTimeoutSeconds() <= 0 ? ModConfig.HTTP_TIMEOUT_SECONDS : override.httpTimeoutSeconds();
        boolean toolCall = override.useToolCall();
        return new RequestConfig(apiUrl, apiKey, model, timeout, toolCall);
    }

    private static OkHttpClient getClient() {
        int cfgTimeout = ModConfig.HTTP_TIMEOUT_SECONDS;
        if (cfgTimeout != CLIENT_TIMEOUT_SECONDS) {
            CLIENT_TIMEOUT_SECONDS = cfgTimeout;
            CLIENT = buildClient(cfgTimeout);
            P2SMod.LOGGER.info("LLM HTTP client rebuilt with timeout {}s", cfgTimeout);
        }
        return CLIENT;
    }

    private static OkHttpClient getClient(int timeoutSeconds) {
        if (timeoutSeconds <= 0 || timeoutSeconds == ModConfig.HTTP_TIMEOUT_SECONDS) {
            return getClient();
        }
        return buildClient(timeoutSeconds);
    }

    private static OkHttpClient buildClient(int timeoutSeconds) {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .writeTimeout(Duration.ofSeconds(timeoutSeconds))
                .callTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public record Result(String rawContent, String fullMessage, StructureBuilder.VbsScriptV2 script) {
    }

    private record ToolCallParseResult(StructureBuilder.VbsScriptV2 script, String toolCallId) {
    }

    public record SessionResult(
            String textContent,
            StructureBuilder.VbsScriptV2 script,
            JsonObject rawAssistantMessage,
            List<ToolCall> toolCalls
    ) {
    }

    public record ToolCall(String id, String name, JsonElement arguments) {
    }

    public record RequestConfig(
            String apiUrl,
            String apiKey,
            String model,
            int httpTimeoutSeconds,
            boolean useToolCall
    ) {
    }
}
