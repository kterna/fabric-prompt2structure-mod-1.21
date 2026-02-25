package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CBuildProgressPayload(String phase, String currentPart, int progress, int blocksPlaced) implements CustomPacketPayload {
    public static final Type<S2CBuildProgressPayload> TYPE = new Type<>(P2SNetworkConstants.S2C_BUILD_PROGRESS_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CBuildProgressPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.phase == null ? "" : payload.phase, 32);
                buf.writeUtf(payload.currentPart == null ? "" : payload.currentPart, 64);
                buf.writeVarInt(payload.progress);
                buf.writeVarInt(payload.blocksPlaced);
            },
            buf -> new S2CBuildProgressPayload(
                    buf.readUtf(32),
                    buf.readUtf(64),
                    buf.readVarInt(),
                    buf.readVarInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
