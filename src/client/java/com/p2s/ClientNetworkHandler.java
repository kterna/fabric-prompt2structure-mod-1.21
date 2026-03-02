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
        ClientPlayNetworking.registerGlobalReceiver(S2CSelectionSyncPayload.TYPE, (payload, context) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] C->S2CSelectionSync received -> pos1={}, pos2={}", payload.pos1(), payload.pos2());
            }
            context.client().execute(() -> ClientSelectionManager.onSyncFromServer(payload.pos1(), payload.pos2()));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2CChatResponsePayload.TYPE, (payload, context) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] C->S2CChatResponse received -> textLen={}, hasStructure={}, status={}", payload.assistantText() == null ? 0 : payload.assistantText().length(), payload.hasStructure(), payload.status());
            }
            context.client().execute(() -> ClientSessionState.onChatResponse(payload.assistantText(), payload.hasStructure(), payload.status()));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2CSessionSyncPayload.TYPE, (payload, context) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] C->S2CSessionSync received -> active={}, sessionId={}, turnCount={}, partCount={}, revision={}, hasPendingPatch={}", payload.active(), payload.sessionId(), payload.turnCount(), payload.partCount(), payload.revision(), payload.hasPendingPatch());
            }
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
                    payload.pendingChangedBlocks(),
                    payload.originX(),
                    payload.originY(),
                    payload.originZ(),
                    payload.hasSize(),
                    payload.sizeX(),
                    payload.sizeY(),
                    payload.sizeZ()
            ));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2CBuildProgressPayload.TYPE, (payload, context) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] C->S2CBuildProgress received -> phase={}, currentPart={}, progress={}, blocksPlaced={}", payload.phase(), payload.currentPart(), payload.progress(), payload.blocksPlaced());
            }
            context.client().execute(() -> ClientSessionState.onBuildProgress(
                    payload.phase(),
                    payload.currentPart(),
                    payload.progress(),
                    payload.blocksPlaced()
            ));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2CPatchPreviewPayload.TYPE, (payload, context) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] C->S2CPatchPreview received -> hasPreview={}, changedBlocks={}, riskLevel={}", payload.hasPreview(), payload.changedBlocks(), payload.riskLevel());
            }
            context.client().execute(() -> ClientSessionState.onPatchPreview(
                    payload.hasPreview(),
                    payload.summary(),
                    payload.detail(),
                    payload.changedBlocks(),
                    payload.riskLevel()
            ));
        });

        ClientPlayNetworking.registerGlobalReceiver(S2CToolBridgePayload.TYPE, (payload, context) -> {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] C->S2CToolBridge received -> requestId={}, ok={}, responseLen={}, error={}", payload.requestId(), payload.ok(), payload.responseJson() == null ? 0 : payload.responseJson().length(), payload.error());
            }
            context.client().execute(() -> ClientToolBridge.onToolResponse(
                    payload.requestId(),
                    payload.ok(),
                    payload.responseJson(),
                    payload.error()
            ));
        });
    }
}
