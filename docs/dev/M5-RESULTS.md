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
- **2026-11-14 现状**：调度已改为「先预告 8s、再触发」；storm / black_sea_surge / survivor_drift
  已有实际效果，bounty 见 §5.16，kraken 仍只广播。详见 §5.13。

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
- **2026-11-14 补**：SETTLE 时把本局战绩写成
  `<world>/cww/state/matches/<match>.json`（结构化复盘证据，见 §5.14）。

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
- ~~**伤害读数**：`/cww stats` 只列击杀/阵亡，没列伤害（面板里有）。~~
  **2026-11-14 已补**：`/cww stats` 现在按击杀/阵亡/伤害三列各列前 5。

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
  由事件 `survivor_drift` 触发（2026-11-14 起按 02 §10 改为每次 3–4 个，见 §5.13）。
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

---

## 5.12 P3：队伍物资箱 + 扩建成本 + 失温（2026-11-14）

### 落地

- **队伍物资箱**（01 §4.1）：初始筏**中线、桨族空排** `(0, FACILITY_Y, deckStart+1)` 放原版箱子，
  内含 **1 木桨 + 8 木板 + 2 淡水**。x=0 对称避免 0.5 质量造成持续偏航；桨族排不占帆位（桨台已删），
  也不占设施位额度。初版放筏角，实测让 7 帆筏持续偏航、终端速度 0.2205→0.1308；
  移中线后 1/3/5/7 帆 = 0.045/0.134/0.223/0.311，`sea_probe` **39/39**。
  **已知边界**：原版箱子没有队伍锁，敌人登筏后也能打开——这是最小实现，
  队伍锁/开箱日志留作后续。新增 `/cww supply` 读数（按注册 id 计数）。
- **扩建成本**（02 §5）：`domain/construct/ExpansionRule`，甲板层（局部 y=0）的**非设施方块**
  每格扣 **4 木板 + 1 线**（"布"取线，因为线已在补给箱掉落表里；避免扩建卡在没有布料来源）。
  放下的木板本身算 4 木里的 1 块（额外扣 3），放下非木板时扣 4；从**放置者背包**扣；
  创造模式跳过。设施与上层建筑不收费。新增 dev 夹具 `/cww build charge <玩家> <队> <lx> <ly> <lz> <方块>`
  复用同一条扣费函数（不瞄准、不真正放方块，便于稳定验证）。
- **失温**（01 §5 / 02 §8 可选机制）：`domain/survival/HypothermiaRule`——
  连续湿身（泡水或淋雨）**10s** 后营养/水分消耗 **×1.5**；连续干燥 **3s** 解除；
  短时间干湿切换不清零。`SurvivalService` 每秒喂 `p.isWet()`；
  `/cww survival status` 增加 `失温=是/否`；进入/解除各发一次 actionbar。
  **没做**：篝火/干衣这类主动恢复物（02 §8 的对应生产物）；失温本身不掉血。
- 单测 +7（`ExpansionRuleTest` 3 + `HypothermiaRuleTest` 4）；总数 **291/0**。
- 现有探针已适配：`build_probe.py` / `m2_round.py` 的 `live_place` 会在 hotbar.1 放 64 线，
  否则新扩建成本会把它们的放置全部拒掉；`build_probe` 初始方块数与 cap 上限各 +1（物资箱 1 格）；
  `m2_round.py` / `raftgen_probe.py` 初始质量 9.0 → **9.5**（物资箱 0.5）。

### 验证（起服实测，flat_test，2026-11-14）

| 断言 | 实测 |
|---|---|
| 两队各有一个物资箱 | `paddle×1 oak_planks×8 fresh_water×2`（两队一致） |
| 水里 11 秒 | `失温=是` |
| 旱地 4 秒 | `失温=否` |
| 扩建扣费（材料够） | 木板 16 → 13、线 4 → 3（probe 不真正放方块，只扣额外 3+1） |
| 扩建扣费（缺线） | 拒绝并说明缺线；木板保持 13（不预扣） |
| 汇总 | `p3_probe`（物资箱 + 失温 + 扩建扣费）**14 PASS / 0 FAIL** |

