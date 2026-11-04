"""M1 round skeleton end-to-end drill.

Usage:
    python tools/m1_round.py [--matches N] [--abort] [--no-stop]

Preconditions:
    ./gradlew runServer  (background; run/server-console.log is being written)
    Carpet jar in run/mods    (test env only, never production - 07 §1)

Evidence discipline (05 methodology / hard rule):
    Fake players prove SERVER DATA and LOGIC only. Rubber-banding, view, HUD and
    teleport *feel* cannot be judged here and are reported as NEEDS-HUMAN.
"""
import io
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

try:
    sys.stdout.reconfigure(errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(ROOT, "run", "server-console.log")

BOTS = ["m1a", "m1b", "m1c", "m1d"]
LOBBY = (0.5, 201.0, 0.5)
ANCHORS = {0: (1000.5, 149.0, 952.5), 1: (1000.5, 149.0, 1048.5)}
GAMEMODE_NAMES = {"0": "survival", "1": "creative", "2": "adventure", "3": "spectator"}
PHASES = ("LOBBY", "ENROLL", "READY", "PLAY", "SETTLE", "RETURN")


def read_log(pos):
    """Server log on this box is written with the platform codepage (GBK); try utf-8 then gbk."""
    if not os.path.exists(LOG):
        return "", pos
    size = os.path.getsize(LOG)
    if size < pos:
        pos = 0
    with open(LOG, "rb") as fh:
        fh.seek(pos)
        raw = fh.read()
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc, errors="replace"), size
        except Exception:
            continue
    return raw.decode("utf-8", errors="replace"), size


