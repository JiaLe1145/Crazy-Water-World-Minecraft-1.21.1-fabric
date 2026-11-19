#!/usr/bin/env python3
"""风暴完整效果探针（2026-11-14）。

要回答：
  1. 常态风下挂帆，筏子会走（正对照）。
  2. 强制触发 storm 后，帆不再出力：3 秒内位移接近 0。
  3. 45 秒后风暴结束，帆恢复出力：3 秒内位移再次 > 0。
  4. 日志里能看到风暴开始/结束。

夹具：test_sea（连续水面）；flat_test 的中央岛/水边会在 ~46s 后把筏子顶住，
不能用来判"风暴结束后帆恢复"。假玩家加 water_breathing 防溺死。
注意：test_3min 的事件池也是 storm，自动第一发在 60–90s；
探针在 ~5s 强制触发，风暴期间不会叠加，整轮在自动第一发之前结束。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, wait_clean_lobby, raft_name, SEA  # noqa: E402

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def raft_pos(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(x) for x in m.groups()) if m else None


def horiz(a, b):
    return math.hypot(a[0] - b[0], a[2] - b[2])


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（%s）" % st.replace("\n", " ")[:120])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("st%s0" % tag, 0), ("st%s1" % tag, 1)]
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
    p("cww start test_3min %s" % SEA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        return 1
    p.sleep(2)

    print("== 1. 正对照：挂帆 + 风，筏子会走")
    print("  " + p("cww sail rig 0 1 south")[:120])
    print("  " + p("cww wind set south 2.0")[:120])
    r0 = raft_pos(p, 0)
    print("      风况1: %s" % p("cww wind read 0")[:220])
    p.sleep(5)
    r1 = raft_pos(p, 0)
    moved = horiz(r0, r1) if r0 and r1 else 0.0
    print("      r0=%s r1=%s" % (r0, r1))
    print("      5 秒位移 %.2f 格" % moved)
    expect(r0 is not None and r1 is not None, "能读到筏位置")
    expect(moved > 1.0, "常态风下帆确实推进（%.2f > 1.0）" % moved)

    print("== 2. 风暴：帆失效")
    p("cww event fire storm")
    p.sleep(2)   # 让残余速度衰减
    r2 = raft_pos(p, 0)
    print("      风况2: %s" % p("cww wind read 0")[:220])
    p.sleep(3)
    r3 = raft_pos(p, 0)
    storm_move = horiz(r2, r3) if r2 and r3 else 999.0
    print("      r2=%s r3=%s" % (r2, r3))
    print("      风暴中 3 秒位移 %.2f 格" % storm_move)
    expect(storm_move < 0.5, "风暴期间帆不再出力（%.2f < 0.5）" % storm_move)

    print("== 3. 45 秒后风暴结束，帆恢复")
    p.sleep(46)
    r4 = raft_pos(p, 0)
    print("      风暴后风况: %s" % p("cww wind read 0")[:220])
    p.sleep(3)
    r5 = raft_pos(p, 0)
    after_move = horiz(r4, r5) if r4 and r5 else 0.0
    print("      r4=%s r5=%s" % (r4, r5))
    print("      风暴后 3 秒位移 %.2f 格" % after_move)
    expect(after_move > 0.5, "风暴结束后帆恢复推进（%.2f > 0.5）" % after_move)

    print("== 4. 日志")
    log = p.log()
    expect("风暴来袭" in log, "日志出现风暴开始公告")
    expect("风暴结束" in log or "风暴 结束" in log, "日志出现风暴结束公告")

    print("== 5. 收尾")
    p("cww abort")
    p.sleep(2)
    for n, _ in bots:
        p("player %s kill" % n.lower())
    print("  清残留：" + repr(sweep_stale_bots(p)))

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
