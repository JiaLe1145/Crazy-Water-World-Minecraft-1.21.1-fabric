#!/usr/bin/env python3
"""Gate G5 · 48h 耐久监测器（2026-11-15）。

对着一台**已经在跑**的 dev/测试服采样，不负责起停服务端：
  - 每 `--interval` 秒读一次 `cww state` / `list` / `cww metrics`；
  - 用服务端 `totalTicks` 的增量除以墙钟得到实测 TPS（不是客户端猜的）；
  - 从 `run/server-console.log` 的**新增段**统计 ERROR / Exception / "Can't keep up"；
  - 逐行写 JSONL（断线也留证据），结束时打印汇总与 PASS/FAIL。

用法（先按 ops/README 起服，确认 RCON 可用）：
    RCON_PORT=25576 RCON_PASSWORD=nbwm0 \
      ./run/py-embed/python.exe -I -X utf8 tools/soak_monitor.py --hours 48
    # 冒烟：--minutes 2 --interval 10

为什么用 totalTicks 而不是插件 TPS 命令：本服没有装 TPS 插件；totalTicks 是
RoundManager 自己每 tick 递增的计数，增量/墙钟就是最直接的服务端存活与负载证据。

退出码：0 = 汇总判据全过；1 = 有判据未过（包括采样缺口、不可达、异常）。
**48h 没跑完时不要拿本脚本的输出宣称 Gate G5 通过。**
"""
import argparse
import json
import os
import re
import statistics
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, LOG  # noqa: E402

STATE_RE = re.compile(r"phase=(\w+).*?totalTicks=(\d+)", re.S)
LIST_RE = re.compile(r"There are (\d+) of a max")
TICK_WARN_RE = re.compile(r"Can't keep up", re.I)
ERROR_RE = re.compile(r"\[[^\]]*ERROR\]|Exception|java\.lang\.\w*Error")
# Sable 启动期的已知噪声：不是本模组引入，也不能把它算成 48h 耐久失败。
KNOWN_NOISE = (
    "create:flywheel",
    "Could not initialize class",
)


def now_iso():
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def parse_state(out):
    m = STATE_RE.search(out)
    if not m:
        return None, None
    return m.group(1), int(m.group(2))


