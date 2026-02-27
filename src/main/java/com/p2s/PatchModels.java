package com.p2s;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        public List<StructureBuilder.VbsAction> actionsAdd = new ArrayList<>();

        @SerializedName(value = "actions_remove_match", alternate = {"actionsRemoveMatch"})
        public List<ActionMatch> actionsRemoveMatch = new ArrayList<>();

        @SerializedName(value = "palette_delta", alternate = {"paletteDelta"})
        public Map<String, String> paletteDelta = new LinkedHashMap<>();
    }

    public static final class ActionMatch {
        public String type;
        public String block;
        public List<Integer> from;
        public List<Integer> to;
        public List<List<Integer>> at;
        public String facing;
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
