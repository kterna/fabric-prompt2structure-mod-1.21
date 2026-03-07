package com.p2s;

import com.p2s.network.P2SNetworkPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class P2SModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        P2SClientConfig.reload();
        P2SNetworkPayloads.register();
        ClientNetworkHandler.register();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(ClientAgentManager::onClientJoin));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientAgentManager::onClientDisconnect));
        ModKeyBindings.registerTickHandler();
        SelectionInputHandler.register();
        SelectionRenderer.register();
        HudOverlay.register();
    }
}