> **2026-11-14 全量回归（便携 Python 3.11.9，`py-embed/` 未改系统 PATH）**：
> `core 19`、`sink 15`、`wind 9`、`raftgen 18`、`build 60`、`m2_round 31 + 1 INCONCLUSIVE`、
> `survivor 9`、`storm 8`、`spectator 5`、`fairness 5`、`hazard 7`、`loot_clearance 7`、`pvp 9`、
> `p3 14`、`helmsman 9`、`craftsman 10`、`guard 10`、`sea 39`、`remote 8`、`zone 8`、`seat 8`、
> `rope 8`、`restart before 3 + after 7`、`g1_guards 9`、`rescue 41 + 1 INCONCLUSIVE` —— **全部 PASS**。
> 首轮 `seat` / `g1_guards` 红是夹具时序（`restart_probe` 按设计停服、`seat_probe` 没回干净 LOBBY），
> 重启/清场后复跑全绿；`m1_round` / `sail_probe` 未复跑（未受本次改动影响，`sea_probe` 已覆盖帆速单调性）。
>
> 本轮顺手修的探针口径：`wind_probe` 顺/逆风按**筏头**选（旧版写死 north，默认朝南的筏上变成逆风）；
> `build_probe` 真实放置用 dirt，扩建成本要 4 木板，补 hotbar.2；`guard_probe` 两处 `%s` 直接接 tuple。

---

## 5.13 事件硬化：8 秒预告 + 幸存者聚集点人数（2026-11-14）

### 落地

- **预告两段式调度**（01 §9 硬约束：提前 5–10s 公告 + 音效）：到点先选中事件并预告 **8s**，
  预告结束才真正 `fire`。事件在选中那一刻定死，预告名与最终触发名不会对不上。
- 预告内容：聊天公告（中文事件名 + 一句效果提示）+ 每个参赛者位置放 `BLOCK_NOTE_BLOCK_PLING` 音效。
- `/cww event` 在预告期间显示 `预告=<id> 还剩=<n>s`；触发后恢复 `下一发=<n>s`。
- `/cww event fire` 强制触发会清掉待预告事件，避免下一 tick 又自动触发一次。
- 事件结束公告改用中文名（`物资雨/风暴/海怪袭击/黑海倒流/幸存者聚集点/悬赏令`）。
- `survivor_drift` 人数从 2 改为 **3–4**（02 §10）；无人小筏仍未做，先只落人数。

### 验证（起服实测，`test_3min` + `flat_test`，2026-11-14）

| 断言 | 实测 |
|---|---|
| 开局在“下一发”而不是预告 | `[event] 池=1 已触发=0 上次=无 下一发=69s 间隔=60-90s` |
| 到点进入预告 | `[event] 池=1 已触发=0 上次=无 预告=storm 还剩=7s 下一发=7s 间隔=60-90s` |
| 预告阶段不触发 | `已触发=0`（看到预告时） |
| 预告后触发 | 墙钟差 **7.0s**（服务端窗 8s，轮询粒度 ±1s） |
| 触发后回到下一发 | `[event] 池=1 已触发=1 上次=storm 下一发=81s 间隔=60-90s` |
| 日志顺序 | `[预告] 8s 后：风暴` 出现 **1 次**，且位于 `触发 storm` 之前 |
| 幸存者聚集点人数 | 日志 `[survivor] 海面漂出 3 个幸存者`；`[event] 幸存者聚集点：海面漂出 3 个` |
| 单测 | `./gradlew test` **291 / 0** |

> **探针文件未落库**：本轮写 `tools/event_probe.py` 时写文件权限被拒（用户拒绝 Write），
> 因此用等价的 Node RCON 脚本执行了同一组命令与断言；命令与读数已记在上表。
> 有写权限后应把这段固化进 `tools/event_probe.py`，再按交接 §6.1 的三态汇总补跑一次。
>
> 夹具口径：`test_3min` 的事件池是 `["storm"]`、间隔 60–90s，PLAY 硬上限 360s；
> 假玩家给 `water_breathing + resistance 255 + regeneration 255`，避免缩圈伤害把两队打空。

### 还没做的

- **kraken 的实际效果**：仍只广播（海怪需要实体）。**bounty 已于 §5.16 落地**。
- **事件权重**：仍等概率抽，没有按局内时间加权。
- **预告音效的真人听感**：服务端放了音效，客户端实际音量/时机需真人验收。
- **事件叠加规则**：预告只在没有 `activeEvent` 时开始；若风暴持续时间跨过下一发间隔，
  会等风暴结束再预告，不会抢窗口。这条是当前实现口径，没有额外机制。

---

## 5.14 战绩落盘：`state/matches/<match>.json`（2026-11-14）

### 落地

