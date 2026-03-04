---
name: "精灵/自然建筑"
description: "精灵/自然建筑 的体量、材料与动作偏好。"
---

# 精灵/自然建筑

## 体量特征
- 有机曲线、与自然融为一体、隐蔽在树木中
- 树屋/树洞/地下穴居、圆形门窗
- 避免直线直角，保持不规则形态

## 材料倾向
- 原木树干、苔藓、树叶、藤蔓、泥砖
- 推荐方块: oak_log, dark_oak_log, oak_leaves, azalea_leaves, moss_block, mud_bricks, vine, glow_lichen, shroomlight, glow_berries, spruce_planks

## 动作偏好
- line 树干骨架 + points 树叶与藤蔓散布 + box:solid 平台地板

## 立面模板
```json
[
  {"type":"line","block":"oak_log","from":[5,0,5],"to":[5,12,5]},
  {"type":"line","block":"oak_log","from":[3,8,5],"to":[0,11,5]},
  {"type":"line","block":"oak_log","from":[7,8,5],"to":[10,11,5]},
  {"type":"box","block":"spruce_planks","mode":"solid","from":[2,7,3],"to":[8,7,7]},
  {"type":"points","block":"oak_leaves","at":[[3,12,3],[5,13,5],[7,12,7],[4,13,6],[6,13,4]]},
  {"type":"points","block":"glow_lichen","at":[[5,6,5],[4,5,5],[6,5,5]]},
  {"type":"points","block":"vine","at":[[2,7,3],[8,7,7]]}
]
```
