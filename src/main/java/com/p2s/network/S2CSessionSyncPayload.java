package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CSessionSyncPayload(
        boolean hasProject,
        boolean sessionActive,
        String sessionId,
        String projectId,
        String projectName,
        String projectDescription,
        int originX,
        int originY,
        int originZ,
        boolean hasSize,
        int sizeX,
        int sizeY,
        int sizeZ,
        String selectedWorkspacePath,
        int partCount,
        int totalBlocks,
        String partsSummary,
        String structureSummary,
        String runtimeState,
        String revision,
        boolean hasPendingPatch,
        String pendingPath,
        String pendingSummary,
        String pendingRisk,
        int pendingChangedBlocks,
        String checkpointsJson,
        String currentScriptJson,
        String workspaceFilesJson
) implements CustomPacketPayload {
    public static final Type<S2CSessionSyncPayload> TYPE = new Type<>(P2SNetworkConstants.S2C_SESSION_SYNC_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CSessionSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.hasProject);
                buf.writeBoolean(payload.sessionActive);
                buf.writeUtf(payload.sessionId == null ? "" : payload.sessionId, 64);
                buf.writeUtf(payload.projectId == null ? "" : payload.projectId, 128);
                buf.writeUtf(payload.projectName == null ? "" : payload.projectName, 256);
                buf.writeUtf(payload.projectDescription == null ? "" : payload.projectDescription, 1024);
                buf.writeInt(payload.originX);
                buf.writeInt(payload.originY);
                buf.writeInt(payload.originZ);
                buf.writeBoolean(payload.hasSize);
                buf.writeInt(payload.sizeX);
                buf.writeInt(payload.sizeY);
                buf.writeInt(payload.sizeZ);
                buf.writeUtf(payload.selectedWorkspacePath == null ? "" : payload.selectedWorkspacePath, 256);
                buf.writeVarInt(payload.partCount);
                buf.writeVarInt(payload.totalBlocks);
                buf.writeUtf(payload.partsSummary == null ? "" : payload.partsSummary, 1024);
                buf.writeUtf(payload.structureSummary == null ? "" : payload.structureSummary, 8192);
                buf.writeUtf(payload.runtimeState == null ? "" : payload.runtimeState, 64);
                buf.writeUtf(payload.revision == null ? "" : payload.revision, 128);
                buf.writeBoolean(payload.hasPendingPatch);
                buf.writeUtf(payload.pendingPath == null ? "" : payload.pendingPath, 256);
                buf.writeUtf(payload.pendingSummary == null ? "" : payload.pendingSummary, 2048);
                buf.writeUtf(payload.pendingRisk == null ? "" : payload.pendingRisk, 32);
                buf.writeVarInt(payload.pendingChangedBlocks);
                buf.writeUtf(payload.checkpointsJson == null ? "[]" : payload.checkpointsJson, 16384);
                buf.writeUtf(payload.currentScriptJson == null ? "" : payload.currentScriptJson, 65536);
                buf.writeUtf(payload.workspaceFilesJson == null ? "[]" : payload.workspaceFilesJson, 32767);
            },
            buf -> new S2CSessionSyncPayload(
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readUtf(64),
                    buf.readUtf(128),
                    buf.readUtf(256),
                    buf.readUtf(1024),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(256),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(1024),
                    buf.readUtf(8192),
                    buf.readUtf(64),
                    buf.readUtf(128),
                    buf.readBoolean(),
                    buf.readUtf(256),
                    buf.readUtf(2048),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    buf.readUtf(16384),
                    buf.readUtf(65536),
                    buf.readUtf(32767)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
