package com.p2s;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class P2SSkillConfigScreen extends Screen {
    private static final int VISIBLE_ROWS = 8;

    private final Screen parent;
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
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    public P2SSkillConfigScreen(Screen parent) {
        super(Component.literal("P2S Skills"));
        this.parent = parent;
    }

    public void setPreferredSelection(String skillId) {
        this.preferredSelection = skillId == null ? "" : skillId;
    }

    @Override
    protected void init() {
        super.init();
        skillRowButtons.clear();
        reloadSkills();

        int panelWidth = Math.min(700, this.width - 24);
        int panelLeft = (this.width - panelWidth) / 2;
        int top = Math.max(18, this.height / 8);

        int listWidth = panelWidth - 220;
        int rowX = panelLeft + 8;
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

        int actionX = panelLeft + panelWidth - 200;
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
        if (upButton != null) {
            upButton.active = scroll > 0;
        }
        if (downButton != null) {
            downButton.active = scroll < maxScroll;
        }

        boolean hasSelection = !selectedId.isBlank() && skills.stream().anyMatch(s -> s.id().equals(selectedId));
        if (editButton != null) {
            editButton.active = hasSelection;
        }
        if (renameButton != null) {
            renameButton.active = hasSelection;
        }
        if (deleteButton != null) {
            deleteButton.active = hasSelection;
        }
        if (activeButton != null) {
            activeButton.active = hasSelection && !selectedId.equals(active);
        }
    }

    private void selectRow(int row) {
        int idx = scroll + row;
        if (idx < 0 || idx >= skills.size()) {
            return;
        }
        selectedId = skills.get(idx).id();
        refreshRows();
    }

    private int indexOfSelected() {
        if (selectedId == null || selectedId.isBlank()) {
            return -1;
        }
        for (int i = 0; i < skills.size(); i++) {
            if (selectedId.equals(skills.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private void openEditor(P2SSkillEditorScreen.Mode mode) {
        String editingId = mode == P2SSkillEditorScreen.Mode.CREATE ? "" : selectedId;
        if (mode != P2SSkillEditorScreen.Mode.CREATE && (editingId == null || editingId.isBlank())) {
            return;
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(new P2SSkillEditorScreen(this, mode, editingId));
        }
    }

    private void setActiveSelected() {
        if (selectedId == null || selectedId.isBlank()) {
            return;
        }
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
        if (selectedId == null || selectedId.isBlank()) {
            return;
        }
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
        statusText = "Saved";
        statusColor = 0x55FF55;
        reloadSkills();
        refreshRows();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(700, this.width - 24);
        int panelLeft = (this.width - panelWidth) / 2;
        int top = Math.max(18, this.height / 8);
        int lineY = top + 10;

        gfx.drawString(this.font, this.title, panelLeft + 8, lineY, 0xFFFFFF, true);
        lineY += this.font.lineHeight + 2;
        gfx.drawString(this.font, "Client-global skills: config/p2s_skills/", panelLeft + 8, lineY, 0xBBBBBB, false);
        lineY += this.font.lineHeight + 2;
        gfx.drawString(this.font, "Total skills: " + skills.size(), panelLeft + 8, lineY, 0xAAAAAA, false);

        SkillStore.SkillMeta selected = skills.stream().filter(s -> s.id().equals(selectedId)).findFirst().orElse(null);
        int detailsX = panelLeft + panelWidth - 200;
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

        if (statusText != null && !statusText.isBlank()) {
            gfx.drawString(this.font, statusText, panelLeft + 8, top + 250, statusColor, false);
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
