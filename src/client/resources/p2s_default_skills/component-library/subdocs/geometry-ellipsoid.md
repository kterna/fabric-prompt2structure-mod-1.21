---
name: "椭球体近似"
description: "通过多层变半径环形轮廓近似椭球体（三轴半径不同的球）。"
---

# 椭球体近似

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 与球体的区别
- 球体三轴相等 `rx = ry = rz`
- 椭球体三轴可不同：`ry < rx,rz` 扁平穹顶，`ry > rx,rz` 高蛋形
- 每层环形点位的半径按椭圆公式缩放

## 扁平穹顶（rx=5, ry=3, rz=5）分层思路
- `y=0`：最大环 r=5
- `y=±1`：r≈4.7
- `y=±2`：r≈3.3
- `y=±3`：顶点

## 示例（扁平穹顶，节选）
```toml
[[operation]]
op = "insert_part"
part = "ellipsoid_flat_dome"

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[5, 0, 0], [0, 0, 5], [-5, 0, 0], [0, 0, -5], [4, 0, 3], [3, 0, 4], [-4, 0, 3], [-3, 0, 4], [4, 0, -3], [3, 0, -4], [-4, 0, -3], [-3, 0, -4]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[5, 1, 0], [0, 1, 5], [-5, 1, 0], [0, 1, -5], [4, 1, 3], [3, 1, 4], [-4, 1, 3], [-3, 1, 4]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[3, 2, 0], [0, 2, 3], [-3, 2, 0], [0, 2, -3], [2, 2, 2], [-2, 2, 2], [2, 2, -2], [-2, 2, -2]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[0, 3, 0]]
```

## 高蛋形（rx=3, ry=6, rz=3）示例（节选）
```toml
[[operation]]
op = "insert_part"
part = "ellipsoid_tall_egg"

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[3, 0, 0], [0, 0, 3], [-3, 0, 0], [0, 0, -3], [2, 0, 2], [-2, 0, 2], [2, 0, -2], [-2, 0, -2]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[3, 1, 0], [0, 1, 3], [-3, 1, 0], [0, 1, -3]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[3, 2, 0], [0, 2, 3], [-3, 2, 0], [0, 2, -3]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[2, 3, 0], [0, 3, 2], [-2, 3, 0], [0, 3, -2]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[2, 4, 0], [0, 4, 2], [-2, 4, 0], [0, 4, -2]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[1, 5, 0], [0, 5, 1], [-1, 5, 0], [0, 5, -1]]

[[operation.actions_add]]
type = "points"
block = "shell"
at = [[0, 6, 0]]
```

## 迁移说明
- 对应外部项目 `drawEllipsoid` 的近似思想。
- 相比球体，需按三轴独立缩放每层半径。
