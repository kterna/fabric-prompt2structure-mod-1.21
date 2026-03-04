---
name: "多边形体"
description: "多边形底面 + 竖向拉伸的模板。"
---

# 多边形体

## 六边形柱（近似）
```json
[
  {"type":"line","block":"edge","from":[3,0,0],"to":[1,0,3]},
  {"type":"line","block":"edge","from":[1,0,3],"to":[-1,0,3]},
  {"type":"line","block":"edge","from":[-1,0,3],"to":[-3,0,0]},
  {"type":"line","block":"edge","from":[-3,0,0],"to":[-1,0,-3]},
  {"type":"line","block":"edge","from":[-1,0,-3],"to":[1,0,-3]},
  {"type":"line","block":"edge","from":[1,0,-3],"to":[3,0,0]},

  {"type":"line","block":"edge","from":[3,0,0],"to":[3,5,0]},
  {"type":"line","block":"edge","from":[-3,0,0],"to":[-3,5,0]}
]
```

## 迁移说明
- 对应外部项目 `drawPolygon`。
