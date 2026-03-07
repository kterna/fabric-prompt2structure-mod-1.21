package com.p2s.screen.chat;

import com.p2s.ClientSessionState;
import com.p2s.P2SI18n;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class P2SChatContextWidgets {
    private P2SChatContextWidgets() {
    }

    public interface Host {
        Font font();

        int screenHeight();

        Button addButton(Button button);

        EditBox addEditBox(EditBox editBox);
    }

    public record Config(
            int panelX,
            int explorerWidth,
            int padding,
            int inputHeight,
            int contextFooterHeight,
            int contextRowHeight,
            int contextRowGap,
            int contextEditorPadding,
            int editorMinWidth,
            int splitGap,
            boolean explorerPanelCollapsed,
            boolean workspaceCreateMode,
            boolean workspaceCreateFolderMode,
            boolean workspaceRenameMode,
            boolean inlineDiffMode,
            boolean selectedWorkspacePending,
            String workspaceCreateDraft,
            String workspaceRenameDraft,
            String selectedWorkspacePath,
            String selectedWorkspaceFolderPath,
            List<ClientSessionState.WorkspaceFileInfo> workspaceFiles,
            List<String> workspaceFolders,
            Set<String> collapsedWorkspaceFolders,
            Runnable onEnterWorkspaceCreateFileMode,
            Runnable onEnterWorkspaceCreateFolderMode,
            Runnable onEnterWorkspaceRenameMode,
            Runnable onDeleteSelectedWorkspaceDoc,
            Consumer<String> onSwitchWorkspaceDoc,
            Consumer<String> onToggleWorkspaceFolder,
            Runnable onConfirmWorkspaceCreate,
            Runnable onExitWorkspaceCreateMode,
            Runnable onConfirmWorkspaceRename,
            Runnable onExitWorkspaceRenameMode,
            Runnable onLoadWorkspaceDiff,
            Runnable onFetchWorkspaceScript,
            Runnable onSaveWorkspaceScript,
            Runnable onClearContextQueue,
            Runnable onFormatContextJson,
            Runnable onClearContextJson,
            Runnable onApplyPatch,
            Runnable onEnterDiscardReasonMode,
            Runnable onNavigateDiffPrev,
            Runnable onNavigateDiffNext
    ) {
    }

    public record BuildResult(
            int currentExplorerSplitterX,
            int contextVisibleRows,
            int contextEditorX,
            int contextEditorY,
            int contextEditorWidth,
            int contextEditorHeight,
            int contextQueueTopY,
            Button workspaceDocCreateButton,
            Button workspaceFolderCreateButton,
            Button workspaceRenameButton,
            Button workspaceDeleteButton,
            EditBox workspaceCreateInput,
            Button workspaceCreateOkButton,
            Button workspaceCreateCancelButton,
            EditBox workspaceRenameInput,
            Button workspaceRenameOkButton,
            Button workspaceRenameCancelButton,
            List<Button> workspaceDocButtons,
            Button contextTabScriptButton,
            Button contextTabDiffButton,
            Button contextLoadButton,
            Button contextSaveButton,
            Button contextClearQueueButton,
            Button contextFormatButton,
            Button contextClearJsonButton,
            Button contextApplyButton,
            Button contextDiscardButton,
            Button contextDiffPrevButton,
            Button contextDiffNextButton
    ) {
    }

    public static BuildResult build(Host host, Config config) {
        int leftX = config.padding();
        int leftWidth = Math.max(100, config.panelX() - config.padding() * 2);
        int currentExplorerSplitterX = leftX + config.explorerWidth();
        int editorXBase = leftX + config.explorerWidth() + config.splitGap();
        int editorWidth = Math.max(config.editorMinWidth(), leftWidth - config.explorerWidth() - config.splitGap());
        int rowGap = 2;
        int row1Y = config.padding();
        int row2Y = row1Y + config.inputHeight() + rowGap;

        List<Button> workspaceDocButtons = new ArrayList<>();
        Button workspaceDocCreateButton = null;
        Button workspaceFolderCreateButton = null;
        Button workspaceRenameButton = null;
        Button workspaceDeleteButton = null;
        EditBox workspaceCreateInput = null;
        Button workspaceCreateOkButton = null;
        Button workspaceCreateCancelButton = null;
        EditBox workspaceRenameInput = null;
        Button workspaceRenameOkButton = null;
        Button workspaceRenameCancelButton = null;

        if (!config.explorerPanelCollapsed()) {
            P2SWorkspaceExplorerComponent.BuildResult explorer = P2SWorkspaceExplorerComponent.build(
                    new P2SWorkspaceExplorerComponent.Host() {
                        @Override
                        public Font font() {
                            return host.font();
                        }

                        @Override
                        public int screenHeight() {
                            return host.screenHeight();
                        }

                        @Override
                        public Button addButton(Button button) {
                            return host.addButton(button);
                        }

                        @Override
                        public EditBox addEditBox(EditBox editBox) {
                            return host.addEditBox(editBox);
                        }
                    },
                    new P2SWorkspaceExplorerComponent.Config(
                            leftX,
                            row1Y,
                            row2Y,
                            config.explorerWidth(),
                            config.inputHeight(),
                            config.contextFooterHeight(),
                            config.workspaceCreateMode(),
                            config.workspaceCreateFolderMode(),
                            config.workspaceRenameMode(),
                            config.workspaceCreateDraft(),
                            config.workspaceRenameDraft(),
                            config.selectedWorkspacePath(),
                            config.selectedWorkspaceFolderPath(),
                            config.workspaceFiles(),
                            config.workspaceFolders(),
                            config.collapsedWorkspaceFolders(),
                            config.onEnterWorkspaceCreateFileMode(),
                            config.onEnterWorkspaceCreateFolderMode(),
                            config.onEnterWorkspaceRenameMode(),
                            config.onDeleteSelectedWorkspaceDoc(),
                            config.onSwitchWorkspaceDoc(),
                            config.onToggleWorkspaceFolder(),
                            config.onConfirmWorkspaceCreate(),
                            config.onExitWorkspaceCreateMode(),
                            config.onConfirmWorkspaceRename(),
                            config.onExitWorkspaceRenameMode()
                    )
            );
            workspaceDocCreateButton = explorer.createFileButton();
            workspaceFolderCreateButton = explorer.createFolderButton();
            workspaceRenameButton = explorer.renameButton();
            workspaceDeleteButton = explorer.deleteButton();
            workspaceCreateInput = explorer.createInput();
            workspaceCreateOkButton = explorer.createOkButton();
            workspaceCreateCancelButton = explorer.createCancelButton();
            workspaceRenameInput = explorer.renameInput();
            workspaceRenameOkButton = explorer.renameOkButton();
            workspaceRenameCancelButton = explorer.renameCancelButton();
            workspaceDocButtons.addAll(explorer.fileButtons());
        }

        int row1ButtonCount = 5;
        int row1ButtonWidth = Math.max(40, (editorWidth - rowGap * (row1ButtonCount - 1)) / row1ButtonCount);
        int row1X = editorXBase;

        Button contextLoadButton = host.addButton(Button.builder(P2SI18n.tr(config.selectedWorkspacePending() || config.inlineDiffMode()
                        ? "screen.p2s.common.refresh"
                        : "screen.p2s.chat.context.fetch"), btn -> {
                    if (config.selectedWorkspacePending() || config.inlineDiffMode()) {
                        config.onLoadWorkspaceDiff().run();
                    } else {
                        config.onFetchWorkspaceScript().run();
                    }
                })
                .bounds(row1X, row1Y, row1ButtonWidth, config.inputHeight()).build());
        row1X += row1ButtonWidth + rowGap;

        Button contextSaveButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.common.save"), btn -> config.onSaveWorkspaceScript().run())
                .bounds(row1X, row1Y, row1ButtonWidth, config.inputHeight()).build());
        row1X += row1ButtonWidth + rowGap;

        Button contextFormatButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.context.format"), btn -> config.onFormatContextJson().run())
                .bounds(row1X, row1Y, row1ButtonWidth, config.inputHeight()).build());
        row1X += row1ButtonWidth + rowGap;

        Button contextClearJsonButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.context.clear"), btn -> config.onClearContextJson().run())
                .bounds(row1X, row1Y, row1ButtonWidth, config.inputHeight()).build());
        row1X += row1ButtonWidth + rowGap;

        Button contextClearQueueButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.context.clear_queue"), btn -> config.onClearContextQueue().run())
                .bounds(row1X, row1Y, row1ButtonWidth, config.inputHeight()).build());

        int row2ButtonCount = 4;
        int row2ButtonWidth = Math.max(40, (editorWidth - rowGap * (row2ButtonCount - 1)) / row2ButtonCount);
        int row2X = editorXBase;

        Button contextApplyButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.apply"), btn -> config.onApplyPatch().run())
                .bounds(row2X, row2Y, row2ButtonWidth, config.inputHeight()).build());
        row2X += row2ButtonWidth + rowGap;

        Button contextDiscardButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.discard"), btn -> config.onEnterDiscardReasonMode().run())
                .bounds(row2X, row2Y, row2ButtonWidth, config.inputHeight()).build());
        row2X += row2ButtonWidth + rowGap;

        Button contextDiffPrevButton = host.addButton(Button.builder(net.minecraft.network.chat.Component.literal("<D"), btn -> config.onNavigateDiffPrev().run())
                .bounds(row2X, row2Y, row2ButtonWidth, config.inputHeight()).build());
        row2X += row2ButtonWidth + rowGap;

        Button contextDiffNextButton = host.addButton(Button.builder(net.minecraft.network.chat.Component.literal("D>"), btn -> config.onNavigateDiffNext().run())
                .bounds(row2X, row2Y, row2ButtonWidth, config.inputHeight()).build());

        int editorTop = row2Y + config.inputHeight() + 4;
        int editorBottom = host.screenHeight() - config.contextFooterHeight();
        int available = Math.max(0, editorBottom - editorTop);
        int rowStep = config.contextRowHeight() + config.contextRowGap();
        int contextVisibleRows = Math.max(4, Math.min(40, available / rowStep));
        int renderedRows = Math.max(1, contextVisibleRows);
        int contextEditorX = editorXBase;
        int contextEditorY = editorTop;
        int contextEditorWidth = editorWidth;
        int contextEditorHeight = renderedRows * rowStep - config.contextRowGap() + config.contextEditorPadding() * 2;
        int contextQueueTopY = contextEditorY + contextEditorHeight + 6;

        return new BuildResult(
                currentExplorerSplitterX,
                contextVisibleRows,
                contextEditorX,
                contextEditorY,
                contextEditorWidth,
                contextEditorHeight,
                contextQueueTopY,
                workspaceDocCreateButton,
                workspaceFolderCreateButton,
                workspaceRenameButton,
                workspaceDeleteButton,
                workspaceCreateInput,
                workspaceCreateOkButton,
                workspaceCreateCancelButton,
                workspaceRenameInput,
                workspaceRenameOkButton,
                workspaceRenameCancelButton,
                workspaceDocButtons,
                null,
                null,
                contextLoadButton,
                contextSaveButton,
                contextClearQueueButton,
                contextFormatButton,
                contextClearJsonButton,
                contextApplyButton,
                contextDiscardButton,
                contextDiffPrevButton,
                contextDiffNextButton
        );
    }
}
