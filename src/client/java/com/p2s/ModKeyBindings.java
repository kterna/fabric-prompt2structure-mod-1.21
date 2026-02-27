package com.p2s;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class ModKeyBindings {
    public static final KeyMapping OPEN_CHAT = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.p2s.open_chat", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.p2s")
    );

    private ModKeyBindings() {
    }

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_CHAT.consumeClick()) {
                if (client.screen instanceof P2SChatScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new P2SChatScreen());
                }
            }
        });
    }
}
