---
name: "日式通用"
description: "和风民居/庭院通用语汇与构造模板。"
---

# 日式通用

## 核心特征
- 关键词：低重心、木构节奏、檐口外挑。
- 默认风格：偏民居/庭院，不走宗教庄严路线。
- 色调：自然木色 + 浅色墙面 + 深色屋顶。

## 材料与配色
- 木构：`dark_oak_planks`, `spruce_planks`, `stripped_oak_log`
- 墙面：`white_concrete`, `calcite`
- 屋顶：深色屋顶材（楼梯/台阶类映射）
- 点缀：`lantern`, `bamboo`（少量）

## 构图建议
- 横向展开优先于纵向拔高。
- 屋檐投影应明显，建议外挑 `1-2` 格。
- 柱网节奏保持均匀，开间重复有序。

## 动作偏好
- `line` 表达柱梁。
- `plane:outline` 表达檐口边界。
- `box:walls` 做主体围护，再开口门窗。

## 基础模板
```json
[
  {"type":"box","block":"main","mode":"walls","from":[0,1,0],"to":[12,5,10]},
  {"type":"line","block":"pillar","from":[0,1,0],"to":[0,5,0]},
  {"type":"line","block":"pillar","from":[12,1,0],"to":[12,5,0]},
  {"type":"plane","block":"eave","axis":"y","mode":"outline","from":[-1,6,-1],"to":[13,6,11]},
  {"type":"plane","block":"floor","axis":"y","mode":"solid","from":[0,0,0],"to":[12,0,10]}
]
```

## 不建议
- 避免使用高饱和大面积色块。
- 避免把屋顶做得过陡、过厚导致失真。
