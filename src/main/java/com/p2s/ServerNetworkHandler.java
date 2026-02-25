package com.p2s;

import com.p2s.network.C2SChatMessagePayload;
import com.p2s.network.C2SSetSelectionPayload;
import com.p2s.network.C2SSessionActionPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(C2SSetSelectionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> SelectionManager.handleClientSelection(player, payload.pointIndex(), payload.pos()));
        });

        ServerPlayNetworking.registerGlobalReceiver(C2SChatMessagePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> SessionManager.handleChatMessage(player, payload.message()));
        });

        ServerPlayNetworking.registerGlobalReceiver(C2SSessionActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> SessionManager.handleSessionAction(player, payload.action(), payload.payload()));
        });
    }

    public static void sendToClient(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
