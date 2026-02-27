package com.p2s;

import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PatchValidator {
    private static final Set<String> SUPPORTED_OPS = Set.of(
            "upsert_part",
            "delete_part",
            "patch_actions",
            "set_palette"
    );

    private static final Set<String> SUPPORTED_ACTION_TYPES = Set.of(
            "fill",
            "frame",
            "set"
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

            if ("set_palette".equals(opName) && (op.paletteDelta == null || op.paletteDelta.isEmpty())) {
                result.addWarning(prefix + " set_palette has empty palette_delta");
            }

            if ("upsert_part".equals(opName) && (op.actionsAdd == null || op.actionsAdd.isEmpty())) {
                result.addError(prefix + " upsert_part requires actions_add");
            }

            if ("patch_actions".equals(opName)) {
                boolean hasAdd = op.actionsAdd != null && !op.actionsAdd.isEmpty();
                boolean hasRemove = op.actionsRemoveMatch != null && !op.actionsRemoveMatch.isEmpty();
                if (!hasAdd && !hasRemove) {
                    result.addError(prefix + " patch_actions requires actions_add or actions_remove_match");
                }
            }

            validateAddActions(op.actionsAdd, prefix, result);
            validateRemoveMatches(op.actionsRemoveMatch, prefix, result);
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
            if (op.paletteDelta != null && !op.paletteDelta.isEmpty()) {
                for (Map.Entry<String, String> entry : op.paletteDelta.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (isBlank(key)) {
                        result.addError(prefix + " palette_delta has blank key");
                        continue;
                    }
                    if (isBlank(value)) {
                        result.addError(prefix + " palette_delta[" + key + "] missing block id");
                        continue;
                    }
                    if (StructureBuilder.resolveDirectBlockState(value) == null) {
                        result.addError(prefix + " palette_delta[" + key + "] invalid block id '" + value + "'");
                    }
                }
            }

            if (op.actionsAdd == null) {
                continue;
            }
            for (int j = 0; j < op.actionsAdd.size(); j++) {
                StructureBuilder.VbsAction action = op.actionsAdd.get(j);
                if (action == null) {
                    continue;
                }
                String block = action.block;
                String path = prefix + ".actions_add[" + j + "]";
                if (isBlank(block)) {
                    continue;
                }
                if (palette.containsKey(block)) {
                    continue;
                }
                if (StructureBuilder.resolveDirectBlockState(block) != null) {
                    continue;
                }
                result.addError(path + " block '" + block + "' not in palette and not a valid block id");
            }
        }
    }

    private static boolean requiresPart(String opName) {
        return "upsert_part".equals(opName)
                || "delete_part".equals(opName)
                || "patch_actions".equals(opName);
    }

    private static void validateAddActions(List<StructureBuilder.VbsAction> actions, String prefix, PatchModels.ValidationResult result) {
        if (actions == null) {
            return;
        }
        for (int i = 0; i < actions.size(); i++) {
            validateAction(actions.get(i), prefix + ".actions_add[" + i + "]", result);
        }
    }

    private static void validateRemoveMatches(List<PatchModels.ActionMatch> matches, String prefix, PatchModels.ValidationResult result) {
        if (matches == null) {
            return;
        }
        for (int i = 0; i < matches.size(); i++) {
            validateActionMatch(matches.get(i), prefix + ".actions_remove_match[" + i + "]", result);
        }
    }

    private static void validateAction(StructureBuilder.VbsAction action, String path, PatchModels.ValidationResult result) {
        if (action == null) {
            result.addError(path + " is null");
            return;
        }

        String type = normalize(action.type);
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
            case "fill", "frame" -> {
                if (!isVec3(action.from)) {
                    result.addError(path + " requires from=[x,y,z]");
                }
                if (!isVec3(action.to)) {
                    result.addError(path + " requires to=[x,y,z]");
                }
            }
            case "set" -> {
                if (action.at == null || action.at.isEmpty()) {
                    result.addError(path + " set requires non-empty at");
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

    private static void validateActionMatch(PatchModels.ActionMatch match, String path, PatchModels.ValidationResult result) {
        if (match == null) {
            result.addError(path + " is null");
            return;
        }

        String type = normalize(match.type);
        if (!type.isBlank() && !SUPPORTED_ACTION_TYPES.contains(type)) {
            result.addError(path + " unsupported type '" + match.type + "'");
        }
        if (!isBlank(match.facing) && !SUPPORTED_FACING.contains(normalize(match.facing))) {
            result.addError(path + " invalid facing '" + match.facing + "'");
        }
        if (match.from != null && !isVec3(match.from)) {
            result.addError(path + " from must be [x,y,z]");
        }
        if (match.to != null && !isVec3(match.to)) {
            result.addError(path + " to must be [x,y,z]");
        }
        if (match.at != null) {
            for (int i = 0; i < match.at.size(); i++) {
                if (!isVec3(match.at.get(i))) {
                    result.addError(path + " at[" + i + "] must be [x,y,z]");
                    break;
                }
            }
        }

        if (isActionMatchEmpty(match)) {
            result.addError(path + " cannot be empty (would match all actions)");
        }
    }

    private static boolean isActionMatchEmpty(PatchModels.ActionMatch match) {
        return match != null
                && isBlank(match.type)
                && isBlank(match.block)
                && isBlank(match.facing)
                && match.from == null
                && match.to == null
                && match.at == null;
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
                            result.addError("Out-of-bounds 'set' point in part '" + part.name + "'");
                            break;
                        }
                    }
                }
            }
        }
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
