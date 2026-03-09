---
name: "金字塔"
description: "按层收进 box/plane 构造金字塔。"
---

# 金字塔

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 方形基底模板
```toml
[[operation]]
op = "insert_part"
part = "pyramid_square"

[[operation.actions_add]]
type = "plane"
block = "stone"
axis = "y"
mode = "solid"
from = [0, 0, 0]
to = [8, 0, 8]

[[operation.actions_add]]
type = "plane"
block = "stone"
axis = "y"
mode = "solid"
from = [1, 1, 1]
to = [7, 1, 7]

[[operation.actions_add]]
type = "plane"
block = "stone"
axis = "y"
mode = "solid"
from = [2, 2, 2]
to = [6, 2, 6]

[[operation.actions_add]]
type = "plane"
block = "stone"
axis = "y"
mode = "solid"
from = [3, 3, 3]
to = [5, 3, 5]

[[operation.actions_add]]
type = "points"
block = "stone"
at = [[4, 4, 4]]
```

## 迁移说明
- 对应外部项目 `drawPyramid`。
