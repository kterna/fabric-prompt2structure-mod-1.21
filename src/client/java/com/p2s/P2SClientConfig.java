package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.nio.file.Files;
import java.nio.file.Path;

public final class P2SClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("p2s_client.json");
    private static final String DEFAULT_SELECTION_ITEM_ID = "minecraft:spectral_arrow";

    private static String selectionItemId = DEFAULT_SELECTION_ITEM_ID;

    private P2SClientConfig() {
    }

    public static synchronized void reload() {
        load();
    }

    public static synchronized String getSelectionItemId() {
        return selectionItemId;
    }

    public static synchronized boolean setSelectionItemId(String rawItemId, boolean persist) {
        String normalized = normalizeItemId(rawItemId);
        if (normalized == null) {
            return false;
        }
        selectionItemId = normalized;
        if (persist) {
            save();
        }
        return true;
    }

    public static String defaultSelectionItemId() {
        return DEFAULT_SELECTION_ITEM_ID;
    }

    public static boolean isSelectionItem(Item item) {
        if (item == null) {
            return false;
        }
        String currentId;
        synchronized (P2SClientConfig.class) {
            currentId = selectionItemId;
        }
        ResourceLocation id = ResourceLocation.tryParse(currentId);
        if (id == null) {
            return false;
        }
        Item target = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return target != null && target == item;
    }

    private static void load() {
        String loaded = null;
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
                loaded = root.has("selectionItem") ? root.get("selectionItem").getAsString() : null;
            }
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed reading client config {}, using defaults: {}", CONFIG_PATH, e.getMessage());
        }

        String normalized = normalizeItemId(loaded);
        if (normalized == null) {
            normalized = DEFAULT_SELECTION_ITEM_ID;
        }
        selectionItemId = normalized;
        save();
    }

    private static void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            root.addProperty("selectionItem", selectionItemId);
            Files.writeString(CONFIG_PATH, GSON.toJson(root));
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed writing client config {}: {}", CONFIG_PATH, e.getMessage());
        }
    }

    private static String normalizeItemId(String rawItemId) {
        if (rawItemId == null || rawItemId.isBlank()) {
            return null;
        }
        String trimmed = rawItemId.trim().toLowerCase();
        ResourceLocation id = ResourceLocation.tryParse(trimmed);
        if (id == null) {
            id = ResourceLocation.tryParse("minecraft:" + trimmed);
        }
        if (id == null) {
            return null;
        }
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        return id.toString();
    }
}
