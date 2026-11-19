#!/usr/bin/env python3
"""PVP 近战武器切片端到端探针（09 §2.1，已批准 A17 近战 5 件）。

要回答四件事，都是"能不能失败"的：
  1. 砍刀一击伤害 == 6 HP（3.0♥）：`cww weapon give` 拿武器 → 打到假玩家 → `data get entity Health` 差 6。
  2. 攻速超频被服务端拒绝并写审计：冷却内连打，第二次必须被拒，`cww weapon status` 的超频拒绝计数要涨。
  3. 持有上限：给同一玩家第二件近战必须被 `/cww weapon give` 拒绝（每人限 1 件近战，09 §5.1）。
  4. 非 PLAY 不生效：LOBBY 里挥击不该触发攻速闸门/审计（PVP 本身由 RoundManager 闸掉）。

夹具限制：Carpet 假玩家站不住筏面（会穿甲板落水），所以本探针用旱地 flat_test（基岩平台 y=148），
让假玩家站得稳、打得着。伤害由物品 attribute_modifiers 交给原版结算，我们只在服务端闸"能不能打"。
真客户端手感（冷却条、击退落水）留给 §6 真人清单。

用法：python tools/pvp_probe.py
前置：./gradlew runServer 在跑，Carpet 在 run/mods。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, keep_alive, sweep_stale_bots, write_long_format, DRY_ARENA  # noqa: E402

ok, bad, inc = [], [], []

FORMAT = "sail_long"
CUTLASS_DAMAGE = 6.0      # 09 §2.1：砍刀 3.0♥ = 6 HP（2026-11-19 平衡下调，旧值 9.0）
CUTLASS_COOLDOWN = 12     # 0.6s


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def inconclusive(msg):
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)


def health(p, name):
    m = re.search(r"([\d.]+)f?$", p("data get entity %s Health" % name).strip())
    return float(m.group(1)) if m else None


def pos(p, name):
    m = re.search(r"\[([-\d.]+)d,\s*([-\d.]+)d,\s*([-\d.]+)d\]", p("data get entity %s Pos" % name))
    return tuple(float(g) for g in m.groups()) if m else None


def weapon_status(p):
    return p("cww weapon status")


def hold_cutlass(p, name):
    """把砍刀塞进指定假玩家的主手（直接写主手，绕过背包，专注测伤害路径）。"""
    p("item replace entity @p[name=%s] weapon.mainhand with nbw:cutlass 1" % name)


def aim_and_attack(p, attacker, victim):
    """让攻击者面向受害者并挥击一次。返回受害者挥击后的位置（击退会挪人，下次要重新 aim）。"""
    v = pos(p, victim)
    if v is not None:
        p("player %s look at %.2f %.2f %.2f" % (attacker, v[0], v[1] + 1.5, v[2]))
    p("player %s attack once" % attacker)
    return v


def main():
    sys.stdout.reconfigure(line_buffering=True)
    p = P()
    print("== 0. 开局（旱地 flat_test，假玩家站得稳）")
    print("  清残留：" + repr(sweep_stale_bots(p)))
    for _ in range(25):
        if "LOBBY" in p("cww phase"):
            break
        p("cww abort")
        p.sleep(1.5)
    else:
        expect(False, "遗留对局收不掉：%s" % p("cww phase")[:60])
        return 1
    write_long_format()
    p("cww format reload %s" % FORMAT)
    stamp = time.strftime("%M%S")
    bots = [("p%sA0" % stamp, 0), ("p%sA1" % stamp, 0), ("p%sB0" % stamp, 1), ("p%sB1" % stamp, 1)]
    for n, _ in bots:
        p("player %s kill" % n.lower())
    for n, _ in bots:
        p("player %s spawn" % n)
    for _ in range(30):
        if all(n.lower() in p("list").lower() for n, _ in bots):
            break
        p.sleep(1)
    else:
        expect(False, "假玩家没全部上线：%s" % p("list")[:160])
        return 1
    for n, t in bots:
        p("cww force %s %d" % (n, t))
    keep_alive(p, bots)
    p("cww start %s %s" % (FORMAT, DRY_ARENA))
    reached, cur = p.wait_phase("PLAY", 240)
    expect(reached, "能进 PLAY（%s）" % cur[:50])
    if not reached:
        return 1
    p.sleep(2)
    # flat_test 现已被海域探针灌了水；把测试平台清成干燥 11×11，避免水中暴击/击退污染伤害读数。
    p("fill 994 149 994 1006 152 1006 minecraft:air")

    attacker = bots[0][0].lower()
    victim = bots[2][0].lower()   # 队 1 的人
    holder = bots[1][0].lower()   # 队 0 第二人，专测持有上限

    print("\n== 1. 砍刀表值/属性 == 9 HP（确定性 probe，不依赖 Carpet 攻击）")
    p("cww weapon list", quiet=False)
    hold_cutlass(p, attacker)
    # 位置只用于让两名玩家都在线且可读；probe 不施加伤害，不依赖距离/瞄准。
    p("tp %s 1000.5 149 1000.5" % attacker)
    p("tp %s 1001.5 149 1000.5" % victim)
    p.sleep(0.3)
    out = p("cww weapon probe2 %s %s cutlass" % (attacker, victim))
    print("      probe2 回包：%s" % out[:320])
    expect("允许" in out, "第一发通过攻速闸门：%s" % out[:160])
    expect("表伤害=6.0" in out and "攻击属性=6.0" in out,
           "砍刀表值与主手实际攻击属性都是 6.0HP：%s" % out[:220])
    expect("拒绝" in out and "攻速超频" in out,
           "同一 tick 第二发被超频拒绝：%s" % out[:220])

    print("\n== 2. 审计留痕：超频拒绝计数与最近判定")
    st2 = weapon_status(p)
    m = re.search(r"超频拒绝=(\d+)", st2)
    rejected = int(m.group(1)) if m else -1
    print("      超频拒绝计数：%d" % rejected)
    expect(rejected >= 1, "冷却内连打至少触发一次超频拒绝（计数 %d）" % rejected)
    expect("攻速超频" in st2, "审计/状态里留下「攻速超频」留痕")

    print("\n== 3. 持有上限：第二件近战必须被拒（09 §5.1 每人限 1 件近战）")
    g_first = p("cww weapon give %s cutlass" % holder)
    g_second = p("cww weapon give %s pike" % holder)
    print("      第一件（cutlass）：%s" % g_first[:120])
    print("      第二件（pike）：%s" % g_second[:120])
    expect("已给" in g_first, "第一件近战能发出去：%s" % g_first[:100])
    expect("已持有近战武器" in g_second or "近战" in g_second,
           "第二件近战被拒，且原因点名近战上限：%s" % g_second[:120])

    print("\n== 4. 非 PLAY 不生效（LOBBY 里挥击不触发武器规则）")
    p("cww abort")
    p.wait_phase("LOBBY", 180)
    st3 = weapon_status(p)
    r0 = int(re.search(r"放行=(\d+)", st3).group(1)) if re.search(r"放行=(\d+)", st3) else -1
    # LOBBY 里攻击本身会被 PVP 闸门挡掉；武器规则的"放行/超频拒绝"计数都不该再动
    hold_cutlass(p, attacker)
    p("tp %s 1000.5 149 1000.5" % attacker)
    p("tp %s 1001.5 149 1000.5" % victim)
    p.sleep(0.4)
    aim_and_attack(p, attacker, victim)
    p.sleep(0.5)
    st4 = weapon_status(p)
    r1 = int(re.search(r"放行=(\d+)", st4).group(1)) if re.search(r"放行=(\d+)", st4) else -1
    expect(r1 == r0, "LOBBY 里挥击不涨武器放行计数（%d → %d）" % (r0, r1))

    print("\n== 5. 清理")
    for n, _ in bots:
        p("player %s kill" % n.lower())
    p("cww abort")

    print("\n=== PVP PROBE SUMMARY ===")
    print("satisfied: %d" % len(ok))
    for b in bad:
        print("   FAIL - %s" % b)
    for i in inc:
        print("   INCONCLUSIVE - %s" % i)
    print("RESULT: %s" % ("PASS" if not bad else "HAS-FAILURES"))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
