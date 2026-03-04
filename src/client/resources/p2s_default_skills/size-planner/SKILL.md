---
name: "尺寸规划规则"
description: "建筑占地、层高、开间进深与分层比例规则。用于把用户需求转换为可执行尺寸参数。"
---

# 尺寸规划规则

## 使用目标
- 将“大小、层数、紧凑/开阔”这类描述转换为明确尺寸。
- 输出可直接落到 `box/plane/line/points` 的尺寸参数。

## 读取方式
1. 先读取本文件获取尺寸流程。
2. 再通过 `read_subdoc` 读取具体尺寸模板。
3. 按工作区边界和用户硬约束修正后再生成 patch。

## 子文档索引
- `subdocs/footprint-presets.md`：占地预设与长宽比。
- `subdocs/floor-height.md`：层高、楼板厚度、总高估算。
- `subdocs/proportion-rules.md`：体量比例与屋顶高度规则。
- `subdocs/zoning-grids.md`：开间进深与网格分配。
- `subdocs/scaling-and-fit.md`：超界缩放与边界适配。
- `subdocs/quick-size-recipes.md`：常见建筑尺寸速查模板。

## 冲突处理
- 用户明确尺寸 > 本 skill 建议值。
- 风格 skill 若要求特殊比例，可覆盖本 skill 默认比例。
