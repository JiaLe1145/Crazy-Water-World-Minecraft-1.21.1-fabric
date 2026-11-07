#!/usr/bin/env python3
"""真实海域 arena 端到端探针（M2 海域轮）。

前置：
  1. ./gradlew runServer 已经起来（日志里能看到本轮的模组版本）
  2. RCON_PORT=25576 RCON_PASSWORD=nbwm0（tools/rcon.py 的默认值是旧仓库的 25575，别用默认）
  3. 这是**测试世界**，建海会重写 default_sea/test_sea 覆盖的区块地形

跑法：
  python tools/sea_probe.py            # 常规：内置图 + 建 test_sea + 开一局 + 航行对比
  python tools/sea_probe.py --big      # 额外完整建一次 default_sea（生产形状，约 75 秒）

设计上的两条硬规矩：
  * 每条断言都必须能失败 —— 建海前先取一次"负对照"读数（旱地 flat_test 必然报"没有水"），
    否则"地形正常"这条断言会因为读不到任何差异而恒真。
  * 上一轮实测：建海把服务端拖住 2157ms。所以 tick 落后是**断言项**，不是"跑完就算了"。
"""
import json
import math
import os
import re
import select
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(ROOT, "run", "server-console.log")
DRY_ARENA = "flat_test"          # y=148 的旱地基岩平台，天生就是"没有海"的负对照
SEA = "test_sea"                 # 内置小海域夹具（半径 60）
SAIL_ARENA = "test_sail7"          # 帆档位夹具：边长 7 的筏，1/3/5/7 帆都合法
BIG = "default_sea"              # 内置生产形状（半径 140）
WATER_LEVEL = 62
# 0.29 来自旱地（筏子搁在基岩上），M2-RESULTS §3.8 已判定它不能当水阻或终端速度基线，故不再用于断言。


class P:
    def __init__(self):
        self.sock = None
        self.attach()
        self.logpos = os.path.getsize(LOG) if os.path.exists(LOG) else 0

    def attach(self):
        """连接并排掉鉴权多回的空包。

        排包不能带短 timeout 直接读：读到半个包就中断，整条流从此读歪，
        第一条命令会一直等不到哨兵 id 而卡死（实测探针钉死在第一步）。
        所以先用 select 确认数据已经在缓冲里，再读完整一包。
        """
        self.sock = rcon.connect()
        for _ in range(4):
            ready, _, _ = select.select([self.sock], [], [], 0.3)
            if not ready:
                return
            rcon._recv(self.sock)

    def reconnect(self):
        try:
            self.sock.close()
        except OSError:
            pass
        self.attach()

    def pump(self):
        """把缓冲里已到达的延迟包读掉。"""
        while True:
            ready, _, _ = select.select([self.sock], [], [], 0)
            if not ready:
                return
            try:
                rcon._recv(self.sock)
            except OSError:
                return

    def sleep(self, seconds):
        """等待但持续排空。

        `/function build` 之后几百个 fill 的输出会被陆续推回同一个客户端，
        这些包没人读就会撑满服务端写缓冲，服务端直接掐掉连接
        （实测探针在开一局的中途死于此）。所以等的时候必须读。
        """
        end = time.time() + seconds
        while time.time() < end:
            self.pump()
            time.sleep(0.2)

    def _cmd(self, command):
        """一条命令 = 直接回包 + 可能陆续到来的延迟回包。

        不要在同一条连接上连着发"命令 + 哨兵"：实测服务端会直接掐掉连接，
        而单发一直正常。所以这里单发，然后用 select 只收"缓冲里已经到了"的后续包
        （select 说不ready 就不读，避免把包读到一半把流读歪）。
        """
        rcon._send(self.sock, 2, 2, command)
        parts = [(rcon._recv(self.sock)[2] or "").strip()]
        while True:
            ready, _, _ = select.select([self.sock], [], [], 0.05)
            if not ready:
                break
            try:
                body = (rcon._recv(self.sock)[2] or "").strip()
            except OSError:
                break
            if body:
                parts.append(body)
        return "\n".join(parts).strip()

    def __call__(self, cmd, quiet=True):
        try:
            out = self._cmd(cmd)
        except OSError:
            self.reconnect()          # 服务端会关掉长时间不回读的 RCON 连接，重连后重试一次
            out = self._cmd(cmd)
        if not quiet:
            print("      <%s>" % out.replace("\n", "\n      <")[:400])
        return out

    def log(self):
        size = os.path.getsize(LOG) if os.path.exists(LOG) else 0
        with open(LOG, "rb") as fh:
            fh.seek(self.logpos)
            raw = fh.read()
        self.logpos = size
        return raw.decode("utf-8", errors="replace")

    def phase(self):
        return self("cww phase")

    def wait_phase(self, want, timeout=120):
        """轮询等阶段转移；超时就如实返回当前阶段，由调用方判失败，不能假装等到了。"""
        end = time.time() + timeout
        cur = ""
        while time.time() < end:
            cur = self("cww phase")
            if want in cur:
                return True, cur
            self.sleep(1.0)
        return False, cur


