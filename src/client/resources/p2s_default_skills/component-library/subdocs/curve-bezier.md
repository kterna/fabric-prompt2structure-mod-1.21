---
name: "曲线离散"
description: "将曲线采样为折线点，再使用 line/points 绘制。"
---

# 曲线离散

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 采样折线示例
```toml
[[operation]]
op = "insert_part"
part = "curve_bezier"

[[operation.actions_add]]
type = "line"
block = "curve"
from = [0, 0, 0]
to = [2, 1, 1]

[[operation.actions_add]]
type = "line"
block = "curve"
from = [2, 1, 1]
to = [4, 3, 2]

[[operation.actions_add]]
type = "line"
block = "curve"
from = [4, 3, 2]
to = [6, 4, 4]

[[operation.actions_add]]
type = "line"
block = "curve"
from = [6, 4, 4]
to = [8, 4, 7]

[[operation.actions_add]]
type = "points"
block = "curve_ctrl"
at = [[0, 0, 0], [3, 4, 1], [6, 5, 5], [8, 4, 7]]
```

## 迁移说明
- 对应外部项目 `drawBezier` 的离散化实现。
