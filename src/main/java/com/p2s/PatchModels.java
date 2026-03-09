package com.p2s;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class PatchModels {
    private PatchModels() {
    }

    public static final class StructurePatch {
        @SerializedName("base_revision")
        public String baseRevision = "";

        public String intent = "";

        public List<PatchOperation> operations = new ArrayList<>();

        @SerializedName("message_to_user")
        public String messageToUser = "";
    }

    public static final class PatchOperation {
        public String op;
        public String part;
        public Integer priority;

        @SerializedName("actions_add")
        public List<StructureBuilder.VbsAction> actionsAdd;

        @SerializedName("old_actions")
        public List<StructureBuilder.VbsAction> oldActions;

        @SerializedName("new_actions")
        public List<StructureBuilder.VbsAction> newActions;

        public List<Integer> offset;

        @SerializedName("target_part")
        public String targetPart;

        public List<PaletteEntry> entries;
    }

    public static final class PaletteEntry {
        public String key;

        @SerializedName("old_value")
        public String oldValue;

        @SerializedName("new_value")
        public String newValue;
    }

    public static final class VerificationError {
        @SerializedName("operation_index")
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
