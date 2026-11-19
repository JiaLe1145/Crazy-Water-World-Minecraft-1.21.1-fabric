# M5 · Gate G5 证据记录（开服标准）

> 对应 `docs/dev/03-里程碑.md` §M5 的 Gate G5。判据：48h 耐久无异常；崩溃恢复演练 3 次；
> 边界用例全过；反作弊与泄露用例全过；回滚演练一次成功。
>
> 本文件只记「做了什么 + 读数」，不把计划写成已完成。**未跑的项明确写“未跑”**。

---

## 1. 本轮完成（2026-11-15）

### 1.1 崩溃恢复演练 ×3

做法：复用 `tools/restart_probe.py` 的两段式（`before` 起一局到 PLAY → `cww save` → 停服；
重启后 `after` 逐字段比对 phase / match / arena / format / tick，并确认 abort 能收干净）。
自动夹具：`tools/g5_drills.sh`（本轮新增；`PY` 可用环境变量覆盖，默认 `run/py-embed/python.exe`）。

| 轮次 | before | after | 结论 |
|---|---|---|---|
| Drill 1 | 3 PASS / 0 FAIL | 7 PASS / 0 FAIL | **PASS** |
| Drill 2 | 3 PASS / 0 FAIL | 7 PASS / 0 FAIL | **PASS** |
| Drill 3 | 3 PASS / 0 FAIL | 7 PASS / 0 FAIL | **PASS** |

关键读数（每轮 before 都一致）：

- 落盘前：`phase=PLAY match=113/115/... tickInPhase=5 queue=4 teams=2`
- `cww save`：`文件=run/world/cww/state/round.json`，盘上读回 `phase=PLAY`
- 重启后：阶段仍 `PLAY`、局号连续、arena/format 一致、阶段内 tick 未倒退、未跳大截
- 收尾：`abort` 仍能把恢复出来的局收干净，回到 `LOBBY`

> 产物：`run/g5_drills.log`（gitignored，不入库）。它上面的“server ready / clean lobby”
> 是夹具时序记录；真正的断言在上表的 before/after 汇总里。

### 1.2 回滚演练 ×1

做法：停服 → `ops/backup.ps1 -ServerDir run -Keep 3` 备份 → 重启并开一局到 PLAY
（把状态从 `match=117 LOBBY` 改成 `match=118 PLAY`）→ 再次停服 → 删除 `run/world`、`run/config`
并从备份解压 → 启服核对。

| 步骤 | 读数 |
|---|---|
| 备份文件 | `run/backups/world_20261115_081234.zip`（65,018,880 B，`world/` + `config/`） |
| 备份时状态 | `run/world/cww/state/round.json`：`phase=LOBBY match=117` |
| 制造变化后 | `phase=PLAY match=118 tickInPhase=9` |
| 还原后磁盘 | `round.json`：`phase=LOBBY match=117`（回到备份时点） |
| 启服后 | `cww state`：`phase=LOBBY match=117`；`cww statefile` 指向还原后的 `round.json` |
| 启动错误 | 仅 Sable 的 `create:flywheel` 已知启动噪声（非本轮引入），无新增 ERROR/Exception |

**结论：回滚演练成功。** 还原后服务端能正常启动、状态与备份时点一致。

**同日补充：还原已脚本化。** 新增 `ops/restore.ps1` + `restore.bat`，用同一份备份重跑：

1. 状态改到 `match=118 PLAY`；
2. `ops/restore.ps1 -ServerDir run -Backup run/backups/world_20261115_081234.zip`；
3. 脚本把旧 `world/`、`config/` 暂存为 `*.pre-restore.<时间戳>`，解压新备份，成功后删除暂存；
4. 磁盘回到 `match=117 LOBBY`；启服后 `cww state` 也是 `match=117 LOBBY`。

脚本解压失败时会自动把暂存目录移回来；`-KeepOld` 可保留旧目录。运维用法已写进 `ops/README.md`。

### 1.3 本轮新增探针实跑

用便携 Python 3.11.9（`run/py-embed`，未改系统 PATH）执行，`-I -X utf8`：

| 探针 | 结果 | 覆盖 |
|---|---|---|
| `tools/event_probe.py` | **18 PASS / 0 FAIL** | 8s 预告、预告只出现一次、日志顺序、聚集点 3–4 人 |
| `tools/bounty_probe.py` | **15 PASS / 0 FAIL** | 120s 到期、GLOWING、击杀领赏、奖励进背包、状态清除 |
| `tools/stats_probe.py` | **13 PASS / 0 FAIL** | 真实命中伤害、死亡名字缓存、`state/matches/<match>.json` 字段 |
| `tools/security_probe.py` | **14 PASS / 0 FAIL / 2 INCONCLUSIVE** | 攻速超频、**包洪泛 1000→40（服务端入口级）**、**旁观者长按上限 0→REJECT**、远程装填冷却、隔墙打不中（正/负对照）、旁观者绳索/钩竿；钩竿隔墙/连点为待裁决测量 |
| `PacketRateLimitTest` | **5 / 0** | 输入包 40/s 限速：爆发、丢弃、跨秒重置、窗口边界、关闭限流 |
| 单测 `./gradlew test` | **304 / 0** | 含 `EventWeightTest` 8 条 + `PacketRateLimitTest` 5 条 |

---

## 2. Gate G5 逐条状态

