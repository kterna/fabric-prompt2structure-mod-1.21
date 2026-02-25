package com.p2s;

import com.p2s.network.P2SNetworkPayloads;
import net.fabricmc.api.ClientModInitializer;

public class P2SModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        P2SNetworkPayloads.register();
        ClientNetworkHandler.register();
        ModKeyBindings.registerTickHandler();
        SelectionInputHandler.register();
        SelectionRenderer.register();
        HudOverlay.register();
    }
}
