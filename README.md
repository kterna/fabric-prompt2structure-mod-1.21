# P2S Workspace (Fabric 1.21.x)

P2S Workspace is an AI IDE-style pipeline for Minecraft structure generation, patching, and iterative editing.

P2S 是一个用于 Minecraft 结构生成与迭代编辑的 Fabric 模组，支持两条主路径：
- 一次性生成（`/p2s <x> <y> <z> <prompt>`）
- 会话式 patch pipeline（`/p2s session` / `/p2s chat` / 客户端聊天面板）

当前结构统一为 V2：`palette + structures`。

## 架构概览

- 服务端入口：[P2SMod](src/main/java/com/p2s/P2SMod.java)
- 命令入口：[ModCommandRegistry](src/main/java/com/p2s/ModCommandRegistry.java)
- 会话编排与 patch 执行：[SessionManager](src/main/java/com/p2s/SessionManager.java)
- patch 引擎与校验：
  - [StructurePatchEngine](src/main/java/com/p2s/StructurePatchEngine.java)
  - [PatchValidator](src/main/java/com/p2s/PatchValidator.java)
  - [PatchModels](src/main/java/com/p2s/PatchModels.java)
- LLM 接口：[LLMService](src/main/java/com/p2s/LLMService.java)

### 客户端 Agent + Skill + Subagent（新增）

- 客户端 agent： [ClientAgentManager](src/client/java/com/p2s/ClientAgentManager.java)
  - 客户端聊天面板发送消息时，本地调用 LLM（使用 `p2s_client.json` 的模型配置）。
  - 支持本地 skill 工具：`list_skills` / `read_skill` / `search_skill`。
  - 支持 Codex 风格计划工具：`update_plan`（可选 `explanation` + `plan[]`，状态为 `pending` / `in_progress` / `completed`）。
  - 支持审批选项工具：`request_user_choice` / `clear_user_choice`（用户在聊天 UI 里点选后继续流程）。
  - 支持 subagent 管理工具：`list_subagents` / `create_subagent` / `get_subagent` / `delete_subagent` / `list_profiles` / `get_profile`。
- 服务端工具桥接： [ClientToolBridge](src/client/java/com/p2s/ClientToolBridge.java)
  - 客户端通过 `c2s_tool_bridge` 请求服务端工具（`read_workspace_file` / `propose_patch` / `search_block_ids`）。
  - `read_workspace_file` 当前返回 `state.workspace_toml` 作为工作区正文。
  - `propose_patch` 当前使用 `{ path, patch_toml }`；其中 `patch_toml` 是 TOML 补丁正文。
  - 服务端返回 `s2c_tool_bridge`。
- Skill 存储： [SkillStore](src/client/java/com/p2s/SkillStore.java)
  - 客户端全局目录：`config/p2s_skills/skills/<skill-id>/SKILL.md`
  - 活跃 skill：`config/p2s_skills/active.json`
  - 内置默认 skill 模板（自动创建）：
    - `default-builder`
    - `subagent-orchestrator`（主 agent 调用 subagent 的流程与参数说明）
  - `default-builder`：
    - 中文规则，提供基础建筑比例建议（占地长宽比、总高/层高、屋顶比例、边距等）
    - 开发模板文件：`src/client/resources/p2s_default_skills/default-builder/SKILL.md`
  - `subagent-orchestrator`：
    - 中文规则，说明 `create_subagent` / `get_subagent` / `list_profiles` 等工具的调用流程
    - 开发模板文件：`src/client/resources/p2s_default_skills/subagent-orchestrator/SKILL.md`
- Subagent 运行时： [SubagentManager](src/client/java/com/p2s/SubagentManager.java)
  - 异步任务状态：`queued` / `running` / `completed` / `failed` / `cancelled` / `deleted`
  - 主 agent 通过工具主动轮询 subagent 结果。
- Subagent profile 存储： [SubagentProfileStore](src/client/java/com/p2s/SubagentProfileStore.java)
  - 客户端全局共享目录：`config/p2s_skills/.agent/*.json`
  - 内置默认 profile 模板（中文）：
    - `src/client/resources/p2s_default_profiles/general-planner.json`
    - `src/client/resources/p2s_default_profiles/block-id-searcher.json`
    - `src/client/resources/p2s_default_profiles/patch-planner.json`
  - 默认 profile：`general-planner` / `block-id-searcher` / `patch-planner`
    - `patch-planner` 已合并 `workspace-analyzer` 的工作区分析能力
- Skill/LLM 配置 UI：
  - [P2SConfigScreen](src/client/java/com/p2s/P2SConfigScreen.java)
  - [P2SClientLLMConfigScreen](src/client/java/com/p2s/P2SClientLLMConfigScreen.java)
  - [P2SSkillConfigScreen](src/client/java/com/p2s/P2SSkillConfigScreen.java)
  - [P2SSkillEditorScreen](src/client/java/com/p2s/P2SSkillEditorScreen.java)

## 命令（权限 >= 2）

### 1) 一次性生成

- `/p2s <x> <y> <z> <prompt>`

