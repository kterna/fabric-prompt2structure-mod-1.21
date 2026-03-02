package com.p2s;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class P2SConfigScreen extends Screen {
    private enum Tab { GENERAL, LLM, SKILLS }

    private static final int VISIBLE_ROWS = 8;

    private final Screen parent;
    private Tab currentTab = Tab.GENERAL;
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    // General tab
    private EditBox selectionItemInput;

    // LLM tab
    private EditBox apiUrlInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox timeoutInput;
    private EditBox systemPromptInput;
    private Button toolCallButton;
    private Button streamingButton;
    private boolean useToolCall;
    private boolean useStreaming;

    // Skills tab
    private final List<Button> skillRowButtons = new ArrayList<>();
    private List<SkillStore.SkillMeta> skills = List.of();
    private int scroll = 0;
    private String selectedId = "";
    private String preferredSelection = "";
    private Button upButton;
    private Button downButton;
    private Button editButton;
    private Button renameButton;
    private Button deleteButton;
    private Button activeButton;

    public P2SConfigScreen(Screen parent) {
        super(Component.literal("P2S Config"));
        this.parent = parent;
    }

    public void setPreferredSelection(String skillId) {
        this.preferredSelection = skillId == null ? "" : skillId;
    }

    @Override
    protected void init() {
        super.init();
        statusText = "";

        int panelWidth = Math.min(760, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int tabY = Math.max(12, this.height / 10);
        int tabWidth = 80;
        int tabGap = 4;

        // Tab buttons
        int tabX = left;
        for (Tab tab : Tab.values()) {
            final Tab t = tab;
            boolean active = (t == currentTab);
            Button tabBtn = Button.builder(Component.literal(tabLabel(t)), btn -> switchTab(t))
                    .bounds(tabX, tabY, tabWidth, 20)
                    .build();
            tabBtn.active = !active;
            addRenderableWidget(tabBtn);
            tabX += tabWidth + tabGap;
        }

        int contentTop = tabY + 36;

        switch (currentTab) {
            case GENERAL -> initGeneralTab(left, contentTop, panelWidth);
            case LLM -> initLlmTab(left, contentTop, panelWidth);
            case SKILLS -> initSkillsTab(left, contentTop, panelWidth);
        }
    }

    private String tabLabel(Tab tab) {
        return switch (tab) {
            case GENERAL -> "General";
            case LLM -> "LLM";
            case SKILLS -> "Skills";
        };
    }

    private void switchTab(Tab tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        statusText = "";
        rebuildWidgets();
    }

    // ──────────────────────── General Tab ────────────────────────

    private void initGeneralTab(int left, int top, int panelWidth) {
        int inputWidth = panelWidth - 20;

        selectionItemInput = new EditBox(this.font, left + 10, top + 14, inputWidth, 20, Component.literal("selection item"));
        selectionItemInput.setMaxLength(128);
        selectionItemInput.setValue(P2SClientConfig.getSelectionItemId());
        selectionItemInput.setFocused(true);
        addRenderableWidget(selectionItemInput);

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> saveSelectionItem())
                .bounds(left + 10, top + 50, 80, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reset Default"), btn -> resetSelectionItem())
                .bounds(left + 98, top + 50, 130, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(left + 10, top + 110, 80, 20).build());
    }

    private void saveSelectionItem() {
        if (selectionItemInput == null) return;
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

    // ──────────────────────── LLM Tab ────────────────────────

    private void initLlmTab(int left, int top, int panelWidth) {
        int inputWidth = panelWidth - 20;
        int y = top;

        apiUrlInput = new EditBox(this.font, left + 10, y + 14, inputWidth, 20, Component.literal("api url"));
        apiUrlInput.setMaxLength(512);
        apiUrlInput.setValue(P2SClientConfig.getApiUrl());
        apiUrlInput.setFocused(true);
        addRenderableWidget(apiUrlInput);
        y += 50;

        apiKeyInput = new EditBox(this.font, left + 10, y + 14, inputWidth, 20, Component.literal("api key"));
        apiKeyInput.setMaxLength(1024);
        apiKeyInput.setValue(P2SClientConfig.getApiKey());
        addRenderableWidget(apiKeyInput);
        y += 50;

        modelInput = new EditBox(this.font, left + 10, y + 14, inputWidth, 20, Component.literal("model"));
        modelInput.setMaxLength(256);
        modelInput.setValue(P2SClientConfig.getModel());
        addRenderableWidget(modelInput);
        y += 50;

        timeoutInput = new EditBox(this.font, left + 10, y + 14, 120, 20, Component.literal("timeout seconds"));
        timeoutInput.setMaxLength(8);
        timeoutInput.setValue(Integer.toString(P2SClientConfig.getHttpTimeoutSeconds()));
        addRenderableWidget(timeoutInput);

        useToolCall = P2SClientConfig.isUseToolCall();
        toolCallButton = addRenderableWidget(Button.builder(toolCallLabel(), btn -> {
            useToolCall = !useToolCall;
            btn.setMessage(toolCallLabel());
        }).bounds(left + 140, y + 14, 160, 20).build());

        useStreaming = P2SClientConfig.getUseStreaming();
        streamingButton = addRenderableWidget(Button.builder(streamingLabel(), btn -> {
            useStreaming = !useStreaming;
            btn.setMessage(streamingLabel());
        }).bounds(left + 310, y + 14, 160, 20).build());
        y += 50;

        systemPromptInput = new EditBox(this.font, left + 10, y + 14, inputWidth, 20, Component.literal("system prompt"));
        systemPromptInput.setMaxLength(2048);
        systemPromptInput.setValue(P2SClientConfig.getSystemPrompt());
        addRenderableWidget(systemPromptInput);
        y += 50;

        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> saveLlmConfig())
                .bounds(left + 10, y, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset Default"), btn -> resetLlmDefaults())
                .bounds(left + 98, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(left + panelWidth - 80, y, 70, 20).build());
    }

    private Component toolCallLabel() {
        return Component.literal("useToolCall: " + (useToolCall ? "ON" : "OFF"));
    }

    private Component streamingLabel() {
        return Component.literal("useStreaming: " + (useStreaming ? "ON" : "OFF"));
    }

    private void saveLlmConfig() {
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
        P2SClientConfig.setUseStreaming(useStreaming, true);
        if (ok) {
            statusText = "Saved";
            statusColor = 0x55FF55;
        } else {
            statusText = "Invalid config values";
            statusColor = 0xFF5555;
        }
    }

    private void resetLlmDefaults() {
        P2SClientConfig.resetLlmConfigDefaults(true);
        if (apiUrlInput != null) apiUrlInput.setValue(P2SClientConfig.getApiUrl());
        if (apiKeyInput != null) apiKeyInput.setValue(P2SClientConfig.getApiKey());
        if (modelInput != null) modelInput.setValue(P2SClientConfig.getModel());
        if (timeoutInput != null) timeoutInput.setValue(Integer.toString(P2SClientConfig.getHttpTimeoutSeconds()));
        if (systemPromptInput != null) systemPromptInput.setValue(P2SClientConfig.getSystemPrompt());
        useToolCall = P2SClientConfig.isUseToolCall();
        if (toolCallButton != null) toolCallButton.setMessage(toolCallLabel());
        useStreaming = P2SClientConfig.getUseStreaming();
        if (streamingButton != null) streamingButton.setMessage(streamingLabel());
        statusText = "Reset to defaults";
        statusColor = 0x55FF55;
    }

    private Integer parseTimeout(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ──────────────────────── Skills Tab ────────────────────────

    private void initSkillsTab(int left, int top, int panelWidth) {
        skillRowButtons.clear();
        reloadSkills();

        int listWidth = panelWidth - 220;
        int rowX = left + 8;
        int rowY = top + 30;
        int rowH = 20;

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            final int row = i;
            Button rowBtn = Button.builder(Component.literal(""), btn -> selectRow(row))
                    .bounds(rowX, rowY + i * (rowH + 2), listWidth, rowH)
                    .build();
            skillRowButtons.add(rowBtn);
            addRenderableWidget(rowBtn);
        }

        upButton = addRenderableWidget(Button.builder(Component.literal("Up"), btn -> {
            if (scroll > 0) {
                scroll -= 1;
                refreshRows();
            }
        }).bounds(rowX, rowY + VISIBLE_ROWS * (rowH + 2), 48, 20).build());

        downButton = addRenderableWidget(Button.builder(Component.literal("Down"), btn -> {
            int maxScroll = Math.max(0, skills.size() - VISIBLE_ROWS);
            if (scroll < maxScroll) {
                scroll += 1;
                refreshRows();
            }
        }).bounds(rowX + 56, rowY + VISIBLE_ROWS * (rowH + 2), 58, 20).build());

        int actionX = left + panelWidth - 200;
        int actionY = rowY;
        addRenderableWidget(Button.builder(Component.literal("New"), btn -> openEditor(P2SSkillEditorScreen.Mode.CREATE))
                .bounds(actionX, actionY, 180, 20).build());

        editButton = addRenderableWidget(Button.builder(Component.literal("Edit"), btn -> openEditor(P2SSkillEditorScreen.Mode.EDIT))
                .bounds(actionX, actionY + 24, 180, 20).build());

        renameButton = addRenderableWidget(Button.builder(Component.literal("Rename"), btn -> openEditor(P2SSkillEditorScreen.Mode.RENAME))
                .bounds(actionX, actionY + 48, 180, 20).build());

        activeButton = addRenderableWidget(Button.builder(Component.literal("Set Active"), btn -> setActiveSelected())
                .bounds(actionX, actionY + 72, 180, 20).build());

        deleteButton = addRenderableWidget(Button.builder(Component.literal("Delete"), btn -> deleteSelected())
                .bounds(actionX, actionY + 96, 180, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Refresh"), btn -> {
            reloadSkills();
            refreshRows();
        }).bounds(actionX, actionY + 120, 180, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(actionX, actionY + 168, 180, 20).build());

        refreshRows();
    }

    private void reloadSkills() {
        this.skills = SkillStore.listSkills();

        String active = SkillStore.activeSkillId();
        if (!preferredSelection.isBlank()) {
            selectedId = preferredSelection;
            preferredSelection = "";
        } else if (selectedId.isBlank() && !active.isBlank()) {
            selectedId = active;
        } else if (selectedId.isBlank() && !skills.isEmpty()) {
            selectedId = skills.get(0).id();
        }

        if (!selectedId.isBlank() && skills.stream().noneMatch(s -> s.id().equals(selectedId))) {
            selectedId = active;
        }
        if (selectedId.isBlank() && !skills.isEmpty()) {
            selectedId = skills.get(0).id();
        }

        int selectedIndex = indexOfSelected();
        if (selectedIndex >= 0) {
            if (selectedIndex < scroll) {
                scroll = selectedIndex;
            } else if (selectedIndex >= scroll + VISIBLE_ROWS) {
                scroll = Math.max(0, selectedIndex - VISIBLE_ROWS + 1);
            }
        } else {
            scroll = 0;
        }
    }

    private void refreshRows() {
        String active = SkillStore.activeSkillId();
        for (int i = 0; i < skillRowButtons.size(); i++) {
            int idx = scroll + i;
            Button btn = skillRowButtons.get(i);
            if (idx >= 0 && idx < skills.size()) {
                SkillStore.SkillMeta meta = skills.get(idx);
                String marker = meta.id().equals(active) ? "*" : " ";
                String selected = meta.id().equals(selectedId) ? ">" : " ";
                String label = selected + marker + " " + meta.name();
                btn.setMessage(Component.literal(label));
                btn.visible = true;
                btn.active = true;
            } else {
                btn.setMessage(Component.literal(""));
                btn.visible = false;
                btn.active = false;
            }
        }

        int maxScroll = Math.max(0, skills.size() - VISIBLE_ROWS);
        if (upButton != null) upButton.active = scroll > 0;
        if (downButton != null) downButton.active = scroll < maxScroll;

        boolean hasSelection = !selectedId.isBlank() && skills.stream().anyMatch(s -> s.id().equals(selectedId));
        if (editButton != null) editButton.active = hasSelection;
        if (renameButton != null) renameButton.active = hasSelection;
        if (deleteButton != null) deleteButton.active = hasSelection;
        if (activeButton != null) activeButton.active = hasSelection && !selectedId.equals(active);
    }

    private void selectRow(int row) {
        int idx = scroll + row;
        if (idx < 0 || idx >= skills.size()) return;
        selectedId = skills.get(idx).id();
        refreshRows();
    }

    private int indexOfSelected() {
        if (selectedId == null || selectedId.isBlank()) return -1;
        for (int i = 0; i < skills.size(); i++) {
            if (selectedId.equals(skills.get(i).id())) return i;
        }
        return -1;
    }

    private void openEditor(P2SSkillEditorScreen.Mode mode) {
        String editingId = mode == P2SSkillEditorScreen.Mode.CREATE ? "" : selectedId;
        if (mode != P2SSkillEditorScreen.Mode.CREATE && (editingId == null || editingId.isBlank())) return;
        if (this.minecraft != null) {
            this.minecraft.setScreen(new P2SSkillEditorScreen(this, mode, editingId));
        }
    }

    private void setActiveSelected() {
        if (selectedId == null || selectedId.isBlank()) return;
        boolean ok = SkillStore.setActiveSkill(selectedId);
        if (ok) {
            statusText = "Active skill set: " + selectedId;
            statusColor = 0x55FF55;
        } else {
            statusText = "Failed setting active skill";
            statusColor = 0xFF5555;
        }
        reloadSkills();
        refreshRows();
    }

    private void deleteSelected() {
        if (selectedId == null || selectedId.isBlank()) return;
        String deleting = selectedId;
        boolean ok = SkillStore.deleteSkill(deleting);
        if (ok) {
            statusText = "Deleted skill: " + deleting;
            statusColor = 0x55FF55;
            selectedId = "";
        } else {
            statusText = "Delete failed: " + deleting;
            statusColor = 0xFF5555;
        }
        reloadSkills();
        refreshRows();
    }

    public void onSkillSaved(String skillId) {
        setPreferredSelection(skillId);
        currentTab = Tab.SKILLS;
        statusText = "Saved";
        statusColor = 0x55FF55;
    }

    // ──────────────────────── Input handling ────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        for (EditBox box : collectEditBoxes()) {
            if (box != null && box.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        for (EditBox box : collectEditBoxes()) {
            if (box != null && box.charTyped(codePoint, modifiers)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private List<EditBox> collectEditBoxes() {
        return switch (currentTab) {
            case GENERAL -> selectionItemInput == null ? List.of() : List.of(selectionItemInput);
            case LLM -> List.of(apiUrlInput, apiKeyInput, modelInput, timeoutInput, systemPromptInput);
            case SKILLS -> List.of();
        };
    }

    // ──────────────────────── Render ────────────────────────

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(760, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int tabY = Math.max(12, this.height / 10);
        int contentTop = tabY + 36;

        // Draw active tab indicator
        int tabWidth = 80;
        int tabGap = 4;
        int activeIndex = currentTab.ordinal();
        int indicatorX = left + activeIndex * (tabWidth + tabGap);
        gfx.fill(indicatorX, tabY + 20, indicatorX + tabWidth, tabY + 22, 0xFFFFFFFF);

        switch (currentTab) {
            case GENERAL -> renderGeneralTab(gfx, left, contentTop);
            case LLM -> renderLlmTab(gfx, left, contentTop);
            case SKILLS -> renderSkillsTab(gfx, left, contentTop, panelWidth);
        }

        // Status text at bottom
        if (statusText != null && !statusText.isBlank()) {
            gfx.drawString(this.font, statusText, left + 10, this.height - 20, statusColor, false);
        }
    }

    private void renderGeneralTab(GuiGraphics gfx, int left, int top) {
        gfx.drawString(this.font, "Selection tool item id", left + 10, top, 0xCCCCCC, false);
        gfx.drawString(this.font, "Default: " + P2SClientConfig.defaultSelectionItemId(), left + 10, top + 38, 0x888888, false);
        gfx.drawString(this.font, "Skills are stored under config/p2s_skills/skills/ (client-global)", left + 10, top + 76, 0x888888, false);
        gfx.drawString(this.font, "LLM apiUrl/apiKey/model are in p2s_client.json (LLM tab)", left + 10, top + 88, 0x888888, false);
    }

    private void renderLlmTab(GuiGraphics gfx, int left, int top) {
        int y = top;
        gfx.drawString(this.font, "apiUrl", left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, "apiKey", left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, "model", left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, "httpTimeoutSeconds", left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, "systemPrompt", left + 10, y, 0xBBBBBB, false);
    }

    private void renderSkillsTab(GuiGraphics gfx, int left, int top, int panelWidth) {
        gfx.drawString(this.font, "Client-global skills: config/p2s_skills/skills/", left + 8, top + 6, 0xBBBBBB, false);
        gfx.drawString(this.font, "Total skills: " + skills.size(), left + 8, top + 18, 0xAAAAAA, false);

        SkillStore.SkillMeta selected = skills.stream().filter(s -> s.id().equals(selectedId)).findFirst().orElse(null);
        int detailsX = left + panelWidth - 200;
        int detailsY = top + 220;
        gfx.drawString(this.font, "Selected", detailsX, detailsY, 0xFFFFFF, true);
        detailsY += this.font.lineHeight + 2;
        if (selected == null) {
            gfx.drawString(this.font, "-", detailsX, detailsY, 0xAAAAAA, false);
        } else {
            gfx.drawString(this.font, "id: " + selected.id(), detailsX, detailsY, 0xCCCCCC, false);
            detailsY += this.font.lineHeight + 2;
            gfx.drawString(this.font, "name: " + selected.name(), detailsX, detailsY, 0xCCCCCC, false);
            detailsY += this.font.lineHeight + 2;
            gfx.drawString(this.font, "desc: " + selected.description(), detailsX, detailsY, 0xAAAAAA, false);
        }
    }

    // ──────────────────────── Lifecycle ────────────────────────

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
