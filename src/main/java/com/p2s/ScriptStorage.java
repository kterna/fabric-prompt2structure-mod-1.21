package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ScriptStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("p2s_storage");
    private static final DateTimeFormatter NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private ScriptStorage() {
    }

    public static synchronized String save(String prompt, StructureBuilder.VbsScript script, String fullMessage, String suggestedName) {
        StructureBuilder.VbsScript scriptToSave = script == null ? new StructureBuilder.VbsScript() : script;
        return saveInternal(prompt, scriptToSave, fullMessage, suggestedName);
    }

    public static synchronized String saveV2(String prompt, StructureBuilder.VbsScriptV2 script, String fullMessage, String suggestedName) {
        StructureBuilder.VbsScriptV2 scriptToSave = script == null ? new StructureBuilder.VbsScriptV2() : script;
        return saveInternal(prompt, scriptToSave, fullMessage, suggestedName);
    }

    private static String saveInternal(String prompt, Object script, String fullMessage, String suggestedName) {
        ensureDir();
        String name = sanitizeName(suggestedName != null ? suggestedName : generateName(prompt));
        Path file = ROOT.resolve(name + ".json");
        if (Files.exists(file)) {
            name = name + "_" + System.currentTimeMillis();
            file = ROOT.resolve(name + ".json");
        }
        Entry entry = new Entry();
        entry.name = name;
        entry.prompt = prompt;
        entry.content = GSON.toJsonTree(script);
        entry.assistantMessage = fullMessage;
        entry.timestamp = System.currentTimeMillis();
        try {
            Files.writeString(file, GSON.toJson(entry));
            P2SMod.LOGGER.info("Saved script as {} ({})", name, file.toAbsolutePath());
        } catch (IOException e) {
            P2SMod.LOGGER.error("Failed to save script {}: {}", name, e.getMessage());
        }
        return name;
    }

    public static synchronized List<EntryInfo> list(int limit) {
        ensureDir();
        try (Stream<Path> stream = Files.list(ROOT)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(ScriptStorage::readEntry)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong((Entry e) -> e.timestamp).reversed())
                    .limit(limit)
                    .map(EntryInfo::from)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            P2SMod.LOGGER.warn("List storage failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public static synchronized Entry load(String name) {
        ensureDir();
        Path file = ROOT.resolve(name + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return readEntry(file);
    }

    public static synchronized boolean delete(String name) {
        ensureDir();
        Path file = ROOT.resolve(name + ".json");
        if (!Files.exists(file)) {
            return false;
        }
        try {
            Files.delete(file);
            return true;
        } catch (IOException e) {
            P2SMod.LOGGER.warn("Delete {} failed: {}", name, e.getMessage());
            return false;
        }
    }

    private static Entry readEntry(Path path) {
        try {
            String json = Files.readString(path);
            return GSON.fromJson(json, Entry.class);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Read entry {} failed: {}", path.getFileName(), e.getMessage());
            return null;
        }
    }

    private static void ensureDir() {
        if (!Files.exists(ROOT)) {
            try {
                Files.createDirectories(ROOT);
            } catch (IOException e) {
                P2SMod.LOGGER.error("Cannot create storage dir {}: {}", ROOT, e.getMessage());
            }
        }
    }

    private static String sanitizeName(String name) {
        String cleaned = name == null ? "script" : name.replaceAll("[^a-zA-Z0-9-_]", "_");
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        if (cleaned.isBlank()) {
            cleaned = "script";
        }
        return cleaned;
    }

    private static String generateName(String prompt) {
        String time = NAME_FMT.format(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()));
        String tail = prompt == null ? "" : prompt.replaceAll("\\s+", "_");
        if (tail.length() > 20) {
            tail = tail.substring(0, 20);
        }
        return (time + "_" + tail).replaceAll("[^a-zA-Z0-9-_]", "_");
    }

    public static class Entry {
        public String name;
        public String prompt;
        public JsonElement content;
        public String assistantMessage;
        public long timestamp;

        public StructureBuilder.VbsScriptV2 toScriptV2() {
            if (content == null || content.isJsonNull()) {
                return null;
            }
            try {
                if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
                    // legacy stored as stringified JSON
                    StructureBuilder.VbsScript v1 = StructureBuilder.parse(content.getAsString());
                    return StructureBuilder.fromV1(v1);
                }
                if (content.isJsonObject()) {
                    var obj = content.getAsJsonObject();
                    if (obj.has("structures")) {
                        return GSON.fromJson(content, StructureBuilder.VbsScriptV2.class);
                    }
                    if (obj.has("structure")) {
                        StructureBuilder.VbsScript v1 = GSON.fromJson(content, StructureBuilder.VbsScript.class);
                        return StructureBuilder.fromV1(v1);
                    }
                }
                StructureBuilder.VbsScriptV2 v2 = GSON.fromJson(content, StructureBuilder.VbsScriptV2.class);
                if (v2 != null && v2.structures != null) {
                    return v2;
                }
                StructureBuilder.VbsScript v1 = GSON.fromJson(content, StructureBuilder.VbsScript.class);
                return StructureBuilder.fromV1(v1);
            } catch (Exception e) {
                P2SMod.LOGGER.warn("Parse stored script failed for {}: {}", name, e.getMessage());
                return null;
            }
        }

        public StructureBuilder.VbsScript toScript() {
            if (content == null || content.isJsonNull()) {
                return null;
            }
            try {
                if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
                    // legacy stored as stringified JSON
                    return StructureBuilder.parse(content.getAsString());
                }
                if (content.isJsonObject() && content.getAsJsonObject().has("structures")) {
                    StructureBuilder.VbsScriptV2 v2 = GSON.fromJson(content, StructureBuilder.VbsScriptV2.class);
                    return StructureBuilder.toV1(v2);
                }
                return GSON.fromJson(content, StructureBuilder.VbsScript.class);
            } catch (Exception e) {
                P2SMod.LOGGER.warn("Parse stored script failed for {}: {}", name, e.getMessage());
                return null;
            }
        }
    }

    public static class EntryInfo {
        public String name;
        public long timestamp;
        public String prompt;

        public static EntryInfo from(Entry e) {
            EntryInfo i = new EntryInfo();
            i.name = e.name;
            i.timestamp = e.timestamp;
            i.prompt = e.prompt;
            return i;
        }
    }
}