### 2) 选区

- `/p2s select pos1 <x> <y> <z>`
- `/p2s select pos2 <x> <y> <z>`
- `/p2s select show`
- `/p2s select clear`

### 3) 会话模式

- `/p2s session start`
- `/p2s session end`
- `/p2s chat <message>`
- `/p2s session apply`
- `/p2s session discard`
- `/p2s session undo`
- `/p2s session redo`
- `/p2s session save [name]`

### 4) 其他命令

- `/p2s gen <prompt>`
- `/p2sreload`
- `/p2slist [limit]`
- `/p2sload <name> <x> <y> <z>`
- `/p2sdelete <name>`
- `/p2sprompt`
- `/p2sprompt list`
- `/p2sprompt set <name>`

## 会话流程（客户端聊天面板）

1. 用户在 `O` 键打开的面板中发送消息。
2. 客户端 agent 调用 LLM，并可先读 skill（`list/read/search_skill`）。
3. 如需分派任务，主 agent 可先创建 subagent（异步）并轮询其状态/结果。
4. 需要世界上下文时，通过 tool bridge 调用服务端工具：
   - `read_workspace_file`（读取 `workspace_toml`）
   - `propose_patch`（提交 `patch_toml`）
   - `search_block_ids`
5. 服务端对 patch 进行试算、diff、校验并生成 preview。
6. 用户在 UI 点击 Apply/Discard；Apply 后写入 commit，可 Undo/Redo。

## 配置

### 服务端配置

- 文件：`config/p2s.json`
- 主要字段：
  - `apiUrl` / `apiKey` / `model` / `httpTimeoutSeconds`
  - `useToolCall`
  - `maxPatchOps`
  - `maxBlocksPerCommit`
  - `confirmRequired`
  - `sessionJobTimeoutSeconds`
  - `riskAutoApplyThreshold`
  - `prompts`
  - `activePrompt`

环境变量覆盖（服务端）：
- `P2S_API_URL`
- `P2S_API_KEY`
- `P2S_MODEL`
- `P2S_TIMEOUT_SECONDS`
- `P2S_USE_TOOL_CALL`
- `P2S_MAX_PATCH_OPS`
- `P2S_MAX_BLOCKS_PER_COMMIT`
- `P2S_CONFIRM_REQUIRED`
- `P2S_SESSION_JOB_TIMEOUT_SECONDS`
- `P2S_PROMPT`

### 客户端配置

- 文件：`config/p2s_client.json`
- 主要字段：
  - `selectionItem`
  - `apiUrl`
  - `apiKey`
  - `model`
  - `httpTimeoutSeconds`
  - `useToolCall`
  - `systemPrompt`

说明：
- 客户端聊天面板使用 `p2s_client.json` 的 LLM 配置（包括 `apiKey`）。
- 进入聊天面板后点 `Config`，可在 `LLM` / `Skills` 页面直接编辑。
- 聊天面板会显示计划清单与待选择项；当 agent 发起 `request_user_choice` 后，需先点选选项再继续。

### Subagent Profile 配置

- 目录：`config/p2s_skills/.agent/*.json`
- 范围：客户端全局共享（不按玩家 UUID 隔离）。
- 默认模板来源：`src/client/resources/p2s_default_profiles/*.json`（中文，首次启动自动写入）。
- 主要字段：
  - `id` / `name` / `description`
  - `system_prompt`
  - `allowed_tools`
  - `max_loops`
  - `timeout_seconds`
  - `enabled`
- 说明：
  - `create_subagent` 仅使用显式 `skill_ids`。
  - `skill` 不是必须项：当未配置到任何有效 skill 时，subagent 仍可创建并以空 skill 运行。
  - 仅当显式传入了非空 `skill_ids` 且全部无效时，接口会直接返回 `error`（不会回退到其他 skill）。

## 存档

- 结构存档：`config/p2s_storage/*.json`
- project 元数据：`config/p2s_projects_v2/projects/*.json`
- workspace 文件：`config/p2s_projects_v2/workspaces/<project-id>/**/*.toml`
- skill 文档：`config/p2s_skills/skills/<skill-id>/SKILL.md`
- subagent profile：`config/p2s_skills/.agent/*.json`
- 统一结构格式：V2（`palette + structures`）
- workspace 可编辑格式：TOML（`workspace + palette + [[part]] + [[part.action]]`）
- patch 提议格式：工具参数外层 JSON（`path + patch_toml`），补丁正文为 TOML（`base_revision + [[operation]] + [[operation.*]]`）

## 构建

- 开发运行：
  - `./gradlew :1.21:runServer`
  - `./gradlew :1.21.1:runServer`
- 打包：
  - `./gradlew build`
  - `./gradlew buildAndGather`

### 镜像配置（网络不稳定时）

已在工程内启用以下镜像策略：
- Gradle 插件与依赖仓库：`settings.gradle` / `common.gradle`（Aliyun + BMCL + Fabric + Maven Central）
- Loom 版本清单镜像：`gradle.properties` 中 `loom_version_manifests`

## License

CC0-1.0
