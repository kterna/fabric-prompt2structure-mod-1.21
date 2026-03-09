# 仓库规范

## 项目结构与模块组织
这个仓库是一个支持多版本的 Fabric 模组，当前目标版本由 `settings.json` 与 `versions/1.21`、`versions/1.21.1` 中各自的 `gradle.properties` 定义。

- `src/main/java/com/p2s`：共享服务端 / 运行时代码，例如网络桥接、项目/工作区持久化、会话编排、Patch 校验与 LLM schema。
- `src/client/java/com/p2s`：共享客户端代码，包含聊天 UI、选区、客户端 agent、工具桥接、屏幕组件与本地持久化。
- `src/main/resources`：模组元数据、服务端 mixin、语言文件与 `assets/prompt2structure` 资源。
- `src/client/resources`：客户端 mixin、默认 skills、默认 subagent profiles。
- `src/client/resources/p2s_default_skills/<skill-id>/`：默认 `SKILL.md` 与可选 `subdocs/`。
- `src/client/resources/p2s_default_profiles/*.json`：默认 subagent profile 模板。
- `common.gradle`：共享 Loom / sourceSets / dependency 配置。
- `.github/workflows/`：CI 与 tag 发布工作流。
- `build/` 和 `versions/*/build/`：构建产物，不要手动修改。

当前主要用户入口是：**`O` 键聊天面板 + 选区物品 + 项目/工作区 UI**。

## 构建、测试与开发命令
- `./gradlew build`：构建所有已配置的 Minecraft 版本，并将发布产物收集到 `build/libs`。
- `./gradlew buildAndGather`：根构建中使用的显式产物收集任务。
- `./gradlew :1.21:runServer`：启动 1.21 开发服务器。
- `./gradlew :1.21.1:runServer`：启动 1.21.1 开发服务器。

GitHub Actions 中的 `build.yml` 会在 Java 21 环境下执行 `./gradlew build`；`release-on-tag.yml` 会在 tag 推送时上传 `build/libs/*.jar`。

## 代码风格与命名约定
使用 Java 21，并将包名保持在 `com.p2s` 下。类名使用 `PascalCase`，方法和字段使用 `camelCase`，资源文件使用类似 `en_us.json` 的全小写命名。

Gradle 当前没有强制格式化器。较新的 Java 文件大多使用 4 空格缩进，但部分旧文件仍保留制表符；修改代码时请遵循所在文件的本地风格，不要顺手重排无关内容。

## 测试规范
目前仓库中没有专门的 `src/test` 测试套件。请将 `./gradlew build` 视为最低验证门槛，然后在对应版本的开发服务器中手动验证受影响的游戏内流程或 UI 流程，尤其是：

- 项目创建 / 打开 / 重命名
- 工作区浏览、创建、保存、重命名、删除
- 聊天面板、补丁预览、Apply / Discard
- 会话恢复、检查点、Undo / Redo
- 技能 / subagent / choice / plan / compaction 相关 UI

如果一次编译或构建尝试失败，应立即停止继续重复尝试，并直接告知用户失败原因；不要在未获得用户明确要求的情况下自行连续重试。

## 常用提权命令集合
在 Codex CLI / 受限沙箱环境里，以下命令经常因为写入 Gradle 缓存、`.git/` 索引锁或只读目录而需要提权。优先申请**窄前缀**，不要申请过宽的规则。

- 编译 / 构建：`./gradlew build`、`./gradlew :1.21:compileClientJava`、`./gradlew :1.21.1:compileClientJava`
- 运行调试：`./gradlew :1.21:runServer`、`./gradlew :1.21.1:runServer`
- Git 写操作：`git add -A`、`git commit -m "..."`；只有用户明确要求发布时再执行 `git push`
- 常见窄前缀：`["./gradlew","build"]`、`["./gradlew",":1.21:runServer"]`、`["./gradlew",":1.21.1:runServer"]`、`["git","commit"]`、`["git","push"]`
- 注意事项：`.git/` 与仓库内 `.codex/` 往往默认受保护；需要修改 skill / agent 元数据时，预期会再次触发提权
- 禁止事项：不要为破坏性命令申请宽前缀；不要把 heredoc / 整段脚本本身当成 `prefix_rule`

## 架构与文档参考
仓库结构、源码定位、文档归属和核对流程，优先查看仓库内 skill：`./.codex/skills/p2s-doc-maintainer/SKILL.md`。

需要细查时：
- 仓库结构 / source-of-truth 文件：`./.codex/skills/p2s-doc-maintainer/references/repo-map.md`
- README / AGENTS / CHANGELOG 的归属边界：`./.codex/skills/p2s-doc-maintainer/references/doc-ownership.md`

## 文档维护要求
文档更新不能只看旧 README，必须优先以当前源码与提交历史为准。核对这类信息时，至少检查：

- `src/main/java/com/p2s` 与 `src/client/java/com/p2s`
- `settings.json`、`common.gradle`、`build.gradle`、`gradle.properties`
- `.github/workflows/build.yml` 与 `.github/workflows/release-on-tag.yml`
- 最近的 `git log --date=short --oneline`

如果用户可感知的交互入口、工具名、配置项或存储格式发生变化，通常需要同步检查：

- `README.md`
- `CHANGELOG.md`
- `AGENTS.md`

如果改动涉及模组名称、描述、链接或发布元数据，也应顺手确认 `src/main/resources/fabric.mod.json` 是否仍然准确。

## Commit 与 Pull Request 规范
近期提交历史偏好简短、祈使句或 `type: summary` 风格标题，例如：

- `Clean TOML workspace editing pipeline`
- `feat: move agent flow to client bridge`
- `refactor: align plan tracking with update_plan`

首行要简洁，并聚焦于行为变化。

每次提交前，必须检查 `README.md` 和 `CHANGELOG.md` 是否需要随本次改动同步更新；如果改动直接影响协作规范，也要同步检查 `AGENTS.md`。

Pull Request 应包含：
- 一段简短的、用户可感知改动摘要；
- 受影响的 Minecraft 版本模块；
- 你执行过的验证命令；
- UI/界面改动对应的截图或 GIF；
- 相关 issue 链接，或后续待处理说明（如果有）。

## 配置与密钥
运行时配置位于 `config/p2s_client.json`。项目 / 工作区 / 会话 / skills / profiles 还会写入 `config/p2s_projects_v2/`、`config/p2s_sessions_v2/`、`config/p2s_skills/` 等目录。

不要提交 API Key、本地配置，或自动生成的世界/项目数据。
