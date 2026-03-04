---
name: "中式园林"
description: "中式园林 的体量、材料与动作偏好。"
---

# 中式园林

## 体量特征
- 曲折路径、院墙分景、轻体量亭廊

## 材料倾向
- 白墙灰瓦、木构、石景

## 动作偏好
- plane 墙面 + line 廊架 + points 景石

## 立面模板
```json
[
  {"type":"box","block":"main","mode":"walls","from":[0,1,0],"to":[12,6,10]},
  {"type":"plane","block":"facade","axis":"z","mode":"outline","from":[0,1,0],"to":[12,6,0]},
  {"type":"points","block":"accent","at":[[2,4,0],[6,4,0],[10,4,0]]}
]
```
