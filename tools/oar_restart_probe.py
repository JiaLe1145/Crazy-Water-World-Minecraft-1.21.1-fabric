#!/usr/bin/env python3
"""桨位重启恢复探针（M2-5）。

分两段跑，因为重启服务端必须由外部做（照 restart_probe.py 的两段式设计）：

    python tools/oar_restart_probe.py before      # 开局、挂 2 台桨、划起来、落盘、停服
    <重启 ./gradlew runServer>
    python tools/oar_restart_probe.py after       # 不许重新挂桨，桨位表必须自己回来

要证的是 OarService 类注释里那句话：**权威身份在 BE 的 NBT 里，那张表只是运行时索引**。
只说"看起来恢复了"不算证据，所以 after 段有三条互相独立的断言：
  1. 日志里出现 2 行 [oar] 桨位回填 —— 数量对得上，且来源是 BE 自己登记的；
  2. 读数里 桨=2 —— 而本段全程没有调用过 oar rig；
  3. 重新登记一个划桨的人之后速率真的起来 —— 回填的不是空壳。

前置：RCON_PORT=25576 RCON_PASSWORD=nbwm0；test_sea 已经建过海。
"""
import io
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, keep_alive, sweep_stale_bots  # noqa: E402
from oar_probe import FORMAT, SEA, TEAM, read_oar, write_oar_format  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HANDOFF = os.path.join(ROOT, "run", "oar_restart_handoff.json")
OARS = 2


def boot():
    """等服务端起来。P() 的构造就会连 RCON，连不上是常态而不是错误。"""
    last = None
    for _ in range(40):
        try:
            return P()
        except Exception as e:      # noqa: BLE001 - 重试循环就是要吞掉所有连接期异常
            last = e
            time.sleep(3)
    raise RuntimeError("服务端 120 秒内没起来：%s" % last)


def start_round(p, names):
    """开一局并把风关掉；两队各一人，缺一队就会立刻判胜（#34 那条坑）。"""
    for n in names:
        p("player %s kill" % n)
    for n in names:
        p("player %s spawn" % n)
    for _ in range(30):
        online = p("list").lower()
        if all(n.lower() in online for n in names):
            break
        p.sleep(1)
    else:
        raise RuntimeError("假玩家没上线：%s" % p("list")[:160])
    p("cww force %s 0" % names[0])
    p("cww force %s 1" % names[1])
    keep_alive(p, [(n, i) for i, n in enumerate(names)])
    p("cww start %s %s" % (FORMAT, SEA))
    reached, cur = p.wait_phase("PLAY", 200)
    if not reached:
        raise RuntimeError("进不了 PLAY：%s" % cur[:80])
    p("cww wind set south 0.0")


