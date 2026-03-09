package com.p2s;

import com.google.gson.JsonObject;
import com.p2s.screen.P2SConfigScreen;
import com.p2s.screen.P2SCheckpointListScreen;
import com.p2s.screen.P2SProjectListScreen;
import com.p2s.screen.P2SSessionListScreen;
import com.p2s.screen.chat.P2SChatContextWidgets;
import com.p2s.screen.chat.P2SChatSessionWidgets;
import com.p2s.screen.chat.P2SWorkspaceExplorerComponent;
import com.p2s.screen.widget.P2SFlatButton;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class P2SChatScreen extends Screen {
    private static DockLayoutState retainedDockLayout = new DockLayoutState(false, false, false, -1, -1, -1, -1);
    private static final int PADDING = 8;
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 22;
    private static final int LINE_SPACING = 2;
    private static final int PANEL_MIN_WIDTH = 320;
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
    private static final int CONTEXT_DIFF_MAX_SOURCE_LINES = 1400;
    private static final int CONTEXT_DIFF_EQUAL_CONTEXT_LINES = 3;
    private static final int CONTEXT_SELECTION_CONFIRM_WIDTH = 196;
    private static final int CONTEXT_SELECTION_CONFIRM_HEIGHT = 54;
    private static final int CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH = 56;
    private static final int CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT = 18;
    private static final int CONTEXT_COLLAPSED_FOOTER_HEIGHT = 24;
    private static final int DOCK_SPLITTER_WIDTH = 4;
    private static final int DOCK_COLLAPSED_WIDTH = 22;
    private static final int DOCK_TOGGLE_BUTTON_SIZE = 18;
    private static final int EXPLORER_MIN_WIDTH = 130;
    private static final int EDITOR_MIN_WIDTH = 180;
    private static final int EXPLORER_HEADER_HEIGHT = 28;
    private static final int CHAT_HEADER_META_HEIGHT = 14;
    private static final int TOOL_LOG_PADDING_X = 6;
    private static final int TOOL_LOG_PADDING_Y = 4;
    private static final int TOOL_LOG_DETAIL_GAP = 4;

    private EditBox input;
    private Button sendButton;
    private Button compactButton;
    private Button configButton;
    private Button applyButton;
    private Button discardButton;
    private Button undoButton;
    private Button redoButton;
    private Button checkpointCreateButton;
    private Button checkpointListButton;
    private Button checkpointPrevButton;
    private Button checkpointNextButton;
    private Button checkpointRollbackButton;
    private Button checkpointModeButton;
    private EditBox checkpointNameInput;
    private Button checkpointRenameButton;
    private Button choiceToggleButton;
    private EditBox choiceCustomInput;
    private Button choiceCustomSubmitButton;
    private Button choicePopupCloseButton;
    private final List<Button> choiceButtons = new ArrayList<>();
    private boolean choicePopupVisible = false;
    private String choiceCustomDraft = "";
    private String activeChoiceRequestId = "";
    private int choicePopupX = 0;
    private int choicePopupY = 0;
    private int choicePopupWidth = 0;
    private int choicePopupHeight = 0;
    private double scrollOffset;
    private final Set<String> expandedToolLogIds = new LinkedHashSet<>();
    private final List<ToolLogToggleArea> toolLogToggleAreas = new ArrayList<>();

    private boolean infoOverlayVisible = false;
    private double infoOverlayScroll = 0;
    private Button infoButton;

    private boolean discardReasonMode = false;
    private EditBox discardReasonInput;
    private Button discardOkButton;
    private Button discardCancelButton;
    private boolean workspaceCreateMode = false;
    private boolean workspaceCreateFolderMode = false;
    private String workspaceCreateDraft = "";
    private EditBox workspaceCreateInput;
    private boolean workspaceRenameMode = false;
    private String workspaceRenameDraft = "";
    private EditBox workspaceRenameInput;
    private Button workspaceFolderCreateButton;
    private Button workspaceRenameButton;
    private Button workspaceDeleteButton;
    private Button workspaceRenameOkButton;
    private Button workspaceRenameCancelButton;

    // Left context editor
    private final List<String> contextEditorLines = new ArrayList<>();
    private final List<ContextSnippet> queuedContexts = new ArrayList<>();
    private Button contextLoadButton;
    private Button contextSaveButton;
    private Button contextFormatButton;
    private Button contextClearButton;
    private Button contextClearQueueButton;
    private Button contextEditorToggleButton;
    private Button contextApplyButton;
    private Button contextDiscardButton;
    private Button contextDiffPrevButton;
    private Button contextDiffNextButton;
    private Button workspaceDocCreateButton;
    private final List<Button> workspaceDocButtons = new ArrayList<>();
    private final List<P2SWorkspaceExplorerComponent.ExplorerRow> explorerRows = new ArrayList<>();
    private final List<ExplorerHitArea> explorerHitAreas = new ArrayList<>();
    private int explorerListX = 0;
    private int explorerListY = 0;
    private int explorerListWidth = 0;
    private int explorerListHeight = 0;
    private int explorerRowHeight = 20;
    private int explorerRowGap = 2;
    private double explorerScrollOffset = 0;
    private ContextTab activeContextTab = ContextTab.SCRIPT;
    private final List<String> workspaceTomlLines = new ArrayList<>();
    private int scriptCursorLine = 0;
    private int scriptCursorColumn = 0;
    private int scriptScroll = 0;
    private boolean scriptLoading = false;
    private boolean contextDiffLoading = false;
    private String contextLoadedDocId = "";
    private String contextDiffLoadedPath = "";
    private String contextDiffLoadedSignature = "";
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
    private int contextQueueTopY = 0;
    private boolean contextSelectionConfirmVisible = false;
    private int contextSelectionConfirmStartLine = 1;
    private int contextSelectionConfirmEndLine = 1;
    private int contextSelectionConfirmX = 0;
    private int contextSelectionConfirmY = 0;
    private int sessionPanelWidth = -1;
    private int explorerPanelWidth = -1;
    private int lastSessionPanelWidth = -1;
    private int lastExplorerPanelWidth = -1;
    private int currentSessionPanelX = 0;
    private int currentExplorerSplitterX = 0;
    private boolean sessionPanelCollapsed = false;
    private boolean explorerPanelCollapsed = false;
    private boolean contextEditorCollapsed = false;
    private boolean draggingSessionSplitter = false;
    private boolean draggingExplorerSplitter = false;
    private boolean gameplayInputActive = false;
    private boolean contextDiffMode = false;
    private final List<DiffViewLine> contextDiffLines = new ArrayList<>();
    private final List<Integer> contextDiffChangeRows = new ArrayList<>();
    private int contextDiffNavIndex = -1;
    private Component contextStatus = Component.empty();
    private int contextStatusColor = 0xAAAAAA;
    private String inputDraft = "";
    private String discardReasonDraft = "";
    private String checkpointNameDraft = "";
    private String checkpointNameSourceId = "";
    private String pendingWorkspaceJumpPath = "";
    private final Set<String> collapsedWorkspaceFolders = new LinkedHashSet<>();
    private String workspaceExpandedSelectionPath = "";
    private String selectedExplorerFolderPath = "";

    private PanelFocus collapsedPanelFocus = PanelFocus.NONE;

    public P2SChatScreen() {
        super(P2SI18n.tr("screen.p2s.chat.title"));
        restoreRetainedDockLayout();
    }

    private void restoreRetainedDockLayout() {
        DockLayoutState state = retainedDockLayout;
        sessionPanelCollapsed = state.sessionPanelCollapsed();
        explorerPanelCollapsed = state.explorerPanelCollapsed();
        contextEditorCollapsed = state.contextEditorCollapsed();
        sessionPanelWidth = state.sessionPanelWidth();
        explorerPanelWidth = state.explorerPanelWidth();
        lastSessionPanelWidth = state.lastSessionPanelWidth();
        lastExplorerPanelWidth = state.lastExplorerPanelWidth();
        collapsedPanelFocus = contextEditorCollapsed ? PanelFocus.LEFT : PanelFocus.NONE;
    }

    private void rememberRetainedDockLayout() {
        retainedDockLayout = new DockLayoutState(
                sessionPanelCollapsed,
                explorerPanelCollapsed,
                contextEditorCollapsed,
                sessionPanelWidth,
                explorerPanelWidth,
                lastSessionPanelWidth,
                lastExplorerPanelWidth
        );
    }

    @Override
    protected void init() {
        super.init();
        createWidgets();
    }

    @Override
    public void removed() {
        rememberRetainedDockLayout();
        super.removed();
    }

    @Override
    public void resize(Minecraft client, int width, int height) {
        super.resize(client, width, height);
        createWidgets();
        clampExplorerScroll();
        clampScroll();
    }

    private void ensureDockLayoutState() {
        int maxSessionWidth = Math.max(PANEL_MIN_WIDTH, this.width - EDITOR_MIN_WIDTH - DOCK_COLLAPSED_WIDTH - PADDING * 3);
        if (sessionPanelWidth <= 0) {
            sessionPanelWidth = Math.max(PANEL_MIN_WIDTH, this.width * 2 / 5);
        }
        sessionPanelWidth = Math.max(PANEL_MIN_WIDTH, Math.min(sessionPanelWidth, maxSessionWidth));
        if (lastSessionPanelWidth <= 0) {
            lastSessionPanelWidth = sessionPanelWidth;
        }

        int sessionVisibleWidth = sessionPanelCollapsed ? DOCK_COLLAPSED_WIDTH : sessionPanelWidth;
        int leftWidth = Math.max(100, this.width - sessionVisibleWidth - PADDING * 2);
        int maxExplorerWidth = Math.max(EXPLORER_MIN_WIDTH, leftWidth - EDITOR_MIN_WIDTH - DOCK_SPLITTER_WIDTH - DOCK_COLLAPSED_WIDTH);
        if (explorerPanelWidth <= 0) {
            explorerPanelWidth = Math.min(220, Math.max(EXPLORER_MIN_WIDTH, leftWidth / 3));
        }
        explorerPanelWidth = Math.max(EXPLORER_MIN_WIDTH, Math.min(explorerPanelWidth, maxExplorerWidth));
        if (lastExplorerPanelWidth <= 0) {
            lastExplorerPanelWidth = explorerPanelWidth;
        }
    }

    private int getSessionVisibleWidth() {
        ensureDockLayoutState();
        return sessionPanelCollapsed ? DOCK_COLLAPSED_WIDTH : sessionPanelWidth;
    }

    private int getExplorerVisibleWidth(int leftWidth) {
        ensureDockLayoutState();
        if (explorerPanelCollapsed) {
            return DOCK_COLLAPSED_WIDTH;
        }
        int maxExplorerWidth = Math.max(EXPLORER_MIN_WIDTH, leftWidth - EDITOR_MIN_WIDTH - DOCK_SPLITTER_WIDTH);
        explorerPanelWidth = Math.max(EXPLORER_MIN_WIDTH, Math.min(explorerPanelWidth, maxExplorerWidth));
        return explorerPanelWidth;
    }

    private void toggleSessionPanel() {
        if (sessionPanelCollapsed) {
            sessionPanelCollapsed = false;
            sessionPanelWidth = lastSessionPanelWidth > 0 ? lastSessionPanelWidth : Math.max(PANEL_MIN_WIDTH, this.width * 2 / 5);
        } else {
            lastSessionPanelWidth = Math.max(PANEL_MIN_WIDTH, sessionPanelWidth);
            sessionPanelCollapsed = true;
            infoOverlayVisible = false;
            discardReasonMode = false;
        }
        createWidgets();
    }

    private void toggleExplorerPanel() {
        if (explorerPanelCollapsed) {
            explorerPanelCollapsed = false;
            explorerPanelWidth = lastExplorerPanelWidth > 0 ? lastExplorerPanelWidth : 180;
        } else {
            lastExplorerPanelWidth = Math.max(EXPLORER_MIN_WIDTH, explorerPanelWidth);
            explorerPanelCollapsed = true;
            workspaceRenameMode = false;
            workspaceRenameDraft = "";
        }
        createWidgets();
    }

    private void toggleContextEditor() {
        contextEditorCollapsed = !contextEditorCollapsed;
        if (contextEditorCollapsed) {
            contextEditorFocused = false;
            contextMouseSelecting = false;
            hideContextSelectionConfirm();
            collapsedPanelFocus = PanelFocus.LEFT;
        } else {
            collapsedPanelFocus = PanelFocus.LEFT;
            setContextEditorFocused(true);
        }
        createWidgets();
    }

    private PanelFocus effectiveCollapsedPanelFocus() {
        if (!contextEditorCollapsed) {
            return PanelFocus.NONE;
        }
        if (workspaceCreateMode || workspaceRenameMode) {
            return PanelFocus.LEFT;
        }
        if (discardReasonMode || choicePopupVisible || infoOverlayVisible) {
            return PanelFocus.RIGHT;
        }
        return collapsedPanelFocus;
    }

    private boolean hasPinnedCollapsedPanelFocus() {
        return workspaceCreateMode || workspaceRenameMode || discardReasonMode || choicePopupVisible || infoOverlayVisible;
    }

    private boolean shouldUseGameplayInput() {
        return false;
    }

    private boolean isGameplayInputActive() {
        return gameplayInputActive && shouldUseGameplayInput();
    }

    private void syncGameplayInputMode() {
        boolean next = shouldUseGameplayInput();
        if (this.minecraft == null || gameplayInputActive == next) {
            if (next) {
                KeyMapping.setAll();
            }
            gameplayInputActive = next;
            return;
        }
        if (next) {
            this.minecraft.mouseHandler.grabMouse();
            KeyMapping.setAll();
        } else {
            this.minecraft.mouseHandler.releaseMouse();
            KeyMapping.releaseAll();
        }
        gameplayInputActive = next;
    }

    private void clearAllPanelTextFocus() {
        if (input != null) {
            input.setFocused(false);
        }
        if (workspaceCreateInput != null) {
            workspaceCreateInput.setFocused(false);
        }
        if (workspaceRenameInput != null) {
            workspaceRenameInput.setFocused(false);
        }
        if (checkpointNameInput != null) {
            checkpointNameInput.setFocused(false);
        }
        if (choiceCustomInput != null) {
            choiceCustomInput.setFocused(false);
        }
        if (discardReasonInput != null) {
            discardReasonInput.setFocused(false);
        }
    }

    private void applyCollapsedPanelFocusState() {
        if (!contextEditorCollapsed) {
            syncGameplayInputMode();
            return;
        }
        clearAllPanelTextFocus();
        switch (effectiveCollapsedPanelFocus()) {
            case LEFT -> {
                if (workspaceCreateMode && workspaceCreateInput != null) {
                    workspaceCreateInput.setFocused(true);
                } else if (workspaceRenameMode && workspaceRenameInput != null) {
                    workspaceRenameInput.setFocused(true);
                }
            }
            case RIGHT -> {
                if (discardReasonMode && discardReasonInput != null) {
                    discardReasonInput.setFocused(true);
                } else if (choicePopupVisible && choiceCustomInput != null) {
                    choiceCustomInput.setFocused(true);
                } else if (!infoOverlayVisible && input != null) {
                    input.setFocused(true);
                }
            }
            case NONE -> {
            }
        }
        syncGameplayInputMode();
    }

    private void setCollapsedPanelFocus(PanelFocus focus) {
        collapsedPanelFocus = focus == null ? PanelFocus.NONE : focus;
        if (collapsedPanelFocus == PanelFocus.NONE) {
            contextEditorFocused = false;
            contextMouseSelecting = false;
        }
        applyCollapsedPanelFocusState();
    }

    private void cycleCollapsedPanelFocus(int direction) {
        PanelFocus[] order = {PanelFocus.NONE, PanelFocus.LEFT, PanelFocus.RIGHT};
        int currentIndex = 0;
        for (int index = 0; index < order.length; index++) {
            if (order[index] == collapsedPanelFocus) {
                currentIndex = index;
                break;
            }
        }
        int nextIndex = Math.floorMod(currentIndex + direction, order.length);
        setCollapsedPanelFocus(order[nextIndex]);
    }

    private int getContextDockRight(int panelX) {
        return contextEditorCollapsed ? currentExplorerSplitterX : panelX;
    }

    private boolean isInsideLeftDock(double mouseX, double mouseY, int panelX) {
        int right = getContextDockRight(panelX);
        return mouseX >= 0 && mouseX < right && mouseY >= 0 && mouseY < this.height;
    }

    private boolean isInsideSessionPanel(double mouseX, double mouseY, int panelX, int panelWidth) {
        return mouseX >= panelX && mouseX < panelX + panelWidth && mouseY >= 0 && mouseY < this.height;
    }

    private boolean isInsideSessionSplitter(double mouseX, double mouseY) {
        if (sessionPanelCollapsed) {
            return false;
        }
        return mouseX >= currentSessionPanelX - DOCK_SPLITTER_WIDTH
                && mouseX <= currentSessionPanelX + DOCK_SPLITTER_WIDTH
                && mouseY >= 0 && mouseY < this.height;
    }

    private boolean isInsideExplorerSplitter(double mouseX, double mouseY) {
        if (explorerPanelCollapsed) {
            return false;
        }
        return mouseX >= currentExplorerSplitterX - DOCK_SPLITTER_WIDTH
                && mouseX <= currentExplorerSplitterX + DOCK_SPLITTER_WIDTH
                && mouseY >= 0 && mouseY < this.height;
    }

    private void updateSessionWidthFromMouse(double mouseX) {
        int nextWidth = this.width - (int) mouseX;
        int maxSessionWidth = Math.max(PANEL_MIN_WIDTH, this.width - EDITOR_MIN_WIDTH - DOCK_COLLAPSED_WIDTH - PADDING * 3);
        sessionPanelWidth = Math.max(PANEL_MIN_WIDTH, Math.min(nextWidth, maxSessionWidth));
        lastSessionPanelWidth = sessionPanelWidth;
    }

    private void updateExplorerWidthFromMouse(double mouseX) {
        int leftX = PADDING;
        int leftWidth = Math.max(100, currentSessionPanelX - PADDING * 2);
        int nextWidth = (int) mouseX - leftX;
        int maxExplorerWidth = Math.max(EXPLORER_MIN_WIDTH, leftWidth - EDITOR_MIN_WIDTH - DOCK_SPLITTER_WIDTH);
        explorerPanelWidth = Math.max(EXPLORER_MIN_WIDTH, Math.min(nextWidth, maxExplorerWidth));
        lastExplorerPanelWidth = explorerPanelWidth;
    }

    private void createWidgets() {
        ensureDockLayoutState();
        if (input != null) {
            inputDraft = input.getValue();
        }
        if (discardReasonInput != null) {
            discardReasonDraft = discardReasonInput.getValue();
        }
        if (choiceCustomInput != null) {
            choiceCustomDraft = choiceCustomInput.getValue();
        }
        if (checkpointNameInput != null) {
            checkpointNameDraft = checkpointNameInput.getValue();
        }
        captureContextControlState();
        hideContextSelectionConfirm();
        clearWidgets();

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        currentSessionPanelX = panelX;
        initContextWidgets(panelX);

        int explorerToggleX = explorerPanelCollapsed
                ? PADDING + 2
                : Math.max(PADDING + 2, currentExplorerSplitterX - DOCK_TOGGLE_BUTTON_SIZE - DOCK_SPLITTER_WIDTH - 2);
        Button explorerToggle = addRenderableWidget(new P2SFlatButton(
                explorerToggleX,
                PADDING,
                DOCK_TOGGLE_BUTTON_SIZE,
                DOCK_TOGGLE_BUTTON_SIZE,
                Component.literal(explorerPanelCollapsed ? ">" : "<"),
                btn -> toggleExplorerPanel(),
                P2SFlatButton.Variant.MUTED
        ));

        int contextEditorToggleX = contextEditorCollapsed
                ? Math.max(PADDING + 2, currentExplorerSplitterX + DOCK_SPLITTER_WIDTH + 2)
                : Math.max(PADDING + 2, currentExplorerSplitterX + DOCK_SPLITTER_WIDTH + 2);
        contextEditorToggleButton = addRenderableWidget(new P2SFlatButton(
                contextEditorToggleX,
                PADDING,
                DOCK_TOGGLE_BUTTON_SIZE,
                DOCK_TOGGLE_BUTTON_SIZE,
                Component.literal(contextEditorCollapsed ? ">" : "<"),
                btn -> toggleContextEditor(),
                P2SFlatButton.Variant.MUTED
        ));

        int sessionToggleX = sessionPanelCollapsed ? panelX + 2 : Math.max(panelX - DOCK_TOGGLE_BUTTON_SIZE - DOCK_SPLITTER_WIDTH - 2, PADDING + 2);
        Button sessionToggle = addRenderableWidget(new P2SFlatButton(
                sessionToggleX,
                PADDING,
                DOCK_TOGGLE_BUTTON_SIZE,
                DOCK_TOGGLE_BUTTON_SIZE,
                Component.literal(sessionPanelCollapsed ? "<" : ">"),
                btn -> toggleSessionPanel(),
                P2SFlatButton.Variant.MUTED
        ));

        if (sessionPanelCollapsed) {
            input = null;
            sendButton = null;
            compactButton = null;
            configButton = null;
            applyButton = null;
            discardButton = null;
            undoButton = null;
            redoButton = null;
            checkpointCreateButton = null;
            checkpointListButton = null;
            checkpointPrevButton = null;
            checkpointNextButton = null;
            checkpointRollbackButton = null;
            checkpointModeButton = null;
            checkpointNameInput = null;
            checkpointRenameButton = null;
            infoButton = null;
            choiceToggleButton = null;
            choiceCustomInput = null;
            choiceCustomSubmitButton = null;
            choicePopupCloseButton = null;
            choiceButtons.clear();
            choicePopupX = 0;
            choicePopupY = 0;
            choicePopupWidth = 0;
            choicePopupHeight = 0;
            discardReasonInput = null;
            discardOkButton = null;
            discardCancelButton = null;
            applyCollapsedPanelFocusState();
            return;
        }

        syncCheckpointNameDraftFromSelection();
        P2SChatSessionWidgets.BuildResult sessionWidgets = P2SChatSessionWidgets.build(
                new P2SChatSessionWidgets.Host() {
                    @Override
                    public net.minecraft.client.gui.Font font() {
                        return P2SChatScreen.this.font;
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
                new P2SChatSessionWidgets.Config(
                        panelX,
                        panelWidth,
                        PADDING,
                        INPUT_HEIGHT,
                        BUTTON_WIDTH,
                        TOP_BUTTON_HEIGHT,
                        SMALL_BUTTON_WIDTH,
                        CHOICE_BUTTON_COUNT,
                        getInputY(),
                        contextEditorFocused,
                        choicePopupVisible,
                        inputDraft,
                        discardReasonDraft,
                        checkpointNameDraft,
                        choiceCustomDraft,
                        modeLabel(),
                        this::sendMessage,
                        () -> this.minecraft.setScreen(new P2SProjectListScreen(this)),
                        () -> this.minecraft.setScreen(new P2SSessionListScreen(this)),
                        ClientAgentManager::newSession,
                        ClientAgentManager::submitManualCompact,
                        () -> {
                            infoOverlayVisible = !infoOverlayVisible;
                            infoOverlayScroll = 0;
                            if (infoOverlayVisible && choicePopupVisible) {
                                closeChoicePopup();
                            }
                        },
                        () -> this.minecraft.setScreen(new P2SConfigScreen(this)),
                        () -> this.minecraft.setScreen(new P2SCheckpointListScreen(this)),
                        ClientAgentManager::submitPatchApply,
                        this::enterDiscardReasonMode,
                        () -> sendSessionAction("undo", ""),
                        () -> sendSessionAction("redo", ""),
                        this::createCheckpoint,
                        ClientSessionState::selectPreviousCheckpoint,
                        ClientSessionState::selectNextCheckpoint,
                        this::rollbackSelectedCheckpoint,
                        this::renameSelectedCheckpoint,
                        ClientSessionState::toggleRollbackMode,
                        this::toggleChoicePopup,
                        this::submitChoice,
                        this::submitCustomChoice,
                        this::closeChoicePopup,
                        this::confirmDiscard,
                        this::exitDiscardReasonMode
                )
        );

        input = sessionWidgets.input();
        sendButton = sessionWidgets.sendButton();
        compactButton = sessionWidgets.compactButton();
        configButton = sessionWidgets.configButton();
        applyButton = sessionWidgets.applyButton();
        discardButton = sessionWidgets.discardButton();
        undoButton = sessionWidgets.undoButton();
        redoButton = sessionWidgets.redoButton();
        checkpointCreateButton = sessionWidgets.checkpointCreateButton();
        checkpointListButton = sessionWidgets.checkpointListButton();
        checkpointPrevButton = sessionWidgets.checkpointPrevButton();
        checkpointNextButton = sessionWidgets.checkpointNextButton();
        checkpointRollbackButton = sessionWidgets.checkpointRollbackButton();
        checkpointModeButton = sessionWidgets.checkpointModeButton();
        checkpointNameInput = sessionWidgets.checkpointNameInput();
        checkpointRenameButton = sessionWidgets.checkpointRenameButton();
        infoButton = sessionWidgets.infoButton();
        choiceToggleButton = sessionWidgets.choiceToggleButton();
        choiceCustomInput = sessionWidgets.choiceCustomInput();
        choiceCustomSubmitButton = sessionWidgets.choiceCustomSubmitButton();
        choicePopupCloseButton = sessionWidgets.choicePopupCloseButton();
        choicePopupX = sessionWidgets.choicePopupX();
        choicePopupY = sessionWidgets.choicePopupY();
        choicePopupWidth = sessionWidgets.choicePopupWidth();
        choicePopupHeight = sessionWidgets.choicePopupHeight();
        discardReasonInput = sessionWidgets.discardReasonInput();
        discardOkButton = sessionWidgets.discardOkButton();
        discardCancelButton = sessionWidgets.discardCancelButton();
        choiceButtons.clear();
        choiceButtons.addAll(sessionWidgets.choiceButtons());
        if (choicePopupVisible && choiceCustomInput != null) {
            choiceCustomInput.setFocused(true);
            if (input != null) {
                input.setFocused(false);
            }
        }
        applyCollapsedPanelFocusState();
    }

    private void initContextWidgets(int panelX) {
        if (activeContextTab == ContextTab.SCRIPT && contextEditorLines.isEmpty()) {
            String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
            String current = ClientSessionState.getWorkspaceFileToml(selectedWorkspacePath);
            if ((current == null || current.isBlank()) && selectedWorkspacePath != null && !selectedWorkspacePath.isBlank()) {
                current = ClientSessionState.getCurrentWorkspaceToml();
            }
            if (current != null && !current.isBlank()) {
                setContextEditorText(current);
                contextLoadedDocId = selectedWorkspacePath == null ? "" : selectedWorkspacePath;
            } else {
                setContextEditorText(defaultWorkspaceToml(selectedWorkspacePath));
            }
        }
        contextScroll = Math.max(0, contextScroll);

        int leftWidth = Math.max(100, panelX - PADDING * 2);
        int explorerWidth = getExplorerVisibleWidth(leftWidth);
        if (workspaceRenameMode && (workspaceRenameDraft == null || workspaceRenameDraft.isBlank())) {
            String selectedName = ClientSessionState.getSelectedWorkspaceLabel();
            workspaceRenameDraft = selectedName == null ? "" : selectedName;
        }
        if (workspaceCreateMode && (workspaceCreateDraft == null || workspaceCreateDraft.isBlank())) {
            workspaceCreateDraft = workspaceCreateFolderMode ? defaultNewWorkspaceFolderPath() : defaultNewWorkspaceFilePath();
        }
        pruneCollapsedWorkspaceFolders();
        syncWorkspaceSelectionExpansion();

        P2SChatContextWidgets.BuildResult contextWidgets = P2SChatContextWidgets.build(
                new P2SChatContextWidgets.Host() {
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
                new P2SChatContextWidgets.Config(
                        panelX,
                        explorerWidth,
                        PADDING,
                        INPUT_HEIGHT,
                        CONTEXT_FOOTER_HEIGHT,
                        CONTEXT_ROW_HEIGHT,
                        CONTEXT_ROW_GAP,
                        CONTEXT_EDITOR_PADDING,
                        EDITOR_MIN_WIDTH,
                        DOCK_TOGGLE_BUTTON_SIZE + DOCK_SPLITTER_WIDTH + 4,
                        explorerPanelCollapsed,
                        contextEditorCollapsed,
                        workspaceCreateMode,
                        workspaceCreateFolderMode,
                        workspaceRenameMode,
                        contextDiffMode,
                        selectedWorkspaceHasPendingPatch(),
                        workspaceCreateDraft,
                        workspaceRenameDraft,
                        ClientSessionState.getSelectedWorkspacePath(),
                        selectedExplorerFolderPath,
                        ClientSessionState.getWorkspaceFiles(),
                        workspaceFolders(),
                        Set.copyOf(collapsedWorkspaceFolders),
                        this::enterWorkspaceCreateFileMode,
                        this::enterWorkspaceCreateFolderMode,
                        this::enterWorkspaceRenameMode,
                        this::deleteSelectedWorkspaceDoc,
                        this::switchWorkspaceDoc,
                        this::toggleWorkspaceFolder,
                        this::confirmWorkspaceCreate,
                        this::exitWorkspaceCreateMode,
                        this::confirmWorkspaceRename,
                        this::exitWorkspaceRenameMode,
                        this::loadWorkspaceDiff,
                        this::fetchWorkspaceScript,
                        this::saveWorkspaceDoc,
                        this::clearContextQueue,
                        this::formatContextToml,
                        this::clearContextEditorText,
                        this::applySelectedPendingPatch,
                        this::enterDiscardReasonMode,
                        () -> navigateContextDiffChange(-1),
                        () -> navigateContextDiffChange(1)
                )
        );

        currentExplorerSplitterX = contextWidgets.currentExplorerSplitterX();
        contextVisibleRows = contextWidgets.contextVisibleRows();
        contextEditorX = contextWidgets.contextEditorX();
        contextEditorY = contextWidgets.contextEditorY();
        contextEditorWidth = contextWidgets.contextEditorWidth();
        contextEditorHeight = contextWidgets.contextEditorHeight();
        contextQueueTopY = contextWidgets.contextQueueTopY();
        workspaceDocCreateButton = contextWidgets.workspaceDocCreateButton();
        workspaceFolderCreateButton = contextWidgets.workspaceFolderCreateButton();
        workspaceRenameButton = contextWidgets.workspaceRenameButton();
        workspaceDeleteButton = contextWidgets.workspaceDeleteButton();
        workspaceCreateInput = contextWidgets.workspaceCreateInput();
        workspaceRenameInput = contextWidgets.workspaceRenameInput();
        workspaceRenameOkButton = contextWidgets.workspaceRenameOkButton();
        workspaceRenameCancelButton = contextWidgets.workspaceRenameCancelButton();
        workspaceDocButtons.clear();
        workspaceDocButtons.addAll(contextWidgets.workspaceDocButtons());
        explorerListX = contextWidgets.explorerListX();
        explorerListY = contextWidgets.explorerListY();
        explorerListWidth = contextWidgets.explorerListWidth();
        explorerListHeight = contextWidgets.explorerListHeight();
        explorerRowHeight = contextWidgets.explorerRowHeight();
        explorerRowGap = contextWidgets.explorerRowGap();
        explorerRows.clear();
        explorerRows.addAll(contextWidgets.explorerRows());
        clampExplorerScroll();
        contextLoadButton = contextWidgets.contextLoadButton();
        contextSaveButton = contextWidgets.contextSaveButton();
        contextClearQueueButton = contextWidgets.contextClearQueueButton();
        contextFormatButton = contextWidgets.contextFormatButton();
        contextClearButton = contextWidgets.contextClearButton();
        contextApplyButton = contextWidgets.contextApplyButton();
        contextDiscardButton = contextWidgets.contextDiscardButton();
        contextDiffPrevButton = contextWidgets.contextDiffPrevButton();
        contextDiffNextButton = contextWidgets.contextDiffNextButton();
        if (workspaceCreateMode && workspaceCreateInput != null) {
            workspaceCreateInput.setFocused(true);
        } else if (workspaceRenameInput != null) {
            workspaceRenameInput.setFocused(true);
        }

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
        if (workspaceCreateMode && workspaceCreateInput != null) {
            workspaceCreateDraft = workspaceCreateInput.getValue();
        }
        if (workspaceRenameMode && workspaceRenameInput != null) {
            workspaceRenameDraft = workspaceRenameInput.getValue();
        }
    }

    private void createWorkspaceDoc() {
        enterWorkspaceCreateFileMode();
    }

    private void enterWorkspaceCreateFileMode() {
        workspaceCreateMode = true;
        workspaceCreateFolderMode = false;
        workspaceRenameMode = false;
        collapsedPanelFocus = PanelFocus.LEFT;
        workspaceCreateDraft = defaultNewWorkspaceFilePath();
        createWidgets();
        if (workspaceCreateInput != null) {
            workspaceCreateInput.setFocused(true);
        }
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(false);
        }
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.create_workspace_file"), 0xAAD5FF);
    }

    private void enterWorkspaceCreateFolderMode() {
        workspaceCreateMode = true;
        workspaceCreateFolderMode = true;
        workspaceRenameMode = false;
        collapsedPanelFocus = PanelFocus.LEFT;
        workspaceCreateDraft = defaultNewWorkspaceFolderPath();
        createWidgets();
        if (workspaceCreateInput != null) {
            workspaceCreateInput.setFocused(true);
        }
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(false);
        }
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.create_workspace_folder"), 0xAAD5FF);
    }

    private void exitWorkspaceCreateMode() {
        workspaceCreateMode = false;
        workspaceCreateFolderMode = false;
        workspaceCreateDraft = "";
        createWidgets();
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(true);
        }
    }

    private void confirmWorkspaceCreate() {
        String rawValue = workspaceCreateInput != null ? workspaceCreateInput.getValue() : workspaceCreateDraft;
        String normalized = normalizeWorkspaceFolderPath(rawValue);
        if (normalized.isBlank()) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_name_empty"), 0xFF5555);
            return;
        }
        if (workspaceCreateFolderMode) {
            if (workspaceFolderExists(normalized) || workspaceFileExists(normalized)) {
                setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_create_failed", normalized), 0xFF5555);
                return;
            }
            rememberWorkspaceFolder(normalized);
            selectedExplorerFolderPath = normalized;
            collapsedWorkspaceFolders.remove(normalized);
            workspaceExpandedSelectionPath = "";
            exitWorkspaceCreateMode();
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.folder_created", normalized), 0x55FF55);
            return;
        }
        String rawPath = rawValue == null ? "" : rawValue.trim().replace('\\', '/');
        if (rawPath.endsWith("/")) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_create_failed", rawPath), 0xFF5555);
            return;
        }
        if (workspaceFileExists(normalized) || workspaceFolderExists(normalized)) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_create_failed", normalized), 0xFF5555);
            return;
        }
        submitCreateWorkspaceDoc(normalized);
    }

    private void submitCreateWorkspaceDoc(String pathValue) {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", pathValue);
        payload.addProperty("path", pathValue);
        payload.addProperty("type", "manual");
        payload.addProperty("switchToNew", true);
        boolean hasSelection = ClientSelectionManager.getPos1() != null && ClientSelectionManager.getPos2() != null;
        payload.addProperty("from_selection", hasSelection);
        activeContextTab = ContextTab.SCRIPT;
        contextLoadedDocId = "";
        workspaceCreateMode = false;
        workspaceCreateFolderMode = false;
        workspaceCreateDraft = "";
        workspaceRenameMode = false;
        workspaceRenameDraft = "";
        clearContextDiffView();
        setContextStatus(P2SI18n.tr(hasSelection
                ? "screen.p2s.chat.context.status.creating_from_selection"
                : "screen.p2s.chat.context.status.creating_empty"), 0xAAAAAA);
        createWidgets();
        ClientToolBridge.call("create_workspace_file", payload)
                .thenAccept(result -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            if (!isToolOk(result)) {
                                setContextStatus(resolveToolError(result, "screen.p2s.chat.context.status.workspace_create_failed"), 0xFF5555);
                                return;
                            }
                            String createdPath = P2SI18n.getString(result, "path");
                            rememberWorkspaceFoldersForPath(createdPath);
                            selectedExplorerFolderPath = parentFolderOfWorkspacePath(createdPath);
                            workspaceExpandedSelectionPath = "";
                            ClientSessionState.setSelectedWorkspacePath(createdPath);
                            String workspaceToml = defaultWorkspaceToml(createdPath);
                            ClientSessionState.setWorkspaceFileToml(createdPath, workspaceToml);
                            setContextEditorText(workspaceToml);
                            contextLoadedDocId = createdPath == null ? "" : createdPath;
                            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_created", createdPath), 0x55FF55);
                            createWidgets();
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_create_failed", shortError(ex.getMessage())), 0xFF5555));
                    }
                    return null;
                });
    }

    private void saveWorkspaceDoc() {
        if (contextDiffMode) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.diff_read_only"), 0xFFAA55);
            return;
        }
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.no_workspace_selected"), 0xFFAA55);
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("path", selectedWorkspacePath);
        args.addProperty("workspace_toml", contextLinesToText());
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.saving_workspace"), 0xAAAAAA);
        ClientToolBridge.call("save_workspace_file", args)
                .thenAccept(result -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            if (!isToolOk(result)) {
                                setContextStatus(resolveToolError(result, "screen.p2s.chat.context.status.workspace_save_failed"), 0xFF5555);
                                return;
                            }
                            String workspaceToml = contextLinesToText();
                            ClientSessionState.setWorkspaceFileToml(selectedWorkspacePath, workspaceToml);
                            contextLoadedDocId = selectedWorkspacePath;
                            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_saved", selectedWorkspacePath), 0x55FF55);
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_save_failed", shortError(ex.getMessage())), 0xFF5555));
                    }
                    return null;
                });
    }

    private void switchWorkspaceDoc(String pathValue) {
        openWorkspaceDoc(pathValue, false);
    }

    private void openWorkspaceDoc(String pathValue, boolean preservePendingJump) {
        if (pathValue == null || pathValue.isBlank()) {
            return;
        }
        if (ClientSessionState.isActive() && !ClientServerBridge.canSendSessionAction()) {
            ClientServerBridge.notifyMissingServer();
            return;
        }
        if (!preservePendingJump) {
            pendingWorkspaceJumpPath = "";
        }
        if (!ClientSessionState.setSelectedWorkspacePath(pathValue)) {
            return;
        }
        selectedExplorerFolderPath = parentFolderOfWorkspacePath(pathValue);
        if (ClientSessionState.isActive()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("path", pathValue);
            sendSessionAction("session_select_workspace", payload.toString());
        }
        if (workspaceRenameMode) {
            workspaceRenameDraft = "";
        }
        activeContextTab = ContextTab.SCRIPT;
        contextLoadedDocId = "";
        clearContextDiffView();
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.switched_file"), 0x55FF55);
        createWidgets();
    }

    private JsonObject workspaceReadArgs(boolean committed) {
        JsonObject args = new JsonObject();
        args.addProperty("committed", committed);
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath != null && !selectedWorkspacePath.isBlank()) {
            args.addProperty("path", selectedWorkspacePath);
        }
        return args;
    }

    private String currentProjectId() {
        String projectId = ClientSessionState.getProjectId();
        return projectId == null ? "" : projectId.trim();
    }

    private List<String> workspaceFolders() {
        LinkedHashSet<String> folders = new LinkedHashSet<>(P2SClientConfig.getWorkspaceFolders(currentProjectId()));
        folders.addAll(collectWorkspaceFolderPathsFromFiles());
        return new ArrayList<>(folders);
    }

    private Set<String> collectWorkspaceFolderPathsFromFiles() {
        Set<String> folders = new LinkedHashSet<>();
        for (ClientSessionState.WorkspaceFileInfo file : ClientSessionState.getWorkspaceFiles()) {
            if (file == null || file.path() == null || file.path().isBlank()) {
                continue;
            }
            String normalized = normalizeWorkspaceFolderPath(file.path());
            int slash = normalized.indexOf('/');
            while (slash >= 0) {
                folders.add(normalized.substring(0, slash));
                slash = normalized.indexOf('/', slash + 1);
            }
        }
        return folders;
    }

    private void pruneCollapsedWorkspaceFolders() {
        collapsedWorkspaceFolders.retainAll(new LinkedHashSet<>(workspaceFolders()));
    }

    private void syncWorkspaceSelectionExpansion() {
        String targetPath = ClientSessionState.getSelectedWorkspacePath();
        if (targetPath == null || targetPath.isBlank()) {
            targetPath = selectedExplorerFolderPath;
        }
        String normalized = normalizeWorkspaceFolderPath(targetPath);
        if (normalized.equals(workspaceExpandedSelectionPath)) {
            return;
        }
        expandWorkspaceAncestors(normalized);
        workspaceExpandedSelectionPath = normalized;
    }

    private void expandWorkspaceAncestors(String workspacePath) {
        String normalized = normalizeWorkspaceFolderPath(workspacePath);
        int slash = normalized.lastIndexOf('/');
        while (slash > 0) {
            collapsedWorkspaceFolders.remove(normalized.substring(0, slash));
            slash = normalized.lastIndexOf('/', slash - 1);
        }
    }

    private void selectWorkspaceFolder(String folderPath) {
        String normalized = normalizeWorkspaceFolderPath(folderPath);
        if (normalized.isBlank()) {
            return;
        }
        selectedExplorerFolderPath = normalized;
        workspaceExpandedSelectionPath = "";
        createWidgets();
    }

    private void toggleWorkspaceFolder(String folderPath) {
        String normalized = normalizeWorkspaceFolderPath(folderPath);
        if (normalized.isBlank()) {
            return;
        }
        selectedExplorerFolderPath = normalized;
        if (!collapsedWorkspaceFolders.add(normalized)) {
            collapsedWorkspaceFolders.remove(normalized);
        }
        workspaceExpandedSelectionPath = "";
        createWidgets();
    }

    private int getExplorerRowStep() {
        return Math.max(1, explorerRowHeight + explorerRowGap);
    }

    private int getExplorerMaxScroll() {
        int contentHeight = Math.max(0, explorerRows.size() * getExplorerRowStep() - explorerRowGap);
        return Math.max(0, contentHeight - explorerListHeight);
    }

    private void clampExplorerScroll() {
        explorerScrollOffset = clamp(explorerScrollOffset, 0, getExplorerMaxScroll());
    }

    private boolean isInsideExplorerList(double mouseX, double mouseY) {
        return !explorerPanelCollapsed
                && explorerListWidth > 0
                && explorerListHeight > 0
                && mouseX >= explorerListX
                && mouseX <= explorerListX + explorerListWidth
                && mouseY >= explorerListY
                && mouseY <= explorerListY + explorerListHeight;
    }

    private String trimEndToWidth(String text, int maxWidth) {
        if (text == null || text.isBlank() || this.font == null || maxWidth <= 0) {
            return text == null ? "" : text;
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int maxChars = text.length();
        while (maxChars > 1) {
            String candidate = text.substring(0, maxChars) + ellipsis;
            if (this.font.width(candidate) <= maxWidth) {
                return candidate;
            }
            maxChars--;
        }
        return ellipsis;
    }

    private String explorerFolderMeta(P2SWorkspaceExplorerComponent.ExplorerRow row) {
        if (row == null || row.itemCount() <= 0) {
            return "";
        }
        return Integer.toString(row.itemCount());
    }

    private String explorerFileBadge(P2SWorkspaceExplorerComponent.ExplorerRow row) {
        if (row == null || !row.pending()) {
            return "";
        }
        return row.changedBlocks() > 0 ? "M" + row.changedBlocks() : "M";
    }

    private boolean handleExplorerClick(double mouseX, double mouseY) {
        for (int index = explorerHitAreas.size() - 1; index >= 0; index--) {
            ExplorerHitArea area = explorerHitAreas.get(index);
            if (!area.contains(mouseX, mouseY)) {
                continue;
            }
            if (area.toggle()) {
                toggleWorkspaceFolder(area.path());
            } else if (area.folder()) {
                selectWorkspaceFolder(area.path());
            } else {
                openWorkspaceDoc(area.path(), false);
            }
            return true;
        }
        return isInsideExplorerList(mouseX, mouseY);
    }

    private String defaultNewWorkspaceFilePath() {
        String baseFolder = selectedCreateBaseFolder();
        int next = Math.max(1, ClientSessionState.getWorkspaceFiles().size() + 1);
        String prefix = baseFolder == null || baseFolder.isBlank() ? "workspace" : baseFolder;
        String candidate = prefix + "/file-" + next + ".toml";
        while (workspaceFileExists(candidate) || workspaceFolderExists(candidate)) {
            next++;
            candidate = prefix + "/file-" + next + ".toml";
        }
        return candidate;
    }

    private String defaultNewWorkspaceFolderPath() {
        String baseFolder = selectedCreateBaseFolder();
        int next = Math.max(1, workspaceFolders().size() + 1);
        String prefix = baseFolder == null || baseFolder.isBlank() ? "workspace" : baseFolder;
        String candidate = prefix + "/folder-" + next;
        while (workspaceFolderExists(candidate) || workspaceFileExists(candidate)) {
            next++;
            candidate = prefix + "/folder-" + next;
        }
        return candidate;
    }

    private String selectedCreateBaseFolder() {
        if (selectedExplorerFolderPath != null && !selectedExplorerFolderPath.isBlank()) {
            return selectedExplorerFolderPath;
        }
        return parentFolderOfWorkspacePath(ClientSessionState.getSelectedWorkspacePath());
    }

    private String parentFolderOfWorkspacePath(String pathValue) {
        String normalized = normalizeWorkspaceFolderPath(pathValue);
        int slash = normalized.lastIndexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : "";
    }

    private boolean workspaceFileExists(String pathValue) {
        String normalized = normalizeWorkspaceFolderPath(pathValue);
        if (normalized.isBlank()) {
            return false;
        }
        for (ClientSessionState.WorkspaceFileInfo file : ClientSessionState.getWorkspaceFiles()) {
            if (file != null && normalized.equals(file.path())) {
                return true;
            }
        }
        return false;
    }

    private boolean workspaceFolderExists(String folderPath) {
        String normalized = normalizeWorkspaceFolderPath(folderPath);
        return !normalized.isBlank() && workspaceFolders().contains(normalized);
    }

    private void rememberWorkspaceFolder(String folderPath) {
        P2SClientConfig.addWorkspaceFolder(currentProjectId(), folderPath, true);
    }

    private void rememberWorkspaceFoldersForPath(String pathValue) {
        P2SClientConfig.ensureWorkspaceFoldersForPath(currentProjectId(), pathValue, true);
    }

    private String normalizeWorkspaceFolderPath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.replaceAll("/{2,}", "/");
    }

    private void syncActiveDocScriptIfNeeded() {
        if (activeContextTab != ContextTab.SCRIPT || contextDiffMode) {
            return;
        }
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            return;
        }
        if (selectedWorkspacePath.equals(contextLoadedDocId)) {
            return;
        }
        String script = ClientSessionState.getWorkspaceFileToml(selectedWorkspacePath);
        if ((script == null || script.isBlank()) && selectedWorkspacePath != null && !selectedWorkspacePath.isBlank()) {
            script = ClientSessionState.getCurrentWorkspaceToml();
        }
        if (script == null || script.isBlank()) {
            setContextEditorText(defaultWorkspaceToml(selectedWorkspacePath));
            contextLoadedDocId = selectedWorkspacePath;
            return;
        }
        setContextEditorText(script);
        contextLoadedDocId = selectedWorkspacePath;
    }

    private ClientSessionState.WorkspaceFileInfo selectedWorkspaceFile() {
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            return null;
        }
        for (ClientSessionState.WorkspaceFileInfo file : ClientSessionState.getWorkspaceFiles()) {
            if (file != null && selectedWorkspacePath.equals(file.path())) {
                return file;
            }
        }
        return null;
    }

    private boolean selectedWorkspaceHasPendingPatch() {
        ClientSessionState.WorkspaceFileInfo file = selectedWorkspaceFile();
        return file != null && file.hasPendingPatch();
    }

    private String selectedWorkspacePendingSignature() {
        ClientSessionState.WorkspaceFileInfo file = selectedWorkspaceFile();
        if (file == null || !file.hasPendingPatch()) {
            return "";
        }
        String summary = file.path().equals(ClientSessionState.getPendingPath()) ? ClientSessionState.getPendingSummary() : "";
        return file.path() + "|" + file.revision() + "|" + file.pendingChangedBlocks() + "|" + summary;
    }

    private boolean canActOnSelectedPendingPatch() {
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        String pendingPath = ClientSessionState.getPendingPath();
        return ClientSessionState.hasPendingPatch()
                && selectedWorkspacePath != null
                && !selectedWorkspacePath.isBlank()
                && selectedWorkspacePath.equals(pendingPath);
    }

    private void syncInlineDiffIfNeeded() {
        if (activeContextTab != ContextTab.SCRIPT) {
            return;
        }
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            if (contextDiffMode) {
                clearContextDiffView();
            }
            return;
        }
        String pendingSignature = selectedWorkspacePendingSignature();
        if (!pendingSignature.isBlank()) {
            if ((!contextDiffMode || !pendingSignature.equals(contextDiffLoadedSignature)) && !contextDiffLoading) {
                loadWorkspaceDiff();
            }
            return;
        }
        if (contextDiffMode) {
            clearContextDiffView();
        }
    }

    private void queueNextPendingWorkspaceJump() {
        if (!canActOnSelectedPendingPatch()) {
            pendingWorkspaceJumpPath = "";
            return;
        }
        String currentPath = ClientSessionState.getPendingPath();
        List<ClientSessionState.WorkspaceFileInfo> files = ClientSessionState.getWorkspaceFiles();
        if (files.isEmpty()) {
            pendingWorkspaceJumpPath = "";
            return;
        }
        int startIndex = -1;
        for (int i = 0; i < files.size(); i++) {
            ClientSessionState.WorkspaceFileInfo file = files.get(i);
            if (file != null && currentPath.equals(file.path())) {
                startIndex = i;
                break;
            }
        }
        String nextPath = "";
        for (int offset = 1; offset <= files.size(); offset++) {
            int index = startIndex >= 0 ? (startIndex + offset) % files.size() : offset - 1;
            ClientSessionState.WorkspaceFileInfo file = files.get(index);
            if (file != null && file.hasPendingPatch() && !currentPath.equals(file.path())) {
                nextPath = file.path();
                break;
            }
        }
        pendingWorkspaceJumpPath = nextPath;
    }

    private void processPendingWorkspaceJumpIfReady() {
        if (pendingWorkspaceJumpPath == null || pendingWorkspaceJumpPath.isBlank()) {
            return;
        }
        ClientSessionState.WorkspaceFileInfo selected = selectedWorkspaceFile();
        if (selected != null && selected.hasPendingPatch()) {
            return;
        }
        String targetPath = pendingWorkspaceJumpPath;
        pendingWorkspaceJumpPath = "";
        if (!targetPath.isBlank()) {
            openWorkspaceDoc(targetPath, true);
        }
    }

    private void refreshWorkspaceExplorerIfNeeded() {
        StringBuilder sb = new StringBuilder();
        sb.append(ClientSessionState.getSelectedWorkspacePath()).append('|');
        List<ClientSessionState.WorkspaceFileInfo> docs = ClientSessionState.getWorkspaceFiles();
        sb.append(docs.size());
        for (ClientSessionState.WorkspaceFileInfo doc : docs) {
            if (doc == null) {
                continue;
            }
            sb.append('|').append(doc.path())
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
        String selectedWorkspaceLabel = ClientSessionState.getSelectedWorkspaceLabel();
        if (selectedWorkspaceLabel == null || selectedWorkspaceLabel.isBlank()) {
            String fallbackWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
            selectedWorkspaceLabel = (fallbackWorkspacePath == null || fallbackWorkspacePath.isBlank()) ? "workspace/main.toml" : fallbackWorkspacePath;
        }
        return switch (activeContextTab) {
            case SCRIPT -> selectedWorkspaceLabel;
            case DIFF -> selectedWorkspaceLabel + ".diff";
        };
    }

    private void saveCurrentTabEditorState() {
        switch (activeContextTab) {
            case SCRIPT -> {
                workspaceTomlLines.clear();
                workspaceTomlLines.addAll(contextEditorLines);
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
            case SCRIPT -> {
                contextDiffMode = false;
                contextEditorLines.clear();
                if (workspaceTomlLines.isEmpty()) {
                    contextEditorLines.add("");
                } else {
                    contextEditorLines.addAll(workspaceTomlLines);
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
        hideContextSelectionConfirm();
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
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.already_fetching"), 0xFFAA55);
            return;
        }
        scriptLoading = true;
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.fetching_script"), 0xAAAAAA);

        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        ClientToolBridge.call("read_workspace_file", workspaceReadArgs(true))
                .thenAccept(result -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            scriptLoading = false;
                            String scriptText = extractWorkspaceTomlText(result);
                            if (scriptText.isBlank()) {
                                scriptText = defaultWorkspaceToml(selectedWorkspacePath);
                                setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.no_script_data"), 0xFFAA55);
                            } else {
                                setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.script_loaded"), 0x55FF55);
                            }
                            if (selectedWorkspacePath != null && !selectedWorkspacePath.isBlank()) {
                                ClientSessionState.setWorkspaceFileToml(selectedWorkspacePath, scriptText);
                            }
                            if (activeContextTab == ContextTab.SCRIPT) {
                                setContextEditorText(scriptText);
                                contextLoadedDocId = selectedWorkspacePath == null ? "" : selectedWorkspacePath;
                            } else {
                                // Update cache only
                                workspaceTomlLines.clear();
                                String[] lines = scriptText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
                                for (String line : lines) {
                                    workspaceTomlLines.add(line == null ? "" : line);
                                }
                                if (workspaceTomlLines.isEmpty()) {
                                    workspaceTomlLines.add("");
                                }
                                scriptCursorLine = 0;
                                scriptCursorColumn = 0;
                                scriptScroll = 0;
                                contextLoadedDocId = selectedWorkspacePath == null ? "" : selectedWorkspacePath;
                            }
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            scriptLoading = false;
                            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.script_fetch_failed", shortError(ex.getMessage())), 0xFF5555);
                        });
                    }
                    return null;
                });
    }


    private void loadWorkspaceDiff() {
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            return;
        }
        if (contextDiffLoading) {
            return;
        }
        String diffSignature = selectedWorkspacePendingSignature();
        contextDiffLoading = true;
        contextDiffLoadedPath = selectedWorkspacePath;
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.loading_diff"), 0xAAAAAA);

        JsonObject committedArgs = workspaceReadArgs(true);
        JsonObject stagedArgs = workspaceReadArgs(false);

        ClientToolBridge.call("read_workspace_file", committedArgs)
                .thenCombine(ClientToolBridge.call("read_workspace_file", stagedArgs), (committed, staged) -> {
                    String committedText = extractWorkspaceTomlText(committed);
                    String stagedText = extractWorkspaceTomlText(staged);
                    return buildContextDiffView(committedText, stagedText);
                })
                .thenAccept(diff -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            contextDiffLoading = false;
                            if (!selectedWorkspacePath.equals(ClientSessionState.getSelectedWorkspacePath())) {
                                if (selectedWorkspacePath.equals(contextDiffLoadedPath)) {
                                    contextDiffLoadedPath = "";
                                    contextDiffLoadedSignature = "";
                                }
                                return;
                            }
                            contextDiffLoadedPath = selectedWorkspacePath;
                            contextDiffLoadedSignature = diffSignature;
                            applyContextDiffView(diff);
                        });
                    }
                })
                .exceptionally(ex -> {
                    Minecraft mc = this.minecraft;
                    if (mc != null) {
                        mc.execute(() -> {
                            contextDiffLoading = false;
                            if (selectedWorkspacePath.equals(contextDiffLoadedPath)) {
                                contextDiffLoadedPath = "";
                                contextDiffLoadedSignature = "";
                            }
                            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.diff_failed", shortError(ex.getMessage())), 0xFF5555);
                        });
                    }
                    return null;
                });
    }

    private String extractWorkspaceTomlText(JsonObject toolPayload) {
        if (toolPayload == null || !toolPayload.has("state") || !toolPayload.get("state").isJsonObject()) {
            return "";
        }
        JsonObject state = toolPayload.getAsJsonObject("state");
        if (state.has("workspace_toml") && state.get("workspace_toml").isJsonPrimitive()) {
            return state.get("workspace_toml").getAsString();
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
        contextDiffLoading = false;
        contextDiffLoadedPath = "";
        contextDiffLoadedSignature = "";
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
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.diff_failed_empty"), 0xFF5555);
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
        hideContextSelectionConfirm();

        if (!contextDiffChangeRows.isEmpty()) {
            int target = contextDiffChangeRows.get(contextDiffNavIndex);
            contextScroll = Math.max(0, target - Math.max(1, contextVisibleRows) / 2);
        } else {
            contextScroll = 0;
        }
        clampContextScroll();
        setContextEditorFocused(true);

        setContextStatus(P2SI18n.tr(diff.truncated()
                ? "screen.p2s.chat.context.status.diff_loaded_truncated"
                : "screen.p2s.chat.context.status.diff_loaded",
                diff.committedLineCount(), diff.stagedLineCount(), diff.changeRows().size()), 0x55FF55);
    }

    private void navigateContextDiffChange(int direction) {
        if (!contextDiffMode) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.diff_not_active"), 0xFFAA55);
            return;
        }
        if (contextDiffChangeRows.isEmpty()) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.diff_no_changes"), 0xFFAA55);
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
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.diff_change", contextDiffNavIndex + 1, size), 0xAAD5FF);
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

    private void formatContextToml() {
        if (contextDiffMode) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.diff_read_only"), 0xFFAA55);
            return;
        }
        String raw = contextLinesToText();
        if (raw.isBlank()) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.nothing_to_format"), 0xFFAA55);
            return;
        }
        try {
            String formatted = WorkspaceTomlCodec.format(raw);
            setContextEditorText(formatted);
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_toml_formatted"), 0x55FF55);
        } catch (Exception e) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.invalid_workspace_toml", shortError(e.getMessage())), 0xFF5555);
        }
    }

    private void clearContextEditorText() {
        setContextEditorText(defaultWorkspaceToml(ClientSessionState.getSelectedWorkspacePath()));
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_cleared"), 0x55FF55);
    }

    private void clearContextQueue() {
        queuedContexts.clear();
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.queue_cleared"), 0x55FF55);
    }

    private void setContextEditorText(String text) {
        exitDiffViewMode();
        contextEditorLines.clear();
        String normalized = text == null ? "" : text;
        String[] lines = normalized.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length == 0) {
            contextEditorLines.add("");
        } else {
            for (String line : lines) {
                contextEditorLines.add(line == null ? "" : line);
            }
        }
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
        }
        contextScroll = 0;
        contextCursorLine = 0;
        contextCursorColumn = 0;
        contextPreferredColumn = -1;
        clearContextSelection();
        contextMouseSelecting = false;
        hideContextSelectionConfirm();
        clampContextScroll();
        clampContextCursor();
        ensureContextCursorVisible();
    }

    private String defaultWorkspaceToml(String workspacePath) {
        ClientSessionState.WorkspaceFileInfo info = null;
        String normalizedPath = workspacePath == null ? "" : workspacePath.trim();
        for (ClientSessionState.WorkspaceFileInfo file : ClientSessionState.getWorkspaceFiles()) {
            if (file != null && normalizedPath.equals(file.path())) {
                info = file;
                break;
            }
        }
        if (info == null) {
            info = selectedWorkspaceFile();
        }

        ProjectPersistence.Vec3Data origin = null;
        if (info != null) {
            origin = new ProjectPersistence.Vec3Data();
            origin.x = info.originX();
            origin.y = info.originY();
            origin.z = info.originZ();
        }

        ProjectPersistence.Vec3Data size = null;
        if (info != null && info.hasSize()) {
            size = new ProjectPersistence.Vec3Data();
            size.x = info.sizeX();
            size.y = info.sizeY();
            size.z = info.sizeZ();
        }

        String resolvedPath = normalizedPath.isBlank()
                ? (info == null ? "workspace/main.toml" : info.path())
                : normalizedPath;
        String type = info == null ? "manual" : info.type();
        return WorkspaceTomlCodec.emptyWorkspaceToml(resolvedPath, type, origin, size);
    }

    private void clampContextCursor() {
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
        }
        contextCursorLine = Math.max(0, Math.min(contextCursorLine, contextEditorLines.size() - 1));
        String line = contextEditorLines.get(contextCursorLine);
        int maxColumn = line == null ? 0 : line.length();
        contextCursorColumn = Math.max(0, Math.min(contextCursorColumn, maxColumn));
        clampContextSelectionAnchor();
    }

    private void clampContextSelectionAnchor() {
        if (!contextSelectionActive) {
            return;
        }
        if (contextEditorLines.isEmpty()) {
            clearContextSelection();
            return;
        }
        contextSelectionAnchorLine = Math.max(0, Math.min(contextSelectionAnchorLine, contextEditorLines.size() - 1));
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
        if (lineIndex < 0 || lineIndex >= contextEditorLines.size()) {
            return "";
        }
        String value = contextEditorLines.get(lineIndex);
        return value == null ? "" : value;
    }

    private void setContextLine(int lineIndex, String value) {
        if (lineIndex < 0 || lineIndex >= contextEditorLines.size()) {
            return;
        }
        contextEditorLines.set(lineIndex, value == null ? "" : value);
    }

    private void clearContextSelection() {
        contextSelectionActive = false;
        hideContextSelectionConfirm();
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
                contextEditorLines.remove(line);
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
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
        }
        contextSelectionActive = true;
        contextSelectionAnchorLine = 0;
        contextSelectionAnchorColumn = 0;
        int lastLine = contextEditorLines.size() - 1;
        setContextCursor(lastLine, getContextLine(lastLine).length(), false);
    }

    private void insertContextText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
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
            contextEditorLines.add(insertAt, parts[i]);
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
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
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
        contextEditorLines.remove(contextCursorLine);
        setContextCursor(contextCursorLine - 1, newColumn, false);
    }

    private void deleteContextChar() {
        if (deleteContextSelection()) {
            return;
        }
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
            setContextCursor(0, 0, false);
            return;
        }
        String line = getContextLine(contextCursorLine);
        if (contextCursorColumn < line.length()) {
            setContextLine(contextCursorLine, line.substring(0, contextCursorColumn) + line.substring(contextCursorColumn + 1));
            return;
        }

        if (contextCursorLine >= contextEditorLines.size() - 1) {
            return;
        }

        String nextLine = getContextLine(contextCursorLine + 1);
        setContextLine(contextCursorLine, line + nextLine);
        contextEditorLines.remove(contextCursorLine + 1);
        setContextCursor(contextCursorLine, contextCursorColumn, false);
    }

    private void moveContextCursorHorizontal(int delta, boolean selecting) {
        if (delta == 0 || contextEditorLines.isEmpty()) {
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
        } else if (line < contextEditorLines.size() - 1) {
            setContextCursor(line + 1, 0, false);
        }
    }

    private void moveContextCursorVertical(int deltaRows, boolean selecting) {
        if (deltaRows == 0 || contextEditorLines.isEmpty()) {
            return;
        }
        updateContextSelectionForCursorMove(selecting);
        int preferred = contextPreferredColumn >= 0 ? contextPreferredColumn : contextCursorColumn;
        int targetLine = Math.max(0, Math.min(contextCursorLine + deltaRows, contextEditorLines.size() - 1));
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
        int totalLines = contextDiffMode ? contextDiffLines.size() : contextEditorLines.size();
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

    private boolean queueContextRange(int start, int end) {
        if (contextDiffMode) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.range_disabled_in_diff"), 0xFFAA55);
            return false;
        }
        if (queuedContexts.size() >= CONTEXT_MAX_SNIPPETS) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.too_many_snippets", CONTEXT_MAX_SNIPPETS), 0xFF5555);
            return false;
        }
        if (contextEditorLines.isEmpty()) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.no_context_lines"), 0xFF5555);
            return false;
        }

        int maxLine = contextEditorLines.size();
        start = Math.max(1, Math.min(start, maxLine));
        end = Math.max(1, Math.min(end, maxLine));
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }

        String snippetText = buildContextRangeText(start, end);
        if (snippetText.isBlank()) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.empty_range"), 0xFF5555);
            return false;
        }
        if (snippetText.length() > CONTEXT_MAX_SNIPPET_CHARS) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.selection_too_large", snippetText.length()), 0xFF5555);
            return false;
        }
        if (totalQueuedContextChars() + snippetText.length() > CONTEXT_MAX_TOTAL_CHARS) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.queue_too_large", CONTEXT_MAX_TOTAL_CHARS), 0xFF5555);
            return false;
        }

        String fileName = activeContextFileName();
        String label = fileName + ":" + start + "-" + end;
        queuedContexts.add(new ContextSnippet(label, snippetText));
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.added_context", label), 0x55FF55);
        return true;
    }

    private void confirmSelectionAsContext() {
        if (!contextSelectionConfirmVisible) {
            return;
        }
        if (queueContextRange(contextSelectionConfirmStartLine, contextSelectionConfirmEndLine)) {
            clearContextSelection();
        }
        hideContextSelectionConfirm();
    }

    private String buildContextRangeText(int startLine, int endLine) {
        if (contextEditorLines.isEmpty()) {
            return "";
        }
        int start = Math.max(1, startLine);
        int end = Math.max(start, endLine);
        StringBuilder sb = new StringBuilder();
        for (int line = start; line <= end && line <= contextEditorLines.size(); line++) {
            if (line > start) {
                sb.append('\n');
            }
            String value = contextEditorLines.get(line - 1);
            sb.append(value == null ? "" : value);
        }
        return sb.toString();
    }

    private String contextLinesToText() {
        if (contextEditorLines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contextEditorLines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(contextEditorLines.get(i) == null ? "" : contextEditorLines.get(i));
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
            sb.append("```").append(contextSnippetFenceLanguage(snippet.label())).append('\n');
            sb.append(snippet.content());
            sb.append("\n```\n");
        }
        return sb.toString().trim();
    }

    private String contextSnippetFenceLanguage(String label) {
        if (label == null || label.isBlank()) {
            return "text";
        }
        int colon = label.indexOf(':');
        String fileName = colon >= 0 ? label.substring(0, colon) : label;
        String normalized = fileName.trim().toLowerCase();
        if (normalized.endsWith(".toml")) {
            return "toml";
        }
        if (normalized.endsWith(".diff")) {
            return "diff";
        }
        return "text";
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

    private String shortError(String message) {
        if (message == null || message.isBlank()) {
            return P2SI18n.tr("screen.p2s.common.unknown").getString();
        }
        String text = message.trim();
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
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

    private Component resolveToolError(JsonObject result, String fallbackKey) {
        if (result == null) {
            return P2SI18n.tr(fallbackKey, P2SI18n.tr("screen.p2s.common.unknown"));
        }
        if (result.has("error_key") || result.has("error")) {
            Component detail = P2SI18n.resolvePayload(result, "error_key", "error_args", "error");
            return P2SI18n.tr(fallbackKey, detail.getString());
        }
        return P2SI18n.tr(fallbackKey, P2SI18n.tr("screen.p2s.common.unknown").getString());
    }

    private void syncCheckpointNameDraftFromSelection() {
        ClientSessionState.CheckpointInfo selected = ClientSessionState.getSelectedCheckpoint();
        String selectedId = selected == null || selected.id() == null ? "" : selected.id().trim();
        if (selectedId.equals(checkpointNameSourceId)) {
            return;
        }
        checkpointNameSourceId = selectedId;
        checkpointNameDraft = selected == null || selected.label() == null ? "" : selected.label();
        if (checkpointNameInput != null) {
            checkpointNameInput.setValue(checkpointNameDraft);
        }
    }

    private void setContextStatus(Component text, int color) {
        contextStatus = text == null ? Component.empty() : text;
        contextStatusColor = color;
    }

    private void hideContextSelectionConfirm() {
        contextSelectionConfirmVisible = false;
    }

    private void showContextSelectionConfirm(double mouseX, double mouseY) {
        if (contextDiffMode) {
            return;
        }
        ContextSelectionRange selection = getContextSelectionRange();
        if (selection == null) {
            return;
        }
        contextSelectionConfirmStartLine = selection.startLine() + 1;
        contextSelectionConfirmEndLine = selection.endLine() + 1;
        int popupWidth = CONTEXT_SELECTION_CONFIRM_WIDTH;
        int popupHeight = CONTEXT_SELECTION_CONFIRM_HEIGHT;
        int minX = contextEditorX + 4;
        int maxX = Math.max(minX, contextEditorX + contextEditorWidth - popupWidth - 4);
        int minY = contextEditorY + 4;
        int maxY = Math.max(minY, contextEditorY + contextEditorHeight - popupHeight - 4);
        contextSelectionConfirmX = Math.max(minX, Math.min((int) mouseX + 8, maxX));
        contextSelectionConfirmY = Math.max(minY, Math.min((int) mouseY + 8, maxY));
        contextSelectionConfirmVisible = true;
    }

    private boolean isInsideContextSelectionConfirm(double mouseX, double mouseY) {
        if (!contextSelectionConfirmVisible) {
            return false;
        }
        return mouseX >= contextSelectionConfirmX
                && mouseX < contextSelectionConfirmX + CONTEXT_SELECTION_CONFIRM_WIDTH
                && mouseY >= contextSelectionConfirmY
                && mouseY < contextSelectionConfirmY + CONTEXT_SELECTION_CONFIRM_HEIGHT;
    }

    private boolean isInsideContextSelectionConfirmOk(double mouseX, double mouseY) {
        int buttonY = contextSelectionConfirmY + CONTEXT_SELECTION_CONFIRM_HEIGHT - CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT - 6;
        int okX = contextSelectionConfirmX + CONTEXT_SELECTION_CONFIRM_WIDTH - CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH * 2 - 10;
        return mouseX >= okX
                && mouseX < okX + CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH
                && mouseY >= buttonY
                && mouseY < buttonY + CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT;
    }

    private boolean isInsideContextSelectionConfirmCancel(double mouseX, double mouseY) {
        int buttonY = contextSelectionConfirmY + CONTEXT_SELECTION_CONFIRM_HEIGHT - CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT - 6;
        int cancelX = contextSelectionConfirmX + CONTEXT_SELECTION_CONFIRM_WIDTH - CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH - 6;
        return mouseX >= cancelX
                && mouseX < cancelX + CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH
                && mouseY >= buttonY
                && mouseY < buttonY + CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT;
    }

    private void refreshContextDiffControls() {
        boolean hasChanges = contextDiffMode && !contextDiffChangeRows.isEmpty();
        if (contextDiffPrevButton != null) {
            contextDiffPrevButton.active = hasChanges;
        }
        if (contextDiffNextButton != null) {
            contextDiffNextButton.active = hasChanges;
        }
    }

    private void setContextEditorFocused(boolean focused) {
        if (contextEditorCollapsed) {
            contextEditorFocused = false;
            return;
        }
        contextEditorFocused = focused;
        if (!focused) {
            return;
        }
        if (input != null) {
            input.setFocused(false);
        }
    }

    private boolean isInsideContextEditor(double mouseX, double mouseY) {
        if (contextEditorCollapsed || contextEditorWidth <= 0 || contextEditorHeight <= 0) {
            return false;
        }
        return mouseX >= contextEditorX && mouseX < contextEditorX + contextEditorWidth
                && mouseY >= contextEditorY && mouseY < contextEditorY + contextEditorHeight;
    }

    private void placeContextCursorFromMouse(double mouseX, double mouseY) {
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
        }
        int rowStep = CONTEXT_ROW_HEIGHT + CONTEXT_ROW_GAP;
        int row = (int) ((mouseY - (contextEditorY + CONTEXT_EDITOR_PADDING)) / rowStep);
        row = Math.max(0, Math.min(row, Math.max(0, contextVisibleRows - 1)));
        int line = Math.max(0, Math.min(contextScroll + row, contextEditorLines.size() - 1));

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
        if (workspaceCreateMode) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                exitWorkspaceCreateMode();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmWorkspaceCreate();
                return true;
            }
            if (workspaceCreateInput != null && workspaceCreateInput.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

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

        if (choicePopupVisible) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeChoicePopup();
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                    && choiceCustomInput != null && choiceCustomInput.isFocused()) {
                submitCustomChoice();
                return true;
            }
            if (choiceCustomInput != null && choiceCustomInput.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }

        if (contextSelectionConfirmVisible) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                hideContextSelectionConfirm();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmSelectionAsContext();
                return true;
            }
            hideContextSelectionConfirm();
        }

        if (contextEditorCollapsed && !hasPinnedCollapsedPanelFocus()) {
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                cycleCollapsedPanelFocus(hasShiftDown() ? -1 : 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && collapsedPanelFocus != PanelFocus.NONE) {
                setCollapsedPanelFocus(PanelFocus.NONE);
                return true;
            }
        }

        if (isGameplayInputActive()) {
            return false;
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
        if (workspaceCreateMode) {
            if (workspaceCreateInput != null && workspaceCreateInput.charTyped(codePoint, modifiers)) {
                return true;
            }
            return super.charTyped(codePoint, modifiers);
        }
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
        if (choicePopupVisible && choiceCustomInput != null && choiceCustomInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (isGameplayInputActive()) {
            return false;
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
        if (isGameplayInputActive()) {
            return false;
        }

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        boolean insideLeftDock = isInsideLeftDock(mouseX, mouseY, panelX);
        boolean insideSessionPanel = isInsideSessionPanel(mouseX, mouseY, panelX, panelWidth);

        if (button == 0 && isInsideSessionSplitter(mouseX, mouseY)) {
            draggingSessionSplitter = true;
            return true;
        }
        if (button == 0 && isInsideExplorerSplitter(mouseX, mouseY)) {
            draggingExplorerSplitter = true;
            return true;
        }

        // Handle info overlay clicks
        if (button == 0 && infoOverlayVisible) {
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

        if (button == 0 && contextSelectionConfirmVisible) {
            if (isInsideContextSelectionConfirmOk(mouseX, mouseY)) {
                confirmSelectionAsContext();
                return true;
            }
            if (isInsideContextSelectionConfirmCancel(mouseX, mouseY)) {
                hideContextSelectionConfirm();
                return true;
            }
            if (isInsideContextSelectionConfirm(mouseX, mouseY)) {
                return true;
            }
            hideContextSelectionConfirm();
        }

        if (button == 0 && choicePopupVisible) {
            if (isInsideChoicePopup(mouseX, mouseY)) {
                if (!isInsideChoicePopupWidget(mouseX, mouseY)) {
                    return true;
                }
            } else if (!isInsideWidget(choiceToggleButton, mouseX, mouseY)) {
                closeChoicePopup();
                return true;
            }
        }

        if (button == 0 && contextEditorCollapsed) {
            if (insideLeftDock) {
                setCollapsedPanelFocus(PanelFocus.LEFT);
            } else if (insideSessionPanel) {
                setCollapsedPanelFocus(PanelFocus.RIGHT);
            }
        }

        if (button == 0 && !sessionPanelCollapsed && !infoOverlayVisible && toggleToolLogAt(mouseX, mouseY)) {
            return true;
        }

        if (button == 0 && handleExplorerClick(mouseX, mouseY)) {
            if (contextEditorCollapsed) {
                setCollapsedPanelFocus(PanelFocus.LEFT);
            }
            contextEditorFocused = false;
            contextMouseSelecting = false;
            return true;
        }

        if (button == 0 && isInsideContextEditor(mouseX, mouseY)) {
            setContextEditorFocused(true);
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
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            return true;
        }
        if (button == 0 && contextEditorCollapsed) {
            if (insideLeftDock || insideSessionPanel) {
                return true;
            }
            if (!hasPinnedCollapsedPanelFocus() && effectiveCollapsedPanelFocus() != PanelFocus.NONE) {
                setCollapsedPanelFocus(PanelFocus.NONE);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isGameplayInputActive()) {
            return false;
        }
        if (button == 0 && draggingSessionSplitter) {
            updateSessionWidthFromMouse(mouseX);
            createWidgets();
            return true;
        }
        if (button == 0 && draggingExplorerSplitter) {
            updateExplorerWidthFromMouse(mouseX);
            createWidgets();
            return true;
        }
        if (contextDiffMode) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (button == 0 && contextMouseSelecting && contextEditorFocused) {
            ensureSelectionAnchor();
            placeContextCursorFromMouse(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (isGameplayInputActive()) {
            return false;
        }
        if (button == 0) {
            boolean releasedSplitter = draggingSessionSplitter || draggingExplorerSplitter;
            draggingSessionSplitter = false;
            draggingExplorerSplitter = false;
            if (releasedSplitter) {
                return true;
            }
            if (contextMouseSelecting && hasContextSelection() && !contextDiffMode) {
                showContextSelectionConfirm(mouseX, mouseY);
            }
            contextMouseSelecting = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isGameplayInputActive()) {
            return false;
        }
        if (choicePopupVisible && isInsideChoicePopup(mouseX, mouseY)) {
            return true;
        }

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
        if (isInsideExplorerList(mouseX, mouseY)) {
            int step = getExplorerRowStep() * 3;
            int delta = verticalAmount > 0 ? -step : verticalAmount < 0 ? step : 0;
            explorerScrollOffset = clamp(explorerScrollOffset + delta, 0, getExplorerMaxScroll());
            return true;
        }
        if (contextEditorCollapsed && mouseX < panelX) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
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

        if (sessionPanelCollapsed) {
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
        syncChoicePopupRequestState();
        refreshChoiceButtons();
        syncCheckpointNameDraftFromSelection();
        if (checkpointModeButton != null) { checkpointModeButton.setMessage(Component.literal(modeLabel())); }
        if (workspaceRenameMode) {
            String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
            if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
                exitWorkspaceRenameMode();
            }
        }
        processPendingWorkspaceJumpIfReady();
        syncInlineDiffIfNeeded();
        if (discardReasonMode && !ClientSessionState.hasPendingPatch()) {
            exitDiscardReasonMode();
        }
        if (discardReasonMode && choicePopupVisible) {
            closeChoicePopup();
        }
        boolean selectedPending = canActOnSelectedPendingPatch();
        if (contextApplyButton != null) {
            contextApplyButton.active = selectedPending && !discardReasonMode;
            contextApplyButton.visible = !discardReasonMode;
        }
        if (contextDiscardButton != null) {
            contextDiscardButton.active = selectedPending && !discardReasonMode;
            contextDiscardButton.visible = !discardReasonMode;
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
        if (compactButton != null) {
            compactButton.active = ClientAgentManager.canManualCompact();
        }
        boolean sessionActive = ClientSessionState.isActive();
        boolean hasCheckpoint = ClientSessionState.getSelectedCheckpoint() != null;
        if (checkpointCreateButton != null) {
            checkpointCreateButton.active = sessionActive;
        }
        if (checkpointListButton != null) {
            checkpointListButton.active = sessionActive;
        }
        if (checkpointPrevButton != null) {
            checkpointPrevButton.active = sessionActive && !ClientSessionState.getCheckpoints().isEmpty();
        }
        if (checkpointNextButton != null) {
            checkpointNextButton.active = sessionActive && !ClientSessionState.getCheckpoints().isEmpty();
        }
        if (checkpointRollbackButton != null) {
            checkpointRollbackButton.active = sessionActive && hasCheckpoint;
        }
        if (checkpointModeButton != null) {
            checkpointModeButton.active = sessionActive && hasCheckpoint;
        }
        if (checkpointRenameButton != null) {
            String checkpointName = checkpointNameInput == null ? checkpointNameDraft : checkpointNameInput.getValue();
            checkpointRenameButton.active = sessionActive && hasCheckpoint && checkpointName != null && !checkpointName.trim().isEmpty();
        }
        boolean contextReadOnly = contextDiffMode || contextDiffLoading || selectedWorkspaceHasPendingPatch();
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        boolean hasSelectedWorkspace = selectedWorkspacePath != null && !selectedWorkspacePath.isBlank();
        if (workspaceRenameButton != null) {
            workspaceRenameButton.active = hasSelectedWorkspace;
        }
        if (workspaceDeleteButton != null) {
            workspaceDeleteButton.active = hasSelectedWorkspace;
        }
        if (contextLoadButton != null) {
            contextLoadButton.active = hasSelectedWorkspace;
        }
        if (contextSaveButton != null) {
            contextSaveButton.active = !contextReadOnly && hasSelectedWorkspace;
        }
        if (contextFormatButton != null) {
            contextFormatButton.active = !contextReadOnly;
        }
        if (contextClearButton != null) {
            contextClearButton.active = !contextReadOnly;
        }
        refreshContextDiffControls();
        refreshWorkspaceExplorerIfNeeded();
        syncActiveDocScriptIfNeeded();
        toolLogToggleAreas.clear();

        int panelWidth = getPanelWidth();
        int panelX = getPanelX(panelWidth);
        drawContextPanel(gfx, panelX, mouseX, mouseY);

        if (!sessionPanelCollapsed) {
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
        } else {
            drawCollapsedSessionRail(gfx, panelX, panelWidth);
        }

        drawDockChrome(gfx, panelX);
        drawChoicePopup(gfx);

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

        applyCollapsedPanelFocusState();

        super.render(gfx, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // No full-screen dimming. Panels draw their own backgrounds.
    }

    private void drawContextPanel(GuiGraphics gfx, int panelX, int mouseX, int mouseY) {
        int contextPanelRight = getContextDockRight(panelX);
        if (contextPanelRight <= 0) {
            return;
        }
        gfx.fill(0, 0, contextPanelRight, this.height, 0xC0141A26);
        gfx.fill(contextPanelRight - 1, 0, contextPanelRight, this.height, 0xFF2F3A4D);
        explorerHitAreas.clear();
        if (explorerPanelCollapsed) {
            drawCollapsedExplorerRail(gfx);
        } else {
            drawExplorerHeader(gfx);
            drawWorkspaceExplorer(gfx, mouseX, mouseY);
        }

        if (contextEditorCollapsed) {
            if (contextStatus != null && !contextStatus.getString().isBlank()) {
                String status = trimEndToWidth(contextStatus.getString(), Math.max(40, contextPanelRight - PADDING * 2));
                gfx.drawString(this.font, status, PADDING, this.height - CONTEXT_COLLAPSED_FOOTER_HEIGHT + 6, contextStatusColor, false);
            }
            return;
        }

        int x = PADDING;
        drawContextEditor(gfx);

        int infoY = contextQueueTopY;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.chat.context.next_message", queuedContexts.size(), CONTEXT_MAX_SNIPPETS), x, infoY, 0xE8F0FF, true);
        infoY += this.font.lineHeight + 2;

        if (queuedContexts.isEmpty()) {
            gfx.drawString(this.font, "-", x, infoY, 0x8A99B8, false);
            infoY += this.font.lineHeight + 2;
        } else {
            int maxRows = 6;
            for (int i = 0; i < queuedContexts.size() && i < maxRows; i++) {
                ContextSnippet snippet = queuedContexts.get(i);
                String line = P2SI18n.tr("screen.p2s.chat.context.snippet", i + 1, snippet.label(), snippet.content().length()).getString();
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
                gfx.drawString(this.font, P2SI18n.tr("screen.p2s.common.more", queuedContexts.size() - maxRows), x, infoY, 0x8A99B8, false);
                infoY += this.font.lineHeight + 1;
            }
        }

        if (contextStatus != null && !contextStatus.getString().isBlank()) {
            gfx.drawString(this.font, contextStatus, x, this.height - 14, contextStatusColor, false);
        }

        drawContextSelectionConfirm(gfx);
    }

    private void drawWorkspaceExplorer(GuiGraphics gfx, int mouseX, int mouseY) {
        if (explorerListWidth <= 0 || explorerListHeight <= 0) {
            return;
        }
        clampExplorerScroll();

        int left = explorerListX;
        int top = explorerListY;
        int right = explorerListX + explorerListWidth;
        int bottom = explorerListY + explorerListHeight;
        int contentHeight = Math.max(0, explorerRows.size() * getExplorerRowStep() - explorerRowGap);
        boolean showScrollbar = contentHeight > explorerListHeight;
        int scrollbarReserved = showScrollbar ? 8 : 0;
        int rowLeft = left + 2;
        int rowRight = right - 2 - scrollbarReserved;

        gfx.fill(left, top, right, bottom, 0x7A0A1018);
        gfx.fill(left, top, right, top + 1, 0xAA324153);
        gfx.fill(left, bottom - 1, right, bottom, 0xAA324153);
        gfx.fill(left, top, left + 1, bottom, 0xAA324153);
        gfx.fill(right - 1, top, right, bottom, 0xAA324153);

        int rowStep = getExplorerRowStep();
        gfx.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        try {
            for (int index = 0; index < explorerRows.size(); index++) {
                P2SWorkspaceExplorerComponent.ExplorerRow row = explorerRows.get(index);
                int rowTop = top + 2 + index * rowStep - (int) explorerScrollOffset;
                int rowBottom = rowTop + explorerRowHeight;
                if (rowBottom < top + 1) {
                    continue;
                }
                if (rowTop > bottom - 1) {
                    break;
                }

                boolean hovered = mouseX >= rowLeft && mouseX <= rowRight && mouseY >= rowTop && mouseY <= rowBottom;
                if (row.placeholder()) {
                    int textY = rowTop + Math.max(0, (explorerRowHeight - this.font.lineHeight) / 2);
                    gfx.drawString(this.font, trimEndToWidth(row.name(), Math.max(20, rowRight - rowLeft - 8)), rowLeft + 6, textY, 0x8797B1, false);
                    continue;
                }

                if (row.selected()) {
                    gfx.fill(rowLeft, rowTop, rowRight, rowBottom, 0xAA263B57);
                    gfx.fill(rowLeft, rowTop, rowLeft + 2, rowBottom, 0xFF7FA3D5);
                } else if (hovered) {
                    gfx.fill(rowLeft, rowTop, rowRight, rowBottom, 0x66324053);
                }

                for (int depth = 0; depth < row.depth(); depth++) {
                    int guideX = rowLeft + 11 + depth * 12;
                    gfx.fill(guideX, rowTop + 4, guideX + 1, rowBottom - 4, 0x223B4D66);
                }

                int textY = rowTop + Math.max(0, (explorerRowHeight - this.font.lineHeight) / 2);
                int baseX = rowLeft + 8 + row.depth() * 12;

                if (row.folder()) {
                    explorerHitAreas.add(new ExplorerHitArea(row.path(), true, false, rowLeft, rowTop, rowRight, rowBottom));
                    int arrowX = baseX;
                    gfx.drawString(this.font, row.collapsed() ? Component.literal("▸") : Component.literal("▾"), arrowX, textY, hovered ? 0xEAF2FF : 0xB9CCE7, false);
                    explorerHitAreas.add(new ExplorerHitArea(row.path(), true, true, arrowX - 2, rowTop, arrowX + 10, rowBottom));

                    String meta = explorerFolderMeta(row);
                    int metaWidth = meta.isBlank() ? 0 : this.font.width(meta);
                    int labelX = arrowX + 12;
                    int maxLabelWidth = Math.max(20, rowRight - labelX - (metaWidth > 0 ? metaWidth + 10 : 0));
                    String label = trimEndToWidth(row.name(), maxLabelWidth);
                    int labelColor = row.selected() ? 0xF2F7FF : hovered ? 0xDFE9F8 : 0xC7D5EA;
                    gfx.drawString(this.font, label, labelX, textY, labelColor, false);
                    if (!meta.isBlank()) {
                        gfx.drawString(this.font, meta, rowRight - metaWidth - 4, textY, 0x7F94AF, false);
                    }
                    continue;
                }

                explorerHitAreas.add(new ExplorerHitArea(row.path(), false, false, rowLeft, rowTop, rowRight, rowBottom));
                gfx.drawString(this.font, Component.literal("•"), baseX + 1, textY, row.pending() ? 0xFFD39C : 0x6E86A4, false);
                String badge = explorerFileBadge(row);
                int badgeWidth = badge.isBlank() ? 0 : this.font.width(badge) + 10;
                int labelX = baseX + 12;
                int maxLabelWidth = Math.max(20, rowRight - labelX - (badgeWidth > 0 ? badgeWidth + 8 : 0));
                String label = trimEndToWidth(row.name(), maxLabelWidth);
                int labelColor = row.selected() ? 0xF2F7FF : hovered ? 0xDCE8F8 : 0xBCCBDD;
                gfx.drawString(this.font, label, labelX, textY, labelColor, false);
                if (!badge.isBlank()) {
                    int badgeLeft = rowRight - badgeWidth - 4;
                    int badgeTop = rowTop + 3;
                    int badgeBottom = rowBottom - 3;
                    gfx.fill(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeBottom, 0xAA4B3520);
                    gfx.fill(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + 1, 0xFFCF9A69);
                    gfx.fill(badgeLeft, badgeBottom - 1, badgeLeft + badgeWidth, badgeBottom, 0xFFCF9A69);
                    gfx.drawString(this.font, badge, badgeLeft + 5, textY, 0xFFF1D6, false);
                }
            }
        } finally {
            gfx.disableScissor();
        }

        if (showScrollbar) {
            boolean scrollbarHovered = mouseX >= right - 8 && mouseX <= right && mouseY >= top && mouseY <= bottom;
            int trackLeft = right - 5;
            int trackTop = top + 3;
            int trackBottom = bottom - 3;
            gfx.fill(trackLeft, trackTop, trackLeft + 3, trackBottom, scrollbarHovered ? 0x66486687 : 0x4435475C);
            int trackHeight = Math.max(1, trackBottom - trackTop);
            int thumbHeight = Math.max(18, (int) ((explorerListHeight / (double) contentHeight) * trackHeight));
            int thumbTravel = Math.max(0, trackHeight - thumbHeight);
            int maxScroll = Math.max(1, getExplorerMaxScroll());
            int thumbTop = trackTop + (int) ((explorerScrollOffset / maxScroll) * thumbTravel);
            gfx.fill(trackLeft, thumbTop, trackLeft + 3, thumbTop + thumbHeight, scrollbarHovered ? 0xFF9BB3D7 : 0xCC7E96B8);
        }
    }

    private void drawExplorerHeader(GuiGraphics gfx) {
        int left = PADDING + 2;
        int right = Math.max(left + 24, currentExplorerSplitterX - DOCK_SPLITTER_WIDTH - 4);
        int titleY = 6;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.workspace.section"), left, titleY, 0x93A9C9, false);

        String breadcrumb = buildExplorerBreadcrumb();
        if (!breadcrumb.isBlank()) {
            gfx.drawString(this.font, trimMiddleToWidth(breadcrumb, Math.max(40, right - left)), left, titleY + this.font.lineHeight + 2, 0xE8F0FF, false);
        }

        int dividerY = titleY + this.font.lineHeight * 2 + 5;
        gfx.fill(left, dividerY, right, dividerY + 1, 0x6637465E);
    }

    private String buildExplorerBreadcrumb() {
        String selectedPath = ClientSessionState.getSelectedWorkspaceLabel();
        if (selectedPath != null && !selectedPath.isBlank()) {
            return selectedPath.replace("/", " › ");
        }
        if (selectedExplorerFolderPath != null && !selectedExplorerFolderPath.isBlank()) {
            return selectedExplorerFolderPath.replace("/", " › ");
        }
        return P2SI18n.tr("screen.p2s.workspace.empty").getString();
    }

    private String buildSessionHeaderMeta() {
        List<String> parts = new ArrayList<>();
        String projectName = ClientSessionState.getProjectName();
        if (projectName != null && !projectName.isBlank()) {
            parts.add(P2SI18n.tr("screen.p2s.chat.project", projectName).getString());
        }
        if (ClientSessionState.isActive()) {
            parts.add(P2SI18n.tr("screen.p2s.chat.session_info", shortId(ClientSessionState.getSessionId()), ClientSessionState.getTurnCount()).getString());
        }
        String selectedPath = ClientSessionState.getSelectedWorkspaceLabel();
        if (selectedPath != null && !selectedPath.isBlank()) {
            parts.add(selectedPath.replace("/", " › "));
        }
        return String.join(" · ", parts);
    }

    private String trimMiddleToWidth(String text, int maxWidth) {
        if (text == null || text.isBlank() || this.font == null || maxWidth <= 0) {
            return text == null ? "" : text;
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        if (this.font.width(ellipsis) >= maxWidth) {
            return ellipsis;
        }
        int leftChars = Math.max(1, (text.length() - 3) / 2);
        int rightChars = Math.max(1, text.length() - 3 - leftChars);
        String candidate = text;
        while (leftChars > 1 || rightChars > 1) {
            candidate = text.substring(0, leftChars) + ellipsis + text.substring(text.length() - rightChars);
            if (this.font.width(candidate) <= maxWidth) {
                return candidate;
            }
            if (leftChars >= rightChars && leftChars > 1) {
                leftChars--;
            } else if (rightChars > 1) {
                rightChars--;
            } else {
                leftChars--;
            }
        }
        return candidate;
    }

    private void drawCollapsedExplorerRail(GuiGraphics gfx) {
        int right = Math.max(DOCK_COLLAPSED_WIDTH, currentExplorerSplitterX);
        gfx.fill(0, 0, right, this.height, 0xA60E141E);
        gfx.fill(right - 1, 0, right, this.height, 0xFF4C5D78);
    }

    private void drawCollapsedSessionRail(GuiGraphics gfx, int panelX, int panelWidth) {
        int left = panelX;
        int right = panelX + panelWidth;
        gfx.fill(left, 0, right, this.height, 0xB0121822);
        gfx.fill(left, 0, left + 1, this.height, 0xFF4C5D78);
    }

    private void drawDockChrome(GuiGraphics gfx, int panelX) {
        int splitterColor = 0xFF2F3A4D;
        int activeColor = 0xFF5A6E91;
        int explorerColor = (explorerPanelCollapsed || draggingExplorerSplitter) ? activeColor : splitterColor;
        int sessionColor = (sessionPanelCollapsed || draggingSessionSplitter) ? activeColor : splitterColor;
        gfx.fill(currentExplorerSplitterX - 1, 0, currentExplorerSplitterX + 1, this.height, explorerColor);
        gfx.fill(panelX - 1, 0, panelX + 1, this.height, sessionColor);
    }

    private void drawContextEditor(GuiGraphics gfx) {
        if (contextEditorCollapsed) {
            return;
        }
        if (contextDiffMode) {
            drawContextDiffEditor(gfx);
            return;
        }
        drawContextTextEditor(gfx);
    }

    private void drawContextTextEditor(GuiGraphics gfx) {
        if (contextEditorLines.isEmpty()) {
            contextEditorLines.add("");
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
        int lastLineExclusive = Math.min(contextEditorLines.size(), firstLine + visibleRows);
        for (int lineIndex = firstLine; lineIndex < lastLineExclusive; lineIndex++) {
            int row = lineIndex - firstLine;
            int rowY = baseY + row * rowStep;
            int rowBottom = rowY + CONTEXT_ROW_HEIGHT;
            int lineNo = lineIndex + 1;
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
                        gfx.fill(drawSelX1, rowY + 2, drawSelX2, rowBottom - 2, 0x665A8BFF);
                    }
                }
            }
            gfx.drawString(this.font, Integer.toString(lineNo), left + 4, rowY + 5, 0x8A99B8, false);
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

    private void drawContextSelectionConfirm(GuiGraphics gfx) {
        if (!contextSelectionConfirmVisible) {
            return;
        }
        int left = contextSelectionConfirmX;
        int top = contextSelectionConfirmY;
        int right = left + CONTEXT_SELECTION_CONFIRM_WIDTH;
        int bottom = top + CONTEXT_SELECTION_CONFIRM_HEIGHT;
        gfx.fill(left, top, right, bottom, 0xE6111720);
        gfx.fill(left, top, right, top + 1, 0xFF5A6E91);
        gfx.fill(left, bottom - 1, right, bottom, 0xFF5A6E91);
        gfx.fill(left, top, left + 1, bottom, 0xFF5A6E91);
        gfx.fill(right - 1, top, right, bottom, 0xFF5A6E91);

        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.chat.context.confirm_add_selection"), left + 6, top + 6, 0xFFFFFF, false);
        String label = activeContextFileName() + ":" + contextSelectionConfirmStartLine + "-" + contextSelectionConfirmEndLine;
        int chars = buildContextRangeText(contextSelectionConfirmStartLine, contextSelectionConfirmEndLine).length();
        String preview = P2SI18n.tr("screen.p2s.chat.context.confirm_selection_preview", label, chars).getString();
        gfx.drawString(this.font, clipTextToWidth(preview, CONTEXT_SELECTION_CONFIRM_WIDTH - 12), left + 6, top + 18, 0xC9D4EB, false);

        int buttonY = bottom - CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT - 6;
        int okX = right - CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH * 2 - 10;
        int cancelX = right - CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH - 6;
        gfx.fill(okX, buttonY, okX + CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH, buttonY + CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT, 0xFF365C3B);
        gfx.fill(cancelX, buttonY, cancelX + CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH, buttonY + CONTEXT_SELECTION_CONFIRM_BUTTON_HEIGHT, 0xFF4C3940);
        gfx.drawCenteredString(this.font, P2SI18n.tr("screen.p2s.common.ok").getString(), okX + CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH / 2, buttonY + 5, 0xFFFFFF);
        gfx.drawCenteredString(this.font, P2SI18n.tr("screen.p2s.common.cancel").getString(), cancelX + CONTEXT_SELECTION_CONFIRM_BUTTON_WIDTH / 2, buttonY + 5, 0xFFFFFF);
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
        if (workspaceCreateInput != null) {
            boxes.add(workspaceCreateInput);
        }
        if (workspaceRenameInput != null) {
            boxes.add(workspaceRenameInput);
        }
        if (checkpointNameInput != null) {
            boxes.add(checkpointNameInput);
        }
        if (choiceCustomInput != null) {
            boxes.add(choiceCustomInput);
        }
        return boxes;
    }

    private void drawHeader(GuiGraphics gfx, int panelX, int panelWidth) {
        String meta = buildSessionHeaderMeta();
        if (meta.isBlank()) {
            return;
        }
        int metaTop = getMessageTop(panelWidth) - CHAT_HEADER_META_HEIGHT;
        int dividerY = metaTop - 2;
        int left = panelX + PADDING;
        int right = panelX + panelWidth - PADDING;
        gfx.fill(left, dividerY, right, dividerY + 1, 0x6637465E);
        gfx.drawString(this.font, trimMiddleToWidth(meta, Math.max(40, right - left)), left, metaTop + 1, 0xAFC2DE, false);
    }

    private int countInfoSections(int contentWidth) {
        int count = 0;
        if (!getChoicePromptLines(contentWidth).isEmpty()) count++;
        if (!getPreviewLines(contentWidth).isEmpty()) count++;
        if (!getSummaryLines(contentWidth).isEmpty()) count++;
        if (!getPlanLines(contentWidth).isEmpty()) count++;
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
            allLines.add(new OverlayLine(null, 0xFFCC66, true, P2SI18n.tr("screen.p2s.chat.overlay.action_required").getString()));
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
            allLines.add(new OverlayLine(null, color, true, P2SI18n.tr("screen.p2s.chat.overlay.pending_patch", changed, P2SI18n.riskComponent(risk)).getString()));
            for (FormattedCharSequence line : previewLines) {
                allLines.add(new OverlayLine(line, 0xE0E0E0, false, null));
            }
            allLines.add(new OverlayLine(null, 0, false, null));
        }

        List<FormattedCharSequence> summaryLines = getSummaryLines(cw);
        if (!summaryLines.isEmpty()) {
            allLines.add(new OverlayLine(null, 0xAAAAAA, true, P2SI18n.tr("screen.p2s.chat.overlay.current_structure").getString()));
            for (FormattedCharSequence line : summaryLines) {
                allLines.add(new OverlayLine(line, 0xCCCCCC, false, null));
            }
            allLines.add(new OverlayLine(null, 0, false, null));
        }

        List<FormattedCharSequence> planLines = getPlanLines(cw);
        if (!planLines.isEmpty()) {
            allLines.add(new OverlayLine(null, 0x99CCFF, true, P2SI18n.tr("screen.p2s.chat.overlay.plan").getString()));
            for (FormattedCharSequence line : planLines) {
                allLines.add(new OverlayLine(line, 0xCCDDEE, false, null));
            }
            allLines.add(new OverlayLine(null, 0, false, null));
        }

        List<FormattedCharSequence> checkpointLines = getCheckpointLines(cw);
        if (!checkpointLines.isEmpty()) {
            allLines.add(new OverlayLine(null, 0x99FFCC, true, P2SI18n.tr("screen.p2s.chat.overlay.checkpoints", modeLabel()).getString()));
            for (FormattedCharSequence line : checkpointLines) {
                allLines.add(new OverlayLine(line, 0xCCFFEE, false, null));
            }
        }

        if (allLines.isEmpty()) {
            gfx.drawString(this.font, P2SI18n.tr("screen.p2s.chat.overlay.no_info"), overlayLeft + PADDING, overlayTop + PADDING + this.font.lineHeight, 0x888888, true);
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

    private record ExplorerHitArea(String path, boolean folder, boolean toggle, int left, int top, int right, int bottom) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
    }

    private record ChatRenderEntry(
            ClientSessionState.ChatMessage message,
            List<FormattedCharSequence> headerLines,
            List<FormattedCharSequence> detailLines,
            int totalHeight,
            int visualHeight,
            boolean toolLog,
            boolean error,
            boolean expanded
    ) {}

    private record ToolLogToggleArea(String messageId, int left, int top, int right, int bottom) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
    }

    private void drawStatus(GuiGraphics gfx, int panelX) {
        String status = ClientSessionState.getStatus();
        if (status == null || status.isBlank()) {
            return;
        }
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.chat.status", P2SI18n.statusComponent(status)), panelX + PADDING, getStatusY(), 0xAAAAAA, true);
    }


    private void drawMessages(GuiGraphics gfx, int panelX, int panelWidth) {
    int contentWidth = panelWidth - PADDING * 2;
    int messageTop = getMessageTop(panelWidth);
    int messageBottom = getMessageBottom();
    int messageAreaHeight = Math.max(0, messageBottom - messageTop);

    int totalHeight = computeContentHeight(contentWidth);
    int maxScroll = Math.max(0, totalHeight - messageAreaHeight);
    scrollOffset = clamp(scrollOffset, 0, maxScroll);

    toolLogToggleAreas.clear();
    int y = messageBottom + (int) scrollOffset;
    List<ChatRenderEntry> entries = buildChatRenderEntries(contentWidth);

    boolean isStreaming = ClientSessionState.isStreaming();
    String streamingText = isStreaming ? ClientSessionState.getStreamingText() : null;
    if (isStreaming && streamingText != null && !streamingText.isBlank()) {
        String streamPrefix = P2SI18n.rolePrefix(P2SI18n.ROLE_ASSISTANT);
        List<FormattedCharSequence> streamLines = this.font.split(Component.literal(streamPrefix + streamingText), contentWidth);
        for (int li = streamLines.size() - 1; li >= 0; li--) {
            y -= this.font.lineHeight + LINE_SPACING;
            if (y < messageTop) {
                return;
            }
            if (y <= messageBottom - this.font.lineHeight) {
                gfx.drawString(this.font, streamLines.get(li), panelX + PADDING, y, 0x55FF55, true);
            }
        }
        y -= LINE_SPACING;
    }

    for (int i = entries.size() - 1; i >= 0 && y > messageTop; i--) {
        ChatRenderEntry entry = entries.get(i);
        int blockTop = y - entry.totalHeight();
        int visualBottom = blockTop + entry.visualHeight();
        if (visualBottom < messageTop) {
            return;
        }
        if (entry.toolLog()) {
            drawToolLogEntry(gfx, panelX, panelWidth, messageTop, messageBottom, blockTop, entry);
        } else {
            drawPlainChatEntry(gfx, panelX, messageTop, messageBottom, blockTop, entry);
        }
        y = blockTop;
    }
}

    private int computeContentHeight(int contentWidth) {
    int total = 0;
    for (ChatRenderEntry entry : buildChatRenderEntries(contentWidth)) {
        total += entry.totalHeight();
    }

    if (ClientSessionState.isStreaming()) {
        String streamingText = ClientSessionState.getStreamingText();
        if (streamingText != null && !streamingText.isBlank()) {
            int lines = this.font.split(Component.literal(P2SI18n.rolePrefix(P2SI18n.ROLE_ASSISTANT) + streamingText), contentWidth).size();
            total += lines * (this.font.lineHeight + LINE_SPACING);
            total += LINE_SPACING;
        }
    }

    return total;
}

    private List<ChatRenderEntry> buildChatRenderEntries(int contentWidth) {
    List<ClientSessionState.ChatMessage> messages = ClientSessionState.getMessages();
    pruneExpandedToolLogIds(messages);

    List<ChatRenderEntry> entries = new ArrayList<>(messages.size());
    int lineStep = this.font.lineHeight + LINE_SPACING;
    int plainWidth = Math.max(40, contentWidth);
    int toolWidth = Math.max(40, contentWidth - TOOL_LOG_PADDING_X * 2);
    for (ClientSessionState.ChatMessage message : messages) {
        if (message == null) {
            continue;
        }
        if (isToolLogMessage(message)) {
            boolean expanded = message.id() != null && expandedToolLogIds.contains(message.id());
            String prefix = expanded ? "v " : "> ";
            List<FormattedCharSequence> headerLines = this.font.split(Component.literal(prefix + message.text()), toolWidth);
            List<FormattedCharSequence> detailLines = expanded ? wrapToolLogDetailLines(message.detail(), toolWidth) : List.of();
            int visualHeight = TOOL_LOG_PADDING_Y * 2 + headerLines.size() * lineStep;
            if (expanded && !detailLines.isEmpty()) {
                visualHeight += TOOL_LOG_DETAIL_GAP + detailLines.size() * lineStep;
            }
            entries.add(new ChatRenderEntry(
                    message,
                    headerLines,
                    detailLines,
                    visualHeight + LINE_SPACING,
                    visualHeight,
                    true,
                    ClientSessionState.MESSAGE_KIND_TOOL_ERROR.equals(message.kind()),
                    expanded
            ));
            continue;
        }

        String line = rolePrefix(message.role()) + message.text();
        List<FormattedCharSequence> lines = this.font.split(Component.literal(line), plainWidth);
        int totalHeight = lines.size() * lineStep + LINE_SPACING;
        entries.add(new ChatRenderEntry(message, lines, List.of(), totalHeight, totalHeight - LINE_SPACING, false, false, false));
    }
    return entries;
}

    private void drawPlainChatEntry(
        GuiGraphics gfx,
        int panelX,
        int messageTop,
        int messageBottom,
        int blockTop,
        ChatRenderEntry entry
) {
    int lineStep = this.font.lineHeight + LINE_SPACING;
    int textX = panelX + PADDING;
    int color = roleColor(entry.message().role());
    for (int lineIndex = 0; lineIndex < entry.headerLines().size(); lineIndex++) {
        int lineY = blockTop + lineIndex * lineStep;
        if (lineY < messageTop || lineY > messageBottom - this.font.lineHeight) {
            continue;
        }
        gfx.drawString(this.font, entry.headerLines().get(lineIndex), textX, lineY, color, true);
    }
}

    private void drawToolLogEntry(
        GuiGraphics gfx,
        int panelX,
        int panelWidth,
        int messageTop,
        int messageBottom,
        int blockTop,
        ChatRenderEntry entry
) {
    int boxLeft = panelX + PADDING;
    int boxRight = panelX + panelWidth - PADDING;
    int visualTop = blockTop;
    int visualBottom = blockTop + entry.visualHeight();
    int fillTop = Math.max(messageTop, visualTop);
    int fillBottom = Math.min(messageBottom, visualBottom);
    if (fillBottom > fillTop) {
        int background = entry.error() ? 0xD0452A22 : 0xD0222C38;
        int border = entry.error() ? 0xCCB77960 : 0xCC7489A6;
        gfx.fill(boxLeft, fillTop, boxRight, fillBottom, background);
        gfx.fill(boxLeft, fillTop, boxRight, fillTop + 1, border);
        gfx.fill(boxLeft, fillBottom - 1, boxRight, fillBottom, border);
    }

    if (entry.message().id() != null && !entry.message().id().isBlank() && fillBottom > fillTop) {
        toolLogToggleAreas.add(new ToolLogToggleArea(entry.message().id(), boxLeft, fillTop, boxRight, fillBottom));
    }

    int lineStep = this.font.lineHeight + LINE_SPACING;
    int textX = boxLeft + TOOL_LOG_PADDING_X;
    int headerY = visualTop + TOOL_LOG_PADDING_Y;
    int summaryColor = entry.error() ? 0xFFD0A2 : 0xDCE8FF;
    for (int lineIndex = 0; lineIndex < entry.headerLines().size(); lineIndex++) {
        int lineY = headerY + lineIndex * lineStep;
        if (lineY < messageTop || lineY > messageBottom - this.font.lineHeight) {
            continue;
        }
        gfx.drawString(this.font, entry.headerLines().get(lineIndex), textX, lineY, summaryColor, true);
    }

    if (!entry.expanded() || entry.detailLines().isEmpty()) {
        return;
    }

    int dividerY = headerY + entry.headerLines().size() * lineStep + 1;
    if (dividerY >= messageTop && dividerY < messageBottom) {
        gfx.fill(boxLeft + TOOL_LOG_PADDING_X, dividerY, boxRight - TOOL_LOG_PADDING_X, dividerY + 1, 0x66FFFFFF);
    }

    int detailY = headerY + entry.headerLines().size() * lineStep + TOOL_LOG_DETAIL_GAP;
    int detailColor = entry.error() ? 0xFFE7D2 : 0xFFBEC9D9;
    for (int lineIndex = 0; lineIndex < entry.detailLines().size(); lineIndex++) {
        int lineY = detailY + lineIndex * lineStep;
        if (lineY < messageTop || lineY > messageBottom - this.font.lineHeight) {
            continue;
        }
        gfx.drawString(this.font, entry.detailLines().get(lineIndex), textX, lineY, detailColor, false);
    }
}

    private List<FormattedCharSequence> wrapToolLogDetailLines(String detail, int contentWidth) {
    List<FormattedCharSequence> lines = new ArrayList<>();
    if (detail == null || detail.isBlank()) {
        return lines;
    }
    String[] rawLines = detail.split("\\R");
    for (String rawLine : rawLines) {
        String normalized = rawLine == null ? "" : rawLine.trim();
        if (normalized.isBlank()) {
            continue;
        }
        lines.addAll(this.font.split(Component.literal("  " + normalized), contentWidth));
    }
    return lines;
}

    private boolean isToolLogMessage(ClientSessionState.ChatMessage message) {
    if (message == null) {
        return false;
    }
    return ClientSessionState.MESSAGE_KIND_TOOL_CALL.equals(message.kind())
            || ClientSessionState.MESSAGE_KIND_TOOL_ERROR.equals(message.kind());
}

    private void pruneExpandedToolLogIds(List<ClientSessionState.ChatMessage> messages) {
    Set<String> validIds = new LinkedHashSet<>();
    for (ClientSessionState.ChatMessage message : messages) {
        if (isToolLogMessage(message) && message.id() != null && !message.id().isBlank()) {
            validIds.add(message.id());
        }
    }
    expandedToolLogIds.retainAll(validIds);
}

    private boolean toggleToolLogAt(double mouseX, double mouseY) {
    for (int index = toolLogToggleAreas.size() - 1; index >= 0; index--) {
        ToolLogToggleArea area = toolLogToggleAreas.get(index);
        if (!area.contains(mouseX, mouseY)) {
            continue;
        }
        if (expandedToolLogIds.contains(area.messageId())) {
            expandedToolLogIds.remove(area.messageId());
        } else {
            expandedToolLogIds.add(area.messageId());
        }
        clampScroll();
        return true;
    }
    return false;
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
        return getSessionVisibleWidth();
    }

    private int getPanelX(int panelWidth) {
        return this.width - panelWidth;
    }

    private int getHeaderHeight(int panelWidth) {
        int maxBottom = PADDING + TOP_BUTTON_HEIGHT;
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(configButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(compactButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(infoButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(applyButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(discardButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(undoButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(redoButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointCreateButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointListButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointPrevButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointNextButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointRollbackButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointModeButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointNameInput));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(checkpointRenameButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(discardReasonInput));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(discardOkButton));
        maxBottom = Math.max(maxBottom, getVisibleWidgetBottom(discardCancelButton));
        return Math.max(TOP_BUTTON_HEIGHT * 3 + 8, maxBottom - PADDING + 6) + CHAT_HEADER_META_HEIGHT;
    }

    private int getVisibleWidgetBottom(AbstractWidget widget) {
        if (widget == null || !widget.visible) {
            return 0;
        }
        return widget.getY() + widget.getHeight();
    }

    private int getInputY() {
        return this.height - INPUT_HEIGHT - PADDING;
    }

    private int getChoicePopupTop() {
        if (!choicePopupVisible || !ClientSessionState.hasPendingChoice() || choicePopupHeight <= 0) {
            return Integer.MAX_VALUE;
        }
        return choicePopupY;
    }

    private int getStatusY() {
        int base = getInputY() - this.font.lineHeight - 4;
        int popupTop = getChoicePopupTop();
        if (popupTop == Integer.MAX_VALUE) {
            return base;
        }
        return Math.min(base, popupTop - this.font.lineHeight - 8);
    }

    private int getMessageTop(int panelWidth) {
        return PADDING + getHeaderHeight(panelWidth);
    }

    private int getMessageBottom() {
        int base = getStatusY() - 4;
        int popupTop = getChoicePopupTop();
        if (popupTop == Integer.MAX_VALUE) {
            return base;
        }
        return Math.min(base, popupTop - 8);
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

    private List<FormattedCharSequence> getPlanLines(int contentWidth) {
        List<ClientSessionState.PlanItem> items = ClientSessionState.getPlanItems();
        String explanation = ClientSessionState.getPlanExplanation();
        if (items.isEmpty() && (explanation == null || explanation.isBlank())) {
            return List.of();
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (explanation != null && !explanation.isBlank()) {
            lines.addAll(this.font.split(Component.literal(explanation.trim()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), contentWidth));
        }
        if (items.isEmpty()) {
            return lines;
        }
        int limit = Math.min(8, items.size());
        for (int i = 0; i < limit; i++) {
            ClientSessionState.PlanItem item = items.get(i);
            String status = item.status() == null ? "pending" : item.status();
            MutableComponent body = Component.literal(item.step());
            if ("completed".equals(status)) {
                body = body.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH);
            } else if ("in_progress".equals(status)) {
                body = body.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
            } else {
                body = body.withStyle(ChatFormatting.GRAY);
            }
            String marker = "completed".equals(status) ? "✔ " : "□ ";
            MutableComponent line = Component.literal(marker).append(body);
            lines.addAll(this.font.split(line, contentWidth));
        }
        int more = items.size() - limit;
        if (more > 0) {
            lines.add(P2SI18n.tr("screen.p2s.common.more", more).getVisualOrderText());
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
            MutableComponent line = Component.literal(marker + " " + shortId(cp.id()) + " ")
                    .append(P2SI18n.checkpointLabelComponent(cp.label()));
            if (cp.revision() != null && !cp.revision().isBlank()) {
                line.append(Component.literal(" [" + cp.revision() + "]"));
            }
            lines.addAll(this.font.split(line, contentWidth));
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
        return P2SI18n.rollbackModeComponent(ClientSessionState.getRollbackMode()).getString();
    }

    private void createCheckpoint() {
        JsonObject payload = new JsonObject();
        String label = checkpointNameInput != null ? checkpointNameInput.getValue().trim() : checkpointNameDraft.trim();
        payload.addProperty("label", label.isEmpty() ? "manual-" + ClientSessionState.getTurnCount() : label);
        sendSessionAction("create_checkpoint", payload.toString());
    }

    private void renameSelectedCheckpoint() {
        ClientSessionState.CheckpointInfo checkpoint = ClientSessionState.getSelectedCheckpoint();
        if (checkpoint == null || checkpoint.id() == null || checkpoint.id().isBlank()) {
            return;
        }
        String label = checkpointNameInput != null ? checkpointNameInput.getValue().trim() : checkpointNameDraft.trim();
        if (label.isEmpty()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("checkpoint_id", checkpoint.id());
        payload.addProperty("label", label);
        sendSessionAction("rename_checkpoint", payload.toString());
    }

    private void enterWorkspaceRenameMode() {
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            return;
        }
        workspaceCreateMode = false;
        workspaceCreateFolderMode = false;
        workspaceCreateDraft = "";
        workspaceRenameMode = true;
        collapsedPanelFocus = PanelFocus.LEFT;
        String selectedName = ClientSessionState.getSelectedWorkspaceLabel();
        workspaceRenameDraft = selectedName == null ? "" : selectedName;
        createWidgets();
        if (workspaceCreateMode && workspaceCreateInput != null) {
            workspaceCreateInput.setFocused(true);
        } else if (workspaceRenameInput != null) {
            workspaceRenameInput.setFocused(true);
        }
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(false);
        }
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.rename_workspace"), 0xAAD5FF);
    }

    private void exitWorkspaceRenameMode() {
        workspaceRenameMode = false;
        workspaceRenameDraft = "";
        createWidgets();
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(true);
        }
    }

    private void confirmWorkspaceRename() {
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            exitWorkspaceRenameMode();
            return;
        }
        String value = workspaceRenameInput != null ? workspaceRenameInput.getValue() : workspaceRenameDraft;
        if (value == null || value.trim().isEmpty()) {
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.workspace_name_empty"), 0xFF5555);
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("path", selectedWorkspacePath);
        payload.addProperty("new_path", value.trim());
        if (!sendSessionAction("workspace_file_rename", payload.toString())) {
            return;
        }
        rememberWorkspaceFoldersForPath(value.trim());
        selectedExplorerFolderPath = parentFolderOfWorkspacePath(value.trim());
        workspaceExpandedSelectionPath = "";
        exitWorkspaceRenameMode();
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.renaming_workspace"), 0xAAAAAA);
    }

    private void deleteSelectedWorkspaceDoc() {
        String selectedWorkspacePath = ClientSessionState.getSelectedWorkspacePath();
        if (selectedWorkspacePath == null || selectedWorkspacePath.isBlank()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("path", selectedWorkspacePath);
        if (!sendSessionAction("workspace_file_delete", payload.toString())) {
            return;
        }
        workspaceRenameMode = false;
        workspaceRenameDraft = "";
        contextLoadedDocId = "";
        clearContextDiffView();
        setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.deleting_workspace"), 0xAAAAAA);
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

    private void syncChoicePopupRequestState() {
        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        String requestId = choice == null ? "" : choice.requestId();
        if (!requestId.equals(activeChoiceRequestId)) {
            activeChoiceRequestId = requestId;
            choiceCustomDraft = "";
            if (choiceCustomInput != null) {
                choiceCustomInput.setValue("");
                choiceCustomInput.setFocused(false);
            }
            choicePopupVisible = !requestId.isBlank();
            if (choicePopupVisible) {
                infoOverlayVisible = false;
                if (input != null) {
                    input.setFocused(false);
                }
                if (choiceCustomInput != null) {
                    choiceCustomInput.setFocused(true);
                }
            }
        }
        if (requestId.isBlank()) {
            activeChoiceRequestId = "";
            choicePopupVisible = false;
        }
    }

    private void refreshChoiceButtons() {
        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        List<ClientSessionState.ChoiceOption> options = choice == null ? List.of() : choice.options();
        boolean hasChoice = !options.isEmpty();
        if (!hasChoice) {
            choicePopupVisible = false;
        }

        if (choiceToggleButton != null) {
            choiceToggleButton.visible = hasChoice;
            choiceToggleButton.active = hasChoice;
            String askLabel = P2SI18n.tr("screen.p2s.chat.choice.ask").getString();
            choiceToggleButton.setMessage(Component.literal(askLabel + (choicePopupVisible ? " ▲" : " ▼")));
        }

        boolean showPopup = hasChoice && choicePopupVisible;
        for (int i = 0; i < choiceButtons.size(); i++) {
            Button button = choiceButtons.get(i);
            if (showPopup && i < options.size()) {
                ClientSessionState.ChoiceOption option = options.get(i);
                button.visible = true;
                button.active = true;
                button.setMessage(Component.literal(choiceButtonLabel(option)));
            } else {
                button.visible = false;
                button.active = false;
                button.setMessage(Component.empty());
            }
        }

        if (choiceCustomInput != null) {
            choiceCustomInput.visible = showPopup;
            if (!showPopup) {
                choiceCustomInput.setFocused(false);
            }
        }
        if (choiceCustomSubmitButton != null) {
            choiceCustomSubmitButton.visible = showPopup;
            choiceCustomSubmitButton.active = showPopup
                    && choiceCustomInput != null
                    && choiceCustomInput.getValue() != null
                    && !choiceCustomInput.getValue().trim().isEmpty();
        }
        if (choicePopupCloseButton != null) {
            choicePopupCloseButton.visible = showPopup;
            choicePopupCloseButton.active = showPopup;
        }
    }

    private String choiceButtonLabel(ClientSessionState.ChoiceOption option) {
        if (option == null) {
            return "";
        }
        String label = option.label() == null ? "" : option.label().trim();
        String description = option.description() == null ? "" : option.description().trim();
        if (description.isBlank()) {
            return label;
        }
        return label + " — " + description;
    }

    private List<FormattedCharSequence> getChoicePopupPromptLines(int contentWidth) {
        ClientSessionState.ChoiceRequest choice = ClientSessionState.getPendingChoice();
        if (choice == null || choice.prompt() == null || choice.prompt().isBlank()) {
            return List.of();
        }
        List<FormattedCharSequence> lines = this.font.split(Component.literal(choice.prompt().trim()), Math.max(40, contentWidth));
        int maxLines = 4;
        if (lines.size() <= maxLines) {
            return lines;
        }
        return new ArrayList<>(lines.subList(0, maxLines));
    }

    private void drawChoicePopup(GuiGraphics gfx) {
        if (!choicePopupVisible || !ClientSessionState.hasPendingChoice() || choicePopupWidth <= 0 || choicePopupHeight <= 0) {
            return;
        }
        int panelWidth = getPanelWidth();
        int panelLeft = getPanelX(panelWidth);
        int panelRight = this.width;
        int left = choicePopupX;
        int top = choicePopupY;
        int right = left + choicePopupWidth;
        int bottom = top + choicePopupHeight;
        gfx.fill(panelLeft, Math.max(0, top - 6), panelRight, Math.min(this.height, bottom + 6), 0xFF0B1118);
        gfx.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF5C7390);
        gfx.fill(left, top, right, bottom, 0xFF111722);
        gfx.fill(left + 1, top + 1, right - 1, bottom - 1, 0xFF161D2A);
        gfx.fill(left, top, right, top + 22, 0xFF1C2635);
        gfx.fill(left, top, right, top + 1, 0xFF8AA6C9);
        gfx.fill(left, bottom - 1, right, bottom, 0xFF4C627F);
        gfx.fill(left, top, left + 1, bottom, 0xFF4C627F);
        gfx.fill(right - 1, top, right, bottom, 0xFF4C627F);

        int titleX = left + 6;
        int titleY = top + 6;
        gfx.drawString(this.font, P2SI18n.tr("screen.p2s.chat.overlay.action_required"), titleX, titleY, 0xFFCC66, false);
        int dividerY = titleY + this.font.lineHeight + 4;
        gfx.fill(left + 6, dividerY, right - 6, dividerY + 1, 0x884C627F);

        int promptY = dividerY + 6;
        List<FormattedCharSequence> promptLines = getChoicePopupPromptLines(choicePopupWidth - 16);
        int lineStep = this.font.lineHeight + 1;
        for (int i = 0; i < promptLines.size(); i++) {
            gfx.drawString(this.font, promptLines.get(i), left + 6, promptY + i * lineStep, 0xE6EEF9, false);
        }
    }

    private boolean isInsideChoicePopup(double mouseX, double mouseY) {
        return choicePopupVisible
                && choicePopupWidth > 0
                && choicePopupHeight > 0
                && mouseX >= choicePopupX
                && mouseX <= choicePopupX + choicePopupWidth
                && mouseY >= choicePopupY
                && mouseY <= choicePopupY + choicePopupHeight;
    }

    private boolean isInsideChoicePopupWidget(double mouseX, double mouseY) {
        if (isInsideWidget(choiceCustomInput, mouseX, mouseY)
                || isInsideWidget(choiceCustomSubmitButton, mouseX, mouseY)
                || isInsideWidget(choicePopupCloseButton, mouseX, mouseY)) {
            return true;
        }
        for (Button button : choiceButtons) {
            if (isInsideWidget(button, mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInsideWidget(AbstractWidget widget, double mouseX, double mouseY) {
        return widget != null
                && widget.visible
                && mouseX >= widget.getX()
                && mouseX <= widget.getX() + widget.getWidth()
                && mouseY >= widget.getY()
                && mouseY <= widget.getY() + widget.getHeight();
    }

    private void toggleChoicePopup() {
        if (!ClientSessionState.hasPendingChoice()) {
            return;
        }
        if (choicePopupVisible) {
            closeChoicePopup();
            return;
        }
        choicePopupVisible = true;
        collapsedPanelFocus = PanelFocus.RIGHT;
        infoOverlayVisible = false;
        if (choiceCustomInput != null) {
            choiceCustomInput.setFocused(true);
        }
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(false);
        }
    }

    private void closeChoicePopup() {
        if (choiceCustomInput != null) {
            choiceCustomDraft = choiceCustomInput.getValue();
            choiceCustomInput.setFocused(false);
        }
        choicePopupVisible = false;
        if (!contextEditorCollapsed && !discardReasonMode && input != null) {
            input.setFocused(true);
        }
        applyCollapsedPanelFocusState();
    }

    private static int roleColor(String role) {
        if (role == null) {
            return 0xFFFFFF;
        }
        if (P2SI18n.isUserRole(role)) {
            return 0xFFFFFF;
        }
        if (P2SI18n.isAssistantRole(role)) {
            return 0x55FF55;
        }
        return 0xAAAAAA;
    }

    private static String rolePrefix(String role) {
        return P2SI18n.rolePrefix(role);
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
        collapsedPanelFocus = PanelFocus.RIGHT;
        if (choicePopupVisible) {
            closeChoicePopup();
        }
        if (discardReasonInput != null) {
            discardReasonInput.setValue("");
            discardReasonInput.setFocused(true);
        }
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(false);
        }
    }

    private void exitDiscardReasonMode() {
        discardReasonMode = false;
        if (discardReasonInput != null) {
            discardReasonInput.setValue("");
            discardReasonInput.setFocused(false);
        }
        if (!contextEditorCollapsed && input != null) {
            input.setFocused(true);
        }
        applyCollapsedPanelFocusState();
    }

    private void confirmDiscard() {
        String reason = discardReasonInput != null ? discardReasonInput.getValue() : "";
        queueNextPendingWorkspaceJump();
        exitDiscardReasonMode();
        ClientAgentManager.submitPatchDiscard(reason);
    }

    private void applySelectedPendingPatch() {
        queueNextPendingWorkspaceJump();
        ClientAgentManager.submitPatchApply();
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
            setContextStatus(P2SI18n.tr("screen.p2s.chat.context.status.sent_with_message"), 0x55FF55);
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
        closeChoicePopup();
        choiceCustomDraft = "";
        ClientAgentManager.submitChoiceSelection(option.id());
    }

    private void submitCustomChoice() {
        String custom = choiceCustomInput != null ? choiceCustomInput.getValue() : choiceCustomDraft;
        if (custom == null || custom.trim().isEmpty()) {
            return;
        }
        closeChoicePopup();
        if (choiceCustomInput != null) {
            choiceCustomInput.setValue("");
        }
        choiceCustomDraft = "";
        ClientAgentManager.submitCustomChoice(custom);
    }

    private boolean sendSessionAction(String action, String payload) {
        return ClientServerBridge.sendSessionAction(action, payload);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record DockLayoutState(
            boolean sessionPanelCollapsed,
            boolean explorerPanelCollapsed,
            boolean contextEditorCollapsed,
            int sessionPanelWidth,
            int explorerPanelWidth,
            int lastSessionPanelWidth,
            int lastExplorerPanelWidth
    ) {
    }

    private record ContextSelectionRange(int startLine, int startColumn, int endLine, int endColumn) {
    }

    private enum PanelFocus {
        NONE,
        LEFT,
        RIGHT
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
        SCRIPT,
        DIFF
    }
}
