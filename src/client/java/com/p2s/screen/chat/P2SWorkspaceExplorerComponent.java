package com.p2s.screen.chat;

import com.p2s.ClientSessionState;
import com.p2s.P2SI18n;
import com.p2s.screen.widget.P2SFlatButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class P2SWorkspaceExplorerComponent {
    private static final int PADDING = 8;
    private static final int EXPLORER_ACTION_GAP = 4;
    private static final int LIST_ROW_HEIGHT = 20;
    private static final int LIST_ROW_GAP = 2;

    private P2SWorkspaceExplorerComponent() {
    }

    static BuildResult build(Host host, Config config) {
        int actionButtonWidth = Math.max(32, (config.explorerWidth() - EXPLORER_ACTION_GAP * 3) / 4);
        int actionX = config.leftX();

        Button createFileButton = host.addButton(new P2SFlatButton(
                actionX,
                config.row1Y(),
                actionButtonWidth,
                config.inputHeight(),
                P2SI18n.tr("screen.p2s.workspace.new_file_short"),
                btn -> config.onEnterCreateFileMode().run(),
                P2SFlatButton.Variant.PRIMARY
        ));
        actionX += actionButtonWidth + EXPLORER_ACTION_GAP;

        Button createFolderButton = host.addButton(new P2SFlatButton(
                actionX,
                config.row1Y(),
                actionButtonWidth,
                config.inputHeight(),
                P2SI18n.tr("screen.p2s.workspace.new_folder_short"),
                btn -> config.onEnterCreateFolderMode().run(),
                P2SFlatButton.Variant.PRIMARY
        ));
        actionX += actionButtonWidth + EXPLORER_ACTION_GAP;

        Button renameButton = host.addButton(new P2SFlatButton(
                actionX,
                config.row1Y(),
                actionButtonWidth,
                config.inputHeight(),
                P2SI18n.tr("screen.p2s.workspace.rename_short"),
                btn -> config.onEnterRename().run(),
                P2SFlatButton.Variant.NORMAL
        ));
        actionX += actionButtonWidth + EXPLORER_ACTION_GAP;

        Button deleteButton = host.addButton(new P2SFlatButton(
                actionX,
                config.row1Y(),
                actionButtonWidth,
                config.inputHeight(),
                P2SI18n.tr("screen.p2s.workspace.delete_short"),
                btn -> config.onDelete().run(),
                P2SFlatButton.Variant.DANGER
        ));

        EditBox createInput = null;
        Button createOkButton = null;
        Button createCancelButton = null;
        EditBox renameInput = null;
        Button renameOkButton = null;
        Button renameCancelButton = null;
        int listY = config.row2Y();

        if (config.createMode()) {
            int inputWidth = config.explorerWidth() - 2 * 22 - EXPLORER_ACTION_GAP * 2;
            int actionWidth = 22;
            createInput = host.addEditBox(new EditBox(
                    host.font(),
                    config.leftX(),
                    config.row2Y(),
                    inputWidth,
                    config.inputHeight(),
                    config.createFolderMode()
                            ? P2SI18n.tr("screen.p2s.workspace.folder_path")
                            : P2SI18n.tr("screen.p2s.workspace.path")
            ));
            createInput.setHint(config.createFolderMode()
                    ? P2SI18n.tr("screen.p2s.workspace.folder_hint")
                    : P2SI18n.tr("screen.p2s.workspace.path_hint"));
            createInput.setValue(config.createDraft() == null ? "" : config.createDraft());

            createOkButton = host.addButton(new P2SFlatButton(
                    config.leftX() + inputWidth + EXPLORER_ACTION_GAP,
                    config.row2Y(),
                    actionWidth,
                    config.inputHeight(),
                    P2SI18n.tr("screen.p2s.common.ok"),
                    btn -> config.onConfirmCreate().run(),
                    P2SFlatButton.Variant.PRIMARY
            ));
            createCancelButton = host.addButton(new P2SFlatButton(
                    config.leftX() + inputWidth + EXPLORER_ACTION_GAP + actionWidth + EXPLORER_ACTION_GAP,
                    config.row2Y(),
                    actionWidth,
                    config.inputHeight(),
                    Component.literal("×"),
                    btn -> config.onCancelCreate().run(),
                    P2SFlatButton.Variant.MUTED
            ));
            listY = config.row2Y() + config.inputHeight() + EXPLORER_ACTION_GAP;
        } else if (config.renameMode()) {
            int inputWidth = config.explorerWidth() - 2 * 22 - EXPLORER_ACTION_GAP * 2;
            int actionWidth = 22;
            renameInput = host.addEditBox(new EditBox(
                    host.font(),
                    config.leftX(),
                    config.row2Y(),
                    inputWidth,
                    config.inputHeight(),
                    P2SI18n.tr("screen.p2s.workspace.path")
            ));
            renameInput.setHint(P2SI18n.tr("screen.p2s.workspace.path_hint"));
            renameInput.setValue(config.renameDraft() == null ? "" : config.renameDraft());

            renameOkButton = host.addButton(new P2SFlatButton(
                    config.leftX() + inputWidth + EXPLORER_ACTION_GAP,
                    config.row2Y(),
                    actionWidth,
                    config.inputHeight(),
                    P2SI18n.tr("screen.p2s.common.ok"),
                    btn -> config.onConfirmRename().run(),
                    P2SFlatButton.Variant.PRIMARY
            ));
            renameCancelButton = host.addButton(new P2SFlatButton(
                    config.leftX() + inputWidth + EXPLORER_ACTION_GAP + actionWidth + EXPLORER_ACTION_GAP,
                    config.row2Y(),
                    actionWidth,
                    config.inputHeight(),
                    Component.literal("×"),
                    btn -> config.onCancelRename().run(),
                    P2SFlatButton.Variant.MUTED
            ));
            listY = config.row2Y() + config.inputHeight() + EXPLORER_ACTION_GAP;
        }

        int listBottom = host.screenHeight() - config.contextFooterHeight() - PADDING;
        int listHeight = Math.max(0, listBottom - listY);
        List<ExplorerRow> rows = buildExplorerRows(
                config.files(),
                config.folders(),
                config.collapsedFolders(),
                config.selectedFolderPath(),
                config.selectedWorkspacePath()
        );

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
                List.of(),
                config.leftX(),
                listY,
                config.explorerWidth(),
                listHeight,
                LIST_ROW_HEIGHT,
                LIST_ROW_GAP,
                rows
        );
    }

    static List<ExplorerRow> buildExplorerRows(
            List<ClientSessionState.WorkspaceFileInfo> files,
            List<String> folders,
            Set<String> collapsedFolders,
            String selectedFolderPath,
            String selectedWorkspacePath
    ) {
        FolderNode root = new FolderNode("", "");
        if (folders != null) {
            for (String folder : folders) {
                insertFolder(root, folder);
            }
        }
        if (files != null) {
            for (ClientSessionState.WorkspaceFileInfo file : files) {
                if (file != null) {
                    insertFile(root, file);
                }
            }
        }

        List<ExplorerRow> rows = new ArrayList<>();
        collectRows(
                root,
                0,
                collapsedFolders,
                normalizeFolderPath(selectedFolderPath),
                normalizeFolderPath(selectedWorkspacePath),
                rows
        );
        if (rows.isEmpty()) {
            rows.add(new ExplorerRow("", P2SI18n.tr("screen.p2s.workspace.empty").getString(), 0, false, false, false, false, 0, 0, true));
        }
        return rows;
    }

    private static void insertFolder(FolderNode root, String folderPath) {
        String normalized = normalizeFolderPath(folderPath);
        if (normalized.isBlank()) {
            return;
        }
        String[] parts = normalized.split("/");
        FolderNode current = root;
        StringBuilder currentPath = new StringBuilder();
        for (String part : parts) {
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
        for (int index = 0; index < parts.length - 1; index++) {
            String part = parts[index];
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

    private static void collectRows(
            FolderNode folder,
            int depth,
            Set<String> collapsedFolders,
            String selectedFolderPath,
            String selectedWorkspacePath,
            List<ExplorerRow> rows
    ) {
        List<FolderNode> childFolders = new ArrayList<>(folder.folders.values());
        childFolders.sort(Comparator.comparing(node -> node.name.toLowerCase(Locale.ROOT)));
        for (FolderNode child : childFolders) {
            boolean collapsed = collapsedFolders != null && collapsedFolders.contains(child.path);
            boolean selected = child.path.equals(selectedFolderPath);
            rows.add(new ExplorerRow(
                    child.path,
                    child.name,
                    depth,
                    true,
                    collapsed,
                    selected,
                    false,
                    0,
                    countEntries(child),
                    false
            ));
            if (!collapsed) {
                collectRows(child, depth + 1, collapsedFolders, selectedFolderPath, selectedWorkspacePath, rows);
            }
        }

        List<ClientSessionState.WorkspaceFileInfo> childFiles = new ArrayList<>(folder.files);
        childFiles.sort(Comparator.comparing(file -> normalizeFolderPath(file.path()).toLowerCase(Locale.ROOT)));
        for (ClientSessionState.WorkspaceFileInfo file : childFiles) {
            String normalizedPath = normalizeFolderPath(file.path());
            boolean selected = normalizedPath.equals(selectedWorkspacePath);
            rows.add(new ExplorerRow(
                    file.path(),
                    leafName(file.path()),
                    depth,
                    false,
                    false,
                    selected,
                    file.hasPendingPatch(),
                    file.pendingChangedBlocks(),
                    0,
                    false
            ));
        }
    }

    private static int countEntries(FolderNode node) {
        if (node == null) {
            return 0;
        }
        int count = node.files.size();
        for (FolderNode child : node.folders.values()) {
            count += 1 + countEntries(child);
        }
        return count;
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
            List<Button> fileButtons,
            int listX,
            int listY,
            int listWidth,
            int listHeight,
            int rowHeight,
            int rowGap,
            List<ExplorerRow> rows
    ) {
    }

    public record ExplorerRow(
            String path,
            String name,
            int depth,
            boolean folder,
            boolean collapsed,
            boolean selected,
            boolean pending,
            int changedBlocks,
            int itemCount,
            boolean placeholder
    ) {
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
