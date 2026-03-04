---
name: "现代高层"
description: "现代高层 的体量、材料与动作偏好。"
---

# 现代高层

## 体量特征
- 高竖向比例、重复窗带与模块立面

## 材料倾向
- 玻璃幕墙、金属、深色结构线

## 动作偏好
- box:solid 主塔芯 + plane 立面网格

## 立面模板
```json
[
  {"type":"box","block":"main","mode":"walls","from":[0,1,0],"to":[12,6,10]},
  {"type":"plane","block":"facade","axis":"z","mode":"outline","from":[0,1,0],"to":[12,6,0]},
  {"type":"points","block":"accent","at":[[2,4,0],[6,4,0],[10,4,0]]}
]
```
