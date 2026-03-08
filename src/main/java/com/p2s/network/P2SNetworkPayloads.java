package com.p2s.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class P2SNetworkPayloads {
    private static boolean REGISTERED = false;

    private P2SNetworkPayloads() {
    }

    public static synchronized void register() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        PayloadTypeRegistry.playC2S().register(C2SSetSelectionPayload.TYPE, C2SSetSelectionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(C2SSessionActionPayload.TYPE, C2SSessionActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(C2SToolBridgePayload.TYPE, C2SToolBridgePayload.CODEC);

        PayloadTypeRegistry.playS2C().register(S2CSelectionSyncPayload.TYPE, S2CSelectionSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(S2CChatResponsePayload.TYPE, S2CChatResponsePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(S2CSessionSyncPayload.TYPE, S2CSessionSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(S2CBuildProgressPayload.TYPE, S2CBuildProgressPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(S2CPatchPreviewPayload.TYPE, S2CPatchPreviewPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(S2CToolBridgePayload.TYPE, S2CToolBridgePayload.CODEC);
    }
}
