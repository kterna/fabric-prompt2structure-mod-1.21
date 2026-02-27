package com.p2s.network;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CPatchPreviewPayload(boolean hasPreview, String summary, String detail, int changedBlocks, String riskLevel) implements CustomPacketPayload {
    public static final Type<S2CPatchPreviewPayload> TYPE = new Type<>(P2SNetworkConstants.S2C_PATCH_PREVIEW_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CPatchPreviewPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.hasPreview);
                buf.writeUtf(payload.summary == null ? "" : payload.summary, 2048);
                buf.writeUtf(payload.detail == null ? "" : payload.detail, 32767);
                buf.writeVarInt(payload.changedBlocks);
                buf.writeUtf(payload.riskLevel == null ? "" : payload.riskLevel, 32);
            },
            buf -> new S2CPatchPreviewPayload(
                    buf.readBoolean(),
                    buf.readUtf(2048),
                    buf.readUtf(32767),
                    buf.readVarInt(),
                    buf.readUtf(32)
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
