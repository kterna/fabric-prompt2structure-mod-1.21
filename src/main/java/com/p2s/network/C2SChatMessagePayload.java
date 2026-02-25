package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record C2SChatMessagePayload(String message) implements CustomPacketPayload {
    public static final Type<C2SChatMessagePayload> TYPE = new Type<>(P2SNetworkConstants.C2S_CHAT_MESSAGE_ID);
    public static final StreamCodec<FriendlyByteBuf, C2SChatMessagePayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeUtf(payload.message, 4096),
            buf -> new C2SChatMessagePayload(buf.readUtf(4096))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
