# M0 实机测试方案（Sable 2.0.6 源码定稿版）

来源：`C:\Users\殷子竣\Desktop\sable-main`（只读，783 个 java 文件）。
本文所有语法都带 `文件:行`，**不接受"文档/注释说"**——Sable 自己的 javadoc 有三处与实际 builder 不符，见 §1.4。

交叉验证：源码注册的顶层字面量正好 12 个（`SableCommand.java:42-77` + 三个 register 调用），
与用户客户端 Tab 补全截图完全一致 → 命令树定稿，M0-3 的 RCON 探测结论作废。

---

## 1. 命令树（定稿）

### 1.1 顶层 `/sable`（需权限等级 2，`SableCommand.java:40`）

| 子命令 | 参数序列 | 证据 |
|---|---|---|
| `assemble` | `area <from> <to>` | `SableAssembleCommands.java:75-78` |
| | `connected [<from> [<capacity>]]` 缺省 from=脚下方块、capacity=256000 | `:28, :80-88` |
| | `sphere <radius 0..256> [<origin>]` | `:90-94` |
| | `cube <range 0..256> [<origin>]` | `:96-100` |
| `assemble shatter` | `sub_level <sl>` / `connected` / `sphere` / `cube` / `area <from> <to>` | `:47-73` |
| `spawn` | `platform <size 1..32> [block] [name]` | `SableSpawnCommands.java:125-129` |
| | `block [block] [name]` / `grid <n 1..32>` / `sphere <r 2..200>` | `:120, :114, :95` |
| | `jenga <height 1..256> [name]` / `clone <single_sl> [name]` | `:89, :92` |
| | `schematic <name>`（命名空间固定 `sable`，读 `schematics/*.nbt`） | `:101-104, :271` |
| | `joint_test` / `rope_test` / `slope_test [name]` | `:106-112` |
| `name` | `set <sl> <name>` / `clear <sl>` / `get <single_sl>` | `SableSubLevelCommands.java:46-56` |
| `teleport` | `<targets> <destination> [<angle>]` —— 会先 `resetVelocity` | `:58-66`, `:158` |
| `remove` | `<targets>` | `:68-70` |
| `physics` | `impulse <sl> linear\|angular <x y z> [global\|local]` | `SablePhysicsCommands.java:44-69` |
| | `rotation <sl> add\|set axis <x y z> <angle> [global\|local]` | `:70-73`, `:157-178` |
| | `rotation <sl> add\|set entity <rot> [global\|local]` | 同上 |
| | `translation <sl> add <x y z> [global\|local]` | `:79-89` |
| | `translation <sl> set <vec3>` | `:91-93` |
| `info` | `<sl>` → 名字/UUID/位置/姿态/质量/线速度/角速度 | `SableCommand.java:76-77`, `:200-252` |
| `paused` | `[bool]`，无参则翻转 | `:63-66` |
| `forceload` | `add <sl>` / `query` / `remove <sl>` | `:68-75` |
| `engage_gizmo` | 无参，**必须真人玩家**，会 `setPaused(true)` | `:60-61`, `:94-102` |
| `storage` | `prune_holding_chunks` / `find_all_sub_levels` / `find <name>` | `SableStorageCommands.java:32-173` |
| `debug` | `joint add <a> <b> rotary ...` / `config <见 §3>` / `udp_test` | `SableCommand.java:48-57` |

### 1.2 sub-level 选择器（等价于 `@e` 之于实体）

`@<type>` + 可选 `[k=v,k=v]`（`SubLevelArgumentType.java:208-244`）；也可以直接写 UUID。

| 选择器 | 语义 | 证据 |
|---|---|---|
| `@e` | 全部（多选） | `SubLevelSelectorType.java:6` |
| `@n` | 离命令源最近的**一个** | `:7`, 实现 `SubLevelTargetSelector.java:55-70` |
| `@r` | 随机一个 | `:8` |
| `@v` | **视线指向的那个**（`pick(100.0, 1.0, true)`） | `:9`, `:96-113` |
| `@l` | 最后创建的那个 | `:10`, `:114-120` |
| `@t` | **命令源实体正在骑乘/贴合的那个 sub-level** | `:11`, `:83-95` |
| `@i` | **包含命令源位置的那个 sub-level** | `:12`, `:75-82` |

