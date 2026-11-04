# M2 结果 · 沉筏、筏心修复、落盘与重启恢复

环境：本机 dev 服务端（nbw 0.1.0 + sable 2.0.6 + carpet 1.4.147），MC 1.21.1。
日期：2026-11-05。全部由代理自主测试完成，未依赖真人操作。

---

## 1. 沉筏（01 §4.3.1 A3-v4）—— 通过

`tools/sink_probe.py` **15/15**。

| 断言 | 实测 |
|---|---|
| 下沉幅度 | 103.98 → 94.49，**9.50 格**，约 1 秒完成 |
| 是否贴底 | 池底 y=93，筏子稳定在 **94.41↔94.54** 之间摆动 14 秒 |
| 是否穿模 | 否（final Y 94.49 > 池底） |
| 对照 | 未判沉的队 1 筏全程 103.98，纹丝不动 |

### 实现口径的两次纠正（都是实测逼出来的）

1. **裸常数加速度不成立。** `applyLinearImpulse` 的注释写 `[N]`，它是**力**不是冲量
   （`RigidBodyHandle.java:115-121`）。`mass×0.05` 不到重力的 1%，筏子不动；
   `mass×30` 把筏**压穿池底石头掉到 Y=51**。
   → 改为"有效重量倍数"，重力取 `DimensionPhysicsData.getGravity(world,pos,dest)`（:57-62），
   换维度自动正确。倍数上限 **4.0**（6.0 实测穿地）。
2. **"触底即停压"是错的。** 停压 6 秒后浮力把筏子从 94.5 顶回水面 104.1——
   沉到一半又浮回去比不沉更糟。SUNK 是本局终态，所以**持续施加**，
   触底只记一次日志。穿模风险改由倍数上限拦。

另：Sable **没有任何质量写入口**（`MassTracker` 只有 getter、`RigidBodyHandle` 只有冲量/速度/传送、
全仓 `setMass|gravityScale|massMultiplier` 零命中），所以"加大重量"只能用等效力实现。

---

## 2. 筏心摧毁与残骸修复（01 §4.3.2 A2 + A2-v2）—— 通过

`tools/core_probe.py` **17/17**，分两局跑（SUNK 不可逆、每局额度只有 1 次，同局连测会互相吃掉前提）。

| 断言 | 实测 |
|---|---|
| 摧毁筏心 → 倒计时开始 | 队0 `COUNTDOWN` ✓（A2 第二条路径成立） |
| 按住 80 tick | 进度到 60/160，筏心仍是残骸 |
| 松手 3 秒 | 进度**归零** |
| **一边引导一边受伤** | 进度峰值只到 **11/160**，反复归零，始终修不好；且**不消耗额度** |
| 连续引导 160 tick | 筏心 `INTACT`、倒计时变 **IDLE（解除，不是重置）**、额度 1/1 |
| 额度用完再摧毁 | 命令明确回"本局修复额度已用完（1 次）"，不静默失败 |

判定全部下沉成零 MC 纯函数 `domain/raft/ChannelRule`（15 个单测，含"两人交替引导永远凑不满"
这条反绕过后门）。真机用 `/cww raftcore hold` 驱动：它只替换"客户端长按"这个输入源，
服务端推进/打断/额度/解除走的是与客户端接入后**完全相同**的一条路径。

### 我自己写错的两处

1. `hold` 最初在命令里自己循环调 `step()`：既与正常 tick 循环**重复推进**，
   又会在 RCON 线程里 sleep **卡住整个服务端**。改成只登记"模拟到第 N tick"。
2. 探针第一版把假玩家打死了（4×11 伤害 > 20 血），之后"找不到在线玩家"造成 4 条**假失败**；
   而且该局倒计时已走完到 SUNK，"恢复解除倒计时"在那一局里永远测不出来。伤害降到 1.0 并拆成两局。

---

## 3. 回合状态落盘与重启恢复（Gate G1 最后一条）—— 通过

`tools/restart_probe.py` **before 3/3 + after 7/7**。

```
重启前  phase=PLAY match=1 tickInPhase=1  arena=flat_test format=test_3min  参战 4 人
启动日志 [state] 已恢复到阶段 PLAY（match=1，阶段内 3 tick，参战 4 人）
重启后  phase=PLAY match=1 tickInPhase=50 arena=flat_test format=test_3min
        T0 赤队 [k0, k1]   T1 蓝队 [K2, k3]        审计尾部 3 条完整
```

新增 `domain/round/RoundSnapshot`（纯数据 + `validate()`）与 `round/RoundStateStore`
（原子写 tmp+ATOMIC_MOVE、`ABSENT/OK/INVALID` 三态、字段级错误）。每次阶段转移自动落盘，
另有 `/cww save` 与 `/cww statefile`。

