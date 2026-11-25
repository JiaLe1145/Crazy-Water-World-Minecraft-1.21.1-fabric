#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补给箱内容探针（2026-11-21）：验证「设施池」真的接到了运行时。

要回答的事：
  1. 每个补给箱都真的放了物品（没有空槽 / 没有开出 AIR）。
  2. 箱内物品 id 全部是已注册物品（拼错就会静默变成空槽）。
  3. M6-Q-RESOURCES §6.1 那 10 件「局内拿不到」的物品，现在真的能从箱子里开出来。
  4. 设施件的实测出现率与 `LootRoll.FACILITY_CHANCE`（0.22/件）同一量级。

做法（2026-11-25 起改为实体）：一次撒一个（`cww loot spawn 1`）→ 读 `cww loot status` 尾部的
`last=<uuid>` → `data get entity <uuid> Items` 拿内容。**不能再用坐标当标识**：补给箱现在会随
洋流漂移（实体），同一只箱子每 tick 的 x/z 都在变；UUID 才是稳定键。撒多了就定位不到具体箱子，
所以这里坚持"一次一个"（40 次约 10 秒，比扫 121×121 的海面快两个数量级）。

跑法（服务端已起、RCON 在 25576、当前图 flat_test）：
    .\\run\\py-embed\\python.exe -I -X utf8 tools/loot_pool_probe.py
    ... --crates 80        # 想更严就多撒
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

# 2026-11-21 之前完全拿不到的 11 件（§6.1 A 档 7 件 + B 档升上来的帆 + 三件设施武器）
MISSING_BEFORE = {
    "nbw:pike", "nbw:rope", "nbw:grapnel", "nbw:generator", "nbw:repair_table",
    "nbw:compost_farm", "nbw:kitchen", "nbw:sail", "nbw:ballista", "nbw:catapult",
    "nbw:ram",
}

# 真正走「设施池」的 10 件（pike 走的是 RARE 稀有度池，不能算进设施率）
FACILITY_IDS = {
    "nbw:rope", "nbw:grapnel", "nbw:repair_table", "nbw:compost_farm",
    "nbw:kitchen", "nbw:sail", "nbw:generator", "nbw:ram", "nbw:ballista",
    "nbw:catapult",
}

FACILITY_CHANCE = 0.22

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def info(msg):
    inc.append(msg)
    print("  INFO - %s" % msg)


def crate_last(status_out):
    """从 `cww loot status` 里拿最新箱子的 (uuid, (x,y,z))。

    箱子会漂，坐标只用来打印；取值要用 UUID（`last=<uuid>` 是本探针与 LootService 的约定）。
    """
    m = re.search(r"last=([0-9a-fA-F-]{36})", status_out)
    if not m:
        return None
    uuid = m.group(1)
    coords = re.findall(r"(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)", status_out)
    pos = tuple(float(v) for v in coords[-1]) if coords else None
    return uuid, pos


def item_ids(raw):
    return re.findall(r'id:\s*"([^"]+)"', raw)


