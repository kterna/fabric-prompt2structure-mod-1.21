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
    private static final String DEFAULT_PROMPT_NAME = "default";
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a Minecraft Architect. 
            Target: Generate a structure based on user prompt.
            Output Format: JSON ONLY. No markdown, no comments.
            Schema:
            {
              "palette": {"KEY": "minecraft:block_id"},
              "structure": [
                {"actions": [{"type": "fill", "block": "KEY", "from": [x,y,z], "to": [x,y,z]}]}
              ]
            }
            Actions:
            1. "fill": Fill a solid cuboid.
            2. "frame": Create hollow walls/box (faces only) for the cuboid region.
            3. "set": Place blocks at specific list of coordinates "at": [[x,y,z],...].
            Optional per-action field: "facing": "north|south|east|west|up|down" to set block facing when supported.
            Rules:
            - Coordinates are relative to 0,0,0.
            - Use standard Minecraft Java Edition block IDs (e.g., minecraft:oak_log).
            - Optimize: Use "fill" and "frame" for large areas to save tokens.
            """;

    public static volatile String API_URL;
    public static volatile String API_KEY;
    public static volatile String MODEL;
    public static volatile int HTTP_TIMEOUT_SECONDS;
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
        String envUrl = System.getenv("P2S_API_URL");
        String envKey = System.getenv("P2S_API_KEY");
        String envModel = System.getenv("P2S_MODEL");
        return "config=" + CONFIG_PATH.toAbsolutePath()
                + ", env(url/key/model)=" + (envUrl != null || envKey != null || envModel != null)
                + ", activePrompt=" + ACTIVE_PROMPT_NAME;
    }

    public static synchronized void reload() {
        apply(loadFromFile());
        P2SMod.LOGGER.info("Config reloaded: url={}, model={}, timeout={}s", API_URL, MODEL, HTTP_TIMEOUT_SECONDS);
    }

    private static synchronized void apply(Values file) {
        API_URL = pickEnvOrConfig("P2S_API_URL", file.apiUrl, DEFAULT_API_URL);
        API_KEY = pickEnvOrConfig("P2S_API_KEY", file.apiKey, "replace-with-api-key");
        MODEL = pickEnvOrConfig("P2S_MODEL", file.model, DEFAULT_MODEL);
        HTTP_TIMEOUT_SECONDS = pickEnvOrConfigInt("P2S_TIMEOUT_SECONDS", file.httpTimeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
        PROMPTS = new LinkedHashMap<>(file.prompts == null ? defaultPrompts() : file.prompts);
        ensureDefaultPromptEntry(PROMPTS);
        ACTIVE_PROMPT_NAME = pickPromptName("P2S_PROMPT", file.activePrompt, PROMPTS);
    }

    private static class Values {
        String apiUrl;
        String apiKey;
        String model;
        Integer httpTimeoutSeconds;
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
