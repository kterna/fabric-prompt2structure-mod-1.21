package com.p2s;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ModKeyBindings {
    public static final KeyMapping SELECT_MODE = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.p2s.select_mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.p2s")
    );

    public static final KeyMapping OPEN_CHAT = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.p2s.open_chat", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.p2s")
    );

    private ModKeyBindings() {
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (SELECT_MODE.consumeClick()) {
                ClientSelectionManager.toggleSelectMode();
                if (client.player != null) {
                    boolean on = ClientSelectionManager.isSelectMode();
                    client.player.displayClientMessage(Component.literal("Selection mode: " + (on ? "ON" : "OFF")), true);
                }
            }
            while (OPEN_CHAT.consumeClick()) {
                client.setScreen(new P2SChatScreen());
            }
        });
    }
}
