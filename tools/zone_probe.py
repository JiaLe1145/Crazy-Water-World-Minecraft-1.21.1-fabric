#!/usr/bin/env python3
"""黑海圈层探针（M3-1）：验证圈会按时收缩、圈外人扣血、预告会广播。

用 flat_test（干地、半径 60、无沉筏干扰）：两队锚点都在距圈心 48 格处，
test_3min 的第一段 30s 把半径收到 0.6*60=36，于是两个锚点都变成「圈外」，
应当每秒掉血 —— 这样不用把假玩家挪来挪去。

用法：python tools/zone_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive  # noqa: E402

ok, bad = [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def health(p, name):
    out = p("data get entity %s Health" % name)
    m = re.search(r"entity data: ([\d.]+)f", out)
    return float(m.group(1)) if m else None


def pos_of(p, name):
    out = p("data get entity %s Pos" % name)
    m = re.search(r"\[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]", out)
    return tuple(float(g) for g in m.groups()) if m else None


def zone_radius(p):
    out = p("cww zone")
    m = re.search(r"半径=([\d.]+)", out)
    return float(m.group(1)) if m else None


def zone_damage(p):
    out = p("cww zone")
    m = re.search(r"圈外伤害=([\d.]+)", out)
    return float(m.group(1)) if m else None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()
    print("== 0. 开局")
    print("  清残留：" + repr(sweep_stale_bots(p)))
    for _ in range(25):
        if "LOBBY" in p("cww phase"):
            break
        p("cww abort")
        p.sleep(1.5)
    stamp = time.strftime("%M%S")
    bots = [("zn%s0" % stamp, 0), ("zn%s1" % stamp, 1)]
    for n, _ in bots:
        p("player %s kill" % n.lower())
        p("player %s spawn" % n.lower())
    for _ in range(30):
        if all(n.lower() in p("list").lower() for n, _ in bots):
            break
        p.sleep(1)
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, [(n.lower(), t) for n, t in bots])
    p("cww start test_3min flat_test")
    reached, cur = p.wait_phase("PLAY", 180)
    expect(reached, "进入 PLAY（%s）" % cur[:50])
    if not reached:
        return 1

    print("\n== 1. 开局：满半径、无圈外伤害")
    time.sleep(1)
    r0 = zone_radius(p)
    d0 = zone_damage(p)
    print("      zone：半径=%s 伤害=%s" % (r0, d0))
    expect(r0 is not None and r0 >= 59.0, "开局半径应为 60（实测 %s）" % r0)
    expect(d0 == 0.0, "开局圈外伤害应为 0（实测 %s）" % d0)

    print("\n== 2. 30s 后第一段收缩：半径变小、伤害 > 0")
    # test_3min: at=30, radiusScale=0.60 -> 36; over=30s。要等它收稳（<40）再测伤害
    t0 = time.time()
    while time.time() - t0 < 75:
        r = zone_radius(p)
        d = zone_damage(p)
        if r is not None and r <= 40.0:
            break
        time.sleep(2)
    r1 = zone_radius(p)
    d1 = zone_damage(p)
    print("      收缩后：半径=%s 伤害=%s" % (r1, d1))
    expect(r1 is not None and r1 < 45.0, "第一段收缩后半径应明显下降（实测 %s）" % r1)
    expect(d1 is not None and d1 > 0.0, "第一段收缩后圈外伤害应 > 0（实测 %s）" % d1)

    print("\n== 3. 圈外玩家扣血（锚点距心 48 > 收稳后半径 36）")
    for n, _ in bots:
        pos = pos_of(p, n.lower())
        print("      %s 位置：%s" % (n.lower(), pos))
    h0 = {n.lower(): health(p, n.lower()) for n, _ in bots}
    print("      收缩前血量：%s" % h0)
    time.sleep(8)
    h1 = {n.lower(): health(p, n.lower()) for n, _ in bots}
    print("      8s 后血量：%s" % h1)
    for n, _ in bots:
        key = n.lower()
        if h0[key] is not None and h1[key] is not None:
            expect(h1[key] < h0[key], "%s 在圈外应掉血（%.1f -> %.1f）" % (key, h0[key], h1[key]))

    print("\n== 4. 预告广播（日志里有「黑海将在」）")
    # 读不到聊天就用日志兜底：探针环境里更可靠
    log = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "run", "server-console.log")
    try:
        with open(log, encoding="utf-8", errors="ignore") as fh:
            text = fh.read()
        expect("黑海将在" in text or "黑海收缩" in text, "日志里出现过黑海预告/收缩公告")
    except OSError as e:
        print("      （跳过日志检查：%s）" % e)

    for n, _ in bots:
        p("player %s kill" % n.lower())
    p("cww abort")

    print("\n=== ZONE PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
