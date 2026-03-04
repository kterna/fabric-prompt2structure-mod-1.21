---
name: "浮空岛/天空建筑"
description: "浮空岛/天空建筑 的体量、材料与动作偏好。"
---

# 浮空岛/天空建筑

## 体量特征
- 反重力悬浮、倒锥形岛屿基座、轻盈镂空结构
- 白色主调 + 金色装饰 + 发光元素
- 层次分明：最高神殿 -> 中层花园 -> 底层入口

## 材料倾向
- 白色石英、金色装饰、发光海晶灯、水晶
- 推荐方块: smooth_quartz, white_concrete, gold_block, sea_lantern, end_rod, amethyst_block, glass, white_wool

## 动作偏好
- box:walls 轻盈主体 + line 细长柱列 + points 发光节点与水晶装饰

## 立面模板
```json
[
  {"type":"box","block":"smooth_quartz","mode":"walls","from":[2,0,2],"to":[12,8,12]},
  {"type":"line","block":"quartz_pillar","from":[2,0,2],"to":[2,10,2]},
  {"type":"line","block":"quartz_pillar","from":[12,0,2],"to":[12,10,2]},
  {"type":"line","block":"quartz_pillar","from":[2,0,12],"to":[2,10,12]},
  {"type":"line","block":"quartz_pillar","from":[12,0,12],"to":[12,10,12]},
  {"type":"points","block":"sea_lantern","at":[[7,10,7]]},
  {"type":"points","block":"end_rod","at":[[4,9,2],[10,9,2],[4,9,12],[10,9,12]]}
]
```
