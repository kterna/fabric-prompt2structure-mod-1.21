---
name: "box 模式"
description: "solid/shell/walls 的典型用法。"
---

# box 模式

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## solid
```toml
[[operation]]
op = "insert_part"
part = "box_solid"

[[operation.actions_add]]
type = "box"
block = "main"
mode = "solid"
from = [0, 0, 0]
to = [6, 4, 6]
```

## shell
```toml
[[operation]]
op = "insert_part"
part = "box_shell"

[[operation.actions_add]]
type = "box"
block = "main"
mode = "shell"
from = [0, 0, 0]
to = [6, 4, 6]
```

## walls
```toml
[[operation]]
op = "insert_part"
part = "box_walls"

[[operation.actions_add]]
type = "box"
block = "wall"
mode = "walls"
from = [0, 1, 0]
to = [8, 5, 8]
```

## 适用场景
- `solid`：基础体块、平台、楼梯坯体。
- `shell`：中空外壳、箱体设备。
- `walls`：快速拉起四面墙，不带地板和天花。
