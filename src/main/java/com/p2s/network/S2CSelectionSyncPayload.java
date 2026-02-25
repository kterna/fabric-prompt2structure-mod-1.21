package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CSelectionSyncPayload(boolean hasPos1, BlockPos pos1, boolean hasPos2, BlockPos pos2) implements CustomPacketPayload {
    public static final Type<S2CSelectionSyncPayload> TYPE = new Type<>(P2SNetworkConstants.S2C_SELECTION_SYNC_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CSelectionSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.hasPos1);
                if (payload.hasPos1) {
                    buf.writeBlockPos(payload.pos1);
                }
                buf.writeBoolean(payload.hasPos2);
                if (payload.hasPos2) {
                    buf.writeBlockPos(payload.pos2);
                }
            },
            buf -> {
                boolean has1 = buf.readBoolean();
                BlockPos p1 = has1 ? buf.readBlockPos() : null;
                boolean has2 = buf.readBoolean();
                BlockPos p2 = has2 ? buf.readBlockPos() : null;
                return new S2CSelectionSyncPayload(has1, p1, has2, p2);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
