---
name: "窗组件"
description: "窗框、玻璃、窗台的可复用模板。"
---

# 窗组件

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 窗口模板（宽 3 高 3）
```toml
[[operation]]
op = "insert_part"
part = "window_module"

[[operation.actions_add]]
type = "plane"
block = "window_frame"
axis = "z"
mode = "outline"
from = [0, 1, 0]
to = [2, 3, 0]

[[operation.actions_add]]
type = "plane"
block = "window_glass"
axis = "z"
mode = "solid"
from = [1, 2, 0]
to = [1, 2, 0]

[[operation.actions_add]]
type = "line"
block = "sill"
from = [0, 1, 1]
to = [2, 1, 1]
```

## 批量放置建议
- 先定义一个局部模板，再通过平移复制到多个立面位置。
- 旋转到其它朝向时，同步修正 `facing`。
