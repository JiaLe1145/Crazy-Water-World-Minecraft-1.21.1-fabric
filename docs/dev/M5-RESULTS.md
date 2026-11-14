# M5 结果记录（幸存者 + 事件 + 运营）

> 对应里程碑 `docs/dev/03-里程碑.md` §M5。
> 本文件只记「落地了什么 + 怎么验的」，设计口径看 `docs/content/01-玩法设计.md` §11。

---

## 5.1 事件池（2026-11-08）

### 落地

- `round/EventService`：读 `format.events`（`pool` + `intervalSec`），在 PLAY 里按随机间隔抽一发。
  - 调度：`begin` 排第一发；`onEndTick` 到点触发并重排；`reset` 局后清空。
- 已接**实际效果**的事件：`supply_rain`（补 4 个补给箱，复用 `LootService.spawn`）。
- 只广播、待后续接系统的事件：`storm` / `kraken` / `black_sea_surge` / `survivor_drift` / `bounty`。
- 命令：`/cww event`（池大小 / 已触发 / 上次事件 / 下一发倒计时）。

### 验证（起服实测，test_3min：pool=["storm"]，间隔 60–90s）

| 断言 | 实测 |
|---|---|
| 开局排第一发 | `[event] 池=1 已触发=0 下一发=70s 间隔=60-90s` |
| 到点触发 | 95s 后 `已触发=1 上次=storm 下一发=39s`；日志 `[event] 触发 storm` |
| 触发后重排 | 日志 `[event] 下一发 tick=…` |

### 还没做的

- **storm 的实际效果**：02 §12 的"远程精度 −50%、航行禁用、视野 −30%"需要风速/精度/视野三套判定。
- **kraken / black_sea_surge / bounty / survivor_drift**：依赖海怪、悬赏、幸存者系统（M5-2）。
- **事件权重**：现在等概率抽，没有按局内时间加权。

---

## 5.2 战绩统计与赛后面板（2026-11-08，M5-3 第一段）

### 落地

- `combat/CombatStats`：每人击杀 / 阵亡 / 造成伤害。
  - 击杀/阵亡：挂 `ServerLivingEntityEvents.AFTER_DEATH`（`source.getAttacker()` 认击杀者）。
  - 造成伤害：近战在 `WeaponService.onAttack`、远程在 `RangedWeaponItem.use` 里累计。
- SETTLE 阶段广播多行面板：`—— 本局战绩 ——` + 击杀王前三（击杀数 + 伤害）。
- 命令：`/cww stats`（击杀/阵亡读数）；`auditResidue` 里 `reset()`。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 开局无战绩 | `[stats] 本局暂无战绩` |
| 击杀记录 | `/damage … by attacker` 致死后 `击杀 st17570=1 ｜阵亡 …=1` |
| 赛后面板 | SETTLE 日志 `—— 本局战绩 ——` / `击杀王 1. st17570：击杀 1｜伤害 0` |

> 注：`/damage` 命令绕过了我们的 `onDamage` 钩子，所以面板里"伤害 0"；真实武器命中会累计。

### 还没做的

- **观战/回放**：M5-3 的观战视角、录像回放没做。
- **MVP 皮肤/舞台**：结算舞台（参考项目那种）按之前裁决不做。
- **伤害读数**：`/cww stats` 只列击杀/阵亡，没列伤害（面板里有）。

---

## 5.3 淘汰后观战（2026-11-08，M5-3 第二段）

### 落地

- 死亡瞬间（`AFTER_DEATH`）若在 PLAY：`changeGameMode(GameMode.SPECTATOR)`——不再直接踹回大厅。
- `placeByPhase` 修：淘汰玩家重生时也走 `SPECTATOR`（原来会按存活玩家摆到队伍锚点、给回生存模式）。
- SETTLE/RETURN 的 `placeInLobby` 仍会把所有人恢复成大厅状态。

### 验证

- 单测 247/0；起服无异常（死亡路径无 ERROR）。
- **限制**：Carpet 假玩家被 `player kill` 后不会重生，`@a[gamemode=spectator]` 抓不到；
  真客户端的死亡→重生→旁观视角需真人验收。

### 还没做的

- **观战 UI**：没有"正在观战 X"的提示、没有自由视角切换快捷键。
- **回放**：M5-3 的录像回放没做。

---

## 5.4 部署运维脚本（2026-11-08，M5-4）

### 落地

`ops/` 下三组脚本（每个 `.ps1` 配一个 `.bat` 包装，绕过 Windows 执行策略）：

