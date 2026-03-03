# CHANGELOG

## 2025-11-30 基础版本落地
- 完成 Fabric 模组初始化：工程骨架、构建/CI、主入口、命令系统、LLM 调用、结构构建与存档能力。
- 配置与 Prompt 管理体系成型：支持多 prompt、active prompt 切换、`/p2sprompt` 管理命令，以及配置持久化。
- 存储格式升级：由原始字符串内容转为结构化 JSON 存档，并兼容旧格式读取。
- 结构动作能力增强：新增 `facing` 方向处理。
- 文档与发布流程补齐：README 扩展为完整使用说明，新增基于 tag 的发布工作流。

## 2026-02-25 ~ 2026-02-26 Agent 原型阶段
- 多次 WIP 快照后，落地 agent tool loop 与结构摘要 UI，形成后续会话式工作流基础。

## 2026-02-27 会话与 Patch 稳定化
- 增加会话配置 UI 与基于物品的选区交互。
- 修复 patch 编排校验与自动应用行为。
- 加强 patch 校验与方块 ID 处理的健壮性。
- 整理并提交阶段性工作区改动。

## 2026-02-28 客户端能力全面增强
- 建立客户端 skill-agent 工作流，并完善镜像构建配置。
- 引入异步 subagent 委派与 profile 化工具控制。
- 将客户端 skills 改为全局共享。
- 默认 skills/profiles 外置化，并补充 subagent 编排指导。
- 新增显式 todo 规划与用户选项审批流（user-choice）。

## 2026-03-02 体验与可靠性强化
- 配置界面整合为单页签式 `P2SConfigScreen`。
- 支持流式输出、并行工具调用、会话持久化，并放宽循环限制策略。
- 增加 DEBUG 开关、带理由的 discard 交互、按 turn 的历史裁剪。
- 持久化空间状态，并在活跃会话中锁定选区，降低误操作风险。

## 2026-03-03 工作流深度增强与名称
- 模组名称升级为 `P2S Workspace`。
- 聊天 UI 新增可编辑 JSON 上下文面板，并支持按行区间注入上下文。
- Patch 安全性增强：由盲写操作改为严格 `old_actions` 校验。
- Subagent 支持 `continue_subagent`，可恢复失败/完成任务继续执行。
- `read_workspace_state` 增加 part 过滤和行区间读取能力。
- 补充 `.gitignore` 调整。
