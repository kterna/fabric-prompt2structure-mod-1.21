---
name: "圆柱近似"
description: "由每层环形点位 + 纵向线段构造圆柱。"
---

# 圆柱近似

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 半径 3 高 6 模板
```toml
[[operation]]
op = "insert_actions"
part = "cylinder_r3_h6"

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[3, 0, 0], [2, 0, 2], [0, 0, 3], [-2, 0, 2], [-3, 0, 0], [-2, 0, -2], [0, 0, -3], [2, 0, -2]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[3, 6, 0], [2, 6, 2], [0, 6, 3], [-2, 6, 2], [-3, 6, 0], [-2, 6, -2], [0, 6, -3], [2, 6, -2]]

[[operation.actions_add]]
type = "line"
block = "shell"
from = [3, 0, 0]
to = [3, 6, 0]

[[operation.actions_add]]
type = "line"
block = "shell"
from = [0, 0, 3]
to = [0, 6, 3]

[[operation.actions_add]]
type = "line"
block = "shell"
from = [-3, 0, 0]
to = [-3, 6, 0]

[[operation.actions_add]]
type = "line"
block = "shell"
from = [0, 0, -3]
to = [0, 6, -3]
```

## 迁移说明
- 对应外部项目 `drawCylinder` 的离散近似。
