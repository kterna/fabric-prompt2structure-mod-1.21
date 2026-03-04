---
name: "金字塔"
description: "按层收进 box/plane 构造金字塔。"
---

# 金字塔

## 方形基底模板
```json
[
  {"type":"plane","block":"stone","axis":"y","mode":"solid","from":[0,0,0],"to":[8,0,8]},
  {"type":"plane","block":"stone","axis":"y","mode":"solid","from":[1,1,1],"to":[7,1,7]},
  {"type":"plane","block":"stone","axis":"y","mode":"solid","from":[2,2,2],"to":[6,2,6]},
  {"type":"plane","block":"stone","axis":"y","mode":"solid","from":[3,3,3],"to":[5,3,5]},
  {"type":"points","block":"stone","at":[[4,4,4]]}
]
```

## 迁移说明
- 对应外部项目 `drawPyramid`。
