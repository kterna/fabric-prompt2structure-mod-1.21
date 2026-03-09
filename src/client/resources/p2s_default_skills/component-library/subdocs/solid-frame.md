---
name: "实心框架"
description: "有厚度的梁柱框架，适合工业或重型风格。"
---

# 实心框架

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 梁柱模板
```toml
[[operation]]
op = "insert_part"
part = "solid_frame"

[[operation.actions_add]]
type = "box"
block = "column"
mode = "solid"
from = [0, 0, 0]
to = [0, 5, 0]

[[operation.actions_add]]
type = "box"
block = "column"
mode = "solid"
from = [8, 0, 0]
to = [8, 5, 0]

[[operation.actions_add]]
type = "box"
block = "column"
mode = "solid"
from = [0, 0, 8]
to = [0, 5, 8]

[[operation.actions_add]]
type = "box"
block = "column"
mode = "solid"
from = [8, 0, 8]
to = [8, 5, 8]

[[operation.actions_add]]
type = "box"
block = "beam"
mode = "solid"
from = [0, 5, 0]
to = [8, 5, 0]

[[operation.actions_add]]
type = "box"
block = "beam"
mode = "solid"
from = [0, 5, 8]
to = [8, 5, 8]

[[operation.actions_add]]
type = "box"
block = "beam"
mode = "solid"
from = [0, 5, 0]
to = [0, 5, 8]

[[operation.actions_add]]
type = "box"
block = "beam"
mode = "solid"
from = [8, 5, 0]
to = [8, 5, 8]
```
