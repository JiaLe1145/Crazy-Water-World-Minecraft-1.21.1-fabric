"""沉筏真机探针。

要点：筏子必须**先漂在水里**，"沉"才有东西可沉。
上一版探针栽在这里——`sable spawn platform` 把筏生成在 RCON 源位置（dev 世界地表 y≈102），
筏子落在陆面上被地面顶住，施加向下力只掉了 0.09 格就停住，看起来像"机制失效"，其实是环境不对。
所以本探针先在筏子脚下挖出水池，再把它投进去，然后才判沉。

用法：python tools/sink_probe.py
前置：./gradlew runServer 已在跑，Carpet 在 run/mods。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

# 每轮用唯一名字。固定名字会被上一轮残留的同名筏干扰：
# `sable info @e[name=nbw_raft_0]` 命中的是旧筏，而真正被判沉的是新筏，
# 于是测出"下沉 0.00 格"——日志里其实已经沉到 Y=94.5。
RUN = time.strftime("%H%M%S")
RAFT0 = "probe_%s_0" % RUN
RAFT1 = "probe_%s_1" % RUN
BOTS = (("t0", 0), ("t1", 0), ("t2", 1), ("t3", 1))


class Probe:
    def __init__(self):
        self.sock = rcon.connect()

    def cmd(self, text, quiet=False):
        out = rcon.run(self.sock, text) or ""
        if not quiet:
            print("  > %s\n      %s" % (text, out.replace("\n", "\n      ")[:300]))
        return out.strip()

    def __call__(self, text, **kw):
        return self.cmd(text, **kw)

    def pos(self, name, quiet=True):
        out = self.cmd("sable info @e[name=%s]" % name, quiet=quiet)
        m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
        return tuple(float(g) for g in m.groups()) if m else None

    def y(self, name):
        p = self.pos(name)
        return p[1] if p else None

    def hits(self, name):
        out = self.cmd("sable info @e[name=%s]" % name, quiet=True)
        m = re.search(r"Found (\d+) sub-levels", out)
        return int(m.group(1)) if m else 0

    def phase(self):
        return self.cmd("cww phase", quiet=True)

    def wait_phase(self, want, timeout=90):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if want in self.phase():
                return True
            time.sleep(1.0)
        return False


def dig_pool(p, cx, cy, cz):
    """在筏子脚下挖一个又宽又深的水池：底在 cy-9，水面 cy-1..cy+1，围墙到 cy+10。

    深度是必须的——筏子从漂浮位到池底只有 9 格可走，
    池子太浅的话"下沉 >4 格"这条断言在物理上就不可能成立（上一版就是这个坑）。

    宽度也要容得下两条筏分开投放：再早一版两条筏被传送到同一坐标，
    raft0 直接压在 raft1 上，向下力只是把它按在对方身上，什么都测不出来。
    """
    x0, x1 = int(cx) - 12, int(cx) + 12
    z0, z1 = int(cz) - 8, int(cz) + 8
    bottom = cy - 9
    p("fill %d %d %d %d %d %d minecraft:stone" % (x0, bottom, z0, x1, bottom, z1), quiet=True)
    p("fill %d %d %d %d %d %d minecraft:air" % (x0, cy - 8, z0, x1, cy + 10, z1), quiet=True)
    p("fill %d %d %d %d %d %d minecraft:water" % (x0 + 1, bottom + 1, z0 + 1, x1 - 1, cy + 1, z1 - 1), quiet=True)
    for wall in (
            "fill %d %d %d %d %d %d minecraft:stone" % (x0, bottom, z0, x1, cy + 10, z0),
            "fill %d %d %d %d %d %d minecraft:stone" % (x0, bottom, z1, x1, cy + 10, z1),
            "fill %d %d %d %d %d %d minecraft:stone" % (x0, bottom, z0, x0, cy + 10, z1),
            "fill %d %d %d %d %d %d minecraft:stone" % (x1, bottom, z0, x1, cy + 10, z1),
    ):
        p(wall, quiet=True)
    print("      水池：x %d..%d, z %d..%d, 底 y=%d, 水面 y=%d..%d"
          % (x0, x1, z0, z1, bottom, cy - 1, cy + 1))


def main():
    p = Probe()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    print("== 0. 等服务端")
    for _ in range(60):
        if "players online" in p.cmd("list", quiet=True):
            break
        time.sleep(2)
    expect("players online" in p.cmd("list", quiet=True), "rcon answering")

    print("== 1. 清残留并造两艘筏")
    for bot, _ in BOTS:
        p("player %s kill" % bot, quiet=True)
    p("sable spawn platform 4 oak_planks %s" % RAFT0)
    p("sable spawn platform 4 oak_planks %s" % RAFT1)
    expect(p.hits(RAFT0) == 1, "名字 %s 只命中 1 艘筏（实际 %d）" % (RAFT0, p.hits(RAFT0)))
    pos = p.pos(RAFT0)
    expect(pos is not None, "raft0 exists at %s" % (pos,))
    cx, cy, cz = pos

    print("== 2. 在筏子脚下挖水池，把两条筏分开投进去")
    dig_pool(p, cx, cy, cz)
    drop_x0, drop_x1 = cx - 6.0, cx + 6.0
    p("sable teleport @e[name=%s] %.1f %.1f %.1f" % (RAFT0, drop_x0, cy + 4, cz))
    p("sable teleport @e[name=%s] %.1f %.1f %.1f" % (RAFT1, drop_x1, cy + 4, cz))
    time.sleep(5.0)
    pos0 = p.pos(RAFT0)
    pos1 = p.pos(RAFT1)
    print("      落水后：raft0 @%s  raft1 @%s（水面 %d..%d）" % (pos0, pos1, cy - 1, cy + 1))
    expect(pos0 is not None and pos1 is not None, "both rafts readable after drop")
    gap = abs(pos0[0] - pos1[0]) if pos0 and pos1 else 0.0
    expect(gap > 6.0, "rafts are horizontally separated by %.1f blocks (not stacked)" % gap)
    expect(pos0 is not None and (cy - 4) < pos0[1] < (cy + 4),
           "raft0 is at the water line (Y=%s)" % (pos0[1] if pos0 else None,))

    print("== 3. 四个假玩家分两队并开一局")
    for bot, _ in BOTS:
        p("player %s spawn" % bot, quiet=True)
    for _ in range(30):
        if all(b.lower() in p.cmd("list", quiet=True).lower() for b, _ in BOTS):
            break
        time.sleep(1)
    for bot, team in BOTS:
        p("cww force %s %d" % (bot, team), quiet=True)
    p("cww start test_3min flat_test")
    expect(p.wait_phase("PLAY", 90), "reached PLAY (phase=%s)" % p.phase())

    print("== 4. 绑定队 0")
    bind_out = p("cww raft bind 0 %s" % RAFT0)
    status = p.cmd("cww raft status", quiet=True)
    expect("已绑到" in bind_out, "bind ok: %s" % bind_out[:140])
    expect("队0" in status and "绑" in status, "status shows binding: %s" % status[:160])

    def sample(seconds, label):
        print("==   采样 %ds（%s）" % (seconds, label))
        out = []
        for i in range(seconds // 2):
            time.sleep(2.0)
            out.append(p.y(RAFT0))
        print("      %s" % ", ".join("%s" % s for s in out[-6:]))
        return out

    print("== 5. 判沉并测下沉幅度（基线必须在 sink 之前取）")
    # 上一版探针先下 sink 命令再读基线，而 2.0 倍重量下筏子 2 秒就沉完并触发"沉到位"停机，
    # 于是测出"累计下沉 0.01 格"——那是探针取样顺序错了，不是机制失效；日志里明明是 9.5 格。
    before = p.y(RAFT0)
    p("cww raft weight 2.0", quiet=True)
    p("cww raft sink 0")
    ys = []
    for _ in range(14):
        time.sleep(1.0)
        ys.append(p.y(RAFT0))
    after = ys[-1] if ys else None
    floor = cy - 9
    dropped = (before - after) if (before is not None and after is not None) else 0.0
    print("      Y %s -> %s（下降 %.2f 格，池底 y=%d）" % (before, after, dropped, floor))
    print("      轨迹：%s" % ", ".join("%.2f" % y for y in ys if y is not None))

    expect(dropped > 4.0, "下沉 %.2f 格 > 4（机制成立）" % dropped)
    expect(after is not None and after < cy - 1,
           "筏沉到水面以下（final Y=%s，水面顶 y=%d）" % (after, cy + 1))
    expect(after is not None and after >= floor - 1.5,
           "停在池底之上没有穿模（final Y=%s，池底 y=%d）" % (after, floor))
    tail = [y for y in ys[-4:] if y is not None]
    # 这条断言的真正含义变了：现在不是"触底后停止加压"，而是"持续加压下它稳定停在池底"。
    # 旧文案会误导——真按旧文案理解，停压才是错的（浮力会把筏子顶回水面）。
    expect(len(tail) >= 3 and (max(tail) - min(tail)) < 0.5,
           "筏子稳定停在池底（末 4 次采样差 %.2f，只摆不沉不浮）"
           % ((max(tail) - min(tail)) if tail else 9.9))
    expect("players online" in p.cmd("list", quiet=True), "server still healthy after sinking")

    print("== 6. 对照：没判沉的队 1 不该沉")
    other_after = p.y(RAFT1)
    print("      raft1 Y %s -> %s" % (pos1[1] if pos1 else None, other_after))
    expect(pos1 is not None and other_after is not None and abs(other_after - pos1[1]) < 3.0,
           "unbound raft stayed near its drop height (delta=%s)"
           % (None if not (pos1 and other_after) else round(other_after - pos1[1], 2)))

    print("== 7. 清理")
    p("cww raft status")
    p("cww abort")
    for bot, _ in BOTS:
        p("player %s kill" % bot, quiet=True)
    for name in (RAFT0, RAFT1):
        p("sable remove @e[name=%s]" % name, quiet=True)

    print("\n=== SINK PROBE SUMMARY ===")
    print("satisfied : %d" % len(ok))
    print("FAILED    : %d" % len(bad))
    for f in bad:
        print("  FAIL %s" % f)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
