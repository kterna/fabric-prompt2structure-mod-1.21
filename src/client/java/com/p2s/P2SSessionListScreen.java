package com.p2s;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class P2SSessionListScreen extends Screen {
    private static final int VISIBLE_ROWS = 10;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 50;

    private final Screen parent;
    private List<SessionPersistence.SessionIndexEntry> sessions = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Button> loadButtons = new ArrayList<>();
    private final List<Button> deleteButtons = new ArrayList<>();
    private int scroll = 0;
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    public P2SSessionListScreen(Screen parent) {
        super(Component.literal("P2S Sessions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        sessions = SessionPersistence.listSessions();
        statusText = "";

        clearWidgets();
        rowButtons.clear();
        loadButtons.clear();
        deleteButtons.clear();

        int panelWidth = Math.min(600, this.width - 40);
        int left = (this.width - panelWidth) / 2;
        int top = 40;

        int listWidth = panelWidth - BUTTON_WIDTH * 2 - 16;

        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int rowY = top + i * (ROW_HEIGHT + 2);
            final int row = i;

            Button rowBtn = Button.builder(Component.literal(""), btn -> {})
                    .bounds(left, rowY, listWidth, ROW_HEIGHT)
                    .build();
            rowBtn.active = false;
            rowButtons.add(rowBtn);
            addRenderableWidget(rowBtn);

            Button loadBtn = Button.builder(Component.literal("Load"), btn -> loadSession(row))
                    .bounds(left + listWidth + 4, rowY, BUTTON_WIDTH, ROW_HEIGHT)
                    .build();
            loadButtons.add(loadBtn);
            addRenderableWidget(loadBtn);

            Button deleteBtn = Button.builder(Component.literal("Del"), btn -> deleteSession(row))
                    .bounds(left + listWidth + 4 + BUTTON_WIDTH + 4, rowY, BUTTON_WIDTH, ROW_HEIGHT)
                    .build();
            deleteButtons.add(deleteBtn);
            addRenderableWidget(deleteBtn);
        }

        int bottomY = top + VISIBLE_ROWS * (ROW_HEIGHT + 2) + 4;
        addRenderableWidget(Button.builder(Component.literal("Up"), btn -> {
            if (scroll > 0) {
                scroll--;
                refreshRows();
            }
        }).bounds(left, bottomY, 50, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Down"), btn -> {
            int maxScroll = Math.max(0, sessions.size() - VISIBLE_ROWS);
            if (scroll < maxScroll) {
                scroll++;
                refreshRows();
            }
        }).bounds(left + 56, bottomY, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(left + panelWidth - 60, bottomY, 60, 20).build());

        refreshRows();
    }

    private void refreshRows() {
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = scroll + i;
            Button rowBtn = rowButtons.get(i);
            Button loadBtn = loadButtons.get(i);
            Button deleteBtn = deleteButtons.get(i);

            if (idx >= 0 && idx < sessions.size()) {
                SessionPersistence.SessionIndexEntry entry = sessions.get(idx);
                String title = entry.title();
                if (title.length() > 40) {
                    title = title.substring(0, 37) + "...";
                }
                String time = formatTime(entry.updatedAt());
                String label = title + " (" + entry.messageCount() + " msgs, " + time + ")";
                rowBtn.setMessage(Component.literal(label));
                rowBtn.visible = true;
                loadBtn.visible = true;
                loadBtn.active = true;
                deleteBtn.visible = true;
                deleteBtn.active = true;
            } else {
                rowBtn.setMessage(Component.literal(""));
                rowBtn.visible = false;
                loadBtn.visible = false;
                loadBtn.active = false;
                deleteBtn.visible = false;
                deleteBtn.active = false;
            }
        }
    }

    private void loadSession(int row) {
        int idx = scroll + row;
        if (idx < 0 || idx >= sessions.size()) {
            return;
        }
        SessionPersistence.SessionIndexEntry entry = sessions.get(idx);
        ClientAgentManager.restoreSession(entry.id());
        statusText = "Loaded: " + entry.title();
        statusColor = 0x55FF55;
        onClose();
    }

    private void deleteSession(int row) {
        int idx = scroll + row;
        if (idx < 0 || idx >= sessions.size()) {
            return;
        }
        SessionPersistence.SessionIndexEntry entry = sessions.get(idx);
        boolean ok = SessionPersistence.deleteSession(entry.id());
        if (ok) {
            sessions = SessionPersistence.listSessions();
            statusText = "Deleted";
            statusColor = 0x55FF55;
        } else {
            statusText = "Delete failed";
            statusColor = 0xFF5555;
        }
        refreshRows();
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "?";
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
            return sdf.format(new Date(millis));
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = Math.min(600, this.width - 40);
        int left = (this.width - panelWidth) / 2;
        gfx.drawString(this.font, "Session History (" + sessions.size() + ")", left, 24, 0xFFFFFF, true);

        if (statusText != null && !statusText.isBlank()) {
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
