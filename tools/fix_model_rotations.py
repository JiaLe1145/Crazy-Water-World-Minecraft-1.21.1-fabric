#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把方块模型里不合法的 rotation.angle 吸附到 MC 允许的五个值。

MC 的 ModelElement.Deserializer 只允许 -45 / -22.5 / 0 / 22.5 / 45，
生成器（mcgen）会给任意角度（实测 -10.9004），整个模型就直接加载失败 →
游戏里显示紫黑格。这个脚本把每个 elements[].rotation.angle 吸附到最近档。

用法：python tools/fix_model_rotations.py [--dry]
"""
import glob
import io
import json
import os
import sys

ALLOWED = [-45.0, -22.5, 0.0, 22.5, 45.0]


def snap(a):
    return min(ALLOWED, key=lambda v: abs(v - a))


def main():
    dry = "--dry" in sys.argv
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.join(os.path.dirname(here), "src", "main", "resources",
                        "assets", "nbw", "models", "block")
    total_bad = 0
    files_changed = 0
    for path in sorted(glob.glob(os.path.join(root, "*.json"))):
        with io.open(path, encoding="utf-8") as f:
            j = json.load(f)
        changed = 0
        for e in j.get("elements", []):
            rot = e.get("rotation")
            if not rot or "angle" not in rot:
                continue
            a = float(rot["angle"])
            if a in ALLOWED:
                continue
            s = snap(a)
            rot["angle"] = s
            changed += 1
        if changed:
            total_bad += changed
            files_changed += 1
            print("  %-26s %d 处角度被吸附" % (os.path.basename(path), changed))
            if not dry:
                with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                    json.dump(j, f, ensure_ascii=False, separators=(",", ":"))
    print("\n合计 %d 个文件 / %d 处角度%s" % (files_changed, total_bad, "（dry-run 未写入）" if dry else "，已写入"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
