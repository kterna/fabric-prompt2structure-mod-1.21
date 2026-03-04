package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record C2SSessionActionPayload(String action, String payload) implements CustomPacketPayload {
    public static final Type<C2SSessionActionPayload> TYPE = new Type<>(P2SNetworkConstants.C2S_SESSION_ACTION_ID);
    public static final StreamCodec<FriendlyByteBuf, C2SSessionActionPayload> CODEC = StreamCodec.of(
            (buf, data) -> {
                buf.writeUtf(data.action, 32);
                buf.writeUtf(data.payload == null ? "" : data.payload, 65536);
            },
            buf -> new C2SSessionActionPayload(buf.readUtf(32), buf.readUtf(65536))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
