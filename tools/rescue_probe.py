#!/usr/bin/env python3
"""落水、扒边自救与伸手救援的端到端探针（M2-8 / 01 §8）。

前置：
  1. ./gradlew runServer 已起来，日志里有 [rescue] 落水与救援已挂载；
  2. RCON_PORT=25576 RCON_PASSWORD=nbwm0；
  3. test_sea 的海已经建过。

跑法：
  python tools/rescue_probe.py

按"能不能失败"排：
  * **坐标口径**（这条要是错的，后面全是空中楼阁）：把玩家精确放到筏的位姿原点，
    服务端算出的局部坐标必须是 (0,0,0) 附近、间隙 0。这是 `SableRafts.worldToLocal`
    那条位姿逆变换唯一的可证伪形式，也是 §3.12 建造规则同一套口径。
  * **时序是真的**：在水里、贴着自家船壳、续住包，2 秒后必须真的升高约一格，`上筏` 计数 +1。
    高度用 Y 读数量，不看服务端自己说什么。
  * **四道门各自单独拦得住**：够不着 / 不在水里 / 不是自家筏 / 非 PLAY 阶段。
  * **打断是真的**：攀爬中挨一刀必须掉回去，被打落的计数要动。
  * **伸手救援（第 5 段）**：动作登记在<b>救援方</b>身上，被救者一次输入都没有——
    所以这里同时断言 `拉回 +1` 与 `扒住 +0`，只涨前者才叫"救援方用力"。
  * **落点必须站进甲板（第 6 段）**：攀爬与拉回的终点局部 x/z 都要落在 footprint 内缩的方框里，
    间隙读到 0。修之前终点只抬 Y，人在离壳两格半处爬到顶是站在筏旁的半空里。
  * **长按通道的坐标空间（第 7 段）**：真客户端的长按包永远造不出来（RCON 与假玩家都没有准星），
    所以用 `cww net probe` 把服务端那道判定原样打出来：父世界直算必须判越界（那就是修前的口径），
    甲板局部帧必须判可达；顺带测出"sub-level 方块物理上在父世界的存储坐标里"，
    所以认方块那条一直是对的，坏的只有距离。

假玩家上不了筏面（会穿过甲板落水），这反而正好是本探针需要的状态：他本来就在水里。
但"客户端长按"这条输入假玩家发不出来，所以这里用 `cww rescue hold|reach` 驱动同一个服务端窗口；
真客户端手感留在 §6 真人清单。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, keep_alive, sweep_stale_bots, write_long_format, SEA, raft_name  # noqa: E402

ok = []
bad = []
inc = []

FORMAT = "sail_long"
WATER_LEVEL = 62
HULL_OFFSET = 1.2        # 站在水面、离船壳 1.2 格：够得着（伸手 2.5）但人不在筏上
REACH_TICKS = 20         # 与 ReachRule.REACH_TICKS 同一个数：02 §8 的"贴边 1 秒"


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


def pulls(p):
    """伸手救援的两个计数：拉回（成功）与伸手作废（中途打断或目标失效）。"""
    m = re.search(r"拉回=(\d+) 伸手作废=(\d+)", status(p))
    return tuple(int(g) for g in m.groups()) if m else None


def horiz_dist(p, name, raft):
    """人到筏中心的<b>水平</b>距离（世界坐标，独立于服务端的说法）。

    <p>为什么用这个而不是读"被拉/扒边"那一行的局部坐标：那一行只在 1 秒的过程里存在，
    RCON 往返快慢直接决定读不读得到；而假玩家站不住筏面，只会<b>竖直</b>穿甲板沉下去，
    水平分量恰好是没被夹具污染的那一维——落点到底进没进甲板，问它就够了。
    """
    pos = y_of(p, name)
    return round(math.hypot(pos[1] - raft[0], pos[2] - raft[2]), 2) if pos and raft else None


def raft_xyz(p, team):
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)",
                  p("sable info @e[name=%s]" % raft_name(p, team)))
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

    print("\n== 5. 伸手救援：动作登记在救援方，被救者一次都不用按")
    victim = a1
    rescuer = bots[1][0].lower()          # 同队第二个人
    enemy = bots[2][0].lower()            # 队 1 的人
    at_hull = (r0[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, r0[2])
    # 救援方站离壳 3.5 格那一侧：离被救者只有 2.3 格（伸手够得着），但他自己离壳 3.5 > 2.5，
    # 因此他**不可能**触发自己的扒边。夹具这样摆，"扒住/上筏"计数再动就一定是假阳性，
    # 而不是像第一版那样把救援方自己的自救记在被救者头上。
    at_out = (r0[0] + half + 3.5, WATER_LEVEL - 0.6, r0[2])
    p("tp %s %.2f %.2f %.2f" % ((victim,) + at_hull))
    p("tp %s %.2f %.2f %.2f" % ((rescuer,) + at_out))
    p.sleep(0.6)
    c0 = counts(p)
    q0 = pulls(p)
    y0 = y_of(p, victim)
    rep = p("cww rescue reach %s 40" % rescuer)
    print("      reach 回包：" + rep[:200])
    expect("已登记伸手" in rep and victim in rep,
           "救援方登记成功，且回包必须点名被救者 %s：%s" % (victim, rep[:120]))
    ysamples, snaps = [], []
    for _ in range(10):
        yy = y_of(p, victim)
        if yy:
            ysamples.append(yy[0])
        snaps.append(status(p))
        p.sleep(0.15)
    c1 = counts(p)
    q1 = pulls(p)
    peak = max(ysamples) if ysamples else None
    print("      被救者 Y 采样 %d 个：%s；拉回 %s → %s"
          % (len(ysamples), [round(v, 2) for v in ysamples], q0, q1))
    expect(peak is not None and q1 and q1[0] == q0[0] + 1,
           "1 秒内必须刚好完成一次拉回（计数 %s → %s）" % (q0, q1))
    if not (q1 and q1[0] == q0[0] + 1):
        print("      事件环（作废原因在这里）：" + " / ".join(
            l for l in p("cww rescue last").split("\n")[-6:]))
    saw_pull = any(("伸手" in s and victim in s) for s in snaps)
    grabbed = [s for s in snaps if ("%s 扒边" % victim) in s]
    if saw_pull:
        expect(not grabbed,
               "被救者全程不许进入自己的扒边状态（%d 条读数里 %d 条带扒边行）——这才是『动作在救援方』那条裁决"
               "的直接证据：%s" % (len(snaps), len(grabbed), (grabbed[0][:170] if grabbed else "")))
    else:
        inconclusive("1 秒的过程里没在任何一条 status 里读到这次拉（RCON 往返比救援本身还慢），"
                     "『被救者不需要输入』只能退回由扒住/上筏两条计数不动来担保")
    expect(c1[0] == c0[0],
           "整个过程『扒住』计数不许动（%d→%d）：被救者一次输入都没按，动了就说明拉回其实靠的是自救"
           % (c0[0], c1[0]))
    expect(c1[1] == c0[1], "拉回不算自救上筏（上筏=%d→%d 应该原地不动）" % (c0[1], c1[1]))
    if peak is not None:
        deck_y = r0[1] + 1.0
        expect(peak >= deck_y - 0.35,
               "被拉的人必须真的到过甲板面附近（甲板面 Y=%.2f，采样峰值 %.2f，起点 %s）"
               % (deck_y, peak, (y0 or (0,))[0]))
    expect("把 %s 拉回筏上" % victim in p("cww rescue last"), "事件环里要有那次拉回，且点名被救者")

    print("\n      —— 三道门")
    p("tp %s %.2f %.2f %.2f" % ((victim,) + at_hull))
    p("tp %s %.2f %.2f %.2f" % ((enemy,) + at_out))
    p.sleep(0.5)
    q2 = pulls(p)
    erep = p("cww rescue reach %s 40" % enemy)
    print("      敌队救援回包：" + erep[:160])
    p.sleep(1.6)
    q3 = pulls(p)
    expect(q3[0] == q2[0], "敌队的人贴着溺水的队 0 队员也不许拉人（拉回 %s → %s）" % (q2, q3))
    expect("没有可拉的人" in erep or "自己队" in erep, "回包要把原因说成归属而不是含糊一句：%s" % erep[:120])
    p("tp %s %.2f %.2f %.2f" % ((rescuer,) + (r0[0] + half + 12.0, WATER_LEVEL - 0.6, r0[2])))
    p.sleep(0.4)
    frep = p("cww rescue reach %s 40" % rescuer)
    print("      隔 12 格回包：" + frep[:160])
    expect("没有可拉的人" in frep and "伸手" in frep,
           "超出伸手上限必须被拒，且原因要点名伸手距离（02 §8 的 12 格那一档是抛绳索，不是伸手）：%s"
           % frep[:120])

    print("\n      —— 救援方挨一刀：这次拉回作废")
    p("tp %s %.2f %.2f %.2f" % ((rescuer,) + at_out))
    p("tp %s %.2f %.2f %.2f" % ((victim,) + at_hull))
    p.sleep(0.5)
    q4 = pulls(p)
    p("cww rescue reach %s 40" % rescuer)
    p.sleep(0.5)
    dmg = p("damage %s 2 minecraft:magic" % rescuer)
    p.sleep(1.2)
    q5 = pulls(p)
    print("      damage 回包：%s；拉回 %s → %s" % (dmg[:50], q4, q5))
    expect(q5[1] == q4[1] + 1, "伸手作废计数必须动（%d→%d），否则 AFTER_DAMAGE 没接到救援方身上"
           % (q4[1], q5[1]))
    expect(q5[0] == q4[0], "被打断的那一次不许算成拉回成功（%s → %s）" % (q4, q5))

    print("\n== 6. 落点必须站进甲板（修前只抬 Y，人会停在筏旁）")
    limit = half - 0.75 + 0.05          # LANDING_INSET = 0.75，留 0.05 给读数取整
    # 独立量法：拉回之前人在甲板外面（离筏中心水平距离 > 半宽），拉完必须落到半宽以内。
    # 为什么不再采样"被拉"那一行：那一行只在 1 秒的过程里存在，RCON 往返快慢直接决定读不读得到，
    # 读不到就报 FAIL 的话，测的是探针自己的时序，不是玩法结论。
    # 而水平距离不受夹具影响：假玩家站不住筏面，只会**竖直**穿甲板沉下去。
    # 起点必须按"筏现在到哪儿了"重新算：r0 是第 0 段读的，筏子会漂，照着旧 r0 摆人就会把
    # "离壳 1.2 格"摆成"离中心 2.2 格"，前置条件与结论的余量全都被漂走的那点距离吃掉。
    rr = raft_xyz(p, 0)
    at_hull = (rr[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, rr[2])
    at_out = (rr[0] + half + 3.5, WATER_LEVEL - 0.6, rr[2])
    p("tp %s %.2f %.2f %.2f" % ((victim,) + at_hull))
    p("tp %s %.2f %.2f %.2f" % ((rescuer,) + at_out))
    p.sleep(0.5)
    d0 = horiz_dist(p, victim, raft_xyz(p, 0))
    expect(d0 is not None and d0 > half + 0.1,
           "前置：被救者必须还在甲板外面（离筏中心水平 %s 格 > 半宽 %d），否则这一趟测不出落点"
           % (d0, half))
    rep6 = p("cww rescue reach %s 40" % rescuer)
    p.sleep(1.4)
    d1 = horiz_dist(p, victim, raft_xyz(p, 0))
    print("      reach 回包：%s｜水平距离 %s → %s 格（半宽 %d）" % (rep6[:120], d0, d1, half))
    # 只判"落进半宽 + 余量"是不够的：起点离中心本来就只有两格出头，旧那条只抬 Y 的写法也能混过去。
    # 真正能失败的是**向内移动了多少**——落点必须明显比起点更接近筏子中心。
    expect(d1 is not None and d0 is not None and d1 <= half + 0.6 and d1 <= d0 - 0.8,
           "拉回之后被救者必须落到甲板 footprint 之内并且明显向内（%s → %s 格，半宽 %d，要求至少向内 0.8）；"
           "停在筏旁就是那条只抬 Y 的老毛病没修干净" % (d0, d1, half))
    ev = p("cww rescue last")
    m = re.search(r"拉回筏上（(\d+) tick，落点本地 \(([-\d.]+),([-\d.]+),([-\d.]+)\)", ev)
    expect(m is not None, "事件环里要有那条带落点的拉回记录：%s" % ev[:120])
    if m:
        lx, ly, lz = (float(m.group(i)) for i in (2, 3, 4))
        expect(m.group(1) == str(REACH_TICKS),
               "那次拉回应当是 %d tick（1 秒），读到 %s" % (REACH_TICKS, m.group(1)))
        expect(abs(lx) <= limit and abs(lz) <= limit,
               "落点局部 x/z 必须落在 ±%.2f 内（footprint 内缩 0.75），实测 (%.2f,%.2f)" % (limit, lx, lz))
        expect(abs(ly - 1.0) < 0.01, "落点高度必须是甲板面（局部 y=1.00），实测 %.2f" % ly)

    # 自救那条路同样必须用夹过的落点
    rr2 = raft_xyz(p, 0)
    p("tp %s %.2f %.2f %.2f" % ((victim,) + (rr2[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, rr2[2])))
    p.sleep(0.5)
    d0b = horiz_dist(p, victim, raft_xyz(p, 0))
    p("cww rescue hold %s 60" % victim)
    p.sleep(2.8)
    d1b = horiz_dist(p, victim, raft_xyz(p, 0))
    print("      自救那趟：水平距离 %s → %s 格" % (d0b, d1b))
    expect(d0b is not None and d0b > half + 0.1,
           "前置：自救那趟也得从甲板外面起（水平 %s 格 > 半宽 %d），否则结论同样测不出来"
           % (d0b, half))
    expect(d1b is not None and d0b is not None and d1b <= half + 0.6 and d1b <= d0b - 0.8,
           "自救落点同样必须进甲板并且明显向内（%s → %s 格，半宽 %d）" % (d0b, d1b, half))

    print("\n== 7. 长按通道的坐标空间（真客户端那条路，用 cww net probe 取证）")
    p("tp %s %.2f %.2f %.2f" % ((rescuer,) + at_out))
    p.sleep(0.4)
    deck = p("cww net probe %s deck 0" % rescuer)
    print("      deck 回包：" + deck[:260])
    m = re.search(r"父世界直算=([-\d.]+)格→(\w+)｜甲板局部帧=([-\d.]+)格→(\w+)", deck)
    expect(m is not None, "net probe 必须同时给出两种口径的读数：%s" % deck[:120])
    if m:
        wd, wdec, ld, ldec = float(m.group(1)), m.group(2), float(m.group(3)), m.group(4)
        expect(wd > 1_000_000 and wdec == "REJECT",
               "父世界直算必须远到被判越界——这就是修之前每个筏上长按都无响应的原因，实测 %.0f 格→%s"
               % (wd, wdec))
        expect(ldec == "KEEPALIVE" and 0 <= ld <= 8.0,
               "换成甲板局部帧，同一个人同一格必须判可达，实测 %.2f 格→%s" % (ld, ldec))
    rig = p("cww oar rig 0 1 south")
    print("      挂桨：" + rig[:120])
    oar = p("cww net probe %s oar 0 0" % rescuer)
    p("cww oar strip 0")
    print("      oar 回包：" + oar[:260])
    expect("归属筏=" in oar and "无（公共地形）" not in oar,
           "桨台那一格必须认得出属于队 0 的筏：%s" % oar[:120])
    expect("父世界BE=OarBlockEntity" in oar,
           "桨台的方块实体必须读得到。这条同时更正一条推断：sub-level 的方块物理上就存在父世界的存储坐标里，"
           "所以旧代码用父世界 getBlockEntity 认方块从来不是问题，坏的只有距离：%s" % oar[:200])

    print("\n== 8. 非 PLAY 阶段不再推进")
    p("cww abort")
    p.wait_phase("LOBBY", 180)
    c6 = counts(p)
    p("cww rescue hold %s 60" % a1)
    p.sleep(2.6)
    c7 = counts(p)
    expect(c7[0] == c6[0] and c7[1] == c6[1],
           "LOBBY 里 hold 不许产生扒住/上筏（onEndTick 只在 PLAY 跑）：%s → %s" % (c6, c7))

    print("\n== 9. 局后清理不留状态")
    st = status(p)
    expect("还没挂载" not in st, "救援读数在收尾后仍然可读：" + st[:120])
    # 配对关系与起点/落点都是局内状态：漏一条就会在下一局开局时凭空把人搬上筏（#41 那一类幽灵）
    leaked = [k for k in ("扒边", "伸手", "被拉") if (" %s " % k) in st]
    expect(not leaked, "局后不许留下任何在扒/在拉的人，实测残留 %s：%s" % (leaked, st[:200]))

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
