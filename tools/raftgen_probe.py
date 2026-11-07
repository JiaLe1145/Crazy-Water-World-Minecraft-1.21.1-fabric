"""开局发筏真机探针（M2-2）。

重点不是"筏子生成了"，而是**票与筏都不许残留**：
实测过 Sable 的加载票会跨重启存活（M2-RESULTS §4），漏收一次就把筏子永久钉在内存里，
而 plot 网格只有 16384 槽位，长期服漏几十局就开始生成失败。
所以清理断言比生成断言更重要。

用法：python tools/raftgen_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(ROOT, "run", "server-console.log")
BOTS = (("g0", 0), ("g1", 0), ("g2", 1), ("g3", 1))
DEFAULT_DECK = 16          # 初始筏削弱到 4×4（limits.initialRaftSide，缺省 RaftSpawner.DEFAULT_SIDE=4）
DECK_MASS = 9.0            # 16 格 oak_planks ×0.5 + 筏心 1.0（筏心是完整方块，默认 1.0）


class P:
    def __init__(self):
        self.sock = rcon.connect()
        self.logpos = os.path.getsize(LOG) if os.path.exists(LOG) else 0
        self.logbuf = ""

    def cmd(self, text, quiet=True):
        out = rcon.run(self.sock, text) or ""
        if not quiet:
            print("  > %s\n      %s" % (text, out.replace("\n", "\n      ")[:300]))
        return out.strip()

    def __call__(self, text, **kw):
        return self.cmd(text, **kw)

    def log(self):
        """累积式读日志。

        只返回"新增段"会推进偏移量，于是先调用的一步把内容消费掉、后一步再找同一段就没了
        ——实测就是把"生成日志在案"搞成了假失败。
        """
        size = os.path.getsize(LOG)
        with open(LOG, "rb") as fh:
            fh.seek(self.logpos)
            raw = fh.read()
        self.logpos = size
        self.logbuf += raw.decode("utf-8", errors="replace")
        if len(self.logbuf) > 400000:
            self.logbuf = self.logbuf[-300000:]
        return self.logbuf

    def phase(self):
        return self.cmd("cww phase")

    def wait_phase(self, want, timeout=90):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if want in self.phase():
                return True
            time.sleep(1.0)
        return False


def raft_name(p, team):
    """当前对局的筏体唯一名 nbw_raft_<队>_<局>（#38 唯一名）。"""
    m = re.search(r"match=(\d+)", p("cww state"))
    match = int(m.group(1)) if m else 0
    return "nbw_raft_%d_%d" % (team, match)


def sweep_raft_residue(p):
    """移除所有 nbw_raft_ 前缀的残留筏（崩溃残留名是 nbw_raft_<队>_<旧局>，按前缀扫）。"""
    info = p("sable info @e", quiet=True)
    removed = []
    for name in sorted(set(re.findall(r"nbw_raft_\d+_\d+", info))):
        p("sable remove @e[name=%s]" % name, quiet=True)
        removed.append(name)
    return removed


def main():
    p = P()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    print("== 0. 清场")
    # abort 是异步的：它要走过 SETTLE→RETURN 才回到 LOBBY。
    # 发完就往下走的话，cww start 会被"对局进行中"拒掉，于是整局开不起来，
    # 后面十几条断言一起红 —— 那是夹具时序，不是产品缺陷（本轮真的这样红过一次）。
    p("cww abort")
    for _ in range(60):
        ph = p.cmd("cww phase")
        if "LOBBY" in ph or "IDLE" in ph:
            break
        time.sleep(1)
    else:
        print("    ⚠ 遗留对局 60 秒还没收干净：%s —— 后面的读数可能全是假的" % ph[:60])
    for bot, _ in BOTS:
        p("player %s kill" % bot)
    print("      清残留筏：" + repr(sweep_raft_residue(p)))
    p.log()   # 丢弃历史日志，后面的断言只看这一段

    print("== 1. 起一局，READY 应当自动发筏")
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

    print("== 2. 生成结果应当符合 arena 配置（边长 4 → 16 格 → 质量 9.0）")
    status = p("cww raft all")
    print("      %s" % status[:260])
    expect(status.count("质量=9.0") == 2,
           "两队都是 16 甲板格(8.0) + 1 筏心(1.0) = 9.0（实际：%s）" % status[:160])
    ys = [float(m) for m in re.findall(r"Y=([\d.-]+)", status)]
    expect(len(ys) == 2, "读到两艘筏的 Y：%s" % ys)
    expect(all(abs(y - 150.0) < 6.0 for y in ys),
           "两艘筏都落在出生锚点水面附近（flat_test 水面 149，实际 %s）" % ys)

    print("== 3. 命名与加载票")
    info = p("sable info @e")
    expect(raft_name(p, 0) in info and raft_name(p, 1) in info,
           "两艘筏按 nbw_raft_<队>_<局> 唯一名可查：%s" % info[:150])
    tickets = p("sable forceload query")
    expect("2 force-loading ticket" in tickets or "for 2 sub-level" in tickets,
           "上了 2 张加载票：%s" % tickets[:170])
    expect("nbw:round_active" in tickets,
           "票是我们自己的类型而不是蹭 sable:command_forced：%s" % tickets[:200])

    print("== 4. 玩家应当在筏子上而不是水里")
    for bot, _ in BOTS:
        pos = p.cmd("data get entity %s Pos" % bot)
        m = re.search(r"\[([\d.-]+)d?,\s*([\d.-]+)d?,\s*([\d.-]+)d?\]", pos)
        if m:
            y = float(m.group(2))
            expect(149.0 <= y <= 156.0, "%s 站在筏面高度（Y=%.2f）" % (bot, y))

    print("== 4b. 自定义 BE 在 sub-level plot 里到底会不会被 tick")
    # ServerLevelPlot.tick() 只跑光照与 contraption 清理，不 tick BE。
    # 唯一入口是实现 Sable 的 BlockEntitySubLevelActor，由 ServerSubLevel 的 actor 循环调用。
    # 这条断言是整个筏心方块设计的成立前提，不是锦上添花。
    ticklog = p.log()
    n = ticklog.count("筏心登记（BE 已被 Sable tick）")
    expect(n == 2, "两个筏心的 BE 都被 Sable tick 到了（实际 %d 条）" % n)

    print("== 5. 收局：票与筏都不许残留")
    p("cww abort")
    time.sleep(4.0)
    after = p("sable info @e")
    expect("No sub-levels found" in after, "筏已销毁，内存里没有残留：%s" % after[:120])
    tickets_after = p("sable forceload query")
    expect("0 force-loading ticket" in tickets_after or "No force-loading" in tickets_after
           or "none" in tickets_after.lower(),
           "加载票已全部释放：%s" % tickets_after[:170])

    tail = p.log()
    expect("[S10] residue=CLEAN" in tail, "S10 残留复检报 CLEAN（含筏体与票两项）")
    expect("[S10] residue=FAIL" not in tail, "没有残留 FAIL")
    expect("[raftgen] 生成 2 艘初始筏" in tail, "生成日志在案")
    leak = [l for l in tail.splitlines() if "清理有残留" in l or "未能释放" in l]
    expect(not leak, "无清理残留告警：%s" % leak[:2])

    print("\n=== RAFTGEN SUMMARY === satisfied=%d FAILED=%d" % (len(ok), len(bad)))
    for f in bad:
        print("  FAIL %s" % f)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
