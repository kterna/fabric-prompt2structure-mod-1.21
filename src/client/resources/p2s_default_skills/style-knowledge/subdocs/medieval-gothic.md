---
name: "中世纪哥特"
description: "中世纪哥特 的体量、材料与动作偏好。"
---

# 中世纪哥特
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 高挑竖向、尖券窗、强轮廓阴影

## 材料倾向
- 深石材、彩窗、金属

## 动作偏好
- line 竖向分割 + plane:outline 窗框

## 立面模板
```toml
[[operation]]
op = "insert_actions"
part = "medieval_gothic_1"

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
