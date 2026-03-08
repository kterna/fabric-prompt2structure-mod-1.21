package com.p2s;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ClientHistoryCompactor {
    static final String COMPACT_SUMMARY_MARKER = "[P2S_CONTEXT_COMPACTION_SUMMARY]";

    private static final String SUMMARY_NATURAL_PREFIX = "This is an authoritative handoff summary of the earlier conversation context. Use it to continue the task without repeating completed work.";
    private static final int MESSAGE_BASE_TOKENS = 4;
    private static final int MIN_TRUNCATED_CHARS = 24;

    private ClientHistoryCompactor() {
    }

    enum CompactTrigger {
        MANUAL,
        AUTO_PRE_TURN,
        AUTO_MID_TURN
    }

    record CompactionResult(
            List<JsonObject> replacementHistory,
            String summaryBody,
            int estimatedTokensBefore,
            int estimatedTokensAfter,
            CompactTrigger trigger
    ) {
    }

    static boolean hasCompactableHistory(List<JsonObject> history) {
        return history != null && countLeadingSystemMessages(history) < history.size();
    }

    static boolean hasNonSummaryNonSystemMessages(List<JsonObject> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (JsonObject message : history) {
            if (message == null) {
                continue;
            }
            String role = roleOf(message);
            if ("system".equals(role)) {
                continue;
            }
            if (!isSummaryMessage(message)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSummaryMessage(JsonObject message) {
        return message != null
                && "user".equals(roleOf(message))
                && isSummaryContent(contentOf(message));
    }

    static boolean isSummaryContent(String content) {
        return content != null && content.startsWith(COMPACT_SUMMARY_MARKER + "\n");
    }

    static String buildSummaryMessageContent(String summaryBody) {
        String normalized = normalizeSummaryBody(summaryBody);
        return COMPACT_SUMMARY_MARKER + "\n" + SUMMARY_NATURAL_PREFIX + "\n" + normalized;
    }

    static int estimateHistoryTokens(List<JsonObject> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (JsonObject message : history) {
            if (message == null) {
                continue;
            }
            total += MESSAGE_BASE_TOKENS + estimateTextTokens(message.toString());
        }
        return total;
    }

    static CompactionResult compactHistory(
            List<JsonObject> history,
            CompactTrigger trigger,
            LLMService.RequestConfig config,
            long timeoutSeconds
    ) {
        List<JsonObject> snapshot = deepCopyMessages(history);
        if (!hasCompactableHistory(snapshot)) {
            throw new IllegalStateException("No non-system history to compact");
        }

        int estimatedTokensBefore = estimateHistoryTokens(snapshot);
        List<JsonObject> requestHistory = deepCopyMessages(snapshot);
        requestHistory.add(buildMessage("user", P2SClientConfig.getCompactPrompt()));

        LLMService.SessionResult result = LLMService.requestPlainTextWithHistory(requestHistory, config)
                .orTimeout(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)
                .join();

        String summaryBody = extractSummaryBody(result);
        if (summaryBody.isBlank()) {
            throw new IllegalStateException("Compaction returned empty summary");
        }

        List<JsonObject> replacementHistory = rebuildHistory(snapshot, summaryBody);
        int estimatedTokensAfter = estimateHistoryTokens(replacementHistory);
        return new CompactionResult(replacementHistory, normalizeSummaryBody(summaryBody), estimatedTokensBefore, estimatedTokensAfter, trigger);
    }

    private static List<JsonObject> rebuildHistory(List<JsonObject> snapshot, String summaryBody) {
        List<JsonObject> replacement = new ArrayList<>();
        int systemCount = countLeadingSystemMessages(snapshot);
        for (int i = 0; i < systemCount; i++) {
            replacement.add(snapshot.get(i).deepCopy());
        }

        List<String> retainedUsers = selectRecentUserMessages(collectRealUserMessages(snapshot), P2SClientConfig.getCompactRetainUserTokenBudget());
        for (String message : retainedUsers) {
            replacement.add(buildMessage("user", message));
        }
        replacement.add(buildMessage("user", buildSummaryMessageContent(summaryBody)));
        return replacement;
    }

    private static List<String> collectRealUserMessages(List<JsonObject> snapshot) {
        List<String> messages = new ArrayList<>();
        if (snapshot == null) {
            return messages;
        }
        for (JsonObject message : snapshot) {
            if (message == null || !"user".equals(roleOf(message))) {
                continue;
            }
            String content = contentOf(message);
            if (content.isBlank() || isSummaryContent(content)) {
                continue;
            }
            messages.add(content);
        }
        return messages;
    }

    private static List<String> selectRecentUserMessages(List<String> userMessages, int tokenBudget) {
        List<String> selected = new ArrayList<>();
        if (userMessages == null || userMessages.isEmpty() || tokenBudget <= 0) {
            return selected;
        }
        int remaining = tokenBudget;
        for (int i = userMessages.size() - 1; i >= 0 && remaining > 0; i--) {
            String message = userMessages.get(i);
            int messageTokens = MESSAGE_BASE_TOKENS + estimateTextTokens(message);
            if (messageTokens <= remaining) {
                selected.add(0, message);
                remaining -= messageTokens;
                continue;
            }
            String truncated = truncateMessageToBudget(message, remaining);
            if (!truncated.isBlank()) {
                selected.add(0, truncated);
            }
            break;
        }
        return selected;
    }

    private static String truncateMessageToBudget(String message, int remainingTokens) {
        if (message == null || message.isBlank() || remainingTokens <= MESSAGE_BASE_TOKENS) {
            return "";
        }
        int textBudget = Math.max(1, remainingTokens - MESSAGE_BASE_TOKENS);
        int charBudget = Math.max(MIN_TRUNCATED_CHARS, textBudget * 4);
        String trimmed = message.trim();
        if (trimmed.length() <= charBudget) {
            return trimmed;
        }
        if (charBudget <= 16) {
            return trimmed.substring(0, Math.min(trimmed.length(), charBudget));
        }
        return trimmed.substring(0, Math.max(1, charBudget - 16)) + " ... [truncated]";
    }

    private static String extractSummaryBody(LLMService.SessionResult result) {
        if (result == null) {
            return "";
        }
        String text = result.textContent();
        if ((text == null || text.isBlank()) && result.rawAssistantMessage() != null) {
            text = contentOf(result.rawAssistantMessage());
        }
        return normalizeSummaryBody(text);
    }

    private static String normalizeSummaryBody(String text) {
        String normalized = text == null ? "" : text.trim();
        return normalized.isBlank() ? "(no summary available)" : normalized;
    }

    private static int countLeadingSystemMessages(List<JsonObject> history) {
        int count = 0;
        if (history == null) {
            return 0;
        }
        while (count < history.size() && "system".equals(roleOf(history.get(count)))) {
            count++;
        }
        return count;
    }

    private static JsonObject buildMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
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

    private static int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    private static List<JsonObject> deepCopyMessages(List<JsonObject> source) {
        List<JsonObject> copy = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return copy;
        }
        for (JsonObject message : source) {
            copy.add(message == null ? null : message.deepCopy());
        }
        return copy;
    }
}
