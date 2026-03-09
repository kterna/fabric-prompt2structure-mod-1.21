# P2S Workspace repo map

## Current user entrypoints

- Main UI entry: press `O` to open or close the chat panel.
- Selection workflow: hold the configured selection item; left click sets `pos1`, right click sets `pos2`.
- No standalone reload command remains; editable runtime settings live in the client config/UI.
- Multiplayer requirement: client and server both need the mod for the bridge features to work.

## Runtime split

### Shared / server-side core

- `src/main/java/com/p2s/P2SMod.java`: mod entrypoint
- `src/main/java/com/p2s/ServerNetworkHandler.java`: C2S packet receivers
- `src/main/java/com/p2s/SessionManager.java`: session actions, tool bridge, project/workspace operations, patch preview/apply/discard
- `src/main/java/com/p2s/ProjectPersistence.java`: project index, project JSON, workspace TOML files
- `src/main/java/com/p2s/WorkspaceTomlCodec.java`: workspace TOML serialization
- `src/main/java/com/p2s/PatchTomlCodec.java`: patch TOML serialization
- `src/main/java/com/p2s/LLMService.java`: tool schema and LLM payload generation
- `src/main/java/com/p2s/P2SDefaults.java`: shared default values and built-in system prompt seed

### Client-side core

- `src/client/java/com/p2s/P2SModClient.java`: client entrypoint
- `src/client/java/com/p2s/ClientNetworkHandler.java`: S2C packet handling
- `src/client/java/com/p2s/ClientServerBridge.java`: bridge capability detection and session action transport
- `src/client/java/com/p2s/ClientToolBridge.java`: async tool bridge requests
- `src/client/java/com/p2s/ClientAgentManager.java`: client-side agent loop, tool execution, session restore/autosave
- `src/client/java/com/p2s/ClientSessionState.java`: chat/session UI state
- `src/client/java/com/p2s/P2SChatScreen.java`: main docked UI

## Current model of work

The current product model is:

- selection -> project -> workspace file -> chat-driven patch iteration

Important implementation facts:

- projects can contain multiple workspace files
- workspace files are stored as TOML
- patches are proposed as TOML inside `patch_toml`
- apply/discard is explicit in UI
- client sessions are auto-restored per project when possible

## Tool surface to verify when docs drift

### Project/workspace tool bridge

Defined primarily in `src/main/java/com/p2s/SessionManager.java` and exposed from the client through `src/client/java/com/p2s/ClientAgentManager.java`.

Commonly relevant tools:

- `list_projects`
- `create_project`
- `open_project`
- `rename_project`
- `get_project_state`
- `read_workspace_file`
- `create_workspace_file`
- `save_workspace_file`
- `rename_workspace_file`
- `delete_workspace_file`
- `propose_patch`
- `search_block_ids`

### Local client tools

- `list_skills`
- `read_skill`
- `read_subdoc`
- `search_skill`
- `update_plan`
- `request_user_choice`
- `clear_user_choice`
- `list_subagents`
- `create_subagent`
- `continue_subagent`
- `get_subagent`
- `delete_subagent`
- `list_profiles`
- `get_profile`

## Config and storage map

### Config

- Client config: `config/p2s_client.json`
- Skill data: `config/p2s_skills/`

### Data

- Project index and metadata: `config/p2s_projects_v2/`
- Exported/saved scripts: `config/p2s_storage/`
- Client session persistence: `config/p2s_sessions_v2/`

## Build and release files

- `settings.json`: enabled Minecraft versions
- `versions/1.21/gradle.properties`: version-specific deps
- `versions/1.21.1/gradle.properties`: version-specific deps
- `common.gradle`: shared Loom/sourceSet/dependency setup
- `.github/workflows/build.yml`: CI build
- `.github/workflows/release-on-tag.yml`: tag release job

## Recent history landmarks

Use `git log --date=short --oneline` and pay special attention to the 2026-03-06 through 2026-03-09 commits when checking documentation drift. Those commits introduced:

- project/workspace APIs
- multi-document workspace model
- client/server bridge changes
- local context compaction
- TOML workspace and patch migration
- gameplay-mode editor collapse
- update_plan alignment
