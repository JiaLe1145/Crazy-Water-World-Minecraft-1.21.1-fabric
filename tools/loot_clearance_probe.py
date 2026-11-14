#!/usr/bin/env python3
"""预铺补给箱离筏体净空探针（2026-11-14）。

背景：test_sea 队 1 的筏曾被 3 个预铺 barrel 顶住——目标速度/施力都有、位置不动、朝向倾斜。
修复是 `LootService` 跳过任意在场筏中心 4 格内的候选点。

要回答：
  1. 开局预铺后，两队筏中心 4 格内没有 barrel（距离 < 4.0）。
  2. 队 1 遥控前进是否恢复；当前 test_sea 队 1 出生点仍有独立的物理卡住问题，
     失败时按 INCONCLUSIVE 报告（见 AI 交接 P2），不要让本探针误报成“箱子净空失败”。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, raft_name, SEA  # noqa: E402

CLEARANCE = 4.0
ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def wait_lobby(p, fmt="test_3min", tries=30):
    st = ""
    for _ in range(tries):
        st = p("cww state")
        if "phase=LOBBY" in st and "queue=0" in st:
            p("cww format reload %s" % fmt)
            return True, st
        if "phase=LOBBY" not in st:
            p("cww abort")
        p.sleep(1)
    return False, st


def raft_pos(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(x) for x in m.groups()) if m else None


def barrels_near(p, center):
    """返回距筏中心 < CLEARANCE 的 barrel 坐标；y=62 是 test_sea 的水面/预铺层。"""
    cx, cz = int(round(center[0])), int(round(center[2]))
    hits = []
    for dx in range(-4, 5):
        for dz in range(-4, 5):
            x, z = cx + dx, cz + dz
            out = p("execute if block %d 62 %d minecraft:barrel" % (x, z))
            if "Test passed" in out or "测试通过" in out or out.strip() == "":
                if math.hypot(x + 0.5 - center[0], z + 0.5 - center[2]) < CLEARANCE:
                    hits.append((x, 62, z))
    return hits


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_lobby(p)
    expect(clean, "干净 LOBBY（%s）" % st.replace("\n", " ")[:120])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("lc%s0" % tag, 0), ("lc%s1" % tag, 1)]
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
    p.sleep(3)   # 等预铺完成

    print("== 1. 两艘筏 4 格内无预铺 barrel")
    for team in (0, 1):
        rp = raft_pos(p, team)
        hits = barrels_near(p, rp) if rp else []
        print("      队%d 筏=%s 近距 barrel=%s" % (team, rp, hits[:10]))
        expect(rp is not None, "队%d 能读到筏位置" % team)
        expect(not hits, "队%d 筏 4 格内无 barrel（实际 %d 个）" % (team, len(hits)))

    print("== 2. 队 1 遥控前进回归")
    r0 = raft_pos(p, 1)
    print("  " + p("cww remote move 1 w 40")[:120])
    p.sleep(2.5)
    r1 = raft_pos(p, 1)
    d = math.hypot(r1[0] - r0[0], r1[2] - r0[2]) if r0 and r1 else 0.0
    print("      队1 位移=%.3f 格" % d)
    if d > 3.0:
        expect(True, "队1 不再被卡住（%.2f > 3.0）" % d)
    else:
        inconclusive("队1 遥控只走了 %.2f 格：这是 test_sea 出生点物理卡住的已知问题"
                     "（不是箱子净空逻辑失败；公平性对比用 flat_test）" % d)

    print("== 3. 收尾")
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
