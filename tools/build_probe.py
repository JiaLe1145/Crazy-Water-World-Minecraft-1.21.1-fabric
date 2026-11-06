#!/usr/bin/env python3
"""建造规则端到端探针（M2-6 / 01 §4.2）。

前置：
  1. ./gradlew runServer 已起来，日志里有 [build] 建造规则已挂载；
  2. RCON_PORT=25576 RCON_PASSWORD=nbwm0；
  3. test_sea 的海已经建过（sea_probe.py 的第 3~5 步；单独跑本探针前先确认）。

跑法：
  python tools/build_probe.py

这套探针守的四件事，按"能不能失败"排：
  * **判定矩阵**（cww build check）：五条门各测一次"只差一个输入"的对照——归属、阶段、
    建造区（水平 + 上下界）、邻接、上限。任何一条门写成恒真都会红。
  * **上限的算术**：帆程序化摆到顶格，验"帆被拒、木板放行"，再拆回限内验它又能放——
    数字必须是活读数，不是判一次就锁存。
  * **方块数上限**：另开一张只改 maxBlocksPerRaft 的夹具图（test_build），
    在"差 4 格"和"正好顶格"之间来回，证明这个配置项真的接进了判定。
  * **事件路径**：假玩家一次真实右键必须把方块写进 plot（证据走另一条代码路径读回来：
    check break 在那一格读到 Dirt，而甲板仍是 Oak Planks），一次真实左键必须取走一格。

为什么放置用 dirt 不用木板：甲板本身就是 oak_planks，"那一格有木板"什么都证明不了；
换成 dirt 才能区分"玩家放上去的"与"开局就在那儿的"。

假玩家上不了筏面（他会穿过甲板掉进水里，v3~v5 实测），所以事件那一段是
"每一发都先把他按回甲板上方再点"。打上就算证据；打不上报 INCONCLUSIVE——
既不把夹具的瞄准问题算成产品结论，也不为了凑绿把断言放宽成"没变就算过"。
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, keep_alive, sweep_stale_bots, write_long_format, SEA  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FORMAT = "sail_long"
CAP_ARENA = "test_build"
CAP_RAFT_RADIUS = 8                 # 配置校验的上界就是 8（ConfigService:195），9 会被判非法
CAP_DECK_SIDE = 2 * CAP_RAFT_RADIUS + 1
CAP_FILL = 7 + 4                    # cww sail rig 最多 7 面帆 + cww oar rig 4 台桨台
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
    m = re.search(r"队%d 方块=(\d+) 列=(\d+) 设施=(\d+) 加载区块=(\d+)" % team, status(p))
    return None if not m else {"blocks": int(m.group(1)), "columns": int(m.group(2)),
                               "facilities": int(m.group(3)), "chunks": int(m.group(4))}


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
                  p("sable info @e[name=nbw_raft_%d]" % team))
    return tuple(float(g) for g in m.groups()) if m else None


def team_of(name, bots):
    for n, t in bots:
        if n == name:
            return t
    return -1


def spawn(p, prefix):
    # Carpet 把假玩家名统一转成小写：用 "u12A0" 去 kill 是对空名字打拳（不报错、也不掉线），
    # 上一轮四人还挂在队里，下一轮 cww force 就报"队伍已满"。所以 kill 一律带 .lower()。
    t0 = [prefix + "A%d" % i for i in range(2)]
    t1 = [prefix + "B%d" % i for i in range(2)]
    bots = [(n, 0) for n in t0] + [(n, 1) for n in t1]
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
    for name, _ in bots:
        p("cww force %s %d" % (name, team_of(name, bots)))
    keep_alive(p, bots)
    p("cww start %s %s" % (FORMAT, arena))
    for name, _ in bots:
        p("cww force %s %d" % (name, team_of(name, bots)))
    return p.wait_phase("PLAY", 240)


def cell_of_last(p):
    """判定环最新一条：类型、局部坐标、筏队号、结论。"""
    out = p("cww build last")
    m = re.search(r"t=(\d+) (\S+) \S+ [-\d, ]+ 局部=\((-?\d+),(-?\d+),(-?\d+)\) 筏队=(-?\d+) → (\S+)", out)
    if not m:
        return None
    return {"tick": int(m.group(1)), "kind": m.group(2), "lx": int(m.group(3)),
            "ly": int(m.group(4)), "lz": int(m.group(5)), "raftTeam": int(m.group(6)),
            "verdict": m.group(7)}


def describe(entry):
    """判定环条目的可读摘要（None 也要能打出来，别让格式化自己炸掉探针）。"""
    return "读不到判定环" if not entry else "%s / %s / 局部(%d,%d,%d)" % (
        entry["kind"], entry["verdict"], entry["lx"], entry["ly"], entry["lz"])


def live_place(p, name, xyz, item):
    """按实测出来的站位打一发，返回命中时的站位与前后统计。

    假玩家站不住筏面（会穿过甲板落水），从筏子上方往下打基本是空枪。
    实测能稳定命中的站位是"水面侧下方、朝筏子平视略俯 25°"：水平 3.5~4.5 格、dy −1.6~−2.4。
    四个方向都试，一是提高命中概率，二是让"瞄的方向"与"解析出的局部坐标符号"能互验。
    """
    p("give %s %s 64" % (name, item))
    p("gamemode survival %s" % name)
    for d in (3.5, 4.5):
        for dy in (-1.6, -2.0, -2.4):
            # (水平偏移, 朝向)：站在东边朝西看 yaw=270，站在西边朝东看 yaw=90，
            # 站在南边朝北看 yaw=180，站在北边朝南看 yaw=0
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
                        return {"station": (dx, dy, dz, yaw, pitch), "aim": (dx, dz, yaw),
                                "before": before, "after": after}
    return None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 1. 开局并确认读数口径")
    print("  清残留：" + repr(sweep_stale_bots(p)))
    # 上一轮崩在这里、或别的探针留下的对局会让 cww start 直接被拒；先把场子收回 LOBBY
    for _ in range(20):
        if "LOBBY" in p("cww phase"):
            break
        p("cww abort")
        p.sleep(1.5)
    else:
        expect(False, "遗留对局收不掉，当前 %s" % p("cww phase")[:60])
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
    expect(c is not None, "cww build status 能读到上限四项：%s" % (c and c))
    if not c:
        return 1
    base = team_counts(p, 0)
    expect(base is not None and base["blocks"] > 0, "开局能扫到队 0 的筏体方块：%s" % base)
    side = int(round(base["columns"] ** 0.5))
    expect(side * side == base["columns"],
           "占用列数是完全平方数（列=%d → 边长 %d，初始筏就是方阵）" % (base["columns"], side))
    expect(base["blocks"] == base["columns"] + base["facilities"],
           "方块数 = 列数 + 设施数（甲板每列一格、设施叠在甲板上）：%s" % base)
    chunks0 = base["chunks"]

    print("\n== 2. 判定矩阵（每条门只差一个输入）")
    radius = c["radius"]
    expect("→ 允许" in check(p, 0, 0, "place", 0, 1, 3), "自家筏、贴着甲板的合法格 → 允许")
    expect("超出建造区" in check(p, 0, 0, "place", radius + 2, 1, 0), "水平越界（+x 出界）→ 拒")
    expect("超出建造区" in check(p, 0, 0, "place", 0, 1, radius * 12), "水平越界（+z 更远）→ 拒")
    expect("超出建造区" in check(p, 0, 0, "place", 0, 8, 0), "高度窗口上界之外 → 拒")
    expect("超出建造区" in check(p, 0, 0, "place", 0, -2, 0), "高度窗口下界之外 → 拒")
    expect("→ 允许" in check(p, 0, 0, "place", 0, -1, 0), "甲板下一格（窗口内且有邻接）→ 允许")
    expect("→ 允许" in check(p, 0, 0, "place", radius, 0, 0),
           "半径边界那一格本身必须放行（它邻着甲板外沿，只考半径不考邻接）")
    expect("超出建造区" in check(p, 0, 0, "place", radius + 1, 0, 0),
           "再往外一格必须被半径拒掉（半径=%d 的两个方向都钉住）" % radius)
    expect("没有可依附的方块" in check(p, 0, 0, "place", 0, 7, 0), "区内悬空 → 拒")
    expect("没有可依附的方块" in check(p, 0, 0, "place", radius, 7, radius), "区角悬空 → 拒")
    expect("那是队伍 0 的筏" in check(p, 1, 0, "place", 0, 1, 3), "别队来放 → 拒，并点名是哪一队")
    expect("那是队伍 0 的筏" in check(p, 1, 0, "break", 0, 0, 0), "别队来拆 → 拒")
    expect("筏心" in check(p, 0, 0, "break", 0, 1, 0), "拆筏心 → 拒（它走破拆伤害那条路）")
    expect("→ 允许" in check(p, 0, 0, "break", 0, 0, 0), "拆自家甲板 → 允许")
    expect("没有筏" in check(p, 0, 99, "place", 0, 1, 3), "查一支不存在的队 → 明确说没筏")
    expect("不存在" in check(p, 0, 0, "place", 0, 1, 3, "not_a_mod:nonsense"),
           "不存在的方块 id 必须报错，不能当成空气默默放行")

    print("\n== 3. 阶段门真的接在 Phase 上")
    say = p("cww stop")
    settled, cur = p.wait_phase("SETTLE", 60)
    expect(settled, "cww stop 能进 SETTLE：%s" % say[:40])
    expect("阶段" in check(p, 0, 0, "place", 0, 1, 3), "SETTLE 里建造被拒，原因要说阶段")
    expect("阶段" in check(p, 0, 0, "break", 0, 0, 0), "SETTLE 里拆除也被拒")
    p.wait_phase("LOBBY", 180)
    gen = p("cww raft gen 1")
    expect("已生成筏" in gen or "已绑定" in gen, "LOBBY 里能单独造一艘备用筏：%s" % gen[:60])
    expect("阶段" in check(p, 1, 1, "place", 0, 1, 3), "LOBBY 里筏也在、队也对，建造仍然被拒")

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
               "一次真实右键恰好放进一格（%s → %s，站 (%.1f,%.1f,%.1f) 朝 yaw=%d pitch=%d）"
               % (b0, b1, dx, dy, dz, yaw, pitch))
        entry = cell_of_last(p)
        expect(entry is not None and entry["verdict"].startswith("允许") and entry["kind"] == "放置",
               "判定环记下这次真实放置：%s" % (describe(entry),))
        if entry:
            # 站在哪一侧、朝哪边瞄，解析出的局部坐标就必须落在同一侧。
            # 这条专抓"原点取错 / 符号写反"——那种情况下数字照样像模像样，但全是错的。
            if dx != 0:
                expect((dx > 0) == (entry["lx"] >= 0),
                       "瞄准方向与解析出的局部坐标同号：人站在 dx=%+.1f 一侧朝筏子看，解析出 x=%d"
                       % (dx, entry["lx"]))
            else:
                expect((dz > 0) == (entry["lz"] >= 0),
                       "瞄准方向与解析出的局部坐标同号：人站在 dz=%+.1f 一侧朝筏子看，解析出 z=%d"
                       % (dz, entry["lz"]))
            expect(abs(entry["lx"]) <= c["radius"] + 1 and abs(entry["lz"]) <= c["radius"] + 1,
                   "真实命中的那一格必须落在建造区附近（(%d,%d,%d)，半径 %d）"
                   % (entry["lx"], entry["ly"], entry["lz"], c["radius"]))
            cell = check(p, 0, 0, "break", entry["lx"], entry["ly"], entry["lz"])
            expect("Dirt" in cell,
                   "换一条代码路径读回那一格，确实是玩家放的 dirt（局部 (%d,%d,%d)）"
                   % (entry["lx"], entry["ly"], entry["lz"]))
            expect("Air" in check(p, 0, 0, "break", 0, 7, 0),
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
               "判定环记下这次真实破坏：%s" % (describe(entry),))
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

    print("\n== 7. 设施上限的算术（数字必须是活读数）")
    p("cww sail strip 0")
    p("cww oar strip 0")
    p.sleep(0.5)
    bare = team_counts(p, 0)
    expect(bare and bare["facilities"] < c["facilities"],
           "拆干净后设施数低于上限（%s < %s），顶格测试才有意义"
           % (bare and bare["facilities"], c["facilities"]))
    need = c["facilities"] - bare["facilities"]
    if need > 7:
        inconclusive("顶格要补 %d 个设施，而 cww sail rig 的参数上限是 7，够不到顶格" % need)
    else:
        rig = p("cww sail rig 0 %d north" % need)
        top = team_counts(p, 0)
        expect(top and top["facilities"] >= c["facilities"],
               "程序化把设施补到顶格（设施=%s，上限=%s）：%s"
               % (top and top["facilities"], c["facilities"], rig[:40]))
        fac_cell = check(p, 0, 0, "place", 0, 1, 3, "nbw:sail")
        expect("设施位已满" in fac_cell, "顶格时再放帆 → 拒")
        expect("%d/%d" % (top["facilities"], c["facilities"]) in fac_cell,
               "拒绝原因里带的是实时数字，不是写死的文案")
        expect("→ 允许" in check(p, 0, 0, "place", 0, 1, 3, "minecraft:oak_planks"),
               "设施位满了不影响普通方块")
        p("cww sail strip 0")
        p.sleep(0.5)
        expect("→ 允许" in check(p, 0, 0, "place", 0, 1, 3, "nbw:sail"),
               "拆回限内之后帆又能放了：上限每发都重算，没有锁存")

    print("\n== 8. 方块数上限（只改 maxBlocksPerRaft 的夹具图）")
    if not write_cap_arena(p):
        inconclusive("夹具图 test_build 写不出来，方块上限这条没测成")
    else:
        # 先单独 reload：配置校验不过时把原因直接打在断言里。上一版只报"这局开不起来"，
        # 真原因（半径 9 越界 → 文件已被移进 _invalid）要翻服务端日志才知道，这不该转嫁给读日志。
        reload_out = p("cww arena reload " + CAP_ARENA)
        expect("被拒绝" not in reload_out, "夹具图必须过配置校验（limits 四项 + 半径 0..8）：%s" % reload_out[:170])
        p("cww abort")
        p.wait_phase("LOBBY", 180)
        reached, cur = start_match(p, bots, arena=CAP_ARENA)
        expect(reached, "用夹具图 %s 能开新的一局：%s" % (CAP_ARENA, cur[:40]))
        if reached:
            capb = caps(p)
            n0 = team_counts(p, 0)
            deck = CAP_DECK_SIDE * CAP_DECK_SIDE
            expect(n0 and n0["columns"] == deck,
                   "半径 %d 的初始筏甲板列数 = %d（扫描盒罩得住整张筏，上限才不是少算的）：%s"
                   % (CAP_RAFT_RADIUS, deck, n0))
            expect(n0 and n0["blocks"] == deck + 1,
                   "初始筏方块数 = 甲板 %d + 筏心 1：%s" % (deck, n0))
            expect(capb and capb["blocks"] == deck + 1 + CAP_FILL,
                   "夹具图的上限真的生效了（maxBlocks=%s，期望 %s）"
                   % (capb and capb["blocks"], deck + 1 + CAP_FILL))
            if capb and n0:
                need = capb["blocks"] - n0["blocks"]
                expect(need == CAP_FILL, "补满需要 %d 格，正好等于帆 %d + 桨 %d" % (need, 7, 4))
                p("cww sail rig 0 7 north")
                p("cww oar rig 0 %d north" % (CAP_FILL - 7))
                at = team_counts(p, 0)
                expect(at and at["blocks"] == capb["blocks"],
                       "补到顶格：方块=%s == maxBlocksPerRaft=%s" % (at and at["blocks"], capb["blocks"]))
                r1 = check(p, 0, 0, "place", 0, 1, 3, "minecraft:oak_planks")
                expect("方块数已达上限" in r1
                       and "%d/%d" % (capb["blocks"], capb["blocks"]) in r1,
                       "顶格时普通方块也拒，并报出实时数字：%s" % r1[:120])
                p("cww sail strip 0")
                p("cww oar strip 0")
                p.sleep(0.5)
                expect("→ 允许" in check(p, 0, 0, "place", 0, 1, 3, "minecraft:oak_planks"),
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
    """复制 test_sea，只改 id、初始筏半径与 maxBlocksPerRaft，写进 world/cww/arenas/。

    坐标与海域几何一个数都不动，所以不需要重新生成地形（那套 function 只认内置 id）；
    这样"上限变了"是唯一变量，方块门与建造区门不会互相干扰。

    <b>maxBlocksPerRaft 必须 ≥ 100</b>（03 §6.1 第 7 条），上一版直接写 54 会被配置校验判非法、
    文件移进 cww/_invalid/，表现成"这局开不起来"——所以这里改的是把筏做大，而不是把上限做小。
    """
    src = os.path.join(ROOT, "src", "main", "resources", "data", "nbw", "arena", "test_sea.json")
    dst = os.path.join(ROOT, "run", "world", "cww", "arenas", CAP_ARENA + ".json")
    try:
        with open(src, encoding="utf-8") as fh:
            arena = json.load(fh)
    except (OSError, ValueError) as e:
        print("  读不到内置 test_sea：%s" % e)
        return False
    cap = CAP_DECK_SIDE * CAP_DECK_SIDE + 1 + CAP_FILL
    limits = arena.setdefault("limits", {})
    limits["initialRaftRadius"] = CAP_RAFT_RADIUS
    limits["maxBlocksPerRaft"] = cap
    arena["id"] = CAP_ARENA
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(arena, ensure_ascii=False, indent=2))
    print("  写入 %s（半径=%d，maxBlocksPerRaft=%d）" % (dst, CAP_RAFT_RADIUS, cap))
    return True


if __name__ == "__main__":
    sys.exit(main())
