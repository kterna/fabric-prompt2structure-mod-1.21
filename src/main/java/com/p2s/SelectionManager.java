package com.p2s;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.p2s.network.S2CSelectionSyncPayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SelectionManager {
    private static final Map<UUID, Selection> serverSelections = new ConcurrentHashMap<>();

    private SelectionManager() {
    }

    public static void setPos1(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return;
        }
        if (SessionManager.hasActiveSession(player.getUUID())) {
            player.displayClientMessage(Component.literal("Selection is locked while session is active"), false);
            return;
        }
        UUID id = player.getUUID();
        Selection current = serverSelections.getOrDefault(id, new Selection(null, null));
        serverSelections.put(id, current.withPos1(pos));
        syncToClient(player);
    }

    public static void setPos2(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return;
        }
        if (SessionManager.hasActiveSession(player.getUUID())) {
            player.displayClientMessage(Component.literal("Selection is locked while session is active"), false);
            return;
        }
        UUID id = player.getUUID();
        Selection current = serverSelections.getOrDefault(id, new Selection(null, null));
        serverSelections.put(id, current.withPos2(pos));
        syncToClient(player);
    }

    public static void clear(ServerPlayer player) {
        if (player == null) {
            return;
        }
        serverSelections.remove(player.getUUID());
        syncToClient(player);
    }

    public static Selection get(UUID playerId) {
        return serverSelections.get(playerId);
    }

    public static void handleClientSelection(ServerPlayer player, int point, BlockPos pos) {
        if (player == null) {
            return;
        }
        if (point == 0) {
            setPos1(player, pos);
        } else if (point == 1) {
            setPos2(player, pos);
        } else if (point < 0) {
            clear(player);
        }
    }

    private static void syncToClient(ServerPlayer player) {
        Selection selection = serverSelections.get(player.getUUID());
        boolean hasPos1 = selection != null && selection.hasPos1();
        boolean hasPos2 = selection != null && selection.hasPos2();
        ServerNetworkHandler.sendToClient(player, new S2CSelectionSyncPayload(
                hasPos1,
                hasPos1 ? selection.pos1() : null,
                hasPos2,
                hasPos2 ? selection.pos2() : null
        ));
    }

    public record Selection(BlockPos pos1, BlockPos pos2) {
        public Selection withPos1(BlockPos pos) {
            return new Selection(pos, pos2);
        }

        public Selection withPos2(BlockPos pos) {
            return new Selection(pos1, pos);
        }

        public boolean hasPos1() {
            return pos1 != null;
        }

        public boolean hasPos2() {
            return pos2 != null;
        }

        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }

        public BlockPos min() {
            if (!isComplete()) {
                return null;
            }
            return new BlockPos(
                    Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ())
            );
        }

        public BlockPos max() {
            if (!isComplete()) {
                return null;
            }
            return new BlockPos(
                    Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ())
            );
        }

        public Vec3i size() {
            if (!isComplete()) {
                return null;
            }
            BlockPos min = min();
            BlockPos max = max();
            return new Vec3i(
                    max.getX() - min.getX() + 1,
                    max.getY() - min.getY() + 1,
                    max.getZ() - min.getZ() + 1
            );
        }
    }
}
