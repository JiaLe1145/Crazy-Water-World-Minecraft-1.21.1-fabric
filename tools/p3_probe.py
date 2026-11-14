#!/usr/bin/env python3
"""P3 探针：队伍物资箱 + 扩建扣费 + 失温（2026-11-14）。

要回答三件事：
  1. 每队初始筏角落都有物资箱：1 木桨 + 8 木板 + 2 淡水（`/cww supply` 现读）。
  2. 扩建扣费：16 木板 / 4 线 → 允许并扣 3 木板 + 1 线；无线时拒绝且不预扣
     （`/cww build charge` 是 dev 夹具，复用真实扣费函数，不真正放方块）。
  3. 失温：水里连续 11 秒 → `失温=是`；旱地 4 秒 → `失温=否`。

夹具：flat_test；假玩家保持 water_breathing。本探针按仓库惯例用 RCON，
本机若无 Python 时可用等价的 Node 脚本跑同一组命令（见 M5-RESULTS §5.12）。
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


def slot_count(p, name, slot):
    out = p("data get entity %s Inventory[{Slot:%db}].count" % (name.lower(), slot))
    m = re.search(r"entity data:\s*(\d+)", out)
    return int(m.group(1)) if m else None


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（%s）" % st.replace("\n", " ")[:140])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("hp%s0" % tag, 0), ("hp%s1" % tag, 1)]
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

    print("== 1. 队伍物资箱（每队 1 木桨 + 8 木板 + 2 淡水）")
    supply = p("cww supply")
    print("      " + supply.replace("\n", " ")[:220])
    expect("paddle×1" in supply, "物资箱有 1 把木桨")
    expect("oak_planks×8" in supply, "物资箱有 8 木板")
    expect("fresh_water×2" in supply, "物资箱有 2 淡水")
    expect(supply.count("物资箱[") >= 2, "两队各有一个物资箱读数")

    wet = bots[1][0].lower()
    print("== 2. 失温：水里 11 秒 → 是；旱地 4 秒 → 否")
    s0 = p("cww survival status %s" % wet)
    print("      " + s0.replace("\n", " ")[:180])
    expect("失温=否" in s0, "开局不在失温状态")
    p.sleep(11)
    s1 = p("cww survival status %s" % wet)
    print("      " + s1.replace("\n", " ")[:180])
    expect("失温=是" in s1, "连续湿身 11 秒后失温")
    p("tp %s 1080 149 1000" % wet)
    p.sleep(4)
    s2 = p("cww survival status %s" % wet)
    print("      " + s2.replace("\n", " ")[:180])
    expect("失温=否" in s2, "离开水/雨 3 秒后解除")

    bot = bots[0][0].lower()
    print("== 3. 扩建扣费：16 木板 / 4 线 → 扣 3 木板 + 1 线；无线时拒绝")
    p("gamemode survival %s" % bot)
    p("item replace entity %s hotbar.0 with minecraft:oak_planks 16" % bot)
    p("item replace entity %s hotbar.1 with minecraft:string 4" % bot)
    p0, s0 = slot_count(p, bot, 0), slot_count(p, bot, 1)
    print("      before planks=%s string=%s" % (p0, s0))
    expect(p0 == 16 and s0 == 4, "备料读数正确")
    out = p("cww build charge %s 0 2 0 0 minecraft:oak_planks" % bot)
    p1, s1 = slot_count(p, bot, 0), slot_count(p, bot, 1)
    print("      after  planks=%s string=%s 回包=%s" % (p1, s1, out[:120]))
    expect("允许" in out, "材料够：允许")
    expect(p1 == 13 and s1 == 3, "扣 3 木板 + 1 线（probe 不真正放方块，放下那块不扣）")

    p("clear %s minecraft:string" % bot)
    out2 = p("cww build charge %s 0 2 0 1 minecraft:oak_planks" % bot)
    p2 = slot_count(p, bot, 0)
    print("      无线重试回包=%s planks=%s" % (out2[:140], p2))
    expect("拒绝" in out2 and "1 线" in out2, "材料不足：拒绝并说明缺线")
    expect(p2 == 13, "被拒时木板保持 13（不预扣）")

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
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
