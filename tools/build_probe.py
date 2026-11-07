#!/usr/bin/env python3
"""建造规则 + 筏面布局端到端探针（M2-6 / M2-7）。

前置：
  1. ./gradlew runServer 已起来，日志里有 [build] 建造规则已挂载；
  2. RCON_PORT=25576 RCON_PASSWORD=nbwm0；
  3. test_sea 的海已经建过（sea_probe.py 的第 3~5 步；单独跑本探针前先确认）。

跑法：
  python tools/build_probe.py

为什么这套探针不许把坐标写死。上一版它假定"甲板是 7×7、合法格就是 (0,1,3)"，
初始筏削弱到 4×4 之后那批格子有一大半在甲板外面，判定当然跟着变——
**这种变化必须让探针自己发现，而不是让它拿旧坐标去撞一个假红**。
所以边长、甲板起始格、设施位额度全部从服务端读数里推：
列数开平方 = 边长，start = -(side>>1)（与 Java 的 RaftLayout.deckStart 同一条算式），
额度由 cww build status 直接报。

守的四件事：
  * **判定矩阵**：归属、阶段、建造区（水平 + 上下界）、邻接、上限，各测一次"只差一个输入"的对照。
  * **设施位随面积**：每 5 格甲板 1 位（用户 2026-11-06 裁决），maxFacilities 只是天花板；
    小筏被面积卡住时原因必须说"甲板太小"，撞到天花板时必须点名 maxFacilities。
  * **方块数上限**：另一张只改 initialRaftSide 与 maxBlocksPerRaft 的夹具图，顶格与限内来回。
  * **事件路径**：假玩家一次真实右键必须把方块写进 plot（用另一条代码路径读回 Dirt），
    一次真实左键必须取走（读回 Air）。

假玩家站不住筏面（会穿过甲板落水），事件那一段用的是"水面侧下方朝筏子平视略俯"的实测打法；
打不上就报 INCONCLUSIVE，不把夹具问题算成产品结论，也不放宽成"没变就算过"。
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import (P, keep_alive, sweep_stale_bots, wait_clean_lobby,  # noqa: E402
                      write_long_format, SEA, raft_name)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FORMAT = "sail_long"
CAP_ARENA = "test_build"
CAP_SIDE = 10          # 夹具筏边长：10×10=100 列 → 甲板 101 格，上限才能既 >=100 又只差 11 格
CAP_FILL = 7 + 4       # cww sail rig 最多 7 面帆 + cww oar rig 4 台桨台 = 11 格
ok = []
bad = []
inc = []


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
    return p("cww build status")


def caps(p):
    """上限一律从服务端读数里取：探针抄一份常量，就可能出现代码改了探针还绿。"""
    m = re.search(r"maxRaftArea=(\d+)→半径(\d+) maxBlocks=(\d+) maxFacilities=(\d+)", status(p))
    return None if not m else {"area": int(m.group(1)), "radius": int(m.group(2)),
                               "blocks": int(m.group(3)), "facilities": int(m.group(4))}


def team_counts(p, team):
    m = re.search(r"队%d 方块=(\d+) 列=(\d+) 设施=(\d+)/(\d+) 加载区块=(\d+)" % team, status(p))
    if not m:
        return None
    out = {"blocks": int(m.group(1)), "columns": int(m.group(2)), "facilities": int(m.group(3)),
           "slots": int(m.group(4)), "chunks": int(m.group(5))}
    out["side"] = int(round(out["columns"] ** 0.5))
    out["start"] = -(out["side"] >> 1)
    out["end"] = out["start"] + out["side"] - 1
    return out


def counters(p):
    m = re.search(r"允许 放置=(\d+) 破坏=(\d+)｜拒绝 放置=(\d+) 破坏=(\d+)", status(p))
    return None if not m else tuple(int(g) for g in m.groups())


def check(p, as_team, on_team, kind, lx, ly, lz, block=None):
    cmd = "cww build check %d %d %s %d %d %d" % (as_team, on_team, kind, lx, ly, lz)
    if block:
        cmd += " " + block
    out = p(cmd)
    print("    %s => %s" % (cmd.replace("cww build check ", ""), out[:180]))
    return out


def raft_xyz(p, team):
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)",
                  p("sable info @e[name=%s]" % raft_name(p, team)))
    return tuple(float(g) for g in m.groups()) if m else None


def team_of(name, bots):
    for n, t in bots:
        if n == name:
            return t
    return -1


def spawn(p, prefix):
    t0 = [prefix + "A%d" % i for i in range(2)]
    t1 = [prefix + "B%d" % i for i in range(2)]
    bots = [(n, 0) for n in t0] + [(n, 1) for n in t1]
    # Carpet 把假玩家名一律转成小写：用 "u12A0" 去 kill 是对空名字打拳（不报错也不掉线），
    # 上一轮四人还挂在队里，下一轮 cww force 就报"队伍已满"。所以 kill 一律带 .lower()。
    for name, _ in bots:
        p("player %s kill" % name.lower())
    for name, _ in bots:
        p("player %s spawn" % name)
    for _ in range(30):
        online = p("list").lower()
        if all(n.lower() in online for n, _ in bots):
            return bots
        p.sleep(1)
    return None


def start_match(p, bots, arena=SEA):
    if "phase=LOBBY" in p("cww state"):
        p("cww format reload %s" % FORMAT)   # 报名上限跟着内存里的赛制走，先装好自己的
    for name, _ in bots:
        p("cww force %s %d" % (name, team_of(name, bots)))
    keep_alive(p, bots)
    p("cww start %s %s" % (FORMAT, arena))
    for name, _ in bots:
        p("cww force %s %d" % (name, team_of(name, bots)))
    return p.wait_phase("PLAY", 240)


def describe(entry):
    return "读不到判定环" if not entry else "%s / %s / 局部(%d,%d,%d)" % (
        entry["kind"], entry["verdict"], entry["lx"], entry["ly"], entry["lz"])


def cell_of_last(p):
    out = p("cww build last")
    m = re.search(r"t=(\d+) (\S+) \S+ [-\d, ]+ 局部=\((-?\d+),(-?\d+),(-?\d+)\) 筏队=(-?\d+) → (\S+)", out)
    if not m:
        return None
    return {"tick": int(m.group(1)), "kind": m.group(2), "lx": int(m.group(3)),
            "ly": int(m.group(4)), "lz": int(m.group(5)), "raftTeam": int(m.group(6)),
            "verdict": m.group(7)}


def live_place(p, name, xyz, item):
    """按实测出来的站位打一发，返回命中时的站位与前后统计。

    假玩家站不住筏面（会穿过甲板落水），从筏子上方往下打基本是空枪。
    实测能稳定命中的站位是"水面侧下方、朝筏子平视略俯 25°"：水平 3.5~4.5 格、dy −1.6~−2.4。
    """
    p("give %s %s 64" % (name, item))
    p("gamemode survival %s" % name)
    for d in (3.5, 4.5):
        for dy in (-1.6, -2.0, -2.4):
            for dx, dz, yaw in ((d, 0.0, 270), (-d, 0.0, 90), (0.0, d, 180), (0.0, -d, 0)):
                for pitch in (25, 15):
                    p("tp %s %.2f %.2f %.2f" % (name, xyz[0] + dx, xyz[1] + dy, xyz[2] + dz))
                    p("player %s look %d %d" % (name, yaw, pitch))
                    p("player %s stop" % name)
                    before = team_counts(p, 0)
                    p("player %s use" % name)
                    p.sleep(0.5)
                    after = team_counts(p, 0)
                    if before and after and after["blocks"] > before["blocks"]:
                        return {"station": (dx, dy, dz, yaw, pitch),
                                "before": before, "after": after}
    return None


def rig_facilities(p, team, want):
    """程序化把帆补到 want 面（帆上限 7；桨台已随遥控器删除，设施只剩帆+筏心）。"""
    sails = min(7, want)
    p("cww sail strip %d" % team)
    p.sleep(0.4)
    echo = [p("cww sail rig %d %d north" % (team, sails))[:60]]
    p.sleep(0.4)
    return echo


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 1. 开局并确认读数口径（几何全部从服务端推）")
    print("  清残留：" + repr(sweep_stale_bots(p)))
    clean, st0 = wait_clean_lobby(p)
    expect(clean, "开局前必须是真正干净的 LOBBY（queue=0 teams=0），实际：%s" % st0[:150])
    if not clean:
        return 1
    write_long_format()
    stamp = time.strftime("%M%S")
    bots = spawn(p, "u%s" % stamp)
    if not bots:
        expect(False, "四台假玩家上线（2 队 × 2）")
        return 1
    expect(True, "四台假玩家上线（2 队 × 2）")
    reached, cur = start_match(p, bots)
    expect(reached, "sail_long %s 能进到 PLAY（当前 %s）" % (SEA, cur[:40]))
    if not reached:
        return 1
    p.sleep(2)

    c = caps(p)
    expect(c is not None, "cww build status 能读到上限四项：%s" % (c,))
    if not c:
        return 1
    base = team_counts(p, 0)
    expect(base is not None and base["blocks"] > 0, "开局能扫到队 0 的筏体方块：%s" % base)
    if not base:
        return 1
    side = base["side"]
    expect(base["columns"] == side * side,
           "列数是完全平方数（列=%d → 边长 %d，甲板是 side×side）" % (base["columns"], side))
    expect(side == 4, "初始筏按用户裁决削弱到 4×4，实测边长 %d（列 %d）" % (side, base["columns"]))
    expect(base["blocks"] == base["columns"] + base["facilities"],
           "方块数 = 列数 + 设施数（甲板每列一格、设施叠在甲板上）：%s" % base)
    expect(base["facilities"] == 1, "初始筏只该有筏心 1 个设施：%s" % base)
    chunks0 = base["chunks"]

    # 后面所有坐标都从这几个数推，不写死
    legal = (0, 1, -1)              # 甲板内、筏心旁边、一定在建造区里
    floating = (0, 7, -1)           # 同一列往上悬空：区内但六邻全空
    edge = (base["end"] + 1, 0, 0)  # 甲板外第一圈的甲板面上（扩建的正路）
    print("  推导出的几何：边长 %d，甲板 [%d,%d]，建造区半径 %d"
          % (side, base["start"], base["end"], c["radius"]))

    print("\n== 2. 判定矩阵（每条门只差一个输入）")
    expect("→ 允许" in check(p, 0, 0, "place", *legal), "自家筏、贴着甲板的合法格 → 允许")
    expect("超出建造区" in check(p, 0, 0, "place", c["radius"] + 2, 1, legal[2]), "水平越界 +x → 拒")
    expect("超出建造区" in check(p, 0, 0, "place", legal[0], 1, c["radius"] * 12), "水平越界 +z → 拒")
    expect("超出建造区" in check(p, 0, 0, "place", legal[0], 8, legal[2]), "高度窗口上界之外 → 拒")
    expect("超出建造区" in check(p, 0, 0, "place", legal[0], -2, legal[2]), "高度窗口下界之外 → 拒")
    expect("→ 允许" in check(p, 0, 0, "place", legal[0], -1, legal[2]), "甲板下一格（窗口内且有邻接）→ 允许")
    expect("没有可依附的方块" in check(p, 0, 0, "place", *floating), "区内悬空 → 拒")
    expect("没有可依附的方块" in check(p, 0, 0, "place", c["radius"], 7, c["radius"]), "区角悬空 → 拒")
    if abs(edge[0]) <= c["radius"]:
        expect("→ 允许" in check(p, 0, 0, "place", *edge),
               "甲板外第一圈（局部 %s）在建造区内且贴着甲板边 → 允许（这就是扩建的正路）" % (edge,))
        expect("超出建造区" in check(p, 0, 0, "place", c["radius"] + 1, 0, 0),
               "建造区半径再往外一格必须被拒")
    else:
        inconclusive("建造区半径 %d 罩不住边长 %d 的甲板外沿，扩建边界只能在夹具图那段验"
                     % (c["radius"], side))
    expect("那是队伍 0 的筏" in check(p, 1, 0, "place", *legal), "别队来放 → 拒，并点名是哪一队")
    expect("那是队伍 0 的筏" in check(p, 1, 0, "break", legal[0], 0, legal[2]), "别队来拆 → 拒")
    expect("筏心" in check(p, 0, 0, "break", 0, 1, 0), "拆筏心 → 拒（它走破拆伤害那条路）")
    expect("→ 允许" in check(p, 0, 0, "break", legal[0], 0, legal[2]), "拆自家甲板 → 允许")
    expect("没有筏" in check(p, 0, 99, "place", *legal), "查一支不存在的队 → 明确说没筏")
    expect("不存在" in check(p, 0, 0, "place", legal[0], 1, legal[2], "not_a_mod:nonsense"),
           "不存在的方块 id 必须报错，不能当成空气默默放行")

    print("\n== 3. 阶段门真的接在 Phase 上")
    say = p("cww stop")
    settled, cur = p.wait_phase("SETTLE", 60)
    expect(settled, "cww stop 能进 SETTLE：%s" % say[:40])
    expect("阶段" in check(p, 0, 0, "place", *legal), "SETTLE 里建造被拒，原因要说阶段")
    expect("阶段" in check(p, 0, 0, "break", legal[0], 0, legal[2]), "SETTLE 里拆除也被拒")
    p.wait_phase("LOBBY", 180)
    gen = p("cww raft gen 1")
    expect("已生成筏" in gen or "已绑定" in gen, "LOBBY 里能单独造一艘备用筏：%s" % gen[:60])
    expect("阶段" in check(p, 1, 1, "place", *legal), "LOBBY 里筏也在、队也对，建造仍然被拒")

    print("\n== 4. 事件路径：真实右键必须进 plot")
    reached, cur = start_match(p, bots)
    expect(reached, "第二局能起来（LOBBY 那艘备用筏不干扰开局生成）：%s" % cur[:40])
    if not reached:
        return 1
    p.sleep(2)
    xyz = raft_xyz(p, 0)
    expect(xyz is not None, "读到队 0 筏的世界坐标 %s" % (xyz,))
    a1 = bots[0][0]
    hit = live_place(p, a1, xyz, "minecraft:dirt") if xyz else None
    if hit:
        b0, b1 = hit["before"], hit["after"]
        dx, dy, dz, yaw, pitch = hit["station"]
        expect(b1["blocks"] == b0["blocks"] + 1,
               "一次真实右键恰好放进一格（%s → %s，站 (%.1f,%.1f,%.1f) yaw=%d pitch=%d）"
               % (b0, b1, dx, dy, dz, yaw, pitch))
        entry = cell_of_last(p)
        expect(entry is not None and entry["verdict"].startswith("允许") and entry["kind"] == "放置",
               "判定环记下这次真实放置：%s" % describe(entry))
        if entry:
            # 站在哪一侧、朝哪边瞄，解析出的局部坐标就必须落在同一侧；这条专抓原点/符号写错
            if dx != 0:
                expect((dx > 0) == (entry["lx"] >= 0),
                       "瞄准方向与解析出的局部坐标同号：dx=%+.1f 一侧朝筏子看，解析出 x=%d"
                       % (dx, entry["lx"]))
            else:
                expect((dz > 0) == (entry["lz"] >= 0),
                       "瞄准方向与解析出的局部坐标同号：dz=%+.1f 一侧朝筏子看，解析出 z=%d"
                       % (dz, entry["lz"]))
            expect(abs(entry["lx"]) <= c["radius"] + 1 and abs(entry["lz"]) <= c["radius"] + 1,
                   "真实命中的那一格必须落在建造区附近（(%d,%d,%d)，半径 %d）"
                   % (entry["lx"], entry["ly"], entry["lz"], c["radius"]))
            cell = check(p, 0, 0, "break", entry["lx"], entry["ly"], entry["lz"])
            expect("Dirt" in cell,
                   "换一条代码路径读回那一格，确实是玩家放的 dirt（局部 (%d,%d,%d)）"
                   % (entry["lx"], entry["ly"], entry["lz"]))
            expect("Air" in check(p, 0, 0, "break", *floating),
                   "对照格（悬空那一格）仍然读到 Air，说明上一条不是把整张表都读成了方块")
        cnt = counters(p)
        expect(cnt is not None and cnt[0] > 0, "允许放置的累计计数在涨：%s" % (cnt,))
    else:
        inconclusive("假玩家一次也没把方块放进筏里（站位与瞄准是夹具问题，不当成产品结论）")

    print("\n== 5. 事件路径：真实左键必须走 BREAK_BEFORE")
    broke = None
    if xyz:
        p("gamemode creative %s" % a1)
        for d in (3.5, 4.5):
            for dy in (-1.6, -2.0, -2.4):
                for dx, dz, yaw in ((d, 0.0, 270), (-d, 0.0, 90), (0.0, d, 180), (0.0, -d, 0)):
                    p("tp %s %.2f %.2f %.2f" % (a1, xyz[0] + dx, xyz[1] + dy, xyz[2] + dz))
                    p("player %s look %d 25" % (a1, yaw))
                    p("player %s stop" % a1)
                    b0 = team_counts(p, 0)
                    p("player %s attack" % a1)
                    p.sleep(0.4)
                    b1 = team_counts(p, 0)
                    if b0 and b1 and b1["blocks"] < b0["blocks"]:
                        broke = (dx, dy, dz, yaw, b0, b1)
                        break
                if broke:
                    break
            if broke:
                break
    if broke:
        dx, dy, dz, yaw, b0, b1 = broke
        expect(b1["blocks"] == b0["blocks"] - 1,
               "一次真实左键恰好取走一格（%s → %s，站 (%+.1f,%+.1f,%+.1f) yaw=%d）"
               % (b0, b1, dx, dy, dz, yaw))
        entry = cell_of_last(p)
        expect(entry and entry["verdict"].startswith("允许") and entry["kind"] == "破坏",
               "判定环记下这次真实破坏：%s" % describe(entry))
        if entry:
            gone = check(p, 0, 0, "break", entry["lx"], entry["ly"], entry["lz"])
            expect("Air" in gone,
                   "被拆的那一格换条路径读回来确实是 Air（局部 (%d,%d,%d)），计数与方块是一致的"
                   % (entry["lx"], entry["ly"], entry["lz"]))
    else:
        inconclusive("假玩家左键也没打中筏体方块（同样受站位限制）")

    print("\n== 6. 扫描不许把 plot 区块撑大（事件内扫描这条不变式的代价检查）")
    now = team_counts(p, 0)
    expect(now and now["chunks"] == chunks0,
           "加载区块数仍是 %s；扫描若会触发扩块这里就会涨：%s" % (chunks0, now))

    print("\n== 7. 设施位随甲板面积（每 5 格 1 位，maxFacilities 只是天花板）")
    slots = now["slots"]
    expect(slots == min(c["facilities"], now["columns"] // 5),
           "服务端报的设施位额度 = min(maxFacilities=%d, 列 %d / 5) = %d"
           % (c["facilities"], now["columns"], slots))
    expect(slots < c["facilities"],
           "4×4 的筏必须被面积卡住（额度 %d < 硬上限 %d），否则这条规则等于没生效"
           % (slots, c["facilities"]))
    echo = rig_facilities(p, 0, slots - now["facilities"])
    top = team_counts(p, 0)
    expect(top and top["facilities"] >= slots,
           "程序化补到顶格：设施=%s，额度=%s（%s）"
           % (top and top["facilities"], slots, " | ".join(echo)[:80]))
    fac_cell = check(p, 0, 0, "place", *legal, "nbw:sail")
    expect("设施位已满" in fac_cell, "顶格时再放帆 → 拒")
    expect("%d/%d" % (top["facilities"], slots) in fac_cell,
           "拒绝原因带的是实时额度（设施 %d / 额度 %d），不是写死的文案" % (top["facilities"], slots))
    expect("甲板" in fac_cell or "maxFacilities" in fac_cell,
           "原因必须说清卡在甲板面积还是硬上限：%s" % fac_cell[:150])
    expect("→ 允许" in check(p, 0, 0, "place", legal[0], 1, legal[2], "minecraft:oak_planks"),
           "设施位满了不影响普通方块")
    p("cww sail strip 0")
    p("cww oar strip 0")
    p.sleep(0.5)
    expect("→ 允许" in check(p, 0, 0, "place", *legal, "nbw:sail"),
           "拆回额度内之后帆又能放了：设施位是活读数，不是锁存")

    print("\n== 8. 方块数上限（只改 initialRaftSide 与 maxBlocksPerRaft 的夹具图）")
    if not write_cap_arena(p):
        inconclusive("夹具图 test_build 写不出来，方块上限这条没测成")
    else:
        reload_out = p("cww arena reload " + CAP_ARENA)
        expect("被拒绝" not in reload_out,
               "夹具图必须过配置校验（limits 四项 + 边长 0..17）：%s" % reload_out[:170])
        p("cww abort")
        p.wait_phase("LOBBY", 180)
        reached, cur = start_match(p, bots, arena=CAP_ARENA)
        expect(reached, "用夹具图 %s 能开新的一局：%s" % (CAP_ARENA, cur[:40]))
        if reached:
            capb = caps(p)
            n0 = team_counts(p, 0)
            expect(n0 and n0["side"] == CAP_SIDE,
                   "夹具图的初始筏边长生效为 %d（实测 %s）" % (CAP_SIDE, n0 and n0["side"]))
            expect(n0 and n0["columns"] == CAP_SIDE * CAP_SIDE,
                   "列数=%d（扫描盒 ±12 罩得住边长 %d 的筏，上限才不是少算的）：%s"
                   % (CAP_SIDE * CAP_SIDE, CAP_SIDE, n0))
            expect(capb and capb["blocks"] == n0["blocks"] + CAP_FILL,
                   "夹具图上限生效：maxBlocks=%s == 甲板 %d + 筏心 1 + 补 %d"
                   % (capb and capb["blocks"], CAP_SIDE * CAP_SIDE, CAP_FILL))
            need = capb["blocks"] - n0["blocks"]
            echo = rig_facilities(p, 0, need)
            at = team_counts(p, 0)
            expect(at and at["blocks"] == capb["blocks"],
                   "补 %d 格后顶格：方块=%s == maxBlocksPerRaft=%s（%s）"
                   % (need, at and at["blocks"], capb["blocks"], " | ".join(echo)[:70]))
            r1 = check(p, 0, 0, "place", legal[0], 1, legal[2], "minecraft:oak_planks")
            expect("方块数已达上限" in r1 and "%d/%d" % (capb["blocks"], capb["blocks"]) in r1,
                   "顶格时普通方块也拒，并报出实时数字：%s" % r1[:140])
            expect(at and at["slots"] > slots,
                   "大筏的设施位额度更多（夹具图 %s > 默认图 %d）——面积那条规则真的跟着甲板变"
                   % (at and at["slots"], slots))
            if abs(at["end"] + 1) <= capb["radius"]:
                expect("→ 允许" in check(p, 0, 0, "place", at["end"] + 1, 0, 0),
                       "边长 %d 的筏，甲板外第一圈（x=%d）仍在半径 %d 的建造区内 → 允许扩建"
                       % (at["side"], at["end"] + 1, capb["radius"]))
            p("cww sail strip 0")
            p("cww oar strip 0")
            p.sleep(0.5)
            expect("→ 允许" in check(p, 0, 0, "place", legal[0], 1, legal[2], "minecraft:oak_planks"),
                   "拆掉补的设施又放行（同样是活读数）")

    print("\n== 9. 收尾")
    for name, _ in bots:
        p("player %s kill" % name.lower())
    p("cww abort")
    p.wait_phase("LOBBY", 180)

    print("\n=== BUILD PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    for i in inc:
        print("   INCONCLUSIVE - %s" % i)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


def write_cap_arena(p):
    """复制 test_sea，只改 id、initialRaftSide 与 maxBlocksPerRaft。

    坐标与海域几何一个数都不动，所以不需要重新生成地形（那套 function 只认内置 id）；
    "上限变了"是唯一变量，方块门与建造区门不会互相干扰。

    maxBlocksPerRaft 必须 ≥ 100（03 §6.1 第 7 条），所以这里把筏做大（10×10）而不是把上限做小；
    边长也必须 ≤ 17，否则配置校验判非法、文件会被移进 cww/_invalid/。
    """
    src = os.path.join(ROOT, "src", "main", "resources", "data", "nbw", "arena", "test_sea.json")
    dst = os.path.join(ROOT, "run", "world", "cww", "arenas", CAP_ARENA + ".json")
    try:
        with open(src, encoding="utf-8") as fh:
            arena = json.load(fh)
    except (OSError, ValueError) as e:
        print("  读不到内置 test_sea：%s" % e)
        return False
    cap = CAP_SIDE * CAP_SIDE + 1 + CAP_FILL
    limits = arena.setdefault("limits", {})
    limits["initialRaftSide"] = CAP_SIDE
    limits["maxBlocksPerRaft"] = cap
    arena["id"] = CAP_ARENA
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(arena, ensure_ascii=False, indent=2))
    print("  写入 %s（边长=%d，maxBlocksPerRaft=%d）" % (dst, CAP_SIDE, cap))
    return True


if __name__ == "__main__":
    sys.exit(main())
