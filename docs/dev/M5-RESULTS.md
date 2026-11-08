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