- `CombatStats.persist(server)` 在 **SETTLE** 阶段调用（`RoundManager.onPhaseEnter`），写
  `<world>/cww/state/matches/<match>.json`。字段：
  - 局级：`match`、`recordedAt`、`winner`、`cause`、`durationSec`、`survivors`、`participants`；
  - 每人：`uuid`、`name`、`team`、`kills`、`deaths`、`damage`。
- 落盘失败只写 `WARN`，不阻断结算；`RETURN` 阶段仍按原逻辑 `reset()` 清内存。
- **修正了两个实测发现的问题**（不是推测）：
  1. **路径**：初版写成 `<world>/state/matches`，与既有的 `<world>/cww/state/round.json` 目录约定不一致；
     实测日志给出的是 `run/world/state/matches/98.json`。已改为 `<world>/cww/state/matches`。
  2. **名字丢失**：玩家死亡后实体被移除（Carpet 假玩家尤其如此），SETTLE 时
     `getPlayerManager().getPlayer(uuid)` 查不到人，`/cww stats` 把阵亡者打成 UUID 前缀
     （实测 `阵亡 d4475a2a=1`）。现在在伤害/死亡钩子处缓存 `UUID → 名字`，
     面板与 JSON 都使用缓存名（实测 `阵亡 xb8558=1`）。

### 验证（起服实测，`test_3min` + `flat_test`，2026-11-14）

| 断言 | 实测 |
|---|---|
| 砍刀命中并记录伤害 | `[survival] xb8558 … 血=5.0 流血=是`（真实 `onAttack` 路径） |
| 击杀/阵亡名字 | `[stats] 击杀 xa8558=1 ｜阵亡 xb8558=1`（不再出现 UUID 前缀） |
| 文件位置 | `run/world/cww/state/matches/99.json` 存在 |
| JSON 内容 | `{"match":99,"winner":"赤队","cause":"WINNER_DECIDED","durationSec":44,"survivors":1,"participants":2,"players":[{"name":"xa8558","kills":1,"damage":9.0,…},{"name":"xb8558","deaths":1,…}]}` |
| 汇总 | **12 PASS / 0 FAIL** |
| 单测 | `./gradlew test` **291 / 0** |

> 本轮同样用等价的 Node RCON 脚本执行（`tools/event_probe.py` / 战绩探针因写文件权限未落库）；
> 需要补的探针：`tools/event_probe.py`、`tools/stats_probe.py`（命令与断言已记在 §5.13 与本表）。

### 还没做的

- **更多局级字段**：02 §11.2 还要求每队死亡时间、物资拾取分布、状态归零次数、航行次数、
  事件响应；当前 JSON 只落了战绩与胜负结果，其余等复盘需求明确后再加，不先造字段。
- **跨局汇总**：`matches/` 是逐局文件，没有自动聚合（P50 局时长、生产参与率等仍看
  `cww/metrics.jsonl` 或人工汇总）。
- **外观解锁**：01 §A11 的"外观解锁（无数值）"仍未做，等美术与结算 UI 一起。

---

## 5.15 黑海倒流：边界外扩 10%（2026-11-14）

### 落地

- `EventService.zoneRadiusMultiplier()`：`black_sea_surge` 生效期间返回 **1.10**，其余 1.0。
- `ZoneService` 统一用**有效半径**（`model.radiusAt(sec) × 倍率`）做：
  收缩公告、actionbar 倒计时、圈外伤害判定、`/cww zone` 读数。
  这样不会出现“公告一个半径、伤害按另一个半径算”的分叉。
- 事件公告改为：`黑海倒流：20s 内边界外扩 10%、圈外伤害 ×1.5`。
- 事件结束（20s 到期）后倍率回到 1.0，边界立即收回；圈外玩家从下一 tick 起按基础半径判定。

### 验证（起服实测，`test_3min` + `flat_test`，2026-11-14）

| 断言 | 实测 |
|---|---|
| 开局基础半径 | `[zone] 进行 4s 半径=60.0` |
| 事件期间外扩 | `[zone] 进行 4s 半径=66.0`（60 × 1.10） |
| 20s 后恢复 | `[zone] 进行 26s 半径=60.0` |
| 汇总 | **5 PASS / 0 FAIL** |
| 单测 | `./gradlew test` **291 / 0** |

