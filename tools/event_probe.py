#!/usr/bin/env python3
"""事件预告 + 幸存者聚集点探针（M5 事件硬化，2026-11-14）。

要回答：
  1. 事件到点先进入“预告”状态（/cww event 显示 预告=... 还剩=1..8s），不是立即触发。
  2. 预告日志在触发之前出现，且 8 秒预告只出现一次；触发后回到“下一发”。
  3. survivor_drift 按 02 §10 漂出 3–4 名幸存者。

夹具：test_3min（事件池只有 storm，间隔 60–90s，PLAY 硬上限 360s）。
假玩家给 resistance 255 + regeneration 255，避免缩圈伤害把两队打空、提前 SETTLE。
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


def parse_status(out):
    """从 /cww event 回包里取 (fired, pending_name, remain, next_fire)。"""
    fired_m = re.search(r"已触发=(\d+)", out)
    pending_m = re.search(r"预告=(\S+)\s+还剩=(\d+)s", out)
    next_m = re.search(r"下一发=(\d+)s", out)
    return (
        int(fired_m.group(1)) if fired_m else -1,
        pending_m.group(1) if pending_m else "",
        int(pending_m.group(2)) if pending_m else -1,
        int(next_m.group(1)) if next_m else -1,
    )


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（%s）：%s" % ("test_3min", st.replace("\n", " ")[:120]))
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("ev%s0" % tag, 0), ("ev%s1" % tag, 1)]
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
    p("cww start test_3min %s" % DRY_ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        return 1
    keep_alive(p, [(n.lower(), t) for n, t in bots])
    for n, _ in bots:
        p("effect give %s minecraft:resistance infinite 255 true" % n.lower())
        p("effect give %s minecraft:regeneration infinite 255 true" % n.lower())
    p.sleep(1)

    print("== 1. 开局：还没有预告")
    st0 = p("cww event")
    fired0, pend0, _, next0 = parse_status(st0)
    print("      <%s>" % st0[:220])
    expect(fired0 == 0 and pend0 == "", "开局处于“下一发”而不是预告（fired=%d pending=%r）" % (fired0, pend0))
    expect(0 <= next0 <= 95, "下一发在夹具间隔内（%ds）" % next0)

    print("== 2. 等预告出现（事件到点后应先预告 8s）")
    warning_at = None
    warning_remain = -1
    trigger_at = None
    deadline = time.time() + 120
    while time.time() < deadline:
        st = p("cww event")
        fired, pend, remain, _ = parse_status(st)
        if pend and warning_at is None:
            warning_at = time.time()
            warning_remain = remain
            print("      <%s>" % st[:220])
            expect(1 <= remain <= 8, "预告剩余秒数在 1–8s（实际 %ds）" % remain)
            expect(fired == 0, "预告阶段尚未触发（已触发=%d）" % fired)
        if fired >= 1:
            trigger_at = time.time()
            print("      <%s>" % st[:220])
            break
        p.sleep(1)

    expect(warning_at is not None, "看到过预告状态（预告=...）")
    expect(warning_remain >= 5, "预告窗口至少 5s（先看到 %ds）" % warning_remain)
    expect(trigger_at is not None, "预告结束后事件确实触发（已触发>=1）")
    if warning_at is not None and trigger_at is not None:
        gap = trigger_at - warning_at
        print("      预告到触发墙钟差 %.1fs（服务端预告窗 8s）" % gap)
        expect(5.0 <= gap <= 12.0, "预告与触发间隔在 5–12s（实际 %.1fs）" % gap)

    print("== 3. 触发后回到“下一发”，且日志顺序正确")
    st2 = p("cww event")
    fired2, pend2, _, next2 = parse_status(st2)
    print("      <%s>" % st2[:220])
    expect(fired2 >= 1 and pend2 == "", "触发后不再停在预告（fired=%d pending=%r）" % (fired2, pend2))
    expect(next2 >= 0, "触发后重新排了下一发（下一发=%ds）" % next2)

    log = p.log()
    warn_marker = "[预告] 8s 后：风暴"
    expect(warn_marker in log, "日志出现 8 秒预告（风暴）")
    expect("触发 storm" in log, "日志出现实际触发 storm")
    expect(log.count(warn_marker) == 1, "同一发事件只预告一次（实际 %d 次）" % log.count(warn_marker))
    warn_pos = log.find(warn_marker)
    fire_pos = log.find("触发 storm")
    expect(warn_pos >= 0 and fire_pos >= 0 and warn_pos < fire_pos, "日志顺序：预告在触发之前")

    print("== 4. survivor_drift 漂出 3–4 名（02 §10）")
    p("cww event fire survivor_drift")
    p.sleep(2)
    log2 = p.log()
    m = re.search(r"\[survivor\] 海面漂出 (\d+) 个幸存者", log2)
    n = int(m.group(1)) if m else -1
    print("      漂流幸存者数量 = %d" % n)
    expect(3 <= n <= 4, "幸存者聚集点人数 3–4（实际 %d）" % n)
    expect("幸存者聚集点：海面漂出" in log2, "日志出现幸存者聚集点公告")

    print("== 5. 收尾")
    p("cww abort")
    p.sleep(2)
    for n, _ in bots:
        p("player %s kill" % n.lower())
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
