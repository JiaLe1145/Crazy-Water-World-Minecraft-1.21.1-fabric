#!/usr/bin/env python3
"""淘汰观战探针（M5-3 观战 UI 最小版，2026-11-14）。

要回答：
  1. 4 人开局（2×2）能进入 PLAY。
  2. 打死队 0 的一人后，服务端日志出现 `[round] spectator notice: <名>｜观战中｜存活队友：<队友>`。
  3. cww state 里该玩家带 `[淘汰]` 标记。

RCON 读不到玩家聊天/actionbar，所以证据取服务端自己的日志行；这是本项目
"瞬时表现读不到就断言过程之后留下的服务端记录"的同一口径。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, sweep_stale_bots, keep_alive, wait_clean_lobby, DRY_ARENA  # noqa: E402

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（%s）" % st.replace("\n", " ")[:120])
    if not clean:
        return 1

    tag = time.strftime("%M%S")
    bots = [("sp%s0" % tag, 0), ("sp%s1" % tag, 1), ("sp%s2" % tag, 0), ("sp%s3" % tag, 1)]
    for n, _ in bots:
        p("player %s kill" % n.lower())
        p("player %s spawn" % n.lower())
    for _ in range(30):
        listing = p("list").lower()
        if all(n.lower() in listing for n, _ in bots):
            break
        p.sleep(1)
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, [(n.lower(), t) for n, t in bots])
    p("cww start test_3min %s" % DRY_ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        return 1
    p.sleep(2)
    p.log()   # 丢掉开局日志，后面只读本轮新增段

    dead, mate = bots[0][0].lower(), bots[2][0].lower()
    print("== 1. 打死队 0 的一人（%s），看观战提示" % dead)
    p("damage %s 1000" % dead)
    p.sleep(2)
    lines = p.log()
    hit = "[round] spectator notice:" in lines and dead in lines and "存活队友" in lines and mate in lines
    expect(hit, "日志出现观战提示：dead=%s mate=%s（原始段 %d 字）" % (dead, mate, len(lines)))
    if not hit:
        print("      日志尾部：<%s>" % lines[-500:].replace("\n", "\n      <"))

    print("== 2. cww state 标记淘汰")
    st = p("cww state")
    expect(dead in st and "[淘汰]" in st, "cww state 中 %s 带 [淘汰] 标记" % dead)

    print("== 3. 存活队友仍在队伍里")
    expect(mate in st and (mate + "[淘汰]") not in st,
           "队友 %s 未被误标淘汰" % mate)

    print("== 4. 收尾")
    p("cww abort")
    p.sleep(2)
    for n, _ in bots:
        p("player %s kill" % n.lower())
    print("  清残留：" + repr(sweep_stale_bots(p)))

    print("\n== 汇总")
    print("  satisfied=%d" % len(ok))
    for x in bad:
        print("  FAIL - %s" % x)
    for x in inc:
        print("  INCONCLUSIVE - %s" % x)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
