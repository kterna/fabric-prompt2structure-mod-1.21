# 方块状态能力

本页描述方向、安装形态和常用 block state 规则。它们不是 NBT。生成时应写入 palette 的完整 block state 字符串，或使用 action 的 `facing` 覆盖已有 `DirectionProperty`。

## 精确查询优先
生成具体方块前，优先调用 `describe_block_state(block_id)` 查询 registry 中真实存在的属性、默认值和允许值。本文中的枚举只作为通用参考，不能替代工具返回结果。

## 写法
推荐在 palette 中写完整方块状态：

```toml
[[operation.entry]]
key = "wall_sign_north"
new_value = "minecraft:oak_wall_sign[facing=north,waterlogged=false]"

[[operation.entry]]
key = "standing_sign_east"
new_value = "minecraft:oak_sign[rotation=4,waterlogged=false]"
```

只有 4/5/6 向 `facing` 属性能用 action 的 `facing` 快速覆盖。`rotation=0..15`、`face=floor|wall|ceiling`、`attachment=*` 这类属性必须写在 palette 的 block state 中。

## 成对方块锚点
门和床是成对方块，但生成时只放置一次：

```toml
[[operation.entry]]
key = "oak_door_north"
new_value = "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"

[[operation.entry]]
key = "red_bed_north"
new_value = "minecraft:red_bed[facing=north,part=foot]"
```

使用规则：
- 门只在底部锚点放一次；执行器会自动生成 `half=lower` 和上方的 `half=upper`。
- 床只在尾部/脚部锚点放一次；执行器会自动生成 `part=foot` 和 `facing` 方向上的 `part=head`。
- 门和床优先使用 `points` 单点动作，不要用两条 action 手写 upper/head。
- 不要用 `box`、`plane`、`line` 批量铺门或床，除非明确想让每个点都展开成一扇门或一张床。

## 方向枚举
`horizontal_facing_4`：

```text
north
east
south
west
```

`facing_6`：

```text
down
up
north
east
south
west
```

`hopper_facing_5`：

```text
down
north
east
south
west
```

注意：hopper 没有 `up`。

## 16 格旋转
`rotation_16` 用于地面 sign、地面 banner、地面 skull/head，以及部分悬挂告示牌：

```text
0
1
2
3
4
5
6
7
8
9
10
11
12
13
14
15
```

近似方向约定：

```text
0=south
4=west
8=north
12=east
```

需要 8 向时可使用偶数值：`0,2,4,6,8,10,12,14`。需要更细角度时使用全部 16 值。

## 安装形态
很多“墙上/地上”不是属性，而是不同 block id：

```text
standing sign: minecraft:<wood>_sign
wall sign: minecraft:<wood>_wall_sign
ceiling hanging sign: minecraft:<wood>_hanging_sign
wall hanging sign: minecraft:<wood>_wall_hanging_sign
standing banner: minecraft:<color>_banner
wall banner: minecraft:<color>_wall_banner
standing skull/head: minecraft:<mob>_head 或 minecraft:<mob>_skull
wall skull/head: minecraft:<mob>_wall_head 或 minecraft:<mob>_wall_skull
standing torch: minecraft:torch, minecraft:soul_torch, minecraft:redstone_torch
wall torch: minecraft:wall_torch, minecraft:soul_wall_torch, minecraft:redstone_wall_torch
```

生成时先选安装形态对应的 block id，再选方向属性。

## 常见方块
Sign：
- 地面：`minecraft:<wood>_sign[rotation=0..15,waterlogged=false]`
- 墙上：`minecraft:<wood>_wall_sign[facing=north|east|south|west,waterlogged=false]`
- 文字内容不在 block state；文字属于方块实体模板 `sign_text`。

Door：
- `minecraft:<wood>_door[facing=north|east|south|west,half=lower,hinge=left|right,open=false,powered=false]`
- 只放 lower 锚点；upper 由执行器自动生成。

Bed：
- `minecraft:<color>_bed[facing=north|east|south|west,part=foot]`
- 只放 foot 锚点；head 在 `facing` 方向自动生成。

Hanging sign：
- 顶挂：`minecraft:<wood>_hanging_sign[rotation=0..15,attached=false,waterlogged=false]`
- 墙挂：`minecraft:<wood>_wall_hanging_sign[facing=north|east|south|west,waterlogged=false]`
- `attached` 是布尔值。

