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
import java.util.Arrays;
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
    private static final int CONTEXT_EDITOR_GUTTER = 38;
    private static final int CONTEXT_DIFF_GUTTER = 84;
    private static final int CONTEXT_EDITOR_PADDING = 4;
    private static final int CONTEXT_FOOTER_HEIGHT = 110;
    private static final int CONTEXT_MAX_SNIPPETS = 8;
    private static final int CONTEXT_MAX_SNIPPET_CHARS = 4000;
    private static final int CONTEXT_MAX_TOTAL_CHARS = 12000;
    private static final int CONTEXT_DEFAULT_RANGE = 30;
    private static final long CONTEXT_LONG_PRESS_MS = 400;
    private static final int CONTEXT_DIFF_MAX_SOURCE_LINES = 1400;
    private static final int CONTEXT_DIFF_EQUAL_CONTEXT_LINES = 3;

    private EditBox input;
    private Button sendButton;
    private Button configButton;
    private Button applyButton;
    private Button discardButton;
    private Button undoButton;
    private Button redoButton;
    private Button checkpointCreateButton;
    private Button checkpointPrevButton;
    private Button checkpointNextButton;
    private Button checkpointRollbackButton;
    private Button checkpointModeButton;
    private final List<Button> choiceButtons = new ArrayList<>();
    private double scrollOffset;

    private boolean infoOverlayVisible = false;
    private double infoOverlayScroll = 0;
    private Button infoButton;

    private boolean discardReasonMode = false;
    private EditBox discardReasonInput;
    private Button discardOkButton;
    private Button discardCancelButton;
    private boolean workspaceRenameMode = false;
    private String workspaceRenameDraft = "";
    private EditBox workspaceRenameInput;
    private Button workspaceRenameButton;
    private Button workspaceDeleteButton;
    private Button workspaceRenameOkButton;
    private Button workspaceRenameCancelButton;

    // Left context editor
    private final List<String> contextJsonLines = new ArrayList<>();
    private final List<ContextSnippet> queuedContexts = new ArrayList<>();
    private Button contextTabStateButton;
    private Button contextTabScriptButton;
    private Button contextTabDiffButton;
    private EditBox contextStartInput;
    private EditBox contextEndInput;
    private Button contextLoadButton;
    private Button contextFormatButton;
    private Button contextClearJsonButton;
    private Button contextClearQueueButton;
    private Button contextDiffPrevButton;
    private Button contextDiffNextButton;
    private Button contextAddRangeButton;
    private Button workspaceDocCreateButton;
    private final List<Button> workspaceDocButtons = new ArrayList<>();
    private ContextTab activeContextTab = ContextTab.SCRIPT;
    private final List<String> stateJsonLines = new ArrayList<>();
    private int stateCursorLine = 0;
    private int stateCursorColumn = 0;
    private int stateScroll = 0;
    private final List<String> scriptJsonLines = new ArrayList<>();
    private int scriptCursorLine = 0;
    private int scriptCursorColumn = 0;
    private int scriptScroll = 0;
    private boolean scriptLoading = false;
    private String contextLoadedDocId = "";
    private String workspaceUiSignature = "";
    private int contextVisibleRows = 0;
    private int contextScroll = 0;
    private int contextEditorX = 0;
    private int contextEditorY = 0;
    private int contextEditorWidth = 0;
    private int contextEditorHeight = 0;
    private boolean contextEditorFocused = false;
    private int contextCursorLine = 0;
    private int contextCursorColumn = 0;
    private int contextPreferredColumn = -1;
    private boolean contextSelectionActive = false;
    private int contextSelectionAnchorLine = 0;
    private int contextSelectionAnchorColumn = 0;
    private boolean contextMouseSelecting = false;
    private long contextMousePressTime = 0;
    private boolean contextLongPressCtxMode = false;
    private int contextQueueTopY = 0;
    private int contextRangeStart = 1;
    private int contextRangeEnd = CONTEXT_DEFAULT_RANGE;
    private boolean contextDiffMode = false;
    private final List<DiffViewLine> contextDiffLines = new ArrayList<>();
    private final List<Integer> contextDiffChangeRows = new ArrayList<>();
    private int contextDiffNavIndex = -1;
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
        captureContextControlState();
        clearWidgets();

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        initContextWidgets(panelX);

        int inputY = getInputY();
        int inputWidth = panelWidth - PADDING * 2 - BUTTON_WIDTH - 4;

        input = new EditBox(this.font, panelX + PADDING, inputY, inputWidth, INPUT_HEIGHT, Component.literal(""));
        input.setMaxLength(512);
        input.setFocused(!contextEditorFocused);
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

        infoButton = Button.builder(Component.literal("[i]"), btn -> {
                    infoOverlayVisible = !infoOverlayVisible;
                    infoOverlayScroll = 0;
                })
                .bounds(panelX + PADDING + 108, PADDING, 40, INPUT_HEIGHT)
                .build();
        addRenderableWidget(infoButton);

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

        int cpY = rowY + TOP_BUTTON_HEIGHT + 2;
        int cpX = panelX + PADDING;
        checkpointCreateButton = Button.builder(Component.literal("CP+"), btn -> createCheckpoint())
                .bounds(cpX, cpY, 36, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(checkpointCreateButton);

        checkpointPrevButton = Button.builder(Component.literal("<"), btn -> ClientSessionState.selectPreviousCheckpoint())
                .bounds(cpX + 38, cpY, 20, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(checkpointPrevButton);

        checkpointNextButton = Button.builder(Component.literal(">"), btn -> ClientSessionState.selectNextCheckpoint())
                .bounds(cpX + 60, cpY, 20, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(checkpointNextButton);

        checkpointRollbackButton = Button.builder(Component.literal("RB"), btn -> rollbackSelectedCheckpoint())
                .bounds(cpX + 82, cpY, 30, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(checkpointRollbackButton);

        checkpointModeButton = Button.builder(Component.literal(modeLabel()), btn -> {
                    ClientSessionState.toggleRollbackMode();
                    if (checkpointModeButton != null) {
                        checkpointModeButton.setMessage(Component.literal(modeLabel()));
                    }
                })
                .bounds(cpX + 114, cpY, 54, TOP_BUTTON_HEIGHT)
                .build();
        addRenderableWidget(checkpointModeButton);

        choiceButtons.clear();
        int actionWidth = SMALL_BUTTON_WIDTH * 4 + 6;
        int choiceY = cpY + TOP_BUTTON_HEIGHT + 2;
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
        if (activeContextTab == ContextTab.STATE) {
            activeContextTab = ContextTab.SCRIPT;
        }
        if (activeContextTab == ContextTab.SCRIPT && contextJsonLines.isEmpty()) {
            String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
            String current = ClientSessionState.getWorkspaceDocScriptJson(selectedWorkspaceId);
            if ((current == null || current.isBlank()) && selectedWorkspaceId != null && !selectedWorkspaceId.isBlank()) {
                current = ClientSessionState.getCurrentScriptJson();
            }
            if (current != null && !current.isBlank()) {
                setContextJsonText(current);
                contextLoadedDocId = selectedWorkspaceId == null ? "" : selectedWorkspaceId;
            } else {
                setContextJsonText("{\n}\n");
            }
        }
        contextScroll = Math.max(0, contextScroll);

        int leftX = PADDING;
        int leftWidth = Math.max(100, panelX - PADDING * 2);
        int explorerWidth = Math.min(220, Math.max(130, leftWidth / 3));
        int splitGap = 6;
        int editorXBase = leftX + explorerWidth + splitGap;
        int editorWidth = Math.max(120, leftWidth - explorerWidth - splitGap);
        int rowGap = 2;
        int row1Y = PADDING + this.font.lineHeight + 6;
        int row2Y = row1Y + INPUT_HEIGHT + rowGap;
        int row3Y = row2Y + INPUT_HEIGHT + rowGap;

        // Explorer (left): VSCode-like file list
        workspaceDocButtons.clear();
        if (workspaceRenameMode && (workspaceRenameDraft == null || workspaceRenameDraft.isBlank())) {
            String selectedName = ClientSessionState.getSelectedWorkspaceName();
            workspaceRenameDraft = selectedName == null ? "" : selectedName;
        }
        P2SWorkspaceExplorerComponent.BuildResult explorer = P2SWorkspaceExplorerComponent.build(
                new P2SWorkspaceExplorerComponent.Host() {
                    @Override
                    public net.minecraft.client.gui.Font font() {
                        return P2SChatScreen.this.font;
                    }

                    @Override
                    public int screenHeight() {
                        return P2SChatScreen.this.height;
                    }

                    @Override
                    public Button addButton(Button button) {
                        return P2SChatScreen.this.addRenderableWidget(button);
                    }

                    @Override
                    public EditBox addEditBox(EditBox editBox) {
                        P2SChatScreen.this.addRenderableWidget(editBox);
                        return editBox;
                    }
                },
                new P2SWorkspaceExplorerComponent.Config(
                        leftX,
                        row1Y,
                        row2Y,
                        explorerWidth,
                        INPUT_HEIGHT,
                        CONTEXT_FOOTER_HEIGHT,
                        workspaceRenameMode,
                        workspaceRenameDraft,
                        ClientSessionState.getSelectedWorkspaceId(),
                        ClientSessionState.getWorkspaceDocs(),
                        this::createWorkspaceDoc,
                        this::enterWorkspaceRenameMode,
                        this::deleteSelectedWorkspaceDoc,
                        this::switchWorkspaceDoc,
                        this::confirmWorkspaceRename,
                        this::exitWorkspaceRenameMode
                )
        );
        workspaceDocCreateButton = explorer.createButton();
        workspaceRenameButton = explorer.renameButton();
        workspaceDeleteButton = explorer.deleteButton();
        workspaceRenameInput = explorer.renameInput();
        workspaceRenameOkButton = explorer.renameOkButton();
        workspaceRenameCancelButton = explorer.renameCancelButton();
        workspaceDocButtons.addAll(explorer.docButtons());
        if (workspaceRenameInput != null) {
            workspaceRenameInput.setFocused(true);
        }

        // Editor controls (right)
        int row1ButtonCount = 4;
        int row1BtnWidth = Math.max(52, (editorWidth - rowGap * (row1ButtonCount - 1)) / row1ButtonCount);
        int row1X = editorXBase;
        contextTabScriptButton = addRenderableWidget(Button.builder(Component.literal("Script"), btn -> switchContextTab(ContextTab.SCRIPT))
                .bounds(row1X, row1Y, row1BtnWidth, INPUT_HEIGHT).build());
        contextTabScriptButton.active = activeContextTab != ContextTab.SCRIPT;
        row1X += row1BtnWidth + rowGap;
        contextTabDiffButton = addRenderableWidget(Button.builder(Component.literal("Diff"), btn -> switchContextTab(ContextTab.DIFF))
                .bounds(row1X, row1Y, row1BtnWidth, INPUT_HEIGHT).build());
        contextTabDiffButton.active = activeContextTab != ContextTab.DIFF;
        row1X += row1BtnWidth + rowGap;
        contextLoadButton = addRenderableWidget(Button.builder(Component.literal(activeContextTab == ContextTab.DIFF ? "Refresh" : "Fetch"), btn -> {
                    if (activeContextTab == ContextTab.DIFF) {
                        loadWorkspaceDiff();
                    } else {
                        fetchWorkspaceScript();
                    }
                })
                .bounds(row1X, row1Y, row1BtnWidth, INPUT_HEIGHT).build());
        row1X += row1BtnWidth + rowGap;
        contextClearQueueButton = addRenderableWidget(Button.builder(Component.literal("Clear Ctx"), btn -> clearContextQueue())
                .bounds(row1X, row1Y, row1BtnWidth, INPUT_HEIGHT).build());

        // Row 2: Editor-specific actions
        int row2ButtonCount = 4;
        int row2BtnWidth = Math.max(46, (editorWidth - rowGap * (row2ButtonCount - 1)) / row2ButtonCount);
        int row2X = editorXBase;
        switch (activeContextTab) {
            case SCRIPT -> {
                contextFormatButton = addRenderableWidget(Button.builder(Component.literal("Format"), btn -> formatContextJson())
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
                row2X += row2BtnWidth + rowGap;
                contextClearJsonButton = addRenderableWidget(Button.builder(Component.literal("Clear"), btn -> clearContextJson())
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
                row2X += row2BtnWidth + rowGap;
                contextDiffPrevButton = addRenderableWidget(Button.builder(Component.literal("<D"), btn -> navigateContextDiffChange(-1))
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
                row2X += row2BtnWidth + rowGap;
                contextDiffNextButton = addRenderableWidget(Button.builder(Component.literal("D>"), btn -> navigateContextDiffChange(1))
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
            }
            case DIFF -> {
                contextDiffPrevButton = addRenderableWidget(Button.builder(Component.literal("<D"), btn -> navigateContextDiffChange(-1))
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
                row2X += row2BtnWidth + rowGap;
                contextDiffNextButton = addRenderableWidget(Button.builder(Component.literal("D>"), btn -> navigateContextDiffChange(1))
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
                row2X += row2BtnWidth + rowGap;
                contextFormatButton = addRenderableWidget(Button.builder(Component.literal("Format"), btn -> formatContextJson())
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
                row2X += row2BtnWidth + rowGap;
                contextClearJsonButton = addRenderableWidget(Button.builder(Component.literal("Clear"), btn -> clearContextJson())
                        .bounds(row2X, row2Y, row2BtnWidth, INPUT_HEIGHT).build());
            }
            default -> {
            }
        }

        // Row 3: Range inputs + Add Range button
        int startWidth = 52;
        int endWidth = 52;
        int addWidth = Math.max(80, editorWidth - startWidth - endWidth - rowGap * 2);

        contextStartInput = new EditBox(this.font, editorXBase, row3Y, startWidth, INPUT_HEIGHT, Component.literal("start line"));
        contextStartInput.setMaxLength(8);
        contextStartInput.setHint(Component.literal("start"));
        addRenderableWidget(contextStartInput);

        contextEndInput = new EditBox(this.font, editorXBase + startWidth + rowGap, row3Y, endWidth, INPUT_HEIGHT, Component.literal("end line"));
        contextEndInput.setMaxLength(8);
        contextEndInput.setHint(Component.literal("end"));
        addRenderableWidget(contextEndInput);

        contextAddRangeButton = addRenderableWidget(Button.builder(Component.literal("Add Range -> Ctx"), btn -> addSelectedRangeAsContext())
                .bounds(editorXBase + startWidth + endWidth + rowGap * 2, row3Y, addWidth, INPUT_HEIGHT).build());

        if (activeContextTab == ContextTab.DIFF) {
            contextStartInput.active = false;
            contextEndInput.active = false;
            contextAddRangeButton.active = false;
        }

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
        int rowStep = CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP;
        contextVisibleRows = Math.max(4, Math.min(40, available / rowStep));
        int renderedRows = Math.max(1, contextVisibleRows);
        contextEditorX = editorXBase;
        contextEditorY = editorTop;
        contextEditorWidth = editorWidth;
        contextEditorHeight = renderedRows * rowStep - CONTEXT_ROW_GAP + CONTEXT_EDITOR_PADDING * 2;
        contextQueueTopY = contextEditorY + contextEditorHeight + 6;

        if (contextDiffMode) {
            clampContextScroll();
        } else {
            clampContextCursor();
            clampContextScroll();
            ensureContextCursorVisible();
        }
    }

    private void captureContextControlState() {
        saveCurrentTabEditorState();
        if (workspaceRenameMode && workspaceRenameInput != null) {
            workspaceRenameDraft = workspaceRenameInput.getValue();
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

    private void createWorkspaceDoc() {
        JsonObject payload = new JsonObject();
        int next = Math.max(1, ClientSessionState.getWorkspaceDocs().size() + 1);
        payload.addProperty("name", "workspace/file-" + next + ".json");
        payload.addProperty("path", "workspace/file-" + next + ".json");
        payload.addProperty("type", "manual");
        payload.addProperty("switchToNew", true);
        sendSessionAction("workspace_file_create", payload.toString());
        activeContextTab = ContextTab.SCRIPT;
        contextLoadedDocId = "";
        workspaceRenameMode = false;
        workspaceRenameDraft = "";
        setContextStatus("Creating new file...", 0xAAAAAA);
        createWidgets();
    }

    private void switchWorkspaceDoc(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        if (!ClientSessionState.setSelectedWorkspaceId(docId)) {
            return;
        }
        if (workspaceRenameMode) {
            workspaceRenameDraft = "";
        }
        activeContextTab = ContextTab.SCRIPT;
        contextLoadedDocId = "";
        clearContextDiffView();
        setContextStatus("Switched local file context", 0x55FF55);
        createWidgets();
    }

    private JsonObject workspaceReadArgs(boolean committed) {
        JsonObject args = new JsonObject();
        args.addProperty("committed", committed);
        String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
        if (selectedWorkspaceId != null && !selectedWorkspaceId.isBlank()) {
            args.addProperty("workspace_id", selectedWorkspaceId);
            args.addProperty("doc_id", selectedWorkspaceId);
        }
        return args;
    }

    private void syncActiveDocScriptIfNeeded() {
        if (activeContextTab != ContextTab.SCRIPT || contextDiffMode) {
            return;
        }
        String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
        if (selectedWorkspaceId == null || selectedWorkspaceId.isBlank()) {
            return;
        }
        if (selectedWorkspaceId.equals(contextLoadedDocId)) {
            return;
        }
        String script = ClientSessionState.getWorkspaceDocScriptJson(selectedWorkspaceId);
        if ((script == null || script.isBlank()) && selectedWorkspaceId.equals(ClientSessionState.getActiveDocId())) {
            script = ClientSessionState.getCurrentScriptJson();
        }
        if (script == null || script.isBlank()) {
            setContextJsonText("{\n}\n");
            contextLoadedDocId = selectedWorkspaceId;
            return;
        }
        setContextJsonText(script);
        contextLoadedDocId = selectedWorkspaceId;
    }

    private void refreshWorkspaceExplorerIfNeeded() {
        StringBuilder sb = new StringBuilder();
        sb.append(ClientSessionState.getSelectedWorkspaceId()).append('|');
        List<ClientSessionState.WorkspaceDocInfo> docs = ClientSessionState.getWorkspaceDocs();
        sb.append(docs.size());
        for (ClientSessionState.WorkspaceDocInfo doc : docs) {
            if (doc == null) {
                continue;
            }
            sb.append('|').append(doc.id())
                    .append(':').append(doc.name())
                    .append(':').append(doc.path())
                    .append(':').append(doc.type())
                    .append(':').append(doc.hasPendingPatch())
                    .append(':').append(doc.revision());
        }
        String signature = sb.toString();
        if (signature.equals(workspaceUiSignature)) {
            return;
        }
        workspaceUiSignature = signature;
        createWidgets();
    }

    private String activeContextFileName() {
        String activeDocName = ClientSessionState.getSelectedWorkspaceName();
        if (activeDocName == null || activeDocName.isBlank()) {
            String activeDocId = ClientSessionState.getSelectedWorkspaceId();
            activeDocName = (activeDocId == null || activeDocId.isBlank()) ? "workspace/main.json" : activeDocId + ".json";
        }
        return switch (activeContextTab) {
            case STATE -> "workspace-state.json";
            case SCRIPT -> activeDocName;
            case DIFF -> activeDocName + ".diff";
        };
    }

    private void saveCurrentTabEditorState() {
        switch (activeContextTab) {
            case STATE -> {
                stateJsonLines.clear();
                stateJsonLines.addAll(contextJsonLines);
                stateCursorLine = contextCursorLine;
                stateCursorColumn = contextCursorColumn;
                stateScroll = contextScroll;
            }
            case SCRIPT -> {
                scriptJsonLines.clear();
                scriptJsonLines.addAll(contextJsonLines);
                scriptCursorLine = contextCursorLine;
                scriptCursorColumn = contextCursorColumn;
                scriptScroll = contextScroll;
            }
            case DIFF -> {
                // Diff view uses contextDiffLines, no editor state to save
            }
        }
    }

    private void restoreTabEditorState(ContextTab tab) {
        switch (tab) {
            case STATE -> {
                contextDiffMode = false;
                contextJsonLines.clear();
                if (stateJsonLines.isEmpty()) {
                    contextJsonLines.add("");
                } else {
                    contextJsonLines.addAll(stateJsonLines);
                }
                contextCursorLine = stateCursorLine;
                contextCursorColumn = stateCursorColumn;
                contextScroll = stateScroll;
            }
            case SCRIPT -> {
                contextDiffMode = false;
                contextJsonLines.clear();
                if (scriptJsonLines.isEmpty()) {
                    contextJsonLines.add("");
                } else {
                    contextJsonLines.addAll(scriptJsonLines);
                }
                contextCursorLine = scriptCursorLine;
                contextCursorColumn = scriptCursorColumn;
                contextScroll = scriptScroll;
            }
            case DIFF -> {
                contextDiffMode = true;
                contextScroll = 0;
            }
        }
        contextPreferredColumn = -1;
        clearContextSelection();
        contextMouseSelecting = false;
        contextLongPressCtxMode = false;
    }

    private void switchContextTab(ContextTab newTab) {
        if (newTab == activeContextTab) {
            return;
        }
        saveCurrentTabEditorState();
        activeContextTab = newTab;
        restoreTabEditorState(newTab);
        createWidgets();
    }

    private void fetchWorkspaceScript() {
        if (scriptLoading) {
            setContextStatus("Already fetching script...", 0xFFAA55);
            return;
        }
        scriptLoading = true;
        setContextStatus("Fetching script...", 0xAAAAAA);

        String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
        ClientToolBridge.call("read_workspace_file", workspaceReadArgs(true))
                .thenAccept(result -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            scriptLoading = false;
                            String scriptText = extractWorkspaceScriptText(result);
                            if (scriptText.isBlank()) {
                                scriptText = "{\n}\n";
                                setContextStatus("No script data found in workspace state", 0xFFAA55);
                            } else {
                                setContextStatus("Script loaded", 0x55FF55);
                            }
                            if (selectedWorkspaceId != null && !selectedWorkspaceId.isBlank()) {
                                ClientSessionState.setWorkspaceDocScriptJson(selectedWorkspaceId, scriptText);
                            }
                            if (activeContextTab == ContextTab.SCRIPT) {
                                setContextJsonText(scriptText);
                                contextLoadedDocId = selectedWorkspaceId == null ? "" : selectedWorkspaceId;
                            } else {
                                // Update cache only
                                scriptJsonLines.clear();
                                String[] lines = scriptText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                                for (String line : lines) {
                                    scriptJsonLines.add(line == null ? "" : line);
                                }
                                if (scriptJsonLines.isEmpty()) {
                                    scriptJsonLines.add("");
                                }
                                scriptCursorLine = 0;
                                scriptCursorColumn = 0;
                                scriptScroll = 0;
                                contextLoadedDocId = selectedWorkspaceId == null ? "" : selectedWorkspaceId;
                            }
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            scriptLoading = false;
                            setContextStatus("Script fetch failed: " + shortError(ex.getMessage()), 0xFF5555);
                        });
                    }
                    return null;
                });
    }


    private void loadWorkspaceDiff() {
        setContextStatus("Loading workspace diff...", 0xAAAAAA);

        JsonObject committedArgs = workspaceReadArgs(true);
        JsonObject stagedArgs = workspaceReadArgs(false);

        ClientToolBridge.call("read_workspace_file", committedArgs)
                .thenCombine(ClientToolBridge.call("read_workspace_file", stagedArgs), (committed, staged) -> {
                    String committedText = extractWorkspaceScriptText(committed);
                    String stagedText = extractWorkspaceScriptText(staged);
                    return buildContextDiffView(committedText, stagedText);
                })
                .thenAccept(diff -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            applyContextDiffView(diff);
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> setContextStatus("Diff failed: " + shortError(ex.getMessage()), 0xFF5555));
                    }
                    return null;
                });
    }

    private String extractWorkspaceScriptText(JsonObject toolPayload) {
        if (toolPayload == null || !toolPayload.has("state") || !toolPayload.get("state").isJsonObject()) {
            return "";
        }
        JsonObject state = toolPayload.getAsJsonObject("state");
        if (state.has("script") && state.get("script").isJsonObject()) {
            return CONTEXT_GSON.toJson(state.get("script"));
        }
        if (state.has("script_json") && state.get("script_json").isJsonPrimitive()) {
            return state.get("script_json").getAsString();
        }
        return "";
    }

    private String[] normalizeLines(String text) {
        if (text == null) {
            return new String[0];
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private DiffBuildResult buildContextDiffView(String committedText, String stagedText) {
        String[] committedAll = normalizeLines(committedText);
        String[] stagedAll = normalizeLines(stagedText);
        int committedTotal = committedAll.length;
        int stagedTotal = stagedAll.length;

        boolean truncated = false;
        String[] committed = committedAll;
        String[] staged = stagedAll;
        if (committed.length > CONTEXT_DIFF_MAX_SOURCE_LINES) {
            committed = Arrays.copyOf(committed, CONTEXT_DIFF_MAX_SOURCE_LINES);
            truncated = true;
        }
        if (staged.length > CONTEXT_DIFF_MAX_SOURCE_LINES) {
            staged = Arrays.copyOf(staged, CONTEXT_DIFF_MAX_SOURCE_LINES);
            truncated = true;
        }

        List<DiffOp> ops = buildDiffOps(committed, staged);
        List<DiffViewLine> lines = new ArrayList<>();
        List<Integer> changeRows = new ArrayList<>();

        int oldLineNo = 1;
        int newLineNo = 1;
        int i = 0;
        while (i < ops.size()) {
            DiffOp op = ops.get(i);
            if (op.type() == DiffOpType.SAME) {
                int runStart = i;
                while (i < ops.size() && ops.get(i).type() == DiffOpType.SAME) {
                    i++;
                }
                int runLength = i - runStart;
                int collapseThreshold = CONTEXT_DIFF_EQUAL_CONTEXT_LINES * 2 + 2;
                if (runLength <= collapseThreshold) {
                    for (int j = runStart; j < i; j++) {
                        lines.add(new DiffViewLine(DiffLineType.SAME, oldLineNo, newLineNo, ops.get(j).text()));
                        oldLineNo++;
                        newLineNo++;
                    }
                } else {
                    int context = CONTEXT_DIFF_EQUAL_CONTEXT_LINES;
                    for (int j = 0; j < context; j++) {
                        lines.add(new DiffViewLine(DiffLineType.SAME, oldLineNo, newLineNo, ops.get(runStart + j).text()));
                        oldLineNo++;
                        newLineNo++;
                    }

                    int hidden = runLength - context * 2;
                    lines.add(new DiffViewLine(DiffLineType.SKIP, -1, -1, "... " + hidden + " unchanged lines ..."));
                    oldLineNo += hidden;
                    newLineNo += hidden;

                    for (int j = i - context; j < i; j++) {
                        lines.add(new DiffViewLine(DiffLineType.SAME, oldLineNo, newLineNo, ops.get(j).text()));
                        oldLineNo++;
                        newLineNo++;
                    }
                }
                continue;
            }

            if (op.type() == DiffOpType.REMOVE) {
                lines.add(new DiffViewLine(DiffLineType.REMOVED, oldLineNo, -1, op.text()));
                changeRows.add(lines.size() - 1);
                oldLineNo++;
                i++;
                continue;
            }

            lines.add(new DiffViewLine(DiffLineType.ADDED, -1, newLineNo, op.text()));
            changeRows.add(lines.size() - 1);
            newLineNo++;
            i++;
        }

        if (truncated) {
            lines.add(new DiffViewLine(
                    DiffLineType.SKIP,
                    -1,
                    -1,
                    "... diff source truncated at " + CONTEXT_DIFF_MAX_SOURCE_LINES + " lines per side ..."
            ));
        }

        return new DiffBuildResult(lines, changeRows, committedTotal, stagedTotal, truncated);
    }

    private List<DiffOp> buildDiffOps(String[] committed, String[] staged) {
        int n = committed.length;
        int m = staged.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (committed[i].equals(staged[j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<DiffOp> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && committed[i].equals(staged[j])) {
                ops.add(new DiffOp(DiffOpType.SAME, committed[i]));
                i++;
                j++;
                continue;
            }
            if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                ops.add(new DiffOp(DiffOpType.ADD, staged[j]));
                j++;
                continue;
            }
            if (i < n) {
                ops.add(new DiffOp(DiffOpType.REMOVE, committed[i]));
                i++;
            }
        }
        return ops;
    }

    private void clearContextDiffView() {
        exitDiffViewMode();
        clearDiffData();
    }

    private void exitDiffViewMode() {
        contextDiffMode = false;
    }

    private void clearDiffData() {
        contextDiffLines.clear();
        contextDiffChangeRows.clear();
        contextDiffNavIndex = -1;
    }

    private void applyContextDiffView(DiffBuildResult diff) {
        if (diff == null) {
            setContextStatus("Diff failed: empty result", 0xFF5555);
            return;
        }
        contextDiffMode = true;
        contextDiffLines.clear();
        contextDiffLines.addAll(diff.lines());
        contextDiffChangeRows.clear();
        contextDiffChangeRows.addAll(diff.changeRows());
        contextDiffNavIndex = contextDiffChangeRows.isEmpty() ? -1 : 0;

        contextCursorLine = 0;
        contextCursorColumn = 0;
        contextPreferredColumn = -1;
        clearContextSelection();
        contextMouseSelecting = false;
        contextLongPressCtxMode = false;

        if (!contextDiffChangeRows.isEmpty()) {
            int target = contextDiffChangeRows.get(contextDiffNavIndex);
            contextScroll = Math.max(0, target - Math.max(1, contextVisibleRows) / 2);
        } else {
            contextScroll = 0;
        }
        clampContextScroll();
        setContextEditorFocused(true);

        String status = "Diff loaded: " + diff.committedLineCount() + " -> " + diff.stagedLineCount()
                + " lines, " + diff.changeRows().size() + " changed rows";
        if (diff.truncated()) {
            status += " (truncated)";
        }
        status += " [F7/Shift+F7]";
        setContextStatus(status, 0x55FF55);
    }

    private void navigateContextDiffChange(int direction) {
        if (!contextDiffMode) {
            setContextStatus("Diff mode is not active", 0xFFAA55);
            return;
        }
        if (contextDiffChangeRows.isEmpty()) {
            setContextStatus("No changed rows in current diff", 0xFFAA55);
            return;
        }
        int size = contextDiffChangeRows.size();
        if (contextDiffNavIndex < 0 || contextDiffNavIndex >= size) {
            contextDiffNavIndex = direction >= 0 ? 0 : size - 1;
        } else {
            contextDiffNavIndex = (contextDiffNavIndex + (direction >= 0 ? 1 : -1) + size) % size;
        }
        int row = contextDiffChangeRows.get(contextDiffNavIndex);
        contextScroll = Math.max(0, row - Math.max(1, contextVisibleRows) / 2);
        clampContextScroll();
        setContextStatus("Diff change " + (contextDiffNavIndex + 1) + "/" + size, 0xAAD5FF);
    }

    private void focusNearestDiffChange(int rowIndex) {
        if (!contextDiffMode || contextDiffChangeRows.isEmpty()) {
            return;
        }
        int bestIndex = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < contextDiffChangeRows.size(); i++) {
            int dist = Math.abs(contextDiffChangeRows.get(i) - rowIndex);
            if (dist < bestDist) {
                bestDist = dist;
                bestIndex = i;
            }
        }
        contextDiffNavIndex = bestIndex;
        int row = contextDiffChangeRows.get(bestIndex);
        contextScroll = Math.max(0, row - Math.max(1, contextVisibleRows) / 2);
        clampContextScroll();
    }

    private void formatContextJson() {
        if (contextDiffMode) {
            setContextStatus("Diff view is read-only. Load/Clear to edit JSON.", 0xFFAA55);
            return;
        }
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
        exitDiffViewMode();
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
        contextCursorLine = 0;
        contextCursorColumn = 0;
        contextPreferredColumn = -1;
        clearContextSelection();
        contextMouseSelecting = false;
        contextLongPressCtxMode = false;
        clampContextScroll();
        clampContextCursor();
        ensureContextCursorVisible();
        contextRangeStart = 1;
        contextRangeEnd = Math.min(contextJsonLines.size(), CONTEXT_DEFAULT_RANGE);
        if (contextStartInput != null) {
            contextStartInput.setValue(Integer.toString(contextRangeStart));
        }
        if (contextEndInput != null) {
            contextEndInput.setValue(Integer.toString(contextRangeEnd));
        }
    }

    private void clampContextCursor() {
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
        }
        contextCursorLine = Math.max(0, Math.min(contextCursorLine, contextJsonLines.size() - 1));
        String line = contextJsonLines.get(contextCursorLine);
        int maxColumn = line == null ? 0 : line.length();
        contextCursorColumn = Math.max(0, Math.min(contextCursorColumn, maxColumn));
        clampContextSelectionAnchor();
    }

    private void clampContextSelectionAnchor() {
        if (!contextSelectionActive) {
            return;
        }
        if (contextJsonLines.isEmpty()) {
            clearContextSelection();
            return;
        }
        contextSelectionAnchorLine = Math.max(0, Math.min(contextSelectionAnchorLine, contextJsonLines.size() - 1));
        String line = getContextLine(contextSelectionAnchorLine);
        contextSelectionAnchorColumn = Math.max(0, Math.min(contextSelectionAnchorColumn, line.length()));
    }

    private void ensureContextCursorVisible() {
        if (contextDiffMode) {
            clampContextScroll();
            return;
        }
        clampContextCursor();
        int visibleRows = Math.max(1, contextVisibleRows);
        if (contextCursorLine < contextScroll) {
            contextScroll = contextCursorLine;
        } else if (contextCursorLine >= contextScroll + visibleRows) {
            contextScroll = contextCursorLine - visibleRows + 1;
        }
        clampContextScroll();
    }

    private void setContextCursor(int line, int column, boolean keepPreferredColumn) {
        if (contextDiffMode) {
            return;
        }
        contextCursorLine = line;
        contextCursorColumn = column;
        if (!keepPreferredColumn) {
            contextPreferredColumn = -1;
        }
        ensureContextCursorVisible();
    }

    private String getContextLine(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= contextJsonLines.size()) {
            return "";
        }
        String value = contextJsonLines.get(lineIndex);
        return value == null ? "" : value;
    }

    private void setContextLine(int lineIndex, String value) {
        if (lineIndex < 0 || lineIndex >= contextJsonLines.size()) {
            return;
        }
        contextJsonLines.set(lineIndex, value == null ? "" : value);
    }

    private void clearContextSelection() {
        contextSelectionActive = false;
    }

    private boolean hasContextSelection() {
        if (!contextSelectionActive) {
            return false;
        }
        clampContextSelectionAnchor();
        return contextSelectionAnchorLine != contextCursorLine || contextSelectionAnchorColumn != contextCursorColumn;
    }

    private void ensureSelectionAnchor() {
        if (contextSelectionActive) {
            return;
        }
        contextSelectionActive = true;
        contextSelectionAnchorLine = contextCursorLine;
        contextSelectionAnchorColumn = contextCursorColumn;
    }

    private void updateContextSelectionForCursorMove(boolean selecting) {
        if (selecting) {
            ensureSelectionAnchor();
            return;
        }
        clearContextSelection();
    }

    private int compareContextPosition(int line1, int col1, int line2, int col2) {
        if (line1 != line2) {
            return Integer.compare(line1, line2);
        }
        return Integer.compare(col1, col2);
    }

    private ContextSelectionRange getContextSelectionRange() {
        if (!hasContextSelection()) {
            return null;
        }
        int anchorLine = contextSelectionAnchorLine;
        int anchorColumn = contextSelectionAnchorColumn;
        int cursorLine = contextCursorLine;
        int cursorColumn = contextCursorColumn;
        if (compareContextPosition(anchorLine, anchorColumn, cursorLine, cursorColumn) <= 0) {
            return new ContextSelectionRange(anchorLine, anchorColumn, cursorLine, cursorColumn);
        }
        return new ContextSelectionRange(cursorLine, cursorColumn, anchorLine, anchorColumn);
    }

    private boolean deleteContextSelection() {
        ContextSelectionRange range = getContextSelectionRange();
        if (range == null) {
            return false;
        }
        String startLineText = getContextLine(range.startLine());
        String endLineText = getContextLine(range.endLine());
        int startColumn = Math.min(range.startColumn(), startLineText.length());
        int endColumn = Math.min(range.endColumn(), endLineText.length());

        if (range.startLine() == range.endLine()) {
            String merged = startLineText.substring(0, startColumn) + startLineText.substring(endColumn);
            setContextLine(range.startLine(), merged);
        } else {
            String merged = startLineText.substring(0, startColumn) + endLineText.substring(endColumn);
            setContextLine(range.startLine(), merged);
            for (int line = range.endLine(); line > range.startLine(); line--) {
                contextJsonLines.remove(line);
            }
        }

        setContextCursor(range.startLine(), startColumn, false);
        clearContextSelection();
        return true;
    }

    private String getContextSelectionText() {
        ContextSelectionRange range = getContextSelectionRange();
        if (range == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int lineIndex = range.startLine(); lineIndex <= range.endLine(); lineIndex++) {
            String line = getContextLine(lineIndex);
            int from = 0;
            int to = line.length();
            if (lineIndex == range.startLine()) {
                from = Math.min(range.startColumn(), line.length());
            }
            if (lineIndex == range.endLine()) {
                to = Math.min(range.endColumn(), line.length());
            }
            if (lineIndex > range.startLine()) {
                sb.append('\n');
            }
            sb.append(line, from, Math.max(from, to));
        }
        return sb.toString();
    }

    private void copyContextSelectionToClipboard() {
        if (this.minecraft == null) {
            return;
        }
        String text = getContextSelectionText();
        if (!text.isEmpty()) {
            this.minecraft.keyboardHandler.setClipboard(text);
        }
    }

    private void selectAllContextText() {
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
        }
        contextSelectionActive = true;
        contextSelectionAnchorLine = 0;
        contextSelectionAnchorColumn = 0;
        int lastLine = contextJsonLines.size() - 1;
        setContextCursor(lastLine, getContextLine(lastLine).length(), false);
    }

    private void insertContextText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
        }
        deleteContextSelection();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String current = getContextLine(contextCursorLine);
        String before = current.substring(0, Math.min(contextCursorColumn, current.length()));
        String after = current.substring(Math.min(contextCursorColumn, current.length()));
        String[] parts = normalized.split("\n", -1);

        if (parts.length == 1) {
            setContextLine(contextCursorLine, before + parts[0] + after);
            setContextCursor(contextCursorLine, before.length() + parts[0].length(), false);
            return;
        }

        setContextLine(contextCursorLine, before + parts[0]);
        int insertAt = contextCursorLine + 1;
        for (int i = 1; i < parts.length; i++) {
            contextJsonLines.add(insertAt, parts[i]);
            insertAt++;
        }

        int lastLine = contextCursorLine + parts.length - 1;
        setContextLine(lastLine, getContextLine(lastLine) + after);
        setContextCursor(lastLine, parts[parts.length - 1].length(), false);
    }

    private void backspaceContextChar() {
        if (deleteContextSelection()) {
            return;
        }
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
            setContextCursor(0, 0, false);
            return;
        }
        String line = getContextLine(contextCursorLine);
        if (contextCursorColumn > 0) {
            int removeIndex = contextCursorColumn - 1;
            setContextLine(contextCursorLine, line.substring(0, removeIndex) + line.substring(contextCursorColumn));
            setContextCursor(contextCursorLine, removeIndex, false);
            return;
        }

        if (contextCursorLine <= 0) {
            return;
        }

        String prevLine = getContextLine(contextCursorLine - 1);
        String currentLine = getContextLine(contextCursorLine);
        int newColumn = prevLine.length();
        setContextLine(contextCursorLine - 1, prevLine + currentLine);
        contextJsonLines.remove(contextCursorLine);
        setContextCursor(contextCursorLine - 1, newColumn, false);
    }

    private void deleteContextChar() {
        if (deleteContextSelection()) {
            return;
        }
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
            setContextCursor(0, 0, false);
            return;
        }
        String line = getContextLine(contextCursorLine);
        if (contextCursorColumn < line.length()) {
            setContextLine(contextCursorLine, line.substring(0, contextCursorColumn) + line.substring(contextCursorColumn + 1));
            return;
        }

        if (contextCursorLine >= contextJsonLines.size() - 1) {
            return;
        }

        String nextLine = getContextLine(contextCursorLine + 1);
        setContextLine(contextCursorLine, line + nextLine);
        contextJsonLines.remove(contextCursorLine + 1);
        setContextCursor(contextCursorLine, contextCursorColumn, false);
    }

    private void moveContextCursorHorizontal(int delta, boolean selecting) {
        if (delta == 0 || contextJsonLines.isEmpty()) {
            return;
        }
        if (!selecting && hasContextSelection()) {
            ContextSelectionRange range = getContextSelectionRange();
            if (range != null) {
                if (delta < 0) {
                    setContextCursor(range.startLine(), range.startColumn(), false);
                } else {
                    setContextCursor(range.endLine(), range.endColumn(), false);
                }
                clearContextSelection();
                return;
            }
        }
        int line = contextCursorLine;
        int column = contextCursorColumn;
        updateContextSelectionForCursorMove(selecting);
        if (delta < 0) {
            if (column > 0) {
                setContextCursor(line, column - 1, false);
            } else if (line > 0) {
                setContextCursor(line - 1, getContextLine(line - 1).length(), false);
            }
            return;
        }

        String current = getContextLine(line);
        if (column < current.length()) {
            setContextCursor(line, column + 1, false);
        } else if (line < contextJsonLines.size() - 1) {
            setContextCursor(line + 1, 0, false);
        }
    }

    private void moveContextCursorVertical(int deltaRows, boolean selecting) {
        if (deltaRows == 0 || contextJsonLines.isEmpty()) {
            return;
        }
        updateContextSelectionForCursorMove(selecting);
        int preferred = contextPreferredColumn >= 0 ? contextPreferredColumn : contextCursorColumn;
        int targetLine = Math.max(0, Math.min(contextCursorLine + deltaRows, contextJsonLines.size() - 1));
        int targetColumn = Math.min(preferred, getContextLine(targetLine).length());
        contextPreferredColumn = preferred;
        setContextCursor(targetLine, targetColumn, true);
    }

    private void moveContextCursorToLineStart(boolean selecting) {
        updateContextSelectionForCursorMove(selecting);
        setContextCursor(contextCursorLine, 0, false);
    }

    private void moveContextCursorToLineEnd(boolean selecting) {
        updateContextSelectionForCursorMove(selecting);
        setContextCursor(contextCursorLine, getContextLine(contextCursorLine).length(), false);
    }

    private void clampContextScroll() {
        int totalLines = contextDiffMode ? contextDiffLines.size() : contextJsonLines.size();
        int max = Math.max(0, totalLines - Math.max(1, contextVisibleRows));
        contextScroll = Math.max(0, Math.min(contextScroll, max));
    }

    private void scrollContextEditor(int deltaRows) {
        if (deltaRows == 0) {
            return;
        }
        contextScroll += deltaRows;
        clampContextScroll();
    }

    private void addSelectedRangeAsContext() {
        if (contextDiffMode) {
            setContextStatus("Range attach is disabled in diff view", 0xFFAA55);
            return;
        }
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

        String fileName = activeContextFileName();
        String label = fileName + ":" + start + "-" + end;
        queuedContexts.add(new ContextSnippet(label, snippetText));
        setContextStatus("Added context " + label, 0x55FF55);
    }

    private void addSelectionAsContext() {
        ContextSelectionRange sel = getContextSelectionRange();
        if (sel == null) return;
        int startLine = sel.startLine() + 1; // 0-indexed to 1-indexed
        int endLine = sel.endLine() + 1;
        if (contextStartInput != null) contextStartInput.setValue(Integer.toString(startLine));
        if (contextEndInput != null) contextEndInput.setValue(Integer.toString(endLine));
        addSelectedRangeAsContext();
        clearContextSelection();
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

    private void refreshContextDiffControls() {
        boolean hasChanges = activeContextTab == ContextTab.DIFF && contextDiffMode && !contextDiffChangeRows.isEmpty();
        if (contextDiffPrevButton != null) {
            contextDiffPrevButton.active = hasChanges;
        }
        if (contextDiffNextButton != null) {
            contextDiffNextButton.active = hasChanges;
        }
    }

    private void setContextEditorFocused(boolean focused) {
        contextEditorFocused = focused;
        if (!focused) {
            return;
        }
        if (input != null) {
            input.setFocused(false);
        }
        if (contextStartInput != null) {
            contextStartInput.setFocused(false);
        }
        if (contextEndInput != null) {
            contextEndInput.setFocused(false);
        }
    }

    private boolean isInsideContextEditor(double mouseX, double mouseY) {
        return mouseX >= contextEditorX && mouseX < contextEditorX + contextEditorWidth
                && mouseY >= contextEditorY && mouseY < contextEditorY + contextEditorHeight;
    }

    private void placeContextCursorFromMouse(double mouseX, double mouseY) {
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
        }
        int rowStep = CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP;
        int row = (int) ((mouseY - (contextEditorY + CONTEXT_EDITOR_PADDING)) / rowStep);
        row = Math.max(0, Math.min(row, Math.max(0, contextVisibleRows - 1)));
        int line = Math.max(0, Math.min(contextScroll + row, contextJsonLines.size() - 1));

        int textX = contextEditorX + CONTEXT_EDITOR_GUTTER;
        int targetX = (int) (mouseX - textX);
        int column = columnAtPixel(getContextLine(line), targetX);
        setContextCursor(line, column, false);
    }

    private int columnAtPixel(String text, int pixelX) {
        if (text == null || text.isEmpty() || pixelX <= 0) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            int charWidth = this.font.width(String.valueOf(text.charAt(i)));
            int mid = width + charWidth / 2;
            if (pixelX <= mid) {
                return i;
            }
            width += charWidth;
        }
        return text.length();
    }

    private boolean handleContextEditorKeyPressed(int keyCode, int modifiers) {
        if (!contextEditorFocused) {
            return false;
        }
        if (contextDiffMode) {
            return switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> {
                    scrollContextEditor(-1);
                    yield true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    scrollContextEditor(1);
                    yield true;
                }
                case GLFW.GLFW_KEY_PAGE_UP -> {
                    scrollContextEditor(-Math.max(1, contextVisibleRows - 1));
                    yield true;
                }
                case GLFW.GLFW_KEY_PAGE_DOWN -> {
                    scrollContextEditor(Math.max(1, contextVisibleRows - 1));
                    yield true;
                }
                case GLFW.GLFW_KEY_HOME -> {
                    contextScroll = 0;
                    clampContextScroll();
                    yield true;
                }
                case GLFW.GLFW_KEY_END -> {
                    contextScroll = Integer.MAX_VALUE;
                    clampContextScroll();
                    yield true;
                }
                case GLFW.GLFW_KEY_F7 -> {
                    navigateContextDiffChange(hasShiftDown() ? -1 : 1);
                    yield true;
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    navigateContextDiffChange(-1);
                    yield true;
                }
                case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    navigateContextDiffChange(1);
                    yield true;
                }
                default -> false;
            };
        }
        if (hasControlDown()) {
            if (keyCode == GLFW.GLFW_KEY_A) {
                selectAllContextText();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_C) {
                copyContextSelectionToClipboard();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_X) {
                copyContextSelectionToClipboard();
                deleteContextSelection();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                String clipboard = this.minecraft == null ? "" : this.minecraft.keyboardHandler.getClipboard();
                if (clipboard != null && !clipboard.isEmpty()) {
                    insertContextText(clipboard);
                }
                return true;
            }
        }
        boolean selecting = hasShiftDown();
        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                insertContextText("\n");
                yield true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                backspaceContextChar();
                yield true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                deleteContextChar();
                yield true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                moveContextCursorHorizontal(-1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                moveContextCursorHorizontal(1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_UP -> {
                moveContextCursorVertical(-1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                moveContextCursorVertical(1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                moveContextCursorToLineStart(selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_END -> {
                moveContextCursorToLineEnd(selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                moveContextCursorVertical(-Math.max(1, contextVisibleRows - 1), selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                moveContextCursorVertical(Math.max(1, contextVisibleRows - 1), selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                insertContextText("    ");
                yield true;
            }
            default -> false;
        };
    }

    private boolean handleContextEditorCharTyped(char codePoint, int modifiers) {
        if (!contextEditorFocused || hasControlDown() || contextDiffMode) {
            return false;
        }
        if (Character.isISOControl(codePoint)) {
            return false;
        }
        insertContextText(Character.toString(codePoint));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (workspaceRenameMode) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                exitWorkspaceRenameMode();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmWorkspaceRename();
                return true;
            }
            if (workspaceRenameInput != null && workspaceRenameInput.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

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

        if (handleContextEditorKeyPressed(keyCode, modifiers)) {
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
        if (workspaceRenameMode) {
            if (workspaceRenameInput != null && workspaceRenameInput.charTyped(codePoint, modifiers)) {
                return true;
            }
            return super.charTyped(codePoint, modifiers);
        }
        if (discardReasonMode) {
            if (discardReasonInput != null && discardReasonInput.charTyped(codePoint, modifiers)) {
                return true;
            }
            return super.charTyped(codePoint, modifiers);
        }
        if (handleContextEditorCharTyped(codePoint, modifiers)) {
            return true;
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
        // Handle info overlay clicks
        if (button == 0 && infoOverlayVisible) {
            int panelWidth = getPanelWidth();
            int panelX = getPanelX(panelWidth);
            int overlayLeft = panelX + PADDING;
            int overlayTop = getMessageTop(panelWidth);
            int overlayBottom = getMessageBottom();
            int overlayRight = panelX + panelWidth - PADDING;

            // Check close button [X]
            int closeBtnX = overlayRight - PADDING - this.font.width("[X]");
            int closeBtnY = overlayTop + 4;
            if (mouseX >= closeBtnX && mouseX <= overlayRight - PADDING && mouseY >= closeBtnY && mouseY <= closeBtnY + this.font.lineHeight) {
                infoOverlayVisible = false;
                return true;
            }

            // Click inside overlay area -> consume event
            if (mouseX >= overlayLeft && mouseX <= overlayRight && mouseY >= overlayTop && mouseY <= overlayBottom) {
                return true;
            }

            // Click outside overlay -> close it
            infoOverlayVisible = false;
        }

        if (button == 0 && isInsideContextEditor(mouseX, mouseY)) {
            setContextEditorFocused(true);
            contextMousePressTime = System.currentTimeMillis();
            contextLongPressCtxMode = false;
            if (contextDiffMode) {
                int rowStep = CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP;
                int row = (int) ((mouseY - (contextEditorY + CONTEXT_EDITOR_PADDING)) / rowStep);
                row = Math.max(0, Math.min(row, Math.max(0, contextVisibleRows - 1)));
                int line = Math.max(0, Math.min(contextScroll + row, Math.max(0, contextDiffLines.size() - 1)));
                focusNearestDiffChange(line);
                contextMouseSelecting = false;
                return true;
            }
            if (hasShiftDown()) {
                ensureSelectionAnchor();
            } else {
                clearContextSelection();
            }
            placeContextCursorFromMouse(mouseX, mouseY);
            if (!hasShiftDown()) {
                contextSelectionActive = true;
                contextSelectionAnchorLine = contextCursorLine;
                contextSelectionAnchorColumn = contextCursorColumn;
            }
            contextMouseSelecting = true;
            return true;
        }
        if (button == 0) {
            contextMouseSelecting = false;
        }
        if (contextEditorFocused) {
            contextEditorFocused = false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (contextDiffMode) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (button == 0 && contextMouseSelecting && contextEditorFocused) {
            if (!contextLongPressCtxMode && System.currentTimeMillis() - contextMousePressTime > CONTEXT_LONG_PRESS_MS) {
                contextLongPressCtxMode = true;
            }
            ensureSelectionAnchor();
            placeContextCursorFromMouse(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (contextLongPressCtxMode && hasContextSelection()) {
                addSelectionAsContext();
            }
            contextMouseSelecting = false;
            contextLongPressCtxMode = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Handle info overlay scrolling
        if (infoOverlayVisible) {
            int panelWidth = getPanelWidth();
            int panelX = getPanelX(panelWidth);
            int overlayLeft = panelX + PADDING;
            int overlayTop = getMessageTop(panelWidth);
            int overlayBottom = getMessageBottom();
            int overlayRight = panelX + panelWidth - PADDING;
            if (mouseX >= overlayLeft && mouseX <= overlayRight && mouseY >= overlayTop && mouseY <= overlayBottom) {
                int step = (this.font.lineHeight + LINE_SPACING) * 3;
                infoOverlayScroll -= verticalAmount * step;
                return true;
            }
        }

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        if (mouseX < panelX) {
            int delta = verticalAmount > 0 ? -3 : 3;
            if (delta != 0) {
                int before = contextScroll;
                scrollContextEditor(delta);
                if (before != contextScroll) {
                    return true;
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
        if (checkpointModeButton != null) { checkpointModeButton.setMessage(Component.literal(modeLabel())); }
        if (workspaceRenameMode) {
            String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
            if (selectedWorkspaceId == null || selectedWorkspaceId.isBlank()) {
                exitWorkspaceRenameMode();
            }
        }
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
        refreshContextDiffControls();
        refreshWorkspaceExplorerIfNeeded();
        syncActiveDocScriptIfNeeded();

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        drawContextPanel(gfx, panelX);

        int panelLeft = panelX;
        int panelRight = this.width;
        int panelTop = 0;
        int panelBottom = this.height;
        gfx.fill(panelLeft, panelTop, panelRight, panelBottom, 0xAA000000);

        drawHeader(gfx, panelX, panelWidth);
        if (!infoOverlayVisible) {
            drawMessages(gfx, panelX, panelWidth);
        }
        drawInfoOverlay(gfx, panelX, panelWidth);
        drawStatus(gfx, panelX);

        // Update info button label
        if (infoButton != null) {
            int infoCount = countInfoSections(panelWidth - PADDING * 2);
            if (infoCount > 0) {
                infoButton.setMessage(Component.literal("[i " + infoCount + "]"));
                infoButton.active = true;
            } else {
                infoButton.setMessage(Component.literal("[i]"));
                infoButton.active = false;
            }
        }

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
        gfx.drawString(this.font, "Workspace Files", x, y, 0xE8F0FF, true);
        y += this.font.lineHeight + 2;
        String docName = ClientSessionState.getSelectedWorkspaceName();
        if (docName == null || docName.isBlank()) {
            docName = "workspace/main.json";
        }
        String modeHint = switch (activeContextTab) {
            case STATE -> "State view";
            case SCRIPT -> docName + " (script)";
            case DIFF -> docName + " diff (committed vs staged) [F7 nav]";
        };
        gfx.drawString(this.font, modeHint, x, y, 0x9CAECC, false);

        int start = parsePositiveInt(contextStartInput == null ? "" : contextStartInput.getValue(), contextRangeStart);
        int end = parsePositiveInt(contextEndInput == null ? "" : contextEndInput.getValue(), contextRangeEnd);
        if (start > end) {
            int t = start;
            start = end;
            end = t;
        }

        drawContextEditor(gfx, start, end);

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

    private void drawContextEditor(GuiGraphics gfx, int startLine, int endLine) {
        if (contextDiffMode) {
            drawContextDiffEditor(gfx);
            return;
        }
        drawContextTextEditor(gfx, startLine, endLine);
    }

    private void drawContextTextEditor(GuiGraphics gfx, int startLine, int endLine) {
        if (contextJsonLines.isEmpty()) {
            contextJsonLines.add("");
        }
        clampContextCursor();
        clampContextScroll();

        int left = contextEditorX;
        int top = contextEditorY;
        int right = contextEditorX + contextEditorWidth;
        int bottom = contextEditorY + contextEditorHeight;
        int bg = contextEditorFocused ? 0xAA0B111A : 0xAA080E16;
        gfx.fill(left, top, right, bottom, bg);
        gfx.fill(left, top, right, top + 1, 0xFF2F3A4D);
        gfx.fill(left, bottom - 1, right, bottom, 0xFF2F3A4D);
        gfx.fill(left, top, left + 1, bottom, 0xFF2F3A4D);
        gfx.fill(right - 1, top, right, bottom, 0xFF2F3A4D);

        int rowStep = CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP;
        int baseY = top + CONTEXT_EDITOR_PADDING;
        int textX = left + CONTEXT_EDITOR_GUTTER;
        int textWidth = Math.max(24, right - textX - CONTEXT_EDITOR_PADDING);
        int maxTextX = right - CONTEXT_EDITOR_PADDING - 1;
        ContextSelectionRange selection = getContextSelectionRange();

        int firstLine = contextScroll;
        int visibleRows = Math.max(1, contextVisibleRows);
        int lastLineExclusive = Math.min(contextJsonLines.size(), firstLine + visibleRows);
        for (int lineIndex = firstLine; lineIndex < lastLineExclusive; lineIndex++) {
            int row = lineIndex - firstLine;
            int rowY = baseY + row * rowStep;
            int rowBottom = rowY + CONTEXT_ROW_HEIGHT;
            int lineNo = lineIndex + 1;
            int lineNoColor = 0x8A99B8;
            if (lineNo >= startLine && lineNo <= endLine) {
                gfx.fill(left + 1, rowY, right - 1, rowBottom, 0x33335522);
                lineNoColor = 0xFFD27D;
            }
            String fullLine = getContextLine(lineIndex);
            if (selection != null && lineIndex >= selection.startLine() && lineIndex <= selection.endLine()) {
                int selStart = 0;
                int selEnd = fullLine.length();
                if (lineIndex == selection.startLine()) {
                    selStart = Math.min(selection.startColumn(), fullLine.length());
                }
                if (lineIndex == selection.endLine()) {
                    selEnd = Math.min(selection.endColumn(), fullLine.length());
                }
                if (selEnd > selStart) {
                    int selX1 = textX + this.font.width(fullLine.substring(0, selStart));
                    int selX2 = textX + this.font.width(fullLine.substring(0, selEnd));
                    int drawSelX1 = Math.max(textX, Math.min(selX1, maxTextX));
                    int drawSelX2 = Math.max(textX, Math.min(selX2, maxTextX));
                    if (drawSelX2 <= drawSelX1) {
                        drawSelX2 = Math.min(maxTextX, drawSelX1 + 1);
                    }
                    if (drawSelX2 > drawSelX1) {
                        int selColor = contextLongPressCtxMode ? 0x6655CC55 : 0x665A8BFF;
                        gfx.fill(drawSelX1, rowY + 2, drawSelX2, rowBottom - 2, selColor);
                    }
                }
            }
            gfx.drawString(this.font, Integer.toString(lineNo), left + 4, rowY + 5, lineNoColor, false);
            String lineText = clipTextToWidth(fullLine, textWidth);
            gfx.drawString(this.font, lineText, textX, rowY + 5, 0xD6E0F5, false);
        }

        if (contextEditorFocused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            if (contextCursorLine >= firstLine && contextCursorLine < lastLineExclusive) {
                int row = contextCursorLine - firstLine;
                int caretY = baseY + row * rowStep + 3;
                int caretHeight = Math.max(4, CONTEXT_ROW_HEIGHT - 6);
                String line = getContextLine(contextCursorLine);
                int cursor = Math.min(contextCursorColumn, line.length());
                int caretX = textX + this.font.width(line.substring(0, cursor));
                int minCaretX = textX;
                int maxCaretX = maxTextX;
                caretX = Math.max(minCaretX, Math.min(caretX, maxCaretX));
                gfx.fill(caretX, caretY, caretX + 1, caretY + caretHeight, 0xFFE8F0FF);
            }
        }
    }

    private void drawContextDiffEditor(GuiGraphics gfx) {
        int left = contextEditorX;
        int top = contextEditorY;
        int right = contextEditorX + contextEditorWidth;
        int bottom = contextEditorY + contextEditorHeight;
        int bg = contextEditorFocused ? 0xAA0B111A : 0xAA080E16;
        gfx.fill(left, top, right, bottom, bg);
        gfx.fill(left, top, right, top + 1, 0xFF2F3A4D);
        gfx.fill(left, bottom - 1, right, bottom, 0xFF2F3A4D);
        gfx.fill(left, top, left + 1, bottom, 0xFF2F3A4D);
        gfx.fill(right - 1, top, right, bottom, 0xFF2F3A4D);

        int rowStep = CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP;
        int baseY = top + CONTEXT_EDITOR_PADDING;
        int gutter = CONTEXT_DIFF_GUTTER;
        int oldNoX = left + 4;
        int newNoX = left + 36;
        int markerX = left + gutter - 14;
        int textX = left + gutter;
        int textWidth = Math.max(24, right - textX - CONTEXT_EDITOR_PADDING);

        int firstLine = contextScroll;
        int visibleRows = Math.max(1, contextVisibleRows);
        int lastLineExclusive = Math.min(contextDiffLines.size(), firstLine + visibleRows);
        for (int lineIndex = firstLine; lineIndex < lastLineExclusive; lineIndex++) {
            int row = lineIndex - firstLine;
            int rowY = baseY + row * rowStep;
            int rowBottom = rowY + CONTEXT_ROW_HEIGHT;
            DiffViewLine diffLine = contextDiffLines.get(lineIndex);

            int textColor = 0xD6E0F5;
            int markerColor = 0x7A8CAA;
            switch (diffLine.type()) {
                case ADDED -> {
                    gfx.fill(left + 1, rowY, right - 1, rowBottom, 0x22306A2A);
                    textColor = 0xBFFFC9;
                    markerColor = 0x66DD88;
                }
                case REMOVED -> {
                    gfx.fill(left + 1, rowY, right - 1, rowBottom, 0x224D2323);
                    textColor = 0xFFBFC0;
                    markerColor = 0xFF7C7C;
                }
                case SKIP -> {
                    gfx.fill(left + 1, rowY, right - 1, rowBottom, 0x22262C36);
                    textColor = 0x8FA2C4;
                    markerColor = 0x8FA2C4;
                }
                case SAME -> {
                }
            }

            if (contextDiffNavIndex >= 0
                    && contextDiffNavIndex < contextDiffChangeRows.size()
                    && contextDiffChangeRows.get(contextDiffNavIndex) == lineIndex) {
                gfx.fill(left + 1, rowY, right - 1, rowY + 1, 0xFFD3A65E);
                gfx.fill(left + 1, rowBottom - 1, right - 1, rowBottom, 0xFFD3A65E);
            }

            if (diffLine.oldLineNo() > 0) {
                gfx.drawString(this.font, Integer.toString(diffLine.oldLineNo()), oldNoX, rowY + 5, 0x8A99B8, false);
            }
            if (diffLine.newLineNo() > 0) {
                gfx.drawString(this.font, Integer.toString(diffLine.newLineNo()), newNoX, rowY + 5, 0x8A99B8, false);
            }
            String marker = switch (diffLine.type()) {
                case ADDED -> "+";
                case REMOVED -> "-";
                case SKIP -> "~";
                default -> " ";
            };
            gfx.drawString(this.font, marker, markerX, rowY + 5, markerColor, false);
            gfx.drawString(this.font, clipTextToWidth(diffLine.text(), textWidth), textX, rowY + 5, textColor, false);
        }
    }

    private String clipTextToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = this.font.width(ellipsis);
        int widthLimit = Math.max(0, maxWidth - ellipsisWidth);
        int width = 0;
        int end = 0;
        while (end < text.length()) {
            int charWidth = this.font.width(String.valueOf(text.charAt(end)));
            if (width + charWidth > widthLimit) {
                break;
            }
            width += charWidth;
            end++;
        }
        return text.substring(0, end) + ellipsis;
    }

    private List<EditBox> collectEditableBoxes() {
        List<EditBox> boxes = new ArrayList<>();
        if (input != null) {
            boxes.add(input);
        }
        if (workspaceRenameInput != null) {
            boxes.add(workspaceRenameInput);
        }
        if (contextStartInput != null) {
            boxes.add(contextStartInput);
        }
        if (contextEndInput != null) {
            boxes.add(contextEndInput);
        }
        return boxes;
    }

    private void drawHeader(GuiGraphics gfx, int panelX, int panelWidth) {
        int y = PADDING;
        gfx.drawString(this.font, "P2S Session", panelX + PADDING, y, 0xFFFFFF, true);
        y += this.font.lineHeight + 2;

        if (ClientSessionState.isActive()) {
            String projectName = ClientSessionState.getProjectName();
            if (projectName != null && !projectName.isBlank()) {
                gfx.drawString(this.font, "Project " + projectName, panelX + PADDING, y, 0xCFE1FF, true);
                y += this.font.lineHeight + 2;
            }
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
    }

    private int countInfoSections(int contentWidth) {
        int count = 0;
        if (!getChoicePromptLines(contentWidth).isEmpty()) count++;
        if (!getPreviewLines(contentWidth).isEmpty()) count++;
        if (!getSummaryLines(contentWidth).isEmpty()) count++;
        if (!getTodoLines(contentWidth).isEmpty()) count++;
        if (!getCheckpointLines(contentWidth).isEmpty()) count++;
        return count;
    }

    private void drawInfoOverlay(GuiGraphics gfx, int panelX, int panelWidth) {
        if (!infoOverlayVisible) return;

        int contentWidth = panelWidth - PADDING * 4;
        int overlayLeft = panelX + PADDING;
        int overlayTop = getMessageTop(panelWidth);
        int overlayBottom = getMessageBottom();
        int overlayRight = panelX + panelWidth - PADDING;
        int overlayHeight = overlayBottom - overlayTop;
        if (overlayHeight <= 0) return;

        // Semi-transparent background
        gfx.fill(overlayLeft, overlayTop, overlayRight, overlayBottom, 0xDD000000);

        // Close button [X] in top-right
        int closeBtnX = overlayRight - PADDING - this.font.width("[X]");
        int closeBtnY = overlayTop + 4;
        gfx.drawString(this.font, "[X]", closeBtnX, closeBtnY, 0xFF6666, true);

        // Build all overlay content lines
        List<OverlayLine> allLines = new ArrayList<>();
        int cw = contentWidth - PADDING;

        List<FormattedCharSequence> choicePromptLines = getChoicePromptLines(cw);
        if (!choicePromptLines.isEmpty()) {
            allLines.add(new OverlayLine(null, 0xFFCC66, true, "Action Required"));
            for (FormattedCharSequence line : choicePromptLines) {
                allLines.add(new OverlayLine(line, 0xFFE6AA, false, null));
            }
            allLines.add(new OverlayLine(null, 0, false, null)); // spacer
        }

        List<FormattedCharSequence> previewLines = getPreviewLines(cw);
        if (!previewLines.isEmpty()) {
            int color = 0xFFDD88;
            String risk = ClientSessionState.getPreviewRisk();
            if (risk == null || risk.isBlank()) risk = ClientSessionState.getPendingRisk();
            if (risk == null || risk.isBlank()) risk = "low";
            if ("high".equalsIgnoreCase(risk)) color = 0xFF6666;
            else if ("medium".equalsIgnoreCase(risk)) color = 0xFFAA55;
            int changed = ClientSessionState.getPreviewChangedBlocks();
            if (changed <= 0) changed = ClientSessionState.getPendingChangedBlocks();
            allLines.add(new OverlayLine(null, color, true, "Pending Patch (" + changed + " blocks, " + risk + ")"));
            for (FormattedCharSequence line : previewLines) {
                allLines.add(new OverlayLine(line, 0xE0E0E0, false, null));
            }
            allLines.add(new OverlayLine(null, 0, false, null));
        }

        List<FormattedCharSequence> summaryLines = getSummaryLines(cw);
        if (!summaryLines.isEmpty()) {
            allLines.add(new OverlayLine(null, 0xAAAAAA, true, "Current Structure"));
            for (FormattedCharSequence line : summaryLines) {
                allLines.add(new OverlayLine(line, 0xCCCCCC, false, null));
            }
            allLines.add(new OverlayLine(null, 0, false, null));
        }

        List<FormattedCharSequence> todoLines = getTodoLines(cw);
        if (!todoLines.isEmpty()) {
            allLines.add(new OverlayLine(null, 0x99CCFF, true, "Todo"));
            for (FormattedCharSequence line : todoLines) {
                allLines.add(new OverlayLine(line, 0xCCDDEE, false, null));
            }
            allLines.add(new OverlayLine(null, 0, false, null));
        }

        List<FormattedCharSequence> checkpointLines = getCheckpointLines(cw);
        if (!checkpointLines.isEmpty()) {
            allLines.add(new OverlayLine(null, 0x99FFCC, true, "Checkpoints [mode=" + modeLabel() + "]"));
            for (FormattedCharSequence line : checkpointLines) {
                allLines.add(new OverlayLine(line, 0xCCFFEE, false, null));
            }
        }

        if (allLines.isEmpty()) {
            gfx.drawString(this.font, "No info available", overlayLeft + PADDING, overlayTop + PADDING + this.font.lineHeight, 0x888888, true);
            return;
        }

        // Compute total content height and clamp scroll
        int lineH = this.font.lineHeight + LINE_SPACING;
        int totalContentHeight = allLines.size() * lineH;
        int maxScroll = Math.max(0, totalContentHeight - overlayHeight + PADDING * 2);
        infoOverlayScroll = clamp(infoOverlayScroll, 0, maxScroll);

        // Enable scissor to clip content within overlay
        gfx.enableScissor(overlayLeft, overlayTop, overlayRight, overlayBottom);

        int drawY = overlayTop + PADDING - (int) infoOverlayScroll;
        for (OverlayLine ol : allLines) {
            if (ol.title != null) {
                gfx.drawString(this.font, ol.title, overlayLeft + PADDING, drawY, ol.color, true);
            } else if (ol.formatted != null) {
                gfx.drawString(this.font, ol.formatted, overlayLeft + PADDING, drawY, ol.color, true);
            }
            drawY += lineH;
        }

        gfx.disableScissor();

        // Scrollbar indicator if content overflows
        if (totalContentHeight > overlayHeight - PADDING * 2) {
            int barHeight = Math.max(10, (overlayHeight * overlayHeight) / totalContentHeight);
            int barTrack = overlayHeight - barHeight;
            int barY = overlayTop + (maxScroll > 0 ? (int) (infoOverlayScroll / maxScroll * barTrack) : 0);
            gfx.fill(overlayRight - 3, barY, overlayRight - 1, barY + barHeight, 0x88FFFFFF);
        }
    }

    private record OverlayLine(FormattedCharSequence formatted, int color, boolean isTitle, String title) {}

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
        return base;
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


    private List<FormattedCharSequence> getCheckpointLines(int contentWidth) {
        List<ClientSessionState.CheckpointInfo> checkpoints = ClientSessionState.getCheckpoints();
        if (checkpoints.isEmpty()) {
            return List.of();
        }
        ClientSessionState.CheckpointInfo selected = ClientSessionState.getSelectedCheckpoint();
        List<FormattedCharSequence> lines = new ArrayList<>();
        int start = Math.max(0, checkpoints.size() - 6);
        for (int i = start; i < checkpoints.size(); i++) {
            ClientSessionState.CheckpointInfo cp = checkpoints.get(i);
            boolean isSelected = selected != null && cp.id().equals(selected.id());
            String marker = isSelected ? ">" : "-";
            String text = marker + " " + shortId(cp.id()) + " " + (cp.label() == null ? "" : cp.label()) +
                    (cp.revision() == null || cp.revision().isBlank() ? "" : " [" + cp.revision() + "]");
            lines.addAll(this.font.split(Component.literal(text), contentWidth));
        }
        return lines;
    }

    private String shortId(String id) {
        if (id == null) {
            return "";
        }
        String v = id.trim();
        return v.length() <= 8 ? v : v.substring(0, 8);
    }

    private String modeLabel() {
        return "session_only".equals(ClientSessionState.getRollbackMode()) ? "Chat" : "All";
    }

    private void createCheckpoint() {
        JsonObject payload = new JsonObject();
        payload.addProperty("label", "manual-" + ClientSessionState.getTurnCount());
        sendSessionAction("create_checkpoint", payload.toString());
    }

    private void enterWorkspaceRenameMode() {
        String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
        if (selectedWorkspaceId == null || selectedWorkspaceId.isBlank()) {
            return;
        }
        workspaceRenameMode = true;
        String selectedName = ClientSessionState.getSelectedWorkspaceName();
        workspaceRenameDraft = selectedName == null ? "" : selectedName;
        createWidgets();
        if (workspaceRenameInput != null) {
            workspaceRenameInput.setFocused(true);
        }
        if (input != null) {
            input.setFocused(false);
        }
        setContextStatus("Rename selected workspace file", 0xAAD5FF);
    }

    private void exitWorkspaceRenameMode() {
        workspaceRenameMode = false;
        workspaceRenameDraft = "";
        createWidgets();
        if (input != null) {
            input.setFocused(true);
        }
    }

    private void confirmWorkspaceRename() {
        String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
        if (selectedWorkspaceId == null || selectedWorkspaceId.isBlank()) {
            exitWorkspaceRenameMode();
            return;
        }
        String value = workspaceRenameInput != null ? workspaceRenameInput.getValue() : workspaceRenameDraft;
        if (value == null || value.trim().isEmpty()) {
            setContextStatus("Workspace file name cannot be empty", 0xFF5555);
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("workspace_id", selectedWorkspaceId);
        payload.addProperty("name", value.trim());
        payload.addProperty("path", value.trim());
        exitWorkspaceRenameMode();
        sendSessionAction("workspace_file_rename", payload.toString());
        setContextStatus("Renaming workspace file...", 0xAAAAAA);
    }

    private void deleteSelectedWorkspaceDoc() {
        String selectedWorkspaceId = ClientSessionState.getSelectedWorkspaceId();
        if (selectedWorkspaceId == null || selectedWorkspaceId.isBlank()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("workspace_id", selectedWorkspaceId);
        sendSessionAction("workspace_file_delete", payload.toString());
        workspaceRenameMode = false;
        workspaceRenameDraft = "";
        contextLoadedDocId = "";
        clearContextDiffView();
        setContextStatus("Deleting workspace file...", 0xAAAAAA);
        createWidgets();
    }

    private void rollbackSelectedCheckpoint() {
        ClientSessionState.CheckpointInfo cp = ClientSessionState.getSelectedCheckpoint();
        if (cp == null || cp.id() == null || cp.id().isBlank()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("id", cp.id());
        payload.addProperty("mode", ClientSessionState.getRollbackMode());
        sendSessionAction("rollback_checkpoint", payload.toString());
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

    private record ContextSelectionRange(int startLine, int startColumn, int endLine, int endColumn) {
    }

    private enum DiffOpType {
        SAME,
        ADD,
        REMOVE
    }

    private enum DiffLineType {
        SAME,
        ADDED,
        REMOVED,
        SKIP
    }

    private record DiffOp(DiffOpType type, String text) {
    }

    private record DiffViewLine(DiffLineType type, int oldLineNo, int newLineNo, String text) {
    }

    private record DiffBuildResult(
            List<DiffViewLine> lines,
            List<Integer> changeRows,
            int committedLineCount,
            int stagedLineCount,
            boolean truncated
    ) {
    }

    private record ContextSnippet(String label, String content) {
    }

    private enum ContextTab {
        STATE,
        SCRIPT,
        DIFF
    }
}