class Drill:
    def __init__(self):
        self.sock = rcon.connect()
        self.logpos = os.path.getsize(LOG) if os.path.exists(LOG) else 0
        self.log_names = []
        self.recent = ""
        self.ok = []
        self.bad = []

    # ---------------- plumbing ----------------
    def cmd(self, command, show=True, quiet_fail=False):
        out = rcon.run(self.sock, command) or ""
        if show and not quiet_fail:
            print("  > %s\n    %s" % (command, out.replace("\n", "\n    ")[:400]))
        return out.strip()

    def drain_log(self, echo=True):
        text, self.logpos = read_log(self.logpos)
        self.recent += text
        if echo:
            for line in text.splitlines():
                if any(k in line for k in ("[AUDIT]", "[S10]", "[C21]", "pvp blocked", "[round]", "placed in lobby")):
                    print("    LOG %s" % line.strip()[:190])
        return text

    def mark(self):
        """取证窗口归零：每个独立断言段之前调用，避免上一段的日志被下一段误当成自己的证据。"""
        self.recent = ""

    def wait_log(self, marker, timeout=12, echo=False):
        """server-console.log is Gradle's buffered stdout redirect; poll instead of assuming it is flushed.

        读到的每一段都累加进 self.recent（滚动窗口）：一条阶段转移日志里往往同时带着多个断言要看的标记，
        第一次轮询消费掉之后，第二次再找同一个窗口就会假失败。
        """
        deadline = time.time() + timeout
        text = ""
        while time.time() < deadline:
            chunk, self.logpos = read_log(self.logpos)
            text += chunk
            self.recent += chunk
            if marker in text or marker in self.recent:
                return self.recent, True
            time.sleep(1.0)
        self.recent = self.recent[-40000:]
        if echo:
            for line in text.splitlines():
                if "[round]" in line or "[AUDIT]" in line or "[S10]" in line:
                    print("    LOG %s" % line.strip()[:190])
        return self.recent, False

    def wait(self, seconds, label):
        print("  ... %ss: %s" % (seconds, label))
        time.sleep(seconds)
        return self.drain_log()

    def expect(self, condition, message):
        (self.ok if condition else self.bad).append(message)
        print("    %s %s" % ("OK  " if condition else "FAIL", message))
        return bool(condition)

    # ---------------- probes ----------------
    def pos(self, name):
        out = self.cmd("data get entity %s Pos" % name, show=False)
        m = re.search(r"\[\s*([-\d.]+)d?,\s*([-\d.]+)d?,\s*([-\d.]+)d?\s*\]", out)
        return tuple(round(float(g), 2) for g in m.groups()) if m else None

    def near(self, actual, target, tol=1.2):
        return actual is not None and all(abs(actual[i] - target[i]) <= tol for i in range(3))

    def health(self, name):
        out = self.cmd("data get entity %s Health" % name, show=False)
        m = re.search(r":\s*([-\d.]+)d?", out)
        return float(m.group(1)) if m else None

    def game_mode(self, name):
        out = self.cmd("data get entity %s playerGameType" % name, show=False)
        m = re.search(r":\s*(\d)", out)
        return GAMEMODE_NAMES.get(m.group(1), out[:40]) if m else "?"

    def phase(self):
        out = self.cmd("cww phase", show=False)
        return next((p for p in PHASES if p in out), "?")

    def team_map(self):
        """Read the authoritative team assignment straight out of /cww state."""
        out = self.cmd("cww state", show=False)
        mapping = {}
        for m in re.finditer(r"T(\d) \S+ \[([^\]]*)\]", out):
            index = int(m.group(1))
            for name in re.split(r",\s*", m.group(2).strip()):
                if name:
                    mapping[name.split("[")[0]] = index
        return mapping

    # ---------------- setup ----------------
    def reset_world(self):
        print("== setup: forceload + platforms + rules")
        self.cmd("forceload add -24 -24 24 24")
        self.cmd("forceload add 920 920 1080 1080")
        self.cmd("fill -24 200 -24 24 200 24 minecraft:bedrock")
        self.cmd("fill 920 148 920 1080 148 1080 minecraft:bedrock")
        self.cmd("gamerule doMobSpawning false")
        for bot in BOTS:
            self.cmd("player %s kill" % bot, show=False, quiet_fail=True)
        time.sleep(1.0)
        self.cmd("cww abort", show=False, quiet_fail=True)   # 若上一轮残留还在局内
        self.drain_log(echo=False)

    def spawn_bots(self):
        print("== spawn fake players (Carpet creates them asynchronously; names may be re-cased)")
        for bot in BOTS:
            self.cmd("player %s spawn" % bot, show=False, quiet_fail=True)
        online = {}
        for _ in range(30):
            time.sleep(1.0)
            out = self.cmd("list", show=False)
            names = [n.strip() for n in out.split(":")[-1].split(",")]
            online = {n.lower(): n for n in names if n}
            if all(b in online for b in BOTS):
                break
        resolved = [online.get(b) for b in BOTS if online.get(b)]
        self.log_names = resolved
        self.expect(len(resolved) == len(BOTS),
                    "all %d fake players online (resolved names: %s)" % (len(BOTS), resolved))
        self.mark()
        joined, _ = self.wait_log("placed in lobby", 10)
        for name in resolved:
            p = self.pos(name)
            self.expect(self.near(p, LOBBY, 3.0), "JOIN hook put %s into the lobby area (pos=%s)" % (name, p))
        self.expect("placed in lobby" in joined, "lobby placement is observable in the log")
        self.expect("JOIN fired" in joined, "JOIN event fires for fake players too")

    def config_checks(self):
        print("== config layer checks (03 §6)")
        self.expect("通过" in self.cmd("cww arena test flat_test", show=False), "arena flat_test validates")
        self.expect("通过" in self.cmd("cww format test test_3min", show=False), "format test_3min validates")
        out = self.cmd("cww lobby test")
        self.expect("通过" in out, "lobby.json validates (03 §5)")

    # ---------------- pvp gate ----------------
    def pvp_probe(self, expect_blocked, tag):
        phase = self.phase()
        print("== pvp probe in phase %s (%s)" % (phase, tag))
        self.mark()
        if len(self.log_names) < 2:
            self.expect(False, "need 2 fake players for the pvp probe")
            return
        victim, attacker = self.log_names[0], self.log_names[1]
        # 场地：受害者站锚点/大厅中心，攻击者在其 +Z 1.5 格处朝 -Z（yaw 180）正好对着他
        base = ANCHORS[0] if phase == "PLAY" else LOBBY
        self.cmd("tp %s %.1f %.1f %.1f" % (victim, base[0], base[1], base[2]), show=False)
        self.cmd("tp %s %.1f %.1f %.1f 180 0" % (attacker, base[0], base[1], base[2] + 1.2), show=False)
        before = self.health(victim)
        blocked_text, blocked_hit = "", False
        for attempt in range(2 if expect_blocked else 1):
            # 连续攻击：单次 attack 常常打空，打空时 ALLOW_DAMAGE 根本不会被咨询，就会假失败
            self.cmd("player %s attack" % attacker, show=False, quiet_fail=True)
            if expect_blocked:
                blocked_text, blocked_hit = self.wait_log("pvp blocked", 8)
                self.cmd("player %s attack" % attacker, show=False, quiet_fail=True)
                if blocked_hit:
                    break
                time.sleep(1.0)
            else:
                time.sleep(3.0)
                self.cmd("player %s attack" % attacker, show=False, quiet_fail=True)
        if not expect_blocked:
            time.sleep(1.0)
        after = self.health(victim)
        blocked = self.recent.count("pvp blocked")
        print("    victim=%s hp %s -> %s, pvp-blocked-lines=%d" % (victim, before, after, blocked))
        if expect_blocked:
            self.expect(blocked_hit and blocked >= 1, "ALLOW_DAMAGE gate logged a denial outside PLAY (lines=%d)" % blocked)
            self.expect(after == before, "no damage landed outside PLAY (hp %s -> %s)" % (before, after))
        else:
            self.expect(blocked == 0, "no denial logged during PLAY")
            self.expect(after is not None and before is not None and after < before,
                        "pvp lands during PLAY (hp %s -> %s)" % (before, after))

    # ---------------- one match ----------------
    def match(self, index):
        print("\n===== match %d =====" % index)
        self.expect(self.phase() == "LOBBY", "phase is LOBBY before start")
        wanted = {self.log_names[0]: 0, self.log_names[1]: 0, self.log_names[2]: 1, self.log_names[3]: 1} \
            if len(self.log_names) == 4 else {}
        for name, team in wanted.items():
            out = self.cmd("cww force %s %d" % (name, team), show=False)
            if "已将" not in out:
                self.expect(False, "force %s -> team %d rejected: %s" % (name, team, out[:90]))
        actual = self.team_map()
        print("    team map from /cww state: %s" % actual)
        self.expect(actual and all(actual.get(n) == t for n, t in wanted.items()),
                    "LOBBY pre-assignment sticks (regression: teams used to be built only at ENROLL)")
        self.cmd("cww start test_3min flat_test")
        self.expect(self.phase() == "ENROLL", "LOBBY --START--> ENROLL")
        self.pvp_probe(True, "enroll")
        log, ready_hit = self.wait_log("--> READY", 45)
        self.expect(ready_hit and "anchors built" in log, "ENROLL_TIMEOUT entered READY with anchors built")
        bad_pos = []
        for name, team in (wanted or self.team_map()).items():
            p = self.pos(name)
            target = ANCHORS.get(team)
            if not self.near(p, target, 2.0):
                bad_pos.append((name, team, p))
            else:
                print("    %s team=%d at %s == anchor %s" % (name, team, p, target))
        self.expect(not bad_pos, "every participant stands on their own team anchor (server-side) %s" % (bad_pos,))
        play_log, play_hit = self.wait_log("--> PLAY", 25)
        log += play_log
        self.expect(play_hit and "SURVIVAL applied" in play_log, "READY_TIMEOUT entered PLAY and mode switched")
        self.pvp_probe(False, "play")
        victims = [n for n, t in (wanted or self.team_map()).items() if t == 1]
        print("  wiping team 1: %s" % victims)
        self.mark()
        t0 = time.time()
        for name in victims:
            self.cmd("kill %s" % name, show=False)
        log, hit = self.wait_log("--WINNER_DECIDED--> SETTLE", 120)
        how = None
        if hit:
            how = "death-event" if (time.time() - t0) < 20 else "seat-expiry"
        self.expect(hit, "last team standing ended the match (via %s)" % how)
        print("    match ended via: %s after %.0fs" % (how, time.time() - t0))
        self.expect("--> SETTLE" in log, "SETTLE entered")
        respawn = log.count("[C21] respawn")
        print("    [C21] respawn observations this match: %d" % respawn)
        self.mark()
        _, s10_hit = self.wait_log("[S10] residue=", 60)
        log = self.recent
        self.expect("[S10] residue=CLEAN" in log, "S10 residue audit CLEAN")
        self.expect("[S10] residue=FAIL" not in log, "S10 audit reported no FAIL")
        _, closed_hit = self.wait_log("--> LOBBY", 40)
        log = self.recent
        self.expect(closed_hit, "cycle closed back to LOBBY")
        self.expect("lobby-recheck=CLEAN" in log, "lobby recheck also CLEAN (second independent assertion)")
        survivors = [n for n, t in wanted.items() if t == 0]
        for name in survivors:
            p = self.pos(name)
            self.expect(self.near(p, LOBBY, 1.5), "%s teleported back to lobby (pos=%s)" % (name, p))
            gm = self.game_mode(name)
            self.expect(gm == "adventure", "%s game mode is adventure in lobby (read: %s)" % (name, gm))
            inv = self.cmd("data get entity %s Inventory" % name, show=False)
            self.expect(re.search(r"entity data:\s*\[\]", inv) is not None, "%s inventory empty after settle" % name)
        # killed bots leave the world (Carpet does not respawn them) -> bring them back for the next match
        for name in victims:
            self.cmd("player %s kill" % name, show=False, quiet_fail=True)
            self.cmd("player %s spawn" % name, show=False, quiet_fail=True)
        for _ in range(20):
            time.sleep(1.0)
            out = self.cmd("list", show=False)
            names = {n.strip().lower(): n.strip() for n in out.split(":")[-1].split(",") if n.strip()}
            if all(b in names for b in BOTS):
                self.log_names = [names[b] for b in BOTS]
                break
        print("    online for next match: %s" % self.log_names)
        return respawn

    # ---------------- abort ----------------
    def abort_path(self):
        print("\n===== abort path =====")
        wanted = dict(zip(self.log_names[:2], [0, 0]))
        wanted.update(dict(zip(self.log_names[2:], [1, 1])))
        for name, team in wanted.items():
            self.cmd("cww force %s %d" % (name, team), show=False)
        self.cmd("cww start test_3min flat_test", show=False)
        self.expect(self.phase() == "ENROLL", "abort-path match started")
        self.drain_log(echo=False)
        self.cmd("cww abort")
        self.mark()
        log, back = self.wait_log("--> LOBBY", 20)
        self.expect(back, "abort goes straight back to LOBBY")
        self.expect(self.phase() == "LOBBY", "phase LOBBY after abort")
        self.wait_log("[S10] ", 20)
        lines = [l for l in self.recent.splitlines() if "[S10]" in l]
        self.expect(any("CLEAN" in l for l in lines) and not any("FAIL" in l for l in lines),
                    "abort path also cleans up and audits (regression) %s" % (lines[-2:],))
        self.cmd("cww audit 8")