修饰符（`SubLevelSelectorModifiers.java:52-142`，全部 `k=v` 形式）：
`name=` `sort=nearest|furthest` `limit=<int>`
`distance= x= y= z= dx= dy= dz= vx= vy= vz= speed= mass= volume= width= height= length=`
其中 range 型支持 `MinMaxBounds` 语法（`5..10`、`..3`、`2..`）。
求值顺序按优先级 POSITION → FILTER → SORTING → SORTING_SELECTION（`SubLevelSelectorModifierType.java:72-77`）。

**`@t` / `@i` 是本次测试最重要的两条**：它们让我们**不写一行代码**就能断言
"服务器认为这个玩家站在哪艘筏上" —— 这正是 M0-4 的核心命题。

### 1.3 参数类型的坑

- `physics impulse / translation add / rotation axis` 用 `Vec3ArgumentAbsolute` → **只收绝对数字，`~` `^` 一律报错**（`Vec3ArgumentAbsolute.java:31-48`）。
- `teleport <destination>`、`physics translation set` 用 `Vec3Argument.vec3(false)` → **`~` 可用**。
- `name set` 的 `<name>` 是 `StringArgumentType.string()` → 带空格要加引号。

### 1.4 Sable 自身 javadoc 的三处错误（记进方法论）

| javadoc 声称 | 实际 | 证据 |
|---|---|---|
| `/sable shatter area` | `/sable assemble shatter area` | `SableAssembleCommands.java:37-41` vs `:46-47` |
| `/sable config <property>` | `/sable debug config <property>` | `SableConfigCommands.java:20` vs `SableCommand.java:48-51` |
| `/sable joint add` | `/sable debug joint add` | `SableJointCommands.java:31` vs `SableCommand.java:50` |

→ 纪律：**读 builder 的 `.then()` 链，不读方法注释。**

---

## 2. 移动/随动链路（源码结论，决定"卡顿"的三种成因怎么分）

```
Rapier 物理步进（每 game tick 做 substeps 次，默认 2 → 40Hz）
   └→ ServerSubLevel.logicalPose() 更新
        ├→ ServerPlayerMixin.tick() HEAD：把 lastPose→pose 的刚体增量直接加到玩家 setPos
        │    （mixin/entity/entities_stick_sublevels/player/ServerPlayerMixin.java:30-51）
        ├→ ServerEntityMixin.sendChanges：把玩家位置换算成 sub-level 局部坐标再发原版移动包
        │    （ServerEntityMixin.java:41-69）—— 防止客户端把"筏的位移"重复算一次
        └→ SubLevelTrackingSystem.sendMovementUpdates：每 tick 批量发快照
             （SubLevelTrackingSystem.java:261-400）
             · 阈值 0.015/16 格 ≈ 0.00094 格、0.015° 才会发（:273）→ 动起来基本每 tick 都发
             · 优先 UDP（:365-378），连不上回退 TCP bundle（:379-395）
```

三条硬结论：

1. **随动是 Sable 原生做的，不需要我们自己搬玩家。** 上一轮自研的"位移骑手"逻辑（旧仓库 RaftPrototype）在 Sable 路线上应当整段删除。
2. 客户端流畅度取决于 **快照插值**，不是我们发多少包。手感问题的排查顺序 = ①物理抖动（substeps/iterations）②UDP 是否生效 ③渲染路径（Sodium / 原版二选一，`AbstractSableMixinPlugin.java:43-49`）。
3. **`@t` 解析成功 = 服务器认定你在筏上**；解析失败 = 贴合系统没生效，此时任何"随动"都不可能存在，M0-4 直接判负，不必再测后面。

另外确认到的、Sable 已自带且我们原计划要自研的东西（全部在 `mixin/` 下，默认开启，只有 `compatibility.*` 与 `sublevel_render.impl.*` 有条件门（`AbstractSableMixinPlugin.java:47-63`））：

`block_placement`（在 sub-level 上放置/建造）、`fluids_on_sub_levels`、`climbing_sub_levels`、
`interaction_distance`、`sign_interaction`、`enchanting_table`、`conduit`、`respawn_point`/`player_standup`(床)、
`portal`、`explosion`、`impact`、`punching`、`recoil`、`world_border`、`entity_pathfinding`（怪物在筏上寻路）、
`entity_leashing`、`arrows_hit_blocks`、`trident`、`sculk_vibrations`、`stop_lightning`、`stop_rain`。

