package com.p2s;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class P2SConfigScreen extends Screen {
    private final Screen parent;
    private EditBox selectionItemInput;
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    public P2SConfigScreen(Screen parent) {
        super(Component.literal("P2S Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int panelWidth = Math.min(420, this.width - 32);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(32, this.height / 4);

        selectionItemInput = new EditBox(this.font, left, top + 46, panelWidth, 20, Component.literal("selection item"));
        selectionItemInput.setMaxLength(128);
        selectionItemInput.setValue(P2SClientConfig.getSelectionItemId());
        selectionItemInput.setFocused(true);
        addRenderableWidget(selectionItemInput);

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> saveSelectionItem())
                .bounds(left, top + 78, 80, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Reset Default"), btn -> resetSelectionItem())
                .bounds(left + 88, top + 78, 120, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(left, top + 132, 80, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("LLM"), btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new P2SClientLLMConfigScreen(this));
                    }
                })
                .bounds(left + panelWidth / 2 - 28, top + 132, 56, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Skills"), btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new P2SSkillConfigScreen(this));
                    }
                })
                .bounds(left + panelWidth - 80, top + 132, 80, 20)
                .build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (selectionItemInput != null && selectionItemInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (selectionItemInput != null && selectionItemInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(420, this.width - 32);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(32, this.height / 4);

        gfx.drawString(this.font, this.title, left, top, 0xFFFFFF, true);
        gfx.drawString(this.font, "Selection tool item id", left, top + 30, 0xCCCCCC, false);
        gfx.drawString(this.font, "Default: " + P2SClientConfig.defaultSelectionItemId(), left, top + 58, 0x888888, false);
        gfx.drawString(this.font, "Skills are stored under config/p2s_skills/ (client-global)", left, top + 92, 0x888888, false);
        gfx.drawString(this.font, "LLM apiUrl/apiKey/model are in p2s_client.json (LLM button)", left, top + 104, 0x888888, false);
        if (!statusText.isBlank()) {
            gfx.drawString(this.font, statusText, left, top + 118, statusColor, false);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void saveSelectionItem() {
        if (selectionItemInput == null) {
            return;
        }
        String inputValue = selectionItemInput.getValue();
        if (P2SClientConfig.setSelectionItemId(inputValue, true)) {
            selectionItemInput.setValue(P2SClientConfig.getSelectionItemId());
            statusText = "Saved";
            statusColor = 0x55FF55;
        } else {
            statusText = "Invalid item id";
            statusColor = 0xFF5555;
        }
    }

    private void resetSelectionItem() {
        P2SClientConfig.setSelectionItemId(P2SClientConfig.defaultSelectionItemId(), true);
        if (selectionItemInput != null) {
            selectionItemInput.setValue(P2SClientConfig.getSelectionItemId());
        }
        statusText = "Reset to default";
        statusColor = 0x55FF55;
    }
}
