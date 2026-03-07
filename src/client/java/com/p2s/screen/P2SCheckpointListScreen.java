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
    private static final int VISIBLE_ROWS = 10;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 50;

    private final Screen parent;
    private final List<Button> rowButtons = new ArrayList<>();
    private int scroll = 0;
    private EditBox checkpointNameInput;
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

        int panelWidth = Math.min(700, this.width - 40);
        int left = (this.width - panelWidth) / 2;
        int top = 40;
        int listTop = 92;
        int listWidth = panelWidth;

        checkpointNameInput = new EditBox(this.font, left, top, panelWidth, 20, P2SI18n.tr("screen.p2s.chat.checkpoint.name_hint"));
        checkpointNameInput.setMaxLength(120);
        checkpointNameInput.setValue(nameDraft == null ? "" : nameDraft);
        checkpointNameInput.setHint(P2SI18n.tr("screen.p2s.chat.checkpoint.name_hint"));
        addRenderableWidget(checkpointNameInput);

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int rowY = listTop + i * (ROW_HEIGHT + 2);
            final int row = i;
            Button rowBtn = Button.builder(Component.empty(), btn -> selectCheckpoint(row))
                    .bounds(left, rowY, listWidth, ROW_HEIGHT)
                    .build();
            rowButtons.add(rowBtn);
            addRenderableWidget(rowBtn);
        }

        int bottomY = listTop + VISIBLE_ROWS * (ROW_HEIGHT + 2) + 6;
        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.up"), btn -> {
            if (scroll > 0) {
                scroll--;
                refreshRows();
            }
        }).bounds(left, bottomY, 50, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.down"), btn -> {
            int maxScroll = Math.max(0, ClientSessionState.getCheckpoints().size() - VISIBLE_ROWS);
            if (scroll < maxScroll) {
                scroll++;
                refreshRows();
            }
        }).bounds(left + 56, bottomY, 60, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.create_short"), btn -> createCheckpoint())
                .bounds(left + 124, bottomY, 60, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.rename_short"), btn -> renameCheckpoint())
                .bounds(left + 190, bottomY, 70, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.rollback_short"), btn -> rollbackCheckpoint())
                .bounds(left + 266, bottomY, 70, 20).build());

        addRenderableWidget(Button.builder(modeLabel(), btn -> {
            ClientSessionState.toggleRollbackMode();
            btn.setMessage(modeLabel());
            refreshRows();
        }).bounds(left + 342, bottomY, 120, 20).build());

        addRenderableWidget(Button.builder(P2SI18n.tr("screen.p2s.common.back"), btn -> onClose())
                .bounds(left + panelWidth - 60, bottomY, 60, 20).build());

        refreshRows();
        lastSignature = buildSignature();
    }

    private void refreshFromStateIfNeeded() {
        String signature = buildSignature();
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        scroll = Math.min(scroll, Math.max(0, ClientSessionState.getCheckpoints().size() - VISIBLE_ROWS));
        if (checkpointNameInput != null && !checkpointNameInput.isFocused()) {
            checkpointNameInput.setValue(currentCheckpointLabel());
        }
        refreshRows();
    }

    private void refreshRows() {
        List<ClientSessionState.CheckpointInfo> checkpoints = ClientSessionState.getCheckpoints();
        ClientSessionState.CheckpointInfo selected = ClientSessionState.getSelectedCheckpoint();
        String selectedId = selected == null ? "" : selected.id();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = scroll + i;
            Button rowBtn = rowButtons.get(i);
            if (idx >= 0 && idx < checkpoints.size()) {
                ClientSessionState.CheckpointInfo checkpoint = checkpoints.get(idx);
                String label = checkpoint.label() == null || checkpoint.label().isBlank() ? checkpoint.id() : checkpoint.label();
                String revision = checkpoint.revision() == null || checkpoint.revision().isBlank() ? "-" : shortRevision(checkpoint.revision());
                boolean isSelected = checkpoint.id() != null && checkpoint.id().equals(selectedId);
                String prefix = isSelected ? "▶ " : "  ";
                rowBtn.setMessage(Component.literal(prefix + label + " · " + revision));
                rowBtn.visible = true;
                rowBtn.active = !isSelected;
            } else {
                rowBtn.setMessage(Component.empty());
                rowBtn.visible = false;
                rowBtn.active = false;
            }
        }
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
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(700, this.width - 40);
        int left = (this.width - panelWidth) / 2;
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
        return super.keyPressed(keyCode, scanCode, modifiers);
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
