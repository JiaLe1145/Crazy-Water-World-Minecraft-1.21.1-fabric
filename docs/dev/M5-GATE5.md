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

### 1.3 本轮新增探针实跑

用便携 Python 3.11.9（`run/py-embed`，未改系统 PATH）执行，`-I -X utf8`：

| 探针 | 结果 | 覆盖 |
|---|---|---|
| `tools/event_probe.py` | **18 PASS / 0 FAIL** | 8s 预告、预告只出现一次、日志顺序、聚集点 3–4 人 |
| `tools/bounty_probe.py` | **15 PASS / 0 FAIL** | 120s 到期、GLOWING、击杀领赏、奖励进背包、状态清除 |
| `tools/stats_probe.py` | **13 PASS / 0 FAIL** | 真实命中伤害、死亡名字缓存、`state/matches/<match>.json` 字段 |
| 单测 `./gradlew test` | **299 / 0** | 含 `EventWeightTest` 8 条 |

---

## 2. Gate G5 逐条状态

| 判据 | 状态 | 说明 |
|---|---|---|
| 48h 耐久无异常 | ⬜ **未跑** | 需要持续运行 48 小时；本会话无法完成，不能拿短跑冒充 |
| 崩溃恢复演练 3 次 | ✅ **已完成** | 3 轮 before 3/0 + after 7/0 全 PASS |
| 边界用例全过 | 🟡 **部分** | M0–M5 的探针边界已覆盖大部分；Gate G5 还没有一份“边界用例清单”逐条签字 |
| 反作弊与泄露用例全过 | ⬜ **未跑** | 需要单独的作弊/泄露用例清单（攻速超频、包重放、坐标越界、物品复制等） |
| 回滚演练一次成功 | ✅ **已完成** | 备份 → 状态变化 → 还原 → 启服核对，见 §1.2 |

---

## 3. 还没做的（诚实清单）

1. **48h 耐久**：需要一台长期在线的测试服；至少覆盖跨日、内存/区块票增长、日志轮转。
2. **边界用例清单**：Gate G5 的“边界用例全过”不是一句口号，需要把 M0–M5 的边界逐条列成
   可勾选清单（配置非法、满员、掉线、重连、跨局残留、极端人数/面积等）。
3. **反作弊/泄露用例**：攻速超频已由 `pvp_probe` 覆盖；包重放、坐标越界、物品复制、
   客户端伪造 `nbw:hold`/`nbw:move` 等还需要独立用例。
4. **生产启动脚本**：`ops/start.ps1` 的崩溃自动重启没有在本轮用生产 `server.jar` 跑过；
   本轮跑的是 dev `gradlew runServer`。
5. **备份恢复脚本化**：本轮还原用的是 PowerShell `Expand-Archive` 手工步骤；
   `ops/` 里还没有 `restore.ps1`，建议补一个，避免开服时记错路径。

---

## 4. 复跑方法

```bash
# 1) 单测
./gradlew test

# 2) 三个 M5 探针（需要 dev 服在跑）
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/event_probe.py
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/bounty_probe.py
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/stats_probe.py

# 3) 3 次崩溃恢复（脚本会自己起停 dev 服）
bash tools/g5_drills.sh
```

> `run/py-embed` 是本轮下载的便携 Python，**不入库**；有系统 Python 时把 `PY` 指过去即可：
> `PY=python bash tools/g5_drills.sh`。
