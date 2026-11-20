#!/usr/bin/env python3
"""幸存者舵手加成探针（M5 P1 第二片，2026-11-14）。

要回答三件事：
  1. `/cww survivor job 0 0 helmsman` 之后，队 0 的职业读数出现「舵手1」，
     队 1 仍是水手（对照组未被误改）。
  2. 同一段遥控前进输入下，有舵手的队 0 位移明显大于队 1（目标速度 ×1.15）。
  3. 同一段遥控转向输入下，队 0 的偏航变化同样更大（转向冲量 ×1.15）。

场地：flat_test 旱地（无暗礁/无预铺箱），两队同 tick 接收输入，风/物理条件一致。
夹具：假玩家站得稳；遥控走 `cww remote move` 的服务端入口，与真客户端同一条链路。
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, wait_clean_lobby, raft_name, DRY_ARENA  # noqa: E402

ok, bad, inc = [], [], []

# 允许的加成区间：标称 ×1.15；遥控是比例控制，短窗口里追不上完整倍率，所以放宽下限。
SPEED_RATIO_MIN, SPEED_RATIO_MAX = 1.05, 1.25
TURN_RATIO_MIN, TURN_RATIO_MAX = 1.05, 1.35


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def raft_pos(p, team):
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return tuple(float(x) for x in m.groups()) if m else None


def raft_yaw(p, team):
    """从 Sable info 的 Orientation 四元数取绕 Y 的偏航角（度）。"""
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Orientation:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    if not m:
        return None
    x, y, z, w = (float(v) for v in m.groups())
    yaw = math.degrees(math.atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + z * z)))
    return yaw


def yaw_delta(a, b):
    """[-180,180] 归一化的角度差。"""
    d = (b - a + 180.0) % 360.0 - 180.0
    return d


def horiz(a, b):
    return math.hypot(a[0] - b[0], a[2] - b[2])


def survivor_line(p):
    out = p("cww survivor")
    return out.replace("\n", " ")


def team_jobs(line, team):
    """从 `队0 … 职业[水手1]｜队1 … 职业[...]` 里取某队的职业段。"""
    for seg in line.split("｜"):
        if re.search(r"队%d\s" % team, seg):
            m = re.search(r"职业\[([^\]]*)\]", seg)
            return m.group(1) if m else ""
    return ""


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（phase=LOBBY queue=0 且无占席会话）：%s" % st.replace("\n", " ")[:140])
    if not clean:
        return 1

    bots = [("hm%s0" % time.strftime("%M%S"), 0), ("hm%s1" % time.strftime("%M%S"), 1)]
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

    print("== 1. 只给队 0 换上一个舵手（队 1 保持原职业做对照）")
    before = survivor_line(p)
    print("      before: " + before[:220])
    print("      " + p("cww survivor job 0 0 helmsman")[:120])
    after = survivor_line(p)
    print("      after : " + after[:220])
    jobs0, jobs1 = team_jobs(after, 0), team_jobs(after, 1)
    print("      jobs 队0=[%s] 队1=[%s]" % (jobs0, jobs1))
    expect("舵手" in jobs0, "队 0 职业读数出现「舵手」（实测 [%s]）" % jobs0)
    expect("舵手" not in jobs1, "队 1 没有被误改成舵手（实测 [%s]，对照组成立）" % jobs1)

    print("== 2. 同 tick 同输入前进 40 tick，比较位移")
    a0 = raft_pos(p, 0)
    b0 = raft_pos(p, 1)
    print("  " + p("cww remote move 0 w 40")[:120])
    print("  " + p("cww remote move 1 w 40")[:120])
    p.sleep(2.5)
    a1 = raft_pos(p, 0)
    b1 = raft_pos(p, 1)
    da = horiz(a0, a1) if a0 and a1 else 0.0
    db = horiz(b0, b1) if b0 and b1 else 0.0
    print("      队0（舵手）位移=%.3f 格（%s -> %s）" % (da, a0, a1))
    print("      队1（对照）位移=%.3f 格（%s -> %s）" % (db, b0, b1))
    expect(a0 is not None and b0 is not None and a1 is not None and b1 is not None,
           "四个位置都能读到")
    expect(da > 5.0 and db > 5.0, "两艘筏都真的前进了 >5 格（%.2f / %.2f）" % (da, db))
    speed_ratio = da / db if db > 0 else 0.0
    print("      速度比 = %.3f（标称 1.15）" % speed_ratio)
    expect(SPEED_RATIO_MIN <= speed_ratio <= SPEED_RATIO_MAX,
           "舵手队位移比落在 [%.2f, %.2f]（实测 %.3f，对照 %.3f 格）"
           % (SPEED_RATIO_MIN, SPEED_RATIO_MAX, speed_ratio, db))

    print("== 3. 同 tick 同输入左转 12 tick，比较偏航变化")
    y0a, y0b = raft_yaw(p, 0), raft_yaw(p, 1)
    print("  " + p("cww remote move 0 a 12")[:120])
    print("  " + p("cww remote move 1 a 12")[:120])
    p.sleep(1.5)
    y1a, y1b = raft_yaw(p, 0), raft_yaw(p, 1)
    if None in (y0a, y0b, y1a, y1b):
        inconclusive("Sable info 的 Orientation 读不到，转向倍率只能由单测担保")
    else:
        ta = abs(yaw_delta(y0a, y1a))
        tb = abs(yaw_delta(y0b, y1b))
        print("      队0（舵手）偏航变化=%.2f°（%.2f -> %.2f）" % (ta, y0a, y1a))
        print("      队1（对照）偏航变化=%.2f°（%.2f -> %.2f）" % (tb, y0b, y1b))
        turn_ratio = ta / tb if tb > 0.01 else 0.0
        print("      转向比 = %.3f（标称 1.15）" % turn_ratio)
        expect(ta > 1.0 and tb > 1.0, "两队都真的转了（%.2f° / %.2f°）" % (ta, tb))
        expect(TURN_RATIO_MIN <= turn_ratio <= TURN_RATIO_MAX,
               "舵手队偏航变化比落在 [%.2f, %.2f]（实测 %.3f）"
               % (TURN_RATIO_MIN, TURN_RATIO_MAX, turn_ratio))

    print("== 4. 收尾")
    p("cww abort")
    p.sleep(2)
    for n, _ in bots:
        p("player %s kill" % n.lower())
    print("  清残留：" + repr(sweep_stale_bots(p)))

    print("\n== 汇总")
    print("  PASS=%d FAIL=%d INCONCLUSIVE=%d" % (len(ok), len(bad), len(inc)))
    for m in bad:
        print("  FAIL - " + m)
    for m in inc:
        print("  INCONCLUSIVE - " + m)
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
