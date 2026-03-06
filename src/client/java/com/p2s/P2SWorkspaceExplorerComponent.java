package com.p2s;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class P2SWorkspaceExplorerComponent {
    private static final int PADDING = 8;

    private P2SWorkspaceExplorerComponent() {
    }

    static BuildResult build(Host host, Config config) {
        int explorerActionGap = 2;
        int explorerRenameWidth = 42;
        int explorerDeleteWidth = 42;
        int explorerCreateWidth = Math.max(72, config.explorerWidth() - explorerRenameWidth - explorerDeleteWidth - explorerActionGap * 2);

        Button createButton = host.addButton(Button.builder(Component.literal("+ New File"), btn -> config.onCreate().run())
                .bounds(config.leftX(), config.row1Y(), explorerCreateWidth, config.inputHeight()).build());

        Button renameButton = host.addButton(Button.builder(Component.literal("Ren"), btn -> config.onEnterRename().run())
                .bounds(config.leftX() + explorerCreateWidth + explorerActionGap, config.row1Y(), explorerRenameWidth, config.inputHeight()).build());

        Button deleteButton = host.addButton(Button.builder(Component.literal("Del"), btn -> config.onDelete().run())
                .bounds(config.leftX() + explorerCreateWidth + explorerActionGap + explorerRenameWidth + explorerActionGap,
                        config.row1Y(), explorerDeleteWidth, config.inputHeight()).build());

        boolean hasSelectedWorkspace = config.selectedWorkspaceId() != null && !config.selectedWorkspaceId().isBlank();
        ClientSessionState.WorkspaceDocInfo selectedWorkspace = null;
        for (ClientSessionState.WorkspaceDocInfo doc : config.docs()) {
            if (doc != null && doc.id().equals(config.selectedWorkspaceId())) {
                selectedWorkspace = doc;
                break;
            }
        }

        renameButton.active = hasSelectedWorkspace && !config.renameMode();
        deleteButton.active = hasSelectedWorkspace
                && config.docs().size() > 1
                && selectedWorkspace != null
                && !selectedWorkspace.hasPendingPatch()
                && !config.renameMode();

        EditBox renameInput = null;
        Button renameOkButton = null;
        Button renameCancelButton = null;
        int docY = config.row1Y() + config.inputHeight() + explorerActionGap;
        if (config.renameMode()) {
            int renameActionWidth = 22;
            int renameInputWidth = Math.max(60, config.explorerWidth() - renameActionWidth * 2 - explorerActionGap * 2);
            renameInput = host.addEditBox(new EditBox(host.font(), config.leftX(), config.row2Y(), renameInputWidth,
                    config.inputHeight(), Component.literal("workspace path")));
            renameInput.setMaxLength(256);
            renameInput.setHint(Component.literal("workspace/file.json"));
            renameInput.setValue(config.renameDraft() == null ? "" : config.renameDraft());

            renameOkButton = host.addButton(Button.builder(Component.literal("OK"), btn -> config.onConfirmRename().run())
                    .bounds(config.leftX() + renameInputWidth + explorerActionGap, config.row2Y(), renameActionWidth, config.inputHeight()).build());

            renameCancelButton = host.addButton(Button.builder(Component.literal("X"), btn -> config.onCancelRename().run())
                    .bounds(config.leftX() + renameInputWidth + explorerActionGap + renameActionWidth + explorerActionGap,
                            config.row2Y(), renameActionWidth, config.inputHeight()).build());

            docY = config.row2Y() + config.inputHeight() + explorerActionGap;
        }

        List<Button> docButtons = new ArrayList<>();
        int docBottom = host.screenHeight() - config.contextFooterHeight() - PADDING;
        for (ClientSessionState.WorkspaceDocInfo doc : config.docs()) {
            if (doc == null) {
                continue;
            }
            if (docY + config.inputHeight() > docBottom) {
                break;
            }
            String docName = doc.path() == null || doc.path().isBlank()
                    ? (doc.name() == null || doc.name().isBlank() ? doc.id() : doc.name())
                    : doc.path();
            int slash = docName.lastIndexOf('/');
            String label = slash >= 0 ? docName.substring(slash + 1) : docName;
            if (doc.hasPendingPatch()) {
                label = label + " *";
            }

            Button docButton = host.addButton(Button.builder(Component.literal(label), btn -> config.onSwitch().accept(doc.id()))
                    .bounds(config.leftX(), docY, config.explorerWidth(), config.inputHeight()).build());
            docButton.active = !doc.id().equals(config.selectedWorkspaceId());
            docButtons.add(docButton);
            docY += config.inputHeight() + 1;
        }

        return new BuildResult(createButton, renameButton, deleteButton, renameInput, renameOkButton, renameCancelButton, docButtons);
    }

    interface Host {
        Font font();

        int screenHeight();

        Button addButton(Button button);

        EditBox addEditBox(EditBox editBox);
    }

    record Config(
            int leftX,
            int row1Y,
            int row2Y,
            int explorerWidth,
            int inputHeight,
            int contextFooterHeight,
            boolean renameMode,
            String renameDraft,
            String selectedWorkspaceId,
            List<ClientSessionState.WorkspaceDocInfo> docs,
            Runnable onCreate,
            Runnable onEnterRename,
            Runnable onDelete,
            Consumer<String> onSwitch,
            Runnable onConfirmRename,
            Runnable onCancelRename
    ) {
    }

    record BuildResult(
            Button createButton,
            Button renameButton,
            Button deleteButton,
            EditBox renameInput,
            Button renameOkButton,
            Button renameCancelButton,
            List<Button> docButtons
    ) {
    }
}
