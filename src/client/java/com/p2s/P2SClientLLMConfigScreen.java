package com.p2s;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class P2SClientLLMConfigScreen extends Screen {
    private final Screen parent;
    private EditBox apiUrlInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox timeoutInput;
    private EditBox systemPromptInput;
    private Button toolCallButton;
    private boolean useToolCall;
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    public P2SClientLLMConfigScreen(Screen parent) {
        super(Component.literal("P2S LLM Config (Client)"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int panelWidth = Math.min(760, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(16, this.height / 10);
        int inputWidth = panelWidth - 20;

        apiUrlInput = new EditBox(this.font, left + 10, top + 30, inputWidth, 20, Component.literal("api url"));
        apiUrlInput.setMaxLength(512);
        apiUrlInput.setValue(P2SClientConfig.getApiUrl());
        addRenderableWidget(apiUrlInput);

        apiKeyInput = new EditBox(this.font, left + 10, top + 62, inputWidth, 20, Component.literal("api key"));
        apiKeyInput.setMaxLength(1024);
        apiKeyInput.setValue(P2SClientConfig.getApiKey());
        addRenderableWidget(apiKeyInput);

        modelInput = new EditBox(this.font, left + 10, top + 94, inputWidth, 20, Component.literal("model"));
        modelInput.setMaxLength(256);
        modelInput.setValue(P2SClientConfig.getModel());
        addRenderableWidget(modelInput);

        timeoutInput = new EditBox(this.font, left + 10, top + 126, 120, 20, Component.literal("timeout seconds"));
        timeoutInput.setMaxLength(8);
        timeoutInput.setValue(Integer.toString(P2SClientConfig.getHttpTimeoutSeconds()));
        addRenderableWidget(timeoutInput);

        systemPromptInput = new EditBox(this.font, left + 10, top + 158, inputWidth, 20, Component.literal("system prompt"));
        systemPromptInput.setMaxLength(2048);
        systemPromptInput.setValue(P2SClientConfig.getSystemPrompt());
        addRenderableWidget(systemPromptInput);

        useToolCall = P2SClientConfig.isUseToolCall();
        toolCallButton = addRenderableWidget(Button.builder(toolCallLabel(), btn -> {
            useToolCall = !useToolCall;
            btn.setMessage(toolCallLabel());
        }).bounds(left + 140, top + 126, 160, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> saveConfig())
                .bounds(left + 10, top + 196, 80, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Reset Default"), btn -> resetDefaults())
                .bounds(left + 98, top + 196, 130, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(left + panelWidth - 80, top + 196, 70, 20)
                .build());
    }

    private Component toolCallLabel() {
        return Component.literal("useToolCall: " + (useToolCall ? "ON" : "OFF"));
    }

    private void saveConfig() {
        Integer timeout = parseTimeout(timeoutInput == null ? "" : timeoutInput.getValue());
        boolean ok = P2SClientConfig.setLlmConfig(
                apiUrlInput == null ? "" : apiUrlInput.getValue(),
                apiKeyInput == null ? "" : apiKeyInput.getValue(),
                modelInput == null ? "" : modelInput.getValue(),
                timeout,
                useToolCall,
                systemPromptInput == null ? "" : systemPromptInput.getValue(),
                true
        );
        if (ok) {
            statusText = "Saved";
            statusColor = 0x55FF55;
        } else {
            statusText = "Invalid config values";
            statusColor = 0xFF5555;
        }
    }

    private void resetDefaults() {
        P2SClientConfig.resetLlmConfigDefaults(true);
        if (apiUrlInput != null) {
            apiUrlInput.setValue(P2SClientConfig.getApiUrl());
        }
        if (apiKeyInput != null) {
            apiKeyInput.setValue(P2SClientConfig.getApiKey());
        }
        if (modelInput != null) {
            modelInput.setValue(P2SClientConfig.getModel());
        }
        if (timeoutInput != null) {
            timeoutInput.setValue(Integer.toString(P2SClientConfig.getHttpTimeoutSeconds()));
        }
        if (systemPromptInput != null) {
            systemPromptInput.setValue(P2SClientConfig.getSystemPrompt());
        }
        useToolCall = P2SClientConfig.isUseToolCall();
        if (toolCallButton != null) {
            toolCallButton.setMessage(toolCallLabel());
        }
        statusText = "Reset to defaults";
        statusColor = 0x55FF55;
    }

    private Integer parseTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        for (EditBox box : inputs()) {
            if (box != null && box.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        for (EditBox box : inputs()) {
            if (box != null && box.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    private List<EditBox> inputs() {
        return List.of(apiUrlInput, apiKeyInput, modelInput, timeoutInput, systemPromptInput);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(760, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(16, this.height / 10);

        gfx.drawString(this.font, this.title, left + 10, top + 8, 0xFFFFFF, true);
        gfx.drawString(this.font, "apiUrl", left + 10, top + 20, 0xBBBBBB, false);
        gfx.drawString(this.font, "apiKey", left + 10, top + 52, 0xBBBBBB, false);
        gfx.drawString(this.font, "model", left + 10, top + 84, 0xBBBBBB, false);
        gfx.drawString(this.font, "httpTimeoutSeconds", left + 10, top + 116, 0xBBBBBB, false);
        gfx.drawString(this.font, "systemPrompt", left + 10, top + 148, 0xBBBBBB, false);

        if (statusText != null && !statusText.isBlank()) {
            gfx.drawString(this.font, statusText, left + 10, top + 228, statusColor, false);
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
}
