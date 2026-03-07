package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class P2SClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("p2s_client.json");
    private static final String DEFAULT_SELECTION_ITEM_ID = "minecraft:spectral_arrow";
    private static final String DEFAULT_API_URL = "http://localhost:8000/v1/chat/completions";
    private static final String DEFAULT_API_KEY = "replace-with-api-key";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final boolean DEFAULT_USE_TOOL_CALL = true;
    private static final boolean DEFAULT_USE_STREAMING = true;
    private static final boolean DEFAULT_AUTO_APPLY_PATCH = false;
    private static final String DEFAULT_SYSTEM_PROMPT = ModConfig.DEFAULT_SYSTEM_PROMPT;

    private static String selectionItemId = DEFAULT_SELECTION_ITEM_ID;
    private static String apiUrl = DEFAULT_API_URL;
    private static String apiKey = DEFAULT_API_KEY;
    private static String model = DEFAULT_MODEL;
    private static int httpTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private static boolean useToolCall = DEFAULT_USE_TOOL_CALL;
    private static boolean useStreaming = DEFAULT_USE_STREAMING;
    private static boolean autoApplyPatch = DEFAULT_AUTO_APPLY_PATCH;
    private static String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    private static final Map<String, List<String>> workspaceFoldersByProject = new LinkedHashMap<>();

    private P2SClientConfig() {
    }

    public static synchronized void reload() {
        load();
    }

    public static synchronized String getSelectionItemId() {
        return selectionItemId;
    }

    public static synchronized boolean setSelectionItemId(String rawItemId, boolean persist) {
        String normalized = normalizeItemId(rawItemId);
        if (normalized == null) {
            return false;
        }
        selectionItemId = normalized;
        if (persist) {
            save();
        }
        return true;
    }

    public static String defaultSelectionItemId() {
        return DEFAULT_SELECTION_ITEM_ID;
    }

    public static synchronized String getApiUrl() {
        return apiUrl;
    }

    public static synchronized String getApiKey() {
        return apiKey;
    }

    public static synchronized String getModel() {
        return model;
    }

    public static synchronized int getHttpTimeoutSeconds() {
        return httpTimeoutSeconds;
    }

    public static synchronized boolean isUseToolCall() {
        return useToolCall;
    }

    public static synchronized String getSystemPrompt() {
        return systemPrompt;
    }

    public static synchronized boolean getUseStreaming() {
        return useStreaming;
    }

    public static synchronized boolean getAutoApplyPatch() {
        return autoApplyPatch;
    }

    public static synchronized void setAutoApplyPatch(boolean value, boolean persist) {
        autoApplyPatch = value;
        if (persist) {
            save();
        }
    }

    public static synchronized void setUseStreaming(boolean value, boolean persist) {
        useStreaming = value;
        if (persist) {
            save();
        }
    }

    public static synchronized List<String> getWorkspaceFolders(String projectId) {
        String key = normalizeProjectKey(projectId);
        if (key == null) {
            return List.of();
        }
        List<String> folders = workspaceFoldersByProject.get(key);
        return folders == null ? List.of() : List.copyOf(folders);
    }

    public static synchronized void addWorkspaceFolder(String projectId, String folderPath, boolean persist) {
        String key = normalizeProjectKey(projectId);
        String normalizedFolder = normalizeFolderPath(folderPath);
        if (key == null || normalizedFolder == null) {
            return;
        }
        List<String> current = new ArrayList<>(workspaceFoldersByProject.getOrDefault(key, List.of()));
        LinkedHashSet<String> merged = new LinkedHashSet<>(normalizeFolderList(current));
        merged.add(normalizedFolder);
        workspaceFoldersByProject.put(key, new ArrayList<>(merged));
        if (persist) {
            save();
        }
    }

    public static synchronized void ensureWorkspaceFoldersForPath(String projectId, String workspacePath, boolean persist) {
        String key = normalizeProjectKey(projectId);
        String normalizedPath = normalizeFolderPath(workspacePath);
        if (key == null || normalizedPath == null) {
            return;
        }
        int slash = normalizedPath.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        Set<String> folders = new LinkedHashSet<>(workspaceFoldersByProject.getOrDefault(key, List.of()));
        while (slash > 0) {
            folders.add(normalizedPath.substring(0, slash));
            slash = normalizedPath.lastIndexOf('/', slash - 1);
        }
        workspaceFoldersByProject.put(key, new ArrayList<>(normalizeFolderList(folders)));
        if (persist) {
            save();
        }
    }

    public static synchronized void removeWorkspaceFolder(String projectId, String folderPath, boolean persist) {
        String key = normalizeProjectKey(projectId);
        String normalizedFolder = normalizeFolderPath(folderPath);
        if (key == null || normalizedFolder == null) {
            return;
        }
        List<String> current = new ArrayList<>(workspaceFoldersByProject.getOrDefault(key, List.of()));
        current.removeIf(folder -> folder.equals(normalizedFolder) || folder.startsWith(normalizedFolder + "/"));
        if (current.isEmpty()) {
            workspaceFoldersByProject.remove(key);
        } else {
            workspaceFoldersByProject.put(key, new ArrayList<>(normalizeFolderList(current)));
        }
        if (persist) {
            save();
        }
    }

    public static synchronized boolean setLlmConfig(
            String rawApiUrl,
            String rawApiKey,
            String rawModel,
            Integer rawTimeoutSeconds,
            Boolean rawUseToolCall,
            String rawSystemPrompt,
            boolean persist
    ) {
        String nextApiUrl = normalizeNonBlank(rawApiUrl);
        String nextApiKey = normalizeNonBlank(rawApiKey);
        String nextModel = normalizeNonBlank(rawModel);
        Integer nextTimeout = normalizePositiveInt(rawTimeoutSeconds);
        Boolean nextToolCall = rawUseToolCall;
        String nextSystemPrompt = rawSystemPrompt == null ? null : rawSystemPrompt.trim();

        if (nextApiUrl == null || nextApiKey == null || nextModel == null || nextTimeout == null || nextToolCall == null) {
            return false;
        }
        if (nextSystemPrompt == null || nextSystemPrompt.isBlank()) {
            nextSystemPrompt = DEFAULT_SYSTEM_PROMPT;
        }

        apiUrl = nextApiUrl;
        apiKey = nextApiKey;
        model = nextModel;
        httpTimeoutSeconds = nextTimeout;
        useToolCall = nextToolCall;
        systemPrompt = nextSystemPrompt;
        if (persist) {
            save();
        }
        return true;
    }

    public static synchronized void resetLlmConfigDefaults(boolean persist) {
        apiUrl = DEFAULT_API_URL;
        apiKey = DEFAULT_API_KEY;
        model = DEFAULT_MODEL;
        httpTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        useToolCall = DEFAULT_USE_TOOL_CALL;
        useStreaming = DEFAULT_USE_STREAMING;
        autoApplyPatch = DEFAULT_AUTO_APPLY_PATCH;
        systemPrompt = DEFAULT_SYSTEM_PROMPT;
        if (persist) {
            save();
        }
    }

    public static synchronized LLMService.RequestConfig llmRequestConfig() {
        return new LLMService.RequestConfig(
                apiUrl,
                apiKey,
                model,
                httpTimeoutSeconds,
                useToolCall
        );
    }

    public static boolean isSelectionItem(Item item) {
        if (item == null) {
            return false;
        }
        String currentId;
        synchronized (P2SClientConfig.class) {
            currentId = selectionItemId;
        }
        ResourceLocation id = ResourceLocation.tryParse(currentId);
        if (id == null) {
            return false;
        }
        Item target = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return target != null && target == item;
    }

    private static void load() {
        String loaded = null;
        String loadedApiUrl = null;
        String loadedApiKey = null;
        String loadedModel = null;
        Integer loadedTimeout = null;
        Boolean loadedUseToolCall = null;
        Boolean loadedUseStreaming = null;
        Boolean loadedAutoApplyPatch = null;
        String loadedSystemPrompt = null;
        Map<String, List<String>> loadedFolders = new LinkedHashMap<>();
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
                loaded = root.has("selectionItem") ? root.get("selectionItem").getAsString() : null;
                loadedApiUrl = root.has("apiUrl") ? root.get("apiUrl").getAsString() : null;
                loadedApiKey = root.has("apiKey") ? root.get("apiKey").getAsString() : null;
                loadedModel = root.has("model") ? root.get("model").getAsString() : null;
                loadedTimeout = root.has("httpTimeoutSeconds") ? root.get("httpTimeoutSeconds").getAsInt() : null;
                loadedUseToolCall = root.has("useToolCall") ? root.get("useToolCall").getAsBoolean() : null;
                loadedUseStreaming = root.has("useStreaming") ? root.get("useStreaming").getAsBoolean() : null;
                loadedAutoApplyPatch = root.has("autoApplyPatch") ? root.get("autoApplyPatch").getAsBoolean() : null;
                loadedSystemPrompt = root.has("systemPrompt") ? root.get("systemPrompt").getAsString() : null;
                if (root.has("workspaceFoldersByProject") && root.get("workspaceFoldersByProject").isJsonObject()) {
                    JsonObject foldersRoot = root.getAsJsonObject("workspaceFoldersByProject");
                    for (Map.Entry<String, JsonElement> entry : foldersRoot.entrySet()) {
                        String projectKey = normalizeProjectKey(entry.getKey());
                        if (projectKey == null || entry.getValue() == null || !entry.getValue().isJsonArray()) {
                            continue;
                        }
                        List<String> folderList = new ArrayList<>();
                        JsonArray arr = entry.getValue().getAsJsonArray();
                        for (JsonElement element : arr) {
                            if (element != null && element.isJsonPrimitive()) {
                                String folder = normalizeFolderPath(element.getAsString());
                                if (folder != null) {
                                    folderList.add(folder);
                                }
                            }
                        }
                        List<String> normalizedFolders = normalizeFolderList(folderList);
                        if (!normalizedFolders.isEmpty()) {
                            loadedFolders.put(projectKey, normalizedFolders);
                        }
                    }
                }
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed reading client config {}, using defaults: {}", CONFIG_PATH, e.getMessage());
        }

        String normalized = normalizeItemId(loaded);
        if (normalized == null) {
            normalized = DEFAULT_SELECTION_ITEM_ID;
        }
        selectionItemId = normalized;
        apiUrl = normalizeNonBlank(loadedApiUrl);
        if (apiUrl == null) {
            apiUrl = DEFAULT_API_URL;
        }
        apiKey = normalizeNonBlank(loadedApiKey);
        if (apiKey == null) {
            apiKey = DEFAULT_API_KEY;
        }
        model = normalizeNonBlank(loadedModel);
        if (model == null) {
            model = DEFAULT_MODEL;
        }
        Integer timeout = normalizePositiveInt(loadedTimeout);
        httpTimeoutSeconds = timeout == null ? DEFAULT_TIMEOUT_SECONDS : timeout;
        useToolCall = loadedUseToolCall == null ? DEFAULT_USE_TOOL_CALL : loadedUseToolCall;
        useStreaming = loadedUseStreaming == null ? DEFAULT_USE_STREAMING : loadedUseStreaming;
        autoApplyPatch = loadedAutoApplyPatch == null ? DEFAULT_AUTO_APPLY_PATCH : loadedAutoApplyPatch;
        String prompt = loadedSystemPrompt == null ? "" : loadedSystemPrompt.trim();
        systemPrompt = prompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : prompt;
        workspaceFoldersByProject.clear();
        workspaceFoldersByProject.putAll(loadedFolders);
        save();
    }

    private static void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            root.addProperty("selectionItem", selectionItemId);
            root.addProperty("apiUrl", apiUrl);
            root.addProperty("apiKey", apiKey);
            root.addProperty("model", model);
            root.addProperty("httpTimeoutSeconds", httpTimeoutSeconds);
            root.addProperty("useToolCall", useToolCall);
            root.addProperty("useStreaming", useStreaming);
            root.addProperty("autoApplyPatch", autoApplyPatch);
            root.addProperty("systemPrompt", systemPrompt);

            JsonObject foldersRoot = new JsonObject();
            for (Map.Entry<String, List<String>> entry : workspaceFoldersByProject.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                List<String> folders = normalizeFolderList(entry.getValue());
                if (folders.isEmpty()) {
                    continue;
                }
                JsonArray arr = new JsonArray();
                for (String folder : folders) {
                    arr.add(folder);
                }
                foldersRoot.add(entry.getKey(), arr);
            }
            root.add("workspaceFoldersByProject", foldersRoot);
            Files.writeString(CONFIG_PATH, GSON.toJson(root));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed writing client config {}: {}", CONFIG_PATH, e.getMessage());
        }
    }

    private static String normalizeItemId(String rawItemId) {
        if (rawItemId == null || rawItemId.isBlank()) {
            return null;
        }
        String trimmed = rawItemId.trim().toLowerCase();
        ResourceLocation id = ResourceLocation.tryParse(trimmed);
        if (id == null) {
            id = ResourceLocation.tryParse("minecraft:" + trimmed);
        }
        if (id == null) {
            return null;
        }
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        return id.toString();
    }

    private static String normalizeNonBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Integer normalizePositiveInt(Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private static String normalizeProjectKey(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        String normalized = projectId.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeFolderPath(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return null;
        }
        String normalized = folderPath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        normalized = normalized.replaceAll("/{2,}", "/");
        return normalized.isBlank() ? null : normalized;
    }

    private static List<String> normalizeFolderList(Iterable<String> folders) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (folders != null) {
            for (String folder : folders) {
                String value = normalizeFolderPath(folder);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        return new ArrayList<>(normalized);
    }
}
