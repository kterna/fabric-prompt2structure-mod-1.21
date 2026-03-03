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

    // ── Result wrapper ──────────────────────────────────────────────────

    public static final class PatchApplyResult {
        public final boolean ok;
        public final StructureBuilder.VbsScriptV2 result;
        public final PatchModels.VerificationError error;

        private PatchApplyResult(StructureBuilder.VbsScriptV2 result) {
            this.ok = true;
            this.result = result;
            this.error = null;
        }

        private PatchApplyResult(PatchModels.VerificationError error, StructureBuilder.VbsScriptV2 snapshot) {
            this.ok = false;
            this.result = snapshot;
            this.error = error;
        }

        public static PatchApplyResult success(StructureBuilder.VbsScriptV2 script) {
            return new PatchApplyResult(script);
        }

        public static PatchApplyResult fail(PatchModels.VerificationError error, StructureBuilder.VbsScriptV2 snapshot) {
            return new PatchApplyResult(error, snapshot);
        }
    }

    // ── Main entry point ────────────────────────────────────────────────

    public static PatchApplyResult applyPatchToModel(StructureBuilder.VbsScriptV2 base, PatchModels.StructurePatch patch) {
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
            return PatchApplyResult.success(working);
        }

        LinkedHashMap<String, StructureBuilder.StructurePart> parts = new LinkedHashMap<>();
        for (StructureBuilder.StructurePart part : working.structures) {
            if (part == null || part.name == null || part.name.isBlank()) {
                continue;
            }
            parts.put(part.name, copyPart(part));
        }

        for (int i = 0; i < patch.operations.size(); i++) {
            PatchModels.PatchOperation op = patch.operations.get(i);
            if (op == null || op.op == null || op.op.isBlank()) {
                continue;
            }
            String opName = op.op.trim().toLowerCase();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] PatchEngine operation: op={}, part={}, priority={}", opName, op.part, op.priority);
            }

            PatchModels.VerificationError err = switch (opName) {
                case "insert_part" -> applyInsertPart(parts, op, i);
                case "delete_part" -> applyDeletePart(parts, op, i);
                case "replace_part" -> applyReplacePart(parts, op, i);
                case "insert_actions" -> applyInsertActions(parts, op, i);
                case "delete_actions" -> applyDeleteActions(parts, op, i);
                case "replace_actions" -> applyReplaceActions(parts, op, i);
                case "move_actions" -> applyMoveActions(parts, op, i);
                case "update_palette" -> applyUpdatePalette(working, op, i);
                default -> {
                    P2SMod.LOGGER.warn("Unknown patch operation: {}", op.op);
                    yield null;
                }
            };

            if (err != null) {
                working.structures = new ArrayList<>(parts.values());
                return PatchApplyResult.fail(err, working);
            }
        }

        working.structures = new ArrayList<>(parts.values());
        return PatchApplyResult.success(working);
    }

    // ── Exact matching ──────────────────────────────────────────────────

    static boolean actionsExactEqual(StructureBuilder.VbsAction a, StructureBuilder.VbsAction b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (!Objects.equals(normalize(a.type), normalize(b.type))) return false;
        if (!Objects.equals(normalize(a.block), normalize(b.block))) return false;
        if (!Objects.equals(normalize(a.mode), normalize(b.mode))) return false;
        if (!Objects.equals(normalize(a.axis), normalize(b.axis))) return false;
        if (!Objects.equals(normalize(a.facing), normalize(b.facing))) return false;
        if (!Objects.equals(a.from, b.from)) return false;
        if (!Objects.equals(a.to, b.to)) return false;
        if (!Objects.equals(a.at, b.at)) return false;
        return true;
    }

    /**
     * Ordered full match: old_actions must equal part.actions element-by-element.
     * Returns null on success, or an error message on failure.
     */
    private static String exactOrderedMatch(List<StructureBuilder.VbsAction> oldActions,
                                            List<StructureBuilder.VbsAction> partActions) {
        int expected = oldActions == null ? 0 : oldActions.size();
        int actual = partActions == null ? 0 : partActions.size();
        if (expected != actual) {
            return "Action count mismatch: old_actions has " + expected + " but part has " + actual;
        }
        for (int i = 0; i < expected; i++) {
            if (!actionsExactEqual(oldActions.get(i), partActions.get(i))) {
                return "Action mismatch at index " + i;
            }
        }
        return null;
    }

    /**
     * Subset match: each old_actions[i] must find a unique exact match in partActions.
     * Returns matched indices on success, or null on failure (caller checks).
     */
    private static int[] subsetExactMatch(List<StructureBuilder.VbsAction> oldActions,
                                          List<StructureBuilder.VbsAction> partActions) {
        if (oldActions == null || oldActions.isEmpty()) {
            return new int[0];
        }
        if (partActions == null || partActions.isEmpty()) {
            return null;
        }
        boolean[] used = new boolean[partActions.size()];
        int[] indices = new int[oldActions.size()];

        for (int i = 0; i < oldActions.size(); i++) {
            StructureBuilder.VbsAction needle = oldActions.get(i);
            int found = -1;
            for (int j = 0; j < partActions.size(); j++) {
                if (!used[j] && actionsExactEqual(needle, partActions.get(j))) {
                    found = j;
                    break;
                }
            }
            if (found < 0) {
                return null;
            }
            used[found] = true;
            indices[i] = found;
        }
        return indices;
    }

    private static String describeAction(StructureBuilder.VbsAction a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(a.type == null ? "?" : a.type);
        if (a.mode != null && !a.mode.isBlank()) {
            sb.append(" mode=").append(a.mode);
        }
        if (a.axis != null && !a.axis.isBlank()) {
            sb.append(" axis=").append(a.axis);
        }
        sb.append(" ").append(a.block == null ? "?" : a.block);
        if (a.from != null) sb.append(" ").append(a.from);
        if (a.to != null) sb.append("->").append(a.to);
        if (a.at != null) sb.append(" at ").append(a.at.size()).append(" points");
        return sb.toString();
    }

    // ── Coordinate translation ──────────────────────────────────────────

    private static StructureBuilder.VbsAction translateAction(StructureBuilder.VbsAction action, List<Integer> offset) {
        if (action == null || offset == null || offset.size() < 3) {
            return action == null ? null : copyAction(action);
        }
        StructureBuilder.VbsAction copy = copyAction(action);
        int dx = offset.get(0);
        int dy = offset.get(1);
        int dz = offset.get(2);

        if (copy.from != null && copy.from.size() >= 3) {
            copy.from.set(0, copy.from.get(0) + dx);
            copy.from.set(1, copy.from.get(1) + dy);
            copy.from.set(2, copy.from.get(2) + dz);
        }
        if (copy.to != null && copy.to.size() >= 3) {
            copy.to.set(0, copy.to.get(0) + dx);
            copy.to.set(1, copy.to.get(1) + dy);
            copy.to.set(2, copy.to.get(2) + dz);
        }
        if (copy.at != null) {
            for (List<Integer> point : copy.at) {
                if (point != null && point.size() >= 3) {
                    point.set(0, point.get(0) + dx);
                    point.set(1, point.get(1) + dy);
                    point.set(2, point.get(2) + dz);
                }
            }
        }
        return copy;
    }

    // ── 8 operation handlers ────────────────────────────────────────────

    private static PatchModels.VerificationError applyInsertPart(
            LinkedHashMap<String, StructureBuilder.StructurePart> parts,
            PatchModels.PatchOperation op, int opIndex) {
        String partName = normalizePartName(op.part);
        if (partName == null) return null;

        if (parts.containsKey(partName)) {
            StructureBuilder.StructurePart existing = parts.get(partName);
            List<StructureBuilder.VbsAction> actual = existing.actions == null ? new ArrayList<>() : existing.actions;
            return new PatchModels.VerificationError(opIndex, "insert_part", partName,
                    "Part '" + partName + "' already exists with " + actual.size() + " actions",
                    null, copyActions(actual));
        }

        StructureBuilder.StructurePart next = new StructureBuilder.StructurePart();
        next.name = partName;
        next.priority = op.priority == null ? 10 : op.priority;
        next.actions = copyActions(op.actionsAdd);
        parts.put(partName, next);
        return null;
    }

    private static PatchModels.VerificationError applyDeletePart(
            LinkedHashMap<String, StructureBuilder.StructurePart> parts,
            PatchModels.PatchOperation op, int opIndex) {
        String partName = normalizePartName(op.part);
        if (partName == null) return null;

        StructureBuilder.StructurePart existing = parts.get(partName);
        if (existing == null) {
            return new PatchModels.VerificationError(opIndex, "delete_part", partName,
                    "Part '" + partName + "' does not exist",
                    op.oldActions, new ArrayList<>());
        }

        List<StructureBuilder.VbsAction> actual = existing.actions == null ? new ArrayList<>() : existing.actions;
        String matchErr = exactOrderedMatch(op.oldActions, actual);
        if (matchErr != null) {
            return new PatchModels.VerificationError(opIndex, "delete_part", partName,
                    matchErr, op.oldActions, copyActions(actual));
        }

        parts.remove(partName);
        return null;
    }

    private static PatchModels.VerificationError applyReplacePart(
            LinkedHashMap<String, StructureBuilder.StructurePart> parts,
            PatchModels.PatchOperation op, int opIndex) {
        String partName = normalizePartName(op.part);
        if (partName == null) return null;

        StructureBuilder.StructurePart existing = parts.get(partName);
        if (existing == null) {
            return new PatchModels.VerificationError(opIndex, "replace_part", partName,
                    "Part '" + partName + "' does not exist",
                    op.oldActions, new ArrayList<>());
        }

        List<StructureBuilder.VbsAction> actual = existing.actions == null ? new ArrayList<>() : existing.actions;
        String matchErr = exactOrderedMatch(op.oldActions, actual);
        if (matchErr != null) {
            return new PatchModels.VerificationError(opIndex, "replace_part", partName,
                    matchErr, op.oldActions, copyActions(actual));
        }

        StructureBuilder.StructurePart next = new StructureBuilder.StructurePart();
        next.name = partName;
        next.priority = op.priority != null ? op.priority : existing.priority;
        next.actions = copyActions(op.newActions);
        parts.put(partName, next);
        return null;
    }

    private static PatchModels.VerificationError applyInsertActions(
            LinkedHashMap<String, StructureBuilder.StructurePart> parts,
            PatchModels.PatchOperation op, int opIndex) {
        String partName = normalizePartName(op.part);
        if (partName == null) return null;

        StructureBuilder.StructurePart existing = parts.get(partName);
        if (existing == null) {
            return new PatchModels.VerificationError(opIndex, "insert_actions", partName,
                    "Part '" + partName + "' does not exist (insert_actions does not auto-create parts)",
                    null, new ArrayList<>());
        }

        StructureBuilder.StructurePart part = copyPart(existing);
        if (part.actions == null) {
            part.actions = new ArrayList<>();
        }
        part.actions.addAll(copyActions(op.actionsAdd));
        parts.put(partName, part);
        return null;
    }

    private static PatchModels.VerificationError applyDeleteActions(
            LinkedHashMap<String, StructureBuilder.StructurePart> parts,
            PatchModels.PatchOperation op, int opIndex) {
        String partName = normalizePartName(op.part);
        if (partName == null) return null;

        StructureBuilder.StructurePart existing = parts.get(partName);
        if (existing == null) {
            return new PatchModels.VerificationError(opIndex, "delete_actions", partName,
                    "Part '" + partName + "' does not exist",
                    op.oldActions, new ArrayList<>());
        }

        List<StructureBuilder.VbsAction> actual = existing.actions == null ? new ArrayList<>() : existing.actions;
        int[] matched = subsetExactMatch(op.oldActions, actual);
        if (matched == null) {
            StructureBuilder.VbsAction notFound = findFirstUnmatched(op.oldActions, actual);
            return new PatchModels.VerificationError(opIndex, "delete_actions", partName,
                    "Action not found in part '" + partName + "': " + describeAction(notFound),
                    op.oldActions, copyActions(actual));
        }

        StructureBuilder.StructurePart part = copyPart(existing);
        // Remove matched indices in reverse order to preserve indices
        boolean[] toRemove = new boolean[part.actions.size()];
        for (int idx : matched) {
            toRemove[idx] = true;
        }
        List<StructureBuilder.VbsAction> remaining = new ArrayList<>();
        for (int j = 0; j < part.actions.size(); j++) {
            if (!toRemove[j]) {
                remaining.add(part.actions.get(j));
            }
        }
        part.actions = remaining;
        parts.put(partName, part);
        return null;
    }

    private static PatchModels.VerificationError applyReplaceActions(
            LinkedHashMap<String, StructureBuilder.StructurePart> parts,
            PatchModels.PatchOperation op, int opIndex) {
        String partName = normalizePartName(op.part);
        if (partName == null) return null;

        StructureBuilder.StructurePart existing = parts.get(partName);
        if (existing == null) {
            return new PatchModels.VerificationError(opIndex, "replace_actions", partName,
                    "Part '" + partName + "' does not exist",
                    op.oldActions, new ArrayList<>());
        }

        int oldSize = op.oldActions == null ? 0 : op.oldActions.size();
        int newSize = op.newActions == null ? 0 : op.newActions.size();
        if (oldSize != newSize) {
            return new PatchModels.VerificationError(opIndex, "replace_actions", partName,
                    "old_actions length (" + oldSize + ") must equal new_actions length (" + newSize + ")",
                    op.oldActions, existing.actions == null ? new ArrayList<>() : copyActions(existing.actions));
        }

        List<StructureBuilder.VbsAction> actual = existing.actions == null ? new ArrayList<>() : existing.actions;
        int[] matched = subsetExactMatch(op.oldActions, actual);
        if (matched == null) {
            StructureBuilder.VbsAction notFound = findFirstUnmatched(op.oldActions, actual);
            return new PatchModels.VerificationError(opIndex, "replace_actions", partName,
                    "Action not found in part '" + partName + "': " + describeAction(notFound),
                    op.oldActions, copyActions(actual));
        }

        StructureBuilder.StructurePart part = copyPart(existing);
        // Replace matched actions 1:1
        for (int i = 0; i < matched.length; i++) {
            part.actions.set(matched[i], copyAction(op.newActions.get(i)));
        }
        parts.put(partName, part);
        return null;
    }

    private static PatchModels.VerificationError applyMoveActions(
            LinkedHashMap<String, StructureBuilder.StructurePart> parts,
            PatchModels.PatchOperation op, int opIndex) {
        String partName = normalizePartName(op.part);
        if (partName == null) return null;

        StructureBuilder.StructurePart existing = parts.get(partName);
        if (existing == null) {
            return new PatchModels.VerificationError(opIndex, "move_actions", partName,
                    "Part '" + partName + "' does not exist",
                    op.oldActions, new ArrayList<>());
        }

        List<StructureBuilder.VbsAction> actual = existing.actions == null ? new ArrayList<>() : existing.actions;
        int[] matched = subsetExactMatch(op.oldActions, actual);
        if (matched == null) {
            StructureBuilder.VbsAction notFound = findFirstUnmatched(op.oldActions, actual);
            return new PatchModels.VerificationError(opIndex, "move_actions", partName,
                    "Action not found in part '" + partName + "': " + describeAction(notFound),
                    op.oldActions, copyActions(actual));
        }

        // Translate actions
        List<StructureBuilder.VbsAction> translated = new ArrayList<>();
        for (int i = 0; i < op.oldActions.size(); i++) {
            translated.add(translateAction(op.oldActions.get(i), op.offset));
        }

        String targetPartName = op.targetPart == null || op.targetPart.isBlank() ? null : op.targetPart.trim();

        if (targetPartName == null || targetPartName.equals(partName)) {
            // In-place move: replace matched actions with translated versions
            StructureBuilder.StructurePart part = copyPart(existing);
            for (int i = 0; i < matched.length; i++) {
                part.actions.set(matched[i], translated.get(i));
            }
            parts.put(partName, part);
        } else {
            // Cross-part move: remove from source, add to target
            StructureBuilder.StructurePart sourcePart = copyPart(existing);
            boolean[] toRemove = new boolean[sourcePart.actions.size()];
            for (int idx : matched) {
                toRemove[idx] = true;
            }
            List<StructureBuilder.VbsAction> remaining = new ArrayList<>();
            for (int j = 0; j < sourcePart.actions.size(); j++) {
                if (!toRemove[j]) {
                    remaining.add(sourcePart.actions.get(j));
                }
            }
            sourcePart.actions = remaining;
            parts.put(partName, sourcePart);

            StructureBuilder.StructurePart target = parts.get(targetPartName);
            if (target == null) {
                return new PatchModels.VerificationError(opIndex, "move_actions", partName,
                        "Target part '" + targetPartName + "' does not exist",
                        op.oldActions, copyActions(actual));
            }
            StructureBuilder.StructurePart targetCopy = copyPart(target);
            if (targetCopy.actions == null) {
                targetCopy.actions = new ArrayList<>();
            }
            targetCopy.actions.addAll(translated);
            parts.put(targetPartName, targetCopy);
        }

        return null;
    }

    private static PatchModels.VerificationError applyUpdatePalette(
            StructureBuilder.VbsScriptV2 working,
            PatchModels.PatchOperation op, int opIndex) {
        if (op.entries == null || op.entries.isEmpty()) {
            return null;
        }
        if (working.palette == null) {
            working.palette = new LinkedHashMap<>();
        }

        for (PatchModels.PaletteEntry entry : op.entries) {
            if (entry == null || entry.key == null || entry.key.isBlank()) {
                continue;
            }
            String key = entry.key.trim();
            String currentValue = working.palette.get(key);
            boolean exists = working.palette.containsKey(key);

            if (entry.oldValue == null) {
                // Add new entry: key must not exist
                if (exists) {
                    List<StructureBuilder.VbsAction> hint = new ArrayList<>();
                    return new PatchModels.VerificationError(opIndex, "update_palette", key,
                            "Palette key '" + key + "' already exists with value '" + currentValue
                                    + "' (old_value=null means add-new, but key exists)",
                            null, hint);
                }
                if (entry.newValue != null && !entry.newValue.isBlank()) {
                    working.palette.put(key, entry.newValue.trim());
                }
            } else if (entry.newValue == null) {
                // Delete entry: old_value must match
                if (!exists) {
                    return new PatchModels.VerificationError(opIndex, "update_palette", key,
                            "Palette key '" + key + "' does not exist (cannot delete)",
                            null, new ArrayList<>());
                }
                if (!entry.oldValue.trim().equals(currentValue == null ? "" : currentValue.trim())) {
                    return new PatchModels.VerificationError(opIndex, "update_palette", key,
                            "Palette key '" + key + "' old_value mismatch: expected '"
                                    + entry.oldValue + "' but actual is '" + currentValue + "'",
                            null, new ArrayList<>());
                }
                working.palette.remove(key);
            } else {
                // Modify entry: old_value must match
                if (!exists) {
                    return new PatchModels.VerificationError(opIndex, "update_palette", key,
                            "Palette key '" + key + "' does not exist (cannot modify)",
                            null, new ArrayList<>());
                }
                if (!entry.oldValue.trim().equals(currentValue == null ? "" : currentValue.trim())) {
                    return new PatchModels.VerificationError(opIndex, "update_palette", key,
                            "Palette key '" + key + "' old_value mismatch: expected '"
                                    + entry.oldValue + "' but actual is '" + currentValue + "'",
                            null, new ArrayList<>());
                }
                working.palette.put(key, entry.newValue.trim());
            }
        }
        return null;
    }

    // ── Helper: find first unmatched old action for error reporting ──────

    private static StructureBuilder.VbsAction findFirstUnmatched(
            List<StructureBuilder.VbsAction> oldActions,
            List<StructureBuilder.VbsAction> partActions) {
        if (oldActions == null || oldActions.isEmpty()) return null;
        if (partActions == null || partActions.isEmpty()) return oldActions.get(0);

        boolean[] used = new boolean[partActions.size()];
        for (StructureBuilder.VbsAction needle : oldActions) {
            boolean found = false;
            for (int j = 0; j < partActions.size(); j++) {
                if (!used[j] && actionsExactEqual(needle, partActions.get(j))) {
                    used[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return needle;
            }
        }
        return null;
    }

    // ── Diff / BlockOps (unchanged) ─────────────────────────────────────

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

    // ── Normalize / Copy helpers (preserved) ────────────────────────────

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

    static StructureBuilder.StructurePart copyPart(StructureBuilder.StructurePart part) {
        if (part == null) {
            return null;
        }
        StructureBuilder.StructurePart copy = new StructureBuilder.StructurePart();
        copy.name = part.name;
        copy.priority = part.priority;
        copy.actions = copyActions(part.actions);
        return copy;
    }

    static List<StructureBuilder.VbsAction> copyActions(List<StructureBuilder.VbsAction> actions) {
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

    static StructureBuilder.VbsAction copyAction(StructureBuilder.VbsAction action) {
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
        copy.mode = action.mode;
        copy.axis = action.axis;
        copy.facing = action.facing;
        return copy;
    }

    static StructureBuilder.VbsScriptV2 copyScript(StructureBuilder.VbsScriptV2 source) {
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

    // ── Materialize (unchanged) ─────────────────────────────────────────

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
                    case "box" -> writeBox(map, action, state);
                    case "plane" -> writePlane(map, action, state);
                    case "line" -> writeLine(map, action, state);
                    case "points" -> writePoints(map, action, state);
                    case "fill", "frame", "set" -> throw new IllegalArgumentException(
                            "Legacy action type '" + type + "' is no longer supported. Use box/plane/line/points."
                    );
                    default -> throw new IllegalArgumentException("Unsupported action type: " + action.type);
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

    // ── Override analysis (unchanged) ───────────────────────────────────

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
                    case "box" -> analyzeBox(written, stats, part, action);
                    case "plane" -> analyzePlane(written, stats, part, action);
                    case "line" -> analyzeLine(written, stats, part, action);
                    case "points" -> analyzePoints(written, stats, part, action);
                    case "fill", "frame", "set" -> throw new IllegalArgumentException(
                            "Legacy action type '" + type + "' is no longer supported. Use box/plane/line/points."
                    );
                    default -> throw new IllegalArgumentException("Unsupported action type: " + action.type);
                }
            }
        }
        return stats;
    }

    private static void writeBox(Map<Int3, BlockState> map, StructureBuilder.VbsAction action, BlockState state) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int[] bounds = bounds(from, to);
        int minX = bounds[0];
        int minY = bounds[1];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxY = bounds[4];
        int maxZ = bounds[5];
        String mode = normalize(action.mode);
        if (mode.isBlank()) {
            mode = "solid";
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean place = switch (mode) {
                        case "solid" -> true;
                        case "shell" -> x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                        case "walls" -> x == minX || x == maxX || z == minZ || z == maxZ;
                        default -> false;
                    };
                    if (!place) {
                        continue;
                    }
                    map.put(new Int3(x, y, z), state);
                }
            }
        }
    }

    private static void writePlane(Map<Int3, BlockState> map, StructureBuilder.VbsAction action, BlockState state) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int axisIdx = axisIndex(action.axis);
        if (axisIdx < 0 || from[axisIdx] != to[axisIdx]) {
            return;
        }
        int[] bounds = bounds(from, to);
        int minX = bounds[0];
        int minY = bounds[1];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxY = bounds[4];
        int maxZ = bounds[5];
        String mode = normalize(action.mode);
        if (mode.isBlank()) {
            mode = "solid";
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!isOnAxisPlane(axisIdx, from, x, y, z)) {
                        continue;
                    }
                    if ("outline".equals(mode)) {
                        boolean boundary;
                        if (axisIdx == 0) {
                            boundary = y == minY || y == maxY || z == minZ || z == maxZ;
                        } else if (axisIdx == 1) {
                            boundary = x == minX || x == maxX || z == minZ || z == maxZ;
                        } else {
                            boundary = x == minX || x == maxX || y == minY || y == maxY;
                        }
                        if (!boundary) {
                            continue;
                        }
                    } else if (!"solid".equals(mode)) {
                        continue;
                    }
                    map.put(new Int3(x, y, z), state);
                }
            }
        }
    }

    private static void writeLine(Map<Int3, BlockState> map, StructureBuilder.VbsAction action, BlockState state) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        drawLine(from[0], from[1], from[2], to[0], to[1], to[2], (x, y, z) -> map.put(new Int3(x, y, z), state));
    }

    private static void writePoints(Map<Int3, BlockState> map, StructureBuilder.VbsAction action, BlockState state) {
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

    private static void analyzeBox(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part,
                                   StructureBuilder.VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int[] bounds = bounds(from, to);
        int minX = bounds[0];
        int minY = bounds[1];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxY = bounds[4];
        int maxZ = bounds[5];
        String mode = normalize(action.mode);
        if (mode.isBlank()) {
            mode = "solid";
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean place = switch (mode) {
                        case "solid" -> true;
                        case "shell" -> x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                        case "walls" -> x == minX || x == maxX || z == minZ || z == maxZ;
                        default -> false;
                    };
                    if (!place) {
                        continue;
                    }
                    recordWrite(written, stats, part, x, y, z);
                }
            }
        }
    }

    private static void analyzePlane(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part,
                                     StructureBuilder.VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        int axisIdx = axisIndex(action.axis);
        if (axisIdx < 0 || from[axisIdx] != to[axisIdx]) {
            return;
        }
        int[] bounds = bounds(from, to);
        int minX = bounds[0];
        int minY = bounds[1];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxY = bounds[4];
        int maxZ = bounds[5];
        String mode = normalize(action.mode);
        if (mode.isBlank()) {
            mode = "solid";
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!isOnAxisPlane(axisIdx, from, x, y, z)) {
                        continue;
                    }
                    if ("outline".equals(mode)) {
                        boolean boundary;
                        if (axisIdx == 0) {
                            boundary = y == minY || y == maxY || z == minZ || z == maxZ;
                        } else if (axisIdx == 1) {
                            boundary = x == minX || x == maxX || z == minZ || z == maxZ;
                        } else {
                            boundary = x == minX || x == maxX || y == minY || y == maxY;
                        }
                        if (!boundary) {
                            continue;
                        }
                    } else if (!"solid".equals(mode)) {
                        continue;
                    }
                    recordWrite(written, stats, part, x, y, z);
                }
            }
        }
    }

    private static void analyzeLine(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part,
                                    StructureBuilder.VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        drawLine(from[0], from[1], from[2], to[0], to[1], to[2], (x, y, z) -> recordWrite(written, stats, part, x, y, z));
    }

    private static void analyzePoints(Map<Int3, BlockSource> written, OverrideStats stats, StructureBuilder.StructurePart part,
                                      StructureBuilder.VbsAction action) {
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

    private static int[] bounds(int[] from, int[] to) {
        return new int[]{
                Math.min(from[0], to[0]),
                Math.min(from[1], to[1]),
                Math.min(from[2], to[2]),
                Math.max(from[0], to[0]),
                Math.max(from[1], to[1]),
                Math.max(from[2], to[2])
        };
    }

    private static int axisIndex(String axis) {
        return switch (normalize(axis)) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> -1;
        };
    }

    private static boolean isOnAxisPlane(int axisIdx, int[] from, int x, int y, int z) {
        return switch (axisIdx) {
            case 0 -> x == from[0];
            case 1 -> y == from[1];
            case 2 -> z == from[2];
            default -> false;
        };
    }

    private interface PointVisitor {
        void visit(int x, int y, int z);
    }

    private static void drawLine(int x1, int y1, int z1, int x2, int y2, int z2, PointVisitor visitor) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);
        int xs = x2 >= x1 ? 1 : -1;
        int ys = y2 >= y1 ? 1 : -1;
        int zs = z2 >= z1 ? 1 : -1;

        visitor.visit(x1, y1, z1);
        if (dx >= dy && dx >= dz) {
            int p1 = 2 * dy - dx;
            int p2 = 2 * dz - dx;
            while (x1 != x2) {
                x1 += xs;
                if (p1 >= 0) {
                    y1 += ys;
                    p1 -= 2 * dx;
                }
                if (p2 >= 0) {
                    z1 += zs;
                    p2 -= 2 * dx;
                }
                p1 += 2 * dy;
                p2 += 2 * dz;
                visitor.visit(x1, y1, z1);
            }
            return;
        }
        if (dy >= dx && dy >= dz) {
            int p1 = 2 * dx - dy;
            int p2 = 2 * dz - dy;
            while (y1 != y2) {
                y1 += ys;
                if (p1 >= 0) {
                    x1 += xs;
                    p1 -= 2 * dy;
                }
                if (p2 >= 0) {
                    z1 += zs;
                    p2 -= 2 * dy;
                }
                p1 += 2 * dx;
                p2 += 2 * dz;
                visitor.visit(x1, y1, z1);
            }
            return;
        }
        int p1 = 2 * dy - dz;
        int p2 = 2 * dx - dz;
        while (z1 != z2) {
            z1 += zs;
            if (p1 >= 0) {
                y1 += ys;
                p1 -= 2 * dz;
            }
            if (p2 >= 0) {
                x1 += xs;
                p2 -= 2 * dz;
            }
            p1 += 2 * dy;
            p2 += 2 * dx;
            visitor.visit(x1, y1, z1);
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