| 脚本 | 作用 |
|---|---|
| `deploy.bat` | `gradlew build`（跳单测）→ 复制最新 `nbw-*.jar` 到服务端 `mods/` |
| `start.bat` | `java -jar server.jar nogui`，日志追加到 `logs/console-<日期>.log`，退出后延迟自动重启 |
| `backup.bat` | 压缩 `world/` + `config/` 到 `backups/world_<时间戳>.zip`，按 `-Keep` 轮换 |

- `.ps1` 存为 **UTF-8 with BOM**：否则 Windows PowerShell 5.1 按 ANSI 读中文注释，会把字符串里的
  全角括号误判成代码边界（实测 `deploy.ps1` 报 `Missing closing '}'`，加 BOM 后正常）。
- `ops/README.md` 写了用法、计划任务示例、恢复演练清单。

### 验证（本机实测）

| 断言 | 实测 |
|---|---|
| `deploy.bat`/`deploy.ps1` 部署 | `已部署：…\mods\nbw-0.1.0.jar（397.7 KB）` |
| `backup.ps1` 备份 + 轮换 | 生成 `world_*.zip`；连跑 4 次后恒为 `-Keep` 个 |
| `start.ps1` 缺 jar 报错 | `找不到服务端 jar：…\server.jar`（可读错误，不是语法崩） |

### 还没做的

- **RCON 预保存**：备份前没代发 `/save-all flush`（不依赖凭据），计划任务里自己先 save。
- **跨平台**：只有 Windows 脚本（`.bat` + `.ps1`）；Linux 部署脚本没写。

---

## 5.5 幸存者（2026-11-08，M5-2 第一段）

### 落地

- `round/SurvivorService`：**队级计数**（不做实体 NPC），先落"口粮压力 → 生产价值"这条闭环。
  - 开局每队按人数发幸存者，各带 `6 × 人数` 口粮缓冲。
  - 每秒扣 `人数 × 1.0/60` 口粮；断粮后每 30s 离队 1 人（广播 + 日志）。
  - **生产即口粮**：海水过滤器/厨台/堆肥农场每产出 1 份，给本队 `+1` 口粮。
- 接进 `RoundManager`：PLAY `begin` + tick `onEndTick` + `auditResidue` `reset`。
- 命令：`/cww survivor`（各队人数 + 口粮）。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 开局每队 1 人 + 6 口粮 | `队0 幸存者=1 口粮=6.0｜队1 幸存者=1 口粮=6.0` |
| 生产加口粮 | 过滤器产 1 份淡水 → `队0 口粮=7.0` |
| 口粮随时间下降 | 70s 后 `队0=5.8｜队1=4.8`（≈1.17/人/分钟） |

### 还没做的

- **实体 NPC**：幸存者还不是会走动/可招募的实体，只是队级计数。
- **岗位加成**：01 §9 的"生产链岗位"（幸存者给生产加速/自动产出）没接。
- **反偷家 ×0.25**：依赖幸存者实体，归后续。
- **招募**：海面漂流的幸存者 → 钩竿拉上筏（需要实体 + 交互）。

---

## 5.6 幸存者实体化第一段（2026-11-09）

### 落地

- `SurvivorService.begin` 在每队锚点旁放 **N 个村民占位**（N = 队伍人数）：
  `EntityType.VILLAGER`，`setAiDisabled(true)`（站桩不 wander）、`setInvulnerable(true)`（不被黑海/流弹误杀）、
  自定义名 `§a幸存者·队N`、`setPersistent()`。
- 断粮离队时同步 `discard()` 一个实体；`reset()`（RETURN）清掉全部实体。
- 队级计数/口粮逻辑不变（实体是"视觉/占位"，口粮仍按计数算）。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 开局每队放 1 个村民 | `@e[type=minecraft:villager]` 命中；名字 `§a幸存者·队0` |
| 队级计数不变 | `/cww survivor` → `队0 幸存者=1 口粮=6.0｜队1 幸存者=1 口粮=6.0` |
| RETURN 清场 | 回到 LOBBY 后 `@e[type=minecraft:villager]` 为空 |

### 还没做的

- **AI / 招募 / 岗位 / 反偷家**：村民现在是"站桩无敌占位"，不会走、不能被招募、不给加成。
- **贴图/模型**：直接用原版村民，没有自定义外观。

---

## 5.7 幸存者招募 + 生产岗位加成（2026-11-09）

### 落地

- **漂流幸存者**：`SurvivorService.spawnFloating(srv, n)` 在海上 20–70 格随机位置放村民（`§e漂流幸存者`）；
  由事件 `survivor_drift` 触发（每次 2 个）。
- **钩竿招募**：`RopeService.cast`（grapnel）**优先**检查 16 格内有没有漂流幸存者——
  有就招募（+1 人 + 筏上放 1 个实体 + 广播），没有才走原来的"拉敌方"逻辑。
