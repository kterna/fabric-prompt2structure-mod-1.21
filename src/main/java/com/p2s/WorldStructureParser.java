package com.p2s;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorldStructureParser {
    public static final int MAX_DEBUG_VOLUME = 32 * 32 * 32;

    private WorldStructureParser() {
    }

    public static ParseDebugReport parseSelection(ServerLevel world, String playerName, SelectionManager.Selection selection) {
        Objects.requireNonNull(world, "world");
        if (selection == null || !selection.isComplete()) {
            throw new IllegalArgumentException("Selection must be complete.");
        }

        BlockPos min = selection.min();
        BlockPos max = selection.max();
        Vec3i size = selection.size();
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume > MAX_DEBUG_VOLUME) {
            throw new IllegalArgumentException("Selection is too large for debug parse (" + volume + " > " + MAX_DEBUG_VOLUME + ").");
        }

        List<ParsedBlockRecord> rawBlocks = new ArrayList<>();
        Map<BlockPos, ParsedBlockRecord> recordsByWorldPos = new HashMap<>();
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        int airBlocks = 0;

        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos worldPos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(worldPos);
                    if (state.isAir()) {
                        airBlocks++;
                        continue;
                    }

                    String blockId = blockId(state);
                    Map<String, String> properties = extractProperties(state);
                    String blockStateString = formatState(blockId, properties);
                    String blockEntityType = "";
                    String blockEntityNbt = "";
                    BlockEntity blockEntity = world.getBlockEntity(worldPos);
                    if (blockEntity != null) {
                        ResourceLocation blockEntityId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                        blockEntityType = blockEntityId == null ? "" : blockEntityId.toString();
                        blockEntityNbt = extractBlockEntityNbt(blockEntity, world);
                    }

                    List<String> tags = extractTags(blockId, properties, blockEntityType);
                    ParsedBlockRecord record = new ParsedBlockRecord(
                            worldPos,
                            new BlockPos(x - min.getX(), y - min.getY(), z - min.getZ()),
                            blockId,
                            blockStateString,
                            properties,
                            tags,
                            blockEntityType,
                            blockEntityNbt
                    );
                    rawBlocks.add(record);
                    recordsByWorldPos.put(worldPos, record);
                    blockCounts.merge(blockId, 1, Integer::sum);
                }
            }
        }

        rawBlocks.sort(Comparator.comparingInt((ParsedBlockRecord record) -> record.relativePos().getY())
                .thenComparingInt(record -> record.relativePos().getX())
                .thenComparingInt(record -> record.relativePos().getZ()));

        List<ParsedStructureUnit> specialUnits = buildSpecialUnits(rawBlocks, recordsByWorldPos);
        Map<String, Integer> unitCounts = new LinkedHashMap<>();
        for (ParsedStructureUnit unit : specialUnits) {
            unitCounts.merge(unit.kind(), 1, Integer::sum);
        }

        return new ParseDebugReport(
                Instant.now(),
                playerName == null ? "" : playerName,
                world.dimension().location().toString(),
                min,
                max,
                size,
                volume,
                rawBlocks.size(),
                airBlocks,
                rawBlocks,
                specialUnits,
                blockCounts,
                unitCounts
        );
    }

    private static List<ParsedStructureUnit> buildSpecialUnits(List<ParsedBlockRecord> rawBlocks, Map<BlockPos, ParsedBlockRecord> recordsByWorldPos) {
        List<ParsedStructureUnit> units = new ArrayList<>();
        Set<BlockPos> consumed = new HashSet<>();

        for (ParsedBlockRecord record : rawBlocks) {
            if (consumed.contains(record.worldPos())) {
                continue;
            }

            String unitKind = preferredUnitKind(record);
            if (unitKind.isBlank()) {
                continue;
            }

            ParsedStructureUnit unit;
            switch (unitKind) {
                case "door", "double_block_vertical" -> unit = buildVerticalPairUnit(record, recordsByWorldPos, consumed, unitKind);
                case "bed" -> unit = buildBedUnit(record, recordsByWorldPos, consumed);
                default -> unit = buildSingleUnit(record, consumed, unitKind);
            }
            units.add(unit);
        }

        units.sort(Comparator.comparingInt((ParsedStructureUnit unit) -> unit.anchorRelativePos().getY())
                .thenComparingInt(unit -> unit.anchorRelativePos().getX())
                .thenComparingInt(unit -> unit.anchorRelativePos().getZ()));
        return units;
    }

    private static ParsedStructureUnit buildVerticalPairUnit(ParsedBlockRecord record,
                                                             Map<BlockPos, ParsedBlockRecord> recordsByWorldPos,
                                                             Set<BlockPos> consumed,
                                                             String unitKind) {
        String half = normalize(record.properties().get("half"));
        Direction direction = "upper".equals(half) ? Direction.DOWN : Direction.UP;
        BlockPos counterpartPos = record.worldPos().relative(direction);
        ParsedBlockRecord counterpart = recordsByWorldPos.get(counterpartPos);

        ParsedBlockRecord anchor = record;
        List<ParsedBlockRecord> members = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        members.add(record);
        consumed.add(record.worldPos());

        if (counterpart == null) {
            notes.add("Counterpart not found at " + formatPos(counterpartPos) + ".");
        } else if (!record.blockId().equals(counterpart.blockId())) {
            notes.add("Counterpart block mismatch at " + formatPos(counterpartPos) + ": expected " + record.blockId() + ", found " + counterpart.blockId() + ".");
        } else {
            members.add(counterpart);
            consumed.add(counterpart.worldPos());
            if ("upper".equals(half)) {
                anchor = counterpart;
            }
        }

        members.sort(Comparator.comparingInt((ParsedBlockRecord entry) -> entry.relativePos().getY())
                .thenComparingInt(entry -> entry.relativePos().getX())
                .thenComparingInt(entry -> entry.relativePos().getZ()));

        return new ParsedStructureUnit(
                unitKind,
                anchor.worldPos(),
                anchor.relativePos(),
                anchor.blockId(),
                members,
                anchor.properties(),
                notes
        );
    }

    private static ParsedStructureUnit buildBedUnit(ParsedBlockRecord record,
                                                    Map<BlockPos, ParsedBlockRecord> recordsByWorldPos,
                                                    Set<BlockPos> consumed) {
        String part = normalize(record.properties().get("part"));
        Direction facing = directionByName(record.properties().get("facing"));
        BlockPos counterpartPos = null;
        if (facing != null) {
            counterpartPos = "head".equals(part)
                    ? record.worldPos().relative(facing.getOpposite())
                    : record.worldPos().relative(facing);
        }

        ParsedBlockRecord anchor = record;
        List<ParsedBlockRecord> members = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        members.add(record);
        consumed.add(record.worldPos());

        ParsedBlockRecord counterpart = counterpartPos == null ? null : recordsByWorldPos.get(counterpartPos);
        if (facing == null) {
            notes.add("Missing facing property for bed counterpart resolution.");
        } else if (counterpart == null) {
            notes.add("Counterpart not found at " + formatPos(counterpartPos) + ".");
        } else if (!record.blockId().equals(counterpart.blockId())) {
            notes.add("Counterpart block mismatch at " + formatPos(counterpartPos) + ": expected " + record.blockId() + ", found " + counterpart.blockId() + ".");
        } else {
            members.add(counterpart);
            consumed.add(counterpart.worldPos());
            if ("head".equals(part)) {
                anchor = counterpart;
            }
        }

        members.sort(Comparator.comparingInt((ParsedBlockRecord entry) -> entry.relativePos().getY())
                .thenComparingInt(entry -> entry.relativePos().getX())
                .thenComparingInt(entry -> entry.relativePos().getZ()));

        return new ParsedStructureUnit(
                "bed",
                anchor.worldPos(),
                anchor.relativePos(),
                anchor.blockId(),
                members,
                anchor.properties(),
                notes
        );
    }

    private static ParsedStructureUnit buildSingleUnit(ParsedBlockRecord record, Set<BlockPos> consumed, String unitKind) {
        consumed.add(record.worldPos());
        return new ParsedStructureUnit(
                unitKind,
                record.worldPos(),
                record.relativePos(),
                record.blockId(),
                List.of(record),
                record.properties(),
                List.of()
        );
    }

    private static String preferredUnitKind(ParsedBlockRecord record) {
        if (record.hasTag("door")) {
            return "door";
        }
        if (record.hasTag("bed")) {
            return "bed";
        }
        if (record.hasTag("double_block_vertical")) {
            return "double_block_vertical";
        }
        if (record.hasTag("directional")) {
            return "directional";
        }
        if (record.hasTag("block_entity")) {
            return "block_entity";
        }
        return "";
    }

    private static List<String> extractTags(String blockId, Map<String, String> properties, String blockEntityType) {
        List<String> tags = new ArrayList<>();
        if (blockId.endsWith("_door")) {
            tags.add("door");
        }
        if (blockId.endsWith("_bed")) {
            tags.add("bed");
        }
        String half = normalize(properties.get("half"));
        if ("upper".equals(half) || "lower".equals(half)) {
            tags.add("double_block_vertical");
        }
        if (hasDirectionalProperties(properties)) {
            tags.add("directional");
        }
        if (blockEntityType != null && !blockEntityType.isBlank()) {
            tags.add("block_entity");
        }
        return tags;
    }

    private static boolean hasDirectionalProperties(Map<String, String> properties) {
        return properties.containsKey("facing")
                || properties.containsKey("axis")
                || properties.containsKey("rotation")
                || properties.containsKey("horizontal_axis")
                || properties.containsKey("orientation");
    }

    private static Map<String, String> extractProperties(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        List<Property<?>> sorted = new ArrayList<>(state.getProperties());
        sorted.sort(Comparator.comparing(Property::getName));
        for (Property<?> property : sorted) {
            Comparable<?> value = state.getValue(property);
            properties.put(property.getName(), propertyValueName(property, value));
        }
        return properties;
    }

    private static String formatState(String blockId, Map<String, String> properties) {
        if (properties.isEmpty()) {
            return blockId;
        }
        StringBuilder sb = new StringBuilder(blockId).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        sb.append(']');
        return sb.toString();
    }

    private static String extractBlockEntityNbt(BlockEntity blockEntity, ServerLevel world) {
        if (blockEntity == null) {
            return "";
        }
        Object tag = tryInvokeBlockEntitySave(blockEntity, "saveWithFullMetadata", world.registryAccess());
        if (tag == null) {
            tag = tryInvokeBlockEntitySave(blockEntity, "saveWithoutMetadata", world.registryAccess());
        }
        return tag == null ? "" : tag.toString();
    }

    private static Object tryInvokeBlockEntitySave(BlockEntity blockEntity, String methodName, Object registryAccess) {
        for (Method method : blockEntity.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            try {
                return method.invoke(blockEntity, registryAccess);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // Try another overload before giving up.
            }
        }
        return null;
    }

    private static String blockId(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) {
        return property.getName(value);
    }

    private static Direction directionByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Direction.byName(name.toLowerCase(Locale.ROOT));
    }

    static String formatPos(BlockPos pos) {
        if (pos == null) {
            return "(?, ?, ?)";
        }
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record ParseDebugReport(
            Instant generatedAt,
            String playerName,
            String dimensionId,
            BlockPos selectionMin,
            BlockPos selectionMax,
            Vec3i selectionSize,
            long selectionVolume,
            int nonAirBlocks,
            int airBlocks,
            List<ParsedBlockRecord> rawBlocks,
            List<ParsedStructureUnit> specialUnits,
            Map<String, Integer> blockCounts,
            Map<String, Integer> unitCounts
    ) {
    }

    public record ParsedBlockRecord(
            BlockPos worldPos,
            BlockPos relativePos,
            String blockId,
            String stateString,
            Map<String, String> properties,
            List<String> tags,
            String blockEntityType,
            String blockEntityNbt
    ) {
        public boolean hasTag(String tag) {
            return tags != null && tags.contains(tag);
        }
    }

    public record ParsedStructureUnit(
            String kind,
            BlockPos anchorWorldPos,
            BlockPos anchorRelativePos,
            String blockId,
            List<ParsedBlockRecord> members,
            Map<String, String> anchorProperties,
            List<String> notes
    ) {
    }
}
