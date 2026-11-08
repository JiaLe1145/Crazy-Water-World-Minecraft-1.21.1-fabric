# M3 结果记录（黑海 + 物资 + 竞技核心）

> 对应里程碑 `docs/dev/03-里程碑.md` §M3，Gate G3。
> 本文件只记「落地了什么 + 怎么验的」，设计口径仍看 `docs/content/02-数值与节奏.md`。

---

## 3.1 黑海圈层运行时（2026-11-08）

### 落地

- `domain/ZoneModel`（纯函数，已有）：`radiusAt(second)` / `damageAt(second)` / `warnCountdownAt(second)` /
  `ringOf(distance)` / `isOutside()` / 表驱动校验。
- 新增 `round/ZoneService`（运行时）：
  - `begin(server)`：PLAY 开始时抓圈层模型与圈心（`arena.sea.centerX/centerZ`）。
  - `onEndTick(server)`：每 tick 读秒 → 取当前半径/伤害；
    **阶段切换公告**（`§c黑海收缩！当前半径 N 格`）、**收缩预告**（`§e黑海将在 N 秒后收缩`）、
    **圈外每秒伤害**（每 20 tick 对圈外参赛者 `damage(magic(), damage)`）。
  - 只对 `RoundManager.participantIds()` 结算，旁观者/大厅玩家不会误伤。
  - `reset()`：局后清圈层模型与预告状态。
- **可视化（2026-11-08 补充）**：每 2 秒把「当前半径 + 下次收缩倒计时」推到 actionbar
  （`sendMessage(Text, true)`，不刷聊天）；收缩/预告仍走聊天公告。
- 新增 `/cww zone`：只读读数（进行秒数 / 半径 / 圈外伤害 / 圈心 / 已收缩阶段）。

### 验证（`tools/zone_probe.py`，8/8 PASS）

用 `flat_test`（干地、半径 60、无沉筏干扰），两队锚点距圈心 48 格：

| 断言 | 实测 |
|---|---|
| 开局半径 = 60、圈外伤害 = 0 | 60.0 / 0.0 |
| 30s 后第一段收缩：半径下降、伤害 > 0 | 40.0（收缩中，目标 36）/ 0.5 |
| 圈外玩家每秒扣血 | 17.5→13.0、18.0→12.5（8s，≈0.5 HP/s） |
| 预告/收缩公告进日志 | 命中 `[zone] §c黑海收缩…` |

### 还没做的

- **可视化细化**：已有 actionbar（半径 + 倒计时）与聊天公告；没有小地图/边界粒子墙/屏幕框架。
- **附加效果**：黑海只扣血，没有视野压暗/体温/减速（归 M4 生存系统一起做）。
- **环带品质/物资刷新**：`ZoneModel.ringOf()` 已就绪，但物资系统（M3-2）尚未接。

---

## 3.2 海面物资刷新（2026-11-08）

### 落地

- 新增 `loot/LootService`：
  - `spawnPrefill(server)`：读 `arena.loot.prefill`（enabled/count/radiusBand），
    在水面那一格（`sea.waterLevel`）撒「补给箱」——原版 `minecraft:barrel`（箱形像补给箱，且自带 loot 容器）。
  - 每个箱子 `setLootTable(RegistryKey<LootTable> nbw:sea_crate, seed)`，右键开箱按 loot table 生成物品。
  - `placeCrate` 做两道保护：**区块没加载不落**（不写进未加载区块）、**位置被占不覆盖**（岛/结构）。
  - `spawn(server, count, minR, maxR)`：dev 命令入口，整片海随机撒。
  - `status()`：已撒数量 + 最近 3 个位置（探针核对用）。
- `data/nbw/loot_table/sea_crate_t{1,2,3}.json`：三档物资表（t1 外圈基础 / t2 中圈含铁与刀 / t3 内圈含钻与重武器）。
- **环带品质**：`LootService.tierAt` 用 `ZoneModel.ringOf(距离)` 查该点属于哪一圈，按圈的 `tier` 选表。
- **定时刷新**：`LootService.onEndTick` 每 `format.loot.respawnIntervalSec`（默认 22s）补 2 个箱子。
- 接进 `RoundManager` PLAY 开局：`LootService.begin(server)`；PLAY tick 里 `onEndTick`；`auditResidue` 里 `reset()`。
- 命令：`/cww loot status`、`/cww loot spawn <n>`。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| `loot spawn 5` 能撒 5 个、位置在环带内 | `已撒补给箱 5 个`，位置 `-1010,62,1000` 等 |
| 箱子带 loot table | `data get block` 返回 `LootTable:"nbw:sea_crate"` + seed + `id:"minecraft:barrel"` |
| loot table 可生成物品 | `loot spawn … loot nbw:sea_crate` → `Dropped 2 items` |
| PLAY 开局按 prefill 自动撒 | `test_sea`（count=24）开局后 `已撒箱子 29 个`（24 本局 + 5 上一轮未清） |
| **环带品质**：距离→档位→表 | 距心 30.1 格 → `t2`；距心 53.8/54.4 格 → `t1`（与 `rings` 的 tier 一致） |
| 补箱间隔读进 log | `[loot] 开局：…（补箱间隔 20s）` |

### 还没做的

- **定时刷新实测**：`onEndTick` 的补箱是 debug 日志、本轮没在真人局里跑满 20s 核对数量（代码路径与 prefill 同一条 `spawn`）。
- **漂浮物**：钩竿拉「漂浮物」需要独立的漂浮物实体，归后续。
- **t3 武器掉率**：目前是等权，等数值表定稿再调权重。
