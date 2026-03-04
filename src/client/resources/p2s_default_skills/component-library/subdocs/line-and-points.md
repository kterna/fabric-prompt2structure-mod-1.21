---
name: "line 与 points"
description: "梁柱、轮廓线与细节点位的基础模式。"
---

# line 与 points

## 梁柱
```json
[
  {"type":"line","block":"pillar","from":[0,0,0],"to":[0,5,0]},
  {"type":"line","block":"beam","from":[0,5,0],"to":[8,5,0]}
]
```

## 点位细节
```json
[{"type":"points","block":"light","at":[[1,4,1],[7,4,1],[1,4,7],[7,4,7]]}]
```

## 适用场景
- `line`：边框、扶手、檐口、柱。
- `points`：灯具、铆钉、把手、按钮。
