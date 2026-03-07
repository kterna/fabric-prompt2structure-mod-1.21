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
import java.util.LinkedHashSet;
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
        int actionButtonWidth = Math.max(28, (config.explorerWidth() - EXPLORER_ACTION_GAP * 3) / 4);
        int actionX = config.leftX();

        Button createFileButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.workspace.new_file_short"), btn -> config.onEnterCreateFileMode().run())
                .bounds(actionX, config.row1Y(), actionButtonWidth, config.inputHeight()).build());
        actionX += actionButtonWidth + EXPLORER_ACTION_GAP;

        Button createFolderButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.workspace.new_folder_short"), btn -> config.onEnterCreateFolderMode().run())
                .bounds(actionX, config.row1Y(), actionButtonWidth, config.inputHeight()).build());
        actionX += actionButtonWidth + EXPLORER_ACTION_GAP;

        Button renameButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.workspace.rename_short"), btn -> config.onEnterRename().run())
                .bounds(actionX, config.row1Y(), actionButtonWidth, config.inputHeight()).build());
        actionX += actionButtonWidth + EXPLORER_ACTION_GAP;

        Button deleteButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.workspace.delete_short"), btn -> config.onDelete().run())
                .bounds(actionX, config.row1Y(), actionButtonWidth, config.inputHeight()).build());

        EditBox createInput = null;
        Button createOkButton = null;
        Button createCancelButton = null;
        EditBox renameInput = null;
        Button renameOkButton = null;
        Button renameCancelButton = null;
        int fileY = config.row2Y();

        if (config.createMode()) {
            int inputWidth = config.explorerWidth() - 2 * 20 - EXPLORER_ACTION_GAP * 2;
            int actionWidth = 20;
            createInput = host.addEditBox(new EditBox(host.font(), config.leftX(), config.row2Y(), inputWidth,
                    config.inputHeight(), config.createFolderMode()
                    ? P2SI18n.tr("screen.p2s.workspace.folder_path")
                    : P2SI18n.tr("screen.p2s.workspace.path")));
            createInput.setHint(config.createFolderMode()
                    ? P2SI18n.tr("screen.p2s.workspace.folder_hint")
                    : P2SI18n.tr("screen.p2s.workspace.path_hint"));
            createInput.setValue(config.createDraft() == null ? "" : config.createDraft());

            createOkButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.common.ok"), btn -> config.onConfirmCreate().run())
                    .bounds(config.leftX() + inputWidth + EXPLORER_ACTION_GAP, config.row2Y(), actionWidth, config.inputHeight()).build());

            createCancelButton = host.addButton(Button.builder(Component.literal("X"), btn -> config.onCancelCreate().run())
                    .bounds(config.leftX() + inputWidth + EXPLORER_ACTION_GAP + actionWidth + EXPLORER_ACTION_GAP,
                            config.row2Y(), actionWidth, config.inputHeight()).build());
            fileY = config.row2Y() + config.inputHeight() + EXPLORER_ACTION_GAP;
        } else if (config.renameMode()) {
            int inputWidth = config.explorerWidth() - 2 * 20 - EXPLORER_ACTION_GAP * 2;
            int actionWidth = 20;
            renameInput = host.addEditBox(new EditBox(host.font(), config.leftX(), config.row2Y(), inputWidth,
                    config.inputHeight(), P2SI18n.tr("screen.p2s.workspace.path")));
            renameInput.setHint(P2SI18n.tr("screen.p2s.workspace.path_hint"));
            renameInput.setValue(config.renameDraft() == null ? "" : config.renameDraft());

            renameOkButton = host.addButton(Button.builder(P2SI18n.tr("screen.p2s.common.ok"), btn -> config.onConfirmRename().run())
                    .bounds(config.leftX() + inputWidth + EXPLORER_ACTION_GAP, config.row2Y(), actionWidth, config.inputHeight()).build());

            renameCancelButton = host.addButton(Button.builder(Component.literal("X"), btn -> config.onCancelRename().run())
                    .bounds(config.leftX() + inputWidth + EXPLORER_ACTION_GAP + actionWidth + EXPLORER_ACTION_GAP,
                            config.row2Y(), actionWidth, config.inputHeight()).build());
            fileY = config.row2Y() + config.inputHeight() + EXPLORER_ACTION_GAP;
        }

        List<Button> rowButtons = new ArrayList<>();
        int fileBottom = host.screenHeight() - config.contextFooterHeight() - PADDING;
        for (ExplorerRow row : buildExplorerRows(config.files(), config.folders(), config.collapsedFolders(), config.selectedFolderPath())) {
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
            rowButtons.add(rowButton);
            fileY += config.inputHeight() + 1;
        }

        return new BuildResult(
                createFileButton,
                createFolderButton,
                renameButton,
                deleteButton,
                createInput,
                createOkButton,
                createCancelButton,
                renameInput,
                renameOkButton,
                renameCancelButton,
                rowButtons
        );
    }

    private static List<ExplorerRow> buildExplorerRows(
            List<ClientSessionState.WorkspaceFileInfo> files,
            List<String> folders,
            Set<String> collapsedFolders,
            String selectedFolderPath
    ) {
        List<ExplorerRow> rows = new ArrayList<>();
        FolderNode root = new FolderNode("", "");

        if (folders != null) {
            for (String folder : folders) {
                insertFolder(root, folder);
            }
        }

        List<ClientSessionState.WorkspaceFileInfo> sortedFiles = new ArrayList<>();
        if (files != null) {
            for (ClientSessionState.WorkspaceFileInfo file : files) {
                if (file != null && file.path() != null && !file.path().isBlank()) {
                    sortedFiles.add(file);
                }
            }
        }
        sortedFiles.sort(Comparator.comparing(file -> file.path().toLowerCase(java.util.Locale.ROOT)));
        for (ClientSessionState.WorkspaceFileInfo file : sortedFiles) {
            insertFile(root, file);
        }

        collectRows(root, 0, collapsedFolders, normalizeFolderPath(selectedFolderPath), rows);
        return rows;
    }

    private static void insertFolder(FolderNode root, String folderPath) {
        String normalized = normalizeFolderPath(folderPath);
        if (normalized.isBlank()) {
            return;
        }
        FolderNode current = root;
        StringBuilder currentPath = new StringBuilder();
        for (String part : normalized.split("/")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (currentPath.length() > 0) {
                currentPath.append('/');
            }
            currentPath.append(part);
            String folderKey = currentPath.toString();
            current = current.folders.computeIfAbsent(part, key -> new FolderNode(part, folderKey));
        }
    }

    private static void insertFile(FolderNode root, ClientSessionState.WorkspaceFileInfo file) {
        String normalizedPath = normalizeFolderPath(file.path());
        if (normalizedPath.isBlank()) {
            return;
        }
        String[] parts = normalizedPath.split("/");
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

    private static void collectRows(FolderNode folder, int depth, Set<String> collapsedFolders, String selectedFolderPath, List<ExplorerRow> rows) {
        List<FolderNode> childFolders = new ArrayList<>(folder.folders.values());
        childFolders.sort(Comparator.comparing(node -> node.name.toLowerCase(java.util.Locale.ROOT)));
        for (FolderNode child : childFolders) {
            boolean collapsed = collapsedFolders != null && collapsedFolders.contains(child.path);
            boolean selected = child.path.equals(selectedFolderPath);
            rows.add(new ExplorerRow(child.path, folderLabel(child.name, depth, collapsed, selected), true));
            if (!collapsed) {
                collectRows(child, depth + 1, collapsedFolders, selectedFolderPath, rows);
            }
        }

        List<ClientSessionState.WorkspaceFileInfo> childFiles = new ArrayList<>(folder.files);
        childFiles.sort(Comparator.comparing(file -> file.path().toLowerCase(java.util.Locale.ROOT)));
        for (ClientSessionState.WorkspaceFileInfo file : childFiles) {
            String fileName = leafName(file.path());
            String label = fileLabel(fileName, depth);
            if (file.hasPendingPatch()) {
                label += file.pendingChangedBlocks() > 0 ? " * (" + file.pendingChangedBlocks() + ")" : " *";
            }
            rows.add(new ExplorerRow(file.path(), label, false));
        }
    }

    private static String folderLabel(String name, int depth, boolean collapsed, boolean selected) {
        String display = selected ? "[" + name + "]" : name;
        return indent(depth) + (collapsed ? "▸ " : "▾ ") + display;
    }

    private static String fileLabel(String name, int depth) {
        return indent(depth) + "  " + name;
    }

    private static String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
    }

    private static String leafName(String path) {
        String normalized = normalizeFolderPath(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String normalizeFolderPath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.replaceAll("/{2,}", "/");
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
            boolean createMode,
            boolean createFolderMode,
            boolean renameMode,
            String createDraft,
            String renameDraft,
            String selectedWorkspacePath,
            String selectedFolderPath,
            List<ClientSessionState.WorkspaceFileInfo> files,
            List<String> folders,
            Set<String> collapsedFolders,
            Runnable onEnterCreateFileMode,
            Runnable onEnterCreateFolderMode,
            Runnable onEnterRename,
            Runnable onDelete,
            Consumer<String> onSwitch,
            Consumer<String> onToggleFolder,
            Runnable onConfirmCreate,
            Runnable onCancelCreate,
            Runnable onConfirmRename,
            Runnable onCancelRename
    ) {
    }

    record BuildResult(
            Button createFileButton,
            Button createFolderButton,
            Button renameButton,
            Button deleteButton,
            EditBox createInput,
            Button createOkButton,
            Button createCancelButton,
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
    }
}
