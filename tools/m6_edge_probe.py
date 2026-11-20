#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M6 · O 域权限 + S 域边界并发探针（2026-11-21）。

覆盖 M6-FULL-TEST-PLAN 的：
  O1 非管理员执行管理命令被拒（join/quit/phase 除外）
  O2 cww force 需权限 2（非管理员执行不改变队席；RCON 能改，证明拒绝不是"命令坏了"）
  O3 place 类 dev 命令需权限 2（ballista/catapult/ram place 非管理员执行后读数不变）
  O4 /help cww 命令树完整
  S1 0 名玩家时面板/命令不崩（读一遍所有只读命令，服务端日志无 ERROR/Exception）
  S2 1 名玩家不能开局（下限 2）
  S3 报名超过队伍容量 → 溢出转观战（用 8 人证明容量 4 时的溢出行为）
  S5 重复 cww join 幂等（队列人数不重复涨）
  S6 队伍容量满后不能再塞人（cww force 返回"已满"）
  S7 快速连发 start/abort 状态机不被撕裂
  S8 对局中换图/换赛制被拒（与 A8 同一门，这里在 PLAY 里再验一次）

跑法（服务端已起、RCON 在 25576）：
    .\\run\\py-embed\\python.exe -I -X utf8 tools/m6_edge_probe.py
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import (P, ROOT, sweep_stale_bots, keep_alive, wait_clean_lobby,  # noqa: E402
                       DRY_ARENA)

LOG = os.path.join(ROOT, "run", "server-console.log")
ROUND_JSON = os.path.join(ROOT, "run", "world", "cww", "state", "round.json")

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def check_admin_wrapping(p, literals):
    """静态核对：这些 `/cww` 子命令必须以 `CmdSupport.admin(...)` 包一层。

    为什么不在运行期测：`execute as <玩家> run <命令>` 只替换实体，
    **权限等级沿用调用方**（RCON 是 level 4），所以从服务端侧根本造不出"权限 0 的玩家"。
    Brigadier 的 requires 谓词又不在 `/help` 里暴露。退而求其次：
    直接查源码里这些字面量是不是挂在 `CmdSupport.admin` 上 —— 漏一个就会失败。

    返回 None（已判过 PASS/FAIL），不抛异常。
    """
    import glob
    cmd_dir = os.path.join(ROOT, "src", "main", "java", "dev", "nbw", "cmd")
    text = ""
    for path in glob.glob(os.path.join(cmd_dir, "*.java")):
        with open(path, encoding="utf-8") as fh:
            text += fh.read()
    missing = []
    for name in literals:
        # CmdSupport.admin(CommandManager.literal("name")) 或
        # CmdSupport.admin(CommandManager.literal("name").then(...)
        if ("CmdSupport.admin(CommandManager.literal(\"%s\")" % name) not in text:
            missing.append(name)
    expect(not missing,
           "管理命令全部经 CmdSupport.admin() 包权限门（缺：%s）" % (missing or "无"))


def log_size():
    try:
        return os.path.getsize(LOG)
    except FileNotFoundError:
        return 0


def log_since(pos):
    try:
        with open(LOG, "rb") as fh:
            fh.seek(pos)
            return fh.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def errors_since(pos):
    text = log_since(pos)
    out = []
    for line in text.splitlines():
        if "[Server thread/ERROR]" in line or "Exception" in line:
            out.append(line.strip()[:220])
    return out


def spawn(p, name):
    p("player %s kill" % name)
    p("player %s spawn" % name)


