---
name: "中式皇家"
description: "中式皇家 的体量、材料与动作偏好。"
---

# 中式皇家
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 轴线对称、台基与重檐组合

## 材料倾向
- 红柱、青瓦、石基

## 动作偏好
- plane/line 复合檐线 + box 台基

## 立面模板
```toml
[[operation]]
op = "insert_part"
part = "chinese_royal_1"

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
