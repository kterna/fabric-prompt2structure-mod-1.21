---
name: "散布模板"
description: "以 points 表达 2D/3D 散布，适合植被和小装饰。"
---

# 散布模板

以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。

## 地面散布
```toml
[[operation]]
op = "insert_actions"
part = "scatter_ground"

[[operation.actions_add]]
type = "points"
block = "plant"
at = [[1, 0, 1], [3, 0, 2], [5, 0, 1], [2, 0, 4], [6, 0, 5], [4, 0, 6]]
```

## 立体散布
```toml
[[operation]]
op = "insert_actions"
part = "scatter_3d"

[[operation.actions_add]]
type = "points"
block = "lantern"
at = [[1, 3, 1], [4, 4, 2], [7, 3, 4], [3, 5, 6]]
```

## 使用建议
- 先固定随机种子，在外部计算点位后再写入 `points`。
- 散布对象超过 80 个点时，分组为多个 action 以减少单次 patch 冲突。
