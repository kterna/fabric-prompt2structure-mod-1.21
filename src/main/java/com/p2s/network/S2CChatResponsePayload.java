package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CChatResponsePayload(
        String assistantText,
        boolean hasStructure,
        String status,
        String messageKey,
        String messageArgsJson
) implements CustomPacketPayload {
    public static final Type<S2CChatResponsePayload> TYPE = new Type<>(P2SNetworkConstants.S2C_CHAT_RESPONSE_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CChatResponsePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.assistantText == null ? "" : payload.assistantText, 16384);
                buf.writeBoolean(payload.hasStructure);
                buf.writeUtf(payload.status == null ? "" : payload.status, 32);
                buf.writeUtf(payload.messageKey == null ? "" : payload.messageKey, 256);
                buf.writeUtf(payload.messageArgsJson == null ? "" : payload.messageArgsJson, 4096);
            },
            buf -> new S2CChatResponsePayload(
                    buf.readUtf(16384),
                    buf.readBoolean(),
                    buf.readUtf(32),
                    buf.readUtf(256),
                    buf.readUtf(4096)
            )
    );

    public S2CChatResponsePayload(String assistantText, boolean hasStructure, String status) {
        this(assistantText, hasStructure, status, "", "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
