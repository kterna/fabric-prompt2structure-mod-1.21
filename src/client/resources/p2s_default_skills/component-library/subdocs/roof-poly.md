---
name: "多边形/圆锥屋顶"
description: "通过分层缩减多边形近似圆锥、穹顶等多边形屋顶。"
---

# 多边形/圆锥屋顶

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 原理
- 底部放置最大多边形环
- 每层向上收缩半径，直到顶点
- 边数决定形状：4=金字塔、8=八角塔、≥16≈圆锥

## 圆锥塔楼屋顶（r=4, h=6, 近似圆）
```toml
[[operation]]
op = "insert_part"
part = "roof_poly_cone"

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[4, 0, 0], [3, 0, 3], [0, 0, 4], [-3, 0, 3], [-4, 0, 0], [-3, 0, -3], [0, 0, -4], [3, 0, -3]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[3, 1, 0], [2, 1, 2], [0, 1, 3], [-2, 1, 2], [-3, 1, 0], [-2, 1, -2], [0, 1, -3], [2, 1, -2]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[3, 2, 0], [2, 2, 2], [0, 2, 3], [-2, 2, 2], [-3, 2, 0], [-2, 2, -2], [0, 2, -3], [2, 2, -2]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[2, 3, 0], [1, 3, 1], [0, 3, 2], [-1, 3, 1], [-2, 3, 0], [-1, 3, -1], [0, 3, -2], [1, 3, -1]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[1, 4, 0], [0, 4, 1], [-1, 4, 0], [0, 4, -1]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[0, 5, 0], [0, 6, 0]]
```

## 八角塔楼屋顶（r=5, h=4, sides=8）
```toml
[[operation]]
op = "insert_part"
part = "roof_poly_octagon"

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[5, 0, 0], [4, 0, 4], [0, 0, 5], [-4, 0, 4], [-5, 0, 0], [-4, 0, -4], [0, 0, -5], [4, 0, -4]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[3, 1, 0], [2, 1, 2], [0, 1, 3], [-2, 1, 2], [-3, 1, 0], [-2, 1, -2], [0, 1, -3], [2, 1, -2]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[2, 2, 0], [1, 2, 1], [0, 2, 2], [-1, 2, 1], [-2, 2, 0], [-1, 2, -1], [0, 2, -2], [1, 2, -1]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[1, 3, 0], [0, 3, 1], [-1, 3, 0], [0, 3, -1]]

[[operation.actions_add]]
type = "points"
block = "roof"
at = [[0, 4, 0]]
```

## 穹顶变体
穹顶与圆锥的区别在于每层半径缩减速率：穹顶先缓后急（球面曲线），圆锥等速缩减。

## 迁移说明
- 对应外部项目 `drawPolyRoof`（支持 cone/dome/curve/steep 样式）。
- 不同样式通过调整每层半径的缩减曲线来实现。
