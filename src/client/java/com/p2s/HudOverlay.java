package com.p2s;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class HudOverlay {
    private HudOverlay() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return;
            }

            int x = 8;
            int y = 8;
            GuiGraphics gfx = drawContext;

            boolean selectMode = ClientSelectionManager.isSelectMode();
            gfx.drawString(client.font, Component.literal("Selection: " + (selectMode ? "ON" : "OFF")), x, y, 0xFFFFFF, true);
            y += 10;

            BlockPos p1 = ClientSelectionManager.getPos1();
            BlockPos p2 = ClientSelectionManager.getPos2();
            if (p1 != null || p2 != null) {
                String pos1 = p1 == null ? "-" : p1.getX() + "," + p1.getY() + "," + p1.getZ();
                String pos2 = p2 == null ? "-" : p2.getX() + "," + p2.getY() + "," + p2.getZ();
                gfx.drawString(client.font, Component.literal("Pos1: " + pos1), x, y, 0xAAAAAA, true);
                y += 10;
                gfx.drawString(client.font, Component.literal("Pos2: " + pos2), x, y, 0xAAAAAA, true);
                y += 10;
                if (p1 != null && p2 != null) {
                    int sx = Math.abs(p1.getX() - p2.getX()) + 1;
                    int sy = Math.abs(p1.getY() - p2.getY()) + 1;
                    int sz = Math.abs(p1.getZ() - p2.getZ()) + 1;
                    gfx.drawString(client.font, Component.literal("Size: " + sx + "x" + sy + "x" + sz), x, y, 0xAAAAAA, true);
                    y += 10;
                }
            }

            if (ClientSessionState.isActive()) {
                gfx.drawString(client.font, Component.literal("Session: " + ClientSessionState.getSessionId()), x, y, 0xFFFFFF, true);
                y += 10;
                gfx.drawString(client.font, Component.literal("Turns: " + ClientSessionState.getTurnCount()
                        + " | Parts: " + ClientSessionState.getPartCount()
                        + " | Blocks: " + ClientSessionState.getTotalBlocks()), x, y, 0xAAAAAA, true);
                y += 10;
                String status = ClientSessionState.getStatus();
                if (status != null && !status.isBlank()) {
                    gfx.drawString(client.font, Component.literal("Status: " + status), x, y, 0xAAAAAA, true);
                    y += 10;
                }
                String summary = ClientSessionState.getPartsSummary();
                if (summary != null && !summary.isBlank()) {
                    gfx.drawString(client.font, Component.literal("Parts: " + summary), x, y, 0xAAAAAA, true);
                }
            }
        });
    }
}
