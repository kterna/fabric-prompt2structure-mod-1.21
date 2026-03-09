---
name: "日式神社"
description: "日式神社 的体量、材料与动作偏好。"
---

# 日式神社
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 中轴对称、门廊强调、台基抬高

## 材料倾向
- 朱红木构、石基、深屋顶

## 动作偏好
- box 台基 + line 构架 + points 灯位

## 立面模板
```toml
[[operation]]
op = "insert_actions"
part = "japanese_shrine_1"

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
