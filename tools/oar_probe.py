#!/usr/bin/env python3
"""桨台推进端到端探针（M2-5 动力第二源）。

前置：
  1. ./gradlew runServer 已经起来（日志里能看到本轮的模组版本）
  2. RCON_PORT=25576 RCON_PASSWORD=nbwm0（tools/rcon.py 的默认值是旧仓库的，别用默认）
  3. 测试世界里 test_sea 已经建过海（sea_probe.py 的第 3~5 步会建；单独跑本探针时先确认）

跑法：
  python tools/oar_probe.py

夹具为什么复用 sea_probe 而不是自带一份：RCON 那三条坑（鉴权多回一个包、响应过大被掐、
双发哨兵被断连）只在 sea_probe 里修过一次。再抄一份就等于把同一颗雷埋两遍。

设计上的三条硬规矩：
  * 每条断言都必须能失败。所以第一段是**负对照**：一台桨都没有时 hold 必须被拒、
    速率必须趴在 0；这一段过了，后面的读数才有意义。
  * 风力全程设成 0。桨与风共用同一条航速上限，如果不先关掉风，
    "桨让筏子动了"这个结论就永远分不清是桨还是风。
  * 上限、目标、档位一律从服务端读数行里取，不在探针里抄常量——
    抄一份就可能出现"探针与代码不一致还全绿"。
  * 断言全部打在「位移」速度上（#39）。施力后那一瞬的峰值只打印出来作诊断：
    它比位移高一个数量级，拿它当"每秒走几格"会让每一档数值都骗人。
"""
import io
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import LOG, P, keep_alive, sweep_stale_bots  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEA = "test_sea"
WATER_LEVEL = 62
FORMAT = "oar_long"
TEAM = 0
# test_sea 圆心 (-1000,1000) 半径 60；把筏子放到偏北的位置往南跑，可跑距离约 114 格
START = (-1000, WATER_LEVEL + 1, 946)


def write_oar_format():
    """2 队 × 4 人、PLAY 900 秒的夹具赛制。

    为什么不用 sail_long（2×2）：桨的档位要测到"满员 4 人同向"，
    一队只有 2 人就测不出桨数↑航速↑的曲线后半段；
    而把所有假玩家塞进同一队会让另一队空掉，aliveTeamCount()==1 直接判胜提前结算
    —— 那是 #34 已经踩过的坑，读数会全变成"场上没筏子"的 0。
    """
    import json
    path = os.path.join(ROOT, "run", "world", "cww", "formats", FORMAT + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    body = {
        "schemaVersion": 1,
        "id": FORMAT,
        "teams": 2,
        "teamSize": 4,
        "minPlayers": 2,
        "duration": {"phases": [5, 5, 900, 60], "targetTotal": 1000},
        "border": {"stages": [
            {"at": 60, "radiusScale": 1.0, "damage": 0.0, "warn": 15},
            {"at": 900, "radiusScale": 0.05, "damage": 1.0, "warn": 10}]},
        "loot": {"tierBias": "standard", "respawnIntervalSec": 22},
    }
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(body, ensure_ascii=False, indent=2))
    return path


def raft_copies(p, team=TEAM):
    """盘上同名 nbw_raft_N 有几只。

    这是"漂行距离能不能信"的前提，不是装饰：同名残骸会让按名字或 UUID 取到的个体不是同一个，
    现场表现就是速率 0.1078 格/tick 而漂行 0 格（见任务 #38）。
    速度读数不受它影响（服务端按 UUID 读刚体力学的实时线速度），所以这里只打印可信度、
    不拿漂行做断言 —— 一条恒过的断言比没有更糟。
    """
    out = p("sable storage find nbw_raft_%d" % team)
    return max(1, out.count("nbw_raft_%d:" % team))


