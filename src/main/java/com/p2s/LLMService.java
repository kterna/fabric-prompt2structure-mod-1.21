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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
            String bodyJson = buildBodyForPrompt(userPrompt, ModConfig.USE_TOOL_CALL, true);
            P2SMod.LOGGER.info("LLM request -> url={}, model={}, timeout={}s", ModConfig.API_URL, ModConfig.MODEL, ModConfig.HTTP_TIMEOUT_SECONDS);
            P2SMod.LOGGER.info("Active prompt preset: {}", ModConfig.activePromptName());
            P2SMod.LOGGER.info("LLM prompt: {}", userPrompt);
            Request request = new Request.Builder()
                    .url(ModConfig.API_URL)
                    .post(RequestBody.create(bodyJson, JSON))
                    .header("Authorization", "Bearer " + ModConfig.API_KEY)
                    .build();

            try (Response response = getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() == null ? "" : response.body().string();
                    P2SMod.LOGGER.error("LLM failed status={}, body={}", response.code(), truncate(errBody));
                    throw new IOException("请求失败，状态码: " + response.code());
                }
                String respBody = response.body() == null ? "" : response.body().string();
                P2SMod.LOGGER.info("LLM raw response (truncated): {}", truncate(respBody));
                return parseResponse(respBody);
            } catch (Exception e) {
                throw new RuntimeException("LLM 请求异常: " + e.getMessage(), e);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<SessionResult> requestWithHistory(java.util.List<JsonObject> messages) {
        return CompletableFuture.supplyAsync(() -> {
            String bodyJson = buildBodyForMessages(messages, ModConfig.USE_TOOL_CALL, false);
            int messageCount = messages == null ? 0 : messages.size();
            int payloadBytes = bodyJson == null ? 0 : bodyJson.length();
            P2SMod.LOGGER.info("LLM session request -> url={}, model={}, timeout={}s", ModConfig.API_URL, ModConfig.MODEL, ModConfig.HTTP_TIMEOUT_SECONDS);
            P2SMod.LOGGER.info("LLM session payload -> messages={}, bytes={}, toolCall={}", messageCount, payloadBytes, ModConfig.USE_TOOL_CALL);
            Request request = new Request.Builder()
                    .url(ModConfig.API_URL)
                    .post(RequestBody.create(bodyJson, JSON))
                    .header("Authorization", "Bearer " + ModConfig.API_KEY)
                    .build();

            try (Response response = getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() == null ? "" : response.body().string();
                    P2SMod.LOGGER.error("LLM failed status={}, body={}", response.code(), truncate(errBody));
                    throw new IOException("请求失败，状态码: " + response.code());
                }
                String respBody = response.body() == null ? "" : response.body().string();
                P2SMod.LOGGER.info("LLM raw response (truncated): {}", truncate(respBody));
                return parseSessionResponse(respBody);
            } catch (Exception e) {
                throw new RuntimeException("LLM 请求异常: " + e.getMessage(), e);
            }
        }, EXECUTOR);
    }

    private static String buildBodyForPrompt(String userPrompt, boolean useToolCall, boolean forceTool) {
        JsonObject body = new JsonObject();
        body.addProperty("model", ModConfig.MODEL);

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

    private static String buildBodyForMessages(java.util.List<JsonObject> messages, boolean useToolCall, boolean forceTool) {
        JsonObject body = new JsonObject();
        body.addProperty("model", ModConfig.MODEL);
        body.add("messages", GSON.toJsonTree(messages));
        body.addProperty("temperature", 0.4);
        applySessionToolOrResponseFormat(body, useToolCall);
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

    private static void applySessionToolOrResponseFormat(JsonObject body, boolean useToolCall) {
        if (useToolCall) {
            body.add("tools", buildSessionToolDefinitions());
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
        typeEnum.add("fill");
        typeEnum.add("frame");
        typeEnum.add("set");
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

    private static JsonArray buildSessionToolDefinitions() {
        JsonArray tools = new JsonArray();

        JsonObject readTool = new JsonObject();
        readTool.addProperty("type", "function");
        JsonObject readFn = new JsonObject();
        readFn.addProperty("name", "read_workspace_state");
        readFn.addProperty("description", "Read current (staged) workspace structure, revision, bounds and summary before proposing a patch.");
        JsonObject readParams = new JsonObject();
        readParams.addProperty("type", "object");
        readParams.add("properties", new JsonObject());
        readParams.addProperty("additionalProperties", false);
        readFn.add("parameters", readParams);
        readTool.add("function", readFn);
        tools.add(readTool);

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

        JsonObject patchTool = new JsonObject();
        patchTool.addProperty("type", "function");
        JsonObject patchFn = new JsonObject();
        patchFn.addProperty("name", "propose_patch");
        patchFn.addProperty("description", "Propose an editable structure patch. Do not build directly.");

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
        opEnum.add("upsert_part");
        opEnum.add("delete_part");
        opEnum.add("patch_actions");
        opEnum.add("set_palette");
        op.add("enum", opEnum);
        operationProps.add("op", op);

        JsonObject part = new JsonObject();
        part.addProperty("type", "string");
        operationProps.add("part", part);

        JsonObject priority = new JsonObject();
        priority.addProperty("type", "integer");
        operationProps.add("priority", priority);

        JsonObject paletteDelta = new JsonObject();
        paletteDelta.addProperty("type", "object");
        JsonObject paletteDeltaValue = new JsonObject();
        paletteDeltaValue.addProperty("type", "string");
        paletteDelta.add("additionalProperties", paletteDeltaValue);
        operationProps.add("palette_delta", paletteDelta);

        JsonObject actionsAdd = new JsonObject();
        actionsAdd.addProperty("type", "array");
        actionsAdd.add("items", buildActionSchema());
        operationProps.add("actions_add", actionsAdd);

        JsonObject actionsRemoveMatch = new JsonObject();
        actionsRemoveMatch.addProperty("type", "array");
        actionsRemoveMatch.add("items", buildActionMatchSchema());
        operationProps.add("actions_remove_match", actionsRemoveMatch);

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

        return tools;
    }

    private static JsonObject buildActionSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        JsonArray typeEnum = new JsonArray();
        typeEnum.add("fill");
        typeEnum.add("frame");
        typeEnum.add("set");
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
        return schema;
    }

    private static JsonObject buildActionMatchSchema() {
        JsonObject schema = buildActionSchema();
        schema.remove("required");
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

    private static OkHttpClient getClient() {
        int cfgTimeout = ModConfig.HTTP_TIMEOUT_SECONDS;
        if (cfgTimeout != CLIENT_TIMEOUT_SECONDS) {
            CLIENT_TIMEOUT_SECONDS = cfgTimeout;
            CLIENT = buildClient(cfgTimeout);
            P2SMod.LOGGER.info("LLM HTTP client rebuilt with timeout {}s", cfgTimeout);
        }
        return CLIENT;
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
}
