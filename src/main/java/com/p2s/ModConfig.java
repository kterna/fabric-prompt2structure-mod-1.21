package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("p2s.json");

    private static final String DEFAULT_API_URL = "http://localhost:8000/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final boolean DEFAULT_USE_TOOL_CALL = true;
    private static final int DEFAULT_MAX_PATCH_OPS = 20000;
    private static final int DEFAULT_MAX_BLOCKS_PER_COMMIT = 50000;
    private static final boolean DEFAULT_CONFIRM_REQUIRED = true;
    private static final int DEFAULT_SESSION_JOB_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_RISK_AUTO_APPLY_THRESHOLD = -1;
    private static final String DEFAULT_PROMPT_NAME = "default";
    public static final String DEFAULT_SYSTEM_PROMPT_V1 = """
            You are a Minecraft Architect. 
            Target: Generate a structure based on user prompt.
            Output Format: JSON ONLY. No markdown, no comments.
            Schema:
            {
              "palette": {"KEY": "minecraft:block_id"},
              "structure": [
                {"actions": [{"type": "box", "block": "KEY", "mode": "solid", "from": [x,y,z], "to": [x,y,z]}]}
              ]
            }
            Actions:
            1. "box": Cuboid primitives with mode=solid|shell|walls.
            2. "plane": Flat primitives with axis=x|y|z and mode=solid|outline.
            3. "line": Draw a 3D line from "from" to "to".
            4. "points": Place blocks at explicit coordinates "at": [[x,y,z],...].
            Optional per-action field: "facing": "north|south|east|west|up|down" to set block facing when supported.
            Rules:
            - Coordinates are relative to 0,0,0.
            - Use standard Minecraft Java Edition block IDs (e.g., minecraft:oak_log).
            - Optimize: Use "box" and "plane" for large areas to save tokens.
            """;

    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个在 IDE 模式下工作的 Minecraft 建筑代理。

            ## 工作流程
            1) 先通过 get_project_state 检查当前项目状态。
            2) 再使用 read_workspace_file 读取目标工作区文件。
            3) 然后基于该工作区调用 propose_patch 提交修改提案。
            4) 不要直接放置方块；服务器只会在用户确认后应用 patch。
            5) 如果 propose_patch 返回错误或警告，先修复，再让用户决定是否应用。

            ## 工具：get_project_state
            - 读取项目摘要、工作区文件列表、待处理文件路径以及工作区元数据。

            ## 工具：propose_patch
            - path：必填，必须明确指定目标工作区文件路径。
            - base_revision：可选；除非服务器给出 revision token，否则传空字符串。
            - operations：按顺序提交 patch 操作。
            - op 可选值：upsert_part | delete_part | patch_actions | set_palette。
            - actions 支持 box / plane / line / points，并可选 facing。

            ## 工具：read_workspace_file
            - 在编辑前读取工作区尺寸和现有脚本内容。

            ## 工具：search_block_ids
            - 不确定方块 ID 时，先按关键词查询合法方块名。

            ## 规则
            - 坐标一律相对 (0,0,0)。
            - palette 中使用合法的 Java 版方块 ID。
            - action.block 可以是 palette key 或完整 block id，但优先使用 palette key。
            - 修改尽量小、尽量增量。
            - 小范围调整优先使用 patch_actions。
            - 整块逻辑替换优先使用 upsert_part。
            - 在 message_to_user 中提供一句简短、面向玩家的变更说明。
            """;

    public static volatile String API_URL;
    public static volatile String API_KEY;
    public static volatile String MODEL;
    public static volatile int HTTP_TIMEOUT_SECONDS;
    public static volatile boolean USE_TOOL_CALL;
    public static volatile int MAX_PATCH_OPS;
    public static volatile int MAX_BLOCKS_PER_COMMIT;
    public static volatile boolean CONFIRM_REQUIRED;
    public static volatile int SESSION_JOB_TIMEOUT_SECONDS;
    public static volatile int RISK_AUTO_APPLY_THRESHOLD;
    public static volatile Map<String, String> PROMPTS;
    public static volatile String ACTIVE_PROMPT_NAME;

    static {
        apply(loadFromFile());
    }

    private ModConfig() {
    }

    private static Values loadFromFile() {
        Values defaults = new Values();
        defaults.apiUrl = DEFAULT_API_URL;
        defaults.apiKey = "replace-with-api-key";
        defaults.model = DEFAULT_MODEL;
        defaults.httpTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        defaults.useToolCall = DEFAULT_USE_TOOL_CALL;
        defaults.maxPatchOps = DEFAULT_MAX_PATCH_OPS;
        defaults.maxBlocksPerCommit = DEFAULT_MAX_BLOCKS_PER_COMMIT;
        defaults.confirmRequired = DEFAULT_CONFIRM_REQUIRED;
        defaults.sessionJobTimeoutSeconds = DEFAULT_SESSION_JOB_TIMEOUT_SECONDS;
        defaults.riskAutoApplyThreshold = DEFAULT_RISK_AUTO_APPLY_THRESHOLD;
        defaults.prompts = defaultPrompts();
        defaults.activePrompt = DEFAULT_PROMPT_NAME;

        try {
            if (!Files.exists(CONFIG_PATH)) {
                ensureParentDir();
                Files.writeString(CONFIG_PATH, GSON.toJson(defaults));
                P2SMod.LOGGER.info("已生成默认配置文件: {}", CONFIG_PATH);
                return defaults;
            }
            String json = Files.readString(CONFIG_PATH);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject copy = root.deepCopy();
            copy.remove("prompts"); // avoid type mismatch when prompts values are arrays
            Values loaded = GSON.fromJson(copy, Values.class);
            if (loaded == null) {
                return defaults;
            }
            loaded.prompts = parsePrompts(root.get("prompts"), defaults.prompts);
            ensurePromptDefaults(loaded);
            return loaded;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("读取配置失败，使用默认值: {}", e.getMessage());
            return defaults;
        }
    }

    private static String pickEnvOrConfig(String envKey, String configValue, String defaultValue) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        if (configValue != null && !configValue.isBlank()) {
            return configValue.trim();
        }
        return defaultValue;
    }

    private static int pickEnvOrConfigInt(String envKey, Integer configValue, int defaultValue) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                P2SMod.LOGGER.warn("环境变量 {} 不是有效数字，将使用配置或默认值", envKey);
            }
        }
        if (configValue != null && configValue > 0) {
            return configValue;
        }
        return defaultValue;
    }

    private static boolean pickEnvOrConfigBool(String envKey, Boolean configValue, boolean defaultValue) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            String val = env.trim().toLowerCase();
            if ("true".equals(val) || "1".equals(val) || "yes".equals(val)) {
                return true;
            }
            if ("false".equals(val) || "0".equals(val) || "no".equals(val)) {
                return false;
            }
            P2SMod.LOGGER.warn("环境变量 {} 不是有效布尔值，将使用配置或默认值", envKey);
        }
        if (configValue != null) {
            return configValue;
        }
        return defaultValue;
    }

    private static String pickPromptName(String envKey, String configName, Map<String, String> prompts) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank() && prompts.containsKey(env.trim())) {
            return env.trim();
        }
        if (configName != null && prompts.containsKey(configName)) {
            return configName;
        }
        if (prompts.containsKey(DEFAULT_PROMPT_NAME)) {
            return DEFAULT_PROMPT_NAME;
        }
        if (!prompts.isEmpty()) {
            return prompts.keySet().iterator().next();
        }
        return DEFAULT_PROMPT_NAME;
    }

    private static Map<String, String> parsePrompts(JsonElement elem, Map<String, String> fallback) {
        Map<String, String> result = new LinkedHashMap<>();
        if (elem != null && elem.isJsonObject()) {
            elem.getAsJsonObject().entrySet().forEach(e -> {
                String val = parsePromptValue(e.getValue());
                if (val != null) {
                    result.put(e.getKey(), val);
                }
            });
        }
        if (result.isEmpty()) {
            return fallback;
        }
        return result;
    }

    private static String parsePromptValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        if (value.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            value.getAsJsonArray().forEach(item -> {
                if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(item.getAsString());
                }
            });
            return sb.toString();
        }
        return null;
    }

    private static void ensureParentDir() throws IOException {
        Path parent = CONFIG_PATH.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    public static String describeConfigSource() {
        return "config=" + CONFIG_PATH.toAbsolutePath();
    }

    public static synchronized void reload() {
        apply(loadFromFile());
        P2SMod.LOGGER.info("Config reloaded: url={}, model={}, timeout={}s, maxPatchOps={}, maxBlocksPerCommit={}, confirmRequired={}",
                API_URL, MODEL, HTTP_TIMEOUT_SECONDS, MAX_PATCH_OPS, MAX_BLOCKS_PER_COMMIT, CONFIRM_REQUIRED);
    }

    private static synchronized void apply(Values file) {
        API_URL = pickEnvOrConfig("P2S_API_URL", file.apiUrl, DEFAULT_API_URL);
        API_KEY = pickEnvOrConfig("P2S_API_KEY", file.apiKey, "replace-with-api-key");
        MODEL = pickEnvOrConfig("P2S_MODEL", file.model, DEFAULT_MODEL);
        HTTP_TIMEOUT_SECONDS = pickEnvOrConfigInt("P2S_TIMEOUT_SECONDS", file.httpTimeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        USE_TOOL_CALL = pickEnvOrConfigBool("P2S_USE_TOOL_CALL", file.useToolCall, DEFAULT_USE_TOOL_CALL);
        MAX_PATCH_OPS = pickEnvOrConfigInt("P2S_MAX_PATCH_OPS", file.maxPatchOps, DEFAULT_MAX_PATCH_OPS);
        MAX_BLOCKS_PER_COMMIT = pickEnvOrConfigInt("P2S_MAX_BLOCKS_PER_COMMIT", file.maxBlocksPerCommit, DEFAULT_MAX_BLOCKS_PER_COMMIT);
        CONFIRM_REQUIRED = pickEnvOrConfigBool("P2S_CONFIRM_REQUIRED", file.confirmRequired, DEFAULT_CONFIRM_REQUIRED);
        SESSION_JOB_TIMEOUT_SECONDS = pickEnvOrConfigInt("P2S_SESSION_JOB_TIMEOUT_SECONDS", file.sessionJobTimeoutSeconds, DEFAULT_SESSION_JOB_TIMEOUT_SECONDS);
        RISK_AUTO_APPLY_THRESHOLD = file.riskAutoApplyThreshold == null ? DEFAULT_RISK_AUTO_APPLY_THRESHOLD : file.riskAutoApplyThreshold;
        PROMPTS = new LinkedHashMap<>(file.prompts == null ? defaultPrompts() : file.prompts);
        ensureDefaultPromptEntry(PROMPTS);
        ACTIVE_PROMPT_NAME = pickPromptName("P2S_PROMPT", file.activePrompt, PROMPTS);

        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] Config applied -> apiUrl={}, model={}, timeout={}s, useToolCall={}, maxPatchOps={}, maxBlocksPerCommit={}, confirmRequired={}, sessionJobTimeout={}s, riskAutoApply={}, activePrompt={}, promptKeys={}",
                    API_URL, MODEL, HTTP_TIMEOUT_SECONDS, USE_TOOL_CALL, MAX_PATCH_OPS, MAX_BLOCKS_PER_COMMIT, CONFIRM_REQUIRED, SESSION_JOB_TIMEOUT_SECONDS, RISK_AUTO_APPLY_THRESHOLD, ACTIVE_PROMPT_NAME, PROMPTS.keySet());
            for (Map.Entry<String, String> entry : PROMPTS.entrySet()) {
                P2SMod.LOGGER.info("[DEBUG] Config prompt [{}]: {}", entry.getKey(), entry.getValue());
            }
        }
    }

    private static class Values {
        String apiUrl;
        String apiKey;
        String model;
        Integer httpTimeoutSeconds;
        Boolean useToolCall;
        Integer maxPatchOps;
        Integer maxBlocksPerCommit;
        Boolean confirmRequired;
        Integer sessionJobTimeoutSeconds;
        Integer riskAutoApplyThreshold;
        Map<String, String> prompts;
        String activePrompt;
    }

    public static String currentSystemPrompt() {
        String prompt = PROMPTS.get(ACTIVE_PROMPT_NAME);
        if (prompt == null) {
            P2SMod.LOGGER.warn("Prompt '{}' not found, fallback to default", ACTIVE_PROMPT_NAME);
            prompt = PROMPTS.getOrDefault(DEFAULT_PROMPT_NAME, DEFAULT_SYSTEM_PROMPT);
        }
        return prompt;
    }

    public static synchronized boolean setActivePrompt(String name, boolean persist) {
        if (name == null || !PROMPTS.containsKey(name)) {
            return false;
        }
        ACTIVE_PROMPT_NAME = name;
        if (persist) {
            persistActivePrompt(name);
        }
        P2SMod.LOGGER.info("Active prompt set to {}", name);
        return true;
    }

    public static synchronized Map<String, String> promptMap() {
        return new LinkedHashMap<>(PROMPTS);
    }

    public static String activePromptName() {
        return ACTIVE_PROMPT_NAME;
    }

    private static Map<String, String> defaultPrompts() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(DEFAULT_PROMPT_NAME, DEFAULT_SYSTEM_PROMPT);
        defaults.put("legacy_v1", DEFAULT_SYSTEM_PROMPT_V1);
        defaults.put("cozy_cabin", DEFAULT_SYSTEM_PROMPT + """

