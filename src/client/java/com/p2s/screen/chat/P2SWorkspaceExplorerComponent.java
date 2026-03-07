package com.p2s.screen.chat;

import com.p2s.ClientSessionState;
import com.p2s.P2SI18n;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class P2SWorkspaceExplorerComponent {
    private static final int PADDING = 8;
    private static final int EXPLORER_ACTION_GAP = 2;

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
            int renameInputWidth = config.explorerWidth() - 2 * 20 - EXPLORER_ACTION_GAP * 2;
            int renameActionWidth = 20;
            renameInput = host.addEditBox(new EditBox(host.font(), config.leftX(), config.row2Y(), renameInputWidth,
                    config.inputHeight(), P2SI18n.tr("screen.p2s.workspace.path")));
            renameInput.setHint(P2SI18n.tr("screen.p2s.workspace.path_hint"));
            renameInput.setValue(config.renameDraft() == null ? "" : config.renameDraft());

            renameOkButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.common.ok"), btn -> config.onConfirmRename().run())
                    .bounds(config.leftX() + renameInputWidth + EXPLORER_ACTION_GAP, config.row2Y(), renameActionWidth, config.inputHeight()).build());

            renameCancelButton = host.addButton(Button.builder(Component.literal("X"), btn -> config.onCancelRename().run())
                    .bounds(config.leftX() + renameInputWidth + EXPLORER_ACTION_GAP + renameActionWidth + EXPLORER_ACTION_GAP,
                            config.row2Y(), renameActionWidth, config.inputHeight()).build());

            fileY = config.row2Y() + config.inputHeight() + EXPLORER_ACTION_GAP;
        }

        List<Button> fileButtons = new ArrayList<>();
        int fileBottom = host.screenHeight() - config.contextFooterHeight() - PADDING;
        for (ExplorerRow row : buildExplorerRows(config.files(), config.collapsedFolders())) {
            if (row == null) {
                continue;
            }
            if (fileY + config.inputHeight() > fileBottom) {
                break;
            }

            Button rowButton = host.addButton(Button.builder(Component.literal(row.label()), btn -> {
                if (row.folder()) {
                    config.onToggleFolder().accept(row.path());
                } else {
                    config.onSwitch().accept(row.path());
                }
            }).bounds(config.leftX(), fileY, config.explorerWidth(), config.inputHeight()).build());

            if (!row.folder()) {
                rowButton.active = !row.path().equals(config.selectedWorkspacePath());
            }
            fileButtons.add(rowButton);
            fileY += config.inputHeight() + 1;
        }

        return new BuildResult(createButton, renameButton, deleteButton, renameInput, renameOkButton, renameCancelButton, fileButtons);
    }

    private static List<ExplorerRow> buildExplorerRows(List<ClientSessionState.WorkspaceFileInfo> files, Set<String> collapsedFolders) {
        List<ExplorerRow> rows = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return rows;
        }

        FolderNode root = new FolderNode("", "");
        List<ClientSessionState.WorkspaceFileInfo> sortedFiles = new ArrayList<>();
        for (ClientSessionState.WorkspaceFileInfo file : files) {
            if (file != null && file.path() != null && !file.path().isBlank()) {
                sortedFiles.add(file);
            }
        }
        sortedFiles.sort(Comparator.comparing(file -> file.path().toLowerCase(java.util.Locale.ROOT)));

        for (ClientSessionState.WorkspaceFileInfo file : sortedFiles) {
            insertFile(root, file);
        }
        collectRows(root, 0, collapsedFolders, rows);
        return rows;
    }

    private static void insertFile(FolderNode root, ClientSessionState.WorkspaceFileInfo file) {
        String normalizedPath = file.path().replace('\\', '/');
        String[] parts = normalizedPath.split("/");
        if (parts.length == 0) {
            return;
        }

        FolderNode current = root;
        StringBuilder folderPath = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (part == null || part.isBlank()) {
                continue;
            }
            if (folderPath.length() > 0) {
                folderPath.append('/');
            }
            folderPath.append(part);
            String currentFolderPath = folderPath.toString();
            current = current.folders.computeIfAbsent(part, key -> new FolderNode(part, currentFolderPath));
        }
        current.files.add(file);
    }

    private static void collectRows(FolderNode folder, int depth, Set<String> collapsedFolders, List<ExplorerRow> rows) {
        List<FolderNode> childFolders = new ArrayList<>(folder.folders.values());
        childFolders.sort(Comparator.comparing(node -> node.name().toLowerCase(java.util.Locale.ROOT)));
        for (FolderNode child : childFolders) {
            boolean collapsed = collapsedFolders != null && collapsedFolders.contains(child.path());
            rows.add(new ExplorerRow(child.path(), folderLabel(child.name(), depth, collapsed), true));
            if (!collapsed) {
                collectRows(child, depth + 1, collapsedFolders, rows);
            }
        }

        List<ClientSessionState.WorkspaceFileInfo> childFiles = new ArrayList<>(folder.files);
        childFiles.sort(Comparator.comparing(file -> file.path().toLowerCase(java.util.Locale.ROOT)));
        for (ClientSessionState.WorkspaceFileInfo file : childFiles) {
            String fileName = file.path();
            int slash = fileName.lastIndexOf('/');
            if (slash >= 0) {
                fileName = fileName.substring(slash + 1);
            }
            String label = fileLabel(fileName, depth);
            if (file.hasPendingPatch()) {
                label += file.pendingChangedBlocks() > 0 ? " * (" + file.pendingChangedBlocks() + ")" : " *";
            }
            rows.add(new ExplorerRow(file.path(), label, false));
        }
    }

    private static String folderLabel(String name, int depth, boolean collapsed) {
        return indent(depth) + (collapsed ? "▸ " : "▾ ") + name;
    }

    private static String fileLabel(String name, int depth) {
        return indent(depth) + "  " + name;
    }

    private static String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
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
            Set<String> collapsedFolders,
            Runnable onCreate,
            Runnable onEnterRename,
            Runnable onDelete,
            Consumer<String> onSwitch,
            Consumer<String> onToggleFolder,
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

    private record ExplorerRow(String path, String label, boolean folder) {
    }

    private static final class FolderNode {
        private final String name;
        private final String path;
        private final Map<String, FolderNode> folders = new LinkedHashMap<>();
        private final List<ClientSessionState.WorkspaceFileInfo> files = new ArrayList<>();

        private FolderNode(String name, String path) {
            this.name = name;
            this.path = path;
        }

        private String name() {
            return name;
        }

        private String path() {
            return path;
        }
    }
}
