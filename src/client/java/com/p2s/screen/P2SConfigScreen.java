package com.p2s.screen;

import com.p2s.P2SClientConfig;
import com.p2s.P2SI18n;
import com.p2s.screen.widget.P2SMultiLineTextEditor;
import com.p2s.store.SkillStore;

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
    private Component statusText = Component.empty();
    private int statusColor = 0xAAAAAA;

    // General tab
    private EditBox selectionItemInput;

    // LLM tab
    private EditBox apiUrlInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox timeoutInput;
    private EditBox sessionTimeoutInput;
    private EditBox maxPatchOpsInput;
    private EditBox maxBlocksPerCommitInput;
    private EditBox riskAutoApplyThresholdInput;
    private EditBox autoCompactTokenLimitInput;
    private EditBox compactRetainUserTokenBudgetInput;
    private P2SMultiLineTextEditor systemPromptEditor;
    private Button toolCallButton;
    private Button streamingButton;
    private Button autoApplyButton;
    private Button confirmRequiredButton;
    private boolean useToolCall;
    private boolean useStreaming;
    private boolean autoApplyPatch;
    private boolean confirmRequired;

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
        super(P2SI18n.tr("screen.p2s.config.title"));
        this.parent = parent;
    }

    public void setPreferredSelection(String skillId) {
        this.preferredSelection = skillId == null ? "" : skillId;
    }

    @Override
    protected void init() {
        super.init();
        statusText = Component.empty();

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
            Button tabBtn = Button.builder(tabLabel(t), btn -> switchTab(t))
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

    private Component tabLabel(Tab tab) {
        return switch (tab) {
            case GENERAL -> P2SI18n.tr("screen.p2s.config.tab.general");
            case LLM -> P2SI18n.tr("screen.p2s.config.tab.llm");
            case SKILLS -> P2SI18n.tr("screen.p2s.config.tab.skills");
        };
    }

    private void switchTab(Tab tab) {
        if (tab == currentTab) return;
        currentTab = tab;
        statusText = Component.empty();
        rebuildWidgets();
    }

    // ──────────────────────── General Tab ────────────────────────

    private void initGeneralTab(int left, int top, int panelWidth) {
        int inputWidth = panelWidth - 20;

        selectionItemInput = new EditBox(this.font, left + 10, top + 14, inputWidth, 20, P2SI18n.tr("screen.p2s.config.selection_item"));
        selectionItemInput.setMaxLength(128);
        selectionItemInput.setValue(P2SClientConfig.getSelectionItemId());
        selectionItemInput.setFocused(true);
        addRenderableWidget(selectionItemInput);

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.save"), btn -> saveSelectionItem())
                .bounds(left + 10, top + 50, 80, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.reset_default"), btn -> resetSelectionItem())
                .bounds(left + 98, top + 50, 130, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.back"), btn -> onClose())
                .bounds(left + 10, top + 110, 80, 20).build());
    }

    private void saveSelectionItem() {
        if (selectionItemInput == null) return;
        String inputValue = selectionItemInput.getValue();
        if (P2SClientConfig.setSelectionItemId(inputValue, true)) {
            selectionItemInput.setValue(P2SClientConfig.getSelectionItemId());
            statusText = P2SI18n.tr("screen.p2s.status.saved");
            statusColor = 0x55FF55;
        } else {
            statusText = P2SI18n.tr("screen.p2s.config.status.invalid_item_id");
            statusColor = 0xFF5555;
        }
    }

    private void resetSelectionItem() {
        P2SClientConfig.setSelectionItemId(P2SClientConfig.defaultSelectionItemId(), true);
        if (selectionItemInput != null) {
            selectionItemInput.setValue(P2SClientConfig.getSelectionItemId());
        }
        statusText = P2SI18n.tr("screen.p2s.status.reset_to_default");
        statusColor = 0x55FF55;
    }

    // ──────────────────────── LLM Tab ────────────────────────

    private void initLlmTab(int left, int top, int panelWidth) {
        int inputWidth = panelWidth - 20;
        int y = top;

        apiUrlInput = new EditBox(this.font, left + 10, y + 14, inputWidth, 20, P2SI18n.tr("screen.p2s.config.llm.api_url"));
        apiUrlInput.setMaxLength(512);
        apiUrlInput.setValue(P2SClientConfig.getApiUrl());
        apiUrlInput.setFocused(true);
        addRenderableWidget(apiUrlInput);
        y += 50;

        apiKeyInput = new EditBox(this.font, left + 10, y + 14, inputWidth, 20, P2SI18n.tr("screen.p2s.config.llm.api_key"));
        apiKeyInput.setMaxLength(1024);
        apiKeyInput.setValue(P2SClientConfig.getApiKey());
        addRenderableWidget(apiKeyInput);
        y += 50;

        modelInput = new EditBox(this.font, left + 10, y + 14, inputWidth, 20, P2SI18n.tr("screen.p2s.config.llm.model"));
        modelInput.setMaxLength(256);
        modelInput.setValue(P2SClientConfig.getModel());
        addRenderableWidget(modelInput);
        y += 50;

        timeoutInput = new EditBox(this.font, left + 10, y + 14, 120, 20, P2SI18n.tr("screen.p2s.config.llm.timeout"));
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

        autoApplyPatch = P2SClientConfig.getAutoApplyPatch();
        autoApplyButton = addRenderableWidget(Button.builder(autoApplyLabel(), btn -> {
            autoApplyPatch = !autoApplyPatch;
            btn.setMessage(autoApplyLabel());
        }).bounds(left + 480, y + 14, 180, 20).build());
        y += 50;

        sessionTimeoutInput = new EditBox(this.font, left + 10, y + 14, 120, 20, P2SI18n.tr("screen.p2s.config.llm.session_timeout"));
        sessionTimeoutInput.setMaxLength(8);
        sessionTimeoutInput.setValue(Integer.toString(P2SClientConfig.getSessionJobTimeoutSeconds()));
        addRenderableWidget(sessionTimeoutInput);

        maxPatchOpsInput = new EditBox(this.font, left + 140, y + 14, 120, 20, P2SI18n.tr("screen.p2s.config.llm.max_patch_ops"));
        maxPatchOpsInput.setMaxLength(8);
        maxPatchOpsInput.setValue(Integer.toString(P2SClientConfig.getMaxPatchOps()));
        addRenderableWidget(maxPatchOpsInput);

        maxBlocksPerCommitInput = new EditBox(this.font, left + 270, y + 14, 150, 20, P2SI18n.tr("screen.p2s.config.llm.max_blocks_per_commit"));
        maxBlocksPerCommitInput.setMaxLength(8);
        maxBlocksPerCommitInput.setValue(Integer.toString(P2SClientConfig.getMaxBlocksPerCommit()));
        addRenderableWidget(maxBlocksPerCommitInput);

        confirmRequired = P2SClientConfig.getConfirmRequired();
        confirmRequiredButton = addRenderableWidget(Button.builder(confirmRequiredLabel(), btn -> {
            confirmRequired = !confirmRequired;
            btn.setMessage(confirmRequiredLabel());
        }).bounds(left + 430, y + 14, 190, 20).build());
        y += 50;

        riskAutoApplyThresholdInput = new EditBox(this.font, left + 10, y + 14, 170, 20, P2SI18n.tr("screen.p2s.config.llm.risk_auto_apply_threshold"));
        riskAutoApplyThresholdInput.setMaxLength(8);
        riskAutoApplyThresholdInput.setValue(Integer.toString(P2SClientConfig.getRiskAutoApplyThreshold()));
        addRenderableWidget(riskAutoApplyThresholdInput);

        autoCompactTokenLimitInput = new EditBox(this.font, left + 200, y + 14, 170, 20, P2SI18n.tr("screen.p2s.config.llm.auto_compact_token_limit"));
        autoCompactTokenLimitInput.setMaxLength(8);
        autoCompactTokenLimitInput.setValue(Integer.toString(P2SClientConfig.getAutoCompactTokenLimit()));
        addRenderableWidget(autoCompactTokenLimitInput);

        compactRetainUserTokenBudgetInput = new EditBox(this.font, left + 390, y + 14, 220, 20, P2SI18n.tr("screen.p2s.config.llm.compact_retain_user_tokens"));
        compactRetainUserTokenBudgetInput.setMaxLength(8);
        compactRetainUserTokenBudgetInput.setValue(Integer.toString(P2SClientConfig.getCompactRetainUserTokenBudget()));
        addRenderableWidget(compactRetainUserTokenBudgetInput);
        y += 50 + compactHintHeight(panelWidth);

        int promptY = y + 14;
        systemPromptEditor = new P2SMultiLineTextEditor(this.font);
        systemPromptEditor.setMaxLength(2048);
        systemPromptEditor.setBounds(left + 10, promptY, inputWidth, llmPromptHeight(promptY));
        systemPromptEditor.setText(P2SClientConfig.getSystemPrompt());
        y = promptY + llmPromptHeight(promptY) + 10;

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.save"), btn -> saveLlmConfig())
                .bounds(left + 10, y, 80, 20).build());
        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.reset_default"), btn -> resetLlmDefaults())
                .bounds(left + 98, y, 130, 20).build());
        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.back"), btn -> onClose())
                .bounds(left + panelWidth - 80, y, 70, 20).build());
    }

    private Component toolCallLabel() {
        return P2SI18n.tr("screen.p2s.config.toggle.use_tool_call",
                P2SI18n.tr(useToolCall ? "screen.p2s.common.on" : "screen.p2s.common.off"));
    }

    private Component streamingLabel() {
        return P2SI18n.tr("screen.p2s.config.toggle.use_streaming",
                P2SI18n.tr(useStreaming ? "screen.p2s.common.on" : "screen.p2s.common.off"));
    }

    private Component autoApplyLabel() {
        return P2SI18n.tr("screen.p2s.config.toggle.auto_apply",
                P2SI18n.tr(autoApplyPatch ? "screen.p2s.common.on" : "screen.p2s.common.off"));
    }

    private Component confirmRequiredLabel() {
        return P2SI18n.tr("screen.p2s.config.toggle.confirm_required",
                P2SI18n.tr(confirmRequired ? "screen.p2s.common.on" : "screen.p2s.common.off"));
    }

    private int llmPromptHeight(int promptY) {
        int available = this.height - promptY - 60;
        if (available <= 0) {
            return 40;
        }
        return Math.max(40, Math.min(180, available));
    }

    private int compactHintHeight(int panelWidth) {
        return Math.max(this.font.lineHeight, this.font.split(P2SI18n.tr("screen.p2s.config.llm.compact_threshold_hint"), panelWidth - 20).size() * (this.font.lineHeight + 1));
    }

    private void saveLlmConfig() {
        Integer timeout = parsePositiveInt(timeoutInput == null ? "" : timeoutInput.getValue());
        Integer sessionTimeout = parsePositiveInt(sessionTimeoutInput == null ? "" : sessionTimeoutInput.getValue());
        Integer maxPatchOps = parsePositiveInt(maxPatchOpsInput == null ? "" : maxPatchOpsInput.getValue());
        Integer maxBlocksPerCommit = parsePositiveInt(maxBlocksPerCommitInput == null ? "" : maxBlocksPerCommitInput.getValue());
        Integer riskAutoApplyThreshold = parseInteger(riskAutoApplyThresholdInput == null ? "" : riskAutoApplyThresholdInput.getValue());
        Integer autoCompactTokenLimit = parsePositiveInt(autoCompactTokenLimitInput == null ? "" : autoCompactTokenLimitInput.getValue());
        Integer compactRetainUserTokenBudget = parsePositiveInt(compactRetainUserTokenBudgetInput == null ? "" : compactRetainUserTokenBudgetInput.getValue());
        boolean ok = P2SClientConfig.setLlmConfig(
                apiUrlInput == null ? "" : apiUrlInput.getValue(),
                apiKeyInput == null ? "" : apiKeyInput.getValue(),
                modelInput == null ? "" : modelInput.getValue(),
                timeout,
                useToolCall,
                sessionTimeout,
                maxPatchOps,
                maxBlocksPerCommit,
                confirmRequired,
                riskAutoApplyThreshold,
                autoCompactTokenLimit,
                compactRetainUserTokenBudget,
                systemPromptEditor == null ? "" : systemPromptEditor.getText(),
                false
        );
        if (ok) {
            P2SClientConfig.setUseStreaming(useStreaming, false);
            P2SClientConfig.setAutoApplyPatch(autoApplyPatch, true);
            statusText = P2SI18n.tr("screen.p2s.status.saved");
            statusColor = 0x55FF55;
        } else {
            statusText = P2SI18n.tr("screen.p2s.config.status.invalid_values");
            statusColor = 0xFF5555;
        }
    }

    private void resetLlmDefaults() {
        P2SClientConfig.resetLlmConfigDefaults(true);
        if (apiUrlInput != null) apiUrlInput.setValue(P2SClientConfig.getApiUrl());
        if (apiKeyInput != null) apiKeyInput.setValue(P2SClientConfig.getApiKey());
        if (modelInput != null) modelInput.setValue(P2SClientConfig.getModel());
        if (timeoutInput != null) timeoutInput.setValue(Integer.toString(P2SClientConfig.getHttpTimeoutSeconds()));
        if (sessionTimeoutInput != null) sessionTimeoutInput.setValue(Integer.toString(P2SClientConfig.getSessionJobTimeoutSeconds()));
        if (maxPatchOpsInput != null) maxPatchOpsInput.setValue(Integer.toString(P2SClientConfig.getMaxPatchOps()));
        if (maxBlocksPerCommitInput != null) maxBlocksPerCommitInput.setValue(Integer.toString(P2SClientConfig.getMaxBlocksPerCommit()));
        if (riskAutoApplyThresholdInput != null) riskAutoApplyThresholdInput.setValue(Integer.toString(P2SClientConfig.getRiskAutoApplyThreshold()));
        if (autoCompactTokenLimitInput != null) autoCompactTokenLimitInput.setValue(Integer.toString(P2SClientConfig.getAutoCompactTokenLimit()));
        if (compactRetainUserTokenBudgetInput != null) compactRetainUserTokenBudgetInput.setValue(Integer.toString(P2SClientConfig.getCompactRetainUserTokenBudget()));
        if (systemPromptEditor != null) systemPromptEditor.setText(P2SClientConfig.getSystemPrompt());
        useToolCall = P2SClientConfig.isUseToolCall();
        if (toolCallButton != null) toolCallButton.setMessage(toolCallLabel());
        useStreaming = P2SClientConfig.getUseStreaming();
        if (streamingButton != null) streamingButton.setMessage(streamingLabel());
        autoApplyPatch = P2SClientConfig.getAutoApplyPatch();
        if (autoApplyButton != null) autoApplyButton.setMessage(autoApplyLabel());
        confirmRequired = P2SClientConfig.getConfirmRequired();
        if (confirmRequiredButton != null) confirmRequiredButton.setMessage(confirmRequiredLabel());
        statusText = P2SI18n.tr("screen.p2s.status.reset_to_defaults");
        statusColor = 0x55FF55;
    }

    private Integer parsePositiveInt(String raw) {
        Integer value = parseInteger(raw);
        return value == null || value <= 0 ? null : value;
    }

    private Integer parseInteger(String raw) {
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
            Button rowBtn = Button.builder(Component.empty(), btn -> selectRow(row))
                    .bounds(rowX, rowY + i * (rowH + 2), listWidth, rowH)
                    .build();
            skillRowButtons.add(rowBtn);
            addRenderableWidget(rowBtn);
        }

        upButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.up"), btn -> {
            if (scroll > 0) {
                scroll -= 1;
                refreshRows();
            }
        }).bounds(rowX, rowY + VISIBLE_ROWS * (rowH + 2), 48, 20).build());

        downButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.down"), btn -> {
            int maxScroll = Math.max(0, skills.size() - VISIBLE_ROWS);
            if (scroll < maxScroll) {
                scroll += 1;
                refreshRows();
            }
        }).bounds(rowX + 56, rowY + VISIBLE_ROWS * (rowH + 2), 58, 20).build());

        int actionX = left + panelWidth - 200;
        int actionY = rowY;
        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.new"), btn -> openEditor(P2SSkillEditorScreen.Mode.CREATE))
                .bounds(actionX, actionY, 180, 20).build());

        editButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.edit"), btn -> openEditor(P2SSkillEditorScreen.Mode.EDIT))
                .bounds(actionX, actionY + 24, 180, 20).build());

        renameButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.rename"), btn -> openEditor(P2SSkillEditorScreen.Mode.RENAME))
                .bounds(actionX, actionY + 48, 180, 20).build());

        activeButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.config.skills.set_active"), btn -> setActiveSelected())
                .bounds(actionX, actionY + 72, 180, 20).build());

        deleteButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.delete"), btn -> deleteSelected())
                .bounds(actionX, actionY + 96, 180, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.refresh"), btn -> {
            reloadSkills();
            refreshRows();
        }).bounds(actionX, actionY + 120, 180, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.back"), btn -> onClose())
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
            statusText = P2SI18n.tr("screen.p2s.config.skills.status.active_set", selectedId);
            statusColor = 0x55FF55;
        } else {
            statusText = P2SI18n.tr("screen.p2s.config.skills.status.active_failed");
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
            statusText = P2SI18n.tr("screen.p2s.config.skills.status.deleted", deleting);
            statusColor = 0x55FF55;
            selectedId = "";
        } else {
            statusText = P2SI18n.tr("screen.p2s.config.skills.status.delete_failed", deleting);
            statusColor = 0xFF5555;
        }
        reloadSkills();
        refreshRows();
    }

    public void onSkillSaved(String skillId) {
        setPreferredSelection(skillId);
        currentTab = Tab.SKILLS;
        statusText = P2SI18n.tr("screen.p2s.status.saved");
        statusColor = 0x55FF55;
    }

    // ──────────────────────── Input handling ────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (currentTab == Tab.LLM && systemPromptEditor != null && systemPromptEditor.keyPressed(keyCode, modifiers)) {
            return true;
        }
        for (EditBox box : collectEditBoxes()) {
            if (box != null && box.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (currentTab == Tab.LLM && systemPromptEditor != null && systemPromptEditor.charTyped(codePoint, modifiers)) {
            return true;
        }
        for (EditBox box : collectEditBoxes()) {
            if (box != null && box.charTyped(codePoint, modifiers)) return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (currentTab == Tab.LLM && systemPromptEditor != null && systemPromptEditor.mouseClicked(mouseX, mouseY, button)) {
            if (apiUrlInput != null) apiUrlInput.setFocused(false);
            if (apiKeyInput != null) apiKeyInput.setFocused(false);
            if (modelInput != null) modelInput.setFocused(false);
            if (timeoutInput != null) timeoutInput.setFocused(false);
            if (sessionTimeoutInput != null) sessionTimeoutInput.setFocused(false);
            if (maxPatchOpsInput != null) maxPatchOpsInput.setFocused(false);
            if (maxBlocksPerCommitInput != null) maxBlocksPerCommitInput.setFocused(false);
            if (riskAutoApplyThresholdInput != null) riskAutoApplyThresholdInput.setFocused(false);
            if (autoCompactTokenLimitInput != null) autoCompactTokenLimitInput.setFocused(false);
            if (compactRetainUserTokenBudgetInput != null) compactRetainUserTokenBudgetInput.setFocused(false);
            return true;
        }
        if (button == 0 && systemPromptEditor != null) {
            systemPromptEditor.setFocused(false);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (currentTab == Tab.LLM && systemPromptEditor != null && systemPromptEditor.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (currentTab == Tab.LLM && systemPromptEditor != null && systemPromptEditor.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (currentTab == Tab.LLM && systemPromptEditor != null && systemPromptEditor.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private List<EditBox> collectEditBoxes() {
        return switch (currentTab) {
            case GENERAL -> selectionItemInput == null ? List.of() : List.of(selectionItemInput);
            case LLM -> List.of(apiUrlInput, apiKeyInput, modelInput, timeoutInput, sessionTimeoutInput, maxPatchOpsInput, maxBlocksPerCommitInput, riskAutoApplyThresholdInput, autoCompactTokenLimitInput, compactRetainUserTokenBudgetInput);
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
            case LLM -> renderLlmTab(gfx, left, contentTop, panelWidth);
            case SKILLS -> renderSkillsTab(gfx, left, contentTop, panelWidth);
        }

        // Status text at bottom
        if (statusText != null && !statusText.getString().isBlank()) {
            gfx.drawString(this.font, statusText, left + 10, this.height - 20, statusColor, false);
        }
    }

    private void renderGeneralTab(GuiGraphics gfx, int left, int top) {
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.general.selection_item"), left + 10, top, 0xCCCCCC, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.general.default_value", P2SClientConfig.defaultSelectionItemId()), left + 10, top + 38, 0x888888, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.general.skills_path"), left + 10, top + 76, 0x888888, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.general.llm_path"), left + 10, top + 88, 0x888888, false);
    }

    private void renderLlmTab(GuiGraphics gfx, int left, int top, int panelWidth) {
        int y = top;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.api_url"), left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.api_key"), left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.model"), left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.timeout"), left + 10, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.session_timeout"), left + 10, y, 0xBBBBBB, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.max_patch_ops"), left + 140, y, 0xBBBBBB, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.max_blocks_per_commit"), left + 270, y, 0xBBBBBB, false);
        y += 50;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.risk_auto_apply_threshold"), left + 10, y, 0xBBBBBB, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.auto_compact_token_limit"), left + 200, y, 0xBBBBBB, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.compact_retain_user_tokens"), left + 390, y, 0xBBBBBB, false);
        int hintY = y + 38;
        for (var line : this.font.split(P2SI18n.tr("screen.p2s.config.llm.compact_threshold_hint"), panelWidth - 20)) {
            gfx.drawString(this.font, line, left + 10, hintY, 0x888888, false);
            hintY += this.font.lineHeight + 1;
        }
        y += 50 + compactHintHeight(panelWidth);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.llm.system_prompt"), left + 10, y, 0xBBBBBB, false);
        if (systemPromptEditor != null) {
            systemPromptEditor.render(gfx);
        }
    }

    private void renderSkillsTab(GuiGraphics gfx, int left, int top, int panelWidth) {
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.skills.path"), left + 8, top + 6, 0xBBBBBB, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.skills.total", skills.size()), left + 8, top + 18, 0xAAAAAA, false);

        SkillStore.SkillMeta selected = skills.stream().filter(s -> s.id().equals(selectedId)).findFirst().orElse(null);
        int detailsX = left + panelWidth - 200;
        int detailsY = top + 220;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.skills.selected"), detailsX, detailsY, 0xFFFFFF, true);
        detailsY += this.font.lineHeight + 2;
        if (selected == null) {
            gfx.drawString(this.font, Component.literal("-"), detailsX, detailsY, 0xAAAAAA, false);
        } else {
            gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.skills.id", selected.id()), detailsX, detailsY, 0xCCCCCC, false);
            detailsY += this.font.lineHeight + 2;
            gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.skills.name", selected.name()), detailsX, detailsY, 0xCCCCCC, false);
            detailsY += this.font.lineHeight + 2;
            gfx.drawString(this.font, P2SI18n.tr("screen.p2s.config.skills.desc", selected.description()), detailsX, detailsY, 0xAAAAAA, false);
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
