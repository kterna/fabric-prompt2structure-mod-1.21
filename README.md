# Prompt to Structure (Fabric 1.21.x)

服务端模组，通过 LLM 生成结构脚本并在世界中直接落方块。客户端无需安装即可使用服务器注册的命令。

## 关键命令（权限≥2）
- `/p2s <x> <y> <z> <prompt>`：向 LLM 发送 prompt，在指定原点生成结构，自动存档。
- `/p2sreload`：重新加载配置（含 prompts）。
- `/p2slist [limit]`：列出最近存档。
- `/p2sload <name> <x> <y> <z>`：按存档名重新生成。
- `/p2sdelete <name>`：删除存档。
- `/p2sprompt`：显示当前使用的提示词名。
- `/p2sprompt list`：列出所有提示词。
- `/p2sprompt set <name>`：切换提示词（写回配置）。

## 配置
- 路径：`config/p2s.json`（开发环境为 `run/config/p2s.json`）。
- 主要字段：
  - `apiUrl` / `apiKey` / `model` / `httpTimeoutSeconds`
  - `prompts`: 名称到提示词文本的映射，值可为单行字符串或字符串数组（数组会按行拼接）。
  - `activePrompt`: 当前使用的提示词名，可被环境变量 `P2S_PROMPT` 覆盖。
  - 支持环境变量覆盖：`P2S_API_URL` / `P2S_API_KEY` / `P2S_MODEL` / `P2S_TIMEOUT_SECONDS`.

### 提示词格式要点
- 输出必须是 JSON 对象，包含 `palette` 与 `structure`。
- `structure` 内动作支持 `fill` / `frame` / `set`，并可选 `facing` 字段（north/south/east/west/up/down）控制可朝向方块的方向。
- 示例：`"facing": "north"` 适用于楼梯、原木、墙、灯笼等有朝向属性的方块。

## 流程
1) 在配置中写好 API/模型与 `prompts`，用 `activePrompt` 或 `/p2sprompt set` 选择预设。
2) 游戏内运行 `/p2s` 下达生成请求；成功后自动存档至 `config/p2s_storage/<name>.json`。
3) 如需重复使用，`/p2sload` 指定存档名与坐标即可。

## 构建
- 开发环境：`./gradlew runServer`
- 发布：`./gradlew build`（仅包含服务端端逻辑）

## License
CC0-1.0
