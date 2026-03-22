package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.network.S2CChatResponsePayload;
import com.p2s.network.S2CPatchPreviewPayload;
import com.p2s.network.S2CSessionSyncPayload;
import com.p2s.network.S2CToolBridgePayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SessionManager {
    private static final Gson GSON = new Gson();
    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private static final Map<UUID, String> currentProjectIds = new ConcurrentHashMap<>();
    private static final Map<String, ProjectPersistence.ProjectRecord> loadedProjects = new ConcurrentHashMap<>();
private static final AtomicLong CHECKPOINT_COUNTER = new AtomicLong();
    private static final int MAX_TOOL_JSON_CHARS = 12000;
    private static final int MAX_SUMMARY_CHARS = 4000;
    private static final int MAX_PREVIEW_SUMMARY_CHARS = 512;
    private static final int MAX_PREVIEW_DETAIL_CHARS = 12000;
    private static final int MAX_PREVIEW_OPERATION_LINES = 40;
    private static final int MAX_PREVIEW_WARNING_LINES = 20;
    private static final int MAX_CHECKPOINTS = 24;
    private static final int MAX_WORKSPACE_FILES = 128;
    private static final int MAX_DEBUG_STAGE_BLOCKS = 256;
    private static final int MAX_DEBUG_STAGE_SAMPLES = 16;
    private static final int MAX_DEBUG_STAGE_FAILURES = 12;
    private static final String DEFAULT_PROJECT_NAME = "Current Project";

    private SessionManager() {
    }

    static boolean hasActiveSession(UUID playerId) {
        return playerId != null && sessions.containsKey(playerId);
    }

    public static Session getSession(UUID playerId) {
        return playerId == null ? null : sessions.get(playerId);
    }

    public static void handleSessionAction(ServerPlayer player, String action, String payload) {
        if (player == null || action == null) {
            return;
        }
        switch (action) {
            case "start" -> startSession(player, payload);
            case "end" -> endSession(player);
            case "undo" -> undo(player, parsePath(payload));
            case "redo" -> redo(player, parsePath(payload));
            case "save" -> save(player, payload == null || payload.isBlank() ? null : payload.trim());
            case "apply" -> applyPendingPatch(player, parsePath(payload));
            case "discard" -> discardPendingPatch(player, payload);
            case "workspace_file_create" -> createWorkspaceFileAction(player, payload, false);
            case "workspace_file_create_from_selection" -> createWorkspaceFileAction(player, payload, true);
            case "workspace_file_rename" -> renameWorkspaceFileAction(player, payload);
            case "workspace_file_delete" -> deleteWorkspaceFileAction(player, payload);
            case "create_checkpoint" -> createCheckpoint(player, payload);
            case "rename_checkpoint" -> renameCheckpoint(player, payload);
            case "rollback_checkpoint" -> rollbackCheckpoint(player, payload);
            case "session_select_workspace" -> selectWorkspacePath(player, payload);
            default -> player.displayClientMessage(P2SI18n.tr("message.p2s.session.unknown_action", action), false);
        }
    }

    public static void handleToolBridgeRequest(ServerPlayer player, String requestId, String toolName, String argumentsJson) {
        if (player == null) {
            return;
        }
        String rid = requestId == null ? "" : requestId;
        String normalizedTool = toolName == null ? "" : toolName.trim();
        if (normalizedTool.isBlank()) {
            sendToolBridgeResponse(player, rid, false, null, "Missing tool name");
            return;
        }

        JsonElement arguments = null;
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                arguments = JsonParser.parseString(argumentsJson);
            } catch (Exception e) {
                sendToolBridgeResponse(player, rid, false, null, "Invalid arguments JSON: " + e.getMessage());
                return;
            }
        }

        try {
            JsonObject payload = switch (normalizedTool) {
                case "list_projects" -> handleListProjectsTool();
                case "create_project" -> handleCreateProjectTool(player, arguments);
                case "open_project" -> handleOpenProjectTool(player, arguments);
                case "rename_project" -> handleRenameProjectTool(player, arguments);
                case "delete_project" -> handleDeleteProjectTool(player, arguments);
                case "get_project_state" -> handleGetProjectState(player);
                case "read_workspace_file" -> handleReadWorkspaceFile(player, arguments);
                case "create_workspace_file" -> handleCreateWorkspaceFileTool(player, arguments);
                case "save_workspace_file" -> handleSaveWorkspaceFileTool(player, arguments);
                case "rename_workspace_file" -> handleRenameWorkspaceFileTool(player, arguments);
                case "delete_workspace_file" -> handleDeleteWorkspaceFileTool(player, arguments);
                case "propose_patch" -> handleProposePatchTool(player, arguments);
                case "search_block_ids" -> handleSearchBlockIds(arguments);
                case "debug_stage_blocks" -> handleDebugStageBlocksTool(player, arguments);
                default -> buildToolErrorKey(normalizedTool, "message.p2s.tool.unknown_tool");
            };
            sendSessionSync(player, sessions.get(player.getUUID()));
            ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
            Session session = sessions.get(player.getUUID());
            ProjectPersistence.WorkspaceFileRecord selected = selectedWorkspace(project, session);
            sendPatchPreview(player, selected != null && selected.pendingPatch != null ? selected.pendingPatch.preview : null);
            sendToolBridgeResponse(player, rid, true, payload, null);
        } catch (Exception e) {
            P2SMod.LOGGER.error("Tool bridge failed -> player={}, tool={}", player.getGameProfile().getName(), normalizedTool, e);
            sendToolBridgeResponse(player, rid, false, null, "Tool bridge failed: " + e.getMessage());
        }
    }

    public static Session startSession(ServerPlayer player, String payload) {
        if (player == null) {
            return null;
        }

        JsonObject obj = normalizeArgsObject(payload == null ? null : JsonParser.parseString(payload.isBlank() ? "{}" : payload));
        String projectId = getString(obj, "projectId");
        String sessionId = getString(obj, "sessionId");
        String selectedPath = ProjectPersistence.normalizeWorkspacePath(getString(obj, "selectedWorkspacePath"));

        if (projectId.isBlank()) {
            projectId = currentProjectIds.getOrDefault(player.getUUID(), "");
        }
        if (projectId.isBlank()) {
            player.displayClientMessage(P2SI18n.tr("message.p2s.session.no_project_open"), false);
            sendSessionSync(player, null);
            return null;
        }

        ProjectPersistence.ProjectRecord project = loadProject(projectId);
        if (project == null) {
            player.displayClientMessage(P2SI18n.tr("message.p2s.project.not_found", projectId), false);
            sendSessionSync(player, null);
            return null;
        }

        currentProjectIds.put(player.getUUID(), project.id);
        Session session = new Session();
        session.id = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId.trim();
        session.projectId = project.id;
        session.selectedWorkspacePath = chooseSelectedWorkspacePath(project, selectedPath);
        session.runtimeState = RuntimeState.IDLE;
        sessions.put(player.getUUID(), session);
        sendSessionSync(player, session);
        sendPatchPreview(player, null);
        player.displayClientMessage(P2SI18n.tr("message.p2s.session.opened", session.id), false);
        return session;
    }

    public static void endSession(ServerPlayer player) {
        if (player == null) {
            return;
        }
        sessions.remove(player.getUUID());
        sendPatchPreview(player, null);
        sendSessionSync(player, null);
        player.displayClientMessage(P2SI18n.tr("message.p2s.session.closed"), false);
    }

    public static void undo(ServerPlayer player) {
        undo(player, "");
    }

    public static void redo(ServerPlayer player) {
        redo(player, "");
    }

    public static void save(ServerPlayer player, String name) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        ProjectPersistence.WorkspaceFileRecord workspace = selectedWorkspace(project, session);
        if (workspace == null || workspace.current == null) {
            player.displayClientMessage(P2SI18n.tr("message.p2s.workspace.no_selected_content"), false);
            return;
        }
        String saved = ScriptStorage.saveV2(workspace.path, workspace.current, workspace.summary, name);
        player.displayClientMessage(P2SI18n.tr("message.p2s.workspace.saved_as", saved), false);
    }

    private static JsonObject handleListProjectsTool() {
        JsonObject payload = buildToolSuccess("list_projects");
        JsonArray projects = new JsonArray();
        for (ProjectPersistence.ProjectIndexEntry entry : ProjectPersistence.listProjects()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.id());
            item.addProperty("name", entry.name());
            item.addProperty("description", entry.description());
            item.addProperty("created_at", entry.createdAt());
            item.addProperty("updated_at", entry.updatedAt());
            item.addProperty("workspace_count", entry.workspaceCount());
            JsonObject origin = new JsonObject();
            origin.addProperty("x", entry.originX());
            origin.addProperty("y", entry.originY());
            origin.addProperty("z", entry.originZ());
            item.add("bounds_origin", origin);
            JsonObject size = new JsonObject();
            size.addProperty("x", entry.sizeX());
            size.addProperty("y", entry.sizeY());
            size.addProperty("z", entry.sizeZ());
            item.add("bounds_size", size);
            projects.add(item);
        }
        payload.add("projects", projects);
        if (ProjectPersistence.hasLegacyData()) {
            addToolWarning(payload, "message.p2s.project.legacy_warning");
            payload.addProperty("legacy_data_detected", true);
        }
        return payload;
    }

    private static JsonObject handleCreateProjectTool(ServerPlayer player, JsonElement argsElem) {
        JsonObject args = normalizeArgsObject(argsElem);
        String name = getString(args, "name");
        String description = getString(args, "description");
        SelectionManager.Selection selection = SelectionManager.get(player.getUUID());
        if (selection == null || !selection.isComplete()) {
            return buildToolErrorKey("create_project", "message.p2s.project.create_requires_selection");
        }
        ProjectPersistence.ProjectRecord project = ProjectPersistence.createProject(
                name.isBlank() ? DEFAULT_PROJECT_NAME : name,
                description,
                selection.min(),
                selection.size()
        );
        if (project == null) {
            return buildToolErrorKey("create_project", "message.p2s.project.create_failed");
        }
        loadedProjects.put(project.id, project);
        currentProjectIds.put(player.getUUID(), project.id);
        sessions.remove(player.getUUID());
        JsonObject payload = buildToolSuccess("create_project");
        payload.addProperty("id", project.id);
        payload.addProperty("name", project.name);
        payload.addProperty("description", project.description);
        return payload;
    }

    private static JsonObject handleOpenProjectTool(ServerPlayer player, JsonElement argsElem) {
        JsonObject args = normalizeArgsObject(argsElem);
        String projectId = getString(args, "id");
        if (projectId.isBlank()) {
            return buildToolErrorKey("open_project", "message.p2s.project.open_requires_id");
        }
        ProjectPersistence.ProjectRecord project = loadProject(projectId);
        if (project == null) {
            return buildToolErrorKey("open_project", "message.p2s.project.not_found", projectId);
        }
        currentProjectIds.put(player.getUUID(), project.id);
        sessions.remove(player.getUUID());
        JsonObject payload = buildToolSuccess("open_project");
        payload.addProperty("id", project.id);
        payload.addProperty("name", project.name);
        payload.addProperty("description", project.description);
        payload.addProperty("workspace_count", project.workspaceFiles == null ? 0 : project.workspaceFiles.size());
        return payload;
    }

    private static JsonObject handleRenameProjectTool(ServerPlayer player, JsonElement argsElem) {
        JsonObject args = normalizeArgsObject(argsElem);
        String projectId = getString(args, "id");
        if (projectId.isBlank()) {
            return buildToolErrorKey("rename_project", "message.p2s.project.rename_requires_id");
        }
        ProjectPersistence.ProjectRecord project = loadProject(projectId);
        if (project == null) {
            return buildToolErrorKey("rename_project", "message.p2s.project.not_found", projectId);
        }
        project.name = getString(args, "name");
        project.description = getString(args, "description");
        saveProject(project);
        JsonObject payload = buildToolSuccess("rename_project");
        payload.addProperty("id", project.id);
        payload.addProperty("name", project.name);
        payload.addProperty("description", project.description);
        payload.addProperty("workspace_count", project.workspaceFiles == null ? 0 : project.workspaceFiles.size());
        return payload;
    }

    private static JsonObject handleDeleteProjectTool(ServerPlayer player, JsonElement argsElem) {
        JsonObject args = normalizeArgsObject(argsElem);
        String projectId = getString(args, "id");
        if (projectId.isBlank()) {
            return buildToolError("delete_project", "Project id is required.");
        }
        ProjectPersistence.ProjectRecord project = loadProject(projectId);
        if (project == null) {
            return buildToolErrorKey("delete_project", "message.p2s.project.not_found", projectId);
        }
        if (!ProjectPersistence.deleteProject(projectId)) {
            return buildToolError("delete_project", "Failed deleting project.");
        }

        loadedProjects.remove(projectId);
        Session session = sessions.get(player.getUUID());
        if (session != null && projectId.equals(session.projectId)) {
            sessions.remove(player.getUUID());
        }
        String currentProjectId = currentProjectIds.getOrDefault(player.getUUID(), "");
        if (projectId.equals(currentProjectId)) {
            currentProjectIds.remove(player.getUUID());
        }

        JsonObject payload = buildToolSuccess("delete_project");
        payload.addProperty("id", projectId);
        return payload;
    }

    private static JsonObject handleGetProjectState(ServerPlayer player) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolErrorKey("get_project_state", "message.p2s.project.no_current_project");
        }
        JsonObject payload = buildToolSuccess("get_project_state");
        payload.add("project", buildProjectPayload(project));
        payload.add("workspace_files", buildWorkspaceSummaryArray(project));
        JsonArray pendingPaths = new JsonArray();
        for (ProjectPersistence.WorkspaceFileRecord workspace : project.workspaceFiles.values()) {
            if (workspace != null && workspace.pendingPatch != null) {
                pendingPaths.add(workspace.path);
            }
        }
        payload.add("pending_paths", pendingPaths);
        payload.addProperty("summary", buildProjectSummaryText(project));
        return payload;
    }

    private static JsonObject handleReadWorkspaceFile(ServerPlayer player, JsonElement argsElem) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolErrorKey("read_workspace_file", "message.p2s.project.no_current_project");
        }
        ReadWorkspaceArgs args = parseReadWorkspaceArgs(argsElem);
        if (args.path.isBlank()) {
            return buildToolErrorKey("read_workspace_file", "message.p2s.workspace.read_requires_path");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, args.path);
        if (workspace == null) {
            return buildToolErrorKey("read_workspace_file", "message.p2s.workspace.unknown_path", args.path);
        }
        JsonObject payload = buildToolSuccess("read_workspace_file");
        payload.addProperty("path", workspace.path);
        payload.addProperty("name", workspace.name == null ? "" : workspace.name);
        payload.addProperty("type", workspace.type == null ? "" : workspace.type);
        payload.addProperty("revision", workspace.revision == null ? "" : workspace.revision);
        payload.add("state", buildWorkspaceStatePayload(project, workspace, args.committed));
        return payload;
    }

    private static JsonObject handleCreateWorkspaceFileTool(ServerPlayer player, JsonElement argsElem) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolErrorKey("create_workspace_file", "message.p2s.project.no_current_project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String type = ProjectPersistence.normalizeWorkspaceType(getString(args, "type"));
        String name = getString(args, "name");
        boolean fromSelection = false;
        boolean switchToNew = false;
        try {
            fromSelection = args.has("from_selection") && args.get("from_selection").isJsonPrimitive() && args.get("from_selection").getAsBoolean();
        } catch (Exception ignored) {
            fromSelection = false;
        }
        try {
            switchToNew = args.has("switchToNew") && args.get("switchToNew").isJsonPrimitive() && args.get("switchToNew").getAsBoolean();
        } catch (Exception ignored) {
            switchToNew = false;
        }
        if (path.isBlank()) {
            return buildToolErrorKey("create_workspace_file", "message.p2s.workspace.create_requires_path");
        }
        if (project.workspaceFiles.size() >= MAX_WORKSPACE_FILES) {
            return buildToolErrorKey("create_workspace_file", "message.p2s.workspace.max_files");
        }
        if (project.workspaceFiles.containsKey(path)) {
            return buildToolErrorKey("create_workspace_file", "message.p2s.workspace.path_exists", path);
        }
        ProjectPersistence.WorkspaceFileRecord workspace = new ProjectPersistence.WorkspaceFileRecord();
        workspace.path = path;
        workspace.name = name == null || name.isBlank() ? ProjectPersistence.leafName(path) : name.trim();
        workspace.type = type;
        workspace.revision = "rev-0";
        workspace.current = new StructureBuilder.VbsScriptV2();
        workspace.metadata = new JsonObject();
        if (fromSelection) {
            SelectionManager.Selection selection = SelectionManager.get(player.getUUID());
            if (selection == null || !selection.isComplete()) {
                return buildToolErrorKey("create_workspace_file", "message.p2s.workspace.create_from_selection_requires_selection");
            }
            if (!isSelectionWithinProject(selection, project)) {
                return buildToolErrorKey("create_workspace_file", "message.p2s.workspace.selection_out_of_bounds");
            }
            workspace.origin = ProjectPersistence.Vec3Data.of(selection.min());
            workspace.size = ProjectPersistence.Vec3Data.of(selection.size());
        }
        project.workspaceFiles.put(path, workspace);
        saveProject(project);
        Session session = sessions.get(player.getUUID());
        if (session != null && (switchToNew || session.selectedWorkspacePath == null || session.selectedWorkspacePath.isBlank())) {
            session.selectedWorkspacePath = path;
        }
        JsonObject payload = buildToolSuccess("create_workspace_file");
        payload.addProperty("path", path);
        payload.addProperty("name", workspace.name);
        payload.addProperty("type", workspace.type);
        return payload;
    }

    private static JsonObject handleSaveWorkspaceFileTool(ServerPlayer player, JsonElement argsElem) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolErrorKey("save_workspace_file", "message.p2s.project.no_current_project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String workspaceToml = getString(args, "workspace_toml");
        if (path.isBlank()) {
            return buildToolErrorKey("save_workspace_file", "message.p2s.workspace.save_requires_path");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            return buildToolErrorKey("save_workspace_file", "message.p2s.workspace.unknown_path", path);
        }
        if (workspaceToml == null || workspaceToml.isBlank()) {
            return buildToolErrorKey("save_workspace_file", "message.p2s.workspace.invalid_workspace_toml", "workspace_toml is required");
        }
        StructureBuilder.VbsScriptV2 script;
        try {
            WorkspaceTomlCodec.WorkspaceTomlDocument document = WorkspaceTomlCodec.parse(workspaceToml);
            WorkspaceTomlCodec.applyToWorkspace(document, workspace);
            script = WorkspaceTomlCodec.toScript(document);
        } catch (Exception e) {
            return buildToolErrorKey("save_workspace_file", "message.p2s.workspace.invalid_workspace_toml", e.getMessage());
        }
        if (script == null) {
            script = new StructureBuilder.VbsScriptV2();
        }
        workspace.current = script;
        workspace.summary = partsSummary(script);
        workspace.revision = nextRevision();
        workspace.pendingPatch = null;
        workspace.undoStack.clear();
        workspace.redoStack.clear();
        saveProject(project);
        JsonObject payload = buildToolSuccess("save_workspace_file");
        payload.addProperty("path", path);
        payload.addProperty("revision", workspace.revision == null ? "" : workspace.revision);
        payload.addProperty("summary", workspace.summary == null ? "" : workspace.summary);
        return payload;
    }

    private static JsonObject handleRenameWorkspaceFileTool(ServerPlayer player, JsonElement argsElem) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolErrorKey("rename_workspace_file", "message.p2s.project.no_current_project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String newPath = ProjectPersistence.normalizeWorkspacePath(getString(args, "new_path"));
        if (path.isBlank() || newPath.isBlank()) {
            return buildToolErrorKey("rename_workspace_file", "message.p2s.workspace.rename_requires_paths");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            return buildToolErrorKey("rename_workspace_file", "message.p2s.workspace.unknown_path", path);
        }
        if (!path.equals(newPath) && project.workspaceFiles.containsKey(newPath)) {
            return buildToolErrorKey("rename_workspace_file", "message.p2s.workspace.target_path_exists", newPath);
        }
        project.workspaceFiles.remove(path);
        workspace.path = newPath;
        workspace.name = ProjectPersistence.leafName(newPath);
        project.workspaceFiles.put(newPath, workspace);
        Session session = sessions.get(player.getUUID());
        if (session != null && path.equals(session.selectedWorkspacePath)) {
            session.selectedWorkspacePath = newPath;
        }
        saveProject(project);
        JsonObject payload = buildToolSuccess("rename_workspace_file");
        payload.addProperty("path", path);
        payload.addProperty("new_path", newPath);
        return payload;
    }

    private static JsonObject handleDeleteWorkspaceFileTool(ServerPlayer player, JsonElement argsElem) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolErrorKey("delete_workspace_file", "message.p2s.project.no_current_project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        if (path.isBlank()) {
            return buildToolErrorKey("delete_workspace_file", "message.p2s.workspace.delete_requires_path");
        }
        if (project.workspaceFiles.size() <= 1) {
            return buildToolErrorKey("delete_workspace_file", "message.p2s.workspace.delete_last_forbidden");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            return buildToolErrorKey("delete_workspace_file", "message.p2s.workspace.unknown_path", path);
        }
        if (workspace.pendingPatch != null) {
            return buildToolErrorKey("delete_workspace_file", "message.p2s.workspace.delete_pending_forbidden");
        }
        project.workspaceFiles.remove(path);
        Session session = sessions.get(player.getUUID());
        if (session != null && path.equals(session.selectedWorkspacePath)) {
            session.selectedWorkspacePath = chooseSelectedWorkspacePath(project, "");
        }
        saveProject(project);
        JsonObject payload = buildToolSuccess("delete_workspace_file");
        payload.addProperty("path", path);
        payload.addProperty("deleted", true);
        return payload;
    }

    private static JsonObject handleProposePatchTool(ServerPlayer player, JsonElement argsElem) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        Session session = sessions.get(player.getUUID());
        if (project == null || session == null) {
            return buildToolErrorKey("propose_patch", "message.p2s.session.no_active_project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        if (path.isBlank()) {
            return buildToolErrorKey("propose_patch", "message.p2s.patch.propose_requires_path");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            return buildToolErrorKey("propose_patch", "message.p2s.workspace.unknown_path", path);
        }
        PatchModels.StructurePatch patch = parsePatchArguments(argsElem);
        if (patch == null) {
            return buildToolErrorKey("propose_patch", "message.p2s.patch.invalid_arguments");
        }
        normalizePatch(patch);

        String committedRevision = workspace.revision == null ? "" : workspace.revision;
        String stagedRevision = committedRevision;
        StructureBuilder.VbsScriptV2 stagedBase = copyScript(workspace.current);
        if (workspace.pendingPatch != null && workspace.pendingPatch.nextScript != null) {
            stagedBase = copyScript(workspace.pendingPatch.nextScript);
            if (workspace.pendingPatch.revisionAfter != null && !workspace.pendingPatch.revisionAfter.isBlank()) {
                stagedRevision = workspace.pendingPatch.revisionAfter;
            }
        }

        String patchBase = patch.baseRevision == null ? "" : patch.baseRevision.trim();
        if (!patchBase.isBlank() && !patchBase.equals(committedRevision) && !patchBase.equals(stagedRevision)) {
            return buildToolErrorKey("propose_patch", "message.p2s.patch.base_revision_mismatch");
        }

        StructureBuilder.VbsScriptV2 committedBase = copyScript(workspace.current);
        StructurePatchEngine.PatchApplyResult applyResult = StructurePatchEngine.applyPatchToModel(stagedBase, patch);
        if (!applyResult.ok && applyResult.error != null) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("verification_failed", true);
            payload.addProperty("operation_index", applyResult.error.operationIndex);
            payload.addProperty("op", applyResult.error.op == null ? "" : applyResult.error.op);
            payload.addProperty("part", applyResult.error.part == null ? "" : applyResult.error.part);
            payload.addProperty("error", applyResult.error.error == null ? "" : applyResult.error.error);
            if (applyResult.error.expected != null) {
                payload.add("expected", GSON.toJsonTree(applyResult.error.expected));
            }
            if (applyResult.error.actual != null) {
                payload.add("actual", GSON.toJsonTree(applyResult.error.actual));
            }
            payload.addProperty("hint", applyResult.error.hint == null ? "" : applyResult.error.hint);
            return payload;
        }

        StructureBuilder.VbsScriptV2 next = applyResult.result;
        StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(committedBase, next);
        PatchModels.StructurePatch mergedPatch = mergePatches(
                workspace.pendingPatch == null ? null : workspace.pendingPatch.patch,
                patch,
                committedRevision
        );
        PatchRuntimeSettings settings = runtimeSettings(args);
        PatchModels.ValidationResult validation = PatchValidator.validate(
                mergedPatch,
                committedRevision,
                workspaceSize(project, workspace),
                next,
                diff,
                settings.maxPatchOps(),
                settings.maxBlocksPerCommit()
        );
        if (!validation.ok) {
            JsonObject payload = buildToolError("propose_patch", String.join("; ", validation.errors));
            JsonArray errors = new JsonArray();
            for (String error : validation.errors) {
                errors.add(error);
            }
            payload.add("errors", errors);
            payload.addProperty("risk", validation.riskLevel);
            payload.addProperty("changed", validation.estimatedChangedBlocks);
            return payload;
        }

        ProjectPersistence.PendingPatchRecord pending = new ProjectPersistence.PendingPatchRecord();
        pending.patch = mergedPatch;
        pending.baseScript = committedBase;
        pending.nextScript = next;
        pending.preview = buildPatchPreview(mergedPatch, validation, diff, committedRevision);
        pending.revisionBefore = committedRevision;
        pending.revisionAfter = nextRevision();
        validation.requiresConfirm = requiresConfirm(pending.preview.changedBlocks, settings);
        workspace.pendingPatch = pending;
        session.runtimeState = RuntimeState.AWAITING_CONFIRM;
        session.selectedWorkspacePath = path;
        saveProject(project);

        JsonObject payload = buildToolSuccess("propose_patch");
        payload.addProperty("path", path);
        payload.addProperty("preview", pending.preview.summary);
        payload.addProperty("changed", pending.preview.changedBlocks);
        payload.addProperty("risk", pending.preview.riskLevel);
        payload.addProperty("requires_confirm", validation.requiresConfirm);
        if (!validation.warnings.isEmpty()) {
            JsonArray warnings = new JsonArray();
            for (String warning : validation.warnings) {
                warnings.add(warning);
            }
            payload.add("warnings", warnings);
            payload.addProperty("warning_count", validation.warnings.size());
        }
        return payload;
    }

    private static JsonObject handleSearchBlockIds(JsonElement argsElem) {
        SearchBlockArgs args = parseSearchBlockArgs(argsElem);
        if (args == null || args.query == null || args.query.isBlank()) {
            return buildToolErrorKey("search_block_ids", "message.p2s.search.missing_query");
        }
        List<String> matches = StructureBuilder.searchBlockIds(args.query, args.limit);
        String closest = StructureBuilder.closestBlockId(args.query);
        JsonObject payload = buildToolSuccess("search_block_ids");
        payload.addProperty("query", args.query);
        payload.addProperty("limit", args.limit);
        JsonArray matchArray = new JsonArray();
        for (String match : matches) {
            matchArray.add(match);
        }
        payload.add("matches", matchArray);
        if (closest != null && !closest.isBlank()) {
            payload.addProperty("closest", closest);
        }
        if (matches.isEmpty()) {
            addToolWarning(payload, "message.p2s.search.no_matches");
        }
        return payload;
    }

    private static JsonObject handleDebugStageBlocksTool(ServerPlayer player, JsonElement argsElem) {
        if (!P2SMod.DEBUG) {
            return buildToolError("debug_stage_blocks", "Debug mode is disabled.");
        }
        if (player == null) {
            return buildToolError("debug_stage_blocks", "Player context is required.");
        }

        SelectionManager.Selection selection = SelectionManager.get(player.getUUID());
        if (selection == null || !selection.isComplete()) {
            return buildToolError("debug_stage_blocks", "Selection is incomplete. Set both points before staging debug blocks.");
        }

        JsonObject args = normalizeArgsObject(argsElem);
        boolean inspectOnly = Boolean.TRUE.equals(getBoolean(args, "inspect_only"));
        boolean stopOnError = !Boolean.FALSE.equals(getBoolean(args, "stop_on_error"));
        String label = getString(args, "label");

        BlockPos min = selection.min();
        BlockPos max = selection.max();
        Vec3i size = selection.size();

        JsonObject payload = buildToolSuccess("debug_stage_blocks");
        if (!label.isBlank()) {
            payload.addProperty("label", label);
        }
        payload.addProperty("inspect_only", inspectOnly);
        payload.addProperty("stop_on_error", stopOnError);
        payload.addProperty("max_placements", MAX_DEBUG_STAGE_BLOCKS);
        payload.add("origin", buildVec3Payload(min.getX(), min.getY(), min.getZ()));
        payload.add("selection_min", buildVec3Payload(min.getX(), min.getY(), min.getZ()));
        payload.add("selection_max", buildVec3Payload(max.getX(), max.getY(), max.getZ()));
        payload.add("selection_size", buildVec3Payload(size.getX(), size.getY(), size.getZ()));

        if (inspectOnly) {
            payload.addProperty("requested_count", 0);
            payload.addProperty("executed_count", 0);
            payload.addProperty("failed_count", 0);
            payload.addProperty("summary", "Selection ready for debug staging.");
            return payload;
        }

        if (!args.has("placements") || !args.get("placements").isJsonArray()) {
            return buildToolError("debug_stage_blocks", "Missing placements array.");
        }

        JsonArray placements = args.getAsJsonArray("placements");
        if (placements.size() == 0) {
            return buildToolError("debug_stage_blocks", "placements must contain at least one item.");
        }
        if (placements.size() > MAX_DEBUG_STAGE_BLOCKS) {
            return buildToolError("debug_stage_blocks", "Too many placements (" + placements.size() + " > " + MAX_DEBUG_STAGE_BLOCKS + ").");
        }

        JsonArray placementsSample = new JsonArray();
        JsonArray commandSample = new JsonArray();
        JsonArray failures = new JsonArray();
        int executedCount = 0;
        int failedCount = 0;
        boolean stoppedEarly = false;

        for (int index = 0; index < placements.size(); index++) {
            JsonElement element = placements.get(index);
            JsonObject placement = element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
            Integer dx = getInt(placement, "dx");
            Integer dy = getInt(placement, "dy");
            Integer dz = getInt(placement, "dz");
            String blockState = getString(placement, "block_state").trim();
            String mode = normalizeSetblockMode(getString(placement, "mode"));

            String validationError = "";
            if (placement == null) {
                validationError = "Placement must be an object.";
            } else if (dx == null || dy == null || dz == null) {
                validationError = "Placement requires integer dx, dy, dz.";
            } else if (blockState.isBlank()) {
                validationError = "Placement requires non-empty block_state.";
            } else if (mode.isBlank()) {
                validationError = "Placement mode must be replace, keep, or destroy.";
            }

            BlockPos worldPos = validationError.isBlank()
                    ? new BlockPos(min.getX() + dx, min.getY() + dy, min.getZ() + dz)
                    : null;
            if (validationError.isBlank() && !isWithinSelection(selection, worldPos)) {
                validationError = "Placement falls outside the current selection.";
            }

            String command = validationError.isBlank() ? buildSetblockCommand(worldPos, blockState, mode) : "";
            if (!validationError.isBlank()) {
                failedCount += 1;
                addDebugStageFailure(failures, index, validationError, placement, command);
                if (stopOnError) {
                    stoppedEarly = true;
                    break;
                }
                continue;
            }

            try {
                executeDebugSetblock(player, command);
                executedCount += 1;
                if (placementsSample.size() < MAX_DEBUG_STAGE_SAMPLES) {
                    JsonObject item = new JsonObject();
                    item.addProperty("index", index);
                    item.add("relative", buildVec3Payload(dx, dy, dz));
                    item.add("world", buildVec3Payload(worldPos.getX(), worldPos.getY(), worldPos.getZ()));
                    item.addProperty("block_state", blockState);
                    item.addProperty("mode", mode);
                    placementsSample.add(item);
                }
                if (commandSample.size() < MAX_DEBUG_STAGE_SAMPLES) {
                    commandSample.add(command);
                }
            } catch (Exception e) {
                failedCount += 1;
                addDebugStageFailure(failures, index, e.getMessage(), placement, command);
                if (stopOnError) {
                    stoppedEarly = true;
                    break;
                }
            }
        }

        payload.addProperty("requested_count", placements.size());
        payload.addProperty("executed_count", executedCount);
        payload.addProperty("failed_count", failedCount);
        payload.addProperty("stopped_early", stoppedEarly);
        payload.add("placements_sample", placementsSample);
        payload.add("commands_sample", commandSample);
        if (failures.size() > 0) {
            payload.add("failures", failures);
        }
        payload.addProperty(
                "summary",
                "Staged " + executedCount + " placement(s)"
                        + (failedCount > 0 ? ", failed " + failedCount : "")
                        + (stoppedEarly ? ", stopped early" : "") + "."
        );
        return payload;
    }

    private static JsonObject buildVec3Payload(int x, int y, int z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }

    private static boolean isWithinSelection(SelectionManager.Selection selection, BlockPos pos) {
        if (selection == null || pos == null || !selection.isComplete()) {
            return false;
        }
        BlockPos min = selection.min();
        BlockPos max = selection.max();
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private static String normalizeSetblockMode(String raw) {
        String value = raw == null || raw.isBlank() ? "replace" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "replace", "keep", "destroy" -> value;
            default -> "";
        };
    }

    private static String buildSetblockCommand(BlockPos pos, String blockState, String mode) {
        String actualMode = normalizeSetblockMode(mode);
        String suffix = "replace".equals(actualMode) ? "" : " " + actualMode;
        return "setblock "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " "
                + blockState + suffix;
    }

    private static void executeDebugSetblock(ServerPlayer player, String command) {
        if (player == null) {
            throw new IllegalStateException("Player context is required.");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Command is blank.");
        }
        var server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("Server is unavailable.");
        }
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(2)
                .withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }

    private static void addDebugStageFailure(JsonArray failures, int index, String error, JsonObject placement, String command) {
        if (failures == null || failures.size() >= MAX_DEBUG_STAGE_FAILURES) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("index", index);
        item.addProperty("error", error == null ? "Execution failed." : error);
        if (placement != null) {
            item.add("placement", placement.deepCopy());
        }
        if (command != null && !command.isBlank()) {
            item.addProperty("command", command);
        }
        failures.add(item);
    }

    private static void createWorkspaceFileAction(ServerPlayer player, String payload, boolean fromSelection) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        Session session = sessions.get(player.getUUID());
        if (project == null || session == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String type = ProjectPersistence.normalizeWorkspaceType(getString(args, "type"));
        if (path.isBlank()) {
            displayMessage(player, "message.p2s.workspace.create_requires_path");
            return;
        }
        if (project.workspaceFiles.size() >= MAX_WORKSPACE_FILES) {
            displayMessage(player, "message.p2s.workspace.max_files");
            return;
        }
        if (project.workspaceFiles.containsKey(path)) {
            displayMessage(player, "message.p2s.workspace.path_exists", path);
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = new ProjectPersistence.WorkspaceFileRecord();
        workspace.path = path;
        workspace.name = ProjectPersistence.leafName(path);
        workspace.type = type;
        workspace.current = new StructureBuilder.VbsScriptV2();
        workspace.metadata = new JsonObject();
        if (fromSelection) {
            SelectionManager.Selection selection = SelectionManager.get(player.getUUID());
            if (selection == null || !selection.isComplete()) {
                displayMessage(player, "message.p2s.workspace.create_from_selection_requires_selection");
                return;
            }
            if (!isSelectionWithinProject(selection, project)) {
                displayMessage(player, "message.p2s.workspace.selection_out_of_bounds");
                return;
            }
            workspace.origin = ProjectPersistence.Vec3Data.of(selection.min());
            workspace.size = ProjectPersistence.Vec3Data.of(selection.size());
        }
        project.workspaceFiles.put(path, workspace);
        session.selectedWorkspacePath = path;
        saveProject(project);
        sendSessionSync(player, session);
        sendPatchPreview(player, null);
        displayMessage(player, "message.p2s.workspace.created", path);
    }

    private static void renameWorkspaceFileAction(ServerPlayer player, String payload) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        Session session = sessions.get(player.getUUID());
        if (project == null || session == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String newPath = ProjectPersistence.normalizeWorkspacePath(getString(args, "new_path"));
        if (path.isBlank() || newPath.isBlank()) {
            displayMessage(player, "message.p2s.workspace.rename_requires_paths");
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            displayMessage(player, "message.p2s.workspace.unknown_path", path);
            return;
        }
        if (!path.equals(newPath) && project.workspaceFiles.containsKey(newPath)) {
            displayMessage(player, "message.p2s.workspace.target_path_exists", newPath);
            return;
        }
        project.workspaceFiles.remove(path);
        workspace.path = newPath;
        workspace.name = ProjectPersistence.leafName(newPath);
        project.workspaceFiles.put(newPath, workspace);
        if (path.equals(session.selectedWorkspacePath)) {
            session.selectedWorkspacePath = newPath;
        }
        saveProject(project);
        sendSessionSync(player, session);
        displayMessage(player, "message.p2s.workspace.renamed", newPath);
    }

    private static void deleteWorkspaceFileAction(ServerPlayer player, String payload) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        Session session = sessions.get(player.getUUID());
        if (project == null || session == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        if (path.isBlank()) {
            displayMessage(player, "message.p2s.workspace.delete_requires_path");
            return;
        }
        if (project.workspaceFiles.size() <= 1) {
            displayMessage(player, "message.p2s.workspace.delete_last_forbidden");
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            displayMessage(player, "message.p2s.workspace.unknown_path", path);
            return;
        }
        if (workspace.pendingPatch != null) {
            displayMessage(player, "message.p2s.workspace.delete_pending_forbidden");
            return;
        }
        project.workspaceFiles.remove(path);
        if (path.equals(session.selectedWorkspacePath)) {
            session.selectedWorkspacePath = chooseSelectedWorkspacePath(project, "");
        }
        saveProject(project);
        sendSessionSync(player, session);
        sendPatchPreview(player, null);
        displayMessage(player, "message.p2s.workspace.deleted", path);
    }

    private static void selectWorkspacePath(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            return;
        }
        String path = parsePath(payload);
        if (path.isBlank()) {
            return;
        }
        if (!project.workspaceFiles.containsKey(path)) {
            displayMessage(player, "message.p2s.workspace.unknown_path", path);
            return;
        }
        session.selectedWorkspacePath = path;
        sendSessionSync(player, session);
        ProjectPersistence.WorkspaceFileRecord workspace = selectedWorkspace(project, session);
        sendPatchPreview(player, workspace != null && workspace.pendingPatch != null ? workspace.pendingPatch.preview : null);
    }

    private static void applyPendingPatch(ServerPlayer player, String path) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.pendingPatch == null) {
            displayMessage(player, "message.p2s.patch.no_pending_patch");
            return;
        }

        StructureBuilder.VbsScriptV2 before = copyScript(workspace.current);
        StructureBuilder.VbsScriptV2 after = copyScript(workspace.pendingPatch.nextScript);
        StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(before, after);
        StructurePatchEngine.applyBlockOps(player.serverLevel(), workspaceOrigin(project, workspace), diff.forwardOps);

        ProjectPersistence.CommitRecord commit = new ProjectPersistence.CommitRecord();
        commit.revisionBefore = workspace.pendingPatch.revisionBefore;
        commit.revisionAfter = workspace.pendingPatch.revisionAfter;
        commit.beforeScript = before;
        commit.afterScript = after;
        commit.summary = workspace.pendingPatch.preview == null ? "patch" : workspace.pendingPatch.preview.summary;
        commit.patch = workspace.pendingPatch.patch;
        pushCommit(workspace.undoStack, commit);
        workspace.redoStack.clear();
        workspace.current = after;
        workspace.revision = workspace.pendingPatch.revisionAfter;
        workspace.pendingPatch = null;
        addCheckpoint(workspace, "apply:" + commit.summary, workspace.revision);
        session.runtimeState = RuntimeState.IDLE;
        saveProject(project);
        sendChatResponseLocalized(player, "message.p2s.patch.applied", true, "committed", commit.summary);
        sendPatchPreview(player, null);
        sendSessionSync(player, session);
    }

    private static void discardPendingPatch(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String reason = getString(args, "reason");
        if (reason.isBlank() && (payload != null && !payload.isBlank()) && !payload.trim().startsWith("{")) {
            reason = payload.trim();
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.pendingPatch == null) {
            displayMessage(player, "message.p2s.patch.no_pending_patch");
            return;
        }
        workspace.pendingPatch = null;
        session.runtimeState = RuntimeState.IDLE;
        saveProject(project);
        sendPatchPreview(player, null);
        sendSessionSync(player, session);
        if (reason == null || reason.isBlank()) {
            sendChatResponseLocalized(player, "message.p2s.patch.discarded", false, "cancelled");
        } else {
            sendChatResponseLocalized(player, "message.p2s.patch.discarded_with_reason", false, "cancelled", reason);
        }
    }

    private static void undo(ServerPlayer player, String path) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.undoStack == null || workspace.undoStack.isEmpty()) {
            displayMessage(player, "message.p2s.history.nothing_to_undo");
            return;
        }
        ProjectPersistence.CommitRecord commit = popCommit(workspace.undoStack);
        if (commit == null) {
            displayMessage(player, "message.p2s.history.nothing_to_undo");
            return;
        }
        StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(copyScript(workspace.current), copyScript(commit.beforeScript));
        StructurePatchEngine.applyBlockOps(player.serverLevel(), workspaceOrigin(project, workspace), diff.forwardOps);
        workspace.current = copyScript(commit.beforeScript);
        workspace.revision = commit.revisionBefore;
        pushCommit(workspace.redoStack, commit);
        workspace.pendingPatch = null;
        session.runtimeState = RuntimeState.IDLE;
        addCheckpoint(workspace, "undo:" + safeSummary(commit.summary), workspace.revision);
        saveProject(project);
        sendPatchPreview(player, null);
        sendSessionSync(player, session);
        displayMessage(player, "message.p2s.history.undo_applied", safeSummary(commit.summary));
    }

    private static void redo(ServerPlayer player, String path) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.redoStack == null || workspace.redoStack.isEmpty()) {
            displayMessage(player, "message.p2s.history.nothing_to_redo");
            return;
        }
        ProjectPersistence.CommitRecord commit = popCommit(workspace.redoStack);
        if (commit == null) {
            displayMessage(player, "message.p2s.history.nothing_to_redo");
            return;
        }
        StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(copyScript(workspace.current), copyScript(commit.afterScript));
        StructurePatchEngine.applyBlockOps(player.serverLevel(), workspaceOrigin(project, workspace), diff.forwardOps);
        workspace.current = copyScript(commit.afterScript);
        workspace.revision = commit.revisionAfter;
        pushCommit(workspace.undoStack, commit);
        workspace.pendingPatch = null;
        session.runtimeState = RuntimeState.IDLE;
        addCheckpoint(workspace, "redo:" + safeSummary(commit.summary), workspace.revision);
        saveProject(project);
        sendPatchPreview(player, null);
        sendSessionSync(player, session);
        displayMessage(player, "message.p2s.history.redo_applied", safeSummary(commit.summary));
    }

    private static void createCheckpoint(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String label = getString(args, "label");
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null) {
            displayMessage(player, "message.p2s.workspace.not_found");
            return;
        }
        addCheckpoint(workspace, label.isBlank() ? "manual" : label, workspace.revision);
        saveProject(project);
        sendSessionSync(player, session);
        displayMessage(player, "message.p2s.checkpoint.created");
    }

    private static void renameCheckpoint(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String checkpointId = getString(args, "checkpoint_id");
        String label = getString(args, "label");
        if (label.isBlank()) {
            displayMessage(player, "message.p2s.checkpoint.rename_requires_label");
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.checkpoints == null || workspace.checkpoints.isEmpty()) {
            displayMessage(player, "message.p2s.checkpoint.none_available");
            return;
        }
        ProjectPersistence.CheckpointRecord checkpoint = findCheckpoint(workspace, checkpointId);
        if (checkpoint == null) {
            displayMessage(player, "message.p2s.checkpoint.not_found");
            return;
        }
        checkpoint.label = label.trim();
        saveProject(project);
        sendSessionSync(player, session);
        displayMessage(player, "message.p2s.checkpoint.renamed", checkpoint.label);
    }

    private static void rollbackCheckpoint(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            displayMessage(player, "message.p2s.session.no_active_project");
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String checkpointId = getString(args, "checkpoint_id");
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.checkpoints == null || workspace.checkpoints.isEmpty()) {
            displayMessage(player, "message.p2s.checkpoint.none_available");
            return;
        }
        ProjectPersistence.CheckpointRecord checkpoint = findCheckpoint(workspace, checkpointId);
        if (checkpoint == null || checkpoint.script == null) {
            displayMessage(player, "message.p2s.checkpoint.not_found");
            return;
        }
        StructureBuilder.VbsScriptV2 before = copyScript(workspace.current);
        StructureBuilder.VbsScriptV2 after = copyScript(checkpoint.script);
        StructurePatchEngine.DiffResult diff = StructurePatchEngine.diff(before, after);
        StructurePatchEngine.applyBlockOps(player.serverLevel(), workspaceOrigin(project, workspace), diff.forwardOps);
        ProjectPersistence.CommitRecord commit = new ProjectPersistence.CommitRecord();
        commit.revisionBefore = workspace.revision;
        commit.revisionAfter = checkpoint.revision == null || checkpoint.revision.isBlank() ? nextRevision() : checkpoint.revision;
        commit.beforeScript = before;
        commit.afterScript = after;
        commit.summary = "rollback:" + (checkpoint.label == null ? checkpoint.id : checkpoint.label);
        pushCommit(workspace.undoStack, commit);
        workspace.redoStack.clear();
        workspace.pendingPatch = null;
        workspace.current = after;
        workspace.revision = commit.revisionAfter;
        addCheckpoint(workspace, commit.summary, workspace.revision);
        saveProject(project);
        sendPatchPreview(player, null);
        sendSessionSync(player, session);
        displayMessage(player, "message.p2s.checkpoint.rolled_back", checkpoint.id);
    }

    private static ProjectPersistence.CheckpointRecord findCheckpoint(ProjectPersistence.WorkspaceFileRecord workspace, String checkpointId) {
        if (workspace == null || workspace.checkpoints == null || workspace.checkpoints.isEmpty()) {
            return null;
        }
        if (checkpointId == null || checkpointId.isBlank()) {
            return workspace.checkpoints.get(workspace.checkpoints.size() - 1);
        }
        String target = checkpointId.trim();
        for (ProjectPersistence.CheckpointRecord checkpoint : workspace.checkpoints) {
            if (checkpoint != null && target.equals(checkpoint.id)) {
                return checkpoint;
            }
        }
        return null;
    }

    private static void addCheckpoint(ProjectPersistence.WorkspaceFileRecord workspace, String label, String revision) {
        if (workspace == null) {
            return;
        }
        if (workspace.checkpoints == null) {
            workspace.checkpoints = new ArrayList<>();
        }
        ProjectPersistence.CheckpointRecord checkpoint = new ProjectPersistence.CheckpointRecord();
        checkpoint.id = "cp-" + Long.toString(System.currentTimeMillis(), 36) + "-" + CHECKPOINT_COUNTER.incrementAndGet();
        checkpoint.label = label == null ? "checkpoint" : label.trim();
        checkpoint.revision = revision == null ? "" : revision;
        checkpoint.script = copyScript(workspace.current);
        checkpoint.createdAt = System.currentTimeMillis();
        workspace.checkpoints.add(checkpoint);
        while (workspace.checkpoints.size() > MAX_CHECKPOINTS) {
            workspace.checkpoints.remove(0);
        }
    }

    private static JsonObject buildProjectPayload(ProjectPersistence.ProjectRecord project) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", project.id == null ? "" : project.id);
        obj.addProperty("name", project.name == null ? "" : project.name);
        obj.addProperty("description", project.description == null ? "" : project.description);
        obj.addProperty("created_at", project.createdAt);
        obj.addProperty("updated_at", project.updatedAt);
        JsonObject origin = new JsonObject();
        origin.addProperty("x", project.boundsOrigin == null ? 0 : project.boundsOrigin.x);
        origin.addProperty("y", project.boundsOrigin == null ? 0 : project.boundsOrigin.y);
        origin.addProperty("z", project.boundsOrigin == null ? 0 : project.boundsOrigin.z);
        obj.add("bounds_origin", origin);
        JsonObject size = new JsonObject();
        size.addProperty("x", project.boundsSize == null ? 0 : project.boundsSize.x);
        size.addProperty("y", project.boundsSize == null ? 0 : project.boundsSize.y);
        size.addProperty("z", project.boundsSize == null ? 0 : project.boundsSize.z);
        obj.add("bounds_size", size);
        obj.add("metadata", project.metadata == null ? new JsonObject() : project.metadata.deepCopy());
        return obj;
    }

    private static JsonArray buildWorkspaceSummaryArray(ProjectPersistence.ProjectRecord project) {
        JsonArray arr = new JsonArray();
        if (project == null || project.workspaceFiles == null) {
            return arr;
        }
        for (ProjectPersistence.WorkspaceFileRecord workspace : project.workspaceFiles.values()) {
            if (workspace != null) {
                arr.add(buildWorkspaceSummaryItem(workspace));
            }
        }
        return arr;
    }

    private static JsonObject buildWorkspaceSummaryItem(ProjectPersistence.WorkspaceFileRecord workspace) {
        JsonObject item = new JsonObject();
        item.addProperty("path", workspace.path == null ? "" : workspace.path);
        item.addProperty("name", workspace.name == null ? "" : workspace.name);
        item.addProperty("type", workspace.type == null ? "" : workspace.type);
        item.addProperty("areaTag", workspace.areaTag == null ? "" : workspace.areaTag);
        item.addProperty("summary", workspace.summary == null ? "" : workspace.summary);
        item.addProperty("revision", workspace.revision == null ? "" : workspace.revision);
        boolean hasPending = workspace.pendingPatch != null;
        item.addProperty("hasPendingPatch", hasPending);
        item.addProperty("pendingChangedBlocks", hasPending && workspace.pendingPatch.preview != null ? workspace.pendingPatch.preview.changedBlocks : 0);
        boolean hasSize = workspace.size != null;
        item.addProperty("hasSize", hasSize);
        item.addProperty("sizeX", hasSize ? workspace.size.x : 0);
        item.addProperty("sizeY", hasSize ? workspace.size.y : 0);
        item.addProperty("sizeZ", hasSize ? workspace.size.z : 0);
        item.addProperty("originX", workspace.origin == null ? 0 : workspace.origin.x);
        item.addProperty("originY", workspace.origin == null ? 0 : workspace.origin.y);
        item.addProperty("originZ", workspace.origin == null ? 0 : workspace.origin.z);
        return item;
    }

    private static JsonObject buildWorkspaceStatePayload(ProjectPersistence.ProjectRecord project, ProjectPersistence.WorkspaceFileRecord workspace, boolean committedOnly) {
        JsonObject payload = new JsonObject();
        payload.addProperty("path", workspace.path == null ? "" : workspace.path);
        payload.addProperty("name", workspace.name == null ? "" : workspace.name);
        payload.addProperty("format", "toml");
        if (workspace.origin != null) {
            JsonObject origin = new JsonObject();
            origin.addProperty("x", workspace.origin.x);
            origin.addProperty("y", workspace.origin.y);
            origin.addProperty("z", workspace.origin.z);
            payload.add("origin", origin);
        }
        if (workspace.size != null) {
            JsonObject size = new JsonObject();
            size.addProperty("x", workspace.size.x);
            size.addProperty("y", workspace.size.y);
            size.addProperty("z", workspace.size.z);
            payload.add("size", size);
        }
        StructureBuilder.VbsScriptV2 effective = committedOnly || workspace.pendingPatch == null || workspace.pendingPatch.nextScript == null
                ? workspace.current
                : workspace.pendingPatch.nextScript;
        String workspaceToml = "";
        try {
            workspaceToml = WorkspaceTomlCodec.write(WorkspaceTomlCodec.fromWorkspace(workspace, effective));
        } catch (Exception ignored) {
        }
        if (workspaceToml.length() <= MAX_TOOL_JSON_CHARS) {
            payload.addProperty("workspace_toml", workspaceToml);
        } else {
            payload.addProperty("truncated", true);
            payload.addProperty("workspace_toml", workspaceToml.substring(0, MAX_TOOL_JSON_CHARS));
        }
        return payload;
    }

    private static String buildProjectSummaryText(ProjectPersistence.ProjectRecord project) {
        if (project == null) {
            return "";
        }
        int workspaceCount = project.workspaceFiles == null ? 0 : project.workspaceFiles.size();
        int pendingCount = 0;
        if (project.workspaceFiles != null) {
            for (ProjectPersistence.WorkspaceFileRecord workspace : project.workspaceFiles.values()) {
                if (workspace != null && workspace.pendingPatch != null) {
                    pendingCount++;
                }
            }
        }
        String summary = "Project " + (project.name == null ? "" : project.name) + " with " + workspaceCount + " workspace files";
        if (pendingCount > 0) {
            summary += ", " + pendingCount + " pending patches";
        }
        return summary;
    }

    private static ProjectPersistence.ProjectRecord currentProject(ServerPlayer player, Session session) {
        String projectId = session != null && session.projectId != null && !session.projectId.isBlank()
                ? session.projectId
                : currentProjectIds.getOrDefault(player.getUUID(), "");
        return projectId.isBlank() ? null : loadProject(projectId);
    }

    private static ProjectPersistence.ProjectRecord loadProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        ProjectPersistence.ProjectRecord cached = loadedProjects.get(projectId);
        if (cached != null) {
            return cached;
        }
        ProjectPersistence.ProjectRecord loaded = ProjectPersistence.loadProject(projectId);
        if (loaded != null) {
            loadedProjects.put(projectId, loaded);
        }
        return loaded;
    }

    private static void saveProject(ProjectPersistence.ProjectRecord project) {
        if (project == null || project.id == null || project.id.isBlank()) {
            return;
        }
        loadedProjects.put(project.id, project);
        ProjectPersistence.saveProject(project);
    }

    private static ProjectPersistence.WorkspaceFileRecord findWorkspace(ProjectPersistence.ProjectRecord project, String path) {
        if (project == null || project.workspaceFiles == null) {
            return null;
        }
        return project.workspaceFiles.get(ProjectPersistence.normalizeWorkspacePath(path));
    }

    private static ProjectPersistence.WorkspaceFileRecord selectedWorkspace(ProjectPersistence.ProjectRecord project, Session session) {
        if (project == null || session == null) {
            return null;
        }
        session.selectedWorkspacePath = chooseSelectedWorkspacePath(project, session.selectedWorkspacePath);
        return findWorkspace(project, session.selectedWorkspacePath);
    }

    private static ProjectPersistence.WorkspaceFileRecord resolveWorkspace(ProjectPersistence.ProjectRecord project, Session session, String path) {
        String candidate = ProjectPersistence.normalizeWorkspacePath(path);
        if (candidate.isBlank() && session != null) {
            candidate = ProjectPersistence.normalizeWorkspacePath(session.selectedWorkspacePath);
        }
        if (candidate.isBlank()) {
            return null;
        }
        if (session != null) {
            session.selectedWorkspacePath = candidate;
        }
        return findWorkspace(project, candidate);
    }

    private static String chooseSelectedWorkspacePath(ProjectPersistence.ProjectRecord project, String preferred) {
        String normalized = ProjectPersistence.normalizeWorkspacePath(preferred);
        if (project != null && project.workspaceFiles != null && !normalized.isBlank() && project.workspaceFiles.containsKey(normalized)) {
            return normalized;
        }
        return "";
    }

    private static BlockPos workspaceOrigin(ProjectPersistence.ProjectRecord project, ProjectPersistence.WorkspaceFileRecord workspace) {
        if (workspace != null && workspace.origin != null) {
            return workspace.origin.toBlockPos();
        }
        if (project != null && project.boundsOrigin != null) {
            return project.boundsOrigin.toBlockPos();
        }
        return BlockPos.ZERO;
    }

    private static Vec3i workspaceSize(ProjectPersistence.ProjectRecord project, ProjectPersistence.WorkspaceFileRecord workspace) {
        if (workspace != null && workspace.size != null) {
            return workspace.size.toVec3i();
        }
        if (project != null && project.boundsSize != null) {
            return project.boundsSize.toVec3i();
        }
        return null;
    }

    private static boolean isSelectionWithinProject(SelectionManager.Selection selection, ProjectPersistence.ProjectRecord project) {
        if (selection == null || !selection.isComplete() || project == null || project.boundsOrigin == null || project.boundsSize == null) {
            return false;
        }
        BlockPos selectionMin = selection.min();
        BlockPos selectionMax = selection.max();
        BlockPos projectMin = project.boundsOrigin.toBlockPos();
        BlockPos projectMax = new BlockPos(
                projectMin.getX() + project.boundsSize.x - 1,
                projectMin.getY() + project.boundsSize.y - 1,
                projectMin.getZ() + project.boundsSize.z - 1
        );
        return selectionMin.getX() >= projectMin.getX()
                && selectionMin.getY() >= projectMin.getY()
                && selectionMin.getZ() >= projectMin.getZ()
                && selectionMax.getX() <= projectMax.getX()
                && selectionMax.getY() <= projectMax.getY()
                && selectionMax.getZ() <= projectMax.getZ();
    }

    private static void pushCommit(List<ProjectPersistence.CommitRecord> stack, ProjectPersistence.CommitRecord commit) {
        if (stack == null || commit == null) {
            return;
        }
        stack.add(commit);
    }

    private static ProjectPersistence.CommitRecord popCommit(List<ProjectPersistence.CommitRecord> stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.remove(stack.size() - 1);
    }

    private static String safeSummary(String summary) {
        return summary == null || summary.isBlank() ? "patch" : summary;
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
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static JsonObject parsePayloadObject(String payload) {
        if (payload == null || payload.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(payload).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String parsePath(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
            return ProjectPersistence.normalizeWorkspacePath(getString(obj, "path"));
        } catch (Exception ignored) {
            return ProjectPersistence.normalizeWorkspacePath(payload.trim());
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


    private static Integer getInt(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean getBoolean(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PatchRuntimeSettings runtimeSettings(JsonObject args) {
        JsonObject settings = args != null && args.has("runtime_settings") && args.get("runtime_settings").isJsonObject()
                ? args.getAsJsonObject("runtime_settings")
                : null;
        Integer maxPatchOps = getInt(settings, "maxPatchOps");
        Integer maxBlocksPerCommit = getInt(settings, "maxBlocksPerCommit");
        Boolean confirmRequired = getBoolean(settings, "confirmRequired");
        Integer riskAutoApplyThreshold = getInt(settings, "riskAutoApplyThreshold");
        return new PatchRuntimeSettings(
                maxPatchOps == null || maxPatchOps <= 0 ? P2SDefaults.DEFAULT_MAX_PATCH_OPS : maxPatchOps,
                maxBlocksPerCommit == null || maxBlocksPerCommit <= 0 ? P2SDefaults.DEFAULT_MAX_BLOCKS_PER_COMMIT : maxBlocksPerCommit,
                confirmRequired == null ? P2SDefaults.DEFAULT_CONFIRM_REQUIRED : confirmRequired,
                riskAutoApplyThreshold == null || riskAutoApplyThreshold < -1 ? P2SDefaults.DEFAULT_RISK_AUTO_APPLY_THRESHOLD : riskAutoApplyThreshold
        );
    }

    private static PatchModels.StructurePatch parsePatchArguments(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return null;
        }
        try {
            JsonObject obj = normalizeArgsObject(argsElem);
            String patchToml = getString(obj, "patch_toml");
            if (patchToml.isBlank()) {
                return null;
            }
            PatchModels.StructurePatch patch = PatchTomlCodec.parse(patchToml);
            normalizePatch(patch);
            return patch;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to parse patch arguments: {}", e.getMessage());
            return null;
        }
    }

    private static SearchBlockArgs parseSearchBlockArgs(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return null;
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                parsed = JsonParser.parseString(raw);
            }
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();
            String query = obj.has("query") && obj.get("query").isJsonPrimitive() ? obj.get("query").getAsString() : "";
            int limit = obj.has("limit") && obj.get("limit").isJsonPrimitive() ? obj.get("limit").getAsInt() : 10;
            if (limit <= 0) {
                limit = 10;
            }
            if (limit > 50) {
                limit = 50;
            }
            return new SearchBlockArgs(query, limit);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to parse search_block_ids arguments: {}", e.getMessage());
            return null;
        }
    }

    private static ReadWorkspaceArgs parseReadWorkspaceArgs(JsonElement argsElem) {
        JsonObject obj = normalizeArgsObject(argsElem);
        boolean committed = !obj.has("committed") || !obj.get("committed").isJsonPrimitive() || obj.get("committed").getAsBoolean();
        String path = ProjectPersistence.normalizeWorkspacePath(getString(obj, "path"));
        return new ReadWorkspaceArgs(committed, path);
    }

    private static void normalizePatch(PatchModels.StructurePatch patch) {
        if (patch == null) {
            return;
        }
        if (patch.baseRevision == null) {
            patch.baseRevision = "";
        }
        if (patch.intent == null) {
            patch.intent = "";
        }
        if (patch.messageToUser == null) {
            patch.messageToUser = "";
        }
        if (patch.operations == null) {
            patch.operations = new ArrayList<>();
        }
    }

    private static PatchModels.StructurePatch mergePatches(PatchModels.StructurePatch existing, PatchModels.StructurePatch incoming, String baseRevision) {
        PatchModels.StructurePatch merged = new PatchModels.StructurePatch();
        merged.baseRevision = baseRevision == null ? "" : baseRevision;
        merged.intent = incoming == null || incoming.intent == null ? "" : incoming.intent;
        merged.messageToUser = incoming == null || incoming.messageToUser == null ? "" : incoming.messageToUser;
        merged.operations = new ArrayList<>();
        if (existing != null && existing.operations != null) {
            merged.operations.addAll(existing.operations);
        }
        if (incoming != null && incoming.operations != null) {
            merged.operations.addAll(incoming.operations);
        }
        return merged;
    }

    private static boolean requiresConfirm(int changedBlocks, PatchRuntimeSettings settings) {
        if (settings == null || !settings.confirmRequired()) {
            return false;
        }
        int threshold = Math.max(0, settings.riskAutoApplyThreshold());
        return changedBlocks > threshold;
    }

    private static PatchModels.Preview buildPatchPreview(
            PatchModels.StructurePatch patch,
            PatchModels.ValidationResult validation,
            StructurePatchEngine.DiffResult diff,
            String revisionBefore
    ) {
        PatchModels.Preview preview = new PatchModels.Preview();
        preview.changedBlocks = validation == null ? 0 : validation.estimatedChangedBlocks;
        preview.riskLevel = validation == null || validation.riskLevel == null ? "low" : validation.riskLevel;
        if (validation != null && validation.warnings != null) {
            preview.warnings.addAll(validation.warnings);
        }

        String summary = validation == null ? "Patch prepared" : validation.summary;
        if (summary == null || summary.isBlank()) {
            int opCount = patch == null || patch.operations == null ? 0 : patch.operations.size();
            summary = "Patch prepared with " + opCount + " operation" + (opCount == 1 ? "" : "s");
        }
        if (revisionBefore != null && !revisionBefore.isBlank()) {
            summary = summary + " @ " + revisionBefore;
        }
        preview.summary = truncateText(summary, MAX_PREVIEW_SUMMARY_CHARS);

        StringBuilder detail = new StringBuilder();
        if (patch != null && patch.intent != null && !patch.intent.isBlank()) {
            detail.append("Intent: ").append(patch.intent.trim()).append('\n');
        }
        if (patch != null && patch.messageToUser != null && !patch.messageToUser.isBlank()) {
            detail.append("Message: ").append(patch.messageToUser.trim()).append('\n');
        }
        detail.append("Changed blocks: ").append(preview.changedBlocks).append('\n');
        detail.append("Risk: ").append(preview.riskLevel).append('\n');
        if (!preview.warnings.isEmpty()) {
            detail.append("Warnings:").append('\n');
            int warningLines = 0;
            for (String warning : preview.warnings) {
                if (warning == null || warning.isBlank()) {
                    continue;
                }
                detail.append("- ").append(warning.trim()).append('\n');
                warningLines++;
                if (warningLines >= MAX_PREVIEW_WARNING_LINES) {
                    break;
                }
            }
        }
        if (patch != null && patch.operations != null && !patch.operations.isEmpty()) {
            detail.append("Operations:").append('\n');
            int lines = 0;
            for (PatchModels.PatchOperation op : patch.operations) {
                if (lines >= MAX_PREVIEW_OPERATION_LINES) {
                    detail.append("- ...").append('\n');
                    break;
                }
                detail.append("- ").append(formatPatchOperation(op)).append('\n');
                lines++;
            }
        }
        preview.detail = truncateText(detail.toString().trim(), MAX_PREVIEW_DETAIL_CHARS);
        return preview;
    }

    private static String formatPatchOperation(PatchModels.PatchOperation op) {
        if (op == null) {
            return "unknown";
        }
        String action = op.op == null ? "unknown" : op.op;
        String part = op.part == null || op.part.isBlank() ? "" : (" part=" + op.part.trim());
        int count = 0;
        if (op.actionsAdd != null) count += op.actionsAdd.size();
        if (op.oldActions != null) count += op.oldActions.size();
        if (op.newActions != null) count += op.newActions.size();
        if (op.entries != null) count += op.entries.size();
        String suffix = count > 0 ? (" items=" + count) : "";
        return action + part + suffix;
    }

    private static String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    private static int countParts(StructureBuilder.VbsScriptV2 script) {
        return script == null || script.structures == null ? 0 : script.structures.size();
    }

    public static String buildAreaConstraint(Vec3i size) {
        if (size == null) {
            return "Use relative coordinates from (0,0,0) for all structure actions.";
        }
        int maxX = Math.max(0, size.getX() - 1);
        int maxY = Math.max(0, size.getY() - 1);
        int maxZ = Math.max(0, size.getZ() - 1);
        return "Build strictly within the selected area using relative coordinates from (0,0,0). "
                + "Valid ranges are x=0.." + maxX
                + ", y=0.." + maxY
                + ", z=0.." + maxZ
                + ". Do not generate or reference blocks outside these bounds.";
    }

    private static String buildStructureSummary(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<StructureBuilder.StructurePart> parts = new ArrayList<>(script.structures);
        parts.sort(Comparator.comparingInt(part -> part == null ? Integer.MAX_VALUE : part.priority));
        int emitted = 0;
        for (StructureBuilder.StructurePart part : parts) {
            if (part == null || part.name == null || part.name.isBlank()) {
                continue;
            }
            if (emitted > 0) {
                sb.append('\n');
            }
            sb.append(part.name.trim());
            sb.append(" (p=").append(part.priority).append(')');
            int actions = part.actions == null ? 0 : part.actions.size();
            sb.append(": ").append(actions).append(" action").append(actions == 1 ? "" : "s");
            if (part.actions != null && !part.actions.isEmpty()) {
                Map<String, String> palette = script.palette == null ? Map.of() : script.palette;
                int previewActions = 0;
                for (StructureBuilder.VbsAction action : part.actions) {
                    String actionSummary = formatActionSummary(action, palette);
                    if (actionSummary == null || actionSummary.isBlank()) {
                        continue;
                    }
                    if (sb.length() < MAX_SUMMARY_CHARS) {
                        sb.append("\n  - ").append(actionSummary);
                    }
                    previewActions++;
                    if (previewActions >= 4) {
                        break;
                    }
                }
                if (part.actions.size() > 4) {
                    sb.append("\n  - ...");
                }
            }
            emitted++;
            if (emitted >= 20 || sb.length() >= MAX_SUMMARY_CHARS) {
                break;
            }
        }
        return truncateText(sb.toString(), MAX_SUMMARY_CHARS);
    }

    private static String formatActionSummary(StructureBuilder.VbsAction action, Map<String, String> palette) {
        if (action == null) {
            return "";
        }
        String type = action.type == null ? "action" : action.type.trim().toLowerCase();
        String block = formatBlock(action.block, palette);
        return switch (type) {
            case "box" -> {
                String mode = action.mode == null || action.mode.isBlank() ? "solid" : action.mode.trim();
                yield "box[" + mode + "] " + block + " " + formatRange(action.from, action.to);
            }
            case "plane" -> {
                String axis = action.axis == null || action.axis.isBlank() ? "?" : action.axis.trim();
                String mode = action.mode == null || action.mode.isBlank() ? "solid" : action.mode.trim();
                yield "plane[" + axis + ":" + mode + "] " + block + " " + formatRange(action.from, action.to);
            }
            case "line" -> "line " + block + " " + formatRange(action.from, action.to);
            case "points" -> "points " + block + " " + formatAt(action.at);
            default -> type + " " + block;
        };
    }

    private static String formatBlock(String block, Map<String, String> palette) {
        if (block == null || block.isBlank()) {
            return "?";
        }
        String value = block.trim();
        if (palette != null && palette.containsKey(value)) {
            return value + "=" + palette.get(value);
        }
        return value;
    }

    private static String formatRange(List<Integer> from, List<Integer> to) {
        return formatVec(from) + " -> " + formatVec(to);
    }

    private static String formatAt(List<List<Integer>> at) {
        if (at == null || at.isEmpty()) {
            return "[]";
        }
        if (at.size() == 1) {
            return formatVec(at.get(0));
        }
        return at.size() + " points";
    }

    private static String formatVec(List<Integer> vec) {
        if (vec == null || vec.size() < 3) {
            return "(?, ?, ?)";
        }
        return "(" + vec.get(0) + ", " + vec.get(1) + ", " + vec.get(2) + ")";
    }

    private static int countBlocks(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null) {
            return 0;
        }
        int total = 0;
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (action == null || action.type == null) {
                    continue;
                }
                String type = action.type.trim().toLowerCase();
                total += switch (type) {
                    case "box" -> countBox(action);
                    case "plane" -> countPlane(action);
                    case "line" -> countLine(action);
                    case "points" -> countPoints(action);
                    default -> 0;
                };
            }
        }
        return total;
    }

    private static int countBox(StructureBuilder.VbsAction action) {
        if (action == null || action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        int dx = Math.abs(action.to.get(0) - action.from.get(0)) + 1;
        int dy = Math.abs(action.to.get(1) - action.from.get(1)) + 1;
        int dz = Math.abs(action.to.get(2) - action.from.get(2)) + 1;
        int volume = dx * dy * dz;
        String mode = action.mode == null ? "solid" : action.mode.trim().toLowerCase();
        return switch (mode) {
            case "shell" -> {
                if (dx <= 2 || dy <= 2 || dz <= 2) {
                    yield volume;
                }
                yield volume - Math.max(0, dx - 2) * Math.max(0, dy - 2) * Math.max(0, dz - 2);
            }
            case "walls" -> {
                if (dx <= 2 || dz <= 2) {
                    yield volume;
                }
                yield (2 * dx + 2 * dz - 4) * dy;
            }
            case "solid" -> volume;
            default -> volume;
        };
    }

    private static int countPlane(StructureBuilder.VbsAction action) {
        if (action == null || action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        int dx = Math.abs(action.to.get(0) - action.from.get(0)) + 1;
        int dy = Math.abs(action.to.get(1) - action.from.get(1)) + 1;
        int dz = Math.abs(action.to.get(2) - action.from.get(2)) + 1;
        String mode = action.mode == null ? "solid" : action.mode.trim().toLowerCase();
        String axis = action.axis == null ? "" : action.axis.trim().toLowerCase();
        int area = switch (axis) {
            case "x" -> dy * dz;
            case "y" -> dx * dz;
            case "z" -> dx * dy;
            default -> dx * dy * dz;
        };
        if (!"outline".equals(mode)) {
            return area;
        }
        return switch (axis) {
            case "x" -> dy <= 2 || dz <= 2 ? area : 2 * dy + 2 * dz - 4;
            case "y" -> dx <= 2 || dz <= 2 ? area : 2 * dx + 2 * dz - 4;
            case "z" -> dx <= 2 || dy <= 2 ? area : 2 * dx + 2 * dy - 4;
            default -> area;
        };
    }

    private static int countLine(StructureBuilder.VbsAction action) {
        if (action == null || action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        int dx = Math.abs(action.to.get(0) - action.from.get(0));
        int dy = Math.abs(action.to.get(1) - action.from.get(1));
        int dz = Math.abs(action.to.get(2) - action.from.get(2));
        return Math.max(dx, Math.max(dy, dz)) + 1;
    }

    private static int countPoints(StructureBuilder.VbsAction action) {
        if (action == null || action.at == null) {
            return 0;
        }
        return action.at.size();
    }

    private static String partsSummary(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return "";
        }
        final int maxLength = 768;
        final int maxPartNameLength = 96;
        List<String> names = new ArrayList<>();
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.name == null) {
                continue;
            }
            String name = part.name.trim();
            if (name.isBlank()) {
                continue;
            }
            names.add(trimForSummary(name, maxPartNameLength));
        }
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            String separator = sb.length() == 0 ? "" : ", ";
            int remaining = names.size() - index - 1;
            String suffix = remaining > 0 ? ", ... (+" + remaining + ")" : "";
            if (sb.length() + separator.length() + name.length() + suffix.length() > maxLength) {
                if (sb.length() == 0) {
                    sb.append(trimForSummary(name, Math.max(8, maxLength - suffix.length())));
                }
                if (remaining >= 0) {
                    if (sb.length() + suffix.length() > maxLength) {
                        sb.append("...");
                    } else {
                        sb.append(suffix);
                    }
                }
                break;
            }
            sb.append(separator).append(name);
        }
        return sb.toString();
    }

    private static String trimForSummary(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        if (maxLength <= 3) {
            return normalized.substring(0, Math.max(0, maxLength));
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private static StructureBuilder.VbsScriptV2 copyScript(StructureBuilder.VbsScriptV2 script) {
        return script == null ? null : GSON.fromJson(GSON.toJsonTree(script), StructureBuilder.VbsScriptV2.class);
    }

    private static JsonObject buildToolSuccess(String tool) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", tool == null ? "" : tool);
        return payload;
    }

    private static JsonObject buildToolError(String tool, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", tool == null ? "" : tool);
        payload.addProperty("error", error == null ? "" : error);
        return payload;
    }

    private static JsonObject buildToolErrorKey(String tool, String errorKey, Object... args) {
        JsonObject payload = buildToolError(tool, P2SI18n.tr(errorKey, args).getString());
        payload.addProperty("error_key", errorKey == null ? "" : errorKey);
        payload.add("error_args", GSON.toJsonTree(args == null ? new Object[0] : args));
        return payload;
    }

    private static void addToolWarning(JsonObject payload, String warningKey, Object... args) {
        if (payload == null) {
            return;
        }
        payload.addProperty("warning", P2SI18n.tr(warningKey, args).getString());
        payload.addProperty("warning_key", warningKey == null ? "" : warningKey);
        payload.add("warning_args", GSON.toJsonTree(args == null ? new Object[0] : args));
    }

    private static void displayMessage(ServerPlayer player, String key, Object... args) {
        if (player == null) {
            return;
        }
        player.displayClientMessage(P2SI18n.tr(key, args), false);
    }

    private static void sendToolBridgeResponse(ServerPlayer player, String requestId, boolean ok, JsonObject payload, String error) {
        JsonObject safePayload = payload == null ? new JsonObject() : payload;
        ServerNetworkHandler.sendToClient(player, new S2CToolBridgePayload(
                requestId == null ? "" : requestId,
                ok,
                GSON.toJson(safePayload),
                error == null ? "" : error
        ));
    }

    private static void sendPatchPreview(ServerPlayer player, PatchModels.Preview preview) {
        if (player == null) {
            return;
        }
        if (preview == null) {
            ServerNetworkHandler.sendToClient(player, new S2CPatchPreviewPayload(false, "", "", 0, ""));
            return;
        }
        ServerNetworkHandler.sendToClient(player, new S2CPatchPreviewPayload(
                true,
                preview.summary == null ? "" : preview.summary,
                preview.detail == null ? "" : preview.detail,
                preview.changedBlocks,
                preview.riskLevel == null ? "" : preview.riskLevel
        ));
    }

    private static void sendChatResponse(ServerPlayer player, String text, boolean hasStructure, String status) {
        ServerNetworkHandler.sendToClient(player, new S2CChatResponsePayload(
                text == null ? "" : text,
                hasStructure,
                status == null ? "" : status
        ));
    }

    private static void sendChatResponseLocalized(ServerPlayer player, String messageKey, boolean hasStructure, String status, Object... args) {
        ServerNetworkHandler.sendToClient(player, new S2CChatResponsePayload(
                P2SI18n.tr(messageKey, args).getString(),
                hasStructure,
                status == null ? "" : status,
                messageKey == null ? "" : messageKey,
                GSON.toJson(args == null ? new Object[0] : args)
        ));
    }

    private static void sendSessionSync(ServerPlayer player, Session session) {
        if (player == null) {
            return;
        }
        Session currentSession = session != null ? session : sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, currentSession);
        boolean hasProject = project != null;
        boolean sessionActive = currentSession != null;
        ProjectPersistence.WorkspaceFileRecord selected = selectedWorkspace(project, currentSession);
        String currentWorkspaceToml = "";
        if (selected != null) {
            try {
                currentWorkspaceToml = WorkspaceTomlCodec.write(WorkspaceTomlCodec.fromWorkspace(selected, selected.current));
            } catch (Exception ignored) {
            }
        }
        ServerNetworkHandler.sendToClient(player, new S2CSessionSyncPayload(
                hasProject,
                sessionActive,
                sessionActive ? currentSession.id : "",
                hasProject ? project.id : "",
                hasProject ? project.name : "",
                hasProject ? project.description : "",
                hasProject && project.boundsOrigin != null ? project.boundsOrigin.x : 0,
                hasProject && project.boundsOrigin != null ? project.boundsOrigin.y : 0,
                hasProject && project.boundsOrigin != null ? project.boundsOrigin.z : 0,
                hasProject && project.boundsSize != null,
                hasProject && project.boundsSize != null ? project.boundsSize.x : 0,
                hasProject && project.boundsSize != null ? project.boundsSize.y : 0,
                hasProject && project.boundsSize != null ? project.boundsSize.z : 0,
                currentSession == null ? "" : currentSession.selectedWorkspacePath,
                selected == null ? 0 : countParts(selected.current),
                selected == null ? 0 : countBlocks(selected.current),
                selected == null ? "" : partsSummary(selected.current),
                selected == null ? "" : buildStructureSummary(selected.current),
                currentSession == null ? "" : toRuntimeStateName(currentSession.runtimeState),
                selected == null || selected.revision == null ? "" : selected.revision,
                selected != null && selected.pendingPatch != null,
                selected != null && selected.pendingPatch != null ? selected.path : "",
                selected != null && selected.pendingPatch != null && selected.pendingPatch.preview != null ? selected.pendingPatch.preview.summary : "",
                selected != null && selected.pendingPatch != null && selected.pendingPatch.preview != null ? selected.pendingPatch.preview.riskLevel : "",
                selected != null && selected.pendingPatch != null && selected.pendingPatch.preview != null ? selected.pendingPatch.preview.changedBlocks : 0,
                checkpointsJson(selected),
                currentWorkspaceToml,
                hasProject ? GSON.toJson(buildWorkspaceSummaryArray(project)) : "[]"
        ));
    }

    private static String checkpointsJson(ProjectPersistence.WorkspaceFileRecord workspace) {
        JsonArray arr = new JsonArray();
        if (workspace == null || workspace.checkpoints == null) {
            return "[]";
        }
        int start = Math.max(0, workspace.checkpoints.size() - 12);
        for (int i = start; i < workspace.checkpoints.size(); i++) {
            ProjectPersistence.CheckpointRecord checkpoint = workspace.checkpoints.get(i);
            if (checkpoint == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", checkpoint.id == null ? "" : checkpoint.id);
            item.addProperty("label", checkpoint.label == null ? "" : checkpoint.label);
            item.addProperty("revision", checkpoint.revision == null ? "" : checkpoint.revision);
            arr.add(item);
        }
        return GSON.toJson(arr);
    }

    private static String toRuntimeStateName(RuntimeState state) {
        return state == null ? "" : state.name().toLowerCase();
    }

    private static String nextRevision() {
        return "rev-" + UUID.randomUUID();
    }

    private enum RuntimeState {
        IDLE,
        AWAITING_CONFIRM
    }

    private static final class SearchBlockArgs {
        private final String query;
        private final int limit;

        private SearchBlockArgs(String query, int limit) {
            this.query = query;
            this.limit = limit;
        }
    }

    private static final class ReadWorkspaceArgs {
        private final boolean committed;
        private final String path;

        private ReadWorkspaceArgs(boolean committed, String path) {
            this.committed = committed;
            this.path = path == null ? "" : path.trim();
        }
    }

    private record PatchRuntimeSettings(
            int maxPatchOps,
            int maxBlocksPerCommit,
            boolean confirmRequired,
            int riskAutoApplyThreshold
    ) {
    }

    public static class Session {
        String id;
        String projectId;
        String selectedWorkspacePath = "";
        RuntimeState runtimeState = RuntimeState.IDLE;
    }
}