| 判据 | 状态 | 说明 |
|---|---|---|
| 48h 耐久无异常 | ⬜ **未跑** | 监测器与判据已就绪（[`M5-SOAK.md`](M5-SOAK.md) + `tools/soak_monitor.py`）；2 分钟冒烟 12/12、avg TPS 19.994；**48h 本身未跑**，不能拿短跑冒充 |
| 崩溃恢复演练 3 次 | ✅ **已完成** | 3 轮 before 3/0 + after 7/0 全 PASS |
| 边界用例全过 | 🟡 **部分** | 逐条清单已建：[`M5-G5-BOUNDARY.md`](M5-G5-BOUNDARY.md)（B-01～B-94）。大部分有探针/单测证据；真客户端、生产 jar、48h 仍未覆盖 |
| 反作弊与泄露用例全过 | 🟡 **部分** | 清单与状态见 [`M5-ANTICHEAT.md`](M5-ANTICHEAT.md)；已实跑攻速/冷却/隔墙/旁观者绳索/旁观者长按/包洪泛（服务端入口级）。**仍开口**：真客户端伪造包、wire-level 包洪泛、绳索连点、钩竿隔墙、物品复制穷举 |
| 回滚演练一次成功 | ✅ **已完成** | 备份 → 状态变化 → 还原 → 启服核对，见 §1.2 |

---

## 3. 还没做的（诚实清单）

1. **48h 耐久**：工具与判据已就绪（`M5-SOAK.md`），需要一台长期在线的测试服实际跑完；
   至少覆盖跨日、内存/区块票增长、日志轮转。
2. ~~**边界用例清单**~~ → **已完成清单**：[`M5-G5-BOUNDARY.md`](M5-G5-BOUNDARY.md) B-01～B-94。
   仍缺的是其中标 ⛔/⬜ 的项：真客户端、生产 jar 崩溃重启、48h。
3. **反作弊/泄露用例**：已建 [`M5-ANTICHEAT.md`](M5-ANTICHEAT.md) 矩阵；
   本轮实跑攻速超频、远程装填、隔墙遮挡、旁观者绳索/钩竿、旁观者长按、包洪泛（服务端入口级），
   并修了旁观者战斗/控制/建造全链路与远程遮挡；`nbw:hold/move` 已加 40 包/秒限速（纯单测 5 条）。
   **仍开口**：真客户端伪造包/wire-level 包洪泛、绳索/钩竿连点冷却、钩竿隔墙判定、物品复制穷举。
4. **生产启动脚本**：`ops/start.ps1` 的崩溃自动重启没有在本轮用生产 `server.jar` 跑过；
   本轮跑的是 dev `gradlew runServer`。
5. ~~**备份恢复脚本化**~~ → **已完成**：`ops/restore.ps1` + `restore.bat`，
   已用同一份备份重跑并核对 `match=117 LOBBY`，见 §1.2 末尾。
6. **真人验收**：清单已建 [`M5-HUMAN-ACCEPTANCE.md`](M5-HUMAN-ACCEPTANCE.md)
   （Gate G2 手感 + G4 HUD + M5 观战/事件），**一次未跑**；需要真客户端与至少 2 名真人。

---

## 4. 复跑方法

```bash
# 1) 单测
./gradlew test

# 2) 三个 M5 探针（需要 dev 服在跑）
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/event_probe.py
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/bounty_probe.py
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/stats_probe.py
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/security_probe.py

# 3) 3 次崩溃恢复（脚本会自己起停 dev 服）
bash tools/g5_drills.sh

# 4) 耐久冒烟（正式 48h 见 M5-SOAK.md）
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/soak_monitor.py --minutes 2 --interval 10
```

> `run/py-embed` 是本轮下载的便携 Python，**不入库**；有系统 Python 时把 `PY` 指过去即可：
> `PY=python bash tools/g5_drills.sh`。

---

## 5. 2026-11-19 复跑（Windows，无 bash）

本机没有 bash，`g5_drills.sh` 无法直接跑；新增 PowerShell 等价脚本
[`tools/g5_drills.ps1`](../tools/g5_drills.ps1)（UTF-8 with BOM，PS 5.1 按 ANSI 读中文会炸语法）。

### 5.1 结果

| 项 | 结果 |
|---|---|
| 单测 | **304 / 0** |
| 崩溃恢复 ×3 | **BEFORE 3/0 ×3、AFTER 7/0 ×3 → PASS** |
| `event_probe` | **18 / 0** |
| `bounty_probe` | **15 / 0** |
| `stats_probe` | **13 / 0** |
| `security_probe` | **14 PASS / 2 INCONCLUSIVE**（钩竿隔墙、无冷却 = D-2/D-3 待裁决） |
| 48h 耐久冒烟（2 分钟） | **PASS**：12 采样、TPS 20.004、0 错误、p95 RCON 51ms |

### 5.2 本轮修的一个探针缺陷

`restart_probe.py` 的 `after` 段用固定 `sleep(1.5)` 等 abort 生效；实测在带 4 个假玩家的恢复局里偶发不够，
读到过渡前的 PLAY → 误报「abort 收不干净」。已改成**轮询最多 10s**（`tools/restart_probe.py`）。

### 5.3 仍缺（不变）

48h 耐久（需长期测试服）、真人验收（需真客户端 + 2 人）、生产 jar 崩溃重启、回滚演练本轮未复跑。
