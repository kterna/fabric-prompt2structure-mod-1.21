---
name: "赛博朋克"
description: "赛博朋克 的体量、材料与动作偏好。"
---

# 赛博朋克
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 高密度叠层体块、外露设施、强对比灯光

## 材料倾向
- 金属、混凝土、霓虹点缀

## 动作偏好
- box:shell + points 灯带节点

## 立面模板
```toml
[[operation]]
op = "insert_part"
part = "cyberpunk_1"

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
