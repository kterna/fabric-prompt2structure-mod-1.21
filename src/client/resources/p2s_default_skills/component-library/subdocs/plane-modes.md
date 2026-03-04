---
name: "plane 模式"
description: "axis + solid/outline 的平面构造方式。"
---

# plane 模式

## 地板与天花
```json
[
  {"type":"plane","block":"floor","axis":"y","mode":"solid","from":[0,0,0],"to":[10,0,10]},
  {"type":"plane","block":"ceiling","axis":"y","mode":"solid","from":[0,6,0],"to":[10,6,10]}
]
```

## 立面轮廓
```json
[{"type":"plane","block":"frame","axis":"z","mode":"outline","from":[0,1,0],"to":[10,5,0]}]
```

## 约束
- `plane.axis` 必须是 `x|y|z`。
- `from` 与 `to` 在该轴上的坐标必须相同。
