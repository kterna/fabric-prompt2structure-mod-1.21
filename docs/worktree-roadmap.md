# 三项工作的并行 worktree 方案（代码现状梳理）

本仓库当前已经具备以下基础：

- **会话动作通道**：`start/end/undo/redo/save/apply/discard` 已有统一入口（`SessionManager.handleSessionAction`）。
- **工具调用通道**：客户端通过 `ClientToolBridge` 发起工具请求，服务端在 `SessionManager.processToolCalls` 处理。 
- **Chat UI 左侧工作区**：`P2SChatScreen` 已经有“加载工作区 JSON / 行范围 / 上下滚动 / 上下文队列”的完整骨架。 
- **Todo 状态模型**：客户端 `ClientSessionState` 已有 todo 数据结构与增删改 API。

---

## 建议的三个 worktree

> 目标是降低冲突，并保证每项可独立评审。

### 1) `feat/todo-tool-slim`
**任务**：合并 todo 工具，减少工具 schema 占用上下文。

**当前现状**（建议改造点）：
- 在 `LLMService.buildSessionToolDefinitions(...)` 中，todo 相关工具目前分散为：
  - `get_todo`
  - `set_todo`
  - `edit_todo_item`
  - `delete_todo_item`
  - `clear_todo`
- 在 `SessionManager.processToolCalls(...)` 中也存在对应分支处理。

**建议方向**：
- 合并为单一工具（例如 `todo` 或 `todo_batch`），通过 `action` 字段区分 `get/set/upsert/delete/clear`。
- 统一响应格式，减少工具定义 token 和模型选择歧义。

---

### 2) `feat/checkpoint-rollback`
**任务**：增加 checkpoint 机制，支持“回退工作区 + 会话”或“仅回退会话”。

**当前现状**（可复用基础）：
- 已有 `CommitEntry`、`undoStack/redoStack`（可作为 checkpoint 历史基础）。
- 已有 `handleSessionAction` 网络动作入口，便于新增动作：
  - `create_checkpoint`
  - `rollback_checkpoint`
- 已有 `sendSessionSync(...)` 与客户端 `ClientSessionState` 状态同步机制。

**建议方向**：
- 新增 checkpoint 模型（id、label、revision、history snapshot、script snapshot）。
- 回退策略支持：
  - `workspace_and_session`
  - `session_only`
- 在 chat UI 增加 checkpoint 列表与回退确认。

---

### 3) `feat/workspace-diff-panel`
**任务**：chat UI 左侧工作区增加 diff 能力，展示 agent 实时修改并支持 accept。

**当前现状**（可复用基础）：
- `P2SChatScreen` 左侧已有 JSON 编辑器和行范围机制。
- 服务端已有 patch 预览与待确认流：`pendingPatch`、`S2CPatchPreviewPayload`、`apply/discard`。

**建议方向**：
- 在左侧增加 “base vs staged” 差异视图（先做行级 diff，再演进到结构级 diff）。
- 与现有 `apply/discard` 按钮联动，形成统一审阅流。
- 首版可先只读展示 + 接受（accept=apply），后续再加局部接收。

---

## 建议执行顺序

1. **先做 todo 工具合并**：减少上下文和提示复杂度，降低后续两项调试噪音。  
2. **再做 checkpoint**：打通“可回退”主能力。  
3. **最后做 workspace diff UI**：基于前两项稳定状态做可视化体验。

---

## 建议分支命名与 worktree 目录

- `feat/todo-tool-slim` -> `../wt-todo-tool-slim`
- `feat/checkpoint-rollback` -> `../wt-checkpoint-rollback`
- `feat/workspace-diff-panel` -> `../wt-workspace-diff-panel`

可直接使用仓库根目录下脚本：`scripts/create_feature_worktrees.sh`。
