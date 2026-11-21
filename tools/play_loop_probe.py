#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真人客户端多轮对局观察探针（2026-11-21）。

它要回答的问题：「服务端把一整局跑起来之后，**客户端画面上**到底对不对」——
而不是「服务端读数对不对」（那是其它探针的事）。

做法：
  1. 用 RCON 把一局摆起来：真客户端玩家 + 1 个假玩家，进 PLAY；
  2. 按脚本做一串「真人会做的事」：发装备 → 上筏 → 挂帆 → 放器械 → 招幸存者 → 挨打/落水；
  3. 每个关键点截一张图存到 `run/play_shots/r<轮次>_<步骤>.png`；
  4. 每轮结束把服务端读数与截图清单写进 `run/play_shots/summary.json`。

**为什么必须跑多轮**：这类问题（贴图/模型/UI/复活/跨局残留）几乎都是概率性出现，
单轮全绿说明不了任何问题。默认 5 轮，正式验收建议 ≥10 轮，且最好换不同时间段跑。

用法（服务端已起、RCON 在 25576、客户端已进服）：
    .\\run\\py-embed\\python.exe -I -X utf8 tools\\play_loop_probe.py --rounds 5
    ... --rounds 10 --arena flat_test --format test_3min
    ... --no-shots            # 只摆局不截图（快速回归用）
    ... --player Player627    # 指定客户端玩家名；不给就从 list 里猜

前置（客户端怎么自动起、怎么自动截图）见 docs/dev/M6-REMAINING-TEST-PLAN.md §1.4。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOT_DIR = os.path.join(ROOT, "run", "play_shots")
LOGSHOT = os.path.join(ROOT, "run", "client-console.log")
PY = os.path.join(ROOT, "run", "py-embed", "python.exe")
SHOTS_PS1 = os.path.join(ROOT, "run", "_shots.ps1")

# 假玩家名单（用来把"真人客户端"从 list 里区分出来）
BOT_PREFIXES = ("s", "g", "r", "c", "e", "k", "p", "h", "hp", "cfg")

