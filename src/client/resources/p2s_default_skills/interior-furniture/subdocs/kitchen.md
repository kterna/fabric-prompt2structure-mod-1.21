---
name: "厨房模板"
description: "多风格厨房家具模板（12+示例），覆盖灶台、水槽、冰箱、橱柜、电器等。"
---

# 厨房模板
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 使用约定
- 台面优先 `plane(axis=y)` 或 `line`，柜体优先 `box:solid`，电器/配件用 `points`。

## 1) 直线型厨房（整体）
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_1"

[[operation.actions_add]]
type = "box"
block = "counter"
mode = "solid"
from = [0, 0, 0]
to = [5, 0, 1]

[[operation.actions_add]]
type = "plane"
block = "backsplash"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [5, 2, 0]

[[operation.actions_add]]
type = "box"
block = "upper_cabinet"
mode = "solid"
from = [0, 3, 0]
to = [5, 4, 1]

[[operation.actions_add]]
type = "points"
block = "sink_or_stove"
at = [[1, 1, 1], [4, 1, 1]]
```

## 2) 现代燃气灶
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_2"

[[operation.actions_add]]
type = "box"
block = "stove_top"
mode = "solid"
from = [0, 1, 0]
to = [1, 1, 1]

[[operation.actions_add]]
type = "points"
block = "burner"
at = [[0, 1, 0], [1, 1, 0]]

[[operation.actions_add]]
type = "box"
block = "oven"
mode = "solid"
from = [0, 0, 0]
to = [1, 0, 1]

[[operation.actions_add]]
type = "points"
block = "oven_door"
at = [[0, 0, 1], [1, 0, 1]]
```

## 3) 中世纪灶台
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_3"

[[operation.actions_add]]
type = "box"
block = "hearth"
mode = "solid"
from = [0, 0, 0]
to = [2, 1, 1]

[[operation.actions_add]]
type = "points"
block = "fire"
at = [[1, 2, 0]]

[[operation.actions_add]]
type = "points"
block = "pot"
at = [[1, 2, 1]]
```

## 4) 现代水槽
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_4"

[[operation.actions_add]]
type = "line"
block = "counter_top"
from = [-1, 1, 0]
to = [1, 1, 0]

[[operation.actions_add]]
type = "points"
block = "sink_basin"
at = [[0, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "faucet"
at = [[0, 2, -1]]
```

## 5) 双槽水槽
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_5"

[[operation.actions_add]]
type = "line"
block = "counter_top"
from = [-1, 1, 0]
to = [2, 1, 0]

[[operation.actions_add]]
type = "points"
block = "sink_basin"
at = [[0, 1, 0], [1, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "faucet"
at = [[0, 2, -1]]
```

## 6) 现代冰箱
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_6"

[[operation.actions_add]]
type = "box"
block = "fridge_body"
mode = "solid"
from = [0, 0, 0]
to = [0, 3, 1]

[[operation.actions_add]]
type = "plane"
block = "fridge_door"
axis = "z"
mode = "solid"
from = [0, 0, 1]
to = [0, 3, 1]

[[operation.actions_add]]
type = "points"
block = "fridge_handle"
at = [[0, 2, 1]]
```

## 7) 复古冰箱
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_7"

[[operation.actions_add]]
type = "box"
block = "retro_fridge"
mode = "solid"
from = [0, 0, 0]
to = [0, 3, 1]

[[operation.actions_add]]
type = "points"
block = "fridge_door"
at = [[0, 1, 1], [0, 2, 1]]

[[operation.actions_add]]
type = "points"
block = "fridge_top"
at = [[0, 3, 0]]
```

## 8) 岛台
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_8"

[[operation.actions_add]]
type = "box"
block = "island_body"
mode = "solid"
from = [0, 0, 0]
to = [3, 1, 1]

[[operation.actions_add]]
type = "plane"
block = "island_top"
axis = "y"
mode = "solid"
from = [0, 2, 0]
to = [3, 2, 1]

[[operation.actions_add]]
type = "points"
block = "bar_stool"
at = [[0, 0, 2], [3, 0, 2]]
```

## 9) 上柜 + 下柜（带抽屉）
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_9"

[[operation.actions_add]]
type = "box"
block = "lower_cabinet"
mode = "solid"
from = [0, 0, 0]
to = [3, 1, 1]

[[operation.actions_add]]
type = "points"
block = "drawer"
at = [[0, 0, 1], [1, 0, 1], [2, 0, 1], [3, 0, 1]]

[[operation.actions_add]]
type = "plane"
block = "counter_top"
axis = "y"
mode = "solid"
from = [0, 2, 0]
to = [3, 2, 1]

[[operation.actions_add]]
type = "box"
block = "upper_cabinet"
mode = "solid"
from = [0, 4, 0]
to = [3, 5, 0]

[[operation.actions_add]]
type = "points"
block = "cabinet_door"
at = [[0, 4, 1], [1, 4, 1], [2, 4, 1], [3, 4, 1]]
```

## 10) 锅具挂架
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_10"

[[operation.actions_add]]
type = "line"
block = "hanging_bar"
from = [0, 3, 0]
to = [3, 3, 0]

[[operation.actions_add]]
type = "points"
block = "hanging_pot"
at = [[0, 2, 0], [2, 2, 0]]

[[operation.actions_add]]
type = "points"
block = "hanging_pan"
at = [[1, 2, 0], [3, 2, 0]]
```

## 11) 调料架
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_11"

[[operation.actions_add]]
type = "line"
block = "shelf"
from = [0, 2, 0]
to = [3, 2, 0]

[[operation.actions_add]]
type = "points"
block = "spice_jar"
at = [[0, 3, 0], [1, 3, 0], [2, 3, 0], [3, 3, 0]]
```

## 12) 厨房电器（微波炉 + 咖啡机）
```toml
[[operation]]
op = "insert_actions"
part = "kitchen_12"

[[operation.actions_add]]
type = "points"
block = "microwave"
at = [[0, 2, 0]]

[[operation.actions_add]]
type = "points"
block = "coffee_maker"
at = [[2, 1, 0]]
```

## 推荐 block key
- 台面：`counter_top`, `island_top`
- 柜体：`counter`, `lower_cabinet`, `upper_cabinet`, `island_body`
- 设备：`sink_basin`, `faucet`, `stove_top`, `burner`, `oven`, `fridge_body`
- 配件：`drawer`, `cabinet_door`, `hanging_pot`, `spice_jar`, `microwave`
