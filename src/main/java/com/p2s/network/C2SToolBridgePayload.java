package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record C2SToolBridgePayload(String requestId, String toolName, String argumentsJson) implements CustomPacketPayload {
    public static final Type<C2SToolBridgePayload> TYPE = new Type<>(P2SNetworkConstants.C2S_TOOL_BRIDGE_ID);
    public static final StreamCodec<FriendlyByteBuf, C2SToolBridgePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.requestId == null ? "" : payload.requestId, 128);
                buf.writeUtf(payload.toolName == null ? "" : payload.toolName, 128);
                buf.writeUtf(payload.argumentsJson == null ? "" : payload.argumentsJson, 32767);
            },
            buf -> new C2SToolBridgePayload(
                    buf.readUtf(128),
                    buf.readUtf(128),
                    buf.readUtf(32767)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
