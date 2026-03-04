---
name: "桌子模板"
description: "餐桌、书桌、茶几、工作台等多风格模板（10+示例）。"
---

# 桌子模板

## 使用约定
- 所有模板为局部坐标，可直接整体平移复用。
- 台面优先 `plane(axis=y)`，桌腿优先 `points` 或 `line`。

## 1) 玻璃茶几
```json
[
  {"type":"plane","block":"glass_top","axis":"y","mode":"solid","from":[0,1,0],"to":[2,1,2]},
  {"type":"points","block":"metal_leg","at":[[0,0,0],[2,0,0],[0,0,2],[2,0,2]]}
]
```

## 2) 办公桌（带抽屉）
```json
[
  {"type":"plane","block":"desk_top","axis":"y","mode":"solid","from":[0,1,0],"to":[4,1,2]},
  {"type":"box","block":"desk_side","mode":"solid","from":[0,0,0],"to":[0,0,2]},
  {"type":"box","block":"desk_side","mode":"solid","from":[4,0,0],"to":[4,0,2]},
  {"type":"box","block":"drawer","mode":"solid","from":[1,0,0],"to":[2,0,1]}
]
```

## 3) 极简餐桌（6人）
```json
[
  {"type":"plane","block":"table_top","axis":"y","mode":"solid","from":[0,1,0],"to":[5,1,2]},
  {"type":"line","block":"table_support","from":[2,0,1],"to":[3,0,1]}
]
```

## 4) 橡木餐桌
```json
[
  {"type":"plane","block":"oak_top","axis":"y","mode":"solid","from":[0,1,0],"to":[4,1,2]},
  {"type":"points","block":"oak_leg","at":[[0,0,0],[4,0,0],[0,0,2],[4,0,2]]}
]
```

## 5) 酒馆大桌
```json
[
  {"type":"box","block":"tavern_top","mode":"solid","from":[0,1,0],"to":[6,1,2]},
  {"type":"line","block":"tavern_leg","from":[0,0,1],"to":[0,0,1]},
  {"type":"line","block":"tavern_leg","from":[6,0,1],"to":[6,0,1]},
  {"type":"points","block":"table_decor","at":[[2,2,1],[4,2,1]]}
]
```

## 6) 工匠工作台
```json
[
  {"type":"box","block":"work_top","mode":"solid","from":[0,1,0],"to":[3,1,2]},
  {"type":"box","block":"tool_box","mode":"solid","from":[0,0,0],"to":[0,0,1]},
  {"type":"box","block":"tool_box","mode":"solid","from":[3,0,0],"to":[3,0,1]},
  {"type":"points","block":"tool_item","at":[[1,2,1],[2,2,1]]}
]
```

## 7) 日式矮桌
```json
[
  {"type":"plane","block":"low_top","axis":"y","mode":"solid","from":[0,0,0],"to":[3,0,2]},
  {"type":"points","block":"zabuton","at":[[0,0,-1],[3,0,-1]]}
]
```

## 8) 中式八仙桌
```json
[
  {"type":"plane","block":"china_top","axis":"y","mode":"solid","from":[0,1,0],"to":[2,1,2]},
  {"type":"points","block":"china_leg","at":[[0,0,0],[2,0,0],[0,0,2],[2,0,2]]},
  {"type":"points","block":"tea_set","at":[[1,2,1]]}
]
```

## 9) 附魔研究台
```json
[
  {"type":"box","block":"magic_core","mode":"solid","from":[1,1,1],"to":[1,1,1]},
  {"type":"box","block":"bookshelf","mode":"solid","from":[0,0,0],"to":[2,1,0]},
  {"type":"box","block":"bookshelf","mode":"solid","from":[0,0,2],"to":[2,1,2]},
  {"type":"points","block":"candle","at":[[0,2,1],[2,2,1]]}
]
```

## 10) 炼金术台
```json
[
  {"type":"plane","block":"alchemy_top","axis":"y","mode":"solid","from":[0,1,0],"to":[2,1,1]},
  {"type":"points","block":"alchemy_set","at":[[0,2,0],[1,2,0],[2,2,0]]},
  {"type":"points","block":"alchemy_container","at":[[0,0,0],[2,0,0]]}
]
```

## 11) 户外野餐桌
```json
[
  {"type":"plane","block":"picnic_top","axis":"y","mode":"solid","from":[0,1,0],"to":[5,1,2]},
  {"type":"line","block":"picnic_bench","from":[0,0,-1],"to":[5,0,-1]},
  {"type":"line","block":"picnic_bench","from":[0,0,3],"to":[5,0,3]},
  {"type":"points","block":"picnic_leg","at":[[0,0,1],[5,0,1]]}
]
```

## 12) 圆桌近似
```json
[
  {"type":"points","block":"round_top","at":[[2,1,0],[1,1,1],[0,1,2],[1,1,3],[2,1,4],[3,1,3],[4,1,2],[3,1,1]]},
  {"type":"line","block":"round_stem","from":[2,0,2],"to":[2,1,2]}
]
```

## 推荐 block key
- 台面：`table_top`, `oak_top`, `glass_top`, `low_top`
- 桌腿：`table_leg`, `metal_leg`, `oak_leg`
- 功能件：`drawer`, `tool_item`, `tea_set`, `alchemy_set`
