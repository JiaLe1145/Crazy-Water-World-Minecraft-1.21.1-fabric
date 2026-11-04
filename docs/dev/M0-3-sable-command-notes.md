# M0-3 补充 · Sable 命令词表（从 class 常量池提取，非文档）

来源：`javap -c` 提取 `dev/ryanhcode/sable/command/SableAssembleCommands` 与 `SableSubLevelCommands` 的字符串常量。

## `/sable assemble`（把世界里的方块装配成 sub-level）
可见字面量：`assemble`、`sub_level`、`origin`、`from`、`to`、`area`、`connected`、`cube`、`sphere`、`radius`、`range`、`capacity`、`shatter`

推断用法（**待实机确认，不要当结论**）：
- 按形状选区装配：`cube` / `sphere` / `connected`（连通域）/ `area`，配合 `origin`、`radius`、`range`、`from`→`to`
- `shatter` = 反向操作（拆回世界方块）→ **对应我们的"靠岸拆解/结算毁筏"**
- `capacity` 可能与 sub-level 容量/尺寸档位有关

## `/sable sublevel`（操作已存在的 sub-level）
可见字面量：`get`、`set`、`name`、`teleport`、`destination`、`angle`、`targets`、`remove`、`clear`

推断用法（**待实机确认**）：
- `get` / `set`：读写 sub-level 数据（`set` 配 `destination`、`angle` → **可以命令级移动与转向**，这正是 M0-5/6 需要的驱动入口）
- `name`：命名（我们给每艘筏一个名字/UUID 映射）
- `remove` / `clear`：移除 sub-level

## 关键架构发现：SubLevel 没有 setPose

`javap dev.ryanhcode.sable.sublevel.SubLevel` 只暴露 `logicalPose()` / `lastPose()` / `boundingBox()` / `tick()` / `getPlot()`，**没有任何设置位姿的方法**。
→ **Sable 的移动是物理驱动的**（力、约束、推进器 `api/block/propeller`），不是"把结构挪到某坐标"。

这条直接解释了旧计划为什么必然卡顿：我们做的是"瞬移方块 + 传送玩家"，而平滑来自"连续物理积分 + 客户端插值"。
→ 我们的玩法要按 Sable 的模型来设计：**桨/帆/电鳗发电机 = 施加力或推进器**，而不是"移动 N 格"。
数值文档里"单次位移格数"的表述需要在 M2 之前改成"推力/最高航速/转向速率"。

## 下一步（M0-5 / M0-6 的执行方式）

1. 用 `/sable assemble` 在 dev 世界里把一片方块装配成 sub-level（先靠 `execute as` + Carpet 假玩家，或直接由你在客户端 tab 补全确认真实语法）。
2. 用 `/sable sublevel set ... destination/angle` 驱动它连续移动，验证：
   - M0-5：移动后箱子可开、方块实体数据保真
   - M0-6：能否在移动中的结构上放置/破坏方块
3. M0-4（手感）由你判定：物理驱动的连续移动应该已经没有"跳格感"，但要看实际帧表现。
