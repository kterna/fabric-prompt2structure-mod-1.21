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
import java.util.List;

public final class SessionPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir().resolve("p2s_sessions");
    private static final Path INDEX_PATH = BASE_DIR.resolve("index.json");
    private static final Path SESSIONS_DIR = BASE_DIR.resolve("sessions");
    private static final int MAX_SAVED_SESSIONS = 50;

    private SessionPersistence() {
    }

    public record SessionIndexEntry(String id, String title, long createdAt, long updatedAt, int messageCount) {
    }

    public record SavedSession(
            String id,
            String title,
            long createdAt,
            long updatedAt,
            int messageCount,
            List<JsonObject> llmHistory,
            List<ChatMessageEntry> chatLog,
            List<TodoItemEntry> todoItems,
            String todoTitle,
            int originX, int originY, int originZ,
            boolean hasSize,
            int sizeX, int sizeY, int sizeZ,
            String activeDocId,
            String workspaceDocsJson,
            String currentScriptJson
    ) {
    }

    public record ChatMessageEntry(String role, String text) {
    }

    public record TodoItemEntry(String id, String content, String status) {
    }

    public static synchronized void saveSession(SavedSession session) {
        if (session == null || session.id() == null || session.id().isBlank()) {
            return;
        }
        try {
            ensureDirectories();

            JsonObject sessionJson = new JsonObject();
            sessionJson.addProperty("id", session.id());
            sessionJson.addProperty("title", session.title() == null ? "" : session.title());
            sessionJson.addProperty("createdAt", session.createdAt());
            sessionJson.addProperty("updatedAt", session.updatedAt());
            sessionJson.addProperty("messageCount", session.messageCount());
            sessionJson.addProperty("todoTitle", session.todoTitle() == null ? "" : session.todoTitle());

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

            sessionJson.addProperty("originX", session.originX());
            sessionJson.addProperty("originY", session.originY());
            sessionJson.addProperty("originZ", session.originZ());
            sessionJson.addProperty("hasSize", session.hasSize());
            sessionJson.addProperty("sizeX", session.sizeX());
            sessionJson.addProperty("sizeY", session.sizeY());
            sessionJson.addProperty("sizeZ", session.sizeZ());
            sessionJson.addProperty("activeDocId", session.activeDocId() == null ? "" : session.activeDocId());
            sessionJson.addProperty("workspaceDocsJson", session.workspaceDocsJson() == null ? "" : session.workspaceDocsJson());
            sessionJson.addProperty("currentScriptJson", session.currentScriptJson() == null ? "" : session.currentScriptJson());

            Path sessionFile = SESSIONS_DIR.resolve(session.id() + ".json");
            Files.writeString(sessionFile, GSON.toJson(sessionJson));

            updateIndex(session);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed saving session {}: {}", session.id(), e.getMessage());
        }
    }

    public static synchronized List<SessionIndexEntry> listSessions() {
        List<SessionIndexEntry> entries = new ArrayList<>();
        try {
            if (!Files.exists(INDEX_PATH)) {
                return entries;
            }
            String content = Files.readString(INDEX_PATH);
            JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
            for (JsonElement elem : arr) {
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
                        getStr(obj, "title"),
                        getLong(obj, "createdAt"),
                        getLong(obj, "updatedAt"),
                        getInt(obj, "messageCount")
                ));
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed reading session index: {}", e.getMessage());
        }
        return entries;
    }

    public static synchronized SavedSession loadSession(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            Path sessionFile = SESSIONS_DIR.resolve(id + ".json");
            if (!Files.exists(sessionFile)) {
                return null;
            }
            String content = Files.readString(sessionFile);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

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
                    todoItems.add(new TodoItemEntry(
                            getStr(obj, "id"),
                            getStr(obj, "content"),
                            getStr(obj, "status")
                    ));
                }
            }

            return new SavedSession(
                    getStr(root, "id"),
                    getStr(root, "title"),
                    getLong(root, "createdAt"),
                    getLong(root, "updatedAt"),
                    getInt(root, "messageCount"),
                    llmHistory,
                    chatLog,
                    todoItems,
                    getStr(root, "todoTitle"),
                    getInt(root, "originX"),
                    getInt(root, "originY"),
                    getInt(root, "originZ"),
                    getBool(root, "hasSize"),
                    getInt(root, "sizeX"),
                    getInt(root, "sizeY"),
                    getInt(root, "sizeZ"),
                    getStr(root, "activeDocId"),
                    getStr(root, "workspaceDocsJson"),
                    getStr(root, "currentScriptJson")
            );
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed loading session {}: {}", id, e.getMessage());
            return null;
        }
    }

    public static synchronized boolean deleteSession(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            Path sessionFile = SESSIONS_DIR.resolve(id + ".json");
            Files.deleteIfExists(sessionFile);

            List<SessionIndexEntry> entries = listSessions();
            entries.removeIf(e -> id.equals(e.id()));
            writeIndex(entries);
            return true;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed deleting session {}: {}", id, e.getMessage());
            return false;
        }
    }

    private static void updateIndex(SavedSession session) {
        List<SessionIndexEntry> entries = listSessions();

        entries.removeIf(e -> session.id().equals(e.id()));
        entries.add(0, new SessionIndexEntry(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.messageCount()
        ));

        while (entries.size() > MAX_SAVED_SESSIONS) {
            SessionIndexEntry oldest = entries.remove(entries.size() - 1);
            try {
                Path sessionFile = SESSIONS_DIR.resolve(oldest.id() + ".json");
                Files.deleteIfExists(sessionFile);
            } catch (Exception ignored) {
            }
        }

        writeIndex(entries);
    }

    private static void writeIndex(List<SessionIndexEntry> entries) {
        try {
            ensureDirectories();
            JsonArray arr = new JsonArray();
            for (SessionIndexEntry entry : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", entry.id());
                obj.addProperty("title", entry.title());
                obj.addProperty("createdAt", entry.createdAt());
                obj.addProperty("updatedAt", entry.updatedAt());
                obj.addProperty("messageCount", entry.messageCount());
                arr.add(obj);
            }
            Files.writeString(INDEX_PATH, GSON.toJson(arr));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed writing session index: {}", e.getMessage());
        }
    }

    private static void ensureDirectories() throws Exception {
        if (!Files.exists(SESSIONS_DIR)) {
            Files.createDirectories(SESSIONS_DIR);
        }
    }

    private static String getStr(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long getLong(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return 0;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean getBool(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return false;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
