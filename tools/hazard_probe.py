#!/usr/bin/env python3
"""海域障碍减速探针（2026-11-14）。

要回答：
  1. 筏子从障碍外进入暗礁矩形后，RaftMotionService 的目标速度从 0.05 降到 0.025。
  2. 障碍矩形之外仍是全速目标。

夹具：复制 test_sea 的坐标与地形，临时写一个 hazard_test arena，
在队 0 的南向航路上放一块 reef（z=954..966）。探针结束后删除该文件。
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, wait_clean_lobby, raft_name, ROOT  # noqa: E402

ARENA = "hazard_test"
ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def write_hazard_arena():
    src = os.path.join(ROOT, "src", "main", "resources", "data", "nbw", "arena", "test_sea.json")
    with open(src, encoding="utf-8") as fh:
        arena = json.load(fh)
    arena["id"] = ARENA
    arena["displayName"] = "障碍减速夹具"
    arena["canSelect"] = False
    arena["weight"] = 0
    arena["hazards"] = [{
        "type": "reef",
        "minX": -1012, "minZ": 954, "maxX": -988, "maxZ": 966,
    }]
    dst = os.path.join(ROOT, "run", "world", "cww", "arenas", ARENA + ".json")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(arena, ensure_ascii=False, indent=2))
    return dst


def raft_z(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return float(m.group(3)) if m else None


def target(p, team):
    out = p("cww wind read %d" % team)
    m = re.search(r"目标=([\d.]+)", out)
    return float(m.group(1)) if m else None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()
    arena_file = write_hazard_arena()
    print("== 0. 清场 + 写障碍夹具")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（%s）" % st.replace("\n", " ")[:120])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("hz%s0" % tag, 0), ("hz%s1" % tag, 1)]
    for n, _ in bots:
        p("player %s kill" % n.lower())
        p("player %s spawn" % n.lower())
    for _ in range(30):
        listing = p("list").lower()
        if all(n.lower() in listing for n, _ in bots):
            break
        p.sleep(1)
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, [(n.lower(), t) for n, t in bots])
    p("cww start test_3min %s" % ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        return 1
    p.sleep(2)

    print("== 1. 障碍外：目标速度 0.05")
    p("cww sail rig 0 1 south")
    p("cww wind set south 2.0")
    p.sleep(1)
    z0 = raft_z(p, 0)
    t0 = target(p, 0)
    print("      z=%s 目标=%s" % (z0, t0))
    expect(z0 is not None and t0 is not None, "能读到筏位置与目标速度")
    if z0 is None or t0 is None:
        return 1
    expect(z0 < 954, "采样点在障碍外（z=%.2f < 954）" % z0)
    expect(abs(t0 - 0.05) < 0.005, "障碍外目标速度约为 0.05（实测 %.4f）" % t0)

    print("== 2. 开进障碍：目标速度减半")
    z1 = z0
    for _ in range(40):
        z1 = raft_z(p, 0)
        if z1 is not None and z1 >= 955:
            break
        p.sleep(0.5)
    t1 = target(p, 0)
    print("      z=%s 目标=%s" % (z1, t1))
    expect(z1 is not None and z1 >= 955, "筏子已进入暗礁矩形（z=%.2f >= 955）" % (z1 or 0))
    expect(t1 is not None and abs(t1 - 0.025) < 0.005,
           "障碍内目标速度减半（实测 %.4f，期望 0.025）" % (t1 if t1 is not None else -1))

    print("== 3. 收尾")
    p("cww abort")
    p.sleep(2)
    for n, _ in bots:
        p("player %s kill" % n.lower())
    print("  清残留：" + repr(sweep_stale_bots(p)))
    try:
        os.remove(arena_file)
    except OSError:
        pass

    print("\n== 汇总")
    print("  satisfied=%d" % len(ok))
    for x in bad:
        print("  FAIL - %s" % x)
    for x in inc:
        print("  INCONCLUSIVE - %s" % x)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
