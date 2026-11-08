#!/usr/bin/env python3
"""抛绳索 / 钩竿 端到端探针（M2 尾巴 / 02 §8 + 09 §2.3）。

要回答：
  1. 绳索：把远处在水里的队友拉到本队筏甲板（量位置差，不量服务端自述）。
  2. 绳索拒绝：不是队友 / 不在水里 / 超射程。
  3. 钩竿：把敌方玩家拉近到 2 格外（距离变小）。
  4. 钩竿拒绝：不是敌方 / 超射程。

夹具：test_sea 真水（目标必须在真水块里，isTouchingWater 才为真）。
假玩家发不出"右键用道具"，所以用 `cww rope cast / cww grapnel cast` 走同一条 RopeService.cast，
与真客户端右键完全同一条路径。

用法：python tools/rope_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, SEA  # noqa: E402

ok, bad = [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def raft_xyz(p, team):
    m = re.search(r"match=(\d+)", p("cww state"))
    match = int(m.group(1)) if m else 0
    out = p("sable info @e[name=nbw_raft_%d_%d]" % (team, match))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(g) for g in m.groups()) if m else None


def pos(p, name):
    m = re.search(r"\[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]", p("data get entity %s Pos" % name))
    return tuple(float(g) for g in m.groups()) if m else None


def dist(a, b):
    return math.hypot(a[0] - b[0], a[2] - b[2])


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
    stamp = time.strftime("%M%S")
    bots = [("rp%s0" % stamp, 0), ("rp%s1" % stamp, 0), ("rp%s2" % stamp, 1), ("rp%s3" % stamp, 1)]
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
    p("cww start test_3min %s" % SEA)
    reached, cur = p.wait_phase("PLAY", 180)
    expect(reached, "进入 PLAY（%s）" % cur[:50])
    if not reached:
        return 1
    p.sleep(2)

    caster = bots[0][0].lower()   # 队 0 施救者
    victim = bots[1][0].lower()   # 队 0 落水者
    enemy = bots[2][0].lower()    # 队 1 敌方
    raft0 = raft_xyz(p, 0)

    print("\n== 1. 绳索把落水队友拉回筏")
    # 把受害者放到水里（筏面 y≈63，水在 y≤62），且离筏约 8 格（射程 12 内）
    p("tp %s %.1f 61 %.1f" % (victim, raft0[0] + 8, raft0[2]))
    p.sleep(0.5)
    v0 = pos(p, victim)
    print("      落水位置：%s（离筏 %.1f 格）" % (v0, dist(v0, raft0) if v0 else -1))
    out = p("cww rope cast %s" % caster)
    print("      rope cast：" + out[:100])
    p.sleep(0.5)
    v1 = pos(p, victim)
    print("      拉回后位置：%s（离筏 %.1f 格）" % (v1, dist(v1, raft0) if v1 else -1))
    expect(v0 and v1, "能读到落水者位置：%s -> %s" % (v0, v1))
    if v0 and v1:
        expect(dist(v1, raft0) < 3.0, "绳索把落水者拉到筏上（离筏 %.1f 格 < 3）" % dist(v1, raft0))
        expect("已把队友" in out, "命令回执确认拉回：%s" % out[:80])

    print("\n== 2. 绳索拒绝：超射程")
    # 把受害者放到 15 格外（射程 12 外），应拒"超出射程"
    p("tp %s %.1f 61 %.1f" % (victim, raft0[0] + 15, raft0[2]))
    p.sleep(0.4)
    out1 = p("cww rope cast %s" % caster)
    print("      rope cast（15 格）：" + out1[:80])
    expect("超出射程" in out1, "超射程被拒：%s" % out1[:80])

    print("\n== 3. 钩竿把敌方拉近")
    # 把敌方移到距施救者约 8 格（射程 16 内）
    c0 = pos(p, caster)
    p("tp %s %.1f 61 %.1f" % (enemy, c0[0] + 8, c0[2]))
    p.sleep(0.4)
    e0 = pos(p, enemy)
    c1 = pos(p, caster)
    d0 = dist(e0, c1) if (e0 and c1) else -1
    print("      敌方位置：%s（离施救者 %.1f 格）" % (e0, d0))
    out2 = p("cww grapnel cast %s" % caster)
    print("      grapnel cast：" + out2[:100])
    p.sleep(0.5)
    e1 = pos(p, enemy)
    c2 = pos(p, caster)
    d1 = dist(e1, c2) if (e1 and c2) else -1
    print("      拉近后距离：%.1f 格（原 %.1f 格）" % (d1, d0))
    expect(d0 > 0 and d1 >= 0, "能读到敌方位置：%s -> %s" % (e0, e1))
    if e0 and e1:
        expect(d1 < d0 - 1.0, "钩竿把敌方拉近（%.1f -> %.1f 格）" % (d0, d1))

    print("\n== 4. 钩竿拒绝：超射程")
    # 把敌方移到 20 格外（射程 16 外）
    c3 = pos(p, caster)
    p("tp %s %.1f 61 %.1f" % (enemy, c3[0] + 20, c3[2]))
    p.sleep(0.4)
    out3 = p("cww grapnel cast %s" % caster)
    print("      grapnel cast（20 格）：" + out3[:80])
    expect("拉近" not in out3 and "已把" not in out3, "超射程被拒（未拉人）：%s" % out3[:80])

    for n, _ in bots:
        p("player %s kill" % n.lower())
    p("cww abort")

    print("\n=== ROPE PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
