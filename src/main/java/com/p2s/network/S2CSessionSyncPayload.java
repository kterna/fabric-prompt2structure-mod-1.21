package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CSessionSyncPayload(boolean active, String sessionId, int turnCount, int partCount, int totalBlocks, String partsSummary, String structureSummary) implements CustomPacketPayload {
    public static final Type<S2CSessionSyncPayload> TYPE = new Type<>(P2SNetworkConstants.S2C_SESSION_SYNC_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CSessionSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.active);
                buf.writeUtf(payload.sessionId == null ? "" : payload.sessionId, 64);
                buf.writeVarInt(payload.turnCount);
                buf.writeVarInt(payload.partCount);
                buf.writeVarInt(payload.totalBlocks);
                buf.writeUtf(payload.partsSummary == null ? "" : payload.partsSummary, 1024);
                buf.writeUtf(payload.structureSummary == null ? "" : payload.structureSummary, 8192);
            },
            buf -> new S2CSessionSyncPayload(
                    buf.readBoolean(),
                    buf.readUtf(64),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(1024),
                    buf.readUtf(8192)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
