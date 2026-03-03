package com.p2s;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    private static final Gson CONTEXT_GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int PADDING = 8;
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 22;
    private static final int LINE_SPACING = 2;
    private static final int PANEL_MIN_WIDTH = 240;
    private static final int TOP_BUTTON_HEIGHT = 20;
    private static final int SMALL_BUTTON_WIDTH = 52;
    private static final int CHOICE_BUTTON_COUNT = 3;

    private static final int CONTEXT_ROW_HEIGHT = 18;
    private static final int CONTEXT_ROW_GAP = 2;
    private static final int CONTEXT_FOOTER_HEIGHT = 110;
    private static final int CONTEXT_MAX_SNIPPETS = 8;
    private static final int CONTEXT_MAX_SNIPPET_CHARS = 4000;
    private static final int CONTEXT_MAX_TOTAL_CHARS = 12000;
    private static final int CONTEXT_DEFAULT_RANGE = 30;

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

    // Left context editor
    private final List<String> contextJsonLines = new ArrayList<>();
    private final List<EditBox> contextLineInputs = new ArrayList<>();
    private final List<ContextSnippet> queuedContexts = new ArrayList<>();
    private EditBox contextFileInput;
    private EditBox contextStartInput;
    private EditBox contextEndInput;
    private Button contextLoadButton;
    private Button contextFormatButton;
    private Button contextClearJsonButton;
    private Button contextClearQueueButton;
    private Button contextAddRangeButton;
    private Button contextUpButton;
    private Button contextDownButton;
    private int contextVisibleRows = 0;
    private int contextScroll = 0;
    private int contextQueueTopY = 0;
    private String contextFileName = "workspace-state.json";
    private int contextRangeStart = 1;
    private int contextRangeEnd = CONTEXT_DEFAULT_RANGE;
    private String contextStatus = "";
    private int contextStatusColor = 0xAAAAAA;

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
        saveContextPage();
        captureContextControlState();
        clearWidgets();

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        initContextWidgets(panelX);

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

    private void initContextWidgets(int panelX) {
        if (contextJsonLines.isEmpty()) {
            setContextJsonText(buildWorkspaceStateJson());
        }
        contextScroll = Math.max(0, contextScroll);

        int leftX = PADDING;
        int leftWidth = Math.max(100, panelX - PADDING * 2);
        int rowGap = 2;
        int row1Y = PADDING + this.font.lineHeight + 6;
        int row2Y = row1Y + INPUT_HEIGHT + rowGap;
        int row3Y = row2Y + INPUT_HEIGHT + rowGap;

        contextFileInput = new EditBox(this.font, leftX, row1Y, leftWidth, INPUT_HEIGHT, Component.literal("context file"));
        contextFileInput.setMaxLength(128);
        contextFileInput.setValue(contextFileName == null || contextFileName.isBlank() ? "workspace-state.json" : contextFileName);
        addRenderableWidget(contextFileInput);

        int row2ButtonCount = 4;
        int row2BtnWidth = Math.max(46, (leftWidth - rowGap * (row2ButtonCount - 1)) / row2ButtonCount);
        int row2X = leftX;
        contextLoadButton = addRenderableWidget(Button.builder(Component.literal("Load"), btn -> loadWorkspaceStateJson())
                .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
        row2X += row2BtnWidth + rowGap;
        contextFormatButton = addRenderableWidget(Button.builder(Component.literal("Format"), btn -> formatContextJson())
                .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
        row2X += row2BtnWidth + rowGap;
        contextClearJsonButton = addRenderableWidget(Button.builder(Component.literal("Clear"), btn -> clearContextJson())
                .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
        row2X += row2BtnWidth + rowGap;
        contextClearQueueButton = addRenderableWidget(Button.builder(Component.literal("Clear Ctx"), btn -> clearContextQueue())
                .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());

        int startWidth = 52;
        int endWidth = 52;
        int addWidth = Math.max(80, leftWidth - startWidth - endWidth - rowGap * 2);

        contextStartInput = new EditBox(this.font, leftX, row3Y, startWidth, INPUT_HEIGHT, Component.literal("start line"));
        contextStartInput.setMaxLength(8);
        contextStartInput.setHint(Component.literal("start"));
        addRenderableWidget(contextStartInput);

        contextEndInput = new EditBox(this.font, leftX + startWidth + rowGap, row3Y, endWidth, INPUT_HEIGHT, Component.literal("end line"));
        contextEndInput.setMaxLength(8);
        contextEndInput.setHint(Component.literal("end"));
        addRenderableWidget(contextEndInput);

        contextAddRangeButton = addRenderableWidget(Button.builder(Component.literal("Add Range -> Ctx"), btn -> addSelectedRangeAsContext())
                .bounds(leftX + startWidth + endWidth + rowGap * 2, row3Y, addWidth, INPUT_HEIGHT).build());

        if (contextRangeStart <= 0) {
            contextRangeStart = contextScroll + 1;
        }
        if (contextRangeEnd <= 0) {
            contextRangeEnd = Math.min(contextJsonLines.size(), contextRangeStart + CONTEXT_DEFAULT_RANGE - 1);
        }
        contextStartInput.setValue(Integer.toString(Math.max(1, contextRangeStart)));
        contextEndInput.setValue(Integer.toString(Math.max(1, contextRangeEnd)));

        int editorTop = row3Y + INPUT_HEIGHT + 4;
        int editorBottom = this.height - CONTEXT_FOOTER_HEIGHT;
        int available = Math.max(0, editorBottom - editorTop);
        contextVisibleRows = Math.max(4, Math.min(22, available / (CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP)));

        contextLineInputs.clear();
        int lineNumberGutter = 36;
        int inputWidth = Math.max(60, leftWidth - lineNumberGutter);
        for (int i = 0; i < contextVisibleRows; i++) {
            int y = editorTop + i * (CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP);
            EditBox rowInput = new EditBox(this.font, leftX + lineNumberGutter, y, inputWidth, CONTEXT_ROW_HEIGHT, Component.literal("json line"));
            rowInput.setMaxLength(4096);
            contextLineInputs.add(rowInput);
            addRenderableWidget(rowInput);
        }

        int navY = editorTop + contextVisibleRows * (CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP) + 4;
        contextUpButton = addRenderableWidget(Button.builder(Component.literal("Up"), btn -> scrollContextEditor(-1))
                .bounds(leftX, navY, 52, INPUT_HEIGHT).build());
        contextDownButton = addRenderableWidget(Button.builder(Component.literal("Down"), btn -> scrollContextEditor(1))
                .bounds(leftX + 54, navY, 64, INPUT_HEIGHT).build());
        contextQueueTopY = navY + INPUT_HEIGHT + 6;

        clampContextScroll();
        loadContextPage();
        refreshContextNavButtons();
    }

    private void captureContextControlState() {
        if (contextFileInput != null) {
            contextFileName = normalizeContextFileName(contextFileInput.getValue());
        }
        if (contextStartInput != null) {
            contextRangeStart = parsePositiveInt(contextStartInput.getValue(), contextRangeStart <= 0 ? 1 : contextRangeStart);
        }
        if (contextEndInput != null) {
            contextRangeEnd = parsePositiveInt(contextEndInput.getValue(), contextRangeEnd <= 0 ? contextRangeStart : contextRangeEnd);
        }
    }

    private void loadWorkspaceStateJson() {
        setContextJsonText(buildWorkspaceStateJson());
        setContextStatus("Loaded current workspace state", 0x55FF55);
    }

    private void formatContextJson() {
        saveContextPage();
        String raw = contextLinesToText();
        if (raw.isBlank()) {
            setContextStatus("Nothing to format", 0xFFAA55);
            return;
        }
        try {
            String formatted = CONTEXT_GSON.toJson(JsonParser.parseString(raw));
            setContextJsonText(formatted);
            setContextStatus("JSON formatted", 0x55FF55);
        } catch (Exception e) {
            setContextStatus("Invalid JSON: " + shortError(e.getMessage()), 0xFF5555);
        }
    }

    private void clearContextJson() {
        setContextJsonText("{\n}\n");
        setContextStatus("JSON cleared", 0x55FF55);
    }

    private void clearContextQueue() {
        queuedContexts.clear();
        setContextStatus("Context queue cleared", 0x55FF55);
    }

    private void setContextJsonText(String text) {
        contextJsonLines.clear();
        String[] lines = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length == 0) {
            contextJsonLines.add("");
        } else {
            for (String line : lines) {
                contextJsonLines.add(line == null ? "" : line);
            }
        }
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
        }
        contextScroll = 0;
        clampContextScroll();
        loadContextPage();
        contextRangeStart = 1;
        contextRangeEnd = Math.min(contextJsonLines.size(), CONTEXT_DEFAULT_RANGE);
        if (contextStartInput != null) {
            contextStartInput.setValue(Integer.toString(contextRangeStart));
        }
        if (contextEndInput != null) {
            contextEndInput.setValue(Integer.toString(contextRangeEnd));
        }
    }

    private void ensureContextCapacity(int size) {
        int target = Math.max(1, size);
        while (contextJsonLines.size() < target) {
            contextJsonLines.add("");
        }
    }

    private void saveContextPage() {
        if (contextLineInputs.isEmpty()) {
            return;
        }
        ensureContextCapacity(contextScroll + contextLineInputs.size());
        for (int i = 0; i < contextLineInputs.size(); i++) {
            int idx = contextScroll + i;
            contextJsonLines.set(idx, contextLineInputs.get(i).getValue());
        }
    }

    private void loadContextPage() {
        if (contextLineInputs.isEmpty()) {
            return;
        }
        ensureContextCapacity(contextScroll + contextLineInputs.size());
        for (int i = 0; i < contextLineInputs.size(); i++) {
            int idx = contextScroll + i;
            String value = idx >= 0 && idx < contextJsonLines.size() ? contextJsonLines.get(idx) : "";
            contextLineInputs.get(i).setValue(value == null ? "" : value);
        }
        refreshContextNavButtons();
    }

    private void clampContextScroll() {
        int max = Math.max(0, contextJsonLines.size() - Math.max(1, contextVisibleRows));
        contextScroll = Math.max(0, Math.min(contextScroll, max));
    }

    private void scrollContextEditor(int deltaRows) {
        if (deltaRows == 0) {
            return;
        }
        saveContextPage();
        contextScroll += deltaRows;
        clampContextScroll();
        loadContextPage();
    }

    private void refreshContextNavButtons() {
        int max = Math.max(0, contextJsonLines.size() - Math.max(1, contextVisibleRows));
        if (contextUpButton != null) {
            contextUpButton.active = contextScroll > 0;
        }
        if (contextDownButton != null) {
            contextDownButton.active = contextScroll < max;
        }
    }

    private void addSelectedRangeAsContext() {
        saveContextPage();
        if (queuedContexts.size() >= CONTEXT_MAX_SNIPPETS) {
            setContextStatus("Too many snippets queued (" + CONTEXT_MAX_SNIPPETS + " max)", 0xFF5555);
            return;
        }
        if (contextJsonLines.isEmpty()) {
            setContextStatus("No JSON lines available", 0xFF5555);
            return;
        }

        int maxLine = contextJsonLines.size();
        int start = parsePositiveInt(contextStartInput == null ? "" : contextStartInput.getValue(), contextScroll + 1);
        int end = parsePositiveInt(contextEndInput == null ? "" : contextEndInput.getValue(), start);
        start = Math.max(1, Math.min(start, maxLine));
        end = Math.max(1, Math.min(end, maxLine));
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        contextRangeStart = start;
        contextRangeEnd = end;
        if (contextStartInput != null) {
            contextStartInput.setValue(Integer.toString(start));
        }
        if (contextEndInput != null) {
            contextEndInput.setValue(Integer.toString(end));
        }

        String snippetText = buildContextRangeText(start, end);
        if (snippetText.isBlank()) {
            setContextStatus("Selected range is empty", 0xFF5555);
            return;
        }
        if (snippetText.length() > CONTEXT_MAX_SNIPPET_CHARS) {
            setContextStatus("Selection too large (" + snippetText.length() + " chars). Narrow the range.", 0xFF5555);
            return;
        }
        if (totalQueuedContextChars() + snippetText.length() > CONTEXT_MAX_TOTAL_CHARS) {
            setContextStatus("Context queue exceeds " + CONTEXT_MAX_TOTAL_CHARS + " chars", 0xFF5555);
            return;
        }

        String fileName = normalizeContextFileName(contextFileInput == null ? contextFileName : contextFileInput.getValue());
        String label = fileName + ":" + start + "-" + end;
        queuedContexts.add(new ContextSnippet(label, snippetText));
        setContextStatus("Added context " + label, 0x55FF55);
    }

    private String buildContextRangeText(int startLine, int endLine) {
        if (contextJsonLines.isEmpty()) {
            return "";
        }
        int start = Math.max(1, startLine);
        int end = Math.max(start, endLine);
        StringBuilder sb = new StringBuilder();
        for (int line = start; line <= end && line <= contextJsonLines.size(); line++) {
            if (line > start) {
                sb.append('\n');
            }
            String value = contextJsonLines.get(line - 1);
            sb.append(value == null ? "" : value);
        }
        return sb.toString();
    }

    private String contextLinesToText() {
        if (contextJsonLines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contextJsonLines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(contextJsonLines.get(i) == null ? "" : contextJsonLines.get(i));
        }
        return sb.toString();
    }

    private int totalQueuedContextChars() {
        int total = 0;
        for (ContextSnippet snippet : queuedContexts) {
            total += snippet.content().length();
        }
        return total;
    }

    private String buildWorkspaceStateJson() {
        JsonObject root = new JsonObject();
        root.addProperty("active", ClientSessionState.isActive());
        root.addProperty("sessionId", ClientSessionState.getSessionId());
        root.addProperty("turnCount", ClientSessionState.getTurnCount());
        root.addProperty("status", ClientSessionState.getStatus());
        root.addProperty("runtimeState", ClientSessionState.getRuntimeState());
        root.addProperty("revision", ClientSessionState.getRevision());

        JsonObject origin = new JsonObject();
        origin.addProperty("x", ClientSessionState.getOriginX());
        origin.addProperty("y", ClientSessionState.getOriginY());
        origin.addProperty("z", ClientSessionState.getOriginZ());
        root.add("origin", origin);

        JsonObject bounds = new JsonObject();
        bounds.addProperty("hasSize", ClientSessionState.hasSize());
        bounds.addProperty("x", ClientSessionState.getSizeX());
        bounds.addProperty("y", ClientSessionState.getSizeY());
        bounds.addProperty("z", ClientSessionState.getSizeZ());
        root.add("size", bounds);

        JsonObject pendingPatch = new JsonObject();
        pendingPatch.addProperty("hasPendingPatch", ClientSessionState.hasPendingPatch());
        pendingPatch.addProperty("summary", ClientSessionState.getPendingSummary());
        pendingPatch.addProperty("risk", ClientSessionState.getPendingRisk());
        pendingPatch.addProperty("changedBlocks", ClientSessionState.getPendingChangedBlocks());
        root.add("pendingPatch", pendingPatch);

        JsonObject preview = new JsonObject();
        preview.addProperty("summary", ClientSessionState.getPreviewSummary());
        preview.addProperty("detail", ClientSessionState.getPreviewDetail());
        preview.addProperty("risk", ClientSessionState.getPreviewRisk());
        preview.addProperty("changedBlocks", ClientSessionState.getPreviewChangedBlocks());
        root.add("preview", preview);

        JsonArray todos = new JsonArray();
        for (ClientSessionState.TodoItem item : ClientSessionState.getTodoItems()) {
            JsonObject todo = new JsonObject();
            todo.addProperty("id", item.id());
            todo.addProperty("content", item.content());
            todo.addProperty("status", item.status());
            todos.add(todo);
        }
        root.addProperty("todoTitle", ClientSessionState.getTodoTitle());
        root.add("todoItems", todos);

        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice != null) {
            JsonObject choiceJson = new JsonObject();
            choiceJson.addProperty("requestId", choice.requestId());
            choiceJson.addProperty("prompt", choice.prompt());
            JsonArray options = new JsonArray();
            for (ClientSessionState.ChoiceOption option : choice.options()) {
                JsonObject opt = new JsonObject();
                opt.addProperty("id", option.id());
                opt.addProperty("label", option.label());
                opt.addProperty("description", option.description());
                options.add(opt);
            }
            choiceJson.add("options", options);
            root.add("pendingChoice", choiceJson);
        }

        JsonArray recent = new JsonArray();
        List<ClientSessionState.ChatMessage> messages = ClientSessionState.getMessages();
        int from = Math.max(0, messages.size() - 20);
        for (int i = from; i < messages.size(); i++) {
            ClientSessionState.ChatMessage msg = messages.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("role", msg.role());
            entry.addProperty("text", msg.text());
            recent.add(entry);
        }
        root.add("recentMessages", recent);

        return CONTEXT_GSON.toJson(root);
    }

    private String buildMessageWithQueuedContext(String userText) {
        if (queuedContexts.isEmpty()) {
            return userText.trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(userText.trim());
        sb.append("\n\nAttached context snippets:\n");
        for (ContextSnippet snippet : queuedContexts) {
            sb.append("- ").append(snippet.label()).append('\n');
        }
        for (ContextSnippet snippet : queuedContexts) {
            sb.append("\n### ").append(snippet.label()).append("\n");
            sb.append("```json\n");
            sb.append(snippet.content());
            sb.append("\n```\n");
        }
        return sb.toString().trim();
    }

    private String buildVisibleMessageText(String userText) {
        String text = userText == null ? "" : userText.trim();
        if (text.isBlank()) {
            return text;
        }
        if (queuedContexts.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        sb.append(" [ctx: ");
        int max = Math.min(queuedContexts.size(), 3);
        for (int i = 0; i < max; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shortContextLabel(queuedContexts.get(i).label()));
        }
        if (queuedContexts.size() > max) {
            sb.append(", +").append(queuedContexts.size() - max);
        }
        sb.append("]");
        return sb.toString();
    }

    private String shortContextLabel(String label) {
        if (label == null) {
            return "";
        }
        String trimmed = label.trim();
        int max = 28;
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max - 3) + "...";
    }

    private String normalizeContextFileName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return "workspace-state.json";
        }
        if (!value.endsWith(".json")) {
            return value + ".json";
        }
        return value;
    }

    private int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value <= 0 ? fallback : value;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String shortError(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String text = message.trim();
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    private void setContextStatus(String text, int color) {
        contextStatus = text == null ? "" : text;
        contextStatusColor = color;
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

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && input != null && input.isFocused()) {
            sendMessage();
            return true;
        }

        for (EditBox box : collectEditableBoxes()) {
            if (box != null && box.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (discardReasonMode) {
            if (discardReasonInput != null && discardReasonInput.charTyped(codePoint, modifiers)) {
                return true;
            }
            return super.charTyped(codePoint, modifiers);
        }
        for (EditBox box : collectEditableBoxes()) {
            if (box != null && box.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        if (mouseX < panelX) {
            if (!contextLineInputs.isEmpty()) {
                int delta = verticalAmount > 0 ? -3 : 3;
                if (delta != 0) {
                    int before = contextScroll;
                    scrollContextEditor(delta);
                    if (before != contextScroll) {
                        return true;
                    }
                }
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

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
        drawContextPanel(gfx, panelX);

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
        // No full-screen dimming. Panels draw their own backgrounds.
    }

    private void drawContextPanel(GuiGraphics gfx, int panelX) {
        if (panelX <= 0) {
            return;
        }
        gfx.fill(0, 0, panelX, this.height, 0xC0141A26);
        gfx.fill(panelX - 1, 0, panelX, this.height, 0xFF2F3A4D);

        int x = PADDING;
        int y = PADDING;
        gfx.drawString(this.font, "Context JSON", x, y, 0xE8F0FF, true);
        y += this.font.lineHeight + 2;
        gfx.drawString(this.font, "Editable lines + range attach", x, y, 0x9CAECC, false);

        int start = parsePositiveInt(contextStartInput == null ? "" : contextStartInput.getValue(), contextRangeStart);
        int end = parsePositiveInt(contextEndInput == null ? "" : contextEndInput.getValue(), contextRangeEnd);
        if (start > end) {
            int t = start;
            start = end;
            end = t;
        }

        for (int i = 0; i < contextLineInputs.size(); i++) {
            EditBox row = contextLineInputs.get(i);
            int lineNo = contextScroll + i + 1;
            int lineY = row.getY() + 5;
            int color = 0x8A99B8;
            if (lineNo >= start && lineNo <= end) {
                gfx.fill(row.getX() - 34, row.getY(), row.getX() + row.getWidth(), row.getY() + row.getHeight(), 0x33335522);
                color = 0xFFD27D;
            }
            gfx.drawString(this.font, Integer.toString(lineNo), row.getX() - 30, lineY, color, false);
        }

        int infoY = contextQueueTopY;
        gfx.drawString(this.font, "Next message context (" + queuedContexts.size() + "/" + CONTEXT_MAX_SNIPPETS + ")", x, infoY, 0xE8F0FF, true);
        infoY += this.font.lineHeight + 2;

        if (queuedContexts.isEmpty()) {
            gfx.drawString(this.font, "-", x, infoY, 0x8A99B8, false);
            infoY += this.font.lineHeight + 2;
        } else {
            int maxRows = 6;
            for (int i = 0; i < queuedContexts.size() && i < maxRows; i++) {
                ContextSnippet snippet = queuedContexts.get(i);
                String line = (i + 1) + ". " + snippet.label() + " (" + snippet.content().length() + " chars)";
                List<FormattedCharSequence> wrapped = this.font.split(Component.literal(line), Math.max(100, panelX - PADDING * 3));
                for (FormattedCharSequence text : wrapped) {
                    gfx.drawString(this.font, text, x, infoY, 0xC9D4EB, false);
                    infoY += this.font.lineHeight + 1;
                }
                if (infoY > this.height - 48) {
                    break;
                }
            }
            if (queuedContexts.size() > maxRows) {
                gfx.drawString(this.font, "... +" + (queuedContexts.size() - maxRows) + " more", x, infoY, 0x8A99B8, false);
                infoY += this.font.lineHeight + 1;
            }
        }

        if (contextStatus != null && !contextStatus.isBlank()) {
            gfx.drawString(this.font, contextStatus, x, this.height - 14, contextStatusColor, false);
        }
    }

    private List<EditBox> collectEditableBoxes() {
        List<EditBox> boxes = new ArrayList<>();
        if (input != null) {
            boxes.add(input);
        }
        if (contextFileInput != null) {
            boxes.add(contextFileInput);
        }
        if (contextStartInput != null) {
            boxes.add(contextStartInput);
        }
        if (contextEndInput != null) {
            boxes.add(contextEndInput);
        }
        boxes.addAll(contextLineInputs);
        return boxes;
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
        List<FormattedCharSequence> result = new ArrayList<>();
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
        List<FormattedCharSequence> result = new ArrayList<>();
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
        saveContextPage();
        String text = input.getValue();
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        String requestText = buildMessageWithQueuedContext(text);
        String visibleText = buildVisibleMessageText(text);
        ClientAgentManager.submitUserMessage(requestText, visibleText);

        if (!queuedContexts.isEmpty()) {
            queuedContexts.clear();
            setContextStatus("Context sent with message", 0x55FF55);
        }

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

    private record ContextSnippet(String label, String content) {
    }
}
