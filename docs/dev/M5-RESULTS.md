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
