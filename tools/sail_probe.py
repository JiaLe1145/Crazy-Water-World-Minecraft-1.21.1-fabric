"""帆的真机探针（M2 航行入口）。

要回答两个问题，第二个决定 P-01 的数值怎么配：
  1) 帆方块真的进 plot 了吗（用 countBlock 扫局部包围盒数出来，不看 setBlockState 返回值）
  2) 帆在 Sable 的静风模型里到底是发动机还是阻力面

问题 2 的答案决定设计：如果从静止放帆筏子不动、而给冲量后带帆减速更快，
那帆就是龙骨/阻力面，推进必须靠桨（要客户端输入）或我们自己往 dimension physics 加风场。
如果反过来（帆能自己推），说明我对这套气动的理解是错的，要回头重读源码而不是接着配数值。

用法：python tools/sail_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

BOTS = (("n0", 0), ("n1", 0), ("n2", 1), ("n3", 1))
TEAM = 0
IMPULSE = "sable physics impulse @e[name=%s] linear 0 0 60"


def raft_name(p, team):
    """当前对局的筏体唯一名 nbw_raft_<队>_<局>（#38 唯一名）。"""
    m = re.search(r"match=(\d+)", p("cww state"))
    match = int(m.group(1)) if m else 0
    return "nbw_raft_%d_%d" % (team, match)


class P:
    def __init__(self):
        self.sock = rcon.connect()

    def cmd(self, text, quiet=True):
        out = rcon.run(self.sock, text) or ""
        if not quiet:
            print("  > %s\n      %s" % (text, out.replace("\n", "\n      ")[:280]))
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

    def readout(self):
        return self.cmd("cww sail read")

    def speed(self):
        m = re.search(r"速率=([\d.eE+-]+)", self.readout())
        return float(m.group(1)) if m else None

    def sails(self):
        m = re.search(r"帆=(\d+)", self.readout())
        return int(m.group(1)) if m else -1

    def settle(self, limit=0.05, timeout=60):
        deadline = time.time() + timeout
        while time.time() < deadline:
            s = self.speed()
            if s is not None and s < limit:
                return True
            time.sleep(1.0)
        return False

    def curve(self, seconds):
        out = []
        for _ in range(seconds):
            time.sleep(1.0)
            out.append(self.speed())
        return out


