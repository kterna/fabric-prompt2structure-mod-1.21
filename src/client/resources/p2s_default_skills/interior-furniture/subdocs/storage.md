---
name: "收纳模板"
description: "多风格收纳家具模板（10+示例），覆盖衣柜、书架、箱子、展示柜等。"
---

# 收纳模板
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 使用约定
- 柜体优先 `box:solid`，柜门/隔板优先 `plane`，把手/装饰用 `points`。

## 1) 现代衣柜（双门）
```toml
[[operation]]
op = "insert_part"
part = "storage_1"

[[operation.actions_add]]
type = "box"
block = "cabinet_body"
mode = "solid"
from = [0, 0, 0]
to = [2, 4, 1]

[[operation.actions_add]]
type = "plane"
block = "cabinet_door"
axis = "z"
mode = "solid"
from = [0, 1, 1]
to = [0, 3, 1]

[[operation.actions_add]]
type = "plane"
block = "cabinet_door"
axis = "z"
mode = "solid"
from = [2, 1, 1]
to = [2, 3, 1]

[[operation.actions_add]]
type = "points"
block = "handle"
at = [[0, 2, 1], [2, 2, 1]]
```

## 2) 中世纪木衣柜
```toml
[[operation]]
op = "insert_part"
part = "storage_2"

[[operation.actions_add]]
type = "box"
block = "wood_body"
mode = "solid"
from = [0, 0, 0]
to = [2, 4, 1]

[[operation.actions_add]]
type = "plane"
block = "wood_door"
axis = "z"
mode = "solid"
from = [0, 1, 1]
to = [1, 3, 1]

[[operation.actions_add]]
type = "points"
block = "wood_top"
at = [[0, 4, 0], [1, 4, 0], [2, 4, 0]]

[[operation.actions_add]]
type = "points"
block = "handle"
at = [[0, 2, 1], [1, 2, 1]]
```

## 3) 满墙书架
```toml
[[operation]]
op = "insert_part"
part = "storage_3"

[[operation.actions_add]]
type = "box"
block = "bookshelf"
mode = "solid"
from = [0, 0, 0]
to = [5, 4, 0]

[[operation.actions_add]]
type = "points"
block = "light_decor"
at = [[2, 2, 0]]

[[operation.actions_add]]
type = "points"
block = "plant_decor"
at = [[4, 3, 0]]
```

## 4) 现代开放书架
```toml
[[operation]]
op = "insert_part"
part = "storage_4"

[[operation.actions_add]]
type = "plane"
block = "shelf_side"
axis = "x"
mode = "solid"
from = [0, 0, 0]
to = [0, 4, 0]

[[operation.actions_add]]
type = "plane"
block = "shelf_side"
axis = "x"
mode = "solid"
from = [4, 0, 0]
to = [4, 4, 0]

[[operation.actions_add]]
type = "line"
block = "shelf_board"
from = [0, 0, 0]
to = [4, 0, 0]

[[operation.actions_add]]
type = "line"
block = "shelf_board"
from = [0, 2, 0]
to = [4, 2, 0]

[[operation.actions_add]]
type = "line"
block = "shelf_board"
from = [0, 4, 0]
to = [4, 4, 0]

[[operation.actions_add]]
type = "points"
block = "book_item"
at = [[1, 1, 0], [2, 3, 0]]
```

## 5) 宝箱堆
```toml
[[operation]]
op = "insert_part"
part = "storage_5"

[[operation.actions_add]]
type = "points"
block = "chest_large"
at = [[0, 0, 0], [1, 0, 0]]

[[operation.actions_add]]
type = "points"
block = "barrel"
at = [[0, 1, 0]]

[[operation.actions_add]]
type = "points"
block = "chest_trap"
at = [[2, 0, 0]]
```

