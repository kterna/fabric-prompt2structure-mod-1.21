---
name: "日式民居"
description: "日式民居 的体量、材料与动作偏好。"
---

# 日式民居

## 体量特征
- 朴素低矮、大坡度屋顶（茅草或灰瓦）
- 高架木地板、深远屋檐、障子拉门
- 内外过渡：室内 -> 縁側 -> 庭院

## 材料倾向
- 原木柱、云杉木板、白色墙面、干海带/干草屋顶
- 推荐方块: spruce_planks, stripped_spruce_log, white_concrete, hay_block, dried_kelp_block, gray_concrete, lime_carpet, white_stained_glass_pane
- 禁忌: 不用朱红色（神社专用），避免过度装饰

## 动作偏好
- line 木柱骨架 + plane 白墙填充 + box:solid 高架地板 + points 障子与灯笼

## 立面模板
```json
[
  {"type":"box","block":"spruce_planks","mode":"solid","from":[0,0,0],"to":[10,0,8]},
  {"type":"line","block":"stripped_spruce_log","from":[0,1,0],"to":[0,5,0]},
  {"type":"line","block":"stripped_spruce_log","from":[10,1,0],"to":[10,5,0]},
  {"type":"plane","block":"white_concrete","axis":"z","mode":"solid","from":[1,1,0],"to":[9,4,0]},
  {"type":"points","block":"white_stained_glass_pane","at":[[3,2,0],[5,2,0],[7,2,0]]},
  {"type":"plane","block":"gray_concrete","axis":"y","mode":"solid","from":[-1,6,0],"to":[11,6,8]}
]
```
