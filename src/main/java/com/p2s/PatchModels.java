package com.p2s;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class PatchModels {
    private PatchModels() {
    }

    public static final class StructurePatch {
        @SerializedName(value = "base_revision", alternate = {"baseRevision"})
        public String baseRevision = "";

        public String intent = "";

        public List<PatchOperation> operations = new ArrayList<>();

        @SerializedName(value = "message_to_user", alternate = {"messageToUser"})
        public String messageToUser = "";
    }

    public static final class PatchOperation {
        public String op;
        public String part;
        public Integer priority;

        @SerializedName(value = "actions_add", alternate = {"actionsAdd"})
        public List<StructureBuilder.VbsAction> actionsAdd;

        @SerializedName(value = "old_actions", alternate = {"oldActions"})
        public List<StructureBuilder.VbsAction> oldActions;

        @SerializedName(value = "new_actions", alternate = {"newActions"})
        public List<StructureBuilder.VbsAction> newActions;

        public List<Integer> offset;

        @SerializedName(value = "target_part", alternate = {"targetPart"})
        public String targetPart;

        public List<PaletteEntry> entries;
    }

    public static final class PaletteEntry {
        public String key;

        @SerializedName(value = "old_value", alternate = {"oldValue"})
        public String oldValue;

        @SerializedName(value = "new_value", alternate = {"newValue"})
        public String newValue;
    }

    public static final class VerificationError {
        @SerializedName(value = "operation_index", alternate = {"operationIndex"})
        public int operationIndex;
        public String op;
        public String part;
        public String error;
        public List<StructureBuilder.VbsAction> expected;
        public List<StructureBuilder.VbsAction> actual;
        public String hint;

        public VerificationError(int operationIndex, String op, String part, String error,
                                 List<StructureBuilder.VbsAction> expected,
                                 List<StructureBuilder.VbsAction> actual) {
            this.operationIndex = operationIndex;
            this.op = op;
            this.part = part;
            this.error = error;
            this.expected = expected;
            this.actual = actual;
            this.hint = "Read the workspace again with read_workspace_file and retry with corrected old_actions in patch_toml.";
        }
    }

    public record BlockOp(int x, int y, int z, BlockState state) {
    }

    public static final class ValidationResult {
        public boolean ok = true;
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int estimatedChangedBlocks = 0;
        public String riskLevel = "low";
        public String summary = "";
        public boolean requiresConfirm = true;

        public void addError(String message) {
            ok = false;
            if (message != null && !message.isBlank()) {
                errors.add(message);
            }
        }

        public void addWarning(String message) {
            if (message != null && !message.isBlank()) {
                warnings.add(message);
            }
        }
    }

    public static final class Preview {
        public String summary = "";
        public String detail = "";
        public int changedBlocks = 0;
        public String riskLevel = "low";
        public final List<String> warnings = new ArrayList<>();
    }
}
