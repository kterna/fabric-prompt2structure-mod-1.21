---
name: "北欧维京"
description: "北欧维京 的体量、材料与动作偏好。"
---

# 北欧维京

## 体量特征
- 长屋轮廓、厚木屋顶、粗犷构架

## 材料倾向
- 原木、石材、深色屋面

## 动作偏好
- box:walls + line 木梁加固

## 立面模板
```json
[
  {"type":"box","block":"main","mode":"walls","from":[0,1,0],"to":[12,6,10]},
  {"type":"plane","block":"facade","axis":"z","mode":"outline","from":[0,1,0],"to":[12,6,0]},
  {"type":"points","block":"accent","at":[[2,4,0],[6,4,0],[10,4,0]]}
]
```