- **人口上限**：`maxSurvivors = teamSize + 2`（02 §1 的 2/4/5 取简）。
- **生产岗位加成**（02 §9）：有幸存者的队，设施冷却 −30%（`SurvivorService.productionSpeedFor`），
  接进过滤器 + 厨台/堆肥。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 漂流幸存者生成 | `@e[type=villager,name="§e漂流幸存者"]` 有实体（位置 `-1063.69,62.5,997.84`） |
| 钩竿招募 | `cww grapnel cast` → `招募了 1 名漂流幸存者（队 0）` |
| 计数 +1 | `/cww survivor` → `队0 幸存者=1 → 2` |
| 招募后钩竿回退拉敌方 | 再 cast → `超出射程 16 格`（没可招募的就按原逻辑判敌方） |

### 还没做的

- **村民 AI/走动**：仍是站桩，不会跟玩家走。
- **反偷家 ×0.25**：还没接（需要"敌人靠近幸存者时减伤"的判定）。
- **招募动画/音效**：没有。

---

## 5.8 反偷家 ×0.25（2026-11-09）

### 落地

- `RaftCoreService` 新增 **筏心 HP**（02 §5：`CORE_HP = 200`）+ `hpOf(team)`；
  `breakCore(team, raw)` 扣血——**本队还有幸存者时 ×0.25**（01 §A3-v2 `ANTI_BACKSTAB`），
  血到 0 走既有 `markDestroyed`（残骸 + 30s 沉筏倒计时）。
- `SurvivorService.hasSurvivors(team)` 作为判定源。
- **真实入口**：`WeaponService` 挂 `AttackBlockCallback`——用武器左键**敌方**筏心时按
  `damage × coreMultiplier` 破拆（自家筏心不打、冷却复用近战表、阻止原版挖方块）。
- `markIntact` 复位 HP；`reset` 清 `coreHp`；命令 `/cww core status` + `/cww core break <队> <伤害>`。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 有幸存者时破拆 ×0.25 | `core break 0 100` → `实际=25.00 剩=175.0` |
| 打到 0 变残骸 | 8 次后 `HP=0.0`、`raftcore status` → `队0=WRECKAGE` |
| 启动接线 | `[combat] 近战武器已挂载：武器表已加载 5 件（含筏心破拆）` |

### 还没做的

- **幸存者全部失去后恢复原速**：逻辑已写（`hasSurvivors` 为 false 即 ×1.0），但没在真人局里跑过"打光幸存者再拆"。
- **破拆特效/音效**：只有 actionbar 文字。
- **`coreMultiplier` 数值**：武器表里的值（木桨 +50% / 铁头锤 +150%）还没按 09 §2.1 逐件核对。

---

## 5.9 幸存者职业第二片：舵手（2026-11-14）

### 落地

- 新增 `domain/raft/HelmsmanRule`（纯函数）：有舵手时**目标速度与转向角冲量 ×1.15**，
  无舵手严格 ×1.0。02 §6 原表的离散口径（单次位移 +1 格 / 冷却 −1 s）在连续航速模型下
  由用户 2026-11-14 批准折算为这个倍率；船体上限 `MAX_TRAVEL_SPEED` 不变。
- `raft/RemoteService`：遥控前进速度与转向冲量都走同一倍率；`raft/WindService`：帆的目标速度同样吃加成
  （先乘倍率再夹上限，满帆顶到上限时加成自然被吸收）。
- 新增 dev 夹具命令 `/cww survivor job <队> <序号> <职业>`：
  `SurvivorJob.byName`（枚举名或中文名）+ `SurvivorService.setJob`（只改职业表与实体名，
  不动人数/口粮/阵位）。自动化验证不再被"开局生产职业优先"的分配顺序挡住。
- 单测 +5：`HelmsmanRuleTest` 4 条 + `SurvivorJobTest.byName…` 1 条；总数 **275/0**。

### 验证（起服实测，flat_test，2026-11-14）

| 断言 | 实测 |
|---|---|
| 队 0 改为舵手、队 1 保持水手 | `职业[舵手1]` / `职业[水手1]` |
| 同 tick 同输入前进 40 tick | 舵手队 **7.963 格** vs 对照 **6.979 格**，比值 **1.141**（标称 1.15） |
| 同 tick 同输入左转 12 tick | 偏航 **8.37°** vs **7.28°**，比值 **1.150** |
| 汇总 | **9 PASS / 0 FAIL** |

> **验证方式备注**：本机当前没有可用的 Python（只有 Microsoft Store 占位存根，执行即 exit 49），
> 本轮用等价的 Node RCON 脚本执行了 `tools/helmsman_probe.py` 的同一组命令与断言；
> **Python 探针本身本次未实跑**，下次有 Python 环境时请复跑一次（`helmsman_probe.py` 已入库）。