GAP_RE = r"海域预热(?:完成|回收)[^\n]*?让路 (\d+) 个 tick[，,][^\n]*?最大 tick 间隔 (\d+)ms"


def problems(p, arena):
    """从 cww arena terrain 的回包里取问题列数与逐列读数。"""
    out = p("cww arena terrain %s" % arena)
    m = re.search(r"问题 (\d+) 列", out)
    return (int(m.group(1)) if m else -1), out


def queued(p, store):
    """队列还有没有活；只取队首一条。

    不要 `data get storage <store> rows` —— 525 条队列的回包大到服务端直接掐掉连接，
    实测探针就是这么变成"卡住"的。
    """
    out = p("execute if data storage %s rows[0] run data get storage %s rows[0]" % (store, store))
    return out.strip()


LONG_FORMAT = "sail_long"


def write_long_format():
    """把专供航行扫描的赛制写进 run/world/cww/formats/，PLAY 给 900 秒。

    随包 standard_12min 的实测阶段是 [60, 45, 45, 90]，PLAY 只有 45 秒。
    四档帆数每档约 8 秒，第 2 档一结束就 SETTLE、筏子被清场，
    后面几档读到的 0 全是"场上没有筏子"，不是物理 —— 这就是 #34 的真因。
    夹具由探针自己写；模组对 world/cww 只读这条纪律不受影响。
    """
    path = os.path.join(ROOT, "run", "world", "cww", "formats", LONG_FORMAT + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    body = {
        "schemaVersion": 1,
        "id": LONG_FORMAT,
        "teams": 2,
        "teamSize": 2,
        "minPlayers": 2,
        "duration": {"phases": [5, 5, 900, 60], "targetTotal": 1000},
        "border": {"stages": [
            {"at": 60, "radiusScale": 1.0, "damage": 0.0, "warn": 15},
            {"at": 900, "radiusScale": 0.05, "damage": 1.0, "warn": 10}]},
        "loot": {"tierBias": "standard", "respawnIntervalSec": 22},
    }
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(body, ensure_ascii=False, indent=2))
    return path


def keep_alive(p, bots):
    """给夹具假玩家加水下呼吸。

    为什么必须加：锚点在水里（Y≈56 泡水），假玩家会溺死；一旦某队全灭，
    RoundMachine 的 aliveTeamCount()==1 就判胜、提前 SETTLE，
    于是 PLAY 哪怕给 900 秒也会被收走，后续帆数档读到的 0 全是"场上没筏子"。
    这是夹具处理，不改变任何产品行为（真人不会溺死在开局三秒）。
    """
    for name, _ in bots:
        p("effect give %s minecraft:water_breathing infinite 1 true" % name)


BOT_PREFIXES = ("s", "x", "z", "a", "d", "v", "h", "w", "g", "t", "q",
                "o", "r", "m", "u", "e", "b")   # o/r/m 桨、u 建造规则、e/b 取证脚本 是桨的探针（oar_/restart_/motion_）用过的前缀


def sweep_stale_bots(p):
    """杀掉上一轮崩溃时没收尾的 Carpet 假玩家。

    它们会占住玩家名额并让新的 player <名> spawn 静默失败，
    表现是"对局开不起来 / 帆数全是 0" —— 看着像物理坏了，其实是夹具残留。
    """
    out = p("list")
    names = out.split(":")[-1].split(",") if ":" in out else []
    killed = []
    for raw in names:
        name = raw.strip()
        if name and name[0].lower() in BOT_PREFIXES and any(c.isdigit() for c in name):
            p("player %s kill" % name)   # remove 不是这台 Carpet 的子命令，会回一句无关报错
            killed.append(name)
    return killed