### 这一条里踩到的三个真错

1. **快照必须记 arena/format 身份。** 第一版没记，重启时加载的是默认 format（4×3），
   快照是 2×2，`restore()` 的规格校验**正确地拒绝恢复**——看起来是守卫的锅，
   实际是快照缺了"这一局用的是哪套配置"。加上 `arenaId/formatId` 并把 `loadConfigs`
   提到 `restore` 之前才成立。
2. **队伍骨架必须构造时就建。** `ensureTeams()` 原本是懒的，新机器快照里 `teams` 为空，
   于是同一个校验又把正常快照判成非法。改成构造时调用（它本来就幂等），
   顺带让 `/cww state` 在 LOBBY 就能看到队伍而不是 `teams=0`。
3. **测试机也要设身份。** `formatId` 为空时存储层拒绝恢复是对的，
   但我的测试没模拟 `loadConfigs` 的那一步，暴露的是测试不完整而不是产品 bug。

### 落盘层的三条硬要求（写进代码注释）

- 原子写：长期服随时可能被 kill，半截 JSON 比没有 JSON 糟糕得多。
- "文件不存在"与"文件读坏了"必须分开处理：前者是正常首次启动，后者必须 ERROR 并留在 LOBBY，
  **绝不"尽力恢复"**。
- 写入型数据只进 `<world>/cww/state/`，绝不碰 `arenas/` 与 `formats/`（03 §5 的分离要求）。

---

## 3.5 开局发筏（M2-2）—— 通过

`tools/raftgen_probe.py` **17/17 一次过**。READY 阶段给每支有人的队生成一艘初始 sub-level 筏。

| 断言 | 实测 |
|---|---|
| 尺寸来自配置 | `limits.initialRaftRadius=3` → 49 格甲板 → 质量 **24.5**（49×0.5，`#sable:light`） |
| 落点 | 两队都在出生锚点，Y=149.50（flat_test 水面 149） |
| 命名 | `nbw_raft_<队>`，`/sable info @e` 直接可查 |
| 加载票 | 2 张，类型是 **`nbw:round_active`** 而不是蹭 Sable 的 `command_forced` |
| 玩家落位 | 4 个假玩家 Y 都在 149（在筏面上，不在水里） |
| **收局残留** | 筏全部销毁、票全部释放、`[S10] residue=CLEAN` |

设计取舍：
- **用 `allocateNewSubLevel` + 往 plot 铺方块，不用 `assembleBlocks`。** 装配要求世界上已有这些方块，
  而开局要的是"凭空造一艘形状可控、可复现的筏"；而且装配在水上还会踩连通域漏水的坑（T0）。
- **`initialRaftRadius` / `initialRaftBlock` 放进 arena 的 `limits`，不放 format。**
  筏子多大是"这张图"的空间事实，赛制只管时间与人数（03 §2 的字段归属）。两个字段都是可选的，
  缺省走 `DEFAULT_RADIUS=3`，所以旧的 arena 文件不会被这次改动打废。
- **先建锚点→再发筏→最后传人。** 顺序反了玩家会落进水里等筏子凭空出现。
- **`plot 网格已满` 翻译成可读失败而不是崩对局。** `allocateNewSubLevel` 满了抛
  `IllegalStateException`，16384 槽位对长期服是真实约束，不是假想。

回归确认：发筏接进 READY 之后，`m1_round.py` 2 局 + abort 路径 **58/58、0 异常日志**，
残留复检跨 3 局仍然 CLEAN；`sink_probe` 15/15、`core_probe` 17/17 也都没被影响
（core_probe 现在能看到队 1 也有绑定的筏，正是自动生成生效的迹象）。

---

## 3.6 筏心方块本体（M2-4 前半）—— 通过

`tools/raftgen_probe.py` **18/18**、`core_probe` **18/18**、`sink_probe` 15/15。

### 这轮回答了一个之前没人验证过的关键问题

**自定义方块实体在 Sable 的 sub-level plot 里到底会不会被 tick？**

答案是：**原版那条路走不通，Sable 留了专用通道。**
`ServerLevelPlot.tick()` 只跑光照与 contraption 清理，**完全不 tick 方块实体**。
每 tick 行为的唯一入口是实现 Sable 的 `BlockEntitySubLevelActor`——
`LevelPlot.java:410-416` 靠 `instanceof` 决定要不要收进 actor 表，
再由 `ServerSubLevel.java:299`（游戏 tick）与 `SubLevelPhysicsSystem.java:226`（物理 tick）调用。
所以 **BE 必须自己实现那个接口**，中间加适配器不会被注册。

实测证据：`[content] 筏心登记（BE 已被 Sable tick）` 出现 2 次（每队一条）。

### 因此记下一条架构例外

