---
name: "中世纪城堡"
description: "城堡防御语汇、体量要素与动作模板。"
---

# 中世纪城堡
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 核心特征
- 关键词：防御、厚重、层层设防。
- 要素：主塔、城墙、塔楼、门楼、垛口。
- 体量：主塔高于外墙，塔楼高于墙线。

## 材料与配色
- 主材：`stone_bricks`, `cobblestone`, `mossy_stone_bricks`
- 木构：`spruce_planks`, `dark_oak_planks`
- 金属：`iron_bars`, `chain`
- 地面：`gravel`, `cobblestone`

## 防御构成建议
- 城墙高度 `8-12`，厚度 `2`。
- 塔楼间距 `12-20`。
- 城门保持单主入口，减少薄弱点。

## 动作偏好
- `box:walls` 快速起城墙。
- `box:solid` 构建塔楼和门楼。
- `line` 做垛口、梁架和防御细线。

## 城墙+塔楼模板
```toml
[[operation]]
op = "insert_actions"
part = "medieval_castle_1"

[[operation.actions_add]]
type = "box"
block = "wall"
mode = "walls"
from = [0, 1, 0]
to = [24, 10, 24]

[[operation.actions_add]]
type = "box"
block = "tower"
mode = "solid"
from = [0, 1, 0]
to = [4, 14, 4]

[[operation.actions_add]]
type = "box"
block = "tower"
mode = "solid"
from = [20, 1, 0]
to = [24, 14, 4]

[[operation.actions_add]]
type = "box"
block = "tower"
mode = "solid"
from = [0, 1, 20]
to = [4, 14, 24]

[[operation.actions_add]]
type = "box"
block = "tower"
mode = "solid"
from = [20, 1, 20]
to = [24, 14, 24]

[[operation.actions_add]]
type = "line"
block = "battlement"
from = [0, 11, 0]
to = [24, 11, 0]
```

## 不建议
- 不要使用过薄墙体（1 格）做主防线。
- 不要把所有塔楼做成同高度同体量，需主次变化。
