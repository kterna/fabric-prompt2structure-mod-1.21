package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StructureBuilder {
    private static final Gson GSON = new GsonBuilder().create();

    private StructureBuilder() {
    }

    public static VbsScript parse(String json) {
        try {
            return GSON.fromJson(json, VbsScript.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("无法解析 VBS JSON", e);
        }
    }

    public static void build(ServerLevel world, BlockPos origin, VbsScript script) {
        if (script == null || script.structure == null) {
            throw new IllegalArgumentException("结构数据为空");
        }

        P2SMod.LOGGER.info("Building structure at {} with {} layers", origin, script.structure.size());
        Map<String, BlockState> palette = resolvePalette(script.palette);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        Set<String> missingPaletteKeys = new HashSet<>();

        for (VbsLayer layer : script.structure) {
            if (layer == null || layer.actions == null) {
                continue;
            }
            for (VbsAction action : layer.actions) {
                if (action == null || action.type == null) {
                    continue;
                }
                switch (action.type.toLowerCase()) {
                    case "fill" -> handleFill(world, origin, palette, missingPaletteKeys, mutable, action);
                    case "frame" -> handleFrame(world, origin, palette, missingPaletteKeys, mutable, action);
                    case "set" -> handleSet(world, origin, palette, missingPaletteKeys, mutable, action);
                    default -> P2SMod.LOGGER.warn("未知动作类型: {}", action.type);
                }
            }
        }
    }

    private static Map<String, BlockState> resolvePalette(Map<String, String> paletteDef) {
        Map<String, BlockState> palette = new HashMap<>();
        if (paletteDef != null) {
            for (Map.Entry<String, String> entry : paletteDef.entrySet()) {
                palette.put(entry.getKey(), resolveBlockState(entry.getValue(), entry.getKey()));
            }
        }
        return palette;
    }

    private static void handleFill(ServerLevel world, BlockPos origin, Map<String, BlockState> palette, Set<String> missingPaletteKeys, BlockPos.MutableBlockPos mutable, VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        BlockState state = getState(palette, missingPaletteKeys, action.block);

        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    world.setBlockAndUpdate(mutable, state);
                }
            }
        }
    }

    private static void handleFrame(ServerLevel world, BlockPos origin, Map<String, BlockState> palette, Set<String> missingPaletteKeys, BlockPos.MutableBlockPos mutable, VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            return;
        }
        BlockState state = getState(palette, missingPaletteKeys, action.block);

        int minX = Math.min(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxX = Math.max(from[0], to[0]);
        int maxY = Math.max(from[1], to[1]);
        int maxZ = Math.max(from[2], to[2]);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (!boundary) {
                        continue;
                    }
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    world.setBlockAndUpdate(mutable, state);
                }
            }
        }
    }

    private static void handleSet(ServerLevel world, BlockPos origin, Map<String, BlockState> palette, Set<String> missingPaletteKeys, BlockPos.MutableBlockPos mutable, VbsAction action) {
        if (action.at == null) {
            return;
        }
        BlockState state = getState(palette, missingPaletteKeys, action.block);
        for (List<Integer> point : action.at) {
            int[] coords = coords(point);
            if (coords == null) {
                continue;
            }
            mutable.set(origin.getX() + coords[0], origin.getY() + coords[1], origin.getZ() + coords[2]);
            world.setBlockAndUpdate(mutable, state);
        }
    }

    private static int[] coords(List<Integer> list) {
        if (list == null || list.size() < 3) {
            return null;
        }
        return new int[]{list.get(0), list.get(1), list.get(2)};
    }

    private static BlockState getState(Map<String, BlockState> palette, Set<String> missingPaletteKeys, String key) {
        if (key != null && palette.containsKey(key)) {
            return palette.get(key);
        }
        if (missingPaletteKeys.add(String.valueOf(key))) {
            P2SMod.LOGGER.warn("Palette key '{}' missing, fallback to stone", key);
        }
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState resolveBlockState(String rawId, String paletteKey) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null && rawId != null && !rawId.contains(":")) {
            id = ResourceLocation.tryParse("minecraft:" + rawId);
        }

        if (id != null) {
            Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block != null) {
                return block.defaultBlockState();
            }
            ResourceLocation similar = findClosestBlock(id.toString());
            if (similar != null) {
                Block similarBlock = BuiltInRegistries.BLOCK.get(similar);
                P2SMod.LOGGER.warn("Palette id {} not found, using similar {}", id, similar);
                return similarBlock.defaultBlockState();
            }
            P2SMod.LOGGER.warn("Palette id {} not found, fallback to stone", id);
            return Blocks.STONE.defaultBlockState();
        }

        ResourceLocation similar = findClosestBlock(rawId);
        if (similar != null) {
            Block similarBlock = BuiltInRegistries.BLOCK.get(similar);
            P2SMod.LOGGER.warn("Palette key {} has invalid id {}, using similar {}", paletteKey, rawId, similar);
            return similarBlock.defaultBlockState();
        }

        P2SMod.LOGGER.warn("Palette key {} has invalid id {}, fallback to stone", paletteKey, rawId);
        return Blocks.STONE.defaultBlockState();
    }

    private static ResourceLocation findClosestBlock(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String target = raw.toLowerCase();
        ResourceLocation best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ResourceLocation candidate : BuiltInRegistries.BLOCK.keySet()) {
            String candStr = candidate.toString().toLowerCase();
            String candPath = candidate.getPath().toLowerCase();
            int score = Math.min(levenshtein(target, candStr), levenshtein(target, candPath));
            if (candPath.contains(target) || candStr.contains(target)) {
                score = Math.min(score, 1); // prioritize substring matches
            }
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore <= 6 ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }

    public static class VbsScript {
        public Map<String, String> palette = new HashMap<>();
        public List<VbsLayer> structure = new ArrayList<>();
    }

    public static class VbsLayer {
        public List<VbsAction> actions = new ArrayList<>();
    }

    public static class VbsAction {
        public String type;
        public String block;
        public List<Integer> from;
        public List<Integer> to;
        public List<List<Integer>> at;
    }
}