装配时**方块实体数据是保真的**：`SubLevelAssemblyHelper.java:380-415` 用 `saveWithFullMetadata` 存 tag、
在新 plot 里 `loadWithComponents` 回读、再清掉世界里的原 BE。→ M0-5 从"能不能实现"降级为"实测复核"。

---

## 3. 运行时可调旋钮（不重启即可 A/B）

`/sable debug config`（`SableConfigCommands.java:23-93`，即时生效，`physicsSystem.onConfigUpdated()`）：

| 属性 | 默认 | 作用 |
|---|---|---|
| `substeps` | 2 | 每 tick 物理步数（1..10，`SableServerConfig.java:16-19`）——**平滑度主旋钮** |
| `solver_iterations` | 18 | 约束求解迭代 |
| `stabilization_iterations` | 2 | |
| `pgs_iterations` | 2 | |
| `contact_spring_natural_frequency` | 40.0 | 接触弹簧 |
| `contact_spring_damping_ratio` | 5.0 | 接触阻尼——**抖动/弹跳主旋钮** |
| `min_island_size` | 128 | 独立体最小动态刚体数 |

`config/sable.toml`（需重启，`SableConfig.java:27-70`）：
`sub_level_tracking_range`(320) `attempt_udp_networking`(true) `disable_udp_pipeline`(false)
`sub_levels_with_players_cannot_unload`(true) `sub_level_splitting`(true)
`sub_level_punch_strength_multiplier`(2.1) `sub_level_punch_downward_strength_multiplier`(0.175)
`sub_level_punch_cooldown_ticks`(3) `sub_level_velocity_retained_on_load`(0.9)

`config/sable-server.toml`：`sub_level_substeps_per_tick`(2) `sub_level_storage_pruning`(false)

数据驱动（datapack，我们的 P-01 数值范式的落点）：
- `data/sable/physics_block_properties/*.json`：`{"selector":"#sable:light","properties":{"sable:mass":0.5},"overrides":{"type=double":{...}}}`
- `data/sable/floating_materials/*.json`：浮力材质 `lift_strength` `transition_speed` `slow/fast_vertical_friction` `slow/fast_horizontal_friction` `prevent_self_lift`
- 内置方块标签：`light super_light normal heavy super_heavy weightless fragile slippery frictive bouncy half_volume quarter_volume`
- 加载器：`PhysicsBlockPropertiesDefinitionLoader.java:32-40`（folder `physics_block_properties`）、`FloatingBlockMaterialDataHandler.java:29-33`（folder `floating_materials`）

→ **木筏"多重、浮多高、多快沉"是可配置的，不用改代码。** 这一条把 P-01 提案从"要不要写控制器"变成"先用数据+impulse 试，再决定要不要闭环控制器"。

---

## 4. 测试步骤

> 前置：启动器实例 `dist/` 里放 `sable` + `fabric-api`（Veil、companion、rapier 已由 Sable jar-in-jar 自带，实测确认）
> 记录方式：每个 T 记 ①命令原文 ②返回文本 ③你的主观描述。视频另存。

### T0 · 建立唯一可寻址的筏（M0-4 前置）

**实机已证伪 `connected`**：水上的筏装配必报 `Couldn't assemble sub-level, too many blocks (maximum 256000)`。

根因（不是容量问题）：
- 连通判定只看 `!state.isAir()` → **水算连通方块**（`SubLevelAssemblyHelper.java:251-253`）
- 命令层传 `frontierPredicate = null`，不做任何材质过滤（`SableAssembleCommands.java:106, :332`）
- BFS 按 6-邻边扩散、对角不连（`:236-240`）→ 从筏板直接漏进整片海，瞬间爆上限

→ 这是**玩法级约束**，不只是测试障碍：水上的筏永远不能用 `connected` 装配。

两条出路：

```
# 出路 1（推荐，保留箱子才能测 T4）：严格包围盒，盒子必须完全避开海水
/sable assemble area <minX> <minY> <minZ> <maxX> <maxY> <maxZ>
# 例：筏板 97..103 / y=64 / 97..103，箱子在 100 65 100
/sable assemble area 97 64 97 103 65 103

# 出路 2（最快，但没有箱子）：直接生成干净的 sub-level 平台，完全跳过装配
/sable spawn platform 3 oak_planks raft
```

