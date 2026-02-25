package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record C2SSetSelectionPayload(int pointIndex, BlockPos pos) implements CustomPacketPayload {
    public static final Type<C2SSetSelectionPayload> TYPE = new Type<>(P2SNetworkConstants.C2S_SET_SELECTION_ID);
    public static final StreamCodec<FriendlyByteBuf, C2SSetSelectionPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.pointIndex);
                buf.writeBlockPos(payload.pos);
            },
            buf -> new C2SSetSelectionPayload(buf.readVarInt(), buf.readBlockPos())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
