---
name: "沙发模板"
description: "多风格沙发模板（12+示例），覆盖单人/双人/三人/L型/户外等。"
---

# 沙发模板

## 使用约定
- 座垫优先 `box:solid`，靠背优先 `plane` 或 `line`，扶手优先 `points`。

## 1) 现代单人沙发
```json
[
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,0],"to":[1,0,1]},
  {"type":"plane","block":"sofa_back","axis":"z","mode":"solid","from":[0,1,0],"to":[1,2,0]},
  {"type":"points","block":"sofa_arm","at":[[-1,1,0],[2,1,0]]}
]
```

## 2) 皮质单人椅
```json
[
  {"type":"box","block":"leather_seat","mode":"solid","from":[0,0,0],"to":[1,0,1]},
  {"type":"plane","block":"leather_back","axis":"z","mode":"solid","from":[0,1,0],"to":[1,2,0]},
  {"type":"points","block":"wood_arm","at":[[-1,1,0],[2,1,0]]}
]
```

## 3) 懒人沙发/豆袋
```json
[
  {"type":"box","block":"bean_body","mode":"solid","from":[0,0,0],"to":[1,0,1]},
  {"type":"points","block":"bean_back","at":[[0,1,0],[1,1,0]]}
]
```

## 4) 现代双人沙发
```json
[
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,0],"to":[2,0,1]},
  {"type":"plane","block":"sofa_back","axis":"z","mode":"solid","from":[0,1,0],"to":[2,2,0]},
  {"type":"points","block":"sofa_arm","at":[[-1,1,0],[3,1,0]]}
]
```

## 5) 复古双人沙发
```json
[
  {"type":"box","block":"vintage_seat","mode":"solid","from":[0,0,0],"to":[2,0,1]},
  {"type":"plane","block":"vintage_back","axis":"z","mode":"solid","from":[0,1,0],"to":[2,2,0]},
  {"type":"points","block":"carved_arm","at":[[-1,1,0],[-1,1,1],[3,1,0],[3,1,1]]}
]
```

## 6) 三人沙发
```json
[
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,0],"to":[3,0,1]},
  {"type":"plane","block":"sofa_back","axis":"z","mode":"solid","from":[0,1,0],"to":[3,2,0]},
  {"type":"box","block":"sofa_arm","mode":"solid","from":[-1,0,0],"to":[-1,1,1]},
  {"type":"box","block":"sofa_arm","mode":"solid","from":[4,0,0],"to":[4,1,1]}
]
```

## 7) 带躺椅三人沙发
```json
[
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,0],"to":[3,0,1]},
  {"type":"plane","block":"sofa_back","axis":"z","mode":"solid","from":[0,1,0],"to":[3,2,0]},
  {"type":"box","block":"chaise","mode":"solid","from":[4,0,0],"to":[4,0,2]}
]
```

## 8) L型沙发
```json
[
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,0],"to":[4,0,1]},
  {"type":"plane","block":"sofa_back","axis":"z","mode":"solid","from":[0,1,0],"to":[4,2,0]},
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,1],"to":[0,0,3]},
  {"type":"plane","block":"sofa_back","axis":"x","mode":"solid","from":[-1,1,1],"to":[-1,2,3]},
  {"type":"points","block":"sofa_arm","at":[[5,1,0],[-1,1,4]]}
]
```

## 9) 大型转角沙发
```json
[
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,0],"to":[5,0,1]},
  {"type":"plane","block":"sofa_back","axis":"z","mode":"solid","from":[0,1,0],"to":[5,2,0]},
  {"type":"box","block":"sofa_seat","mode":"solid","from":[0,0,1],"to":[1,0,4]},
  {"type":"plane","block":"sofa_back","axis":"x","mode":"solid","from":[-1,1,1],"to":[-1,2,4]},
  {"type":"points","block":"wood_arm","at":[[6,1,0],[-1,1,5]]}
]
```

## 10) 藤编户外沙发
```json
[
  {"type":"box","block":"rattan_seat","mode":"solid","from":[0,0,0],"to":[2,0,1]},
  {"type":"plane","block":"rattan_back","axis":"z","mode":"solid","from":[0,1,0],"to":[2,2,0]},
  {"type":"points","block":"rattan_arm","at":[[-1,1,0],[3,1,0]]}
]
```

## 11) 石质户外长椅
```json
[
  {"type":"box","block":"stone_seat","mode":"solid","from":[0,0,0],"to":[4,0,0]},
  {"type":"points","block":"stone_support","at":[[0,0,-1],[4,0,-1]]}
]
```

## 12) 沙发配件：抱枕 + 边几
```json
[
  {"type":"points","block":"cushion_a","at":[[0,1,0]]},
  {"type":"points","block":"cushion_b","at":[[2,1,0]]},
  {"type":"points","block":"side_table","at":[[4,0,0]]},
  {"type":"points","block":"table_lamp","at":[[4,1,0]]}
]
```

## 推荐 block key
- 座垫：`sofa_seat`, `leather_seat`, `rattan_seat`, `stone_seat`
- 靠背：`sofa_back`, `leather_back`, `vintage_back`, `rattan_back`
- 扶手：`sofa_arm`, `wood_arm`, `carved_arm`, `rattan_arm`
- 配件：`cushion_a`, `cushion_b`, `side_table`, `table_lamp`
