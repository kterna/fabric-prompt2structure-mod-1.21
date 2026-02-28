package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SkillStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("(?s)^---\\s*\\R(.*?)\\R---\\s*\\R?(.*)$");
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String ACTIVE_FILE_NAME = "active.json";
    // Client-global skill root (shared by this client instance, not split per player UUID).
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("p2s_skills");

    private SkillStore() {
    }

    public static synchronized List<SkillMeta> listSkills() {
        Path root = skillsRoot();
        ensureDir(root);
        List<SkillMeta> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            for (Path dir : stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .collect(Collectors.toList())) {
                SkillDocument doc = readSkillInternal(dir.getFileName().toString(), false);
                if (doc != null) {
                    result.add(doc.meta());
                }
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed listing skills in {}: {}", root, e.getMessage());
        }
        result.sort(Comparator.comparing(SkillMeta::id));
        return result;
    }

    public static synchronized SkillDocument readSkill(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return readSkillInternal(id.trim(), true);
    }

    public static synchronized SkillDocument readActiveSkill() {
        String active = activeSkillId();
        if (active == null || active.isBlank()) {
            return null;
        }
        return readSkill(active);
    }

    public static synchronized String activeSkillId() {
        ActiveIndex idx = readActiveIndex();
        if (idx.activeSkillId == null || idx.activeSkillId.isBlank()) {
            return "";
        }
        if (!existsSkill(idx.activeSkillId)) {
            return "";
        }
        return idx.activeSkillId;
    }

    public static synchronized boolean setActiveSkill(String id) {
        if (id == null || id.isBlank() || !existsSkill(id)) {
            return false;
        }
        ActiveIndex idx = new ActiveIndex();
        idx.activeSkillId = id;
        idx.updatedAt = Instant.now().toEpochMilli();
        writeActiveIndex(idx);
        return true;
    }

    public static synchronized SkillDocument createSkill(String name, String description, String body) {
        String slug = uniqueSlug(slugify(name == null ? "" : name), "");
        if (slug.isBlank()) {
            slug = uniqueSlug("skill", "");
        }

        String finalName = normalizeLabel(name, slug);
        String finalDesc = normalizeLabel(description, "Player custom skill");
        String finalBody = body == null ? "" : body;

        Path dir = skillDir(slug);
        ensureDir(dir);
        writeSkillFile(dir.resolve(SKILL_FILE_NAME), finalName, finalDesc, finalBody);

        String active = activeSkillId();
        if (active.isBlank()) {
            setActiveSkill(slug);
        }
        return readSkill(slug);
    }

    public static synchronized SkillDocument updateSkill(String id, String name, String description, String body) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String skillId = id.trim();
        if (!existsSkill(skillId)) {
            return null;
        }
        String finalName = normalizeLabel(name, skillId);
        String finalDesc = normalizeLabel(description, "Player custom skill");
        String finalBody = body == null ? "" : body;
        Path file = skillDir(skillId).resolve(SKILL_FILE_NAME);
        writeSkillFile(file, finalName, finalDesc, finalBody);
        return readSkill(skillId);
    }

    public static synchronized SkillDocument renameSkill(String id, String newName) {
        if (id == null || id.isBlank() || !existsSkill(id)) {
            return null;
        }
        String activeBefore = activeSkillId();
        SkillDocument existing = readSkill(id);
        if (existing == null) {
            return null;
        }
        String targetSlugBase = slugify(newName == null ? "" : newName);
        String targetSlug = uniqueSlug(targetSlugBase, id);
        if (targetSlug.isBlank()) {
            targetSlug = uniqueSlug("skill", id);
        }
        if (targetSlug.equals(id)) {
            return updateSkill(id, newName, existing.meta().description(), existing.body());
        }

        Path from = skillDir(id);
        Path to = skillDir(targetSlug);
        ensureDir(skillsRoot());
        try {
            Files.move(from, to);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed renaming skill {} -> {}: {}", id, targetSlug, e.getMessage());
            return null;
        }

        if (id.equals(activeBefore)) {
            setActiveSkill(targetSlug);
        }
        updateSkill(targetSlug, newName, existing.meta().description(), existing.body());
        return readSkill(targetSlug);
    }

    public static synchronized boolean deleteSkill(String id) {
        if (id == null || id.isBlank() || !existsSkill(id)) {
            return false;
        }
        String activeBefore = activeSkillId();
        Path dir = skillDir(id);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            P2SMod.LOGGER.warn("Failed deleting skill {}: {}", id, cause.getMessage());
            return false;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed deleting skill {}: {}", id, e.getMessage());
            return false;
        }

        if (id.equals(activeBefore)) {
            List<SkillMeta> skills = listSkills();
            if (skills.isEmpty()) {
                ActiveIndex idx = new ActiveIndex();
                idx.activeSkillId = "";
                idx.updatedAt = Instant.now().toEpochMilli();
                writeActiveIndex(idx);
            } else {
                setActiveSkill(skills.get(0).id());
            }
        }
        return true;
    }

    public static synchronized List<SearchHit> searchSkill(String id, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return List.of();
        }
        int max = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 50));

        List<SkillDocument> docs = new ArrayList<>();
        if (id != null && !id.isBlank()) {
            SkillDocument doc = readSkill(id);
            if (doc != null) {
                docs.add(doc);
            }
        } else {
            String active = activeSkillId();
            if (!active.isBlank()) {
                SkillDocument doc = readSkill(active);
                if (doc != null) {
                    docs.add(doc);
                }
            }
            if (docs.isEmpty()) {
                for (SkillMeta meta : listSkills()) {
                    SkillDocument doc = readSkill(meta.id());
                    if (doc != null) {
                        docs.add(doc);
                    }
                }
            }
        }

        List<SearchHit> hits = new ArrayList<>();
        for (SkillDocument doc : docs) {
            String[] lines = doc.body().split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i] == null ? "" : lines[i];
                if (line.toLowerCase(Locale.ROOT).contains(q)) {
                    hits.add(new SearchHit(doc.meta().id(), i + 1, line.trim()));
                    if (hits.size() >= max) {
                        return hits;
                    }
                }
            }
            String metaText = (doc.meta().name() + " " + doc.meta().description()).toLowerCase(Locale.ROOT);
            if (metaText.contains(q) && hits.size() < max) {
                hits.add(new SearchHit(doc.meta().id(), 0, doc.meta().name() + " | " + doc.meta().description()));
            }
            if (hits.size() >= max) {
                return hits;
            }
        }
        return hits;
    }

    private static SkillDocument readSkillInternal(String id, boolean strict) {
        Path file = skillDir(id).resolve(SKILL_FILE_NAME);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String text = Files.readString(file);
            long updatedAt = Files.getLastModifiedTime(file).toMillis();
            return parseSkillMarkdown(id, text, updatedAt);
        } catch (Exception e) {
            if (strict) {
                P2SMod.LOGGER.warn("Failed reading skill {}: {}", id, e.getMessage());
            }
            return null;
        }
    }

    private static SkillDocument parseSkillMarkdown(String id, String markdown, long updatedAt) {
        String text = markdown == null ? "" : markdown;
        String name = id;
        String description = "";
        String body = text;

        Matcher matcher = FRONTMATTER_PATTERN.matcher(text);
        if (matcher.matches()) {
            String front = matcher.group(1) == null ? "" : matcher.group(1);
            body = matcher.group(2) == null ? "" : matcher.group(2);
            String[] lines = front.split("\\R");
            for (String line : lines) {
                if (line == null || line.isBlank() || !line.contains(":")) {
                    continue;
                }
                int idx = line.indexOf(':');
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                value = unquoteYaml(value);
                if ("name".equals(key)) {
                    name = value;
                } else if ("description".equals(key)) {
                    description = value;
                }
            }
        }

        SkillMeta meta = new SkillMeta(id, normalizeLabel(name, id), normalizeLabel(description, ""), updatedAt);
        return new SkillDocument(meta, body);
    }

    private static void writeSkillFile(Path file, String name, String description, String body) {
        try {
            ensureDir(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(quoteYaml(name)).append("\n");
            sb.append("description: ").append(quoteYaml(description)).append("\n");
            sb.append("---\n\n");
            if (body != null) {
                sb.append(body);
            }
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            Files.writeString(file, sb.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Failed writing skill file: " + e.getMessage(), e);
        }
    }

    private static ActiveIndex readActiveIndex() {
        Path file = skillsRoot().resolve(ACTIVE_FILE_NAME);
        if (!Files.exists(file)) {
            return new ActiveIndex();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            ActiveIndex idx = new ActiveIndex();
            idx.activeSkillId = root.has("activeSkillId") ? root.get("activeSkillId").getAsString() : "";
            idx.updatedAt = root.has("updatedAt") ? root.get("updatedAt").getAsLong() : 0L;
            return idx;
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed reading active skill index: {}", e.getMessage());
            return new ActiveIndex();
        }
    }

    private static void writeActiveIndex(ActiveIndex idx) {
        Path file = skillsRoot().resolve(ACTIVE_FILE_NAME);
        try {
            ensureDir(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("activeSkillId", idx.activeSkillId == null ? "" : idx.activeSkillId);
            root.addProperty("updatedAt", idx.updatedAt);
            Files.writeString(file, GSON.toJson(root));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed writing active skill index: {}", e.getMessage());
        }
    }

    private static String normalizeLabel(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value.trim();
    }

    private static String quoteYaml(String value) {
        String v = value == null ? "" : value;
        v = v.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + v + "\"";
    }

    private static String unquoteYaml(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            String inner = trimmed.substring(1, trimmed.length() - 1);
            inner = inner.replace("\\\"", "\"").replace("\\\\", "\\");
            return inner;
        }
        return trimmed;
    }

    private static String slugify(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^a-z0-9]+", "-");
        value = value.replaceAll("-{2,}", "-");
        value = value.replaceAll("^-+", "");
        value = value.replaceAll("-+$", "");
        return value;
    }

    private static String uniqueSlug(String base, String keepId) {
        String normalized = base == null || base.isBlank() ? "skill" : base;
        String candidate = normalized;
        int suffix = 2;
        while (true) {
            if (candidate.equals(keepId) || !existsSkill(candidate)) {
                return candidate;
            }
            candidate = normalized + "-" + suffix;
            suffix += 1;
        }
    }

    private static boolean existsSkill(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return Files.exists(skillDir(id).resolve(SKILL_FILE_NAME));
    }

    private static Path skillDir(String id) {
        return skillsRoot().resolve(id);
    }

    private static Path skillsRoot() {
        return ROOT;
    }

    private static void ensureDir(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create directory " + dir + ": " + e.getMessage(), e);
        }
    }

    private static final class ActiveIndex {
        String activeSkillId = "";
        long updatedAt = 0L;
    }

    public record SkillMeta(String id, String name, String description, long updatedAt) {
    }

    public record SkillDocument(SkillMeta meta, String body) {
    }

    public record SearchHit(String skillId, int line, String text) {
    }
}
