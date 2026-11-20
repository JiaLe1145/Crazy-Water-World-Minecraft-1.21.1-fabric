#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M6 · 测试世界地形修复（2026-11-21）。

背景（真问题，不是探针断言错）：
  `wind_probe` / `sail_probe` 在 flat_test 还是"旱地基岩平台"的年代，会自己在两个出生
  锚点处 `fill 149..153 air` 再 `fill 149..152 water`，并砌一圈石墙，给筏子造一个能浮
  的小水池。2026-11-21 worldbuild 已按 arena 配置把 flat_test 建成正经水池
  （海床 139、水面 149），旧夹具却仍然把锚点水位抬到 152 并留石墙：

    - 玩家被传送到配置的 y=149 → 落在水面下 3 格，掉进水里；
    - raftgen_probe 因此报 "g0..g3 没站在筏面高度（Y=143.71）"（真 bug 现象，其实是世界残留）；
    - `cww arena terrain flat_test` 也会如实报 2 列水位不符（C1 就是抓这个的）。

本脚本把两个锚点 ±12 的补丁复位成配置地形：空气 130..160 → 石底 130..139 → 水 140..149，
然后用 `cww arena terrain flat_test` 复核"问题 0 列"。

跑法（服务端已起、RCON 在 25576）：
    .\\run\\py-embed\\python.exe -I -X utf8 tools/m6_world_repair.py
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARENA_JSON = os.path.join(ROOT, "src", "main", "resources", "data", "nbw", "arena", "flat_test.json")
PATCH_RADIUS = 12


def load_anchors():
    with open(ARENA_JSON, encoding="utf-8") as fh:
        arena = json.load(fh)
    anchors = []
    for t in (arena.get("spawns") or {}).get("teams") or []:
        anchors.append((t["x"], t["z"]))
    sea = arena["sea"]
    return anchors, sea


def main():
    sock = rcon.connect()
    anchors, sea = load_anchors()
    water = sea["waterLevel"]
    floor_top = water - 10          # 与 worldbuild.build_flat_test 一致：水面 149 → 海床顶 139
    bottom = floor_top - 9          # 130
    clear_top = water + 11          # 160
    print("锚点：%s" % anchors)
    print("复位地形：空气 %d..%d → 石底 %d..%d → 水 %d..%d（水面 y=%d）"
          % (bottom, clear_top, bottom, floor_top, floor_top + 1, water, water))
    failed = []
    for cx, cz in anchors:
        x0, x1 = cx - PATCH_RADIUS, cx + PATCH_RADIUS
        z0, z1 = cz - PATCH_RADIUS, cz + PATCH_RADIUS
        for label, y0, y1, block in (
                ("清空", bottom, clear_top, "minecraft:air"),
                ("石底", bottom, floor_top, "minecraft:stone"),
                ("水体", floor_top + 1, water, "minecraft:water")):
            out = rcon.run(sock, "fill %d %d %d %d %d %d %s" % (x0, y0, z0, x1, y1, z1, block)) or ""
            print("  [%s] %s → %s" % (label, (cx, cz), out[:90]))
            if "Successfully" not in out and "成功" not in out and "No blocks" not in out:
                failed.append((label, cx, cz, out[:120]))

    out = rcon.run(sock, "cww arena terrain flat_test") or ""
    flat = out.replace("\n", " ")
    print("\n[cww arena terrain flat_test] " + flat[:420])
    if "问题 0 列" in flat:
        print("\nRESULT: PASS（地形已复位，3 个采样列全过）")
        return 0
    print("\nRESULT: FAIL（地形仍有问题，见上）")
    for f in failed:
        print("  fill 失败：%s" % (f,))
    return 1


if __name__ == "__main__":
    sys.exit(main())
