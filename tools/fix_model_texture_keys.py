#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修掉方块模型 textures 键名多余的 '#'。

MC 的约定是：
    "textures": { "wood": "nbw:block/mat_wood" },   <- 键【不带】#
    "faces":    { "north": { "texture": "#wood" } } <- 面引用【带】#

生成器把键也写成了 "#wood"，于是 JsonUnbakedModel 解析面引用 "#wood" 时
去查裸名 "wood"，查不到 → 整个面用 missing texture（紫黑格）。

用法：python tools/fix_model_texture_keys.py [--dry]
"""
import glob
import io
import json
import os
import sys


def main():
    dry = "--dry" in sys.argv
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.join(os.path.dirname(here), "src", "main", "resources",
                        "assets", "nbw", "models", "block")
    total = 0
    files = 0
    for path in sorted(glob.glob(os.path.join(root, "*.json"))):
        with io.open(path, encoding="utf-8") as f:
            j = json.load(f)
        tex = j.get("textures")
        if not tex:
            continue
        fixed = {}
        changed = 0
        for k, v in tex.items():
            if k.startswith("#"):
                fixed[k[1:]] = v
                changed += 1
            else:
                fixed[k] = v
        if changed:
            j["textures"] = fixed
            total += changed
            files += 1
            print("  %-26s %d 个键去掉 #" % (os.path.basename(path), changed))
            if not dry:
                with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                    json.dump(j, f, ensure_ascii=False, separators=(",", ":"))
    print("\n合计 %d 个文件 / %d 个键%s" % (files, total, "（dry-run）" if dry else "，已写入"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
