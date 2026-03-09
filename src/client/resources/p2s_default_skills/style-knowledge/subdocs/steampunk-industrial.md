---
name: "蒸汽工业"
description: "蒸汽工业 的体量、材料与动作偏好。"
---

# 蒸汽工业
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 模块重复、管线外露、机械感构件

## 材料倾向
- 铜色、铁色、砖石

## 动作偏好
- line 管线 + box 设备舱

## 立面模板
```toml
[[operation]]
op = "insert_actions"
part = "steampunk_industrial_1"

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
