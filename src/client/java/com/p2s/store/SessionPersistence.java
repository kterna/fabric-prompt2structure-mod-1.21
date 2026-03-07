package com.p2s.store;

import com.p2s.P2SMod;
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
import java.util.List;

public final class SessionPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir().resolve("p2s_sessions_v2");
    private static final Path INDEX_PATH = BASE_DIR.resolve("index.json");
    private static final Path SESSIONS_DIR = BASE_DIR.resolve("sessions");
    private static final Path LEGACY_DIR = FabricLoader.getInstance().getConfigDir().resolve("p2s_sessions");
    private static final int MAX_SAVED_SESSIONS = 100;
    private static boolean legacyWarned = false;

    private SessionPersistence() {
    }

    public record SessionIndexEntry(String id, String projectId, String title, long createdAt, long updatedAt, int messageCount) {
    }

    public record SavedSession(
            String id,
            String projectId,
            String title,
            long createdAt,
            long updatedAt,
            int messageCount,
            List<JsonObject> llmHistory,
            List<ChatMessageEntry> chatLog,
            List<TodoItemEntry> todoItems,
            String todoTitle,
            String selectedWorkspacePath
    ) {
    }

    public record ChatMessageEntry(String role, String text) {
    }

    public record TodoItemEntry(String id, String content, String status) {
    }

    public static synchronized void saveSession(SavedSession session) {
        warnIfLegacyDataExists();
        if (session == null || session.id() == null || session.id().isBlank()) {
            return;
        }
        try {
            ensureDirectories();
            JsonObject sessionJson = new JsonObject();
            sessionJson.addProperty("id", session.id());
            sessionJson.addProperty("projectId", session.projectId() == null ? "" : session.projectId());
            sessionJson.addProperty("title", session.title() == null ? "" : session.title());
            sessionJson.addProperty("createdAt", session.createdAt());
            sessionJson.addProperty("updatedAt", session.updatedAt());
            sessionJson.addProperty("messageCount", session.messageCount());
            sessionJson.addProperty("todoTitle", session.todoTitle() == null ? "" : session.todoTitle());
            sessionJson.addProperty("selectedWorkspacePath", session.selectedWorkspacePath() == null ? "" : session.selectedWorkspacePath());

            JsonArray historyArray = new JsonArray();
            if (session.llmHistory() != null) {
                for (JsonObject msg : session.llmHistory()) {
                    historyArray.add(msg == null ? new JsonObject() : msg.deepCopy());
                }
            }
            sessionJson.add("llmHistory", historyArray);

            JsonArray chatArray = new JsonArray();
            if (session.chatLog() != null) {
                for (ChatMessageEntry entry : session.chatLog()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("role", entry.role() == null ? "" : entry.role());
                    obj.addProperty("text", entry.text() == null ? "" : entry.text());
                    chatArray.add(obj);
                }
            }
            sessionJson.add("chatLog", chatArray);

            JsonArray todoArray = new JsonArray();
            if (session.todoItems() != null) {
                for (TodoItemEntry item : session.todoItems()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", item.id() == null ? "" : item.id());
                    obj.addProperty("content", item.content() == null ? "" : item.content());
                    obj.addProperty("status", item.status() == null ? "pending" : item.status());
                    todoArray.add(obj);
                }
            }
            sessionJson.add("todoItems", todoArray);

            Files.writeString(SESSIONS_DIR.resolve(session.id() + ".json"), GSON.toJson(sessionJson));
            updateIndex(session);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed saving session {}: {}", session.id(), e.getMessage());
        }
    }

    public static synchronized List<SessionIndexEntry> listSessions() {
        warnIfLegacyDataExists();
        ensureDirectories();
        List<SessionIndexEntry> entries = new ArrayList<>();
        try {
            if (!Files.exists(INDEX_PATH)) {
                return entries;
            }
            JsonElement root = JsonParser.parseString(Files.readString(INDEX_PATH));
            if (!root.isJsonArray()) {
                return entries;
            }
            for (JsonElement elem : root.getAsJsonArray()) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject obj = elem.getAsJsonObject();
                String id = getStr(obj, "id");
                if (id.isBlank()) {
                    continue;
                }
                entries.add(new SessionIndexEntry(
                        id,
                        getStr(obj, "projectId"),
                        getStr(obj, "title"),
                        getLong(obj, "createdAt"),
                        getLong(obj, "updatedAt"),
                        getInt(obj, "messageCount")
                ));
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed reading session index: {}", e.getMessage());
        }
        entries.sort(Comparator.comparingLong(SessionIndexEntry::updatedAt).reversed());
        return entries;
    }

    public static synchronized List<SessionIndexEntry> listSessions(String projectId) {
        String normalizedProjectId = projectId == null ? "" : projectId.trim();
        if (normalizedProjectId.isBlank()) {
            return List.of();
        }
        List<SessionIndexEntry> filtered = new ArrayList<>();
        for (SessionIndexEntry entry : listSessions()) {
            if (entry != null && normalizedProjectId.equals(entry.projectId())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    public static synchronized SavedSession loadSession(String id) {
        warnIfLegacyDataExists();
        ensureDirectories();
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            Path sessionFile = SESSIONS_DIR.resolve(id + ".json");
            if (!Files.exists(sessionFile)) {
                return null;
            }
            JsonObject root = JsonParser.parseString(Files.readString(sessionFile)).getAsJsonObject();

            List<JsonObject> llmHistory = new ArrayList<>();
            if (root.has("llmHistory") && root.get("llmHistory").isJsonArray()) {
                for (JsonElement elem : root.getAsJsonArray("llmHistory")) {
                    if (elem.isJsonObject()) {
                        llmHistory.add(elem.getAsJsonObject().deepCopy());
                    }
                }
            }

            List<ChatMessageEntry> chatLog = new ArrayList<>();
            if (root.has("chatLog") && root.get("chatLog").isJsonArray()) {
                for (JsonElement elem : root.getAsJsonArray("chatLog")) {
                    if (!elem.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = elem.getAsJsonObject();
                    chatLog.add(new ChatMessageEntry(getStr(obj, "role"), getStr(obj, "text")));
                }
            }

            List<TodoItemEntry> todoItems = new ArrayList<>();
            if (root.has("todoItems") && root.get("todoItems").isJsonArray()) {
                for (JsonElement elem : root.getAsJsonArray("todoItems")) {
                    if (!elem.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = elem.getAsJsonObject();
                    todoItems.add(new TodoItemEntry(getStr(obj, "id"), getStr(obj, "content"), getStr(obj, "status")));
                }
            }

            return new SavedSession(
                    getStr(root, "id"),
                    getStr(root, "projectId"),
                    getStr(root, "title"),
                    getLong(root, "createdAt"),
                    getLong(root, "updatedAt"),
                    getInt(root, "messageCount"),
                    llmHistory,
                    chatLog,
                    todoItems,
                    getStr(root, "todoTitle"),
                    getStr(root, "selectedWorkspacePath")
            );
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed loading session {}: {}", id, e.getMessage());
            return null;
        }
    }

    public static synchronized boolean deleteSession(String id) {
        warnIfLegacyDataExists();
        ensureDirectories();
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            Files.deleteIfExists(SESSIONS_DIR.resolve(id + ".json"));
            List<SessionIndexEntry> entries = listSessions();
            entries.removeIf(entry -> id.equals(entry.id()));
            writeIndex(entries);
            return true;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed deleting session {}: {}", id, e.getMessage());
            return false;
        }
    }

    public static synchronized boolean hasLegacyData() {
        try {
            if (!Files.exists(LEGACY_DIR) || !Files.isDirectory(LEGACY_DIR)) {
                return false;
            }
            try (var entries = Files.list(LEGACY_DIR)) {
                return entries.findAny().isPresent();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized String legacyWarningMessage() {
        return "Legacy P2S session data is not supported by this version. Clear old p2s session data before use.";
    }

    private static void updateIndex(SavedSession session) {
        List<SessionIndexEntry> entries = listSessions();
        entries.removeIf(entry -> session.id().equals(entry.id()));
        entries.add(new SessionIndexEntry(
                session.id(),
                session.projectId() == null ? "" : session.projectId(),
                session.title() == null ? "" : session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.messageCount()
        ));
        while (entries.size() > MAX_SAVED_SESSIONS) {
            SessionIndexEntry oldest = entries.remove(entries.size() - 1);
            try {
                Files.deleteIfExists(SESSIONS_DIR.resolve(oldest.id() + ".json"));
            } catch (Exception ignored) {
            }
        }
        writeIndex(entries);
    }

    private static void writeIndex(List<SessionIndexEntry> entries) {
        try {
            ensureDirectories();
            JsonArray arr = new JsonArray();
            entries.stream()
                    .sorted(Comparator.comparingLong(SessionIndexEntry::updatedAt).reversed())
                    .forEach(entry -> {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("id", entry.id());
                        obj.addProperty("projectId", entry.projectId());
                        obj.addProperty("title", entry.title());
                        obj.addProperty("createdAt", entry.createdAt());
                        obj.addProperty("updatedAt", entry.updatedAt());
                        obj.addProperty("messageCount", entry.messageCount());
                        arr.add(obj);
                    });
            Files.writeString(INDEX_PATH, GSON.toJson(arr));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed writing session index: {}", e.getMessage());
        }
    }

    private static void ensureDirectories() {
        try {
            Files.createDirectories(SESSIONS_DIR);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed creating session directories: {}", e.getMessage());
        }
    }

    private static void warnIfLegacyDataExists() {
        if (legacyWarned || !hasLegacyData()) {
            return;
        }
        legacyWarned = true;
        P2SMod.LOGGER.warn(legacyWarningMessage());
    }

    private static String getStr(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
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