def before():
    stamp = time.strftime("%M%S")
    names = ["r%sA" % stamp, "r%sB" % stamp]
    p = boot()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    sweep_stale_bots(p)
    write_oar_format()
    print("== before. 开局、挂 %d 台桨、划起来，然后落盘停服" % OARS)
    start_round(p, names)
    p("cww oar strip %d" % TEAM)
    rigged = p("cww oar rig %d %d south" % (TEAM, OARS))
    expect(("挂上 %d 台桨台" % OARS) in rigged,
           "挂桨要成功（队 %d 共 %d 台）" % (TEAM, OARS))
    expect(read_oar(p)["oars"] == OARS, "挂完读数就是 %d 台" % OARS)
    held = p("cww oar hold %s %d south 600" % (names[0], TEAM))
    expect("已登记" in held, "登记划桨要成功（回：%s）" % held[:70])
    p.sleep(6)
    r = read_oar(p)
    expect(r["speed"] > 0.02, "重启前必须先划得动（速率 %.4f），否则 after 段的 0 无意义" % r["speed"])
    with open(HANDOFF, "w", encoding="utf-8") as fh:
        json.dump({"stamp": stamp, "names": names, "team": TEAM, "oars": OARS,
                   "speed_before": r["speed"], "rowers_before": r["rowers"]}, fh, ensure_ascii=False)
    p("cww save")
    print("      已落盘：%s（速率 %.4f）" % (os.path.basename(HANDOFF), r["speed"]))
    p("stop")
    print("RESULT: %s —— 现在重启 ./gradlew runServer，再跑 after 段"
          % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


def log_text():
    """整份控制台日志。

    不能用 p.log() 的增量：回填发生在世界加载那几拍里，很可能早于 RCON 开口，
    游标一切就什么都没剩下 —— 那时"日志里要有 N 行"会变成一条恒假的断言，
    而它要证的恰恰是最关键的一条。日志每次重启都被 > 覆盖，所以整份读就是本次启动。
    """
    path = os.path.join(ROOT, "run", "server-console.log")
    if not os.path.exists(path):
        return ""
    return io.open(path, encoding="utf-8", errors="replace").read()


def after():
    p = boot()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    with open(HANDOFF, encoding="utf-8") as fh:
        hand = json.load(fh)
    print("== after. 不许重新挂桨，桨位表必须自己回来")
    # run/server-console.log 是 Gradle 缓冲的 stdout，回填行可能还没刷到盘上；
    # 一次性读会把"还没刷"当成"没有回填"，所以轮询到出现为止（M2-RESULTS 早就记过这条缓冲特性）。
    rows = []
    for _ in range(30):
        rows = re.findall(r"\[oar\] 桨位回填 team=(-?\d+) slot=(-?\d+) dir=\(([-\d.]+), ([-\d.]+)\)"
                          r" plot 偏移=\((-?\d+),(-?\d+),(-?\d+)\)", log_text())
        if rows:
            break
        p.sleep(2)
    slots = sorted(set(int(r[1]) for r in rows))
    teams = set(int(r[0]) for r in rows)
    expect(teams == {hand["team"]} and slots == list(range(hand["oars"])),
           "回填出去的桨位必须恰好是队 %d 的 0..%d（实测 team=%s slot=%s，共 %d 行）"
           % (hand["team"], hand["oars"] - 1, sorted(teams), slots, len(rows)))
    dirs = sorted(set((float(r[2]), float(r[3])) for r in rows))
    offs = sorted(set((int(r[4]), int(r[5]), int(r[6])) for r in rows))
    expect(dirs == [(0.0, 1.0)], "朝南的桨回填后方向必须还是 (0, 1)（实测 %s）" % dirs)
    expect(offs == [(-3, 1, -2), (-3, 1, -1)],
           "plot 偏移必须原样回来，拆桨就靠它寻址（实测 %s）" % offs)

    # 行数多于桨位数 = 同一个桨位被多个 sub-level 认领（dev 世界残留的同号筏体 nbw_raft_N）。
    # 这不是可以忽略的"环境噪声"：产品侧必须把它吵出来，所以两种情形分别断言，都能失败。
    dup = len(rows) - len(slots)
    conflicts = re.findall(r"桨位 \d+ 已被 sub-level", log_text())
    if dup:
        expect(len(conflicts) >= 1,
               "有 %d 次重复认领（场上存在同号重复筏体），日志必须出现\"已被 sub-level 认领\"的 ERROR"
               "（实际 %d 行）" % (dup, len(conflicts)))
    else:
        expect(not conflicts, "没有重复认领时不该冒出冲突 ERROR（实际 %d 行）" % len(conflicts))

    r = read_oar(p)
    expect(r["oars"] == hand["oars"],
           "本段一次 oar rig 都没调用，读数却必须是 桨=%d（实测 %.0f）" % (hand["oars"], r["oars"]))
    expect(r["rowers"] == 0,
           "划桨是实时状态，重启后必须归零（实测在划 %.0f）—— 非 0 就是凭空多出动力" % r["rowers"])
    phase = p("cww phase")
    expect("PLAY" in phase, "对局本身要恢复到 PLAY（当前：%s）" % phase[:60])

    # 拆桨台数只作观测：残留的同号筏体让"这队的筏子"到底指哪一具变得不确定，
    # clearPlotBlock 寻址到哪一具不属于本探针能控制的范围；表清空才是本段要判的。
    adopted = ""
    for _ in range(20):
        adopted = p("cww raft all")
        if "队%d Y=" % hand["team"] in adopted:
            break
        p.sleep(1)
    expect("队%d Y=" % hand["team"] in adopted,
           "重启后这队的筏必须被重新认领（回：%s）—— 没认领上，拆桨与桨力都无从判起" % adopted[:70])
    stripped = p("cww oar strip %d" % hand["team"])
    print("      拆桨回包：%s" % stripped[:90])
    expect(read_oar(p)["oars"] == 0, "拆完桨位表必须空（实测 %.0f）" % read_oar(p)["oars"])
    again = p("cww oar strip %d" % hand["team"])
    expect("拆掉 0 台" in again, "第二次拆应当什么也拆不到（回：%s）" % again[:60])

    old = hand["names"][0]
    p("player %s kill" % old)
    p("player %s spawn" % old)
    for _ in range(30):
        if old.lower() in p("list").lower():
            break
        p.sleep(1)
    refuse = p("cww force %s 0" % old)
    expect("禁止中途加入" in refuse or "不得改队" in refuse,
           "PLAY 中必须拒绝把这人来路不明的席位配上（回：%s）—— 两条话术分别对应"
           "\"没有席位\"与\"已有席位想改队\"，都算拒绝" % refuse[:80])
    print("      说明：重启后场上不再有已分配的队员，而对局进行中既不让中途加入也不让改队，"
          "Carpet 假玩家重启后又不是同一个 UUID。所以「重启后仍然划得动」这条不在本探针里判，"
          "它留在 M2-RESULTS §6 的真人清单里。")
    p("cww stop")
    p("player %s kill" % old)
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "before":
        sys.exit(before())
    if mode == "after":
        sys.exit(after())
    print("用法：python tools/oar_restart_probe.py before|after（两段之间要重启服务端）")
    sys.exit(2)
