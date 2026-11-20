#!/usr/bin/env python3
"""幸存者跟随探针（M5 跟随收口，2026-11-14）。

要回答三件事：
  1. 开局每队都有幸存者（cww survivor）。
  2. 村民生成在筏面附近（水平距离 <= 2 格）。
  3. 遥控器把筏子开走 >3 格后，村民同向移动 >3 格，且仍贴着筏面（<=2.5 格）。

夹具：flat_test 旱地；假玩家站得稳、筏子不泡水。幸存者的服务端重定位
（SurvivorService.followRafts）与真客户端走同一条路径；这里不需要真客户端。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, wait_clean_lobby, raft_name, DRY_ARENA  # noqa: E402

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def raft_pos(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(x) for x in m.groups()) if m else None


def _parse_pos(out):
    m = re.search(r"\[([-\d.]+)d?,\s*([-\d.]+)d?,\s*([-\d.]+)d?\]", out)
    return tuple(float(x) for x in m.groups()) if m else None


def villager_pos(p, team):
    # 以筏心为命令源取"最近的那只同名村民"：世界里可能有上一轮持久化的旧村民，
    # 不能只按 name+limit=1 随便抓一只（2026-11-14 实测抓到旧 test_sea 锚点那只）。
    rp = raft_pos(p, team)
    if rp:
        out = p('execute positioned %.2f %.2f %.2f run data get entity '
                '@e[type=minecraft:villager,name="§a幸存者·队%d",limit=1,sort=nearest] Pos'
                % (rp[0], rp[1], rp[2], team))
        pos = _parse_pos(out)
        if pos:
            return pos
    # 后备：名字匹配失败时取筏心附近任意最近村民。
    if not rp:
        return None
    out = p("execute positioned %.2f %.2f %.2f run data get entity "
            "@e[type=minecraft:villager,limit=1,sort=nearest] Pos" % rp)
    return _parse_pos(out)


def horiz(a, b):
    return math.hypot(a[0] - b[0], a[2] - b[2])


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（phase=LOBBY queue=0 且无占席会话）：%s" % st.replace("\n", " ")[:140])
    if not clean:
        return 1

    bots = [("sv%s0" % time.strftime("%M%S"), 0), ("sv%s1" % time.strftime("%M%S"), 1)]
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

    print("== 1. 幸存者已生成")
    sv = p("cww survivor")
    print("      <%s>" % sv[:220])
    expect("队0 幸存者=" in sv and "队1 幸存者=" in sv, "两队都有幸存者：%s" % sv[:120])

    print("== 2. 村民在筏面附近")
    r0 = raft_pos(p, 0)
    v0 = villager_pos(p, 0)
    print("      筏=%s 村民=%s" % (r0, v0))
    expect(r0 is not None and v0 is not None, "能读到筏与村民位置")
    if not (r0 and v0):
        return 1
    d0 = horiz(r0, v0)
    expect(d0 <= 2.0, "开局村民贴着筏面（水平距离 %.2f <= 2.0）" % d0)

    print("== 3. 筏子开走，村民跟着走")
    p("cww remote move 0 w 160")
    p.sleep(5)
    r1 = raft_pos(p, 0)
    v1 = villager_pos(p, 0)
    print("      筏=%s 村民=%s" % (r1, v1))
    expect(r1 is not None and v1 is not None, "移动后仍能读到位置")
    if not (r1 and v1):
        return 1
    raft_moved = horiz(r0, r1)
    villager_moved = horiz(v0, v1)
    d1 = horiz(r1, v1)
    print("      筏位移=%.2f 村民位移=%.2f 距离=%.2f" % (raft_moved, villager_moved, d1))
    expect(raft_moved >= 3.0, "筏子确实开走（%.2f >= 3.0）" % raft_moved)
    expect(villager_moved >= 3.0, "村民跟着移动（%.2f >= 3.0）" % villager_moved)
    expect(d1 <= 2.5, "村民仍在筏面附近（%.2f <= 2.5）" % d1)

    print("== 4. 收尾")
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
