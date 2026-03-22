package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.store.MemoryStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ClientPersistentMemoryManager {
    static final String MEMORY_SYSTEM_MARKER = "[P2S_PERSISTENT_MEMORY]";

    private static final String MEMORY_SYSTEM_PREFIX = "These durable memories persist across session restore and context compaction. Treat them as stable preferences and long-lived project facts unless the current user request overrides them.";
    private static final int MAX_MEMORY_TEXT_LENGTH = 240;
    private static final int MAX_TURN_TEXT_LENGTH = 4_000;

    private ClientPersistentMemoryManager() {
    }

    record PromptSnapshot(List<String> globalMemories, List<String> projectMemories, String systemMessage) {
        boolean hasContent() {
            return (globalMemories != null && !globalMemories.isEmpty())
                    || (projectMemories != null && !projectMemories.isEmpty());
        }
    }

    record UpdateInput(
            String projectId,
            String projectName,
            String projectDescription,
            String selectedWorkspacePath,
            String userText,
            String assistantText
    ) {
    }

    record UpdateResult(boolean changed, PromptSnapshot promptSnapshot, String summary) {
    }

    static boolean isMemorySystemMessage(JsonObject message) {
        return message != null
                && "system".equals(roleOf(message))
                && contentOf(message).startsWith(MEMORY_SYSTEM_MARKER + "\n");
    }

    static PromptSnapshot loadPromptSnapshot(String projectId) {
        MemoryStore.MemorySnapshot snapshot = MemoryStore.load(projectId);
        List<String> global = normalizeMemories(texts(snapshot.globalEntries()), P2SClientConfig.getPersistentMemoryMaxGlobalEntries());
        List<String> project = normalizeMemories(texts(snapshot.projectEntries()), P2SClientConfig.getPersistentMemoryMaxProjectEntries());
        return new PromptSnapshot(global, project, buildSystemMessage(global, project));
    }

    static UpdateResult updateFromTurn(
            UpdateInput input,
            LLMService.RequestConfig config,
            long timeoutSeconds
    ) {
        if (input == null || input.userText() == null || input.userText().isBlank()) {
            return new UpdateResult(false, loadPromptSnapshot(input == null ? "" : input.projectId()), "");
        }

        PromptSnapshot current = loadPromptSnapshot(input.projectId());
        List<JsonObject> messages = new ArrayList<>();
        messages.add(buildMessage("system", buildExtractionSystemPrompt()));
        messages.add(buildMessage("user", buildExtractionUserPrompt(input, current)));

        LLMService.SessionResult result = LLMService.requestPlainTextWithHistory(messages, config)
                .orTimeout(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS)
                .join();

        MemoryPayload payload = parsePayload(result);
        if (payload == null) {
            return new UpdateResult(false, current, "");
        }

        List<String> nextGlobal = normalizeMemories(payload.globalMemories(), P2SClientConfig.getPersistentMemoryMaxGlobalEntries());
        List<String> nextProject = input.projectId() == null || input.projectId().isBlank()
                ? List.of()
                : normalizeMemories(payload.projectMemories(), P2SClientConfig.getPersistentMemoryMaxProjectEntries());

        boolean changed = !sameMemories(current.globalMemories(), nextGlobal)
                || !sameMemories(current.projectMemories(), nextProject);
        if (!changed) {
            return new UpdateResult(false, current, normalizeSummary(payload.summary()));
        }

        MemoryStore.replaceGlobal(nextGlobal);
        if (input.projectId() != null && !input.projectId().isBlank()) {
            MemoryStore.replaceProject(input.projectId(), nextProject);
        }

        PromptSnapshot updated = loadPromptSnapshot(input.projectId());
        return new UpdateResult(true, updated, normalizeSummary(payload.summary()));
    }

    private static String buildExtractionSystemPrompt() {
        return """
                You maintain durable memories for a Prompt2Structure client session.
                Durable memories survive context compaction and restored sessions.

                Update the full memory lists using the latest completed turn plus the existing memories.

                Keep only:
                - stable user preferences
                - long-lived constraints
                - reusable project facts
                - naming conventions, chosen workspace focus, or architectural decisions likely to matter later

                Do not keep:
                - one-off requests for just this turn
                - transient plans, temporary TODOs, or progress updates
                - raw tool output, stack traces, or patch previews
                - secrets, tokens, or credentials

                If a new fact conflicts with an old memory, replace the old memory.
                Return strict JSON only, with this exact shape:
                {
                  "global_memories": ["..."],
                  "project_memories": ["..."],
                  "summary": "one short sentence"
                }
                Each memory item must be a single concise sentence.
                """;
    }

    private static String buildExtractionUserPrompt(UpdateInput input, PromptSnapshot current) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project Id: ").append(blankFallback(input.projectId(), "(none)")).append('\n');
        sb.append("Project Name: ").append(blankFallback(input.projectName(), "(none)")).append('\n');
        sb.append("Project Description: ").append(blankFallback(input.projectDescription(), "(none)")).append('\n');
        sb.append("Selected Workspace: ").append(blankFallback(input.selectedWorkspacePath(), "(none)")).append('\n');
        sb.append("Global memory cap: ").append(P2SClientConfig.getPersistentMemoryMaxGlobalEntries()).append('\n');
        sb.append("Project memory cap: ").append(P2SClientConfig.getPersistentMemoryMaxProjectEntries()).append('\n');
        sb.append('\n');
        sb.append("Existing global memories:\n");
        appendMemoryList(sb, current.globalMemories());
        sb.append('\n');
        sb.append("Existing project memories:\n");
        appendMemoryList(sb, current.projectMemories());
        sb.append('\n');
        sb.append("Latest completed user message:\n");
        sb.append(limitText(input.userText(), MAX_TURN_TEXT_LENGTH)).append('\n');
        sb.append('\n');
        sb.append("Latest assistant response:\n");
        sb.append(limitText(input.assistantText(), MAX_TURN_TEXT_LENGTH)).append('\n');
        return sb.toString();
    }

    private static void appendMemoryList(StringBuilder sb, List<String> memories) {
        if (memories == null || memories.isEmpty()) {
            sb.append("- (none)\n");
            return;
        }
        for (String memory : memories) {
            sb.append("- ").append(memory).append('\n');
        }
    }

    private static String buildSystemMessage(List<String> globalMemories, List<String> projectMemories) {
        if ((globalMemories == null || globalMemories.isEmpty())
                && (projectMemories == null || projectMemories.isEmpty())) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(MEMORY_SYSTEM_MARKER).append('\n');
        sb.append(MEMORY_SYSTEM_PREFIX).append('\n');
        if (globalMemories != null && !globalMemories.isEmpty()) {
            sb.append('\n').append("Global durable memories:\n");
            for (String memory : globalMemories) {
                sb.append("- ").append(memory).append('\n');
            }
        }
        if (projectMemories != null && !projectMemories.isEmpty()) {
            sb.append('\n').append("Project durable memories:\n");
            for (String memory : projectMemories) {
                sb.append("- ").append(memory).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static MemoryPayload parsePayload(LLMService.SessionResult result) {
        String raw = "";
        if (result != null && result.textContent() != null && !result.textContent().isBlank()) {
            raw = result.textContent();
        } else if (result != null && result.rawAssistantMessage() != null) {
            raw = contentOf(result.rawAssistantMessage());
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String jsonText = extractJsonObject(raw);
        if (jsonText.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
            return new MemoryPayload(
                    readStringArray(root, "global_memories"),
                    readStringArray(root, "project_memories"),
                    getString(root, "summary")
            );
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed parsing persistent memory payload: {}", e.getMessage());
            return null;
        }
    }

    private static String extractJsonObject(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return trimmed.substring(start, end + 1);
    }

    private static List<String> readStringArray(JsonObject obj, String key) {
        List<String> values = new ArrayList<>();
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            return values;
        }
        JsonArray array = obj.getAsJsonArray(key);
        for (JsonElement element : array) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            values.add(element.getAsString());
        }
        return values;
    }

    private static List<String> texts(List<MemoryStore.MemoryEntry> entries) {
        List<String> values = new ArrayList<>();
        if (entries == null) {
            return values;
        }
        for (MemoryStore.MemoryEntry entry : entries) {
            if (entry == null || entry.text() == null || entry.text().isBlank()) {
                continue;
            }
            values.add(entry.text());
        }
        return values;
    }

    private static List<String> normalizeMemories(List<String> memories, int maxEntries) {
        Map<String, String> deduped = new LinkedHashMap<>();
        if (memories != null) {
            for (String memory : memories) {
                String normalized = normalizeMemoryText(memory);
                if (normalized.isBlank()) {
                    continue;
                }
                String key = normalized.toLowerCase(Locale.ROOT);
                if (!deduped.containsKey(key)) {
                    deduped.put(key, normalized);
                }
                if (deduped.size() >= Math.max(1, maxEntries)) {
                    break;
                }
            }
        }
        return List.copyOf(deduped.values());
    }

    private static String normalizeMemoryText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        normalized = normalized.replaceAll("[\\t\\n ]+", " ");
        normalized = normalized.replaceAll("^[\\-•*\\d.()\\s]+", "");
        normalized = normalized.trim();
        if (normalized.length() > MAX_MEMORY_TEXT_LENGTH) {
            normalized = normalized.substring(0, MAX_MEMORY_TEXT_LENGTH - 3).trim() + "...";
        }
        return normalized;
    }

    private static boolean sameMemories(List<String> left, List<String> right) {
        List<String> a = left == null ? List.of() : left;
        List<String> b = right == null ? List.of() : right;
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!normalizeMemoryText(a.get(i)).equalsIgnoreCase(normalizeMemoryText(b.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject buildMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }

    private static String limitText(String text, int maxLength) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxLength - 3)).trim() + "...";
    }

    private static String normalizeSummary(String summary) {
        if (summary == null) {
            return "";
        }
        String normalized = summary.replace("\r\n", "\n").replace('\r', '\n').trim();
        normalized = normalized.replaceAll("[\\t\\n ]+", " ");
        if (normalized.length() > 180) {
            normalized = normalized.substring(0, 177).trim() + "...";
        }
        return normalized;
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String roleOf(JsonObject message) {
        if (message == null || !message.has("role") || !message.get("role").isJsonPrimitive()) {
            return "";
        }
        try {
            return message.get("role").getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String contentOf(JsonObject message) {
        if (message == null || !message.has("content") || !message.get("content").isJsonPrimitive()) {
            return "";
        }
        try {
            return message.get("content").getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private record MemoryPayload(List<String> globalMemories, List<String> projectMemories, String summary) {
    }
}
