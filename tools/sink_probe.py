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

RAFT0 = "nbw_raft_0"
RAFT1 = "nbw_raft_1"
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
    """在筏子脚下造一个 13x13 的水池：底在 cy-2，水面 cy-1..cy+1，围墙到 cy+8。"""
    x0, x1 = int(cx) - 6, int(cx) + 6
    z0, z1 = int(cz) - 6, int(cz) + 6
    p("fill %d %d %d %d %d %d minecraft:stone" % (x0, cy - 2, z0, x1, cy - 2, z1))
    p("fill %d %d %d %d %d %d minecraft:air" % (x0, cy - 1, z0, x1, cy + 8, z1))
    p("fill %d %d %d %d %d %d minecraft:water" % (x0 + 1, cy - 1, z0 + 1, x1 - 1, cy + 1, z1 - 1))
    for wall in (
            "fill %d %d %d %d %d %d minecraft:stone" % (x0, cy - 1, z0, x1, cy + 8, z0),
            "fill %d %d %d %d %d %d minecraft:stone" % (x0, cy - 1, z1, x1, cy + 8, z1),
            "fill %d %d %d %d %d %d minecraft:stone" % (x0, cy - 1, z0, x0, cy + 8, z1),
            "fill %d %d %d %d %d %d minecraft:stone" % (x1, cy - 1, z0, x1, cy + 8, z1),
    ):
        p(wall)


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
    for name in (RAFT0, RAFT1):
        p("sable remove @e[name=%s]" % name, quiet=True)
    for bot, _ in BOTS:
        p("player %s kill" % bot, quiet=True)
    p("sable spawn platform 4 oak_planks %s" % RAFT0)
    p("sable spawn platform 4 oak_planks %s" % RAFT1)
    pos = p.pos(RAFT0)
    expect(pos is not None, "raft0 exists at %s" % (pos,))
    cx, cy, cz = pos

    print("== 2. 在筏子脚下挖水池，把筏投进去")
    dig_pool(p, cx, cy, cz)
    p("sable teleport @e[name=%s] %.1f %.1f %.1f" % (RAFT0, cx, cy + 4, cz))
    p("sable teleport @e[name=%s] %.1f %.1f %.1f" % (RAFT1, cx + 0.0, cy + 4, cz + 0.0))
    time.sleep(4.0)
    float0 = p.y(RAFT0)
    float1 = p.y(RAFT1)
    print("      漂浮稳定后：raft0 Y=%s raft1 Y=%s（水面在 %d..%d）" % (float0, float1, cy - 1, cy + 1))
    expect(float0 is not None and (cy - 3) < float0 < (cy + 4),
           "raft0 is floating inside the pool (Y=%s, pool water %d..%d)" % (float0, cy - 1, cy + 1))

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

    print("== 5. 判沉并采样 40s")
    before = p.y(RAFT0)
    other_before = p.y(RAFT1)
    print("      before: raft0 Y=%s  raft1 Y=%s" % (before, other_before))
    p("cww raft sink 0")
    samples = []
    for i in range(20):
        time.sleep(2.0)
        samples.append(p.y(RAFT0))
    for i, y in enumerate(samples):
        print("      t+%2ds Y=%s" % ((i + 1) * 2, y))
    after = samples[-1] if samples else None

    expect(before is not None and after is not None and after < before - 4.0,
           "raft0 sank by >4 blocks (before=%s after=%s)" % (before, after))
    expect(after is not None and after <= cy - 1,
           "raft0 reached the pool bottom area (after=%s, floor=%d)" % (after, cy - 2))
    distinct = len(set(s for s in samples if s is not None))
    expect(distinct >= 5, "sank progressively, not one teleport jump (distinct=%d)" % distinct)

    print("== 6. 对照：没判沉的队 1 不该沉")
    other_after = p.y(RAFT1)
    print("      raft1 Y %s -> %s" % (other_before, other_after))
    expect(other_after is not None and other_before is not None
           and abs(other_after - other_before) < 2.0,
           "unbound raft stayed put (delta=%s)" % (None if not (other_after and other_before)
                                                   else round(other_after - other_before, 2)))

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