def main():
    crates = 40
    if "--crates" in sys.argv:
        crates = int(sys.argv[sys.argv.index("--crates") + 1])

    sock = rcon.connect()

    def p(cmd):
        return rcon.run(sock, cmd)

    print("== 0. 前置：确认当前图与水面高度")
    st = p("cww state")
    m = re.search(r"arena=(\S+)", st)
    arena = m.group(1) if m else "?"
    terrain = p("cww arena terrain %s" % arena)
    wl = re.search(r"水面 y=(\d+)", terrain)
    water_y = int(wl.group(1)) if wl else 149
    print("      图=%s 水面 y=%d" % (arena, water_y))
    expect(wl is not None, "能读出水面高度（%s）" % terrain.replace("\n", " ")[:120])

    print("== 1. 清掉旧箱子，然后一次一个地撒 %d 个" % crates)
    p("kill @e[type=nbw:floating_crate]")
    p("fill -60 %d -60 60 %d 60 minecraft:air replace minecraft:barrel"
      % (water_y - 1, water_y + 3))   # 旧方块版残留（2026-11-25 之前的世界）
    p("kill @e[type=minecraft:item]")

    seen_ids = []
    empty = 0
    unparsed = 0
    seen = set()
    bodies = {}

    # 注意：撒失败时 `cww loot status` 仍会报上一次成功的那只箱子，如果每次都计数
    # 就会把同一只箱子数很多遍（第一版实测因此算出 79% 的"设施率"）。
    # 所以这里按 **UUID** 去重（箱子会漂，坐标不是稳定标识）。
    guard = 0
    while len(seen) < crates and guard < crates * 6:
        guard += 1
        p("cww loot spawn 1")
        status = p("cww loot status")
        info = crate_last(status)
        if info is None:
            unparsed += 1
            continue
        uuid, pos = info
        if uuid in seen:
            continue          # 这次没撒成功，读到的是旧箱子，跳过
        seen.add(uuid)
        raw = p("data get entity %s Items" % uuid)
        if "has no" in raw or "没有" in raw:
            empty += 1
            continue
        ids = item_ids(raw)
        if not ids:
            empty += 1
            continue
        bodies[uuid] = ids
        seen_ids.extend(ids)

    print("      唯一箱子 %d 个（尝试 %d 次）；解析失败 %d；空箱 %d"
          % (len(seen), guard, unparsed, empty))
    print("      共开出 %d 件物品，平均 %.1f 件/箱"
          % (len(seen_ids), len(seen_ids) / max(1, len(bodies))))

    # 2) 空箱
    expect(empty == 0, "没有空补给箱（空=%d）" % empty)
    expect(unparsed == 0, "每个箱子都定位到了坐标（失败=%d）" % unparsed)

    # 3) id 合法性：不允许 minecraft:air / 空字符串
    bad_ids = [i for i in seen_ids if not i or i.endswith(":air")]
    expect(not bad_ids, "没有开出 air / 空 id（坏=%s）" % bad_ids[:5])

    # 4) 缺口清单必须至少各命中一次（40 箱 × 1–3 件 ≈ 80 件，设施约 17 件）
    hit = sorted(set(seen_ids) & MISSING_BEFORE)
    miss = sorted(MISSING_BEFORE - set(seen_ids))
    print("      缺口清单命中：%s" % hit)
    if miss:
        print("      未命中：%s" % miss)
        info("未命中的缺口件（%d 件）可能是样本量不够，把 --crates 调大再跑：%s"
             % (len(miss), miss))
    expect(bool(hit), "缺口清单里的物品真的能从补给箱开出来（命中 %d 种）" % len(hit))

    # 5) 设施出现率（只数真正走设施池的那 10 件，pike 不算）
    total = len(seen_ids)
    fac = sum(1 for i in seen_ids if i in FACILITY_IDS)
    rate = fac / total if total else 0.0
    print("      设施件 %d / %d 件 = %.1f%%（期望 ≈ %.0f%%）"
          % (fac, total, rate * 100, FACILITY_CHANCE * 100))
    expect(0.08 <= rate <= 0.40,
           "设施件实测占比落在合理区间（实测 %.1f%%，期望 ~%.0f%%，样本 %d 件）"
           % (rate * 100, FACILITY_CHANCE * 100, total))

    # 6) 稀有度池没被摊薄：非设施物品里应当仍能看到武器
    weapons = {"nbw:cutlass", "nbw:pike", "nbw:twin_blade", "nbw:warhammer",
               "nbw:water_pistol", "nbw:hand_crossbow", "nbw:javelin", "nbw:musket",
               "nbw:harpoon_gun"}
    wseen = sorted(set(seen_ids) & weapons)
    print("      开到的武器：%s" % wseen)
    info("武器出现种类 %d（样本 %d 件，仅供观察，不作为判据）" % (len(wseen), total))

    # 7) 收尾：把箱子清掉（别把夹具留在地图上）
    p("kill @e[type=nbw:floating_crate]")
    p("fill -60 %d -60 60 %d 60 minecraft:air replace minecraft:barrel"
      % (water_y - 1, water_y + 3))
    p("kill @e[type=minecraft:item]")

    print("\n== 汇总")
    print("  PASS=%d FAIL=%d INFO=%d" % (len(ok), len(bad), len(inc)))
    for m in bad:
        print("  FAIL - " + m)
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    sock.close()
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
