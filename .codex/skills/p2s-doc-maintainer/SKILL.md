---
name: p2s-doc-maintainer
description: Maintain P2S Workspace repository docs and locate the current source of truth in the codebase. Use when updating README, CHANGELOG, AGENTS, or when checking whether user-facing docs, architecture notes, tool names, config keys, storage paths, or workflows still match the current Fabric mod implementation.
---

# P2S Doc Maintainer

Keep P2S Workspace documentation aligned with the current repository state. Use this skill to decide what belongs in `README.md`, what belongs in `AGENTS.md`, and where to verify claims in source code before editing docs.

## Workflow

1. Decide the target audience of the doc you are editing.
2. Verify the relevant behavior in source before rewriting prose.
3. Keep `README.md` user-facing; move repo/source details to `AGENTS.md` or the reference files in this skill.
4. Update `CHANGELOG.md` only for meaningful dated project changes, not for every wording cleanup.
5. After editing docs, re-check that commands, paths, config keys, and UI entrypoints still match code.

## Document Split

### `README.md`

Keep only end-user content:

- what the mod does
- installation prerequisites
- how to open the UI and start using it
- config files users need to edit
- save/data locations users may want to back up
- common troubleshooting and outdated-command warnings

Avoid putting these in `README.md` unless absolutely needed for user operation:

- class names
- source tree walkthroughs
- implementation architecture
- detailed tool schema lists
- CI, build internals, or code maintenance workflow

### `AGENTS.md`

Keep contributor/agent guidance here:

- repo layout
- build and validation commands
- coding style
- doc maintenance rules
- pointers to the reference files in this skill

### `CHANGELOG.md`

Keep dated project history here:

- user-visible feature milestones
- protocol/storage transitions
- UI workflow changes
- meaningful refactors worth preserving historically

Do not log every small doc rewrite.

## Verification Checklist

Before changing documentation, verify against the relevant source files.

### User entrypoints

- Open chat key: `src/client/java/com/p2s/ModKeyBindings.java`
- Default selection item: `src/client/java/com/p2s/P2SClientConfig.java`
- Remaining server command(s): `src/main/java/com/p2s/ModCommandRegistry.java`
- Multiplayer bridge requirement: `src/client/java/com/p2s/ClientServerBridge.java`

### Session/project/workspace workflow

- Client agent/session behavior: `src/client/java/com/p2s/ClientAgentManager.java`
- Chat UI behavior: `src/client/java/com/p2s/P2SChatScreen.java`
- Server tool bridge and session actions: `src/main/java/com/p2s/SessionManager.java`
- Project/workspace persistence: `src/main/java/com/p2s/ProjectPersistence.java`

### Config and persistence

- Server config keys: `src/main/java/com/p2s/ModConfig.java`
- Client config keys: `src/client/java/com/p2s/P2SClientConfig.java`
- Saved client sessions: `src/client/java/com/p2s/store/SessionPersistence.java`

### Build/release metadata

- Version matrix: `settings.json`, `versions/*/gradle.properties`
- Shared Gradle setup: `common.gradle`, `build.gradle`, `gradle.properties`
- CI/release workflow: `.github/workflows/build.yml`, `.github/workflows/release-on-tag.yml`
- Mod metadata: `src/main/resources/fabric.mod.json`

## References

- For current repo architecture and source-of-truth file map, read `references/repo-map.md`.
- For doc ownership and where different kinds of information should live, read `references/doc-ownership.md`.
