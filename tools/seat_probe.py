"""幽灵席位清扫探针（#41 根因）：kill 掉假玩家后，报名期兜底必须放掉他的会话与队席。"""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.getcwd(), "tools"))
from sea_probe import LOG, P  # noqa: E402

p = P()
ok, bad = [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("    %s %s" % ("OK  " if cond else "FAIL", msg))


def online(name):
    return name.lower() in p("list").lower()


print("== 1. 上线两个假玩家并分到队 0")
for n in ("h1", "h2"):
    p("player %s kill" % n)
for n in ("h1", "h2"):
    p("player %s spawn" % n)
for _ in range(30):
    if online("h1") and online("h2"):
        break
    p.sleep(1)
expect(online("h1") and online("h2"), "两个假玩家必须都在线：%s" % p("list")[:120])
f1 = p("cww force h1 0")
f2 = p("cww force h2 0")
expect("已满" not in f1 and "找不到" not in f1, "h1 指派到队 0（回：%s）" % f1[:70])
expect("已满" not in f2 and "找不到" not in f2, "h2 指派到队 0（回：%s）" % f2[:70])

print("== 2. kill 掉 h1（Carpet 不触发 DISCONNECT，会话就此变成幽灵）")
p("player h1 kill")
for _ in range(20):
    if not online("h1"):
        break
    p.sleep(1)
expect(not online("h1"), "h1 确实已下线")
audit_before = p("cww audit 20")
print("      kill 后审计尾部：%s" % audit_before[:110])

released_line = ""
for _ in range(25):
    p.sleep(1)
    with open(LOG, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    hit = re.search(r"\[round\] 报名期放掉 (\d+) 个不在场的席位（事件漏报兜底）：([^\n]*)", text)
    if hit:
        released_line = hit.group(0)
        break
expect(bool(released_line), "报名期兜底必须放掉不在场的席位（日志要有那行 WARN）")
print("      %s" % released_line[:160])
expect("h1" in released_line, "被释放的必须是 h1（含队席信息）：%s" % released_line[:120])

print("== 3. 席位腾出来后，重新上线的 h1 必须能再被指派")
p("player h1 spawn")
for _ in range(30):
    if online("h1"):
        break
    p.sleep(1)
expect(online("h1"), "h1 重新上线")
f3 = p("cww force h1 0")
expect("已满" not in f3 and "找不到" not in f3, "重新指派必须成功（回：%s）" % f3[:70])
for n in ("h1", "h2"):
    p("player %s kill" % n)

print("\nRESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
for b in bad:
    print("   FAIL - %s" % b)
print("satisfied: %d" % len(ok))
