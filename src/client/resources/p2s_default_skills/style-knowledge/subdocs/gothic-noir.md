---
name: "哥特暗黑"
description: "哥特暗黑 的体量、材料与动作偏好。"
---

# 哥特暗黑
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 尖顶轮廓、深阴影、强竖向序列

## 材料倾向
- 深石材、铁件、暗色玻璃

## 动作偏好
- line 尖顶骨架 + plane:outline 窗洞

## 立面模板
```toml
[[operation]]
op = "insert_part"
part = "gothic_noir_1"

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
