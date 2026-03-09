# P2S doc ownership

## Goal

Keep each document focused on one audience so information does not fight itself.

## `README.md`

Audience: end users.

Include:

- mod purpose
- install prerequisites
- quick start
- key UI entrypoints
- how to configure client/server API access
- how the normal play workflow works
- common problems users will hit

Avoid:

- source tree tours
- class/file indexes
- tool schema internals
- CI/release implementation details
- contributor workflow rules

## `AGENTS.md`

Audience: Codex and contributors working inside the repo.

Include:

- repo layout
- build/test expectations
- coding style
- doc maintenance rules
- pointers to the maintenance skill and reference files

Avoid:

- long end-user tutorials
- repeated architecture dumps that are better kept in skill references

## Skill references

Audience: future agent turns doing repo maintenance.

Put here:

- architecture map
- source-of-truth file list
- doc verification workflow
- where to check commands/config/storage/tool names

## `CHANGELOG.md`

Audience: maintainers and readers looking for project history.

Include only meaningful dated changes. Prefer grouping related commits into one dated section instead of mirroring git line by line.
