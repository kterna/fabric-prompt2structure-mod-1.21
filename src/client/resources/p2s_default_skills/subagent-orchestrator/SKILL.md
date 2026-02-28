---
name: "Subagent 调度指南"
description: "何时创建：任务可拆分且会阻塞主流程，或需独立循环分析时；简单一步任务不创建。并指导主 agent 如何传参与轮询结果。"
---

# Subagent 调度指南

## 使用目标
- 本 skill 用于指导主 agent 调用 subagent 管理工具：
  - `list_profiles`
  - `get_profile`
  - `create_subagent`
  - `get_subagent`
  - `list_subagents`
  - `delete_subagent`

## 何时创建 subagent
- 任务可拆分且主流程会被阻塞时，优先分派 subagent。
- 需要独立循环分析的子任务（如工作区影响分析、补丁方案设计、方块与配色方案）可单独派发。
- 简单一步可完成的问题，不要滥用 subagent。

## 主 agent 输入要求
- 在 `create_subagent` 前，主 agent 应明确给出：
  - 子任务目标（必须）
  - 约束条件（尺寸、风格、改动边界、禁止项）
  - 期望输出格式（例如步骤清单、补丁建议、方块方案）
  - 是否需要 `skill_ids`（可选，不是必填）

## 调用流程
1. 先用 `list_profiles` 选 profile，必要时用 `get_profile` 查看细节。
2. 调用 `create_subagent`：
   - `task` 必填，必须可执行、可验证。
   - `profile_id` 建议显式指定。
   - `skill_ids` 可选；如果显式传了非空列表且都无效，会直接报错。
3. 通过 `get_subagent` 轮询直到状态为 `completed` / `failed` / `cancelled` / `deleted`。
4. 需要中断时调用 `delete_subagent`。

## 结果处理
- 对 `completed`：提取结果中的可执行结论，再决定是否进入下一步。
- 对 `failed`：先汇总失败原因，再决定重试、补充约束或切换 profile。
- 对长任务：可用 `list_subagents` 做总览，再按 id 精查。

## 质量准则
- 子任务描述必须避免歧义，尽量给出边界和成功标准。
- 优先最小必要的 subagent 数量，避免重复派发相同任务。
- 不允许递归让 subagent 再创建 subagent。
