---
name: "环形体近似"
description: "主环路径 + 横截面点位的近似构造。"
---

# 环形体近似

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 主环路径（节选）
```toml
[[operation]]
op = "insert_part"
part = "torus_ring"

[[operation.actions_add]]
type = "points"
block = "ring"
at = [[6, 0, 0], [4, 0, 4], [0, 0, 6], [-4, 0, 4], [-6, 0, 0], [-4, 0, -4], [0, 0, -6], [4, 0, -4]]

[[operation.actions_add]]
type = "points"
block = "ring"
at = [[6, 1, 0], [4, 1, 4], [0, 1, 6], [-4, 1, 4], [-6, 1, 0], [-4, 1, -4], [0, 1, -6], [4, 1, -4]]

[[operation.actions_add]]
type = "points"
block = "ring"
at = [[6, -1, 0], [4, -1, 4], [0, -1, 6], [-4, -1, 4], [-6, -1, 0], [-4, -1, -4], [0, -1, -6], [4, -1, -4]]
```

## 迁移说明
- 对应外部项目 `drawTorus`，该实现偏向视觉近似而非严格数学体。
