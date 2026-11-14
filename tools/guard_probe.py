#!/usr/bin/env python3
"""幸存者卫兵守筏探针（M5 P1 第四片，2026-11-14）。

要回答四件事：
  1. `/cww survivor job 0 0 guard` 后，队 0 出现 1 名卫兵，队 1 保持水手。
  2. 敌方玩家进入筏心 12 格后，约 1.2 秒内掉 6.5 HP（1 发）或 13 HP（2 发）。
  3. 同一名敌方玩家退到 12 格外，2.2 秒内一滴血都不掉。
  4. 同队玩家站在自己筏上，卫兵不误伤。

夹具：flat_test 旱地；两队假玩家保持 water_breathing。卫兵走服务端 tick 链路，
与真人局完全一致；`cww guard` 提供本局命中数作为独立证据。
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


def raft_pos(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(x) for x in m.groups()) if m else None


def health(p, name):
    out = p("data get entity %s Health" % name.lower())
    m = re.search(r"entity data:\s*([-\d.]+)", out)
    return float(m.group(1)) if m else None


def tp(p, name, x, y, z):
    p("tp %s %.2f %.2f %.2f" % (name.lower(), x, y, z))


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（%s）" % st.replace("\n", " ")[:140])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("hg%s0" % tag, 0), ("hg%s1" % tag, 1)]
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

    print("== 1. 只给队 0 换一个卫兵")
    print("  " + p("cww survivor job 0 0 guard")[:120])
    line = p("cww survivor").replace("\n", " ")
    print("      " + line[:220])
    print("      " + p("cww guard")[:160])
    expect("卫兵=1" in p("cww guard"), "队 0 出现 1 名卫兵")

    rp = raft_pos(p, 0)
    expect(rp is not None, "能读到队 0 的筏心位置")
    if rp is None:
        return 1
    cx, cy, cz = rp
    enemy, ally = bots[1][0].lower(), bots[0][0].lower()

    print("== 2. 敌方进入 12 格：第一发应约 6.5 HP")
    # 难度必须先切 normal：MC 对 mobAttack 打玩家按难度缩放，easy = 0.65×，
    # 6.5 会变成 4.25（2026-11-14 实测），读数会误以为代码错了。
    prev_difficulty = "easy"
    d = p("difficulty")
    m = re.search(r"(peaceful|easy|normal|hard)", d.lower())
    if m:
        prev_difficulty = m.group(1)
    print("      原难度=%s，临时切 normal" % prev_difficulty)
    p("difficulty normal")

    # flat_test 水面 y=149、筏心 y≈153；放到水面（筏心下方 4 格），在 12 格内且无坠落伤害。
    base = health(p, enemy)
    tp(p, enemy, cx + 1.5, cy - 4.0, cz)
    p.sleep(0.5)
    h1 = health(p, enemy)
    print("      enemy Health %s -> %s（筏心 %s）" % (base, h1, rp))
    expect(base is not None and h1 is not None, "两次血量都能读到")
    lost = (base - h1) if (base is not None and h1 is not None) else 0.0
    # 下限 4.5 是为了把 Easy 的 4.25 挡在外面；自然回血会让读数略低于 6.5。
    expect(4.5 <= lost <= 7.5, "normal 难度下第一发约 6.5 HP（实测 %.2f）" % lost)

    p.sleep(1.2)
    g1 = p("cww guard")
    mg = re.search(r"队0 卫兵=(\d+) 命中=(\d+)", g1)
    expect(mg is not None and int(mg.group(2)) >= 1,
           "1.2 秒内至少命中 1 次（实测 %s）" % ((mg.groups() if mg else "无"),))

    print("== 3. 血量补满后退到 12 格外：2.2 秒内不应再掉血")
    # 先撤出射程再治疗：否则"治疗→撤退"之间可能又挨一发，基线会被污染。
    far = 25.0
    tp(p, enemy, cx + far, cy - 4.0, cz)
    p.sleep(0.3)
    p("effect give %s minecraft:instant_health 1 3 true" % enemy)
    p.sleep(0.3)
    healed = health(p, enemy)
    print("      治疗后 Health=%s" % healed)
    p.sleep(2.2)
    h2 = health(p, enemy)
    print("      12 格外 Health=%s（治疗前 %.1f）" % (h2, healed or -1))
    expect(healed is not None and h2 is not None and abs(h2 - healed) < 0.05,
           "射程外完全不掉血（%.1f -> %.1f）" % ((healed or -1), (h2 or -1)))

    print("== 4. 同队玩家站在自己筏上：卫兵不应误伤")
    tp(p, ally, cx + 1.5, cy - 4.0, cz)
    p.sleep(0.2)
    a0 = health(p, ally)
    p.sleep(2.2)
    a1 = health(p, ally)
    print("      ally Health %.1f -> %.1f" % (a0 or -1, a1 or -1))
    expect(a0 is not None and a1 is not None and abs(a1 - a0) < 0.05,
           "同队不掉血（%.1f -> %.1f）" % ((a0 or -1), (a1 or -1)))

    print("== 5. 读数：卫兵命中数应 > 0")
    g = p("cww guard")
    print("      " + g[:200])
    m = re.search(r"队0 卫兵=(\d+) 命中=(\d+)", g)
    expect(m is not None and int(m.group(1)) >= 1 and int(m.group(2)) >= 1,
           "队 0 卫兵存在且至少命中 1 次（%s）" % ((m.groups() if m else "无"),))

    print("== 6. 收尾")
    p("cww abort")
    if "prev_difficulty" in locals():
        p("difficulty %s" % prev_difficulty)   # 恢复 dev 服的 easy，别把环境改脏
    p.sleep(2)
    for n, _ in bots:
        p("player %s kill" % n.lower())
    print("  清残留：" + repr(sweep_stale_bots(p)))

    print("\n== 汇总")
    print("  PASS=%d FAIL=%d INCONCLUSIVE=%d" % (len(ok), len(bad), len(inc)))
    for m in bad:
        print("  FAIL - " + m)
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
