---
name: "默认建筑规则"
description: "建筑总控规则：定义动作协议、补丁策略，并在细节任务上路由到尺寸/风格/内饰等专用 skill。"
---

# 默认建筑规则

## 角色定位
- 本 skill 只负责“总体构建策略 + 动作协议约束 + 任务分流”。
- 细节设计（尺寸、风格、内饰/桌椅）优先交给对应专用 skill，不在本文件展开长篇细则。

## 动作协议（硬约束）
- 仅允许 `box` / `plane` / `line` / `points`。
- 禁止旧动作 `fill` / `frame` / `set`。
- 通用必填：`type`, `block`。
- 几何字段：
- `box` / `plane` / `line` 使用 `from` + `to`。
- `points` 使用 `at`。
- 模式字段：
- `box.mode`: `solid|shell|walls`
- `plane.mode`: `solid|outline`
- `plane.axis`: `x|y|z`，并满足 `from[axis] == to[axis]`
- 方向字段：需要时用 `facing`。

## 任务分流（必须）
- 内饰、桌椅、厨房、卧室等：读取 `interior-furniture`。
- 建筑风格、材料语义、屋顶语汇：读取 `style-knowledge`。
- 占地/层高/开间/进深/尺度约束：读取 `size-planner`。
- 结构组件与几何模板（墙、框架、屋顶、曲线、几何体）：读取 `component-library`，再用 `read_subdoc` 取具体示例。
- 多类细节同时出现时，可按需组合读取多个 skill。

## 读取顺序建议
1. 先按当前任务关键词选择专用 skill。
2. 通过 `list_skills` 确认目标 skill 存在，再用 `read_skill` 读取内容。
3. 读取专用 skill 的规则与模板后，再组装 patch。
4. 若用户要求与 skill 冲突，以用户硬约束优先。

## 最小几何优先
- 外壳和大面：优先 `box` / `plane`。
- 轮廓和边框：优先 `plane:outline` 或 `line`。
- 稀疏细节点：优先 `points`。
- 按“主体 -> 细节”拆分 part，便于 `patch_actions` 小步修改。

## Patch 策略
- 优先最小改动，尽量保留现有有效结构。
- 不确定时分批提补丁，降低一次性风险。
- 信息不足时先给简要理由并请求补充约束。