⚠️ `assemble area` 会把**包围盒内所有方块**搬进 plot 并在世界置 AIR
（`executeAssembleAreaCommand:278-291` 用 `betweenClosedStream` 不过滤 + `moveBlocks:409/452`）
→ 盒子里只要含水，海里就会留下一个洞、筏上还多一块"水砖"。**盒子必须只框住筏体。**

装配成功后：
```
/sable info @l
/sable name set @l raft
```
- 期望 `info` 回显方块数、位置、质量；`@l`（最后创建的那个）此时应唯一。
- 正式方案（M2，写在 `sable/` 隔离层）：直接调
  `SubLevelAssemblyHelper.gatherConnectedBlocks(origin, level, capacity, frontierPredicate)`，
  第 4 参传一个拒绝流体的 `FrontierPredicate`（签名 `SubLevelAssemblyHelper.java:496-508`）。
  Sable 留了这个口子，只是它自己的命令没用。


### T1 · 随动绑定（M0-4 的生死线，30 秒）—— ✅ 已过

站上筏，然后：
```
/sable info @t
```
- **`@t` 解析出该筏 = 通过。** 它读 `getTrackingSubLevel(player)`，正是 `ServerPlayerMixin` 每 tick
  施加刚体增量的前提；它通了，整条随动链路就活着。
- ~~`@i` 也必须通~~ —— **这条要求是我写错的**。`getContaining` 实际是**按 chunk 槽位反查 plot**
  （`ActiveSableCompanion.java:92-104` → `container.getPlot(chunkX>>4, chunkZ>>4)`），
  而 plot 在 16384 槽 plot 网格中占的槽位与它的世界坐标没有必然对应关系，
  所以人明明站在筏上，`@i` 照样可以空返回。**`@i` 不是"我站在什么上面"的查询，不要拿它做判据。**
  实测：`@t` 通、`@i` 空 → 筏随动完全正常。
- `@t` 不通 → 贴合判定（碰撞/`onGround`）没触发，**M0-4 判负，直接走 §5 决策分叉**，后面的手感测试全都不用做。

### T2 · 位移基线：两种驱动对比（M0-4 手感）—— ✅ 已过（用户判定顺畅）

实测结论：`/sable physics impulse @n linear 0 0 12` 连打 3 次，移动顺畅、人货不分离，
**没有复现旧自研方案的跳格感** → 物理驱动路线成立，B 对照组（`translation add`）不必再跑。

A) 物理驱动（我们玩法要的样子）
```
/sable physics impulse @n linear 0 0 12
/sable info @n
```
B) 瞬移驱动（对照组，旧自研方案的样子）
```
/sable physics translation @n add 0 0 6 global
/sable info @n
```

判据：
- A：筏连续滑行，人**不掉队、不被甩、不穿筏**，视角随筏转动（`entities_turn_with_sub_levels/GameRendererMixin.java:40`）。
- B：预期就是跳格感 —— 它是**对照组**，不是失败。A 明显优于 B 即证明"物理驱动路线成立"。
- 记录 A 里"从推出到停"的 tick 数与格数：`/sable info @n` 连打 5 次即可粗测速度衰减曲线（这决定 §3 的 substeps 是否要提）。

### T3 · 抖动归因（把"卡顿"拆开）—— ⏸ 本轮跳过（T2 已顺畅），留作回归手段

按顺序做，每步只改一个变量，对比 T2-A 的录屏：

1. `/sable debug config substeps 4` → 再看 `/sable debug config substeps 8`
   - 明显变顺 → 成因是**物理步数不足**，属可调项，不构成路线否决。
2. 若仍抖：`/sable debug config contact_spring_damping_ratio 12`（默认 5.0）
   - 变稳 → 成因是**接触抖动**。
3. 若仍抖：查 UDP。
   ```
   /sable debug udp_test
   ```
   并看日志里 `SableUDPServer` 是否握手成功；再把 `config/sable.toml` 的 `disable_udp_pipeline` 设 true 复测一次。
   - UDP 没通而 TCP 回退 → 20Hz 快照 + 包序抖动，**这才是"卡顿不顺畅"最可能的真因**。
   - 注意：UDP 需要服务端可达端口，**公网服务器上这是个运维成本项**，要进 04-依赖与运维。