"全工程只有 `sable/` 允许 import Sable"这条纪律，现在有一处**刻意的例外**：
`content/RaftCoreBlockEntity` 实现 `BlockEntitySubLevelActor`。
例外只到"实现接口"为止——接口方法内部要调的 Sable 能力仍然走 `sable/`。
这是接口约束不是设计偷懒，写进代码注释而不是藏着。

### 质量算式顺手成了方块真进 plot 的证据

初始筏质量从 **24.5 变成 25.5** = 49 格木板×0.5 + 筏心 1.0（完整方块默认质量）。
筏心是用 `plot.getEmbeddedLevelAccessor().setBlockState()` 写在 **plot 局部坐标 (0,1,0)** 的，
原版 `setBlockState` 负责创建方块实体。

### 一个差点埋进产品的坐标陷阱

BE 在自己 tick 里看到的 `getPos()` 是 **plot 局部坐标**（实测 `x=20481032` 这种量级），
而交互回调给的 pos 属于哪个坐标空间**没有保证**。两套空间一旦错位，
表现是"玩家怎么点都没反应"，而且日志里什么都不会报。
所以 `onUse` 改成**从玩家实际点到的那个 BE 读归属**，不再按坐标查表。

### 两个我主动没写的东西

1. **`onBroken` 没写。** 1.21.1 的 Yarn 里它有两个归属不明的重载
   （`(DamageSource, World, BlockPos)` 与 `(WorldAccess, BlockPos, BlockState)`），
   而"武器打掉筏心"本来要等 M4 的武器表。破坏只留一个入口 `RaftCoreService.markDestroyed`，
   方块状态由 `syncState` 每 tick 回写。与其猜个签名把逻辑钉死在错的钩子上，不如等伤害来源出现再挂。
2. **`onUseWithItem` 没写。** 1.21.1 把右键拆成空手 `onUse(state,world,pos,player,hitResult)`
   与拿东西 `onUseWithItem(stack,...)`。带物品那一半和客户端长按周期包是同一个问题的两面
   （玩家真在修的时候手里往往有工具），分开写会各测一半，所以一起等客户端包时补。

### 新增的可断言证据

`/cww raftcore status` 现在带 `状态回写 N 次`。没有它，"倒计时在走但方块看着还是好的"
这种不一致在日志里完全看不出来，玩家也无从判断该不该去修。

---

## 4. Q9 · Sable sub-level 跨重启（顺带拿到数据）

造一艘筏、推开、上加载票、重启：

| 项 | 重启前 | 重启后 |
|---|---|---|
| 位置 | 400.0 **104.155** 400.715 | 400.0 **103.976** 400.715 |
| 质量 | 24.5 | 24.5 |
| 速度 | (0, −0.064, 0.008) | (0, 0, 0)（重新沉降稳定） |
| 加载票 | `sable:command_forced` | **仍然存在** |
| 是否自动载入内存 | — | 是（`/sable info @e` 直接能找到） |

两条结论：
1. sub-level 的位置与形态跨重启**保真**，`storage find` 也能按名字查到。
2. **加载票会跨重启存活** —— 这是运维隐患：我们的 `nbw:round_active` 票如果结算时漏收，
   筏子会被永久钉在内存里，重启也不会释放。收票必须做成局后清理的**断言项**而不是"记得做"。

（早先担心的 `sub_level_velocity_retained_on_load=0.9` 会让筏子重启后"窜一下"，
实测影响很小：保留的是保存瞬间的速度，而沉底/静止时那个速度本就接近 0。）

---

## 5. 当前测试资产

| 脚本 | 覆盖 | 最近结果 |
|---|---|---|
| `tools/m1_round.py` | 回合骨架端到端 + 残留复检 | 5 局 120/120 |
| `tools/g1_guards.py` | 坏配置拒绝 + 归档 + 不崩服 | 9/9 |
| `tools/sink_probe.py` | 沉筏 | 15/15 |
| `tools/core_probe.py` | 筏心摧毁 / 8s 修复 / 受伤打断 / 额度 / 状态回写 | 18/18 |
| `tools/restart_probe.py` | 回合状态落盘 + 重启恢复 | before 3/3, after 7/7 |
| `tools/raftgen_probe.py` | 开局发筏 + 票/筏零残留 + BE 被 tick | 18/18 |
| 单测 | domain 纯函数 6 个类 | **118 个，0 失败** |

## 6. 仍需真人验证（攒着，等客户端输入接上一起测）

- 真实长按右键 → 自定义 keepalive 包（目前只有服务端等价路径被验证过）
- 修复进度条 HUD 与视觉反馈
- 沉筏过程的观感（筏子入水是否自然、残骸是否可辨认）
- 假玩家不触发的复活路径（`[C21] respawn` 在演练里恒为 0）
