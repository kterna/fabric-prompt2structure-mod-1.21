---
name: "现代生态"
description: "现代生态 的体量、材料与动作偏好。"
---

# 现代生态
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 退台体量、可种植屋面、通风开口

## 材料倾向
- 木材、石材、绿色点缀

## 动作偏好
- plane 露台 + points 绿植位

## 立面模板
```toml
[[operation]]
op = "insert_actions"
part = "modern_eco_1"

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
