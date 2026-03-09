---
name: "古典/古罗马"
description: "古典/古罗马 的体量、材料与动作偏好。"
---

# 古典/古罗马
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 严格对称、柱廊环绕、三角楣、穹顶
- 三级台基抬高、柱式系统（多立克/爱奥尼/科林斯）
- 宽高比接近黄金比例 1:1.618

## 材料倾向
- 白色石英、抛光安山岩、石砖
- 推荐方块: quartz_pillar, smooth_quartz, polished_andesite, chiseled_quartz_block, stone_brick_stairs

## 动作偏好
- box:solid 台基 + line 柱列 + plane 三角楣面 + points 装饰浮雕

## 立面模板
```toml
[[operation]]
op = "insert_part"
part = "classical_roman_1"

[[operation.actions_add]]
type = "box"
block = "smooth_quartz"
mode = "solid"
from = [0, 0, 0]
to = [14, 1, 10]

[[operation.actions_add]]
type = "line"
block = "quartz_pillar"
from = [0, 2, 0]
to = [0, 8, 0]

[[operation.actions_add]]
type = "line"
block = "quartz_pillar"
from = [4, 2, 0]
to = [4, 8, 0]

[[operation.actions_add]]
type = "line"
block = "quartz_pillar"
from = [8, 2, 0]
to = [8, 8, 0]

[[operation.actions_add]]
type = "line"
block = "quartz_pillar"
from = [12, 2, 0]
to = [12, 8, 0]

[[operation.actions_add]]
type = "plane"
block = "smooth_quartz"
axis = "z"
mode = "solid"
from = [0, 9, 0]
to = [14, 12, 0]

[[operation.actions_add]]
type = "points"
block = "chiseled_quartz_block"
at = [[7, 11, 0]]
```
