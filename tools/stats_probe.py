#!/usr/bin/env python3
"""战绩落盘探针（M5-3，2026-11-14）。

要回答：
  1. 真实武器命中会累计伤害（砍刀 9 HP），并触发流血。
  2. 玩家死亡后实体被移除，但 /cww stats 仍能显示名字（不是 UUID 前缀）。
  3. SETTLE 写出 <world>/cww/state/matches/<match>.json，字段与读数一致。

夹具：test_3min + flat_test；攻击者用 item replace 把砍刀塞进主手，
受害者用 /damage 100 ... by 攻击者 击杀（Carpet 的 attack once 打不出致命伤）。
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, ROOT, sweep_stale_bots, keep_alive, wait_clean_lobby, DRY_ARENA  # noqa: E402

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def stats_file(match):
    return os.path.join(ROOT, "run", "world", "cww", "state", "matches", "%s.json" % match)


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY：%s" % st.replace("\n", " ")[:120])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("sp%sa" % tag, 0), ("sp%sb" % tag, 1)]
    a, b = bots[0][0].lower(), bots[1][0].lower()
    for n, _ in bots:
        p("player %s kill" % n.lower())
        p("player %s spawn" % n.lower())
    for _ in range(30):
        listing = p("list").lower()
        if all(n.lower() in listing for n, _ in bots):
            break
        p.sleep(1)
    for n, t in bots:
        p("cww force %s %d" % (n.lower(), t))
    p("cww start test_3min %s" % DRY_ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        return 1
    keep_alive(p, [(n.lower(), t) for n, t in bots])

    raw = p("cww state")
    m = re.search(r"match=(\d+)", raw)
    match = int(m.group(1)) if m else -1
    print("      match=%d" % match)
    path = stats_file(match)
    if os.path.exists(path):
        os.remove(path)   # 只清本局这个文件；不碰别的局

    print("== 1. 真实武器命中：砍刀 9 HP + 流血")
    p("item replace entity %s weapon.mainhand with nbw:cutlass 1" % a)
    p("tp %s 1000.5 149 1000.5" % a)
    p("tp %s 1000.5 149 1002.5" % b)
    p.sleep(0.5)
    p("player %s look at 1000.5 150.5 1002.5" % a)
    p("player %s attack once" % a)
    p.sleep(1.5)
    surv = p("cww survival status %s" % b)
    print("      <%s>" % surv[:220])
    expect("流血=是" in surv, "砍刀命中触发流血（真实 onAttack 路径）")

    print("== 2. 击杀后 /cww stats 名字完整")
    p("damage %s 100 minecraft:player_attack by %s" % (b, a))
    p.sleep(1.5)
    stt = p("cww stats")
    print("      <%s>" % stt[:220])
    expect(a + "=1" in stt, "击杀者名字正确（%s）" % a)
    expect(b + "=1" in stt, "阵亡者名字正确（%s，不是 UUID 前缀）" % b)

    print("== 3. SETTLE 落盘")
    deadline = time.time() + 30
    while time.time() < deadline and not os.path.exists(path):
        p.sleep(1)
    expect(os.path.exists(path), "战绩文件已生成：%s" % path)
    if not os.path.exists(path):
        return 1
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    print("      %s" % json.dumps(data, ensure_ascii=False)[:500])
    expect(data.get("match") == match, "JSON match 与本局一致")
    expect(isinstance(data.get("winner"), str) and data["winner"], "JSON 带胜者")
    expect(isinstance(data.get("durationSec"), (int, float)), "JSON 带局时长")
    players = {row.get("name", "").lower(): row for row in data.get("players", [])}
    pa = players.get(a)
    pb = players.get(b)
    expect(pa is not None and pa.get("kills") == 1, "攻击者 kills=1")
    expect(pa is not None and pa.get("damage", 0) >= 5.5, "攻击者 damage>=5.5（实际 %s）"
           % (pa.get("damage") if pa else "?"))
    expect(pb is not None and pb.get("deaths") == 1, "被击杀者 deaths=1")
    expect(pb is not None and pb.get("name", "").lower() == b, "阵亡者名字已持久化")

    print("== 4. 收尾")
    if "PLAY" in p("cww phase"):
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
