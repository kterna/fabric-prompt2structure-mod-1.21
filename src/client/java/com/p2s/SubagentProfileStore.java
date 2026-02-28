package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SubagentProfileStore {
    public static final String DEFAULT_PROFILE_ID = "general-planner";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PROFILE_ROOT = FabricLoader.getInstance().getConfigDir()
            .resolve("p2s_skills")
            .resolve(".agent");
    private static final int DEFAULT_MAX_LOOPS = 4;
    private static final int DEFAULT_TIMEOUT_SECONDS = 45;
    private static final Set<String> SUPPORTED_TOOLS = Set.of(
            "list_skills",
            "read_skill",
            "search_skill",
            "read_workspace_state",
            "propose_patch",
            "search_block_ids"
    );

    private SubagentProfileStore() {
    }

    public static synchronized List<ProfileSummary> listProfileSummaries() {
        ensureDefaults();
        List<ProfileSummary> result = new ArrayList<>();
        for (SubagentProfile profile : listProfilesInternal()) {
            result.add(new ProfileSummary(
                    profile.id(),
                    profile.name(),
                    profile.description(),
                    profile.enabled(),
                    profile.allowedTools().size()
            ));
        }
        result.sort(Comparator.comparing(ProfileSummary::id));
        return result;
    }

    public static synchronized SubagentProfile resolveProfile(String requestedId) {
        ensureDefaults();
        String normalized = normalizeId(requestedId);
        if (normalized.isBlank()) {
            normalized = DEFAULT_PROFILE_ID;
        }
        SubagentProfile fallback = null;
        for (SubagentProfile profile : listProfilesInternal()) {
            if (profile.id().equals(DEFAULT_PROFILE_ID)) {
                fallback = profile;
            }
            if (profile.id().equals(normalized)) {
                return profile;
            }
        }
        return fallback;
    }

    public static synchronized SubagentProfile getProfile(String id) {
        String normalized = normalizeId(id);
        if (normalized.isBlank()) {
            return null;
        }
        ensureDefaults();
        for (SubagentProfile profile : listProfilesInternal()) {
            if (profile.id().equals(normalized)) {
                return profile;
            }
        }
        return null;
    }

    public static Set<String> supportedTools() {
        return SUPPORTED_TOOLS;
    }

    private static List<SubagentProfile> listProfilesInternal() {
        ensureDir(PROFILE_ROOT);
        List<SubagentProfile> profiles = new ArrayList<>();
        try (var stream = Files.list(PROFILE_ROOT)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".json")) {
                    continue;
                }
                SubagentProfile profile = parseProfile(file);
                if (profile != null) {
                    profiles.add(profile);
                }
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed listing subagent profiles in {}: {}", PROFILE_ROOT, e.getMessage());
        }
        profiles.sort(Comparator.comparing(SubagentProfile::id));
        return profiles;
    }

    private static SubagentProfile parseProfile(Path file) {
        try {
            String text = Files.readString(file);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String fromFileName = stripJsonExt(file.getFileName().toString());
            String id = normalizeId(getString(root, "id", fromFileName));
            if (id.isBlank()) {
                return null;
            }

            String name = getString(root, "name", id);
            String description = getString(root, "description", "");
            String systemPrompt = getString(root, "system_prompt", "");
            boolean enabled = getBoolean(root, "enabled", true);
            int maxLoops = clamp(getInt(root, "max_loops", DEFAULT_MAX_LOOPS), 1, 12);
            int timeoutSeconds = clamp(getInt(root, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS), 5, 300);

            List<String> allowedTools = readStringArray(root.get("allowed_tools"));
            List<String> filteredAllowedTools = new ArrayList<>();
            for (String tool : allowedTools) {
                if (SUPPORTED_TOOLS.contains(tool)) {
                    filteredAllowedTools.add(tool);
                }
            }
            if (filteredAllowedTools.isEmpty()) {
                filteredAllowedTools = defaultAllowedTools(id);
            }

            List<String> defaultSkillIds = readStringArray(root.get("default_skill_ids"));

            return new SubagentProfile(
                    id,
                    name.isBlank() ? id : name,
                    description,
                    systemPrompt,
                    List.copyOf(filteredAllowedTools),
                    List.copyOf(defaultSkillIds),
                    maxLoops,
                    timeoutSeconds,
                    enabled
            );
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed parsing subagent profile {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static void ensureDefaults() {
        ensureDir(PROFILE_ROOT);
        for (SubagentProfile profile : defaultProfiles()) {
            Path path = PROFILE_ROOT.resolve(profile.id() + ".json");
            if (Files.exists(path)) {
                continue;
            }
            try {
                JsonObject root = new JsonObject();
                root.addProperty("id", profile.id());
                root.addProperty("name", profile.name());
                root.addProperty("description", profile.description());
                root.addProperty("system_prompt", profile.systemPrompt());
                root.addProperty("max_loops", profile.maxLoops());
                root.addProperty("timeout_seconds", profile.timeoutSeconds());
                root.addProperty("enabled", profile.enabled());
                JsonArray allowedTools = new JsonArray();
                for (String tool : profile.allowedTools()) {
                    allowedTools.add(tool);
                }
                root.add("allowed_tools", allowedTools);
                JsonArray defaultSkills = new JsonArray();
                for (String id : profile.defaultSkillIds()) {
                    defaultSkills.add(id);
                }
                root.add("default_skill_ids", defaultSkills);
                Files.writeString(path, GSON.toJson(root));
            } catch (Exception e) {
                P2SMod.LOGGER.warn("Failed writing default subagent profile {}: {}", path, e.getMessage());
            }
        }
    }

    private static List<SubagentProfile> defaultProfiles() {
        List<SubagentProfile> defaults = new ArrayList<>();
        defaults.add(new SubagentProfile(
                "general-planner",
                "General Planner",
                "General decomposition and planning helper with workspace read access.",
                "You are a subagent focused on decomposing tasks, gathering context, and producing concise outputs.",
                List.of("list_skills", "read_skill", "search_skill", "read_workspace_state", "search_block_ids"),
                List.of(),
                DEFAULT_MAX_LOOPS,
                DEFAULT_TIMEOUT_SECONDS,
                true
        ));
        defaults.add(new SubagentProfile(
                "block-id-searcher",
                "Block ID Searcher",
                "Find accurate block ids and provide ranked candidates.",
                "You specialize in identifying exact Minecraft block IDs and short compatibility notes.",
                List.of("search_block_ids", "list_skills", "read_skill", "search_skill"),
                List.of(),
                DEFAULT_MAX_LOOPS,
                DEFAULT_TIMEOUT_SECONDS,
                true
        ));
        defaults.add(new SubagentProfile(
                "workspace-analyzer",
                "Workspace Analyzer",
                "Analyze staged workspace state and summarize constraints/risks.",
                "You analyze workspace state and produce practical summaries for the parent agent.",
                List.of("read_workspace_state", "list_skills", "read_skill", "search_skill"),
                List.of(),
                DEFAULT_MAX_LOOPS,
                DEFAULT_TIMEOUT_SECONDS,
                true
        ));
        defaults.add(new SubagentProfile(
                "patch-planner",
                "Patch Planner",
                "Design patch proposals with staged validation awareness.",
                "You plan safe, minimal patch operations and report expected impact clearly.",
                List.of("read_workspace_state", "search_block_ids", "propose_patch", "list_skills", "read_skill", "search_skill"),
                List.of(),
                DEFAULT_MAX_LOOPS,
                DEFAULT_TIMEOUT_SECONDS,
                true
        ));
        return defaults;
    }

    private static List<String> defaultAllowedTools(String profileId) {
        for (SubagentProfile profile : defaultProfiles()) {
            if (profile.id().equals(profileId)) {
                return new ArrayList<>(profile.allowedTools());
            }
        }
        return new ArrayList<>(defaultProfiles().get(0).allowedTools());
    }

    private static List<String> readStringArray(JsonElement element) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (!entry.isJsonPrimitive()) {
                    continue;
                }
                String value = normalizeId(entry.getAsString());
                if (!value.isBlank()) {
                    items.add(value);
                }
            }
        }
        return new ArrayList<>(items);
    }

    private static String getString(JsonObject root, String key, String fallback) {
        if (root == null || key == null || !root.has(key)) {
            return fallback;
        }
        try {
            String value = root.get(key).getAsString();
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value.trim();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int getInt(JsonObject root, String key, int fallback) {
        if (root == null || key == null || !root.has(key)) {
            return fallback;
        }
        try {
            return root.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        if (root == null || key == null || !root.has(key)) {
            return fallback;
        }
        try {
            return root.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static String stripJsonExt(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        return fileName;
    }

    private static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9_-]+", "-");
        value = value.replaceAll("-{2,}", "-");
        value = value.replaceAll("^-+", "");
        value = value.replaceAll("-+$", "");
        return value;
    }

    private static void ensureDir(Path dir) {
        try {
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed creating directory " + dir + ": " + e.getMessage(), e);
        }
    }

    public record SubagentProfile(
            String id,
            String name,
            String description,
            String systemPrompt,
            List<String> allowedTools,
            List<String> defaultSkillIds,
            int maxLoops,
            int timeoutSeconds,
            boolean enabled
    ) {
    }

    public record ProfileSummary(
            String id,
            String name,
            String description,
            boolean enabled,
            int toolCount
    ) {
    }
}
