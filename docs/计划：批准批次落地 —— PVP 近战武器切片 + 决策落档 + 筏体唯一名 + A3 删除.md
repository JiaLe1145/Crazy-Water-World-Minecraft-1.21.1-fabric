# 计划：批准批次落地 —— PVP 近战武器切片 + 决策落档 + 筏体唯一名 + A3 删除

## Context

用户已裁决（2026-11-07）：**[A17] 武器表批准**、**ADR-001 客户端模组前提确认**；其余按我建议：
伸手 3.5 / 落点 0.75 保留、互救不收紧、P-01 批准、许可证选 MIT、A3 建材重修删除、筏体唯一名要做、质量打折暂不做。
武器范围经追问定为 **§2.1 的 5 件近战 playable 切片**（木桨/砍刀/长矛/铁头锤/弯刀）——远程/控制/设施/流血骨折/弹药/风暴
依赖未建的 M3/M4 系统，分批后补。用户最终要一份**完整测试流程**去实机测手感。

分层与纪律照交接文档：`domain/` 纯函数零 MC import；Sable 调用只在 `sable/`；断言必须能失败；不留半成品。

---

## Part A — 决策落档（纯文档，低风险，先做）

| 文件 | 改动 |
|---|---|
| `docs/dev/00-决策与版本.md` | 新增 **ADR-001** 条目（玩家必须安装我们的 Fabric 客户端模组；武器/HUD/反作弊都挂它），补上 `09:75` 那句悬空引用的本体 |
| `docs/dev/PROPOSAL-P01-航行数值范式.md` | 状态 `待你批准` → **已批准（2026-11-07）**，注明帆/桨/控制器已按它落地 |
| `docs/content/09-武器与美术管线.md` | §2 表头 `[新增·待批 A17]` → **已批准**；注明本轮先落 §2.1 近战 5 件，其余分批 |
| `docs/content/02-数值与节奏.md` §8 | 伸手 3.5 / 落点 0.75 标 **已批准**；互救不收紧记一句裁定 |
| `LICENSE`（新增）+ `README.md` | **MIT**（Sable 是 PolyForm Shield，我们的代码 MIT 兼容；注明依赖许可） |
| `docs/dev/00-决策与版本.md` | 记一条许可证裁定（MIT） |

## Part B — 近战武器切片（核心交付）

**数值来源**：`09 §2.1`（伤害按 ♥×2 换 HP；攻速秒×20 换 tick）：
木桨 3HP/18tick/极高击退/对筏心+50%；砍刀 9HP/12tick/中；长矛 10HP/22tick/高/射程3.5；铁头锤 14HP/32tick/中/对筏心+150%；弯刀 7HP/7tick/低。
（流血/骨折/连击移速等特殊效果本轮**不做**，属 M3/M4，文档标明。）

**新增/改动文件**：
- `domain/combat/CombatRule.java`（纯函数）+ `CombatRuleTest`：攻速闸门（距上次有效攻击 < cooldown → 拒）、持有上限（每人 ≤1 近战）、伤害/击退查表。每道门单独用例 + 反证。
- `cfg/WeaponConfig.java` + `cfg/ConfigModels` 增 Weapon 记录 + `arena/BuiltinWeaponsJson.java`：`weapons.json` 加载，照 `ConfigService.loadArena`（`ConfigService.java:83`）的"world/cww/ 只读 + jar 内置回退"模式。内置资源 `src/main/resources/data/nbw/weapons.json`。
- `content/WeaponItem.java` + `content/NbwWeapons.java`：照 `NbwContent.register()`（`NbwContent.java:34`）的 `Registry.register(Registries.ITEM, Identifier.of("nbw", id), …)` 注册 5 件自定义 Item；伤害/攻速走 attribute modifiers（**实现时按 yarn build.3 映射核名，不猜**）。占位模型/贴图（`assets/nbw/models/item/*.json` + 16×16 占位 png），美术后补，**渲染失败不影响判定**。
- `combat/WeaponService.java`（适配层）：挂 `AttackEntityCallback.EVENT`（Fabric 已有，`BuildService` 用过 `AttackBlockCallback` 同源）——服务端记上次有效攻击 tick，超频取消并写审计；按表施加击退；只在对局 PLAY 内生效。`register()` 由 `NBW.onInitialize` 调用（`NBW.java:24` 之后）。
- `cmd/NbwCommands.java`：`/cww weapon list|reload|give <玩家> <id>`（admin 闸 + tell/reply 风格）；give 时查持有上限。
- 禁原版武器配方：datapack 移除原版剑/斧/弓/弩/三叉戟合成（`data/minecraft/recipe/` 覆盖；实现时核 1.21.1 移除机制）。**物品本体不禁**（09 §1.5 已批准）。
- `tools/pvp_probe.py`（新）：独立读回——给砍刀→打victim→`data get entity Health` 差==9HP；超频攻击被拒+审计在案；持有上限拒绝第二件近战；非 PLAY 不生效。三态汇总，照 `rescue_probe` 骨架。

