---
name: "line 与 points"
description: "梁柱、轮廓线与细节点位的基础模式。"
---

# line 与 points

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 梁柱
```toml
[[operation]]
op = "insert_part"
part = "line_beam_pillar"

[[operation.actions_add]]
type = "line"
block = "pillar"
from = [0, 0, 0]
to = [0, 5, 0]

[[operation.actions_add]]
type = "line"
block = "beam"
from = [0, 5, 0]
to = [8, 5, 0]
```

## 点位细节
```toml
[[operation]]
op = "insert_part"
part = "points_lights"

[[operation.actions_add]]
type = "points"
block = "light"
at = [[1, 4, 1], [7, 4, 1], [1, 4, 7], [7, 4, 7]]
```

## 适用场景
- `line`：边框、扶手、檐口、柱。
- `points`：灯具、铆钉、把手、按钮。
