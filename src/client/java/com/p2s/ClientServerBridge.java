package com.p2s;

import com.p2s.network.C2SSetSelectionPayload;
import com.p2s.network.C2SSessionActionPayload;
import com.p2s.network.C2SToolBridgePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class ClientServerBridge {
    private static boolean missingServerNoticeShown = false;

    private ClientServerBridge() {
    }

    public static void onJoin() {
        missingServerNoticeShown = false;
    }

    public static void onDisconnect() {
        missingServerNoticeShown = false;
    }

    public static boolean hasRequiredServer() {
        return canSendSessionAction() && canUseToolBridge() && canSyncSelection();
    }

    public static boolean canSendSessionAction() {
        return ClientPlayNetworking.canSend(C2SSessionActionPayload.TYPE);
    }

    public static boolean canUseToolBridge() {
        return ClientPlayNetworking.canSend(C2SToolBridgePayload.TYPE);
    }

    public static boolean canSyncSelection() {
        return ClientPlayNetworking.canSend(C2SSetSelectionPayload.TYPE);
    }

    public static boolean sendSessionAction(String action, String payload) {
        if (!canSendSessionAction()) {
            notifyMissingServer();
            return false;
        }
        ClientPlayNetworking.send(new C2SSessionActionPayload(action == null ? "" : action, payload == null ? "" : payload));
        return true;
    }

    public static boolean sendSelection(int pointIndex, BlockPos pos) {
        if (!canSyncSelection()) {
            notifyMissingServer();
            return false;
        }
        ClientPlayNetworking.send(new C2SSetSelectionPayload(pointIndex, pos));
        return true;
    }

    public static IllegalStateException missingServerException() {
        notifyMissingServer();
        return new IllegalStateException(P2SI18n.tr("message.p2s.multiplayer.requires_client_and_server").getString());
    }

    public static void notifyMissingServer() {
        if (missingServerNoticeShown) {
            return;
        }
        missingServerNoticeShown = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            String message = P2SI18n.tr("message.p2s.multiplayer.requires_client_and_server").getString();
            ClientSessionState.addSystemMessage(message);
            ClientSessionState.setStatus("error");
        });
    }
}
