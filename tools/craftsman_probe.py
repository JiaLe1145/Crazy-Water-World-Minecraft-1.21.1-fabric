#!/usr/bin/env python3
"""幸存者工匠加成探针（M5 P1 第二片，2026-11-14）。

要回答三件事：
  1. `/cww survivor job 0 0 craftsman` 之后，队 0 的职业读数出现「工匠1」，
     队 1 仍是水手（对照）。
  2. 队 0 的残骸修复引导时长变成 120 tick（6s），队 1 仍是 160 tick（8s）；
     `raftcore hold` 的回包会直接报出目标 tick 数。
  3. 同时开修：约 6.8 秒时队 0 已 INTACT，队 1 仍在引导；约 9.5 秒时两队都 INTACT。

夹具：flat_test 旱地；两队假玩家都保持 water_breathing。修复走 `/cww raftcore hold`
（服务端等价入口，与真实长按同一条 tick 路径）。
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


def team_jobs(line, team):
    for seg in line.split("｜"):
        if re.search(r"队%d\s" % team, seg):
            m = re.search(r"职业\[([^\]]*)\]", seg)
            return m.group(1) if m else ""
    return ""


def team_phase(status, team):
    m = re.search(r"队%d=(\w+)" % team, status)
    return m.group(1) if m else None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（phase=LOBBY queue=0 teams=0）：%s" % st.replace("\n", " ")[:140])
    if not clean:
        return 1

    # 前缀必须落在 sea_probe.BOT_PREFIXES 里，否则收尾扫不到自己造的假玩家
    bots = [("hc%s0" % time.strftime("%M%S"), 0), ("hc%s1" % time.strftime("%M%S"), 1)]
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

    print("== 1. 只给队 0 换一个工匠")
    print("  " + p("cww survivor job 0 0 craftsman")[:120])
    line = p("cww survivor").replace("\n", " ")
    print("      " + line[:220])
    jobs0, jobs1 = team_jobs(line, 0), team_jobs(line, 1)
    expect("工匠" in jobs0, "队 0 职业读数出现「工匠」（实测 [%s]）" % jobs0)
    expect("工匠" not in jobs1, "队 1 没有被误改（实测 [%s]）" % jobs1)

    print("== 2. 同时摧毁两队筏心，再同 tick 开修（队0 120 tick / 队1 160 tick）")
    print("  " + p("cww raftcore destroy 0")[:120])
    print("  " + p("cww raftcore destroy 1")[:120])
    p.sleep(0.5)
    msg0 = p("cww raftcore hold %s 0 120" % bots[0][0].lower())
    msg1 = p("cww raftcore hold %s 1 160" % bots[1][0].lower())
    print("      hold0: " + msg0[:160])
    print("      hold1: " + msg1[:160])
    expect("120 tick" in msg0 and "工匠加成" in msg0,
           "队 0 的引导被报成 120 tick（工匠加成）")
    expect("160 tick" in msg1 and "工匠加成" not in msg1,
           "队 1 的引导仍是 160 tick（无工匠）")

    print("== 3. 约 6.8 秒后：队 0 应已修好，队 1 还在引导")
    p.sleep(6.8)
    mid = p("cww raftcore status")
    print("      <%s>" % mid.replace("\n", " ")[:300])
    ph0, ph1 = team_phase(mid, 0), team_phase(mid, 1)
    expect(ph0 == "INTACT", "120 tick 时队 0 已恢复（实测 %s）" % ph0)
    expect(ph1 == "WRECKAGE", "同一时刻队 1 仍在引导（实测 %s）" % ph1)

    print("== 4. 再等 3 秒：队 1 也应在 160 tick 时恢复")
    p.sleep(3.0)
    late = p("cww raftcore status")
    print("      <%s>" % late.replace("\n", " ")[:300])
    expect(team_phase(late, 0) == "INTACT", "队 0 保持 INTACT")
    expect(team_phase(late, 1) == "INTACT", "队 1 在 160 tick 后恢复（实测 %s）" % team_phase(late, 1))

    print("== 5. 收尾")
    p("cww abort")
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
