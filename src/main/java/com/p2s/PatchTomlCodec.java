package com.p2s;

import java.util.ArrayList;
import java.util.List;

public final class PatchTomlCodec {
    private PatchTomlCodec() {
    }

    public static PatchModels.StructurePatch parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        return parser.parse();
    }

    private static final class Parser {
        private final String[] lines;

        private PatchModels.StructurePatch patch = new PatchModels.StructurePatch();
        private PatchModels.PatchOperation currentOperation;
        private StructureBuilder.VbsAction currentAction;
        private PatchModels.PaletteEntry currentEntry;
        private Section section = Section.ROOT;

        private Parser(String text) {
            this.lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        }

        private PatchModels.StructurePatch parse() {
            for (int index = 0; index < lines.length; index++) {
                int lineNumber = index + 1;
                String line = stripComment(lines[index]).trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("[[") && line.endsWith("]]")) {
                    String table = line.substring(2, line.length() - 2).trim();
                    openArrayTable(table, lineNumber);
                    continue;
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    throw error(lineNumber, "Unsupported table: " + line);
                }

                int assignment = findAssignment(line);
                if (assignment < 0) {
                    throw error(lineNumber, "Expected key = value");
                }
                String key = line.substring(0, assignment).trim();
                String rawValue = line.substring(assignment + 1).trim();
                Object value = parseValue(rawValue, lineNumber);
                applyValue(key, value, lineNumber);
            }
            normalizePatch(patch);
            return patch;
        }

        private void openArrayTable(String table, int lineNumber) {
            switch (table) {
                case "operation" -> {
                    currentOperation = new PatchModels.PatchOperation();
                    if (patch.operations == null) {
                        patch.operations = new ArrayList<>();
                    }
                    patch.operations.add(currentOperation);
                    currentAction = null;
                    currentEntry = null;
                    section = Section.OPERATION;
                }
                case "operation.actions_add" -> {
                    ensureOperation(lineNumber);
                    if (currentOperation.actionsAdd == null) {
                        currentOperation.actionsAdd = new ArrayList<>();
                    }
                    currentAction = new StructureBuilder.VbsAction();
                    currentOperation.actionsAdd.add(currentAction);
                    currentEntry = null;
                    section = Section.ACTIONS_ADD;
                }
                case "operation.old_actions" -> {
                    ensureOperation(lineNumber);
                    if (currentOperation.oldActions == null) {
                        currentOperation.oldActions = new ArrayList<>();
                    }
                    currentAction = new StructureBuilder.VbsAction();
                    currentOperation.oldActions.add(currentAction);
                    currentEntry = null;
                    section = Section.OLD_ACTIONS;
                }
                case "operation.new_actions" -> {
                    ensureOperation(lineNumber);
                    if (currentOperation.newActions == null) {
                        currentOperation.newActions = new ArrayList<>();
                    }
                    currentAction = new StructureBuilder.VbsAction();
                    currentOperation.newActions.add(currentAction);
                    currentEntry = null;
                    section = Section.NEW_ACTIONS;
                }
                case "operation.entry" -> {
                    ensureOperation(lineNumber);
                    if (currentOperation.entries == null) {
                        currentOperation.entries = new ArrayList<>();
                    }
                    currentEntry = new PatchModels.PaletteEntry();
                    currentOperation.entries.add(currentEntry);
                    currentAction = null;
                    section = Section.ENTRY;
                }
                default -> throw error(lineNumber, "Unknown array table: " + table);
            }
        }

        private void ensureOperation(int lineNumber) {
            if (currentOperation == null) {
                throw error(lineNumber, "Nested operation table requires a preceding [[operation]]");
            }
        }

        private void applyValue(String key, Object value, int lineNumber) {
            switch (section) {
                case ROOT -> applyRoot(key, value, lineNumber);
                case OPERATION -> applyOperation(key, value, lineNumber);
                case ACTIONS_ADD, OLD_ACTIONS, NEW_ACTIONS -> applyAction(key, value, lineNumber);
                case ENTRY -> applyEntry(key, value, lineNumber);
            }
        }

        private void applyRoot(String key, Object value, int lineNumber) {
            switch (key) {
                case "base_revision" -> patch.baseRevision = requireString(value, lineNumber, "base_revision");
                case "intent" -> patch.intent = requireString(value, lineNumber, "intent");
                case "message_to_user" -> patch.messageToUser = requireString(value, lineNumber, "message_to_user");
                default -> throw error(lineNumber, "Unknown top-level key: " + key);
            }
        }

        private void applyOperation(String key, Object value, int lineNumber) {
            ensureOperation(lineNumber);
            switch (key) {
                case "op" -> currentOperation.op = requireString(value, lineNumber, "operation.op");
                case "part" -> currentOperation.part = requireString(value, lineNumber, "operation.part");
                case "priority" -> currentOperation.priority = requireInteger(value, lineNumber, "operation.priority");
                case "offset" -> currentOperation.offset = requireIntArray(value, lineNumber, "operation.offset");
                case "target_part" -> currentOperation.targetPart = requireString(value, lineNumber, "operation.target_part");
                default -> throw error(lineNumber, "Unknown operation key: " + key);
            }
        }

        private void applyAction(String key, Object value, int lineNumber) {
            if (currentAction == null) {
                throw error(lineNumber, "Action key requires a current action table");
            }
            switch (key) {
                case "type" -> currentAction.type = requireString(value, lineNumber, "action.type");
                case "block" -> currentAction.block = requireString(value, lineNumber, "action.block");
                case "from" -> currentAction.from = requireIntArray(value, lineNumber, "action.from");
                case "to" -> currentAction.to = requireIntArray(value, lineNumber, "action.to");
                case "at" -> currentAction.at = requireNestedIntArray(value, lineNumber, "action.at");
                case "mode" -> currentAction.mode = requireString(value, lineNumber, "action.mode");
                case "axis" -> currentAction.axis = requireString(value, lineNumber, "action.axis");
                case "facing" -> currentAction.facing = requireString(value, lineNumber, "action.facing");
                default -> throw error(lineNumber, "Unknown action key: " + key);
            }
        }

        private void applyEntry(String key, Object value, int lineNumber) {
            if (currentEntry == null) {
                throw error(lineNumber, "Entry key requires a current entry table");
            }
            switch (key) {
                case "key" -> currentEntry.key = requireString(value, lineNumber, "entry.key");
                case "old_value" -> currentEntry.oldValue = requireString(value, lineNumber, "entry.old_value");
                case "new_value" -> currentEntry.newValue = requireString(value, lineNumber, "entry.new_value");
                default -> throw error(lineNumber, "Unknown entry key: " + key);
            }
        }

        private void normalizePatch(PatchModels.StructurePatch result) {
            if (result.baseRevision == null) {
                result.baseRevision = "";
            }
            if (result.intent == null) {
                result.intent = "";
            }
            if (result.messageToUser == null) {
                result.messageToUser = "";
            }
            if (result.operations == null) {
                result.operations = new ArrayList<>();
            }
        }

        private String requireString(Object value, int lineNumber, String label) {
            if (value instanceof String stringValue) {
                return stringValue;
            }
            throw error(lineNumber, label + " must be a string");
        }

        private Integer requireInteger(Object value, int lineNumber, String label) {
            if (value instanceof Integer integerValue) {
                return integerValue;
            }
            throw error(lineNumber, label + " must be an integer");
        }

        private List<Integer> requireIntArray(Object value, int lineNumber, String label) {
            if (!(value instanceof List<?> listValue)) {
                throw error(lineNumber, label + " must be an integer array");
            }
            List<Integer> result = new ArrayList<>();
            for (Object entry : listValue) {
                if (!(entry instanceof Integer integerValue)) {
                    throw error(lineNumber, label + " must contain only integers");
                }
                result.add(integerValue);
            }
            return result;
        }

        private List<List<Integer>> requireNestedIntArray(Object value, int lineNumber, String label) {
            if (!(value instanceof List<?> listValue)) {
                throw error(lineNumber, label + " must be an array of integer arrays");
            }
            List<List<Integer>> result = new ArrayList<>();
            for (Object entry : listValue) {
                result.add(requireIntArray(entry, lineNumber, label));
            }
            return result;
        }

        private Object parseValue(String rawValue, int lineNumber) {
            ValueParser parser = new ValueParser(rawValue, lineNumber);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.isEof()) {
                throw error(lineNumber, "Unexpected trailing content after value");
            }
            return value;
        }

        private int findAssignment(String line) {
            boolean inString = false;
            int depth = 0;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (ch == '[') {
                    depth++;
                    continue;
                }
                if (ch == ']') {
                    depth = Math.max(0, depth - 1);
                    continue;
                }
                if (ch == '=' && depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        private String stripComment(String line) {
            boolean inString = false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                    inString = !inString;
                    sb.append(ch);
                    continue;
                }
                if (ch == '#' && !inString) {
                    break;
                }
                sb.append(ch);
            }
            return sb.toString();
        }

        private IllegalArgumentException error(int lineNumber, String message) {
            return new IllegalArgumentException("Invalid patch TOML at line " + lineNumber + ": " + message);
        }
    }

    private enum Section {
        ROOT,
        OPERATION,
        ACTIONS_ADD,
        OLD_ACTIONS,
        NEW_ACTIONS,
        ENTRY
    }

    private static final class ValueParser {
        private final String text;
        private final int lineNumber;
        private int index = 0;

        private ValueParser(String text, int lineNumber) {
            this.text = text == null ? "" : text;
            this.lineNumber = lineNumber;
        }

        private Object parseValue() {
            skipWhitespace();
            if (isEof()) {
                throw error("Missing value");
            }
            char ch = text.charAt(index);
            if (ch == '"') {
                return parseString();
            }
            if (ch == '[') {
                return parseArray();
            }
            if (ch == '-' || Character.isDigit(ch)) {
                return parseInteger();
            }
            throw error("Unsupported value syntax");
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            expect('"');
            while (!isEof()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (isEof()) {
                        throw error("Unterminated escape sequence");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        default -> throw error("Unsupported escape sequence '\\" + escaped + "'");
                    }
                    continue;
                }
                sb.append(ch);
            }
            throw error("Unterminated string");
        }

        private List<Object> parseArray() {
            List<Object> values = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                index++;
                return values;
            }
            while (true) {
                values.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    skipWhitespace();
                    continue;
                }
                if (peek(']')) {
                    index++;
                    return values;
                }
                throw error("Expected ',' or ']' in array");
            }
        }

        private Integer parseInteger() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (!isEof() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            try {
                return Integer.parseInt(text.substring(start, index));
            } catch (NumberFormatException e) {
                throw error("Invalid integer");
            }
        }

        private void expect(char expected) {
            if (isEof() || text.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char ch) {
            return !isEof() && text.charAt(index) == ch;
        }

        private void skipWhitespace() {
            while (!isEof() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean isEof() {
            return index >= text.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid patch TOML at line " + lineNumber + ": " + message);
        }
    }
}
