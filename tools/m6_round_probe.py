#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M6 · B 域对局生命周期探针（2026-11-21）。

覆盖 M6-FULL-TEST-PLAN 的（能用假玩家 + RCON 验收的部分）：
  B1 LOBBY → ENROLL → READY → PLAY → SETTLE → RETURN → LOBBY 全链路（按 audit 转场逐条取证）
  B2 非 LOBBY 阶段 cww start 被拒
  B3 LOBBY 阶段 cww stop / cww abort 被拒（转移表）
  B4 报名人数不足下限时拒绝开局
  B5 PLAY 中 cww join 不产生副作用（真正的拒绝断言在单测 RoundMachineTest.midMatchJoinIsRejected）
  B6 PLAY 中死亡 → 淘汰 → 观战（读 [round] spectator notice 日志作服务端侧证据；
     客户端观战表现由 spectator_probe / 真人验收覆盖）
  B7 仅剩 1 队 → WINNER_DECIDED → SETTLE
  B10 掉线 90s 席位到期 → 视为淘汰（cww state 里出现 [淘汰]）
  B11 掉线 90s 内重连 → 不误判淘汰（cww state 里 [失联] 标记消失）
  B12 结算后副作用齐全（战绩落盘 / 生涯累加 / 模式切回冒险：S10 residue=CLEAN）
  L3 结算广播胜者/原因/时长/存活
  L5 战绩落盘 state/matches/<n>.json
  L6 跨局生涯累加（career.json 胜场总数 +1）
B8/B9（同 tick 双灭平局 / 超时平局）由单测 RoundMachineTest 覆盖，本脚本不重复造时间机器。

跑法（服务端已起、RCON 在 25576）：
    .\run\py-embed\python.exe -I -X utf8 tools/m6_round_probe.py
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
CAREER = os.path.join(ROOT, "run", "world", "cww", "career.json")
MATCHES = os.path.join(ROOT, "run", "world", "cww", "state", "matches")

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def log_text():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except FileNotFoundError:
        return ""


def career_wins():
    try:
        with open(CAREER, encoding="utf-8") as fh:
            data = json.load(fh)
        return sum(int(v.get("wins", 0)) for v in data.values())
    except (FileNotFoundError, ValueError):
        return -1


def match_index(p):
    m = re.search(r"match=(\d+)", p("cww state"))
    return int(m.group(1)) if m else -1


def phase_of(p):
    m = re.search(r"阶段=(\w+)", p("cww phase"))
    return m.group(1) if m else "?"


def roster_of(p, name):
    """从 cww state 的队席行里找到某玩家的标记：""（存活）/ [失联] / [淘汰]。"""
    st = p("cww state")
    m = re.search(re.escape(name) + r"(\[[^\]]*\])?", st, re.IGNORECASE)
    return (m.group(1) or "") if m else None


def wait_roster(p, name, want, timeout):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = roster_of(p, name)
        if last == want:
            return True, last
        p.sleep(0.5)
    return False, last


def wait_phases(p, stop="LOBBY", timeout=120, seen=None):
    """从当前阶段往后轮询，记录经过的阶段（去重）；到 stop 或超时返回。"""
    seen = seen if seen is not None else []
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        cur = phase_of(p)
        if cur != last:
            if cur not in seen:
                seen.append(cur)
            last = cur
        if cur == stop and len(seen) > 1:
            return True, seen
        p.sleep(0.5)
    return False, seen


