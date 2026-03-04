---
name: "门洞组件"
description: "门洞挖空、门框和门楣的组合模板。"
---

# 门洞组件

## 门洞 + 门框
```json
[
  {"type":"plane","block":"air","axis":"z","mode":"solid","from":[3,1,0],"to":[4,3,0]},
  {"type":"plane","block":"door_frame","axis":"z","mode":"outline","from":[2,1,0],"to":[5,4,0]},
  {"type":"line","block":"lintel","from":[2,4,0],"to":[5,4,0]}
]
```

## 说明
- 使用前需确认 `air` 在 palette 中映射到 `minecraft:air`。
