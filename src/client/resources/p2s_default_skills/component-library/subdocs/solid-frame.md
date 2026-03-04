---
name: "实心框架"
description: "有厚度的梁柱框架，适合工业或重型风格。"
---

# 实心框架

## 梁柱模板
```json
[
  {"type":"box","block":"column","mode":"solid","from":[0,0,0],"to":[0,5,0]},
  {"type":"box","block":"column","mode":"solid","from":[8,0,0],"to":[8,5,0]},
  {"type":"box","block":"column","mode":"solid","from":[0,0,8],"to":[0,5,8]},
  {"type":"box","block":"column","mode":"solid","from":[8,0,8],"to":[8,5,8]},

  {"type":"box","block":"beam","mode":"solid","from":[0,5,0],"to":[8,5,0]},
  {"type":"box","block":"beam","mode":"solid","from":[0,5,8],"to":[8,5,8]},
  {"type":"box","block":"beam","mode":"solid","from":[0,5,0],"to":[0,5,8]},
  {"type":"box","block":"beam","mode":"solid","from":[8,5,0],"to":[8,5,8]}
]
```
