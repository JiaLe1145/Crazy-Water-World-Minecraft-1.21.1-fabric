#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the five NBW survival-status icons as deterministic 16x16 RGBA PNGs.

The HUD draws these textures directly, so they deliberately follow the existing
project palette: one-pixel charcoal outlines, compact silhouettes and a small
number of readable interior highlights.
"""

import os
import struct
import sys
import zlib


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "nbw", "textures", "status")

PALETTE = {
    ".": (0, 0, 0, 0),
    "d": (20, 20, 24, 255),
    "R": (120, 28, 32, 255),
    "r": (192, 57, 43, 255),
    "p": (244, 112, 95, 255),
    "B": (112, 96, 74, 255),
    "b": (218, 207, 180, 255),
    "w": (250, 246, 226, 255),
    "I": (34, 91, 116, 255),
    "i": (59, 156, 192, 255),
    "c": (135, 229, 246, 255),
    "u": (48, 112, 190, 255),
    "v": (86, 177, 239, 255),
    "q": (184, 232, 255, 255),
    "O": (137, 69, 15, 255),
    "o": (226, 133, 30, 255),
    "y": (255, 207, 92, 255),
    "n": (207, 186, 142, 255),
}

ART = {
    "bleeding": [
        "................",
        ".......r........",
        "......rrr.......",
        "......rrr.......",
        ".....rrrrr......",
        ".....rrprr......",
        "....rrrprrr.....",
        "...rrrrrrrrr....",
        "...rrrprrrrr....",
        "...rrrprrrrr....",
        "....rrrrrrr.....",
        ".....rrrrr......",
        "......rrr.......",
        ".......r........",
        "................",
        "................",
    ],
    "fracture": [
        "................",
        "................",
        "....bbb..bbb....",
        "...bbbbbbbbbb...",
        "..bbbbbbRbbbbb..",
        "..bbbbbbRbbbbb..",
        "..bbbbbbRbbbbb..",
        "...bbbbbbbbbb...",
        "....bbb..bbb....",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "hypothermia": [
        "................",
        ".......ii.......",
        ".......ci.......",
        "...i...ii...i...",
        "...ii..ci..ii...",
        "....ii.ii.ii....",
        ".....i.ii.i.....",
        "..iiiiiiciiiii..",
        ".....i.ii.i.....",
        "....ii.ii.ii....",
        "...ii..ci..ii...",
        "...i...ii...i...",
        ".......ci.......",
        ".......ii.......",
        "................",
        "................",
    ],
    "low_nutrition": [
        "................",
        "......oooo......",
        "......oooo......",
        ".......oo.......",
        "......oooo......",
        ".....oooooo.....",
        "................",
        "..wwwwwwwwwwww..",
        "..wnnnnnnnnnnw..",
        "...wnnnnnnnnw...",
        "....wnnnnnnw....",
        ".....wwwwww.....",
        "......wwww......",
        "................",
        "................",
        "................",
    ],
    "low_hydration": [
        "................",
        "......iiii......",
        ".....IIIIII.....",
        "....IIiiiiII....",
        "...IIiiiiiiII...",
        "...IIiiiiiiII...",
        "...IIiiiiiiII...",
        "...IIqqqqqqII...",
        "...IIiiiiiiII...",
        "...IIiiiiiiII...",
        "...IIiiiiiiII...",
        "...IIiiiiiiII...",
        "....IIiiiiII....",
        ".....IIIIII.....",
        "................",
        "................",
    ],
}


def add_outline(rows):
    """Add a one-pixel dark outline around every opaque silhouette."""
    grid = [list(row) for row in rows]
    out = [row[:] for row in grid]
    for y in range(16):
        for x in range(16):
            if grid[y][x] != ".":
                continue
            neighbours = ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
            if any(0 <= nx < 16 and 0 <= ny < 16 and grid[ny][nx] != "."
                   for nx, ny in neighbours):
                out[y][x] = "d"
    return ["".join(row) for row in out]


def write_png(path, rows):
    if len(rows) != 16 or any(len(row) != 16 for row in rows):
        raise SystemExit("status icon must be 16x16: %s" % path)
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for ch in row:
            if ch not in PALETTE:
                raise SystemExit("unknown palette character %r in %s" % (ch, path))
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
    os.makedirs(OUT, exist_ok=True)
    for name, rows in ART.items():
        path = os.path.join(OUT, name + ".png")
        write_png(path, add_outline(rows))
        print("wrote %s (%d bytes)" % (path, os.path.getsize(path)))
    print("generated %d status icons" % len(ART))


if __name__ == "__main__":
    sys.exit(main())
