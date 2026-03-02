package com.p2s;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class P2SSkillEditorScreen extends Screen {
    private static final int BODY_ROWS = 10;

    public enum Mode {
        CREATE,
        EDIT,
        RENAME
    }

    private final Screen parent;
    private final Mode mode;
    private final String skillId;

    private EditBox nameInput;
    private EditBox descInput;
    private final List<EditBox> bodyInputs = new ArrayList<>();
    private final List<String> bodyLines = new ArrayList<>();
    private int bodyPage = 0;

    private String originalBody = "";
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    private Button prevPageButton;
    private Button nextPageButton;

    public P2SSkillEditorScreen(Screen parent, Mode mode, String skillId) {
        super(Component.literal(mode == Mode.CREATE ? "Create Skill" : (mode == Mode.RENAME ? "Rename Skill" : "Edit Skill")));
        this.parent = parent;
        this.mode = mode == null ? Mode.CREATE : mode;
        this.skillId = skillId == null ? "" : skillId;
    }

    @Override
    protected void init() {
        super.init();
        loadInitialState();

        int panelWidth = Math.min(860, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(14, this.height / 10);

        nameInput = new EditBox(this.font, left + 10, top + 26, panelWidth - 20, 20, Component.literal("name"));
        nameInput.setMaxLength(128);
        addRenderableWidget(nameInput);

        descInput = new EditBox(this.font, left + 10, top + 56, panelWidth - 20, 20, Component.literal("description"));
        descInput.setMaxLength(256);
        addRenderableWidget(descInput);

        nameInput.setValue(extractName());
        descInput.setValue(extractDescription());

        int bodyTop = top + 94;
        int bodyWidth = panelWidth - 20;
        int rowHeight = 18;
        int rowSpacing = 3;

        bodyInputs.clear();
        if (mode != Mode.RENAME) {
            for (int i = 0; i < BODY_ROWS; i++) {
                EditBox row = new EditBox(this.font, left + 10, bodyTop + i * (rowHeight + rowSpacing), bodyWidth, rowHeight, Component.literal("body line"));
                row.setMaxLength(1024);
                bodyInputs.add(row);
                addRenderableWidget(row);
            }
        }
        loadBodyPage();

        int buttonY = top + 94 + BODY_ROWS * (rowHeight + rowSpacing) + 10;
        addRenderableWidget(Button.builder(Component.literal("Save"), btn -> saveSkill())
                .bounds(left + 10, buttonY, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> onClose())
                .bounds(left + 98, buttonY, 90, 20).build());

        if (mode != Mode.RENAME) {
            prevPageButton = addRenderableWidget(Button.builder(Component.literal("Prev"), btn -> changePage(-1))
                    .bounds(left + panelWidth - 190, buttonY, 80, 20).build());
            nextPageButton = addRenderableWidget(Button.builder(Component.literal("Next"), btn -> changePage(1))
                    .bounds(left + panelWidth - 100, buttonY, 80, 20).build());
            refreshPageButtons();
        }
    }

    private void loadInitialState() {
        bodyLines.clear();
        originalBody = "";
        if (mode == Mode.CREATE) {
            return;
        }
        SkillStore.SkillDocument doc = SkillStore.readSkill(skillId);
        if (doc == null) {
            return;
        }
        if (doc.body() != null) {
            originalBody = doc.body();
            String[] lines = doc.body().split("\\R", -1);
            for (String line : lines) {
                bodyLines.add(line == null ? "" : line);
            }
        }
        if (bodyLines.isEmpty()) {
            bodyLines.add("");
        }
        cachedName = doc.meta().name();
        cachedDescription = doc.meta().description();
    }

    private String cachedName = "";
    private String cachedDescription = "";

    private String extractName() {
        if (cachedName == null || cachedName.isBlank()) {
            if (mode == Mode.CREATE) {
                return "";
            }
            return skillId;
        }
        return cachedName;
    }

    private String extractDescription() {
        return cachedDescription == null ? "" : cachedDescription;
    }

    private void saveBodyPage() {
        if (mode == Mode.RENAME) {
            return;
        }
        ensureBodyCapacity((bodyPage + 1) * BODY_ROWS);
        for (int i = 0; i < bodyInputs.size(); i++) {
            int idx = bodyPage * BODY_ROWS + i;
            String value = bodyInputs.get(i).getValue();
            bodyLines.set(idx, value == null ? "" : value);
        }
    }

    private void loadBodyPage() {
        if (mode == Mode.RENAME) {
            return;
        }
        ensureBodyCapacity((bodyPage + 1) * BODY_ROWS);
        for (int i = 0; i < bodyInputs.size(); i++) {
            int idx = bodyPage * BODY_ROWS + i;
            String value = idx < bodyLines.size() ? bodyLines.get(idx) : "";
            bodyInputs.get(i).setValue(value == null ? "" : value);
        }
    }

    private void changePage(int delta) {
        saveBodyPage();
        int next = Math.max(0, bodyPage + delta);
        if (next == bodyPage) {
            return;
        }
        bodyPage = next;
        loadBodyPage();
        refreshPageButtons();
    }

    private void refreshPageButtons() {
        if (prevPageButton == null || nextPageButton == null) {
            return;
        }
        prevPageButton.active = bodyPage > 0;
        nextPageButton.active = true;
    }

    private void ensureBodyCapacity(int size) {
        while (bodyLines.size() < size) {
            bodyLines.add("");
        }
    }

    private String buildBodyText() {
        saveBodyPage();
        int end = bodyLines.size();
        while (end > 0 && (bodyLines.get(end - 1) == null || bodyLines.get(end - 1).isBlank())) {
            end -= 1;
        }
        if (end <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(bodyLines.get(i) == null ? "" : bodyLines.get(i));
        }
        return sb.toString();
    }

    private void saveSkill() {
        String name = nameInput == null ? "" : nameInput.getValue();
        String desc = descInput == null ? "" : descInput.getValue();
        if (name == null || name.isBlank()) {
            statusText = "Name is required";
            statusColor = 0xFF5555;
            return;
        }

        try {
            SkillStore.SkillDocument saved;
            if (mode == Mode.CREATE) {
                String body = buildBodyText();
                saved = SkillStore.createSkill(name, desc, body);
            } else if (mode == Mode.EDIT) {
                String body = buildBodyText();
                saved = SkillStore.updateSkill(skillId, name, desc, body);
            } else {
                SkillStore.SkillDocument renamed = SkillStore.renameSkill(skillId, name);
                if (renamed == null) {
                    saved = null;
                } else {
                    saved = SkillStore.updateSkill(renamed.meta().id(), name, desc, originalBody);
                }
            }
            if (saved == null) {
                statusText = "Save failed";
                statusColor = 0xFF5555;
                return;
            }
            if (parent instanceof P2SConfigScreen cfg) {
                cfg.onSkillSaved(saved.meta().id());
            }
            if (this.minecraft != null) {
                this.minecraft.setScreen(parent);
            }
        } catch (Exception e) {
            statusText = "Save failed: " + e.getMessage();
            statusColor = 0xFF5555;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (nameInput != null && nameInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (descInput != null && descInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        for (EditBox box : bodyInputs) {
            if (box.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (nameInput != null && nameInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (descInput != null && descInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        for (EditBox box : bodyInputs) {
            if (box.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(860, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(14, this.height / 10);

        gfx.drawString(this.font, this.title, left + 10, top + 8, 0xFFFFFF, true);
        gfx.drawString(this.font, "name", left + 10, top + 16, 0xCCCCCC, false);
        gfx.drawString(this.font, "description", left + 10, top + 46, 0xCCCCCC, false);
        if (mode != Mode.RENAME) {
            gfx.drawString(this.font, "body (" + (bodyPage + 1) + ")", left + 10, top + 84, 0xCCCCCC, false);
        } else {
            gfx.drawString(this.font, "Rename only: body unchanged", left + 10, top + 84, 0xAAAAAA, false);
        }
        if (statusText != null && !statusText.isBlank()) {
            gfx.drawString(this.font, statusText, left + 10, top + this.height / 2, statusColor, false);
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
