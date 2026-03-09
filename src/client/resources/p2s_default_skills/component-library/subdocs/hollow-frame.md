---
name: "空心框架"
description: "仅由 12 条棱边组成的线框框架模板。"
---

# 空心框架

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 线框模板
```toml
[[operation]]
op = "insert_part"
part = "hollow_frame"

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 0, 0]
to = [8, 0, 0]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 0, 0]
to = [0, 0, 8]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [8, 0, 0]
to = [8, 0, 8]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 0, 8]
to = [8, 0, 8]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 5, 0]
to = [8, 5, 0]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 5, 0]
to = [0, 5, 8]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [8, 5, 0]
to = [8, 5, 8]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 5, 8]
to = [8, 5, 8]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 0, 0]
to = [0, 5, 0]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [8, 0, 0]
to = [8, 5, 0]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [0, 0, 8]
to = [0, 5, 8]

[[operation.actions_add]]
type = "line"
block = "frame"
from = [8, 0, 8]
to = [8, 5, 8]
```
