---
name: "四坡屋顶"
description: "用层层收进的 outline 平面构造四坡屋顶。"
---

# 四坡屋顶

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 层收进模板
```toml
[[operation]]
op = "insert_part"
part = "roof_hip"

[[operation.actions_add]]
type = "plane"
block = "roof"
axis = "y"
mode = "outline"
from = [0, 6, 0]
to = [10, 6, 10]

[[operation.actions_add]]
type = "plane"
block = "roof"
axis = "y"
mode = "outline"
from = [1, 7, 1]
to = [9, 7, 9]

[[operation.actions_add]]
type = "plane"
block = "roof"
axis = "y"
mode = "outline"
from = [2, 8, 2]
to = [8, 8, 8]

[[operation.actions_add]]
type = "plane"
block = "roof"
axis = "y"
mode = "outline"
from = [3, 9, 3]
to = [7, 9, 7]

[[operation.actions_add]]
type = "points"
block = "roof_cap"
at = [[5, 10, 5]]
```

## 迁移说明
- 对应外部项目 `drawPolyRoof` 的规则多边形近似。