## 6) 储物架（三层）
```toml
[[operation]]
op = "insert_part"
part = "storage_6"

[[operation.actions_add]]
type = "line"
block = "shelf_board"
from = [0, 0, 0]
to = [3, 0, 0]

[[operation.actions_add]]
type = "line"
block = "shelf_board"
from = [0, 2, 0]
to = [3, 2, 0]

[[operation.actions_add]]
type = "line"
block = "shelf_board"
from = [0, 4, 0]
to = [3, 4, 0]

[[operation.actions_add]]
type = "line"
block = "shelf_support"
from = [0, 0, -1]
to = [0, 4, -1]

[[operation.actions_add]]
type = "line"
block = "shelf_support"
from = [3, 0, -1]
to = [3, 4, -1]

[[operation.actions_add]]
type = "points"
block = "stored_item"
at = [[1, 1, 0], [2, 3, 0]]
```

## 7) 厨房橱柜组合（上下柜 + 台面）
```toml
[[operation]]
op = "insert_part"
part = "storage_7"

[[operation.actions_add]]
type = "box"
block = "lower_cabinet"
mode = "solid"
from = [0, 0, 0]
to = [4, 1, 1]

[[operation.actions_add]]
type = "plane"
block = "countertop"
axis = "y"
mode = "solid"
from = [0, 2, 0]
to = [4, 2, 1]

[[operation.actions_add]]
type = "box"
block = "upper_cabinet"
mode = "solid"
from = [0, 4, 0]
to = [4, 5, 0]

[[operation.actions_add]]
type = "points"
block = "drawer_face"
at = [[0, 0, 1], [1, 0, 1], [2, 0, 1], [3, 0, 1], [4, 0, 1]]
```

## 8) 玻璃展示柜
```toml
[[operation]]
op = "insert_part"
part = "storage_8"

[[operation.actions_add]]
type = "box"
block = "display_base"
mode = "solid"
from = [0, 0, 0]
to = [2, 0, 2]

[[operation.actions_add]]
type = "plane"
block = "glass_panel"
axis = "x"
mode = "solid"
from = [0, 1, 0]
to = [0, 3, 2]

[[operation.actions_add]]
type = "plane"
block = "glass_panel"
axis = "x"
mode = "solid"
from = [2, 1, 0]
to = [2, 3, 2]

[[operation.actions_add]]
type = "plane"
block = "glass_panel"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [2, 3, 0]

[[operation.actions_add]]
type = "plane"
block = "glass_panel"
axis = "z"
mode = "solid"
from = [0, 1, 2]
to = [2, 3, 2]

[[operation.actions_add]]
type = "plane"
block = "display_top"
axis = "y"
mode = "solid"
from = [0, 3, 0]
to = [2, 3, 2]

[[operation.actions_add]]
type = "points"
block = "exhibit"
at = [[1, 1, 1]]
```

## 9) 武器架
```toml
[[operation]]
op = "insert_part"
part = "storage_9"

[[operation.actions_add]]
type = "plane"
block = "rack_back"
axis = "z"
mode = "solid"
from = [0, 1, 0]
to = [3, 3, 0]

[[operation.actions_add]]
type = "points"
block = "weapon_hook"
at = [[1, 2, 0], [2, 2, 0]]
```

## 10) 行李箱（旅行风）
```toml
[[operation]]
op = "insert_part"
part = "storage_10"

[[operation.actions_add]]
type = "box"
block = "trunk_body"
mode = "solid"
from = [0, 0, 0]
to = [2, 1, 1]

[[operation.actions_add]]
type = "points"
block = "trunk_clasp"
at = [[1, 1, 1]]

[[operation.actions_add]]
type = "points"
block = "trunk_strap"
at = [[0, 1, 0], [2, 1, 0]]
```

## 推荐 block key
- 柜体：`cabinet_body`, `wood_body`, `lower_cabinet`, `upper_cabinet`
- 门/面板：`cabinet_door`, `wood_door`, `drawer_face`, `glass_panel`
- 架板：`shelf_board`, `countertop`, `shelf_support`
- 功能件：`handle`, `chest_large`, `barrel`, `exhibit`
