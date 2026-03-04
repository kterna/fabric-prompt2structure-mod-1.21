---
name: "建筑风格规则"
description: "不同建筑风格的体块特征、材料倾向与屋顶/开窗语汇。用于风格化约束。"
---

# 建筑风格规则

## 使用目标
- 给出“风格 -> 形体与材料约束”的可执行映射。
- 在不改变动作协议的前提下，控制风格一致性。

## 读取方式
1. 先确定目标风格关键词。
2. 用 `read_subdoc` 读取对应风格卡片。
3. 再结合 `size-planner` 输出尺寸，和 `interior-furniture` 输出室内细节。

## 子文档索引
- `subdocs/modern-minimalist.md`
- `subdocs/modern-skyscraper.md`
- `subdocs/modern-eco.md`
- `subdocs/medieval-castle.md`
- `subdocs/medieval-rustic.md`
- `subdocs/medieval-gothic.md`
- `subdocs/japanese-general.md`
- `subdocs/japanese-shrine.md`
- `subdocs/chinese-royal.md`
- `subdocs/chinese-garden.md`
- `subdocs/nordic-viking.md`
- `subdocs/cyberpunk.md`
- `subdocs/steampunk-industrial.md`
- `subdocs/desert-egyptian.md`
- `subdocs/rustic-farmhouse.md`
- `subdocs/gothic-noir.md`
- `subdocs/classical-roman.md`
- `subdocs/japanese-castle.md`
- `subdocs/japanese-vernacular.md`
- `subdocs/fantasy-floating.md`
- `subdocs/fantasy-magic.md`
- `subdocs/fantasy-nature.md`
- `subdocs/underwater-atlantis.md`
- `subdocs/type-landscape.md`
- `subdocs/type-statue.md`
- `subdocs/type-vehicle.md`

## 风格一致性检查
- 同一建筑主材不超过 2-3 类。
- 立面开窗节奏保持一致，避免随机孔洞。
- 屋顶形式与主体风格一致，不混用冲突语汇。

## 与其他 skill 的关系
- 尺度由 `size-planner` 提供。
- 家具和内饰由 `interior-furniture` 提供。
- 复杂几何与构件模板由 `component-library` 提供（通过 `read_subdoc` 按需读取）。
