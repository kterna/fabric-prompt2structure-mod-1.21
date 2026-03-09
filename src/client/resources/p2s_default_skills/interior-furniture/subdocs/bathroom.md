---
name: "卫浴模板"
description: "多风格卫浴模板（12+示例），覆盖马桶、浴缸、淋浴、洗手台、配件等。"
---

# 卫浴模板
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 使用约定
- 洁具主体优先 `box:solid` 或 `points`，墙面/玻璃用 `plane`，管件用 `points`。

## 1) 紧凑卫浴（整体）
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_1"

[[operation.actions_add]]
type = "plane"
block = "tile_floor"
axis = "y"
mode = "solid"
from = [0, 0, 0]
to = [4, 0, 4]

[[operation.actions_add]]
type = "box"
block = "vanity"
mode = "solid"
from = [0, 0, 0]
to = [1, 1, 1]

[[operation.actions_add]]
type = "plane"
block = "mirror"
axis = "z"
mode = "solid"
from = [0, 2, 0]
to = [1, 3, 0]

[[operation.actions_add]]
type = "plane"
block = "shower_glass"
axis = "x"
mode = "outline"
from = [4, 1, 1]
to = [4, 3, 4]
```

## 2) 现代马桶
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_2"

[[operation.actions_add]]
type = "points"
block = "toilet_base"
at = [[0, 0, 0]]

[[operation.actions_add]]
type = "points"
block = "toilet_seat"
at = [[0, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "toilet_tank"
at = [[0, 1, -1]]

[[operation.actions_add]]
type = "points"
block = "flush_button"
at = [[0, 0, 1]]
```

## 3) 简约马桶
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_3"

[[operation.actions_add]]
type = "points"
block = "toilet_body"
at = [[0, 0, 0]]

[[operation.actions_add]]
type = "points"
block = "toilet_bowl"
at = [[0, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "tank"
at = [[0, 1, -1]]
```

## 4) 现代浴缸
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_4"

[[operation.actions_add]]
type = "box"
block = "tub_shell"
mode = "solid"
from = [0, 0, 0]
to = [1, 1, 3]

[[operation.actions_add]]
type = "box"
block = "tub_water"
mode = "solid"
from = [0, 0, 1]
to = [1, 0, 2]

[[operation.actions_add]]
type = "points"
block = "faucet"
at = [[1, 2, 0]]
```

## 5) 爪足浴缸（复古）
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_5"

[[operation.actions_add]]
type = "box"
block = "tub_body"
mode = "solid"
from = [0, 1, 0]
to = [1, 1, 3]

[[operation.actions_add]]
type = "points"
block = "claw_foot"
at = [[0, 0, 0], [1, 0, 0], [0, 0, 3], [1, 0, 3]]

[[operation.actions_add]]
type = "points"
block = "tub_water"
at = [[0, 1, 1], [1, 1, 1], [0, 1, 2], [1, 1, 2]]
```

## 6) 现代淋浴间
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_6"

[[operation.actions_add]]
type = "plane"
block = "shower_base"
axis = "y"
mode = "solid"
from = [0, 0, 0]
to = [1, 0, 1]

[[operation.actions_add]]
type = "plane"
block = "glass_wall"
axis = "x"
mode = "solid"
from = [0, 1, 0]
to = [0, 3, 1]

[[operation.actions_add]]
type = "plane"
block = "glass_wall"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [1, 3, 0]

[[operation.actions_add]]
type = "points"
block = "shower_head"
at = [[1, 3, 1]]

[[operation.actions_add]]
type = "line"
block = "shower_pipe"
from = [1, 1, 1]
to = [1, 3, 1]
```

## 7) 现代洗手台
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_7"

[[operation.actions_add]]
type = "line"
block = "counter_top"
from = [0, 1, 0]
to = [2, 1, 0]

[[operation.actions_add]]
type = "points"
block = "sink"
at = [[1, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "faucet"
at = [[1, 2, -1]]

[[operation.actions_add]]
type = "plane"
block = "mirror"
axis = "z"
mode = "solid"
from = [0, 2, -1]
to = [2, 4, -1]
```

## 8) 双人洗手台
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_8"

[[operation.actions_add]]
type = "line"
block = "counter_top"
from = [0, 1, 0]
to = [4, 1, 0]

[[operation.actions_add]]
type = "points"
block = "sink"
at = [[1, 1, 0], [3, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "faucet"
at = [[1, 2, -1], [3, 2, -1]]

[[operation.actions_add]]
type = "plane"
block = "mirror"
axis = "z"
mode = "solid"
from = [0, 2, -1]
to = [4, 4, -1]
```

## 9) 毛巾架 + 镜柜
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_9"

[[operation.actions_add]]
type = "line"
block = "towel_bar"
from = [0, 2, 0]
to = [2, 2, 0]

[[operation.actions_add]]
type = "points"
block = "towel"
at = [[1, 1, 0]]

[[operation.actions_add]]
type = "plane"
block = "mirror_cabinet"
axis = "z"
mode = "solid"
from = [3, 2, 0]
to = [5, 4, 0]

[[operation.actions_add]]
type = "box"
block = "cabinet_back"
mode = "solid"
from = [3, 2, -1]
to = [5, 4, -1]
```

## 10) 卫浴配件组合
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_10"

[[operation.actions_add]]
type = "points"
block = "toilet_paper"
at = [[0, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "brush_holder"
at = [[0, 0, 1]]

[[operation.actions_add]]
type = "points"
block = "soap_dish"
at = [[2, 2, 0]]
```

## 11) 按摩浴缸
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_11"

[[operation.actions_add]]
type = "box"
block = "jacuzzi_body"
mode = "solid"
from = [0, 0, 0]
to = [3, 1, 3]

[[operation.actions_add]]
type = "box"
block = "jacuzzi_water"
mode = "solid"
from = [1, 0, 1]
to = [2, 0, 2]

[[operation.actions_add]]
type = "points"
block = "jacuzzi_light"
at = [[0, 1, 0], [3, 1, 0], [0, 1, 3], [3, 1, 3]]
```

## 12) 浴帘
```toml
[[operation]]
op = "insert_actions"
part = "bathroom_12"

[[operation.actions_add]]
type = "line"
block = "curtain_rod"
from = [0, 4, 0]
to = [3, 4, 0]

[[operation.actions_add]]
type = "plane"
block = "curtain"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [3, 3, 0]
```

## 推荐 block key
- 洁具：`toilet_base`, `toilet_seat`, `tub_shell`, `sink`, `shower_head`
- 台面/柜：`counter_top`, `vanity`, `mirror`, `mirror_cabinet`
- 玻璃：`shower_glass`, `glass_wall`
- 配件：`faucet`, `towel_bar`, `towel`, `toilet_paper`, `curtain`
