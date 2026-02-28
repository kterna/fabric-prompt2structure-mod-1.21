# Prompt to Structure (Fabric 1.21.x)

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

### 客户端 Agent + Skill（新增）

- 客户端 agent： [ClientAgentManager](src/client/java/com/p2s/ClientAgentManager.java)
  - 客户端聊天面板发送消息时，本地调用 LLM（使用 `p2s_client.json` 的模型配置）。
  - 支持本地 skill 工具：`list_skills` / `read_skill` / `search_skill`。
- 服务端工具桥接： [ClientToolBridge](src/client/java/com/p2s/ClientToolBridge.java)
  - 客户端通过 `c2s_tool_bridge` 请求服务端工具（`read_workspace_state` / `propose_patch` / `search_block_ids`）。
  - 服务端返回 `s2c_tool_bridge`。
- Skill 存储： [SkillStore](src/client/java/com/p2s/SkillStore.java)
  - 每玩家目录：`config/p2s_skills/<player-uuid>/<skill-id>/SKILL.md`
  - 活跃 skill：`config/p2s_skills/<player-uuid>/active.json`
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
3. 需要世界上下文时，通过 tool bridge 调用服务端工具：
   - `read_workspace_state`
   - `propose_patch`
   - `search_block_ids`
4. 服务端对 patch 进行试算、diff、校验并生成 preview。
5. 用户在 UI 点击 Apply/Discard；Apply 后写入 commit，可 Undo/Redo。

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

## 存档

- 结构存档：`config/p2s_storage/*.json`
- skill 文档：`config/p2s_skills/<player-uuid>/<skill-id>/SKILL.md`
- 统一结构格式：V2（`palette + structures`）

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
