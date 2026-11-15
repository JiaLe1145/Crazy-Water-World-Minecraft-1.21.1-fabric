#!/usr/bin/env python3
"""反作弊/泄露用例探针（Gate G5，2026-11-15）。

覆盖能自动化的三条：
  1. 近战攻速超频：同一 tick 连判两次，第二发必须被服务端拒绝。
  2. 旁观者不能用绳索/钩竿：淘汰观战者即使伪造命令也不能拉人。
  3. 远程 hitscan 不能穿墙：中间有方块时打不中，拆掉后同一发能命中。

**测不了的**（Carpet 假玩家发不出自定义包 / 不能模拟真实客户端交互）：
  - 伪造 nbw:hold / nbw:move 包、包重放、包洪泛；
  - 旁观者伪造远程武器/建造交互包；
  这些在 docs/dev/M5-ANTICHEAT.md 里标 INCONCLUSIVE/需真客户端，不在这里凑绿灯。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, wait_clean_lobby, DRY_ARENA  # noqa: E402

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def health(p, name):
    out = p("data get entity %s Health" % name)
    m = re.search(r"([\d.]+)f?\s*$", out.strip())
    return float(m.group(1)) if m else None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY：%s" % st.replace("\n", " ")[:120])
    if not clean:
        return 1
    p("fill 998 150 998 1002 153 1012 minecraft:air")   # 清上一轮可能残留的石板/墙

    tag = time.strftime("%M%S")
    a = "se%sa" % tag
    b = "se%sb" % tag
    bots = [(a, 0), (b, 1)]
    for n, _ in bots:
        p("player %s kill" % n)
        p("player %s spawn" % n)
    for _ in range(30):
        listing = p("list").lower()
        if all(n in listing for n, _ in bots):
            break
        p.sleep(1)
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    p("cww start test_3min %s" % DRY_ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        return 1
    keep_alive(p, bots)
    p.sleep(1)

    print("== 1. 近战攻速超频：第二发必须被拒")
    p("item replace entity %s weapon.mainhand with nbw:cutlass 1" % a)
    p("tp %s 1000.5 149 1000.5" % a)
    p("tp %s 1000.5 149 1002.5" % b)
    p.sleep(0.5)
    out = p("cww weapon probe2 %s %s cutlass" % (a, b))
    print("      <%s>" % out[:320])
    expect("#1" in out and ("允许" in out or "PASS" in out or "通过" in out),
           "第一发允许（%s）" % out[:100])
    expect("#2" in out and ("冷却" in out or "拒" in out or "超频" in out),
           "第二发被攻速闸门拒绝（%s）" % out[:160])

    print("== 2. 旁观者不能用绳索/钩竿")
    p("gamemode spectator %s" % a)
    p.sleep(0.5)
    rope = p("cww rope cast %s" % a)
    grap = p("cww grapnel cast %s" % a)
    print("      rope=<%s>" % rope[:160])
    print("      grapnel=<%s>" % grap[:160])
    expect("旁观者" in rope, "旁观者绳索被拒（%s）" % rope[:120])
    expect("旁观者" in grap, "旁观者钩竿被拒（%s）" % grap[:120])
    p("gamemode survival %s" % a)
    p.sleep(0.5)

    print("== 3. 远程 hitscan：先正对照（无墙能命中），再负对照（有墙打不中）")
    # flat_test 水面在 y=149，直接 tp 到 149 会泡水漂走（实测 1s 偏 1 格）。
    # 先铺一层 y=150 的干燥石板，人站 151，射线走 152.6，避免“瞄着 A 漂到 B”。
    p("fill 998 150 998 1002 150 1012 minecraft:stone")
    p("item replace entity %s weapon.mainhand with nbw:hand_crossbow 1" % a)
    p("tp %s 1000.5 151 1000.5" % a)
    p("tp %s 1000.5 151 1010.5" % b)
    p("effect clear %s" % b)
    p("fill 999 151 1004 1001 153 1006 minecraft:air")
    p.sleep(1.0)
    print("      a pos=<%s>" % p("data get entity %s Pos" % a)[:160])
    print("      b pos=<%s>" % p("data get entity %s Pos" % b)[:160])
    p("player %s look at 1000.5 151.9 1010.5" % a)
    h0 = health(p, b)
    out1 = p("cww weapon ranged %s" % a)
    out2 = p("cww weapon ranged %s" % a)   # 立刻第二发：必须仍在冷却里
    print("      #1 " + out1[:180])
    print("      #2 " + out2[:180])
    p.sleep(1.5)
    h1 = health(p, b)
    print("      无墙：health %s -> %s" % (h0, h1))
    positive = h0 is not None and h1 is not None and h1 < h0 - 0.01
    if not positive:
        inconclusive("远程夹具没造成伤害（无墙也没掉血）；先查主手/瞄准/冷却")
    else:
        expect(True, "无墙正对照：远程能命中（%s -> %s）" % (h0, h1))
        expect("冷却剩余(执行前)=0tick" in out1, "第一发执行前冷却为 0")
        m = re.search(r"冷却剩余\(执行前\)=(\d+)tick", out2)
        expect(m is not None and int(m.group(1)) > 0,
               "第二发执行前仍在冷却（实际 %s tick）" % (m.group(1) if m else "?"))
        p("fill 999 151 1004 1001 153 1006 minecraft:stone")
        p.sleep(2.2)   # 手弩装填 1.6s
        p("player %s look at 1000.5 151.9 1010.5" % a)
        h2 = health(p, b)
        print("      " + p("cww weapon ranged %s" % a)[:160])
        p.sleep(1.5)
        h3 = health(p, b)
        print("      有墙：health %s -> %s" % (h2, h3))
        expect(h2 is not None and h3 is not None and h3 >= h2 - 0.01,
               "有墙时远程打不中（血量不降：%s -> %s）" % (h2, h3))
    p("fill 998 150 998 1002 153 1012 minecraft:air")

    print("== 4. 收尾")
    p("fill 999 149 1004 1001 151 1006 minecraft:air")
    p("cww abort")
    p.sleep(2)
    for n, _ in bots:
        p("player %s kill" % n)
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
