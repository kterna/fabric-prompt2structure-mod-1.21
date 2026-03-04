---
name: "乡村农舍"
description: "乡村农舍 的体量、材料与动作偏好。"
---

# 乡村农舍

## 体量特征
- 低层宽体、附属仓储与门廊

## 材料倾向
- 木板、石基、浅色墙

## 动作偏好
- box:walls 主体 + plane 屋面

## 立面模板
```json
[
  {"type":"box","block":"main","mode":"walls","from":[0,1,0],"to":[12,6,10]},
  {"type":"plane","block":"facade","axis":"z","mode":"outline","from":[0,1,0],"to":[12,6,0]},
  {"type":"points","block":"accent","at":[[2,4,0],[6,4,0],[10,4,0]]}
]
```
