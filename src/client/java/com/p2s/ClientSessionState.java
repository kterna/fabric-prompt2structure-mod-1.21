package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ClientSessionState {
    private static boolean active = false;
    private static String sessionId = "";
    private static String projectId = "";
    private static String projectName = "";
    private static String projectDescription = "";
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
    private static int originX = 0;
    private static int originY = 0;
    private static int originZ = 0;
    private static boolean hasSize = false;
    private static int sizeX = 0;
    private static int sizeY = 0;
    private static int sizeZ = 0;
    private static String activeDocId = "";
    private static String activeDocName = "";
    private static String selectedWorkspaceId = "";
    private static final List<WorkspaceDocInfo> workspaceDocs = new ArrayList<>();
    private static final Map<String, String> workspaceDocScripts = new LinkedHashMap<>();
    private static String todoTitle = "";
    private static final List<TodoItem> todoItems = new ArrayList<>();
    private static ChoiceRequest pendingChoice = null;
    private static final List<CheckpointInfo> checkpoints = new ArrayList<>();
    private static int selectedCheckpointIndex = -1;
    private static String rollbackMode = "workspace_and_session";
    private static final List<ChatMessage> messages = new ArrayList<>();
    private static final StringBuilder streamingBuffer = new StringBuilder();
    private static volatile boolean streaming = false;
    private static String currentScriptJson = "";

    private ClientSessionState() {
    }

    public static void onSessionSync(
            boolean activeFlag,
            String id,
            String projectIdValue,
            String projectNameValue,
            String projectDescriptionValue,
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
            int changed,
            int ox, int oy, int oz,
            boolean hasSz,
            int sx, int sy, int sz,
            String checkpointsJson,
            String scriptJson,
            String activeDoc,
            String activeDocDisplayName,
            String docsSummaryJson
    ) {
        active = activeFlag;
        sessionId = id == null ? "" : id;
        projectId = projectIdValue == null ? "" : projectIdValue;
        projectName = projectNameValue == null ? "" : projectNameValue;
        projectDescription = projectDescriptionValue == null ? "" : projectDescriptionValue;
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
        originX = ox;
        originY = oy;
        originZ = oz;
        hasSize = hasSz;
        sizeX = sx;
        sizeY = sy;
        sizeZ = sz;
        currentScriptJson = scriptJson == null ? "" : scriptJson;
        activeDocId = activeDoc == null ? "" : activeDoc;
        activeDocName = activeDocDisplayName == null ? "" : activeDocDisplayName;
        updateWorkspaceDocs(docsSummaryJson);
        if (active && !activeDocId.isBlank() && currentScriptJson != null && !currentScriptJson.isBlank()) {
            workspaceDocScripts.put(activeDocId, currentScriptJson);
        }
        updateCheckpoints(checkpointsJson);
        if (!pending) {
            clearPreview();
        }
        if (!activeFlag) {
            status = "";
            messages.clear();
            clearPreview();
            clearTodo();
            clearPendingChoice();
            checkpoints.clear();
            selectedCheckpointIndex = -1;
            originX = 0;
            originY = 0;
            originZ = 0;
            hasSize = false;
            sizeX = 0;
            sizeY = 0;
            sizeZ = 0;
            currentScriptJson = "";
            activeDocId = "";
            activeDocName = "";
            selectedWorkspaceId = "";
            projectId = "";
            projectName = "";
            projectDescription = "";
            workspaceDocs.clear();
            workspaceDocScripts.clear();
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

    public static void clearPendingPatch() {
        hasPendingPatch = false;
        pendingSummary = "";
        pendingRisk = "";
        pendingChangedBlocks = 0;
        clearPreview();
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

    public static int getOriginX() {
        return originX;
    }

    public static int getOriginY() {
        return originY;
    }

    public static int getOriginZ() {
        return originZ;
    }

    public static boolean hasSize() {
        return hasSize;
    }

    public static int getSizeX() {
        return sizeX;
    }

    public static int getSizeY() {
        return sizeY;
    }

    public static int getSizeZ() {
        return sizeZ;
    }

    public static String getCurrentScriptJson() {
        return currentScriptJson;
    }

    public static synchronized String getActiveDocId() {
        return activeDocId;
    }

    public static synchronized String getActiveDocName() {
        return activeDocName;
    }

    public static synchronized String getProjectId() {
        return projectId;
    }

    public static synchronized String getProjectName() {
        return projectName;
    }

    public static synchronized String getProjectDescription() {
        return projectDescription;
    }

    public static synchronized String getSelectedWorkspaceId() {
        return selectedWorkspaceId;
    }

    public static synchronized boolean setSelectedWorkspaceId(String workspaceId) {
        String normalized = workspaceId == null ? "" : workspaceId.trim();
        if (normalized.isBlank()) {
            return false;
        }
        for (WorkspaceDocInfo doc : workspaceDocs) {
            if (doc != null && normalized.equals(doc.id())) {
                selectedWorkspaceId = normalized;
                return true;
            }
        }
        return false;
    }

    public static synchronized String getSelectedWorkspaceName() {
        WorkspaceDocInfo selected = findWorkspaceDoc(selectedWorkspaceId);
        if (selected != null) {
            if (selected.path() != null && !selected.path().isBlank()) {
                return selected.path();
            }
            if (selected.name() != null && !selected.name().isBlank()) {
                return selected.name();
            }
        }
        return activeDocName;
    }

    public static synchronized List<WorkspaceDocInfo> getWorkspaceDocs() {
        return List.copyOf(workspaceDocs);
    }

    public static synchronized String getWorkspaceDocScriptJson(String docId) {
        if (docId == null || docId.isBlank()) {
            return "";
        }
        String value = workspaceDocScripts.get(docId.trim());
        return value == null ? "" : value;
    }

    public static synchronized Map<String, String> getWorkspaceDocScripts() {
        return Map.copyOf(workspaceDocScripts);
    }

    public static synchronized void setWorkspaceDocScriptJson(String workspaceId, String scriptJson) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        if (scriptJson == null || scriptJson.isBlank()) {
            workspaceDocScripts.remove(workspaceId.trim());
            return;
        }
        workspaceDocScripts.put(workspaceId.trim(), scriptJson);
    }

    private static synchronized void updateWorkspaceDocs(String docsSummaryJson) {
        workspaceDocs.clear();
        List<String> validIds = new ArrayList<>();
        if (docsSummaryJson == null || docsSummaryJson.isBlank()) {
            workspaceDocScripts.clear();
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(docsSummaryJson);
            if (!root.isJsonArray()) {
                workspaceDocScripts.clear();
                return;
            }
            for (JsonElement el : root.getAsJsonArray()) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                String id = obj.has("id") && obj.get("id").isJsonPrimitive() ? obj.get("id").getAsString().trim() : "";
                if (id.isBlank()) {
                    continue;
                }
                validIds.add(id);
                String name = obj.has("name") && obj.get("name").isJsonPrimitive() ? obj.get("name").getAsString().trim() : "";
                String path = obj.has("path") && obj.get("path").isJsonPrimitive() ? obj.get("path").getAsString().trim() : "";
                String type = obj.has("type") && obj.get("type").isJsonPrimitive() ? obj.get("type").getAsString().trim() : "";
                String areaTag = obj.has("areaTag") && obj.get("areaTag").isJsonPrimitive() ? obj.get("areaTag").getAsString().trim() : "";
                String summary = obj.has("summary") && obj.get("summary").isJsonPrimitive() ? obj.get("summary").getAsString().trim() : "";
                boolean active = obj.has("active") && obj.get("active").isJsonPrimitive() && obj.get("active").getAsBoolean();
                boolean hasPending = obj.has("hasPendingPatch") && obj.get("hasPendingPatch").isJsonPrimitive() && obj.get("hasPendingPatch").getAsBoolean();
                String revision = obj.has("revision") && obj.get("revision").isJsonPrimitive() ? obj.get("revision").getAsString().trim() : "";
                boolean hasSizeValue = obj.has("hasSize") && obj.get("hasSize").isJsonPrimitive() && obj.get("hasSize").getAsBoolean();
                int sx = obj.has("sizeX") && obj.get("sizeX").isJsonPrimitive() ? obj.get("sizeX").getAsInt() : 0;
                int sy = obj.has("sizeY") && obj.get("sizeY").isJsonPrimitive() ? obj.get("sizeY").getAsInt() : 0;
                int sz = obj.has("sizeZ") && obj.get("sizeZ").isJsonPrimitive() ? obj.get("sizeZ").getAsInt() : 0;
                int ox = obj.has("originX") && obj.get("originX").isJsonPrimitive() ? obj.get("originX").getAsInt() : 0;
                int oy = obj.has("originY") && obj.get("originY").isJsonPrimitive() ? obj.get("originY").getAsInt() : 0;
                int oz = obj.has("originZ") && obj.get("originZ").isJsonPrimitive() ? obj.get("originZ").getAsInt() : 0;
                int changed = obj.has("pendingChangedBlocks") && obj.get("pendingChangedBlocks").isJsonPrimitive()
                        ? Math.max(0, obj.get("pendingChangedBlocks").getAsInt())
                        : 0;
                workspaceDocs.add(new WorkspaceDocInfo(id, name, path, type, areaTag, summary, active, hasPending, revision, ox, oy, oz, hasSizeValue, sx, sy, sz, changed));
                if (active && (activeDocName == null || activeDocName.isBlank()) && !name.isBlank()) {
                    activeDocName = name;
                }
            }
        } catch (Exception ignored) {
        }
        workspaceDocScripts.keySet().removeIf(id -> !validIds.contains(id));

        boolean hasSelected = false;
        for (WorkspaceDocInfo doc : workspaceDocs) {
            if (doc != null && doc.id().equals(selectedWorkspaceId)) {
                hasSelected = true;
                break;
            }
        }
        if (!hasSelected) {
            if (activeDocId != null && !activeDocId.isBlank() && validIds.contains(activeDocId)) {
                selectedWorkspaceId = activeDocId;
            } else if (!workspaceDocs.isEmpty()) {
                selectedWorkspaceId = workspaceDocs.get(0).id();
            } else {
                selectedWorkspaceId = "";
            }
        }
    }

    private static WorkspaceDocInfo findWorkspaceDoc(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        for (WorkspaceDocInfo doc : workspaceDocs) {
            if (doc != null && workspaceId.equals(doc.id())) {
                return doc;
            }
        }
        return null;
    }

    private static synchronized void updateCheckpoints(String checkpointsJson) {
        checkpoints.clear();
        if (checkpointsJson != null && !checkpointsJson.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(checkpointsJson);
                if (root.isJsonArray()) {
                    JsonArray arr = root.getAsJsonArray();
                    for (JsonElement el : arr) {
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        JsonObject obj = el.getAsJsonObject();
                        String id = obj.has("id") && obj.get("id").isJsonPrimitive() ? obj.get("id").getAsString().trim() : "";
                        if (id.isBlank()) {
                            continue;
                        }
                        String label = obj.has("label") && obj.get("label").isJsonPrimitive() ? obj.get("label").getAsString().trim() : "";
                        String revisionLabel = obj.has("revision") && obj.get("revision").isJsonPrimitive() ? obj.get("revision").getAsString().trim() : "";
                        checkpoints.add(new CheckpointInfo(id, label, revisionLabel));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (checkpoints.isEmpty()) {
            selectedCheckpointIndex = -1;
            return;
        }
        if (selectedCheckpointIndex < 0 || selectedCheckpointIndex >= checkpoints.size()) {
            selectedCheckpointIndex = checkpoints.size() - 1;
        }
    }

    public static synchronized List<CheckpointInfo> getCheckpoints() {
        return List.copyOf(checkpoints);
    }

    public static synchronized CheckpointInfo getSelectedCheckpoint() {
        if (selectedCheckpointIndex < 0 || selectedCheckpointIndex >= checkpoints.size()) {
            return null;
        }
        return checkpoints.get(selectedCheckpointIndex);
    }

    public static synchronized void selectPreviousCheckpoint() {
        if (checkpoints.isEmpty()) {
            selectedCheckpointIndex = -1;
            return;
        }
        if (selectedCheckpointIndex < 0) {
            selectedCheckpointIndex = checkpoints.size() - 1;
            return;
        }
        selectedCheckpointIndex = (selectedCheckpointIndex - 1 + checkpoints.size()) % checkpoints.size();
    }

    public static synchronized void selectNextCheckpoint() {
        if (checkpoints.isEmpty()) {
            selectedCheckpointIndex = -1;
            return;
        }
        if (selectedCheckpointIndex < 0) {
            selectedCheckpointIndex = 0;
            return;
        }
        selectedCheckpointIndex = (selectedCheckpointIndex + 1) % checkpoints.size();
    }

    public static synchronized String getRollbackMode() {
        return rollbackMode;
    }

    public static synchronized String toggleRollbackMode() {
        rollbackMode = "workspace_and_session".equals(rollbackMode) ? "session_only" : "workspace_and_session";
        return rollbackMode;
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

    public record CheckpointInfo(String id, String label, String revision) {
    }

    public record WorkspaceDocInfo(
            String id,
            String name,
            String path,
            String type,
            String areaTag,
            String summary,
            boolean active,
            boolean hasPendingPatch,
            String revision,
            int originX,
            int originY,
            int originZ,
            boolean hasSize,
            int sizeX,
            int sizeY,
            int sizeZ,
            int pendingChangedBlocks
    ) {
    }
}
