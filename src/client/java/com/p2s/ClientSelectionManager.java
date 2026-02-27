package com.p2s;

import com.p2s.network.C2SSetSelectionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ClientSelectionManager {
    private static BlockPos pos1;
    private static BlockPos pos2;

    private ClientSelectionManager() {
    }

    public static void onSyncFromServer(BlockPos p1, BlockPos p2) {
        pos1 = p1;
        pos2 = p2;
    }

    public static boolean isSelectionToolHeld(Player player) {
        if (player == null) {
            return false;
        }
        return isSelectionTool(player.getMainHandItem()) || isSelectionTool(player.getOffhandItem());
    }

    public static BlockPos getPos1() {
        return pos1;
    }

    public static BlockPos getPos2() {
        return pos2;
    }

    public static void clearSelection() {
        sendC2SSetSelection(-1, BlockPos.ZERO);
    }

    public static void onLeftClick(BlockPos pos) {
        if (pos == null) {
            return;
        }
        sendC2SSetSelection(0, pos);
    }

    public static void onRightClick(BlockPos pos) {
        if (pos == null) {
            return;
        }
        sendC2SSetSelection(1, pos);
    }

    private static boolean isSelectionTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return P2SClientConfig.isSelectionItem(stack.getItem());
    }

    private static void sendC2SSetSelection(int pointIndex, BlockPos pos) {
        ClientPlayNetworking.send(new C2SSetSelectionPayload(pointIndex, pos));
    }
}