def write_sail_arena():
    """复制 test_sea，只改 id 与 initialRaftSide=7；坐标不动所以海不需要重建。"""
    src = os.path.join(ROOT, "src", "main", "resources", "data", "nbw", "arena", "test_sea.json")
    dst = os.path.join(ROOT, "run", "world", "cww", "arenas", SAIL_ARENA + ".json")
    with open(src, encoding="utf-8") as fh:
        arena = json.load(fh)
    arena["id"] = SAIL_ARENA
    arena.setdefault("limits", {})["initialRaftSide"] = 7
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(arena, ensure_ascii=False, indent=2))
    return dst


def raft_name(p, team):
    """当前对局的筏体唯一名 nbw_raft_<队>_<局>（#38 唯一名：名字带 matchIndex 避免跨局重名）。"""
    m = re.search(r"match=(\d+)", p("cww state"))
    match = int(m.group(1)) if m else 0
    return "nbw_raft_%s_%d" % (team, match)


def sweep_raft_residue(p):
    """移除所有 nbw_raft_ 前缀的残留筏（#38 唯一名后，崩溃残留名是 nbw_raft_<队>_<旧局>，只能按前缀扫）。"""
    info = p("sable info @e", quiet=True)
    removed = []
    for name in sorted(set(re.findall(r"nbw_raft_\d+_\d+", info))):
        p("sable remove @e[name=%s]" % name, quiet=True)
        removed.append(name)
    return removed


def raft_pos(p, team):
    """筏体的世界 XZ（sable info 的 Position 行）。test_sea 半径只有 60，
    漂行距离用来判断这段读数是不是已经撞到海域边缘（撞边后不能当终端速度用）。"""
    out = p("sable info @e[name=%s]" % raft_name(p, team))
    m = re.search(r"Position:\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)", out)
    return (float(m.group(1)), float(m.group(3))) if m else (0.0, 0.0)


def wait_clean_lobby(p, fmt=None, tries=40):
    """等一台真正干净的 LOBBY：既没有对局在跑，也没有会话还挂在队席上。

    为什么不能只等 phase=LOBBY：kill 掉的 Carpet 假玩家不触发 DISCONNECT，而局后清理是按
    "参战者实体"处理的——实体早没了就一个都处理不到，会话于是继续占着队席，要等报名期兜底
    （RoundMachine.releaseAbsent，实测约 1 秒一次）才放掉。探针不等这一步，`cww force` 就会
    撞上"队伍 已满"（2026-11-06 实测：上一支探针的 4 个会话把 2×4 的两个队各挤掉 2 个位置），
    看着像 #41 复发，其实是谁都没等谁。
    """
    st = ""
    for _ in range(tries):
        st = p("cww state")
        if "phase=LOBBY" in st and "queue=0" in st and "teams=0" in st:
            if fmt:
                # 报名上限跟着"内存里的赛制"走，而不是跟着下一局要用的赛制走：
                # 上一局 2x2 时 2x4 的第 3 个玩家起就会被"队伍 已满"拒掉。
                # 装赛制这件事是 cww format reload 存在的理由。
                p("cww format reload %s" % fmt)
            return True, st
        if "phase=LOBBY" not in st:
            p("cww abort")
        p.sleep(1)
    return False, st


