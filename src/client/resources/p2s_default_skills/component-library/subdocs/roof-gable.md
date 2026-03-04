---
name: "双坡屋顶"
description: "通过分层线与面近似实现双坡屋顶。"
---

# 双坡屋顶

## 分层模板
```json
[
  {"type":"line","block":"roof_edge","from":[0,6,0],"to":[10,6,0]},
  {"type":"line","block":"roof_edge","from":[0,6,8],"to":[10,6,8]},
  {"type":"line","block":"roof_edge","from":[1,7,1],"to":[9,7,1]},
  {"type":"line","block":"roof_edge","from":[1,7,7],"to":[9,7,7]},
  {"type":"line","block":"ridge","from":[2,8,4],"to":[8,8,4]},
  {"type":"plane","block":"roof_fill","axis":"y","mode":"outline","from":[0,6,0],"to":[10,8,8]}
]
```

## 迁移说明
- 对应外部项目 `drawRoofBounds` 的基础版本。