def raft_uuid(p, team=TEAM):
    """本局这队「当前那只筏」的 UUID。

    为什么不用 sable info @e[name=nbw_raft_0]：dev 世界的 sub-level 存储里会留下同名残骸
    （实测 find_all_sub_levels 一次列出 3 只 nbw_raft_0 与 3 只 nbw_raft_1，
    是多轮开局与重启累积出来的；sable remove 只清运行时对象，不清盘上的存储）。
    按名字取会把「速度」与「位置」配到不同个体上，表现成速率 0.108 而漂行 0 格。
    服务端的速度本来就是按 UUID 读的（RaftSpawner.raftOf），位置也必须按同一个 UUID 读。
    """
    out = p("cww raft status")
    m = re.search(r"绑:队%d\u2192([0-9a-fA-F-]+)" % team, out)
    if not m:
        m = re.search(r"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})", out)
    return m.group(1) if m else None


def raft_xyz(p, uuid):
    """按 UUID 精确取那只筏的世界 XZ。"""
    out = p("sable info %s" % uuid)
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return (float(m.group(1)), float(m.group(3))) if m else (0.0, 0.0)


def read_oar(p, team=TEAM):
    """解析 /cww oar read 那一行。缺字段一律返回 -1/None，让断言真的失败。"""
    line = p("cww oar read %d" % team)
    def num(pattern):
        m = re.search(pattern, line)
        return float(m.group(1)) if m else -1.0
    # 速度=/速率= 现在是「位移」口径（相邻两 tick 的位姿差），也就是玩家看见的快慢；
    # 峰值只作诊断——它比位移高一个数量级，以前正是它被当成航速，数值才全错（#39）。
    return {
        "line": line,
        "oars": num(r"桨=(\d+)"),
        "rowers": num(r"在划=(\d+)"),
        "strokes": num(r"桨档=([-\d.]+)"),
        "vx": num(r"速度=\(([-\d.eE+]+),"),
        "vz": num(r"速度=\([-\d.eE+]+,\s*([-\d.eE+]+)\)"),
        "speed": num(r"速率=([-\d.eE+]+)"),
        "target": num(r"目标=([-\d.]+)"),
        "peak": num(r"峰值=([-\d.]+)"),
        "cap": num(r"上限=([-\d.]+)"),
    }


