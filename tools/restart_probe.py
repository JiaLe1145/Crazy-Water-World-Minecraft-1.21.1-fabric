"""重启恢复真机探针（Gate G1 最后一条：重启能恢复到当前阶段）。

分两段跑，因为重启服务端这件事必须由外部做：
    python tools/restart_probe.py before    # 开一局到 PLAY，落盘，停服
    <重启 runServer>
    python tools/restart_probe.py after     # 读回状态并断言与落盘前一致

before 段会把关键读数写进 run/restart_handoff.json，after 段拿它做逐字段比对——
只说"看起来恢复了"不算证据。
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HANDOFF = os.path.join(ROOT, "run", "restart_handoff.json")
BOTS = (("k0", 0), ("k1", 0), ("k2", 1), ("k3", 1))


def connect():
    for _ in range(60):
        try:
            s = rcon.connect()
            if "players online" in (rcon.run(s, "list") or ""):
                return s
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("连不上 rcon")


def cmd(sock, text, quiet=True):
    out = rcon.run(sock, text) or ""
    if not quiet:
        print("  > %s\n      %s" % (text, out.replace("\n", "\n      ")[:280]))
    return out.strip()


def read_state(sock):
    """从 /cww state 里抠出关键读数。"""
    raw = cmd(sock, "cww state")
    def grab(key):
        m = re.search(key + r"=(\S+)", raw)
        return m.group(1).rstrip('[') if m else None
    return {
        "phase": grab("phase"),
        "match": grab("match"),
        "tickInPhase": grab("tickInPhase"),
        "totalTicks": grab("totalTicks"),
        "arena": grab("arena"),
        "format": grab("format"),
        "raw": raw,
    }


def before():
    sock = connect()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    print("== before: 起一局到 PLAY")
    cmd(sock, "cww abort")
    for bot, _ in BOTS:
        cmd(sock, "player %s kill" % bot)
    for bot, _ in BOTS:
        cmd(sock, "player %s spawn" % bot)
    for _ in range(30):
        if all(b.lower() in cmd(sock, "list").lower() for b, _ in BOTS):
            break
        time.sleep(1)
    # flat_test 现在是有水的测试池：假玩家会沉水并在 15s 后溺水，必须补水下呼吸。
    for bot, _ in BOTS:
        cmd(sock, "effect give %s minecraft:water_breathing infinite 0 true" % bot)
    for bot, team in BOTS:
        cmd(sock, "cww force %s %d" % (bot, team))
    cmd(sock, "cww start test_3min flat_test")
    deadline = time.time() + 90
    while time.time() < deadline and "PLAY" not in cmd(sock, "cww phase"):
        time.sleep(1)

    st = read_state(sock)
    print("      落盘前：%s" % st["raw"][:220])
    expect(st["phase"] == "PLAY", "已到 PLAY（实际 %s）" % st["phase"])

    print("== before: 手动落盘并确认文件可读")
    out = cmd(sock, "cww save", quiet=False)
    expect("round.json" in out, "落盘指向了 round.json：%s" % out[:180])
    expect("phase=PLAY" in out, "盘上读回来也是 PLAY：%s" % out[:180])

    with open(HANDOFF, "w", encoding="utf-8") as fh:
        json.dump({"state": st, "expectRestored": True}, fh, ensure_ascii=False, indent=2)
    print("      交接数据写入 %s" % HANDOFF)

    print("== before: 停服")
    cmd(sock, "stop")
    time.sleep(8)

    print("\n=== BEFORE SUMMARY === satisfied=%d FAILED=%d" % (len(ok), len(bad)))
    for f in bad:
        print("  FAIL %s" % f)
    return 0 if not bad else 1


def after():
    sock = connect()
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    with open(HANDOFF, encoding="utf-8") as fh:
        hand = json.load(fh)
    want = hand["state"]

    print("== after: 服务端启动日志应当已自述恢复结果")
    out = cmd(sock, "cww statefile", quiet=False)
    expect("phase=PLAY" in out, "盘上快照仍是 PLAY：%s" % out[:200])

    st = read_state(sock)
    print("      重启后：%s" % st["raw"][:260])
    expect(st["phase"] == want["phase"],
           "阶段恢复到 %s（实际 %s）" % (want["phase"], st["phase"]))
    expect(st["match"] == want["match"],
           "局号连续：%s（实际 %s）" % (want["match"], st["match"]))
    expect(st["arena"] == want["arena"] and st["format"] == want["format"],
           "arena/format 与落盘前一致（实际 %s/%s）" % (st["arena"], st["format"]))
    # tickInPhase 允许继续往前走，但不能倒退或归零
    before_tick = int(want["tickInPhase"])
    after_tick = int(st["tickInPhase"])
    expect(after_tick >= before_tick,
           "阶段内 tick 没倒退：%d -> %d" % (before_tick, after_tick))
    expect(after_tick - before_tick < 6000,
           "阶段内 tick 也没莫名跳一大截（差 %d）" % (after_tick - before_tick))

    print("== after: 玩法子系统也必须恢复（2026-11-21 修复的半死局）")
    # 筏子可能比状态机晚载入；先等重认领，再断言幸存者/帆等依赖筏的登记。
    raft_all = ""
    for _ in range(30):
        raft_all = cmd(sock, "cww raft all")
        if "队0" in raft_all and "还没生成" not in raft_all:
            break
        time.sleep(1)
    expect("队0" in raft_all, "重启后筏已重新认领：%s" % raft_all[:140])
    expect("未初始化" not in cmd(sock, "cww zone"), "圈层已恢复：%s" % cmd(sock, "cww zone")[:100])
    expect("没有电量池" not in cmd(sock, "cww power status"),
           "电量池已恢复：%s" % cmd(sock, "cww power status")[:100])
    expect("没有事件池" not in cmd(sock, "cww event"), "事件池已恢复：%s" % cmd(sock, "cww event")[:100])
    expect("未开局" not in cmd(sock, "cww metrics"), "埋点已恢复：%s" % cmd(sock, "cww metrics")[:100])
    survivor = cmd(sock, "cww survivor")
    expect("本局没有幸存者" not in survivor and "幸存者=0" not in survivor,
           "幸存者已恢复：%s" % survivor[:140])

    print("== after: 恢复出来的机器还得能继续跑完")
    cmd(sock, "cww audit 6", quiet=False)
    cmd(sock, "cww abort")
    # 不要用固定 sleep：abort 是"下一 tick 生效"，1.5s 在带 4 个假玩家的恢复局里偶发不够，
    # 会读到过渡前的 PLAY（2026-11-19 实测踩到）。改成轮询，最多 10s。
    lobby = False
    for _ in range(20):
        time.sleep(0.5)
        if "LOBBY" in cmd(sock, "cww phase"):
            lobby = True
            break
    expect(lobby, "abort 仍能把恢复出来的局收干净")

    print("\n=== AFTER SUMMARY === satisfied=%d FAILED=%d" % (len(ok), len(bad)))
    for f in bad:
        print("  FAIL %s" % f)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    if os.path.exists(HANDOFF):
        os.remove(HANDOFF)
    return 0 if not bad else 1


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "before"
    sys.exit(before() if mode == "before" else after())
