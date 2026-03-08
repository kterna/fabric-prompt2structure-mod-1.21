package com.p2s.screen;

import com.google.gson.JsonObject;
import com.p2s.ClientSessionState;
import com.p2s.P2SI18n;
import com.p2s.network.C2SSessionActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class P2SCheckpointListScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_GAP = 2;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MIN_VISIBLE_ROWS = 6;
    private static final int MAX_VISIBLE_ROWS = 18;

    private final Screen parent;
    private final List<Button> rowButtons = new ArrayList<>();
    private int scroll = 0;
    private int visibleRows = MIN_VISIBLE_ROWS;
    private int listLeft = 0;
    private int listTop = 0;
    private int listRight = 0;
    private int listBottom = 0;

    private EditBox checkpointNameInput;
    private Button scrollUpButton;
    private Button scrollDownButton;
    private Button createButton;
    private Button renameButton;
    private Button rollbackButton;
    private Button modeButton;
    private Component statusText = Component.empty();
    private int statusColor = 0xAAAAAA;
    private String lastSignature = "";

    public P2SCheckpointListScreen(Screen parent) {
        super(P2SI18n.tr("screen.p2s.checkpoints.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        String nameDraft = checkpointNameInput == null ? currentCheckpointLabel() : checkpointNameInput.getValue();

        clearWidgets();
        rowButtons.clear();

        int panelWidth = Math.min(760, this.width - 40);
        int left = (this.width - panelWidth) / 2;
        int top = 40;
        int rowStep = ROW_HEIGHT + ROW_GAP;
        listTop = 92;

        int reservedBottom = 58;
        int availableListHeight = Math.max(ROW_HEIGHT, this.height - listTop - reservedBottom - BUTTON_HEIGHT - 12);
        visibleRows = Math.max(MIN_VISIBLE_ROWS, Math.min(MAX_VISIBLE_ROWS, availableListHeight / rowStep));
        int listHeight = visibleRows * rowStep - ROW_GAP;
        int bottomY = listTop + listHeight + 8;

        listLeft = left;
        listRight = left + panelWidth;
        listBottom = listTop + listHeight;

        checkpointNameInput = new EditBox(this.font, left, top, panelWidth, 20, P2SI18n.tr("screen.p2s.chat.checkpoint.name_hint"));
        checkpointNameInput.setMaxLength(120);
        checkpointNameInput.setValue(nameDraft == null ? "" : nameDraft);
        checkpointNameInput.setHint(P2SI18n.tr("screen.p2s.chat.checkpoint.name_hint"));
        addRenderableWidget(checkpointNameInput);

        for (int i = 0; i < visibleRows; i++) {
            int rowY = listTop + i * rowStep;
            final int row = i;
            Button rowBtn = Button.builder(Component.empty(), btn -> selectCheckpoint(row))
                    .bounds(left, rowY, panelWidth, ROW_HEIGHT)
                    .build();
            rowButtons.add(rowBtn);
            addRenderableWidget(rowBtn);
        }

        scrollUpButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.up"), btn -> scrollBy(-1))
                .bounds(left, bottomY, 50, BUTTON_HEIGHT).build());

        scrollDownButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.down"), btn -> scrollBy(1))
                .bounds(left + 56, bottomY, 60, BUTTON_HEIGHT).build());

        createButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.create_short"), btn -> createCheckpoint())
                .bounds(left + 124, bottomY, 60, BUTTON_HEIGHT).build());

        renameButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.rename_short"), btn -> renameCheckpoint())
                .bounds(left + 190, bottomY, 70, BUTTON_HEIGHT).build());

        rollbackButton = addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.rollback_short"), btn -> rollbackCheckpoint())
                .bounds(left + 266, bottomY, 70, BUTTON_HEIGHT).build());

        modeButton = addRenderableWidget(Button.builder(modeLabel(), btn -> {
            ClientSessionState.toggleRollbackMode();
            btn.setMessage(modeLabel());
            refreshRows();
            refreshControls();
        }).bounds(left + 342, bottomY, 120, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.back"), btn -> onClose())
                .bounds(left + panelWidth - 60, bottomY, 60, BUTTON_HEIGHT).build());

        refreshRows();
        refreshControls();
        lastSignature = buildSignature();
    }

    private void refreshFromStateIfNeeded() {
        String signature = buildSignature();
        if (signature.equals(lastSignature)) {
            refreshControls();
            return;
        }
        lastSignature = signature;
        scroll = Math.min(scroll, maxScroll());
        if (checkpointNameInput != null && !checkpointNameInput.isFocused()) {
            checkpointNameInput.setValue(currentCheckpointLabel());
        }
        refreshRows();
        refreshControls();
    }

    private void refreshRows() {
        List<ClientSessionState.CheckpointInfo> checkpoints = ClientSessionState.getCheckpoints();
        ClientSessionState.CheckpointInfo selected = ClientSessionState.getSelectedCheckpoint();
        String selectedId = selected == null ? "" : selected.id();
        for (int i = 0; i < visibleRows; i++) {
            int idx = scroll + i;
            Button rowBtn = rowButtons.get(i);
            if (idx >= 0 && idx < checkpoints.size()) {
                ClientSessionState.CheckpointInfo checkpoint = checkpoints.get(idx);
                String label = checkpoint.label() == null || checkpoint.label().isBlank() ? checkpoint.id() : checkpoint.label();
                String revision = checkpoint.revision() == null || checkpoint.revision().isBlank() ? "-" : shortRevision(checkpoint.revision());
                boolean isSelected = checkpoint.id() != null && checkpoint.id().equals(selectedId);
                String prefix = isSelected ? "› " : "  ";
                rowBtn.setMessage(Component.literal(prefix + label + "  [" + revision + "]"));
                rowBtn.visible = true;
                rowBtn.active = !isSelected;
            } else {
                rowBtn.setMessage(Component.empty());
                rowBtn.visible = false;
                rowBtn.active = false;
            }
        }
    }

    private void refreshControls() {
        boolean hasSelected = ClientSessionState.getSelectedCheckpoint() != null;
        String label = checkpointNameInput == null ? "" : checkpointNameInput.getValue().trim();
        if (scrollUpButton != null) {
            scrollUpButton.active = scroll > 0;
        }
        if (scrollDownButton != null) {
            scrollDownButton.active = scroll < maxScroll();
        }
        if (createButton != null) {
            createButton.active = true;
        }
        if (renameButton != null) {
            renameButton.active = hasSelected && !label.isEmpty();
        }
        if (rollbackButton != null) {
            rollbackButton.active = hasSelected;
        }
        if (modeButton != null) {
            modeButton.active = hasSelected;
            modeButton.setMessage(modeLabel());
        }
    }

    private int maxScroll() {
        return Math.max(0, ClientSessionState.getCheckpoints().size() - visibleRows);
    }

    private void scrollBy(int amount) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + amount));
        refreshRows();
        refreshControls();
    }

    private void ensureVisible(int index) {
        if (index < 0) {
            return;
        }
        if (index < scroll) {
            scroll = index;
        } else if (index >= scroll + visibleRows) {
            scroll = Math.max(0, index - visibleRows + 1);
        }
    }

    private void moveSelection(int delta) {
        List<ClientSessionState.CheckpointInfo> checkpoints = ClientSessionState.getCheckpoints();
        if (checkpoints.isEmpty()) {
            return;
        }
        int selectedIndex = -1;
        ClientSessionState.CheckpointInfo selected = ClientSessionState.getSelectedCheckpoint();
        String selectedId = selected == null ? "" : selected.id();
        for (int i = 0; i < checkpoints.size(); i++) {
            ClientSessionState.CheckpointInfo checkpoint = checkpoints.get(i);
            if (checkpoint != null && checkpoint.id() != null && checkpoint.id().equals(selectedId)) {
                selectedIndex = i;
                break;
            }
        }
        int targetIndex;
        if (selectedIndex < 0) {
            targetIndex = delta > 0 ? 0 : checkpoints.size() - 1;
        } else {
            targetIndex = Math.max(0, Math.min(checkpoints.size() - 1, selectedIndex + delta));
        }
        ClientSessionState.CheckpointInfo checkpoint = checkpoints.get(targetIndex);
        if (checkpoint == null || checkpoint.id() == null || checkpoint.id().isBlank()) {
            return;
        }
        ClientSessionState.selectCheckpointById(checkpoint.id());
        if (checkpointNameInput != null && !checkpointNameInput.isFocused()) {
            checkpointNameInput.setValue(checkpoint.label() == null ? "" : checkpoint.label());
        }
        ensureVisible(targetIndex);
        refreshRows();
        refreshControls();
    }

    private void selectCheckpoint(int row) {
        int idx = scroll + row;
        List<ClientSessionState.CheckpointInfo> checkpoints = ClientSessionState.getCheckpoints();
        if (idx < 0 || idx >= checkpoints.size()) {
            return;
        }
        ClientSessionState.CheckpointInfo checkpoint = checkpoints.get(idx);
        if (checkpoint == null || checkpoint.id() == null || checkpoint.id().isBlank()) {
            return;
        }
        ClientSessionState.selectCheckpointById(checkpoint.id());
        if (checkpointNameInput != null) {
            checkpointNameInput.setValue(checkpoint.label() == null ? "" : checkpoint.label());
        }
        refreshRows();
        refreshControls();
    }

    private void createCheckpoint() {
        JsonObject payload = new JsonObject();
        String label = checkpointNameInput == null ? "" : checkpointNameInput.getValue().trim();
        payload.addProperty("label", label.isEmpty() ? "manual-" + ClientSessionState.getTurnCount() : label);
        sendSessionAction("create_checkpoint", payload.toString());
        statusText = P2SI18n.tr("screen.p2s.checkpoints.status.creating");
        statusColor = 0xAAAAAA;
    }

    private void renameCheckpoint() {
        ClientSessionState.CheckpointInfo checkpoint = ClientSessionState.getSelectedCheckpoint();
        if (checkpoint == null || checkpoint.id() == null || checkpoint.id().isBlank()) {
            return;
        }
        String label = checkpointNameInput == null ? "" : checkpointNameInput.getValue().trim();
        if (label.isEmpty()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("checkpoint_id", checkpoint.id());
        payload.addProperty("label", label);
        sendSessionAction("rename_checkpoint", payload.toString());
        statusText = P2SI18n.tr("screen.p2s.checkpoints.status.renaming", label);
        statusColor = 0xAAAAAA;
    }

    private void rollbackCheckpoint() {
        ClientSessionState.CheckpointInfo checkpoint = ClientSessionState.getSelectedCheckpoint();
        if (checkpoint == null || checkpoint.id() == null || checkpoint.id().isBlank()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("id", checkpoint.id());
        payload.addProperty("mode", ClientSessionState.getRollbackMode());
        sendSessionAction("rollback_checkpoint", payload.toString());
        statusText = P2SI18n.tr("screen.p2s.checkpoints.status.rolling_back", checkpoint.label() == null ? checkpoint.id() : checkpoint.label());
        statusColor = 0xAAAAAA;
    }

    private void sendSessionAction(String action, String payload) {
        ClientPlayNetworking.send(new C2SSessionActionPayload(action, payload == null ? "" : payload));
    }

    private Component modeLabel() {
        return P2SI18n.tr("screen.p2s.checkpoints.mode", P2SI18n.rollbackModeComponent(ClientSessionState.getRollbackMode()).getString());
    }

    private String currentCheckpointLabel() {
        ClientSessionState.CheckpointInfo checkpoint = ClientSessionState.getSelectedCheckpoint();
        return checkpoint == null || checkpoint.label() == null ? "" : checkpoint.label();
    }

    private String buildSignature() {
        StringBuilder sb = new StringBuilder();
        ClientSessionState.CheckpointInfo selected = ClientSessionState.getSelectedCheckpoint();
        sb.append(selected == null ? "" : selected.id()).append('|').append(ClientSessionState.getRollbackMode());
        for (ClientSessionState.CheckpointInfo checkpoint : ClientSessionState.getCheckpoints()) {
            if (checkpoint == null) {
                continue;
            }
            sb.append('|').append(checkpoint.id()).append(':').append(checkpoint.label()).append(':').append(checkpoint.revision());
        }
        return sb.toString();
    }

    private static String shortRevision(String revision) {
        if (revision == null) {
            return "-";
        }
        String value = revision.trim();
        return value.length() <= 16 ? value : value.substring(0, 16) + "...";
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        refreshFromStateIfNeeded();
        super.renderBackground(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(760, this.width - 40);
        int left = (this.width - panelWidth) / 2;
        int panelLeft = left - 8;
        int panelTop = 18;
        int panelRight = left + panelWidth + 8;
        int panelBottom = Math.min(this.height - 18, listBottom + 38);
        gfx.fill(panelLeft, panelTop, panelRight, panelBottom, 0xC0141A26);
        gfx.fill(panelLeft, panelTop, panelRight, panelTop + 1, 0xFF2F3A4D);
        gfx.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, 0xFF2F3A4D);
        gfx.fill(panelLeft, panelTop, panelLeft + 1, panelBottom, 0xFF2F3A4D);
        gfx.fill(panelRight - 1, panelTop, panelRight, panelBottom, 0xFF2F3A4D);

        super.render(gfx, mouseX, mouseY, delta);

        String fileName = ClientSessionState.getSelectedWorkspaceLabel();
        if (fileName == null || fileName.isBlank()) {
            fileName = P2SI18n.tr("screen.p2s.workspace.unnamed").getString();
        }
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.checkpoints.header", fileName, ClientSessionState.getCheckpoints().size()), left, 24, 0xFFFFFF, true);

        if (statusText != null && !statusText.getString().isBlank()) {
            gfx.drawString(this.font, statusText, left, this.height - 20, statusColor, false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP && (checkpointNameInput == null || !checkpointNameInput.isFocused())) {
            moveSelection(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN && (checkpointNameInput == null || !checkpointNameInput.isFocused())) {
            moveSelection(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            moveSelection(-Math.max(1, visibleRows - 1));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            moveSelection(Math.max(1, visibleRows - 1));
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && checkpointNameInput != null && checkpointNameInput.isFocused()) {
            if (ClientSessionState.getSelectedCheckpoint() != null) {
                renameCheckpoint();
            } else {
                createCheckpoint();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY <= listBottom) {
            if (verticalAmount > 0) {
                scrollBy(-1);
            } else if (verticalAmount < 0) {
                scrollBy(1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
