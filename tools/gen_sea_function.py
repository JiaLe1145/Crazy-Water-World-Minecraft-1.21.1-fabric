#!/usr/bin/env python3
"""生成随 jar 发布的海域地形 function（模组不写地形，地形=原版命令包）。

几何一律从 src/main/resources/data/nbw/arena/<id>.json 读，
所以半径/水位改了忘了重生成，会被 cww arena terrain 的读数审计抓出来。

用法：
    python tools/gen_sea_function.py                # 两张内置图都重生成
    python tools/gen_sea_function.py --check        # 只检查现有产物是否与 JSON 同源，不写文件
"""
import argparse
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ARENA_DIR = ROOT / "src" / "main" / "resources" / "data" / "nbw" / "arena"
FUNC_DIR = ROOT / "src" / "main" / "resources" / "data" / "nbw" / "function" / "arena"

WATER_DEPTH = 10   # 水：waterLevel-9 .. waterLevel
FLOOR_DEPTH = 3    # 海床：waterLevel-12 .. waterLevel-10（石头）
AIR_CLEAR = 9      # 水面以上清空到 waterLevel+9，给玩家和以后的桅杆留空间
LOAD_FIRST = True  # 先按区块 forceload 一整轮，再开始写方块
LOAD_TICK = 2      # 下票每几 tick 一笔（建海见证明；预热侧见 ArenaChunkLoader.PER_TICK）：新区块首次生成很贵，挤在一起就会拖爆 tick

STORAGE_TMPL = "nbw:sea_%s"


def chord_half(r, z):
    """圆内某行的半宽（含端点）。"""
    return int(math.isqrt(r * r - z * z))


def rows_of(radius):
    return [z for z in range(-radius, radius + 1)]


def chunk_box(arena):
    """圆覆盖到的区块包围盒（区块坐标），逐区块下票才是安全的粒度。"""
    cx, cz, r = arena["sea"]["centerX"], arena["sea"]["centerZ"], arena["sea"]["radius"]
    x0 = math.floor((cx - r) / 16)
    x1 = math.floor((cx + r) / 16)
    z0 = math.floor((cz - r) / 16)
    z1 = math.floor((cz + r) / 16)
    return x0, x1, z0, z1


def build_queue(arena):
    cxc, czc, r = arena["sea"]["centerX"], arena["sea"]["centerZ"], arena["sea"]["radius"]
    w = arena["sea"]["waterLevel"]
    layers = [
        (w - WATER_DEPTH + 1, w, "minecraft:water"),
        (w - WATER_DEPTH - FLOOR_DEPTH + 1, w - WATER_DEPTH, "minecraft:stone"),
        (w + 1, w + AIR_CLEAR, "minecraft:air"),
    ]
    widest = chord_half(r, 0)
    peak = (2 * widest + 1) * max(y1 - y0 + 1 for y0, y1, _ in layers)
    entries = []
    if LOAD_FIRST:
        # 逐区块下票。按行下票会一次拉起最多 8 个新区块，实测把服务端拖住 2157ms（43 tick 落后）
        bx0, bx1, bz0, bz1 = chunk_box(arena)
        for cz in range(bz0, bz1 + 1):
            for cx in range(bx0, bx1 + 1):
                entries.append({"load": {"x0": cx * 16, "z0": cz * 16,
                                         "x1": cx * 16 + 15, "z1": cz * 16 + 15}})
    for z in rows_of(r):
        hx = chord_half(r, z)
        for y0, y1, block in layers:
            entries.append({"fill": {"x0": cxc - hx, "y0": y0, "z": czc + z,
                                     "x1": cxc + hx, "y1": y1, "block": block}})
    bx0, bx1, bz0, bz1 = chunk_box(arena)
    nchunks = (bx1 - bx0 + 1) * (bz1 - bz0 + 1)
    return entries, peak, layers, nchunks


def release_queue(arena):
    bx0, bx1, bz0, bz1 = chunk_box(arena)
    out = []
    for cz in range(bz0, bz1 + 1):
        for cx in range(bx0, bx1 + 1):
            out.append({"unload": {"x0": cx * 16, "z0": cz * 16, "x1": cx * 16 + 15, "z1": cz * 16 + 15}})
    return out


