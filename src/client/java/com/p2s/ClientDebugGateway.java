package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.store.SessionPersistence;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientDebugGateway {
    private static final Gson GSON = new Gson();
    private static final String ROOT_PATH = "/debug/agent";
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_EVENT_HISTORY = 256;
    private static final long MONITOR_INTERVAL_MS = 150L;
    private static final long SSE_KEEPALIVE_MS = 15_000L;
    private static final AtomicLong NEXT_JOB_ID = new AtomicLong();
    private static final ConcurrentHashMap<String, DebugJob> JOBS = new ConcurrentHashMap<>();
    private static final Object START_STOP_LOCK = new Object();

    private static volatile HttpServer server;
    private static volatile ScheduledExecutorService monitorExecutor;
    private static volatile DebugJob currentJob;

    private ClientDebugGateway() {
    }

    public static void startIfEnabled() {
        if (!P2SMod.DEBUG || !P2SClientConfig.isDebugGatewayEnabled()) {
            return;
        }

        synchronized (START_STOP_LOCK) {
            if (server != null) {
                return;
            }
            try {
                String configuredHost = P2SClientConfig.getDebugGatewayHost();
                int port = P2SClientConfig.getDebugGatewayPort();
                InetSocketAddress address = createBindAddress(configuredHost, port);
                HttpServer created = HttpServer.create(address, 0);
                created.createContext(ROOT_PATH, new RootHandler());
                created.setExecutor(Executors.newCachedThreadPool(namedFactory("p2s-debug-http")));
                created.start();

                ScheduledExecutorService createdMonitor = Executors.newSingleThreadScheduledExecutor(
                        namedFactory("p2s-debug-monitor")
                );
                createdMonitor.scheduleAtFixedRate(
                        ClientDebugGateway::pollCurrentJob,
                        MONITOR_INTERVAL_MS,
                        MONITOR_INTERVAL_MS,
                        TimeUnit.MILLISECONDS
                );

                server = created;
                monitorExecutor = createdMonitor;
                P2SMod.LOGGER.info(
                        "P2S debug gateway listening on {}{} (configured host={})",
                        bindDisplayHost(configuredHost, port),
                        ROOT_PATH,
                        configuredHost
                );
            } catch (Exception e) {
                P2SMod.LOGGER.warn("Failed to start P2S debug gateway: {}", e.getMessage());
            }
        }
    }

    private static InetSocketAddress createBindAddress(String configuredHost, int port) {
        if (isWildcardHost(configuredHost)) {
            return new InetSocketAddress(port);
        }
        return new InetSocketAddress(configuredHost, port);
    }

    private static boolean isWildcardHost(String configuredHost) {
        if (configuredHost == null || configuredHost.isBlank()) {
            return true;
        }
        String normalized = configuredHost.trim();
        return "*".equals(normalized)
                || "0.0.0.0".equals(normalized)
                || "::".equals(normalized)
                || "[::]".equals(normalized);
    }

    private static String bindDisplayHost(String configuredHost, int port) {
        if (isWildcardHost(configuredHost)) {
            return "http://0.0.0.0:" + port;
        }
        return "http://" + configuredHost + ":" + port;
    }

    private static ThreadFactory namedFactory(String baseName) {
        return r -> {
            Thread t = new Thread(r, baseName);
            t.setDaemon(true);
            return t;
        };
    }

    private static void pollCurrentJob() {
        DebugJob job = currentJob;
        if (job == null || job.isTerminal()) {
            return;
        }

        try {
            GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
            processSnapshot(job, snapshot);
        } catch (TimeoutException e) {
            failJob(job, "Timed out while reading client state.");
        } catch (Exception e) {
            failJob(job, "Failed reading client state: " + safeMessage(e));
        }
    }

    private static GatewaySnapshot captureSnapshotOnClient() {
        ClientSessionState.DebugSnapshot session = ClientSessionState.debugSnapshot();
        boolean busy = ClientAgentManager.isBusy();
        boolean serverBridgeReady = ClientServerBridge.hasRequiredServer();
        String effectiveSessionId = ClientAgentManager.debugCurrentSessionId();
        JsonObject subagents = emptySubagentsPayload();
        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            subagents = SubagentManager.listSubagents(effectiveSessionId, false);
        }
        return new GatewaySnapshot(
                session,
                busy,
                serverBridgeReady,
                effectiveSessionId == null ? "" : effectiveSessionId,
                subagents,
                ClientSelectionManager.getPos1(),
                ClientSelectionManager.getPos2()
        );
    }

    private static void processSnapshot(DebugJob job, GatewaySnapshot snapshot) {
        synchronized (job) {
            if (job.isTerminal()) {
                return;
            }

            if (!snapshot.effectiveSessionId().isBlank()) {
                job.sessionId = snapshot.effectiveSessionId();
            }

            ClientSessionState.DebugSnapshot session = snapshot.session();
            String statusText = safe(session.status());
            if (!Objects.equals(job.clientStatus, statusText)) {
                job.clientStatus = statusText;
                JsonObject payload = new JsonObject();
                payload.addProperty("status", statusText);
                job.addEventLocked("status.changed", payload);
            }

            String streamingText = safe(session.streamingText());
            if (streamingText.length() < job.lastStreamingText.length()) {
                job.lastStreamingText = "";
            }
            if (streamingText.length() > job.lastStreamingText.length()) {
                String delta = streamingText.substring(job.lastStreamingText.length());
                if (!delta.isEmpty()) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("delta", delta);
                    job.addEventLocked("assistant.delta", payload);
                }
            }
            job.lastStreamingText = streamingText;

            List<ClientSessionState.ChatMessage> messages = session.messages();
            int startIndex = Math.max(0, Math.min(job.seenMessageCount, messages.size()));
            for (int i = startIndex; i < messages.size(); i++) {
                ClientSessionState.ChatMessage message = messages.get(i);
                if (message == null || !P2SI18n.isAssistantRole(message.role())) {
                    continue;
                }
                job.lastAssistantText = safe(message.text());
                JsonObject payload = new JsonObject();
                payload.addProperty("id", safe(message.id()));
                payload.addProperty("text", job.lastAssistantText);
                payload.addProperty("kind", safe(message.kind()));
                payload.addProperty("detail", safe(message.detail()));
                job.addEventLocked("assistant.message", payload);
            }
            job.seenMessageCount = messages.size();

            JsonObject planJson = buildPlanJson(session);
            String planFingerprint = GSON.toJson(planJson);
            if (!Objects.equals(job.lastPlanFingerprint, planFingerprint)) {
                job.lastPlanFingerprint = planFingerprint;
                job.lastPlan = planJson.deepCopy();
                job.addEventLocked("plan.updated", planJson);
            }

            JsonObject patchJson = buildPendingPatchJson(session);
            String patchFingerprint = patchJson == null ? "" : GSON.toJson(patchJson);
            if (!Objects.equals(job.lastPatchFingerprint, patchFingerprint)) {
                job.lastPatchFingerprint = patchFingerprint;
                job.lastPendingPatch = patchJson == null ? null : patchJson.deepCopy();
                job.addEventLocked(patchJson == null ? "patch.cleared" : "patch.pending", patchJson == null ? new JsonObject() : patchJson);
            }

            JsonObject choiceJson = buildPendingChoiceJson(session.pendingChoice());
            String choiceFingerprint = choiceJson == null ? "" : GSON.toJson(choiceJson);
            if (!Objects.equals(job.lastChoiceFingerprint, choiceFingerprint)) {
                job.lastChoiceFingerprint = choiceFingerprint;
                job.lastPendingChoice = choiceJson == null ? null : choiceJson.deepCopy();
                job.addEventLocked(choiceJson == null ? "choice.cleared" : "choice.pending", choiceJson == null ? new JsonObject() : choiceJson);
            }

            JsonObject subagentsJson = snapshot.subagents();
            String subagentFingerprint = GSON.toJson(subagentsJson);
            if (!Objects.equals(job.lastSubagentFingerprint, subagentFingerprint)) {
                job.lastSubagentFingerprint = subagentFingerprint;
                job.lastSubagents = subagentsJson.deepCopy();
                job.addEventLocked("subagents.updated", subagentsJson);
            }

            if (snapshot.busy()) {
                markRunningLocked(job);
                return;
            }
            if (job.cancelled) {
                completeTerminalLocked(job, "cancelled", "job.cancelled", "");
                return;
            }
            if (session.pendingChoice() != null && session.pendingChoice().options() != null && !session.pendingChoice().options().isEmpty()) {
                job.status = "awaiting_choice";
                return;
            }
            if (session.hasPendingPatch()) {
                job.status = "awaiting_patch";
                return;
            }
            if ("error".equalsIgnoreCase(statusText)) {
                completeTerminalLocked(job, "failed", "job.failed", failureMessage(job));
                return;
            }
            if (job.startedAt > 0L) {
                completeTerminalLocked(job, "completed", "job.completed", "");
            }
        }
    }

    private static void markRunningLocked(DebugJob job) {
        if (job.startedAt <= 0L) {
            job.startedAt = System.currentTimeMillis();
            job.addEventLocked("job.started", new JsonObject());
        }
        job.status = "running";
    }

    private static String failureMessage(DebugJob job) {
        if (job.lastAssistantText != null && !job.lastAssistantText.isBlank()) {
            return job.lastAssistantText;
        }
        if (job.clientStatus != null && !job.clientStatus.isBlank()) {
            return job.clientStatus;
        }
        return "Agent request failed.";
    }

    private static void failJob(DebugJob job, String message) {
        synchronized (job) {
            if (job.isTerminal()) {
                return;
            }
            completeTerminalLocked(job, "failed", "job.failed", safe(message));
        }
    }

    private static void completeTerminalLocked(DebugJob job, String status, String eventType, String error) {
        if (job.isTerminal()) {
            return;
        }
        job.status = status;
        job.error = safe(error);
        job.endedAt = System.currentTimeMillis();
        JsonObject payload = new JsonObject();
        if (!job.error.isBlank()) {
            payload.addProperty("error", job.error);
        }
        job.addEventLocked(eventType, payload);
        synchronized (START_STOP_LOCK) {
            if (currentJob == job) {
                currentJob = null;
            }
        }
    }

    private static JsonObject emptySubagentsPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("count", 0);
        payload.add("subagents", new JsonArray());
        return payload;
    }

    private static JsonObject buildPlanJson(ClientSessionState.DebugSnapshot snapshot) {
        JsonObject plan = new JsonObject();
        plan.addProperty("explanation", safe(snapshot.planExplanation()));
        JsonArray items = new JsonArray();
        if (snapshot.planItems() != null) {
            for (ClientSessionState.PlanItem item : snapshot.planItems()) {
                if (item == null) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("step", safe(item.step()));
                entry.addProperty("status", safe(item.status()));
                items.add(entry);
            }
        }
        plan.add("items", items);
        return plan;
    }

    private static JsonObject buildPendingPatchJson(ClientSessionState.DebugSnapshot snapshot) {
        if (!snapshot.hasPendingPatch()) {
            return null;
        }
        JsonObject patch = new JsonObject();
        patch.addProperty("path", safe(snapshot.pendingPath()));
        patch.addProperty("summary", safe(snapshot.pendingSummary()));
        patch.addProperty("risk", safe(snapshot.pendingRisk()));
        patch.addProperty("changed_blocks", snapshot.pendingChangedBlocks());
        patch.addProperty("preview_summary", safe(snapshot.previewSummary()));
        patch.addProperty("preview_detail", safe(snapshot.previewDetail()));
        patch.addProperty("preview_risk", safe(snapshot.previewRisk()));
        patch.addProperty("preview_changed_blocks", snapshot.previewChangedBlocks());
        return patch;
    }

    private static JsonObject buildPendingChoiceJson(ClientSessionState.ChoiceRequest choice) {
        if (choice == null || choice.options() == null || choice.options().isEmpty()) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("request_id", safe(choice.requestId()));
        payload.addProperty("prompt", safe(choice.prompt()));
        JsonArray options = new JsonArray();
        for (ClientSessionState.ChoiceOption option : choice.options()) {
            if (option == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", safe(option.id()));
            item.addProperty("label", safe(option.label()));
            item.addProperty("description", safe(option.description()));
            options.add(item);
        }
        payload.add("options", options);
        return payload;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                addCommonHeaders(exchange);
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                route(exchange);
            } catch (RequestFailure e) {
                sendJson(exchange, e.statusCode, errorPayload(e.errorCode, e.getMessage()));
            } catch (Exception e) {
                P2SMod.LOGGER.warn("Debug gateway request failed: {}", safeMessage(e));
                sendJson(exchange, 500, errorPayload("internal_error", safeMessage(e)));
            } finally {
                if (!isSse(exchange)) {
                    exchange.close();
                }
            }
        }
    }

    private static void route(HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod() == null ? "" : exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = normalizePath(exchange.getRequestURI().getPath());
        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] Debug gateway request -> method={}, path={}", method, path);
        }

        if (ROOT_PATH.equals(path) || (ROOT_PATH + "/").equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET on /debug/agent/state or POST on /debug/agent/jobs."));
                return;
            }
            handleState(exchange);
            return;
        }
        if ((ROOT_PATH + "/state").equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET for state."));
                return;
            }
            handleState(exchange);
            return;
        }
        if ((ROOT_PATH + "/capabilities").equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET for capabilities."));
                return;
            }
            handleCapabilities(exchange);
            return;
        }
        if ((ROOT_PATH + "/selection").equals(path)) {
            if ("GET".equals(method)) {
                handleSelection(exchange);
                return;
            }
            if ("POST".equals(method)) {
                handleSetSelection(exchange);
                return;
            }
            sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET or POST on /debug/agent/selection."));
            return;
        }
        if ((ROOT_PATH + "/selection/clear").equals(path) && "POST".equals(method)) {
            handleClearSelection(exchange);
            return;
        }
        if ((ROOT_PATH + "/tools").equals(path)) {
            if ("GET".equals(method)) {
                handleListTools(exchange);
                return;
            }
            sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET on /debug/agent/tools."));
            return;
        }
        if ((ROOT_PATH + "/tools/call").equals(path)) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, errorPayload("method_not_allowed", "Use POST on /debug/agent/tools/call."));
                return;
            }
            handleToolCall(exchange);
            return;
        }
        if ((ROOT_PATH + "/project/state").equals(path)) {
            if (!"GET".equals(method)) {
                sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET on /debug/agent/project/state."));
                return;
            }
            handleProjectState(exchange);
            return;
        }
        if ((ROOT_PATH + "/workspaces").equals(path)) {
            if ("GET".equals(method)) {
                handleListWorkspaces(exchange);
                return;
            }
            sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET on /debug/agent/workspaces."));
            return;
        }
        if ((ROOT_PATH + "/workspaces/select").equals(path) && "POST".equals(method)) {
            handleSelectWorkspace(exchange);
            return;
        }
        if ((ROOT_PATH + "/jobs").equals(path)) {
            if (!"POST".equals(method)) {
                sendJson(exchange, 405, errorPayload("method_not_allowed", "Use POST to create a job."));
                return;
            }
            handleCreateJob(exchange);
            return;
        }
        if ((ROOT_PATH + "/projects").equals(path)) {
            if ("GET".equals(method)) {
                handleListProjects(exchange);
                return;
            }
            sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET on /debug/agent/projects."));
            return;
        }
        if ((ROOT_PATH + "/projects/create").equals(path) && "POST".equals(method)) {
            handleCreateProject(exchange);
            return;
        }
        if ((ROOT_PATH + "/projects/open").equals(path) && "POST".equals(method)) {
            handleOpenProject(exchange);
            return;
        }
        if ((ROOT_PATH + "/projects/update").equals(path) && "POST".equals(method)) {
            handleUpdateProject(exchange);
            return;
        }
        if ((ROOT_PATH + "/projects/delete").equals(path) && "POST".equals(method)) {
            handleDeleteProject(exchange);
            return;
        }
        if ((ROOT_PATH + "/sessions").equals(path)) {
            if ("GET".equals(method)) {
                handleListSessions(exchange);
                return;
            }
            sendJson(exchange, 405, errorPayload("method_not_allowed", "Use GET on /debug/agent/sessions."));
            return;
        }
        if ((ROOT_PATH + "/sessions/create").equals(path) && "POST".equals(method)) {
            handleCreateSession(exchange);
            return;
        }
        if ((ROOT_PATH + "/sessions/update").equals(path) && "POST".equals(method)) {
            handleUpdateSession(exchange);
            return;
        }
        if ((ROOT_PATH + "/sessions/switch").equals(path) && "POST".equals(method)) {
            handleSwitchSession(exchange);
            return;
        }
        if (!path.startsWith(ROOT_PATH + "/jobs/")) {
            sendJson(exchange, 404, errorPayload("not_found", "Unknown debug gateway endpoint."));
            return;
        }

        String suffix = path.substring((ROOT_PATH + "/jobs/").length());
        String[] parts = splitPath(suffix);
        if (parts.length == 1 && "GET".equals(method)) {
            handleGetJob(exchange, parts[0]);
            return;
        }
        if (parts.length == 2 && "events".equals(parts[1]) && "GET".equals(method)) {
            handleJobEvents(exchange, parts[0]);
            return;
        }
        if (parts.length == 2 && "cancel".equals(parts[1]) && "POST".equals(method)) {
            handleCancelJob(exchange, parts[0]);
            return;
        }
        if (parts.length == 3 && "patch".equals(parts[1]) && "apply".equals(parts[2]) && "POST".equals(method)) {
            handlePatchApply(exchange, parts[0]);
            return;
        }
        if (parts.length == 3 && "patch".equals(parts[1]) && "discard".equals(parts[2]) && "POST".equals(method)) {
            handlePatchDiscard(exchange, parts[0]);
            return;
        }
        if (parts.length == 3 && "choice".equals(parts[1]) && "select".equals(parts[2]) && "POST".equals(method)) {
            handleChoiceSelect(exchange, parts[0]);
            return;
        }
        if (parts.length == 3 && "choice".equals(parts[1]) && "custom".equals(parts[2]) && "POST".equals(method)) {
            handleChoiceCustom(exchange, parts[0]);
            return;
        }
        sendJson(exchange, 404, errorPayload("not_found", "Unknown debug job endpoint."));
    }

    private static void handleState(HttpExchange exchange) throws Exception {
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        sendJson(exchange, 200, buildStatePayload(snapshot));
    }

    private static void handleCapabilities(HttpExchange exchange) throws Exception {
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("api_version", 1);
        payload.addProperty("root_path", ROOT_PATH);
        payload.addProperty("max_body_bytes", MAX_BODY_BYTES);
        payload.addProperty("sse_enabled", P2SClientConfig.getDebugGatewayExposeSse());
        payload.addProperty("server_bridge_ready", snapshot.serverBridgeReady());
        payload.add("state", buildStatePayload(snapshot));
        payload.add("endpoints", toStringArray(List.of(
                "GET /debug/agent/state",
                "GET /debug/agent/capabilities",
                "GET /debug/agent/selection",
                "POST /debug/agent/selection",
                "POST /debug/agent/selection/clear",
                "GET /debug/agent/tools",
                "POST /debug/agent/tools/call",
                "GET /debug/agent/project/state",
                "GET /debug/agent/workspaces",
                "POST /debug/agent/workspaces/select",
                "GET /debug/agent/projects",
                "POST /debug/agent/projects/create",
                "POST /debug/agent/projects/open",
                "POST /debug/agent/projects/update",
                "POST /debug/agent/projects/delete",
                "GET /debug/agent/sessions",
                "POST /debug/agent/sessions/create",
                "POST /debug/agent/sessions/update",
                "POST /debug/agent/sessions/switch",
                "POST /debug/agent/jobs",
                "GET /debug/agent/jobs/{jobId}",
                "GET /debug/agent/jobs/{jobId}/events",
                "POST /debug/agent/jobs/{jobId}/cancel",
                "POST /debug/agent/jobs/{jobId}/patch/apply",
                "POST /debug/agent/jobs/{jobId}/patch/discard",
                "POST /debug/agent/jobs/{jobId}/choice/select",
                "POST /debug/agent/jobs/{jobId}/choice/custom"
        )));
        payload.add("tool_bridge_tools", knownToolNames());
        sendJson(exchange, 200, payload);
    }

    private static void handleSelection(HttpExchange exchange) throws Exception {
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.add("selection", buildSelectionPayload(snapshot.selectionPos1(), snapshot.selectionPos2()));
        sendJson(exchange, 200, payload);
    }

    private static void handleSetSelection(HttpExchange exchange) throws Exception {
        JsonObject body = parseBodyObject(exchange);
        if (getBoolean(body, "clear", false)) {
            handleClearSelection(exchange);
            return;
        }

        BlockPos pos1 = parseBlockPos(body, "pos1");
        BlockPos pos2 = parseBlockPos(body, "pos2");
        if (pos1 == null && pos2 == null) {
            Integer point = getOptionalInt(body, "point");
            if (point == null) {
                point = getOptionalInt(body, "point_index");
            }
            BlockPos pointPos = parseBlockPos(body, "pos");
            if (pointPos == null) {
                pointPos = parseBlockPos(body, null);
            }
            if (point != null && pointPos != null) {
                if (point == 0 || point == 1) {
                    pos1 = point == 0 ? pointPos : null;
                    pos2 = point == 1 ? pointPos : null;
                } else {
                    sendJson(exchange, 400, errorPayload("invalid_request", "point must be 0 or 1."));
                    return;
                }
            }
        }
        if (pos1 == null && pos2 == null) {
            sendJson(exchange, 400, errorPayload("invalid_request", "Provide pos1/pos2 or point plus pos."));
            return;
        }

        BlockPos finalPos1 = pos1;
        BlockPos finalPos2 = pos2;
        SelectionUpdateResult result = callOnClientThread(() -> updateSelectionOnClient(finalPos1, finalPos2, false));
        if (!result.ok()) {
            sendJson(exchange, result.statusCode(), errorPayload(result.errorCode(), result.message()));
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "set_selection");
        payload.add("selection", buildSelectionPayload(result.pos1(), result.pos2()));
        sendJson(exchange, 200, payload);
    }

    private static void handleClearSelection(HttpExchange exchange) throws Exception {
        SelectionUpdateResult result = callOnClientThread(() -> updateSelectionOnClient(null, null, true));
        if (!result.ok()) {
            sendJson(exchange, result.statusCode(), errorPayload(result.errorCode(), result.message()));
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "clear_selection");
        payload.add("selection", buildSelectionPayload(result.pos1(), result.pos2()));
        sendJson(exchange, 200, payload);
    }

    private static void handleListTools(HttpExchange exchange) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.add("tools", knownToolNames());
        sendJson(exchange, 200, payload);
    }

    private static void handleToolCall(HttpExchange exchange) throws Exception {
        JsonObject body = parseBodyObject(exchange);
        String toolName = getString(body, "tool_name");
        if (toolName.isBlank()) {
            toolName = getString(body, "tool");
        }
        if (toolName.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "tool_name is required."));
            return;
        }

        JsonObject args = getObject(body, "arguments");
        if (args == null) {
            args = getObject(body, "args");
        }
        JsonObject result = callToolBridge(toolName, args == null ? new JsonObject() : args);
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }
        sendJson(exchange, 200, result);
    }

    private static void handleProjectState(HttpExchange exchange) throws Exception {
        JsonObject result = callToolBridge("get_project_state", new JsonObject());
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }
        sendJson(exchange, 200, result);
    }

    private static void handleListWorkspaces(HttpExchange exchange) throws Exception {
        JsonObject result = callToolBridge("get_project_state", new JsonObject());
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "list_workspaces");
        payload.addProperty("selected_workspace_path", safe(snapshot.session().selectedWorkspacePath()));
        payload.add("project", result.has("project") ? result.get("project").deepCopy() : new JsonObject());
        payload.add("workspace_files", result.has("workspace_files") ? result.get("workspace_files").deepCopy() : new JsonArray());
        payload.add("pending_paths", result.has("pending_paths") ? result.get("pending_paths").deepCopy() : new JsonArray());
        sendJson(exchange, 200, payload);
    }

    private static void handleSelectWorkspace(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        String path = getString(body, "path");
        if (path.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "path is required."));
            return;
        }

        boolean selected = callOnClientThread(() -> ClientAgentManager.selectWorkspacePath(path));
        if (!selected) {
            sendJson(exchange, 422, errorPayload("invalid_workspace", "path is invalid for the current project."));
            return;
        }
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "select_workspace");
        payload.addProperty("selected_workspace_path", safe(snapshot.session().selectedWorkspacePath()));
        payload.add("state", buildStatePayload(snapshot));
        sendJson(exchange, 200, payload);
    }

    private static void handleListProjects(HttpExchange exchange) throws Exception {
        JsonObject result = callToolBridge("list_projects", new JsonObject());
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }
        sendJson(exchange, 200, result);
    }

    private static void handleCreateProject(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        JsonObject args = new JsonObject();
        String name = getString(body, "name");
        String description = getString(body, "description");
        if (!name.isBlank()) {
            args.addProperty("name", name);
        }
        if (!description.isBlank()) {
            args.addProperty("description", description);
        }

        JsonObject result = callToolBridge("create_project", args);
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }

        callOnClientThread(() -> {
            ClientAgentManager.onProjectChanged();
            return null;
        });
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "create_project");
        payload.add("result", result);
        payload.add("state", buildStatePayload(snapshot));
        sendJson(exchange, 200, payload);
    }

    private static void handleOpenProject(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        String projectId = getString(body, "id");
        if (projectId.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "id is required."));
            return;
        }

        JsonObject result = openProjectById(projectId);
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "open_project");
        payload.add("result", result);
        payload.add("state", buildStatePayload(snapshot));
        sendJson(exchange, 200, payload);
    }

    private static void handleUpdateProject(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        String projectId = getString(body, "id");
        if (projectId.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "id is required."));
            return;
        }

        JsonObject args = new JsonObject();
        args.addProperty("id", projectId);
        if (body.has("name")) {
            args.addProperty("name", getString(body, "name"));
        }
        if (body.has("description")) {
            args.addProperty("description", getString(body, "description"));
        }

        JsonObject result = callToolBridge("rename_project", args);
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "update_project");
        payload.add("result", result);
        sendJson(exchange, 200, payload);
    }

    private static void handleDeleteProject(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        String projectId = getString(body, "id");
        if (projectId.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "id is required."));
            return;
        }

        GatewaySnapshot before = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject args = new JsonObject();
        args.addProperty("id", projectId);
        JsonObject result = callToolBridge("delete_project", args);
        if (!isToolOk(result)) {
            sendJson(exchange, 422, result);
            return;
        }

        if (projectId.equals(safe(before.session().projectId()))) {
            callOnClientThread(() -> {
                ClientAgentManager.onProjectChanged();
                return null;
            });
        }
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "delete_project");
        payload.add("result", result);
        payload.add("state", buildStatePayload(snapshot));
        sendJson(exchange, 200, payload);
    }

    private static void handleListSessions(HttpExchange exchange) throws Exception {
        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        String projectId = safe(snapshot.session().projectId());
        List<SessionPersistence.SessionIndexEntry> sessions = projectId.isBlank()
                ? SessionPersistence.listSessions()
                : SessionPersistence.listSessions(projectId);

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("project_id", projectId);
        payload.addProperty("active_session_id", safe(snapshot.effectiveSessionId()));
        JsonArray items = new JsonArray();
        for (SessionPersistence.SessionIndexEntry entry : sessions) {
            if (entry == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", safe(entry.id()));
            item.addProperty("project_id", safe(entry.projectId()));
            item.addProperty("title", safe(entry.title()));
            item.addProperty("created_at", entry.createdAt());
            item.addProperty("updated_at", entry.updatedAt());
            item.addProperty("message_count", entry.messageCount());
            item.addProperty("active", safe(snapshot.effectiveSessionId()).equals(safe(entry.id())));
            items.add(item);
        }
        payload.add("sessions", items);
        sendJson(exchange, 200, payload);
    }

    private static void handleCreateSession(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        String requestedProjectId = getString(body, "project_id");
        String selectedWorkspacePath = getString(body, "selected_workspace_path");
        boolean startNow = getBoolean(body, "start_now", true);

        GatewaySnapshot before = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        if (!requestedProjectId.isBlank() && !requestedProjectId.equals(safe(before.session().projectId()))) {
            JsonObject openResult = openProjectById(requestedProjectId);
            if (!isToolOk(openResult)) {
                sendJson(exchange, 422, openResult);
                return;
            }
        }

        callOnClientThread(() -> {
            ClientAgentManager.newSession();
            return null;
        });
        if (!selectedWorkspacePath.isBlank()) {
            boolean selected = callOnClientThread(() -> ClientAgentManager.selectWorkspacePath(selectedWorkspacePath));
            if (!selected) {
                sendJson(exchange, 422, errorPayload("invalid_workspace", "selected_workspace_path is invalid for current project."));
                return;
            }
        }

        String sessionId = "";
        if (startNow) {
            ClientAgentManager.SessionStartResult start = callOnClientThread(ClientAgentManager::ensureSessionStartedForHttp);
            if (!start.ok()) {
                sendJson(exchange, 409, errorPayload("session_create_failed", safe(start.error())));
                return;
            }
            sessionId = safe(start.sessionId());
        }

        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "create_session");
        payload.addProperty("session_id", sessionId.isBlank() ? safe(snapshot.effectiveSessionId()) : sessionId);
        payload.add("state", buildStatePayload(snapshot));
        sendJson(exchange, 200, payload);
    }

    private static void handleUpdateSession(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        String sessionId = getString(body, "id");
        if (sessionId.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "id is required."));
            return;
        }

        String title = getOptionalString(body, "title");
        String projectId = getOptionalString(body, "project_id");
        String selectedWorkspacePath = getOptionalString(body, "selected_workspace_path");
        if (title == null && projectId == null && selectedWorkspacePath == null) {
            sendJson(exchange, 400, errorPayload("invalid_request", "At least one of title, project_id, selected_workspace_path is required."));
            return;
        }

        SessionPersistence.SavedSession saved = SessionPersistence.loadSession(sessionId);
        if (saved == null) {
            sendJson(exchange, 404, errorPayload("not_found", "Unknown session id."));
            return;
        }

        String activeSessionId = callOnClientThread(ClientAgentManager::debugCurrentSessionId);
        boolean active = sessionId.equals(safe(activeSessionId));
        if (active && projectId != null && !projectId.equals(safe(saved.projectId()))) {
            sendJson(exchange, 409, errorPayload("invalid_request", "Cannot change project_id of active session."));
            return;
        }

        SessionPersistence.SavedSession updated = new SessionPersistence.SavedSession(
                saved.id(),
                projectId == null ? safe(saved.projectId()) : projectId,
                title == null ? safe(saved.title()) : title,
                saved.createdAt(),
                System.currentTimeMillis(),
                saved.messageCount(),
                saved.llmHistory(),
                saved.chatLog(),
                saved.planItems(),
                saved.planExplanation(),
                selectedWorkspacePath == null ? safe(saved.selectedWorkspacePath()) : selectedWorkspacePath,
                saved.pendingChoice()
        );
        SessionPersistence.saveSession(updated);

        if (active && selectedWorkspacePath != null) {
            boolean switched = callOnClientThread(() -> ClientAgentManager.selectWorkspacePath(selectedWorkspacePath));
            if (!switched) {
                sendJson(exchange, 422, errorPayload("invalid_workspace", "selected_workspace_path is invalid for current project."));
                return;
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "update_session");
        payload.addProperty("id", safe(updated.id()));
        payload.addProperty("project_id", safe(updated.projectId()));
        payload.addProperty("title", safe(updated.title()));
        payload.addProperty("selected_workspace_path", safe(updated.selectedWorkspacePath()));
        payload.addProperty("updated_at", updated.updatedAt());
        payload.addProperty("active", active);
        sendJson(exchange, 200, payload);
    }

    private static void handleSwitchSession(HttpExchange exchange) throws Exception {
        requireNoActiveExternalJob();
        JsonObject body = parseBodyObject(exchange);
        String sessionId = getString(body, "id");
        if (sessionId.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "id is required."));
            return;
        }

        SessionPersistence.SavedSession saved = SessionPersistence.loadSession(sessionId);
        if (saved == null) {
            sendJson(exchange, 404, errorPayload("not_found", "Unknown session id."));
            return;
        }

        GatewaySnapshot before = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        String targetProjectId = getString(body, "project_id");
        if (targetProjectId.isBlank()) {
            targetProjectId = safe(saved.projectId());
        }
        if (!targetProjectId.isBlank() && !targetProjectId.equals(safe(before.session().projectId()))) {
            JsonObject openResult = openProjectById(targetProjectId);
            if (!isToolOk(openResult)) {
                sendJson(exchange, 422, openResult);
                return;
            }
        }

        callOnClientThread(() -> {
            ClientAgentManager.restoreSession(sessionId);
            return null;
        });

        String activeSessionId = callOnClientThread(ClientAgentManager::debugCurrentSessionId);
        if (!sessionId.equals(safe(activeSessionId))) {
            sendJson(exchange, 409, errorPayload("switch_failed", "Session switch was not applied."));
            return;
        }

        GatewaySnapshot snapshot = callOnClientThread(ClientDebugGateway::captureSnapshotOnClient);
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("action", "switch_session");
        payload.addProperty("session_id", safe(snapshot.effectiveSessionId()));
        payload.add("state", buildStatePayload(snapshot));
        sendJson(exchange, 200, payload);
    }

    private static void handleCreateJob(HttpExchange exchange) throws Exception {
        JsonObject body = parseBodyObject(exchange);
        SubmissionRequest request = new SubmissionRequest(
                getString(body, "message"),
                getString(body, "display_text"),
                getString(body, "session_id"),
                getString(body, "project_id"),
                getString(body, "selected_workspace_path")
        );
        if (request.message().isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "message is required."));
            return;
        }

        DebugJob job;
        synchronized (START_STOP_LOCK) {
            DebugJob existing = currentJob;
            if (existing != null && !existing.isTerminal()) {
                sendJson(exchange, 409, errorPayload("busy", "An external debug job is already active."));
                return;
            }
            job = new DebugJob(nextJobId());
            currentJob = job;
            JOBS.put(job.id, job);
        }

        SubmissionResult result;
        try {
            result = callOnClientThread(() -> submitJobOnClient(request));
        } catch (Exception e) {
            synchronized (START_STOP_LOCK) {
                if (currentJob == job) {
                    currentJob = null;
                }
            }
            JOBS.remove(job.id);
            throw e;
        }

        if (!result.accepted()) {
            synchronized (START_STOP_LOCK) {
                if (currentJob == job) {
                    currentJob = null;
                }
            }
            JOBS.remove(job.id);
            sendJson(exchange, result.httpStatus(), errorPayload(result.errorCode(), result.errorMessage()));
            return;
        }

        synchronized (job) {
            job.sessionId = result.snapshot().effectiveSessionId();
            job.seenMessageCount = result.snapshot().session().messages().size();
            job.clientStatus = safe(result.snapshot().session().status());
            job.lastStreamingText = safe(result.snapshot().session().streamingText());
            job.lastPlan = buildPlanJson(result.snapshot().session());
            job.lastPlanFingerprint = GSON.toJson(job.lastPlan);
            job.lastPendingPatch = buildPendingPatchJson(result.snapshot().session());
            job.lastPatchFingerprint = job.lastPendingPatch == null ? "" : GSON.toJson(job.lastPendingPatch);
            job.lastPendingChoice = buildPendingChoiceJson(result.snapshot().session().pendingChoice());
            job.lastChoiceFingerprint = job.lastPendingChoice == null ? "" : GSON.toJson(job.lastPendingChoice);
            job.lastSubagents = result.snapshot().subagents().deepCopy();
            job.lastSubagentFingerprint = GSON.toJson(job.lastSubagents);
            job.addEventLocked("job.accepted", buildAcceptedPayload(job));
            if (!job.clientStatus.isBlank()) {
                JsonObject payload = new JsonObject();
                payload.addProperty("status", job.clientStatus);
                job.addEventLocked("status.changed", payload);
            }
            if (result.snapshot().busy()) {
                markRunningLocked(job);
            }
        }

        sendJson(exchange, 202, buildAcceptedPayload(job));
    }

    private static SubmissionResult submitJobOnClient(SubmissionRequest request) {
        if (!ClientServerBridge.hasRequiredServer()) {
            return SubmissionResult.failure(409, "server_unavailable", "Compatible P2S client/server bridge is not available.");
        }
        if (!ClientSessionState.hasProject()) {
            return SubmissionResult.failure(409, "no_project", "No project is open.");
        }
        if (ClientAgentManager.isBusy()) {
            return SubmissionResult.failure(409, "busy", "Agent is already running.");
        }
        if (ClientSessionState.hasPendingPatch()) {
            return SubmissionResult.failure(409, "pending_patch", "Pending patch awaiting decision.");
        }
        if (ClientSessionState.hasPendingChoice()) {
            return SubmissionResult.failure(409, "pending_choice", "Pending choice awaiting selection.");
        }

        String currentProjectId = safe(ClientSessionState.getProjectId());
        if (!request.projectId().isBlank() && !request.projectId().equals(currentProjectId)) {
            return SubmissionResult.failure(409, "project_mismatch", "Current project does not match project_id.");
        }

        String currentSessionId = safe(ClientAgentManager.debugCurrentSessionId());
        if (!request.sessionId().isBlank() && !currentSessionId.isBlank() && !request.sessionId().equals(currentSessionId)) {
            return SubmissionResult.failure(409, "session_mismatch", "Current session does not match session_id.");
        }

        if (!request.selectedWorkspacePath().isBlank() && !ClientAgentManager.selectWorkspacePath(request.selectedWorkspacePath())) {
            return SubmissionResult.failure(422, "invalid_workspace", "selected_workspace_path is invalid for the current project.");
        }

        String displayText = request.displayText().isBlank() ? request.message() : request.displayText();
        ClientAgentManager.submitUserMessage(request.message(), displayText);
        GatewaySnapshot snapshot = captureSnapshotOnClient();
        return SubmissionResult.success(snapshot);
    }

    private static void handleGetJob(HttpExchange exchange, String jobId) throws IOException {
        DebugJob job = JOBS.get(jobId);
        if (job == null) {
            sendJson(exchange, 404, errorPayload("not_found", "Unknown job id."));
            return;
        }
        sendJson(exchange, 200, buildJobPayload(job));
    }

    private static void handleJobEvents(HttpExchange exchange, String jobId) throws IOException {
        if (!P2SClientConfig.getDebugGatewayExposeSse()) {
            sendJson(exchange, 404, errorPayload("not_found", "SSE is disabled by config."));
            return;
        }
        DebugJob job = JOBS.get(jobId);
        if (job == null) {
            sendJson(exchange, 404, errorPayload("not_found", "Unknown job id."));
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        markSse(exchange);

        SseSubscriber subscriber = new SseSubscriber();
        List<GatewayEvent> history = job.register(subscriber);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
            for (GatewayEvent event : history) {
                writeEvent(writer, event);
            }

            long lastWrite = System.currentTimeMillis();
            while (true) {
                GatewayEvent event = subscriber.queue.poll(5, TimeUnit.SECONDS);
                if (event != null) {
                    writeEvent(writer, event);
                    lastWrite = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastWrite >= SSE_KEEPALIVE_MS) {
                    writer.write(": keep-alive\n\n");
                    writer.flush();
                    lastWrite = System.currentTimeMillis();
                }
                if (job.isTerminal() && subscriber.queue.isEmpty()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (P2SMod.DEBUG) {
                P2SMod.LOGGER.info("[DEBUG] Debug gateway SSE closed -> jobId={}, reason={}", jobId, safeMessage(e));
            }
        } finally {
            job.unregister(subscriber);
            exchange.close();
        }
    }

    private static void handlePatchApply(HttpExchange exchange, String jobId) throws Exception {
        DebugJob job = requireCurrentJob(jobId, exchange, "awaiting_patch");
        if (job == null) {
            return;
        }
        callOnClientThread(() -> {
            ClientAgentManager.submitPatchApply();
            return null;
        });
        sendJson(exchange, 202, simpleActionPayload(jobId, "accepted"));
    }

    private static void handlePatchDiscard(HttpExchange exchange, String jobId) throws Exception {
        DebugJob job = requireCurrentJob(jobId, exchange, "awaiting_patch");
        if (job == null) {
            return;
        }
        JsonObject body = parseBodyObject(exchange);
        String reason = getString(body, "reason");
        callOnClientThread(() -> {
            ClientAgentManager.submitPatchDiscard(reason);
            return null;
        });
        sendJson(exchange, 202, simpleActionPayload(jobId, "accepted"));
    }

    private static void handleChoiceSelect(HttpExchange exchange, String jobId) throws Exception {
        DebugJob job = requireCurrentJob(jobId, exchange, "awaiting_choice");
        if (job == null) {
            return;
        }
        JsonObject body = parseBodyObject(exchange);
        String optionId = getString(body, "option_id");
        if (optionId.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "option_id is required."));
            return;
        }
        callOnClientThread(() -> {
            ClientAgentManager.submitChoiceSelection(optionId);
            return null;
        });
        sendJson(exchange, 202, simpleActionPayload(jobId, "accepted"));
    }

    private static void handleChoiceCustom(HttpExchange exchange, String jobId) throws Exception {
        DebugJob job = requireCurrentJob(jobId, exchange, "awaiting_choice");
        if (job == null) {
            return;
        }
        JsonObject body = parseBodyObject(exchange);
        String text = getString(body, "text");
        if (text.isBlank()) {
            sendJson(exchange, 400, errorPayload("invalid_request", "text is required."));
            return;
        }
        callOnClientThread(() -> {
            ClientAgentManager.submitCustomChoice(text);
            return null;
        });
        sendJson(exchange, 202, simpleActionPayload(jobId, "accepted"));
    }

    private static void handleCancelJob(HttpExchange exchange, String jobId) throws IOException {
        DebugJob job = JOBS.get(jobId);
        if (job == null) {
            sendJson(exchange, 404, errorPayload("not_found", "Unknown job id."));
            return;
        }
        synchronized (job) {
            if (!job.isTerminal()) {
                job.cancelled = true;
                if (!"running".equals(job.status)) {
                    completeTerminalLocked(job, "cancelled", "job.cancelled", "");
                }
            }
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("job_id", jobId);
        payload.addProperty("status", "accepted");
        payload.addProperty("note", "Underlying agent interruption is not supported; the external job will stop tracking once the current turn settles.");
        sendJson(exchange, 202, payload);
    }

    private static DebugJob requireCurrentJob(String jobId, HttpExchange exchange, String expectedStatus) throws IOException {
        DebugJob job = JOBS.get(jobId);
        if (job == null) {
            sendJson(exchange, 404, errorPayload("not_found", "Unknown job id."));
            return null;
        }
        if (currentJob != job || job.isTerminal()) {
            sendJson(exchange, 409, errorPayload("inactive_job", "The requested job is not the active external job."));
            return null;
        }
        synchronized (job) {
            if (!expectedStatus.equals(job.status)) {
                sendJson(exchange, 409, errorPayload("invalid_state", "Job is currently in state: " + job.status));
                return null;
            }
        }
        return job;
    }

    private static JsonObject simpleActionPayload(String jobId, String status) {
        JsonObject payload = new JsonObject();
        payload.addProperty("job_id", jobId);
        payload.addProperty("status", status);
        return payload;
    }

    private static JsonObject buildStatePayload(GatewaySnapshot snapshot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("debug_enabled", P2SMod.DEBUG);
        payload.addProperty("gateway_running", server != null);
        payload.addProperty("bind_host", P2SClientConfig.getDebugGatewayHost());
        payload.addProperty("bind_port", P2SClientConfig.getDebugGatewayPort());
        payload.addProperty("server_bridge_ready", snapshot.serverBridgeReady());
        payload.addProperty("has_project", snapshot.session().hasProject());
        payload.addProperty("project_id", safe(snapshot.session().projectId()));
        payload.addProperty("project_name", safe(snapshot.session().projectName()));
        payload.addProperty("session_active", snapshot.session().sessionActive());
        payload.addProperty("session_id", safe(snapshot.effectiveSessionId()));
        payload.addProperty("selected_workspace_path", safe(snapshot.session().selectedWorkspacePath()));
        payload.addProperty("busy", snapshot.busy());
        payload.addProperty("pending_patch", snapshot.session().hasPendingPatch());
        payload.addProperty("pending_choice", snapshot.session().pendingChoice() != null);
        payload.addProperty("current_job_id", currentJob == null || currentJob.isTerminal() ? "" : currentJob.id);
        payload.add("selection", buildSelectionPayload(snapshot.selectionPos1(), snapshot.selectionPos2()));
        return payload;
    }

    private static JsonObject buildAcceptedPayload(DebugJob job) {
        JsonObject payload = new JsonObject();
        payload.addProperty("job_id", job.id);
        payload.addProperty("session_id", safe(job.sessionId));
        payload.addProperty("status", safe(job.status));
        payload.addProperty("events_url", ROOT_PATH + "/jobs/" + job.id + "/events");
        payload.addProperty("status_url", ROOT_PATH + "/jobs/" + job.id);
        return payload;
    }

    private static JsonObject buildJobPayload(DebugJob job) {
        synchronized (job) {
            JsonObject payload = new JsonObject();
            payload.addProperty("job_id", job.id);
            payload.addProperty("status", safe(job.status));
            payload.addProperty("created_at", job.createdAt);
            payload.addProperty("started_at", job.startedAt);
            payload.addProperty("ended_at", job.endedAt);
            payload.addProperty("session_id", safe(job.sessionId));
            payload.addProperty("client_status", safe(job.clientStatus));
            payload.addProperty("assistant_text", safe(job.lastAssistantText));
            payload.addProperty("streaming_text", safe(job.lastStreamingText));
            payload.add("plan", job.lastPlan == null ? new JsonObject() : job.lastPlan.deepCopy());
            payload.add("pending_patch", job.lastPendingPatch == null ? JsonNull.INSTANCE : job.lastPendingPatch.deepCopy());
            payload.add("pending_choice", job.lastPendingChoice == null ? JsonNull.INSTANCE : job.lastPendingChoice.deepCopy());
            payload.add("subagents", job.lastSubagents == null ? emptySubagentsPayload() : job.lastSubagents.deepCopy());
            payload.addProperty("error", safe(job.error));
            payload.addProperty("events_url", ROOT_PATH + "/jobs/" + job.id + "/events");
            payload.addProperty("status_url", ROOT_PATH + "/jobs/" + job.id);
            return payload;
        }
    }

    private static JsonArray knownToolNames() {
        return toStringArray(List.of(
                "list_projects",
                "create_project",
                "open_project",
                "rename_project",
                "delete_project",
                "get_project_state",
                "read_workspace_file",
                "create_workspace_file",
                "save_workspace_file",
                "rename_workspace_file",
                "delete_workspace_file",
                "propose_patch",
                "search_block_ids",
                "describe_block_state",
                "compose_block_state",
                "describe_block_entity_template",
                "debug_stage_blocks"
        ));
    }

    private static JsonArray toStringArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            array.add(safe(value));
        }
        return array;
    }

    private static SelectionUpdateResult updateSelectionOnClient(BlockPos pos1, BlockPos pos2, boolean clear) {
        if (!ClientServerBridge.canSyncSelection()) {
            return SelectionUpdateResult.failure(409, "server_unavailable", "Compatible P2S selection bridge is not available.");
        }
        if (clear) {
            if (!ClientServerBridge.sendSelection(-1, BlockPos.ZERO)) {
                return SelectionUpdateResult.failure(409, "server_unavailable", "Failed sending selection clear request.");
            }
            ClientSelectionManager.onSyncFromServer(null, null);
            return SelectionUpdateResult.success(null, null);
        }

        BlockPos nextPos1 = ClientSelectionManager.getPos1();
        BlockPos nextPos2 = ClientSelectionManager.getPos2();
        if (pos1 != null) {
            if (!ClientServerBridge.sendSelection(0, pos1)) {
                return SelectionUpdateResult.failure(409, "server_unavailable", "Failed sending pos1 selection request.");
            }
            nextPos1 = pos1;
        }
        if (pos2 != null) {
            if (!ClientServerBridge.sendSelection(1, pos2)) {
                return SelectionUpdateResult.failure(409, "server_unavailable", "Failed sending pos2 selection request.");
            }
            nextPos2 = pos2;
        }

        ClientSelectionManager.onSyncFromServer(nextPos1, nextPos2);
        return SelectionUpdateResult.success(nextPos1, nextPos2);
    }

    private static JsonObject buildSelectionPayload(BlockPos pos1, BlockPos pos2) {
        JsonObject payload = new JsonObject();
        payload.add("pos1", blockPosJsonOrNull(pos1));
        payload.add("pos2", blockPosJsonOrNull(pos2));
        boolean complete = pos1 != null && pos2 != null;
        payload.addProperty("complete", complete);
        if (complete) {
            BlockPos min = new BlockPos(
                    Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ())
            );
            BlockPos max = new BlockPos(
                    Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ())
            );
            payload.add("min", blockPosJson(min));
            payload.add("max", blockPosJson(max));
            JsonObject size = new JsonObject();
            size.addProperty("x", max.getX() - min.getX() + 1);
            size.addProperty("y", max.getY() - min.getY() + 1);
            size.addProperty("z", max.getZ() - min.getZ() + 1);
            payload.add("size", size);
        }
        return payload;
    }

    private static JsonElement blockPosJsonOrNull(BlockPos pos) {
        return pos == null ? JsonNull.INSTANCE : blockPosJson(pos);
    }

    private static JsonObject blockPosJson(BlockPos pos) {
        JsonObject payload = new JsonObject();
        payload.addProperty("x", pos == null ? 0 : pos.getX());
        payload.addProperty("y", pos == null ? 0 : pos.getY());
        payload.addProperty("z", pos == null ? 0 : pos.getZ());
        return payload;
    }

    private static JsonObject parseBodyObject(HttpExchange exchange) throws IOException, RequestFailure {
        byte[] bytes = readBody(exchange.getRequestBody());
        if (bytes.length == 0) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            throw new RequestFailure(400, "invalid_json", "Invalid JSON body: " + safeMessage(e));
        }
    }

    private static void requireNoActiveExternalJob() throws RequestFailure {
        DebugJob running = currentJob;
        if (running != null && !running.isTerminal()) {
            throw new RequestFailure(409, "busy", "An external debug job is already active.");
        }
        if (ClientAgentManager.isBusy()) {
            throw new RequestFailure(409, "busy", "Agent is running.");
        }
        if (ClientSessionState.hasPendingPatch()) {
            throw new RequestFailure(409, "pending_patch", "Pending patch awaiting decision.");
        }
        if (ClientSessionState.hasPendingChoice()) {
            throw new RequestFailure(409, "pending_choice", "Pending choice awaiting selection.");
        }
    }

    private static JsonObject openProjectById(String projectId) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("id", projectId);
        JsonObject result = callToolBridge("open_project", args);
        if (isToolOk(result)) {
            callOnClientThread(() -> {
                ClientAgentManager.onProjectChanged();
                return null;
            });
        }
        return result;
    }

    private static JsonObject callToolBridge(String toolName, JsonObject args) throws RequestFailure {
        if (!ClientServerBridge.canUseToolBridge()) {
            throw new RequestFailure(409, "server_unavailable", "Compatible P2S client/server bridge is not available.");
        }
        try {
            JsonObject result = ClientToolBridge.call(toolName, args == null ? new JsonObject() : args).get(20, TimeUnit.SECONDS);
            return result == null ? new JsonObject() : result;
        } catch (TimeoutException e) {
            throw new RequestFailure(504, "tool_timeout", "Tool call timed out.");
        } catch (Exception e) {
            throw new RequestFailure(500, "tool_failed", "Tool call failed: " + safeMessage(e));
        }
    }

    private static boolean isToolOk(JsonObject payload) {
        if (payload == null || !payload.has("ok")) {
            return false;
        }
        try {
            return payload.get("ok").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] readBody(InputStream body) throws IOException, RequestFailure {
        if (body == null) {
            return new byte[0];
        }
        byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new RequestFailure(413, "payload_too_large", "Request body is too large.");
        }
        return bytes;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getOptionalString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer getOptionalInt(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject getObject(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            return null;
        }
        try {
            return obj.getAsJsonObject(key).deepCopy();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BlockPos parseBlockPos(JsonObject obj, String key) {
        JsonObject source = key == null ? obj : getObject(obj, key);
        if (source == null) {
            return null;
        }
        Integer x = getOptionalInt(source, "x");
        Integer y = getOptionalInt(source, "y");
        Integer z = getOptionalInt(source, "z");
        if (x == null || y == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean defaultValue) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static void addCommonHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int statusCode, JsonObject payload) throws IOException {
        byte[] bytes = GSON.toJson(payload == null ? new JsonObject() : payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static JsonObject errorPayload(String code, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("error", safe(code));
        payload.addProperty("message", safe(message));
        return payload;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return ROOT_PATH;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String[] splitPath(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return new String[0];
        }
        return suffix.split("/");
    }

    private static String nextJobId() {
        return Long.toString(NEXT_JOB_ID.incrementAndGet(), 36);
    }

    private static boolean isSse(HttpExchange exchange) {
        Object flag = exchange.getAttribute("p2s_sse");
        return flag instanceof Boolean b && b;
    }

    private static void markSse(HttpExchange exchange) {
        exchange.setAttribute("p2s_sse", Boolean.TRUE);
    }

    private static void writeEvent(BufferedWriter writer, GatewayEvent event) throws IOException {
        writer.write("event: ");
        writer.write(event.type());
        writer.write('\n');
        writer.write("data: ");
        writer.write(GSON.toJson(event.payload()));
        writer.write("\n\n");
        writer.flush();
    }

    private static <T> T callOnClientThread(ClientCallable<T> callable) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new IllegalStateException("Minecraft client is not available.");
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(callable.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.get(5, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ClientCallable<T> {
        T call() throws Exception;
    }

    private record GatewaySnapshot(
            ClientSessionState.DebugSnapshot session,
            boolean busy,
            boolean serverBridgeReady,
            String effectiveSessionId,
            JsonObject subagents,
            BlockPos selectionPos1,
            BlockPos selectionPos2
    ) {
    }

    private record SelectionUpdateResult(
            boolean ok,
            int statusCode,
            String errorCode,
            String message,
            BlockPos pos1,
            BlockPos pos2
    ) {
        private static SelectionUpdateResult success(BlockPos pos1, BlockPos pos2) {
            return new SelectionUpdateResult(true, 200, "", "", pos1, pos2);
        }

        private static SelectionUpdateResult failure(int statusCode, String errorCode, String message) {
            return new SelectionUpdateResult(false, statusCode, errorCode, message, null, null);
        }
    }

    private record SubmissionRequest(
            String message,
            String displayText,
            String sessionId,
            String projectId,
            String selectedWorkspacePath
    ) {
    }

    private record SubmissionResult(
            boolean accepted,
            int httpStatus,
            String errorCode,
            String errorMessage,
            GatewaySnapshot snapshot
    ) {
        private static SubmissionResult success(GatewaySnapshot snapshot) {
            return new SubmissionResult(true, 202, "", "", snapshot);
        }

        private static SubmissionResult failure(int status, String code, String message) {
            return new SubmissionResult(false, status, code, message, null);
        }
    }

    private record GatewayEvent(String type, JsonObject payload) {
    }

    private static final class RequestFailure extends Exception {
        private final int statusCode;
        private final String errorCode;

        private RequestFailure(int statusCode, String errorCode, String message) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }
    }

    private static final class SseSubscriber {
        private final BlockingQueue<GatewayEvent> queue = new LinkedBlockingQueue<>();
    }

    private static final class DebugJob {
        private final String id;
        private final long createdAt;
        private final List<GatewayEvent> history = new ArrayList<>();
        private final List<SseSubscriber> subscribers = new ArrayList<>();
        private String sessionId = "";
        private String status = "accepted";
        private String error = "";
        private long startedAt = 0L;
        private long endedAt = 0L;
        private String clientStatus = "";
        private String lastAssistantText = "";
        private String lastStreamingText = "";
        private JsonObject lastPlan = new JsonObject();
        private JsonObject lastPendingPatch = null;
        private JsonObject lastPendingChoice = null;
        private JsonObject lastSubagents = emptySubagentsPayload();
        private String lastPlanFingerprint = "";
        private String lastPatchFingerprint = "";
        private String lastChoiceFingerprint = "";
        private String lastSubagentFingerprint = "";
        private int seenMessageCount = 0;
        private boolean cancelled = false;

        private DebugJob(String id) {
            this.id = id;
            this.createdAt = System.currentTimeMillis();
        }

        private boolean isTerminal() {
            return "completed".equals(status)
                    || "failed".equals(status)
                    || "cancelled".equals(status);
        }

        private void addEventLocked(String type, JsonObject payload) {
            JsonObject enriched = payload == null ? new JsonObject() : payload.deepCopy();
            enriched.addProperty("job_id", id);
            enriched.addProperty("timestamp", System.currentTimeMillis());
            if (!sessionId.isBlank()) {
                enriched.addProperty("session_id", sessionId);
            }
            GatewayEvent event = new GatewayEvent(type, enriched);
            history.add(event);
            if (history.size() > MAX_EVENT_HISTORY) {
                history.remove(0);
            }
            for (SseSubscriber subscriber : subscribers) {
                if (subscriber != null) {
                    subscriber.queue.offer(event);
                }
            }
        }

        private List<GatewayEvent> register(SseSubscriber subscriber) {
            synchronized (this) {
                subscribers.add(subscriber);
                return new ArrayList<>(history);
            }
        }

        private void unregister(SseSubscriber subscriber) {
            synchronized (this) {
                subscribers.remove(subscriber);
            }
        }
    }
}
