package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
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
            String bodyJson = buildBody(userPrompt);
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

    private static String buildBody(String userPrompt) {
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
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        return GSON.toJson(body);
    }

    private static Result parseResponse(String responseBody) throws IOException {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("LLM 未返回内容");
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null || !message.has("content")) {
            throw new IOException("响应缺少 message.content 字段");
        }

        String fullMessage = message.get("content").getAsString();
        String content = cleanContent(fullMessage);
        P2SMod.LOGGER.info("LLM cleaned content (truncated): {}", truncate(content));
        try {
            StructureBuilder.VbsScript script = StructureBuilder.parse(content);
            return new Result(content, fullMessage, script);
        } catch (Exception e) {
            P2SMod.LOGGER.error("LLM content parse failed, content snippet: {}", truncate(content));
            throw e;
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

    public record Result(String rawContent, String fullMessage, StructureBuilder.VbsScript script) {
    }
}
