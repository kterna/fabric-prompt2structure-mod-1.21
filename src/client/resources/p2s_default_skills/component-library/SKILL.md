---
name: "组件库与高级示例"
description: "移植并改写的组件模板库。通过 read_subdoc 按需读取墙体、框架、屋顶、几何体、楼梯、散布等示例。"
---

# 组件库与高级示例

## 使用方式
1. 先用 `read_skill` 读取本技能，拿到 `subdocs` 路径列表。
2. 按任务选择对应路径，再用 `read_subdoc` 读取细节模板。
3. 将模板中的 `block` key 映射到当前 `palette` 后，写入 `propose_patch.patch_toml` 的 `[[operation]]` / `[[operation.actions_add]]` 等 TOML 表结构。

## 子文档索引
- `subdocs/box-modes.md`：实心、空壳、四面墙。
- `subdocs/plane-modes.md`：平面与轮廓面。
- `subdocs/line-and-points.md`：线段与点集细节。
- `subdocs/hollow-frame.md`：线框框架（仅棱边）。
- `subdocs/solid-frame.md`：实心框架（有厚度梁柱）。
- `subdocs/window-module.md`：窗组件模板。
- `subdocs/door-module.md`：门洞组件模板。
- `subdocs/roof-gable.md`：双坡屋顶模板。
- `subdocs/roof-hip.md`：四坡屋顶模板。
- `subdocs/stairs-spiral.md`：螺旋楼梯模板。
- `subdocs/geometry-cylinder.md`：圆柱近似模板。
- `subdocs/geometry-sphere.md`：球体近似模板。
- `subdocs/geometry-pyramid.md`：金字塔模板。
- `subdocs/geometry-polygon.md`：多边形体模板。
- `subdocs/geometry-torus.md`：环形体近似模板。
- `subdocs/curve-bezier.md`：曲线离散模板。
- `subdocs/scatter-patterns.md`：散布点位模板。
- `subdocs/geometry-ellipsoid.md`：椭球体近似模板。
- `subdocs/hanging-decor.md`：悬挂装饰模板（单点/环形）。
- `subdocs/roof-poly.md`：多边形/圆锥屋顶模板。
- `subdocs/block-state-capabilities.md`：方向、安装形态和常用方块状态枚举。
- `subdocs/block-entity-templates.md`：方块实体 NBT 安全模板、枚举和值域。

## 约束
- 仅使用 `box/plane/line/points`。
- 禁止 `fill/frame/set`。
- 示例坐标均为局部坐标，使用时请整体平移到目标位置。
- 提交补丁时使用 `patch_toml` 的 TOML 格式，不使用旧 JSON `operations` 数组。
- 方向、墙上/地上/顶上等形态属于 block state；优先调用 `describe_block_state` 查询目标方块的真实属性和值域，再读取 `subdocs/block-state-capabilities.md` 做通用规则参考，不要把它们写成 NBT。
- 只有工具 schema 明确支持方块实体模板时，才使用 `subdocs/block-entity-templates.md`；否则不要输出原始 NBT 或自造字段。
