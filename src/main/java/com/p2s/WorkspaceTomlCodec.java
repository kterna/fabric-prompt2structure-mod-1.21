package com.p2s;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class WorkspaceTomlCodec {
    public static final int FORMAT_VERSION = 1;

    private WorkspaceTomlCodec() {
    }

    public static WorkspaceTomlDocument parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        return parser.parse();
    }

    public static String format(String text) {
        return write(parse(text));
    }

    public static String write(WorkspaceTomlDocument document) {
        WorkspaceTomlDocument normalized = document == null ? new WorkspaceTomlDocument() : document;
        if (normalized.workspace == null) {
            normalized.workspace = new WorkspaceHeader();
        }
        if (normalized.palette == null) {
            normalized.palette = new LinkedHashMap<>();
        }
        if (normalized.parts == null) {
            normalized.parts = new ArrayList<>();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("format_version = ").append(FORMAT_VERSION).append('\n');
        sb.append('\n');

        sb.append("[workspace]\n");
        if (normalized.workspace.name != null && !normalized.workspace.name.isBlank()) {
            sb.append("name = ").append(stringValue(normalized.workspace.name)).append('\n');
        }
        if (normalized.workspace.type != null && !normalized.workspace.type.isBlank()) {
            sb.append("type = ").append(stringValue(normalized.workspace.type)).append('\n');
        }
        if (normalized.workspace.areaTag != null && !normalized.workspace.areaTag.isBlank()) {
            sb.append("area_tag = ").append(stringValue(normalized.workspace.areaTag)).append('\n');
        }
        if (normalized.workspace.origin != null) {
            sb.append("origin = ").append(intArray(normalized.workspace.origin)).append('\n');
        }
        if (normalized.workspace.size != null) {
            sb.append("size = ").append(intArray(normalized.workspace.size)).append('\n');
        }
        sb.append('\n');

        sb.append("[palette]\n");
        for (Map.Entry<String, String> entry : normalized.palette.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isBlank()) {
                continue;
            }
            sb.append(key).append(" = ").append(stringValue(entry.getValue() == null ? "" : entry.getValue())).append('\n');
        }

        for (WorkspacePart part : normalized.parts) {
            if (part == null) {
                continue;
            }
            sb.append('\n');
            sb.append("[[part]]\n");
            sb.append("name = ").append(stringValue(part.name == null ? "" : part.name)).append('\n');
            sb.append("priority = ").append(part.priority).append('\n');
            if (part.actions == null) {
                continue;
            }
            for (WorkspaceAction action : part.actions) {
                if (action == null) {
                    continue;
                }
                sb.append('\n');
                sb.append("[[part.action]]\n");
                if (action.type != null && !action.type.isBlank()) {
                    sb.append("type = ").append(stringValue(action.type)).append('\n');
                }
                if (action.block != null && !action.block.isBlank()) {
                    sb.append("block = ").append(stringValue(action.block)).append('\n');
                }
                if (action.from != null) {
                    sb.append("from = ").append(intArray(action.from)).append('\n');
                }
                if (action.to != null) {
                    sb.append("to = ").append(intArray(action.to)).append('\n');
                }
                if (action.at != null && !action.at.isEmpty()) {
                    sb.append("at = ").append(nestedIntArray(action.at)).append('\n');
                }
                if (action.mode != null && !action.mode.isBlank()) {
                    sb.append("mode = ").append(stringValue(action.mode)).append('\n');
                }
                if (action.axis != null && !action.axis.isBlank()) {
                    sb.append("axis = ").append(stringValue(action.axis)).append('\n');
                }
                if (action.facing != null && !action.facing.isBlank()) {
                    sb.append("facing = ").append(stringValue(action.facing)).append('\n');
                }
                if (action.blockEntity != null && !action.blockEntity.isBlank()) {
                    sb.append("block_entity = ").append(stringValue(action.blockEntity)).append('\n');
                }
                if (action.signFront != null) {
                    sb.append("sign_front = ").append(stringArray(action.signFront)).append('\n');
                }
                if (action.signBack != null) {
                    sb.append("sign_back = ").append(stringArray(action.signBack)).append('\n');
                }
                if (action.signColor != null && !action.signColor.isBlank()) {
                    sb.append("sign_color = ").append(stringValue(action.signColor)).append('\n');
                }
                if (action.signGlowing != null) {
                    sb.append("sign_glowing = ").append(action.signGlowing).append('\n');
                }
                if (action.signWaxed != null) {
                    sb.append("sign_waxed = ").append(action.signWaxed).append('\n');
                }
                if (action.bannerPatterns != null) {
                    sb.append("banner_patterns = ").append(stringArray(action.bannerPatterns)).append('\n');
                }
            }
        }

        return sb.toString();
    }

    public static WorkspaceTomlDocument fromWorkspace(ProjectPersistence.WorkspaceFileRecord workspace, StructureBuilder.VbsScriptV2 script) {
        WorkspaceTomlDocument document = new WorkspaceTomlDocument();
        document.formatVersion = FORMAT_VERSION;
        document.workspace = new WorkspaceHeader();
        document.workspace.name = workspace == null ? "" : blankToEmpty(workspace.name);
        document.workspace.type = workspace == null ? "manual" : ProjectPersistence.normalizeWorkspaceType(workspace.type);
        document.workspace.areaTag = workspace == null ? "" : blankToEmpty(workspace.areaTag);
        document.workspace.origin = vec3(workspace == null ? null : workspace.origin);
        document.workspace.size = vec3(workspace == null ? null : workspace.size);
        document.palette = new LinkedHashMap<>();
        document.parts = new ArrayList<>();

        StructureBuilder.VbsScriptV2 effective = script == null ? new StructureBuilder.VbsScriptV2() : script;
        if (effective.palette != null) {
            document.palette.putAll(effective.palette);
        }
        if (effective.structures != null) {
            for (StructureBuilder.StructurePart sourcePart : effective.structures) {
                if (sourcePart == null) {
                    continue;
                }
                WorkspacePart targetPart = new WorkspacePart();
                targetPart.name = blankToEmpty(sourcePart.name);
                targetPart.priority = sourcePart.priority;
                targetPart.actions = new ArrayList<>();
                if (sourcePart.actions != null) {
                    for (StructureBuilder.VbsAction sourceAction : sourcePart.actions) {
                        if (sourceAction == null) {
                            continue;
                        }
                        WorkspaceAction targetAction = new WorkspaceAction();
                        targetAction.type = blankToEmpty(sourceAction.type);
                        targetAction.block = blankToEmpty(sourceAction.block);
                        targetAction.from = copyInts(sourceAction.from);
                        targetAction.to = copyInts(sourceAction.to);
                        targetAction.at = copyPoints(sourceAction.at);
                        targetAction.mode = blankToEmpty(sourceAction.mode);
                        targetAction.axis = blankToEmpty(sourceAction.axis);
                        targetAction.facing = blankToEmpty(sourceAction.facing);
                        targetAction.blockEntity = blankToEmpty(sourceAction.blockEntity);
                        targetAction.signFront = copyStrings(sourceAction.signFront);
                        targetAction.signBack = copyStrings(sourceAction.signBack);
                        targetAction.signColor = blankToEmpty(sourceAction.signColor);
                        targetAction.signGlowing = sourceAction.signGlowing;
                        targetAction.signWaxed = sourceAction.signWaxed;
                        targetAction.bannerPatterns = copyStrings(sourceAction.bannerPatterns);
                        targetPart.actions.add(targetAction);
                    }
                }
                document.parts.add(targetPart);
            }
        }
        return document;
    }

    public static StructureBuilder.VbsScriptV2 toScript(WorkspaceTomlDocument document) {
        WorkspaceTomlDocument normalized = document == null ? new WorkspaceTomlDocument() : document;
        StructureBuilder.VbsScriptV2 script = new StructureBuilder.VbsScriptV2();
        if (normalized.palette != null) {
            script.palette.putAll(normalized.palette);
        }
        if (normalized.parts != null) {
            for (WorkspacePart sourcePart : normalized.parts) {
                if (sourcePart == null) {
                    continue;
                }
                StructureBuilder.StructurePart part = new StructureBuilder.StructurePart();
                part.name = blankToEmpty(sourcePart.name);
                part.priority = sourcePart.priority;
                part.actions = new ArrayList<>();
                if (sourcePart.actions != null) {
                    for (WorkspaceAction sourceAction : sourcePart.actions) {
                        if (sourceAction == null) {
                            continue;
                        }
                        StructureBuilder.VbsAction action = new StructureBuilder.VbsAction();
                        action.type = blankToEmpty(sourceAction.type);
                        action.block = blankToEmpty(sourceAction.block);
                        action.from = copyInts(sourceAction.from);
                        action.to = copyInts(sourceAction.to);
                        action.at = copyPoints(sourceAction.at);
                        action.mode = blankToEmpty(sourceAction.mode);
                        action.axis = blankToEmpty(sourceAction.axis);
                        action.facing = blankToEmpty(sourceAction.facing);
                        action.blockEntity = blankToEmpty(sourceAction.blockEntity);
                        action.signFront = copyStrings(sourceAction.signFront);
                        action.signBack = copyStrings(sourceAction.signBack);
                        action.signColor = blankToEmpty(sourceAction.signColor);
                        action.signGlowing = sourceAction.signGlowing;
                        action.signWaxed = sourceAction.signWaxed;
                        action.bannerPatterns = copyStrings(sourceAction.bannerPatterns);
                        part.actions.add(action);
                    }
                }
                script.structures.add(part);
            }
        }
        return script;
    }

    public static void applyToWorkspace(WorkspaceTomlDocument document, ProjectPersistence.WorkspaceFileRecord workspace) {
        if (workspace == null) {
            return;
        }
        WorkspaceHeader header = document == null ? null : document.workspace;
        if (header == null) {
            if (workspace.name == null || workspace.name.isBlank()) {
                workspace.name = ProjectPersistence.leafName(workspace.path);
            }
            workspace.type = ProjectPersistence.normalizeWorkspaceType(workspace.type);
            workspace.areaTag = blankToEmpty(workspace.areaTag);
            return;
        }
        workspace.name = header.name == null || header.name.isBlank() ? ProjectPersistence.leafName(workspace.path) : header.name.trim();
        workspace.type = normalizeWorkspaceTypeOrThrow(header.type);
        workspace.areaTag = blankToEmpty(header.areaTag);
        workspace.origin = toVec3Data(header.origin);
        workspace.size = toVec3Data(header.size);
    }

    public static String emptyWorkspaceToml(String workspacePath, String type, ProjectPersistence.Vec3Data origin, ProjectPersistence.Vec3Data size) {
        ProjectPersistence.WorkspaceFileRecord workspace = new ProjectPersistence.WorkspaceFileRecord();
        workspace.path = ProjectPersistence.normalizeWorkspacePath(workspacePath);
        workspace.name = ProjectPersistence.leafName(workspace.path);
        workspace.type = normalizeWorkspaceTypeOrThrow(type);
        workspace.origin = origin == null ? null : copyVec3(origin);
        workspace.size = size == null ? null : copyVec3(size);
        return write(fromWorkspace(workspace, new StructureBuilder.VbsScriptV2()));
    }

    private static ProjectPersistence.Vec3Data copyVec3(ProjectPersistence.Vec3Data value) {
        if (value == null) {
            return null;
        }
        ProjectPersistence.Vec3Data copy = new ProjectPersistence.Vec3Data();
        copy.x = value.x;
        copy.y = value.y;
        copy.z = value.z;
        return copy;
    }

    private static String normalizeWorkspaceTypeOrThrow(String raw) {
        String value = blankToEmpty(raw);
        if (value.isBlank()) {
            return "manual";
        }
        String normalized = ProjectPersistence.normalizeWorkspaceType(value);
        if (!normalized.equals(value.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("workspace.type must be one of: layout, floor, facade, component, semantic, generated, manual, shared");
        }
        return normalized;
    }

    private static List<Integer> vec3(ProjectPersistence.Vec3Data value) {
        if (value == null) {
            return null;
        }
        List<Integer> vec = new ArrayList<>(3);
        vec.add(value.x);
        vec.add(value.y);
        vec.add(value.z);
        return vec;
    }

    private static ProjectPersistence.Vec3Data toVec3Data(List<Integer> value) {
        if (value == null) {
            return null;
        }
        List<Integer> normalized = requireVec3(value, "workspace vector");
        ProjectPersistence.Vec3Data data = new ProjectPersistence.Vec3Data();
        data.x = normalized.get(0);
        data.y = normalized.get(1);
        data.z = normalized.get(2);
        return data;
    }

    private static List<Integer> copyInts(List<Integer> source) {
        return source == null ? null : new ArrayList<>(source);
    }

    private static List<List<Integer>> copyPoints(List<List<Integer>> source) {
        if (source == null) {
            return null;
        }
        List<List<Integer>> copy = new ArrayList<>();
        for (List<Integer> point : source) {
            copy.add(copyInts(point));
        }
        return copy;
    }

    private static List<String> copyStrings(List<String> source) {
        return source == null ? null : new ArrayList<>(source);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stringValue(String value) {
        return '"' + escape(value == null ? "" : value) + '"';
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String intArray(List<Integer> values) {
        List<Integer> normalized = requireVec3(values, "vector");
        return "[" + normalized.get(0) + ", " + normalized.get(1) + ", " + normalized.get(2) + "]";
    }

    private static String nestedIntArray(List<List<Integer>> points) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(intArray(points.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String stringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(stringValue(values.get(i)));
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<Integer> requireVec3(List<Integer> values, String label) {
        if (values == null || values.size() != 3) {
            throw new IllegalArgumentException(label + " must contain exactly 3 integers");
        }
        return values;
    }

    public static final class WorkspaceTomlDocument {
        public int formatVersion = FORMAT_VERSION;
        public WorkspaceHeader workspace = new WorkspaceHeader();
        public Map<String, String> palette = new LinkedHashMap<>();
        public List<WorkspacePart> parts = new ArrayList<>();
    }

    public static final class WorkspaceHeader {
        public String name = "";
        public String type = "manual";
        public String areaTag = "";
        public List<Integer> origin;
        public List<Integer> size;
    }

    public static final class WorkspacePart {
        public String name = "";
        public int priority = 0;
        public List<WorkspaceAction> actions = new ArrayList<>();
    }

    public static final class WorkspaceAction {
        public String type = "";
        public String block = "";
        public List<Integer> from;
        public List<Integer> to;
        public List<List<Integer>> at;
        public String mode = "";
        public String axis = "";
        public String facing = "";
        public String blockEntity = "";
        public List<String> signFront;
        public List<String> signBack;
        public String signColor = "";
        public Boolean signGlowing;
        public Boolean signWaxed;
        public List<String> bannerPatterns;
    }

    private static final class Parser {
        private final String[] lines;

        private Parser(String text) {
            this.lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        }

        private WorkspaceTomlDocument parse() {
            WorkspaceTomlDocument document = new WorkspaceTomlDocument();
            Section section = Section.ROOT;
            WorkspacePart currentPart = null;
            WorkspaceAction currentAction = null;

            for (int index = 0; index < lines.length; index++) {
                int lineNumber = index + 1;
                String line = stripComment(lines[index]).trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("[[") && line.endsWith("]]")) {
                    String name = line.substring(2, line.length() - 2).trim();
                    if ("part".equals(name)) {
                        currentPart = new WorkspacePart();
                        document.parts.add(currentPart);
                        currentAction = null;
                        section = Section.PART;
                        continue;
                    }
                    if ("part.action".equals(name)) {
                        if (currentPart == null) {
                            fail(lineNumber, "[[part.action]] requires a preceding [[part]] section");
                        }
                        currentAction = new WorkspaceAction();
                        currentPart.actions.add(currentAction);
                        section = Section.ACTION;
                        continue;
                    }
                    fail(lineNumber, "Unknown array table: " + name);
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    String name = line.substring(1, line.length() - 1).trim();
                    currentAction = null;
                    section = switch (name) {
                        case "workspace" -> Section.WORKSPACE;
                        case "palette" -> Section.PALETTE;
                        default -> throw error(lineNumber, "Unknown table: " + name);
                    };
                    continue;
                }

                int assignment = findAssignment(line);
                if (assignment < 0) {
                    fail(lineNumber, "Expected key = value");
                }
                String key = line.substring(0, assignment).trim();
                String rawValue = line.substring(assignment + 1).trim();
                if (key.isBlank()) {
                    fail(lineNumber, "Missing key before '='");
                }
                Object value = parseValue(rawValue, lineNumber);

                switch (section) {
                    case ROOT -> applyRoot(document, key, value, lineNumber);
                    case WORKSPACE -> applyWorkspace(document.workspace, key, value, lineNumber);
                    case PALETTE -> applyPalette(document.palette, key, value, lineNumber);
                    case PART -> applyPart(currentPart, key, value, lineNumber);
                    case ACTION -> applyAction(currentAction, key, value, lineNumber);
                }
            }

            validate(document);
            return document;
        }

        private void applyRoot(WorkspaceTomlDocument document, String key, Object value, int lineNumber) {
            if (!"format_version".equals(key)) {
                fail(lineNumber, "Unknown top-level key: " + key);
            }
            if (!(value instanceof Integer)) {
                fail(lineNumber, "format_version must be an integer");
            }
            document.formatVersion = (Integer) value;
        }

        private void applyWorkspace(WorkspaceHeader workspace, String key, Object value, int lineNumber) {
            switch (key) {
                case "name" -> workspace.name = requireString(value, lineNumber, "workspace.name");
                case "type" -> workspace.type = normalizeWorkspaceTypeOrThrow(requireString(value, lineNumber, "workspace.type"));
                case "area_tag" -> workspace.areaTag = requireString(value, lineNumber, "workspace.area_tag");
                case "origin" -> workspace.origin = requireIntArray(value, lineNumber, "workspace.origin");
                case "size" -> workspace.size = requireIntArray(value, lineNumber, "workspace.size");
                default -> fail(lineNumber, "Unknown workspace key: " + key);
            }
        }

        private void applyPalette(Map<String, String> palette, String key, Object value, int lineNumber) {
            palette.put(key, requireString(value, lineNumber, "palette." + key));
        }

        private void applyPart(WorkspacePart part, String key, Object value, int lineNumber) {
            if (part == null) {
                fail(lineNumber, "Part properties require [[part]]");
            }
            switch (key) {
                case "name" -> part.name = requireString(value, lineNumber, "part.name");
                case "priority" -> part.priority = requireInt(value, lineNumber, "part.priority");
                default -> fail(lineNumber, "Unknown part key: " + key);
            }
        }

        private void applyAction(WorkspaceAction action, String key, Object value, int lineNumber) {
            if (action == null) {
                fail(lineNumber, "Action properties require [[part.action]]");
            }
            switch (key) {
                case "type" -> action.type = requireString(value, lineNumber, "part.action.type");
                case "block" -> action.block = requireString(value, lineNumber, "part.action.block");
                case "from" -> action.from = requireIntArray(value, lineNumber, "part.action.from");
                case "to" -> action.to = requireIntArray(value, lineNumber, "part.action.to");
                case "at" -> action.at = requireNestedIntArray(value, lineNumber, "part.action.at");
                case "mode" -> action.mode = requireString(value, lineNumber, "part.action.mode");
                case "axis" -> action.axis = requireString(value, lineNumber, "part.action.axis");
                case "facing" -> action.facing = requireString(value, lineNumber, "part.action.facing");
                case "block_entity" -> action.blockEntity = requireString(value, lineNumber, "part.action.block_entity");
                case "sign_front" -> action.signFront = requireStringArray(value, lineNumber, "part.action.sign_front");
                case "sign_back" -> action.signBack = requireStringArray(value, lineNumber, "part.action.sign_back");
                case "sign_color" -> action.signColor = requireString(value, lineNumber, "part.action.sign_color");
                case "sign_glowing" -> action.signGlowing = requireBoolean(value, lineNumber, "part.action.sign_glowing");
                case "sign_waxed" -> action.signWaxed = requireBoolean(value, lineNumber, "part.action.sign_waxed");
                case "banner_patterns" -> action.bannerPatterns = requireStringArray(value, lineNumber, "part.action.banner_patterns");
                default -> fail(lineNumber, "Unknown action key: " + key);
            }
        }

        private void validate(WorkspaceTomlDocument document) {
            if (document.formatVersion != FORMAT_VERSION) {
                throw new IllegalArgumentException("workspace format_version must be 1");
            }
            if (document.workspace == null) {
                document.workspace = new WorkspaceHeader();
            }
            document.workspace.type = normalizeWorkspaceTypeOrThrow(document.workspace.type);
            if (document.palette == null) {
                document.palette = new LinkedHashMap<>();
            }
            if (document.parts == null) {
                document.parts = new ArrayList<>();
            }

            Set<String> partNames = new HashSet<>();
            for (int partIndex = 0; partIndex < document.parts.size(); partIndex++) {
                WorkspacePart part = document.parts.get(partIndex);
                if (part == null) {
                    continue;
                }
                String partName = blankToEmpty(part.name);
                if (partName.isBlank()) {
                    throw new IllegalArgumentException("part[" + partIndex + "] missing 'name'");
                }
                if (!partNames.add(partName)) {
                    throw new IllegalArgumentException("duplicate part name '" + partName + "'");
                }
                if (part.actions == null) {
                    part.actions = new ArrayList<>();
                }
                for (int actionIndex = 0; actionIndex < part.actions.size(); actionIndex++) {
                    WorkspaceAction action = part.actions.get(actionIndex);
                    if (action == null) {
                        continue;
                    }
                    validateAction(action, partIndex, actionIndex);
                }
            }
        }

        private void validateAction(WorkspaceAction action, int partIndex, int actionIndex) {
            String prefix = "part[" + partIndex + "].action[" + actionIndex + "]";
            String type = blankToEmpty(action.type).toLowerCase(Locale.ROOT);
            if (type.isBlank()) {
                throw new IllegalArgumentException(prefix + " missing 'type'");
            }
            if (blankToEmpty(action.block).isBlank()) {
                throw new IllegalArgumentException(prefix + " missing 'block'");
            }
            switch (type) {
                case "box" -> {
                    requireVec3Field(action.from, prefix + " requires from");
                    requireVec3Field(action.to, prefix + " requires to");
                    String mode = blankToEmpty(action.mode).toLowerCase(Locale.ROOT);
                    if (!mode.isBlank() && !mode.equals("solid") && !mode.equals("shell") && !mode.equals("walls")) {
                        throw new IllegalArgumentException(prefix + " has unsupported box mode '" + action.mode + "'");
                    }
                }
                case "plane" -> {
                    List<Integer> from = requireVec3Field(action.from, prefix + " requires from");
                    List<Integer> to = requireVec3Field(action.to, prefix + " requires to");
                    String axis = blankToEmpty(action.axis).toLowerCase(Locale.ROOT);
                    if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
                        throw new IllegalArgumentException(prefix + " requires axis=x|y|z");
                    }
                    int axisIndex = axis.equals("x") ? 0 : axis.equals("y") ? 1 : 2;
                    if (!from.get(axisIndex).equals(to.get(axisIndex))) {
                        throw new IllegalArgumentException(prefix + " requires from/to with matching coordinate on axis=" + axis);
                    }
                    String mode = blankToEmpty(action.mode).toLowerCase(Locale.ROOT);
                    if (!mode.isBlank() && !mode.equals("solid") && !mode.equals("outline")) {
                        throw new IllegalArgumentException(prefix + " has unsupported plane mode '" + action.mode + "'");
                    }
                }
                case "line" -> {
                    requireVec3Field(action.from, prefix + " requires from");
                    requireVec3Field(action.to, prefix + " requires to");
                }
                case "points" -> {
                    if (action.at == null || action.at.isEmpty()) {
                        throw new IllegalArgumentException(prefix + " requires at");
                    }
                    for (int i = 0; i < action.at.size(); i++) {
                        requireVec3Field(action.at.get(i), prefix + ".at[" + i + "] must contain 3 integers");
                    }
                }
                default -> throw new IllegalArgumentException(prefix + " has unsupported type '" + action.type + "'");
            }

            String facing = blankToEmpty(action.facing).toLowerCase(Locale.ROOT);
            if (!facing.isBlank() && !List.of("north", "south", "east", "west", "up", "down").contains(facing)) {
                throw new IllegalArgumentException(prefix + " has unsupported facing '" + action.facing + "'");
            }
        }

        private List<Integer> requireVec3Field(List<Integer> value, String message) {
            if (value == null || value.size() != 3) {
                throw new IllegalArgumentException(message);
            }
            return value;
        }

        private String requireString(Object value, int lineNumber, String label) {
            if (value instanceof String stringValue) {
                return stringValue;
            }
            fail(lineNumber, label + " must be a string");
            return "";
        }

        private int requireInt(Object value, int lineNumber, String label) {
            if (value instanceof Integer integer) {
                return integer;
            }
            fail(lineNumber, label + " must be an integer");
            return 0;
        }

        private List<Integer> requireIntArray(Object value, int lineNumber, String label) {
            if (!(value instanceof List<?> listValue)) {
                fail(lineNumber, label + " must be an array of 3 integers");
            }
            List<Integer> result = new ArrayList<>();
            for (Object entry : (List<?>) value) {
                if (!(entry instanceof Integer integerValue)) {
                    fail(lineNumber, label + " must contain only integers");
                }
                result.add((Integer) entry);
            }
            if (result.size() != 3) {
                fail(lineNumber, label + " must contain exactly 3 integers");
            }
            return result;
        }

        private List<List<Integer>> requireNestedIntArray(Object value, int lineNumber, String label) {
            if (!(value instanceof List<?>)) {
                fail(lineNumber, label + " must be an array of coordinate arrays");
            }
            List<List<Integer>> result = new ArrayList<>();
            for (Object entry : (List<?>) value) {
                result.add(requireIntArray(entry, lineNumber, label));
            }
            return result;
        }

        private List<String> requireStringArray(Object value, int lineNumber, String label) {
            if (!(value instanceof List<?>)) {
                fail(lineNumber, label + " must be a string array");
            }
            List<String> result = new ArrayList<>();
            for (Object entry : (List<?>) value) {
                if (!(entry instanceof String)) {
                    fail(lineNumber, label + " must contain only strings");
                }
                result.add((String) entry);
            }
            return result;
        }

        private Boolean requireBoolean(Object value, int lineNumber, String label) {
            if (value instanceof Boolean booleanValue) {
                return booleanValue;
            }
            fail(lineNumber, label + " must be a boolean");
            return Boolean.FALSE;
        }

        private Object parseValue(String rawValue, int lineNumber) {
            ValueParser parser = new ValueParser(rawValue, lineNumber);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.isEof()) {
                fail(lineNumber, "Unexpected trailing content after value");
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

        private void fail(int lineNumber, String message) {
            throw error(lineNumber, message);
        }

        private IllegalArgumentException error(int lineNumber, String message) {
            return new IllegalArgumentException("Invalid workspace TOML at line " + lineNumber + ": " + message);
        }
    }

    private enum Section {
        ROOT,
        WORKSPACE,
        PALETTE,
        PART,
        ACTION
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
            if (startsWith("true") || startsWith("false")) {
                return parseBoolean();
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

        private Boolean parseBoolean() {
            if (startsWith("true")) {
                index += 4;
                return Boolean.TRUE;
            }
            if (startsWith("false")) {
                index += 5;
                return Boolean.FALSE;
            }
            throw error("Invalid boolean");
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

        private boolean startsWith(String value) {
            return text.startsWith(value, index);
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
            return new IllegalArgumentException("Invalid workspace TOML at line " + lineNumber + ": " + message);
        }
    }
}