### 还没做的（职业后半剩余）

- 职业后半已全部落地：工匠见 §5.10，卫兵见 §5.11。

---

## 5.10 幸存者职业第三片：工匠（2026-11-14）

### 落地

- 新增 `domain/survivor/CraftsmanRule`（纯函数）：有工匠时 A2-v2 残骸修复引导
  **8s → 6s**（160 → 120 tick，倍率 0.75，向上取整）；无工匠原样 160。
- `domain/raft/ChannelRule.Channel` 增加 `requiredTicks`：时长在**引导开始那一刻**定格，
  中途工匠上下筏不改已开始的引导（防止最后一秒塞工匠免费加速）。
  新增 `tick(current, inputs, startTicks)`；旧 `tick(current, inputs)` 仍按 160，历史测试不变。
- `raft/RaftCoreService`：按 `SurvivorService.hasJob(team, CRAFTSMAN)` 计算本次引导时长；
  进度/打断日志、`raftcore status`、`raftcore hold` 回包都显示实际时长。
- **明确没做**：01 §7 的"建造加速"——建造在本项目是**瞬时事件**，没有冷却或引导时长可缩短，
  新增建造冷却属于新机制，不借工匠之名偷偷加；"重置沉筏倒计时"属于已删除的 A3 建材重修，
  A2-v2 修复成功本身就会 `RaftSinking.cancelByCoreRestore` 解除倒计时。
- 单测 +6：`ChannelRuleTest` 3 条（120 tick 完成 / 时长定格 / 工匠剩余秒数）+ `CraftsmanRuleTest` 3 条；总数 **281/0**。

### 验证（起服实测，flat_test，2026-11-14）

| 断言 | 实测 |
|---|---|
| 队 0 改为工匠、队 1 保持水手 | `职业[工匠1]` / `职业[水手1]` |
| `raftcore hold` 回包 | 队0 `120 tick…工匠加成`；队1 `160 tick` |
| 约 6.8 秒 | 队0 **INTACT**；队1 仍 **WRECKAGE(引导 142/160，剩 1s)** |
| 约 9.8 秒 | 队0 保持 INTACT；队1 **INTACT** |
| 汇总 | **10 PASS / 0 FAIL** |

> 验证方式备注同上：本机无可用 Python，本轮用等价 Node RCON 脚本执行；
> **`tools/craftsman_probe.py` 本身本次未实跑**，有 Python 环境时请复跑。

---

## 5.11 幸存者职业第四片：卫兵（2026-11-14）

### 落地

- 新增 `domain/combat/GuardRule`（纯函数）：射程 **12 格**（3D，从本队筏心量）、**20 tick 一发**、
  **6.5 HP/发**。6.5 = 现役 5 件近战平均 DPS（≈11.2）× 0.6 的取整，用户批准"文档锚点"口径；
  无追击、无友伤、风暴中照常。
- `round/GuardService`：每名卫兵独立冷却；PLAY 内按筏心 12 格找最近敌方玩家，用
  `mobAttack`（伤害来源=筏上卫兵村民实体）结算。**多卫兵 DPS 叠加**（每人独立 6.5 HP/s）。
- 接进 `RoundManager` PLAY tick（幸存者贴回甲板之后）与 `auditResidue` 的 `reset`。
- 新增 `/cww guard`：各队卫兵数 + 本局命中数。
- 单测 +3：`GuardRuleTest`；总数 **284/0**。

### 验证（起服实测，flat_test，2026-11-14）

| 断言 | 实测 |
|---|---|
| 队 0 出现卫兵、队 1 保持水手 | `[guard] 队0 卫兵=1 命中=0｜队1 卫兵=0` |
| 第一发（normal 难度） | 日志 `剩余 13.5 HP`（20 → 13.5 = **6.5**） |
| 持续开火 | `cww guard` 命中 = 3 |
| 12 格外站 2.2s | 20 → 20（完全不掉血） |
| 同队站筏上 2.2s | 20 → 20（不误伤） |
| 汇总 | **10 PASS / 0 FAIL** |

> **难度依赖（重要）**：Minecraft 对 `mobAttack` 打玩家按难度缩放，Easy = **0.65×**。
> dev 服 `server.properties` 是 `difficulty=easy`，实测一发只有 **4.25**，不是代码少打；
> 探针会临时 `/difficulty normal` 取 6.5 锚点、收尾恢复 easy。**生产服若跑 Easy，卫兵实际 DPS 也会是 4.25。**
>
> 验证方式备注同上：本机无可用 Python，用等价 Node RCON 脚本执行；`tools/guard_probe.py` 未实跑。
