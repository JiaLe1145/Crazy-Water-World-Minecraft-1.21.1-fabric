"""Gate G1 guard probes: bad config must not crash the server.

Usage:
    python tools/g1_guards.py

Preconditions:
    ./gradlew runServer   (background)
    RCON_PORT / RCON_PASSWORD set

Why this exists: 03-里程碑.md Gate G1 says "坏配置不崩服".
The old repo proved this once; the port must re-prove it because ConfigService moved
to a new package and the quarantine path is derived from the world directory.
"""
import io
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARENAS = os.path.join(ROOT, "run", "world", "cww", "arenas")
INVALID = os.path.join(ROOT, "run", "world", "cww", "_invalid")

BROKEN_ID = "broken_probe"

# 忠实照抄 flat_test.json 的结构，只留一处干净的错：环带之间留 1 格空洞（20 → 21）
# 这就是 03 §6.2 里那条字段级报错示例，能同时验证"拒绝"与"报错定位"两件事。
BROKEN = {
    "schemaVersion": 1,
    "id": BROKEN_ID,
    "dimension": "minecraft:overworld",
    "bounds": {"minX": 920, "minY": 140, "minZ": 920, "maxX": 1081, "maxY": 160, "maxZ": 1081},
    "sea": {"centerX": 1000, "centerZ": 1000, "radius": 60, "waterLevel": 149, "generateY": 149},
    "rings": [
        {"fromRadius": 0, "toRadius": 20, "lootMultiplier": 2.5, "tier": 3},
        {"fromRadius": 21, "toRadius": 40, "lootMultiplier": 1.5, "tier": 2},
        {"fromRadius": 40, "toRadius": 60, "lootMultiplier": 1.0, "tier": 1},
    ],
    "spawns": {
        "teams": [
            {"teamIndex": 0, "x": 1000, "y": 149, "z": 952, "facing": "south"},
            {"teamIndex": 1, "x": 1000, "y": 149, "z": 1048, "facing": "north"},
        ],
        "auto": {"strategy": "equidistant", "minSeparation": 60},
    },
    "loot": {"exclude": [], "candidates": "auto",
             "prefill": {"enabled": False, "count": 0, "radiusBand": [40, 60]}},
    "hazards": [],
    "limits": {"maxRaftArea": 96, "maxBlocksPerRaft": 3200, "maxFacilities": 8},
}


def main():
    sock = rcon.connect()

    def cmd(text, quiet=False):
        out = rcon.run(sock, text) or ""
        if not quiet:
            print("  > %s\n    %s" % (text, out.replace("\n", "\n    ")[:500]))
        return out.strip()

    ok, bad = [], []

    def expect(cond, msg):
        (ok if cond else bad).append(msg)
        print("    %s %s" % ("OK  " if cond else "FAIL", msg))

    print("== 1. server alive before probe")
    before = cmd("cww phase", quiet=True)
    expect(any(p in before for p in ("LOBBY", "ENROLL", "READY", "PLAY", "SETTLE", "RETURN")),
           "rcon reachable and round machine answering (phase=%s)" % before[:40])

    print("== 2. drop a deliberately broken arena")
    os.makedirs(ARENAS, exist_ok=True)
    path = os.path.join(ARENAS, BROKEN_ID + ".json")
    with io.open(path, "w", encoding="utf-8") as fh:
        json.dump(BROKEN, fh, ensure_ascii=False, indent=2)
    expect(os.path.exists(path), "broken arena written: %s" % path)

    print("== 3. test must reject with field-level errors, not load it")
    out = cmd("cww arena test %s" % BROKEN_ID)
    expect("通过" not in out, "test did NOT pass the broken arena")
    expect("rings" in out, "rejection names the offending field (rings), not a generic message")
    expect("broken_probe" in out or BROKEN_ID in out, "rejection names the file")

    print("== 4. reload must reject AND quarantine the file")
    cmd("cww arena reload %s" % BROKEN_ID)
    time.sleep(1.0)
    expect(not os.path.exists(path), "broken file removed from arenas/ (quarantined)")
    quarantined = []
    if os.path.isdir(INVALID):
        quarantined = [f for f in os.listdir(INVALID) if BROKEN_ID in f]
    expect(bool(quarantined), "file landed in _invalid/ with a timestamp: %s" % quarantined)

    print("== 5. server must still be alive and healthy")
    alive = cmd("cww phase", quiet=True)
    expect("LOBBY" in alive or "ENROLL" in alive or "PLAY" in alive,
           "server still answers commands after a bad config (phase=%s)" % alive[:40])
    good = cmd("cww arena test flat_test", quiet=True)
    expect("通过" in good, "the valid arena still validates afterwards")

    print("\n=== G1 GUARD SUMMARY ===")
    print("satisfied : %d" % len(ok))
    print("FAILED    : %d" % len(bad))
    for f in bad:
        print("  FAIL %s" % f)
    for f in quarantined:
        os.remove(os.path.join(INVALID, f))
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