def classify_log(text):
    """返回新增日志里的问题计数；已知噪声不计入 error。"""
    errors, tick_warns = 0, 0
    samples = []
    for line in text.splitlines():
        if not line.strip():
            continue
        if any(n in line for n in KNOWN_NOISE):
            continue
        if TICK_WARN_RE.search(line):
            tick_warns += 1
            samples.append(line.strip()[:300])
        elif ERROR_RE.search(line):
            errors += 1
            samples.append(line.strip()[:300])
    return errors, tick_warns, samples


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hours", type=float, default=48.0)
    ap.add_argument("--minutes", type=float, default=0.0)
    ap.add_argument("--interval", type=float, default=60.0)
    ap.add_argument("--out", default="")
    ap.add_argument("--min-tps", type=float, default=19.0)
    ap.add_argument("--max-consecutive-errors", type=int, default=3)
    args = ap.parse_args()

    duration = args.minutes * 60.0 if args.minutes > 0 else args.hours * 3600.0
    if duration <= 0:
        print("duration must be positive", file=sys.stderr)
        return 2

    out = args.out or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "run", "soak", "soak_%s.jsonl" % datetime.now().strftime("%Y%m%d_%H%M%S"))
    os.makedirs(os.path.dirname(out), exist_ok=True)

    p = P()
    started = time.time()
    deadline = started + duration
    samples = 0
    rcon_errors = 0
    consecutive_errors = 0
    tps_values = []
    latencies = []
    error_count = 0
    tick_warn_count = 0
    error_examples = []
    last_ticks = None
    last_at = None
    restarts = 0

    print("soak start: duration=%.0fs interval=%.0fs out=%s" % (duration, args.interval, out))
    with open(out, "a", encoding="utf-8") as fh:
        while True:
            tick_at = time.time()
            if tick_at >= deadline:
                break
            sample = {"at": now_iso(), "epoch": round(tick_at, 3)}
            try:
                t0 = time.time()
                state = p("cww state")
                latency = time.time() - t0
                players = p("list")
                metrics = p("cww metrics")
                log_new = p.log()
                log_errors, tick_warns, examples = classify_log(log_new)
                phase, ticks = parse_state(state)
                lm = LIST_RE.search(players)
                sample.update({
                    "ok": True,
                    "rcon_ms": round(latency * 1000, 1),
                    "phase": phase,
                    "total_ticks": ticks,
                    "players": int(lm.group(1)) if lm else None,
                    "log_errors": log_errors,
                    "tick_warnings": tick_warns,
                    "state": state.replace("\n", " ")[:400],
                    "metrics": metrics.replace("\n", " ")[:300],
                })
                latencies.append(latency * 1000)
                error_count += log_errors
                tick_warn_count += tick_warns
                error_examples.extend(examples[:3])
                error_examples = error_examples[-20:]
                consecutive_errors = 0
                if ticks is not None and last_ticks is not None:
                    dt = tick_at - last_at
                    dticks = ticks - last_ticks
                    if dticks >= 0 and dt > 0:
                        tps = dticks / dt
                        sample["tps"] = round(tps, 3)
                        tps_values.append(tps)
                    elif dticks < 0:
                        restarts += 1
                        sample["restart_detected"] = True
                if ticks is not None:
                    last_ticks, last_at = ticks, tick_at
                samples += 1
            except Exception as exc:  # noqa: BLE001 - 监测器必须活到 48h
                rcon_errors += 1
                consecutive_errors += 1
                sample.update({"ok": False, "error": repr(exc)})
                print("sample failed (%d consecutive): %r" % (consecutive_errors, exc),
                      file=sys.stderr)
                if consecutive_errors >= args.max_consecutive_errors:
                    # 先记下来再退出：不要把“服务器死了”拖成 48 小时静默。
                    sample["fatal"] = "%d consecutive sample failures" % consecutive_errors
            fh.write(json.dumps(sample, ensure_ascii=False) + "\n")
            fh.flush()
            if consecutive_errors >= args.max_consecutive_errors:
                break
            sleep_for = min(args.interval, max(0.0, deadline - time.time()))
            p.sleep(sleep_for) if hasattr(p, "sleep") else time.sleep(sleep_for)

    expected = max(1, int(duration / args.interval))
    ratio = samples / expected
    avg_tps = statistics.fmean(tps_values) if tps_values else None
    min_tps = min(tps_values) if tps_values else None
    p95_lat = None
    if latencies:
        ordered = sorted(latencies)
        p95_lat = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]

    failures = []
    if ratio < 0.95:
        failures.append("采样缺口：%d/%d（%.1f%%）" % (samples, expected, ratio * 100))
    if consecutive_errors >= args.max_consecutive_errors:
        failures.append("连续 RCON 失败 %d 次" % consecutive_errors)
    if error_count > 0:
        failures.append("日志 ERROR/Exception %d 条" % error_count)
    if avg_tps is not None and avg_tps < args.min_tps:
        failures.append("平均 TPS %.2f < %.2f" % (avg_tps, args.min_tps))
    if min_tps is not None and min_tps < args.min_tps:
        failures.append("最低 TPS %.2f < %.2f" % (min_tps, args.min_tps))
    if restarts > 0:
        failures.append("检测到服务端重启 %d 次" % restarts)

    summary = {
        "out": out,
        "duration_sec": round(time.time() - started, 1),
        "samples": samples,
        "expected_samples": expected,
        "sample_ratio": round(ratio, 4),
        "rcon_errors": rcon_errors,
        "avg_tps": round(avg_tps, 3) if avg_tps is not None else None,
        "min_tps": round(min_tps, 3) if min_tps is not None else None,
        "p95_rcon_ms": round(p95_lat, 1) if p95_lat is not None else None,
        "log_errors": error_count,
        "tick_warnings": tick_warn_count,
        "restarts": restarts,
        "examples": error_examples,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print("RESULT: %s" % ("PASS" if not failures else "HAS-FAILURES"))
    for f in failures:
        print("  FAIL - %s" % f)
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
