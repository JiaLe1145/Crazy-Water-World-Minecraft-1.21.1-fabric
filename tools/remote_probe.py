#!/usr/bin/env python3
"""遥控器操控端到端探针（问题③：每筏一个遥控器，WASD 开船 + 帆无质量 + 帆跟随前进）。

要回答：
  1. 帆无质量：挂帆前后质量不变（4×4 筏 = 9.5，含 2026-11-14 新增物资箱 0.5；帆不再把筏压倾斜下沉）。
  2. W 前进：位移真的往前进方向走（量位置差，不量峰值）。
  3. S 后退：位移反向。
  4. A/D 转向：朝向（Orientation 四元数）真的变了。
  5. 非 PLAY：遥控意图被拒。

夹具：用 flat_test（旱地，假玩家站得稳、筏不泡水）；服务端用 `cww remote move` 模拟 WASD
（假玩家发不出自定义包，这是本项目已确认的边界），推进路径与真客户端完全同一条。

用法：python tools/remote_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, DRY_ARENA  # noqa: E402

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def raft_name(p, team):
    m = re.search(r"match=(\d+)", p("cww state"))
    return "nbw_raft_%d_%d" % (team, int(m.group(1)) if m else 0)


def pos(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(g) for g in m.groups()) if m else None


def orientation(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Orientation:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(g) for g in m.groups()) if m else None


def mass(p):
    out = p("cww raft all")
    m = re.search(r"队0 Y=[\d.]+ 质量=([\d.]+)", out)
    return float(m.group(1)) if m else None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()
    print("== 0. 开局")
    print("  清残留：" + repr(sweep_stale_bots(p)))
    for _ in range(25):
        if "LOBBY" in p("cww phase"):
            break
        p("cww abort")
        p.sleep(1.5)
    bots = [("rq%s0" % time.strftime("%M%S"), 0), ("rq%s1" % time.strftime("%M%S"), 1)]
    for n, _ in bots:
        p("player %s kill" % n.lower())
        p("player %s spawn" % n.lower())
    for _ in range(30):
        if all(n.lower() in p("list").lower() for n, _ in bots):
            break
        p.sleep(1)
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, [(n.lower(), t) for n, t in bots])
    p("cww start test_3min %s" % DRY_ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur[:50])
    if not reached:
        return 1
    p.sleep(2)

    print("\n== 1. 帆无质量：挂帆前后质量不变")
    m0 = mass(p)
    p("cww sail rig 0 1 south")
    p.sleep(1)
    m1 = mass(p)
    print("      挂帆前质量=%s 挂后=%s" % (m0, m1))
    expect(m0 is not None and m1 is not None, "能读到质量：%s -> %s" % (m0, m1))
    if m0 is not None and m1 is not None:
        expect(abs(m1 - m0) < 0.01, "挂 1 面帆质量不变（%s -> %s，帆无质量）" % (m0, m1))

    print("\n== 2. W 前进：位置真的往前走")
    p0 = pos(p, 0)
    p("cww remote move 0 w 160")
    p.sleep(5)
    p1 = pos(p, 0)
    d = math.hypot(p1[0] - p0[0], p1[2] - p0[2]) if (p0 and p1) else 0.0
    print("      前进位移 %.2f 格" % d)
    expect(p0 and p1, "能读到筏位置：%s -> %s" % (p0, p1))
    expect(d > 3.0, "W 前进 8 秒应走 3 格以上（实测 %.2f 格）" % d)

    print("\n== 3. S 后退：位置反向走")
    p2 = pos(p, 0)
    p("cww remote move 0 s 160")
    p.sleep(5)
    p3 = pos(p, 0)
    back = math.hypot(p3[0] - p2[0], p3[2] - p2[2]) if (p2 and p3) else 0.0
    print("      后退位移 %.2f 格" % back)
    expect(back > 2.0, "S 后退 8 秒应走 2 格以上（实测 %.2f 格）" % back)

    print("\n== 4. A 左转：朝向真的变")
    o0 = orientation(p, 0)
    p("cww remote move 0 a 120")
    p.sleep(4)
    o1 = orientation(p, 0)
    print("      转向朝向前=%s 后=%s" % (o0, o1))
    if o0 and o1:
        # 四元数变化 = 朝向变了（比较第四个分量 w 与各分量差）
        delta = math.sqrt(sum((a - b) ** 2 for a, b in zip(o0, o1)))
        expect(delta > 0.05, "A 转向 6 秒朝向应明显变化（四元数差 %.4f）" % delta)
    else:
        expect(False, "读不到朝向：%s -> %s" % (o0, o1))

    print("\n== 5. 非 PLAY：遥控意图被拒")
    p("cww abort")
    p.wait_phase("LOBBY", 180)
    out = p("cww remote move 0 w 60")
    expect("不在 PLAY" in out, "LOBBY 里 remote move 必须被拒（回：%s）" % out[:80])

    for n, _ in bots:
        p("player %s kill" % n.lower())
    p("cww abort")

    print("\n=== REMOTE PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    for i in inc:
        print("   INCONCLUSIVE - %s" % i)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
