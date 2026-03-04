---
name: "水下/亚特兰蒂斯"
description: "水下/亚特兰蒂斯 的体量、材料与动作偏好。"
---

# 水下/亚特兰蒂斯

## 体量特征
- 穹顶抗压结构、有机贝壳/海螺形态
- 透明管道连接建筑群、深海发光照明
- 珊瑚礁环绕、垂直发展

## 材料倾向
- 海晶石系列、玻璃穹顶、珊瑚装饰
- 推荐方块: prismarine, dark_prismarine, prismarine_bricks, sea_lantern, glass, cyan_stained_glass, coral_block, seagrass, conduit

## 动作偏好
- box:walls 海晶石主体 + points 珊瑚与海草散布 + line 玻璃连接通道

## 立面模板
```json
[
  {"type":"box","block":"prismarine_bricks","mode":"walls","from":[0,0,0],"to":[12,8,12]},
  {"type":"plane","block":"dark_prismarine","axis":"y","mode":"solid","from":[0,0,0],"to":[12,0,12]},
  {"type":"points","block":"sea_lantern","at":[[3,4,0],[6,4,0],[9,4,0],[3,4,12],[6,4,12],[9,4,12]]},
  {"type":"points","block":"cyan_stained_glass","at":[[4,3,0],[8,3,0],[4,5,0],[8,5,0]]},
  {"type":"points","block":"coral_block","at":[[0,1,6],[12,1,6],[6,1,0],[6,1,12]]}
]
```
