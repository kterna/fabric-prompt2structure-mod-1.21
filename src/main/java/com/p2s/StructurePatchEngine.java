package com.p2s;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class StructurePatchEngine {
    private StructurePatchEngine() {
    }

    public static StructureBuilder.VbsScriptV2 applyPatchToModel(StructureBuilder.VbsScriptV2 base, PatchModels.StructurePatch patch) {
        StructureBuilder.VbsScriptV2 working = copyScript(base);
        if (working == null) {
            working = new StructureBuilder.VbsScriptV2();
        }
        if (working.palette == null) {
            working.palette = new LinkedHashMap<>();
        }
        if (working.structures == null) {
            working.structures = new ArrayList<>();
        }
        if (patch == null || patch.operations == null) {
            return working;
        }

        LinkedHashMap<String, StructureBuilder.StructurePart> parts = new LinkedHashMap<>();
        for (StructureBuilder.StructurePart part : working.structures) {
            if (part == null || part.name == null || part.name.isBlank()) {
                continue;
            }
            parts.put(part.name, copyPart(part));
        }

        for (PatchModels.PatchOperation op : patch.operations) {
            if (op == null || op.op == null || op.op.isBlank()) {
                continue;
            }
            String opName = op.op.trim().toLowerCase();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] PatchEngine operation: op={}, part={}, priority={}", opName, op.part, op.priority);
            }
            switch (opName) {
                case "set_palette" -> applySetPalette(working, op);
                case "delete_part" -> applyDeletePart(parts, op);
                case "upsert_part" -> applyUpsertPart(parts, op);
                case "patch_actions" -> applyPatchActions(parts, op);
                default -> P2SMod.LOGGER.warn("Unknown patch operation: {}", op.op);
            }
        }

        working.structures = new ArrayList<>(parts.values());
        return working;
    }

    public static DiffResult diff(StructureBuilder.VbsScriptV2 before, StructureBuilder.VbsScriptV2 after) {
        Map<Int3, BlockState> beforeMap = materialize(before);
        Map<Int3, BlockState> afterMap = materialize(after);

        Set<Int3> all = new HashSet<>();
        all.addAll(beforeMap.keySet());
        all.addAll(afterMap.keySet());

        List<PatchModels.BlockOp> forward = new ArrayList<>();
        List<PatchModels.BlockOp> inverse = new ArrayList<>();
        for (Int3 key : all) {
            BlockState oldState = beforeMap.get(key);
            BlockState newState = afterMap.get(key);
            if (Objects.equals(oldState, newState)) {
                continue;
            }
            forward.add(new PatchModels.BlockOp(key.x, key.y, key.z, newState == null ? Blocks.AIR.defaultBlockState() : newState));
            inverse.add(new PatchModels.BlockOp(key.x, key.y, key.z, oldState == null ? Blocks.AIR.defaultBlockState() : oldState));
        }

        forward.sort(BLOCK_OP_COMPARATOR);
        inverse.sort(BLOCK_OP_COMPARATOR);

        DiffResult result = new DiffResult();
        result.changedBlocks = forward.size();
        result.beforeBlocks = beforeMap.size();
        result.afterBlocks = afterMap.size();
        result.forwardOps = forward;
        result.inverseOps = inverse;
        return result;
    }

    public static void applyBlockOps(ServerLevel world, BlockPos origin, List<PatchModels.BlockOp> ops) {
        if (world == null || origin == null || ops == null || ops.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (PatchModels.BlockOp op : ops) {
            if (op == null) {
                continue;
            }
            mutable.set(origin.getX() + op.x(), origin.getY() + op.y(), origin.getZ() + op.z());
            world.setBlockAndUpdate(mutable, op.state() == null ? Blocks.AIR.defaultBlockState() : op.state());
        }
    }

    private static void applySetPalette(StructureBuilder.VbsScriptV2 working, PatchModels.PatchOperation op) {
        if (op.paletteDelta == null || op.paletteDelta.isEmpty()) {
            return;
        }
        if (working.palette == null) {
            working.palette = new LinkedHashMap<>();
        }
        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] PatchEngine set_palette delta: {}", op.paletteDelta);
        }
        for (Map.Entry<String, String> entry : op.paletteDelta.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            working.palette.put(entry.getKey().trim(), entry.getValue().trim());
        }
    }

    private static void applyDeletePart(LinkedHashMap<String, StructureBuilder.StructurePart> parts, PatchModels.PatchOperation op) {
        String partName = normalizePartName(op.part);
        if (partName == null) {
            return;
        }
        parts.remove(partName);
    }

    private static void applyUpsertPart(LinkedHashMap<String, StructureBuilder.StructurePart> parts, PatchModels.PatchOperation op) {
        String partName = normalizePartName(op.part);
        if (partName == null) {
            return;
        }
        StructureBuilder.StructurePart existing = parts.get(partName);
        StructureBuilder.StructurePart next = new StructureBuilder.StructurePart();
        next.name = partName;
        if (op.priority != null) {
            next.priority = op.priority;
        } else if (existing != null) {
            next.priority = existing.priority;
        } else {
            next.priority = 10;
        }
        next.actions = copyActions(op.actionsAdd);
        parts.put(partName, next);
    }

    private static void applyPatchActions(LinkedHashMap<String, StructureBuilder.StructurePart> parts, PatchModels.PatchOperation op) {
        String partName = normalizePartName(op.part);
        if (partName == null) {
            return;
        }

        StructureBuilder.StructurePart part = parts.get(partName);
        if (part == null) {
            part = new StructureBuilder.StructurePart();
            part.name = partName;
            part.priority = op.priority == null ? 10 : op.priority;
            part.actions = new ArrayList<>();
        } else {
            part = copyPart(part);
        }

        if (op.priority != null) {
            part.priority = op.priority;
        }

        if (part.actions == null) {
            part.actions = new ArrayList<>();
        }
        if (op.actionsRemoveMatch != null && !op.actionsRemoveMatch.isEmpty()) {
            part.actions.removeIf(action -> matchesAny(action, op.actionsRemoveMatch));
        }
        part.actions.addAll(copyActions(op.actionsAdd));
        parts.put(partName, part);
    }

    private static boolean matchesAny(StructureBuilder.VbsAction action, List<PatchModels.ActionMatch> matches) {
        for (PatchModels.ActionMatch match : matches) {
            if (matchesAction(action, match)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAction(StructureBuilder.VbsAction action, PatchModels.ActionMatch match) {
        if (action == null || match == null) {
            return false;
        }
        if (!equalsIgnoreBlank(action.type, match.type)) {
            return false;
        }
        if (!equalsIgnoreBlank(action.block, match.block)) {
            return false;
        }
        if (!equalsIgnoreBlank(action.facing, match.facing)) {
            return false;
        }
        if (match.from != null && !Objects.equals(action.from, match.from)) {
            return false;
        }
        if (match.to != null && !Objects.equals(action.to, match.to)) {
            return false;
        }
        if (match.at != null && !Objects.equals(action.at, match.at)) {
            return false;
        }
        return true;
    }

    private static boolean equalsIgnoreBlank(String value, String condition) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        return Objects.equals(normalize(condition), normalize(value));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private static String normalizePartName(String partName) {
        if (partName == null || partName.isBlank()) {
            return null;
        }
        return partName.trim();
    }

    private static StructureBuilder.StructurePart copyPart(StructureBuilder.StructurePart part) {
        if (part == null) {
            return null;
        }
        StructureBuilder.StructurePart copy = new StructureBuilder.StructurePart();
        copy.name = part.name;
        copy.priority = part.priority;
        copy.actions = copyActions(part.actions);
        return copy;
    }

    private static List<StructureBuilder.VbsAction> copyActions(List<StructureBuilder.VbsAction> actions) {
        List<StructureBuilder.VbsAction> out = new ArrayList<>();
        if (actions == null) {
            return out;
        }
        for (StructureBuilder.VbsAction action : actions) {
            if (action == null) {
                continue;
            }
            out.add(copyAction(action));
        }
        return out;
    }

    private static StructureBuilder.VbsAction copyAction(StructureBuilder.VbsAction action) {
        StructureBuilder.VbsAction copy = new StructureBuilder.VbsAction();
        copy.type = action.type;
        copy.block = action.block;
        copy.from = action.from == null ? null : new ArrayList<>(action.from);
        copy.to = action.to == null ? null : new ArrayList<>(action.to);
        if (action.at != null) {
            copy.at = new ArrayList<>();
            for (List<Integer> point : action.at) {
                copy.at.add(point == null ? null : new ArrayList<>(point));
            }
        } else {
            copy.at = null;
        }
        copy.facing = action.facing;
        return copy;
    }

    private static StructureBuilder.VbsScriptV2 copyScript(StructureBuilder.VbsScriptV2 source) {
        if (source == null) {
            return null;
        }
        StructureBuilder.VbsScriptV2 copy = new StructureBuilder.VbsScriptV2();
        if (source.palette != null) {
            copy.palette = new LinkedHashMap<>(source.palette);
        } else {
            copy.palette = new LinkedHashMap<>();
        }
        copy.structures = new ArrayList<>();
        if (source.structures != null) {
            for (StructureBuilder.StructurePart part : source.structures) {
                StructureBuilder.StructurePart cloned = copyPart(part);
                if (cloned != null) {
                    copy.structures.add(cloned);
                }
            }
        }
        return copy;
    }

    private static Map<Int3, BlockState> materialize(StructureBuilder.VbsScriptV2 script) {
        Map<Int3, BlockState> map = new HashMap<>();
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return map;
        }

        Map<String, BlockState> palette = StructureBuilder.resolvePaletteStates(script.palette);
        Set<String> missingPaletteKeys = new HashSet<>();
        List<StructureBuilder.StructurePart> sorted = new ArrayList<>(script.structures);
        sorted.sort(Comparator.comparingInt(part -> part == null ? 0 : part.priority));

        for (StructureBuilder.StructurePart part : sorted) {
            if (part == null || part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (action == null || action.type == null) {
                    continue;
                }
                BlockState state = resolveActionState(palette, missingPaletteKeys, action);
                String type = action.type.trim().toLowerCase();
                switch (type) {
                    case "fill" -> writeFill(map, action, state);
                    case "frame" -> writeFrame(map, action, state);
                    case "set" -> writeSet(map, action, state);
                    default -> {
                    }
                }
            }
        }

        return map;
    }

    private static BlockState resolveActionState(Map<String, BlockState> palette, Set<String> missingPaletteKeys, StructureBuilder.VbsAction action) {
        String key = action.block;
        BlockState base = null;
        if (key != null && palette != null) {
            base = palette.get(key);
        }
        if (base == null && key != null && !key.isBlank()) {
            BlockState direct = StructureBuilder.resolveDirectBlockState(key);
            if (direct != null) {
                if (missingPaletteKeys.add(key)) {
                    P2SMod.LOGGER.info("Patch materialize: palette key '{}' missing, used direct block id", key);
                }
                base = direct;
            }
        }
        if (base == null) {
            if (key != null && !key.isBlank() && missingPaletteKeys.add(key)) {
                P2SMod.LOGGER.warn("Patch materialize: palette key '{}' missing, fallback stone", key);
            }
            base = Blocks.STONE.defaultBlockState();
        }
        return StructureBuilder.applyFacingState(base, action.facing);
    }

    public static OverrideStats analyzeOverrides(StructureBuilder.VbsScriptV2 script) {
        OverrideStats stats = new OverrideStats();
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return stats;
        }

        List<StructureBuilder.StructurePart> sorted = new ArrayList<>(script.structures);
        sorted.sort(Comparator.comparingInt(part -> part == null ? 0 : part.priority));
        Map<Int3, BlockSource> written = new HashMap<>();

        for (StructureBuilder.StructurePart part : sorted) {
            if (part == null || part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (action == null || action.type == null) {
                    continue;
                }
                String type = action.type.trim().toLowerCase();
                switch (type) {
                    case "fill" -> analyzeFill(written, stats, part, action);
                    case "frame" -> analyzeFrame(written, stats, part, action);
                    case "set" -> analyzeSet(written, stats, part, action);
                    default -> {
                    }
                }
            }
        }
        return stats;
    }

    private static void writeFill(Map<Int3, BlockState> map, StructureBuilder.VbsAction action, BlockState state) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    map.put(new Int3(x, y, z), state);
                }
            }
        }
    }

    private static void writeFrame(Map<Int3, BlockState> map, StructureBuilder.VbsAction action, BlockState state) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (boundary) {
                        map.put(new Int3(x, y, z), state);
                    }
                }
            }
        }
    }

    private static void writeSet(Map<Int3, BlockState> map, StructureBuilder.VbsAction action, BlockState state) {
        if (action.at == null) {
            return;
        }
        for (List<Integer> point : action.at) {
            int[] c = coords(point);
            if (c == null) {
                continue;
            }
            map.put(new Int3(c[0], c[1], c[2]), state);
        }
    }

    private static void analyzeFill(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part, StructureBuilder.VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    recordWrite(written, stats, part, x, y, z);
                }
            }
        }
    }

    private static void analyzeFrame(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part, StructureBuilder.VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (!boundary) {
                        continue;
                    }
                    recordWrite(written, stats, part, x, y, z);
                }
            }
        }
    }

    private static void analyzeSet(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part, StructureBuilder.VbsAction action) {
        if (action.at == null) {
            return;
        }
        for (List<Integer> point : action.at) {
            int[] c = coords(point);
            if (c == null) {
                continue;
            }
            recordWrite(written, stats, part, c[0], c[1], c[2]);
        }
    }

    private static void recordWrite(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part, int x, int y, int z) {
        if (stats == null) {
            return;
        }
        stats.totalWrites++;
        Int3 key = new Int3(x, y, z);
        BlockSource next = new BlockSource(part == null ? "" : part.name, part == null ? 0 : part.priority);
        BlockSource prev = written.put(key, next);
        if (prev == null) {
            return;
        }
        if (prev.partName.equals(next.partName)) {
            return;
        }
        stats.overriddenBlocks++;
        if (next.priority > prev.priority) {
            stats.overriddenByHigherPriority++;
        } else {
            stats.overriddenBySamePriority++;
        }
        if (!next.partName.isBlank()) {
            stats.overriderCounts.merge(next.partName, 1, Integer::sum);
        }
        if (!prev.partName.isBlank()) {
            stats.overriddenCounts.merge(prev.partName, 1, Integer::sum);
        }
    }

    private static int[] coords(List<Integer> list) {
        if (list == null || list.size() < 3) {
            return null;
        }
        return new int[]{list.get(0), list.get(1), list.get(2)};
    }

    private record Int3(int x, int y, int z) {
    }

    private record BlockSource(String partName, int priority) {
        private BlockSource {
            partName = partName == null ? "" : partName;
        }
    }

    public static final class OverrideStats {
        public int totalWrites = 0;
        public int overriddenBlocks = 0;
        public int overriddenByHigherPriority = 0;
        public int overriddenBySamePriority = 0;
        public final Map<String, Integer> overriderCounts = new HashMap<>();
        public final Map<String, Integer> overriddenCounts = new HashMap<>();
    }

    public static final class DiffResult {
        public int changedBlocks;
        public int beforeBlocks;
        public int afterBlocks;
        public List<PatchModels.BlockOp> forwardOps = new ArrayList<>();
        public List<PatchModels.BlockOp> inverseOps = new ArrayList<>();
    }

    private static final Comparator<PatchModels.BlockOp> BLOCK_OP_COMPARATOR = Comparator
            .comparingInt(PatchModels.BlockOp::x)
            .thenComparingInt(PatchModels.BlockOp::y)
            .thenComparingInt(PatchModels.BlockOp::z);
}