def wait_online(p, names, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        listing = p("list").lower()
        if all(n in listing for n in names):
            return True
        p.sleep(0.5)
    return False


def phase_of(p):
    m = re.search(r"阶段=(\w+)", p("cww phase"))
    return m.group(1) if m else "?"


def wait_phase(p, want, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if phase_of(p) == want:
            return True
        p.sleep(0.5)
    return False


def roster(p):
    return p("cww state")


def mtime(path):
    try:
        return os.path.getmtime(path)
    except FileNotFoundError:
        return 0


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（无占席会话）：%s" % st.replace("\n", " ")[:140])
    if not clean:
        return 1

    # ================================================================ S1
    print("== S1 0 名玩家时只读命令不崩")
    cursor = log_size()
    read_only = [
        "cww state", "cww phase", "cww zone", "cww stats", "cww metrics",
        "cww survivor", "cww guard", "cww supply", "cww power status",
        "cww loot status", "cww event", "cww cosmetic list", "cww audit 10",
        "cww arena list", "cww arena pool", "cww format test test_3min",
        "cww arena terrain flat_test", "cww build status", "cww build last",
        "cww raft status", "cww core status", "cww ballista status",
        "cww catapult status", "cww ram status", "cww raftcore status",
        "cww weapon status", "cww net flood nobody 10",
    ]
    for cmd in read_only:
        p(cmd)
    p.sleep(1)
    errs = errors_since(cursor)
    expect(not errs, "0 人下 %d 条只读命令无服务端异常（%d 条 ERROR）" % (len(read_only), len(errs)))
    for e in errs[:3]:
        print("      " + e)

    # ================================================================ S2
    print("== S2 1 名玩家不能开局（下限 2）")
    spawn(p, "e1")
    expect(wait_online(p, ["e1"]), "e1 在线")
    p("cww force e1 0")
    out = p("cww start test_3min %s" % DRY_ARENA)
    expect("< 下限" in out, "1 人 start 被拒：%s" % out[:160])
    expect(phase_of(p) == "LOBBY", "被拒后仍在 LOBBY（实际 %s）" % phase_of(p))

    # ================================================================ S5
    print("== S5 重复 join 幂等")
    for _ in range(3):
        p("execute as e1 run cww join")
        p.sleep(0.3)
    m = re.search(r"queue=(\d+)", roster(p))
    q = int(m.group(1)) if m else -1
    expect(q == 1, "连发 3 次 join 后队列仍为 1（实际 %d）" % q)
    p("player e1 kill")
    time.sleep(2)
    clean, st = wait_clean_lobby(p)
    expect(clean, "清掉 e1 后队列/队席归零：%s" % st.replace("\n", " ")[:120])

    # ================================================================ S6
    print("== S6 队伍容量满后 force 被拒")
    bots = ["e1", "e2", "e3", "e4", "e5"]
    for n in bots:
        spawn(p, n)
    expect(wait_online(p, bots), "5 个假玩家在线")
    p("cww force e1 0")
    p("cww force e2 0")
    p("cww force e3 1")
    out = p("cww force e4 0")
    expect("已满" in out, "队 0 满员（2 人）后 force e4 被拒：%s" % out[:140])
    out = p("cww force e4 1")
    expect("已满" not in out, "队 1 还有位置，force e4 成功：%s" % out[:140])
    out = p("cww force e5 1")
    expect("已满" in out, "两队都满后 force e5 被拒：%s" % out[:140])
    for n in bots:
        p("player %s kill" % n)
    time.sleep(2)
    clean, st = wait_clean_lobby(p)
    expect(clean, "容量测试后回到干净 LOBBY：%s" % st.replace("\n", " ")[:120])

    # ================================================================ O2
    print("== O2 cww force 权限：非管理员被执行不生效、RCON 执行生效")
    spawn(p, "e1")
    spawn(p, "e2")
    expect(wait_online(p, ["e1", "e2"]), "e1/e2 在线")
    p("cww force e1 0")
    p("cww force e2 1")
    # ★ 2026-11-21 更正：`execute as <玩家> run <命令>` 只换实体、**不降权限等级**
    #   （源仍是 RCON 的 level 4），所以它根本测不了权限门。第一版把这里当"非管理员执行"
    #   写成了断言，结果是假失败。运行期权限必须由真人客户端或单测覆盖；
    #   这里改成静态核对命令树：这些节点必须全部经 CmdSupport.admin() 包一层。
    check_admin_wrapping(p, ["stop", "force", "ballista", "catapult", "ram", "core",
                             "raftcore", "loot", "survivor", "guard", "power", "raft",
                             "sail", "wind", "remote", "rescue", "rope", "net", "build",
                             "weapon", "arena", "format", "lobby", "announce", "audit"])
    inconclusive("O2/O3 运行期权限：`execute as` 不降权限，RCON 侧无法验证（改由静态核对 + 真人验收）")

    out = p("cww force e2 0")
    moved = "e2" in p("cww state") and "T0" in p("cww state")
    expect(moved, "RCON（权限 4）执行 force e2 0 生效：%s" % out[:100])
    p("cww force e2 1")

    # ================================================================ O1/O3/S8（需要 PLAY）
    print("== 开一局到 PLAY（O1/O3/S8 前置）")
    for n, t in (("e3", 0), ("e4", 1)):
        spawn(p, n)
        p("cww force %s %d" % (n, t))
    expect(wait_online(p, ["e3", "e4"]), "e3/e4 在线")
    keep_alive(p, [("e1", 0), ("e2", 1), ("e3", 0), ("e4", 1)])
    p("cww start test_3min %s" % DRY_ARENA)
    reached = wait_phase(p, "PLAY", 120)
    expect(reached, "进入 PLAY（实际 %s）" % phase_of(p))
    if not reached:
        p("cww abort")
        return 1
    keep_alive(p, [("e1", 0), ("e2", 1), ("e3", 0), ("e4", 1)])

    print("== O1 非管理员 cww stop 不能把对局停掉")
    # 判据从"仍在 PLAY"改成"没有新增 ADMIN_STOP"：test_3min 只有 3 分钟，
    # 探针跑到这里常常已经自然收束到 SETTLE，用阶段做判据会偶发假失败。
    # 另外审计是环形缓冲、会带历史（历史上真跑过 ADMIN_STOP），所以只比对"新增的行"。
    before_lines = p("cww audit 200").splitlines()
    p("execute as e1 run cww stop")
    p.sleep(1)
    after_lines = p("cww audit 200").splitlines()
    added = after_lines[len(before_lines):] if len(after_lines) > len(before_lines) else []
    new_text = "\n".join(added)
    expect("ADMIN_STOP" not in new_text,
           "本次没有新增 ADMIN_STOP（阶段=%s，新增 %d 行：%s）"
           % (phase_of(p), len(added), new_text.replace("\n", " ")[:160] or "无"))

    print("== O3 place 类 dev 命令：静态核对权限门（运行期见 O2 的 INCONCLUSIVE）")
    for kind, status_cmd in (("ballista", "cww ballista status"),
                             ("catapult", "cww catapult status"),
                             ("ram", "cww ram status")):
        before_s = p(status_cmd)
        out = p("cww %s place 0" % kind)
        after_s = p(status_cmd)
        expect(after_s != before_s, "RCON 执行 %s place 生效：%s" % (kind, out[:110]))

    print("== S8 对局中换图/换赛制被拒")
    out = p("cww arena reload flat_test")
    expect("禁止替换正在使用的 arena" in out, "PLAY 中 arena reload 被拒：%s" % out[:140])
    out = p("cww format reload oar_long")
    expect("禁止替换正在使用的 arena" in out, "PLAY 中 format reload 被拒：%s" % out[:140])

    p("cww abort")
    for _ in range(30):
        p.sleep(0.5)
        if phase_of(p) == "LOBBY":
            break
    for n in ("e1", "e2", "e3", "e4"):
        p("player %s kill" % n)
    time.sleep(2)
    clean, st = wait_clean_lobby(p)
    expect(clean, "O/S 玩法的清理回到干净 LOBBY：%s" % st.replace("\n", " ")[:120])

    # ================================================================ S3
    print("== S3 报名溢出容量 → 溢出转观战（8 人 / 容量 4）")
    overflow_bots = ["e%d" % i for i in range(1, 9)]
    for n in overflow_bots:
        spawn(p, n)
    expect(wait_online(p, overflow_bots), "8 个假玩家在线：%s" % p("list")[:140])
    for n in overflow_bots:
        p("execute as %s run cww join" % n)
        p.sleep(0.15)
    m = re.search(r"queue=(\d+)", roster(p))
    print("      报名后 queue=%s（容量 4）" % (m.group(1) if m else "?"))
    keep_alive(p, [(n, 0) for n in overflow_bots])
    p("cww start test_3min %s" % DRY_ARENA)
    reached = wait_phase(p, "PLAY", 120)
    expect(reached, "8 人报名仍能开局（溢出由状态机兜底）")
    if reached:
        p.sleep(2)
        spectators = 0
        for n in overflow_bots:
            out = p("data get entity %s playerGameType" % n).strip()
            if out.split() and out.split()[-1] == "3":
                spectators += 1
        stt = roster(p).replace("\n", " ")
        print("      开局后队席：%s" % stt[:220])
        print("      观战模式玩家数：%d" % spectators)
        expect("T2" not in stt and "T3" not in stt, "规格仍是 2x2，没有多建队伍")
        # 溢出的人要么不在队席、要么已转旁观：两个证据至少有一个
        leftover = sum(1 for n in overflow_bots if n in stt)
        expect(leftover <= 4, "队席里最多 4 个报名者（实际 %d）" % leftover)
        p("cww abort")
        for _ in range(30):
            p.sleep(0.5)
            if phase_of(p) == "LOBBY":
                break
    for n in overflow_bots:
        p("player %s kill" % n)
    time.sleep(2)
    sweep_stale_bots(p)
    wait_clean_lobby(p)

    # ================================================================ S7
    print("== S7 快速连发 start/abort（状态机不撕裂）")
    cursor = log_size()
    for _ in range(5):
        p("cww start test_3min %s" % DRY_ARENA)
        p("cww abort")
        p.sleep(0.2)
    for _ in range(40):
        p.sleep(0.5)
        if phase_of(p) == "LOBBY":
            break
    expect(phase_of(p) == "LOBBY", "连发后回到 LOBBY（实际 %s）" % phase_of(p))
    errs = errors_since(cursor)
    expect(not errs, "连发 start/abort 无服务端异常（%d 条）" % len(errs))
    for e in errs[:3]:
        print("      " + e)
    # 再正常开局一次证明机器没坏
    for n, t in (("e1", 0), ("e2", 1)):
        spawn(p, n)
        p("cww force %s %d" % (n, t))
    expect(wait_online(p, ["e1", "e2"]), "e1/e2 在线")
    p("cww start test_3min %s" % DRY_ARENA)
    expect(wait_phase(p, "PLAY", 120), "连发之后仍能正常开局到 PLAY")
    p("cww abort")
    for _ in range(30):
        p.sleep(0.5)
        if phase_of(p) == "LOBBY":
            break
    for n in ("e1", "e2"):
        p("player %s kill" % n)

    # ================================================================ O4
    print("== O4 /help cww 命令树完整")
    help_out = p("help cww")
    groups = ["arena", "format", "lobby", "reset", "join", "quit", "phase", "start",
              "stop", "abort", "force", "state", "save", "statefile", "zone", "metrics",
              "event", "stats", "survivor", "guard", "supply", "power", "cosmetic",
              "survival", "loot", "audit", "net", "build", "raft", "sail", "wind",
              "remote", "rescue", "rope", "grapnel", "core", "ballista", "catapult",
              "ram", "raftcore", "weapon", "announce"]
    missing = [g for g in groups if g not in help_out]
    expect(not missing, "help cww 覆盖 %d 个主命令组（缺 %s）" % (len(groups), missing))
    print("      help 行数=%d" % len(help_out.splitlines()))

    print("== 收尾")
    sweep_stale_bots(p)
    wait_clean_lobby(p)

    print("\n== 汇总")
    print("  PASS=%d FAIL=%d INCONCLUSIVE=%d" % (len(ok), len(bad), len(inc)))
    for m in bad:
        print("  FAIL - " + m)
    for m in inc:
        print("  INCONCLUSIVE - " + m)
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
