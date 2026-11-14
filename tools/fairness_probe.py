#!/usr/bin/env python3
"""同规格筏航速方差探针（P-01 竞技公平承诺，2026-11-14）。

要回答：
  1. 两艘同规格（4×4、同材质、无帆）的筏，在同一段遥控输入下都能前进 >5 格。
  2. 两艘筏的位移离散度 <= 5%（P-01 的“同规格筏航速方差”指标）。

做法：flat_test 上两队各 1 个假玩家，PLAY 稳定后同时 `cww remote move <队> w 40`，
等 2.5 秒，量两艘筏的水平位移。遥控路径与真客户端完全同一条服务端链路
（假玩家发不出 nbw:move，`cww remote move` 是等价的服务端入口）。

场地备注：test_sea 测试世界仍可能残留旧补给箱/旧筏，挡住其中一队的航道；
本探针固定用无 prefill 的 flat_test 做“同规格筏”对比。test_sea 的旧筏已由
RaftSpawner 开局按前缀清扫；旧补给箱由 LootService.reset 局后还原成水。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, raft_name, DRY_ARENA  # noqa: E402

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


def wait_lobby(p, fmt="test_3min", tries=30):
    """新起服时 team 壳会被 RoundMachine 构造出来（teams=4），不能要求 teams=0。

    只要求真正没有对局在跑（LOBBY + queue=0），然后 reload 本探针要用的赛制；
    `cww start` 的 ENROLL 会按新规格重建队伍。
    """
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


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（%s）" % st.replace("\n", " ")[:120])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("sf%s0" % tag, 0), ("sf%s1" % tag, 1)]
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
    p("cww start test_3min %s" % DRY_ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        return 1
    p.sleep(2)

    print("== 1. 同规格筏 + 同一段遥控输入")
    a0 = raft_pos(p, 0)
    b0 = raft_pos(p, 1)
    print("  " + p("cww remote move 0 w 40")[:120])
    print("  " + p("cww remote move 1 w 40")[:120])
    p.sleep(2.5)
    a1 = raft_pos(p, 0)
    b1 = raft_pos(p, 1)
    da = horiz(a0, a1) if a0 and a1 else 0.0
    db = horiz(b0, b1) if b0 and b1 else 0.0
    print("      队0 位移=%.3f 格（%s -> %s）" % (da, a0, a1))
    print("      队1 位移=%.3f 格（%s -> %s）" % (db, b0, b1))
    expect(a0 is not None and b0 is not None and a1 is not None and b1 is not None, "四个位置都能读到")
    expect(da > 5.0 and db > 5.0, "两艘筏都真的前进了 >5 格（%.2f / %.2f）" % (da, db))

    print("== 2. 离散度 <= 5%")
    denom = max(da, db)
    spread = abs(da - db) / denom if denom > 0 else 1.0
    print("      离散度 = |%.3f - %.3f| / %.3f = %.2f%%" % (da, db, denom, spread * 100))
    expect(spread <= 0.05, "同规格筏航速方差 <= 5%%（实测 %.2f%%）" % (spread * 100))

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
