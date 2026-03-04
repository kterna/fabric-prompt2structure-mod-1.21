---
name: "户外家具模板"
description: "多风格户外家具模板（13+示例），覆盖桌椅、秋千、凉亭、花园装饰、烧烤等。"
---

# 户外家具模板

## 使用约定
- 支柱优先 `line`，平台/顶棚优先 `plane`，散落装饰用 `points`。

## 1) 小庭院整体
```json
[
  {"type":"plane","block":"path","axis":"y","mode":"solid","from":[0,0,2],"to":[8,0,4]},
  {"type":"box","block":"planter","mode":"shell","from":[1,0,1],"to":[3,1,3]},
  {"type":"points","block":"outdoor_chair","at":[[5,0,1],[6,0,1],[5,0,6],[6,0,6]]}
]
```

## 2) 野餐桌
```json
[
  {"type":"plane","block":"table_top","axis":"y","mode":"solid","from":[0,1,0],"to":[4,1,1]},
  {"type":"line","block":"bench_front","from":[0,0,-1],"to":[4,0,-1]},
  {"type":"line","block":"bench_back","from":[0,0,2],"to":[4,0,2]},
  {"type":"points","block":"table_leg","at":[[0,0,0],[4,0,0]]}
]
```

## 3) 木质躺椅
```json
[
  {"type":"line","block":"lounge_base","from":[0,0,0],"to":[0,0,3]},
  {"type":"points","block":"lounge_back","at":[[0,1,0]]},
  {"type":"points","block":"lounge_leg","at":[[0,0,-1],[0,0,4]]}
]
```

## 4) 单人秋千
```json
[
  {"type":"line","block":"swing_post","from":[-1,0,0],"to":[-1,3,0]},
  {"type":"line","block":"swing_post","from":[1,0,0],"to":[1,3,0]},
  {"type":"line","block":"swing_beam","from":[-1,3,0],"to":[1,3,0]},
  {"type":"line","block":"swing_chain","from":[0,3,0],"to":[0,2,0]},
  {"type":"points","block":"swing_seat","at":[[0,1,0]]}
]
```

## 5) 双人秋千椅
```json
[
  {"type":"line","block":"frame","from":[-2,0,-1],"to":[-2,3,-1]},
  {"type":"line","block":"frame","from":[-2,0,1],"to":[-2,3,1]},
  {"type":"line","block":"frame","from":[2,0,-1],"to":[2,3,-1]},
  {"type":"line","block":"frame","from":[2,0,1],"to":[2,3,1]},
  {"type":"line","block":"swing_beam","from":[-2,3,0],"to":[2,3,0]},
  {"type":"line","block":"swing_chain","from":[-1,3,0],"to":[-1,2,0]},
  {"type":"line","block":"swing_chain","from":[1,3,0],"to":[1,2,0]},
  {"type":"box","block":"swing_bench","mode":"solid","from":[-1,1,-1],"to":[1,1,0]},
  {"type":"plane","block":"swing_back","axis":"z","mode":"solid","from":[-1,1,-1],"to":[1,2,-1]}
]
```

## 6) 简易凉亭
```json
[
  {"type":"line","block":"post","from":[0,0,0],"to":[0,2,0]},
  {"type":"line","block":"post","from":[3,0,0],"to":[3,2,0]},
  {"type":"line","block":"post","from":[0,0,3],"to":[0,2,3]},
  {"type":"line","block":"post","from":[3,0,3],"to":[3,2,3]},
  {"type":"plane","block":"canopy","axis":"y","mode":"solid","from":[0,3,0],"to":[3,3,3]}
]
```

## 7) 遮阳伞
```json
[
  {"type":"line","block":"pole","from":[0,0,0],"to":[0,2,0]},
  {"type":"plane","block":"umbrella","axis":"y","mode":"solid","from":[-1,3,-1],"to":[1,3,1]},
  {"type":"points","block":"pole_top","at":[[0,3,0]]}
]
```

## 8) 藤架/葡萄架
```json
[
  {"type":"line","block":"post","from":[0,0,0],"to":[0,2,0]},
  {"type":"line","block":"post","from":[4,0,0],"to":[4,2,0]},
  {"type":"line","block":"post","from":[0,0,3],"to":[0,2,3]},
  {"type":"line","block":"post","from":[4,0,3],"to":[4,2,3]},
  {"type":"line","block":"trellis","from":[0,3,0],"to":[4,3,0]},
  {"type":"line","block":"trellis","from":[0,3,3],"to":[4,3,3]},
  {"type":"points","block":"vine_decor","at":[[1,2,0],[2,2,3],[3,2,1]]}
]
```

## 9) 花盆组合
```json
[
  {"type":"points","block":"small_pot","at":[[0,0,0],[1,0,0],[2,0,0]]},
  {"type":"box","block":"large_planter","mode":"shell","from":[0,0,2],"to":[1,1,3]},
  {"type":"points","block":"tall_plant","at":[[0,2,2]]}
]
```

## 10) 喷泉
```json
[
  {"type":"box","block":"basin","mode":"shell","from":[-2,0,-2],"to":[2,1,2]},
  {"type":"line","block":"fountain_pillar","from":[0,1,0],"to":[0,3,0]},
  {"type":"points","block":"water_top","at":[[0,4,0]]},
  {"type":"points","block":"water_fill","at":[[0,0,0],[1,0,0],[-1,0,0],[0,0,1],[0,0,-1]]}
]
```

## 11) BBQ 烤架
```json
[
  {"type":"box","block":"grill_body","mode":"solid","from":[0,0,0],"to":[2,1,1]},
  {"type":"points","block":"grill_fire","at":[[1,2,0]]},
  {"type":"points","block":"grill_grate","at":[[0,2,0],[2,2,0]]}
]
```

## 12) 户外壁炉
```json
[
  {"type":"box","block":"fireplace_body","mode":"solid","from":[0,0,0],"to":[2,2,1]},
  {"type":"points","block":"fire","at":[[1,0,1]]},
  {"type":"line","block":"chimney","from":[1,3,0],"to":[1,5,0]}
]
```

## 13) 鸟浴盆
```json
[
  {"type":"points","block":"pedestal","at":[[0,0,0]]},
  {"type":"points","block":"basin","at":[[0,1,0]]}
]
```

## 推荐 block key
- 支柱/框架：`post`, `pole`, `frame`, `trellis`
- 平面/顶棚：`canopy`, `umbrella`, `table_top`
- 座椅：`swing_seat`, `swing_bench`, `bench_front`, `lounge_base`
- 装饰：`vine_decor`, `small_pot`, `large_planter`, `water_top`, `fire`
