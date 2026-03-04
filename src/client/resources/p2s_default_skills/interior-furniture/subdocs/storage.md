---
name: "收纳模板"
description: "多风格收纳家具模板（10+示例），覆盖衣柜、书架、箱子、展示柜等。"
---

# 收纳模板

## 使用约定
- 柜体优先 `box:solid`，柜门/隔板优先 `plane`，把手/装饰用 `points`。

## 1) 现代衣柜（双门）
```json
[
  {"type":"box","block":"cabinet_body","mode":"solid","from":[0,0,0],"to":[2,4,1]},
  {"type":"plane","block":"cabinet_door","axis":"z","mode":"solid","from":[0,1,1],"to":[0,3,1]},
  {"type":"plane","block":"cabinet_door","axis":"z","mode":"solid","from":[2,1,1],"to":[2,3,1]},
  {"type":"points","block":"handle","at":[[0,2,1],[2,2,1]]}
]
```

## 2) 中世纪木衣柜
```json
[
  {"type":"box","block":"wood_body","mode":"solid","from":[0,0,0],"to":[2,4,1]},
  {"type":"plane","block":"wood_door","axis":"z","mode":"solid","from":[0,1,1],"to":[1,3,1]},
  {"type":"points","block":"wood_top","at":[[0,4,0],[1,4,0],[2,4,0]]},
  {"type":"points","block":"handle","at":[[0,2,1],[1,2,1]]}
]
```

## 3) 满墙书架
```json
[
  {"type":"box","block":"bookshelf","mode":"solid","from":[0,0,0],"to":[5,4,0]},
  {"type":"points","block":"light_decor","at":[[2,2,0]]},
  {"type":"points","block":"plant_decor","at":[[4,3,0]]}
]
```

## 4) 现代开放书架
```json
[
  {"type":"plane","block":"shelf_side","axis":"x","mode":"solid","from":[0,0,0],"to":[0,4,0]},
  {"type":"plane","block":"shelf_side","axis":"x","mode":"solid","from":[4,0,0],"to":[4,4,0]},
  {"type":"line","block":"shelf_board","from":[0,0,0],"to":[4,0,0]},
  {"type":"line","block":"shelf_board","from":[0,2,0],"to":[4,2,0]},
  {"type":"line","block":"shelf_board","from":[0,4,0],"to":[4,4,0]},
  {"type":"points","block":"book_item","at":[[1,1,0],[2,3,0]]}
]
```

## 5) 宝箱堆
```json
[
  {"type":"points","block":"chest_large","at":[[0,0,0],[1,0,0]]},
  {"type":"points","block":"barrel","at":[[0,1,0]]},
  {"type":"points","block":"chest_trap","at":[[2,0,0]]}
]
```

## 6) 储物架（三层）
```json
[
  {"type":"line","block":"shelf_board","from":[0,0,0],"to":[3,0,0]},
  {"type":"line","block":"shelf_board","from":[0,2,0],"to":[3,2,0]},
  {"type":"line","block":"shelf_board","from":[0,4,0],"to":[3,4,0]},
  {"type":"line","block":"shelf_support","from":[0,0,-1],"to":[0,4,-1]},
  {"type":"line","block":"shelf_support","from":[3,0,-1],"to":[3,4,-1]},
  {"type":"points","block":"stored_item","at":[[1,1,0],[2,3,0]]}
]
```

## 7) 厨房橱柜组合（上下柜 + 台面）
```json
[
  {"type":"box","block":"lower_cabinet","mode":"solid","from":[0,0,0],"to":[4,1,1]},
  {"type":"plane","block":"countertop","axis":"y","mode":"solid","from":[0,2,0],"to":[4,2,1]},
  {"type":"box","block":"upper_cabinet","mode":"solid","from":[0,4,0],"to":[4,5,0]},
  {"type":"points","block":"drawer_face","at":[[0,0,1],[1,0,1],[2,0,1],[3,0,1],[4,0,1]]}
]
```

## 8) 玻璃展示柜
```json
[
  {"type":"box","block":"display_base","mode":"solid","from":[0,0,0],"to":[2,0,2]},
  {"type":"plane","block":"glass_panel","axis":"x","mode":"solid","from":[0,1,0],"to":[0,3,2]},
  {"type":"plane","block":"glass_panel","axis":"x","mode":"solid","from":[2,1,0],"to":[2,3,2]},
  {"type":"plane","block":"glass_panel","axis":"z","mode":"solid","from":[0,1,0],"to":[2,3,0]},
  {"type":"plane","block":"glass_panel","axis":"z","mode":"solid","from":[0,1,2],"to":[2,3,2]},
  {"type":"plane","block":"display_top","axis":"y","mode":"solid","from":[0,3,0],"to":[2,3,2]},
  {"type":"points","block":"exhibit","at":[[1,1,1]]}
]
```

## 9) 武器架
```json
[
  {"type":"plane","block":"rack_back","axis":"z","mode":"solid","from":[0,1,0],"to":[3,3,0]},
  {"type":"points","block":"weapon_hook","at":[[1,2,0],[2,2,0]]}
]
```

## 10) 行李箱（旅行风）
```json
[
  {"type":"box","block":"trunk_body","mode":"solid","from":[0,0,0],"to":[2,1,1]},
  {"type":"points","block":"trunk_clasp","at":[[1,1,1]]},
  {"type":"points","block":"trunk_strap","at":[[0,1,0],[2,1,0]]}
]
```

## 推荐 block key
- 柜体：`cabinet_body`, `wood_body`, `lower_cabinet`, `upper_cabinet`
- 门/面板：`cabinet_door`, `wood_door`, `drawer_face`, `glass_panel`
- 架板：`shelf_board`, `countertop`, `shelf_support`
- 功能件：`handle`, `chest_large`, `barrel`, `exhibit`
