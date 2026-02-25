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

    private SessionManager() {
    }

    public static void handleChatMessage(ServerPlayer player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }

        Session session = ensureSession(player);
        if (session.inFlight) {
            sendChatResponse(player, "Busy with previous request.", false, "error");
            return;
        }

        session.inFlight = true;

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", message.trim());
        session.history.add(userMsg);
        trimHistory(session);

        sendChatResponse(player, "", false, "thinking");

        List<JsonObject> historySnapshot = deepCopyMessages(session.history);

        LLMService.requestWithHistory(historySnapshot).thenAccept(result -> {
            MinecraftServer server = player.getServer();
            server.execute(() -> {
                session.inFlight = false;
                session.turnCount += 1;

                JsonObject assistant = result.rawAssistantMessage().deepCopy();
                session.history.add(assistant);
                trimHistory(session);

                boolean hasStructure = result.script() != null;
                if (hasStructure) {
                    if (session.current != null) {
                        session.versions.push(copyScript(session.current));
                    }
                    session.current = StructureBuilder.mergeScripts(session.current, result.script());
                    sendChatResponse(player, result.textContent(), true, "building");
                    rebuildStructure(player, session);
                    sendChatResponse(player, "", true, "done");

                    if (result.toolCallId() != null && !result.toolCallId().isBlank()) {
                        JsonObject toolMsg = new JsonObject();
                        toolMsg.addProperty("role", "tool");
                        toolMsg.addProperty("tool_call_id", result.toolCallId());
                        toolMsg.addProperty("content", buildToolSummary(session.current));
                        session.history.add(toolMsg);
                        trimHistory(session);
                    }
                } else {
                    sendChatResponse(player, result.textContent(), false, "done");
                }

                sendSessionSync(player, session);
            });
        }).exceptionally(ex -> {
            MinecraftServer server = player.getServer();
            server.execute(() -> {
                session.inFlight = false;
                sendChatResponse(player, "Request failed: " + ex.getMessage(), false, "error");
            });
            return null;
        });
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
        player.displayClientMessage(Component.literal("Session started: " + session.id), false);
        return session;
    }

    public static void endSession(ServerPlayer player) {
        if (player == null) {
            return;
        }
        sessions.remove(player.getUUID());
        sendSessionSync(player, null);
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
        int partCount = active && session.current != null && session.current.structures != null ? session.current.structures.size() : 0;
        int totalBlocks = active ? countBlocks(session.current) : 0;
        String summary = active ? partsSummary(session.current) : "";

        ServerNetworkHandler.sendToClient(player, new S2CSessionSyncPayload(
                active,
                sessionId,
                turns,
                partCount,
                totalBlocks,
                summary
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
