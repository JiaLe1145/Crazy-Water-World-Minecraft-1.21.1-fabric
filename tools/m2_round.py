#!/usr/bin/env python3
"""M2 无人值守全量演练探针（任务 #45 / Gate G2 的自动化半边）。

把 M2 的九块玩法在**一局**里串起来，全程零人工：
  起局发筏 → 建造 → 航行 → 落水自救 → 伸手救援 → 筏心摧毁 → 沉筏 → 结算分胜负 → 局后零残留。
独立探针各自绿不算数，阶段转移与跨系统状态交接、局后清理只有连成一局才暴露。

前置（照交接文档 §2）：
  1. ./gradlew runServer 在跑，且 stdout 重定向到 run/server-console.log（否则读日志的断言全假）；
  2. RCON_PORT=25576 RCON_PASSWORD=nbwm0；Carpet 在 run/mods；test_sea 的海已建过。
跑法：
  PYTHONIOENCODING=utf-8 RCON_PORT=25576 RCON_PASSWORD=nbwm0 python tools/m2_round.py

纪律（交接 §6）：每条断言都要能失败、旁边打原始读数；瞬时/夹具测不了的标 INCONCLUSIVE 并说清谁担保；
不拿服务端自己的 status 当唯一证据（建造量计数、航行量位移、救援量水平距离、沉筏量 Y）。
本探针纯测试基础设施，不改任何产品代码、不碰玩法数值。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import (P, keep_alive, sweep_stale_bots, write_long_format,  # noqa: E402
                       SEA, LONG_FORMAT, raft_name, sweep_raft_residue)

ok, bad, inc = [], [], []

WATER_LEVEL = 62          # test_sea 水面
HULL_OFFSET = 1.2         # 贴壳但不在筏上（够得着 2.5）
ITEM = "minecraft:oak_planks"


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


# ---- 独立读回 helpers（照 build_probe / oar_probe / rescue_probe / sink_probe 的量法）----

def raft_xyz(p, team):
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)",
                  p("sable info @e[name=%s]" % raft_name(p, team)))
    return tuple(float(g) for g in m.groups()) if m else None


def raft_y(p, team):
    xyz = raft_xyz(p, team)
    return xyz[1] if xyz else None


def y_of(p, name):
    m = re.search(r"\[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]", p("data get entity %s Pos" % name))
    return (float(m.group(2)), float(m.group(1)), float(m.group(3))) if m else None


def horiz_dist(p, name, raft):
    pos = y_of(p, name)
    return round(math.hypot(pos[1] - raft[0], pos[2] - raft[2]), 2) if pos and raft else None


def team_blocks(p, team):
    m = re.search(r"队%d 方块=(\d+) 列=(\d+)" % team, p("cww build status"))
    return (int(m.group(1)), int(m.group(2))) if m else None


def half_width(p):
    bc = team_blocks(p, 0)
    if not bc:
        return None
    side = int(round(bc[1] ** 0.5))
    return -(-(side >> 1)) if side % 2 else side // 2


def rescue_counts(p):
    m = re.search(r"扒住=(\d+) 上筏=(\d+) 被打落=(\d+)", p("cww rescue status"))
    return tuple(int(g) for g in m.groups()) if m else None


def rescue_pulls(p):
    m = re.search(r"拉回=(\d+) 伸手作废=(\d+)", p("cww rescue status"))
    return tuple(int(g) for g in m.groups()) if m else None


def live_place(p, name, xyz):
    """照 build_probe.live_place：假玩家站不住筏面，从水侧下方平视略俯打一发真实右键。"""
    p("give %s %s 64" % (name, ITEM))
    p("gamemode survival %s" % name)
    for d in (3.5, 4.5):
        for dy in (-1.6, -2.0, -2.4):
            for dx, dz, yaw in ((d, 0.0, 270), (-d, 0.0, 90), (0.0, d, 180), (0.0, -d, 0)):
                for pitch in (25, 15):
                    p("tp %s %.2f %.2f %.2f" % (name, xyz[0] + dx, xyz[1] + dy, xyz[2] + dz))
                    p("player %s look %d %d" % (name, yaw, pitch))
                    p("player %s stop" % name)
                    before = team_blocks(p, 0)
                    p("player %s use" % name)
                    p.sleep(0.5)
                    after = team_blocks(p, 0)
                    if before and after and after[0] > before[0]:
                        return before, after
    return None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()
    stamp = time.strftime("%M%S")
    a0, a1 = "m%sa0" % stamp, "m%sa1" % stamp
    b0, b1 = "m%sb0" % stamp, "m%sb1" % stamp
    bots = [(a0, 0), (a1, 0), (b0, 1), (b1, 1)]

    print("== 0. 清场")
    print("  清残留假玩家：" + repr(sweep_stale_bots(p)))
    p("cww abort")
    lobby = False
    for _ in range(60):
        if "LOBBY" in p("cww phase") or "IDLE" in p("cww phase"):
            lobby = True
            break
        p.sleep(1)
    expect(lobby, "遗留对局收回到 LOBBY（否则后面读数可能全是假的）：%s" % p("cww phase")[:50])
    print("      清残留筏：" + repr(sweep_raft_residue(p)))
    for n, _ in bots:
        p("player %s kill" % n)

    print("\n== 1. 起局发筏")
    write_long_format()
    if "phase=LOBBY" in p("cww state"):
        p("cww format reload %s" % LONG_FORMAT)
    for n, _ in bots:
        p("player %s spawn" % n)
    online = False
    for _ in range(30):
        lst = p("list").lower()
        if all(n in lst for n, _ in bots):
            online = True
            break
        p.sleep(1)
    expect(online, "四个假玩家都在线：%s" % p("list")[:120])
    if not online:
        return summary()
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, bots)                       # 不给水下呼吸会中途全灭、提前判胜（交接 §5.3）
    p("cww start %s %s" % (LONG_FORMAT, SEA))
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    reached, cur = p.wait_phase("PLAY", 240)
    expect(reached, "进入 PLAY（%s）" % cur[:50])
    if not reached:
        return summary()
    p.sleep(2)
    p.log()                                    # 丢弃开局前的日志，后面只收本局
    info = p("sable info @e")
    expect(raft_name(p, 0) in info and raft_name(p, 1) in info,
           "两队筏按 nbw_raft_<队>_<局> 唯一名可查：%s" % info[:120])
    allst = p("cww raft all")
    expect(allst.count("质量=9.0") == 2, "两艘初始筏质量都是 9.0（16 甲板×0.5 + 筏心 1.0）：%s" % allst[:140])
    tickets = p("sable forceload query")
    expect("nbw:round_active" in tickets and ("2 force-loading" in tickets or "2 sub-level" in tickets),
           "上了 2 张我们自己的加载票：%s" % tickets[:160])

    print("\n== 2. 建造（真实放置 + 判定矩阵）")
    allow = p("cww build check 0 0 place 0 1 -1")
    deny = p("cww build check 1 0 place 0 1 -1")
    expect("允许" in allow, "本队本筏合法格判允许：%s" % allow[:120])
    expect("队伍 0" in deny or "拒绝" in deny, "别队改队 0 的筏判拒绝：%s" % deny[:120])
    r0 = raft_xyz(p, 0)
    placed = live_place(p, a0, r0) if r0 else None
    if placed:
        before, after = placed
        expect(after[0] == before[0] + 1,
               "真实右键放一格，独立读回 build status 方块计数 +1（%d→%d）" % (before[0], after[0]))
    else:
        expect(False, "真实右键没能放进一格（站位扫描全空）——建造的真实事件路径没验到")

    print("\n== 3. 航行（桨推进，量位移不量峰值）")
    half = half_width(p)
    p("cww oar rig 0 1 south")
    start = raft_xyz(p, 0)
    p("cww oar hold %s 0 south 160" % a0)
    p.sleep(8.0)
    end = raft_xyz(p, 0)
    p("cww oar strip 0")
    if start and end:
        travelled = math.hypot(end[0] - start[0], end[2] - start[2])
        print("      位移 %.2f 格（%s → %s）" % (travelled, start, end))
        expect(travelled > 2.0, "划桨 8 秒真的走出 2 格以上（实测 %.2f）——走不出去航行就没有玩法意义" % travelled)
        expect(travelled < 55.0, "没飞出 test_sea（半径 60，实测 %.2f）——超出就是控制器甩筏子" % travelled)
    else:
        inconclusive("读不到筏位姿，航行位移没测成（由 oar_probe 43/43 担保）")

    print("\n== 4. 落水 + 扒边自救")
    r0 = raft_xyz(p, 0)
    half = half_width(p) or 2
    if r0:
        p("tp %s %.2f %.2f %.2f" % (a0, r0[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, r0[2]))
        p.sleep(0.5)
        c0 = rescue_counts(p)
        p("cww rescue hold %s 60" % a0)
        peak = None
        for _ in range(14):
            yy = y_of(p, a0)
            if yy:
                peak = yy[0] if peak is None else max(peak, yy[0])
            p.sleep(0.18)
        c1 = rescue_counts(p)
        print("      Y 峰值 %s，计数 %s → %s" % (peak, c0, c1))
        expect(c0 and c1 and c1[1] == c0[1] + 1, "自救完成一次，上筏计数 +1（%s→%s）" % (c0, c1))
        if peak is not None and r0:
            expect(peak >= r0[1] + 1.0 - 0.5,
                   "攀爬过程真的到过甲板面附近（峰值 %.2f，甲板面约 %.2f）" % (peak, r0[1] + 1.0))
        inconclusive("假玩家站不住筏面，落地后的 Y 不算证据；落点夹进甲板由 rescue_probe 与 ClingRuleTest 担保")
    else:
        expect(False, "读不到队 0 筏位姿，自救段没法摆位")

    print("\n== 5. 伸手救援（动作在救援方）")
    r0 = raft_xyz(p, 0)
    if r0:
        p("tp %s %.2f %.2f %.2f" % (a0, r0[0] + half + HULL_OFFSET, WATER_LEVEL - 0.6, r0[2]))
        p("tp %s %.2f %.2f %.2f" % (a1, r0[0] + half + 3.5, WATER_LEVEL - 0.6, r0[2]))
        p.sleep(0.5)
        d0 = horiz_dist(p, a0, r0)
        q0 = rescue_pulls(p)
        g0 = rescue_counts(p)
        rep = p("cww rescue reach %s 40" % a1)
        print("      reach 回包：%s" % rep[:140])
        p.sleep(1.5)
        q1 = rescue_pulls(p)
        g1 = rescue_counts(p)
        d1 = horiz_dist(p, a0, r0)
        print("      拉回 %s → %s；扒住 %s → %s；被救者水平 %s → %s" % (q0, q1, g0, g1, d0, d1))
        expect("已登记伸手" in rep and a0 in rep, "救援方登记成功且点名被救者：%s" % rep[:120])
        expect(q0 and q1 and q1[0] == q0[0] + 1, "拉回计数 +1（%s→%s）" % (q0, q1))
        expect(g0 and g1 and g1[0] == g0[0], "被救者没自己扒边（扒住计数不动 %s→%s）——动作确实在救援方" % (g0, g1))
        if d0 is not None and d1 is not None:
            expect(d1 < d0, "被救者水平方向被拉向筏子（%s→%s）" % (d0, d1))
    else:
        expect(False, "读不到队 0 筏位姿，伸手段没法摆位")

    print("\n== 6. 筏心摧毁（队 1）")
    p("cww raftcore destroy 1")
    p.sleep(0.5)
    core = p("cww raftcore status")
    raftst = p("cww raft status")
    print("      raftcore：%s" % core[:140])
    print("      raft：%s" % raftst[:140])
    expect("队1=WRECKAGE" in core, "队 1 筏心进入残骸态：%s" % core[:120])
    expect("队1=COUNTDOWN" in raftst, "队 1 开始 30s 沉筏倒计时：%s" % raftst[:120])

    print("\n== 7. 沉筏（队 1，量 Y 下沉）")
    before = raft_y(p, 1)
    p("cww raft weight 2.0")
    p("cww raft sink 1")
    ys = []
    for _ in range(12):
        p.sleep(1.0)
        y = raft_y(p, 1)
        if y is not None:
            ys.append(y)
    after = ys[-1] if ys else None
    dropped = (before - after) if (before is not None and after is not None) else 0.0
    print("      Y %s → %s（下沉 %.2f 格）" % (before, after, dropped))
    expect(dropped > 4.0, "队 1 筏下沉超过 4 格（实测 %.2f）——沉筏机制成立" % dropped)
    expect(after is not None and after < WATER_LEVEL,
           "筏沉到水面以下（final Y=%s，水面 %d）" % (after, WATER_LEVEL))

    print("\n== 8. 结算分胜负（淘汰队 1 → 队 0 唯一存活）")
    # 原版 /kill 会把 Carpet 假玩家直接移出世界 → 产品按"掉线"处理（这是 #41 的设计行为，不是缺陷）：
    # 开 90s 保席倒计时（01 §2 A13），到期 onSeatExpired 判淘汰 → aliveTeamCount()==1 → WINNER_DECIDED 自动判胜。
    # 所以这里**不能** cww stop：那是 ADMIN_STOP，双方都还 alive 时会判平局（第一版就这么假失败过）。
    # 照 m1_round 的做法耐心等席位到期（≤120s），验的是真实的"最后存活队获胜"链路。
    p("kill %s" % b0)
    p("kill %s" % b1)
    reached, cur = p.wait_phase("SETTLE", 120)
    expect(reached, "队 1 保席到期淘汰后自动进入 SETTLE（%s）" % cur[:50])
    p.sleep(1)
    log = p.log()
    won = "获胜" in log and "本局结束" in log
    draw = "平局" in log
    real_win = "WINNER_DECIDED" in log
    print("      结算广播在案：%s；平局字样：%s；WINNER_DECIDED：%s" % (won, draw, real_win))
    expect(won and not draw and real_win,
           "分出唯一胜负且原因是 WINNER_DECIDED（非平局/ADMIN_STOP）——Gate G2 的硬判据")

    print("\n== 9. 局后零残留")
    reached, cur = p.wait_phase("LOBBY", 90)
    expect(reached, "回收到 LOBBY（%s）" % cur[:50])
    p.sleep(2)
    after_info = p("sable info @e")
    expect("No sub-levels found" in after_info, "筏已销毁，内存无残留：%s" % after_info[:100])
    tickets_after = p("sable forceload query")
    expect("0 force-loading" in tickets_after or "No force-loading" in tickets_after
           or "none" in tickets_after.lower(), "加载票全部释放：%s" % tickets_after[:140])
    state = p("cww state")
    expect("queue=0" in state and "teams=0" in state,
           "会话与队席零残留（queue=0 teams=0）：%s" % state[:140])
    rst = p("cww rescue status")
    leak = [k for k in ("扒边", "伸手", "被拉") if (" %s " % k) in rst]
    expect(not leak, "救援状态零残留（无 扒边/伸手/被拉 行）：%s" % rst[:140])
    log2 = p.log()
    expect("[S10] residue=CLEAN" in log2, "S10 残留复检报 CLEAN")
    expect("[S10] residue=FAIL" not in log2 and "清理有残留" not in log2, "没有残留 FAIL / 清理告警")

    print("\n== 收尾清场")
    for n, _ in bots:
        p("player %s kill" % n)
    p("cww raft weight 1.0")                  # 复原全局沉筏重量倍数，别污染下一支
    p.sleep(1)
    left = [n for n, _ in bots if n in p("list").lower()]
    expect(not left, "假玩家全部清掉，不留污染：%s" % left)

    return summary()


def summary():
    print("\n=== M2 ROUND SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    for i in inc:
        print("   INCONCLUSIVE - %s" % i)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())