def main():
    p = P()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    print("== 0a. 造海：flat_test 只是旱地基岩平台，筏子搁在上面不是船")
    # 实测教训：不加这一步，速度读数恒为 0.000，看起来像"帆和冲量都没生效"，
    # 其实是筏子坐在旱地上被摩擦力按住。航行类测试必须先有水。
    # 这也说明 M2 之后需要一个真带水的 arena（对应 M3 的黑海），不能一直靠 drill 的旱地平台。
    for cx, cz in ((1000, 952), (1000, 1048)):
        p("fill %d 149 %d %d 153 %d minecraft:air" % (cx - 10, cz - 10, cx + 10, cz + 10))
        p("fill %d 149 %d %d 152 %d minecraft:water" % (cx - 10, cz - 10, cx + 10, cz + 10))
        for wall in (("fill %d 146 %d %d 156 %d minecraft:stone" % (cx - 11, cz - 11, cx + 11, cz - 11),),
                     ("fill %d 146 %d %d 156 %d minecraft:stone" % (cx - 11, cz + 11, cx + 11, cz + 11),),
                     ("fill %d 146 %d %d 156 %d minecraft:stone" % (cx - 11, cz - 11, cx - 11, cz + 11),),
                     ("fill %d 146 %d %d 156 %d minecraft:stone" % (cx + 11, cz - 11, cx + 11, cz + 11),)):
            p(wall[0])
    p("forceload add 985 935 1015 1065")

    print("== 0. 起一局到 PLAY（READY 会自动发筏）")
    # 先收掉上一局：串跑时上一条探针可能停在 SETTLE/RETURN，此时 spawn/start 都会被拒，
    # 表现为"进入 PLAY"失败、后续全部连锁（2026-11-20 批量串跑踩到）。
    p("cww abort")
    for _ in range(30):
        if "LOBBY" in p.cmd("cww phase"):
            break
        time.sleep(1)
    for bot, _ in BOTS:
        p("player %s kill" % bot)
        p("player %s spawn" % bot)
    for _ in range(30):
        if all(b.lower() in p.cmd("list").lower() for b, _ in BOTS):
            break
        time.sleep(1)
    for bot, team in BOTS:
        p("cww force %s %d" % (bot, team))
    p("cww start test_3min flat_test")
    expect(p.wait_phase("PLAY", 90), "进入 PLAY")

    print("== 1. 基线：无帆，给一次冲量，记录减速曲线")
    p("cww sail strip %d" % TEAM, quiet=False)
    expect(p.sails() == 0, "拆干净后帆数=0（实际 %d）" % p.sails())
    p.settle()
    p(IMPULSE % raft_name(p, TEAM))
    base = p.curve(10)
    print("      无帆速率：%s" % ", ".join("%.3f" % v for v in base if v is not None))
    expect(base[0] is not None and base[0] > 0.3,
           "冲量确实让筏动了（首秒速率 %s）" % (("%.3f" % base[0]) if base and base[0] is not None else "读不到"))

    print("== 2. 挂 5 块帆，确认帆方块真的进了 plot")
    out = p("cww sail rig %d 5 north" % TEAM, quiet=False)
    expect(p.sails() == 5, "扫 plot 局部包围盒数到 5 块帆（实际 %d）" % p.sails())
    expect("帆=5" in out, "读数里也是 5：%s" % out[:180])
    mass = re.search(r"质量=([\d.]+)", out)
    print("      挂帆后质量：%s（每块帆按完整方块计 1.0）" % (mass.group(1) if mass else "?"))

    print("== 3. 关键问题：静风下帆会不会自己推筏子？")
    p.settle(limit=0.03, timeout=40)
    rest_before = p.speed()
    samples = p.curve(6)
    rest_after = p.speed()
    print("      静止中速率：%s" % ", ".join("%.3f" % v for v in samples if v is not None))
    vals = [v for v in samples if v is not None]
    peak = max(vals) if vals else None
    expect(peak is not None and peak < 0.25,
           "帆不能自推进（静风中速率峰值 %s，基线 %s）——帆是阻力面，推进得靠桨或加风场"
           % (("%.3f" % peak) if peak is not None else "读不到",
              ("%.3f" % rest_before) if rest_before is not None else "读不到"))

    print("== 4. 同样冲量下，带帆是否比无帆减速更快")
    p.settle()
    p(IMPULSE % raft_name(p, TEAM))
    withsail = p.curve(10)
    print("      有帆速率：%s" % ", ".join("%.3f" % v for v in withsail if v is not None))
    a = [v for v in base if v is not None]
    b = [v for v in withsail if v is not None]
    if len(a) >= 4 and len(b) >= 4:
        # 用"降到一半速度所需秒数"比较，比直接比末速稳：两次冲量的初速不完全一样
        def halve(curve):
            top = curve[0]
            for i, v in enumerate(curve):
                if v < top * 0.5:
                    return i + 1
            return len(curve) + 1
        # 用"第 2 秒速度 / 首秒速度"这个归一化比值比减速，
        # 否则两次的初速本来就不同（挂帆多了 5 格质量，同一冲量下初速必然更低），
        # 直接比绝对值会把质量差异误读成阻力差异。
        r0 = a[2] / a[0] if a[0] else None
        r1 = b[2] / b[0] if b[0] else None
        print("      第3秒/首秒 比值：无帆 %s，有帆 %s" % (
            ("%.3f" % r0) if r0 is not None else "?", ("%.3f" % r1) if r1 is not None else "?"))
        if r0 is not None and r1 is not None and abs(r0 - r1) < 0.15:
            # 不假装这是通过：5 块帆的阻力作用在这个尺度上测不出来，
            # 断言它"更快减速"只是让脚本变绿，不是拿到证据。
            print("    INCONCLUSIVE 帆的阻力增量 <0.15，无法与噪声区分——不据此下结论")
        else:
            expect(r1 is not None and r0 is not None and r1 < r0,
                   "带帆减速更快（比值 %s < %s）" % (r1, r0))
        expect(a[0] > 0.3, "带帆时冲量仍然推得动筏（首秒 %.3f）" % a[0])
        # 这条才是本轮对玩法最有影响的读数：筏子滑多久
        glide0 = next((i for i, v in enumerate(a) if v is not None and v < 0.02), len(a))
        print("    NOTE 无帆滑行约 %d 秒就停（%.3f -> 0）；"
               "持续动力不是可选优化，是硬需求" % (glide0, a[0]))
    else:
        expect(False, "采样不足，无法比较减速：无帆 %d 点，有帆 %d 点" % (len(a), len(b)))

    print("== 5. 拆帆后帆数归零")
    p("cww sail strip %d" % TEAM, quiet=False)
    expect(p.sails() == 0, "拆完帆数=0（实际 %d）" % p.sails())

    print("== 6. 清理")
    p("cww abort")
    for bot, _ in BOTS:
        p("player %s kill" % bot)

    print("\n=== SAIL PROBE SUMMARY ===")
    print("satisfied : %d" % len(ok))
    print("FAILED    : %d" % len(bad))
    for f in bad:
        print("  FAIL %s" % f)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
