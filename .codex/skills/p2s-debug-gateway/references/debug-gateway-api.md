# P2S debug gateway API

## Contents

- Runtime and conventions
- State endpoints
- Capabilities and tool bridge endpoints
- Selection endpoints
- Project state and workspace endpoints
- Job endpoints
- Patch and choice follow-up
- Project endpoints
- Session endpoints
- SSE events
- Error behavior
- Curl examples
- Source-of-truth files

## Runtime and conventions

Base path: `/debug/agent`

Default local URL:

```bash
ROOT=http://127.0.0.1:17862/debug/agent
```

Defaults in `P2SClientConfig`:

- `debugGatewayEnabled`: defaults to `P2SMod.DEBUG`
- `debugGatewayHost`: `0.0.0.0`
- `debugGatewayPort`: `17862`
- `debugGatewayExposeSse`: `true`

HTTP conventions:

- Methods: `GET`, `POST`, `OPTIONS`
- JSON content type: `application/json; charset=utf-8`
- CORS: `Access-Control-Allow-Origin: *`
- Request body limit: 1 MiB
- Empty or non-object request bodies parse as `{}`.
- Error responses use `{"error":"code","message":"text"}`.

Gateway operations that call server-side tools require a compatible P2S client/server bridge. These calls can return `409 server_unavailable`.

## State endpoints

`GET /debug/agent`

`GET /debug/agent/`

`GET /debug/agent/state`

Response fields:

- `debug_enabled`
- `gateway_running`
- `bind_host`
- `bind_port`
- `server_bridge_ready`
- `has_project`
- `project_id`
- `project_name`
- `session_active`
- `session_id`
- `selected_workspace_path`
- `busy`
- `pending_patch`
- `pending_choice`
- `current_job_id`
- `selection`

## Capabilities and tool bridge endpoints

`GET /debug/agent/capabilities`

Returns endpoint names, available tool bridge tool names, body limit, SSE availability, current state, and `api_version`.

`GET /debug/agent/tools`

Returns known server-side tool bridge names.

`POST /debug/agent/tools/call`

Generic HTTP wrapper around the P2S tool bridge.

Body:

```json
{
  "tool_name": "required",
  "arguments": {}
}
```

`tool` may be used instead of `tool_name`; `args` may be used instead of `arguments`.

Known tool names:

- `list_projects`
- `create_project`
- `open_project`
- `rename_project`
- `delete_project`
- `get_project_state`
- `read_workspace_file`
- `create_workspace_file`
- `save_workspace_file`
- `rename_workspace_file`
- `delete_workspace_file`
- `propose_patch`
- `search_block_ids`
- `describe_block_state`
- `compose_block_state`
- `describe_block_entity_template`
- `debug_stage_blocks`

## Selection endpoints

`GET /debug/agent/selection`

Returns:

- `ok`
- `selection.pos1`
- `selection.pos2`
- `selection.complete`
- `selection.min`, `selection.max`, and `selection.size` when complete

`POST /debug/agent/selection`

Set one or both selection points through the client/server selection bridge.

Body with both points:

```json
{
  "pos1": {"x": 0, "y": 64, "z": 0},
  "pos2": {"x": 4, "y": 68, "z": 4}
}
```

Body with one point:

```json
{
  "point": 0,
  "pos": {"x": 0, "y": 64, "z": 0}
}
```

`point_index` may be used instead of `point`. A direct body with `x`, `y`, and `z` is accepted when `point` is also supplied.

`POST /debug/agent/selection/clear`

Clears the current selection.

`POST /debug/agent/selection` with `{"clear": true}` also clears the selection.

## Project state and workspace endpoints

`GET /debug/agent/project/state`

Calls `get_project_state` and returns its payload.

`GET /debug/agent/workspaces`

Calls `get_project_state` and returns:

- `ok`
- `action=list_workspaces`
- `selected_workspace_path`
- `project`
- `workspace_files`
- `pending_paths`

`POST /debug/agent/workspaces/select`

Selects an existing workspace path for the current client session.

Body:

```json
{
  "path": "required"
}
```

## Job endpoints

`POST /debug/agent/jobs`

Body:

```json
{
  "message": "required",
  "display_text": "optional",
  "session_id": "optional",
  "project_id": "optional",
  "selected_workspace_path": "optional"
}
```

Accepted response status: `202`

Accepted response fields:

- `job_id`
- `session_id`
- `status`
- `events_url`
- `status_url`

Preconditions:

- `message` must be non-blank.
- A compatible server bridge must be available.
- A project must be open.
- The agent must not already be busy.
- No pending patch or choice may be awaiting a decision.
- Optional `project_id` and `session_id` must match the current client state when supplied.
- Optional `selected_workspace_path` must be valid for the current project.

`GET /debug/agent/jobs/{jobId}`

Response fields:

- `job_id`
- `status`
- `created_at`
- `started_at`
- `ended_at`
- `session_id`
- `client_status`
- `assistant_text`
- `streaming_text`
- `plan`
- `pending_patch`
- `pending_choice`
- `subagents`
- `error`
- `events_url`
- `status_url`

Job statuses:

- `accepted`
- `running`
- `awaiting_patch`
- `awaiting_choice`
- `completed`
- `failed`
- `cancelled`

`GET /debug/agent/jobs/{jobId}/events`

Open an SSE stream for a known job. Returns `404` when SSE is disabled by `debugGatewayExposeSse=false`.

`POST /debug/agent/jobs/{jobId}/cancel`

Cancels external tracking. The gateway response notes that underlying agent interruption is not supported; tracking stops once the current turn settles.

## Patch and choice follow-up

