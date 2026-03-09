---
name: "沙漠埃及"
description: "沙漠埃及 的体量、材料与动作偏好。"
---

# 沙漠埃及
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 厚实体、阶梯体量、平顶或缓坡顶

## 材料倾向
- 砂岩、石灰岩、金色点缀

## 动作偏好
- plane 大面 + box 退台

## 立面模板
```toml
[[operation]]
op = "insert_actions"
part = "desert_egyptian_1"

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
