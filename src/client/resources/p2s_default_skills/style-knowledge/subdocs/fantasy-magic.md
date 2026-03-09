---
name: "魔法/巫师建筑"
description: "魔法/巫师建筑 的体量、材料与动作偏好。"
---

# 魔法/巫师建筑
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 体量特征
- 高细塔楼、螺旋上升、反重力漂浮元素
- 发光符文、水晶簇、扭曲几何
- 塔楼分层：底层入口 -> 图书馆 -> 实验室 -> 顶层天文台

## 材料倾向
- 紫珀块、末地石砖、紫水晶、黑曜石
- 推荐方块: purpur_block, end_stone_bricks, amethyst_block, amethyst_cluster, sea_lantern, end_rod, obsidian, crying_obsidian, bookshelf, sculk

## 动作偏好
- box:walls 塔身 + line 螺旋结构 + points 发光符文与水晶节点

## 立面模板
```toml
[[operation]]
op = "insert_part"
part = "fantasy_magic_1"

[[operation.actions_add]]
type = "box"
block = "end_stone_bricks"
mode = "walls"
from = [3, 0, 3]
to = [9, 12, 9]

[[operation.actions_add]]
type = "box"
block = "purpur_block"
mode = "walls"
from = [4, 12, 4]
to = [8, 18, 8]

[[operation.actions_add]]
type = "line"
block = "end_rod"
from = [6, 18, 6]
to = [6, 22, 6]

[[operation.actions_add]]
type = "points"
block = "amethyst_cluster"
at = [[3, 6, 3], [9, 6, 9], [3, 10, 9], [9, 10, 3]]

[[operation.actions_add]]
type = "points"
block = "sea_lantern"
at = [[6, 5, 3], [6, 5, 9], [3, 5, 6], [9, 5, 6]]
```
