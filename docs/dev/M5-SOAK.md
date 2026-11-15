# M5 · 48h 耐久跑测（Gate G5）

> 对应 Gate G5 的「48h 耐久无异常」。**截至 2026-11-15 仍未跑 48h**；
> 本文给的是跑测方法、监测器与判据，不是完成证据。

---

## 1. 为什么需要单独跑

Gate G5 的 48h 不是“挂一晚上看看”：

- **跨日**：日期边界、自动重启、日志轮转；
- **内存/区块票增长**：每局发筏/沉筏/清场后是否留下实体、票、BE；
- **长时间 RCON**：监测器自己会不会把连接拖死；
- **无人在线时段**：服务端仍按 20 TPS tick，不能只看“有玩家时”的读数。

短跑（几分钟）只能证明“能起服”，不能替代 48h。

---

## 2. 跑法

### 2.1 前置

1. 用**独立测试服**，不要拿生产服做耐久；备份先做一次（`ops/backup.ps1`）。
2. 确认 RCON 可用：
   ```bash
   RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/rcon.py "cww state"
   ```
3. 监测器与服务器**分两个终端**；监测器只读，不改对局。

### 2.2 冒烟（2 分钟）

先跑短测确认采样、TPS 计算、日志增量都正常：

```bash
RCON_PORT=25576 RCON_PASSWORD=nbwm0 \
  ./run/py-embed/python.exe -I -X utf8 tools/soak_monitor.py --minutes 2 --interval 10
```

判据：`samples` 接近 12、`avg_tps` 在 19–20 之间、`log_errors=0`。
冒烟通过后再跑正式 48h。

**2026-11-15 冒烟实测**（本机 dev 服，无人对局）：

```
samples=12/12  sample_ratio=1.0  rcon_errors=0
avg_tps=19.994  min_tps=19.924  p95_rcon_ms=79.1
log_errors=0  tick_warnings=0  restarts=0
RESULT: PASS
```

### 2.3 正式 48h

```bash
RCON_PORT=25576 RCON_PASSWORD=nbwm0 \
  ./run/py-embed/python.exe -I -X utf8 tools/soak_monitor.py --hours 48 --interval 60
```

输出：`run/soak/soak_<时间戳>.jsonl`（逐样本）与终端汇总。
**不要**把 `run/` 提交进版本库；结束后把汇总贴进 `M5-GATE5.md`。

### 2.4 期间建议做的操作

48h 不是“放着不动”，至少覆盖：

- 每 2–4 小时开一局并跑完（发筏、航行、沉筏、结算、局后清理）；
- 中途 `cww save` + 重启一次，确认恢复（可与崩溃恢复演练合并）；
- 满员/掉线/重连各一次；
- 事件池全开（含 storm / bounty / black_sea_surge / survivor_drift）；
- 记录 `ops/backup.ps1` 一次、`ops/restore.ps1` 一次（另开窗口，不打断监测）。

---

## 3. 判据（监测器自动判）

`tools/soak_monitor.py` 结束时输出 `RESULT: PASS|HAS-FAILURES`：

| 判据 | 阈值 | 说明 |
|---|---|---|
| 采样完成率 | ≥ 95% | 48h / 60s ≈ 2880 个样本；掉太多说明监测器或服务端有断档 |
| 平均 TPS | ≥ 19.0 | 用 `totalTicks` 增量 ÷ 墙钟；不是插件猜测 |
| 最低 TPS | ≥ 19.0 | 任一样本低于阈值即记失败（卡顿会体现在这里） |
| 日志 ERROR/Exception | 0 条 | 排除 Sable 启动期已知噪声（`create:flywheel`） |
| 连续 RCON 失败 | < 3 次 | 连续 3 次失败直接停测并判失败，避免静默拖 48h |
| 服务端重启 | 0 次 | `totalTicks` 倒退即视为重启；耐久跑不应依赖自动重启 |

**人工判据（监测器看不到）**：

- 进程内存（任务管理器 / JFR）48h 内无持续单调上涨；
- `run/world/cww/state` 与 `run/logs` 体积增长可解释；
- 对局内没有“越跑越卡”“筏子漂移异常”的肉眼观感。

---

## 4. 记录模板

跑完后在 `M5-GATE5.md` §2 把「48h 耐久无异常」改成 ✅，并附：

```
起止：2026-11-__ __:__ → 2026-11-__ __:__
服务端：<jar 版本 / dev gradlew>
样本：____ / 2880（完成率 __%）
TPS：avg=__.__ min=__.__
RCON：p95=____ ms，连续失败=__
日志：ERROR/Exception=__（排除已知噪声）
重启：__ 次
人工：内存曲线 / 日志体积 / 观感（附截图或视频路径）
结论：PASS / FAIL + 失败原因
```

---

## 5. 当前状态

- ✅ 监测器 `tools/soak_monitor.py` 已落库；
- ✅ 2 分钟冒烟已实跑：12/12 样本、avg TPS 19.994、min 19.924、0 ERROR、0 重启；
- ⬜ **48h 正式跑测未执行**——本会话不满足时长条件，不拿短跑冒充；
- ⬜ 生产 `server.jar` 的崩溃自动重启仍未验证（见 `M5-G5-BOUNDARY.md` B-93）。
