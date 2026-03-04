---
name: "椅子模板"
description: "多风格椅子/凳子模板（10+示例），均为可平移复用的局部动作。"
---

# 椅子模板

## 使用约定
- 所有模板都是局部坐标，默认锚点在椅子前左脚附近。
- 使用时仅需整体平移坐标；旋转 90 度时同步修正 `facing`。

## 1) 现代办公椅
```json
[
  {"type":"points","block":"chair_leg","at":[[0,0,0]]},
  {"type":"plane","block":"chair_seat","axis":"y","mode":"solid","from":[-1,1,-1],"to":[1,1,1]},
  {"type":"plane","block":"chair_back","axis":"z","mode":"solid","from":[-1,2,-1],"to":[1,3,-1]},
  {"type":"points","block":"chair_wheel","at":[[-1,0,-1],[1,0,-1],[-1,0,1],[1,0,1]]}
]
```

## 2) 吧台高脚椅
```json
[
  {"type":"line","block":"stool_core","from":[0,0,0],"to":[0,2,0]},
  {"type":"plane","block":"stool_seat","axis":"y","mode":"solid","from":[-1,3,-1],"to":[1,3,1]},
  {"type":"line","block":"stool_footrest","from":[-1,1,0],"to":[1,1,0]}
]
```

## 3) 现代扶手椅
```json
[
  {"type":"box","block":"chair_seat","mode":"solid","from":[0,0,0],"to":[2,0,2]},
  {"type":"plane","block":"chair_back","axis":"z","mode":"solid","from":[0,1,0],"to":[2,2,0]},
  {"type":"line","block":"chair_arm","from":[0,1,1],"to":[0,1,2]},
  {"type":"line","block":"chair_arm","from":[2,1,1],"to":[2,1,2]}
]
```

## 4) 橡木餐椅
```json
[
  {"type":"box","block":"wood_seat","mode":"solid","from":[0,1,0],"to":[1,1,1]},
  {"type":"points","block":"wood_leg","at":[[0,0,0],[1,0,0],[0,0,1],[1,0,1]]},
  {"type":"plane","block":"wood_back","axis":"z","mode":"solid","from":[0,2,0],"to":[1,3,0]}
]
```

## 5) 王座椅
```json
[
  {"type":"box","block":"throne_seat","mode":"solid","from":[0,0,0],"to":[2,0,2]},
  {"type":"box","block":"throne_back","mode":"solid","from":[0,1,0],"to":[2,4,0]},
  {"type":"points","block":"throne_arm","at":[[0,1,2],[2,1,2]]},
  {"type":"points","block":"throne_gold","at":[[1,5,0]]}
]
```

## 6) 酒馆长凳（双人）
```json
[
  {"type":"plane","block":"bench_seat","axis":"y","mode":"solid","from":[0,1,0],"to":[3,1,0]},
  {"type":"points","block":"bench_leg","at":[[0,0,0],[3,0,0]]},
  {"type":"line","block":"bench_back","from":[0,2,-1],"to":[3,2,-1]}
]
```

## 7) 日式坐垫
```json
[
  {"type":"plane","block":"zabuton","axis":"y","mode":"solid","from":[0,0,0],"to":[1,0,1]},
  {"type":"points","block":"zabuton_trim","at":[[0,0,0],[1,0,0],[0,0,1],[1,0,1]]}
]
```

## 8) 中式太师椅
```json
[
  {"type":"box","block":"china_seat","mode":"solid","from":[0,1,0],"to":[1,1,1]},
  {"type":"plane","block":"china_back","axis":"z","mode":"outline","from":[0,2,0],"to":[1,4,0]},
  {"type":"line","block":"china_arm","from":[0,2,1],"to":[0,3,1]},
  {"type":"line","block":"china_arm","from":[1,2,1],"to":[1,3,1]}
]
```

## 9) 北欧木椅
```json
[
  {"type":"plane","block":"nordic_seat","axis":"y","mode":"solid","from":[0,1,0],"to":[1,1,1]},
  {"type":"points","block":"nordic_leg","at":[[0,0,0],[1,0,0],[0,0,1],[1,0,1]]},
  {"type":"line","block":"nordic_back","from":[0,2,0],"to":[1,3,0]}
]
```

## 10) 工业铁椅
```json
[
  {"type":"plane","block":"metal_seat","axis":"y","mode":"solid","from":[0,1,0],"to":[1,1,1]},
  {"type":"line","block":"metal_frame","from":[0,0,0],"to":[0,2,0]},
  {"type":"line","block":"metal_frame","from":[1,0,1],"to":[1,2,1]},
  {"type":"line","block":"metal_back","from":[0,2,0],"to":[1,2,0]}
]
```

## 11) 户外铁艺椅
```json
[
  {"type":"plane","block":"garden_seat","axis":"y","mode":"solid","from":[0,1,0],"to":[1,1,1]},
  {"type":"points","block":"garden_leg","at":[[0,0,0],[1,0,0],[0,0,1],[1,0,1]]},
  {"type":"plane","block":"garden_back","axis":"z","mode":"outline","from":[0,2,0],"to":[1,3,0]}
]
```

## 12) 赛博霓虹椅
```json
[
  {"type":"box","block":"cyber_seat","mode":"solid","from":[0,1,0],"to":[1,1,1]},
  {"type":"plane","block":"cyber_back","axis":"z","mode":"solid","from":[0,2,0],"to":[1,3,0]},
  {"type":"points","block":"cyber_light","at":[[0,2,1],[1,2,1]]}
]
```

## 13) 奇幻水晶椅
```json
[
  {"type":"box","block":"crystal_seat","mode":"solid","from":[0,1,0],"to":[1,1,1]},
  {"type":"line","block":"crystal_spine","from":[0,2,0],"to":[1,4,0]},
  {"type":"points","block":"crystal_glow","at":[[0,3,1],[1,3,1]]}
]
```

## 14) 长排候客椅（四座）
```json
[
  {"type":"plane","block":"row_seat","axis":"y","mode":"solid","from":[0,1,0],"to":[7,1,1]},
  {"type":"line","block":"row_back","from":[0,2,0],"to":[7,2,0]},
  {"type":"points","block":"row_leg","at":[[0,0,0],[2,0,0],[4,0,0],[6,0,0],[7,0,0]]}
]
```

## 推荐 block key
- 现代：`chair_seat`, `chair_back`, `chair_leg`, `chair_wheel`
- 木质：`wood_seat`, `wood_back`, `wood_leg`
- 华丽：`throne_seat`, `throne_back`, `throne_gold`
- 特殊：`cyber_light`, `crystal_glow`
