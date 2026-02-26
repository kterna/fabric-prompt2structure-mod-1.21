package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import com.p2s.network.S2CChatResponsePayload;
import com.p2s.network.S2CSessionSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionManager {
    private static final Gson GSON = new Gson();
    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 40;
    private static final int MAX_AGENT_LOOPS = 6;
    private static final int MAX_TOOL_JSON_CHARS = 12000;
    private static final int MAX_SUMMARY_LINES = 20;
    private static final int MAX_SUMMARY_CHARS = 4000;

    private SessionManager() {
    }

    public static void handleChatMessage(ServerPlayer player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }

        Session session = ensureSession(player);
        String playerName = player.getGameProfile().getName();
        String sessionId = session == null ? "-" : session.id;
        int msgLen = safeLength(message);
        boolean inFlight = session != null && session.inFlight;
        P2SMod.LOGGER.info("AgentLoop receive -> player={}, session={}, msgLen={}, inFlight={}", playerName, sessionId, msgLen, inFlight);
        if (session.inFlight) {
            P2SMod.LOGGER.warn("AgentLoop reject busy -> player={}, session={}", playerName, sessionId);
            sendChatResponse(player, "Busy with previous request.", false, "error");
            return;
        }

        session.inFlight = true;

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", message.trim());
        int historyBefore = session.history == null ? 0 : session.history.size();
        session.history.add(userMsg);
        trimHistory(session);
        int historyAfter = session.history == null ? 0 : session.history.size();
        P2SMod.LOGGER.info("AgentLoop history -> player={}, session={}, size {}->{}", playerName, sessionId, historyBefore, historyAfter);

        sendChatResponse(player, "", false, "thinking");

        runAgentLoop(player, session, MAX_AGENT_LOOPS);
    }

    private static void runAgentLoop(ServerPlayer player, Session session, int remaining) {
        if (player == null || session == null) {
            return;
        }
        String playerName = player.getGameProfile().getName();
        String sessionId = session.id;
        List<JsonObject> historySnapshot = deepCopyMessages(session.history);
        int snapshotSize = historySnapshot.size();
        P2SMod.LOGGER.info("AgentLoop llm request -> player={}, session={}, snapshotSize={}, remaining={}", playerName, sessionId, snapshotSize, remaining);
        long llmStartMs = System.currentTimeMillis();

        LLMService.requestWithHistory(historySnapshot).thenAccept(result -> {
            MinecraftServer server = player.getServer();
            server.execute(() -> handleAgentResult(player, session, result, remaining, llmStartMs));
        }).exceptionally(ex -> {
            MinecraftServer server = player.getServer();
            server.execute(() -> {
                session.inFlight = false;
                P2SMod.LOGGER.error("AgentLoop failed -> player={}, session={}", playerName, sessionId, ex);
                sendChatResponse(player, "Request failed: " + ex.getMessage(), false, "error");
            });
            return null;
        });
    }

    private static void handleAgentResult(ServerPlayer player, Session session, LLMService.SessionResult result, int remaining, long llmStartMs) {
        if (player == null || session == null) {
            return;
        }
        String playerName = player.getGameProfile().getName();
        String sessionId = session.id;

        if (result == null) {
            session.inFlight = false;
            sendChatResponse(player, "Request failed: empty response", false, "error");
            return;
        }

        long llmMs = System.currentTimeMillis() - llmStartMs;
        int textLen = result.textContent() == null ? 0 : result.textContent().length();
        int toolCallCount = result.toolCalls() == null ? 0 : result.toolCalls().size();
        boolean hasScript = result.script() != null;
        P2SMod.LOGGER.info("AgentLoop llm response -> player={}, session={}, ms={}, textLen={}, toolCalls={}, hasScript={}",
                playerName, sessionId, llmMs, textLen, toolCallCount, hasScript);

        JsonObject assistant = result.rawAssistantMessage() == null ? null : result.rawAssistantMessage().deepCopy();
        if (assistant != null) {
            session.history.add(assistant);
            trimHistory(session);
        }

        if (toolCallCount > 0) {
            ToolCallProcessingResult toolResult = processToolCalls(session, result.toolCalls(), playerName, sessionId);
            for (JsonObject toolMsg : toolResult.toolMessages) {
                session.history.add(toolMsg);
                trimHistory(session);
            }

            if (toolResult.structureChanged) {
                sendChatResponse(player, "", true, "building");
                long buildStartMs = System.currentTimeMillis();
                P2SMod.LOGGER.info("AgentLoop build start -> player={}, session={}, origin={}, size={}",
                        playerName, sessionId, posString(session.origin), sizeString(session.size));
                rebuildStructure(player, session);
                long buildMs = System.currentTimeMillis() - buildStartMs;
                P2SMod.LOGGER.info("AgentLoop build done -> player={}, session={}, ms={}, parts={}, blocks={}",
                        playerName, sessionId, buildMs, countParts(session.current), countBlocks(session.current));
                sendChatResponse(player, "", true, "done");
            }

            sendSessionSync(player, session);

            if (remaining <= 0) {
                session.inFlight = false;
                sendChatResponse(player, "Tool calls exceeded max iterations.", false, "error");
                return;
            }

            sendChatResponse(player, "", toolResult.structureChanged, "thinking");
            runAgentLoop(player, session, remaining - 1);
            return;
        }

        session.turnCount += 1;

        if (hasScript) {
            int beforeParts = countParts(session.current);
            int beforeBlocks = countBlocks(session.current);
            if (session.current != null) {
                session.versions.push(copyScript(session.current));
            }
            session.current = StructureBuilder.mergeScripts(session.current, result.script());
            int afterParts = countParts(session.current);
            int afterBlocks = countBlocks(session.current);
            P2SMod.LOGGER.info("AgentLoop apply -> player={}, session={}, parts {}->{}, blocks {}->{}",
                    playerName, sessionId, beforeParts, afterParts, beforeBlocks, afterBlocks);
            sendChatResponse(player, result.textContent(), true, "building");
            long buildStartMs = System.currentTimeMillis();
            P2SMod.LOGGER.info("AgentLoop build start -> player={}, session={}, origin={}, size={}",
                    playerName, sessionId, posString(session.origin), sizeString(session.size));
            rebuildStructure(player, session);
            long buildMs = System.currentTimeMillis() - buildStartMs;
            P2SMod.LOGGER.info("AgentLoop build done -> player={}, session={}, ms={}, parts={}, blocks={}",
                    playerName, sessionId, buildMs, afterParts, afterBlocks);
            sendChatResponse(player, "", true, "done");
        } else {
            P2SMod.LOGGER.info("AgentLoop text-only -> player={}, session={}, textLen={}", playerName, sessionId, textLen);
            sendChatResponse(player, result.textContent(), false, "done");
        }

        sendSessionSync(player, session);
        session.inFlight = false;
    }

    private static ToolCallProcessingResult processToolCalls(Session session, List<LLMService.ToolCall> toolCalls, String playerName, String sessionId) {
        ToolCallProcessingResult result = new ToolCallProcessingResult();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return result;
        }

        boolean pushed = false;
        StructureBuilder.VbsScriptV2 working = session.current;

        for (LLMService.ToolCall call : toolCalls) {
            if (call == null || call.name() == null) {
                continue;
            }
            String toolName = call.name();
            switch (toolName) {
                case "apply_structure" -> {
                    StructureBuilder.VbsScriptV2 delta = LLMService.parseToolArguments(call.arguments());
                    if (delta == null) {
                        result.toolMessages.add(buildToolMessage(call, buildToolError(toolName, "Invalid tool arguments")));
                        P2SMod.LOGGER.warn("AgentLoop tool apply_structure failed -> player={}, session={}, reason=parse_failed", playerName, sessionId);
                        continue;
                    }
                    if (!pushed && session.current != null) {
                        session.versions.push(copyScript(session.current));
                        pushed = true;
                    }
                    working = StructureBuilder.mergeScripts(working, delta);
                    result.structureChanged = true;
                    int parts = countParts(working);
                    int blocks = countBlocks(working);
                    JsonObject payload = buildToolSuccess(toolName);
                    payload.addProperty("summary", buildToolSummary(working));
                    payload.addProperty("parts", parts);
                    payload.addProperty("blocks", blocks);
                    result.toolMessages.add(buildToolMessage(call, payload));
                    P2SMod.LOGGER.info("AgentLoop tool apply_structure ok -> player={}, session={}, parts={}, blocks={}", playerName, sessionId, parts, blocks);
                }
                case "get_current_structure" -> {
                    JsonObject payload = buildToolSuccess(toolName);
                    StructureBuilder.VbsScriptV2 target = working;
                    JsonObject structurePayload = buildCurrentStructurePayload(target);
                    payload.add("current", structurePayload);
                    result.toolMessages.add(buildToolMessage(call, payload));
                    P2SMod.LOGGER.info("AgentLoop tool get_current_structure -> player={}, session={}, empty={}",
                            playerName, sessionId, working == null);
                }
                default -> {
                    result.toolMessages.add(buildToolMessage(call, buildToolError(toolName, "Unknown tool")));
                    P2SMod.LOGGER.warn("AgentLoop tool unknown -> player={}, session={}, tool={}", playerName, sessionId, toolName);
                }
            }
        }

        if (result.structureChanged) {
            session.current = working;
        }
        return result;
    }

    public static void handleSessionAction(ServerPlayer player, String action, String payload) {
        if (player == null || action == null) {
            return;
        }
        switch (action) {
            case "start" -> startSession(player);
            case "end" -> endSession(player);
            case "undo" -> undo(player);
            case "save" -> save(player, payload);
            default -> player.displayClientMessage(Component.literal("Unknown session action: " + action), false);
        }
    }

    public static Session startSession(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        Session session = createSession(player);
        sessions.put(player.getUUID(), session);
        sendSessionSync(player, session);
        P2SMod.LOGGER.info("Session start -> player={}, session={}, origin={}, size={}",
                player.getGameProfile().getName(), session.id, posString(session.origin), sizeString(session.size));
        player.displayClientMessage(Component.literal("Session started: " + session.id), false);
        return session;
    }

    public static void endSession(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Session removed = sessions.remove(player.getUUID());
        sendSessionSync(player, null);
        P2SMod.LOGGER.info("Session end -> player={}, session={}, turns={}, parts={}, blocks={}",
                player.getGameProfile().getName(),
                removed == null ? "-" : removed.id,
                removed == null ? 0 : removed.turnCount,
                removed == null || removed.current == null || removed.current.structures == null ? 0 : removed.current.structures.size(),
                removed == null ? 0 : countBlocks(removed.current));
        player.displayClientMessage(Component.literal("Session ended"), false);
    }

    public static void undo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null || session.versions.isEmpty()) {
            player.displayClientMessage(Component.literal("Nothing to undo"), false);
            return;
        }
        session.current = session.versions.pop();
        rebuildStructure(player, session);
        sendSessionSync(player, session);
        P2SMod.LOGGER.info("Session undo -> player={}, session={}, parts={}, blocks={}",
                player.getGameProfile().getName(),
                session.id,
                session.current == null || session.current.structures == null ? 0 : session.current.structures.size(),
                countBlocks(session.current));
        player.displayClientMessage(Component.literal("Undo applied"), false);
    }

    public static void save(ServerPlayer player, String name) {
        if (player == null) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null || session.current == null) {
            player.displayClientMessage(Component.literal("No active session or empty structure"), false);
            return;
        }
        String saved = ScriptStorage.saveV2("session", session.current, "session", name);
        P2SMod.LOGGER.info("Session save -> player={}, session={}, name={}",
                player.getGameProfile().getName(), session.id, saved);
        player.displayClientMessage(Component.literal("Saved session as " + saved), false);
    }

    public static Session getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    private static Session ensureSession(ServerPlayer player) {
        Session existing = sessions.get(player.getUUID());
        if (existing != null) {
            return existing;
        }
        return startSession(player);
    }

    private static Session createSession(ServerPlayer player) {
        SelectionManager.Selection sel = SelectionManager.get(player.getUUID());
        BlockPos origin = player.blockPosition();
        Vec3i size = null;
        if (sel != null && sel.isComplete()) {
            origin = sel.min();
            size = sel.size();
        }
        String systemPrompt = ModConfig.currentSystemPrompt();
        if (size != null) {
            systemPrompt = systemPrompt + "\n\n" + buildAreaConstraint(size);
        }

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);

        Session session = new Session();
        session.id = UUID.randomUUID().toString();
        session.origin = origin;
        session.size = size;
        session.history = new ArrayList<>();
        session.history.add(systemMsg);
        return session;
    }

    private static void rebuildStructure(ServerPlayer player, Session session) {
        if (player == null || session == null || session.current == null) {
            return;
        }
        ServerLevel world = player.serverLevel();
        if (session.size != null) {
            clearArea(world, session.origin, session.size);
        }
        StructureBuilder.buildV2(world, session.origin, session.current);
    }

    private static void clearArea(ServerLevel world, BlockPos origin, Vec3i size) {
        if (world == null || origin == null || size == null) {
            return;
        }
        int maxX = origin.getX() + size.getX() - 1;
        int maxY = origin.getY() + size.getY() - 1;
        int maxZ = origin.getZ() + size.getZ() - 1;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = origin.getX(); x <= maxX; x++) {
            for (int y = origin.getY(); y <= maxY; y++) {
                for (int z = origin.getZ(); z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    world.setBlockAndUpdate(mutable, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void trimHistory(Session session) {
        while (session.history.size() > MAX_HISTORY) {
            if (session.history.size() <= 1) {
                break;
            }
            session.history.remove(1);
        }
    }

    private static List<JsonObject> deepCopyMessages(List<JsonObject> source) {
        List<JsonObject> copy = new ArrayList<>(source.size());
        for (JsonObject msg : source) {
            copy.add(msg.deepCopy());
        }
        return copy;
    }

    private static StructureBuilder.VbsScriptV2 copyScript(StructureBuilder.VbsScriptV2 script) {
        return GSON.fromJson(GSON.toJson(script), StructureBuilder.VbsScriptV2.class);
    }

    private static String buildToolSummary(StructureBuilder.VbsScriptV2 script) {
        int parts = script == null || script.structures == null ? 0 : script.structures.size();
        int blocks = countBlocks(script);
        return "Structure applied: " + parts + " parts. " + blocks + " blocks.";
    }

    private static int countParts(StructureBuilder.VbsScriptV2 script) {
        return script == null || script.structures == null ? 0 : script.structures.size();
    }

    private static String buildStructureSummary(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        boolean truncated = false;
        Map<String, String> palette = script.palette == null ? Map.of() : script.palette;

        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.name == null) {
                continue;
            }
            if (lines.size() >= MAX_SUMMARY_LINES) {
                truncated = true;
                break;
            }
            lines.add("[" + part.name + "] priority=" + part.priority);
            if (part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (lines.size() >= MAX_SUMMARY_LINES) {
                    truncated = true;
                    break;
                }
                lines.add("- " + formatActionSummary(action, palette));
            }
            if (truncated) {
                break;
            }
        }

        if (truncated) {
            if (lines.isEmpty()) {
                lines.add("...");
            } else {
                lines.set(lines.size() - 1, "...");
            }
        }

        String text = String.join("\n", lines);
        if (text.length() > MAX_SUMMARY_CHARS) {
            int end = Math.max(0, MAX_SUMMARY_CHARS - 3);
            text = text.substring(0, end) + "...";
        }
        return text;
    }

    private static String formatActionSummary(StructureBuilder.VbsAction action, Map<String, String> palette) {
        if (action == null || action.type == null) {
            return "unknown";
        }
        String type = action.type.trim().toLowerCase();
        String block = formatBlock(action.block, palette);
        StringBuilder sb = new StringBuilder();
        sb.append(type);
        if (!block.isBlank()) {
            sb.append(" block=").append(block);
        }
        switch (type) {
            case "fill", "frame" -> {
                String range = formatRange(action.from, action.to);
                if (!range.isBlank()) {
                    sb.append(" ").append(range);
                }
            }
            case "set" -> {
                String at = formatAt(action.at);
                if (!at.isBlank()) {
                    sb.append(" ").append(at);
                }
            }
            default -> {
            }
        }
        if (action.facing != null && !action.facing.isBlank()) {
            sb.append(" facing=").append(action.facing.trim().toLowerCase());
        }
        return sb.toString();
    }

    private static String formatBlock(String block, Map<String, String> palette) {
        if (block == null || block.isBlank()) {
            return "";
        }
        if (palette == null || palette.isEmpty()) {
            return block;
        }
        String resolved = palette.get(block);
        if (resolved == null || resolved.isBlank() || resolved.equals(block)) {
            return block;
        }
        return block + " (" + resolved + ")";
    }

    private static String formatRange(List<Integer> from, List<Integer> to) {
        if (from == null || to == null || from.size() < 3 || to.size() < 3) {
            return "";
        }
        return "from " + formatVec(from) + " to " + formatVec(to);
    }

    private static String formatAt(List<List<Integer>> at) {
        if (at == null || at.isEmpty()) {
            return "";
        }
        int count = at.size();
        StringBuilder sb = new StringBuilder();
        sb.append("at ").append(count).append(" [");
        int sample = Math.min(3, count);
        for (int i = 0; i < sample; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(formatVec(at.get(i)));
        }
        if (count > sample) {
            sb.append("; ...");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatVec(List<Integer> vec) {
        if (vec == null || vec.size() < 3) {
            return "?";
        }
        return vec.get(0) + "," + vec.get(1) + "," + vec.get(2);
    }

    private static JsonObject buildToolSuccess(String tool) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", tool == null ? "" : tool);
        return payload;
    }

    private static JsonObject buildToolError(String tool, String error) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", tool == null ? "" : tool);
        payload.addProperty("error", error == null ? "" : error);
        return payload;
    }

    private static JsonObject buildToolMessage(LLMService.ToolCall call, JsonObject payload) {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        if (call != null && call.id() != null && !call.id().isBlank()) {
            toolMsg.addProperty("tool_call_id", call.id());
        }
        toolMsg.addProperty("content", payload == null ? "" : GSON.toJson(payload));
        return toolMsg;
    }

    private static JsonObject buildCurrentStructurePayload(StructureBuilder.VbsScriptV2 script) {
        JsonObject payload = new JsonObject();
        payload.addProperty("parts", countParts(script));
        payload.addProperty("blocks", countBlocks(script));
        if (script == null) {
            payload.addProperty("empty", true);
            return payload;
        }
        com.google.gson.JsonElement scriptJson = GSON.toJsonTree(script);
        String jsonText = GSON.toJson(scriptJson);
        if (jsonText.length() <= MAX_TOOL_JSON_CHARS) {
            payload.add("script", scriptJson);
        } else {
            payload.addProperty("truncated", true);
            payload.addProperty("script_json", jsonText.substring(0, MAX_TOOL_JSON_CHARS));
            payload.addProperty("summary", buildStructureSummary(script));
        }
        return payload;
    }

    private static class ToolCallProcessingResult {
        private boolean structureChanged = false;
        private final List<JsonObject> toolMessages = new ArrayList<>();
    }

    private static int safeLength(String text) {
        if (text == null) {
            return 0;
        }
        return text.trim().length();
    }

    private static String posString(BlockPos pos) {
        if (pos == null) {
            return "-";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String sizeString(Vec3i size) {
        if (size == null) {
            return "-";
        }
        return size.getX() + "x" + size.getY() + "x" + size.getZ();
    }

    private static int countBlocks(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null) {
            return 0;
        }
        int total = 0;
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.actions == null) {
                continue;
            }
            for (StructureBuilder.VbsAction action : part.actions) {
                if (action == null || action.type == null) {
                    continue;
                }
                switch (action.type.toLowerCase()) {
                    case "fill" -> total += countFill(action);
                    case "frame" -> total += countFrame(action);
                    case "set" -> total += action.at == null ? 0 : action.at.size();
                    default -> {
                    }
                }
            }
        }
        return total;
    }

    private static int countFill(StructureBuilder.VbsAction action) {
        if (action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        int dx = Math.abs(action.from.get(0) - action.to.get(0)) + 1;
        int dy = Math.abs(action.from.get(1) - action.to.get(1)) + 1;
        int dz = Math.abs(action.from.get(2) - action.to.get(2)) + 1;
        return dx * dy * dz;
    }

    private static int countFrame(StructureBuilder.VbsAction action) {
        if (action.from == null || action.to == null || action.from.size() < 3 || action.to.size() < 3) {
            return 0;
        }
        int dx = Math.abs(action.from.get(0) - action.to.get(0)) + 1;
        int dy = Math.abs(action.from.get(1) - action.to.get(1)) + 1;
        int dz = Math.abs(action.from.get(2) - action.to.get(2)) + 1;
        if (dx <= 2 || dy <= 2 || dz <= 2) {
            return dx * dy * dz;
        }
        int surface = 2 * (dx * dy + dx * dz + dy * dz) - 4 * (dx + dy + dz) + 8;
        return Math.max(surface, 0);
    }

    private static void sendSessionSync(ServerPlayer player, Session session) {
        if (player == null) {
            return;
        }
        boolean active = session != null;
        String sessionId = active ? session.id : "";
        int turns = active ? session.turnCount : 0;
        int partCount = active ? countParts(session.current) : 0;
        int totalBlocks = active ? countBlocks(session.current) : 0;
        String summary = active ? partsSummary(session.current) : "";
        String structureSummary = active ? buildStructureSummary(session.current) : "";

        ServerNetworkHandler.sendToClient(player, new S2CSessionSyncPayload(
                active,
                sessionId,
                turns,
                partCount,
                totalBlocks,
                summary,
                structureSummary
        ));
    }

    private static String partsSummary(StructureBuilder.VbsScriptV2 script) {
        if (script == null || script.structures == null || script.structures.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (StructureBuilder.StructurePart part : script.structures) {
            if (part == null || part.name == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.name);
        }
        return sb.toString();
    }

    private static void sendChatResponse(ServerPlayer player, String text, boolean hasStructure, String status) {
        ServerNetworkHandler.sendToClient(player, new S2CChatResponsePayload(
                text == null ? "" : text,
                hasStructure,
                status == null ? "" : status
        ));
    }

    static String buildAreaConstraint(Vec3i size) {
        int sizeX = size.getX();
        int sizeY = size.getY();
        int sizeZ = size.getZ();
        return "## Build Area\n" +
                "The structure must fit within a " + sizeX + "x" + sizeY + "x" + sizeZ + " region.\n" +
                "Max coordinates: (" + (sizeX - 1) + ", " + (sizeY - 1) + ", " + (sizeZ - 1) + ").";
    }

    public static class Session {
        String id;
        BlockPos origin;
        Vec3i size;
        List<JsonObject> history;
        Deque<StructureBuilder.VbsScriptV2> versions = new ArrayDeque<>();
        StructureBuilder.VbsScriptV2 current;
        int turnCount = 0;
        boolean inFlight = false;
    }
}
