package com.p2s;

import com.p2s.network.P2SNetworkPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class P2SModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        P2SClientConfig.reload();
        ClientDebugGateway.startIfEnabled();
        P2SNetworkPayloads.register();
        ClientNetworkHandler.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientServerBridge.onJoin();
            ClientAgentManager.onClientJoin();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ClientServerBridge.onDisconnect();
            ClientAgentManager.onClientDisconnect();
        }));
        ModKeyBindings.registerTickHandler();
        SelectionInputHandler.register();
        SelectionRenderer.register();
        HudOverlay.register();
    }
}
