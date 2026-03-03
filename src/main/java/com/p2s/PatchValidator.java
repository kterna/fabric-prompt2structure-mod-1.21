package com.p2s;

import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PatchValidator {
    private static final Set<String> SUPPORTED_OPS = Set.of(
            "insert_part",
            "delete_part",
            "replace_part",
            "insert_actions",
            "delete_actions",
            "replace_actions",
            "move_actions",
            "update_palette"
    );

    private static final Set<String> SUPPORTED_ACTION_TYPES = Set.of(
            "box",
            "plane",
            "line",
            "points"
    );

    private static final Set<String> LEGACY_ACTION_TYPES = Set.of(
            "fill",
            "frame",
            "set"
    );

    private static final Set<String> SUPPORTED_BOX_MODES = Set.of(
            "solid",
            "shell",
            "walls"
    );

    private static final Set<String> SUPPORTED_PLANE_MODES = Set.of(
            "solid",
            "outline"
    );

    private static final Set<String> SUPPORTED_AXES = Set.of(
            "x",
            "y",
            "z"
    );

    private static final Set<String> SUPPORTED_FACING = Set.of(
            "north",
            "south",
            "east",
            "west",
            "up",
            "down"
    );

    private PatchValidator() {
    }

    public static PatchModels.ValidationResult validate(
            PatchModels.StructurePatch patch,
            String currentRevision,
            Vec3i size,
            StructureBuilder.VbsScriptV2 candidateScript,
            StructurePatchEngine.DiffResult diff,
            int maxPatchOps,
            int maxBlocksPerCommit
    ) {
        PatchModels.ValidationResult result = new PatchModels.ValidationResult();

        if (patch == null) {
            result.addError("Patch is null");
            result.summary = "Invalid patch";
            return result;
        }

        int opCount = patch.operations == null ? 0 : patch.operations.size();
        if (opCount <= 0) {
            result.addError("Patch has no operations");
        }
        if (maxPatchOps > 0 && opCount > maxPatchOps) {
            result.addError("Patch operation count exceeds limit: " + opCount + " > " + maxPatchOps);
        }
        validatePatchOperations(patch, result);
        validateBlockReferences(patch, candidateScript, result);

        String baseRevision = patch.baseRevision == null ? "" : patch.baseRevision.trim();
        if (!baseRevision.isBlank() && currentRevision != null && !currentRevision.isBlank() && !baseRevision.equals(currentRevision)) {
            result.addError("Patch base_revision mismatch");
        }

        validatePartBasics(candidateScript, result);
        if (size != null) {
            validateBounds(candidateScript, size, result);
        }
        addOverrideWarnings(candidateScript, result);

        int changed = diff == null ? 0 : diff.changedBlocks;
        result.estimatedChangedBlocks = changed;
        if (maxBlocksPerCommit > 0 && changed > maxBlocksPerCommit) {
            result.addError("Changed block count exceeds limit: " + changed + " > " + maxBlocksPerCommit);
        }

        if (changed == 0 && opCount > 0 && !result.errors.isEmpty()) {
            result.addError("Patch has operations but produces no valid changes");
        } else if (changed == 0) {
            result.addWarning("Patch does not change any blocks");
        }

        result.riskLevel = computeRisk(changed, result.warnings, result.errors);
        result.summary = "ops=" + opCount
                + ", changed=" + changed
                + ", risk=" + result.riskLevel
                + ", errors=" + result.errors.size()
                + ", warnings=" + result.warnings.size();
        return result;
    }

    private static void validatePatchOperations(PatchModels.StructurePatch patch, PatchModels.ValidationResult result) {
        if (patch == null || patch.operations == null) {
            return;
        }

        for (int i = 0; i < patch.operations.size(); i++) {
            PatchModels.PatchOperation op = patch.operations.get(i);
            String prefix = "Operation[" + i + "]";

            if (op == null) {
                result.addError(prefix + " is null");
                continue;
            }

            String opName = normalize(op.op);
            if (opName.isBlank()) {
                result.addError(prefix + " missing op");
                continue;
            }
            if (!SUPPORTED_OPS.contains(opName)) {
                result.addError(prefix + " unsupported op '" + op.op + "'");
                continue;
            }

            if (requiresPart(opName) && isBlank(op.part)) {
                result.addError(prefix + " requires non-empty part");
            }

            switch (opName) {
                case "insert_part" -> {
                    if (op.actionsAdd == null || op.actionsAdd.isEmpty()) {
                        result.addError(prefix + " insert_part requires actions_add");
                    }
                    validateAddActions(op.actionsAdd, prefix, result);
                }
                case "delete_part" -> {
                    validateOldActions(op.oldActions, prefix, "delete_part", result);
                }
                case "replace_part" -> {
                    validateOldActions(op.oldActions, prefix, "replace_part", result);
                    validateNewActions(op.newActions, prefix, "replace_part", result);
                }
                case "insert_actions" -> {
                    if (op.actionsAdd == null || op.actionsAdd.isEmpty()) {
                        result.addError(prefix + " insert_actions requires actions_add");
                    }
                    validateAddActions(op.actionsAdd, prefix, result);
                }
                case "delete_actions" -> {
                    validateOldActions(op.oldActions, prefix, "delete_actions", result);
                }
                case "replace_actions" -> {
                    validateOldActions(op.oldActions, prefix, "replace_actions", result);
                    validateNewActions(op.newActions, prefix, "replace_actions", result);
                }
                case "move_actions" -> {
                    validateOldActions(op.oldActions, prefix, "move_actions", result);
                    validateOffset(op.offset, prefix, result);
                }
                case "update_palette" -> {
                    validatePaletteEntries(op.entries, prefix, result);
                }
                default -> {
                }
            }
        }
    }

    private static void validateOldActions(List<StructureBuilder.VbsAction> oldActions, String prefix,
                                           String opName, PatchModels.ValidationResult result) {
        if (oldActions == null || oldActions.isEmpty()) {
            result.addError(prefix + " " + opName + " requires old_actions");
            return;
        }
        for (int i = 0; i < oldActions.size(); i++) {
            validateAction(oldActions.get(i), prefix + ".old_actions[" + i + "]", result);
        }
    }

    private static void validateNewActions(List<StructureBuilder.VbsAction> newActions, String prefix,
                                           String opName, PatchModels.ValidationResult result) {
        if (newActions == null || newActions.isEmpty()) {
            result.addError(prefix + " " + opName + " requires new_actions");
            return;
        }
        for (int i = 0; i < newActions.size(); i++) {
            validateAction(newActions.get(i), prefix + ".new_actions[" + i + "]", result);
        }
    }

    private static void validateOffset(List<Integer> offset, String prefix, PatchModels.ValidationResult result) {
        if (offset == null || offset.size() < 3) {
            result.addError(prefix + " move_actions requires offset=[dx,dy,dz]");
            return;
        }
        if (offset.get(0) == null || offset.get(1) == null || offset.get(2) == null) {
            result.addError(prefix + " offset values must not be null");
        }
    }

    private static void validatePaletteEntries(List<PatchModels.PaletteEntry> entries, String prefix,
                                               PatchModels.ValidationResult result) {
        if (entries == null || entries.isEmpty()) {
            result.addWarning(prefix + " update_palette has empty entries");
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            PatchModels.PaletteEntry entry = entries.get(i);
            String path = prefix + ".entries[" + i + "]";
            if (entry == null) {
                result.addError(path + " is null");
                continue;
            }
            if (isBlank(entry.key)) {
                result.addError(path + " missing key");
                continue;
            }
            // new_value validation: if adding or modifying, new_value should resolve
            if (entry.newValue != null && !entry.newValue.isBlank()) {
                if (StructureBuilder.resolveDirectBlockState(entry.newValue.trim()) == null) {
                    result.addError(path + " new_value '" + entry.newValue + "' is not a valid block id");
                }
            }
        }
    }

    private static void validateBlockReferences(
            PatchModels.StructurePatch patch,
            StructureBuilder.VbsScriptV2 candidateScript,
            PatchModels.ValidationResult result
    ) {
        if (patch == null || patch.operations == null) {
            return;
        }
        Map<String, String> palette = candidateScript == null || candidateScript.palette == null
                ? Map.of()
                : candidateScript.palette;

        for (int i = 0; i < patch.operations.size(); i++) {
            PatchModels.PatchOperation op = patch.operations.get(i);
            if (op == null) {
                continue;
            }
            String prefix = "Operation[" + i + "]";

            // Check actions_add block references
            checkActionsBlockRefs(op.actionsAdd, prefix + ".actions_add", palette, result);
            // Check new_actions block references
            checkActionsBlockRefs(op.newActions, prefix + ".new_actions", palette, result);

            // Check palette entries new_value block references
            if (op.entries != null) {
                for (int j = 0; j < op.entries.size(); j++) {
                    PatchModels.PaletteEntry entry = op.entries.get(j);
                    if (entry == null) continue;
                    if (entry.newValue != null && !entry.newValue.isBlank()) {
                        // Already validated in validatePaletteEntries
                    }
                }
            }
        }
    }

    private static void checkActionsBlockRefs(List<StructureBuilder.VbsAction> actions, String prefix,
                                              Map<String, String> palette, PatchModels.ValidationResult result) {
        if (actions == null) return;
        for (int j = 0; j < actions.size(); j++) {
            StructureBuilder.VbsAction action = actions.get(j);
            if (action == null) continue;
            String block = action.block;
            String path = prefix + "[" + j + "]";
            if (isBlank(block)) continue;
            if (palette.containsKey(block)) continue;
            if (StructureBuilder.resolveDirectBlockState(block) != null) continue;
            result.addError(path + " block '" + block + "' not in palette and not a valid block id");
        }
    }

    private static boolean requiresPart(String opName) {
        return !"update_palette".equals(opName);
    }

    private static void validateAddActions(List<StructureBuilder.VbsAction> actions, String prefix, PatchModels.ValidationResult result) {
        if (actions == null) {
            return;
        }
        for (int i = 0; i < actions.size(); i++) {
            validateAction(actions.get(i), prefix + ".actions_add[" + i + "]", result);
        }
    }

    private static void validateAction(StructureBuilder.VbsAction action, String path, PatchModels.ValidationResult result) {
        if (action == null) {
            result.addError(path + " is null");
            return;
        }

        String type = normalize(action.type);
        if (LEGACY_ACTION_TYPES.contains(type)) {
            result.addError(path + " legacy action type '" + action.type + "' is removed; use box/plane/line/points");
            return;
        }
        if (!SUPPORTED_ACTION_TYPES.contains(type)) {
            result.addError(path + " unsupported action type '" + action.type + "'");
            return;
        }
        if (isBlank(action.block)) {
            result.addError(path + " missing block");
        }
        if (!isBlank(action.facing) && !SUPPORTED_FACING.contains(normalize(action.facing))) {
            result.addError(path + " invalid facing '" + action.facing + "'");
        }

        switch (type) {
            case "box" -> {
                if (!isVec3(action.from)) {
                    result.addError(path + " requires from=[x,y,z]");
                }
                if (!isVec3(action.to)) {
                    result.addError(path + " requires to=[x,y,z]");
                }
                String mode = normalize(action.mode);
                if (mode.isBlank()) {
                    result.addError(path + " box requires mode=solid|shell|walls");
                } else if (!SUPPORTED_BOX_MODES.contains(mode)) {
                    result.addError(path + " invalid box mode '" + action.mode + "'");
                }
            }
            case "plane" -> {
                if (!isVec3(action.from)) {
                    result.addError(path + " requires from=[x,y,z]");
                }
                if (!isVec3(action.to)) {
                    result.addError(path + " requires to=[x,y,z]");
                }
                String axis = normalize(action.axis);
                if (axis.isBlank()) {
                    result.addError(path + " plane requires axis=x|y|z");
                } else if (!SUPPORTED_AXES.contains(axis)) {
                    result.addError(path + " invalid axis '" + action.axis + "'");
                }
                String mode = normalize(action.mode);
                if (mode.isBlank()) {
                    result.addError(path + " plane requires mode=solid|outline");
                } else if (!SUPPORTED_PLANE_MODES.contains(mode)) {
                    result.addError(path + " invalid plane mode '" + action.mode + "'");
                }
                if (isVec3(action.from) && isVec3(action.to) && SUPPORTED_AXES.contains(axis)) {
                    int idx = axisIndex(axis);
                    if (idx >= 0 && !action.from.get(idx).equals(action.to.get(idx))) {
                        result.addError(path + " plane requires from/to with same coordinate on axis '" + axis + "'");
                    }
                }
            }
            case "line" -> {
                if (!isVec3(action.from)) {
                    result.addError(path + " requires from=[x,y,z]");
                }
                if (!isVec3(action.to)) {
                    result.addError(path + " requires to=[x,y,z]");
                }
            }
            case "points" -> {
                if (action.at == null || action.at.isEmpty()) {
                    result.addError(path + " points requires non-empty at");
                } else {
                    for (int i = 0; i < action.at.size(); i++) {
                        if (!isVec3(action.at.get(i))) {
                            result.addError(path + " at[" + i + "] must be [x,y,z]");
                            break;
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    private static boolean isVec3(List<Integer> vec) {
        return vec != null
                && vec.size() >= 3
                && vec.get(0) != null
                && vec.get(1) != null
                && vec.get(2) != null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void validatePartBasics(StructureBuilder.VbsScriptV2 candidateScript, PatchModels.ValidationResult result) {
        if (candidateScript == null || candidateScript.structures == null) {
            return;
        }
        List<String> duplicateNames = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (StructureBuilder.StructurePart part : candidateScript.structures) {
            if (part == null || part.name == null || part.name.isBlank()) {
                result.addError("Part has blank name");
                continue;
            }
            if (!seen.add(part.name)) {
                duplicateNames.add(part.name);
            }
            if (part.actions == null || part.actions.isEmpty()) {
                result.addWarning("Part '" + part.name + "' has no actions");
            }
        }
        if (!duplicateNames.isEmpty()) {
            result.addError("Duplicate part names: " + String.join(", ", duplicateNames));
        }
    }

    private static void validateBounds(StructureBuilder.VbsScriptV2 script, Vec3i size, PatchModels.ValidationResult result) {
        if (script == null || script.structures == null || size == null) {
            return;
        }
        int maxX = size.getX() - 1;
        int maxY = size.getY() - 1;
        int maxZ = size.getZ() - 1;

        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (action == null) {
                    continue;
                }
                if (!checkVec(action.from, maxX, maxY, maxZ)) {
                    result.addError("Out-of-bounds 'from' in part '" + part.name + "'");
                }
                if (!checkVec(action.to, maxX, maxY, maxZ)) {
                    result.addError("Out-of-bounds 'to' in part '" + part.name + "'");
                }
                if (action.at != null) {
                    for (List<Integer> point : action.at) {
                        if (!checkVec(point, maxX, maxY, maxZ)) {
                            result.addError("Out-of-bounds 'points' point in part '" + part.name + "'");
                            break;
                        }
                    }
                }
            }
        }
    }

    private static int axisIndex(String axis) {
        return switch (normalize(axis)) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> -1;
        };
    }

    private static boolean checkVec(List<Integer> vec, int maxX, int maxY, int maxZ) {
        if (vec == null) {
            return true;
        }
        if (vec.size() < 3 || vec.get(0) == null || vec.get(1) == null || vec.get(2) == null) {
            return false;
        }
        int x = vec.get(0);
        int y = vec.get(1);
        int z = vec.get(2);
        return x >= 0 && y >= 0 && z >= 0 && x <= maxX && y <= maxY && z <= maxZ;
    }

    private static String computeRisk(int changed, List<String> warnings, List<String> errors) {
        if (errors != null && !errors.isEmpty()) {
            return "high";
        }
        if (changed > 20000) {
            return "high";
        }
        if (changed > 5000) {
            return "medium";
        }
        if (warnings != null && !warnings.isEmpty()) {
            return "medium";
        }
        return "low";
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private static void addOverrideWarnings(StructureBuilder.VbsScriptV2 script, PatchModels.ValidationResult result) {
        StructurePatchEngine.OverrideStats stats = StructurePatchEngine.analyzeOverrides(script);
        if (stats.overriddenBlocks <= 0) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Overlapping parts: ").append(stats.overriddenBlocks)
                .append(" blocks overwritten by later parts (")
                .append(stats.overriddenByHigherPriority).append(" by higher priority, ")
                .append(stats.overriddenBySamePriority).append(" by same priority).");
        String topOverriders = formatTopCounts(stats.overriderCounts, 3);
        if (!topOverriders.isBlank()) {
            sb.append(" Top overwriters: ").append(topOverriders).append(".");
        }
        String topOverridden = formatTopCounts(stats.overriddenCounts, 3);
        if (!topOverridden.isBlank()) {
            sb.append(" Most overwritten: ").append(topOverridden).append(".");
        }
        result.addWarning(sb.toString());
    }

    private static String formatTopCounts(Map<String, Integer> counts, int limit) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            if (cmp != 0) {
                return cmp;
            }
            return a.getKey().compareTo(b.getKey());
        });
        int size = Math.min(limit, entries.size());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            parts.add(entry.getKey() + "(+" + entry.getValue() + ")");
        }
        return String.join(", ", parts);
    }
}
