---
name: "床模板"
description: "多风格床体模板（10+示例），覆盖现代、中世纪、东方、特殊类型。"
---

# 床模板
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 使用约定
- 所有模板为局部坐标，可整体平移复用。
- 床头/床尾优先 `plane`，床体优先 `box:solid`，装饰用 `points`。

## 1) 现代简约双人床
```toml
[[operation]]
op = "insert_part"
part = "bed_1"

[[operation.actions_add]]
type = "box"
block = "bed_base"
mode = "solid"
from = [0, 0, 0]
to = [2, 0, 4]

[[operation.actions_add]]
type = "plane"
block = "bed_head"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [2, 2, 0]

[[operation.actions_add]]
type = "line"
block = "bed_tail"
from = [0, 1, 4]
to = [2, 1, 4]
```

## 2) 榻榻米床
```toml
[[operation]]
op = "insert_part"
part = "bed_2"

[[operation.actions_add]]
type = "box"
block = "platform"
mode = "solid"
from = [0, 0, 0]
to = [3, 0, 4]

[[operation.actions_add]]
type = "box"
block = "mattress"
mode = "solid"
from = [0, 1, 0]
to = [2, 1, 3]

[[operation.actions_add]]
type = "points"
block = "pillow"
at = [[0, 2, 0], [1, 2, 0]]
```

## 3) 四柱大床（中世纪）
```toml
[[operation]]
op = "insert_part"
part = "bed_3"

[[operation.actions_add]]
type = "line"
block = "post"
from = [0, 0, 0]
to = [0, 4, 0]

[[operation.actions_add]]
type = "line"
block = "post"
from = [2, 0, 0]
to = [2, 4, 0]

[[operation.actions_add]]
type = "line"
block = "post"
from = [0, 0, 3]
to = [0, 4, 3]

[[operation.actions_add]]
type = "line"
block = "post"
from = [2, 0, 3]
to = [2, 4, 3]

[[operation.actions_add]]
type = "plane"
block = "canopy"
axis = "y"
mode = "solid"
from = [0, 4, 0]
to = [2, 4, 3]

[[operation.actions_add]]
type = "box"
block = "mattress"
mode = "solid"
from = [0, 1, 0]
to = [2, 1, 3]

[[operation.actions_add]]
type = "points"
block = "curtain"
at = [[0, 3, 0], [0, 3, 3], [2, 3, 0], [2, 3, 3]]
```

## 4) 稻草床（乡村）
```toml
[[operation]]
op = "insert_part"
part = "bed_4"

[[operation.actions_add]]
type = "box"
block = "hay_mattress"
mode = "solid"
from = [0, 0, 0]
to = [1, 0, 2]

[[operation.actions_add]]
type = "points"
block = "blanket"
at = [[0, 1, 1], [1, 1, 1], [0, 1, 2], [1, 1, 2]]
```

## 5) 木质单人床
```toml
[[operation]]
op = "insert_part"
part = "bed_5"

[[operation.actions_add]]
type = "box"
block = "bed_frame"
mode = "solid"
from = [0, 0, 0]
to = [1, 0, 3]

[[operation.actions_add]]
type = "box"
block = "mattress"
mode = "solid"
from = [0, 1, 0]
to = [1, 1, 3]

[[operation.actions_add]]
type = "plane"
block = "headboard"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [1, 2, 0]

[[operation.actions_add]]
type = "line"
block = "footboard"
from = [0, 1, 3]
to = [1, 1, 3]
```

## 6) 日式布团
```toml
[[operation]]
op = "insert_part"
part = "bed_6"

[[operation.actions_add]]
type = "box"
block = "futon"
mode = "solid"
from = [0, 0, 0]
to = [2, 0, 3]

[[operation.actions_add]]
type = "points"
block = "pillow"
at = [[0, 1, 0], [1, 1, 0]]

[[operation.actions_add]]
type = "box"
block = "blanket"
mode = "solid"
from = [0, 0, 1]
to = [2, 0, 3]
```

## 7) 中式架子床
```toml
[[operation]]
op = "insert_part"
part = "bed_7"

[[operation.actions_add]]
type = "box"
block = "bed_platform"
mode = "solid"
from = [0, 0, 0]
to = [3, 0, 4]

[[operation.actions_add]]
type = "plane"
block = "railing"
axis = "x"
mode = "outline"
from = [0, 1, 0]
to = [0, 3, 4]

[[operation.actions_add]]
type = "plane"
block = "railing"
axis = "x"
mode = "outline"
from = [3, 1, 0]
to = [3, 3, 4]

[[operation.actions_add]]
type = "box"
block = "mattress"
mode = "solid"
from = [1, 1, 0]
to = [2, 1, 3]

[[operation.actions_add]]
type = "plane"
block = "canopy"
axis = "y"
mode = "solid"
from = [0, 4, 0]
to = [3, 4, 4]
```

## 8) 上下铺
```toml
[[operation]]
op = "insert_part"
part = "bed_8"

[[operation.actions_add]]
type = "box"
block = "bed_frame"
mode = "solid"
from = [0, 0, 0]
to = [1, 0, 3]

[[operation.actions_add]]
type = "box"
block = "mattress"
mode = "solid"
from = [0, 1, 0]
to = [1, 1, 3]

[[operation.actions_add]]
type = "box"
block = "bed_frame"
mode = "solid"
from = [0, 3, 0]
to = [1, 3, 3]

[[operation.actions_add]]
type = "box"
block = "mattress"
mode = "solid"
from = [0, 4, 0]
to = [1, 4, 3]

[[operation.actions_add]]
type = "line"
block = "ladder"
from = [2, 0, 0]
to = [2, 4, 0]
```

## 9) 吊床
```toml
[[operation]]
op = "insert_part"
part = "bed_9"

[[operation.actions_add]]
type = "points"
block = "hook"
at = [[-2, 2, 0], [2, 2, 0]]

[[operation.actions_add]]
type = "line"
block = "hammock"
from = [-1, 1, 0]
to = [1, 1, 0]
```

## 10) 棺材床（哥特）
```toml
[[operation]]
op = "insert_part"
part = "bed_10"

[[operation.actions_add]]
type = "box"
block = "coffin_base"
mode = "solid"
from = [0, 0, 0]
to = [1, 0, 3]

[[operation.actions_add]]
type = "points"
block = "coffin_head"
at = [[0, 1, 0], [1, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "coffin_foot"
at = [[0, 1, 3], [1, 1, 3]]

[[operation.actions_add]]
type = "box"
block = "lining"
mode = "solid"
from = [0, 0, 1]
to = [1, 0, 2]
```

## 11) 沙发床
```toml
[[operation]]
op = "insert_part"
part = "bed_11"

[[operation.actions_add]]
type = "box"
block = "sofa_base"
mode = "solid"
from = [0, 0, 0]
to = [3, 0, 1]

[[operation.actions_add]]
type = "plane"
block = "sofa_back"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [3, 2, 0]

[[operation.actions_add]]
type = "box"
block = "cushion"
mode = "solid"
from = [0, 1, 0]
to = [3, 1, 1]
```

## 推荐 block key
- 床体：`bed_frame`, `bed_base`, `platform`
- 床垫：`mattress`, `futon`, `hay_mattress`
- 床头/尾：`headboard`, `footboard`, `bed_head`
- 装饰：`pillow`, `blanket`, `curtain`, `canopy`
