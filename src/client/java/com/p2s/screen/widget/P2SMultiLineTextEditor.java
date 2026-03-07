package com.p2s.screen.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class P2SMultiLineTextEditor {
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 2;
    private static final int GUTTER_WIDTH = 38;
    private static final int PADDING = 4;

    private final Font font;
    private final List<String> lines = new ArrayList<>();

    private int x;
    private int y;
    private int width;
    private int height;
    private int maxLength = 32768;
    private boolean focused;
    private boolean mouseSelecting;
    private boolean showLineNumbers = true;

    private int scroll;
    private int cursorLine;
    private int cursorColumn;
    private int preferredColumn = -1;
    private boolean selectionActive;
    private int selectionAnchorLine;
    private int selectionAnchorColumn;

    public P2SMultiLineTextEditor(Font font) {
        this.font = font;
        this.lines.add("");
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(24, width);
        this.height = Math.max(28, height);
        clampScroll();
        ensureCursorVisible();
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
    }

    public void setShowLineNumbers(boolean showLineNumbers) {
        this.showLineNumbers = showLineNumbers;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (!focused) {
            this.mouseSelecting = false;
        }
    }

    public boolean isFocused() {
        return focused;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public void setText(String text) {
        lines.clear();
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        if (parts.length == 0) {
            lines.add("");
        } else {
            for (String part : parts) {
                lines.add(part == null ? "" : part);
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        scroll = 0;
        cursorLine = 0;
        cursorColumn = 0;
        preferredColumn = -1;
        clearSelection();
        mouseSelecting = false;
        clampScroll();
        clampCursor();
        ensureCursorVisible();
    }

    public String getText() {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines);
    }

    public void render(GuiGraphics gfx) {
        if (lines.isEmpty()) {
            lines.add("");
        }
        clampCursor();
        clampScroll();

        int left = x;
        int top = y;
        int right = x + width;
        int bottom = y + height;
        int bg = focused ? 0xAA0B111A : 0xAA080E16;
        gfx.fill(left, top, right, bottom, bg);
        gfx.fill(left, top, right, top + 1, 0xFF2F3A4D);
        gfx.fill(left, bottom - 1, right, bottom, 0xFF2F3A4D);
        gfx.fill(left, top, left + 1, bottom, 0xFF2F3A4D);
        gfx.fill(right - 1, top, right, bottom, 0xFF2F3A4D);

        int rowStep = ROW_HEIGHT + ROW_GAP;
        int baseY = top + PADDING;
        int textX = left + (showLineNumbers ? GUTTER_WIDTH : PADDING);
        int textWidth = Math.max(24, right - textX - PADDING);
        int maxTextX = right - PADDING - 1;
        SelectionRange selection = getSelectionRange();

        int firstLine = scroll;
        int lastLineExclusive = Math.min(lines.size(), firstLine + visibleRows());
        for (int lineIndex = firstLine; lineIndex < lastLineExclusive; lineIndex++) {
            int row = lineIndex - firstLine;
            int rowY = baseY + row * rowStep;
            int rowBottom = rowY + ROW_HEIGHT;
            String fullLine = getLine(lineIndex);

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
                    int selX1 = textX + font.width(fullLine.substring(0, selStart));
                    int selX2 = textX + font.width(fullLine.substring(0, selEnd));
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

            if (showLineNumbers) {
                gfx.drawString(font, Integer.toString(lineIndex + 1), left + 4, rowY + 5, 0x8A99B8, false);
            }
            gfx.drawString(font, clipTextToWidth(fullLine, textWidth), textX, rowY + 5, 0xD6E0F5, false);
        }

        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            if (cursorLine >= firstLine && cursorLine < lastLineExclusive) {
                int row = cursorLine - firstLine;
                int caretY = baseY + row * rowStep + 3;
                int caretHeight = Math.max(4, ROW_HEIGHT - 6);
                String line = getLine(cursorLine);
                int cursor = Math.min(cursorColumn, line.length());
                int caretX = textX + font.width(line.substring(0, cursor));
                caretX = Math.max(textX, Math.min(caretX, maxTextX));
                gfx.fill(caretX, caretY, caretX + 1, caretY + caretHeight, 0xFFE8F0FF);
            }
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        setFocused(true);
        if (Screen.hasShiftDown()) {
            ensureSelectionAnchor();
        } else {
            clearSelection();
        }
        placeCursorFromMouse(mouseX, mouseY);
        if (!Screen.hasShiftDown()) {
            selectionActive = true;
            selectionAnchorLine = cursorLine;
            selectionAnchorColumn = cursorColumn;
        }
        mouseSelecting = true;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (button != 0 || !mouseSelecting || !focused) {
            return false;
        }
        ensureSelectionAnchor();
        placeCursorFromMouse(mouseX, mouseY);
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        boolean handled = mouseSelecting;
        mouseSelecting = false;
        return handled;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (!contains(mouseX, mouseY) && !focused) {
            return false;
        }
        int delta = verticalAmount > 0 ? -3 : verticalAmount < 0 ? 3 : 0;
        if (delta == 0) {
            return false;
        }
        int before = scroll;
        scroll += delta;
        clampScroll();
        return before != scroll;
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        if (!focused) {
            return false;
        }
        if (Screen.hasControlDown()) {
            if (keyCode == GLFW.GLFW_KEY_A) {
                selectAllText();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_C) {
                copySelectionToClipboard();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_X) {
                copySelectionToClipboard();
                deleteSelection();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                Minecraft minecraft = Minecraft.getInstance();
                String clipboard = minecraft == null ? "" : minecraft.keyboardHandler.getClipboard();
                if (clipboard != null && !clipboard.isEmpty()) {
                    insertText(clipboard);
                }
                return true;
            }
        }

        boolean selecting = Screen.hasShiftDown();
        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                insertText("\n");
                yield true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                backspaceChar();
                yield true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                deleteChar();
                yield true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                moveCursorHorizontal(-1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                moveCursorHorizontal(1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_UP -> {
                moveCursorVertical(-1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                moveCursorVertical(1, selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                moveCursorToLineStart(selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_END -> {
                moveCursorToLineEnd(selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                moveCursorVertical(-Math.max(1, visibleRows() - 1), selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                moveCursorVertical(Math.max(1, visibleRows() - 1), selecting);
                yield true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                insertText("    ");
                yield true;
            }
            default -> false;
        };
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!focused || Screen.hasControlDown() || Character.isISOControl(codePoint)) {
            return false;
        }
        insertText(Character.toString(codePoint));
        return true;
    }

    private int visibleRows() {
        int rowStep = ROW_HEIGHT + ROW_GAP;
        return Math.max(1, (Math.max(0, height - PADDING * 2) + ROW_GAP) / rowStep);
    }

    private void placeCursorFromMouse(double mouseX, double mouseY) {
        if (lines.isEmpty()) {
            lines.add("");
        }
        int rowStep = ROW_HEIGHT + ROW_GAP;
        int row = (int) ((mouseY - (y + PADDING)) / rowStep);
        row = Math.max(0, Math.min(row, Math.max(0, visibleRows() - 1)));
        int line = Math.max(0, Math.min(scroll + row, lines.size() - 1));

        int textX = x + (showLineNumbers ? GUTTER_WIDTH : PADDING);
        int targetX = (int) (mouseX - textX);
        int column = columnAtPixel(getLine(line), targetX);
        setCursor(line, column, false);
    }

    private int columnAtPixel(String text, int pixelX) {
        if (text == null || text.isEmpty() || pixelX <= 0) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            int charWidth = font.width(String.valueOf(text.charAt(i)));
            int mid = width + charWidth / 2;
            if (pixelX <= mid) {
                return i;
            }
            width += charWidth;
        }
        return text.length();
    }

    private void clampCursor() {
        if (lines.isEmpty()) {
            lines.add("");
        }
        cursorLine = Math.max(0, Math.min(cursorLine, lines.size() - 1));
        String line = getLine(cursorLine);
        cursorColumn = Math.max(0, Math.min(cursorColumn, line.length()));
        clampSelectionAnchor();
    }

    private void clampSelectionAnchor() {
        if (!selectionActive) {
            return;
        }
        if (lines.isEmpty()) {
            clearSelection();
            return;
        }
        selectionAnchorLine = Math.max(0, Math.min(selectionAnchorLine, lines.size() - 1));
        String line = getLine(selectionAnchorLine);
        selectionAnchorColumn = Math.max(0, Math.min(selectionAnchorColumn, line.length()));
    }

    private void clampScroll() {
        int max = Math.max(0, lines.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll, max));
    }

    private void ensureCursorVisible() {
        clampCursor();
        int visibleRows = visibleRows();
        if (cursorLine < scroll) {
            scroll = cursorLine;
        } else if (cursorLine >= scroll + visibleRows) {
            scroll = cursorLine - visibleRows + 1;
        }
        clampScroll();
    }

    private void setCursor(int line, int column, boolean keepPreferredColumn) {
        cursorLine = line;
        cursorColumn = column;
        if (!keepPreferredColumn) {
            preferredColumn = -1;
        }
        ensureCursorVisible();
    }

    private String getLine(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            return "";
        }
        String value = lines.get(lineIndex);
        return value == null ? "" : value;
    }

    private void setLine(int lineIndex, String value) {
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            return;
        }
        lines.set(lineIndex, value == null ? "" : value);
    }

    private void clearSelection() {
        selectionActive = false;
    }

    private boolean hasSelection() {
        if (!selectionActive) {
            return false;
        }
        clampSelectionAnchor();
        return selectionAnchorLine != cursorLine || selectionAnchorColumn != cursorColumn;
    }

    private void ensureSelectionAnchor() {
        if (selectionActive) {
            return;
        }
        selectionActive = true;
        selectionAnchorLine = cursorLine;
        selectionAnchorColumn = cursorColumn;
    }

    private void updateSelectionForCursorMove(boolean selecting) {
        if (selecting) {
            ensureSelectionAnchor();
        } else {
            clearSelection();
        }
    }

    private int comparePosition(int line1, int col1, int line2, int col2) {
        if (line1 != line2) {
            return Integer.compare(line1, line2);
        }
        return Integer.compare(col1, col2);
    }

    private SelectionRange getSelectionRange() {
        if (!hasSelection()) {
            return null;
        }
        if (comparePosition(selectionAnchorLine, selectionAnchorColumn, cursorLine, cursorColumn) <= 0) {
            return new SelectionRange(selectionAnchorLine, selectionAnchorColumn, cursorLine, cursorColumn);
        }
        return new SelectionRange(cursorLine, cursorColumn, selectionAnchorLine, selectionAnchorColumn);
    }

    private boolean deleteSelection() {
        SelectionRange range = getSelectionRange();
        if (range == null) {
            return false;
        }
        String startLineText = getLine(range.startLine());
        String endLineText = getLine(range.endLine());
        int startColumn = Math.min(range.startColumn(), startLineText.length());
        int endColumn = Math.min(range.endColumn(), endLineText.length());

        if (range.startLine() == range.endLine()) {
            setLine(range.startLine(), startLineText.substring(0, startColumn) + startLineText.substring(endColumn));
        } else {
            setLine(range.startLine(), startLineText.substring(0, startColumn) + endLineText.substring(endColumn));
            for (int line = range.endLine(); line > range.startLine(); line--) {
                lines.remove(line);
            }
        }

        setCursor(range.startLine(), startColumn, false);
        clearSelection();
        return true;
    }

    private String getSelectionText() {
        SelectionRange range = getSelectionRange();
        if (range == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int lineIndex = range.startLine(); lineIndex <= range.endLine(); lineIndex++) {
            String line = getLine(lineIndex);
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

    private void copySelectionToClipboard() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        String text = getSelectionText();
        if (!text.isEmpty()) {
            minecraft.keyboardHandler.setClipboard(text);
        }
    }

    private void selectAllText() {
        if (lines.isEmpty()) {
            lines.add("");
        }
        selectionActive = true;
        selectionAnchorLine = 0;
        selectionAnchorColumn = 0;
        int lastLine = lines.size() - 1;
        setCursor(lastLine, getLine(lastLine).length(), false);
    }

    private int currentLength() {
        return getText().length();
    }

    private String limitInsertedText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int selectedLength = getSelectionText().length();
        int remaining = maxLength - (currentLength() - selectedLength);
        if (remaining <= 0) {
            return "";
        }
        if (text.length() <= remaining) {
            return text;
        }
        return text.substring(0, remaining);
    }

    private void insertText(String text) {
        String limited = limitInsertedText(text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n'));
        if (limited.isEmpty()) {
            return;
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        deleteSelection();

        String current = getLine(cursorLine);
        String before = current.substring(0, Math.min(cursorColumn, current.length()));
        String after = current.substring(Math.min(cursorColumn, current.length()));
        String[] parts = limited.split("\n", -1);

        if (parts.length == 1) {
            setLine(cursorLine, before + parts[0] + after);
            setCursor(cursorLine, before.length() + parts[0].length(), false);
            return;
        }

        setLine(cursorLine, before + parts[0]);
        int insertAt = cursorLine + 1;
        for (int i = 1; i < parts.length; i++) {
            lines.add(insertAt, parts[i]);
            insertAt++;
        }

        int lastLine = cursorLine + parts.length - 1;
        setLine(lastLine, getLine(lastLine) + after);
        setCursor(lastLine, parts[parts.length - 1].length(), false);
    }

    private void backspaceChar() {
        if (deleteSelection()) {
            return;
        }
        if (lines.isEmpty()) {
            lines.add("");
            setCursor(0, 0, false);
            return;
        }
        String line = getLine(cursorLine);
        if (cursorColumn > 0) {
            int removeIndex = cursorColumn - 1;
            setLine(cursorLine, line.substring(0, removeIndex) + line.substring(cursorColumn));
            setCursor(cursorLine, removeIndex, false);
            return;
        }
        if (cursorLine <= 0) {
            return;
        }

        String prevLine = getLine(cursorLine - 1);
        int newColumn = prevLine.length();
        setLine(cursorLine - 1, prevLine + line);
        lines.remove(cursorLine);
        setCursor(cursorLine - 1, newColumn, false);
    }

    private void deleteChar() {
        if (deleteSelection()) {
            return;
        }
        if (lines.isEmpty()) {
            lines.add("");
            setCursor(0, 0, false);
            return;
        }
        String line = getLine(cursorLine);
        if (cursorColumn < line.length()) {
            setLine(cursorLine, line.substring(0, cursorColumn) + line.substring(cursorColumn + 1));
            return;
        }
        if (cursorLine >= lines.size() - 1) {
            return;
        }

        setLine(cursorLine, line + getLine(cursorLine + 1));
        lines.remove(cursorLine + 1);
        setCursor(cursorLine, cursorColumn, false);
    }

    private void moveCursorHorizontal(int delta, boolean selecting) {
        if (delta == 0 || lines.isEmpty()) {
            return;
        }
        if (!selecting && hasSelection()) {
            SelectionRange range = getSelectionRange();
            if (range != null) {
                if (delta < 0) {
                    setCursor(range.startLine(), range.startColumn(), false);
                } else {
                    setCursor(range.endLine(), range.endColumn(), false);
                }
                clearSelection();
                return;
            }
        }
        int line = cursorLine;
        int column = cursorColumn;
        updateSelectionForCursorMove(selecting);
        if (delta < 0) {
            if (column > 0) {
                setCursor(line, column - 1, false);
            } else if (line > 0) {
                setCursor(line - 1, getLine(line - 1).length(), false);
            }
            return;
        }

        String current = getLine(line);
        if (column < current.length()) {
            setCursor(line, column + 1, false);
        } else if (line < lines.size() - 1) {
            setCursor(line + 1, 0, false);
        }
    }

    private void moveCursorVertical(int deltaRows, boolean selecting) {
        if (deltaRows == 0 || lines.isEmpty()) {
            return;
        }
        updateSelectionForCursorMove(selecting);
        int preferred = preferredColumn >= 0 ? preferredColumn : cursorColumn;
        int targetLine = Math.max(0, Math.min(cursorLine + deltaRows, lines.size() - 1));
        int targetColumn = Math.min(preferred, getLine(targetLine).length());
        preferredColumn = preferred;
        setCursor(targetLine, targetColumn, true);
    }

    private void moveCursorToLineStart(boolean selecting) {
        updateSelectionForCursorMove(selecting);
        setCursor(cursorLine, 0, false);
    }

    private void moveCursorToLineEnd(boolean selecting) {
        updateSelectionForCursorMove(selecting);
        setCursor(cursorLine, getLine(cursorLine).length(), false);
    }

    private String clipTextToWidth(String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        int widthLimit = Math.max(0, maxWidth - ellipsisWidth);
        int width = 0;
        int end = 0;
        while (end < text.length()) {
            int charWidth = font.width(String.valueOf(text.charAt(end)));
            if (width + charWidth > widthLimit) {
                break;
            }
            width += charWidth;
            end++;
        }
        return text.substring(0, end) + ellipsis;
    }

    private record SelectionRange(int startLine, int startColumn, int endLine, int endColumn) {
    }
}
