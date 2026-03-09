---
name: "球体近似"
description: "通过多层环形轮廓近似球体。"
---

# 球体近似

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 半径 4 分层思路
- `y=0`：最大环。
- `y=±1`：次大环。
- `y=±2`：中环。
- `y=±3`：小环。
- `y=±4`：顶点。

## 示例（节选）
```toml
[[operation]]
op = "insert_actions"
part = "sphere_r4"

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[4, 0, 0], [0, 0, 4], [-4, 0, 0], [0, 0, -4]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[3, 1, 0], [0, 1, 3], [-3, 1, 0], [0, 1, -3], [3, -1, 0], [0, -1, 3], [-3, -1, 0], [0, -1, -3]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[0, 4, 0], [0, -4, 0]]
```

## 迁移说明
- 对应外部项目 `drawSphere/drawEllipsoid` 的近似思想。
