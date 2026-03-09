---
name: "螺旋楼梯"
description: "中心柱 + 台阶点位的螺旋楼梯离散模板。"
---

# 螺旋楼梯

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 局部模板
```toml
[[operation]]
op = "insert_part"
part = "stairs_spiral"

[[operation.actions_add]]
type = "line"
block = "core"
from = [0, 0, 0]
to = [0, 6, 0]

[[operation.actions_add]]
type = "points"
block = "step"
at = [[1, 0, 0], [1, 1, 1], [0, 2, 1], [-1, 3, 1], [-1, 4, 0], [-1, 5, -1], [0, 6, -1]]
```

## 说明
- 适合半径 1-2 的紧凑楼梯。
- 扩大半径时，把 `points` 沿环向外推并增加层级点。