4. 若客户端装了 Sodium（我们 dist/ 没装）：Sable 走 Sodium 渲染 mixin 分支（`AbstractSableMixinPlugin.java:43`）。dist/ 保持"仅 Sable+Fabric API"，先排除这个变量。

### T4 · 移动中的方块实体交互（M0-5）—— ✅ 全过

实测三条全部通过：
1. **箱子数据没丢，能正常放回取出**（移动中开箱亦可）
2. **筏上掉落物随船移动**
3. **水面没有漫过甲板**（§"顺带发现"里担心的干舷≈0 在 50 格尺度上不成立，暂不需调浮力）

### T5 · 移动中建造（M0-6）—— ✅ 已过（建造作用在筏上，非世界）

筏在滑行中放置橡木木板 → 质量随之上升、新块随筏移动；破坏甲板块 → 质量回落。
→ **"边航行边建造"成立**，M2 不需要退化成"靠岸建造"两段式（`02-风险与M0验证.md` Q4 的退路作废）。

### T5b · `shatter` 的真实语义（**不是"靠岸拆解"**）

重读源码：`shatterBlockToSubLevel` 对**每一格**单独调
`assembleBlocks(level, anchor, List.of(anchor), bounds)`（`SableAssembleCommands.java:256-272`）
→ `shatter` = **碎块化**：每个实心方块变成一个**独立的单格 sub-level**（漂浮碎片），
非实心方块则 `level.destroyBlock(pos, true)` 掉落物品（`:242-246`）。
它**不会**把筏"写回世界方块"，所以它跟"靠岸"没有任何关系。

**玩法归属已定（用户裁定）**：`靠岸拆解 / 结算毁筏` **不是我们的机制，删除**。靠岸不需要毁筏。
内容里真正定的是 **沉筏**（`docs/content/01-玩法设计.md:107`、`02-数值与节奏.md:102`：
无幸存者且玩家死亡 → 30s 后沉没 → 淘汰）。
`shatter` 的恰当用途是**击碎后的残骸 / 漂浮碎片**（对应 `01-玩法设计.md:143` 的"沉没残骸需潜水打捞"），
局后清理走 `container.removeSubLevel(...)`，那是工程清理不是玩法。

### T6 · 边界与破坏性（M0-7 提前扫一遍）

```
/sable paused true      → 筏应定住；玩家能否走动
/sable paused false
/sable forceload add @n → /sable forceload query
/sable teleport @n ~ ~ ~  （对照 /sable remove @n 后的世界状态）
```
- `remove` 会把 plot 写回世界还是删除？源码走 `container.removeSubLevel(..., REMOVED)`（`SableSubLevelCommands.java:191`）→ **实测必须确认**，这直接决定"筏被击沉"的表现。
- 记录一次 TPK：故意让筏从高处砸向地面，看玩家是否受伤/筏是否碎裂（`impact`/`explosion` mixin 在场）。

### T7 · 多人（M0-9 公平性）

需要第二个客户端。当前可先用 `dist/` 起一个局域网世界验证 2 客户端：
- 两人在同一艘筏上，`/sable info @n` 各自看线速度是否一致。
- 两人各一艘筏，`/sable physics impulse @n linear 0 0 12` 同参驱动，**测同规格筏航速方差**（P-01 里提的公平性指标，>5% 就要在 M2 前定死补偿方案）。

### T8 · 崩溃恢复（M0-8）

筏移动到一半 `/stop`（或强杀进程），重启世界：
- 筏位置/形态是否恢复（走 storage 系统：`SableStorageCommands.java` + `plot.serialization.ChunkMapMixin`）
- 玩家是否被卡进方块 / `@t` 是否失效
- `sub_level_velocity_retained_on_load`=0.9 → 重启后筏应带 90% 速度，**观感上会"突然窜一下"**，记下来评估是否要关。

---

## 5. 判定与决策分叉

| 结果 | 决策 |
|---|---|
| T1 `@t` 失败 | Sable 的贴合系统不认玩家 → **换路线**：Eureka!（需退 1.20.1）或自研+移植其平滑方案。不要试图在 Sable 上补丁。 |
| T2 A≈B（都卡） | 物理驱动没带来平滑收益 → M0 判负，走上一条同样的分叉。 |
| T3 归因成功且可调顺 | M0-4 通过，记录最终 substeps/iterations/damping 进 §3 表，作为 M2 默认值。 |
| T4 箱子不可用 | M4 生产层改为"筏上岸才有储物"，或我们自建 BE（工作量大，需重新排期）。 |
| T5 新块进世界 | M2 的"边航行边建造"取消，改为"靠岸建造"。玩法要回头改 01-玩法设计。 |
| 全通过 | M0 完成，进 M1；P-01 按"数据+impulse"版本批准，闭环控制器暂不排期。 |

