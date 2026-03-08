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
    private static final int CHOICE_TOGGLE_WIDTH = 52;
    private static final int CHOICE_POPUP_PADDING = 6;
    private static final int CHOICE_POPUP_GAP = 4;
    private static final int CHOICE_POPUP_PROMPT_LINES = 4;
    private static final int CHOICE_POPUP_CLOSE_SIZE = 18;
    private static final int CHOICE_POPUP_SUBMIT_WIDTH = 44;

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
            boolean choicePopupVisible,
            String inputDraft,
            String discardReasonDraft,
            String checkpointNameDraft,
            String choiceCustomDraft,
            String rollbackModeLabel,
            Runnable onSendMessage,
            Runnable onOpenProjects,
            Runnable onOpenSessions,
            Runnable onNewSession,
            Runnable onCompactHistory,
            Runnable onToggleInfo,
            Runnable onOpenConfig,
            Runnable onOpenCheckpoints,
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
            Runnable onToggleChoicePopup,
            IntConsumer onSubmitChoice,
            Runnable onSubmitCustomChoice,
            Runnable onCloseChoicePopup,
            Runnable onConfirmDiscard,
            Runnable onExitDiscardMode
    ) {
    }

    public record BuildResult(
            EditBox input,
            Button sendButton,
            Button compactButton,
            Button configButton,
            Button applyButton,
            Button discardButton,
            Button undoButton,
            Button redoButton,
            Button checkpointCreateButton,
            Button checkpointListButton,
            Button checkpointPrevButton,
            Button checkpointNextButton,
            Button checkpointRollbackButton,
            Button checkpointModeButton,
            EditBox checkpointNameInput,
            Button checkpointRenameButton,
            Button infoButton,
            Button choiceToggleButton,
            EditBox choiceCustomInput,
            Button choiceCustomSubmitButton,
            Button choicePopupCloseButton,
            int choicePopupX,
            int choicePopupY,
            int choicePopupWidth,
            int choicePopupHeight,
            EditBox discardReasonInput,
            Button discardOkButton,
            Button discardCancelButton,
            List<Button> choiceButtons
    ) {
    }

    public static BuildResult build(Host host, Config config) {
        int inputLeft = config.panelX() + config.padding();
        int sendX = config.panelX() + config.panelWidth() - config.padding() - config.buttonWidth();
        int choiceToggleX = sendX - CHOICE_TOGGLE_WIDTH - 4;
        int inputWidth = Math.max(60, choiceToggleX - 4 - inputLeft);

        EditBox input = host.addEditBox(new EditBox(host.font(),
                inputLeft,
                config.inputY(),
                inputWidth,
                config.inputHeight(),
                Component.empty()));
        input.setMaxLength(512);
        input.setValue(config.inputDraft() == null ? "" : config.inputDraft());
        input.setFocused(!config.contextEditorFocused());

        Button choiceToggleButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.choice.ask"), btn -> config.onToggleChoicePopup().run())
                .bounds(choiceToggleX, config.inputY(), CHOICE_TOGGLE_WIDTH, config.inputHeight())
                .build());
        choiceToggleButton.visible = false;
        choiceToggleButton.active = false;

        Button sendButton = host.addButton(Button.builder(Component.literal(">"), btn -> config.onSendMessage().run())
                .bounds(sendX, config.inputY(), config.buttonWidth(), config.inputHeight())
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

        int configWidth = 56;
        int compactWidth = 64;
        Button configButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.config"), btn -> config.onOpenConfig().run())
                .bounds(config.panelX() + config.panelWidth() - config.padding() - configWidth, topRowY, configWidth, config.inputHeight())
                .build());

        Button compactButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.compact"), btn -> config.onCompactHistory().run())
                .bounds(config.panelX() + config.panelWidth() - config.padding() - configWidth - compactWidth - 4, topRowY, compactWidth, config.inputHeight())
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
                .bounds(checkpointX, checkpointY, 40, config.topButtonHeight())
                .build());

        Button checkpointListButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.checkpoint.list_short"), btn -> config.onOpenCheckpoints().run())
                .bounds(checkpointX + 42, checkpointY, 40, config.topButtonHeight())
                .build());

        int promptLineStep = host.font().lineHeight + 1;
        int promptHeight = CHOICE_POPUP_PROMPT_LINES * promptLineStep;
        int choicePopupWidth = config.panelWidth() - config.padding() * 2;
        int optionAreaHeight = config.choiceButtonCount() * config.topButtonHeight() + Math.max(0, config.choiceButtonCount() - 1) * CHOICE_POPUP_GAP;
        int choicePopupHeight = CHOICE_POPUP_PADDING
                + CHOICE_POPUP_CLOSE_SIZE
                + 2
                + promptHeight
                + 8
                + optionAreaHeight
                + 8
                + config.inputHeight()
                + CHOICE_POPUP_PADDING;
        int choicePopupX = config.panelX() + config.padding();
        int choicePopupY = Math.max(checkpointY + config.topButtonHeight() + 6, config.inputY() - choicePopupHeight - 6);
        int popupInnerX = choicePopupX + CHOICE_POPUP_PADDING;
        int popupInnerWidth = choicePopupWidth - CHOICE_POPUP_PADDING * 2;
        int closeButtonX = choicePopupX + choicePopupWidth - CHOICE_POPUP_PADDING - CHOICE_POPUP_CLOSE_SIZE;
        int closeButtonY = choicePopupY + CHOICE_POPUP_PADDING;

        Button choicePopupCloseButton = host.addButton(Button.builder(Component.literal("X"), btn -> config.onCloseChoicePopup().run())
                .bounds(closeButtonX, closeButtonY, CHOICE_POPUP_CLOSE_SIZE, CHOICE_POPUP_CLOSE_SIZE)
                .build());
        choicePopupCloseButton.visible = config.choicePopupVisible();
        choicePopupCloseButton.active = config.choicePopupVisible();

        List<Button> choiceButtons = new ArrayList<>();
        int optionY = closeButtonY + CHOICE_POPUP_CLOSE_SIZE + 2 + promptHeight + 8;
        for (int i = 0; i < config.choiceButtonCount(); i++) {
            final int index = i;
            Button choiceButton = host.addButton(Button.builder(Component.empty(), btn -> config.onSubmitChoice().accept(index))
                    .bounds(popupInnerX, optionY + i * (config.topButtonHeight() + CHOICE_POPUP_GAP), popupInnerWidth, config.topButtonHeight())
                    .build());
            choiceButton.visible = false;
            choiceButton.active = false;
            choiceButtons.add(choiceButton);
        }

        int customY = optionY + optionAreaHeight + 8;
        int customInputWidth = popupInnerWidth - CHOICE_POPUP_SUBMIT_WIDTH - CHOICE_POPUP_GAP;
        EditBox choiceCustomInput = host.addEditBox(new EditBox(host.font(), popupInnerX, customY, customInputWidth,
                config.inputHeight(), Component.empty()));
        choiceCustomInput.setMaxLength(256);
        choiceCustomInput.setHint(P2SI18n.tr("screen.p2s.chat.choice.custom_hint"));
        choiceCustomInput.setValue(config.choiceCustomDraft() == null ? "" : config.choiceCustomDraft());
        choiceCustomInput.visible = config.choicePopupVisible();

        Button choiceCustomSubmitButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.chat.choice.use"), btn -> config.onSubmitCustomChoice().run())
                .bounds(popupInnerX + customInputWidth + CHOICE_POPUP_GAP, customY, CHOICE_POPUP_SUBMIT_WIDTH, config.inputHeight())
                .build());
        choiceCustomSubmitButton.visible = config.choicePopupVisible();
        choiceCustomSubmitButton.active = config.choicePopupVisible();

        int discardRowY = checkpointY + config.topButtonHeight() + 2;
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
                compactButton,
                configButton,
                null,
                null,
                undoButton,
                redoButton,
                checkpointCreateButton,
                checkpointListButton,
                null,
                null,
                null,
                null,
                null,
                null,
                infoButton,
                choiceToggleButton,
                choiceCustomInput,
                choiceCustomSubmitButton,
                choicePopupCloseButton,
                choicePopupX,
                choicePopupY,
                choicePopupWidth,
                choicePopupHeight,
                discardReasonInput,
                discardOkButton,
                discardCancelButton,
                choiceButtons
        );
    }
}
