# M0-3 补充 · Sable 命令词表（从 class 常量池提取，非文档）

> ⚠️ **整篇作废，仅作反面教材保留。** 定稿命令树见 `M0-TEST-PLAN.md` §1（逐行读 builder 的 `.then()` 链得出，且与客户端 Tab 补全的 12 个顶层子命令完全一致）。
> 本文下方两个表格都错：常量池提取的"推断用法"层级不对；后来那次"RCON 实测更正"**更错**——它把 `add`/`query`/`remove`/`sub_level`/`get`/`set`/`name`/`teleport` 这些**子节点字面量当成了顶层命令**（因为 `forceload add`、`forceload query`、`assemble shatter sub_level`、`name set/get` 都存在），而顶层 `assemble`/`info`/`teleport` 反而被判成"不存在"。
> 根因：RCON 客户端拿到的 Brigadier 错误文本**丢失插入符位置**，无法区分"缺参数"与"节点不存在"。**教训：命令语法只能从源码或客户端 Tab 补全取证，不能从服务端错误文本反推。**

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

---

# 更正与实机确认（2026-11-04 23:45，RCON 实测）

上面"推断用法"**部分是错的**，以实测为准：

## 真实的 `/sable` 子命令树（RCON 逐个探测）

| 命令 | 实测结果 |
|---|---|
| `/sable add` | 存在，需参数 |
| `/sable add assemble` | **存在，仍需参数**（装配入口在这里，不是 `/sable assemble`） |
| `/sable remove` | 存在 |
| `/sable query` | 存在，需参数 |
| `/sable sub_level` | 存在，需参数 |
| `/sable sub_level get` | 存在，需参数 |
| `/sable sub_level teleport` | **存在，需参数** → 命令级移动 sub-level 的入口 |
| `/sable engage_gizmo` | 存在，但 **「A player is required to run this command here」** → 交互式的拖拽/转向工具，**必须真客户端** |
| `/sable info` | ❌ 不存在（我上轮从常量池提取的字面量里混入了非命令字符串） |
| `/sable assemble`、`/assemble`、`/sublevel` | ❌ 均不存在 |

## 教训（写进方法论）
从 class 常量池提取字符串**只能当线索，不能当命令语法**——`SableCommand` 的常量里既有命令名也有参数提示语与调试开关名。
上一版笔记里的 `cube/sphere/connected/area/origin/from/to/radius/range/capacity/shatter` 是真实存在的字面量，
但**层级关系必须由客户端 Tab 补全或源码 builder 调用顺序确认**，不能猜。

## M0-5/6 的执行前提
装配需要正确的参数顺序。两条路：
1. **你在客户端打 `/sable add assemble ` 按 Tab**，把补全提示抄给我（1 分钟，最快）。
2. 我用 `javap -c` 逐条读 `SableAssembleCommands` 的 builder 调用顺序推断参数序列（可行但费时间，且可能仍错）。

另外 `engage_gizmo` 需要玩家 → 说明 Sable 的**主要交互方式就是给真人玩家的工具**，
这进一步支持"手感由你判定"这条纪律，也意味着我们的玩法 UI 未来可以直接复用它的 gizmo 或自建命令层。
