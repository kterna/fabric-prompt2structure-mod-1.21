---
name: "中世纪乡村"
description: "中世纪乡村 的体量、材料与动作偏好。"
---

# 中世纪乡村

## 体量特征
- 低层体块、坡屋顶、外露木梁

## 材料倾向
- 石材、木板、灰泥

## 动作偏好
- box:walls + line 木梁 + plane 屋面

## 立面模板
```json
[
  {"type":"box","block":"main","mode":"walls","from":[0,1,0],"to":[12,6,10]},
  {"type":"plane","block":"facade","axis":"z","mode":"outline","from":[0,1,0],"to":[12,6,0]},
  {"type":"points","block":"accent","at":[[2,4,0],[6,4,0],[10,4,0]]}
]
```
