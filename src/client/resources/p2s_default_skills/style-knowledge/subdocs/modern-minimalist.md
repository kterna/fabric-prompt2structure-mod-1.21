---
name: "现代极简"
description: "现代极简的体量、材料、空间组织与模板。"
---

# 现代极简
以下示例均为可直接写入 `patch_toml` 的 `[[operation]]` 片段。


## 核心特征
- 关键词：少即是多、几何纯净、低装饰。
- 体块：立方体/长方体叠加，允许悬挑与退台。
- 屋顶：平屋顶或极低坡屋顶。

## 材料与配色
- 主材：`white_concrete`, `smooth_quartz`, `light_gray_concrete`
- 透明材：`glass`, `glass_pane`
- 对比线条：`black_concrete`, `deepslate`
- 点缀：原木色（控制在 10%-20%）

## 空间组织
- 一层偏开放：客厅、餐厅、厨房可连通。
- 二层偏私密：卧室与书房。
- 室内外联系：大开窗 + 露台平台。

## 动作偏好
- 大面优先 `box/plane`，减少碎片化动作。
- 线条控制用 `line`，灯带点位用 `points`。

## 立面模板
```toml
[[operation]]
op = "insert_actions"
part = "modern_minimalist_1"

[[operation.actions_add]]
type = "box"
block = "main"
mode = "walls"
from = [0, 1, 0]
to = [14, 6, 10]

[[operation.actions_add]]
type = "plane"
block = "glass_band"
axis = "z"
mode = "solid"
from = [2, 3, 0]
to = [12, 4, 0]

[[operation.actions_add]]
type = "line"
block = "dark_trim"
from = [0, 6, 0]
to = [14, 6, 0]

[[operation.actions_add]]
type = "plane"
block = "roof"
axis = "y"
mode = "solid"
from = [0, 7, 0]
to = [14, 7, 10]
```

## 不建议
- 避免大量曲线和复杂雕花。
- 避免材料种类过多（建议 <= 4 种主材）。
