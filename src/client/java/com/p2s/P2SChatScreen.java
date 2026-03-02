package com.p2s;

import com.p2s.network.C2SSessionActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class P2SChatScreen extends Screen {
    private static final int PADDING = 8;
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 22;
    private static final int LINE_SPACING = 2;
    private static final int PANEL_MIN_WIDTH = 240;
    private static final int TOP_BUTTON_HEIGHT = 20;
    private static final int SMALL_BUTTON_WIDTH = 52;
    private static final int CHOICE_BUTTON_COUNT = 3;

    private EditBox input;
    private Button sendButton;
    private Button configButton;
    private Button applyButton;
    private Button discardButton;
    private Button undoButton;
    private Button redoButton;
    private final List<Button> choiceButtons = new ArrayList<>();
    private double scrollOffset;

    private boolean discardReasonMode = false;
    private EditBox discardReasonInput;
    private Button discardOkButton;
    private Button discardCancelButton;

    public P2SChatScreen() {
        super(Component.literal("P2S Session"));
    }

    @Override
    protected void init() {
        super.init();
        createWidgets();
    }

    @Override
    public void resize(Minecraft client, int width, int height) {
        super.resize(client, width, height);
        createWidgets();
        clampScroll();
    }

    private void createWidgets() {
        clearWidgets();

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        int inputY = getInputY();
        int inputWidth = panelWidth - PADDING * 2 - BUTTON_WIDTH - 4;

        input = new EditBox(this.font, panelX + PADDING, inputY, inputWidth, INPUT_HEIGHT, Component.literal(""));
        input.setMaxLength(512);
        input.setFocused(true);
        addRenderableWidget(input);

        sendButton = Button.builder(Component.literal(">"), btn -> sendMessage())
                .bounds(panelX + PADDING + inputWidth + 4, inputY, BUTTON_WIDTH, INPUT_HEIGHT)
                .build();
        addRenderableWidget(sendButton);

        configButton = Button.builder(Component.literal("Config"), btn -> this.minecraft.setScreen(new P2SConfigScreen(this)))
                .bounds(panelX + panelWidth - PADDING - 56, PADDING, 56, INPUT_HEIGHT)
                .build();
        addRenderableWidget(configButton);

        Button sessionsButton = Button.builder(Component.literal("Sessions"), btn -> this.minecraft.setScreen(new P2SSessionListScreen(this)))
                .bounds(panelX + PADDING, PADDING, 60, INPUT_HEIGHT)
                .build();
        addRenderableWidget(sessionsButton);

        Button newButton = Button.builder(Component.literal("New"), btn -> ClientAgentManager.newSession())
                .bounds(panelX + PADDING + 64, PADDING, 40, INPUT_HEIGHT)
                .build();
        addRenderableWidget(newButton);

        int rowY = PADDING + TOP_BUTTON_HEIGHT + 4;
        int rowStart = panelX + panelWidth - PADDING - (SMALL_BUTTON_WIDTH * 4 + 6);

        applyButton = Button.builder(Component.literal("Apply"), btn -> ClientAgentManager.submitPatchApply())
                .bounds(rowStart, rowY, SMALL_BUTTON_WIDTH, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(applyButton);

        discardButton = Button.builder(Component.literal("Discard"), btn -> enterDiscardReasonMode())
                .bounds(rowStart + SMALL_BUTTON_WIDTH + 2, rowY, SMALL_BUTTON_WIDTH, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(discardButton);

        undoButton = Button.builder(Component.literal("Undo"), btn -> sendSessionAction("undo", ""))
                .bounds(rowStart + (SMALL_BUTTON_WIDTH + 2) * 2, rowY, SMALL_BUTTON_WIDTH, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(undoButton);

        redoButton = Button.builder(Component.literal("Redo"), btn -> sendSessionAction("redo", ""))
                .bounds(rowStart + (SMALL_BUTTON_WIDTH + 2) * 3, rowY, SMALL_BUTTON_WIDTH, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(redoButton);

        choiceButtons.clear();
        int actionWidth = SMALL_BUTTON_WIDTH * 4 + 6;
        int choiceY = rowY + TOP_BUTTON_HEIGHT + 2;
        int choiceGap = 2;
        int choiceWidth = (actionWidth - choiceGap * (CHOICE_BUTTON_COUNT - 1)) / CHOICE_BUTTON_COUNT;
        for (int i = 0; i < CHOICE_BUTTON_COUNT; i++) {
            final int index = i;
            Button choiceBtn = Button.builder(Component.literal(""), btn -> submitChoice(index))
                    .bounds(rowStart + i * (choiceWidth + choiceGap), choiceY, choiceWidth, TOP_BUTTON_HEIGHT)
                    .build();
            choiceBtn.visible = false;
            choiceBtn.active = false;
            choiceButtons.add(choiceBtn);
            addRenderableWidget(choiceBtn);
        }

        int discardRowY = choiceY + TOP_BUTTON_HEIGHT + 2;
        int discardInputWidth = actionWidth - BUTTON_WIDTH * 2 - 8;
        discardReasonInput = new EditBox(this.font, rowStart, discardRowY, discardInputWidth, INPUT_HEIGHT, Component.literal(""));
        discardReasonInput.setMaxLength(256);
        discardReasonInput.setHint(Component.literal("Reason (optional)"));
        discardReasonInput.visible = false;
        addRenderableWidget(discardReasonInput);

        discardOkButton = Button.builder(Component.literal("OK"), btn -> confirmDiscard())
                .bounds(rowStart + discardInputWidth + 4, discardRowY, BUTTON_WIDTH, INPUT_HEIGHT)
                .build();
        discardOkButton.visible = false;
        addRenderableWidget(discardOkButton);

        discardCancelButton = Button.builder(Component.literal("X"), btn -> exitDiscardReasonMode())
                .bounds(rowStart + discardInputWidth + BUTTON_WIDTH + 8, discardRowY, BUTTON_WIDTH, INPUT_HEIGHT)
                .build();
        discardCancelButton.visible = false;
        addRenderableWidget(discardCancelButton);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (discardReasonMode) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                exitDiscardReasonMode();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmDiscard();
                return true;
            }
            if (discardReasonInput != null && discardReasonInput.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sendMessage();
            return true;
        }
        if (input != null && input.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (input != null && input.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (input != null && input.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = getMaxScroll();
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int step = (this.font.lineHeight + LINE_SPACING) * 3;
        scrollOffset = clamp(scrollOffset + verticalAmount * step, 0, maxScroll);
        return true;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        refreshChoiceButtons();
        if (discardReasonMode && !ClientSessionState.hasPendingPatch()) {
            exitDiscardReasonMode();
        }
        if (applyButton != null) {
            boolean pending = ClientSessionState.hasPendingPatch();
            applyButton.active = pending && !discardReasonMode;
            applyButton.visible = !discardReasonMode;
            discardButton.active = pending && !discardReasonMode;
            discardButton.visible = !discardReasonMode;
        }
        if (discardReasonInput != null) {
            discardReasonInput.visible = discardReasonMode;
            discardOkButton.visible = discardReasonMode;
            discardCancelButton.visible = discardReasonMode;
        }
        if (undoButton != null) {
            boolean active = ClientSessionState.isActive();
            undoButton.active = active;
            redoButton.active = active;
        }

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        int panelLeft = panelX;
        int panelRight = this.width;
        int panelTop = 0;
        int panelBottom = this.height;

        gfx.fill(panelLeft, panelTop, panelRight, panelBottom, 0xAA000000);

        drawHeader(gfx, panelX, panelWidth);
        drawMessages(gfx, panelX, panelWidth);
        drawStatus(gfx, panelX);

        super.render(gfx, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // No full-screen dimming. The chat panel draws its own background.
    }

    private void drawHeader(GuiGraphics gfx, int panelX, int panelWidth) {
        int y = PADDING;
        gfx.drawString(this.font, "P2S Session", panelX + PADDING, y, 0xFFFFFF, true);
        y += this.font.lineHeight + 2;

        if (ClientSessionState.isActive()) {
            String info = "Session " + ClientSessionState.getSessionId() + " | Turns " + ClientSessionState.getTurnCount();
            gfx.drawString(this.font, info, panelX + PADDING, y, 0xAAAAAA, true);
            y += this.font.lineHeight + 2;

            String regionInfo;
            if (ClientSessionState.hasSize()) {
                regionInfo = "Origin: (" + ClientSessionState.getOriginX() + ", " + ClientSessionState.getOriginY() + ", " + ClientSessionState.getOriginZ() + ") | Size: " + ClientSessionState.getSizeX() + "x" + ClientSessionState.getSizeY() + "x" + ClientSessionState.getSizeZ() + " [Locked]";
            } else {
                regionInfo = "Origin: (" + ClientSessionState.getOriginX() + ", " + ClientSessionState.getOriginY() + ", " + ClientSessionState.getOriginZ() + ") | No bounds [Locked]";
            }
            gfx.drawString(this.font, regionInfo, panelX + PADDING, y, 0xFFCC66, true);
            y += this.font.lineHeight + 2;

            String runtime = ClientSessionState.getRuntimeState();
            String revision = ClientSessionState.getRevision();
            if (runtime != null && !runtime.isBlank()) {
                gfx.drawString(this.font, "State " + runtime + " | Rev " + revision, panelX + PADDING, y, 0x9999FF, true);
            }
        } else {
            gfx.drawString(this.font, "No active session (send a message to start)", panelX + PADDING, y, 0xAAAAAA, true);
        }

        List<FormattedCharSequence> choicePromptLines = getChoicePromptLines(panelWidth - PADDING * 2);
        if (!choicePromptLines.isEmpty()) {
            y += this.font.lineHeight + 4;
            gfx.drawString(this.font, "Action Required", panelX + PADDING, y, 0xFFCC66, true);
            y += this.font.lineHeight + 2;
            for (FormattedCharSequence line : choicePromptLines) {
                gfx.drawString(this.font, line, panelX + PADDING, y, 0xFFE6AA, true);
                y += this.font.lineHeight + LINE_SPACING;
            }
        }

        List<FormattedCharSequence> previewLines = getPreviewLines(panelWidth - PADDING * 2);
        if (!previewLines.isEmpty()) {
            y += this.font.lineHeight + 4;
            int color = 0xFFDD88;
            String risk = ClientSessionState.getPreviewRisk();
            if (risk == null || risk.isBlank()) {
                risk = ClientSessionState.getPendingRisk();
            }
            if (risk == null || risk.isBlank()) {
                risk = "low";
            }
            if ("high".equalsIgnoreCase(risk)) {
                color = 0xFF6666;
            } else if ("medium".equalsIgnoreCase(risk)) {
                color = 0xFFAA55;
            }
            int changed = ClientSessionState.getPreviewChangedBlocks();
            if (changed <= 0) {
                changed = ClientSessionState.getPendingChangedBlocks();
            }
            String title = "Pending Patch (" + changed + " blocks, " + risk + ")";
            gfx.drawString(this.font, title, panelX + PADDING, y, color, true);
            y += this.font.lineHeight + 2;
            for (FormattedCharSequence line : previewLines) {
                gfx.drawString(this.font, line, panelX + PADDING, y, 0xE0E0E0, true);
                y += this.font.lineHeight + LINE_SPACING;
            }
        }

        List<FormattedCharSequence> summaryLines = getSummaryLines(panelWidth - PADDING * 2);
        if (!summaryLines.isEmpty()) {
            y += this.font.lineHeight + 4;
            gfx.drawString(this.font, "Current Structure", panelX + PADDING, y, 0xAAAAAA, true);
            y += this.font.lineHeight + 2;
            for (FormattedCharSequence line : summaryLines) {
                gfx.drawString(this.font, line, panelX + PADDING, y, 0xCCCCCC, true);
                y += this.font.lineHeight + LINE_SPACING;
            }
        }

        List<FormattedCharSequence> todoLines = getTodoLines(panelWidth - PADDING * 2);
        if (!todoLines.isEmpty()) {
            y += this.font.lineHeight + 4;
            gfx.drawString(this.font, "Todo", panelX + PADDING, y, 0x99CCFF, true);
            y += this.font.lineHeight + 2;
            for (FormattedCharSequence line : todoLines) {
                gfx.drawString(this.font, line, panelX + PADDING, y, 0xCCDDEE, true);
                y += this.font.lineHeight + LINE_SPACING;
            }
        }
    }

    private void drawStatus(GuiGraphics gfx, int panelX) {
        String status = ClientSessionState.getStatus();
        if (status == null || status.isBlank()) {
            return;
        }
        gfx.drawString(this.font, "Status: " + status, panelX + PADDING, getStatusY(), 0xAAAAAA, true);
    }

    private void drawMessages(GuiGraphics gfx, int panelX, int panelWidth) {
        int contentWidth = panelWidth - PADDING * 2;
        int messageTop = getMessageTop(panelWidth);
        int messageBottom = getMessageBottom();
        int messageAreaHeight = Math.max(0, messageBottom - messageTop);

        int totalHeight = computeContentHeight(contentWidth);
        int maxScroll = Math.max(0, totalHeight - messageAreaHeight);
        scrollOffset = clamp(scrollOffset, 0, maxScroll);

        int y = messageBottom + (int) scrollOffset;
        List<ClientSessionState.ChatMessage> messages = ClientSessionState.getMessages();

        boolean isStreaming = ClientSessionState.isStreaming();
        String streamingText = isStreaming ? ClientSessionState.getStreamingText() : null;
        if (isStreaming && streamingText != null && !streamingText.isBlank()) {
            String streamPrefix = "AI: ";
            List<FormattedCharSequence> streamLines = this.font.split(Component.literal(streamPrefix + streamingText), contentWidth);
            for (int li = streamLines.size() - 1; li >= 0; li--) {
                y -= this.font.lineHeight + LINE_SPACING;
                if (y < messageTop) {
                    return;
                }
                gfx.drawString(this.font, streamLines.get(li), panelX + PADDING, y, 0x55FF55, true);
            }
            y -= LINE_SPACING;
        }

        for (int i = messages.size() - 1; i >= 0 && y > messageTop; i--) {
            ClientSessionState.ChatMessage msg = messages.get(i);
            String role = msg.role();
            String prefix = rolePrefix(role);
            int color = roleColor(role);

            List<FormattedCharSequence> lines = this.font.split(Component.literal(prefix + msg.text()), contentWidth);
            for (int li = lines.size() - 1; li >= 0; li--) {
                y -= this.font.lineHeight + LINE_SPACING;
                if (y < messageTop) {
                    return;
                }
                gfx.drawString(this.font, lines.get(li), panelX + PADDING, y, color, true);
            }
            y -= LINE_SPACING;
        }
    }

    private int computeContentHeight(int contentWidth) {
        int total = 0;
        List<ClientSessionState.ChatMessage> messages = ClientSessionState.getMessages();
        for (ClientSessionState.ChatMessage msg : messages) {
            String line = rolePrefix(msg.role()) + msg.text();
            int lines = this.font.split(Component.literal(line), contentWidth).size();
            total += lines * (this.font.lineHeight + LINE_SPACING);
            total += LINE_SPACING;
        }

        if (ClientSessionState.isStreaming()) {
            String streamingText = ClientSessionState.getStreamingText();
            if (streamingText != null && !streamingText.isBlank()) {
                int lines = this.font.split(Component.literal("AI: " + streamingText), contentWidth).size();
                total += lines * (this.font.lineHeight + LINE_SPACING);
                total += LINE_SPACING;
            }
        }

        return total;
    }

    private int getMaxScroll() {
        int panelWidth = getPanelWidth();
        int contentWidth = panelWidth - PADDING * 2;
        int messageAreaHeight = Math.max(0, getMessageBottom() - getMessageTop(panelWidth));
        int totalHeight = computeContentHeight(contentWidth);
        return Math.max(0, totalHeight - messageAreaHeight);
    }

    private void clampScroll() {
        scrollOffset = clamp(scrollOffset, 0, getMaxScroll());
    }

    private int getPanelWidth() {
        return Math.max(PANEL_MIN_WIDTH, this.width * 2 / 5);
    }

    private int getPanelX(int panelWidth) {
        return this.width - panelWidth;
    }

    private int getHeaderHeight(int panelWidth) {
        int base = this.font.lineHeight * 5 + 12;
        base += TOP_BUTTON_HEIGHT + 6;
        base += TOP_BUTTON_HEIGHT + 2;

        List<FormattedCharSequence> choiceLines = getChoicePromptLines(panelWidth - PADDING * 2);
        if (!choiceLines.isEmpty()) {
            base += this.font.lineHeight + 4;
            base += choiceLines.size() * (this.font.lineHeight + LINE_SPACING);
            base += LINE_SPACING;
        }

        List<FormattedCharSequence> previewLines = getPreviewLines(panelWidth - PADDING * 2);
        if (!previewLines.isEmpty()) {
            base += this.font.lineHeight + 4;
            base += previewLines.size() * (this.font.lineHeight + LINE_SPACING);
            base += LINE_SPACING;
        }

        int extra = 0;
        List<FormattedCharSequence> summaryLines = getSummaryLines(panelWidth - PADDING * 2);
        if (!summaryLines.isEmpty()) {
            extra += this.font.lineHeight + 4;
            extra += summaryLines.size() * (this.font.lineHeight + LINE_SPACING);
            extra += LINE_SPACING;
        }
        List<FormattedCharSequence> todoLines = getTodoLines(panelWidth - PADDING * 2);
        if (!todoLines.isEmpty()) {
            extra += this.font.lineHeight + 4;
            extra += todoLines.size() * (this.font.lineHeight + LINE_SPACING);
            extra += LINE_SPACING;
        }
        return base + extra;
    }

    private int getInputY() {
        return this.height - INPUT_HEIGHT - PADDING;
    }

    private int getStatusY() {
        return getInputY() - this.font.lineHeight - 4;
    }

    private int getMessageTop(int panelWidth) {
        return PADDING + getHeaderHeight(panelWidth);
    }

    private int getMessageBottom() {
        return getStatusY() - 4;
    }

    private List<FormattedCharSequence> getSummaryLines(int contentWidth) {
        String summary = ClientSessionState.getStructureSummary();
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        String[] lines = summary.split("\\n");
        List<FormattedCharSequence> result = new java.util.ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            result.addAll(this.font.split(Component.literal(line), contentWidth));
        }
        return result;
    }

    private List<FormattedCharSequence> getPreviewLines(int contentWidth) {
        String summary = ClientSessionState.getPreviewSummary();
        String detail = ClientSessionState.getPreviewDetail();
        if (summary == null || summary.isBlank()) {
            summary = ClientSessionState.getPendingSummary();
        }
        if ((summary == null || summary.isBlank()) && (detail == null || detail.isBlank())) {
            return List.of();
        }
        List<FormattedCharSequence> result = new java.util.ArrayList<>();
        if (summary != null && !summary.isBlank()) {
            result.addAll(this.font.split(Component.literal(summary), contentWidth));
        }
        if (detail != null && !detail.isBlank()) {
            String[] lines = detail.split("\\n");
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                result.addAll(this.font.split(Component.literal(line), contentWidth));
            }
        }
        int max = 12;
        if (result.size() > max) {
            return result.subList(0, max);
        }
        return result;
    }

    private List<FormattedCharSequence> getChoicePromptLines(int contentWidth) {
        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null || choice.prompt() == null || choice.prompt().isBlank()) {
            return List.of();
        }
        return this.font.split(Component.literal(choice.prompt().trim()), contentWidth);
    }

    private List<FormattedCharSequence> getTodoLines(int contentWidth) {
        List<ClientSessionState.TodoItem> items = ClientSessionState.getTodoItems();
        if (items.isEmpty()) {
            return List.of();
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        String title = ClientSessionState.getTodoTitle();
        if (title != null && !title.isBlank()) {
            lines.addAll(this.font.split(Component.literal(title.trim()), contentWidth));
        }
        int limit = Math.min(8, items.size());
        for (int i = 0; i < limit; i++) {
            ClientSessionState.TodoItem item = items.get(i);
            String mark = switch (item.status()) {
                case "done" -> "[x]";
                case "in_progress" -> "[~]";
                case "blocked" -> "[!]";
                default -> "[ ]";
            };
            String text = mark + " " + item.id() + " " + item.content();
            lines.addAll(this.font.split(Component.literal(text), contentWidth));
        }
        int more = items.size() - limit;
        if (more > 0) {
            lines.add(Component.literal("... +" + more + " more").getVisualOrderText());
        }
        return lines;
    }

    private void refreshChoiceButtons() {
        if (choiceButtons.isEmpty()) {
            return;
        }
        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        List<ClientSessionState.ChoiceOption> options = choice == null ? List.of() : choice.options();
        for (int i = 0; i < choiceButtons.size(); i++) {
            Button button = choiceButtons.get(i);
            if (i < options.size()) {
                ClientSessionState.ChoiceOption option = options.get(i);
                button.visible = true;
                button.active = true;
                button.setMessage(Component.literal(shortChoiceLabel(option.label())));
            } else {
                button.visible = false;
                button.active = false;
                button.setMessage(Component.literal(""));
            }
        }
    }

    private String shortChoiceLabel(String label) {
        if (label == null) {
            return "";
        }
        String text = label.trim();
        int max = 12;
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 3) + "...";
    }

    private static int roleColor(String role) {
        if (role == null) {
            return 0xFFFFFF;
        }
        String lower = role.toLowerCase();
        if (lower.contains("you") || lower.contains("user")) {
            return 0xFFFFFF;
        }
        if (lower.contains("ai") || lower.contains("assistant")) {
            return 0x55FF55;
        }
        return 0xAAAAAA;
    }

    private static String rolePrefix(String role) {
        if (role == null) {
            return "";
        }
        String lower = role.toLowerCase();
        if (lower.contains("you") || lower.contains("user")) {
            return "You: ";
        }
        if (lower.contains("ai") || lower.contains("assistant")) {
            return "AI: ";
        }
        return role + ": ";
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private void enterDiscardReasonMode() {
        if (!ClientSessionState.hasPendingPatch()) {
            return;
        }
        discardReasonMode = true;
        if (discardReasonInput != null) {
            discardReasonInput.setValue("");
            discardReasonInput.setFocused(true);
        }
        if (input != null) {
            input.setFocused(false);
        }
    }

    private void exitDiscardReasonMode() {
        discardReasonMode = false;
        if (discardReasonInput != null) {
            discardReasonInput.setValue("");
            discardReasonInput.setFocused(false);
        }
        if (input != null) {
            input.setFocused(true);
        }
    }

    private void confirmDiscard() {
        String reason = discardReasonInput != null ? discardReasonInput.getValue() : "";
        exitDiscardReasonMode();
        ClientAgentManager.submitPatchDiscard(reason);
    }

    private void sendMessage() {
        if (input == null) {
            return;
        }
        String text = input.getValue();
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        ClientAgentManager.submitUserMessage(text);
        input.setValue("");
        input.moveCursorToEnd(false);
        scrollOffset = 0;
    }

    private void submitChoice(int index) {
        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null || choice.options() == null) {
            return;
        }
        if (index < 0 || index >= choice.options().size()) {
            return;
        }
        ClientSessionState.ChoiceOption option = choice.options().get(index);
        if (option == null || option.id() == null || option.id().isBlank()) {
            return;
        }
        ClientAgentManager.submitChoiceSelection(option.id());
    }

    private void sendSessionAction(String action, String payload) {
        ClientPlayNetworking.send(new C2SSessionActionPayload(action == null ? "" : action, payload == null ? "" : payload));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
