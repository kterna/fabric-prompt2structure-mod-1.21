package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CToolBridgePayload(String requestId, boolean ok, String responseJson, String error) implements CustomPacketPayload {
    public static final Type<S2CToolBridgePayload> TYPE = new Type<>(P2SNetworkConstants.S2C_TOOL_BRIDGE_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CToolBridgePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.requestId == null ? "" : payload.requestId, 128);
                buf.writeBoolean(payload.ok);
                buf.writeUtf(payload.responseJson == null ? "" : payload.responseJson, 32767);
                buf.writeUtf(payload.error == null ? "" : payload.error, 4096);
            },
            buf -> new S2CToolBridgePayload(
                    buf.readUtf(128),
                    buf.readBoolean(),
                    buf.readUtf(32767),
                    buf.readUtf(4096)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
