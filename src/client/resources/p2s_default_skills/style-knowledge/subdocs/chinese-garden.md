---
name: "中式园林"
description: "中式园林 的体量、材料与动作偏好。"
---

# 中式园林
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 曲折路径、院墙分景、轻体量亭廊

## 材料倾向
- 白墙灰瓦、木构、石景

## 动作偏好
- plane 墙面 + line 廊架 + points 景石

## 立面模板
```toml
[[operation]]
op = "insert_part"
part = "chinese_garden_1"

[[operation.actions_add]]
type = "box"
block = "main"
mode = "walls"
from = [0, 1, 0]
to = [12, 6, 10]

[[operation.actions_add]]
type = "plane"
block = "facade"
axis = "z"
mode = "outline"
from = [0, 1, 0]
to = [12, 6, 0]

[[operation.actions_add]]
type = "points"
block = "accent"
at = [[2, 4, 0], [6, 4, 0], [10, 4, 0]]
```
