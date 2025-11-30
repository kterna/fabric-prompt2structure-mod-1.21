package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("p2s.json");

    private static final String DEFAULT_API_URL = "http://localhost:8000/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public static volatile String API_URL;
    public static volatile String API_KEY;
    public static volatile String MODEL;
    public static volatile int HTTP_TIMEOUT_SECONDS;

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

        try {
            if (!Files.exists(CONFIG_PATH)) {
                ensureParentDir();
                Files.writeString(CONFIG_PATH, GSON.toJson(defaults));
                P2SMod.LOGGER.info("已生成默认配置文件: {}", CONFIG_PATH);
                return defaults;
            }
            String json = Files.readString(CONFIG_PATH);
            Values loaded = GSON.fromJson(json, Values.class);
            return loaded == null ? defaults : loaded;
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
                + ", env(url/key/model)=" + (envUrl != null || envKey != null || envModel != null);
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
    }

    private static class Values {
        String apiUrl;
        String apiKey;
        String model;
        Integer httpTimeoutSeconds;
    }
}