## Part C — 筏体唯一名（#38， approved）

- `raft/RaftSpawner.java`：命名 `nbw_raft_<队>` → `nbw_raft_<队>_<matchIndex>`（matchIndex 来自 `RoundManager.machine().matchIndex()`）。
- `rebindFromWorld`（`RaftSpawner.java:269`）改为按"队号+当前 match"解析名字反查（不再靠静态名）；残留复检（`:203`）按前缀匹配。
- **blast radius**：所有用 `sable info @e[name=nbw_raft_<队>]` 的探针（sea/oar/rescue/build/m2_round/raftgen/sink）需改成按当前 match 名或前缀查。逐个更新并复跑。
- 验证：`raftgen_probe`、`oar_restart_probe`、`m2_round` 全绿（重名歧义 WARN 归零）。

## Part D — A3 建材重修删除（approved）

- 删 `RaftSinking.repair()`（`:160`）+ `cww raft repair` 命令（`NbwCommands.raftRepair`）+ `SinkRule.reset/MAX_RESETS/resetsUsed`（`SinkRule.java:22/122`）中**仅属于 A3 建材重修**的部分。
- **注意**：代码里 A2-v2 与 A3 曾"合并成一个动作共享额度"（交接 §8.3）。实现前先读 `SinkRule.next`（`:80` 内部调 reset）厘清哪条是 A2-v2（残骸修复解除倒计时，**保留**）哪条是 A3（建材重修重置，**删**），不误删 A2-v2。
- **blast radius**：`SinkRuleTest`、`core_probe`（测 repair）需更新。验证 `sink_probe`/`core_probe`/`m2_round` 全绿。

---

## 执行顺序

A（文档）→ B（武器，最大块）→ 跑 pvp_probe + 回归 → C（唯一名）+ 探针更新 → D（A3）+ 探针更新 → 全量回归 → 文档同步（M2-RESULTS 新章节 + 交接文档）→ 提交。
B 先做是因为用户要尽快测手感；C/D 改动域代码与探针，放后面单独验。

## Verification（端到端，必须真跑）

1. `./gradlew test`（含新 CombatRuleTest；SinkRuleTest 按 D 更新）。
2. 起服（重定向 `run/server-console.log`）→ `pvp_probe.py` 目标 PASS。
3. 回归：`rescue_probe`、`oar_probe`、`core_probe`、`sink_probe`、`raftgen_probe`、`m2_round`（C/D 改了命名与沉筏，这些必复跑）。
4. 两次连续绿排除环境残留。

## 交付给用户的完整手感测试流程（写在交付说明里）

1. 构建：`./gradlew build`，jar 在 `build/libs/`（或 `dist/`）。
2. 客户端装我们的 Fabric 模组 + Sable + Fabric API（ADR-001 前提），进 dev 服。
3. `/cww start sail_long test_sea` 开局；`/cww weapon give <自己> cutlass`（或 pike/warhammer/twin_blade/oar）拿武器。
4. 手感清单：① 伤害是否符合表（砍刀 4.5♥）；② 攻速冷却条与超频被拒；③ 击退（木桨极高能否把人打落水）；④ 长矛 3.5 格射程；⑤ 弯刀 0.35s 连击节奏；⑥ 持有上限（拿第二件近战被拒）；⑦ 占位模型是否影响判定（不应）。
5. 反馈给我据此调数值/手感。

## 风险

- attribute modifiers / 配方移除的 yarn 名与 1.21.1 机制须实现时核映射，不猜（交接 C2）。
- C/D 改域代码与多支探针，blast radius 大，单独提交单独验。
- 武器特殊效果（流血/骨折/连击）本轮明确不做，文档标明，避免被当成漏做。