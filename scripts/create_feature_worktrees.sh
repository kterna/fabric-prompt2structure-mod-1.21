#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BASE_DIR="$(cd "$ROOT_DIR/.." && pwd)"

create_wt() {
  local branch="$1"
  local dir="$2"
  if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/heads/$branch"; then
    git -C "$ROOT_DIR" worktree add "$BASE_DIR/$dir" "$branch"
  else
    git -C "$ROOT_DIR" worktree add -b "$branch" "$BASE_DIR/$dir"
  fi
}

create_wt "feat/todo-tool-slim" "wt-todo-tool-slim"
create_wt "feat/checkpoint-rollback" "wt-checkpoint-rollback"
create_wt "feat/workspace-diff-panel" "wt-workspace-diff-panel"

echo "Done. Current worktrees:"
git -C "$ROOT_DIR" worktree list
