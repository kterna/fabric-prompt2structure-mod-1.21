---
name: "日式城堡"
description: "日式城堡 的体量、材料与动作偏好。"
---

# 日式城堡

## 体量特征
- 多层天守阁、层层向上收缩、石垣基座
- 入母屋造（歇山顶）屋顶、破风（山花）装饰
- 白墙 + 灰瓦 + 金色点缀

## 材料倾向
- 白色混凝土墙面、石砖石垣、深色木构
- 推荐方块: white_concrete, stone_bricks, andesite, dark_oak_planks, gray_concrete, deepslate_tiles, gold_block

## 动作偏好
- box:solid 石垣台基 + box:walls 各层主体 + plane 白墙立面 + points 金色装饰与破风

## 立面模板
```json
[
  {"type":"box","block":"stone_bricks","mode":"solid","from":[0,0,0],"to":[16,4,16]},
  {"type":"box","block":"white_concrete","mode":"walls","from":[2,5,2],"to":[14,10,14]},
  {"type":"box","block":"white_concrete","mode":"walls","from":[4,11,4],"to":[12,15,12]},
  {"type":"plane","block":"deepslate_tiles","axis":"y","mode":"solid","from":[1,10,1],"to":[15,10,15]},
  {"type":"plane","block":"deepslate_tiles","axis":"y","mode":"solid","from":[3,15,3],"to":[13,15,13]},
  {"type":"points","block":"gold_block","at":[[8,16,4],[8,16,12]]}
]
```
