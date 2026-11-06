#!/usr/bin/env python3
"""落水与扒边端到端探针（M2-8 / 01 §8）。

前置：
  1. ./gradlew runServer 已起来，日志里有 [rescue] 落水与扒边已挂载；
  2. RCON_PORT=25576 RCON_PASSWORD=nbwm0；
  3. test_sea 的海已经建过。

跑法：
  python tools/rescue_probe.py

四件事，按"能不能失败"排：
  * **坐标口径**（这条要是错的，后面全是空中楼阁）：把玩家精确放到筏的位姿原点，
    服务端算出的局部坐标必须是 (0,0,0) 附近、间隙 0。这是 `SableRafts.worldToLocal`
    那条位姿逆变换唯一的可证伪形式，也是 §3.12 建造规则同一套口径。
  * **时序是真的**：在水里、贴着自家船壳、续住包，2 秒后必须真的升高约 DECK_LIFT 格，
    并且 `上筏` 计数 +1。高度用 Y 读数量，不看服务端自己说什么——
    "lifted() 说抬了 1.3 格"不等于人真的在 1.3 格之上。
  * **四道门各自单独拦得住**：够不着 / 不在水里 / 不是自家筏 / 非 PLAY 阶段。
  * **打断是真的**：攀爬中挨一刀必须掉回去，而且被打落的计数要动。

假玩家上不了筏面（会穿过甲板落水），这反而正好是本探针需要的状态：他本来就在水里。
但"客户端长按"这条输入假玩家发不出来（自定义包只能真客户端发），所以这里用
`cww rescue hold` 驱动同一个服务端窗口；真客户端手感留在 §6 真人清单。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, keep_alive, sweep_stale_bots, write_long_format, SEA  # noqa: E402

ok = []
bad = []
inc = []

FORMAT = "sail_long"
WATER_LEVEL = 62
HULL_OFFSET = 1.2        # 站在水面、离船壳 1.2 格：够得着（伸手 2.5）但人不在筏上


def expect(cond, msg):
    if cond:
        ok.append(msg)
        print("  PASS - %s" % msg)
    else:
        bad.append(msg)
        print("  FAIL - %s" % msg)


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def status(p):
    return p("cww rescue status")


def counts(p):
    m = re.search(r"扒住=(\d+) 上筏=(\d+) 被打落=(\d+)", status(p))
    return tuple(int(g) for g in m.groups()) if m else None


def raft_xyz(p, team):
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)",
                  p("sable info @e[name=nbw_raft_%d]" % team))
    return tuple(float(g) for g in m.groups()) if m else None


def y_of(p, name):
    m = re.search(r"\[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]", p("data get entity %s Pos" % name))
    return (float(m.group(2)), float(m.group(1)), float(m.group(3))) if m else None


def half_width(p):
    """从服务端读初始筏边长：cww raft status 的 Y 那行没有边长，改用 build status 的列数开方。"""
    m = re.search(r"队0 方块=(\d+) 列=(\d+)", p("cww build status"))
    if not m:
        return None
    side = int(round(int(m.group(2)) ** 0.5))
    return -(-(side >> 1)) if side % 2 else side // 2   # halfWidth = max(-start, end)


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
    else:
        expect(False, "遗留对局收不掉：%s" % p("cww phase")[:60])
        return 1
    write_long_format()
    p("cww format reload %s" % FORMAT)
    stamp = time.strftime("%M%S")
    bots = [("w%sA0" % stamp, 0), ("w%sA1" % stamp, 0), ("w%sB0" % stamp, 1), ("w%sB1" % stamp, 1)]
    for n, _ in bots:
        p("player %s kill" % n.lower())
    for n, _ in bots:
        p("player %s spawn" % n)
    for _ in range(30):
        if all(n.lower() in p("list").lower() for n, _ in bots):
            break
        p.sleep(1)
    else:
        expect(False, "假玩家没全部上线：%s" % p("list")[:160])
        return 1
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, bots)
    p("cww start %s %s" % (FORMAT, SEA))
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    reached, cur = p.wait_phase("PLAY", 240)
    expect(reached, "sail_long %s 能进到 PLAY（%s）" % (SEA, cur[:40]))
    if not reached:
        return 1
    p.sleep(2)
    a1 = bots[0][0].lower()
    b1 = bots[2][0].lower()
    r0 = raft_xyz(p, 0)
    r1 = raft_xyz(p, 1)
    half = half_width(p)
    expect(r0 and r1 and half is not None, "读到两队筏坐标与甲板半宽：%s / %s / half=%s" % (r0, r1, half))
    if not (r0 and r1) or half is None:
        return 1
    print("      挂载确认：" + p("cww rescue status")[:160])

    print("\n== 1. 坐标口径：精确放到筏的位姿原点，局部坐标必须是 (0,0,0)")
    p("tp %s %.2f %.2f %.2f" % (a1, r0[0], r0[1], r0[2]))
    p.sleep(0.4)
    rep = p("cww rescue hold %s 1" % a1)
    print("      hold 回包：" + rep[:200])
    m = re.search(r"间隙 ([-\d.]+) 格", rep)
    gap = float(m.group(1)) if m else 99.0
    expect(gap < 0.05, "人在位姿原点上，算出来的间隙必须≈0（实测 %.3f）——否则世界→局部换算错了" % gap)
    m2 = re.search(r"本地 \(([-\d.]+),([-\d.]+),([-\d.]+)\)", status(p) + rep + p("cww rescue last"))
    if m2:
        lx, ly, lz = (float(m2.group(i)) for i in (1, 2, 3))
        expect(abs(lx) < 0.6 and abs(lz) < 0.6,
               "位姿原点喂进 worldToLocal 的 x/z 必须落在 0 附近，实测 (%.2f,%.2f,%.2f)"
               "（y 会偏：假玩家站不住甲板，会漂下去 1~2 格，那是夹具限制不是换算错）" % (lx, ly, lz))
    else:
        inconclusive("没在回包里找到局部坐标读数，坐标口径只验到间隙这一半")

    print("\n== 2. 贴着船壳：扒住并真的爬上来")
    tp_at = (r0[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, r0[2])
    p("tp %s %.2f %.2f %.2f" % (a1, tp_at[0], tp_at[1], tp_at[2]))
    p.sleep(0.5)
    y0 = y_of(p, a1)
    c0 = counts(p)
    rep = p("cww rescue hold %s 60" % a1)
    print("      hold 回包：" + rep[:180])
    # 边爬边采样，取最高点。为什么不能只读"上筏之后"的 Y：假玩家站不住甲板（会穿过去落水），
    # 落地后再读已经被拽回水面之下了 —— 那读出来的是夹具限制，不是这次攀爬到没到甲板面。
    samples = []
    for _ in range(14):
        yy = y_of(p, a1)
        if yy:
            samples.append(yy[0])
        p.sleep(0.18)
    y1 = y_of(p, a1)
    c1 = counts(p)
    peak = max(samples) if samples else None
    print("      攀爬中 Y 采样 %d 个：%s" % (len(samples), [round(s, 2) for s in samples[:14]]))
    expect(y0 and y1 and peak is not None, "Y 读数与采样都拿到：起点 %s 终点 %s 峰值 %s" % (y0, y1, peak))
    if y0 and y1 and c0 and c1 and peak is not None:
        rise = peak - y0[0]
        print("      ΔY=%.3f 格，计数 扒住=%d 上筏=%d（前值 %s → %s）"
              % (rise, c1[0] - c0[0], c1[1] - c0[1], c0, c1))
        expect(c1[0] > c0[0], "必须记录到一次「扒住」，否则根本没进状态机")
        expect(c1[1] == c0[1] + 1, "2.6 秒后必须刚好完成一次上筏（计数 %d→%d）" % (c0[1], c1[1]))
        deck_y = r0[1] + 1.0
        expect(peak >= deck_y - 0.35,
               "攀爬过程中人必须真的到过甲板面附近（甲板面 Y=%.2f，采样峰值 %.2f，起点 %.2f）——"
               "只涨计数不涨高度就是自说自话" % (deck_y, peak, y0[0]))
        expect(rise >= 1.0, "峰值相对起点必须净涨 1 格以上（实测 %.3f）" % rise)
        expect(len([s for s in samples if s > y0[0] + 0.2]) >= 3,
               "采样里必须有连续若干个高于起点，否则那一次冲高只是读数抖动")
        expect("扒边回筏成功" in p("cww rescue last"), "事件环里要有那次上筏")
    else:
        inconclusive("Y 或计数读数缺失，本段没测成")

    print("\n== 3. 四道门各自单独拦得住")
    p("tp %s %.2f %.2f %.2f" % (a1, r0[0] + half + 8.0, WATER_LEVEL - 0.6, r0[2]))
    p.sleep(0.4)
    far = p("cww rescue hold %s 40" % a1)
    expect("够不着" in far, "离船壳 8 格必须判够不着（伸手只 %.1f 格）：%s"
           % (2.5, far[:120]))
    # "不在水里"这一门在假玩家身上做不出来：他会穿过甲板掉进水里（v3~v5 反复实测），
    # 所以这里只能记 INCONCLUSIVE，由单测 ClingRuleTest.everyInputAloneCanBreakTheCling 负责那条门。
    inconclusive("「不在水里就不许扒边」这条没法用假玩家验：他站不住筏面，一定掉进水里；该门由单测覆盖")
    p("tp %s %.2f %.2f %.2f" % (b1, r0[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, r0[2]))
    p.sleep(0.4)
    enemy = p("cww rescue hold %s 40" % b1)
    print("      别队筏旁边（他自己筏在别处）：" + enemy[:150])
    c2 = counts(p)
    p.sleep(2.6)
    c3 = counts(p)
    expect("够不着" in enemy or "没有筏" in enemy,
           "别队的人贴到队 0 船壳边也必须被拒（他够的是自己队的筏）：%s" % enemy[:120])
    expect(c3[1] == c2[1], "上面三次失败尝试都不许产生上筏（计数 %s → %s）" % (c2, c3))

    print("\n== 4. 攀爬中挨一刀必须掉回水里")
    p("tp %s %.2f %.2f %.2f" % (a1, r0[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, r0[2]))
    p.sleep(0.4)
    c4 = counts(p)
    p("cww rescue hold %s 60" % a1)
    p.sleep(0.7)
    dmg = p("damage %s 2 minecraft:magic" % a1)
    p.sleep(1.6)
    c5 = counts(p)
    y2 = y_of(p, a1)
    print("      damage 回包：%s；Y=%.2f 计数 %s → %s" % (dmg[:60], (y2 or (0,))[0], c4, c5))
    expect(c5[2] > c4[2], "被打落的计数必须动（%d→%d），否则 AFTER_DAMAGE 根本没接上" % (c4[2], c5[2]))
    expect(c5[1] == c4[1], "被打断的这一次不许算成上筏成功")

    print("\n== 5. 非 PLAY 阶段不再推进")
    p("cww abort")
    p.wait_phase("LOBBY", 180)
    c6 = counts(p)
    p("cww rescue hold %s 60" % a1)
    p.sleep(2.6)
    c7 = counts(p)
    expect(c7[0] == c6[0] and c7[1] == c6[1],
           "LOBBY 里 hold 不许产生扒住/上筏（onEndTick 只在 PLAY 跑）：%s → %s" % (c6, c7))

    print("\n== 6. 局后清理不留状态")
    expect("还没挂载" not in status(p), "救援读数在收尾后仍然可读：" + status(p)[:120])

    for n, _ in bots:
        p("player %s kill" % n.lower())
    p("cww abort")

    print("\n=== RESCUE PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    for i in inc:
        print("   INCONCLUSIVE - %s" % i)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
