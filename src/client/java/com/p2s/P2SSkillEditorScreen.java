package com.p2s;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class P2SSkillEditorScreen extends Screen {
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
    private P2SMultiLineTextEditor bodyEditor;

    private String originalBody = "";
    private Component statusText = Component.empty();
    private int statusColor = 0xAAAAAA;

    public P2SSkillEditorScreen(Screen parent, Mode mode, String skillId) {
        super(switch (mode == null ? Mode.CREATE : mode) {
            case CREATE -> P2SI18n.tr("screen.p2s.skill_editor.title.create");
            case RENAME -> P2SI18n.tr("screen.p2s.skill_editor.title.rename");
            case EDIT -> P2SI18n.tr("screen.p2s.skill_editor.title.edit");
        });
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

        nameInput = new EditBox(this.font, left + 10, top + 26, panelWidth - 20, 20, P2SI18n.tr("screen.p2s.skill_editor.name"));
        nameInput.setMaxLength(128);
        addRenderableWidget(nameInput);

        descInput = new EditBox(this.font, left + 10, top + 56, panelWidth - 20, 20, P2SI18n.tr("screen.p2s.skill_editor.description"));
        descInput.setMaxLength(256);
        addRenderableWidget(descInput);

        nameInput.setValue(extractName());
        descInput.setValue(extractDescription());

        int bodyTop = top + 94;
        int bodyWidth = panelWidth - 20;
        int buttonY;
        if (mode != Mode.RENAME) {
            int bodyHeight = Math.max(72, this.height - bodyTop - 44);
            bodyEditor = new P2SMultiLineTextEditor(this.font);
            bodyEditor.setMaxLength(65536);
            bodyEditor.setBounds(left + 10, bodyTop, bodyWidth, bodyHeight);
            bodyEditor.setText(originalBody);
            buttonY = bodyTop + bodyHeight + 10;
        } else {
            bodyEditor = null;
            buttonY = top + 104;
        }

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.save"), btn -> saveSkill())
                .bounds(left + 10, buttonY, 80, 20).build());
        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.cancel"), btn -> onClose())
                .bounds(left + 98, buttonY, 90, 20).build());
    }

    private void loadInitialState() {
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

    private String buildBodyText() {
        if (bodyEditor == null) {
            return "";
        }
        String[] lines = bodyEditor.getText().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int end = lines.length;
        while (end > 0 && (lines[end - 1] == null || lines[end - 1].isBlank())) {
            end -= 1;
        }
        if (end <= 0) {
            return "";
        }
        return String.join("\n", java.util.Arrays.copyOf(lines, end));
    }

    private void saveSkill() {
        String name = nameInput == null ? "" : nameInput.getValue();
        String desc = descInput == null ? "" : descInput.getValue();
        if (name == null || name.isBlank()) {
            statusText = P2SI18n.tr("screen.p2s.skill_editor.status.name_required");
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
                statusText = P2SI18n.tr("screen.p2s.skill_editor.status.save_failed");
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
            statusText = P2SI18n.tr("screen.p2s.skill_editor.status.save_failed_with_reason", e.getMessage());
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
        if (bodyEditor != null && bodyEditor.keyPressed(keyCode, modifiers)) {
            return true;
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
        if (bodyEditor != null && bodyEditor.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (bodyEditor != null && bodyEditor.mouseClicked(mouseX, mouseY, button)) {
            if (nameInput != null) nameInput.setFocused(false);
            if (descInput != null) descInput.setFocused(false);
            return true;
        }
        if (button == 0 && bodyEditor != null) {
            bodyEditor.setFocused(false);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (bodyEditor != null && bodyEditor.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (bodyEditor != null && bodyEditor.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (bodyEditor != null && bodyEditor.mouseScrolled(mouseX, mouseY, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(860, this.width - 24);
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(14, this.height / 10);

        gfx.drawString(this.font, this.title, left + 10, top + 8, 0xFFFFFF, true);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.skill_editor.name"), left + 10, top + 16, 0xCCCCCC, false);
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.skill_editor.description"), left + 10, top + 46, 0xCCCCCC, false);
        if (mode != Mode.RENAME) {
            if (bodyEditor != null) {
                bodyEditor.render(gfx);
            }
            gfx.drawString(this.font, P2SI18n.tr("screen.p2s.skill_editor.body"), left + 10, top + 84, 0xCCCCCC, false);
        } else {
            gfx.drawString(this.font, P2SI18n.tr("screen.p2s.skill_editor.rename_only"), left + 10, top + 84, 0xAAAAAA, false);
        }
        if (statusText != null && !statusText.getString().isBlank()) {
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
