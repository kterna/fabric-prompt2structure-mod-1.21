---
name: "多边形体"
description: "多边形底面 + 竖向拉伸的模板。"
---

# 多边形体

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 六边形柱（近似）
```toml
[[operation]]
op = "insert_actions"
part = "polygon_hex_column"

[[operation.actions_add]]
type = "line"
block = "edge"
from = [3, 0, 0]
to = [1, 0, 3]

[[operation.actions_add]]
type = "line"
block = "edge"
from = [1, 0, 3]
to = [-1, 0, 3]

[[operation.actions_add]]
type = "line"
block = "edge"
from = [-1, 0, 3]
to = [-3, 0, 0]

[[operation.actions_add]]
type = "line"
block = "edge"
from = [-3, 0, 0]
to = [-1, 0, -3]

[[operation.actions_add]]
type = "line"
block = "edge"
from = [-1, 0, -3]
to = [1, 0, -3]

[[operation.actions_add]]
type = "line"
block = "edge"
from = [1, 0, -3]
to = [3, 0, 0]

[[operation.actions_add]]
type = "line"
block = "edge"
from = [3, 0, 0]
to = [3, 5, 0]

[[operation.actions_add]]
type = "line"
block = "edge"
from = [-3, 0, 0]
to = [-3, 5, 0]
```

## 迁移说明
- 对应外部项目 `drawPolygon`。