Style preset: Cozy wooden cabin. Keep footprint <= 12x12, height <= 12. Palette: spruce_log frame, oak_planks walls, spruce_stairs + spruce_slab roof, glass_pane windows, cobblestone/chiseled_stone_bricks chimney, spruce_door. Roof pitched (slope ~3:1), 1-block foundation, windows 2x2 with flower_pots, lanterns at entry. Interior must include: bed, crafting_table, furnace, chest. Add campfire on chimney for smoke.""");
        defaults.put("modern_villa", DEFAULT_SYSTEM_PROMPT + """

Style preset: Modern villa. Keep footprint <= 16x20, height <= 14. Palette: white_concrete walls, gray_concrete accents, black_stained_glass panes, quartz_stairs/slabs overhangs, dark_oak_door, sea_lantern lighting. Flat roof with 1-block parapet, large windows (3x4 or larger), balcony with glass pane railing and quartz_slab floor, small pool (water + quartz_slab edge). Avoid medieval blocks.""");
        return defaults;
    }

    private static void ensurePromptDefaults(Values v) {
        if (v.prompts == null || v.prompts.isEmpty()) {
            v.prompts = defaultPrompts();
        } else if (!v.prompts.containsKey(DEFAULT_PROMPT_NAME)) {
            v.prompts.put(DEFAULT_PROMPT_NAME, DEFAULT_SYSTEM_PROMPT);
        }
        if (v.activePrompt == null || !v.prompts.containsKey(v.activePrompt)) {
            v.activePrompt = DEFAULT_PROMPT_NAME;
        }
    }

    private static void ensureDefaultPromptEntry(Map<String, String> prompts) {
        if (!prompts.containsKey(DEFAULT_PROMPT_NAME)) {
            prompts.put(DEFAULT_PROMPT_NAME, DEFAULT_SYSTEM_PROMPT);
        }
    }

    private static void persistActivePrompt(String name) {
        try {
            ensureParentDir();
            JsonObject root;
            if (Files.exists(CONFIG_PATH)) {
                root = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
            } else {
                root = new JsonObject();
                root.add("prompts", GSON.toJsonTree(defaultPrompts()));
            }
            root.addProperty("activePrompt", name);
            Files.writeString(CONFIG_PATH, GSON.toJson(root));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to persist active prompt: {}", e.getMessage());
        }
    }
}