## 6. 内容层边界：Sable 给钩子、不给内容

Sable 仓库**零 Item / 零 Block 注册 / 零配方**，是纯库模组。但它把我们玩法要的四个执行件都留成了**接口**（我们自己实现）：

| 我们的玩法件 | Sable 接口 | 证据 |
|---|---|---|
| 帆（含多块拼成一面帆） | `BlockSubLevelLiftProvider` —— 升力/阻力/升力中心，邻接块 flood-fill 自动成组 | `api/block/BlockSubLevelLiftProvider.java:22`, `:45-70`, `:211-241` |
| 桨 / 螺旋桨 | `BlockEntityPropeller` + `BlockEntitySubLevelPropellerActor`（皆接口，无实现） | `api/block/propeller/`（整目录只有 2 个 .java，全 `interface`） |
| 转向 | `BlockEntitySubLevelReactionWheel`（角动量） | `api/block/BlockEntitySubLevelReactionWheel.java:11` |
| 任意 BE 对筏施加行为 | `BlockEntitySubLevelActor` | `api/block/BlockEntitySubLevelActor.java:13` |
| 浮力 / 水面 | `FloatingBlockController` + `data/sable/floating_materials/*.json` | `physics/floating_block/FloatingBlockController.java` |

→ 对 P-01 的影响：**"推力/航速/转向速率"这套数值范式正好落在这些接口上，不需要我们先造闭环控制器。**
帆=升力系数、桨=冲量源、转向=角动量，物理积分与平滑由 Sable 负责。M2 先用 `impulse` + 数据验证航速手感，闭环控制器留到有"航速不达标"证据时再排期。

## 7. 硬上限与必须避开的坑（实测前要心里有数）

| 项 | 值 / 表现 | 影响 |
|---|---|---|
| `/sable` 根节点权限 | `hasPermission(2)`（`SableCommand.java:40`） | 普通玩家用不了；我们的玩法命令层要自己注册，不能套 `/sable` |
| `engage_gizmo` | 进入即 `setPaused(true)`（`:98`） | 它是调试工具，**不是**玩法交互入口 |
| `sub_level_tracking_range` | 320 格 | 超出即不再收快照 → 筏"消失/定格"，缩圈远距离观战要显式处理 |
| `connected` 装配上限 | 256,000 块（可传 `capacity`，上限 ×100） | 大筏一次装配没问题 |
| `shatter` 半径 | `cube/sphere` ≤128 | 碎块化的范围上限（残骸用，非玩法机制） |
| `sub_level_remove_min/max` | Y −10000 / 100000 之外回收 | 筏沉入深处的表现 |
| `sub_level_velocity_retained_on_load` | 0.9 | 重载后筏会带 90% 速度 → **重启会"窜一下"**，见 T8 |
| **装配后不能用世界坐标寻址容器** | 方块写进 plot，`/data modify block <原坐标>` 失效 | T4 必须在**装配前**塞箱子内容；结算/战利品逻辑要改走 plot API |

## 8. 待我（代理）后续核实清单

- [ ] `SubLevelHelper` 全量签名 + `SubLevelAssemblyHelper.assembleBlocks` 的 anchor/包围盒语义（决定我们程序化造筏怎么写）
- [ ] `api/sublevel/KinematicContraption` —— 若它是"非物理、纯跟随"通道，P-01 的控制器选项再多一条
- [ ] `mixin/world_border/WorldBorderMixin` —— 原版世界边框是否对 sub-level 生效；生效则缩圈机制可直接复用
- [ ] `api/sublevel/ticket` + `SubLevelTicketLoadingSystem` —— 回合系统需显式给筏上加载票，否则离开视野会 unload
- [ ] `BlockSubLevelLiftProvider` 的 `LiftProviderContext` 字段（面积/法向/攻角怎么给）—— M2 做帆之前必须读通
- [ ] Sable Beyond 实际新增内容（Mechanical Hand、流体施力）—— 决定是否进 dist/ 依赖
