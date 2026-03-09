---
name: "悬挂装饰"
description: "从顶部向下延伸的悬挂元素（藤蔓、锁链、吊灯），包括单点悬挂与环形悬挂。"
---

# 悬挂装饰

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 单点悬挂
从一个点向下放置若干方块，模拟藤蔓、锁链等。

```toml
[[operation]]
op = "insert_part"
part = "hanging_single"

[[operation.actions_add]]
type = "line"
block = "chain"
from = [5, 10, 5]
to = [5, 6, 5]

[[operation.actions_add]]
type = "points"
block = "lantern"
at = [[5, 5, 5]]
```

## 多条悬挂（垂柳效果）
在同一高度水平扩散多条下垂线段，长度可不等。

```toml
[[operation]]
op = "insert_part"
part = "hanging_weeping"

[[operation.actions_add]]
type = "line"
block = "oak_leaves"
from = [4, 12, 5]
to = [4, 7, 5]

[[operation.actions_add]]
type = "line"
block = "oak_leaves"
from = [5, 12, 4]
to = [5, 8, 4]

[[operation.actions_add]]
type = "line"
block = "oak_leaves"
from = [6, 12, 5]
to = [6, 6, 5]

[[operation.actions_add]]
type = "line"
block = "oak_leaves"
from = [5, 12, 6]
to = [5, 9, 6]
```

## 环形悬挂
在圆环路径上均匀分布悬挂线段，适合吊灯环或垂柳树冠。

```toml
[[operation]]
op = "insert_part"
part = "hanging_ring"

[[operation.actions_add]]
type = "line"
block = "chain"
from = [6, 10, 0]
to = [6, 7, 0]

[[operation.actions_add]]
type = "line"
block = "chain"
from = [0, 10, 6]
to = [0, 7, 6]

[[operation.actions_add]]
type = "line"
block = "chain"
from = [-6, 10, 0]
to = [-6, 8, 0]

[[operation.actions_add]]
type = "line"
block = "chain"
from = [0, 10, -6]
to = [0, 7, -6]

[[operation.actions_add]]
type = "points"
block = "lantern"
at = [[6, 6, 0], [0, 6, 6], [-6, 7, 0], [0, 6, -6]]
```

## 迁移说明
- 对应外部项目 `drawHanging`（单点悬挂）和 `drawHangingRing`（环形悬挂）。
- 原始版本有随机长度变化和摆动参数，这里用预计算的离散点位替代。
