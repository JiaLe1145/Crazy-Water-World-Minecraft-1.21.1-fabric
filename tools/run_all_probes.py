#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量跑全部探针，输出汇总。

用法：python tools/run_all_probes.py [--only a,b,c] [--skip x,y]
每个探针独立子进程 + 超时，互不影响；一个崩了不影响后面的。

注意：
  * `restart_probe` 是两段式人工脚本（before 停服 → 外部重启 → after），
    不能塞进本批跑；重启恢复验证另行编排（见 M6-RESULTS 记录方式）。
  * 判定"失败行"必须只认真正的失败行：探针自己的汇总里有
    `FAILED    : 0`、`PASS=10 FAIL=0` 这类通过计数，不能当成失败。
  * 每个探针开跑前先探一次 RCON；服务端掉线就标记 SKIP 并终止，
    避免像 2026-11-21 那轮一样把"服务器已停"误记成 20 个探针 FAIL。
"""
import os
import re
import subprocess
import sys
import time
import io

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
PY = os.path.join(PROJECT, "run", "py-embed", "python.exe")
LOG_DIR = os.path.join(PROJECT, "run", "probe_logs")
SUMMARY_PATH = os.path.join(PROJECT, "run", "all_probes_summary.txt")
TIMEOUT = 300
# 个别重活探针单独给时长：sea_probe 要建 1.35M 方块的 test_sea 海域
PROBE_TIMEOUTS = {"sea_probe": 600, "build_probe": 400}

# 两段式探针不进批量列表（restart_probe 会主动停服）
PROBES = [
    "p3_probe", "raftgen_probe", "build_probe", "core_probe", "seat_probe",
    "sea_probe", "wind_probe", "sail_probe", "rope_probe", "rescue_probe",
    "remote_probe", "sink_probe", "survivor_probe", "guard_probe",
    "helmsman_probe", "craftsman_probe", "event_probe", "zone_probe",
    "loot_clearance_probe", "hazard_probe", "storm_probe", "pvp_probe",
    "stats_probe", "spectator_probe", "fairness_probe", "security_probe",
    "bounty_probe",
    # M6 计划专用探针：A 域配置 / B+L 域生命周期 / O+S 域权限与边界
    "m6_config_probe", "m6_round_probe", "m6_edge_probe",
]

# 真正的失败行：行首 FAIL（后面是空白/短横/冒号/行尾）、"- FAIL"、或 RESULT 非 PASS。
# 排除 FAILED / FAIL= / "FAILED    : 0" 之类计数。
FAIL_LINE = re.compile(r"^(FAIL(?=[\s\-:]|$)|- FAIL)")
FAIL_TOKEN = re.compile(r"(^|\s)FAIL(ED)?\s*[=:]\s*(?!0\b)\d+")


def summarize(text):
    """挑出真正的失败行，外加最后几行。"""
    lines = [l.rstrip() for l in text.splitlines() if l.strip()]
    fail = []
    for l in lines:
        s = l.strip()
        if FAIL_LINE.match(s) or FAIL_TOKEN.search(s):
            fail.append(l)
        elif "RESULT:" in s and "PASS" not in s:
            fail.append(l)
    tail = lines[-3:] if lines else []
    return fail, tail


def rcon_alive(env):
    try:
        r = subprocess.run(
            [PY, "-I", "-X", "utf8", os.path.join(HERE, "rcon.py"), "list"],
            cwd=PROJECT, env=env, capture_output=True, timeout=25)
        return r.returncode == 0
    except Exception:
        return False


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
    env["PYTHONUTF8"] = "1"

    started_at = time.strftime("%Y-%m-%d %H:%M:%S")
    lines_out = ["# run_all_probes @ " + started_at, ""]

    def emit(s=""):
        print(s, flush=True)
        lines_out.append(s)

    # 开局前先把测试世界的地形补丁复位：wind/sail 的历史夹具曾经把 flat_test 锚点水位
    # 抬到 y=152，导致后面 raftgen 等探针误报"玩家没站在筏面上"（2026-11-21 踩到）。
    if "--no-repair" not in args and rcon_alive(env):
        repair = os.path.join(HERE, "m6_world_repair.py")
        if os.path.isfile(repair):
            emit("\n>>> m6_world_repair（开局前复位 flat_test 锚点地形）")
            try:
                rr = subprocess.run([PY, "-I", "-X", "utf8", repair], cwd=PROJECT, env=env,
                                    capture_output=True, timeout=300)
                rtxt = rr.stdout.decode("utf-8", "replace")
                for l in rtxt.strip().splitlines()[-4:]:
                    emit("    " + l)
                if rr.returncode != 0:
                    emit("    [警告] 世界修复脚本返回非 0，继续跑（失败项会在 C1 暴露）")
            except subprocess.TimeoutExpired:
                emit("    [警告] 世界修复脚本超时，继续跑")

    results = []
    aborted = False
    for name in PROBES:
        if only is not None and name not in only:
            continue
        if name in skip:
            continue
        if aborted:
            results.append((name, "SKIP", [], []))
            continue
        if not rcon_alive(env):
            emit("\n!!! 服务端 RCON 不可达，终止批量跑（后续标记 SKIP）")
            aborted = True
            results.append((name, "SKIP", [], []))
            continue
        path = os.path.join(HERE, name + ".py")
        if not os.path.isfile(path):
            results.append((name, "MISSING", [], []))
            continue
        emit("\n" + "=" * 70)
        emit(">>> " + name)
        emit("=" * 70)
        t0 = time.time()
        try:
            r = subprocess.run([PY, "-I", "-X", "utf8", path], cwd=PROJECT, env=env,
                               capture_output=True, timeout=PROBE_TIMEOUTS.get(name, TIMEOUT))
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
            emit("    " + l)
        if err.strip():
            for l in err.strip().splitlines()[-3:]:
                emit("    [stderr] " + l)
        if code == -1:
            status = "TIMEOUT"
        elif code == 0 and not fails:
            status = "PASS"
        else:
            status = "FAIL"
        results.append((name, status, fails, tail))
        emit("  -> " + status + " (" + str(dur) + "s)")
        time.sleep(3)

    emit("\n" + "=" * 70)
    emit("汇总 @ " + time.strftime("%Y-%m-%d %H:%M:%S"))
    emit("=" * 70)
    ok = sum(1 for r in results if r[1] == "PASS")
    for name, status, fails, _ in results:
        mark = "OK  " if status == "PASS" else "!!  "
        emit(mark + name.ljust(24) + status)
        for f in fails[:4]:
            emit("      - " + f[:140])
    emit("\n%d/%d 通过" % (ok, len([r for r in results if r[1] != "SKIP"])))
    with io.open(SUMMARY_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(lines_out) + "\n")
    print("（汇总已写入 %s）" % SUMMARY_PATH, flush=True)
    return 0 if ok == len([r for r in results if r[1] != "SKIP"]) else 1


if __name__ == "__main__":
    sys.exit(main())
