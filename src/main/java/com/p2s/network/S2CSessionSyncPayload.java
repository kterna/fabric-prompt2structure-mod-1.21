package com.p2s.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.p2s.P2SNetworkConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record S2CSessionSyncPayload(
        boolean hasProject,
        boolean sessionActive,
        String sessionId,
        String projectId,
        String projectName,
        String projectDescription,
        int originX,
        int originY,
        int originZ,
        boolean hasSize,
        int sizeX,
        int sizeY,
        int sizeZ,
        String selectedWorkspacePath,
        int partCount,
        int totalBlocks,
        String partsSummary,
        String structureSummary,
        String runtimeState,
        String revision,
        boolean hasPendingPatch,
        String pendingPath,
        String pendingSummary,
        String pendingRisk,
        int pendingChangedBlocks,
        String checkpointsJson,
        String currentScriptJson,
        String workspaceFilesJson
) implements CustomPacketPayload {
    public static final Type<S2CSessionSyncPayload> TYPE = new Type<>(P2SNetworkConstants.S2C_SESSION_SYNC_ID);
    public static final StreamCodec<FriendlyByteBuf, S2CSessionSyncPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.hasProject);
                buf.writeBoolean(payload.sessionActive);
                buf.writeUtf(payload.sessionId == null ? "" : payload.sessionId, 64);
                buf.writeUtf(payload.projectId == null ? "" : payload.projectId, 128);
                buf.writeUtf(payload.projectName == null ? "" : payload.projectName, 256);
                buf.writeUtf(fitDisplayText(payload.projectDescription, 1024), 1024);
                buf.writeInt(payload.originX);
                buf.writeInt(payload.originY);
                buf.writeInt(payload.originZ);
                buf.writeBoolean(payload.hasSize);
                buf.writeInt(payload.sizeX);
                buf.writeInt(payload.sizeY);
                buf.writeInt(payload.sizeZ);
                buf.writeUtf(payload.selectedWorkspacePath == null ? "" : payload.selectedWorkspacePath, 256);
                buf.writeVarInt(payload.partCount);
                buf.writeVarInt(payload.totalBlocks);
                buf.writeUtf(fitDisplayText(payload.partsSummary, 1024), 1024);
                buf.writeUtf(fitDisplayText(payload.structureSummary, 8192), 8192);
                buf.writeUtf(payload.runtimeState == null ? "" : payload.runtimeState, 64);
                buf.writeUtf(payload.revision == null ? "" : payload.revision, 128);
                buf.writeBoolean(payload.hasPendingPatch);
                buf.writeUtf(payload.pendingPath == null ? "" : payload.pendingPath, 256);
                buf.writeUtf(fitDisplayText(payload.pendingSummary, 2048), 2048);
                buf.writeUtf(payload.pendingRisk == null ? "" : payload.pendingRisk, 32);
                buf.writeVarInt(payload.pendingChangedBlocks);
                buf.writeUtf(encodeLargeJson(payload.checkpointsJson, 16384, "[]"), 16384);
                buf.writeUtf(encodeLargeJson(payload.currentScriptJson, 65536, ""), 65536);
                buf.writeUtf(encodeLargeJson(payload.workspaceFilesJson, 32767, "[]"), 32767);
            },
            buf -> new S2CSessionSyncPayload(
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readUtf(64),
                    buf.readUtf(128),
                    buf.readUtf(256),
                    buf.readUtf(1024),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(256),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(1024),
                    buf.readUtf(8192),
                    buf.readUtf(64),
                    buf.readUtf(128),
                    buf.readBoolean(),
                    buf.readUtf(256),
                    buf.readUtf(2048),
                    buf.readUtf(32),
                    buf.readVarInt(),
                    decodeLargeJson(buf.readUtf(16384), "[]"),
                    decodeLargeJson(buf.readUtf(65536), ""),
                    decodeLargeJson(buf.readUtf(32767), "[]")
            )
    );



    private static final String COMPRESSED_PREFIX = "@p2s-gzip@";

    private static String fitDisplayText(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, Math.max(0, maxLength));
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private static String encodeLargeJson(String value, int maxLength, String fallback) {
        String normalized = value == null ? fallback : value;
        if (normalized == null || normalized.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        String compressed = compressToTaggedBase64(normalized);
        if (compressed != null && compressed.length() <= maxLength) {
            return compressed;
        }
        return fallback == null ? "" : fallback;
    }

    private static String decodeLargeJson(String value, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        if (!value.startsWith(COMPRESSED_PREFIX)) {
            return value;
        }
        String decoded = decompressTaggedBase64(value);
        if (decoded == null) {
            return fallback == null ? "" : fallback;
        }
        return decoded;
    }

    private static String compressToTaggedBase64(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(value.getBytes(StandardCharsets.UTF_8));
            }
            return COMPRESSED_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String decompressTaggedBase64(String value) {
        if (value == null || !value.startsWith(COMPRESSED_PREFIX)) {
            return null;
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(value.substring(COMPRESSED_PREFIX.length()));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
