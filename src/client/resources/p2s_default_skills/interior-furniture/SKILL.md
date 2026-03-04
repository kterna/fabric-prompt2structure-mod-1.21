---
name: "内饰与桌椅组件"
description: "内饰、桌椅与功能家具的可组合模板。基于 box/plane/line/points 输出局部构件。"
---

# 内饰与桌椅组件

## 使用目标
- 处理室内细节：桌、椅、床、收纳、灯具、厨卫与户外家具点位。
- 输出“可重复实例化”的局部构件动作片段。

## 读取方式
1. 先读取本文件确定家具类别。
2. 再用 `read_subdoc` 按类别读取模板。
3. 使用局部坐标模板，整体平移到目标房间。

## 子文档索引
- `subdocs/table.md`
- `subdocs/chair.md`
- `subdocs/bed.md`
- `subdocs/sofa.md`
- `subdocs/storage.md`
- `subdocs/kitchen.md`
- `subdocs/bathroom.md`
- `subdocs/lighting.md`
- `subdocs/outdoor.md`

## 组件复用规范
- 组件先按局部坐标定义（原点为组件锚点）。
- 放置时通过坐标平移实例化到目标位置。
- 旋转只用 90 度离散处理（同步修正 `facing`）。

## 联动建议
- 若需要更复杂框架、曲线或几何体组件，读取 `component-library` 并用 `read_subdoc` 获取目标模板。
