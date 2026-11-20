#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成/修复 `assets/nbw/textures/item/water_pistol.png`。

2026-11-21 全量测试发现：这张 16x16 RGBA 图是**截断的**（82 字节，解压后像素数据只有
944 字节，而 16x16 RGBA 需要 1040），客户端 `NativeImage.read` 直接抛异常，
日志里是 `Using missing texture, unable to load nbw:item/water_pistol`，
游戏里显示成紫黑格。文件"存在"所以 `check_resources.py` 当初放过了它 ——
所以顺便给检查脚本加了 PNG 结构校验（见 check_png）。

这里用标准库直接写一张合法的 16x16 像素画占位图（水枪轮廓：枪管 + 扳机护圈 + 握把 + 水滴）。
美术正式稿到位后直接覆盖同名文件即可。
"""
import os
import struct
import sys
import zlib

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "src", "main", "resources", "assets", "nbw", "textures", "item",
                   "water_pistol.png")

# 调色板
PALETTE = {
    ".": (0, 0, 0, 0),          # 透明
    "d": (20, 20, 24, 255),     # 描边（与 cutlass/warhammer 的描边同色）
    "b": (74, 155, 232, 255),   # 主体蓝（与 fresh_water 同色系）
    "c": (150, 215, 245, 255),  # 高光
    "g": (110, 110, 120, 255),  # 金属灰
    "w": (235, 245, 255, 255),  # 水滴
}

ART = [
    "................",   # 0
    "................",   # 1
    "....ddddddddd...",   # 2  枪管顶
    "....dbbbbbbbd.gg",   # 3  枪管 + 喷嘴
    "....dbccbbbbd.gg",   # 4  高光 + 喷嘴
    "....dbbbbbbbd...",   # 5  枪管底
    "....ddddddddd...",   # 6
    "......dbbd......",   # 7  扳机护圈上
    ".....dbbd.......",   # 8
    ".....dbbd....w..",   # 9  握把 + 水滴
    "....dbbd.....w..",   # 10
    "....dbbd........",   # 11
    "....dddd........",   # 12 握把底
    "................",   # 13
    "................",   # 14
    "................",   # 15
]


def check_png(path):
    """返回 (ok, 说明)。校验：PNG 头 / 全部 chunk CRC / zlib 可解 / 像素数据够长。"""
    data = open(path, "rb").read()
    if len(data) < 8 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return False, "不是 PNG（头=%r）" % data[:8]
    off, idat = 8, b""
    w = h = bd = ct = None
    while off < len(data):
        if off + 8 > len(data):
            return False, "chunk 头被截断（偏移 %d）" % off
        ln = struct.unpack(">I", data[off:off + 4])[0]
        typ = data[off + 4:off + 8]
        body = data[off + 8:off + 8 + ln]
        if len(body) < ln:
            return False, "%r chunk 数据被截断" % typ
        crc_at = off + 8 + ln
        if crc_at + 4 > len(data):
            return False, "%r chunk 缺 CRC" % typ
        crc = struct.unpack(">I", data[crc_at:crc_at + 4])[0]
        if zlib.crc32(typ + body) & 0xFFFFFFFF != crc:
            return False, "%r chunk CRC 不匹配" % typ
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", body[:10])
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
        off += 12 + ln
    if w is None:
        return False, "没有 IHDR"
    try:
        raw = zlib.decompress(idat)
    except zlib.error as e:
        return False, "IDAT 解压失败：%s" % e
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    need = h * (1 + (w * ch * bd + 7) // 8)
    if len(raw) < need:
        return False, "像素数据不足：%d < %d（%dx%d bd=%d ct=%d）" % (len(raw), need, w, h, bd, ct)
    return True, "%dx%d ch=%d bd=%d" % (w, h, ch, bd)


def write_png(path, art, palette):
    h = len(art)
    w = len(art[0])
    assert all(len(r) == w for r in art), "每行宽度必须一致"
    raw = bytearray()
    for row in art:
        raw.append(0)   # filter type 0 (None)
        for ch in row:
            raw.extend(palette[ch])
    def chunk(typ, body):
        return (struct.pack(">I", len(body)) + typ + body
                + struct.pack(">I", zlib.crc32(typ + body) & 0xFFFFFFFF))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)   # 8bit RGBA
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    write_png(OUT, ART, PALETTE)
    ok, why = check_png(OUT)
    print("%s -> %s  (%s)" % (OUT, "OK" if ok else "STILL BROKEN", why))
    # 顺手把整份贴图目录都校验一遍，坏的一张一张列出来
    tex_root = os.path.join(os.path.dirname(OUT), "..")
    bad = 0
    for root, _dirs, files in os.walk(os.path.abspath(tex_root)):
        for name in sorted(files):
            if not name.endswith(".png"):
                continue
            p = os.path.join(root, name)
            ok, why = check_png(p)
            if not ok:
                bad += 1
                print("  BAD %s：%s" % (p, why))
    print("全目录坏图：%d 张" % bad)
    return 0 if ok and bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
