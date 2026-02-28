package com.p2s;

import com.p2s.network.S2CBuildProgressPayload;
import com.p2s.network.S2CChatResponsePayload;
import com.p2s.network.S2CPatchPreviewPayload;
import com.p2s.network.S2CSelectionSyncPayload;
import com.p2s.network.S2CSessionSyncPayload;
import com.p2s.network.S2CToolBridgePayload;
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
                        payload.partsSummary(),
                        payload.structureSummary(),
                        payload.runtimeState(),
                        payload.revision(),
                        payload.hasPendingPatch(),
                        payload.pendingSummary(),
                        payload.pendingRisk(),
                        payload.pendingChangedBlocks()
                )));

        ClientPlayNetworking.registerGlobalReceiver(S2CBuildProgressPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientSessionState.onBuildProgress(
                        payload.phase(),
                        payload.currentPart(),
                        payload.progress(),
                        payload.blocksPlaced()
                )));

        ClientPlayNetworking.registerGlobalReceiver(S2CPatchPreviewPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientSessionState.onPatchPreview(
                        payload.hasPreview(),
                        payload.summary(),
                        payload.detail(),
                        payload.changedBlocks(),
                        payload.riskLevel()
                )));

        ClientPlayNetworking.registerGlobalReceiver(S2CToolBridgePayload.TYPE, (payload, context) ->
                context.client().execute(() -> ClientToolBridge.onToolResponse(
                        payload.requestId(),
                        payload.ok(),
                        payload.responseJson(),
                        payload.error()
                )));
    }
}
