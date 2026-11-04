"""风推进真机探针（M2-5）。

要回答的是上一轮留下的那条硬结论：无动力时筏子只滑约 2 秒就停。
所以这里的关键不是"动没动"，而是**能不能持续**——
如果风速稳定维持在某个值上不掉回 0，风才算真的解决了动力问题。

顺带验三件容易出错的事：
  - 背风（风从帆背面吹）必须不产生反向力，否则帆变成"倒车挡"，玩家会看不懂
  - 风是全局单值，两队同一时刻受同一阵风（公平性硬约束）
  - 无帆的筏不该被风推（帆是受风面）

用法：python tools/wind_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

BOTS = (("w0", 0), ("w1", 0), ("w2", 1), ("w3", 1))
ANCHORS = ((1000, 952), (1000, 1048))


class P:
    def __init__(self):
        self.sock = rcon.connect()

    def cmd(self, text, quiet=True):
        out = rcon.run(self.sock, text) or ""
        if not quiet:
            print("  > %s\n      %s" % (text, out.replace("\n", "\n      ")[:300]))
        return out.strip()

    def __call__(self, text, **kw):
        return self.cmd(text, **kw)

    def phase(self):
        return self.cmd("cww phase")

    def wait_phase(self, want, timeout=90):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if want in self.phase():
                return True
            time.sleep(1.0)
        return False

    def readout(self, team=0):
        return self.cmd("cww wind read %d" % team)

    def speed(self, team=0):
        m = re.search(r"速度=\(([\d.eE+-]+), ([\d.eE+-]+)\)", self.readout(team))
        if not m:
            return None
        return (float(m.group(1)), float(m.group(2)))

    def rate(self, team=0):
        v = self.speed(team)
        return None if v is None else (v[0] ** 2 + v[1] ** 2) ** 0.5

    def windline(self, team=0):
        m = re.search(r"风 dir=\(([-\d.]+), ([-\d.]+)\) strength=([-\d.]+)", self.readout(team))
        return m.groups() if m else None

    def settle(self, timeout=45):
        deadline = time.time() + timeout
        while time.time() < deadline:
            r = self.rate()
            if r is not None and r < 0.02:
                return True
            time.sleep(1.0)
        return False

    def curve(self, seconds):
        out = []
        for _ in range(seconds):
            time.sleep(1.0)
            out.append(self.rate())
        return out


def main():
    p = P()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    print("== 0. 造海 + 起一局")
    for cx, cz in ANCHORS:
        p("fill %d 149 %d %d 153 %d minecraft:air" % (cx - 10, cz - 10, cx + 10, cz + 10))
        p("fill %d 149 %d %d 152 %d minecraft:water" % (cx - 10, cz - 10, cx + 10, cz + 10))
        for wall in ("fill %d 146 %d %d 156 %d minecraft:stone" % (cx - 11, cz - 11, cx + 11, cz - 11),
                     "fill %d 146 %d %d 156 %d minecraft:stone" % (cx - 11, cz + 11, cx + 11, cz + 11),
                     "fill %d 146 %d %d 156 %d minecraft:stone" % (cx - 11, cz - 11, cx - 11, cz + 11),
                     "fill %d 146 %d %d 156 %d minecraft:stone" % (cx + 11, cz - 11, cx + 11, cz + 11)):
            p(wall)
    p("forceload add 985 935 1015 1065")
    p("forceload add 985 1031 1015 1061")

    for bot, _ in BOTS:
        p("player %s kill" % bot)
    p("cww abort")
    for bot, _ in BOTS:
        p("player %s spawn" % bot)
    for _ in range(30):
        if all(b.lower() in p.cmd("list").lower() for b, _ in BOTS):
            break
        time.sleep(1)
    for bot, team in BOTS:
        p("cww force %s %d" % (bot, team))
    p("cww start test_3min flat_test")
    expect(p.wait_phase("PLAY", 90), "进入 PLAY")

    print("== 1. 对照组：有帆但无风，筏子不该动")
    p("cww wind set calm 0")
    p("cww sail rig 0 5 north")
    p.settle()
    ctrl = p.curve(5)
    print("      无风速率：%s" % ", ".join("%.3f" % v for v in ctrl if v is not None))
    expect(max([v for v in ctrl if v is not None] or [0]) < 0.05,
           "无风时帆不产生任何推力（对照组）")

    print("== 2. 关键：顺风应当持续推动，而不是冲一下再停")
    p("cww wind set north 2.0", quiet=False)
    curve = p.curve(12)
    print("      速率曲线：%s" % ", ".join("%.3f" % v for v in curve if v is not None))
    vals = [v for v in curve if v is not None]
    expect(len(vals) >= 8, "采到足够多的点")
    peak = max(vals)
    expect(peak > 0.15, "风确实把筏子推起来了（峰值速率 %.3f）" % peak)
    tail = vals[-4:]
    sustained = min(tail)
    expect(sustained > 0.10,
           "速度没有掉回 0（末 4 秒最低 %.3f）→ 风解决了'只滑 2 秒'的问题" % sustained)
    v = p.speed()
    expect(v is not None and v[1] < 0, "往北（-Z）走：速度=(%.3f, %.3f)" % (v[0], v[1]))

    print("== 3. 背风必须不产生反向力（否则帆变成倒车挡，玩家看不懂）")
    p.settle(timeout=60)
    p("cww wind set south 2.0")
    leeward = p.curve(6)
    lv = [x for x in leeward if x is not None]
    print("      背风速率：%s" % ", ".join("%.3f" % x for x in lv))
    expect(max(lv or [0]) < 0.08,
           "朝北的帆遇到南风不产生推力（峰值 %.3f）" % (max(lv) if lv else 0))

    print("== 4. 无帆的筏不该被风推")
    p("cww wind set calm 0")
    p("cww sail strip 1")
    p.settle()
    p("cww wind set north 3.0")
    b = []
    for _ in range(6):
        time.sleep(1.0)
        b.append(p.rate(1))
    bv = [x for x in b if x is not None]
    print("      队1（无帆）速率：%s" % ", ".join("%.3f" % x for x in bv))
    expect(max(bv or [0]) < 0.08, "没挂帆的筏不受风力（峰值 %.3f）" % (max(bv) if bv else 0))

    print("== 5. 公平性：两队同一时刻必须是同一阵风")
    w0 = p.windline(0)
    w1 = p.windline(1)
    expect(w0 == w1, "队0 与 队1 读到同一阵风：%s vs %s" % (w0, w1))

    print("== 6. 风强越界要拒绝且不改状态")
    p("cww wind set calm 0")
    out = p("cww wind set north 9.9")
    # 9.9 会被 Brigadier 的参数范围直接拒（根本进不到 handler），所以这里断言的是
    # "命令失败 + 状态没变"，而不是某句具体文案。服务层自己的范围检查由单测覆盖。
    expect("must not be more than" in out or "必须在" in out,
           "越界风强被 Brigadier 拒绝：%s" % out[:140])
    wl = p.windline(0)
    expect(wl is not None and float(wl[2]) == 0.0, "被拒后状态未改动：%s" % (wl,))

    print("== 7. 清理")
    p("cww wind set calm 0")
    p("cww sail strip 0")
    p("cww abort")
    for bot, _ in BOTS:
        p("player %s kill" % bot)

    print("\n=== WIND PROBE SUMMARY ===")
    print("satisfied : %d" % len(ok))
    print("FAILED    : %d" % len(bad))
    for f in bad:
        print("  FAIL %s" % f)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
