package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BlockStructureLibrary {
    private static final Gson GSON = new Gson();
    private static final String INDEX_RESOURCE = "/p2s_block_structure_library/index.json";
    private static volatile Library library;

    private BlockStructureLibrary() {
    }

    public static ResolvedStructureFamily resolve(String blockId, Map<String, String> properties) {
        Library current = ensureLoaded();
        for (StructureFamilyDefinition definition : current.definitions()) {
            if (definition.matches(blockId, properties)) {
                return new ResolvedStructureFamily(
                        definition.id(),
                        definition.unitKind(),
                        definition.grouping().mode(),
                        List.copyOf(definition.tags())
                );
            }
        }
        return null;
    }

    public static StructureFamilyDefinition definition(String familyId) {
        if (familyId == null || familyId.isBlank()) {
            return null;
        }
        return ensureLoaded().definitionsById().get(normalize(familyId));
    }

    private static Library ensureLoaded() {
        Library current = library;
        if (current != null) {
            return current;
        }
        synchronized (BlockStructureLibrary.class) {
            current = library;
            if (current == null) {
                current = loadLibrary();
                library = current;
            }
            return current;
        }
    }

    private static Library loadLibrary() {
        JsonArray index = readJsonArray(INDEX_RESOURCE);
        List<StructureFamilyDefinition> definitions = new ArrayList<>();
        Map<String, StructureFamilyDefinition> definitionsById = new LinkedHashMap<>();
        for (JsonElement element : index) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            String resourcePath = element.getAsString();
            if (resourcePath == null || resourcePath.isBlank()) {
                continue;
            }
            RawStructureFamilyDefinition raw = readJson(resourcePath, RawStructureFamilyDefinition.class);
            StructureFamilyDefinition definition = normalize(raw, resourcePath);
            definitions.add(definition);
            definitionsById.put(definition.id(), definition);
        }
        return new Library(List.copyOf(definitions), Map.copyOf(definitionsById));
    }

    private static StructureFamilyDefinition normalize(RawStructureFamilyDefinition raw, String resourcePath) {
        if (raw == null || raw.id == null || raw.id.isBlank()) {
            throw new IllegalStateException("Invalid block structure family resource: " + resourcePath);
        }

        MatchRule match = normalizeMatch(raw.match);
        GroupingRule grouping = normalizeGrouping(raw.grouping);
        List<String> tags = normalizeList(raw.tags);
        String unitKind = normalize(raw.unitKind);
        if (unitKind.isBlank()) {
            unitKind = normalize(raw.id);
        }

        return new StructureFamilyDefinition(
                normalize(raw.id),
                unitKind,
                match,
                grouping,
                tags
        );
    }

    private static MatchRule normalizeMatch(RawMatchRule raw) {
        if (raw == null) {
            throw new IllegalStateException("Block structure family must define a match rule.");
        }
        List<String> exactBlockIds = normalizeList(raw.exactBlockIds);
        List<String> blockSuffixes = normalizeList(raw.blockSuffixes);
        List<String> requiredProperties = normalizeList(raw.requiredProperties);
        Map<String, String> requiredValues = normalizeMap(raw.requiredValues);
        if (exactBlockIds.isEmpty() && blockSuffixes.isEmpty()) {
            throw new IllegalStateException("Block structure family must define exact_block_ids or block_suffixes.");
        }
        return new MatchRule(exactBlockIds, blockSuffixes, requiredProperties, requiredValues);
    }

    private static GroupingRule normalizeGrouping(RawGroupingRule raw) {
        if (raw == null) {
            return new GroupingRule(GroupingMode.SINGLE, "", List.of(), List.of(), "");
        }
        GroupingMode mode = GroupingMode.from(raw.mode);
        String pairProperty = normalize(raw.pairProperty);
        List<String> anchorValues = normalizeList(raw.anchorValues);
        List<String> counterpartValues = normalizeList(raw.counterpartValues);
        String directionProperty = normalize(raw.directionProperty);

        if (mode == GroupingMode.VERTICAL_PAIR || mode == GroupingMode.FACING_PAIR) {
            if (pairProperty.isBlank() || anchorValues.isEmpty() || counterpartValues.isEmpty()) {
                throw new IllegalStateException("Grouped block family must define pair_property, anchor_values, and counterpart_values.");
            }
        }
        if (mode == GroupingMode.FACING_PAIR && directionProperty.isBlank()) {
            throw new IllegalStateException("Facing-pair block family must define direction_property.");
        }
        return new GroupingRule(mode, pairProperty, anchorValues, counterpartValues, directionProperty);
    }

    private static JsonArray readJsonArray(String resourcePath) {
        JsonElement root = readJsonElement(resourcePath);
        if (!root.isJsonArray()) {
            throw new IllegalStateException("Expected JSON array in " + resourcePath);
        }
        return root.getAsJsonArray();
    }

    private static <T> T readJson(String resourcePath, Class<T> type) {
        try (InputStream stream = BlockStructureLibrary.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + resourcePath);
            }
            return GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading resource " + resourcePath + ": " + e.getMessage(), e);
        }
    }

    private static JsonElement readJsonElement(String resourcePath) {
        try (InputStream stream = BlockStructureLibrary.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource " + resourcePath);
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading resource " + resourcePath + ": " + e.getMessage(), e);
        }
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalize(value);
            if (!item.isBlank()) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    private static Map<String, String> normalizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalize(entry.getKey());
            String value = normalize(entry.getValue());
            if (!key.isBlank() && !value.isBlank()) {
                normalized.put(key, value);
            }
        }
        return Map.copyOf(normalized);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record Library(
            List<StructureFamilyDefinition> definitions,
            Map<String, StructureFamilyDefinition> definitionsById
    ) {
    }

    public record ResolvedStructureFamily(
            String familyId,
            String unitKind,
            GroupingMode groupingMode,
            List<String> tags
    ) {
    }

    public record StructureFamilyDefinition(
            String id,
            String unitKind,
            MatchRule match,
            GroupingRule grouping,
            List<String> tags
    ) {
        public boolean matches(String blockId, Map<String, String> properties) {
            return match.matches(blockId, properties);
        }
    }

    public record MatchRule(
            List<String> exactBlockIds,
            List<String> blockSuffixes,
            List<String> requiredProperties,
            Map<String, String> requiredValues
    ) {
        public boolean matches(String blockId, Map<String, String> properties) {
            String normalizedBlockId = normalize(blockId);
            if (normalizedBlockId.isBlank()) {
                return false;
            }

            boolean blockMatch = false;
            for (String exactBlockId : exactBlockIds) {
                if (normalizedBlockId.equals(exactBlockId)) {
                    blockMatch = true;
                    break;
                }
            }
            if (!blockMatch) {
                for (String suffix : blockSuffixes) {
                    if (normalizedBlockId.endsWith(suffix)) {
                        blockMatch = true;
                        break;
                    }
                }
            }
            if (!blockMatch) {
                return false;
            }

            for (String propertyName : requiredProperties) {
                if (properties == null || !properties.containsKey(propertyName)) {
                    return false;
                }
            }
            for (Map.Entry<String, String> entry : requiredValues.entrySet()) {
                String actual = properties == null ? "" : normalize(properties.get(entry.getKey()));
                if (!entry.getValue().equals(actual)) {
                    return false;
                }
            }
            return true;
        }
    }

    public record GroupingRule(
            GroupingMode mode,
            String pairProperty,
            List<String> anchorValues,
            List<String> counterpartValues,
            String directionProperty
    ) {
        public boolean isAnchorValue(String value) {
            return anchorValues.contains(normalize(value));
        }

        public boolean isCounterpartValue(String value) {
            return counterpartValues.contains(normalize(value));
        }
    }

    public enum GroupingMode {
        SINGLE,
        VERTICAL_PAIR,
        FACING_PAIR;

        public static GroupingMode from(String raw) {
            String normalized = normalize(raw);
            return switch (normalized) {
                case "", "single" -> SINGLE;
                case "vertical_pair" -> VERTICAL_PAIR;
                case "facing_pair" -> FACING_PAIR;
                default -> throw new IllegalStateException("Unsupported grouping mode: " + raw);
            };
        }
    }

    private static final class RawStructureFamilyDefinition {
        String id;
        String unitKind;
        RawMatchRule match;
        RawGroupingRule grouping;
        List<String> tags;
    }

    private static final class RawMatchRule {
        List<String> exactBlockIds;
        List<String> blockSuffixes;
        List<String> requiredProperties;
        Map<String, String> requiredValues;
    }

    private static final class RawGroupingRule {
        String mode;
        String pairProperty;
        List<String> anchorValues;
        List<String> counterpartValues;
        String directionProperty;
    }
}