def main():
    sys.stdout.reconfigure(line_buffering=True)
    big = "--big" in sys.argv
    p = P()
    stale = sweep_stale_bots(p)
    if stale:
        print("      清掉上一轮残留的假玩家：%s" % stale)
    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    print("== 0. 起服日志里不能有加载失败的函数（函数静默不加载 = 整套地形机制不存在）")
    boot = p.log()
    expect("Failed to load function" not in boot, "启动阶段 0 条 Failed to load function")
    expect("Can't keep up" not in boot, "空闲起服没有 tick 落后")

    print("== 1. 内置默认图：来源必须是 jar，且本地同名文件优先")
    listing = p("cww arena list")
    print("      <%s>" % listing[:300])
    expect("内置=[" in listing, "arena list 分得出 world 与 内置 两个来源")
    expect("world=[" in listing, "arena list 仍列出 world 目录的图")
    t = p("cww arena test %s" % SEA)
    expect("来源=jar 内置默认图" in t, "test_sea 走的是 jar 内置回退（实际：%s）" % t[:120])
    expect("校验通过" in t, "内置 test_sea 过同一套校验")

    print("== 2. 负对照：旱地 flat_test 必须报『没有水』，否则后面的『地形正常』是恒真")
    n_dry, dry_out = problems(p, DRY_ARENA)
    print("      <%s>" % dry_out.replace("\n", "\n      <")[:400])
    expect(n_dry > 0, "旱地 arena 报出问题列数（实际 %d）" % n_dry)
    expect("没有水" in dry_out or "水位不符" in dry_out, "旱地的结论是水相关的失败，不是别的")

    print("== 3. 建海前的 test_sea 读数（允许已经有海，也可能还没建；差别留到第 5 步验证）")
    n_before, before_out = problems(p, SEA)
    print("      问题列数 = %d" % n_before)

    print("== 4. 逐区块下票 + 分批写块：build 跑完不能出现 tick 落后")
    p("function nbw:arena/%s/release" % SEA, quiet=False)
    p("schedule clear nbw:arena/%s/step" % SEA)
    p("data modify storage nbw:sea_%s rows set value []" % SEA)
    t0 = time.time()
    p("function nbw:arena/%s/build" % SEA)
    deadline = time.time() + 150
    while time.time() < deadline:
        head = queued(p, "nbw:sea_" + SEA)
        if not head:
            break
        print("      等待建海队列…%.0fs  队首=%s" % (time.time() - t0, head[:70]))
        p.sleep(3)
    spent = time.time() - t0
    lag = p.log()
    slow = re.findall(r"Can't keep up!.*?(\d+)ms", lag)
    expect(not queued(p, "nbw:sea_" + SEA),
           "建海队列已排空（%.0fs，含逐区块下票一轮）" % spent)
    expect(not slow, "建海期间没有 Can't keep up（实测 %s）" % (slow[:3] or "0 次"))

    print("== 5. 建完之后 test_sea 必须全列吻合")
    n_after, after_out = problems(p, SEA)
    print("      <%s>" % after_out.replace("\n", "\n      <")[:500])
    expect(n_after == 0, "建海后问题列数 = 0（实际 %d）" % n_after)
    expect("圈心" in after_out and "%d" % WATER_LEVEL in after_out, "读数里能看见水面 y=%d" % WATER_LEVEL)

    print("== 6. 撤票只撤自己下的那一批")
    p("function nbw:arena/%s/release" % SEA)
    deadline = time.time() + 120
    while time.time() < deadline:
        if not queued(p, "nbw:sea_" + SEA):
            break
        p.sleep(1)
    # 不查全局 forceload query：那是一张 222 个区块的大清单，回包能掐断连接，
    # 里面还混着别的系统/别的图下的票，拿它断"我方零残留"会因为环境状态误判。
    probes = [(-1000, 1000), (-1060, 1000), (-940, 1000), (-1000, 940), (-1000, 1060)]
    left = [(x, z) for x, z in probes if "is marked for force loading" in p("forceload query %d %d" % (x, z))]
    expect(not left, "撤票后 test_sea 抽查的 5 个点都不再是强制加载（实际仍挂着：%s）" % left)

    print("== 7. 在真海上开一局：筏子必须浮在水面附近")
    stamp = time.strftime("%M%S")
    bots = [("s%s%d" % (stamp, i), i % 2) for i in range(4)]
    for name, _ in bots:
        p("player %s kill" % name)      # 上一轮若中途死掉就不收尾，同名假玩家会还留在场上
    for name, _ in bots:
        p("player %s spawn" % name)
    # Carpet 的假玩家上线是异步的：连发 spawn 后立刻 force 会漏人，
    # 漏到不足下限就变成"对局开不起来"，读数全是假的 0（老探针都是先轮询 list 再 force）
    for _ in range(30):
        online = p("list").lower()   # Carpet 会把假玩家名首字母大写回显（s33401 → S33401），
        if all(name.lower() in online for name, _ in bots):   # 大小写敏感的比对会假失败
            break
        p.sleep(1)
    else:
        expect(False, "假玩家没能全部上线，只有这些：%s" % p("list")[:160])
    for name, team in bots:
        p("cww force %s %d" % (name, team))
    keep_alive(p, bots)
    # 用 standard_12min 开局：它的 PLAY 有 180 秒，够第 8 步把帆数扫描跑完。
    # test_3min 的 PLAY 只有 60 秒，等第 7 步查完筏子就所剩无几，后面几档会读到"筏子已被结算清场"的 0。
    p("cww start standard_12min %s" % SEA)
    reached, cur = p.wait_phase("PLAY", 200)
    expect(reached, "test_sea 能开整局并进到 PLAY（当前：%s）" % cur[:60])
    ready_log = ""
    # 要等到 READY→PLAY 那一行才算采完这段窗口（日志是 Gradle 缓冲的，刷盘有延迟）。
    # 早停在第一行 [arena] 上，后面的 PLAY 窗口根本没被采到，断言就成了没对象的空话。
    for _ in range(60):
        ready_log += p.log()
        # 两个都要采到：READY→PLAY 用来判"传送之后必须干净"，预热完成行用来取最大 tick 间隔。
        # 预热可能比 ENROLL(专测赛制只给 5 秒) 更久，会晚于 READY→PLAY 出现，不能提前收采。
        if ("READY --READY_TIMEOUT--> PLAY" in ready_log
                and ("海域预热完成" in ready_log or "海域预热回收" in ready_log)):
            break
        p.sleep(1)
    expect("[arena]" in ready_log,
           "READY 必须留下地形读数（一致或明确报未加载都行），不许沉默地把人扔出去")
    expect("地形与配置一致" in ready_log,
           "READY 前海域区块必须已预热到位（回归护栏：漏了就退化成传送时同步生成区块，"
           "实测代价是一次 2001ms / 40 tick 冻结）。实际日志：%s"
           % (ready_log.strip().splitlines()[-1][:150] if ready_log.strip() else "无 [arena] 行"))
    # 断言对象要卡准：预热本身要重载 81 个区块，实测这笔成本落在 ENROLL 窗口
    # （6137ms / 122 tick，此时还没有人被传送过去，玩家看不到）。
    # 真正不允许发生的是"READY→PLAY 之后还在掉帧"——那时筏子上已经站着人了。
    # 所以这里只消费到 READY→PLAY 的行为止，后面的窗口必须干净。
    idx = ready_log.find("READY --READY_TIMEOUT--> PLAY")
    expect(idx >= 0, "日志里要能看到 READY→PLAY 的转移行（否则这段断言没对象）")
    after_play = ready_log[idx:] if idx >= 0 else ""
    expect("Can't keep up" not in after_play,
           "READY→PLAY 之后不得出现 tick 落后（预热必须在有人之前完成；实测落后出现在：%s）"
           % [ln[:70] for ln in after_play.splitlines() if "Can't keep up" in ln][:2])
    # 顺带把预热本身的成本记下来，别让它悄悄变差
    enroll_lag = [ln for ln in ready_log[:idx if idx >= 0 else len(ready_log)].splitlines()
                  if "Can't keep up" in ln]
    worst = max([int(re.search(r"Running (\d+)ms", ln).group(1)) for ln in enroll_lag
                 if re.search(r"Running (\d+)ms", ln)] or [0])
    print("      ENROLL 窗口预热掉帧 %d 次，最大 %d ms（背压应当把它摊开）" % (len(enroll_lag), worst))
    # 判据用玩家真正感知的帧时间：预热完成行里的"最大 tick 间隔"。
    # Can't keep up 那个毫秒数是累计欠账（6137→4705 只说明积压变少），不能当单帧阻塞时长用。
    gap = re.search(GAP_RE, ready_log)
    expect(bool(gap), "日志里要有预热完成行（带最大 tick 间隔），否则这条判据没有对象：%s"
           % [ln[:80] for ln in ready_log.splitlines() if "海域预热" in ln][:2])
    if gap:
        maxgap = int(gap.group(2))
        print("      预热让路 %s 个 tick，最大 tick 间隔 %d ms（累计欠账行：max %d ms）"
              % (gap.group(1), maxgap, worst))
        expect(maxgap <= 400,
               "预热期玩家感知的单帧时长不得超过 400ms（实测最大 %d ms；改造前传送瞬间是 2001ms）" % maxgap)

    rafts = p("cww raft all")
    ys = re.findall(r"队(\d+) Y=([\d.-]+)", rafts)
    print("      <%s>" % rafts.replace("\n", "\n      <")[:300])
    expect(len(ys) >= 2, "test_3min 两队的筏都发出来了（实际 %d 艘）" % len(ys))
    near = [y for _, y in ys if WATER_LEVEL - 2 <= float(y) <= WATER_LEVEL + 6]
    expect(len(near) == len(ys) and ys, "所有筏子都在水面 y=%d 附近（实际 %s）" % (WATER_LEVEL, ys))

    print("== 8. 缺陷 B 的正规测法：不注入冲量，扫帆数量风驱终端速度（0.29 那个基线已作废）")
    team0 = ys[0][0] if ys else "0"
    print("      专测赛制写入 %s（PLAY 900 秒）" % write_long_format())
    # 档位扫描要 1/3/5/7 帆都能"合法地"挂上去。初始筏削弱到 4×4 之后，
    # 设施位额度 = min(8, 16/5) = 3，扣掉筏心只剩 2 个位 —— 5 帆、7 帆于是成了
    # 玩家根本达不到的状态，而实测那种小筏大帆会打转（7 帆 Yaw 变化 -161°），
    # 曲线不再是配平依据。所以这一档改用边长 7 的夹具图（49 列 → 额度 8，1/3/5/7 全合法），
    # 坐标与 test_sea 完全一致，因此不需要重新建海。
    print("      帆档位夹具图写入 %s" % write_sail_arena())
    p("cww abort")
    p.sleep(3)
    for name, team in bots:
        p("cww force %s %d" % (name, team))
    keep_alive(p, bots)
    started = p("cww start %s %s" % (LONG_FORMAT, SAIL_ARENA))
    ok_long = p.wait_phase("PLAY", 200)[0]
    expect(ok_long, "专测赛制要能开到 PLAY，否则扫描场上没有活筏子：%s" % started[:90])
    start = raft_pos(p, team0)

    def ensure_playing():
        """对局一旦被 SETTLE/RETURN 收走筏子，读数就全是 0 —— 那是夹具过期，不是物理结论。

        重开用 standard_12min：它的 PLAY 有 180 秒，够跑完整轮帆数扫描；
        test_3min 的 PLAY 只有 60 秒，被第 7 步吃掉一半后剩下的档会读到"筏子没了"的 0。
        返回 (ok, 原因)，原因要能直接看，不要让人再去猜 start 为什么被拒。
        """
        if "PLAY" in p("cww phase"):
            return True, ""
        p("cww abort")
        p.sleep(3)
        for name, team in bots:
            p("cww force %s %d" % (name, team))
        keep_alive(p, bots)
        why = p("cww start %s %s" % (LONG_FORMAT, SEA))
        ok = p.wait_phase("PLAY", 150)[0] or "PLAY" in p("cww phase")
        return ok, "" if ok else "start=%s / phase=%s" % (why[:80], p("cww phase")[:40])

    def terminal(sails, strength=2.0):
        """只靠风推进（绝不注入冲量），稳定 8 秒后取 4 个读数的均值。

        每档测前把筏子复位到锚点：test_sea 半径只有 60，上一档漂出去的筏子
        会卡在岸上，读出来的 0 会被误读成"风推不动"。
        """
        ok, why = ensure_playing()
        if not ok:
            return None, None, None, None   # 不 assert 崩掉整个探针：后面的步骤还要跑，这档如实记不可信
        # 复位不消速度（实测每档开头能带着上一档的 4~5 格/tick 与几十度的角速度），
        # 固定 sleep(2) 会把这些残留算进"这一档的表现"。所以反复复位并等到读数真的静止。
        for _ in range(25):
            p("sable teleport @e[name=%s] %d %d %d" % (raft_name(p, team0), -1000, WATER_LEVEL + 1, 946))
            p.sleep(1.2)
            line = p("cww wind read %s" % team0)
            m0 = re.search(r"速率=([\d.]+)", line)
            if m0 and float(m0.group(1)) < 0.004:
                break
        p("cww wind set south 0.0")
        p("cww sail strip %s" % team0)
        if sails:
            p("cww sail rig %s %d south" % (team0, sails))
        pos0 = raft_pos(p, team0)
        p("cww wind set south %.1f" % strength)
        p("cww wind read %s" % team0)   # 先读一次，确认帆数与合力真的挂上了
        p.sleep(3)   # 朝南最多 114 格可跑；上限 0.45 格/tick 时 3 秒 ≈ 81 格，留足余量不撞岸
        got = []
        cap = -1.0
        for _ in range(4):
            line = p("cww wind read %s" % team0)
            m = re.search(r"速度=\([\d.eE+-]+, ([\d.eE+-]+)\)", line)
            f = re.search(r"顺风=([\d.]+)", line)   # 帆跟随前进后，风力读数里是"顺风分量"而不是"合力"
            # 上限从服务端读数里取，不在探针里再抄一遍常量：抄一份就可能出现"探针与代码不一致还全绿"
            c = re.search(r"上限=([\d.]+)", line)
            got.append((abs(float(m.group(1))) if m else -1, abs(float(f.group(1))) if f else -1))
            if c:
                cap = float(c.group(1))
            p.sleep(0.5)
        v = sum(g[0] for g in got) / len(got)
        thrust = got[0][1]
        drift = math.hypot(pos0[0] - start[0], pos0[1] - start[1])
        return v, thrust, drift, cap

    v0, t0, _, _ = terminal(0)
    expect(v0 < 0.01, "0 块帆时风不产生推力（终端速度 %.4f，合力 %.2f）" % (v0, t0))
    curve = {}
    cap = -1.0
    for sails in (1, 3, 5, 7):
        v, th, drift, c = terminal(sails)
        if v is None:
            expect(False, "%d 帆这一档没测成：对局没能进 PLAY（%s）" % (sails, "见上一行原因"))
            break
        curve[sails] = v
        cap = c if c > 0 else cap
        print("      %d 帆：合力 %.2f → %.4f 格/tick（%.2f 格/秒），漂行 %.0f 格，服务端上限=%.3f"
              % (sails, th, v, v * 20, drift, c))
        if drift > 90:
            print("      ⚠ 漂行 %.0f 格，接近 test_sea 边缘 —— 该档读数只作参考" % drift)
    # 符号必须在真水上单独验一次：上面的曲线全程取绝对值，
    # 风向 -Z 而筏子往 +Z 跑这种错误，曲线照样"单调不越限"。
    sign_line = p("cww wind read %s" % team0)
    sm = re.search(r"速度=\([-\d.eE+]+, ([-\d.eE+]+)\)", sign_line)
    sign_z = float(sm.group(1)) if sm else 0.0
    expect(sign_z >= 0.0,
           "南风的位移 z 分量不能是负的（实测 %.4f）；反了说明帆法向或风矢量符号弄错"
           % sign_z)

    if len(curve) < 4:
        print("      ⚠ 只测到 %d 档，后面按实测档位判" % len(curve))
    ks = sorted(curve)
    ordered = [curve[k] for k in ks]
    expect(cap > 0, "读数行里要带服务端航速上限（探针不自己抄常量），实际 cap=%.3f" % cap)
    expect(bool(curve), "至少要有一档风驱读数，否则整段判不可信")
    expect(all(x >= 0 for x in ordered) and ordered == sorted(ordered),
           "帆数↑ 终端速度不能↓（实测 1/3/5/7 帆 = %s）" % ordered)
    over = [(k, v) for k, v in curve.items() if cap > 0 and v > cap * 1.05]
    expect(not over, "任何帆数都不该超过服务端上限 5%%（越界档：%s，上限 %.3f）" % (over, cap))
    expect(7 in curve, "必须测到满帆那一档，否则\"上限是否成为天花板\"没有读数")
    if 7 in curve and curve[7] < cap * 0.9:
        print("      INCONCLUSIVE 7 帆在 3 秒窗口内没跑到上限（%.4f vs %.3f）—— "
              "窗口太短，要判\"上限是否成为满帆天花板\"必须换 default_sea 长航段" % (curve[7], cap))
    if 1 in curve:
        expect(curve[1] <= cap * 0.6,
               "1 帆必须明显慢于上限，否则帆数不再影响航速：1 帆 %.4f vs 上限 %.3f" % (curve[1], cap))
    p("cww wind set north 0.0")
    p.sleep(3)

    # 8b：回到默认的 4×4 初始筏，量"玩家真能建出来的那一档"是否顺风。
    #    帆档位扫描用的是 7×7 夹具筏，因为 4×4 的设施位额度只允许 2 面帆；
    #    而"小筏大帆"（5~7 帆压在 16 列甲板上）实测会打转，那正是设施位规则要挡在门外的状态。
    #    这一档是给削弱初始筏之后的航向稳定性兜底的：可玩配置必须基本顺风，斜着走就是回炉。
    print("== 8b. 4×4 初始筏在合法帆数（2 面）下的航向稳定性")
    p("cww abort")
    p.wait_phase("LOBBY", 180)
    for name, team in bots:
        p("cww force %s %d" % (name, team))
    keep_alive(p, bots)
    p("cww start %s %s" % (LONG_FORMAT, SEA))
    if not p.wait_phase("PLAY", 200)[0]:
        print("      INCONCLUSIVE 8b 没能进 PLAY，小筏航向稳定性未测成")
    else:
        p("cww wind set south 0.0")
        p("cww sail strip %s" % team0)
        legal = 2
        p("cww sail rig %s %d south" % (team0, legal))
        still = False
        for _ in range(25):
            p("sable teleport @e[name=%s] %d %d %d" % (raft_name(p, team0), -1000, WATER_LEVEL + 1, 946))
            p.sleep(1.2)
            mm = re.search(r"速率=([\d.]+)", p("cww wind read %s" % team0))
            if mm and float(mm.group(1)) < 0.004:
                still = True
                break
        p0 = raft_pos(p, team0)
        p("cww wind set south 2.0")
        p.sleep(5)
        p1 = raft_pos(p, team0)
        if not (still and p0 and p1):
            print("      INCONCLUSIVE 8b 没能拿到静止基线或位置读数（still=%s）" % still)
        else:
            dx, dz = p1[0] - p0[0], p1[1] - p0[1]
            expect(dz > 0, "南风下 4×4 筏必须往南走（Δz=%.2f）" % dz)
            ratio = abs(dx) / dz if dz else 99.9
            print("      Δx=%.2f Δz=%.2f 侧移/顺风=%.3f" % (dx, dz, ratio))
            expect(ratio <= 0.10,
                   "可玩配置（2 帆 / 4×4 筏）的侧移比例必须 ≤0.10，实测 %.3f —— 更大帆数属于"
                   "设施位规则挡在门外的非法状态，见 §3.13" % ratio)
        p("cww wind set south 0.0")

    print("== 9. 没有任何 forceload 的远端：筏子不能被踢出物理管线（Sable 的 inhabit 票）")
    p("cww wind set north 0.0")
    assert ensure_playing(), "对局已不在 PLAY，第 9 步无法判"
    far_z = 700                      # 海域 z 920..1080 之外，且第 6 步已把票撤干净
    p("sable teleport @e[name=%s] -1000 %d %d" % (raft_name(p, team0), WATER_LEVEL + 1, far_z), quiet=False)
    p.sleep(6)
    pose = re.findall(r"队%s Y=([\d.-]+)" % team0, p("cww raft all"))
    expect(len(pose) == 1, "推到未加载空域后筏子仍在管线里（读数：%s）" % pose)
    p.sleep(8)
    pose2 = re.findall(r"队%s Y=([\d.-]+)" % team0, p("cww raft all"))
    expect(len(pose2) == 1, "14 秒后依然读得到姿态，没有被 moveToUnloaded 收走（读数：%s）" % pose2)

    print("== 10. 局后清理零残留")
    p("cww abort")
    p.wait_phase("LOBBY", 120)
    after = p("cww raft all")
    expect("队" not in after, "abort 后场上不再有筏：%s" % after[:120])
    tickets = p("sable forceload query")
    print("      <sable forceload query> %s" % tickets.replace("\n", " | ")[:220])
    expect("nbw:" not in tickets,
           "局后 Sable 侧不再挂着 nbw: 的加载票（实际：%s）" % tickets.replace("\n", " | ")[:160])
    # 判据换成玩家真正感知的帧时间。`Can't keep up! Running Nms` 里的 N 是**累计欠账**：
    # 实测整局最大单 tick 只有 59ms，但那行照样报了 10474ms —— 拿它当"卡了 10 秒"是错的读法。
    tail = p.log()
    gap2 = re.search(GAP_RE, tail)
    expect(bool(gap2), "回收行里要有整局最大 tick 间隔：%s" % [ln[:70] for ln in tail.splitlines() if "海域预热回收" in ln][:1])
    if gap2:
        whole = int(gap2.group(2))
        print("      整局（含航行与远端推移）最大 tick 间隔 %d ms；让路 %s 个 tick" % (whole, gap2.group(1)))
        expect(whole <= 400,
               "整局任何一帧不得超过 400ms（实测 %d ms）。看门狗的累计欠账另计，不当帧时间用" % whole)

    print("== 11. --big：生产形状 default_sea（半径 140）完整建一次")
    if big:
        t0 = time.time()
        p("function nbw:arena/%s/build" % BIG)
        deadline = time.time() + 900
        while time.time() < deadline:
            if not queued(p, "nbw:sea_" + BIG):
                break
            p.sleep(5)
        n_big, big_out = problems(p, BIG)
        print("      default_sea 建完耗时 %.0fs，问题列数 %d" % (time.time() - t0, n_big))
        expect(n_big == 0, "半径 140 建完全列吻合：%s" % big_out[:200])
        expect(not re.findall(r"Can't keep up!.*?(\d+)ms", p.log()), "半径 140 的建海也没把 tick 拖爆")
        p("function nbw:arena/%s/release" % BIG)

    print()
    print("=== SEA PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    for name, _ in bots:
        p("player %s kill" % name)
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
