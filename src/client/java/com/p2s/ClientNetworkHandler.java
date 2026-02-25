package com.p2s;

import com.p2s.network.S2CBuildProgressPayload;
import com.p2s.network.S2CChatResponsePayload;
import com.p2s.network.S2CSelectionSyncPayload;
import com.p2s.network.S2CSessionSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientNetworkHandler {
    private ClientNetworkHandler() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(S2CSelectionSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientSelectionManager.onSyncFromServer(payload.pos1(), payload.pos2())));

        ClientPlayNetworking.registerGlobalReceiver(S2CChatResponsePayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientSessionState.onChatResponse(payload.assistantText(), payload.hasStructure(), payload.status())));

        ClientPlayNetworking.registerGlobalReceiver(S2CSessionSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientSessionState.onSessionSync(
                        payload.active(),
                        payload.sessionId(),
                        payload.turnCount(),
                        payload.partCount(),
                        payload.totalBlocks(),
                        payload.partsSummary()
                )));

        ClientPlayNetworking.registerGlobalReceiver(S2CBuildProgressPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientSessionState.onBuildProgress(
                        payload.phase(),
                        payload.currentPart(),
                        payload.progress(),
                        payload.blocksPlaced()
                )));
    }
}
