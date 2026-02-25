package com.p2s;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;

public final class SelectionInputHandler {
    private SelectionInputHandler() {
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!ClientSelectionManager.isSelectMode()) {
                return InteractionResult.PASS;
            }
            ClientSelectionManager.onLeftClick(pos);
            return InteractionResult.SUCCESS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!ClientSelectionManager.isSelectMode()) {
                return InteractionResult.PASS;
            }
            ClientSelectionManager.onRightClick(hitResult.getBlockPos());
            return InteractionResult.SUCCESS;
        });
    }
}