> 夹具注意：`flat_test` 的第一段缩圈在 30s 触发，假玩家必须给
> `resistance 255 + regeneration 255`，否则会在 30s 后被圈外伤害打空、提前 SETTLE，
> 测试就永远读不到“事件结束后的半径”（本轮第一次就踩了这条，日志显示 `存活 0/2`）。

### 还没做的 / 当前口径

- **“随后伤害 ×1.5”**：02 §10 的原文是“边界外扩 10%，随后 P 段伤害 ×1.5”。
  当前实现是**事件生效的 20s 内**圈外伤害 ×1.5（沿用 M3 补的旧逻辑），
  不是“事件结束后再进入一个增伤窗口”。这是当前口径，若要做成“随后”的独立窗口，
  需要再加一个 20s 的后效状态，属于新数值，等用户裁决。
- **边界粒子墙/小地图**：仍只有 actionbar + darkness，没有视觉边界。

---

## 5.16 悬赏令：标记 + 击杀领赏 + 120s 到期（2026-11-14）

### 落地

- `EventService` 新增 `register()`，挂 `ServerLivingEntityEvents.AFTER_DEATH`；没有悬赏时监听器空转。
- `bounty` 触发时：
  - 从参赛者里随机挑一名**活着、非旁观**的玩家，设置 `GLOWING`（事件窗口 + 1s），
    公告 `悬赏令：目标 <名字>`，全场可见。
  - 事件窗口 **120s**（实现口径，02 §10 原文是“至完成”；见下方说明）。
  - 目标被玩家击杀 → 击杀者获得一件 **Epic 武备**（`harpoon_gun` / `warhammer`），
    公告击杀者、目标与奖励；背包满则掉在脚下，不静默吞奖励。
  - 目标被非玩家来源杀死 → 公告“无人领赏”，不给奖励。
  - 120s 到期 → 公告“悬赏令到期：<名字> 未被击杀”，状态清空，事件池继续排下一发。
  - 场上没有合法目标（全员旁观/死亡）→ 公告“没有可标记的玩家”，不占事件窗口。
- `/cww event` 在悬赏生效时显示 `悬赏=<名字> 剩=<n>s`。
- **避免撞持有上限**：若击杀者已持有近战，奖励只发远程的 `harpoon_gun`，不发 `warhammer`
  （09 §5.1：每人 ≤1 件近战）。

### 验证（起服实测，`test_3min` + `flat_test`，2026-11-14）

| 断言 | 实测 |
|---|---|
| 悬赏目标与倒计时 | `[event] 池=1 已触发=1 上次=bounty 悬赏=ba7990 剩=119s` |
| 目标带公开标记 | `active_effects` 含 `minecraft:glowing` |
| 到期路径：120s 自动清除 | 到期后状态变成 `预告=storm 还剩=8s`，日志 `悬赏令到期：ba7990 未被击杀` |
| 到期不误杀 | `list` 仍有 `ba7990, bb7990` 两人在线 |
| 击杀路径：奖励落袋 | 日志 `悬赏完成：kb8143 击杀 ka8143，获得 Epic 武备 harpoon_gun`；击杀者背包 `Slot 1b id:"nbw:harpoon_gun"` |
| 击杀后状态清除 | `[event] 池=1 已触发=1 上次=bounty 下一发=57s`（无 `悬赏=`） |
| 汇总 | 到期路径 8 PASS + 击杀路径 5 PASS，**0 FAIL** |
| 单测 | `./gradlew test` **291 / 0** |

> **夹具教训**：首次击杀路径失败不是产品问题——假玩家带着 `resistance 255`，
> `/damage 100` 被完全减免，目标没死，AFTER_DEATH 自然不触发。
> 复验时先 `effect clear <目标>` 再 `/damage`，一次通过。以后测“击杀类”断言都要先确认减伤效果已清。
>
> 本轮仍是等价 Node RCON 脚本实跑（探针文件待写权限）。

### 还没做的 / 当前口径

- **120s 上限**：02 §10 原文是“至完成”。选 120s 是为了不让一个不死的目标永久占住事件窗口；
  这是实现口径，若要改成“至击杀或局末”需要重新评估事件轮转。
- **奖励池**：现在只有 `harpoon_gun` / `warhammer` 两件 Epic；是否按 09 表加入更多、
  是否随机权重，等武器表全量落地后再调。
- **目标掉线**：目标断线后悬赏继续到 120s 到期，不提前结束；是否需要“目标离线即换人”等运营需求再定。
- **UI**：只有 `GLOWING` 与聊天公告，没有屏幕边缘标记/音效。
