package com.p2s;

import com.p2s.network.C2SSetSelectionPayload;
import com.p2s.network.C2SSessionActionPayload;
import com.p2s.network.C2SToolBridgePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(C2SSetSelectionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] S->C2SSetSelection received -> player={}, pointIndex={}, pos={}", player.getGameProfile().getName(), payload.pointIndex(), payload.pos());
            }
            runOnPlayerServer(player, () -> SelectionManager.handleClientSelection(player, payload.pointIndex(), payload.pos()));
        });

        ServerPlayNetworking.registerGlobalReceiver(C2SSessionActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] S->C2SSessionAction received -> player={}, action={}, payloadLen={}", player.getGameProfile().getName(), payload.action(), payload.payload() == null ? 0 : payload.payload().length());
            }
            runOnPlayerServer(player, () -> SessionManager.handleSessionAction(player, payload.action(), payload.payload()));
        });

        ServerPlayNetworking.registerGlobalReceiver(C2SToolBridgePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] S->C2SToolBridge received -> player={}, requestId={}, tool={}, argsLen={}", player.getGameProfile().getName(), payload.requestId(), payload.toolName(), payload.argumentsJson() == null ? 0 : payload.argumentsJson().length());
            }
            runOnPlayerServer(player, () -> SessionManager.handleToolBridgeRequest(
                    player,
                    payload.requestId(),
                    payload.toolName(),
                    payload.argumentsJson()
            ));
        });
    }

    private static void runOnPlayerServer(ServerPlayer player, Runnable task) {
        if (player == null || task == null) {
            return;
        }
        var server = player.getServer();
        if (server != null) {
            server.execute(task);
        }
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
