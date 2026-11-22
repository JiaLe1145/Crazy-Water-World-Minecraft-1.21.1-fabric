#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重画武器/工具/消耗品的 16×16 物品图标。

背景：2026-11-22 实机发现 `paddle / hand_crossbow / harpoon_gun / musket /
javelin` 以及 `rope / grapnel / bandage / fresh_water` 的 PNG 是纯色占位图
（80 字节、8 色、整块不透明），资源扫描只能证明“文件存在且 PNG 合法”，
看不出“画的是一个色块”。本脚本用标准库直接写合法 RGBA PNG。

用法：
    .\\run\\py-embed\\python.exe -I -X utf8 tools/gen_weapon_icons.py
"""

import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "nbw", "textures", "item")

# 调色板：与现有 cutlass/warhammer 的配色保持一致
PALETTE = {
    ".": (0, 0, 0, 0),
    "d": (20, 20, 24, 255),      # 描边
    "W": (92, 70, 48, 255),      # 深木
    "w": (139, 107, 71, 255),    # 木
    "h": (168, 129, 84, 255),    # 亮木
    "M": (74, 74, 82, 255),      # 深铁
    "m": (138, 138, 147, 255),   # 铁
    "l": (192, 192, 200, 255),   # 亮铁
    "R": (139, 107, 71, 255),    # 深绳
    "r": (196, 166, 117, 255),   # 绳
    "x": (192, 57, 43, 255),     # 红
    "n": (232, 232, 232, 255),   # 白
    "N": (189, 189, 189, 255),   # 灰白
    "b": (74, 155, 232, 255),    # 水蓝
    "c": (159, 216, 245, 255),   # 亮水蓝
}

ICONS = {
    "paddle": [
        "................",
        "..........ddd...",
        ".........dwwwd..",
        "........dwwwwd..",
        ".......dwwwwd...",
        "......dwwwwd....",
        ".....dwwwwd.....",
        "....dwwwwd......",
        "...dWWwwd.......",
        "..dWWwwd........",
        ".dWWwwd.........",
        "dWWwwd..........",
        "dWwwd...........",
        ".dWd............",
        "..d.............",
        "................",
    ],
    "javelin": [
        "............dd..",
        "...........dmmd.",
        "..........dmlmd.",
        ".........dmlmd..",
        "........dmlmd...",
        ".......dmlmd....",
        "......dmlmd.....",
        ".....dmlmd......",
        "....dmlmd.......",
        "...dWmd.........",
        "..dWwd..........",
        ".dxwxd..........",
        "dxxwd...........",
        ".dd.............",
        "................",
        "................",
    ],
    "musket": [
        "...........dd...",
        "..........dmld..",
        ".........dmld...",
        "........dmld....",
        ".......dmld.....",
        "......dmld......",
        ".....dmld.......",
        "....dmld........",
        "...dmwd.........",
        "..dWwwd.........",
        ".dWwwd..........",
        "dWwwd...........",
        "dWwd............",
        ".dd.............",
        "................",
        "................",
    ],
    "harpoon_gun": [
        "..........dd....",
        ".........dmld...",
        "........dmld....",
        ".......dmld.....",
        "......dmld......",
        ".....dmld.......",
        "....dmwd........",
        "...dWwwd........",
        "..dWwwd.........",
        ".dWwwd..........",
        "dWwwd...........",
        "dWwd............",
        ".dd.............",
        "...rr...........",
        "..rRr...........",
        "................",
    ],
    "hand_crossbow": [
        "................",
        "..........ddd...",
        ".........dmmmd..",
        "........dmd.dmd.",
        ".......dmd.r.dmd",
        "......dmd..r..dm",
        ".....dmd...r....",
        "....dmd....r....",
        "...dmd....r.....",
        "..dmd...r.......",
        ".dmd..r.........",
        "dmd.r...........",
        "dmr.............",
        "dr..............",
        "d...............",
        "................",
    ],
    "rope": [
        "................",
        "....dddddd......",
        "...drrrrrrd.....",
        "..drrrrrrrrd....",
        "..drrddddrrd....",
        "..drrd..drrd....",
        "..drrd..drrd....",
        "..drrd..drrd....",
        "..drrd..drrd....",
        "..drrddddrrd....",
        "..drrrrrrrrd....",
        "...drrrrrrd.....",
        "....dddddd......",
        "........rr......",
        ".......rRr......",
        "................",
    ],
    "grapnel": [
        "................",
        "...d...d...d....",
        "..dmd.dmd.dmd...",
        "...dmd.dmd.dmd..",
        "....dmd.dmd.dm..",
        ".....dmd.dmd....",
        "......dmd.d.....",
        ".......dmd......",
        "........dm......",
        ".......drd......",
        "......drrd......",
        ".....drrd.......",
        "....drrd........",
        "...drrd.........",
        "..dd............",
        "................",
    ],
    "bandage": [
        "................",
        "....dddddd......",
        "...dnnnnnd......",
        "..dnnnnnnnd.....",
        "..dnnxxxnnnd....",
        "..dnnxxxnnnd....",
        "..dnnxxxnnnd....",
        "..dnnnnnnnd.....",
        "..dnnnnnnnd.....",
        "..dNNNNNNNd.....",
        "...dNNNNNd......",
        "....ddddd.......",
        "................",
        "................",
        "................",
        "................",
    ],
    "fresh_water": [
        "................",
        "......dddd......",
        "......dbbd......",
        ".....dbbbbd.....",
        "....dbbbbbbd....",
        "....dbbccbbd....",
        "...dbbccccbbd...",
        "...dbbccccbbd...",
        "...dbbbccbbbd...",
        "...dbbbbbbbbd...",
        "...dbbbbbbbbd...",
        "....dbbbbbbd....",
        ".....dbbbbd.....",
        "......dddd......",
        "................",
        "................",
    ],
}


def write_png(path, rows):
    if len(rows) != 16 or any(len(r) != 16 for r in rows):
        raise SystemExit("图标必须是 16×16：%s" % path)
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter: None
        for ch in row:
            if ch not in PALETTE:
                raise SystemExit("未知调色板字符 %r：%s" % (ch, path))
            raw.extend(PALETTE[ch])
    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    for name, rows in ICONS.items():
        path = os.path.join(OUT, name + ".png")
        write_png(path, rows)
        print("写入 %s（%d 字节）" % (path, os.path.getsize(path)))
    print("完成：%d 张图标" % len(ICONS))


if __name__ == "__main__":
    sys.exit(main())