def main():
    args = sys.argv[1:]
    matches = 3
    if "--matches" in args:
        matches = int(args[args.index("--matches") + 1])
    do_abort = "--abort" in args
    no_stop = "--no-stop" in args
    d = Drill()
    d.reset_world()
    d.spawn_bots()
    d.config_checks()
    d.logpos = os.path.getsize(LOG)
    respawn_total = 0
    for i in range(1, matches + 1):
        respawn_total += d.match(i)
    if do_abort:
        d.abort_path()
    tail = d.drain_log(echo=False)
    noise = [l for l in tail.splitlines()
             if re.search(r"Exception|ERROR|Can't keep up|moved wrongly", l) and "data fixer" not in l]
    print("\n=== M1 ROUND E2E SUMMARY ===")
    print("matches run              : %d%s" % (matches, " +1 abort path" if do_abort else ""))
    print("expectations satisfied   : %d" % len(d.ok))
    print("expectations FAILED      : %d" % len(d.bad))
    for f in d.bad:
        print("  FAIL %s" % f)
    print("C21 respawn observed     : %d  (0 -> fake players never respawn; needs a human)" % respawn_total)
    print("unexpected log lines     : %d" % len(noise))
    for l in noise[:8]:
        print("  %s" % l.strip()[:190])
    print("NEEDS-HUMAN: teleport feel / rubber-banding / view / HUD cannot be judged by fake players")
    print("SUGGESTED-HUMAN-CHECKLIST: /cww join -> /cww start -> watch the 10s READY countdown ->")
    print("  confirm you stand on your team raft spot, die once and confirm you respawn into the arena")
    if not no_stop:
        d.cmd("stop", show=False)
    print("RESULT: %s" % ("PASS" if not d.bad else "HAS-FAILURES"))
    return 0 if not d.bad else 1


if __name__ == "__main__":
    sys.exit(main())
