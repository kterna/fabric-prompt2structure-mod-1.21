package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2s.store.SkillStore;
import com.p2s.store.SubagentProfileStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class SubagentManager {
    private static final Gson GSON = new Gson();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int MAX_ACTIVE_SUBAGENTS = 2;
    private static final int MAX_QUEUED_SUBAGENTS = 16;
    private static final int MAX_RESPONSE_CHARS = 8192;
    private static final int MAX_SUMMARY_CHARS = 512;
    private static final int MAX_TRACE_ITEMS = 40;
    private static final int MAX_ATTEMPT_RECORDS = 20;
    private static final int MAX_PLAN_ITEMS = 40;
    private static final int MAX_TOOL_LOOPS = 30;
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            MAX_ACTIVE_SUBAGENTS,
            MAX_ACTIVE_SUBAGENTS,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_SUBAGENTS),
            r -> {
                Thread t = new Thread(r, "p2s-subagent");
                t.setDaemon(true);
                return t;
            }
    );

    private static final ConcurrentHashMap<String, SubagentTask> TASKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> SESSION_INDEX = new ConcurrentHashMap<>();
    private static final ExecutorService TOOL_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "p2s-subagent-tool");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> PARALLEL_SAFE_TOOLS = Set.of(
            "list_skills", "read_skill", "read_subdoc", "search_skill", "update_plan",
            "get_project_state", "read_workspace_file", "search_block_ids", "describe_block_state"
    );

    private SubagentManager() {
    }

    public static JsonObject createSubagent(String sessionId, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        String taskText = asString(args, "task");
        if (taskText.isBlank()) {
            return error("create_subagent", "Missing task");
        }

        String requestedProfileId = asString(args, "profile_id");
        SubagentProfileStore.SubagentProfile profile = SubagentProfileStore.resolveProfile(requestedProfileId);
        if (profile == null) {
            return error("create_subagent", "Profile not found");
        }
        if (!profile.enabled()) {
            return error("create_subagent", "Profile disabled: " + profile.id());
        }
        if (profile.allowedTools() == null || profile.allowedTools().isEmpty()) {
            return error("create_subagent", "Profile has no allowed tools: " + profile.id());
        }

        boolean hasExplicitSkillIds = args.has("skill_ids");
        List<String> requestedSkillIds = parseStringArray(args.get("skill_ids"));
        ResolveSkillResult resolveSkills = resolveSkillIds(requestedSkillIds, hasExplicitSkillIds);
        if (hasExplicitSkillIds && !requestedSkillIds.isEmpty() && resolveSkills.skillIds().isEmpty()) {
            if (resolveSkills.warnings().isEmpty()) {
                return error("create_subagent", "No valid requested skills resolved");
            }
            return error("create_subagent", "No valid requested skills resolved: " + String.join("; ", resolveSkills.warnings()));
        }
        String id = Long.toString(NEXT_ID.incrementAndGet(), 36);
        String sid = sessionKey(sessionId);
        long now = System.currentTimeMillis();
        JsonObject metadata = args.has("metadata") && args.get("metadata").isJsonObject()
                ? args.getAsJsonObject("metadata").deepCopy()
                : null;

        SubagentTask subagent = new SubagentTask();
        subagent.id = id;
        subagent.sessionId = sid;
        subagent.profileId = profile.id();
        subagent.profileName = profile.name();
        subagent.task = taskText;
        subagent.skillIds = new ArrayList<>(resolveSkills.skillIds());
        subagent.metadata = metadata;
        subagent.status = SubagentStatus.QUEUED;
        subagent.createdAt = now;
        subagent.summary = "Queued";
        subagent.attempt = 1;
        subagent.attemptCount = 1;
        initializeTaskHistory(subagent, profile);

        TASKS.put(id, subagent);
        SESSION_INDEX.computeIfAbsent(sid, k -> new CopyOnWriteArrayList<>()).add(id);

        if (P2SMod.DEBUG) {
            P2SMod.LOGGER.info("[DEBUG] Subagent created -> id={}, session={}, profile={}, task={}, allowedTools={}, skillIds={}",
                    id, sid, profile.id(), taskText, profile.allowedTools(), resolveSkills.skillIds());
        }

        queueSubagentRun(subagent, profile);

        JsonObject payload = ok("create_subagent");
        payload.addProperty("subagent_id", id);
        payload.addProperty("status", statusName(subagent.status));
        payload.addProperty("profile_id", profile.id());
        payload.addProperty("created_at", now);
        payload.addProperty("queued", subagent.status == SubagentStatus.QUEUED);
        payload.addProperty("attempt", subagent.attempt);
        payload.addProperty("attempt_count", subagent.attemptCount);
        payload.addProperty("skill_count", resolveSkills.skillIds().size());
        payload.add("skill_ids", toJsonArray(resolveSkills.skillIds()));
        if (!resolveSkills.warnings().isEmpty()) {
            payload.add("warnings", toJsonArray(resolveSkills.warnings()));
        }
        return payload;
    }

    public static JsonObject continueSubagent(String sessionId, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        String subagentId = asString(args, "id");
        if (subagentId.isBlank()) {
            return error("continue_subagent", "Missing id");
        }
        SubagentTask task = getBySession(sessionId, subagentId);
        if (task == null) {
            return error("continue_subagent", "Subagent not found");
        }
        SubagentProfileStore.SubagentProfile profile = SubagentProfileStore.getProfile(task.profileId);
        if (profile == null) {
            return error("continue_subagent", "Profile not found: " + task.profileId);
        }
        if (!profile.enabled()) {
            return error("continue_subagent", "Profile disabled: " + profile.id());
        }
        if (profile.allowedTools() == null || profile.allowedTools().isEmpty()) {
            return error("continue_subagent", "Profile has no allowed tools: " + profile.id());
        }

        String instruction = asString(args, "instruction");
        if (instruction.isBlank()) {
            instruction = "Continue from previous context and finish the task with actionable output.";
        }

        int attempt;
        int attemptCount;
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED) {
                return error("continue_subagent", "Subagent is deleted");
            }
            if (task.status == SubagentStatus.RUNNING || task.status == SubagentStatus.QUEUED) {
                return error("continue_subagent", "Subagent is already active");
            }
            if (!canContinue(task.status)) {
                return error("continue_subagent", "Subagent cannot continue from status: " + statusName(task.status));
            }
            if (task.history == null || task.history.isEmpty()) {
                initializeTaskHistory(task, profile);
            }
            JsonObject continueMsg = new JsonObject();
            continueMsg.addProperty("role", "user");
            continueMsg.addProperty("content", instruction);
            task.history.add(continueMsg);
            task.cancelRequested = false;
            task.status = SubagentStatus.QUEUED;
            task.summary = "Queued (continue)";
            task.error = "";
            task.response = "";
            task.startedAt = 0L;
            task.endedAt = 0L;
            task.attempt += 1;
            task.attemptCount += 1;
            attempt = task.attempt;
            attemptCount = task.attemptCount;
        }

        queueSubagentRun(task, profile);
        TaskSnapshot snap = snapshot(task);

        JsonObject payload = ok("continue_subagent");
        payload.addProperty("id", subagentId);
        payload.addProperty("status", statusName(snap.status));
        payload.addProperty("continued", true);
        payload.addProperty("queued", snap.status == SubagentStatus.QUEUED);
        payload.addProperty("attempt", attempt);
        payload.addProperty("attempt_count", attemptCount);
        return payload;
    }

    public static JsonObject listSubagents(String sessionId, boolean includeDeleted) {
        String sid = sessionKey(sessionId);
        List<String> ids = SESSION_INDEX.getOrDefault(sid, new CopyOnWriteArrayList<>());
        JsonArray items = new JsonArray();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            SubagentTask task = TASKS.get(id);
            if (task == null) {
                continue;
            }
            TaskSnapshot snapshot = snapshot(task);
            if (!includeDeleted && snapshot.status == SubagentStatus.DELETED) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", snapshot.id);
            item.addProperty("status", statusName(snapshot.status));
            item.addProperty("profile_id", snapshot.profileId);
            item.addProperty("created_at", snapshot.createdAt);
            item.addProperty("started_at", snapshot.startedAt);
            item.addProperty("ended_at", snapshot.endedAt);
            item.addProperty("attempt", snapshot.attempt);
            item.addProperty("attempt_count", snapshot.attemptCount);
            item.addProperty("can_continue", canContinue(snapshot.status));
            item.addProperty("summary", snapshot.summary);
            if (!snapshot.error.isBlank()) {
                item.addProperty("error", snapshot.error);
            }
            items.add(item);
        }
        JsonObject payload = ok("list_subagents");
        payload.addProperty("count", items.size());
        payload.add("subagents", items);
        return payload;
    }

    public static JsonObject getSubagent(String sessionId, String subagentId) {
        SubagentTask task = getBySession(sessionId, subagentId);
        if (task == null) {
            return error("get_subagent", "Subagent not found");
        }
        TaskSnapshot snapshot = snapshot(task);
        JsonObject payload = ok("get_subagent");
        payload.addProperty("id", snapshot.id);
        payload.addProperty("status", statusName(snapshot.status));
        payload.addProperty("profile_id", snapshot.profileId);
        payload.addProperty("profile_name", snapshot.profileName);
        payload.addProperty("task", snapshot.task);
        payload.add("skill_ids", toJsonArray(snapshot.skillIds));
        payload.addProperty("created_at", snapshot.createdAt);
        payload.addProperty("started_at", snapshot.startedAt);
        payload.addProperty("ended_at", snapshot.endedAt);
        payload.addProperty("attempt", snapshot.attempt);
        payload.addProperty("attempt_count", snapshot.attemptCount);
        payload.addProperty("can_continue", canContinue(snapshot.status));
        payload.addProperty("summary", snapshot.summary);
        payload.addProperty("response", snapshot.response);
        if (!snapshot.error.isBlank()) {
            payload.addProperty("error", snapshot.error);
        }
        if (snapshot.metadata != null) {
            payload.add("metadata", snapshot.metadata.deepCopy());
        }
        payload.add("tool_trace", toJsonArray(snapshot.toolTrace));
        if (snapshot.attempts != null && !snapshot.attempts.isEmpty()) {
            JsonArray attempts = new JsonArray();
            for (AttemptRecord record : snapshot.attempts) {
                JsonObject item = new JsonObject();
                item.addProperty("attempt", record.attempt());
                item.addProperty("status", statusName(record.status()));
                item.addProperty("summary", record.summary());
                item.addProperty("response", record.response());
                item.addProperty("error", record.error());
                item.addProperty("started_at", record.startedAt());
                item.addProperty("ended_at", record.endedAt());
                attempts.add(item);
            }
            payload.add("attempts", attempts);
        }
        return payload;
    }

    public static JsonObject deleteSubagent(String sessionId, String subagentId) {
        SubagentTask task = getBySession(sessionId, subagentId);
        if (task == null) {
            return error("delete_subagent", "Subagent not found");
        }

        boolean alreadyDeleted;
        synchronized (task) {
            alreadyDeleted = task.status == SubagentStatus.DELETED;
            if (!alreadyDeleted) {
                task.cancelRequested = true;
                task.status = SubagentStatus.DELETED;
                task.summary = "Deleted";
                if (task.error == null || task.error.isBlank()) {
                    task.error = "Deleted by parent agent";
                }
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
            }
        }

        JsonObject payload = ok("delete_subagent");
        payload.addProperty("id", subagentId == null ? "" : subagentId);
        payload.addProperty("status", "deleted");
        payload.addProperty("already_deleted", alreadyDeleted);
        return payload;
    }

    public static JsonObject listProfiles() {
        List<SubagentProfileStore.ProfileSummary> profiles = SubagentProfileStore.listProfileSummaries();
        JsonArray items = new JsonArray();
        for (SubagentProfileStore.ProfileSummary profile : profiles) {
            JsonObject item = new JsonObject();
            item.addProperty("id", profile.id());
            item.addProperty("name", profile.name());
            item.addProperty("description", profile.description());
            item.addProperty("enabled", profile.enabled());
            item.addProperty("tool_count", profile.toolCount());
            items.add(item);
        }
        JsonObject payload = ok("list_profiles");
        payload.addProperty("count", items.size());
        payload.add("profiles", items);
        payload.addProperty("profile_root", "config/p2s_skills/.agent");
        return payload;
    }

    public static JsonObject getProfile(String profileId) {
        SubagentProfileStore.SubagentProfile profile = SubagentProfileStore.getProfile(profileId);
        if (profile == null) {
            return error("get_profile", "Profile not found: " + (profileId == null ? "" : profileId));
        }

        JsonObject payload = ok("get_profile");
        payload.addProperty("id", profile.id());
        payload.addProperty("name", profile.name());
        payload.addProperty("description", profile.description());
        payload.addProperty("system_prompt", profile.systemPrompt());
        payload.add("allowed_tools", toJsonArray(profile.allowedTools()));
        payload.addProperty("max_loops", profile.maxLoops());
        payload.addProperty("timeout_seconds", profile.timeoutSeconds());
        payload.addProperty("enabled", profile.enabled());
        return payload;
    }

    private static void queueSubagentRun(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        if (task == null || profile == null) {
            return;
        }
        try {
            EXECUTOR.execute(() -> runSubagent(task, profile));
        } catch (RejectedExecutionException e) {
            long now = System.currentTimeMillis();
            synchronized (task) {
                task.status = SubagentStatus.FAILED;
                task.error = "Subagent queue is full";
                task.summary = "Queue full";
                task.response = "";
                task.startedAt = now;
                task.endedAt = now;
                appendAttemptRecord(task);
            }
        }
    }

    private static void initializeTaskHistory(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        if (task == null || profile == null) {
            return;
        }
        if (task.history == null) {
            task.history = new ArrayList<>();
        } else {
            task.history.clear();
        }

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", buildSubagentSystemPrompt(task, profile));
        task.history.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", task.task == null ? "" : task.task);
        task.history.add(user);

        if (task.metadata != null && !task.metadata.entrySet().isEmpty()) {
            JsonObject metadataMsg = new JsonObject();
            metadataMsg.addProperty("role", "user");
            metadataMsg.addProperty("content", "metadata: " + GSON.toJson(task.metadata));
            task.history.add(metadataMsg);
        }
    }

    private static void runSubagent(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        synchronized (task) {
            if (task.cancelRequested || task.status == SubagentStatus.DELETED) {
                task.status = SubagentStatus.DELETED;
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
                return;
            }
            if (task.history == null || task.history.isEmpty()) {
                initializeTaskHistory(task, profile);
            }
            task.status = SubagentStatus.RUNNING;
            task.startedAt = System.currentTimeMillis();
            task.summary = "Running (attempt " + task.attempt + ")";
            task.error = "";
            task.response = "";
        }

        int maxLoops = clamp(profile.maxLoops(), 1, MAX_TOOL_LOOPS);
        int timeoutSeconds = clamp(profile.timeoutSeconds(), 5, 300);
        Set<String> allowedTools = new LinkedHashSet<>(profile.allowedTools());

        try {
            for (int loop = 0; loop < maxLoops; loop++) {
                if (shouldStop(task)) {
                    return;
                }

                if (P2SMod.DEBUG) {
                    int historySize;
                    synchronized (task) {
                        historySize = task.history == null ? 0 : task.history.size();
                    }
                    P2SMod.LOGGER.info("[DEBUG] Subagent {} loop iteration {} of {}, historySize={}", task.id, loop, maxLoops, historySize);
                }

                List<JsonObject> snapshot;
                synchronized (task) {
                    snapshot = deepCopyMessages(task.history);
                }
                LLMService.SessionResult result = LLMService.requestWithHistoryWithSkills(
                                snapshot,
                                P2SClientConfig.llmRequestConfig(),
                                allowedTools
                        )
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .join();

                if (result == null) {
                    fail(task, "Empty response");
                    return;
                }

                if (result.rawAssistantMessage() != null) {
                    synchronized (task) {
                        task.history.add(result.rawAssistantMessage().deepCopy());
                    }
                }

                List<LLMService.ToolCall> toolCalls = result.toolCalls() == null ? List.of() : result.toolCalls();
                if (!toolCalls.isEmpty()) {
                    if (P2SMod.DEBUG) {
                        for (LLMService.ToolCall tc : toolCalls) {
                            P2SMod.LOGGER.info("[DEBUG] Subagent {} tool call: name={}, args={}", task.id, tc.name(), tc.arguments());
                        }
                    }
                    List<ToolCallResult> batchResults = executeToolCallsBatch(task, toolCalls, allowedTools);
                    for (ToolCallResult tcr : batchResults) {
                        synchronized (task) {
                            task.history.add(buildToolMessage(tcr.call(), tcr.payload()));
                        }
                        appendTrace(task, tcr.call() == null ? "" : tcr.call().name(), tcr.payload());
                        if (P2SMod.DEBUG) {
                            P2SMod.LOGGER.info("[DEBUG] Subagent {} tool result: name={}, payload={}", task.id, tcr.call() == null ? "" : tcr.call().name(), GSON.toJson(tcr.payload()));
                        }
                    }
                    continue;
                }

                String text = result.textContent();
                if (text == null) {
                    text = "";
                }
                if (P2SMod.DEBUG) {
                    long durationMs = System.currentTimeMillis() - task.startedAt;
                    P2SMod.LOGGER.info("[DEBUG] Subagent {} completed -> durationMs={}, loops={}, responseLen={}", task.id, durationMs, loop + 1, text.length());
                    P2SMod.LOGGER.info("[DEBUG] Subagent {} final response: {}", task.id, text);
                }
                complete(task, text);
                return;
            }
            fail(task, "Reached max subagent loops (" + maxLoops + ")");
            P2SMod.LOGGER.warn("Subagent {} reached max loops ({})", task.id, maxLoops);
        } catch (Exception e) {
            if (shouldStop(task)) {
                return;
            }
            fail(task, formatError(e));
        }
    }

    private record ToolCallResult(LLMService.ToolCall call, JsonObject payload) {
    }

    private static List<ToolCallResult> executeToolCallsBatch(SubagentTask task, List<LLMService.ToolCall> toolCalls, Set<String> allowedTools) {
        if (toolCalls.size() == 1) {
            LLMService.ToolCall call = toolCalls.get(0);
            JsonObject payload = executeToolCall(task, call, allowedTools);
            return List.of(new ToolCallResult(call, payload));
        }

        List<LLMService.ToolCall> parallelCalls = new ArrayList<>();
        List<LLMService.ToolCall> serialCalls = new ArrayList<>();
        for (LLMService.ToolCall call : toolCalls) {
            if (call != null && call.name() != null && PARALLEL_SAFE_TOOLS.contains(call.name())) {
                parallelCalls.add(call);
            } else {
                serialCalls.add(call);
            }
        }

        Map<LLMService.ToolCall, JsonObject> resultMap = new LinkedHashMap<>();

        if (!parallelCalls.isEmpty()) {
            List<CompletableFuture<ToolCallResult>> futures = new ArrayList<>();
            for (LLMService.ToolCall call : parallelCalls) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> new ToolCallResult(call, executeToolCall(task, call, allowedTools)),
                        TOOL_EXECUTOR
                ));
            }
            for (CompletableFuture<ToolCallResult> future : futures) {
                try {
                    ToolCallResult tcr = future.join();
                    resultMap.put(tcr.call(), tcr.payload());
                } catch (Exception e) {
                    P2SMod.LOGGER.warn("Subagent parallel tool call failed: {}", e.getMessage());
                }
            }
        }

        for (LLMService.ToolCall call : serialCalls) {
            JsonObject payload = executeToolCall(task, call, allowedTools);
            resultMap.put(call, payload);
        }

        List<ToolCallResult> ordered = new ArrayList<>();
        for (LLMService.ToolCall call : toolCalls) {
            JsonObject payload = resultMap.get(call);
            if (payload == null) {
                payload = toolError(call == null ? "" : call.name(), "Execution failed");
            }
            ordered.add(new ToolCallResult(call, payload));
        }
        return ordered;
    }

    private static JsonObject executeToolCall(SubagentTask task, LLMService.ToolCall call, Set<String> allowedTools) {
        if (call == null || call.name() == null || call.name().isBlank()) {
            return toolError("unknown", "Invalid tool call");
        }

        String toolName = call.name();
        if (!allowedTools.contains(toolName)) {
            return toolError(toolName, "Tool not allowed by profile: " + toolName);
        }

        return switch (toolName) {
            case "list_skills" -> listSkillsPayload(task);
            case "read_skill" -> readSkillPayload(task, call.arguments());
            case "read_subdoc" -> readSubdocPayload(task, call.arguments());
            case "search_skill" -> searchSkillPayload(task, call.arguments());
            case "update_plan" -> updatePlanPayload(call.arguments());
            case "get_project_state", "read_workspace_file",
                    "create_workspace_file", "rename_workspace_file", "delete_workspace_file",
                    "propose_patch", "search_block_ids", "describe_block_state" ->
                    callServerTool(toolName, normalizeArgsObject(call.arguments()));
            default -> toolError(toolName, "Unsupported tool");
        };
    }

    private static JsonObject updatePlanPayload(JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        if (!args.has("plan") || !args.get("plan").isJsonArray()) {
            return toolError("update_plan", "Missing plan array");
        }

        List<ClientSessionState.PlanItem> items = new ArrayList<>();
        for (JsonElement element : args.getAsJsonArray("plan")) {
            if (items.size() >= MAX_PLAN_ITEMS) {
                break;
            }
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String step = asString(obj, "step");
            if (step.isBlank()) {
                continue;
            }
            items.add(new ClientSessionState.PlanItem(step.trim(), normalizePlanStatus(asString(obj, "status"))));
        }

        ClientSessionState.setPlan(asString(args, "explanation"), items);
        JsonObject payload = toolOk("update_plan");
        payload.addProperty("count", items.size());
        return payload;
    }

    private static JsonObject listSkillsPayload(SubagentTask task) {
        JsonObject payload = toolOk("list_skills");
        String active = SkillStore.activeSkillId();
        payload.addProperty("active_skill_id", active == null ? "" : active);
        JsonArray items = new JsonArray();

        Set<String> scope = scopedSkillSet(task);
        for (SkillStore.SkillMeta meta : SkillStore.listSkills()) {
            if (!scope.isEmpty() && !scope.contains(meta.id())) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("id", meta.id());
            item.addProperty("name", meta.name());
            item.addProperty("description", meta.description());
            items.add(item);
        }

        payload.add("skills", items);
        payload.addProperty("count", items.size());
        payload.addProperty("scoped", !scope.isEmpty());
        return payload;
    }

    private static JsonObject readSkillPayload(SubagentTask task, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        Set<String> scope = scopedSkillSet(task);

        if (id.isBlank()) {
            if (!task.skillIds.isEmpty()) {
                id = task.skillIds.get(0);
            } else {
                id = SkillStore.activeSkillId();
            }
        }
        if (id.isBlank()) {
            return toolError("read_skill", "No skill id available");
        }
        if (!scope.isEmpty() && !scope.contains(id)) {
            return toolError("read_skill", "Skill not allowed for this subagent: " + id);
        }

        SkillStore.SkillDocument doc = SkillStore.readSkill(id);
        if (doc == null) {
            return toolError("read_skill", "Skill not found: " + id);
        }
        JsonObject payload = toolOk("read_skill");
        payload.addProperty("id", doc.meta().id());
        payload.addProperty("name", doc.meta().name());
        payload.addProperty("description", doc.meta().description());
        payload.addProperty("body", doc.body() == null ? "" : doc.body());
        JsonArray subdocs = new JsonArray();
        for (SkillStore.SubdocMeta subdoc : SkillStore.listSubdocs(doc.meta().id())) {
            JsonObject item = new JsonObject();
            item.addProperty("path", subdoc.path());
            item.addProperty("updated_at", subdoc.updatedAt());
            subdocs.add(item);
        }
        payload.add("subdocs", subdocs);
        payload.addProperty("subdoc_count", subdocs.size());
        return payload;
    }

    private static JsonObject readSubdocPayload(SubagentTask task, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String path = asString(args, "path");
        Set<String> scope = scopedSkillSet(task);

        if (id.isBlank()) {
            if (!task.skillIds.isEmpty()) {
                id = task.skillIds.get(0);
            } else {
                id = SkillStore.activeSkillId();
            }
        }
        if (id.isBlank()) {
            return toolError("read_subdoc", "No skill id available");
        }
        if (!scope.isEmpty() && !scope.contains(id)) {
            return toolError("read_subdoc", "Skill not allowed for this subagent: " + id);
        }
        if (path.isBlank()) {
            return toolError("read_subdoc", "Missing path");
        }

        SkillStore.SubdocDocument doc = SkillStore.readSubdoc(id, path);
        if (doc == null) {
            return toolError("read_subdoc", "Subdoc not found: " + id + "/" + path);
        }
        JsonObject payload = toolOk("read_subdoc");
        payload.addProperty("id", doc.skillId());
        payload.addProperty("path", doc.path());
        payload.addProperty("body", doc.body() == null ? "" : doc.body());
        payload.addProperty("updated_at", doc.updatedAt());
        return payload;
    }

    private static JsonObject searchSkillPayload(SubagentTask task, JsonElement arguments) {
        JsonObject args = normalizeArgsObject(arguments);
        String id = asString(args, "id");
        String query = asString(args, "query");
        int limit = asInt(args, "limit", 10, 1, 50);
        if (query.isBlank()) {
            return toolError("search_skill", "Missing query");
        }

        Set<String> scope = scopedSkillSet(task);
        List<SkillStore.SearchHit> hits = new ArrayList<>();
        if (!id.isBlank()) {
            if (!scope.isEmpty() && !scope.contains(id)) {
                return toolError("search_skill", "Skill not allowed for this subagent: " + id);
            }
            hits.addAll(SkillStore.searchSkill(id, query, limit));
        } else if (!scope.isEmpty()) {
            int remaining = limit;
            for (String skillId : scope) {
                if (remaining <= 0) {
                    break;
                }
                List<SkillStore.SearchHit> partial = SkillStore.searchSkill(skillId, query, remaining);
                hits.addAll(partial);
                remaining = limit - hits.size();
            }
        } else {
            hits.addAll(SkillStore.searchSkill("", query, limit));
        }

        if (hits.size() > limit) {
            hits = hits.subList(0, limit);
        }

        JsonObject payload = toolOk("search_skill");
        payload.addProperty("query", query);
        payload.addProperty("limit", limit);
        JsonArray arr = new JsonArray();
        for (SkillStore.SearchHit hit : hits) {
            JsonObject item = new JsonObject();
            item.addProperty("id", hit.skillId());
            item.addProperty("line", hit.line());
            item.addProperty("text", hit.text());
            arr.add(item);
        }
        payload.add("matches", arr);
        payload.addProperty("count", arr.size());
        if (arr.isEmpty()) {
            payload.addProperty("warning", "No matches");
        }
        return payload;
    }

    private static JsonObject callServerTool(String toolName, JsonObject args) {
        try {
            JsonObject payload = ClientToolBridge.call(toolName, args).join();
            if (payload == null) {
                return toolError(toolName, "Empty server response");
            }
            return payload;
        } catch (Exception e) {
            return toolError(toolName, "Server tool failed: " + formatError(e));
        }
    }

    private static String buildSubagentSystemPrompt(SubagentTask task, SubagentProfileStore.SubagentProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(P2SClientConfig.getSystemPrompt()).append('\n').append('\n');
        sb.append("## Subagent Contract").append('\n');
        sb.append("- You are a specialized subagent created by a parent agent.").append('\n');
        sb.append("- Focus only on the assigned task and return concise, actionable output.").append('\n');
        sb.append("- Do not ask to create, list, or delete subagents.").append('\n');
        sb.append("- Use only available tools and prefer minimal tool calls.").append('\n');
        sb.append("- If insufficient context exists, explain precisely what is missing.").append('\n');
        sb.append('\n');
        sb.append("Profile: ").append(profile.id()).append(" / ").append(profile.name()).append('\n');
        if (profile.systemPrompt() != null && !profile.systemPrompt().isBlank()) {
            sb.append(profile.systemPrompt().trim()).append('\n');
        }
        if (task.skillIds != null && !task.skillIds.isEmpty()) {
            sb.append("Scoped skills: ").append(String.join(", ", task.skillIds)).append('\n');
        }
        return sb.toString();
    }

    private static ResolveSkillResult resolveSkillIds(List<String> requested, boolean hasExplicitSkillIds) {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (SkillStore.SkillMeta meta : SkillStore.listSkills()) {
            available.add(meta.id());
        }

        if (!hasExplicitSkillIds || requested.isEmpty()) {
            return new ResolveSkillResult(List.of(), List.of());
        }

        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        String unknownPrefix = "Unknown requested skill id: ";

        for (String id : requested) {
            if (available.contains(id)) {
                chosen.add(id);
            } else {
                warnings.add(unknownPrefix + id);
            }
        }
        return new ResolveSkillResult(new ArrayList<>(chosen), warnings);
    }

    private static Set<String> scopedSkillSet(SubagentTask task) {
        if (task == null || task.skillIds == null || task.skillIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(task.skillIds);
    }

    private static SubagentTask getBySession(String sessionId, String subagentId) {
        if (subagentId == null || subagentId.isBlank()) {
            return null;
        }
        SubagentTask task = TASKS.get(subagentId);
        if (task == null) {
            return null;
        }
        String sid = sessionKey(sessionId);
        synchronized (task) {
            if (!sid.equals(task.sessionId)) {
                return null;
            }
        }
        return task;
    }

    private static TaskSnapshot snapshot(SubagentTask task) {
        synchronized (task) {
            return new TaskSnapshot(
                    task.id,
                    task.profileId,
                    task.profileName,
                    task.task,
                    task.status,
                    task.summary == null ? "" : task.summary,
                    task.response == null ? "" : task.response,
                    task.error == null ? "" : task.error,
                    new ArrayList<>(task.skillIds),
                    task.metadata == null ? null : task.metadata.deepCopy(),
                    new ArrayList<>(task.toolTrace),
                    task.attempt,
                    task.attemptCount,
                    new ArrayList<>(task.attempts),
                    task.createdAt,
                    task.startedAt,
                    task.endedAt
            );
        }
    }

    private static boolean shouldStop(SubagentTask task) {
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED || task.cancelRequested) {
                if (task.status != SubagentStatus.DELETED) {
                    task.status = SubagentStatus.CANCELLED;
                }
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
                if (task.status == SubagentStatus.CANCELLED) {
                    task.summary = "Cancelled";
                    appendAttemptRecord(task);
                }
                return true;
            }
            return false;
        }
    }

    private static void complete(SubagentTask task, String response) {
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED || task.cancelRequested) {
                if (task.status != SubagentStatus.DELETED) {
                    task.status = SubagentStatus.CANCELLED;
                }
                if (task.endedAt <= 0) {
                    task.endedAt = System.currentTimeMillis();
                }
                return;
            }
            task.status = SubagentStatus.COMPLETED;
            task.response = truncateText(response, MAX_RESPONSE_CHARS);
            task.summary = summarize(task.response);
            task.endedAt = System.currentTimeMillis();
            appendAttemptRecord(task);
        }
    }

    private static void fail(SubagentTask task, String message) {
        synchronized (task) {
            if (task.status == SubagentStatus.DELETED) {
                return;
            }
            if (task.cancelRequested) {
                task.status = SubagentStatus.CANCELLED;
                task.summary = "Cancelled";
            } else {
                task.status = SubagentStatus.FAILED;
                task.summary = "Failed";
                task.error = truncateText(message, MAX_RESPONSE_CHARS);
            }
            if (task.endedAt <= 0) {
                task.endedAt = System.currentTimeMillis();
            }
            appendAttemptRecord(task);
        }
    }

    private static boolean canContinue(SubagentStatus status) {
        return status == SubagentStatus.FAILED
                || status == SubagentStatus.COMPLETED
                || status == SubagentStatus.CANCELLED;
    }

    private static void appendAttemptRecord(SubagentTask task) {
        if (task == null) {
            return;
        }
        int attempt = task.attempt;
        for (int i = task.attempts.size() - 1; i >= 0; i--) {
            if (task.attempts.get(i).attempt() == attempt) {
                return;
            }
        }
        task.attempts.add(new AttemptRecord(
                attempt,
                task.status,
                truncateText(task.summary == null ? "" : task.summary, MAX_SUMMARY_CHARS),
                truncateText(task.response == null ? "" : task.response, MAX_SUMMARY_CHARS),
                truncateText(task.error == null ? "" : task.error, MAX_SUMMARY_CHARS),
                task.startedAt,
                task.endedAt
        ));
        while (task.attempts.size() > MAX_ATTEMPT_RECORDS) {
            task.attempts.remove(0);
        }
    }

    private static void appendTrace(SubagentTask task, String toolName, JsonObject payload) {
        synchronized (task) {
            if (task.toolTrace.size() >= MAX_TRACE_ITEMS) {
                return;
            }
            String name = toolName == null ? "" : toolName;
            boolean ok = payload != null && payload.has("ok") && payload.get("ok").getAsBoolean();
            String trace = name + " -> " + (ok ? "ok" : "error");
            if (!ok && payload != null && payload.has("error")) {
                trace = trace + " (" + truncateText(payload.get("error").getAsString(), 120) + ")";
            }
            task.toolTrace.add(trace);
        }
    }

    private static JsonObject buildToolMessage(LLMService.ToolCall call, JsonObject payload) {
        JsonObject toolMsg = new JsonObject();
        toolMsg.addProperty("role", "tool");
        if (call != null && call.id() != null && !call.id().isBlank()) {
            toolMsg.addProperty("tool_call_id", call.id());
        }
        toolMsg.addProperty("content", payload == null ? "{}" : GSON.toJson(payload));
        return toolMsg;
    }

    private static List<JsonObject> deepCopyMessages(List<JsonObject> source) {
        List<JsonObject> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (JsonObject msg : source) {
            copy.add(msg == null ? new JsonObject() : msg.deepCopy());
        }
        return copy;
    }

    private static JsonObject normalizeArgsObject(JsonElement argsElem) {
        if (argsElem == null || argsElem.isJsonNull()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = argsElem;
            if (argsElem.isJsonPrimitive() && argsElem.getAsJsonPrimitive().isString()) {
                String raw = argsElem.getAsString();
                if (raw == null || raw.isBlank()) {
                    return new JsonObject();
                }
                parsed = JsonParser.parseString(raw);
            }
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            return new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static List<String> parseStringArray(JsonElement element) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                continue;
            }
            String value;
            try {
                value = item.getAsString();
            } catch (Exception ignored) {
                continue;
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            values.add(value.trim());
        }
        return new ArrayList<>(values);
    }

    private static int asInt(JsonObject obj, String key, int fallback, int min, int max) {
        if (obj == null || key == null || !obj.has(key)) {
            return fallback;
        }
        try {
            int value = obj.get(key).getAsInt();
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String asString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return "";
        }
        try {
            String value = obj.get(key).getAsString();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizePlanStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "pending";
        }
        return switch (raw.trim().toLowerCase()) {
            case "completed" -> "completed";
            case "in_progress" -> "in_progress";
            case "pending" -> "pending";
            default -> "pending";
        };
    }

    private static String statusName(SubagentStatus status) {
        if (status == null) {
            return "";
        }
        return status.name().toLowerCase();
    }

    private static String sessionKey(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static JsonObject ok(String toolName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        return payload;
    }

    private static JsonObject error(String toolName, String err) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("tool", toolName == null ? "" : toolName);
        payload.addProperty("error", err == null ? "" : err);
        return payload;
    }

    private static JsonObject toolOk(String toolName) {
        return ok(toolName);
    }

    private static JsonObject toolError(String toolName, String err) {
        return error(toolName, err);
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray arr = new JsonArray();
        if (values == null) {
            return arr;
        }
        for (String value : values) {
            arr.add(value == null ? "" : value);
        }
        return arr;
    }

    private static String summarize(String response) {
        String text = response == null ? "" : response.trim();
        if (text.isBlank()) {
            return "Completed";
        }
        return truncateText(text, MAX_SUMMARY_CHARS);
    }

    private static String truncateText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    private static String formatError(Throwable ex) {
        Throwable cause = ex;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return "Timed out";
        }
        String message = cause == null ? null : cause.getMessage();
        if (message == null || message.isBlank()) {
            message = ex == null ? "" : ex.getMessage();
        }
        return message == null || message.isBlank() ? "Unknown error" : message;
    }

    private enum SubagentStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        DELETED
    }

    private static final class SubagentTask {
        String id;
        String sessionId;
        String profileId;
        String profileName;
        String task;
        SubagentStatus status = SubagentStatus.QUEUED;
        String summary = "";
        String response = "";
        String error = "";
        List<String> skillIds = new ArrayList<>();
        JsonObject metadata;
        List<String> toolTrace = new ArrayList<>();
        List<JsonObject> history = new ArrayList<>();
        int attempt = 1;
        int attemptCount = 1;
        List<AttemptRecord> attempts = new ArrayList<>();
        long createdAt;
        long startedAt;
        long endedAt;
        boolean cancelRequested = false;
    }

    private record TaskSnapshot(
            String id,
            String profileId,
            String profileName,
            String task,
            SubagentStatus status,
            String summary,
            String response,
            String error,
            List<String> skillIds,
            JsonObject metadata,
            List<String> toolTrace,
            int attempt,
            int attemptCount,
            List<AttemptRecord> attempts,
            long createdAt,
            long startedAt,
            long endedAt
    ) {
    }

    private record AttemptRecord(
            int attempt,
            SubagentStatus status,
            String summary,
            String response,
            String error,
            long startedAt,
            long endedAt
    ) {
    }

    private record ResolveSkillResult(
            List<String> skillIds,
            List<String> warnings
    ) {
    }
}
