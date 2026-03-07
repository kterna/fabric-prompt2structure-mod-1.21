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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final String DEFAULT_PROJECT_NAME = "Current Project";

    private SessionManager() {
    }

    public static void handleChatMessage(ServerPlayer player, String message) {
        if (player == null) {
            return;
        }
        sendChatResponse(player,
                "Server chat mode is not available in the new project/session architecture. Use the client chat panel.",
                false,
                "error");
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
            case "rollback_checkpoint" -> rollbackCheckpoint(player, payload);
            case "session_select_workspace" -> selectWorkspacePath(player, payload);
            default -> player.displayClientMessage(Component.literal("Unknown session action: " + action), false);
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
                case "get_project_state" -> handleGetProjectState(player);
                case "read_workspace_file" -> handleReadWorkspaceFile(player, arguments);
                case "create_workspace_file" -> handleCreateWorkspaceFileTool(player, arguments);
                case "rename_workspace_file" -> handleRenameWorkspaceFileTool(player, arguments);
                case "delete_workspace_file" -> handleDeleteWorkspaceFileTool(player, arguments);
                case "propose_patch" -> handleProposePatchTool(player, arguments);
                case "search_block_ids" -> handleSearchBlockIds(arguments);
                case "explain_plan" -> handleExplainPlan();
                default -> buildToolError(normalizedTool, "Unknown tool");
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
            player.displayClientMessage(Component.literal("No project open. Create or open a project first."), false);
            sendSessionSync(player, null);
            return null;
        }

        ProjectPersistence.ProjectRecord project = loadProject(projectId);
        if (project == null) {
            player.displayClientMessage(Component.literal("Project not found: " + projectId), false);
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
        player.displayClientMessage(Component.literal("Session opened: " + session.id), false);
        return session;
    }

    public static void endSession(ServerPlayer player) {
        if (player == null) {
            return;
        }
        sessions.remove(player.getUUID());
        sendPatchPreview(player, null);
        sendSessionSync(player, null);
        player.displayClientMessage(Component.literal("Session closed"), false);
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
            player.displayClientMessage(Component.literal("No selected workspace with structure content"), false);
            return;
        }
        String saved = ScriptStorage.saveV2(workspace.path, workspace.current, workspace.summary, name);
        player.displayClientMessage(Component.literal("Saved workspace as " + saved), false);
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
            payload.addProperty("warning", ProjectPersistence.legacyWarningMessage());
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
            return buildToolError("create_project", "Create project requires a complete selection");
        }
        ProjectPersistence.ProjectRecord project = ProjectPersistence.createProject(
                name.isBlank() ? DEFAULT_PROJECT_NAME : name,
                description,
                selection.min(),
                selection.size()
        );
        if (project == null) {
            return buildToolError("create_project", "Failed to create project");
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
            return buildToolError("open_project", "Open project requires id");
        }
        ProjectPersistence.ProjectRecord project = loadProject(projectId);
        if (project == null) {
            return buildToolError("open_project", "Project not found: " + projectId);
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

    private static JsonObject handleGetProjectState(ServerPlayer player) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolError("get_project_state", "No current project");
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
            return buildToolError("read_workspace_file", "No current project");
        }
        ReadWorkspaceArgs args = parseReadWorkspaceArgs(argsElem);
        if (args.path.isBlank()) {
            return buildToolError("read_workspace_file", "Read workspace file requires path");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, args.path);
        if (workspace == null) {
            return buildToolError("read_workspace_file", "Unknown path: " + args.path);
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
            return buildToolError("create_workspace_file", "No current project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String type = ProjectPersistence.normalizeWorkspaceType(getString(args, "type"));
        String name = getString(args, "name");
        if (path.isBlank()) {
            return buildToolError("create_workspace_file", "Create workspace file requires path");
        }
        if (project.workspaceFiles.size() >= MAX_WORKSPACE_FILES) {
            return buildToolError("create_workspace_file", "Maximum workspace file count reached");
        }
        if (project.workspaceFiles.containsKey(path)) {
            return buildToolError("create_workspace_file", "Path already exists: " + path);
        }
        ProjectPersistence.WorkspaceFileRecord workspace = new ProjectPersistence.WorkspaceFileRecord();
        workspace.path = path;
        workspace.name = name == null || name.isBlank() ? ProjectPersistence.leafName(path) : name.trim();
        workspace.type = type;
        workspace.revision = "rev-0";
        workspace.metadata = new JsonObject();
        project.workspaceFiles.put(path, workspace);
        saveProject(project);
        Session session = sessions.get(player.getUUID());
        if (session != null && (session.selectedWorkspacePath == null || session.selectedWorkspacePath.isBlank())) {
            session.selectedWorkspacePath = path;
        }
        JsonObject payload = buildToolSuccess("create_workspace_file");
        payload.addProperty("path", path);
        payload.addProperty("name", workspace.name);
        payload.addProperty("type", workspace.type);
        return payload;
    }

    private static JsonObject handleRenameWorkspaceFileTool(ServerPlayer player, JsonElement argsElem) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        if (project == null) {
            return buildToolError("rename_workspace_file", "No current project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String newPath = ProjectPersistence.normalizeWorkspacePath(getString(args, "new_path"));
        if (path.isBlank() || newPath.isBlank()) {
            return buildToolError("rename_workspace_file", "Rename requires path and new_path");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            return buildToolError("rename_workspace_file", "Unknown path: " + path);
        }
        if (!path.equals(newPath) && project.workspaceFiles.containsKey(newPath)) {
            return buildToolError("rename_workspace_file", "Target path already exists: " + newPath);
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
            return buildToolError("delete_workspace_file", "No current project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        if (path.isBlank()) {
            return buildToolError("delete_workspace_file", "Delete requires path");
        }
        if (project.workspaceFiles.size() <= 1) {
            return buildToolError("delete_workspace_file", "Cannot delete the last workspace file");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            return buildToolError("delete_workspace_file", "Unknown path: " + path);
        }
        if (workspace.pendingPatch != null) {
            return buildToolError("delete_workspace_file", "Cannot delete workspace file with pending patch");
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
            return buildToolError("propose_patch", "No active session/project");
        }
        JsonObject args = normalizeArgsObject(argsElem);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        if (path.isBlank()) {
            return buildToolError("propose_patch", "propose_patch requires path");
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            return buildToolError("propose_patch", "Unknown path: " + path);
        }
        PatchModels.StructurePatch patch = parsePatchArguments(argsElem);
        if (patch == null) {
            return buildToolError("propose_patch", "Invalid patch arguments");
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
            return buildToolError("propose_patch", "Patch base_revision mismatch");
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
        PatchModels.ValidationResult validation = PatchValidator.validate(
                mergedPatch,
                committedRevision,
                workspaceSize(project, workspace),
                next,
                diff,
                ModConfig.MAX_PATCH_OPS,
                ModConfig.MAX_BLOCKS_PER_COMMIT
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
        validation.requiresConfirm = requiresConfirm(pending.preview.changedBlocks);
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
            return buildToolError("search_block_ids", "Missing query");
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
            payload.addProperty("warning", "No matches");
        }
        return payload;
    }

    private static JsonObject handleExplainPlan() {
        JsonObject payload = buildToolSuccess("explain_plan");
        payload.addProperty("accepted", true);
        return payload;
    }

    private static void createWorkspaceFileAction(ServerPlayer player, String payload, boolean fromSelection) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        Session session = sessions.get(player.getUUID());
        if (project == null || session == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String type = ProjectPersistence.normalizeWorkspaceType(getString(args, "type"));
        if (path.isBlank()) {
            player.displayClientMessage(Component.literal("Workspace create requires path"), false);
            return;
        }
        if (project.workspaceFiles.size() >= MAX_WORKSPACE_FILES) {
            player.displayClientMessage(Component.literal("Maximum workspace file count reached"), false);
            return;
        }
        if (project.workspaceFiles.containsKey(path)) {
            player.displayClientMessage(Component.literal("Workspace path already exists: " + path), false);
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = new ProjectPersistence.WorkspaceFileRecord();
        workspace.path = path;
        workspace.name = ProjectPersistence.leafName(path);
        workspace.type = type;
        workspace.metadata = new JsonObject();
        if (fromSelection) {
            SelectionManager.Selection selection = SelectionManager.get(player.getUUID());
            if (selection == null || !selection.isComplete()) {
                player.displayClientMessage(Component.literal("Selection required to create workspace from selection"), false);
                return;
            }
            if (!isSelectionWithinProject(selection, project)) {
                player.displayClientMessage(Component.literal("Selection must stay inside project bounds"), false);
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
        player.displayClientMessage(Component.literal("Workspace file created: " + path), false);
    }

    private static void renameWorkspaceFileAction(ServerPlayer player, String payload) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        Session session = sessions.get(player.getUUID());
        if (project == null || session == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String newPath = ProjectPersistence.normalizeWorkspacePath(getString(args, "new_path"));
        if (path.isBlank() || newPath.isBlank()) {
            player.displayClientMessage(Component.literal("Rename requires path and new_path"), false);
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            player.displayClientMessage(Component.literal("Workspace not found: " + path), false);
            return;
        }
        if (!path.equals(newPath) && project.workspaceFiles.containsKey(newPath)) {
            player.displayClientMessage(Component.literal("Target path already exists: " + newPath), false);
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
        player.displayClientMessage(Component.literal("Workspace renamed: " + newPath), false);
    }

    private static void deleteWorkspaceFileAction(ServerPlayer player, String payload) {
        ProjectPersistence.ProjectRecord project = currentProject(player, sessions.get(player.getUUID()));
        Session session = sessions.get(player.getUUID());
        if (project == null || session == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        if (path.isBlank()) {
            player.displayClientMessage(Component.literal("Delete requires path"), false);
            return;
        }
        if (project.workspaceFiles.size() <= 1) {
            player.displayClientMessage(Component.literal("Cannot delete the last workspace file"), false);
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = findWorkspace(project, path);
        if (workspace == null) {
            player.displayClientMessage(Component.literal("Workspace not found: " + path), false);
            return;
        }
        if (workspace.pendingPatch != null) {
            player.displayClientMessage(Component.literal("Cannot delete workspace file with pending patch"), false);
            return;
        }
        project.workspaceFiles.remove(path);
        if (path.equals(session.selectedWorkspacePath)) {
            session.selectedWorkspacePath = chooseSelectedWorkspacePath(project, "");
        }
        saveProject(project);
        sendSessionSync(player, session);
        sendPatchPreview(player, null);
        player.displayClientMessage(Component.literal("Workspace deleted: " + path), false);
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
            player.displayClientMessage(Component.literal("Workspace not found: " + path), false);
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
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.pendingPatch == null) {
            player.displayClientMessage(Component.literal("No pending patch for selected workspace"), false);
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
        sendChatResponse(player, "Patch applied: " + commit.summary, true, "committed");
        sendPatchPreview(player, null);
        sendSessionSync(player, session);
    }

    private static void discardPendingPatch(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
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
            player.displayClientMessage(Component.literal("No pending patch for selected workspace"), false);
            return;
        }
        workspace.pendingPatch = null;
        session.runtimeState = RuntimeState.IDLE;
        saveProject(project);
        sendPatchPreview(player, null);
        sendSessionSync(player, session);
        sendChatResponse(player,
                reason == null || reason.isBlank() ? "Patch discarded" : "Patch discarded: " + reason,
                false,
                "cancelled");
    }

    private static void undo(ServerPlayer player, String path) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.undoStack == null || workspace.undoStack.isEmpty()) {
            player.displayClientMessage(Component.literal("Nothing to undo"), false);
            return;
        }
        ProjectPersistence.CommitRecord commit = popCommit(workspace.undoStack);
        if (commit == null) {
            player.displayClientMessage(Component.literal("Nothing to undo"), false);
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
        player.displayClientMessage(Component.literal("Undo applied: " + safeSummary(commit.summary)), false);
    }

    private static void redo(ServerPlayer player, String path) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.redoStack == null || workspace.redoStack.isEmpty()) {
            player.displayClientMessage(Component.literal("Nothing to redo"), false);
            return;
        }
        ProjectPersistence.CommitRecord commit = popCommit(workspace.redoStack);
        if (commit == null) {
            player.displayClientMessage(Component.literal("Nothing to redo"), false);
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
        player.displayClientMessage(Component.literal("Redo applied: " + safeSummary(commit.summary)), false);
    }

    private static void createCheckpoint(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String label = getString(args, "label");
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null) {
            player.displayClientMessage(Component.literal("Workspace not found"), false);
            return;
        }
        addCheckpoint(workspace, label.isBlank() ? "manual" : label, workspace.revision);
        saveProject(project);
        sendSessionSync(player, session);
        player.displayClientMessage(Component.literal("Checkpoint created"), false);
    }

    private static void rollbackCheckpoint(ServerPlayer player, String payload) {
        Session session = sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, session);
        if (session == null || project == null) {
            player.displayClientMessage(Component.literal("No active session/project"), false);
            return;
        }
        JsonObject args = parsePayloadObject(payload);
        String path = ProjectPersistence.normalizeWorkspacePath(getString(args, "path"));
        String checkpointId = getString(args, "checkpoint_id");
        ProjectPersistence.WorkspaceFileRecord workspace = resolveWorkspace(project, session, path);
        if (workspace == null || workspace.checkpoints == null || workspace.checkpoints.isEmpty()) {
            player.displayClientMessage(Component.literal("No checkpoints available"), false);
            return;
        }
        ProjectPersistence.CheckpointRecord checkpoint = findCheckpoint(workspace, checkpointId);
        if (checkpoint == null || checkpoint.script == null) {
            player.displayClientMessage(Component.literal("Checkpoint not found"), false);
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
        player.displayClientMessage(Component.literal("Rolled back to checkpoint: " + checkpoint.id), false);
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
        if (effective == null) {
            payload.addProperty("empty", true);
            return payload;
        }
        JsonElement scriptJson = GSON.toJsonTree(effective);
        String jsonText = GSON.toJson(scriptJson);
        if (jsonText.length() <= MAX_TOOL_JSON_CHARS) {
            payload.add("script", scriptJson);
        } else {
            payload.addProperty("truncated", true);
            payload.addProperty("script_json", jsonText.substring(0, MAX_TOOL_JSON_CHARS));
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

    private static PatchModels.StructurePatch parsePatchArguments(JsonElement argsElem) {
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
            PatchModels.StructurePatch patch = GSON.fromJson(parsed, PatchModels.StructurePatch.class);
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

    private static boolean requiresConfirm(int changedBlocks) {
        if (!ModConfig.CONFIRM_REQUIRED) {
            return false;
        }
        int threshold = Math.max(0, ModConfig.RISK_AUTO_APPLY_THRESHOLD);
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
        String type = action.type == null ? "action" : action.type.trim();
        String block = formatBlock(action.block, palette);
        return switch (type) {
            case "fill" -> "fill " + block + " " + formatRange(action.from, action.to);
            case "box" -> {
                String mode = action.mode == null || action.mode.isBlank() ? "solid" : action.mode.trim();
                yield "box[" + mode + "] " + block + " " + formatRange(action.from, action.to);
            }
            case "plane" -> {
                String axis = action.axis == null ? "?" : action.axis.trim();
                yield "plane[" + axis + "] " + block + " " + formatRange(action.from, action.to);
            }
            case "line" -> "line " + block + " " + formatAt(action.at);
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
                    case "fill", "setblock" -> countBox(action);
                    case "box" -> countBox(action);
                    case "plane" -> countPlane(action);
                    case "line" -> countLine(action);
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
        String mode = action.mode == null ? "solid" : action.mode.trim().toLowerCase();
        if (!"hollow".equals(mode)) {
            return dx * dy * dz;
        }
        if (dx <= 2 || dy <= 2 || dz <= 2) {
            return dx * dy * dz;
        }
        return dx * dy * dz - Math.max(0, dx - 2) * Math.max(0, dy - 2) * Math.max(0, dz - 2);
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
        if (!"frame".equals(mode)) {
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
        if (action == null || action.at == null) {
            return 0;
        }
        return action.at.size();
    }

    private static String partsSummary(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.name == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.name);
        }
        return sb.toString();
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

    private static void sendSessionSync(ServerPlayer player, Session session) {
        if (player == null) {
            return;
        }
        Session currentSession = session != null ? session : sessions.get(player.getUUID());
        ProjectPersistence.ProjectRecord project = currentProject(player, currentSession);
        boolean hasProject = project != null;
        boolean sessionActive = currentSession != null;
        ProjectPersistence.WorkspaceFileRecord selected = selectedWorkspace(project, currentSession);
        String currentScriptJson = "";
        if (selected != null && selected.current != null) {
            try {
                currentScriptJson = GSON.toJson(GSON.toJsonTree(selected.current));
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
                currentScriptJson,
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

    public static class Session {
        String id;
        String projectId;
        String selectedWorkspacePath = "";
        RuntimeState runtimeState = RuntimeState.IDLE;
    }
}
