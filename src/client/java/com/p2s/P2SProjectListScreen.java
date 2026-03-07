package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class P2SProjectListScreen extends Screen {
    private static final int MAX_VISIBLE_ROWS = 10;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 50;
    private static final int INPUT_HEIGHT = 20;
    private static final int INPUT_GAP = 8;

    private final Screen parent;
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Button> openButtons = new ArrayList<>();
    private List<ProjectEntry> projects = new ArrayList<>();
    private EditBox nameInput;
    private EditBox descriptionInput;
    private Button createButton;
    private int scroll = 0;
    private int visibleRows = MAX_VISIBLE_ROWS;
    private boolean loading = false;
    private String statusText = "";
    private int statusColor = 0xAAAAAA;

    public P2SProjectListScreen(Screen parent) {
        super(Component.literal("P2S Projects"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        String nameDraft = nameInput == null ? "" : nameInput.getValue();
        String descriptionDraft = descriptionInput == null ? "" : descriptionInput.getValue();

        clearWidgets();
        rowButtons.clear();
        openButtons.clear();

        int panelWidth = getPanelWidth();
        int left = (this.width - panelWidth) / 2;
        int labelY = 72;
        int inputY = 84;
        int inputWidth = (panelWidth - INPUT_GAP) / 2;
        int listTop = 116;
        visibleRows = Math.max(4, Math.min(MAX_VISIBLE_ROWS, (this.height - listTop - 56) / (ROW_HEIGHT + 2)));
        int listWidth = panelWidth - BUTTON_WIDTH - 12;

        nameInput = new EditBox(this.font, left, inputY, inputWidth, INPUT_HEIGHT, Component.literal("project name"));
        nameInput.setMaxLength(120);
        nameInput.setValue(nameDraft);
        nameInput.setHint(Component.literal("Project name (optional)"));
        addRenderableWidget(nameInput);

        descriptionInput = new EditBox(this.font, left + inputWidth + INPUT_GAP, inputY, panelWidth - inputWidth - INPUT_GAP, INPUT_HEIGHT, Component.literal("remark"));
        descriptionInput.setMaxLength(240);
        descriptionInput.setValue(descriptionDraft);
        descriptionInput.setHint(Component.literal("Remark / description (optional)"));
        addRenderableWidget(descriptionInput);

        for (int i = 0; i < visibleRows; i++) {
            int rowY = listTop + i * (ROW_HEIGHT + 2);
            final int row = i;

            Button rowBtn = Button.builder(Component.literal(""), btn -> {})
                    .bounds(left, rowY, listWidth, ROW_HEIGHT)
                    .build();
            rowBtn.active = false;
            rowButtons.add(rowBtn);
            addRenderableWidget(rowBtn);

            Button openBtn = Button.builder(Component.literal("Open"), btn -> openProject(row))
                    .bounds(left + listWidth + 4, rowY, BUTTON_WIDTH, ROW_HEIGHT)
                    .build();
            openButtons.add(openBtn);
            addRenderableWidget(openBtn);
        }

        int bottomY = listTop + visibleRows * (ROW_HEIGHT + 2) + 4;
        addRenderableWidget(Button.builder(Component.literal("Up"), btn -> {
            if (scroll > 0) {
                scroll--;
                refreshRows();
            }
        }).bounds(left, bottomY, 50, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Down"), btn -> {
            int maxScroll = maxScroll();
            if (scroll < maxScroll) {
                scroll++;
                refreshRows();
            }
        }).bounds(left + 56, bottomY, 60, 20).build());

        createButton = addRenderableWidget(Button.builder(Component.literal("Create Project"), btn -> createProject())
                .bounds(left + 124, bottomY, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Refresh"), btn -> loadProjects())
                .bounds(left + 230, bottomY, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), btn -> onClose())
                .bounds(left + panelWidth - 60, bottomY, 60, 20).build());

        refreshRows();
        loadProjects();
        setInitialFocus(nameInput);

        if (nameInput != null) {
            nameInput.setFocused(true);
        }
    }

    private int getPanelWidth() {
        return Math.min(720, this.width - 40);
    }

    private int maxScroll() {
        return Math.max(0, projects.size() - visibleRows);
    }

    private void loadProjects() {
        loading = true;
        statusText = "Loading projects...";
        statusColor = 0xAAAAAA;
        refreshRows();
        ClientToolBridge.call("list_projects", new JsonObject())
                .thenAccept(result -> {
                    List<ProjectEntry> loaded = new ArrayList<>();
                    JsonArray items = result.has("projects") && result.get("projects").isJsonArray()
                            ? result.getAsJsonArray("projects")
                            : new JsonArray();
                    for (JsonElement element : items) {
                        if (element == null || !element.isJsonObject()) {
                            continue;
                        }
                        JsonObject obj = element.getAsJsonObject();
                        JsonObject origin = obj.has("bounds_origin") && obj.get("bounds_origin").isJsonObject()
                                ? obj.get("bounds_origin").getAsJsonObject()
                                : new JsonObject();
                        JsonObject size = obj.has("bounds_size") && obj.get("bounds_size").isJsonObject()
                                ? obj.get("bounds_size").getAsJsonObject()
                                : new JsonObject();
                        loaded.add(new ProjectEntry(
                                getString(obj, "id"),
                                getString(obj, "name"),
                                getString(obj, "description"),
                                getLong(obj, "updated_at"),
                                getInt(obj, "workspace_count"),
                                getInt(origin, "x"),
                                getInt(origin, "y"),
                                getInt(origin, "z"),
                                getInt(size, "x"),
                                getInt(size, "y"),
                                getInt(size, "z")
                        ));
                    }
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            projects = loaded;
                            loading = false;
                            scroll = Math.min(scroll, maxScroll());
                            statusText = result.has("warning") ? getString(result, "warning") : "";
                            statusColor = result.has("warning") ? 0xFFAA55 : 0xAAAAAA;
                            refreshRows();
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            loading = false;
                            statusText = "Load failed: " + shortError(ex.getMessage());
                            statusColor = 0xFF5555;
                            refreshRows();
                        });
                    }
                    return null;
                });
    }

    private void createProject() {
        SelectionSnapshot selection = currentSelection();
        if (!selection.complete()) {
            statusText = "Create project requires a complete selection.";
            statusColor = 0xFFAA55;
            return;
        }

        JsonObject args = new JsonObject();
        String name = nameInput == null ? "" : nameInput.getValue().trim();
        String description = descriptionInput == null ? "" : descriptionInput.getValue().trim();
        if (!name.isBlank()) {
            args.addProperty("name", name);
        }
        if (!description.isBlank()) {
            args.addProperty("description", description);
        }

        loading = true;
        statusText = "Creating project...";
        statusColor = 0xAAAAAA;
        refreshRows();

        ClientToolBridge.call("create_project", args)
                .thenAccept(result -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            loading = false;
                            if (!isToolOk(result)) {
                                statusText = "Create failed: " + shortError(getString(result, "error"));
                                statusColor = 0xFF5555;
                                refreshRows();
                                return;
                            }
                            ClientAgentManager.onProjectChanged();
                            statusText = "Created: " + getString(result, "name");
                            statusColor = 0x55FF55;
                            onClose();
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            loading = false;
                            statusText = "Create failed: " + shortError(ex.getMessage());
                            statusColor = 0xFF5555;
                            refreshRows();
                        });
                    }
                    return null;
                });
    }

    private void openProject(int row) {
        int idx = scroll + row;
        if (idx < 0 || idx >= projects.size()) {
            return;
        }
        ProjectEntry entry = projects.get(idx);
        JsonObject args = new JsonObject();
        args.addProperty("id", entry.id());
        loading = true;
        statusText = "Opening: " + entry.name();
        statusColor = 0xAAAAAA;
        refreshRows();
        ClientToolBridge.call("open_project", args)
                .thenAccept(result -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            loading = false;
                            if (!isToolOk(result)) {
                                statusText = "Open failed: " + shortError(getString(result, "error"));
                                statusColor = 0xFF5555;
                                refreshRows();
                                return;
                            }
                            ClientAgentManager.onProjectChanged();
                            statusText = "Opened: " + entry.name();
                            statusColor = 0x55FF55;
                            onClose();
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            loading = false;
                            statusText = "Open failed: " + shortError(ex.getMessage());
                            statusColor = 0xFF5555;
                            refreshRows();
                        });
                    }
                    return null;
                });
    }

    private void refreshRows() {
        if (createButton != null) {
            createButton.active = !loading && currentSelection().complete();
        }
        for (int i = 0; i < visibleRows; i++) {
            int idx = scroll + i;
            Button rowBtn = rowButtons.get(i);
            Button openBtn = openButtons.get(i);
            if (idx >= 0 && idx < projects.size()) {
                ProjectEntry entry = projects.get(idx);
                String title = entry.name();
                if (title.length() > 28) {
                    title = title.substring(0, 25) + "...";
                }
                String bbox = "@(" + entry.originX() + "," + entry.originY() + "," + entry.originZ() + ") "
                        + entry.sizeX() + "x" + entry.sizeY() + "x" + entry.sizeZ();
                String label = title + " | " + entry.workspaceCount() + " files | " + bbox + " | " + formatTime(entry.updatedAt());
                rowBtn.setMessage(Component.literal(label));
                rowBtn.visible = true;
                openBtn.visible = true;
                openBtn.active = !loading;
            } else {
                rowBtn.setMessage(Component.literal(""));
                rowBtn.visible = false;
                openBtn.visible = false;
                openBtn.active = false;
            }
        }
    }

    private SelectionSnapshot currentSelection() {
        BlockPos pos1 = ClientSelectionManager.getPos1();
        BlockPos pos2 = ClientSelectionManager.getPos2();
        if (pos1 == null || pos2 == null) {
            return new SelectionSnapshot(false, pos1, pos2, null, null, 0, 0, 0);
        }
        BlockPos min = new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        );
        return new SelectionSnapshot(
                true,
                pos1,
                pos2,
                min,
                max,
                max.getX() - min.getX() + 1,
                max.getY() - min.getY() + 1,
                max.getZ() - min.getZ() + 1
        );
    }

    private static boolean isToolOk(JsonObject result) {
        if (result == null || !result.has("ok")) {
            return false;
        }
        try {
            return result.get("ok").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String formatPos(BlockPos pos) {
        if (pos == null) {
            return "-";
        }
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String shortError(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.length() > 80 ? raw.substring(0, 77) + "..." : raw;
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "?";
        }
        try {
            return new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
        } catch (Exception e) {
            return "?";
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long getLong(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return 0L;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        super.renderBackground(gfx, mouseX, mouseY, delta);
        super.render(gfx, mouseX, mouseY, delta);

        int panelWidth = getPanelWidth();
        int left = (this.width - panelWidth) / 2;
        int inputWidth = (panelWidth - INPUT_GAP) / 2;
        int labelY = 72;
        SelectionSnapshot selection = currentSelection();

        gfx.drawString(this.font, "Projects (" + projects.size() + ")", left, 24, 0xFFFFFF, true);
        if (selection.complete()) {
            gfx.drawString(this.font,
                    "Selection: " + formatPos(selection.pos1()) + " -> " + formatPos(selection.pos2()),
                    left,
                    44,
                    0xCFE1FF,
                    false);
            gfx.drawString(this.font,
                    "Bounds: origin " + formatPos(selection.min()) + " | size " + selection.sizeX() + "x" + selection.sizeY() + "x" + selection.sizeZ(),
                    left,
                    56,
                    0xAAAAAA,
                    false);
        } else {
            gfx.drawString(this.font,
                    "Selection incomplete. Use the selection tool to set both points before creating a project.",
                    left,
                    44,
                    0xFFCC66,
                    false);
            gfx.drawString(this.font,
                    "pos1: " + formatPos(selection.pos1()) + " | pos2: " + formatPos(selection.pos2()),
                    left,
                    56,
                    0x888888,
                    false);
        }
        gfx.drawString(this.font, "Name", left, labelY, 0xCCCCCC, false);
        gfx.drawString(this.font, "Remark", left + inputWidth + INPUT_GAP, labelY, 0xCCCCCC, false);
        if (createButton != null) {
            createButton.active = !loading && selection.complete();
        }

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
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && ((nameInput != null && nameInput.isFocused()) || (descriptionInput != null && descriptionInput.isFocused()))) {
            createProject();
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

    private record SelectionSnapshot(
            boolean complete,
            BlockPos pos1,
            BlockPos pos2,
            BlockPos min,
            BlockPos max,
            int sizeX,
            int sizeY,
            int sizeZ
    ) {
    }

    private record ProjectEntry(
            String id,
            String name,
            String description,
            long updatedAt,
            int workspaceCount,
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ
    ) {
    }
}
