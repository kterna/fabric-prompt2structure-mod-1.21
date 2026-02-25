package com.p2s;

import com.p2s.network.C2SChatMessagePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class P2SChatScreen extends Screen {
    private EditBox input;

    public P2SChatScreen() {
        super(Component.literal("P2S Chat"));
    }

    @Override
    protected void init() {
        int padding = 10;
        int inputHeight = 20;
        int inputY = this.height - inputHeight - padding;
        input = new EditBox(this.font, padding, inputY, this.width - padding * 2, inputHeight, Component.literal(""));
        input.setMaxLength(512);
        input.setFocused(true);
        addRenderableWidget(input);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sendMessage();
            return true;
        }
        if (input != null && input.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (input != null && input.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (input != null && input.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        renderBackground(gfx, mouseX, mouseY, delta);

        int padding = 10;
        int panelTop = padding;
        int panelBottom = this.height - padding * 2 - 20;
        int panelHeight = panelBottom - panelTop;
        int panelWidth = this.width - padding * 2;

        gfx.fill(padding - 2, panelTop - 2, padding + panelWidth + 2, panelTop + panelHeight + 2, 0x66000000);

        List<ClientSessionState.ChatMessage> messages = ClientSessionState.getMessages();
        int lineHeight = this.font.lineHeight + 2;
        int maxLines = panelHeight / lineHeight;
        int start = Math.max(0, messages.size() - maxLines);
        int y = panelTop;

        for (int i = start; i < messages.size(); i++) {
            ClientSessionState.ChatMessage msg = messages.get(i);
            String line = msg.role() + ": " + msg.text();
            gfx.drawString(this.font, line, padding, y, 0xFFFFFF, true);
            y += lineHeight;
        }

        if (input != null) {
            input.render(gfx, mouseX, mouseY, delta);
        }

        String status = ClientSessionState.getStatus();
        if (status != null && !status.isBlank()) {
            gfx.drawString(this.font, "Status: " + status, padding, this.height - padding - 32, 0xAAAAAA, true);
        }

        super.render(gfx, mouseX, mouseY, delta);
    }

    private void sendMessage() {
        if (input == null) {
            return;
        }
        String text = input.getValue();
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        ClientPlayNetworking.send(new C2SChatMessagePayload(text));

        ClientSessionState.addUserMessage(text);
        ClientSessionState.setStatus("thinking");
        input.setValue("");
        input.moveCursorToEnd(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