`POST /debug/agent/jobs/{jobId}/patch/apply`

Requires job state `awaiting_patch`. Body is ignored.

`POST /debug/agent/jobs/{jobId}/patch/discard`

Requires job state `awaiting_patch`.

Body:

```json
{
  "reason": "optional"
}
```

`POST /debug/agent/jobs/{jobId}/choice/select`

Requires job state `awaiting_choice`.

Body:

```json
{
  "option_id": "required"
}
```

`POST /debug/agent/jobs/{jobId}/choice/custom`

Requires job state `awaiting_choice`.

Body:

```json
{
  "text": "required"
}
```

Follow-up responses use status `202` and payload:

```json
{
  "job_id": "id",
  "status": "accepted"
}
```

## Project endpoints

`GET /debug/agent/projects`

Calls the `list_projects` tool bridge and returns its payload.

`POST /debug/agent/projects/create`

Body:

```json
{
  "name": "optional",
  "description": "optional"
}
```

Calls `create_project`. On success, returns `ok`, `action=create_project`, `result`, and updated `state`.

`POST /debug/agent/projects/open`

Body:

```json
{
  "id": "required"
}
```

Calls `open_project`. On success, returns `ok`, `action=open_project`, `result`, and updated `state`.

`POST /debug/agent/projects/update`

Body:

```json
{
  "id": "required",
  "name": "optional",
  "description": "optional"
}
```

Calls `rename_project`. On success, returns `ok`, `action=update_project`, and `result`.

`POST /debug/agent/projects/delete`

Body:

```json
{
  "id": "required"
}
```

Calls `delete_project`. On success, returns `ok`, `action=delete_project`, `result`, and updated `state`.

All mutating project endpoints reject active external jobs, busy agent runs, pending patches, and pending choices.

## Session endpoints

`GET /debug/agent/sessions`

Lists saved sessions. If a project is open, the list is scoped to the current project.

Response fields:

- `ok`
- `project_id`
- `active_session_id`
- `sessions`

Each session item includes:

- `id`
- `project_id`
- `title`
- `created_at`
- `updated_at`
- `message_count`
- `active`

`POST /debug/agent/sessions/create`

Body:

```json
{
  "project_id": "optional",
  "selected_workspace_path": "optional",
  "start_now": true
}
```

Creates a new client session. When `start_now` is omitted, it defaults to `true`.

`POST /debug/agent/sessions/update`

Body:

```json
{
  "id": "required",
  "title": "optional",
  "project_id": "optional",
  "selected_workspace_path": "optional"
}
```

At least one of `title`, `project_id`, or `selected_workspace_path` is required. The active session cannot change `project_id`.

`POST /debug/agent/sessions/switch`

Body:

```json
{
  "id": "required",
  "project_id": "optional"
}
```

Switches to a saved session, opening the target project first when needed.

All mutating session endpoints reject active external jobs, busy agent runs, pending patches, and pending choices.

## SSE events

Event frames use:

```text
event: event.name
data: {"job_id":"id","timestamp":1234567890}
```

History is retained per job up to 256 events. The stream emits `: keep-alive` comments about every 15 seconds when idle.

Known event names:

- `job.accepted`
- `job.started`
- `job.completed`
- `job.failed`
- `job.cancelled`
- `status.changed`
- `assistant.delta`
- `assistant.message`
- `plan.updated`
- `patch.pending`
- `patch.cleared`
- `choice.pending`
- `choice.cleared`
- `subagents.updated`

Every event payload is enriched with:

- `job_id`
- `timestamp`
- `session_id` when available

## Error behavior

Common errors:

- `400 invalid_request`
- `400 invalid_json`
- `404 not_found`
- `405 method_not_allowed`
- `409 busy`
- `409 server_unavailable`
- `409 no_project`
- `409 pending_patch`
- `409 pending_choice`
- `409 project_mismatch`
- `409 session_mismatch`
- `409 inactive_job`
- `409 invalid_state`
- `413 payload_too_large`
- `422 invalid_workspace`
- `422` with a tool bridge payload when a tool returns `ok=false`
- `500 internal_error`
- `500 tool_failed`
- `504 tool_timeout`

## Curl examples

Check state:

```bash
curl -s "$ROOT/state"
```

Create a project:

```bash
curl -s -X POST "$ROOT/projects/create" \
  -H "Content-Type: application/json" \
  -d '{"name":"Debug Project","description":"Created through the debug gateway."}'
```

Open a project:

```bash
curl -s -X POST "$ROOT/projects/open" \
  -H "Content-Type: application/json" \
  -d '{"id":"PROJECT_ID"}'
```

Submit a job:

```bash
curl -s -X POST "$ROOT/jobs" \
  -H "Content-Type: application/json" \
  -d '{"message":"Inspect the current workspace and suggest next steps."}'
```

Poll a job:

```bash
curl -s "$ROOT/jobs/$JOB_ID"
```

Stream events:

```bash
curl -N "$ROOT/jobs/$JOB_ID/events"
```

Apply a pending patch:

```bash
curl -s -X POST "$ROOT/jobs/$JOB_ID/patch/apply"
```

Select a pending choice:

```bash
curl -s -X POST "$ROOT/jobs/$JOB_ID/choice/select" \
  -H "Content-Type: application/json" \
  -d '{"option_id":"OPTION_ID"}'
```

## Source-of-truth files

Verify this reference against current source before changing it:

- `src/client/java/com/p2s/ClientDebugGateway.java`
- `src/client/java/com/p2s/P2SClientConfig.java`
- `src/client/java/com/p2s/ClientAgentManager.java`
- `src/client/java/com/p2s/ClientServerBridge.java`
