#!/usr/bin/env python3
"""悬赏令探针（M5 事件收尾，2026-11-14）。

分两局跑，因为“击杀目标”会让一队全灭、提前 SETTLE：
  A. 到期路径：标记目标 → 等 120s → 状态自动清除、目标仍活着。
  B. 击杀路径：标记目标 → 清掉目标减伤 → 另一名玩家击杀 → 击杀者背包出现 Epic 武备。

夹具：test_3min + flat_test；两队各 1 名假玩家。
A 段给 resistance 255 + regeneration 255，否则缩圈会把两队打空；
B 段在击杀前 effect clear 目标，否则 /damage 100 会被完全减免（实测踩过）。
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


def parse_bounty(out):
    """从 /cww event 取 (target, remain)；没有悬赏返回 (None, -1)。"""
    m = re.search(r"悬赏=(\S+)\s+剩=(\d+)s", out)
    return (m.group(1), int(m.group(2))) if m else (None, -1)


def start_round(p, tag, prefix):
    """清场 → 装赛制 → 两名假玩家分队 → 开局到 PLAY。返回 (bots, err)。"""
    print("  清残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY：%s" % st.replace("\n", " ")[:100])
    if not clean:
        return None, "LOBBY 不干净"
    bots = [("%s%sa" % (prefix, tag), 0), ("%s%sb" % (prefix, tag), 1)]
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
        return None, "没进 PLAY"
    keep_alive(p, [(n.lower(), t) for n, t in bots])
    return bots, None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()
    tag = time.strftime("%M%S")

    print("== A. 到期路径（120s 未完成自动清除）")
    bots, err = start_round(p, tag + "a", "bo")
    if err:
        return 1
    for n, _ in bots:
        p("effect give %s minecraft:resistance infinite 255 true" % n.lower())
        p("effect give %s minecraft:regeneration infinite 255 true" % n.lower())
    p("cww event fire bounty")
    p.sleep(1)
    st = p("cww event")
    print("      <%s>" % st[:200])
    target, remain = parse_bounty(st)
    expect(target is not None, "悬赏令标记了目标")
    expect(remain >= 100, "悬赏窗口约 120s（剩 %ds）" % remain)
    if target:
        eff = p("data get entity %s active_effects" % target)
        expect("minecraft:glowing" in eff.lower(), "目标带 GLOWING 公开标记")
    expired_at = None
    deadline = time.time() + 180
    while time.time() < deadline:
        st = p("cww event")
        t, _ = parse_bounty(st)
        if t is None:
            expired_at = time.time()
            print("      到期后状态：<%s>" % st[:180])
            break
        p.sleep(1)
    expect(expired_at is not None, "120s 后悬赏状态自动清除")
    log = p.log()
    expect("悬赏令到期" in log and "未被击杀" in log, "日志出现悬赏到期公告")
    listing = p("list")
    online = listing.lower()
    expect(all(n.lower() in online for n, _ in bots), "到期路径没有误杀目标，双方仍在场")
    p("cww abort")
    p.sleep(2)

    print("== B. 击杀路径（另一名玩家领取 Epic 武备）")
    bots, err = start_round(p, tag + "b", "bk")
    if err:
        return 1
    for n, _ in bots:
        p("effect give %s minecraft:resistance infinite 255 true" % n.lower())
        p("effect give %s minecraft:regeneration infinite 255 true" % n.lower())
    p("cww event fire bounty")
    p.sleep(1)
    st = p("cww event")
    print("      <%s>" % st[:200])
    target, _ = parse_bounty(st)
    expect(target is not None, "第二次悬赏标记了目标")
    if not target:
        return 1
    names = [n.lower() for n, _ in bots]
    if target not in names:
        inconclusive("目标 %s 不在本次假玩家名单里（既视夹具异常）" % target)
        return 1
    killer = names[0] if target == names[1] else names[1]
    # 关键：目标身上的 resistance 255 会把 /damage 100 完全减免，必须先清掉。
    p("effect clear %s" % target)
    p.sleep(0.5)
    p("damage %s 100 minecraft:player_attack by %s" % (target, killer))
    p.sleep(1.5)
    log = p.log()
    m = re.search(r"悬赏完成：(\S+) 击杀 (\S+)，获得 Epic 武备 (\S+)", log)
    expect(m is not None, "日志出现悬赏完成与奖励")
    if m:
        got_killer, got_target, reward = m.group(1), m.group(2), m.group(3)
        print("      reward=%s" % reward)
        expect(got_killer == killer and got_target == target,
               "击杀者/目标与探针一致（%s / %s）" % (got_killer, got_target))
        inv = p("data get entity %s Inventory" % killer)
        expect("nbw:%s" % reward in inv, "击杀者背包里确实有 nbw:%s" % reward)
    st = p("cww event")
    t, _ = parse_bounty(st)
    expect(t is None, "击杀后悬赏状态清除")

    print("== 收尾")
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
