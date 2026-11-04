"""筏心残骸修复真机探针（01 §4.3.2 A2 + A2-v2）。

全程自主测试，不需要真人：`/cww raftcore hold` 只替换"客户端长按"这个输入源，
服务端推进、打断、额度、倒计时解除走的都是与客户端接入后完全相同的那条路径。

分两局跑：SUNK 是终态、每局修复额度只有 1 次（沿用 A3），
在同一局里连测会互相吃掉前提，所以"打断类"和"成功类"各用一局干净对局。

用法：python tools/core_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

# 每轮跑用全新的假玩家名字。
# 原因（实测踩过）：Carpet 的 `player <名> kill` + `spawn` 会造出一个**新 UUID** 的同名假玩家，
# 而引导绑的是 UUID。复用名字会让上一轮的旧 UUID 残留在绑定里，
# getPlayer(旧UUID) 永远返回 null，表现为"进度卡在 19/160 每秒重来"，看起来像产品 bug。
RUN = time.strftime("%M%S")
BOTS = (("h%s0" % RUN, 0), ("h%s1" % RUN, 0), ("h%s2" % RUN, 1), ("h%s3" % RUN, 1))
TEAM = 0
CHANNEL_TICKS = 160


class P:
    def __init__(self):
        self.sock = rcon.connect()

    def cmd(self, text, quiet=True):
        out = rcon.run(self.sock, text) or ""
        if not quiet:
            print("  > %s\n      %s" % (text, out.replace("\n", "\n      ")[:260]))
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

    def core(self):
        return self.cmd("cww raftcore status")

    def sink(self):
        return self.cmd("cww raft status")

    def progress(self):
        m = re.search(r"引导 (\d+)/%d" % CHANNEL_TICKS, self.core())
        return int(m.group(1)) if m else 0

    def core_phase(self):
        m = re.search(r"队%d=(\w+)" % TEAM, self.core())
        return m.group(1) if m else "?"

    def syncs(self):
        """相位→方块状态的回写次数。读不到就当 0，让断言去吵。"""
        m = re.search(r"状态回写 (\d+) 次", self.core())
        return int(m.group(1)) if m else 0

    def sink_phase(self):
        m = re.search(r"队%d=(\w+)" % TEAM, self.sink())
        return m.group(1) if m else "?"


CH = ""   # 第 3b 步之后换用的干净引导者名字


def main():
    global CH
    p = P()
    CH = "h%s9" % time.strftime("%M%S")
    LEAD = BOTS[0][0]   # 第 2、3 步的引导者；3b 之后换成没挨过打的 CH
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    def start_match(label):
        p("cww abort")
        time.sleep(1.0)
        # 每局都要重新拉起假玩家：上一局的 WINNER_DECIDED 是靠 kill 掉一队达成的，
        # 而 kill 掉的 Carpet 假玩家不会自己回来。少了这一步，第二局的 cww force
        # 会静默失败、start 因"报名不足"被拒，然后所有断言都变成假失败。
        # 先 kill 再 spawn：上一局 WINNER_DECIDED 靠 kill 达成、而第 3 步的伤害也可能把引导者打死，
        # 处在重生界面的假玩家不会自己回来，player spawn 对它是无效的，
        # 于是引导每 tick 被 isAlive()=false 打断（实测进度反复停在 19/160）。
        for bot, _ in BOTS:
            p("player %s kill" % bot)
        time.sleep(1.0)
        for bot, _ in BOTS:
            p("player %s spawn" % bot)
        for _ in range(30):
            if all(b.lower() in p.cmd("list").lower() for b, _ in BOTS):
                break
            time.sleep(1)
        for bot, team in BOTS:
            p("cww force %s %d" % (bot, team))
        p("cww start test_3min flat_test")
        expect(p.wait_phase("PLAY", 90), "%s：进入 PLAY" % label)

    print("== 0. 起 4 个假玩家")
    for bot, _ in BOTS:
        p("player %s kill" % bot)
    for bot, _ in BOTS:
        p("player %s spawn" % bot)
    for _ in range(30):
        if all(b.lower() in p.cmd("list").lower() for b, _ in BOTS):
            break
        time.sleep(1)
    expect(all(b.lower() in p.cmd("list").lower() for b, _ in BOTS), "4 个假玩家在线")

    print("\n########## 第 1 局：触发 / 松手归零 / 受伤打断 ##########")
    start_match("第1局")

    print("== 1. 摧毁筏心 → 30s 沉筏倒计时必须开始（A2 第二条触发路径）")
    sync_before = p.syncs()
    p("cww raftcore destroy %d" % TEAM)
    expect(p.core_phase() == "WRECKAGE", "筏心进入残骸态（实际 %s）" % p.core_phase())
    time.sleep(1.5)
    expect(p.syncs() > sync_before,
           "服务端相位已回写进方块状态（%d -> %d）；不回写就会出现倒计时在走、筏心看着完好" %
           (sync_before, p.syncs()))
    expect(p.sink_phase() == "COUNTDOWN", "倒计时已开始（实际 %s）" % p.sink_phase())

    print("== 2. 按住 80 tick 后松手 → 进度先涨、再归零")
    p(("cww raftcore hold " + LEAD + " %d 80") % TEAM, quiet=False)
    time.sleep(3.0)
    mid = p.progress()
    print("      t+3s 进度 = %d" % mid)
    expect(20 <= mid <= 80, "进度确实在涨（t+3s = %d）" % mid)
    expect(p.core_phase() == "WRECKAGE", "80 tick 不足以修好")
    time.sleep(3.0)
    expect(p.progress() == 0, "松手 3 秒后进度归零（实际 %d）" % p.progress())

    print("== 3. 一边引导一边受伤 → 永远修不好（用户点名的打断条件）")
    # 伤害只给 1.0：打断只需要"受了伤害"。给 4.0 会把假玩家打死，
    # 打死之后走的是 isAlive=false 那条分支，就证明不了"伤害"打断本身了。
    p(("cww raftcore hold " + LEAD + " %d 400") % TEAM, quiet=False)
    time.sleep(1.0)
    # 0.5 伤害 × 8：够打断，但不会把引导者打死。
    # 打死也是合法打断（死玩家当然不能修东西），但那会顺带把"人没了"和"受伤"两件事混在一起，
    # 后面几步还会因为找不到尸体而假失败——实测就是这样绕了一圈才分清是探针的锅。
    print("      /damage 回包：%s" % p.cmd("damage " + LEAD + " 0.5 generic")[:110])
    peak = 0
    for _ in range(8):
        time.sleep(0.6)
        peak = max(peak, p.progress())
        p.cmd("damage " + LEAD + " 0.5 generic")
    print("      挨打期间的进度峰值 = %d" % peak)
    expect(0 < peak < CHANNEL_TICKS, "进度在走但从未完成（峰值 %d，%d 才够）" % (peak, CHANNEL_TICKS))
    expect(p.core_phase() == "WRECKAGE", "挨打期间筏心仍是残骸")
    expect("已用额度 0/1" in p.core(), "被打断不消耗每局额度：%s" % p.core()[:130])

    print("\n########## 第 2 局：连续引导成功 + 额度耗尽 ##########")
    # 换局的原因：第 1 局里倒计时可能已经走完到 SUNK，而 SUNK 不可逆，
    # "恢复解除倒计时"这条断言在 SUNK 之后永远测不出来。
    for bot, _ in BOTS:
        p("player %s kill" % bot)
        p("player %s spawn" % bot)
    time.sleep(3.0)
    start_match("第2局")
    p("cww raftcore destroy %d" % TEAM)
    time.sleep(1.0)
    expect(p.sink_phase() == "COUNTDOWN", "第2局倒计时开始（实际 %s）" % p.sink_phase())

    print("== 3b. 换一个干净的引导者")
    # 不 kill+spawn（那会换 UUID）、也不复用被打残的人：用一个本局全新的名字
    p("player " + CH + " spawn")
    time.sleep(3.0)
    expect(CH.lower() in p.cmd("list").lower(), CH + " 在线，可以当引导者")

    print("== 4. 一次连续引导 → 筏心恢复 + 倒计时解除（不是重置）")
    p(("cww raftcore hold " + CH + " %d 200") % TEAM, quiet=False)
    restored = False
    for _ in range(12):
        time.sleep(1.0)
        if p.core_phase() == "INTACT":
            restored = True
            break
    expect(restored, "连续引导 160 tick 后筏心恢复（当前 %s）" % p.core_phase())
    expect(p.sink_phase() == "IDLE", "倒计时被解除而不是重置（实际 %s）" % p.sink_phase())
    expect("已用额度 1/1" in p.core(), "恢复成功消耗掉那一次额度：%s" % p.core()[:130])

    print("== 5. 额度用完后再摧毁 → 修不了，且命令明确告知")
    p("cww raftcore destroy %d" % TEAM)
    time.sleep(1.0)
    out = p(("cww raftcore hold " + CH + " %d 160") % TEAM, quiet=False)
    print("      第二次修复尝试：%s" % out[:160])
    expect(p.core_phase() == "WRECKAGE", "额度用完，仍是残骸")
    expect("额度已用完" in out, "命令明确告知额度用完，不是静默失败")

    print("== 6. 清理")
    p("cww raftcore status", quiet=False)
    p("cww raft status", quiet=False)
    p("cww abort")
    for bot, _ in BOTS:
        p("player %s kill" % bot)

    print("\n=== CORE PROBE SUMMARY ===")
    print("satisfied : %d" % len(ok))
    print("FAILED    : %d" % len(bad))
    for f in bad:
        print("  FAIL %s" % f)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
