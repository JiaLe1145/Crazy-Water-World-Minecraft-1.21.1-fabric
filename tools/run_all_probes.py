#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量跑全部探针，输出汇总。

用法：python tools/run_all_probes.py [--only a,b,c] [--skip x,y]
每个探针独立子进程 + 超时，互不影响；一个崩了不影响后面的。
"""
import os
import subprocess
import sys
import time
import io

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
PY = os.path.join(PROJECT, "run", "py-embed", "python.exe")
LOG_DIR = os.path.join(PROJECT, "run", "probe_logs")
TIMEOUT = 240

PROBES = [
    "p3_probe", "raftgen_probe", "build_probe", "core_probe", "seat_probe",
    "sea_probe", "wind_probe", "sail_probe", "rope_probe", "rescue_probe",
    "remote_probe", "sink_probe", "survivor_probe", "guard_probe",
    "helmsman_probe", "craftsman_probe", "event_probe", "zone_probe",
    "loot_clearance_probe", "hazard_probe", "storm_probe", "pvp_probe",
    "stats_probe", "spectator_probe", "fairness_probe", "security_probe",
    "bounty_probe", "restart_probe",
]


def summarize(text):
    """挑出真正的失败行，外加最后几行。

    只看"行首就是 FAIL"或"RESULT: ...FAIL"这种，别把
    "没有残留 FAIL" / "FAILED=0" 这类通过提示误判成失败。
    """
    lines = [l.rstrip() for l in text.splitlines() if l.strip()]
    fail = []
    for l in lines:
        s = l.strip()
        if s.startswith("FAIL") or s.startswith("- FAIL") or "FAILED=" in s and not s.endswith("FAILED=0"):
            fail.append(l)
        elif "RESULT:" in s and "PASS" not in s:
            fail.append(l)
    tail = lines[-3:] if lines else []
    return fail, tail


def main():
    only = None
    skip = set()
    args = sys.argv[1:]
    for i, a in enumerate(args):
        if a == "--only" and i + 1 < len(args):
            only = set(args[i + 1].split(","))
        if a == "--skip" and i + 1 < len(args):
            skip = set(args[i + 1].split(","))

    os.makedirs(LOG_DIR, exist_ok=True)
    env = dict(os.environ)
    env["RCON_PORT"] = "25576"
    env["RCON_PASSWORD"] = "nbwm0"
    env["PYTHONIOENCODING"] = "utf-8"

    results = []
    for name in PROBES:
        if only is not None and name not in only:
            continue
        if name in skip:
            continue
        path = os.path.join(HERE, name + ".py")
        if not os.path.isfile(path):
            results.append((name, "MISSING", [], []))
            continue
        print("\n" + "=" * 70)
        print(">>> " + name)
        print("=" * 70, flush=True)
        t0 = time.time()
        try:
            r = subprocess.run([PY, "-I", "-X", "utf8", path], cwd=PROJECT, env=env,
                               capture_output=True, timeout=TIMEOUT)
            out = r.stdout.decode("utf-8", "replace")
            err = r.stderr.decode("utf-8", "replace")
            code = r.returncode
        except subprocess.TimeoutExpired:
            out, err, code = "", "TIMEOUT", -1
        dur = int(time.time() - t0)
        with io.open(os.path.join(LOG_DIR, name + ".log"), "w", encoding="utf-8") as f:
            f.write(out + "\n--- stderr ---\n" + err)
        fails, tail = summarize(out)
        for l in tail:
            print("    " + l, flush=True)
        if err.strip():
            for l in err.strip().splitlines()[-3:]:
                print("    [stderr] " + l, flush=True)
        status = "PASS" if code == 0 and not fails else ("TIMEOUT" if code == -1 else "FAIL")
        results.append((name, status, fails, tail))
        print("  -> " + status + " (" + str(dur) + "s)", flush=True)
        time.sleep(3)

    print("\n" + "=" * 70)
    print("汇总")
    print("=" * 70)
    ok = sum(1 for r in results if r[1] == "PASS")
    for name, status, fails, _ in results:
        mark = "OK  " if status == "PASS" else "!!  "
        print(mark + name.ljust(24) + status)
        for f in fails[:4]:
            print("      - " + f[:140])
    print("\n%d/%d 通过" % (ok, len(results)))


if __name__ == "__main__":
    main()
