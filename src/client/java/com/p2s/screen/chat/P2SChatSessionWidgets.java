package com.p2s.screen.chat;

import com.p2s.P2SI18n;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public final class P2SChatSessionWidgets {
    private P2SChatSessionWidgets() {
    }

    public interface Host {
        Font font();

        Button addButton(Button button);

        EditBox addEditBox(EditBox editBox);
    }

    public record Config(
            int panelX,
            int panelWidth,
            int padding,
            int inputHeight,
            int buttonWidth,
            int topButtonHeight,
            int smallButtonWidth,
            int choiceButtonCount,
            int inputY,
            boolean contextEditorFocused,
            String inputDraft,
            String discardReasonDraft,
            String checkpointNameDraft,
            String rollbackModeLabel,
            Runnable onSendMessage,
            Runnable onOpenProjects,
            Runnable onOpenSessions,
            Runnable onNewSession,
            Runnable onToggleInfo,
            Runnable onOpenConfig,
            Runnable onApplyPatch,
            Runnable onEnterDiscardReasonMode,
            Runnable onUndo,
            Runnable onRedo,
            Runnable onCreateCheckpoint,
            Runnable onSelectPreviousCheckpoint,
            Runnable onSelectNextCheckpoint,
            Runnable onRollbackCheckpoint,
            Runnable onRenameCheckpoint,
            Runnable onToggleRollbackMode,
            IntConsumer onSubmitChoice,
            Runnable onConfirmDiscard,
            Runnable onExitDiscardMode
    ) {
    }

    public record BuildResult(
            EditBox input,
            Button sendButton,
            Button configButton,
            Button applyButton,
            Button discardButton,
            Button undoButton,
            Button redoButton,
            Button checkpointCreateButton,
            Button checkpointPrevButton,
            Button checkpointNextButton,
            Button checkpointRollbackButton,
            Button checkpointModeButton,
            EditBox checkpointNameInput,
            Button checkpointRenameButton,
            Button infoButton,
            EditBox discardReasonInput,
            Button discardOkButton,
            Button discardCancelButton,
            List<Button> choiceButtons
    ) {
    }

    public static BuildResult build(Host host, Config config) {
        int inputWidth = config.panelWidth() - config.padding() * 2 - config.buttonWidth() - 4;

        EditBox input = host.addEditBox(new EditBox(host.font(),
                config.panelX() + config.padding(),
                config.inputY(),
                inputWidth,
                config.inputHeight(),
                Component.empty()));
        input.setMaxLength(512);
        input.setValue(config.inputDraft() == null ? "" : config.inputDraft());
        input.setFocused(!config.contextEditorFocused());

        Button sendButton = host.addButton(Button.builder(Component.literal(">"), btn -> config.onSendMessage().run())
                .bounds(config.panelX() + config.padding() + inputWidth + 4, config.inputY(), config.buttonWidth(), config.inputHeight())
                .build());

        int topRowY = config.padding();
        int navX = config.panelX() + config.padding();

        host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.projects"), btn -> config.onOpenProjects().run())
                .bounds(navX, topRowY, 60, config.inputHeight())
                .build());
        navX += 64;

        host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.sessions"), btn -> config.onOpenSessions().run())
                .bounds(navX, topRowY, 60, config.inputHeight())
                .build());
        navX += 64;

        host.addButton(Button.builder(P2SI18n.tr("screen.p2s.common.new"), btn -> config.onNewSession().run())
                .bounds(navX, topRowY, 40, config.inputHeight())
                .build());
        navX += 44;

        Button infoButton = host.addButton(Button.builder(Component.literal("[i]"), btn -> config.onToggleInfo().run())
                .bounds(navX, topRowY, 32, config.inputHeight())
                .build());

        Button configButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.config"), btn -> config.onOpenConfig().run())
                .bounds(config.panelX() + config.panelWidth() - config.padding() - 56, topRowY, 56, config.inputHeight())
                .build());

        int rowY = config.padding() + config.topButtonHeight() + 4;
        int actionWidth = config.smallButtonWidth() * 2 + 2;
        int rowStart = config.panelX() + config.panelWidth() - config.padding() - actionWidth;

        Button undoButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.undo"), btn -> config.onUndo().run())
                .bounds(rowStart, rowY, config.smallButtonWidth(), config.topButtonHeight())
                .build());

        Button redoButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.redo"), btn -> config.onRedo().run())
                .bounds(rowStart + config.smallButtonWidth() + 2, rowY, config.smallButtonWidth(), config.topButtonHeight())
                .build());

        int checkpointY = rowY + config.topButtonHeight() + 2;
        int checkpointX = config.panelX() + config.padding();
        Button checkpointCreateButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.create_short"), btn -> config.onCreateCheckpoint().run())
                .bounds(checkpointX, checkpointY, 36, config.topButtonHeight())
                .build());

        Button checkpointPrevButton = host.addButton(Button.builder(Component.literal("<"), btn -> config.onSelectPreviousCheckpoint().run())
                .bounds(checkpointX + 38, checkpointY, 20, config.topButtonHeight())
                .build());

        Button checkpointNextButton = host.addButton(Button.builder(Component.literal(">"), btn -> config.onSelectNextCheckpoint().run())
                .bounds(checkpointX + 60, checkpointY, 20, config.topButtonHeight())
                .build());

        Button checkpointRollbackButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.rollback_short"), btn -> config.onRollbackCheckpoint().run())
                .bounds(checkpointX + 82, checkpointY, 30, config.topButtonHeight())
                .build());

        Button checkpointModeButton = host.addButton(Button.builder(Component.literal(config.rollbackModeLabel()), btn -> config.onToggleRollbackMode().run())
                .bounds(checkpointX + 114, checkpointY, 54, config.topButtonHeight())
                .build());

        List<Button> choiceButtons = new ArrayList<>();
        int choiceY = checkpointY + config.topButtonHeight() + 2;
        int choiceGap = 2;
        int choiceWidth = (actionWidth - choiceGap * (config.choiceButtonCount() - 1)) / config.choiceButtonCount();

        int checkpointAvailableWidth = Math.max(0, rowStart - checkpointX - 4);
        int checkpointRenameWidth = Math.min(42, Math.max(30, checkpointAvailableWidth / 3));
        int checkpointNameWidth = Math.max(48, checkpointAvailableWidth - checkpointRenameWidth - 4);
        EditBox checkpointNameInput = host.addEditBox(new EditBox(host.font(), checkpointX, choiceY, checkpointNameWidth,
                config.inputHeight(), Component.empty()));
        checkpointNameInput.setMaxLength(120);
        checkpointNameInput.setHint(P2SI18n.tr("screen.p2s.chat.checkpoint.name_hint"));
        checkpointNameInput.setValue(config.checkpointNameDraft() == null ? "" : config.checkpointNameDraft());

        Button checkpointRenameButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.rename_short"), btn -> config.onRenameCheckpoint().run())
                .bounds(checkpointX + checkpointNameWidth + 4, choiceY, checkpointRenameWidth, config.topButtonHeight())
                .build());

        for (int i = 0; i < config.choiceButtonCount(); i++) {
            final int index = i;
            Button choiceButton = host.addButton(Button.builder(Component.empty(), btn -> config.onSubmitChoice().accept(index))
                    .bounds(rowStart + i * (choiceWidth + choiceGap), choiceY, choiceWidth, config.topButtonHeight())
                    .build());
            choiceButton.visible = false;
            choiceButton.active = false;
            choiceButtons.add(choiceButton);
        }

        int discardRowY = choiceY + config.topButtonHeight() + 2;
        int discardInputWidth = actionWidth - config.buttonWidth() * 2 - 8;
        EditBox discardReasonInput = host.addEditBox(new EditBox(host.font(), rowStart, discardRowY, discardInputWidth,
                config.inputHeight(), Component.empty()));
        discardReasonInput.setMaxLength(256);
        discardReasonInput.setHint(P2SI18n.tr("screen.p2s.chat.discard_reason_hint"));
        discardReasonInput.setValue(config.discardReasonDraft() == null ? "" : config.discardReasonDraft());
        discardReasonInput.visible = false;

        Button discardOkButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.common.ok"), btn -> config.onConfirmDiscard().run())
                .bounds(rowStart + discardInputWidth + 4, discardRowY, config.buttonWidth(), config.inputHeight())
                .build());
        discardOkButton.visible = false;

        Button discardCancelButton = host.addButton(Button.builder(Component.literal("X"), btn -> config.onExitDiscardMode().run())
                .bounds(rowStart + discardInputWidth + config.buttonWidth() + 8, discardRowY, config.buttonWidth(), config.inputHeight())
                .build());
        discardCancelButton.visible = false;

        return new BuildResult(
                input,
                sendButton,
                configButton,
                null,
                null,
                undoButton,
                redoButton,
                checkpointCreateButton,
                checkpointPrevButton,
                checkpointNextButton,
                checkpointRollbackButton,
                checkpointModeButton,
                checkpointNameInput,
                checkpointRenameButton,
                infoButton,
                discardReasonInput,
                discardOkButton,
                discardCancelButton,
                choiceButtons
        );
    }
}
