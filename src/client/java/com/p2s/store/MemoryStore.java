package com.p2s.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.P2SMod;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MemoryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("p2s_memories_v1");
    private static final Path GLOBAL_PATH = ROOT.resolve("global.json");
    private static final Path PROJECTS_DIR = ROOT.resolve("projects");

    private MemoryStore() {
    }

    public record MemoryEntry(String text, long createdAt, long updatedAt) {
    }

    public record MemorySnapshot(List<MemoryEntry> globalEntries, List<MemoryEntry> projectEntries) {
    }

    public static synchronized MemorySnapshot load(String projectId) {
        ensureDirectories();
        return new MemorySnapshot(
                readBucket(GLOBAL_PATH, ""),
                projectId == null || projectId.isBlank()
                        ? List.of()
                        : readBucket(projectPath(projectId), projectId)
        );
    }

    public static synchronized boolean replaceGlobal(List<String> entries) {
        return writeBucket(GLOBAL_PATH, "", entries);
    }

    public static synchronized boolean replaceProject(String projectId, List<String> entries) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        return writeBucket(projectPath(projectId), projectId, entries);
    }

    private static List<MemoryEntry> readBucket(Path path, String expectedProjectId) {
        List<MemoryEntry> entries = new ArrayList<>();
        if (path == null || !Files.exists(path)) {
            return entries;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (expectedProjectId != null && !expectedProjectId.isBlank()) {
                String actualProjectId = getString(root, "projectId");
                if (!expectedProjectId.equals(actualProjectId)) {
                    return entries;
                }
            }
            if (!root.has("entries") || !root.get("entries").isJsonArray()) {
                return entries;
            }
            for (JsonElement element : root.getAsJsonArray("entries")) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                String text = normalizeText(getString(obj, "text"));
                if (text.isBlank()) {
                    continue;
                }
                entries.add(new MemoryEntry(
                        text,
                        getLong(obj, "createdAt"),
                        getLong(obj, "updatedAt")
                ));
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed reading memory store {}: {}", path, e.getMessage());
        }
        return entries;
    }

    private static boolean writeBucket(Path path, String projectId, List<String> entries) {
        if (path == null) {
            return false;
        }
        ensureDirectories();
        List<String> normalized = normalizeEntries(entries);
        if (normalized.isEmpty()) {
            try {
                Files.deleteIfExists(path);
                return true;
            } catch (Exception e) {
                P2SMod.LOGGER.warn("Failed deleting empty memory store {}: {}", path, e.getMessage());
                return false;
            }
        }

        List<MemoryEntry> existing = readBucket(path, projectId);
        Map<String, MemoryEntry> existingByKey = new LinkedHashMap<>();
        for (MemoryEntry entry : existing) {
            if (entry == null || entry.text() == null || entry.text().isBlank()) {
                continue;
            }
            existingByKey.put(canonicalKey(entry.text()), entry);
        }

        long now = System.currentTimeMillis();
        JsonObject root = new JsonObject();
        root.addProperty("updatedAt", now);
        if (projectId != null && !projectId.isBlank()) {
            root.addProperty("projectId", projectId);
        }

        JsonArray array = new JsonArray();
        for (String text : normalized) {
            MemoryEntry previous = existingByKey.get(canonicalKey(text));
            JsonObject entry = new JsonObject();
            entry.addProperty("text", text);
            entry.addProperty("createdAt", previous == null || previous.createdAt() <= 0 ? now : previous.createdAt());
            entry.addProperty("updatedAt", now);
            array.add(entry);
        }
        root.add("entries", array);

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, GSON.toJson(root));
            return true;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed writing memory store {}: {}", path, e.getMessage());
            return false;
        }
    }

    private static void ensureDirectories() {
        try {
            Files.createDirectories(PROJECTS_DIR);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed creating memory directories: {}", e.getMessage());
        }
    }

    private static Path projectPath(String projectId) {
        return PROJECTS_DIR.resolve(projectFileName(projectId));
    }

    private static String projectFileName(String projectId) {
        String normalized = projectId == null ? "" : projectId.trim();
        String slug = normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        slug = slug.replaceAll("_{2,}", "_");
        slug = slug.replaceAll("^_+", "");
        slug = slug.replaceAll("_+$", "");
        if (slug.isBlank()) {
            slug = "project";
        }
        if (slug.length() > 48) {
            slug = slug.substring(0, 48);
        }
        return slug + "-" + Integer.toHexString(normalized.hashCode()) + ".json";
    }

    private static List<String> normalizeEntries(List<String> entries) {
        List<String> normalized = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        if (entries != null) {
            for (String entry : entries) {
                String text = normalizeText(entry);
                if (text.isBlank()) {
                    continue;
                }
                String key = canonicalKey(text);
                if (!seen.containsKey(key)) {
                    seen.put(key, text);
                }
            }
        }
        normalized.addAll(seen.values());
        return normalized;
    }

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        normalized = normalized.replaceAll("[\\t\\n ]+", " ");
        normalized = normalized.replaceAll("^[\\-•*\\d.()\\s]+", "");
        return normalized.trim();
    }

    private static String canonicalKey(String text) {
        return normalizeText(text).toLowerCase(Locale.ROOT);
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long getLong(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return 0L;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }
}