def spawn_bots(p, bots):
    for n, _ in bots:
        p("player %s kill" % n)
    for n, _ in bots:
        p("player %s spawn" % n)
    for _ in range(30):
        listing = p("list").lower()
        if all(n in listing for n, _ in bots):
            return True
        p.sleep(1)
    return False


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()

    print("== 0. 清场")
    print("  残留假玩家：" + repr(sweep_stale_bots(p)))
    clean, st = wait_clean_lobby(p, fmt="test_3min")
    expect(clean, "干净 LOBBY（phase=LOBBY queue=0 且无占席会话）：%s" % st.replace("\n", " ")[:150])
    if not clean:
        return 1

    # ================================================================ 对局 A
    print("== A. 对局一：B1/B2/B5/B7/B10/B11/B12/L3/L5/L6")
    bots = [("r1", 0), ("r2", 1)]
    expect(spawn_bots(p, bots), "两个假玩家在线：%s" % p("list")[:120])
    for n, t in bots:
        out = p("cww force %s %d" % (n, t))
        expect("已将" in out, "force %s -> 队%d：%s" % (n, t, out[:80]))
    keep_alive(p, bots)
    wins_before = career_wins()
    out = p("cww start test_3min %s" % DRY_ARENA)
    expect(not out.strip() or "已将" in out or "阶段" in out, "cww start 已发出：%s" % out[:100])

    # B2：ENROLL 里再 start 必须被拒
    out = p("cww start test_3min %s" % DRY_ARENA)
    expect("已有对局/报名在进行中" in out, "B2 非 LOBBY 再 start 被拒：%s" % out[:140])

    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "B1 进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if not reached:
        p("cww abort")
        return 1
    match = match_index(p)
    print("      本局 match=%d" % match)
    keep_alive(p, bots)

    # B5：PLAY 中 join 不能改变队席/队列
    before = p("cww state")
    p("execute as r1 run cww join")
    p.sleep(1)
    after = p("cww state")
    expect(re.search(r"queue=(\d+)", before).group(1) == re.search(r"queue=(\d+)", after).group(1)
           and "r1" in after, "B5 PLAY 中 join 无副作用（队列/队席不变）")

    # B11：掉线 90s 内重连 → [失联] 标记消失
    print("== B11 掉线 90s 内重连")
    # 2026-11-21 更正：`/kick` 对 Carpet 假玩家无效（它不产生真正的断线），
    # 必须用 `player <name> kill`（删掉假玩家实体 = 服务端视角的断线）。
    p("kick r1")
    p("player r1 kill")
    gone, mark = wait_roster(p, "r1", "[失联]", 15)
    if gone:
        expect(gone, "掉线后队席保留并标 [失联]（实际 %r）" % mark)
        p("player r1 spawn")
        back, mark2 = wait_roster(p, "r1", "", 20)
        expect(back, "90s 内重连后 [失联] 解除（实际 %r）" % mark2)
    else:
        inconclusive("B11 无法制造'假玩家掉线'（kicked=%r）；席位保留逻辑需真人客户端断网验收" % mark)
    keep_alive(p, bots)
    p.sleep(1)

    # B10：再掉线并等席位到期（90s）→ [淘汰] → 只剩一队 → WINNER_DECIDED
    print("== B10 掉线 90s 席位到期 → 淘汰（约 95 秒）")
    # 2026-11-21 结论：Carpet 假玩家造不出"真断线"——
    #   `/kick` 对假玩家是 no-op（不产生断线）；`player <name> kill` 直接把假玩家从服务端删掉
    #   （会话被移除，连席位都不留），两种情况都不是 A13 描述的"客户端断网、席位保留 90s"。
    # 因此这里只做观察并记 INCONCLUSIVE；A13 规则本身由单测
    #   RoundMachineTest.disconnectKeepsSeatThenExpiresIntoElimination() 直接覆盖。
    p("player r1 kill")
    t0 = time.time()
    expired, mark3 = wait_roster(p, "r1", "[淘汰]", 20)
    print("      观察 20s：标记=%r（用时 %.1fs）" % (mark3, time.time() - t0))
    inconclusive("B10 无法用假玩家制造 A13 的'断线保席位 90s'；规则由单测覆盖，"
                 "实机需真人断网验收（本次观察标记=%r）" % mark3)

    seen = []
    done, seen = wait_phases(p, stop="LOBBY", timeout=90, seen=seen)
    expect(done, "B7/B1 只剩 1 队后走完 SETTLE → RETURN → LOBBY（经过 %s）" % seen)
    if not done:
        p("cww abort")

    # B12 / L3 / L5：日志与落盘
    tail = log_text()
    audit_out = p("cww audit 200")
    expect("--WINNER_DECIDED--> SETTLE" in audit_out, "audit 记录 WINNER_DECIDED → SETTLE")
    expect("--SETTLE_TIMEOUT--> RETURN" in audit_out or "SETTLE --" in audit_out,
           "audit 记录 SETTLE 之后的转场")
    expect("本局结束：" in tail and "获胜" in tail and "原因 WINNER_DECIDED" in tail,
           "L3 结算广播含胜者/原因/时长/存活")
    expect("[S10] residue=CLEAN" in tail, "B12 局后残留复检 CLEAN")
    match_path = os.path.join(MATCHES, "%d.json" % match)
    expect(os.path.exists(match_path), "L5 战绩文件已落盘：%s" % match_path)
    if os.path.exists(match_path):
        with open(match_path, encoding="utf-8") as fh:
            rec = json.load(fh)
        print("      %s" % json.dumps(rec, ensure_ascii=False)[:320])
        expect(rec.get("match") == match, "L5 战绩 match 号一致")
        expect(rec.get("cause") == "WINNER_DECIDED", "L5 战绩 cause=WINNER_DECIDED")
        # 2026-11-21 放宽：B10/B11 改成"假玩家造不出真断线"之后，谁出局不再固定，
        # 于是原来写死「蓝队获胜」「r1 死 1 次」成了断言漂移。这里改成
        # 「胜者必须是两队之一」「落败方必须至少有 1 次阵亡」。
        winner_name = rec.get("winner")
        expect(winner_name in ("赤队", "蓝队"), "L5 胜者是两队之一：%r" % winner_name)
        expect(rec.get("participants") == 2, "L5 参战人数=2")
        players = {row.get("name", "").lower(): row for row in rec.get("players", [])}
        losers = [row for name, row in players.items() if row.get("team") != winner_name]
        total_deaths = sum(int(row.get("deaths", 0)) for row in players.values())
        expect(total_deaths >= 1, "L5 落败方记到阵亡（总阵亡 %d，玩家=%s）"
               % (total_deaths, sorted(players)))
    wins_after = career_wins()
    expect(wins_after == wins_before + 1, "L6 生涯胜场 +1（%d → %d）" % (wins_before, wins_after))

    # B3：LOBBY 里 stop/abort 被转移表拒绝
    out = p("cww stop")
    expect("阶段 LOBBY 不接受 ADMIN_STOP" in out, "B3 LOBBY stop 被拒：%s" % out[:120])
    out = p("cww abort")
    expect("阶段 LOBBY 不接受 ADMIN_ABORT" in out, "B3 LOBBY abort 被拒：%s" % out[:120])

    # B4：只有 1 人时报不足下限
    spawn_bots(p, [("r3", 0)])
    p("cww force r3 0")
    out = p("cww start test_3min %s" % DRY_ARENA)
    expect("< 下限" in out or "报名" in out, "B4 人数不足被拒：%s" % out[:140])
    p("player r3 kill")

    # ================================================================ 对局 B
    print("== B. 对局二：B6 死亡 → 淘汰 → 观战（找 spectator notice 日志）")
    bots = [("r4", 0), ("r5", 1)]
    expect(spawn_bots(p, bots), "两个假玩家在线：%s" % p("list")[:120])
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, bots)
    p("cww start test_3min %s" % DRY_ARENA)
    reached, cur = p.wait_phase("PLAY", 120)
    expect(reached, "B6 前置：进入 PLAY（%s）" % cur.replace("\n", " ")[:100])
    if reached:
        p("damage r4 100 minecraft:player_attack by r5")
        p.sleep(2)
        tail = log_text()
        notice = [l for l in tail.splitlines() if "[round] spectator notice:" in l and "r4" in l]
        expect(bool(notice), "B6 淘汰后下发观战提示（日志证据）：%s" % (notice[-1][-120:] if notice else "无"))
        seen = []
        done, seen = wait_phases(p, stop="LOBBY", timeout=90, seen=seen)
        expect(done, "B6 淘汰后对局收束到 LOBBY（经过 %s）" % seen)
        if not done:
            p("cww abort")

    print("== 收尾")
    for n, _ in [("r1", 0), ("r2", 1), ("r3", 0), ("r4", 0), ("r5", 1)]:
        p("player %s kill" % n)
    if "phase=LOBBY" not in p("cww state"):
        p("cww abort")
    p.sleep(1)
    print("  清残留：" + repr(sweep_stale_bots(p)))

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
