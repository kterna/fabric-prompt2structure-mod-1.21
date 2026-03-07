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
    private static final int explorerActionGap = 2;

    private P2SWorkspaceExplorerComponent() {
    }

    static BuildResult build(Host host, Config config) {
        Button createButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.workspace.new_file"), btn -> config.onCreate().run())
                .bounds(config.leftX(), config.row1Y(), 58, config.inputHeight()).build());

        Button renameButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.workspace.rename_short"), btn -> config.onEnterRename().run())
                .bounds(config.leftX() + 60, config.row1Y(), 38, config.inputHeight()).build());

        Button deleteButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.workspace.delete_short"), btn -> config.onDelete().run())
                .bounds(config.leftX() + 100, config.row1Y(), 38, config.inputHeight()).build());

        EditBox renameInput = null;
        Button renameOkButton = null;
        Button renameCancelButton = null;
        int fileY = config.row2Y();

        if (config.renameMode()) {
            int renameInputWidth = config.explorerWidth() - 2 * 20 - explorerActionGap * 2;
            int renameActionWidth = 20;
            renameInput = host.addEditBox(new EditBox(host.font(), config.leftX(), config.row2Y(), renameInputWidth,
                    config.inputHeight(), P2SI18n.tr("screen.p2s.workspace.path")));
            renameInput.setHint(P2SI18n.tr("screen.p2s.workspace.path_hint"));
            renameInput.setValue(config.renameDraft() == null ? "" : config.renameDraft());

            renameOkButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.common.ok"), btn -> config.onConfirmRename().run())
                    .bounds(config.leftX() + renameInputWidth + explorerActionGap, config.row2Y(), renameActionWidth, config.inputHeight()).build());

            renameCancelButton = host.addButton(Button.builder(Component.literal("X"), btn -> config.onCancelRename().run())
                    .bounds(config.leftX() + renameInputWidth + explorerActionGap + renameActionWidth + explorerActionGap,
                            config.row2Y(), renameActionWidth, config.inputHeight()).build());

            fileY = config.row2Y() + config.inputHeight() + explorerActionGap;
        }

        List<Button> fileButtons = new ArrayList<>();
        int fileBottom = host.screenHeight() - config.contextFooterHeight() - PADDING;
        for (ClientSessionState.WorkspaceFileInfo file : config.files()) {
            if (file == null) {
                continue;
            }
            if (fileY + config.inputHeight() > fileBottom) {
                break;
            }
            String fileName = file.path() == null || file.path().isBlank()
                    ? (file.name() == null || file.name().isBlank() ? P2SI18n.tr("screen.p2s.workspace.unnamed").getString() : file.name())
                    : file.path();
            int slash = fileName.lastIndexOf('/');
            String label = slash >= 0 ? fileName.substring(slash + 1) : fileName;
            if (file.hasPendingPatch()) {
                label = label + " *";
            }

            Button fileButton = host.addButton(Button.builder(Component.literal(label), btn -> config.onSwitch().accept(file.path()))
                    .bounds(config.leftX(), fileY, config.explorerWidth(), config.inputHeight()).build());
            fileButton.active = !file.path().equals(config.selectedWorkspacePath());
            fileButtons.add(fileButton);
            fileY += config.inputHeight() + 1;
        }

        return new BuildResult(createButton, renameButton, deleteButton, renameInput, renameOkButton, renameCancelButton, fileButtons);
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
            String selectedWorkspacePath,
            List<ClientSessionState.WorkspaceFileInfo> files,
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
            List<Button> fileButtons
    ) {
    }
}
