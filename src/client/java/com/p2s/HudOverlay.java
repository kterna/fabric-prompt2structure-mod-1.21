package com.p2s;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

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

            boolean canSelect = ClientSelectionManager.isSelectionToolHeld(client.player);
            String selectionItem = P2SClientConfig.getSelectionItemId();
            gfx.drawString(client.font, P2SI18n.tr("hud.p2s.selection",
                    canSelect ? P2SI18n.tr("hud.p2s.selection.ready") : P2SI18n.tr("hud.p2s.selection.hold", selectionItem)), x, y, 0xFFFFFF, true);
            y += 10;

            BlockPos p1 = ClientSelectionManager.getPos1();
            BlockPos p2 = ClientSelectionManager.getPos2();
            if (p1 != null || p2 != null) {
                String pos1 = p1 == null ? "-" : p1.getX() + "," + p1.getY() + "," + p1.getZ();
                String pos2 = p2 == null ? "-" : p2.getX() + "," + p2.getY() + "," + p2.getZ();
                gfx.drawString(client.font, P2SI18n.tr("hud.p2s.pos1", pos1), x, y, 0xAAAAAA, true);
                y += 10;
                gfx.drawString(client.font, P2SI18n.tr("hud.p2s.pos2", pos2), x, y, 0xAAAAAA, true);
                y += 10;
                if (p1 != null && p2 != null) {
                    int sx = Math.abs(p1.getX() - p2.getX()) + 1;
                    int sy = Math.abs(p1.getY() - p2.getY()) + 1;
                    int sz = Math.abs(p1.getZ() - p2.getZ()) + 1;
                    gfx.drawString(client.font, P2SI18n.tr("hud.p2s.size", sx, sy, sz), x, y, 0xAAAAAA, true);
                    y += 10;
                }
            }

            if (ClientSessionState.isActive()) {
                gfx.drawString(client.font, P2SI18n.tr("hud.p2s.session", ClientSessionState.getSessionId()), x, y, 0xFFFFFF, true);
                y += 10;
                gfx.drawString(client.font, P2SI18n.tr("hud.p2s.stats",
                        ClientSessionState.getTurnCount(),
                        ClientSessionState.getPartCount(),
                        ClientSessionState.getTotalBlocks()), x, y, 0xAAAAAA, true);
                y += 10;
                String status = ClientSessionState.getStatus();
                if (status != null && !status.isBlank()) {
                    gfx.drawString(client.font, P2SI18n.tr("hud.p2s.status", P2SI18n.statusComponent(status)), x, y, 0xAAAAAA, true);
                    y += 10;
                }
                String summary = ClientSessionState.getPartsSummary();
                if (summary != null && !summary.isBlank()) {
                    gfx.drawString(client.font, P2SI18n.tr("hud.p2s.parts", summary), x, y, 0xAAAAAA, true);
                }
            }
        });
    }
}
