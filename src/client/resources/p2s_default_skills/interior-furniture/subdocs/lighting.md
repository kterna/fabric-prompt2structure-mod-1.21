---
name: "照明模板"
description: "多风格照明模板（13+示例），覆盖吊灯、壁灯、台灯、落地灯、特殊灯具。"
---

# 照明模板
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 使用约定
- 灯体优先 `points`，灯柱/吊链用 `line`，灯罩用 `plane` 或 `box`。

## 1) 2x2 主灯阵列
```toml
[[operation]]
op = "insert_actions"
part = "lighting_1"

[[operation.actions_add]]
type = "points"
block = "ceiling_light"
at = [[2, 4, 2], [6, 4, 2], [2, 4, 6], [6, 4, 6]]

[[operation.actions_add]]
type = "points"
block = "wall_light"
at = [[0, 3, 4], [8, 3, 4]]
```

## 2) 现代吊灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_2"

[[operation.actions_add]]
type = "line"
block = "chain"
from = [0, 5, 0]
to = [0, 4, 0]

[[operation.actions_add]]
type = "points"
block = "lamp_body"
at = [[0, 3, 0]]

[[operation.actions_add]]
type = "points"
block = "lamp_shade"
at = [[-1, 3, 0], [1, 3, 0], [0, 3, -1], [0, 3, 1]]
```

## 3) 水晶吊灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_3"

[[operation.actions_add]]
type = "line"
block = "chain"
from = [0, 5, 0]
to = [0, 4, 0]

[[operation.actions_add]]
type = "points"
block = "light_core"
at = [[0, 4, 0]]

[[operation.actions_add]]
type = "points"
block = "crystal_arm"
at = [[-1, 4, 0], [1, 4, 0], [0, 4, -1], [0, 4, 1]]

[[operation.actions_add]]
type = "points"
block = "crystal_drop"
at = [[-1, 3, 0], [1, 3, 0], [0, 3, -1], [0, 3, 1]]
```

## 4) 中世纪枝形吊灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_4"

[[operation.actions_add]]
type = "line"
block = "chain"
from = [0, 5, 0]
to = [0, 4, 0]

[[operation.actions_add]]
type = "points"
block = "chandelier_center"
at = [[0, 4, 0]]

[[operation.actions_add]]
type = "points"
block = "chandelier_arm"
at = [[-1, 4, 0], [1, 4, 0], [0, 4, -1], [0, 4, 1]]

[[operation.actions_add]]
type = "points"
block = "candle"
at = [[-1, 5, 0], [1, 5, 0], [0, 5, -1], [0, 5, 1]]
```

## 5) 日式纸灯笼
```toml
[[operation]]
op = "insert_actions"
part = "lighting_5"

[[operation.actions_add]]
type = "line"
block = "chain"
from = [0, 4, 0]
to = [0, 3, 0]

[[operation.actions_add]]
type = "line"
block = "lantern_body"
from = [0, 1, 0]
to = [0, 3, 0]

[[operation.actions_add]]
type = "points"
block = "light_core"
at = [[0, 2, 0]]

[[operation.actions_add]]
type = "points"
block = "lantern_bottom"
at = [[0, 0, 0]]
```

## 6) 火把壁灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_6"

[[operation.actions_add]]
type = "points"
block = "bracket"
at = [[0, 2, 1]]

[[operation.actions_add]]
type = "points"
block = "torch"
at = [[0, 2, 0]]
```

## 7) 现代壁灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_7"

[[operation.actions_add]]
type = "points"
block = "wall_mount"
at = [[0, 2, 1]]

[[operation.actions_add]]
type = "points"
block = "lamp_body"
at = [[0, 2, 0]]

[[operation.actions_add]]
type = "points"
block = "lamp_shade"
at = [[0, 2, -1]]
```

## 8) 哥特壁灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_8"

[[operation.actions_add]]
type = "points"
block = "wall_chain"
at = [[0, 2, 1]]

[[operation.actions_add]]
type = "points"
block = "soul_lamp"
at = [[0, 1, 0]]
```

## 9) 现代台灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_9"

[[operation.actions_add]]
type = "points"
block = "lamp_base"
at = [[0, 0, 0]]

[[operation.actions_add]]
type = "points"
block = "lamp_shade"
at = [[0, 1, 0]]
```

## 10) 蜡烛台
```toml
[[operation]]
op = "insert_actions"
part = "lighting_10"

[[operation.actions_add]]
type = "points"
block = "candle_base"
at = [[0, 0, 0]]

[[operation.actions_add]]
type = "points"
block = "candle"
at = [[-1, 1, 0], [0, 1, 0], [1, 1, 0]]
```

## 11) 现代落地灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_11"

[[operation.actions_add]]
type = "points"
block = "floor_base"
at = [[0, 0, 0]]

[[operation.actions_add]]
type = "line"
block = "lamp_pole"
from = [0, 1, 0]
to = [0, 3, 0]

[[operation.actions_add]]
type = "points"
block = "lamp_shade"
at = [[0, 4, 0]]
```

## 12) 路灯
```toml
[[operation]]
op = "insert_actions"
part = "lighting_12"

[[operation.actions_add]]
type = "line"
block = "lamp_post"
from = [0, 0, 0]
to = [0, 3, 0]

[[operation.actions_add]]
type = "points"
block = "lamp_top"
at = [[0, 4, 0]]

[[operation.actions_add]]
type = "points"
block = "lamp_light"
at = [[0, 3, 1]]
```

## 13) 日式石灯笼
```toml
[[operation]]
op = "insert_actions"
part = "lighting_13"

[[operation.actions_add]]
type = "points"
block = "stone_base"
at = [[0, 0, 0]]

[[operation.actions_add]]
type = "line"
block = "stone_pillar"
from = [0, 1, 0]
to = [0, 2, 0]

[[operation.actions_add]]
type = "points"
block = "lantern_chamber"
at = [[0, 2, 0]]

[[operation.actions_add]]
type = "plane"
block = "stone_cap"
axis = "y"
mode = "solid"
from = [-1, 3, -1]
to = [1, 3, 1]
```

## 光源方块参考
| 方块 | 亮度 | 风格 |
|------|------|------|
| glowstone | 15 | 魔法/现代 |
| sea_lantern | 15 | 海洋/现代 |
| lantern | 15 | 中世纪/乡村 |
| soul_lantern | 10 | 哥特/阴森 |
| candle | 3-12 | 浪漫/古典 |
| campfire | 15 | 乡村/野外 |
| end_rod | 14 | 奇幻/现代 |
| shroomlight | 15 | 奇幻/自然 |
| froglight | 15 | 自然/温馨 |

## 推荐 block key
- 灯体：`lamp_body`, `light_core`, `torch`, `soul_lamp`, `candle`
- 灯罩/结构：`lamp_shade`, `lantern_body`, `lamp_post`, `chain`
- 底座/支架：`lamp_base`, `floor_base`, `stone_base`, `bracket`, `wall_mount`
