#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把全部自定义物品挂到展示墙上（item_frame），供客户端截图目视验收。

为什么用 item frame 而不是开背包：本机的键盘/鼠标注入（SendInput / keybd_event /
PostMessage）在这台机器上到不了 Minecraft 客户端，按 E 打不开背包；而 item frame 是
纯服务端命令能摆出来的、还能把物品图标以方块尺度画在墙上——一张截图就能看全 28 个图标。

用法（服务端已起、RCON 在 25576）：
    .\\run\\py-embed\\python.exe -I -X utf8 tools/frame_showcase.py
    .\\run\\py-embed\\python.exe -I -X utf8 tools/frame_showcase.py --clear
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

# 顺序 = 展示墙上的顺序（14 列 × 2 行）：先 12 个方块，再 16 个物品
ITEMS = [
    "raft_core", "sail", "oar", "seawater_filter", "medical_kit", "compost_farm",
    "kitchen", "generator", "repair_table", "ballista", "catapult", "ram",
    "remote", "rope", "grapnel", "fresh_water", "bandage", "water_pistol",
    "hand_crossbow", "harpoon_gun", "musket", "javelin", "paddle", "cutlass",
    "pike", "warhammer", "twin_blade", "ballista_bolt",
]

# 墙：z=-9 是石头背景，画框挂在 z=-8；玩家站在 z=0 看北（yaw 180）
WALL_X0, WALL_X1 = -8, 7
WALL_Y0, WALL_Y1 = 199, 204
FRAME_Z = -8
COLS = 14


def main():
    clear = "--clear" in sys.argv
    sock = rcon.connect()

    def run(cmd):
        return rcon.run(sock, cmd)

    run("kill @e[type=minecraft:item_frame]")
    if clear:
        run("fill %d %d %d %d %d %d minecraft:air" % (WALL_X0, WALL_Y0, FRAME_Z, WALL_X1, WALL_Y1, FRAME_Z))
        print("已清空展示墙")
        return 0

    run("kill @e[type=minecraft:item_frame]")
    run("fill %d %d -9 %d %d -9 minecraft:stone_bricks" % (WALL_X0, WALL_Y0, WALL_X1, WALL_Y1))
    run("fill %d %d -8 %d %d -8 minecraft:air" % (WALL_X0, WALL_Y0, WALL_X1, WALL_Y1))

    for i, item in enumerate(ITEMS):
        col = i % COLS
        row = i // COLS
        x = WALL_X0 + 1 + col
        y = WALL_Y0 + 2 + row
        cmd = ('summon minecraft:item_frame %d %d %d '
               '{Facing:3b,Item:{id:"nbw:%s",count:1}}' % (x, y, FRAME_Z, item))
        out = run(cmd)
        if "Summoned" not in out:
            print("  失败 %-16s %s" % (item, out[:120]))
    got = run("execute if entity @e[type=minecraft:item_frame] run data get entity "
              "@e[type=minecraft:item_frame,limit=1,sort=nearest] Item.id")
    print("画框总数：%s" % run("execute store result score #n nbw_x run "
                              "execute if entity @e[type=minecraft:item_frame]"))
    print("抽查一个：%s" % got[:120])

    # 站到能一眼看全墙的位置
    print(run("tp @a 0 201.0 1 180 0"))
    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