Banner：
- 地面：`minecraft:<color>_banner[rotation=0..15]`
- 墙上：`minecraft:<color>_wall_banner[facing=north|east|south|west]`
- 图案不在 block state；图案属于方块实体模板 `banner_patterns`。

Stairs / slab：
- 楼梯：`minecraft:<material>_stairs[facing=north|east|south|west,half=bottom|top,shape=straight|inner_left|inner_right|outer_left|outer_right,waterlogged=false]`
- 台阶：`minecraft:<material>_slab[type=bottom|top|double,waterlogged=false]`
- `shape` 通常由相邻楼梯连接决定；如果只是生成基础台阶/楼梯，默认用 `shape=straight`。

Glass pane / iron bars：
- 玻璃板：`minecraft:<color>_stained_glass_pane[north=true|false,east=true|false,south=true|false,west=true|false,waterlogged=false]`
- 普通玻璃板：`minecraft:glass_pane[north=true|false,east=true|false,south=true|false,west=true|false,waterlogged=false]`
- 铁栏杆：`minecraft:iron_bars[north=true|false,east=true|false,south=true|false,west=true|false,waterlogged=false]`
- 四向布尔值表示是否连接到对应方向；生成连续窗格时要按相邻连接写状态，或先用默认状态再通过实际测试修正。

Fence / wall / fence gate：
- 栅栏：`minecraft:<wood>_fence[north=true|false,east=true|false,south=true|false,west=true|false,waterlogged=false]`
- 墙：`minecraft:<material>_wall[up=true|false,north=none|low|tall,east=none|low|tall,south=none|low|tall,west=none|low|tall,waterlogged=false]`
- 栅栏门：`minecraft:<wood>_fence_gate[facing=north|east|south|west,open=false,powered=false,in_wall=false]`

Carpet / chain / lantern：
- 地毯：`minecraft:<color>_carpet`，通常无额外状态。
- 链条：`minecraft:chain[axis=x|y|z,waterlogged=false]`。
- 灯笼：`minecraft:lantern[hanging=true|false,waterlogged=false]` 或 `minecraft:soul_lantern[hanging=true|false,waterlogged=false]`。

Rail / pressure plate：
- 铁轨：`minecraft:rail[shape=north_south|east_west|ascending_east|ascending_west|ascending_north|ascending_south|south_east|south_west|north_west|north_east,waterlogged=false]`
- powered/detector/activator rail：同样有 `shape`，另有 `powered=true|false`，具体值以 `describe_block_state` 返回为准。
- 普通压力板：`minecraft:<material>_pressure_plate[powered=true|false]`。
- 轻/重质测重压力板：`minecraft:light_weighted_pressure_plate[power=0..15]`、`minecraft:heavy_weighted_pressure_plate[power=0..15]`。

Skull/head：
- 地面：`minecraft:<mob>_head[rotation=0..15]` 或 `minecraft:<mob>_skull[rotation=0..15]`
- 墙上：`minecraft:<mob>_wall_head[facing=north|east|south|west]` 或 `minecraft:<mob>_wall_skull[facing=north|east|south|west]`

Hopper：
- `minecraft:hopper[facing=down|north|east|south|west,enabled=true]`
- 不允许 `facing=up`。

Dispenser/dropper/furnace/barrel/chest/lectern/beehive/campfire：
- dispenser/dropper：`facing_6`，另有 `triggered=true|false`。
- furnace/smoker/blast_furnace：`horizontal_facing_4`，另有 `lit=true|false`。
- barrel：`facing_6`，另有 `open=true|false`。
- chest/trapped_chest：`horizontal_facing_4`，另有 `type=single|left|right`、`waterlogged=true|false`。
- lectern：`horizontal_facing_4`，另有 `has_book=true|false`、`powered=true|false`。
- beehive/bee_nest：`horizontal_facing_4`，另有 `honey_level=0..5`。
- campfire/soul_campfire：`horizontal_facing_4`，另有 `lit=true|false`、`signal_fire=true|false`、`waterlogged=true|false`。

## 通用属性枚举
`axis`：

```text
x
y
z
```

`face` 用于 button、lever、grindstone 等：

```text
floor
wall
ceiling
```

`half`：

```text
top
bottom
```

`slab type`：

```text
top
bottom
double
```

`stairs shape`：

```text
straight
inner_left
inner_right
outer_left
outer_right
```

`chest type`：

```text
single
left
right
```

`bell attachment`：

```text
floor
ceiling
single_wall
double_wall
```
