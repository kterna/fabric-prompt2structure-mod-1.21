---
name: p2s-debug-gateway
description: Use the local Prompt2Structure/P2S debug gateway HTTP and SSE API. Use when Codex needs to inspect gateway state, submit external agent jobs, stream job events, drive patch apply/discard or choice selection loops, or manage P2S projects and sessions through /debug/agent endpoints.
---

# P2S Debug Gateway

Use this skill to operate the client-side debug gateway exposed by `ClientDebugGateway`.
Treat the gateway as a local debug automation surface, not as a production or multiplayer server API.

## Quick Workflow

1. Confirm the gateway is expected to run:
   - It only starts when `P2SMod.DEBUG` is true and `debugGatewayEnabled` is true.
   - Default config is `debugGatewayHost=0.0.0.0`, `debugGatewayPort=17862`, `debugGatewayExposeSse=true`.
   - The client must be connected to a compatible P2S server bridge for project/job tool calls.
2. Set the root URL for commands:
   ```bash
   ROOT=http://127.0.0.1:17862/debug/agent
   ```
   If the Minecraft client runs outside the current network namespace, replace `127.0.0.1` with the host IP.
3. Check state before mutating anything:
   ```bash
   curl -s "$ROOT/state"
   ```
   Require `gateway_running=true`, `server_bridge_ready=true`, and `has_project=true` before submitting jobs.
4. Use HTTP to prepare the in-game context when needed:
   ```bash
   curl -s -X POST "$ROOT/selection" \
     -H "Content-Type: application/json" \
     -d '{"pos1":{"x":0,"y":64,"z":0},"pos2":{"x":4,"y":68,"z":4}}'

   curl -s -X POST "$ROOT/tools/call" \
     -H "Content-Type: application/json" \
     -d '{"tool_name":"get_project_state","arguments":{}}'
   ```
5. Submit one job at a time:
   ```bash
   curl -s -X POST "$ROOT/jobs" \
     -H "Content-Type: application/json" \
     -d '{"message":"Describe the current project state."}'
   ```
6. Track the returned `status_url` or `events_url`.
   - Use `GET /jobs/{jobId}` for polling.
   - Use `curl -N "$ROOT/jobs/{jobId}/events"` for SSE streaming when enabled.
7. When a job enters `awaiting_patch` or `awaiting_choice`, complete the loop through the matching follow-up endpoint.

## Job State Rules

Only one external debug job can be active. A new job returns `409 busy` if another external job or the in-game agent is running.

Common states:

- `accepted`: job was created and accepted by the gateway.
- `running`: the client agent is processing the submitted message.
- `awaiting_patch`: a patch preview is waiting for apply or discard.
- `awaiting_choice`: a choice request is waiting for an option or custom response.
- `completed`: the agent turn settled without pending follow-up.
- `failed`: gateway/client monitoring detected an error.
- `cancelled`: external tracking was cancelled. This does not interrupt the underlying agent turn.

Use the pending payload from `GET /jobs/{jobId}` or SSE events before deciding which follow-up endpoint to call.

## Follow-Up Actions

Patch:

```bash
curl -s -X POST "$ROOT/jobs/$JOB_ID/patch/apply"
curl -s -X POST "$ROOT/jobs/$JOB_ID/patch/discard" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Rejecting this preview during debug automation."}'
```

Choice:

```bash
curl -s -X POST "$ROOT/jobs/$JOB_ID/choice/select" \
  -H "Content-Type: application/json" \
  -d '{"option_id":"OPTION_ID_FROM_PENDING_CHOICE"}'

curl -s -X POST "$ROOT/jobs/$JOB_ID/choice/custom" \
  -H "Content-Type: application/json" \
  -d '{"text":"Use this custom answer."}'
```

## Project And Session Setup

Use the project/session endpoints when automation needs a known context before submitting a job:

```bash
curl -s "$ROOT/projects"
curl -s "$ROOT/sessions"
curl -s "$ROOT/workspaces"
curl -s -X POST "$ROOT/projects/open" -H "Content-Type: application/json" -d '{"id":"PROJECT_ID"}'
curl -s -X POST "$ROOT/workspaces/select" -H "Content-Type: application/json" -d '{"path":"WORKSPACE_PATH"}'
curl -s -X POST "$ROOT/sessions/switch" -H "Content-Type: application/json" -d '{"id":"SESSION_ID"}'
```

Do not create, update, delete, or switch projects/sessions while a job is active, while the agent is busy, or while a patch/choice is pending.

## Generic Tool Bridge

Prefer `POST /tools/call` for complete external validation of server-side P2S tools:

```bash
curl -s -X POST "$ROOT/tools/call" \
  -H "Content-Type: application/json" \
  -d '{"tool_name":"read_workspace_file","arguments":{"path":"WORKSPACE_PATH"}}'
```

Use `GET /tools` or `GET /capabilities` to discover the current tool names exposed by the gateway.

## Reference

Read `references/debug-gateway-api.md` when endpoint details, request bodies, response fields, SSE event names, or error codes matter.

If changing this skill after gateway code changes, verify against:

- `src/client/java/com/p2s/ClientDebugGateway.java`
- `src/client/java/com/p2s/P2SClientConfig.java`
