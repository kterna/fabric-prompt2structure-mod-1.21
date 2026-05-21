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
- 方向字段：4/5/6 向 `DirectionProperty` 可用 action `facing`；`rotation=0..15`、墙上/地上/顶上等形态应写入 palette 的完整 block state。
- 精确属性和值域：生成复杂方块状态前先调用 `describe_block_state(block_id)`，不要凭记忆编造枚举值。
- 成对方块：门只放底部 `half=lower` 锚点，床只放脚部 `part=foot` 锚点；执行器会自动补 upper/head。
- 复杂方块状态：读取 `component-library/subdocs/block-state-capabilities.md`。

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
- 按“主体 -> 细节”拆分 part，便于在 `patch_toml` 中做小步增量修改。

## Patch 输出约束
- 调用 `propose_patch` 时，工具参数使用 JSON 外壳：`path` + `patch_toml`。
- `patch_toml` 必须是 TOML，不要再输出旧的 JSON `operations` 数组。
- 顶层键使用 `base_revision`、`intent`、`message_to_user`。
- 每个补丁步骤使用 `[[operation]]`。
- 几何新增动作写入 `[[operation.actions_add]]`。
- 对现有动作做精确匹配时，使用 `[[operation.old_actions]]` / `[[operation.new_actions]]`。
- 小范围调整优先 `insert_actions` / `delete_actions` / `replace_actions`。
- 整块新增/替换/删除优先 `insert_part` / `replace_part` / `delete_part`。
- 创建全新 `part` 必须使用 `insert_part`；`insert_actions` 只用于给已存在的 `part` 追加动作。
- 纯平移使用 `move_actions` + `offset = [dx, dy, dz]`。
- palette 调整使用 `update_palette` + `[[operation.entry]]`；`new_value` 可为方块 ID 或完整 block state，例如 `minecraft:oak_wall_sign[facing=north,waterlogged=false]`。
- 复杂 block state 不要手写猜测；先调用 `describe_block_state` 看 family/tags/属性，再用 `compose_block_state` 生成并验证完整状态，尤其是玻璃板、栅栏、墙、楼梯、铁轨、门、床、sign、banner。
- 方块实体只允许 action 级安全模板；写入前调用 `describe_block_entity_template(block_id)`，不要输出原始 NBT。当前可用模板包括 `block_entity = "sign_text"` 和 `block_entity = "banner_patterns"`。

## Patch 策略
- 优先最小改动，尽量保留现有有效结构。
- 不确定时分批提补丁，降低一次性风险。
- 信息不足时先给简要理由并请求补充约束。
