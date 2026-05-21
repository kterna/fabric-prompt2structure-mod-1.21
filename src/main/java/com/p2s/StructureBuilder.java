package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StructureBuilder {
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_SIGN_LINES = 4;
    private static final int MAX_SIGN_LINE_CHARS = 80;
    private static final int MAX_BANNER_PATTERN_LAYERS = 6;
    private static final Set<String> SUPPORTED_DYE_COLORS = new LinkedHashSet<>(List.of(
            "white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
    ));
    private static final Set<String> SUPPORTED_BANNER_PATTERNS = new LinkedHashSet<>(List.of(
            "base",
            "square_bottom_left", "square_bottom_right", "square_top_left", "square_top_right",
            "stripe_bottom", "stripe_top", "stripe_left", "stripe_right", "stripe_center", "stripe_middle",
            "stripe_downright", "stripe_downleft", "small_stripes",
            "cross", "straight_cross", "triangle_bottom", "triangle_top", "triangles_bottom", "triangles_top",
            "diagonal_left", "diagonal_up_right", "diagonal_up_left", "diagonal_right",
            "circle", "rhombus", "half_vertical", "half_horizontal", "half_vertical_right", "half_horizontal_bottom",
            "border", "curly_border", "gradient", "gradient_up", "bricks",
            "globe", "creeper", "skull", "flower", "mojang", "piglin", "flow", "guster"
    ));
    private static final Set<String> SUPPORTED_BANNER_LAYER_PATTERNS = supportedBannerLayerPatternSet();

    private StructureBuilder() {
    }

    public static VbsScript parse(String json) {
        try {
            return GSON.fromJson(json, VbsScript.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("无法解析 VBS JSON", e);
        }
    }

    public static VbsScriptV2 parseV2(String json) {
        try {
            return GSON.fromJson(json, VbsScriptV2.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("无法解析 VBS V2 JSON", e);
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
                executeAction(world, origin, palette, missingPaletteKeys, mutable, action);
            }
        }
    }

    public static void buildV2(ServerLevel world, BlockPos origin, VbsScriptV2 script) {
        if (script == null || script.structures == null) {
            throw new IllegalArgumentException("结构数据为空");
        }

        P2SMod.LOGGER.info("Building V2 structure at {} with {} parts", origin, script.structures.size());
        Map<String, BlockState> palette = resolvePalette(script.palette);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        Set<String> missingPaletteKeys = new HashSet<>();

        List<StructurePart> sorted = new ArrayList<>(script.structures);
        sorted.sort(Comparator.comparingInt(part -> part == null ? 0 : part.priority));

        for (StructurePart part : sorted) {
            if (part == null || part.actions == null) {
                continue;
            }
            for (VbsAction action : part.actions) {
                if (action == null || action.type == null) {
                    continue;
                }
                executeAction(world, origin, palette, missingPaletteKeys, mutable, action);
            }
        }
    }

    public static VbsScriptV2 mergeScripts(VbsScriptV2 base, VbsScriptV2 delta) {
        if (base == null) {
            return delta;
        }
        if (delta == null) {
            return base;
        }

        VbsScriptV2 merged = new VbsScriptV2();

        if (base.palette != null) {
            merged.palette.putAll(base.palette);
        }
        if (delta.palette != null) {
            merged.palette.putAll(delta.palette);
        }

        Map<String, StructurePart> partMap = new LinkedHashMap<>();
        if (base.structures != null) {
            for (StructurePart part : base.structures) {
                if (part != null && part.name != null) {
                    partMap.put(part.name, part);
                }
            }
        }
        if (delta.structures != null) {
            for (StructurePart part : delta.structures) {
                if (part != null && part.name != null) {
                    partMap.put(part.name, part);
                }
            }
        }
        merged.structures = new ArrayList<>(partMap.values());
        return merged;
    }

    public static VbsScriptV2 fromV1(VbsScript v1) {
        if (v1 == null) {
            return null;
        }
        VbsScriptV2 v2 = new VbsScriptV2();
        if (v1.palette != null) {
            v2.palette.putAll(v1.palette);
        }
        if (v1.structure != null) {
            for (int i = 0; i < v1.structure.size(); i++) {
                VbsLayer layer = v1.structure.get(i);
                if (layer == null) {
                    continue;
                }
                StructurePart part = new StructurePart();
                part.name = "layer_" + i;
                part.priority = i * 10;
                part.actions = layer.actions;
                v2.structures.add(part);
            }
        }
        return v2;
    }

    public static VbsScript toV1(VbsScriptV2 v2) {
        if (v2 == null) {
            return null;
        }
        VbsScript v1 = new VbsScript();
        if (v2.palette != null) {
            v1.palette.putAll(v2.palette);
        }
        if (v2.structures != null) {
            List<StructurePart> parts = new ArrayList<>(v2.structures);
            parts.sort(Comparator.comparingInt(part -> part == null ? 0 : part.priority));
            for (StructurePart part : parts) {
                if (part == null) {
                    continue;
                }
                VbsLayer layer = new VbsLayer();
                layer.actions = part.actions;
                v1.structure.add(layer);
            }
        }
        return v1;
    }

    public static Map<String, BlockState> resolvePaletteStates(Map<String, String> paletteDef) {
        return resolvePalette(paletteDef);
    }

    public static BlockState resolvePaletteBlockState(String rawId, String paletteKey) {
        return resolveBlockState(rawId, paletteKey);
    }

    public static BlockState resolveDirectBlockState(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return resolveBlockStateExpression(rawId);
    }

    public static List<String> searchBlockIds(String query, int limit) {
        String normalized = normalizeBlockQuery(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), 50);
        String normalizedNoNs = normalized.startsWith("minecraft:") ? normalized.substring(10) : normalized;
        String[] tokens = normalizedNoNs.split("[^a-z0-9_]+");

        List<BlockMatch> matches = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            String idStr = id.toString().toLowerCase();
            String path = id.getPath().toLowerCase();
            int score = scoreBlockCandidate(normalized, normalizedNoNs, tokens, idStr, path);
            if (score == Integer.MAX_VALUE) {
                continue;
            }
            matches.add(new BlockMatch(id.toString(), score));
        }

        matches.sort(Comparator.comparingInt((BlockMatch m) -> m.score).thenComparing(m -> m.id));
        List<String> out = new ArrayList<>();
        int size = Math.min(capped, matches.size());
        for (int i = 0; i < size; i++) {
            out.add(matches.get(i).id);
        }
        return out;
    }

    public static String closestBlockId(String query) {
        String normalized = normalizeBlockQuery(query);
        if (normalized.isBlank()) {
            return "";
        }
        ResourceLocation closest = findClosestBlock(normalized);
        return closest == null ? "" : closest.toString();
    }

    public static BlockState applyFacingState(BlockState state, String facing) {
        return applyFacing(state, facing);
    }

    public static Set<String> supportedDyeColors() {
        return SUPPORTED_DYE_COLORS;
    }

    public static Set<String> supportedBannerPatterns() {
        return SUPPORTED_BANNER_PATTERNS;
    }

    public static Set<String> supportedBannerLayerPatterns() {
        return SUPPORTED_BANNER_LAYER_PATTERNS;
    }

    public static int maxSignLines() {
        return MAX_SIGN_LINES;
    }

    public static int maxSignLineChars() {
        return MAX_SIGN_LINE_CHARS;
    }

    public static int maxBannerPatternLayers() {
        return MAX_BANNER_PATTERN_LAYERS;
    }

    public static boolean isSignBlockState(BlockState state) {
        String path = blockPath(state);
        return path.endsWith("_sign");
    }

    public static boolean isBannerBlockState(BlockState state) {
        String path = blockPath(state);
        return path.endsWith("_banner");
    }

    public static boolean supportsSignTextTemplate(BlockState state) {
        return isSignBlockState(state);
    }

    public static boolean supportsBannerPatternsTemplate(BlockState state) {
        return isBannerBlockState(state);
    }

    public static List<String> supportedBlockEntityTemplates(BlockState state) {
        List<String> templates = new ArrayList<>();
        if (supportsSignTextTemplate(state)) {
            templates.add("sign_text");
        }
        if (supportsBannerPatternsTemplate(state)) {
            templates.add("banner_patterns");
        }
        return templates;
    }

    public static BlockState resolveActionBlockState(Map<String, BlockState> palette, VbsAction action) {
        return getState(palette == null ? Map.of() : palette, new HashSet<>(), action == null ? null : action.block, action == null ? null : action.facing);
    }

    public static List<PlacementState> expandedPlacementStates(BlockState state) {
        if (state == null) {
            return List.of();
        }
        if (isDoorState(state)) {
            return List.of(
                    new PlacementState(0, 0, 0, setPropertyByName(state, "half", "lower")),
                    new PlacementState(0, 1, 0, setPropertyByName(state, "half", "upper"))
            );
        }
        if (isBedState(state)) {
            Direction facing = directionPropertyValue(state, "facing");
            if (facing != null && facing != Direction.UP && facing != Direction.DOWN) {
                return List.of(
                        new PlacementState(0, 0, 0, setPropertyByName(state, "part", "foot")),
                        new PlacementState(facing.getStepX(), 0, facing.getStepZ(), setPropertyByName(state, "part", "head"))
                );
            }
        }
        return List.of(new PlacementState(0, 0, 0, state));
    }

    public static boolean hasBlockEntityTemplate(VbsAction action) {
        return action != null && action.blockEntity != null && !action.blockEntity.isBlank();
    }

    public static List<String> validateBlockEntityTemplate(VbsAction action, BlockState state) {
        if (!hasBlockEntityTemplate(action)) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        String template = normalize(action.blockEntity);
        switch (template) {
            case "sign_text" -> {
                if (!supportsSignTextTemplate(state)) {
                    errors.add("block_entity=sign_text requires a sign or hanging sign block state");
                }
                if (action.bannerPatterns != null) {
                    errors.add("banner_patterns requires block_entity=banner_patterns");
                }
                validateSignLines(action.signFront, "sign_front", errors);
                validateSignLines(action.signBack, "sign_back", errors);
                validateDyeColor(action.signColor, "sign_color", "black", errors);
            }
            case "banner_patterns" -> {
                if (!supportsBannerPatternsTemplate(state)) {
                    errors.add("block_entity=banner_patterns requires a banner or wall banner block state");
                }
                if (action.signFront != null
                        || action.signBack != null
                        || (action.signColor != null && !action.signColor.isBlank())
                        || action.signGlowing != null
                        || action.signWaxed != null) {
                    errors.add("sign_* fields require block_entity=sign_text");
                }
                validateBannerPatterns(action.bannerPatterns, errors);
            }
            default -> errors.add("unsupported block_entity template '" + action.blockEntity + "'");
        }
        return errors;
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

    private static void executeAction(ServerLevel world, BlockPos origin, Map<String, BlockState> palette,
                                      Set<String> missingPaletteKeys, BlockPos.MutableBlockPos mutable, VbsAction action) {
        String type = normalize(action.type);
        BlockState state = getState(palette, missingPaletteKeys, action.block, action.facing);
        switch (type) {
            case "box" -> handleBox(world, origin, mutable, state, action);
            case "plane" -> handlePlane(world, origin, mutable, state, action);
            case "line" -> handleLine(world, origin, mutable, state, action);
            case "points" -> handlePoints(world, origin, mutable, state, action);
            case "fill", "frame", "set" -> throw new IllegalArgumentException(
                    "Legacy action type '" + type + "' is no longer supported. Use box/plane/line/points."
            );
            default -> throw new IllegalArgumentException("Unsupported action type: " + action.type);
        }
    }

    private static void handleBox(ServerLevel world, BlockPos origin, BlockPos.MutableBlockPos mutable,
                                  BlockState state, VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            throw new IllegalArgumentException("box action requires from/to");
        }
        int[] bounds = bounds(from, to);
        int minX = bounds[0];
        int minY = bounds[1];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxY = bounds[4];
        int maxZ = bounds[5];

        String mode = normalize(action.mode);
        if (mode.isBlank()) {
            mode = "solid";
        }
        switch (mode) {
            case "solid" -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            place(world, origin, mutable, state, action, x, y, z);
                        }
                    }
                }
            }
            case "shell" -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                            if (!boundary) {
                                continue;
                            }
                            place(world, origin, mutable, state, action, x, y, z);
                        }
                    }
                }
            }
            case "walls" -> {
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            boolean boundary = x == minX || x == maxX || z == minZ || z == maxZ;
                            if (!boundary) {
                                continue;
                            }
                            place(world, origin, mutable, state, action, x, y, z);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported box mode: " + action.mode);
        }
    }

    private static void handlePlane(ServerLevel world, BlockPos origin, BlockPos.MutableBlockPos mutable,
                                    BlockState state, VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            throw new IllegalArgumentException("plane action requires from/to");
        }
        int axisIdx = axisIndex(action.axis);
        if (axisIdx < 0) {
            throw new IllegalArgumentException("plane action requires axis=x|y|z");
        }
        if (from[axisIdx] != to[axisIdx]) {
            throw new IllegalArgumentException("plane action requires from/to with same coordinate on axis=" + normalize(action.axis));
        }
        int[] bounds = bounds(from, to);
        int minX = bounds[0];
        int minY = bounds[1];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxY = bounds[4];
        int maxZ = bounds[5];

        String mode = normalize(action.mode);
        if (mode.isBlank()) {
            mode = "solid";
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!isOnAxisPlane(axisIdx, from, x, y, z)) {
                        continue;
                    }
                    if ("outline".equals(mode)) {
                        boolean boundary;
                        if (axisIdx == 0) {
                            boundary = y == minY || y == maxY || z == minZ || z == maxZ;
                        } else if (axisIdx == 1) {
                            boundary = x == minX || x == maxX || z == minZ || z == maxZ;
                        } else {
                            boundary = x == minX || x == maxX || y == minY || y == maxY;
                        }
                        if (!boundary) {
                            continue;
                        }
                    } else if (!"solid".equals(mode)) {
                        throw new IllegalArgumentException("Unsupported plane mode: " + action.mode);
                    }
                    place(world, origin, mutable, state, action, x, y, z);
                }
            }
        }
    }

    private static void handleLine(ServerLevel world, BlockPos origin, BlockPos.MutableBlockPos mutable,
                                   BlockState state, VbsAction action) {
        int[] from = coords(action.from);
        int[] to = coords(action.to);
        if (from == null || to == null) {
            throw new IllegalArgumentException("line action requires from/to");
        }
        drawLine(from[0], from[1], from[2], to[0], to[1], to[2], (x, y, z) -> place(world, origin, mutable, state, action, x, y, z));
    }

    private static void handlePoints(ServerLevel world, BlockPos origin, BlockPos.MutableBlockPos mutable,
                                     BlockState state, VbsAction action) {
        if (action.at == null) {
            throw new IllegalArgumentException("points action requires at");
        }
        for (List<Integer> point : action.at) {
            int[] c = coords(point);
            if (c == null) {
                continue;
            }
            place(world, origin, mutable, state, action, c[0], c[1], c[2]);
        }
    }

    private static boolean isOnAxisPlane(int axisIdx, int[] from, int x, int y, int z) {
        return switch (axisIdx) {
            case 0 -> x == from[0];
            case 1 -> y == from[1];
            case 2 -> z == from[2];
            default -> false;
        };
    }

    private static int axisIndex(String axis) {
        String v = normalize(axis);
        return switch (v) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> -1;
        };
    }

    private static int[] bounds(int[] from, int[] to) {
        return new int[]{
                Math.min(from[0], to[0]),
                Math.min(from[1], to[1]),
                Math.min(from[2], to[2]),
                Math.max(from[0], to[0]),
                Math.max(from[1], to[1]),
                Math.max(from[2], to[2])
        };
    }

    private static void place(ServerLevel world, BlockPos origin, BlockPos.MutableBlockPos mutable,
                              BlockState state, VbsAction action, int x, int y, int z) {
        for (PlacementState placement : expandedPlacementStates(state)) {
            mutable.set(origin.getX() + x + placement.dx(), origin.getY() + y + placement.dy(), origin.getZ() + z + placement.dz());
            world.setBlockAndUpdate(mutable, placement.state());
            if (placement.dx() == 0 && placement.dy() == 0 && placement.dz() == 0) {
                applyBlockEntityTemplate(world, mutable.immutable(), placement.state(), action);
            }
        }
    }

    private static void applyBlockEntityTemplate(ServerLevel world, BlockPos pos, BlockState state, VbsAction action) {
        if (!hasBlockEntityTemplate(action)) {
            return;
        }
        List<String> errors = validateBlockEntityTemplate(action, state);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        String template = normalize(action.blockEntity);
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null) {
            throw new IllegalArgumentException("Block entity template '" + template + "' has no block entity at " + pos);
        }
        switch (template) {
            case "sign_text" -> applySignTextTemplate(world, pos, state, blockEntity, action);
            case "banner_patterns" -> applyBannerPatternsTemplate(world, pos, state, blockEntity, action);
            default -> throw new IllegalArgumentException("Unsupported block_entity template: " + action.blockEntity);
        }
    }

    private static void applySignTextTemplate(ServerLevel world, BlockPos pos, BlockState state, BlockEntity blockEntity, VbsAction action) {
        if (!(blockEntity instanceof SignBlockEntity sign)) {
            throw new IllegalArgumentException("block_entity=sign_text target is not a sign block entity");
        }
        DyeColor color = dyeColor(action.signColor, DyeColor.BLACK);
        boolean glowing = Boolean.TRUE.equals(action.signGlowing);
        sign.setText(signText(action.signFront, color, glowing), true);
        if (action.signBack != null) {
            sign.setText(signText(action.signBack, color, glowing), false);
        }
        sign.setWaxed(Boolean.TRUE.equals(action.signWaxed));
        sign.setChanged();
        world.sendBlockUpdated(pos, state, state, 3);
    }

    private static SignText signText(List<String> lines, DyeColor color, boolean glowing) {
        Component[] messages = new Component[MAX_SIGN_LINES];
        for (int i = 0; i < MAX_SIGN_LINES; i++) {
            String line = lines != null && i < lines.size() && lines.get(i) != null ? lines.get(i) : "";
            messages[i] = Component.literal(truncate(line, MAX_SIGN_LINE_CHARS));
        }
        return new SignText(messages, messages, color, glowing);
    }

    private static void applyBannerPatternsTemplate(ServerLevel world, BlockPos pos, BlockState state, BlockEntity blockEntity, VbsAction action) {
        if (!(blockEntity instanceof BannerBlockEntity banner)) {
            throw new IllegalArgumentException("block_entity=banner_patterns target is not a banner block entity");
        }
        Registry<BannerPattern> registry = world.registryAccess().registryOrThrow(Registries.BANNER_PATTERN);
        List<BannerPatternLayers.Layer> layers = new ArrayList<>();
        if (action.bannerPatterns != null) {
            for (String rawLayer : action.bannerPatterns) {
                BannerLayerSpec spec = parseBannerLayer(rawLayer);
                if (spec == null) {
                    continue;
                }
                ResourceKey<BannerPattern> key = ResourceKey.create(
                        Registries.BANNER_PATTERN,
                        ResourceLocation.fromNamespaceAndPath("minecraft", spec.pattern())
                );
                layers.add(new BannerPatternLayers.Layer(registry.getHolderOrThrow(key), dyeColor(spec.color(), DyeColor.WHITE)));
            }
        }
        DataComponentPatch patch = DataComponentPatch.builder()
                .set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(layers))
                .build();
        banner.applyComponents(DataComponentMap.EMPTY, patch);
        banner.setChanged();
        world.sendBlockUpdated(pos, state, state, 3);
    }

    private interface PointVisitor {
        void visit(int x, int y, int z);
    }

    private static void drawLine(int x1, int y1, int z1, int x2, int y2, int z2, PointVisitor visitor) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);
        int xs = x2 >= x1 ? 1 : -1;
        int ys = y2 >= y1 ? 1 : -1;
        int zs = z2 >= z1 ? 1 : -1;

        visitor.visit(x1, y1, z1);
        if (dx >= dy && dx >= dz) {
            int p1 = 2 * dy - dx;
            int p2 = 2 * dz - dx;
            while (x1 != x2) {
                x1 += xs;
                if (p1 >= 0) {
                    y1 += ys;
                    p1 -= 2 * dx;
                }
                if (p2 >= 0) {
                    z1 += zs;
                    p2 -= 2 * dx;
                }
                p1 += 2 * dy;
                p2 += 2 * dz;
                visitor.visit(x1, y1, z1);
            }
            return;
        }
        if (dy >= dx && dy >= dz) {
            int p1 = 2 * dx - dy;
            int p2 = 2 * dz - dy;
            while (y1 != y2) {
                y1 += ys;
                if (p1 >= 0) {
                    x1 += xs;
                    p1 -= 2 * dy;
                }
                if (p2 >= 0) {
                    z1 += zs;
                    p2 -= 2 * dy;
                }
                p1 += 2 * dx;
                p2 += 2 * dz;
                visitor.visit(x1, y1, z1);
            }
            return;
        }
        int p1 = 2 * dy - dz;
        int p2 = 2 * dx - dz;
        while (z1 != z2) {
            z1 += zs;
            if (p1 >= 0) {
                y1 += ys;
                p1 -= 2 * dz;
            }
            if (p2 >= 0) {
                x1 += xs;
                p2 -= 2 * dz;
            }
            p1 += 2 * dy;
            p2 += 2 * dx;
            visitor.visit(x1, y1, z1);
        }
    }

    private static int[] coords(List<Integer> list) {
        if (list == null || list.size() < 3) {
            return null;
        }
        return new int[]{list.get(0), list.get(1), list.get(2)};
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }

    private static BlockState getState(Map<String, BlockState> palette, Set<String> missingPaletteKeys, String key, String facing) {
        if (key != null && palette.containsKey(key)) {
            return applyFacing(palette.get(key), facing);
        }
        if (key != null && !key.isBlank()) {
            BlockState direct = resolveDirectBlockState(key);
            if (direct != null) {
                if (missingPaletteKeys.add(key)) {
                    P2SMod.LOGGER.info("Palette key '{}' missing, used direct block id", key);
                }
                return applyFacing(direct, facing);
            }
        }
        if (missingPaletteKeys.add(String.valueOf(key))) {
            P2SMod.LOGGER.warn("Palette key '{}' missing, fallback to stone", key);
        }
        return applyFacing(Blocks.STONE.defaultBlockState(), facing);
    }

    private static BlockState resolveBlockState(String rawId, String paletteKey) {
        BlockState exact = resolveBlockStateExpression(rawId);
        if (exact != null) {
            return exact;
        }
        if (rawId != null && rawId.contains("[")) {
            P2SMod.LOGGER.warn("Palette key {} has invalid block state {}, fallback to stone", paletteKey, rawId);
            return Blocks.STONE.defaultBlockState();
        }

        String blockIdPart = blockIdPart(rawId);
        ResourceLocation id = parseBlockId(blockIdPart);

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

        ResourceLocation similar = findClosestBlock(blockIdPart);
        if (similar != null) {
            Block similarBlock = BuiltInRegistries.BLOCK.get(similar);
            P2SMod.LOGGER.warn("Palette key {} has invalid id {}, using similar {}", paletteKey, rawId, similar);
            return similarBlock.defaultBlockState();
        }

        P2SMod.LOGGER.warn("Palette key {} has invalid id {}, fallback to stone", paletteKey, rawId);
        return Blocks.STONE.defaultBlockState();
    }

    private static BlockState resolveBlockStateExpression(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String trimmed = rawId.trim().toLowerCase(Locale.ROOT);
        int bracket = trimmed.indexOf('[');
        String idPart = bracket < 0 ? trimmed : trimmed.substring(0, bracket);
        ResourceLocation id = parseBlockId(idPart);
        if (id == null) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        if (bracket < 0) {
            return state;
        }
        if (!trimmed.endsWith("]")) {
            return null;
        }
        String propertyList = trimmed.substring(bracket + 1, trimmed.length() - 1).trim();
        if (propertyList.isBlank()) {
            return null;
        }
        for (String assignment : propertyList.split(",")) {
            int equals = assignment.indexOf('=');
            if (equals <= 0 || equals == assignment.length() - 1) {
                return null;
            }
            String name = assignment.substring(0, equals).trim();
            String value = assignment.substring(equals + 1).trim();
            Property<?> property = findProperty(state, name);
            if (property == null) {
                return null;
            }
            Optional<? extends Comparable<?>> parsed = property.getValue(value);
            if (parsed.isEmpty()) {
                return null;
            }
            state = setPropertyValue(state, property, parsed.get());
        }
        return state;
    }

    private static ResourceLocation parseBlockId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String trimmed = rawId.trim().toLowerCase(Locale.ROOT);
        ResourceLocation id = ResourceLocation.tryParse(trimmed);
        if (id == null && !trimmed.contains(":")) {
            id = ResourceLocation.tryParse("minecraft:" + trimmed);
        }
        return id;
    }

    private static String blockIdPart(String rawId) {
        if (rawId == null) {
            return "";
        }
        String trimmed = rawId.trim();
        int bracket = trimmed.indexOf('[');
        return bracket < 0 ? trimmed : trimmed.substring(0, bracket);
    }

    private static boolean isDoorState(BlockState state) {
        return blockPath(state).endsWith("_door")
                && hasPropertyValue(state, "half", "lower")
                && hasPropertyValue(state, "half", "upper")
                && propertyValueEquals(state, "half", "lower");
    }

    private static boolean isBedState(BlockState state) {
        return blockPath(state).endsWith("_bed")
                && hasPropertyValue(state, "part", "foot")
                && hasPropertyValue(state, "part", "head")
                && propertyValueEquals(state, "part", "foot")
                && directionPropertyValue(state, "facing") != null;
    }

    private static String blockPath(BlockState state) {
        if (state == null) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.getPath();
    }

    private static void validateSignLines(List<String> lines, String field, List<String> errors) {
        if (lines == null) {
            return;
        }
        if (lines.size() > MAX_SIGN_LINES) {
            errors.add(field + " must contain at most " + MAX_SIGN_LINES + " lines");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                errors.add(field + "[" + i + "] must be a string");
            } else if (line.length() > MAX_SIGN_LINE_CHARS) {
                errors.add(field + "[" + i + "] exceeds " + MAX_SIGN_LINE_CHARS + " chars");
            }
        }
    }

    private static void validateDyeColor(String value, String field, String fallback, List<String> errors) {
        String color = value == null || value.isBlank() ? fallback : normalize(value);
        if (!SUPPORTED_DYE_COLORS.contains(color)) {
            errors.add(field + " must be one of: " + String.join(", ", SUPPORTED_DYE_COLORS));
        }
    }

    private static void validateBannerPatterns(List<String> layers, List<String> errors) {
        if (layers == null || layers.isEmpty()) {
            errors.add("banner_patterns requires at least one pattern:color layer");
            return;
        }
        if (layers.size() > MAX_BANNER_PATTERN_LAYERS) {
            errors.add("banner_patterns must contain at most " + MAX_BANNER_PATTERN_LAYERS + " layers");
            return;
        }
        for (int i = 0; i < layers.size(); i++) {
            BannerLayerSpec layer = parseBannerLayer(layers.get(i));
            if (layer == null) {
                errors.add("banner_patterns[" + i + "] must use pattern:color");
                continue;
            }
            if ("base".equals(layer.pattern())) {
                errors.add("banner_patterns[" + i + "] must not use base; choose the banner block color instead");
            } else if (!SUPPORTED_BANNER_LAYER_PATTERNS.contains(layer.pattern())) {
                errors.add("banner_patterns[" + i + "] has unsupported pattern '" + layer.pattern() + "'");
            }
            if (!SUPPORTED_DYE_COLORS.contains(layer.color())) {
                errors.add("banner_patterns[" + i + "] has unsupported color '" + layer.color() + "'");
            }
        }
    }

    private static BannerLayerSpec parseBannerLayer(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new BannerLayerSpec(parts[0].trim(), parts[1].trim());
    }

    private static DyeColor dyeColor(String raw, DyeColor fallback) {
        String value = raw == null || raw.isBlank() ? "" : normalize(raw);
        return DyeColor.byName(value, fallback);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || maxChars < 0 || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars);
    }

    private static Set<String> supportedBannerLayerPatternSet() {
        Set<String> patterns = new LinkedHashSet<>(SUPPORTED_BANNER_PATTERNS);
        patterns.remove("base");
        return patterns;
    }

    private static boolean hasPropertyValue(BlockState state, String name, String value) {
        Property<?> property = findProperty(state, name);
        return property != null && property.getValue(value).isPresent();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean propertyValueEquals(BlockState state, String name, String value) {
        Property property = findProperty(state, name);
        if (property == null) {
            return false;
        }
        return property.getName(state.getValue(property)).equals(value);
    }

    private static BlockState setPropertyByName(BlockState state, String name, String value) {
        Property<?> property = findProperty(state, name);
        if (property == null) {
            return state;
        }
        Optional<? extends Comparable<?>> parsed = property.getValue(value);
        return parsed.isEmpty() ? state : setPropertyValue(state, property, parsed.get());
    }

    private static Direction directionPropertyValue(BlockState state, String name) {
        Property<?> property = findProperty(state, name);
        if (property instanceof DirectionProperty directionProperty) {
            return state.getValue(directionProperty);
        }
        return null;
    }

    private static Property<?> findProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setPropertyValue(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
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

    private static final class BlockMatch {
        private final String id;
        private final int score;

        private BlockMatch(String id, int score) {
            this.id = id;
            this.score = score;
        }
    }

    private static String normalizeBlockQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().toLowerCase().replace(' ', '_');
    }

    private static int scoreBlockCandidate(String normalized, String normalizedNoNs, String[] tokens, String idStr, String path) {
        if (normalized.isBlank()) {
            return Integer.MAX_VALUE;
        }
        if (idStr.contains(normalized) || path.contains(normalized) || path.contains(normalizedNoNs)) {
            return 0;
        }
        boolean hasToken = false;
        boolean allTokens = true;
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            hasToken = true;
            if (!idStr.contains(token) && !path.contains(token)) {
                allTokens = false;
                break;
            }
        }
        if (hasToken && allTokens) {
            return 1;
        }
        int score = Math.min(levenshtein(normalizedNoNs, path), levenshtein(normalized, idStr));
        return score <= 8 ? score + 2 : Integer.MAX_VALUE;
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

    private static BlockState applyFacing(BlockState state, String facing) {
        if (facing == null || facing.isBlank()) {
            return state;
        }
        var dir = net.minecraft.core.Direction.byName(facing.trim().toLowerCase());
        if (dir == null) {
            return state;
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof DirectionProperty dirProp && dirProp.getPossibleValues().contains(dir)) {
                return state.setValue(dirProp, dir);
            }
        }
        return state;
    }

    public record PlacementState(int dx, int dy, int dz, BlockState state) {
    }

    private record BannerLayerSpec(String pattern, String color) {
    }

    public static class VbsScript {
        public Map<String, String> palette = new HashMap<>();
        public List<VbsLayer> structure = new ArrayList<>();
    }

    public static class VbsScriptV2 {
        public Map<String, String> palette = new HashMap<>();
        public List<StructurePart> structures = new ArrayList<>();
    }

    public static class StructurePart {
        public String name;
        public int priority = 0;
        public List<VbsAction> actions = new ArrayList<>();
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
        public String mode;
        public String axis;
        public String facing;

        @SerializedName("block_entity")
        public String blockEntity;

        @SerializedName("sign_front")
        public List<String> signFront;

        @SerializedName("sign_back")
        public List<String> signBack;

        @SerializedName("sign_color")
        public String signColor;

        @SerializedName("sign_glowing")
        public Boolean signGlowing;

        @SerializedName("sign_waxed")
        public Boolean signWaxed;

        @SerializedName("banner_patterns")
        public List<String> bannerPatterns;
    }
}
