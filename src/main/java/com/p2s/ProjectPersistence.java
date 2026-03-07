package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ProjectPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir().resolve("p2s_projects_v2");
    private static final Path PROJECTS_DIR = BASE_DIR.resolve("projects");
    private static final Path INDEX_PATH = BASE_DIR.resolve("index.json");
    private static final Path LEGACY_SESSIONS_DIR = FabricLoader.getInstance().getConfigDir().resolve("p2s_sessions");
    private static boolean legacyWarned = false;

    private ProjectPersistence() {
    }

    public static synchronized List<ProjectIndexEntry> listProjects() {
        warnIfLegacyDataExists();
        ensureDirectories();
        if (!Files.exists(INDEX_PATH)) {
            rebuildIndex();
        }
        List<ProjectIndexEntry> entries = new ArrayList<>();
        try {
            if (!Files.exists(INDEX_PATH)) {
                return entries;
            }
            JsonElement root = JsonParser.parseString(Files.readString(INDEX_PATH));
            if (!root.isJsonArray()) {
                return entries;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                String id = getString(obj, "id");
                if (id.isBlank()) {
                    continue;
                }
                entries.add(new ProjectIndexEntry(
                        id,
                        getString(obj, "name"),
                        getString(obj, "description"),
                        getLong(obj, "createdAt"),
                        getLong(obj, "updatedAt"),
                        Math.max(0, getInt(obj, "workspaceCount")),
                        getInt(obj, "originX"),
                        getInt(obj, "originY"),
                        getInt(obj, "originZ"),
                        getInt(obj, "sizeX"),
                        getInt(obj, "sizeY"),
                        getInt(obj, "sizeZ")
                ));
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to read project index: {}", e.getMessage());
        }
        entries.sort(Comparator.comparingLong(ProjectIndexEntry::updatedAt).reversed());
        return entries;
    }

    public static synchronized ProjectRecord loadProject(String id) {
        warnIfLegacyDataExists();
        ensureDirectories();
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            Path file = PROJECTS_DIR.resolve(id.trim() + ".json");
            if (!Files.exists(file)) {
                return null;
            }
            ProjectRecord project = GSON.fromJson(Files.readString(file), ProjectRecord.class);
            normalizeProject(project);
            return project;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed loading project {}: {}", id, e.getMessage());
            return null;
        }
    }

    public static synchronized ProjectRecord createProject(String name, String description, BlockPos origin, Vec3i size) {
        warnIfLegacyDataExists();
        ensureDirectories();
        if (origin == null || size == null) {
            return null;
        }
        ProjectRecord project = new ProjectRecord();
        project.id = uniqueProjectId(name);
        project.name = normalizeProjectName(name);
        project.description = description == null ? "" : description.trim();
        project.createdAt = System.currentTimeMillis();
        project.updatedAt = project.createdAt;
        project.boundsOrigin = Vec3Data.of(origin);
        project.boundsSize = Vec3Data.of(size);
        project.workspaceFiles = new LinkedHashMap<>();
        project.metadata = new JsonObject();
        saveProject(project);
        return project;
    }

    public static synchronized void saveProject(ProjectRecord project) {
        warnIfLegacyDataExists();
        ensureDirectories();
        if (project == null || project.id == null || project.id.isBlank()) {
            return;
        }
        normalizeProject(project);
        project.updatedAt = System.currentTimeMillis();
        try {
            Files.writeString(PROJECTS_DIR.resolve(project.id + ".json"), GSON.toJson(project));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed saving project {}: {}", project.id, e.getMessage());
        }
        updateIndex(project);
    }

    public static synchronized boolean deleteProject(String id) {
        warnIfLegacyDataExists();
        ensureDirectories();
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            Files.deleteIfExists(PROJECTS_DIR.resolve(id.trim() + ".json"));
            List<ProjectIndexEntry> entries = listProjects();
            entries.removeIf(entry -> id.trim().equals(entry.id()));
            writeIndex(entries);
            return true;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed deleting project {}: {}", id, e.getMessage());
            return false;
        }
    }

    public static synchronized boolean hasLegacyData() {
        try {
            if (!Files.exists(LEGACY_SESSIONS_DIR) || !Files.isDirectory(LEGACY_SESSIONS_DIR)) {
                return false;
            }
            try (var entries = Files.list(LEGACY_SESSIONS_DIR)) {
                return entries.findAny().isPresent();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized String legacyWarningMessage() {
        return "Legacy P2S data is not supported by this version. Clear old p2s session/project data before use.";
    }

    private static void warnIfLegacyDataExists() {
        if (legacyWarned || !hasLegacyData()) {
            return;
        }
        legacyWarned = true;
        P2SMod.LOGGER.warn(legacyWarningMessage());
    }

    private static void ensureDirectories() {
        try {
            Files.createDirectories(PROJECTS_DIR);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed creating project directories: {}", e.getMessage());
        }
    }

    private static void rebuildIndex() {
        ensureDirectories();
        List<ProjectIndexEntry> entries = new ArrayList<>();
        try {
            if (!Files.exists(PROJECTS_DIR)) {
                writeIndex(entries);
                return;
            }
            Files.list(PROJECTS_DIR)
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            ProjectRecord project = GSON.fromJson(Files.readString(path), ProjectRecord.class);
                            normalizeProject(project);
                            return project == null ? null : new ProjectIndexEntry(
                                    project.id,
                                    project.name,
                                    project.description,
                                    project.createdAt,
                                    project.updatedAt,
                                    project.workspaceFiles == null ? 0 : project.workspaceFiles.size(),
                                    project.boundsOrigin == null ? 0 : project.boundsOrigin.x,
                                    project.boundsOrigin == null ? 0 : project.boundsOrigin.y,
                                    project.boundsOrigin == null ? 0 : project.boundsOrigin.z,
                                    project.boundsSize == null ? 0 : project.boundsSize.x,
                                    project.boundsSize == null ? 0 : project.boundsSize.y,
                                    project.boundsSize == null ? 0 : project.boundsSize.z
                            );
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .forEach(entries::add);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed rebuilding project index: {}", e.getMessage());
        }
        writeIndex(entries);
    }

    private static void updateIndex(ProjectRecord project) {
        List<ProjectIndexEntry> entries = listProjects();
        entries.removeIf(entry -> project.id.equals(entry.id()));
        entries.add(new ProjectIndexEntry(
                project.id,
                project.name,
                project.description,
                project.createdAt,
                project.updatedAt,
                project.workspaceFiles == null ? 0 : project.workspaceFiles.size(),
                project.boundsOrigin == null ? 0 : project.boundsOrigin.x,
                project.boundsOrigin == null ? 0 : project.boundsOrigin.y,
                project.boundsOrigin == null ? 0 : project.boundsOrigin.z,
                project.boundsSize == null ? 0 : project.boundsSize.x,
                project.boundsSize == null ? 0 : project.boundsSize.y,
                project.boundsSize == null ? 0 : project.boundsSize.z
        ));
        writeIndex(entries);
    }

    private static void writeIndex(List<ProjectIndexEntry> entries) {
        ensureDirectories();
        JsonArray arr = new JsonArray();
        entries.stream()
                .sorted(Comparator.comparingLong(ProjectIndexEntry::updatedAt).reversed())
                .forEach(entry -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", entry.id());
                    obj.addProperty("name", entry.name());
                    obj.addProperty("description", entry.description());
                    obj.addProperty("createdAt", entry.createdAt());
                    obj.addProperty("updatedAt", entry.updatedAt());
                    obj.addProperty("workspaceCount", entry.workspaceCount());
                    obj.addProperty("originX", entry.originX());
                    obj.addProperty("originY", entry.originY());
                    obj.addProperty("originZ", entry.originZ());
                    obj.addProperty("sizeX", entry.sizeX());
                    obj.addProperty("sizeY", entry.sizeY());
                    obj.addProperty("sizeZ", entry.sizeZ());
                    arr.add(obj);
                });
        try {
            Files.writeString(INDEX_PATH, GSON.toJson(arr));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed writing project index: {}", e.getMessage());
        }
    }

    private static void normalizeProject(ProjectRecord project) {
        if (project == null) {
            return;
        }
        project.id = project.id == null ? "" : project.id.trim();
        project.name = normalizeProjectName(project.name);
        project.description = project.description == null ? "" : project.description.trim();
        if (project.createdAt <= 0) {
            project.createdAt = System.currentTimeMillis();
        }
        if (project.updatedAt <= 0) {
            project.updatedAt = project.createdAt;
        }
        if (project.workspaceFiles == null) {
            project.workspaceFiles = new LinkedHashMap<>();
        }
        if (project.metadata == null) {
            project.metadata = new JsonObject();
        }
        Map<String, WorkspaceFileRecord> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, WorkspaceFileRecord> entry : project.workspaceFiles.entrySet()) {
            WorkspaceFileRecord workspace = entry.getValue();
            if (workspace == null) {
                continue;
            }
            normalizeWorkspace(workspace);
            if (workspace.path == null || workspace.path.isBlank()) {
                continue;
            }
            normalized.put(workspace.path, workspace);
        }
        project.workspaceFiles = normalized;
    }

    private static void normalizeWorkspace(WorkspaceFileRecord workspace) {
        workspace.path = normalizeWorkspacePath(workspace.path);
        workspace.name = workspace.name == null || workspace.name.isBlank() ? leafName(workspace.path) : workspace.name.trim();
        workspace.type = normalizeWorkspaceType(workspace.type);
        workspace.areaTag = workspace.areaTag == null ? "" : workspace.areaTag.trim();
        workspace.revision = workspace.revision == null || workspace.revision.isBlank() ? "rev-0" : workspace.revision.trim();
        if (workspace.undoStack == null) {
            workspace.undoStack = new ArrayList<>();
        }
        if (workspace.redoStack == null) {
            workspace.redoStack = new ArrayList<>();
        }
        if (workspace.checkpoints == null) {
            workspace.checkpoints = new ArrayList<>();
        }
        if (workspace.summary == null) {
            workspace.summary = "";
        }
        if (workspace.dependsOn == null) {
            workspace.dependsOn = new ArrayList<>();
        }
        if (workspace.generatedFrom == null) {
            workspace.generatedFrom = "";
        }
        if (workspace.metadata == null) {
            workspace.metadata = new JsonObject();
        }
        if (workspace.pendingPatch != null) {
            if (workspace.pendingPatch.revisionBefore == null) {
                workspace.pendingPatch.revisionBefore = workspace.revision;
            }
            if (workspace.pendingPatch.revisionAfter == null || workspace.pendingPatch.revisionAfter.isBlank()) {
                workspace.pendingPatch.revisionAfter = workspace.revision;
            }
        }
    }

    public static String normalizeWorkspacePath(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        value = value.replaceAll("/{2,}", "/");
        return value;
    }

    public static String normalizeWorkspaceType(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "layout", "floor", "facade", "component", "semantic", "generated", "manual", "shared" -> value;
            default -> "manual";
        };
    }

    public static String leafName(String path) {
        String normalized = normalizeWorkspacePath(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String normalizeProjectName(String name) {
        String value = name == null ? "" : name.trim();
        return value.isBlank() ? "Current Project" : value;
    }

    private static String uniqueProjectId(String name) {
        String base = slugify(name);
        if (base.isBlank()) {
            base = "project";
        }
        String id = base;
        int index = 2;
        while (Files.exists(PROJECTS_DIR.resolve(id + ".json"))) {
            id = base + "-" + index;
            index++;
        }
        return id + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String slugify(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-+", "");
        normalized = normalized.replaceAll("-+$", "");
        return normalized;
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

    private static long getLong(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return 0L;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public record ProjectIndexEntry(
            String id,
            String name,
            String description,
            long createdAt,
            long updatedAt,
            int workspaceCount,
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ
    ) {
    }

    public static final class ProjectRecord {
        public String id;
        public String name;
        public String description;
        public long createdAt;
        public long updatedAt;
        public Vec3Data boundsOrigin;
        public Vec3Data boundsSize;
        public Map<String, WorkspaceFileRecord> workspaceFiles = new LinkedHashMap<>();
        public JsonObject metadata = new JsonObject();
    }

    public static final class WorkspaceFileRecord {
        public String path;
        public String name;
        public String type = "manual";
        public String areaTag = "";
        public Vec3Data origin;
        public Vec3Data size;
        public StructureBuilder.VbsScriptV2 current;
        public String revision = "rev-0";
        public PendingPatchRecord pendingPatch;
        public List<CommitRecord> undoStack = new ArrayList<>();
        public List<CommitRecord> redoStack = new ArrayList<>();
        public List<CheckpointRecord> checkpoints = new ArrayList<>();
        public String summary = "";
        public List<String> dependsOn = new ArrayList<>();
        public String generatedFrom = "";
        public JsonObject metadata = new JsonObject();
    }

    public static final class PendingPatchRecord {
        public PatchModels.StructurePatch patch;
        public StructureBuilder.VbsScriptV2 baseScript;
        public StructureBuilder.VbsScriptV2 nextScript;
        public PatchModels.Preview preview;
        public String revisionBefore;
        public String revisionAfter;
    }

    public static final class CommitRecord {
        public String revisionBefore;
        public String revisionAfter;
        public StructureBuilder.VbsScriptV2 beforeScript;
        public StructureBuilder.VbsScriptV2 afterScript;
        public String summary;
        public PatchModels.StructurePatch patch;
    }

    public static final class CheckpointRecord {
        public String id;
        public String label;
        public String revision;
        public StructureBuilder.VbsScriptV2 script;
        public long createdAt;
    }

    public static final class Vec3Data {
        public int x;
        public int y;
        public int z;

        public static Vec3Data of(BlockPos pos) {
            if (pos == null) {
                return null;
            }
            Vec3Data value = new Vec3Data();
            value.x = pos.getX();
            value.y = pos.getY();
            value.z = pos.getZ();
            return value;
        }

        public static Vec3Data of(Vec3i size) {
            if (size == null) {
                return null;
            }
            Vec3Data value = new Vec3Data();
            value.x = size.getX();
            value.y = size.getY();
            value.z = size.getZ();
            return value;
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        public Vec3i toVec3i() {
            return new Vec3i(x, y, z);
        }
    }
}