def sample(p, team=TEAM, settle=4.0, reads=4, gap=0.6):
    """复位到起点、静置、再取均值。

    每次都复位是因为 test_sea 只有半径 60：上一档漂到岸上的筏子读出来的 0
    会被误读成"桨推不动"。静置时间给足，终端速度才有意义。
    """
    uuid = raft_uuid(p, team)
    if uuid is None:
        print("      \u26a0 拿不到队 %d 的筏 UUID，漂行距离无法与速率配对" % team)
    p("sable teleport %s %d %d %d" % (uuid, START[0], START[1], START[2]))
    p.sleep(settle)
    got = [read_oar(p, team) for _ in range(reads)]
    # 单次最大值单独留出来：只看均值会把几秒的 runaway 平均掉（GAIN=20 那次就是这么漏掉的）
    pos = raft_xyz(p, uuid) if uuid else (0.0, 0.0)
    drift = math.hypot(pos[0] - START[0], pos[1] - START[2])
    mean = lambda k: sum(g[k] for g in got) / len(got)
    # 峰值才是"终端速度"的口径：高推力档在静置期里还没收敛，取均值会系统性低估，
    # 表现成"4 人反而没有 1 人的四倍快"这种其实是采样窗口的问题
    return {"speed": mean("speed"), "peak": max(g["speed"] for g in got),
            "vx": mean("vx"), "vz": mean("vz"),
            "strokes": mean("strokes"), "target": mean("target"),
            "impulse_peak": mean("peak"), "cap": max(g["cap"] for g in got),
            "oars": got[0]["oars"], "rowers": got[0]["rowers"],
            "drift": drift, "line": got[0]["line"]}


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()
    # 连上服务端时的日志末尾位置。用它取"本次运行以来"的完整日志：
    # 残留清理发生在开局那一刻，而 step 11 的 p.log() 游标早已被 step 1 推进过，只看增量会漏掉。
    log_base = p.logpos
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    stale = sweep_stale_bots(p)
    if stale:
        print("      清掉上一轮残留的假玩家：%s" % stale)
    write_oar_format()

    # 上一轮探针中途崩掉时不会走到 cww stop，对局会一直挂在 PLAY 上。
    # 那时 cww force 被"对局进行中不得改队"拒掉，本段后面会看到十几条
    # "这人不在队 0（他的队号=-1）" —— 那是夹具残留，不是产品缺陷，必须先收掉再判。
    cur = p("cww phase")
    if "LOBBY" not in cur and "IDLE" not in cur:
        print("      检测到遗留对局（%s），先 abort 收场" % cur[:40])
        p("cww abort")
        for _ in range(30):
            if "LOBBY" in p("cww phase") or "IDLE" in p("cww phase"):
                break
            p.sleep(2)
        else:
            expect(False, "遗留对局收不掉，当前 %s" % p("cww phase")[:60])
            return 1

    print("== 1. 开一局 2×4 的长局，并把风关掉")
    stamp = time.strftime("%M%S")
    # 前 4 个进队 0（要测的桨就在队 0），后 4 个进队 1。另一队必须有人：
    # 空掉一队会让 aliveTeamCount()==1 立刻判胜、筏子被结算收走，后面全是"场上没筏子"的 0（#34）
    t0 = ["o%sA%d" % (stamp, i) for i in range(4)]
    t1 = ["o%sB%d" % (stamp, i) for i in range(4)]
    bots = [(n, 0) for n in t0] + [(n, 1) for n in t1]
    for name, _ in bots:
        p("player %s kill" % name)
    for name, _ in bots:
        p("player %s spawn" % name)
    for _ in range(30):
        online = p("list").lower()   # Carpet 回显首字母大写，比对必须不分大小写
        if all(n.lower() in online for n, _ in bots):
            break
        p.sleep(1)
    else:
        expect(False, "假玩家没能全部上线，只有这些：%s" % p("list")[:200])
    for name, team in bots:
        p("cww force %s %d" % (name, team))
    keep_alive(p, bots)
    p("cww start %s %s" % (FORMAT, SEA))
    reached, cur = p.wait_phase("PLAY", 200)
    expect(reached, "oar_long 能开整局并进到 PLAY（当前：%s）" % cur[:60])
    if not reached:
        print("\n=== 进不了 PLAY，探针到此为止（后面的读数都会是假的 0）===")
        return 1
    p.log()   # 采帧时间前先推进日志游标：8 个假玩家同时上线本身欠了一拍，不该算进划桨这段
    p("cww wind set south 0.0")
    calm = p("cww wind read %d" % TEAM)
    expect("strength=0.00" in calm, "风力必须置 0，否则桨与风的贡献分不开：%s" % calm[:70])
    p("cww oar strip %d" % TEAM)
    # 筏子是在 PLAY 的那一 tick 之后才放好的，命令翻转与生成之间有一拍；
    # 读一次就判"海没建"会把夹具的时序当成产品结论，所以轮询到出现读数为止。
    # 命令必须用 cww raft all：Y= 与质量只在 RaftSpawner.status 里，
    # cww raft status 打的是沉筏相位，永远没有 Y=，用它只会假失败。
    copies = raft_copies(p)
    print("      盘上 nbw_raft_%d 共 %d 只；漂行距离%s"
          % (TEAM, copies, "可信" if copies == 1 else "只作参考（同名残骸污染，见 #38）"))
    y = -999.0
    for _ in range(20):
        m = re.search(r"队0 Y=([-\d.]+)", p("cww raft all"))
        if m:
            y = float(m.group(1))
            break
        p.sleep(1)
    expect(abs(y - (WATER_LEVEL + 1)) < 6,
           "筏子必须浮在 test_sea 水面附近（Y=%.2f）；差这么多说明这片海还没建 —— "
           "先跑 tools/sea_probe.py 建海，否则下面每一条速率读数都是假的" % y)

    print("== 2. 负对照：一台桨都没有时，必须既划不动也推不走")
    denied = p("cww oar hold %s %d south 60" % (t0[0], TEAM))
    expect("一台桨都没有" in denied, "没挂桨时 hold 必须被拒（回：%s）" % denied[:70])
    base = sample(p, settle=6.0)
    expect(base["speed"] < 0.01,
           "无桨无风时速率必须趴在 0（实测 %.4f）—— 这条过了，后面的桨读数才有意义" % base["speed"])

    print("== 3. 一台桨：方向必须朝南、速率要落在桨档")
    rigged = p("cww oar rig %d 1 south" % TEAM)
    r3 = read_oar(p)
    expect("挂上 1 台桨台" in rigged and r3["oars"] == 1, "挂 1 台桨要读数一致（%s）" % rigged[:60])
    expect(r3["cap"] > 0, "读数行要带服务端位移上限（探针不自己抄常量）：%.3f" % r3["cap"])
    expect(r3["strokes"] == 0 and r3["target"] == 0,
           "没人划的时候桨档与目标都必须是 0（实测 桨档=%.2f 目标=%.4f）—— 这条是桨力的负对照，"
           "它若恒过，就等于承认「有人没人在划都一样」，整段读数作废" % (r3["strokes"], r3["target"]))
    first_hold = p("cww oar hold %s %d south 240" % (t0[0], TEAM))
    expect("已登记" in first_hold,
           "队 0 的假玩家必须真的拿到席位（回：%s）—— 拿不到就是夹具席位没配好，"
           "后面每条读数都没有对象" % first_hold[:80])
    held = read_oar(p)
    expect(abs(held["strokes"] - 1.0) < 1e-6,
           "一台朝南的桨在有人划时桨档应当是 1.00，实测 %.2f" % held["strokes"])
    expect(abs(held["target"] - 0.0167) < 1e-6,
           "单人单桨的目标位移速度应当是 0.0167 格/tick（02 §6 的 2 格/6 秒），实测 %.4f"
           % held["target"])
    one = sample(p)
    print("      1 人划桨：均值 %.4f 峰值 %.4f 格/tick（%.2f 格/秒）v=(%.3f, %.3f) 漂行 %.0f 格"
          % (one["speed"], one["peak"], one["peak"] * 20, one["vx"], one["vz"], one["drift"]))
    expect(one["rowers"] == 1, "登记后在划人数必须是 1（实测 %.0f）" % one["rowers"])
    expect(one["vz"] > 0 and abs(one["vx"]) < 0.05,
           "朝南的桨必须往 +Z 推（实测 v=(%.3f, %.3f)）；符号反了就是 north/south 换算错了"
           % (one["vx"], one["vz"]))
    expect(0.008 <= one["speed"] <= 0.030,
           "单人单桨的「位移」速度要在 0.008..0.030 格/tick（0.16..0.6 格/秒）内，"
           "对应 02 §6 的 2 格/6 秒；实测 %.4f 格/tick = %.2f 格/秒" % (one["speed"], one["speed"] * 20))
    expect(abs(one["speed"] - one["target"]) <= max(1e-4, 0.20 * one["target"]),
           "控制器必须把位移追上目标（差 20%% 以内）：目标 %.4f 实测 %.4f"
           % (one["target"], one["speed"]))

    print("== 4. 人数↑ 速率↑（桨档玩法成立的前提）")
    p("cww oar strip %d" % TEAM)
    p("cww oar rig %d 2 south" % TEAM)
    for n in (0, 1):
        p("cww oar hold %s %d south 240" % (t0[n], TEAM))
    two = sample(p)
    print("      2 人划桨：速率 %.4f 格/tick（%.2f 格/秒）在划 %.0f"
          % (two["speed"], two["speed"] * 20, two["rowers"]))
    expect(two["rowers"] == 2, "两台桨两个人，在划必须是 2（实测 %.0f）" % two["rowers"])
    expect(two["peak"] >= one["peak"] * 1.3,
           "2 人应当明显快过 1 人（峰值 %.4f vs %.4f），否则桨数不再影响航速" % (two["peak"], one["peak"]))

    print("== 5. 满员 4 人：仍然不许越过服务端上限")
    p("cww oar strip %d" % TEAM)
    p("cww oar rig %d 4 south" % TEAM)
    for n in range(4):
        p("cww oar hold %s %d south 240" % (t0[n], TEAM))
    four = sample(p, settle=3.0)
    expect(four["cap"] > 0, "读数行要带上限（实测 %.3f）" % four["cap"])
    print("      4 人划桨：速率 %.4f 格/tick，服务端上限 %.3f，漂行 %.0f 格"
          % (four["speed"], four["cap"], four["drift"]))
    if four["drift"] > 90:
        print("      ⚠ 漂行 %.0f 格，接近 test_sea 边缘 —— 该档只作参考" % four["drift"])
    expect(four["peak"] <= four["cap"] * 1.05,
           "满员划桨不许越过上限 +5%%（实测峰值 %.4f > %.4f）" % (four["peak"], four["cap"] * 1.05))
    expect(four["peak"] > one["peak"], "4 人至少要比 1 人快（峰值 %.4f vs %.4f）"
           % (four["peak"], one["peak"]))

    # 位移实测：满员那一档的登记窗口还有十几秒，正好用满。
    # 为什么必须单独量一次位移：linearVelocity 读到的是"刚施完冲量那一瞬"的峰值，
    # 而水阻在每个 tick 里把绝大部分速度吃掉（对照实验：impulse 12 之后读数还有 0.0169，
    # 实际只走了 0.23 格）。只看读数会得出一个系统性偏大的"航速"口径 —— 见任务 #39。
    uuid = raft_uuid(p, TEAM)
    p("sable teleport %s %d %d %d" % (uuid, START[0], START[1], START[2]))
    t_start = time.time()   # 别叫 t0：那是本探针的队 0 假玩家列表，覆盖掉后面整段都会炸
    x0, z0 = raft_xyz(p, uuid)
    p.sleep(8.0)
    x1, z1 = raft_xyz(p, uuid)
    travelled = math.hypot(x1 - x0, z1 - z0)
    seconds = max(0.5, time.time() - t_start)
    read_v = read_oar(p)["speed"]
    print("      位移实测：%.1f 秒走了 %.1f 格 = %.2f 格/秒；同时刻速率读数 %.4f 格/tick（%.2f 格/秒），"
          "读数/位移 = %.1f 倍" % (seconds, travelled, travelled / seconds, read_v, read_v * 20,
                                   (read_v * 20) / max(0.01, travelled / seconds)))
    expect(travelled > 5.0,
           "满员划桨 8 秒必须真的走出 5 格以上（实测 %.1f 格 = %.2f 格/秒）——"
           "走不出去，控制器口径再自洽也没有玩法意义" % (travelled, travelled / seconds))
    # 上界同样要断言：GAIN=20 那次实测 8.4 秒飞了 1475 格（筏子被甩出海域后脱离水阻失控），
    # 而均值采样恰好没抓到那一段，只靠"均值不超上限"根本拦不住 runaway。
    expect(travelled < 40.0,
           "满员划桨 8 秒不该飞出海域（实测 %.1f 格 = %.2f 格/秒）——"
           "4 人的目标是 1.33 格/秒，超过这个量级 3 倍以上就是控制器在甩筏子"
           % (travelled, travelled / seconds))
    runaway = [g for g in [one["peak"], two["peak"], four["peak"]] if g > four["cap"] * 1.5]
    expect(not runaway, "任何一档的单次读数都不该越过上限 1.5 倍（实测 %s，上限 %.3f）"
           % (["%.3f" % x for x in runaway], four["cap"]))
    expect(abs(four["speed"] - four["target"]) <= max(1e-4, 0.20 * four["target"]),
           "满员时控制器也要把位移追上目标（差 20%% 以内）：目标 %.4f 实测 %.4f"
           % (four["target"], four["speed"]))

    print("== 6. 反向桨互相抵消：乱装桨不该白得动力")
    p("cww oar strip %d" % TEAM)
    p("cww oar rig %d 2 south" % TEAM)
    p("cww oar rig %d 2 north" % TEAM)
    r6 = read_oar(p)
    expect(r6["oars"] == 4, "两次 rig 应当共 4 台桨（实测 %.0f）" % r6["oars"])
    registered = []
    for n in range(2):
        for facing, idx in (("south", n), ("north", n + 2)):
            out = p("cww oar hold %s %d %s 240" % (t0[idx], TEAM, facing))
            registered.append((t0[idx], facing, "已登记" in out, out[:60]))
    failed = [r for r in registered if not r[2]]
    expect(not failed, "四台桨都该登记成功，失败的是：%s" % failed)
    cancel = sample(p, settle=6.0)
    print("      2 南 + 2 北：桨档 %.2f，目标 %.4f，位移速率 %.4f，在划 %.0f"
          % (cancel["strokes"], cancel["target"], cancel["speed"], cancel["rowers"]))
    expect(cancel["rowers"] == 4, "四台桨都该有人（实测 %.0f）" % cancel["rowers"])
    expect(abs(cancel["strokes"]) < 1e-6 and abs(cancel["target"]) < 1e-6,
           "南向与北向桨数相等时净桨档与目标都必须归零（实测 桨档=%.2f 目标=%.4f）"
           % (cancel["strokes"], cancel["target"]))
    expect(cancel["peak"] < one["peak"] * 0.5,
           "抵消后的速率应当远低于单人单桨（峰值 %.4f vs %.4f），否则抵消没生效"
           % (cancel["peak"], one["peak"]))

    print("== 7. 一支桨一个人：第三个人挤不上只剩一台空桨的两桨位")
    p("cww oar strip %d" % TEAM)
    p("cww oar rig %d 2 south" % TEAM)
    first = p("cww oar hold %s %d south 240" % (t0[0], TEAM))
    second = p("cww oar hold %s %d south 240" % (t0[1], TEAM))
    third = p("cww oar hold %s %d south 240" % (t0[2], TEAM))
    expect("已登记" in first and "已登记" in second, "两台桨应当各自登记成功（%s / %s）"
           % (first[:40], second[:40]))
    expect("没有空着的" in third, "第三个人必须被拒（回：%s）" % third[:70])
    expect(read_oar(p)["rowers"] == 2, "被拒之后在划人数仍是 2（实测 %.0f）" % read_oar(p)["rowers"])

    cross = p("cww oar hold %s %d south 240" % (t1[0], TEAM))
    expect("不在队" in cross, "别队的人占这队的桨位必须被拒（回：%s）" % cross[:80])
    expect(read_oar(p)["rowers"] == 2, "被拒之后在划人数仍是 2（实测 %.0f）" % read_oar(p)["rowers"])

    print("== 8. 一个人一支桨：同一个人对第二台桨续包不叠力")
    move = p("cww oar hold %s %d south 240" % (t0[0], TEAM))
    expect("换桨得先松手" in move, "已占桨位的人想再占一台必须被拒（回：%s）" % move[:80])
    expect(read_oar(p)["rowers"] == 2, "拒掉之后在划仍是 2（实测 %.0f）" % read_oar(p)["rowers"])

    print("== 9. 断输入即失力：窗口过期后动力必须消失")
    p("cww oar strip %d" % TEAM)
    p("cww oar rig %d 4 south" % TEAM)
    for n in range(4):
        p("cww oar hold %s %d south 40" % (t0[n], TEAM))   # 只给 40 tick（2 秒）
    p.sleep(1.0)
    during = read_oar(p)
    p.sleep(14.0)
    after = sample(p, settle=2.0)
    print("      窗口内：在划 %.0f 速率 %.4f；窗口过后 14 秒：在划 %.0f 速率 %.4f"
          % (during["rowers"], during["speed"], after["rowers"], after["speed"]))
    expect(after["rowers"] == 0, "窗口过后在划人数必须归零（实测 %.0f）" % after["rowers"])
    expect(after["speed"] < 0.02,
           "停止输入后应当被水阻拖停（实测 %.4f 格/tick）；P-01 说的只是惯性滑行，不是永久推进"
           % after["speed"])

    print("== 10. 拆桨与局后清理：不许残留动力")
    stripped = p("cww oar strip %d" % TEAM)
    r10 = read_oar(p)
    expect("拆掉 4 台桨台" in stripped and r10["oars"] == 0,
           "拆桨要把桨位表清空（%s | 桨=%.0f）" % (stripped[:50], r10["oars"]))
    p("cww oar rig %d 1 south" % TEAM)
    p("cww oar hold %s %d south 240" % (t0[0], TEAM))
    p("cww stop")
    for _ in range(40):
        if "RETURN" in p("cww phase") or "IDLE" in p("cww phase"):
            break
        p.sleep(1)
    r11 = read_oar(p)
    expect(r11["oars"] == 0 and r11["rowers"] == 0,
           "局后桨位表与在划状态都必须归零（桨=%.0f 在划=%.0f）" % (r11["oars"], r11["rowers"]))

    print("== 11. 划桨期间不许把服务端拖卡")
    log = p.log()
    behind = re.findall(r"Can't keep up!", log)
    gaps = [int(m.group(1)) for m in re.finditer(r"最大 tick 间隔 (\d+) ms", log)]
    expect(not behind, "整段划桨不该出现 tick 落后（实测 %d 次）" % len(behind))
    if gaps:
        expect(max(gaps) <= 400, "帧时间要用 tick 间隔量，不看门狗累计欠账（实测最大 %d ms）" % max(gaps))

    # 按名字反查筏体必须始终没有歧义。日志里出现过"命中 N 个 sub-level"就说明世界里有同名残骸，
    # 那么这一轮的漂行距离、桨位归属、票回收全都指向不同个体，结论要整体打折（见 #38）。
    # RaftSpawner.clearNameSlot 之后这条应该恒绿；它红了就说明残骸又开始累积。
    with io.open(LOG, encoding="utf-8", errors="replace") as fh:
        fh.seek(log_base)
        whole = fh.read()
    ambiguous = re.findall(r"命中 (\d+) 个 sub-level", whole)
    cleaned_at = whole.rfind("清掉上一局残留的同名筏")
    # 一刀切地要求"从来没出现过歧义 WARN"是错的：清理机制第一次按名字找的时候
    # 本来就会撞上残骸并打出这条 WARN，那正是它在工作的证据。要看的是**最后一次清理之后**
    # 还有没有歧义 —— 残骸若重新累积，这里就会红。
    late = whole[cleaned_at:].count("命中") if cleaned_at >= 0 else len(ambiguous)
    expect(late == 0,
           "最后一次清理残留之后不应再出现按名字反查歧义（清理后仍出现 %d 次；本次运行累计 %d 次）"
           % (late, len(ambiguous)))
    print("      开局清掉的同名残留：%d 次；累计歧义 WARN：%d 次；清理之后仍歧义：%d 次"
          % (whole.count("清掉上一局残留的同名筏"), len(ambiguous), late))

    for name, _ in bots:
        p("player %s kill" % name)
    print("\n=== OAR PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
