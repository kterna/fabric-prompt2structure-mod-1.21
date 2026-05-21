# 方块实体 NBT 安全模板

本页只描述可给 agent 开放的安全方块实体模板和值域。方向、墙上/地上/顶上、旋转等属于 block state，见 `subdocs/block-state-capabilities.md`，不要写成 NBT。不要直接输出原始 NBT；只有补丁工具 schema 明确提供方块实体模板字段时，才使用这些模板。

## 通用规则
- 模板必须绑定到已有放置动作的坐标，先放置方块状态，再应用方块实体模板。
- 方块 ID 和方块状态属性仍由 `palette` / action `block` 决定；模板只写方块实体数据。
- 所有枚举必须按本页白名单校验，不能让模型生成任意字符串。
- 不认识的模板、字段、枚举值、物品 ID、注册表 ID 必须拒绝或忽略，不能透传为 NBT。

## 通用枚举
`dye_color` 用于旗帜图案和告示牌文字颜色：

```text
white
orange
magenta
light_blue
yellow
lime
pink
gray
light_gray
cyan
purple
blue
brown
green
red
black
```

## 告示牌文字
适用方块：`*_sign`、`*_wall_sign`、`*_hanging_sign`、`*_wall_hanging_sign`。

可开放模板：

```json
{
  "type": "sign_text",
  "front": {
    "lines": ["Line 1", "Line 2", "", ""],
    "color": "black",
    "glowing": false
  },
  "back": {
    "lines": ["", "", "", ""],
    "color": "black",
    "glowing": false
  },
  "waxed": false
}
```

校验规则：
- `front` 和 `back` 分别对应 NBT 的 `front_text` / `back_text`。
- `lines` 固定最多 4 行；每行必须是纯文本，由执行器转换为文本组件。
- `color` 必须是 `dye_color`。
- `glowing` 对应 `has_glowing_text`，只能是布尔值。
- `waxed` 对应 `is_waxed`，只能是布尔值。
- 不开放 `filtered_messages`，不开放任意 JSON text component。

## 旗帜图案
适用方块：`*_banner`、`*_wall_banner`。

可开放模板：

```json
{
  "type": "banner_patterns",
  "layers": [
    { "pattern": "stripe_bottom", "color": "red" }
  ]
}
```

校验规则：
- `layers` 最多 6 层。
- `color` 必须是 `dye_color`。
- `pattern` 必须是下列枚举之一。
- 执行器应默认拒绝 `base` 出现在 `layers` 中；底色应由 `white_banner` / `red_wall_banner` 等方块 ID 决定。
- 不开放原始 `patterns` NBT，不开放任意 pattern 字符串。
- 暂不开放 `CustomName`；如果后续需要，只能走纯文本 `custom_name` 模板。

```text
base
square_bottom_left
square_bottom_right
square_top_left
square_top_right
stripe_bottom
stripe_top
stripe_left
stripe_right
stripe_center
stripe_middle
stripe_downright
stripe_downleft
small_stripes
cross
straight_cross
triangle_bottom
triangle_top
triangles_bottom
triangles_top
diagonal_left
diagonal_up_right
diagonal_up_left
diagonal_right
circle
rhombus
half_vertical
half_horizontal
half_vertical_right
half_horizontal_bottom
border
curly_border
gradient
gradient_up
bricks
globe
creeper
skull
flower
mojang
piglin
flow
guster
```

## 头颅
适用方块：`*_head`、`*_skull`、`*_wall_head`、`*_wall_skull`。

建议初期只开放非常窄的模板：

```json
{
  "type": "skull_label",
  "custom_name": "Display name"
}
```

约束：
- `custom_name` 对应 NBT 的 `custom_name`，必须是纯文本，由执行器转换为文本组件。
- 暂不开放 `profile`。玩家头 profile 会牵涉 UUID、签名纹理 properties、网络查询和隐私/伪造风险。
- 暂不开放 `note_block_sound`，除非执行器从声音事件注册表提供独立白名单。

## 雕纹书架
适用方块：`minecraft:chiseled_bookshelf`。

可作为第二阶段模板：

```json
{
  "type": "chiseled_bookshelf_slots",
  "slots": [
    { "slot": 0, "item": "minecraft:book" },
    { "slot": 1, "item": "minecraft:written_book" }
  ],
  "last_interacted_slot": 1
}
```

校验规则：
- `slot` 和 `last_interacted_slot` 只能是 `0` 到 `5`。
- `item` 必须属于 `bookshelf_book_item`。
- 执行器必须同步设置方块状态 `slot_0_occupied` 到 `slot_5_occupied`，不能只写 `Items`。
- 暂不开放书本内容、附魔内容或任意 ItemStack NBT。

`bookshelf_book_item`：

```text
minecraft:book
minecraft:written_book
minecraft:enchanted_book
minecraft:writable_book
minecraft:knowledge_book
```

## 讲台
适用方块：`minecraft:lectern`。

讲台 NBT 的核心字段是 `Book` 和 `Page`，但 `Book` 是 ItemStack，1.21 起书本内容使用物品组件。初期不要开放原始 `Book`。

可作为后续安全模板：

```json
{
  "type": "lectern_book",
  "book": "minecraft:written_book",
  "title": "Title",
  "author": "P2S",
  "pages": ["Page 1"],
  "page": 0
}
```

校验规则：
- `book` 只能属于 `lectern_book_item`。
- `page` 从 0 开始，必须小于页数。
- `pages` 只接受纯文本，由执行器转换为书本组件。
- 执行器必须同步设置方块状态 `has_book=true`。
- 暂不开放任意 ItemStack NBT。

`lectern_book_item`：

```text
minecraft:written_book
minecraft:writable_book
```

## 容器、熔炉、营火、蜂巢
这些方块都有方块实体数据，但不建议第一阶段开放。

容器类包括 `minecraft:chest`、`minecraft:trapped_chest`、`minecraft:barrel`、`minecraft:hopper`、`minecraft:dispenser`、`minecraft:dropper`：
- 常见 NBT 有 `Items`、`CustomName`、`Lock`，部分容器还有 loot table。
- 初期不开放 `Items`、`Lock`、loot table、任意物品 NBT。
- 若后续开放，只能走固定槽位、固定物品 ID、固定 count 上限的 ItemStack 模板。

熔炉类包括 `minecraft:furnace`、`minecraft:smoker`、`minecraft:blast_furnace`：
- 常见 NBT 有 `Items`、燃烧/烹饪进度和配方相关数据。
- 初期不开放；外观通常用方块状态 `lit` 就足够。

营火包括 `minecraft:campfire`、`minecraft:soul_campfire`：
- 常见 NBT 有 `Items`、`CookingTimes`、`CookingTotalTimes`。
- 初期不开放；外观通常用方块状态 `lit`、`signal_fire`、`waterlogged`。

蜂巢包括 `minecraft:beehive`、`minecraft:bee_nest`：
- 常见 NBT 有 `bees` 和 `flower_pos`，其中 `bees` 包含实体数据。
- 初期不开放；外观通常用方块状态 `honey_level`。
