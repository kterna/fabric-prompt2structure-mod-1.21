---
name: "自然景观"
description: "自然景观（树木、山脉、水体、洞穴）的构建要点与材料偏好。"
---

# 自然景观

## 体量特征
- 有机不规则形态、避免直线与对称
- 生态分层：乔木 -> 灌木 -> 草本 -> 地被
- 景深层次：前景细节 -> 中景主体 -> 远景轮廓

## 树木要点
- 根部向外扩展、树干从底到顶逐渐变细
- 树冠类型: 橡树型（圆润）、松树型（锥形）、柳树型（下垂）
- 群落布局疏密有致、高低错落

## 地形要点
- 山体: 尖峰型 / 圆顶型 / 台地型 / 火山型
- 坡度渐变、材质分层（山顶岩石 -> 中部草地 -> 底部泥土）
- 水体: 源头窄 -> 上游急 -> 中游弯 -> 下游宽

## 材料倾向
| 场景 | 主要方块 | 点缀方块 |
|------|----------|----------|
| 温带森林 | oak_log, oak_leaves | grass, poppy, brown_mushroom |
| 针叶林 | spruce_log, spruce_leaves | podzol, fern, snow |
| 山地岩石 | stone, andesite, diorite | gravel, cobblestone |
| 河流湖泊 | water, clay, sand | lily_pad, seagrass |
| 沙漠 | sand, sandstone | dead_bush, cactus |
| 雪原 | snow_block, ice, packed_ice | spruce_log, stone |

## 动作偏好
- line 树干/山脊 + points 树冠与植被散布 + box:solid 地形体块
