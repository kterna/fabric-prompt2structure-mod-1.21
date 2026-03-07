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
    private static boolean hasProject = false;
    private static boolean sessionActive = false;
    private static String sessionId = "";
    private static String projectId = "";
    private static String projectName = "";
    private static String projectDescription = "";
    private static int partCount = 0;
    private static int totalBlocks = 0;
    private static String partsSummary = "";
    private static String structureSummary = "";
    private static String status = "";
    private static String runtimeState = "";
    private static String revision = "";
    private static boolean hasPendingPatch = false;
    private static String pendingPath = "";
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
    private static String selectedWorkspacePath = "";
    private static final List<WorkspaceFileInfo> workspaceFiles = new ArrayList<>();
    private static final Map<String, String> workspaceFileScripts = new LinkedHashMap<>();
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

    public static synchronized void onSessionSync(
            boolean hasProjectFlag,
            boolean sessionActiveFlag,
            String id,
            String projectIdValue,
            String projectNameValue,
            String projectDescriptionValue,
            int ox,
            int oy,
            int oz,
            boolean hasSz,
            int sx,
            int sy,
            int sz,
            String selectedPath,
            int parts,
            int blocks,
            String summary,
            String structure,
            String runtime,
            String rev,
            boolean pending,
            String pendingWorkspacePath,
            String pendingPatchSummary,
            String risk,
            int changed,
            String checkpointsJson,
            String scriptJson,
            String workspaceFilesJson
    ) {
        hasProject = hasProjectFlag;
        sessionActive = sessionActiveFlag;
        sessionId = id == null ? "" : id;
        projectId = projectIdValue == null ? "" : projectIdValue;
        projectName = projectNameValue == null ? "" : projectNameValue;
        projectDescription = projectDescriptionValue == null ? "" : projectDescriptionValue;
        originX = ox;
        originY = oy;
        originZ = oz;
        hasSize = hasSz;
        sizeX = sx;
        sizeY = sy;
        sizeZ = sz;
        selectedWorkspacePath = normalizeWorkspacePath(selectedPath);
        partCount = Math.max(0, parts);
        totalBlocks = Math.max(0, blocks);
        partsSummary = summary == null ? "" : summary;
        structureSummary = structure == null ? "" : structure;
        runtimeState = runtime == null ? "" : runtime;
        revision = rev == null ? "" : rev;
        hasPendingPatch = pending;
        pendingPath = normalizeWorkspacePath(pendingWorkspacePath);
        pendingSummary = pendingPatchSummary == null ? "" : pendingPatchSummary;
        pendingRisk = risk == null ? "" : risk;
        pendingChangedBlocks = Math.max(0, changed);
        currentScriptJson = scriptJson == null ? "" : scriptJson;

        updateWorkspaceFiles(workspaceFilesJson);
        if (sessionActive && !selectedWorkspacePath.isBlank() && !currentScriptJson.isBlank()) {
            workspaceFileScripts.put(selectedWorkspacePath, currentScriptJson);
        }
        updateCheckpoints(checkpointsJson);
        if (!pending) {
            clearPreview();
        }

        if (!sessionActiveFlag) {
            status = "";
            clearPreview();
            clearTodo();
            clearPendingChoice();
            checkpoints.clear();
            selectedCheckpointIndex = -1;
            currentScriptJson = "";
            runtimeState = "";
            revision = "";
            pendingPath = "";
            pendingSummary = "";
            pendingRisk = "";
            pendingChangedBlocks = 0;
            hasPendingPatch = false;
            partCount = 0;
            totalBlocks = 0;
            partsSummary = "";
            structureSummary = "";
            selectedWorkspacePath = hasProjectFlag && !workspaceFiles.isEmpty() && workspaceFiles.get(0) != null
                    ? normalizeWorkspacePath(workspaceFiles.get(0).path())
                    : "";
            messages.clear();
        }

        if (!hasProjectFlag) {
            projectId = "";
            projectName = "";
            projectDescription = "";
            originX = 0;
            originY = 0;
            originZ = 0;
            hasSize = false;
            sizeX = 0;
            sizeY = 0;
            sizeZ = 0;
            workspaceFiles.clear();
            workspaceFileScripts.clear();
        }
    }

    public static synchronized void onChatResponse(String text, boolean hasStructure, String newStatus) {
        onChatResponse(text, hasStructure, newStatus, "", "");
    }

    public static synchronized void onChatResponse(String text, boolean hasStructure, String newStatus, String messageKey, String messageArgsJson) {
        String resolved = P2SI18n.resolve(messageKey, messageArgsJson, text).getString();
        if (resolved != null && !resolved.isBlank()) {
            addMessage(P2SI18n.ROLE_ASSISTANT, resolved.trim());
        }
        if (newStatus != null) {
            status = newStatus;
        }
    }

    public static synchronized void onBuildProgress(String phase, String currentPart, int progress, int blocksPlaced) {
        StringBuilder sb = new StringBuilder();
        if (phase != null && !phase.isBlank()) {
            sb.append(phase);
        }
        if (currentPart != null && !currentPart.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(currentPart);
        }
        if (progress >= 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(progress).append('%');
        }
        status = sb.toString();
    }

    public static synchronized void onPatchPreview(boolean hasPreview, String summary, String detail, int changedBlocks, String riskLevel) {
        if (!hasPreview) {
            clearPreview();
            return;
        }
        previewSummary = summary == null ? "" : summary;
        previewDetail = detail == null ? "" : detail;
        previewRisk = riskLevel == null ? "" : riskLevel;
        previewChangedBlocks = Math.max(0, changedBlocks);
    }

    public static synchronized void addUserMessage(String text) {
        if (text != null && !text.isBlank()) {
            addMessage(P2SI18n.ROLE_USER, text.trim());
        }
    }

    public static synchronized void setTodo(String title, List<TodoItem> items) {
        todoTitle = title == null ? "" : title.trim();
        todoItems.clear();
        if (items == null) {
            return;
        }
        for (TodoItem item : items) {
            if (item == null || item.id() == null || item.id().isBlank()) {
                continue;
            }
            String content = item.content() == null ? "" : item.content().trim();
            if (content.isBlank()) {
                continue;
            }
            todoItems.add(new TodoItem(item.id().trim(), content, normalizeTodoStatus(item.status())));
        }
    }

    public static synchronized String getTodoTitle() {
        return todoTitle;
    }

    public static synchronized List<TodoItem> getTodoItems() {
        return List.copyOf(todoItems);
    }

    public static synchronized boolean upsertTodoItem(String id, String content, String statusValue) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String itemId = id.trim();
        String normalizedStatus = statusValue == null || statusValue.isBlank() ? "" : normalizeTodoStatus(statusValue);
        for (int i = 0; i < todoItems.size(); i++) {
            TodoItem existing = todoItems.get(i);
            if (!itemId.equals(existing.id())) {
                continue;
            }
            String nextContent = content == null || content.isBlank() ? existing.content() : content.trim();
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
        List<ChoiceOption> normalized = new ArrayList<>();
        if (options != null) {
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
        }
        pendingChoice = normalized.isEmpty()
                ? null
                : new ChoiceRequest(requestId == null ? "" : requestId.trim(), prompt == null ? "" : prompt.trim(), List.copyOf(normalized));
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

    public static synchronized void clearPendingPatch() {
        hasPendingPatch = false;
        pendingPath = "";
        pendingSummary = "";
        pendingRisk = "";
        pendingChangedBlocks = 0;
        clearPreview();
    }

    public static synchronized void beginStreaming() {
        streaming = true;
        streamingBuffer.setLength(0);
    }

    public static synchronized void appendStreamingToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        streamingBuffer.append(token);
    }

    public static synchronized String getStreamingText() {
        return streamingBuffer.toString();
    }

    public static boolean isStreaming() {
        return streaming;
    }

    public static synchronized void endStreaming() {
        streaming = false;
        streamingBuffer.setLength(0);
    }

    static synchronized void addMessage(String role, String text) {
        messages.add(new ChatMessage(P2SI18n.normalizeRole(role), text));
        if (messages.size() > 200) {
            messages.remove(0);
        }
    }

    public static synchronized void clearMessages() {
        messages.clear();
    }

    public static synchronized void resetAll() {
        hasProject = false;
        sessionActive = false;
        sessionId = "";
        projectId = "";
        projectName = "";
        projectDescription = "";
        partCount = 0;
        totalBlocks = 0;
        partsSummary = "";
        structureSummary = "";
        status = "";
        runtimeState = "";
        revision = "";
        hasPendingPatch = false;
        pendingPath = "";
        pendingSummary = "";
        pendingRisk = "";
        pendingChangedBlocks = 0;
        previewSummary = "";
        previewDetail = "";
        previewRisk = "";
        previewChangedBlocks = 0;
        originX = 0;
        originY = 0;
        originZ = 0;
        hasSize = false;
        sizeX = 0;
        sizeY = 0;
        sizeZ = 0;
        selectedWorkspacePath = "";
        workspaceFiles.clear();
        workspaceFileScripts.clear();
        todoTitle = "";
        todoItems.clear();
        pendingChoice = null;
        checkpoints.clear();
        selectedCheckpointIndex = -1;
        rollbackMode = "workspace_and_session";
        messages.clear();
        streamingBuffer.setLength(0);
        streaming = false;
        currentScriptJson = "";
    }

    public static synchronized List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public static synchronized boolean hasProject() {
        return hasProject;
    }

    public static synchronized boolean isActive() {
        return sessionActive;
    }

    public static synchronized String getSessionId() {
        return sessionId;
    }

    public static synchronized int getTurnCount() {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message != null && P2SI18n.isUserRole(message.role())) {
                count++;
            }
        }
        return count;
    }

    public static synchronized int getPartCount() {
        return partCount;
    }

    public static synchronized int getTotalBlocks() {
        return totalBlocks;
    }

    public static synchronized String getPartsSummary() {
        return partsSummary;
    }

    public static synchronized String getStructureSummary() {
        return structureSummary;
    }

    public static synchronized String getStatus() {
        return status;
    }

    public static synchronized void setStatus(String value) {
        status = value == null ? "" : value;
    }

    public static synchronized String getRuntimeState() {
        return runtimeState;
    }

    public static synchronized String getRevision() {
        return revision;
    }

    public static synchronized boolean hasPendingPatch() {
        return hasPendingPatch;
    }

    public static synchronized String getPendingPath() {
        return pendingPath;
    }

    public static synchronized String getPendingSummary() {
        return pendingSummary;
    }

    public static synchronized String getPendingRisk() {
        return pendingRisk;
    }

    public static synchronized int getPendingChangedBlocks() {
        return pendingChangedBlocks;
    }

    public static synchronized String getPreviewSummary() {
        return previewSummary;
    }

    public static synchronized String getPreviewDetail() {
        return previewDetail;
    }

    public static synchronized String getPreviewRisk() {
        return previewRisk;
    }

    public static synchronized int getPreviewChangedBlocks() {
        return previewChangedBlocks;
    }

    public static synchronized int getOriginX() {
        return originX;
    }

    public static synchronized int getOriginY() {
        return originY;
    }

    public static synchronized int getOriginZ() {
        return originZ;
    }

    public static synchronized boolean hasSize() {
        return hasSize;
    }

    public static synchronized int getSizeX() {
        return sizeX;
    }

    public static synchronized int getSizeY() {
        return sizeY;
    }

    public static synchronized int getSizeZ() {
        return sizeZ;
    }

    public static synchronized String getCurrentScriptJson() {
        return currentScriptJson;
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

    public static synchronized String getSelectedWorkspacePath() {
        return selectedWorkspacePath;
    }

    public static synchronized boolean setSelectedWorkspacePath(String workspacePath) {
        String normalized = normalizeWorkspacePath(workspacePath);
        if (normalized.isBlank()) {
            selectedWorkspacePath = "";
            return true;
        }
        for (WorkspaceFileInfo file : workspaceFiles) {
            if (file != null && normalized.equals(file.path())) {
                selectedWorkspacePath = normalized;
                return true;
            }
        }
        return false;
    }

    public static synchronized String getSelectedWorkspaceLabel() {
        WorkspaceFileInfo selected = findWorkspaceFile(selectedWorkspacePath);
        if (selected == null) {
            return "";
        }
        if (selected.path() != null && !selected.path().isBlank()) {
            return selected.path();
        }
        return selected.name() == null ? "" : selected.name();
    }

    public static synchronized List<WorkspaceFileInfo> getWorkspaceFiles() {
        return List.copyOf(workspaceFiles);
    }

    public static synchronized String getWorkspaceFileScriptJson(String workspacePath) {
        String normalized = normalizeWorkspacePath(workspacePath);
        if (normalized.isBlank()) {
            return "";
        }
        String value = workspaceFileScripts.get(normalized);
        return value == null ? "" : value;
    }

    public static synchronized Map<String, String> getWorkspaceFileScripts() {
        return Map.copyOf(workspaceFileScripts);
    }

    public static synchronized void setWorkspaceFileScriptJson(String workspacePath, String scriptJson) {
        String normalized = normalizeWorkspacePath(workspacePath);
        if (normalized.isBlank()) {
            return;
        }
        if (scriptJson == null || scriptJson.isBlank()) {
            workspaceFileScripts.remove(normalized);
            return;
        }
        workspaceFileScripts.put(normalized, scriptJson);
    }

    private static synchronized void updateWorkspaceFiles(String workspaceFilesJson) {
        workspaceFiles.clear();
        List<String> validPaths = new ArrayList<>();
        if (workspaceFilesJson == null || workspaceFilesJson.isBlank()) {
            workspaceFileScripts.clear();
            selectedWorkspacePath = "";
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(workspaceFilesJson);
            if (!root.isJsonArray()) {
                workspaceFileScripts.clear();
                selectedWorkspacePath = "";
                return;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                String path = normalizeWorkspacePath(getString(obj, "path"));
                if (path.isBlank()) {
                    continue;
                }
                validPaths.add(path);
                workspaceFiles.add(new WorkspaceFileInfo(
                        path,
                        getString(obj, "name"),
                        getString(obj, "type"),
                        getString(obj, "areaTag"),
                        getString(obj, "summary"),
                        getBoolean(obj, "hasPendingPatch"),
                        getString(obj, "revision"),
                        getInt(obj, "originX"),
                        getInt(obj, "originY"),
                        getInt(obj, "originZ"),
                        getBoolean(obj, "hasSize"),
                        getInt(obj, "sizeX"),
                        getInt(obj, "sizeY"),
                        getInt(obj, "sizeZ"),
                        Math.max(0, getInt(obj, "pendingChangedBlocks"))
                ));
            }
        } catch (Exception ignored) {
        }
        workspaceFileScripts.keySet().removeIf(path -> !validPaths.contains(path));
        if (!selectedWorkspacePath.isBlank() && !validPaths.contains(selectedWorkspacePath)) {
            selectedWorkspacePath = "";
        }
    }

    private static WorkspaceFileInfo findWorkspaceFile(String workspacePath) {
        String normalized = normalizeWorkspacePath(workspacePath);
        if (normalized.isBlank()) {
            return null;
        }
        for (WorkspaceFileInfo file : workspaceFiles) {
            if (file != null && normalized.equals(file.path())) {
                return file;
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
                    for (JsonElement element : arr) {
                        if (element == null || !element.isJsonObject()) {
                            continue;
                        }
                        JsonObject obj = element.getAsJsonObject();
                        String id = getString(obj, "id").trim();
                        if (id.isBlank()) {
                            continue;
                        }
                        checkpoints.add(new CheckpointInfo(id, getString(obj, "label"), getString(obj, "revision")));
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

    public static synchronized boolean selectCheckpointById(String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank()) {
            return false;
        }
        String target = checkpointId.trim();
        for (int i = 0; i < checkpoints.size(); i++) {
            CheckpointInfo checkpoint = checkpoints.get(i);
            if (checkpoint != null && target.equals(checkpoint.id())) {
                selectedCheckpointIndex = i;
                return true;
            }
        }
        return false;
    }

    public static synchronized String getRollbackMode() {
        return rollbackMode;
    }

    public static synchronized String toggleRollbackMode() {
        rollbackMode = "workspace_and_session".equals(rollbackMode) ? "session_only" : "workspace_and_session";
        return rollbackMode;
    }

    private static synchronized void clearPreview() {
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

    private static String normalizeWorkspacePath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.replaceAll("/{2,}", "/");
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

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean getBoolean(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return false;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
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

    public record WorkspaceFileInfo(
            String path,
            String name,
            String type,
            String areaTag,
            String summary,
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