def total_blocks(entries):
    t = 0
    for e in entries:
        f = e.get("fill")
        if f:
            t += (f["x1"] - f["x0"] + 1) * (f["y1"] - f["y0"] + 1)
    return t


def queue_file(store, id_, entries, title, note):
    lines = [
        "# %s —— 本文件由 tools/gen_sea_function.py 生成，不要手改。" % title,
        "# %s" % note,
        "# 一笔 = 一个命令 = 一个 tick，队列跑完自动收尾。",
    ]
    for e in entries:
        key, v = next(iter(e.items()))
        body = ",".join("%s:%s" % (k, ('"%s"' % val if isinstance(val, str) else val))
                        for k, val in sorted(v.items()))
        lines.append("data modify storage %s rows append value {%s:{%s}}" % (store, key, body))
    return "\n".join(lines) + "\n"


def render(arena):
    id_ = arena["id"]
    store = STORAGE_TMPL % id_
    fdir = FUNC_DIR / id_
    entries, peak, layers, nchunks = build_queue(arena)
    rel = release_queue(arena)
    blocks = total_blocks(entries)
    load_ticks = len(rel) * LOAD_TICK
    fill_ticks = len(entries) - len(rel)
    build_ticks = load_ticks + fill_ticks
    release_ticks = len(rel) * LOAD_TICK
    load_entries = len(rel)
    fill_entries = fill_ticks
    r = arena["sea"]["radius"]
    chunks = nchunks
    files = {}

    files["build.mcfunction"] = "\n".join([
        "# %s 建海入口：/function nbw:arena/%s/build" % (id_, id_),
        "#",
        "# 几何来自 data/nbw/arena/%s.json（圆心 %d,%d 半径 %d 水面 %d），改图请重跑生成器。" % (
            id_, arena["sea"]["centerX"], arena["sea"]["centerZ"], r, arena["sea"]["waterLevel"]),
        "# 分层：水 y %d..%d、海床 y %d..%d（石头）、水面以上清空 y %d..%d" % (
            layers[0][0], layers[0][1], layers[1][0], layers[1][1], layers[2][0], layers[2][1]),
        "#",
        "# 代价（算术写在生成器里，别照抄感觉）：",
        "#   forceload %d 笔（逐区块，每 %d tick 一笔 = %d tick）+ fill %d 笔（每 tick 一笔）"
        "→ 建海 ≈ %.0f 秒；撤票 ≈ %.0f 秒" % (
            load_entries, LOAD_TICK, load_entries * LOAD_TICK, fill_entries,
            build_ticks / 20.0, release_ticks / 20.0),
        "#   方块写入合计 %d，单 tick 最大 %d —— 远低于原版 fill 的 32768 上限" % (blocks, peak),
        "#   覆盖区块 %d 个；建海跑完不会自动撤票，收尾看 finish 的提示" % chunks,
        "#",
        "# 注意：这是**写地形**的命令，会覆盖该区域已有的方块。",
        "# 正式服请先确认这片坐标归地图作者所有，再执行。",
        "# 队列还有剩余时不重开：清空重跑会把已写的行再写一遍，",
        "# 而且重复 forceload 同一批区块会逐条命令失败（噪音会淹没真错误）。",
        "execute if data storage %s rows[0] run tellraw @a [{\"text\":\"[nbw] %s 队列仍有剩余笔数，"
        "先跑 /function nbw:arena/%s/abort 或直接 resume，不要重复 build。\",\"color\":\"red\"}]" % (store, id_, id_),
        "execute if data storage %s rows[0] run return fail" % store,
        "data modify storage %s note set value \"建海\"" % store,
        "data modify storage %s rows set value []" % store,
        "function nbw:arena/%s/queue_build" % id_,
        "tellraw @a [{\"text\":\"[nbw] %s 开始建海：\",\"color\":\"gold\"},"
        "{\"nbt\":\"rows\",\"storage\":\"%s\",\"color\":\"red\"},{\"text\":\" 笔\",\"color\":\"gold\"}]" % (id_, store),
        "schedule function nbw:arena/%s/step 1t replace" % id_,
    ]) + "\n"

    files["step.mcfunction"] = "\n".join([
        "# 队列推进器：每 tick 从队首取一笔干完，再排下一 tick。",
        "# 用 key 存在性分派，不用值比较 —— execute if data 只判存在，不判相等。",
        "execute if data storage %s rows[0] run data modify storage %s job set from storage %s rows[0]" % (store, store, store),
        "execute if data storage %s rows[0] run data remove storage %s rows[0]" % (store, store),
        "execute if data storage %s job.load run function nbw:arena/%s/macro_load with storage %s job.load" % (store, id_, store),
        "execute if data storage %s job.fill run function nbw:arena/%s/macro_fill with storage %s job.fill" % (store, id_, store),
        "execute if data storage %s job.unload run function nbw:arena/%s/macro_unload with storage %s job.unload" % (store, id_, store),
        "# 排程按活种分节流：下票要生成新区块（贵），写块只是改方块（便宜）",
        "execute if data storage %s rows[0] if data storage %s job.load run schedule function nbw:arena/%s/step %dt replace" % (store, store, id_, LOAD_TICK),
        "execute if data storage %s rows[0] if data storage %s job.unload run schedule function nbw:arena/%s/step %dt replace" % (store, store, id_, LOAD_TICK),
        "execute if data storage %s rows[0] if data storage %s job.fill run schedule function nbw:arena/%s/step 1t replace" % (store, store, id_),
        "# 取完即清：不把这笔留给下一个 tick 重放（最后一行 fill 被重复执行就是这个坑）",
        "execute if data storage %s job run data remove storage %s job" % (store, store),
        "# 队列空了才收尾 —— 最后一笔之后循环不再排队，收尾必须放在末尾判，放开头永远触发不了",
        "execute unless data storage %s rows[0] run function nbw:arena/%s/finish" % (store, id_),
    ]) + "\n"

    files["macro_load.mcfunction"] = "\n".join([
        "# 宏：把一个区块强制加载（未加载的区块写不进去）。逐区块，不给整行——",
        "# 整行会一次拉起最多 8 个新区块，实测把服务端拖住 2157ms。",
        "$forceload add $(x0) $(z0) $(x1) $(z1)",
    ]) + "\n"

    files["macro_fill.mcfunction"] = "\n".join([
        "# 宏：一行一个断面的方块写入。",
        "$fill $(x0) $(y0) $(z) $(x1) $(y1) $(z) $(block)",
    ]) + "\n"

    files["macro_unload.mcfunction"] = "\n".join([
        "# 宏：只撤这一批自己的 forceload，绝不用 forceload remove all ——",
        "# 那会把地图作者或别的系统下的票一起撤掉。",
        "$forceload remove $(x0) $(z0) $(x1) $(z1)",
    ]) + "\n"

    files["finish.mcfunction"] = "\n".join([
        "execute if data storage %s job run data remove storage %s job" % (store, store),
        "tellraw @a [{\"text\":\"[nbw] %s ：\",\"color\":\"green\"},"
        "{\"nbt\":\"note\",\"storage\":\"%s\",\"color\":\"yellow\"},"
        "{\"text\":\" 队列已跑完。\",\"color\":\"gray\"},"
        "{\"text\":\"用 /function nbw:arena/%s/status 复查剩余笔数；\",\"color\":\"gray\"},"
        "{\"text\":\"建海期间的 forceload 不会自动撤，确认无误后跑 /function nbw:arena/%s/release\",\"color\":\"red\"}]"
        % (id_, store, id_, id_),
    ]) + "\n"

    files["release.mcfunction"] = "\n".join([
        "# 撤掉建海期间下的 forceload（按同一张行表逐行撤，一次一批）。",
        "# 建海/撤票共用一个队列，跑着的时候绝不能插一脚：先 status 看剩余，跑完再撤。",
        "execute if data storage %s rows[0] run tellraw @a [{\"text\":\"[nbw] %s 队列还有剩余笔数，"
        "撤票要等它跑完（共用同一个队列）。\",\"color\":\"red\"}]" % (store, id_),
        "execute if data storage %s rows[0] run return fail" % store,
        "data modify storage %s note set value \"撤票\"" % store,
        "data modify storage %s rows set value []" % store,
        "function nbw:arena/%s/queue_release" % id_,
        "schedule function nbw:arena/%s/step 1t replace" % id_,
    ]) + "\n"

    files["resume.mcfunction"] = "\n".join([
        "# 中断（abort）或服务端重启后接着跑剩余队列，不重铺、不重复已写的行。",
        "execute if data storage %s rows[0] run schedule function nbw:arena/%s/step 1t replace" % (store, id_),
        "execute unless data storage %s rows[0] run tellraw @a [{\"text\":\"[nbw] %s 队列是空的：要么已经跑完，要么还没 build。\",\"color\":\"red\"}]" % (store, id_),
    ]) + "\n"

    files["abort.mcfunction"] = "\n".join([
        "# 停手：撤掉排队的 step。队列内容留在 storage 里，想继续跑 resume。",
        "schedule clear nbw:arena/%s/step" % id_,
        "tellraw @a [{\"text\":\"[nbw] %s 已中断，剩余笔数见下一行\",\"color\":\"yellow\"}]" % id_,
        "data get storage %s rows" % store,
    ]) + "\n"

    files["status.mcfunction"] = "\n".join([
        "# 剩余笔数：data get 的那行回包就是机器可读读数（RCON 直接拿到）。",
        "# 不用 @s —— RCON/控制台这个来源不是实体，@s 会选择失败并刷一条无关的错误。",
        "tellraw @a [{\"text\":\"[nbw] %s 剩余笔数见下一行\",\"color\":\"aqua\"}]" % id_,
        "data get storage %s rows" % store,
    ]) + "\n"

    files["queue_build.mcfunction"] = queue_file(
        store, id_, entries, "建海队列",
        "几何来自 data/nbw/arena/%s.json；共 %d 笔、%d 个方块写入、单笔最大 %d 方块" % (id_, len(entries), blocks, peak))
    files["queue_release.mcfunction"] = queue_file(
        store, id_, rel, "撤票队列", "与建海队列的 forceload 行表一一对应，撤完正好归零")

    return fdir, build_ticks, release_ticks, blocks, peak, chunks, files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只比对现有产物是否与 JSON 同源")
    ap.add_argument("--ids", nargs="*", default=None)
    args = ap.parse_args()

    ids = args.ids or sorted(p.stem for p in ARENA_DIR.glob("*.json"))
    rc = 0
    for id_ in ids:
        arena = json.loads((ARENA_DIR / (id_ + ".json")).read_text(encoding="utf-8"))
        res = render(arena)
        fdir, build_ticks, release_ticks, blocks, peak, chunks = res[:6]
        files = res[6]
        drift = []
        for name, content in files.items():
            if "%s" in content or "%d" in content:
                print("残留未替换的占位符：%s/%s" % (id_, name))
                return 1
        for name, content in files.items():
            for line in content.splitlines():
                if "tellraw" not in line:
                    continue
                # 锚在 "tellraw @a " 之后：组件文案里本身就带 [nbw]，用第一个左括号会误判
                try:
                    json.loads(line.split("tellraw @a ", 1)[1])
                except Exception as e:
                    # JSON 少一个闭引号不会让函数报错，而是整个函数静默不加载 —— 必须在生成时就拦
                    print("BAD tellraw JSON in %s/%s: %s :: %s" % (id_, name, e, line))
                    return 1
        for name, content in files.items():
            target = fdir / name
            if args.check:
                if not target.exists():
                    drift.append("%s 缺失" % name)
                elif target.read_text(encoding="utf-8") != content:
                    drift.append("%s 与 JSON 不同源（改过 arena 配置没重生成？）" % name)
            else:
                fdir.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8", newline="\n")
        print("%-12s 建海笔数=%-5d 撤票=%-4d 方块=%-9d 单笔峰值=%-6d 建海≈%.0fs 撤票≈%.0fs 区块≈%d %s" % (
            id_, build_ticks, release_ticks, blocks, peak,
            build_ticks / 20.0, release_ticks / 20.0, chunks,
            "OK" if not drift else "DRIFT: " + "; ".join(drift[:3])))
        if drift:
            rc = 1
    if args.check and rc:
        print("存在漂移：python tools/gen_sea_function.py 重新生成后再跑探针")
    return rc


if __name__ == "__main__":
    sys.exit(main())
