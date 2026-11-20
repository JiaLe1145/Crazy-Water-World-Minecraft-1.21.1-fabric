#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M6 · A 域配置与启动探针（2026-11-21）。

覆盖 M6-FULL-TEST-PLAN 的：
  A2 4 张内置赛制全部可 `cww format test <id>` 通过
  A3 6 张内置图全部可 `cww arena test <id>` 通过
  A4 `cww lobby test` 通过
  A5 坏 JSON → 隔离到 _invalid/ 且拒绝加载（不静默纠错）
  A6 schemaVersion 不是 1 → 拒绝
  A7 world/cww/* 本地覆盖优先于 jar 内置
  A8 `format reload` / `arena reload` 热重载生效，且对局中禁止
  C1 已建好的三张图 `cww arena terrain <id>` 问题 0 列
（A1 干净世界首启需要搬 world/cww 目录 + 重启服，单独用 tools/m6_fresh_restart.py 编排。）

跑法（服务端已起、RCON 在 25576）：
    .\run\py-embed\python.exe -I -X utf8 tools/m6_config_probe.py
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, wait_clean_lobby, no_seated_sessions  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORLD_CWW = os.path.join(ROOT, "run", "world", "cww")
ARENAS = os.path.join(WORLD_CWW, "arenas")
INVALID = os.path.join(WORLD_CWW, "_invalid")
BUILTIN_ARENAS = os.path.join(ROOT, "src", "main", "resources", "data", "nbw", "arena")

FORMATS = ["oar_long", "sail_long", "standard_12min", "test_3min"]
ARENAS_ALL = ["default_sea", "flat_test", "test_build", "test_sail7", "test_sea", "test_side7"]
ARENAS_BUILT = ["flat_test", "test_sea", "default_sea"]

ok, bad = [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def files_in(d):
    try:
        return set(os.listdir(d))
    except FileNotFoundError:
        return set()


def rm_quiet(path):
    try:
        os.remove(path)
    except FileNotFoundError:
        pass


def cleanup_quarantine(arena_id):
    for name in files_in(INVALID):
        if name.endswith(arena_id + ".json"):
            rm_quiet(os.path.join(INVALID, name))


def read_builtin_arena(arena_id):
    with open(os.path.join(BUILTIN_ARENAS, arena_id + ".json"), encoding="utf-8") as fh:
        return fh.read()


def main():
    sys.stdout.reconfigure(line_buffering=True)
    global p
    p = P()
    print("== 0. 清场（只要求没有对局在跑，空队伍骨架不算脏）")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p)
    expect(clean, "干净 LOBBY（phase=LOBBY queue=0 且无占席会话）：%s" % st.replace("\n", " ")[:150])
    if not clean:
        return 1

    # ---------------------------------------------------------------- A2
    print("== A2 4 张内置赛制 cww format test")
    for fid in FORMATS:
        out = p("cww format test %s" % fid)
        expect("校验通过" in out, "format '%s' 校验通过：%s" % (fid, out.replace("\n", " ")[:150]))

    # ---------------------------------------------------------------- A3
    print("== A3 6 张内置图 cww arena test")
    for aid in ARENAS_ALL:
        out = p("cww arena test %s" % aid)
        expect("校验通过" in out, "arena '%s' 校验通过：%s" % (aid, out.replace("\n", " ")[:150]))

    # ---------------------------------------------------------------- A4
    print("== A4 cww lobby test")
    out = p("cww lobby test")
    expect("校验通过" in out, "lobby 校验通过：%s" % out.replace("\n", " ")[:150])

    # ---------------------------------------------------------------- A5
    print("== A5 坏 JSON → _invalid/ 隔离 + 拒绝加载")
    bad_id = "m6badjson"
    bad_path = os.path.join(ARENAS, bad_id + ".json")
    cleanup_quarantine(bad_id)
    os.makedirs(ARENAS, exist_ok=True)
    before_invalid = files_in(INVALID)
    with open(bad_path, "w", encoding="utf-8") as fh:
        fh.write("{ this is not valid json")
    out = p("cww arena reload %s" % bad_id)
    after_invalid = files_in(INVALID)
    quarantined = [n for n in (after_invalid - before_invalid) if bad_id in n]
    expect("被拒绝" in out and "JSON 解析失败" in out, "坏 JSON 被拒绝且报出解析失败：%s" % out[:150])
    expect(not os.path.isfile(bad_path), "坏文件已从 arenas/ 移走")
    expect(bool(quarantined), "坏文件归档到 _invalid/：%s" % quarantined[:2])
    out2 = p("cww arena test %s" % bad_id)
    expect("找不到 arena 文件" in out2 or "被拒绝" in out2, "拒绝加载后该 id 仍不可用：%s" % out2[:150])
    cleanup_quarantine(bad_id)

    # ---------------------------------------------------------------- A6
    print("== A6 schemaVersion != 1 → 拒绝")
    ver_id = "m6badver"
    ver_path = os.path.join(ARENAS, ver_id + ".json")
    cleanup_quarantine(ver_id)
    text = read_builtin_arena("flat_test")
    doc = json.loads(text)
    doc["id"] = ver_id
    doc["schemaVersion"] = 2
    with open(ver_path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)
    out = p("cww arena reload %s" % ver_id)
    expect("被拒绝" in out and "schemaVersion 必须是 1" in out,
           "schemaVersion=2 被拒绝（不猜测纠正）：%s" % out[:170])
    expect(not os.path.isfile(ver_path), "版本不对的文件也已归档")
    cleanup_quarantine(ver_id)

    # ---------------------------------------------------------------- A7
    print("== A7 world/cww 本地覆盖优先于 jar 内置")
    local_flat = os.path.join(ARENAS, "flat_test.json")
    had_local_flat = os.path.isfile(local_flat)
    try:
        with open(local_flat, "w", encoding="utf-8") as fh:
            fh.write(read_builtin_arena("flat_test"))
        out = p("cww arena test flat_test")
        expect("来源=world/cww/arenas 文件" in out, "有本地文件时来源=world 文件：%s" % out[:170])
        lst = p("cww arena list")
        expect("内置被本地同名文件覆盖" in lst and "flat_test" in lst,
               "arena list 如实报告 flat_test 被本地覆盖：%s" % lst.replace("\n", " ")[:190])
        rm_quiet(local_flat)
        out = p("cww arena test flat_test")
        expect("来源=jar 内置默认图" in out, "删掉本地文件后回退 jar 内置：%s" % out[:170])
    finally:
        if not had_local_flat:
            rm_quiet(local_flat)

    # ---------------------------------------------------------------- A8
    print("== A8 热重载：LOBBY 生效 / 对局中禁止")
    out = p("cww format reload test_3min")
    expect("已装入内存" in out, "LOBBY 里 format reload 生效：%s" % out.replace("\n", " ")[:170])
    out = p("cww arena reload flat_test")
    expect("已装入内存" in out, "LOBBY 里 arena reload 生效：%s" % out.replace("\n", " ")[:170])

    tag = "cfg"
    bots = [("c1", 0), ("c2", 1)]
    for n, _ in bots:
        p("player %s kill" % n)
        p("player %s spawn" % n)
    for _ in range(30):
        listing = p("list").lower()
        if all(n in listing for n, _ in bots):
            break
        p.sleep(1)
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, list(bots))
    p("cww start test_3min flat_test")
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "A8 前置：进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if reached:
        out = p("cww arena reload flat_test")
        expect("禁止替换正在使用的 arena" in out, "PLAY 中 arena reload 被拒：%s" % out[:170])
        out = p("cww format reload oar_long")
        expect("禁止替换正在使用的 arena" in out and "换赛制同样禁止" in out,
               "PLAY 中 format reload 同样被拒：%s" % out[:170])
        p("cww abort")
        for _ in range(20):
            p.sleep(0.5)
            if "phase=LOBBY" in p("cww phase"):
                break
    for n, _ in bots:
        p("player %s kill" % n)
    p.sleep(1)
    print("  清残留：" + repr(sweep_stale_bots(p)))

    # ---------------------------------------------------------------- C1
    print("== C1 地形审计（已建好的三张图应 0 列问题）")
    for aid in ARENAS_BUILT:
        out = p("cww arena terrain %s" % aid)
        flat = out.replace("\n", " ")
        expect("问题 0 列" in flat, "arena '%s' 地形审计 0 列问题：%s" % (aid, flat[:190]))
    for aid in [a for a in ARENAS_ALL if a not in ARENAS_BUILT]:
        out = p("cww arena terrain %s" % aid)
        print("      [记录] %s：%s" % (aid, out.replace("\n", " ")[:160]))

    print("\n== 汇总")
    print("  PASS=%d FAIL=%d" % (len(ok), len(bad)))
    for m in bad:
        print("  FAIL - " + m)
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
