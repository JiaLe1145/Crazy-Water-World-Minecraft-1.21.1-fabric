#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""NB 水世界 · 测试世界构建器

用途：把 `run/world` 从零生成之后，补齐"模组不负责、必须有人建"的那部分：
  1. 大厅（lobby.json 的 spawn/bounds 指向一片天空平台，模组只负责把人传送过去，不负责造）
  2. 中心残骸模板（reset/buildtemplate 会建，但也可以在这里一起跑）
  3. 两张海域（由随包 mcfunction 建造，这里只是调用 + 等完成）

用法：
    python tools/worldbuild.py lobby      # 只建大厅
    python tools/worldbuild.py arena      # 建两张海域地形（调 mcfunction）
    python tools/worldbuild.py template   # 建中心残骸模板
    python tools/worldbuild.py all        # 全做

前置：服务端已启动，RCON 可用（run/server.properties 里的 rcon.port / rcon.password）。
"""
import sys
import time
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))

import rcon


def build_lobby(run, log=print):
    """按 data/nbw/lobby.json 的坐标造一座天空大厅。

    坐标（与 lobby.json 一致）：spawn (0,201,0)、bounds x/z -24..24、y 195..225。
    地面放 y=200（玩家站在 201），三层石砖外墙保证不会掉出平台。
    """
    log("== 建大厅 ==")
    # 预热区块，否则 fill 会静默失败
    run("forceload add -40 -40 40 40")
    time.sleep(0.5)

    FLOOR = "minecraft:smooth_stone"
    INNER = "minecraft:oak_planks"
    DAIS = "minecraft:polished_andesite"
    WALL = "minecraft:stone_bricks"
    GLASS = "minecraft:glass"
    LAMP = "minecraft:sea_lantern"
    DARK = "minecraft:dark_oak_planks"

    # 底层（两层，避免视觉上是一张纸）
    run(f"fill -24 199 -24 24 199 24 {FLOOR}")
    run(f"fill -24 200 -24 24 200 24 {FLOOR}")
    # 内圈木地板
    run(f"fill -20 200 -20 20 200 20 {INNER}")
    # 中央石台（出生点）
    run(f"fill -5 200 -5 5 200 5 {DAIS}")
    # 外墙：三层，中间一层换玻璃做窗
    run(f"fill -24 201 -24 24 201 24 {WALL}")
    run(f"fill -23 201 -23 23 201 23 minecraft:air")
    run(f"fill -24 202 -24 24 202 24 {WALL}")
    run(f"fill -23 202 -23 23 202 23 minecraft:air")
    run(f"fill -24 202 -24 24 202 -24 {GLASS}")
    run(f"fill -24 202 -24 -24 202 24 {GLASS}")
    run(f"fill 24 202 -24 24 202 24 {GLASS}")
    run(f"fill -24 202 24 24 202 24 {GLASS}")
    run(f"fill -24 203 -24 24 203 24 {WALL}")
    run(f"fill -23 203 -23 23 203 23 minecraft:air")

    # 四角塔：5x5，高到 212，顶端海晶灯
    for sx in (-1, 1):
        for sz in (-1, 1):
            x = 22 * sx
            z = 22 * sz
            run(f"fill {x-2} 201 {z-2} {x+2} 211 {z+2} {WALL}")
            run(f"fill {x-1} 204 {z-1} {x+1} 210 {z+1} minecraft:air")
            run(f"setblock {x} 212 {z} {LAMP}")

    # 出生点四角灯
    for dx, dz in ((-3, -3), (3, -3), (-3, 3), (3, 3)):
        run(f"setblock {dx} 201 {dz} {LAMP}")

    # 报名 NPC 台（lobby.json enrollNpc = 0,201,3）
    run(f"fill -2 200 1 2 200 5 {DARK}")
    # 两队旗座（lobby.json teamFlags = -6,201,6 与 6,201,6）
    for cx in (-6, 6):
        run(f"fill {cx-1} 200 5 {cx+1} 200 7 {WALL}")
        run(f"setblock {cx} 201 6 minecraft:white_wool")

    log("  大厅完成")


def build_template(run, log=print):
    log("== 建中心残骸模板 ==")
    log("  " + run("cww reset buildtemplate")[:120])


def wait_queues(run, keys, log=print, timeout=300):
    start = time.time()
    while time.time() - start < timeout:
        busy = []
        for k in keys:
            r = run(f"data get storage nbw:{k} rows")
            if "has no" not in r and "没有" not in r and "[]" not in r:
                busy.append(k)
        if not busy:
            return True
        time.sleep(3)
    return False


def build_arenas(run, log=print):
    log("== 建两张海域地形 ==")
    for aid, key in (("test_sea", "sea_test_sea"), ("default_sea", "sea_default_sea")):
        log("  " + run(f"function nbw:arena/{aid}/build")[:120])
    log("  等待建造队列…")
    ok = wait_queues(run, ["sea_test_sea", "sea_default_sea"], log)
    log("  完成" if ok else "  超时（可能仍在跑，用 cww arena terrain 复查）")


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    sock = rcon.connect()
    def run(cmd):
        return rcon.run(sock, cmd)

    if what in ("lobby", "all"):
        build_lobby(run)
    if what in ("template", "all"):
        build_template(run)
    if what in ("arena", "all"):
        build_arenas(run)
    print("\n完成。复查：cww lobby test / cww arena terrain default_sea / cww arena terrain test_sea")


if __name__ == "__main__":
    main()