ok, bad, inc = [], [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def info(msg):
    inc.append(msg)
    print("  INFO - %s" % msg)


class P:
    def __init__(self):
        self.sock = rcon.connect()

    def __call__(self, cmd):
        return rcon.run(self.sock, cmd)

    def sleep(self, sec):
        time.sleep(sec)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def phase(p):
    m = re.search(r"阶段=(\w+)", p("cww phase"))
    return m.group(1) if m else "?"


def state(p):
    return p("cww state")


def wait_phase(p, want, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        cur = phase(p)
        if cur == want:
            return True, cur
        p.sleep(1)
    return False, phase(p)


def wait_lobby(p, timeout=120):
    """回到 LOBBY 且队列清空。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        st = state(p)
        if "phase=LOBBY" in st and re.search(r"queue=0\b", st) and re.search(r"teams=0\b", st):
            return True
        p.sleep(1)
    return False


def online_names(p):
    out = p("list")
    m = re.search(r"There are \d+ of a max of \d+ players online:\s*(.*)", out)
    if not m:
        return []
    raw = m.group(1).strip()
    return [n.strip() for n in raw.split(",") if n.strip()]


def guess_player(p):
    """客户端玩家 = 在线且不是我们起的假玩家（假玩家名字都很短，且不带大写前缀）。"""
    names = online_names(p)
    real = [n for n in names if not re.fullmatch(r"[a-zA-Z]?\d{1,3}", n)]
    return real[0] if real else (names[0] if names else None)


def ps_exe():
    """挑一个能用的 PowerShell 可执行文件。

    这台机器上 `pwsh`（PS7）**不在 PATH 里**，只有 `powershell.exe`（5.1）——
    第一版脚本硬编码 `pwsh` 直接 `FileNotFoundError`。这里两个都试，
    并记住第一次成功的那一个。
    """
    global _PS_EXE
    if _PS_EXE is not None:
        return _PS_EXE
    for cand in ("pwsh", "powershell"):
        try:
            proc = subprocess.run([cand, "-NoProfile", "-Command", "exit 0"],
                                  capture_output=True, timeout=30)
            if proc.returncode == 0:
                _PS_EXE = cand
                return cand
        except (FileNotFoundError, subprocess.TimeoutExpired):
            continue
    _PS_EXE = "powershell"
    return _PS_EXE


_PS_EXE = None


def shoot(name, enabled=True):
    """截一张图。返回路径（失败返回 None）。

    为什么要绕一层 PowerShell：截图逻辑在 run/_shots.ps1 里（含 DPI 处理、最小化 DSH、
    按窗口矩形 CopyFromScreen）。.ps1 不能直接执行（策略拦截），必须 iex 进去。
    """
    if not enabled:
        return None
    os.makedirs(SHOT_DIR, exist_ok=True)
    path = os.path.join(SHOT_DIR, name + ".png")
    cmd = (
        "iex (Get-Content '%s' -Raw -Encoding utf8); "
        "$r = Save-McShot '%s'; if (-not $r) { exit 3 }" % (SHOTS_PS1, path)
    )
    try:
        proc = subprocess.run([ps_exe(), "-NoProfile", "-Command", cmd],
                              cwd=ROOT, capture_output=True, timeout=120)
        if proc.returncode != 0 or not os.path.isfile(path):
            print("    [shot] 失败 rc=%d %s" % (proc.returncode,
                                               proc.stderr.decode("utf-8", "replace")[-200:]))
            return None
        print("    [shot] %s" % os.path.basename(path))
        return path
    except Exception as exc:      # noqa: BLE001
        print("    [shot] 异常：%s" % exc)
        return None


def client_log_tail(n=40):
    try:
        with open(LOGSHOT, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - 20000))
            text = fh.read().decode("utf-8", "replace")
        return "\n".join(text.splitlines()[-n:])
    except FileNotFoundError:
        return ""


def spawn_bot(p, name):
    p("player %s kill" % name)
    p("player %s spawn" % name)
    p.sleep(0.5)


def one_round(p, idx, player, arena, fmt, shots, notes):
    """跑一轮对局 + 观察。返回本轮的截图清单。"""
    tag = "r%02d" % idx
    got = []
    print("\n== 第 %d 轮（玩家=%s 图=%s 赛制=%s）" % (idx, player, arena, fmt))

    if not wait_lobby(p, 120):
        expect(False, "[%s] 开局前回到干净 LOBBY（%s）" % (tag, state(p).replace("\n", " ")[:120]))
        p("cww abort")
        p.sleep(2)
        return got
    p("time set noon")
    p("weather clear")

    bot = "s%d" % (idx % 9 + 1)
    spawn_bot(p, bot)
    p("cww force %s 0" % player)
    p("cww force %s 1" % bot)
    p("cww start %s %s" % (fmt, arena))
    reached, cur = wait_phase(p, "PLAY", 150)
    expect(reached, "[%s] 进入 PLAY（实际 %s）" % (tag, cur))
    if not reached:
        p("cww abort")
        p.sleep(2)
        return got

    p.sleep(3)
    st = state(p)
    expect(player in st, "[%s] 客户端玩家在 T0 队席上" % tag)
    s = shoot("%s_01_play_start" % tag, shots)
    if s:
        got.append(s)

    # ---- 真人会做的事：清背包 → 发一套装备 → 看手持/热键栏/HUD ----
    p("clear %s" % player)
    for item, cnt in (("nbw:cutlass", 1), ("nbw:musket", 1), ("nbw:sail", 4),
                      ("nbw:ballista", 1), ("nbw:ballista_bolt", 8),
                      ("nbw:rope", 1), ("nbw:grapnel", 1), ("nbw:fresh_water", 2),
                      ("nbw:bandage", 2), ("minecraft:iron_pickaxe", 1)):
        p("give %s %s %d" % (player, item, cnt))
    keep_alive(p, [(player, 0), (bot, 1)])
    p.sleep(2)
    s = shoot("%s_02_equipped" % tag, shots)
    if s:
        got.append(s)

    # ---- 器械：在筏上放一门弩炮（走 placeAt 路径），看外形与登记 ----
    raft = p("cww raft all")
    m = re.search(r"队0 Y=([\d.]+)", raft)
    if m:
        info_msg = p("execute positioned 1001 150 953 run cww ballista place 0")
        if "放置" not in info_msg:
            info("第 %d 轮弩炮放置回包：%s（不同图锚点不同，属于夹具限制）" % (idx, info_msg[:80]))
        p("cww ballista ammo 0 16")
        p.sleep(2)
        s = shoot("%s_03_ballista" % tag, shots)
        if s:
            got.append(s)
    p("cww ballista status")

    # ---- 幸存者 ----
    surv = p("cww survivor")
    print("    " + surv.replace("\n", " ")[:160])
    expect("幸存者=" in surv, "[%s] 幸存者读数可读：%s" % (tag, surv.replace("\n", " ")[:110]))

    # ---- 落水 / 湿身：看 HUD 与失温 ----
    p("tp %s 1000.5 146 1000 0 0" % player)
    p.sleep(11)
    surv_st = p("cww survival status %s" % player)
    print("    " + surv_st.replace("\n", " ")[:160])
    if "失温=否" in surv_st:
        notes.append("第 %d 轮：泡水 11s 仍未失温（可能没真的进水里）" % idx)
    expect("失温=是" in surv_st or "失温=否" in surv_st,
           "[%s] survival status 有读数" % tag)
    s = shoot("%s_04_wet_hud" % tag, shots)
    if s:
        got.append(s)

    # ---- 死亡 → 淘汰 → 观战：**重点盯复活/死亡屏** ----
    st_before = state(p)
    if player in st_before:
        p("damage %s 200 minecraft:player_attack by %s" % (player, bot))
    p.sleep(6)
    st_after = state(p)
    eliminated = ("[淘汰]" in st_after) or ("[失联]" in st_after)
    still_online = player in online_names(p)
    expect(eliminated, "[%s] 死亡后被标记淘汰/失联（%s）"
           % (tag, st_after.replace("\n", " ")[:130]))
    s = shoot("%s_05_after_death" % tag, shots)
    if s:
        got.append(s)

    # 死亡屏卡死检测：淘汰后 8 秒再看客户端日志有没有 respawn 记录 / 玩家是否还活着
    p.sleep(8)
    tail = client_log_tail(60)
    respawned = ("[C21] respawn" in tail) or ("respawn" in tail.lower())
    print("    淘汰后仍在线=%s 客户端日志出现 respawn=%s" % (still_online, respawned))
    if still_online and not respawned:
        notes.append("第 %d 轮：客户端疑似卡在死亡界面（无 respawn 日志）—— 见 §3.11" % idx)
    s = shoot("%s_06_spectator" % tag, shots)
    if s:
        got.append(s)

    # ---- 收尾：把这一局关掉，回到 LOBBY ----
    p("cww abort")
    if not wait_lobby(p, 120):
        p("cww stop")
        p.sleep(3)
        wait_lobby(p, 60)
    p("player %s kill" % bot)
    return got


def keep_alive(p, pairs):
    """给假玩家/真人续夜视（避免在水下看不清、窒息干扰观察）。"""
    for name, _t in pairs:
        p("effect give %s minecraft:night_vision 900 0 true" % name)
        p("effect give %s minecraft:water_breathing 900 0 true" % name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--arena", default="flat_test")
    ap.add_argument("--format", default="test_3min")
    ap.add_argument("--player", default=None)
    ap.add_argument("--no-shots", action="store_true")
    args = ap.parse_args()

    os.makedirs(SHOT_DIR, exist_ok=True)
    p = P()
    player = args.player or guess_player(p)
    if not player:
        print("没有找到客户端玩家：请先启动客户端并进服（见 §1.4）")
        return 2
    print("客户端玩家：%s；轮次：%d；图：%s" % (player, args.rounds, args.arena))

    notes = []
    all_shots = {}
    t0 = time.time()
    for idx in range(1, args.rounds + 1):
        try:
            got = one_round(p, idx, player, args.arena, args.format, not args.no_shots, notes)
            all_shots["r%02d" % idx] = [os.path.relpath(x, ROOT) for x in got]
        except Exception as exc:                      # noqa: BLE001
            bad.append("第 %d 轮异常终止：%s" % (idx, exc))
            print("  !! 第 %d 轮异常：%s" % (idx, exc))
            try:
                p("cww abort")
            except Exception:                          # noqa: BLE001
                pass

    # 收尾
    p("cww abort")
    p.sleep(2)

    summary = {
        "player": player,
        "rounds": args.rounds,
        "arena": args.arena,
        "format": args.format,
        "elapsedSec": round(time.time() - t0),
        "pass": ok,
        "fail": bad,
        "notes": notes,
        "shots": all_shots,
        "finalState": state(p),
    }
    path = os.path.join(SHOT_DIR, "summary.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
    print("\n汇总已写入 %s" % path)

    print("\n== 汇总")
    print("  PASS=%d FAIL=%d NOTE=%d 用时 %ds" % (len(ok), len(bad), len(notes), summary["elapsedSec"]))
    for m in bad:
        print("  FAIL - " + m)
    for m in notes:
        print("  NOTE - " + m)
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    p.close()
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
