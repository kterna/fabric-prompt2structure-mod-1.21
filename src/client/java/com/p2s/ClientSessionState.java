package com.p2s;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ClientSessionState {
    private static boolean active = false;
    private static String sessionId = "";
    private static int turnCount = 0;
    private static int partCount = 0;
    private static int totalBlocks = 0;
    private static String partsSummary = "";
    private static String structureSummary = "";
    private static String status = "";
    private static String runtimeState = "";
    private static String revision = "";
    private static boolean hasPendingPatch = false;
    private static String pendingSummary = "";
    private static String pendingRisk = "";
    private static int pendingChangedBlocks = 0;
    private static String previewSummary = "";
    private static String previewDetail = "";
    private static String previewRisk = "";
    private static int previewChangedBlocks = 0;
    private static String todoTitle = "";
    private static final List<TodoItem> todoItems = new ArrayList<>();
    private static ChoiceRequest pendingChoice = null;
    private static final List<ChatMessage> messages = new ArrayList<>();
    private static final StringBuilder streamingBuffer = new StringBuilder();
    private static volatile boolean streaming = false;

    private ClientSessionState() {
    }

    public static void onSessionSync(
            boolean activeFlag,
            String id,
            int turns,
            int parts,
            int blocks,
            String summary,
            String structure,
            String runtime,
            String rev,
            boolean pending,
            String pendingPatchSummary,
            String risk,
            int changed
    ) {
        active = activeFlag;
        sessionId = id == null ? "" : id;
        turnCount = turns;
        partCount = parts;
        totalBlocks = blocks;
        partsSummary = summary == null ? "" : summary;
        structureSummary = structure == null ? "" : structure;
        runtimeState = runtime == null ? "" : runtime;
        revision = rev == null ? "" : rev;
        hasPendingPatch = pending;
        pendingSummary = pendingPatchSummary == null ? "" : pendingPatchSummary;
        pendingRisk = risk == null ? "" : risk;
        pendingChangedBlocks = Math.max(0, changed);
        if (!pending) {
            clearPreview();
        }
        if (!activeFlag) {
            status = "";
            messages.clear();
            clearPreview();
            clearTodo();
            clearPendingChoice();
        }
    }

    public static void onChatResponse(String text, boolean hasStructure, String newStatus) {
        if (text != null && !text.isBlank()) {
            addMessage("AI", text.trim());
        }
        if (newStatus != null) {
            status = newStatus;
        }
    }

    public static void onBuildProgress(String phase, String currentPart, int progress, int blocksPlaced) {
        StringBuilder sb = new StringBuilder();
        if (phase != null && !phase.isBlank()) {
            sb.append(phase);
        }
        if (currentPart != null && !currentPart.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(currentPart);
        }
        if (progress >= 0) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(progress).append("%");
        }
        status = sb.toString();
    }

    public static void onPatchPreview(boolean hasPreview, String summary, String detail, int changedBlocks, String riskLevel) {
        if (!hasPreview) {
            clearPreview();
            return;
        }
        previewSummary = summary == null ? "" : summary;
        previewDetail = detail == null ? "" : detail;
        previewRisk = riskLevel == null ? "" : riskLevel;
        previewChangedBlocks = Math.max(0, changedBlocks);
    }

    public static void addUserMessage(String text) {
        if (text != null && !text.isBlank()) {
            addMessage("You", text.trim());
        }
    }

    public static synchronized void setTodo(String title, List<TodoItem> items) {
        todoTitle = title == null ? "" : title.trim();
        todoItems.clear();
        if (items != null) {
            for (TodoItem item : items) {
                if (item == null || item.id() == null || item.id().isBlank()) {
                    continue;
                }
                String content = item.content() == null ? "" : item.content().trim();
                if (content.isBlank()) {
                    continue;
                }
                todoItems.add(new TodoItem(
                        item.id().trim(),
                        content,
                        normalizeTodoStatus(item.status())
                ));
            }
        }
    }

    public static synchronized String getTodoTitle() {
        return todoTitle;
    }

    public static synchronized List<TodoItem> getTodoItems() {
        return List.copyOf(todoItems);
    }

    public static synchronized boolean upsertTodoItem(String id, String content, String status) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String itemId = id.trim();
        String normalizedStatus = (status == null || status.isBlank()) ? "" : normalizeTodoStatus(status);

        for (int i = 0; i < todoItems.size(); i++) {
            TodoItem existing = todoItems.get(i);
            if (!itemId.equals(existing.id())) {
                continue;
            }
            String nextContent = (content == null || content.isBlank()) ? existing.content() : content.trim();
            String nextStatus = normalizedStatus.isBlank() ? existing.status() : normalizedStatus;
            todoItems.set(i, new TodoItem(itemId, nextContent, nextStatus));
            return true;
        }

        if (content == null || content.isBlank()) {
            return false;
        }
        todoItems.add(new TodoItem(itemId, content.trim(), normalizedStatus.isBlank() ? "pending" : normalizedStatus));
        return true;
    }

    public static synchronized boolean removeTodoItem(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String itemId = id.trim();
        for (int i = 0; i < todoItems.size(); i++) {
            if (itemId.equals(todoItems.get(i).id())) {
                todoItems.remove(i);
                return true;
            }
        }
        return false;
    }

    public static synchronized void clearTodo() {
        todoTitle = "";
        todoItems.clear();
    }

    public static synchronized void setPendingChoice(String requestId, String prompt, List<ChoiceOption> options) {
        if (prompt == null || prompt.isBlank() || options == null || options.isEmpty()) {
            pendingChoice = null;
            return;
        }
        List<ChoiceOption> normalized = new ArrayList<>();
        for (ChoiceOption option : options) {
            if (option == null || option.id() == null || option.id().isBlank()) {
                continue;
            }
            String label = option.label() == null ? "" : option.label().trim();
            if (label.isBlank()) {
                continue;
            }
            normalized.add(new ChoiceOption(
                    option.id().trim(),
                    label,
                    option.description() == null ? "" : option.description().trim()
            ));
        }
        if (normalized.isEmpty()) {
            pendingChoice = null;
            return;
        }
        pendingChoice = new ChoiceRequest(
                requestId == null ? "" : requestId.trim(),
                prompt.trim(),
                List.copyOf(normalized)
        );
    }

    public static synchronized ChoiceRequest getPendingChoice() {
        return pendingChoice;
    }

    public static synchronized boolean hasPendingChoice() {
        return pendingChoice != null && pendingChoice.options() != null && !pendingChoice.options().isEmpty();
    }

    public static synchronized void clearPendingChoice() {
        pendingChoice = null;
    }

    public static void beginStreaming() {
        synchronized (streamingBuffer) {
            streamingBuffer.setLength(0);
            streaming = true;
        }
    }

    public static void appendStreamingToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        synchronized (streamingBuffer) {
            streamingBuffer.append(token);
        }
    }

    public static String getStreamingText() {
        synchronized (streamingBuffer) {
            return streamingBuffer.toString();
        }
    }

    public static boolean isStreaming() {
        return streaming;
    }

    public static void endStreaming() {
        synchronized (streamingBuffer) {
            streaming = false;
            streamingBuffer.setLength(0);
        }
    }

    static void addMessage(String role, String text) {
        messages.add(new ChatMessage(role, text));
        if (messages.size() > 200) {
            messages.remove(0);
        }
    }

    public static void clearMessages() {
        messages.clear();
    }

    public static List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public static boolean isActive() {
        return active;
    }

    public static String getSessionId() {
        return sessionId;
    }

    public static int getTurnCount() {
        return turnCount;
    }

    public static int getPartCount() {
        return partCount;
    }

    public static int getTotalBlocks() {
        return totalBlocks;
    }

    public static String getPartsSummary() {
        return partsSummary;
    }

    public static String getStructureSummary() {
        return structureSummary;
    }

    public static String getStatus() {
        return status;
    }

    public static void setStatus(String value) {
        status = value == null ? "" : value;
    }

    public static String getRuntimeState() {
        return runtimeState;
    }

    public static String getRevision() {
        return revision;
    }

    public static boolean hasPendingPatch() {
        return hasPendingPatch;
    }

    public static String getPendingSummary() {
        return pendingSummary;
    }

    public static String getPendingRisk() {
        return pendingRisk;
    }

    public static int getPendingChangedBlocks() {
        return pendingChangedBlocks;
    }

    public static String getPreviewSummary() {
        return previewSummary;
    }

    public static String getPreviewDetail() {
        return previewDetail;
    }

    public static String getPreviewRisk() {
        return previewRisk;
    }

    public static int getPreviewChangedBlocks() {
        return previewChangedBlocks;
    }

    private static void clearPreview() {
        previewSummary = "";
        previewDetail = "";
        previewRisk = "";
        previewChangedBlocks = 0;
    }

    private static String normalizeTodoStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "pending", "in_progress", "done", "blocked" -> value;
            default -> "pending";
        };
    }

    public record ChatMessage(String role, String text) {
    }

    public record TodoItem(String id, String content, String status) {
    }

    public record ChoiceOption(String id, String label, String description) {
    }

    public record ChoiceRequest(String requestId, String prompt, List<ChoiceOption> options) {
    }
}
