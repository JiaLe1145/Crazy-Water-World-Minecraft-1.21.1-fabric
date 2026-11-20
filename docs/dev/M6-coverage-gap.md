# M6 全量测试计划 · 覆盖缺口审计与补测清单

> 审计对象：`docs/dev/M6-FULL-TEST-PLAN.md`（A–S 共 19 域、**153 项**，截至本审计全部未勾选）
> 审计快照：**2026-11-21 03:14 +08:00**，`git HEAD = f9d202e`
> 覆盖判据与行号约定见 §1 前的"审计口径"。本文件只做审计与方案，**未运行 RCON、未起停服、未改任何源码/探针**。

---

## 0. 审计口径（先读这一节）

1. **"已覆盖"的定义**：仓库里存在一个**自动化断言**直接命中该计划项的**可自动观测部分**，且断言带实测读数（符合交接文档 §7 纪律）。
   - 主要证据 = `tools/*_probe.py`（含旧的两段式/M6 前脚本 `tools/m1_round.py`、`m2_round.py`、`g1_guards.py`、`restart_probe.py`）。
   - 次要证据 = `src/test/java` 单测（只在计划项天然是纯逻辑、或服务端无法造出该状态时计入，并在表中标"仅单测"）。
   - **"部分覆盖"计入缺口**（在 §2 中注明"部分"，避免把半条断言当已收口）。
2. **行号约定**：不特别说明时行号指 **git HEAD `f9d202e`** 的版本。审计期间工作树有**并发未提交改动**（见第 4 条），`tools/sea_probe.py / sail_probe.py / wind_probe.py` 等行号在工作树里已整体漂移，抽查时请用 `git show HEAD:tools/<f>.py` 对齐。
3. **命令与读数全部从代码核对**：`src/main/java/dev/nbw/cmd/*.java` 的 `literal()/argument()/then()` 是命令名唯一来源；读数文案取自对应 Service 的 `status()`。找不到实现依据的断言在文中标「待核对」。
4. **审计期间存在并发补测流（重要）**：别的会话正在同一工作树里新增未提交文件并在同一台 dev 服上跑测试。审计快照时的工作树状态：

   | 状态 | 文件 | 对本报告的影响 |
   |---|---|---|
   | 已修改未提交 | `tools/run_all_probes.py`、`sea_probe.py`、`sail_probe.py`、`wind_probe.py`、`build_probe.py`、`craftsman_probe.py`、`helmsman_probe.py`、`survivor_probe.py` | `restart_probe` 已移出批量、新增 RCON 预检、新增"干净 LOBBY"新口径、sail/wind 旧造海夹具修复（详见 §4） |
   | 未跟踪新增 | `tools/m6_config_probe.py`、`tools/m6_round_probe.py`、`tools/m6_world_repair.py`、`tools/check_resources.py` | 分别针对 **A2–A8+C1**、**B1–B7/B10–B12+L3/L5/L6**、世界夹具复位、**Q1–Q8**；**均未提交、未验证**，本报告不把它们计入"已覆盖"，只作为"已在补"提示 |

   **销项规则**：§1 的覆盖数按 HEAD 计；并发新增脚本跑绿并提交后，对应缺口行可直接销项（§2 每行已标目标域）。**审计定稿时并发流仍在追加新文件**（如 `tools/m6_edge_probe.py`、`docs/dev/M6-Q-RESOURCES.md`），以 `git status` 实时状态为准。

---

## 1. 总览表（A–S）

计数口径：计划 153 项（不是"两百多项"；按 `M6-FULL-TEST-PLAN.md` 逐项数得）。"已覆盖 41 / 缺口 112"，缺口里有一部分只是"部分覆盖/只差一条断言"（在 §2 各行注明），补测成本差别很大，见"建议方式"列。

| 域 | 项数 | 已覆盖 | 缺口 | 缺口编号 | 补测建议方式 |
|---|---|---|---|---|---|
| A 配置与启动 | 8 | 3 | 5 | A1 A2 A3 A4 A8 | A1 单独停/起服（干净世界）；A2–A4/A8 RCON 脚本一次跑完 |
| B 对局生命周期 | 12 | 7 | 5 | B2 B3 B4 B11 B12 | B2–B4/B12 RCON 脚本；B11 客户端实机（假玩家 UUID 会变） |
| C 地图与重置 | 6 | 1 | 5 | C2 C3 C4 C5 C6 | C2–C5 夹具 arena + 日志断言；C6 客户端目视 |
| D 筏与航行 | 10 | 7 | 3 | D1 D2 D7 | 补"出生点/质量随方块数"断言；D7 当前无 dev 划桨入口，待核对 |
| E 战斗 | 14 | 4 | 10 | E1 E2 E3 E4 E5 E6 E9 E10 E13 E14 | 武器表驱动脚本（`cww weapon list/probe/ranged`）+ 客户端 |
| F 设施 | 14 | 2 | 12 | F1 F4 F5 F6 F7 F8 F9 F10 F11 F12 F13 F14 | 设施命令夹具脚本（ballista/catapult/ram/core/raftcore）+ 客户端 |
| G 生存与幸存者 | 10 | 0 | 10 | G1–G10 | `cww survival/survivor` 读数脚本；G3/G10 客户端 |
| H 物资与电量 | 6 | 0 | 6 | H1–H6 | `cww loot/power` 读数 + 方块扫描脚本 |
| I 事件与黑海 | 8 | 2 | 6 | I1 I2 I3 I5 I7 I8 | `cww event/zone` 脚本；I8 客户端 |
| J 救援与遥控 | 8 | 4 | 4 | J1 J2 J3 J8 | RCON 脚本（remote/rope/grapnel/rescue） |
| K 建造限制 | 6 | 5 | 1 | K5 | 先澄清计划：代码里没有"建造耗电/充能"门槛 |
| L 观战与结算 | 6 | 1 | 5 | L1 L2 L3 L4 L6 | L3/L4/L6 RCON+日志/文件；L1/L2 动作条与视角需客户端 |
| M 持久化 | 5 | 2 | 3 | M3 M4 M5 | 两段式（before→停服→起服→after）脚本 |
| N 网络与反作弊 | 5 | 2 | 3 | N2 N3 N4 | N2 RCON；N3/N4 需伪造客户端或读数命令（待核对） |
| O 权限 | 4 | 0 | 4 | O1 O2 O3 O4 | O1–O3 客户端实机（RCON 是权限 4，无法降权）；O4 RCON `help cww` |
| P 客户端表现 | 10 | 0 | 10 | P1–P10 | 客户端实机截图（已有 `run/_shot_*.png` 素材，但未逐项映射） |
| Q 资源完整性 | 8 | 0 | 8 | Q1–Q8 | 离线静态扫描；并发新增的 `tools/check_resources.py` 已针对 Q1–Q8（未提交） |
| R 性能与稳定 | 5 | 1 | 4 | R1 R2 R4 R5 | TPS/日志采样脚本；R3 已有 `sea_probe --big`；R5 30min soak |
| S 边界与并发 | 8 | 0 | 8 | S1–S8 | RCON 边界脚本（0/1/满员/重复/竞态） |
| **合计** | **153** | **41** | **112** | — | — |

> 并发补测流的现状对照：A2–A8/C1 → `tools/m6_config_probe.py`；B1–B7/B10–B12/L3/L5/L6 → `tools/m6_round_probe.py`（其 B11 写法有问题，见 §4.9）；Q1–Q8 → `tools/check_resources.py`；世界夹具复位 → `tools/m6_world_repair.py`。**这些脚本跑绿并提交前，§1 仍按缺口计。**

---

## 2. 缺口明细

### 2.1 A 域（配置与启动，缺口 5）

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| A1 | 干净世界首启：`configs loaded` 成功、无 `读取失败` | `sea_probe.py` §0（HEAD L303–306）只断言"函数 0 条 Failed to load + 空闲起服无 tick 落后"，**从未断言配置装载行**；`M6-FULL-TEST-PLAN` 要求"干净世界"必须移动 `run/world/cww` 并重启，没有脚本做 | 停服 → 把 `run/world/cww` 改名为 `run/world/cww.bak`（等价"全新安装"）→ 起服等 `Done (` → 从日志新段 grep；再 RCON 读状态 | 日志含 `[round] configs loaded: arena=default_sea format=standard_12min spec=4x3 min=4 lobby=(0,201,0)`（`RoundManager.java:1029`）；0 条 `读取失败`/`被拒绝`/`ERROR`；`cww state` 含 `phase=LOBBY ... lobby=已加载` | 停服恢复 `cww.bak`；重起（或把干净世界留作下一项 A2–A4 的基准） |
| A2 | 4 张内置赛制全部 `cww format test <id>` 通过 | 只有旧脚本 `m1_round.py:L192` 覆盖 1 张（test_3min），不在批量；其余探针把 `format reload` 当夹具用，不跑 `format test` | 任一空闲阶段（只读）：`cww format test oar_long` → `sail_long` → `standard_12min` → `test_3min` | 每条含 `校验通过；圈阶段 N 段，规格 2x4/2x2/4x3/2x2，目标局长 1000/1000/720/180s`（`ArenaCommands.java:226-228`） | 无 |
| A3 | 6 张内置图全部 `cww arena test <id>` 通过 | `sea_probe.py:L333` 只测 test_sea；`m1_round.py:L191` 只测 flat_test；**且工作世界已有 `run/world/cww/arenas/{test_build,test_sail7}.json` 本地覆盖，会顶掉 2 张"内置"图**（§4.3） | 先清本地覆盖（见清理）→ 逐个：`cww arena test default_sea`、`flat_test`、`test_build`、`test_sail7`、`test_sea`、`test_side7` | 每条含 `校验通过；来源=jar 内置默认图；环带 N 段，初始半径 X`；若出现 `来源=world/cww/arenas 文件` 说明环境不干净，不算"内置通过"（`ArenaCommands.java:132-135`） | 测前把 `test_build.json`/`test_sail7.json` 移出 `run/world/cww/arenas`（备份到 `run/_fixtures_backup/`）；测完不恢复，等需要夹具的探针自己重建 |
| A4 | `cww lobby test` 通过 | 只有旧脚本 `m1_round.py:L193-194`；批量探针无 | `cww lobby test`（再跑一次 `cww lobby reload` 验热载） | `lobby 校验通过；出生点 (0,201,0) pvp=false 分队点=2`（`ArenaCommands.java:272-275`）；`reload` 多出 `已装入内存` | 无 |
| A8 | `format reload` / `arena reload` 热重载生效且**对局中禁止** | `build_probe.py:L399-402` 只在 LOBBY 断言 reload "没有被拒绝"；`wait_clean_lobby` 把 `format reload` 当夹具（`sea_probe.py:L282`），**无人断言对局中被拒** | LOBBY：`cww format reload test_3min`、`cww arena reload flat_test`（各含 `已装入内存`）→ 2 假玩家 `cww force ...` → `cww start test_3min flat_test` → 等 PLAY → 两个 reload 各发一次 → `cww abort` → 回 LOBBY 后再各发一次 | PLAY 中：`对局进行中（阶段 PLAY）禁止替换正在使用的 arena`；format 那行还带 `（换赛制同样禁止）`（`RoundManager.java:1126-1128`、`ArenaCommands.java:243-247`）；回 LOBBY 后恢复 `已装入内存` | `cww abort`；`player <bot> kill`；`cww format reload test_3min` 复位内存配置 |

### 2.2 B 域（对局生命周期，缺口 5）

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| B2 | 非 LOBBY 阶段 `cww start` 被拒 | 无任何探针在 ENROLL/READY/PLAY 里再发一次 `start`；`RoundManager.start()` 的拒绝分支（`RoundManager.java:1038-1040`）从未被服务端测过 | 2 假玩家 `cww force` → `cww start test_3min flat_test` → 在 ENROLL 里立刻再 `cww start test_3min flat_test`；READY/PLAY 各再试一次 | 均回 `已有对局/报名在进行中（阶段 ENROLL/READY/PLAY），先 /cww stop 或 /cww abort`；`cww phase` 阶段不因重复 start 改变 | `cww abort` |
| B3 | LOBBY 阶段 `cww stop` / `cww abort` 被拒（转移表） | 无人测"空转表"拒绝；`fire()` 的拒绝文案（`RoundManager.java:1086-1090`）没有探针采样 | 干净 LOBBY 下直接 `cww stop`、`cww abort` | `阶段 LOBBY 不接受 ADMIN_STOP（转移表见 04 §3）`、`...ADMIN_ABORT...`；`cww audit` 里出现 `拒绝非法转移：LOBBY + ADMIN_STOP` 备注（`RoundMachine.java:434`） | 无（仍 LOBBY） |
| B4 | 报名人数不足下限时拒绝开局 | 只有单测 `RoundMachineTest.autoStartFiresOnlyWhenEnrollmentReachesMin`；服务端 `start()` 的人数分支（`RoundManager.java:1049-1052`）无探针 | 只 spawn **1** 个假玩家 → `cww force <bot> 0` → `cww start test_3min flat_test` | `报名 1 人 < 下限 2；用 /cww join 报名或 /cww force <玩家> <队伍> 拉人`；`cww phase` 仍是 LOBBY | `player <bot> kill` |
| B11 | 掉线 90s 内重连 → 不误判淘汰 | **假玩家不可测**：Carpet `player <名> kill` + `spawn` 会换 UUID（`docs/dev/M2-RESULTS.md:300`、`tools/core_probe.py:21` 注释），`RoundMachine.onReconnect`（`RoundMachine.java:474-480`）按 UUID 找会话，假玩家永远匹配不上；单测 `RoundMachineTest.reconnectWithinSeatWindowRestoresPlayer` 只覆盖 domain | **客户端实机**：真人账号在 PLAY 中拔线（或 `kick`）→ <90s 内重连 → RCON 读 `cww state` | 该席位不出现 `[淘汰]`；`cww audit` 无 `掉线席位到期视为淘汰：<名>`；玩家继续正常传送/操作（`placeByPhase`，`RoundManager.java:546-563`） | 对局正常结束或 `cww abort` |
| B12 | 结算后副作用齐全（战绩落盘／生涯累加／模式切回冒险） | 部分覆盖：残留复检有（`raftgen_probe.py:L170-174`、`m2_round.py:L313`）；战绩落盘有（`stats_probe.py:L97-117`）；但**生涯累加无任何 committed 断言**，"模式切回冒险+物品清空"只有旧脚本 `m1_round.py:L297-299` | 完整一局制造 WINNER_DECIDED（可 `execute as @a run kill @s` 或等保席到期）→ 等 SETTLE→RETURN→LOBBY → 读 `cww state`、`cww audit`、日志、文件 | `cww state` 有 `lastResult=matchN winner=蓝队 cause=WINNER_DECIDED dur=Ns participants=2 survivors=1`（`RoundMachine.java:747-752`）；`run/world/cww/state/matches/<N>.json` 的 winner/cause/durationSec/players[].kills/deaths/damage 与读数一致（`CombatStats.java:155-194`）；`run/world/cww/career.json` 胜者队成员 `wins` +1、kills 累加（`CareerService.java:97-111`）；幸存者 `data get entity <bot> playerGameType` 应为 `2`（Adventure，`RoundManager.java:414`+`placeInLobby`）；日志 `[S10] residue=CLEAN` | `cww abort`（若还在收尾）；`player <bot> kill` |

> 已覆盖但建议复核：B1 全链路只有旧脚本 `m1_round.py:L239-291`；B7/B10 只有旧脚本 `m2_round.py:L279-298`。**建议批量前各跑一遍旧脚本确认仍绿**（它们不在 `run_all_probes.py` 的 PROBES 列表里）。

### 2.3 C 域（地图与重置，缺口 5）

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| C1 | `cww arena terrain <id>` 5 个采样列全部符合水面/海床 | `sea_probe.py` 只测 test_sea（2 队 → 3 列，HEAD L347-351）；**5 列只对 4 队图成立（default_sea）**，而 default_sea 全量建海只在 `sea_probe.py --big`（HEAD L640-652）里，批量默认不跑；当前世界 default_sea 是否已建是环境未知项 | 先建图：`python tools/sea_probe.py --big`（或 `function nbw:arena/default_sea/build` 并等队列）→ `cww arena terrain default_sea` | 回包含 `采样 5 列，问题 0 列`；每列 `水面 y=62 / 海床顶 y=52`（`default_sea` 分层：水 53..62、海床 50..52，`build.mcfunction` 头注释；`ArenaCommands.java:163-170`、`SeaTerrain.java:42-65`） | 建好的海保留（是后续夹具）；不再用时用 `function nbw:arena/default_sea/release` 撤票 |
| C2 | 每局 READY 阶段模板复制完成（`cww reset status` 从"进行"到"空闲"） | 无任何探针读 `cww reset status`；只有 `worldbuild.py:L130` 调过一次 `cww reset buildtemplate`，不涉及每局复制 | 前置 default_sea 已建。LOBBY：`cww reset buildtemplate`（建模板区）→ 2 假玩家 + `cww format reload sail_long`（2x2，minPlayers=2）→ `cww start sail_long default_sea` → ENROLL 30s→READY 10s（阶段时长由 `RoundConfig.fromFormat` 固定 30/10，与 format 的 `duration.phases` 无关）期间轮询 `cww reset status` 并读日志新段 | 日志含 `[reset] 模板复制开始 src=(140,62,140)..(147,69,147) dst=(-4,62,-4)..(3,69,3) 共 512 块` 与 `[reset] 模板复制完成（512 块）`（`MapResetService.java:52-55,74-77`）；`cww reset status` 文案是 `[reset] 复制中 n/512 块`/`[reset] 空闲`（**512 块 < 8192/tick，可能一 tick 就完成，"从进行到空闲"只能用日志两行取证**） | `cww abort`；无需改世界（下局 READY 会再覆盖） |
| C3 | 中心粘贴区被还原成模板内容 | 无探针比对"模板区↔粘贴区"方块；`worldbuild.py` 只建模板 | 接 C2。赛前先在 LOBBY 破坏贴合区一格：`setblock -4 62 -4 minecraft:air`（负对照：`execute if block -4 62 -4 minecraft:air` 应"通过"=空回包）→ 开一局到 READY 完成 → 用 `execute if block <x> <y> <z> <block>` 验（成功回包为空、"Test failed" 为失败；`loot_clearance_probe.py:L56-57` 同口径） | `execute if block -4 62 -4 minecraft:stone_bricks` → 空回包；特征块：模板 chest 在 `(143,63,143)` → 贴合区 `(-1,63,-1)`：`execute if block -1 63 -1 minecraft:chest` → 空回包（`build_template.mcfunction`） | 无需（下局覆盖）；若想恢复"干净中心"，再跑一次开局即可 |
| C4 | `noReset` 图正确跳过 | 6 张内置图**没有任何一张 `noReset:true`**（只有 default_sea 有 reset 且 `noReset:false`）→ 计划项当前**没有夹具**，探针也从未覆盖 | 写夹具 `run/world/cww/arenas/m6_noreset.json`（复制 `flat_test.json`，`id` 改 `m6_noreset`，加 `"reset":{"noReset":true}`；名字必须匹配 `[a-z0-9_]{1,32}`）→ LOBBY `cww arena reload m6_noreset` → 2 假玩家 `cww start test_3min m6_noreset` → 等 READY | 日志含 `[reset] 地图 m6_noreset 声明 noReset，跳过还原` 且**不含** `[reset] 模板复制开始`（`MapResetService.java:42-45`）；`cww reset status` = `[reset] 空闲`；服务端无 ERROR | `cww abort`；删夹具文件；`cww arena reload flat_test` 复位内存 |
| C5 | 缺 template/paste 配置 → 跳过且不崩 | 同上，无夹具；且配置校验**不检查 reset 完整性**（`ConfigService.validateArena` 不含 reset 字段），所以这条只能靠日志 | 写夹具 `run/world/cww/arenas/m6_partialreset.json`（flat_test 副本 + `"reset":{"noReset":false,"resetTemplateArea":{...8×8×8...}}`，不给 `resetPasteArea`）→ `cww arena test m6_partialreset`（应通过）→ 开局到 READY | 日志含 `[reset] 地图 m6_partialreset 的 reset 配置缺 template/paste 区，跳过`（`MapResetService.java:46-49`）；`cww phase` 正常响应；无 ERROR | 同 C4 |
| C6 | 客户端目视：海面平整/海床可见/中心残骸存在 | 纯视觉项 | **客户端实机**：进 default_sea 观战/PLAY，截图 3 张（海面、水中向下看海床、中心残骸） | 截图入 `run/_shot_m6_C6_*.png`；海面 y=62 平整、海床顶 y=57 可见、中心 8×8×8 残骸与模板一致 | 无 |

### 2.4 L 域（观战与结算，缺口 5）

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| L1 | 淘汰后立即旁观 + 提示 | 部分覆盖：`spectator_probe.py:L59-75` 已断言日志 `[round] spectator notice: <名>｜观战中｜存活队友：<队友>` 与 `cww state` 的 `[淘汰]`；但"**立即旁观**"= `changeGameMode(SPECTATOR)`（`RoundManager.java:146-152`）没有服务端读数（玩家实体死亡即移除，RCON 读不到 `playerGameType`） | 服务端侧沿用 `spectator_probe`；表现侧**客户端实机**（真人账号在 PLAY 中被打死）观察是否立即切旁观视角 | 服务端：日志 notice + `cww state [淘汰]`（已有）；客户端：死亡瞬间切旁观、能看到存活队友视角 | `cww abort` |
| L2 | 观战 actionbar 显示战况 | `broadcastSpectatorStatus`（`RoundManager.java:705-720`）每 40 tick 只发 actionbar 包，**无日志、无读数命令**，RCON 读不到 | **客户端实机**：淘汰后停在图里，观察 actionbar 是否每 2s 刷新 `[cww] 观战中｜存活队友：X` | 客户端截图/录屏；服务端侧间接证据 = `cww state` 的 `[淘汰]` + 日志 notice | `cww abort` |
| L3 | 结算广播胜者/原因/时长/存活 | 无 committed 断言（`m2_round.py:L290-295` 只 grep `获胜/平局/WINNER_DECIDED`，**不验时长/存活/格式**）；`stats_probe` 只读文件 | 制造 SETTLE（WINNER_DECIDED / ADMIN_STOP / 超时均可）→ 读日志新段与 `cww state` | `[round] broadcast: 本局结束：<队> 获胜｜原因 WINNER_DECIDED｜时长 <n>s｜存活 <a>/<b>`（`RoundManager.java:405-407`，颜色符被 `announce` 去掉，`RoundManager.java:1151`）；平局时为 `平局（未分出唯一存活队）`；`cww state` 的 `lastResult=` 行三字段一致 | 收尾/abort |
| L4 | 战绩面板（击杀/死亡/伤害） | `stats_probe.py:L89-117` 只验 `cww stats` 名字与 JSON 的 kills/deaths/damage；**SETTLE 广播的"战绩面板"段**（`CombatStats.summary`，`CombatStats.java:80-100`）无断言 | 接 L3：读日志 SETTLE 广播段 | 日志含 `[round] broadcast: —— 本局战绩 ——` 与 `击杀王 1. <名>：击杀 N｜伤害 M`（伤害保留 1 位小数），且 N/M 与 `cww stats` 的 `击杀 <名>=N｜伤害 <名>=M` 一致 | 收尾 |
| L6 | 跨局生涯 + 称号（5 档阈值） | 无 committed 探针；`CosmeticRuleTest` 只测阈值单调/解锁规则；`CareerService` 落盘、称号选择从未端到端验 | 记录 `run/world/cww/career.json` 的 wins 总和 → 完成一局产生胜者（同 B12）→ 重读文件；`cww cosmetic list`；`cww cosmetic status`（只能玩家执行，RCON 会回提示） | `career.json` 胜者队成员 `wins` 各 +1、`kills` 累加；`cww cosmetic list` 输出 5 档 `id=显示名(胜场/击杀门槛)`（`MetaCommands.java:219-228`、`CosmeticRule.TITLES`）；RCON 下 `cww cosmetic status` = `这条只能玩家自己执行（RCON 用 cosmetic list 看全部称号）`；称号解锁/select 的画面显示需客户端实机 | 无需（生涯是累积数据；污染时可备份/还原 `career.json`） |

### 2.5 M 域（持久化，缺口 3）

> M1/M2 已被 `restart_probe.py`（两段式）覆盖，但**只能人工编排**。下表的 M3/M4/M5 建议合并进**同一次重启窗口**。

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| M3 | 对局中重启 → 筏重新认领（`rebindFromWorld`） | `restart_probe.py after()`（L121-152）只断言阶段/局号/tick 与 abort 收尾，**从未读 `[raftgen] 重启重认领` 或 `cww raft all`**；机制本身有旧日志证据（2026-11-21 2:58 重启：`重认领 0 艘`→`延后重试认领：重认领 1 艘`→`2 艘`，`run/server-console.log:161,174,179`）但不是断言 | before：4 假玩家 2x2 → PLAY → `cww save` → `cww state` 记 match → `stop`；外部起服；after：30s 内轮询 `cww raft all` 直到 2 队 | `cww raft all` 含 `[raftgen] 队0 Y=… 质量=… 队1 Y=… 质量=…`（`RaftSpawner.java:342-354`）；日志新段含 `[raftgen] 重启重认领 N 艘筏` 与/或 `[state] 延后重试认领：重认领 2 艘筏`（`RoundManager.java:231-233`、`RaftSpawner.java:298-321`）；`sable info @e` 含 `nbw_raft_0_<match>`/`nbw_raft_1_<match>`；`cww sail read` 可读到帆读数 | 30s 内断言完就 `cww abort`；删除 `run/restart_handoff.json` |
| M4 | 重启后大厅/配置仍正常 | `restart_probe after()` 只间接依赖 `cww state` 的 arena/format 字段（L131-132），没有显式断言 `lobby=已加载`/`cww lobby test`/`cww arena test`；也没验 `configs loaded` 日志 | 接 M3 after 段（起服后立即） | `cww state` 含 `lobby=已加载`；`cww lobby test` 含 `校验通过`；`cww arena test default_sea`、`cww format test <快照里的 format>` 均 `校验通过`；日志新段含 `[round] configs loaded: ...`；`cww save` 仍能写 | 同 M3 |
| M5 | 公告持久化（post → 重启 → list 仍在） | 无任何探针碰 `announce`；`AnnouncementService` 落盘路径 `run/world/cww/announcements.json`（`AnnouncementService.java:165-171`）从未被读 | before（与 M3 同一次停服前）：`cww announce post "M6-TEST" 持久化测试` → `cww announce list` 记序号 → 停服；after：`cww announce list` 仍含 M6-TEST 且文件存在 | 发布回包 `已发布公告（当前共 N 条）`；`list` 行 `#N [MM-dd HH:mm] Server — M6-TEST：持久化测试`（`AnnouncementService.java:106-121,136-144`）；`announcements.json` 为 JSON 数组且含 title=M6-TEST | after 段用 `cww announce del <index>` 只删本条（**不要 `clear`**，避免清掉他人公告） |

### 2.6 N 域（网络与反作弊，缺口 3）

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| N2 | `cww net flood` 洪泛不崩服 | `security_probe.py:L90-104` 已断言"1000 包放行 40/丢弃 960 + 跨秒窗口重置"，但**没有断言洪泛后服务端仍健康**（后续命令能跑不等于证据） | 接 security §2 后：`cww state`、`cww phase`、`list` 各读一次；读日志新段；再 `cww net flood <bot> 100000`（参数上限 100000，`ProbeCommands.java:60`）→ 再读 | 三条读数都正常返回；日志新段 0 条 `ERROR`/`Exception`；第二次 flood 回包仍含 `放行=…丢弃=…` 且服务端继续响应 | 无（限速窗口会自然过期） |
| N3 | 伪造载荷（越界数值）被拒 | 部分覆盖：`rescue_probe.py:L409-415` 用 `cww net probe` 断言"父世界距离 >1e6 → REJECT"；单测 `HoldPolicyTest.nonsenseDistanceIsRejectedNotTrusted` 断言 NaN/负数被拒；**wire-level 伪造包（真实 payload 篡改）Carpet 假玩家发不出**（`security_probe.py:12-15` 已自述） | RCON 可补：`cww net probe <bot> at <超大 x> <y> <z>`（`at` 分支不做范围限制，`ProbeCommands.java:46-50`）观察服务端判定；wire-level 部分**客户端实机/需伪造客户端** | RCON 分支：回包给世界/局部两种距离与判定，非法距离判 `REJECT`、无异常；wire-level 标 `INCONCLUSIVE`（沿用 M5-ANTICHEAT 口径） | `cww net probe` 无状态残留 |
| N4 | 玩家断线后限速表清理（不泄漏） | 清理逻辑存在（`NbwNetworking.java:91-98`：`DISCONNECT → clearPlayer` 清 `packetRate`/`lastRateWarn`），但**没有读数命令**，单测也覆盖不到（Map 在 MC 层）；Carpet 假玩家 `kill` 不触发 DISCONNECT（已知），`kick` 是否触发待核对 | 标 `INCONCLUSIVE`：现工具链无法观测；建议（不属本轮改码）加 `/cww net <player> counter` 读数或对 `NbwNetworking.clearPlayer` 做可注入单测；客户端实机可做行为侧验证（断开重连后首包计数从 1 开始） | 行为侧：重连后 `cww net flood <bot> 10` 应 `放行=10 丢弃=0`（窗口不继承旧计数）；直接读数暂缺 | 无 |

### 2.7 O 域（权限，缺口 4）

> **关键限制**：RCON 控制台权限等级是 4，Brigadier 的 `execute as <玩家>` 只换实体/位置、**不降权限**（`CommandSourceStack.withEntity` 保留 permissionLevel），因此 O1–O3 用 RCON 无法造出"非管理员"。

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| O1 | 非管理员执行管理命令被拒（`join`/`quit`/`phase` 除外） | 无任何权限探针；RCON 源是权限 4，见上 | **客户端实机**：非 OP 玩家（permission 0）依次 `/cww force a 0`、`/cww start`、`/cww stop`、`/cww arena test flat_test`；再试 `/cww join`、`/cww quit`、`/cww phase` | 管理命令：客户端聊天报"未知命令/没有权限"类错误，服务端命令实现**无** `[cmd]` 日志（解析前就被 `requires()` 拦，`CmdSupport.java:22-25`）；join/quit/phase 正常回话 | 无 |
| O2 | `cww force` 需权限 2 | 同上；静态证据：`RoundCommands.java:53-56` 包在 `CmdSupport.admin()` 内 | 客户端实机：非 OP 执行 `/cww force <自己> 0`；OP 再执行一次对照 | 非 OP 被拒；OP 回 `已将 <名> 分入队伍 0`（`RoundCommands.java:106`） | 若 OP 测试改了分队，`cww quit` 或 kill 假玩家 |
| O3 | 所有 `place` 类 dev 命令需权限 2 | 同上。静态核对：`cww ballista place`（`FacilityCommands.java:52-54`）、`catapult place`（L65-67）、`ram place`（L73-75）、`cww build check/charge`（`ProbeCommands.java:86,100`）全部包 `CmdSupport.admin` | 客户端实机：非 OP 各试一条（如 `/cww ballista place 0`），OP 对照 | 非 OP 被拒；OP 回 `已在 (x,y,z) 放置队 0 的弩炮` 类回包 | 测试放的弩炮/投石机/撞角随对局清理（`BallistaService.reset` 等）；若在 LOBBY 放置则需 `setblock air` 清掉 |
| O4 | `/help cww` 树完整 | 无探针断言命令树完整性；`NbwCommands.java:21-23` 自述过"漏注册一行整组静默消失"事故（2026-11-20 拆 MetaCommands 漏 FacilityCommands） | RCON `help cww`（若分页则 `help cww 2`、`3`…；**回包是否分页/截断待核对**） | 输出覆盖 9 组子树：join/quit/phase/start/stop/abort/force/state/save/statefile；zone/metrics/event/stats/survivor/guard/supply/power/cosmetic/survival/loot/audit；core/ballista/catapult/ram/raftcore；arena/reset/format/lobby；announce；raft/sail/wind/remote；rescue/rope/grapnel；net/build；weapon（与 `NbwCommands.java:37-45` 一一对应） | 无 |

### 2.8 R 域（性能与稳定，缺口 4）

> 读数口径：本服没装 TPS 插件（`soak_monitor.py:15-16`），用 `cww state` 的 `totalTicks` 增量 ÷ 墙钟算实测 TPS 是既有口径；`Can't keep up!` 的毫秒数是**累计欠账**，不能当单帧阻塞时长（`sea_probe.py:444` 注释）。

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| R1 | 空载 tick 耗时 | 无探针采样空载 TPS；`sea_probe.py:L306` 只在起服瞬间断言"没有 tick 落后"，不是稳态 | 干净 LOBBY、0 玩家：间隔 20s 读两次 `cww state` 取 `totalTicks`，TPS=Δticks/Δwall；同时读日志新段 `Can't keep up` | TPS ≥ 19.0（等于 tick 均值 ≤ 52.6ms）；新日志段 0 条 `Can't keep up`；`cww metrics` 正常 | 无 |
| R2 | 4 队满员 + 全设施 tick 耗时 | 无任何负载采样探针 | 写夹具 format `m6_cap12`（4x3、minPlayers 2、targetTotal 300）或直接用 `standard_12min`；spawn 12 假玩家、`cww force` 4 队各 3 → `cww start standard_12min test_sea`（海需已建）→ PLAY 后 `execute at <bot> run cww ballista place <team>` ×4、`catapult place` ×4、`ram place` ×4、`cww power add <team> 20` ×4 → 采样 30s | TPS ≥ 19.0；0 条 `Can't keep up`/`ERROR`；`cww metrics` 有读数。**"全设施"里的过滤器/堆肥/厨台/医疗箱/发电机/修理台没有 dev 放置入口**（只有设施武器有），这部分标客户端实机或待补夹具 | `cww abort`；`player <bot> kill`；设施方块随对局清理 |
| R4 | 连续 5 局不累积泄漏 | 单测 `RoundMachineTest.fiveMatchesInARowStayClean` 只测 domain；服务端无 5 局循环探针 | 夹具 2x2（test_3min）+ 2 假玩家，循环 5 局：`start` → 等 PLAY → `execute as @a run kill @s`（WINNER_DECIDED，平局）→ 等 LOBBY；每局断言并记录 | 每局：`cww state` `queue=0` 且无成员占席、`sable info @e` 含 `No sub-levels found`、`sable forceload query` 0 票、`cww rescue status` 无扒边/伸手行、日志 `[S10] residue=CLEAN`；5 局后 `list` 人数为 0；Java 进程 WorkingSet（PS：`Get-Process java`）不单调暴涨（阈值待 5 局实测校准，建议先记基线） | 第 5 局后 `cww abort`/`player kill` |
| R5 | 长时间（≥30 分钟）运行无异常日志 | 工具就绪（`soak_monitor.py`，48h 版本）但**从未跑过**（`M5-SOAK.md` 自述未跑）；30 分钟版本无脚本参数说明以外的编排 | 服务端已起、RCON 可用：`.\run\py-embed\python.exe -I -X utf8 tools\soak_monitor.py --minutes 30 --interval 15`（可与 R4 的 5 局循环并行） | 汇总 `avg_tps/min_tps ≥19`、`log_errors=0`、`tick_warnings=0`、`restarts=0`、采样比 ≥0.95；退出码 0 | 无（监控器只读） |

> R3 已覆盖：`sea_probe.py --big`（HEAD L640-652）已断言"半径 140 建完 0 问题列 + 建海/整局最大 tick 间隔 ≤400ms + 无 Can't keep up"。注意 `--big` 是**重活**，放最后跑（§5）。

### 2.9 S 域（边界与并发，缺口 8）

| 编号 | 断言 | 为什么现有探针没覆盖 | 补测步骤（前置 → RCON 操作） | 期望读数/证据 | 清理 |
|---|---|---|---|---|---|
| S1 | 0 名玩家时面板/命令不崩 | 无探针在 `list`=0 时跑命令电池；`wait_clean_lobby` 只读 `cww state` 一条 | 所有假玩家 kill 后（`list` 0 人），逐条发：`cww state`/`phase`/`zone`/`metrics`/`event`/`stats`/`supply`/`guard`/`survivor`/`power status`/`loot status`/`audit 20`/`reset status`/`raft all`/`raftcore status`/`core status`/`arena list`/`arena pool`/`cosmetic list`/`announce list`/`net probe ghost deck 0`/`start test_3min flat_test` | 每条都有可读回包或可读拒绝（`net probe ghost` = `找不到在线玩家 ghost`；`start` = 人数不足）；日志新段 0 条 `Exception`/`ERROR`；服务端继续响应 | 无 |
| S2 | 1 名玩家不能开局（下限 2） | 同 B4（无服务端探针） | spawn 1 假玩家 → `cww force <bot> 0` → `cww start test_3min flat_test` | `报名 1 人 < 下限 2；…`；`cww phase` 仍 LOBBY | `player <bot> kill` |
| S3 | 满员（20 人）报名 | 无探针；内置 format 最大容量是 `standard_12min` 4x3=12，**"20 人"需要夹具 format**（server.properties `max-players=20`，刚好够） | 写夹具 `run/world/cww/formats/m6_cap20.json`（teams=4、teamSize=5、minPlayers=2、duration.phases 4 段、targetTotal 300、border 1 段）→ `cww format reload m6_cap20` → spawn 20 假玩家 → 逐个 `cww force <bot> <队>` → `cww start m6_cap20 flat_test` → 等 PLAY | `cww state` `queue=20`、`teams=4`、每队 5 人、阶段进 PLAY；日志无 `队伍已满`（或对多余玩家给出明确拒绝）；20 个 `player spawn` 全部 `list` 可见 | `execute as @a run kill @s` 或 `player kill` ×20；`cww abort`；删夹具 format；`cww format reload test_3min` 复位 |
| S4 | 全队同 tick 掉线 | 无探针造"同 tick 整队消失"；旧 `m2_round.py:L284-285` 用两条 `kill` 串行，不是同 tick | 2x2 进 PLAY 后：**单条** `kill @e[type=minecraft:player,name=!<敌队玩家1>,name=!<敌队玩家2>]`（一条命令里清掉队 0 两人，等价同 tick 全灭；比两条 `kill` 真实）→ 等 90s+ | `cww audit` 出现两条 `掉线席位到期视为淘汰：<名>`（`RoundMachine.java:464`）或 `sweep: ... 按淘汰处理`；对局随后 `WINNER_DECIDED → SETTLE`；`cww state` 无幽灵席位。**注意**：Carpet 假玩家 `kill` 不走 DISCONNECT 事件（`RoundManager.java:273-275` 注释），实际走 sweep 兜底；真实 DISCONNECT 路径需客户端实机 | `cww abort`；`player kill` |
| S5 | 重复 `cww join`（幂等） | 无探针；`cww join` 在工具里只出现过 1 次（`m1_round.py` 附近），没有重复调用断言 | LOBBY spawn 1 假玩家 → `execute as <bot> run cww join 0`（连续两次，间隔 1s）→ `execute as <bot> run cww join` → `execute as <bot> run cww quit` | 两次 join 后 `cww state` `queue=1`（不重复）；回包 `已报名（阶段 LOBBY）`；再 join 仍 `queue=1`；`quit` 后 `queue=0`、回包 `已取消报名` | `player <bot> kill` |
| S6 | 队伍容量满后转观战 | 只有单测 `RoundMachineTest.overflowApplicantsBecomeSpectatorsNotCrash`；服务端无断言；**且实现与文案有出入**：`balanceAndLock()` 把超员会话直接移除（`RoundMachine.java:627-637`），之后 `placeByPhase` 会把他放回大厅（`RoundManager.java:558-562`），并不是留在图里观战 | 2x2（容量 4）spawn **5** 个假玩家 → 全部 `execute as <bot> run cww join`（不 force）→ `cww start test_3min flat_test` → 等 READY 后再等 2s | `cww audit 20` 含 `容量已满，转观战：<第5人>`（`RoundMachine.java:635`）；`cww state` 只列 4 人；第 5 人不在 teams；**实际位置**：`data get entity <第5人> Pos` 显示仍在 Lobby 区（y≈201）→ 记录为"文案说观战、实现放大厅"的文档/实现不一致 | `cww abort`；`player kill` ×5 |
| S7 | 快速连发 `start`/`stop`（状态机不被撕裂） | 无探针；`start/abort` 的跨 tick 副作用（`drainPendingEffects`）竞态从未被测 | LOBBY 2 假玩家 force 后，**不 sleep** 连发：`cww start test_3min flat_test`、`cww start test_3min flat_test`、`cww abort`、`cww start test_3min flat_test` → sleep 2s → 读状态；重复 3 轮 | 第二轮 start 被拒；`cww phase` 落在合法阶段（ENROLL/PLAY）；`cww audit` 转移链合法；无 `Exception`；`[S10] residue=CLEAN`；最后 `cww abort` 能回 LOBBY。**任何阶段卡死/残留/抛异常即真 bug** | `cww abort`；`player kill` |
| S8 | 换图/换赛制在对局中被拒 | 同 A8（committed 无人断言对局中拒绝） | 见 A8 步骤（PLAY 中 `cww arena reload flat_test`、`cww format reload oar_long`） | 两条均被拒：`对局进行中（阶段 PLAY）禁止替换正在使用的 arena`；format 那条带 `（换赛制同样禁止）` | `cww abort` |

### 2.10 D–K 域缺口摘要（已有较厚探针，只列缺口与一行做法）

| 编号 | 缺口 | 现状证据 | 补测做法（一句话） |
|---|---|---|---|
| D1 | 筏生成在**配置的出生点**（XZ） | `raftgen_probe.py:L123-150` 只断言 Y≈150 在筏面，没有 XZ=锚点 | PLAY 后 `sable info @e[name=nbw_raft_0_<match>]` 的 Position XZ 与 `arena.spawns.teams[0]`（或等角点）比对，容差 ≤3 格 |
| D2 | 筏质量**随方块数增长** | `raftgen_probe.py:L126` 只验初始 18 块 → `质量=9.5`；`remote_probe.py:L91-93` 只验挂帆不增质量 | 两种做法：① 用 `test_build` 夹具图（`initialRaftSide=10`）与默认图各开一局，比较 `cww raft all` 的质量（大筏更重）；② 真实交互加 5 格后再读一次质量（放置路径见 `build_probe.py:L159-200` 的 `live_place`） |
| D7 | 桨驱动与限速 | **命令树里没有模拟划桨的入口**：`cww oar` 不存在（`RaftCommands` 只有 raft/sail/wind/remote），`build_probe.py:L391/L435` 调它属过期残留（§4.5）；`m2_round` 只测遥控 | 需客户端实机划桨，或先补 dev 夹具命令（属改码，本轮不做）→ 标"待核对：D7 目前不可自动化" |
| E1 | 5 件近战伤害/冷却符合 `weapons.json` | `pvp_probe.py:L114-127` 只验 cutlass 6.0/同 tick 冷却；`weapon list` 只打印不断言 | `cww weapon list` 解析 5 行 `伤害/冷却/击退/对筏心`，与 `src/main/resources/data/nbw/weapons.json` 逐字段比对（paddle 2.0/18、cutlass 6.0/12、pike 7.0/22、warhammer 10.0/32、twin_blade 5.0/7） |
| E2 | 4 件远程伤害/射程符合代码常量 | `security_probe.py:L128-167` 只验 hand_crossbow | 对 hand_crossbow/harpoon_gun/musket/javelin 各做一轮：`cww weapon ranged <bot>` 读"冷却剩余(执行前)"与"目标="，伤害用 `data get entity <target> Health` 差；射程用 `tp` 到 26/18/34/20 格内外各一发（`RangedWeaponItem.java:40`、`NbwContent.java:159-168`） |
| E3 | 木桨击退最高 | 无断言（配置里 paddle knockback=2.0 最高） | `cww weapon probe <a> <b> paddle` 后读 b 的 Pos 位移，与 cutlass/pike/warhammer/twin_blade 各打一发比较位移（同一站位、同一 tick 起始） |
| E4 | 长矛 3.5 格射程 | **实现里查不到**：`NbwWeapons.java` 只注册伤害/攻速修饰，没有 `entity_interaction_range` 或服务端距离判定；3.5 出现在 `docs/content/09-武器与美术管线.md:33` 设计表 | `待核对/需澄清`：若计划仍要求 3.5 格，则这是"未实现"而非"未测"；不要写成测试失败 |
| E5 | 弯刀 0.35s 连击节奏 | `pvp_probe.py` 只验 cutlass 同 tick 拒绝；twin_blade（7 tick=0.35s）无断言 | `cww weapon probe2 <a> <b> twin_blade` → `#2` 应被拒且回包含冷却差值；再用 `cww weapon status` 的 `冷却` 计数核对 |
| E6 | 铁头锤对筏心 ×2.5 | 无探针用 `cww core break`（dev 强制伤害）验倍率 | `cww core status` 记 HP → 真实命中路径不可用 RCON 造，改用 `cww weapon list` 断言 `对筏心=×2.5` + `cww core break` 的 `实际=伤害×2.5` 对照（两者口径不同，报告里要注明） |
| E9 | 幸存者允许被击杀（血量 0） | 无探针；`survivor_probe.py` 只测村民跟随 | PLAY 后 `cww survivor` 读人数 → 对村民 `data get entity @e[type=villager,limit=1] Health` → `damage @e[type=villager,limit=1] 100` → Health 归 0/实体消失，`cww survivor` 人数 -1 |
| E10 | 反偷家 ×0.25 有幸存者时生效、无幸存者不生效 | 无探针；`FacilityCommands` 的 `cww core break` 就是为它准备的，但从未使用 | 开局（无幸存者）`cww core break 0 100` → 回包 `实际≈100`；`cww survivor job 0 0 guard`（或等幸存者生成）后再打一次 → `实际≈25`（`RaftCoreService.breakCore`，`cww core status` 里还有 `幸存者=有/无` 可作旁证） |
| E13 | 风暴期间远程失效/弩炮减半 | 单测 `WeatherCombatRuleTest` 覆盖 musket/风暴失准；`storm_probe.py` 只验帆失效；弩炮风暴减半无探针 | PLAY 中 `cww event fire storm` → `cww weapon ranged <bot>`（musket 应失效）→ 弩炮用 `cww ballista place/ammo` 摆好，风暴内外的伤害读数对比（`BallistaService.java:34,88`） |
| F1/F11/F12 | 8 类设施放置与效果（产水/产食/烹饪/医疗/发电/修理台耐久） | 无 dev 放置入口（只有筏心/桨/设施武器有），生产链从未端到端测 | **客户端实机**：真人上筏放置 8 类设施并观察产出（过滤器 30s→2 淡水、堆肥 45s→2 面包、厨台 20s→1 熟食、医疗箱 60s→1 绷带、发电机 +5 电、修理台耐久）；服务端可并行读 `cww power status`、`cww supply` 做旁证 |
| F4/F5/F6 | 弩炮：需电+弹药/32 格/7♥/风暴减半/弹药全队共享/2×2 多方块/拆任意格全拆 | 无探针（`cww ballista place/ammo/status` 全未使用） | `execute at <bot> run cww ballista place 0` → `cww ballista status` 读归属 → `cww ballista ammo 0 4` 读弹药 → `cww power status` 对照 → `execute if block` 验 2×2 四格都是 `nbw:ballista`；玩家真实拆一格验连带（Carpet attack 定位，参照 `build_probe.py:L330-360` 的站位扫描） |
| F7/F8 | 投石机：只打敌方筏心 12♥、4 格内玩家 40%、2×2 多方块 | 无探针 | `execute at <bot> run cww catapult place 0` → `cww catapult status` → 等 2 个 tick 后读 `cww core status` 敌方 HP 下降；2×2 用 `execute if block`；玩家 40% 需把敌方 bot 放到落点 4 格内再读 Health |
| F9 | 撞角：筏速 >0.05 才触发、5♥/次、1s 节流 | 无探针 | `execute at <bot> run cww ram place 0` → `cww remote move 0 w 40` 使筏加速 → 敌方 bot tp 到撞角贴边位置 → 读 Health 下降；停船后重复一次应不触发（`RamService` 常量） |
| F10 | 筏心被打爆 → 残骸 + 全队出局 | 部分：`core_probe.py:L129-137` 用 `cww raftcore destroy`（dev 强制）验残骸+倒计时；`m2_round.py:L252-298` 验沉筏+全队出局 | 补"真实破拆路径"：`cww weapon probe`（warhammer 对筏心）+ `cww core status` 观察 HP 下降/残骸；"全队出局"沿用 m2_round 的沉筏→WINNER_DECIDED |
| F13/F14 | 客户端目视：12 个方块模型比例/2×2 碰撞与选中框 | 纯视觉 | 客户端实机截图（12 blockstates 已确认存在：`src/main/resources/assets/nbw/blockstates/`） |
| G1 | 开局满营养满水分 | `p3_probe.py:L76-78` 只断言失温=否；读数 `营养=100.0 水分=99.9` 打印但**没断言** | PLAY 后 `cww survival status <bot>` 断言 `营养=100.0` 且 `水分>=99.0` |
| G2 | 每秒衰减 + 低值 debuff | 只有单测 `SurvivalRuleTest`；`cww survival set` 这个 dev 入口从未被使用 | `cww survival set <bot> 19 19` → `cww survival status` 断言 `低`/debuff 文案；间隔 60s 再读，营养/水分按 1.0/min、3.5/min 下降 |
| G4 | 6 种职业按人数发放 | `survivor_probe.py:L98-101` 只断言"两队都有幸存者"；`SurvivorJobTest` 只测分配算法 | `cww survivor` 解析各队 `人数 + 职业列表`；按 2 人局预期职业顺序（`SurvivorJob.initialOrder`：生产类优先）断言；再 `cww survivor job` 逐个改职业验中文名映射 |
| G5 | 职业加成（厨师/医护/水手 -30% 生产） | `helmsman/craftsman/guard_probe` 覆盖舵手/工匠/卫兵 3 个；生产类 3 个无端到端 | 需 F1 的生产链夹具（客户端放设施）→ 同一时钟窗口内比较"有/无该职业"的产速，或先补 dev 生产夹具命令（本轮不做，标客户端） |
| G6 | 口粮消耗 + 断粮离队 | 无探针；`cww survivor` 有口粮读数但从不改 | `cww survivor` 读口粮 → 等待/消耗（或补 dev 设置）→ 断粮后断言人数下降/离队日志 |
| G7 | 幸存者被击杀后职业加成消失 | 无探针 | 先改职业（如工匠）→ `damage @e[type=villager...]` 杀掉 → `cww survivor`/`cww guard` 断言职业列表与加成读数消失 |
| G8 | 幸存者不攻击友方 | 部分：`guard_probe.py:L134-141` 只验卫兵不误伤同队玩家 | 把卫兵与同队玩家放 12 格内 → 2.2s 内玩家 Health 不变（已有）；"幸存者（非卫兵）不攻击友方"目前没有攻击 AI 可测 → 标 `待核对` |
| G9 | 失温规则（风暴/水中） | `p3_probe.py:L74-87` 覆盖"水中 11s→失温 / 旱地解除"；**风暴场景与"湿身消耗 ×1.5"没验** | 用 `cww event fire storm` 期间读 `cww survival status` 的扣减速率，对比非风暴 60s 的下降量 |
| H1 | 开局按 `loot.prefill` 撒箱（数量/半径带） | `loot_clearance_probe.py` 只断言"筏 4 格内无 barrel"，不验预铺数量/半径带 | PLAY 后 `cww loot status` 读 `已撒箱子 N 个`：test_sea 应为 16（prefill count=16、radiusBand 30–60），default_sea 应为 24（count=24、band 70–140）；`[loot] 已撒箱子 16 个（上限 16，存活 120s）`（`LootService.java:327-336`）与日志 `[loot] 开局：已撒补给箱 16 个（环带 30-60 格）` 同时成立 |
| H2 | 定时补箱间隔 | 无探针 | PLAY 中隔 `respawnIntervalSec`（test_3min=20s）前后各读一次 `cww loot status`，数量应 0→3→6… 每 20s 加 `respawnBatch` 个（test_3min=3；同屏上限 16 时封顶，见 `LootService.java:181-183`） |
| H3 | 圈层 lootMultiplier 影响品质 | 无探针（单测 `LootRollTest` 只测稀有度权重） | `cww loot spawn 30` + `cww zone` 定圈层，对比 tier1/tier3 圈内 barrel 内容（`data get block <x> <y> <z> Items`，tier 由 `LootService.tierAt` 按半径取，`LootService.java:277-283`）；至少断言 Legendary 只出现在 tier3 |
| H4 | 排除区（中心遗迹）不撒箱 | `loot_clearance_probe` 只测"筏 4 格净空"；`default_sea/test_sea` 的 `loot.exclude`（中心 40×40）才是排除区 | `cww loot spawn 400`（dev 入口与真实路径共用 `LootService.spawn`，会读 `arena.loot.exclude`，`LootService.java:175-197`）→ 日志应含 `…排除区跳过 b 次…` 且 `b>0`；exclude 矩形内抽样 `execute if block … minecraft:barrel` 全为 `Test failed` |
| H5 | 局后归还补给箱方块（不残留） | `auditResidue` 不查 barrel；`LootService.reset` 有清理（`LootService.java:346-363`：把本局箱子 `setBlockState(WATER)` 再清列表）但无断言 | 对局结束（RETURN/LOBBY）后：日志新段应有 `[loot] 局后还原 N 个补给箱方块`（N=本局撒出数）；再对结束前 `cww loot status` 最后 3 个坐标做 `execute if block … minecraft:barrel`，应全为 `Test failed`；再开一局验不因旧箱卡筏（`raftgen` 的 Y 断言可作旁证） |
| H6 | 功率池：清零/增长/消耗 | 无探针；`cww power status/add` 从未被使用 | PLAY 开始 `cww power status` 应全 0；`cww power add 0 5` 后读回 5；放一台 `cww ballista place 0` + `ammo` 后读功率下降（`PowerService.java:33-86`） |
| I1 | 事件池按权重抽取 | 单测 `EventWeightTest` 覆盖纯函数；服务端无统计断言；**内置 `standard_12min.json:L18` 事件池仍含已删除的 `kraken`**（§4.6），会污染"权重"分母 | 用 `standard_12min` 连开 N 局或反复 `cww event fire` 统计；先修/移除 kraken；断言 `cww event` 的 `池=5` 且所有池内事件都有实际效果 |
| I2 | `cww event fire` 手动触发 | **没有任何探针调用 `cww event fire`**（`event_probe` 只等自动事件；`storm_probe` 只读状态） | PLAY 中 `cww event fire storm` → 回包 `已触发事件 storm`；`cww event` 显示 `上次=storm`；日志 `[event] 触发 storm`；45s 后风暴结束（`EventService.java:277-287,298-302`） |
| I3 | 风暴事件的风力/战斗影响 | `storm_probe.py:L86-112` 只验帆失效；远程/弩炮的风暴影响只有单测 | 见 E13；再加 `cww wind read` 对比风暴内外目标速度 |
| I5 | 圈外每秒伤害随阶段递增 | `zone_probe.py:L96-100` 只验第一段 `伤害>0`，不验递增 | test_3min 三段（30/60/120s）各收缩稳定后读 `cww zone` 的 `圈外伤害=`，断言严格递增（0.5→1.0→2.0，`Format border stages`） |
| I7 | 圈心与 arena 配置一致 | `zone_probe.py` 只断言半径，不读圈心 | `cww zone` 回包含 `圈心=(0,0)`（default_sea）或 `(1000,1000)`（flat_test），与 arena JSON `sea.centerX/Z` 比对（`ZoneService.java:155-161`） |
| J1 | `remote give`/`remote move` 转向角冲量 | `remote_probe.py:L80-125` 已验 move 的前进/后退/转向位移；**`remote give` 没有断言**（发道具但没人读背包） | 接 `remote_probe` 后：`cww remote give <bot>` → 回包 `已给 <名> 遥控器`；`data get entity <bot> Inventory` 含 `nbw:remote`；`remote move` 的转向用 `sable info` 四元数差已有断言 |
| J2 | 遥控限速与冷却 | `remote_probe` 只验位移，不验目标速度上限/输入冷却；单测 `SpeedGovernorTest` 覆盖模型 | `cww remote status <team>` 读目标速度；同 tick 连发两次 `cww remote move 0 w 5` 观察限制/回包；或读 `RemoteService` 冷却文案 |
| J3 | 非舵手使用遥控被拒 | 无探针；`HelmsmanRule` 的 ×1.15 有单测和 `helmsman_probe`，但"非舵手被拒"没有服务端入口 | **已核对**：`RemoteService.java:134-146` 只在"本队有舵手"时把目标速度/转向乘 ×1.15，**没有"非舵手拒绝遥控"的分支** → 标 `待核对`：是设计已放宽还是未实现，确认后再决定补测或补实现 |
| J8 | 救援期间不掉水 | `rescue_probe.py:L196-212` 用 Y 峰值/上筏计数间接验证；"救援全程不掉水"没有逐拍断言 | 在 `rescue reach` 登记后逐拍 `data get entity <被救者> Pos` 的 Y，断言 ≥ 水面（不被拉入水下）；已有 `rescue_probe` 的 peak 断言可扩展 |
| K5 | 建造需电量/充能 | **代码里没有**：`BuildService` 只有材料扣费（`chargeExpansion`），`PowerService` 只服务设施；docs 也搜不到 | `待核对/需澄清`：计划若仍要求，属"未实现"；若已作废，从 M6 计划里删掉 |

---

## 3. 已覆盖映射（简表，抽查用）

> 只列"已覆盖"的 41 项。行号 = git HEAD。`旧脚本` = 存在但不在 `run_all_probes.py` 批量列表里，**建议批量前手动跑一遍确认仍绿**。

| 域/编号 | 证据（探针/单测 + 行号） | 断言要点 |
|---|---|---|
| A5 | `tools/g1_guards.py:L76-96`；`ConfigServiceTest.brokenJsonIsQuarantinedNotCrash / ringHoleIsReportedWithFieldLevelDetail` | 坏 arena JSON 被拒绝、从 arenas/ 移走、落到 `_invalid/` 带时间戳、服不崩 |
| A6 | `src/test/java/dev/nbw/cfg/ConfigServiceTest.java`：`unknownSchemaVersionIsRefusedNotGuessed` | schemaVersion≠1 拒绝不猜测（仅单测；服务端证据可由 A5 夹具顺带覆盖） |
| A7 | `tools/sea_probe.py:L308-315`（来源=world/内置、arena list 显示覆盖）；`ConfigServiceTest.worldFileShadowsBuiltinEvenWhenBroken` | 本地同名文件优先于 jar 内置，且坏本地文件也优先（不静默回退） |
| B1 | `tools/m1_round.py:L239-291`（旧脚本） | LOBBY→ENROLL→READY→PLAY→SETTLE→LOBBY 全链路 + 锚点/模式/清场 |
| B5 | `RoundMachineTest.midMatchJoinIsRejected`（单测）；服务端无 | PLAY 中 join 被拒 |
| B6 | `tools/spectator_probe.py:L59-75` | 死亡后日志 `spectator notice` + `cww state [淘汰]` + 队友未误标 |
| B7 | `tools/m2_round.py:L279-298`（旧脚本） | 仅剩 1 队 → WINNER_DECIDED → SETTLE（非平局） |
| B8 | `RoundMachineTest.drawWhenEveryTeamWipedSameTick`（单测） | 同 tick 双灭判平局 |
| B9 | `RoundMachineTest.matchTimeoutEndsMatch`（单测） | 超时 MATCH_TIMEOUT 收局 |
| B10 | `tools/m2_round.py:L279-287`（旧脚本） | 90s 保席到期→淘汰→判胜 |
| C1 | `tools/sea_probe.py:L347-351`（test_sea 3 列）、`:L640-652`（default_sea 5 列，需 `--big`） | 地形审计问题 0 列 + 建海不拖 tick |
| D3 | `tools/sail_probe.py:L145-155` | 静风下帆不自推进（峰值 <0.25） |
| D4 | `tools/sail_probe.py:L158-197` | 同冲量下带帆减速更快 |
| D5 | `tools/sea_probe.py:L442-561` | 帆数档风驱终端速度单调不减且不超服务端上限 |
| D6 | `tools/sail_probe.py:L138-141,L195-197` | rig 后读数=5、strip 后=0 |
| D8 | `tools/sink_probe.py:L160-192` | 判沉 + 下沉 >4 格 + 停止下沉 + 对方筏不沉 |
| D9 | `tools/core_probe.py:L129-204` | `raftcore hold` 80 tick 不足、松手归零、受伤打断不耗额度、160 tick 修复且解除倒计时、额度用完 |
| D10 | `tools/raftgen_probe.py:L159-174`；`tools/sea_probe.py:L620-637` | 局后筏/票零残留、S10 CLEAN |
| E7 | `tools/pvp_probe.py:L137-144` | 第二件近战被拒且原因点名近战上限 |
| E8 | `tools/m1_round.py:L199-233`（旧脚本） | LOBBY 非 PLAY 阶段 PVP 被闸门挡下（日志 denial + 血量不变），PLAY 内正常 |
| E11 | `tools/security_probe.py:L128-167` | 远程 hitscan 正对照命中、负对照隔墙不中 |
| E12 | `tools/security_probe.py:L154-157` | 装填期间第二发仍在冷却 |
| F2 | `tools/build_probe.py:L369-395` | 设施位额度 = min(maxFacilities, 甲板列/5)、顶格被拒 |
| F3 | `tools/build_probe.py:L265-266` | 别队来放/拆被拒并点名归属 |
| I4 | `tools/zone_probe.py:L79-100` | 开局满半径、第一段收缩半径下降且圈外伤害>0 |
| I6 | `tools/zone_probe.py:L121-129` | 日志出现黑海预告/收缩公告 |
| J4 | `tools/rope_probe.py:L96-99` | 绳索把落水队友拉回筏上 |
| J5 | `tools/rope_probe.py:L125-136` | 钩竿把敌方拉近、超射程被拒 |
| J6 | `tools/rescue_probe.py:L156-234,L240-338` | 扒边时序/计数/峰值/被打断不计数/归属校验 |
| J7 | `tools/rescue_probe.py:L340-433` | 伸手极限距离/落点高度/净距/局后零残留 |
| K1 | `tools/build_probe.py:L250-252` | 超建造区（水平/垂直窗口）被拒 |
| K2 | `tools/build_probe.py:L415-426` | 方块数达上限被拒并报实时数字 |
| K3 | `tools/build_probe.py:L383-387` | 设施位已满被拒、原因带实时额度 |
| K4 | `tools/build_probe.py:L250-253` | `buildYRange` 上下界之外被拒、下界内允许 |
| K6 | `tools/build_probe.py:L265-266` | 不能改敌方筏（放/拆均拒） |
| L5 | `tools/stats_probe.py:L97-117` | `state/matches/<N>.json` 落盘且 winner/duration/players.kills/deaths/damage 与读数一致 |
| M1 | `tools/restart_probe.py:L90-93`（两段式） | `cww save` 指向 `round.json` 且盘上 `phase=PLAY` |
| M2 | `tools/restart_probe.py:L121-139`（两段式） | 重启后 phase/match/arena/format 与落盘一致、tickInPhase 不倒退 |
| N1 | `tools/security_probe.py:L90-104` | 1000 包只放行 40、跨秒窗口重置（10 包全放行） |
| N5 | `tools/rescue_probe.py:L409-415`；`security_probe.py:L106-114` | `cww net probe` 同时给世界/局部两种距离与判定（>1e6 判 REJECT；旁观者上限 0） |
| R3 | `tools/sea_probe.py:L640-652`（`--big`） | 半径 140 建海后地形 0 问题、最大 tick 间隔 ≤400ms、无 Can't keep up |

---

## 4. 现有探针的过期/脆弱/污染点（先标注，不改码）

> 每条都给证据与建议；优先级：P0 = 会造成假失败/污染下一批结果，P1 = 会误导判断，P2 = 文案/整洁。

**4.0 并发改动说明**：审计时工作树已有未提交修复（`git status` 见 §0 第 4 条）。下列 4.1/4.3(b)/4.4 的**部分问题已被并发修掉**，但修复未提交、未跑批量验证；本清单以 HEAD 行为 + 当前工作树现状为准，修复项标「工作树已修，未验证」。

**4.1 restart_probe 被当一次性探针塞进批量（P0，工作树已修未验证）**
- 证据：`tools/restart_probe.py:L99-100` 的 `before` 段最后主动 `stop` 服务端；2026-11-21 那轮批量证据：`run/probes_rest.txt`（restart_probe TIMEOUT 240s）、`run/probes_rest2.txt`（restart_probe PASS 218s，**同批其余 5 个探针全部 ConnectionRefused**）、`run/probes_rest3.txt`（5 个全 ConnectionRefused）；`run/probe_logs/{p3_probe,spectator_probe,fairness_probe,security_probe,bounty_probe}.log` 的 traceback 全是 `WinError 10061`。
- 另有一层误导：`probes_rest2.txt` 里那行 `PASS` 只是 **before 段**（`=== BEFORE SUMMARY === satisfied=3 FAILED=0`），`after` 段当时并没在批内跑——把"停服前检查通过"记成"重启恢复探针通过"。
- 现状（工作树）：`run_all_probes.py` 已把 `restart_probe` 移出 PROBES（L33-41），并加了 `rcon_alive()` 预检（L63-70、L107-111）与 `all_probes_summary.txt` 汇总（L157-158）。
- 建议：保持两段式单独编排；before/after 用两个独立退出码记录，别共用一行 PASS。

**4.2 p3_probe 的 11 秒墙钟等待在卡顿时不足 10 游戏秒（P0）**
- 证据：`tools/p3_probe.py:L74-87`——失温断言靠 `p.sleep(11)`（墙钟 11s），而规则是**连续湿身 200 tick（10 游戏秒）**（`HypothermiaRuleTest.tenWetSecondsTriggersHypothermia` 等）。TPS < 18.2 时 11s 墙钟 < 200 tick，断言必假失败；服务端被建海/多人探针/重启占用时正是这种场景。另 L87 文案"离开水/雨 3 秒后解除"与实际 `p.sleep(4)` 不一致（规则：3s 干身重置，等 4s 合理，改文案即可）。
- 建议：改成 `wait_game_ticks(p, 200)`（读 `cww state` 的 `totalTicks` 直到 Δ≥200，上限 30s 墙钟）或轮询 `cww survival status` 直到 `失温=是`；这比加 sleep 稳，也不会在满 TPS 时空等。

**4.3 探针自建夹具留在 `run/world/cww`，污染后续"内置口径"检查（P0，部分工作树已修）**
- (a) `build_probe.py:L456-483` 写 `run/world/cww/arenas/test_build.json` 后**从不删除**；审计时该文件仍在（2026-11-21 2:11，2387B）。后果：之后 A3"6 张**内置**图 `cww arena test`"对 test_build 实际测的是本地覆盖副本。
- (b) `sea_probe.py` 的 `write_sail_arena()`（HEAD L226-237）写 `run/world/cww/arenas/test_sail7.json`、`write_long_format()`（HEAD L166-190）写 `run/world/cww/formats/sail_long.json`；审计时 `test_sail7.json` 仍在（2:15）→ 内置 test_sail7 同样被覆盖。
- (c) `run/probe_logs/sea_probe.log` 时间戳 2:18 的尾部是 `TIMEOUT`：sea_probe 中途超时可能留下建海队列/加载票/半建海域；批量前应先 `cww abort`、`sable info @e`、`sable forceload query` 清点。
- 建议：批量前删/备份 `run/world/cww/arenas/{test_build,test_sail7}.json`、`run/world/cww/formats/sail_long.json`；把"干净基准"做成检查清单第一步；夹具类探针跑完自己恢复（或统一放 `run/_fixtures/` 由批量脚本按需注入）。

**4.4 sail_probe / wind_probe 的旧造海夹具与新 flat_test 真水池冲突（P0，工作树已修未验证）**
- 证据（HEAD）：`sail_probe.py:L94-107`、`wind_probe.py:L107-125` 会在两个锚点 `fill … 149..153 air` + `fill … 149..152 water` 并砌石墙，把配置水面 149 抬到 152；而 2026-11-21 `worldbuild.py` 已按 arena 配置把 flat_test 建成正经水池（海床 139/水面 149）。后果：玩家被传送到水下 3 格，`raftgen_probe` 报 Y=143.71 类假失败（并发补测流 `tools/m6_world_repair.py` 的 docstring 记录了这次真实现场）。
- 现状：工作树已改为 `from sea_probe import reset_flat_test_anchors`（新增 helper，`sea_probe.py` 工作树 L265-286 附近）并在探针收尾复位。
- 建议：批量前跑一次 `tools/m6_world_repair.py`（或等价复位），并把 `cww arena terrain flat_test` 问题 0 列作为**批量前置自检**。

**4.5 build_probe 调用了不存在的命令 `cww oar`（P1）**
- 证据：`tools/build_probe.py:L391`、`:L435` 发 `p("cww oar strip 0")`；命令树 `RaftCommands.java:36-95` 只有 `raft/sail/wind/remote`，全仓库 `literal("oar")` 无匹配（`NbwContent` 里只有方块常量 `"oar"`）→ 回包是未知命令且**返回值被丢弃**，静默无效。推测是 M2 时代 `oar_probe`（`run/oar_probe.log` 等旧产物）的残留。
- 建议：删除这两行，或替换为真实清场入口；若确实需要"清桨位"，得先补 dev 命令（本轮不改码，标待核对）。

**4.6 内置 `standard_12min` 事件池仍含已删除的 `kraken`（P1，配置过期）**
- 证据：`src/main/resources/data/nbw/format/standard_12min.json:L18` 的 `events.pool` 仍含 `"kraken"`；`docs/dev/AI-交接-v2-当前状态.md:160` 与 `docs/dev/M5-RESULTS.md §5.18` 明确 2026-11-19 已删除海怪；`EventService.displayName()` 的 default 分支（L255-262）与 `fire()` 的 default 分支（L329）对未知事件只公告、无效果。
- 后果：事件池每 6 选 1 空放，I1"按权重抽取"、I2"手动触发"的统计会被污染；"5 个事件全部有实际效果"的结论不成立。
- 建议：内置 format 移除 `kraken`（属配置修正，不是玩法改动），并在 I1 测试里加"池内事件都必须有非空 effectHint/生效分支"的断言。

**4.7 武器数值口径漂移：注释/探针文案仍写 9HP（P2）**
- 证据：`weapons.json` 里 `cutlass.damage=6.0`（2026-11-19 整体下调 30%）；但 `NbwWeapons.java:27` 注释仍写"砍刀 9HP"，`pvp_probe.py:L114` 文案"砍刀表值/属性 == 9 HP"（断言本身用的是 6.0，不会假失败），`stats_probe.py:L6/L77` 文案"砍刀 9 HP"。
- 建议：统一文案为 6.0HP，避免补测者按 9HP 写新断言。

**4.8 m1_round / m2_round 旧脚本与"干净 LOBBY"新口径冲突（P1）**
- 证据：`m2_round.py:L306-308` 断言 `queue=0` **且** `teams=0`；但 2026-11-21 之后 `releaseAbsent` 只放成员不清空队伍骨架（`sea_probe` 工作树新增 `no_seated_sessions()` 注释：`seat_probe` 在 LOBBY force 过假玩家会留下 `T0 赤队 []` 空壳，空壳不占名额）。旧断言 `teams=0` 在现在的机器上会假失败。`m1_round.py` 同类风险较小（L286-291 的 S10 断言仍稳），但 `L239-254` 的阶段断言依赖旧 `run_all` 行为。
- 建议：若把两脚本纳入 M6 回归，先把 `teams=0` 改成"无成员占席"；否则在 M6-RESULTS 标注"旧脚本仅作 B1/B7/B10 参考，不作为批量门禁"。

**4.9 并发新增 `m6_round_probe.py` 已知待修点（P0，未提交，别当绿）**
- B11 写法不成立（两处）：(i) `m6_round_probe.py:L171-179` 用 `kick r1` + `player r1 spawn` 期望 `[失联]` 出现/消失；但 `docs/dev/M2-RESULTS.md:300` 已证明 Carpet `kill`+`spawn` 会换 UUID（`core_probe.py:L21` 同注释），`onReconnect` 按 UUID 找不到旧会话；(ii) 即便 UUID 不变，`RoundMachine.dump()` 的标记逻辑（`RoundMachine.java:741`）是"掉线但 alive → 不显示标记，只有 alive=false 且未淘汰才显示 `[失联]`"，正常掉线流程（`onDisconnect` 只记 tick，不清 alive）根本到不了 `[失联]`。→ B11 应改客户端实机或明确 `INCONCLUSIVE`。
- B5 只断言"无副作用"（L163-169），缺拒绝文案断言（`本局进行中，禁止中途加入`）；补一行即可。
- 日志断言读整份 `server-console.log`（L52-57 的 `log_text()`），历史对局的"本局结束/CLEAN"会命中 → 应像 `sea_probe.P.log()` 一样按字节偏移读增量。
- `m6_config_probe.py`：A5/A6/A7 会写/删 `run/world/cww/arenas` 与 `_invalid`；C1 断言三张图"问题 0 列"，若 `default_sea` 未建图会假失败（前置需 `sea_probe --big` 或 `function nbw:arena/default_sea/build`）。批量编排时它必须**独占**服务端（当前环境已被另一路测试占用，见 §0）。
- 工作树 `run_all_probes.py` 的 PROBES 列表**还没加** m6_*，需要另建 m6 批量入口或 `--only`。

**4.10 其它小项（P2）**
- `seat_probe.py` 收尾只 `player kill`，会在 LOBBY 留下空队伍骨架（现已由 `no_seated_sessions` 容忍，但会让后来捏 `teams=0` 的脚本假失败——同 4.8）。
- `p3_probe.py` 用 `tp 1080 149 1000` 作为"旱地"落点：这是 flat_test 水池边界外，但如果以后改水域半径会失效；建议从 arena JSON 推坐标。
- `stats_probe.py` 文档里"victim 用 /damage 100 ... by 攻击者击杀"没问题，但它依赖 `cww stats` 名字缓存（`CombatStats.nameOf` 在玩家离线后回 UUID 前缀）——测试必须在击杀后立即读，别等实体移除后再读名字。

---

## 5. 推荐执行顺序

**总原则**：同一时刻只允许一支 RCON 探针批次（当前 dev 服已被另一路测试占用）；每条断言留证据读数；每批开始前清残留（`sweep_stale_bots` + `cww abort` + `sable info @e`/`sable forceload query` 清点）。

### 5.1 按"起停服成本"分桶

| 顺序 | 桶 | 内容 | 是否需要停/起服 | 预计 |
|---|---|---|---|---|
| P0 | 离线/静态 | Q1–Q8（`tools/check_resources.py`，无需服务端）；E1 的 5 件武器表静态比对；O2/O3 的代码静态核对（§3 已给行号） | 否 | 5–10 min |
| P1 | 干净世界窗口 | 停服→移走 `run/world/cww`→起服：A1（日志 `configs loaded` + 0 读取失败）→ A2–A4（4 张赛制 + 6 张图全量 `test`）→ **建图**（`m6_world_repair.py` + `function nbw:arena/default_sea/build` 或 `sea_probe --big`）→ C1（5 列 0 问题）→ A7（本地覆盖/回退）→ A5/A6（坏 JSON/schemaVersion，用 `m6_config_probe` A5/A6 夹具）→ A8（reload 生效/对局中禁止）→ O4（`help cww`）→ 恢复 `cww.bak`（或把这套干净世界留作 P2 的基准） | **是**（1 次停/起） | 20–30 min |
| P2 | 单次起服、一局接一局（接在 P1 的同一脚本后面跑，**最适合脚本化批量**） | B2→B3→B4→B5→B1→B6→B7→B10→B12 → L3→L4→L5→L6 → C2→C3→C4→C5 → S1→S2→S5→S6→S7→S8 → J1/J2/J4/J5/J6/J7/J8 → K 抽查 → E1/E2/E5/E7/E11/E12 → F2/F3/F4/F5 → G1/G2/G4/G9 → H1/H2/H5/H6 → I2/I3/I5/I7 → D1/D2/D3/D4/D6/D9/D10 回归 | 否（起服一次） | 30–50 min（含 B10 的 90s 保席与 2 次完整 SETTLE） |
| P3 | 专项长测 | B8（同 tick 双灭，~2min 局面）、B9（超时平局，test_3min 硬上限 360s ≈ 6min）、R1/R2（4 队 12 人 + 设施武器，~5min）、S3（20 人，~5min）、R4（5 局，~10min） | 否 | 25–30 min |
| P4 | 重活（会拖 tick，放最后） | R3：`python tools/sea_probe.py --big`（建 1.35M 方块，含 §8 帆数扫描约 5–10min）；跑完等空载 TPS 恢复后再做其它 TPS 断言 | 否 | 10–15 min |
| P5 | 两段式重启（**唯一需要停/起 2 次**） | 一次重启窗口合并 M1+M2+M3+M4+M5：`before`＝起 PLAY + `cww save` + `cww announce post` + 记 match → `stop` → 外部起服 → `after`＝M2 阶段/tick、M3 `cww raft all`/重认领日志、M4 `lobby test`/`arena test`/`configs loaded`、M5 `announce list` → 清理（abort、`announce del`、删 handoff） | **是**（1 个窗口含 2 次起停：M5 与 M2 共用同一次） | 15–20 min |
| P6 | 长稳 | R5：`soak_monitor.py --minutes 30 --interval 15`（可与 R4 的 5 局循环并行，soak 只读） | 否 | 30 min |
| P7 | 客户端实机 | P1–P10、C6、E14、F13/F14、G3/G10、I8、L1/L2、B11、B6 的观战视角、O1–O3、N3 wire-level、N4 行为侧、S4 的真 DISCONNECT 变体 | 否（客户端进服即可） | 1–2 h（逐项截图入 `run/_shot_m6_*.png`） |

### 5.2 最适合"一个脚本自动跑完"的域顺序

> 说明：下面这一串就是 **P1 窗口内**的脚本顺序（A 段需要"无 `run/world/cww` 的干净世界"前置；A 段跑完后不再需要停/起服，B/L/C/S 可以一局接一局地连续跑）。

```
A2 → A3 → A4 → A5 → A6 → A7 → A8 → C1 (需已建图)
→ B2 → B3 → B4 → B5 → B1 → B6 → B7 → B10 → B12
→ L3 → L4 → L5 → L6
→ C2 → C3 → C4 → C5
→ S1 → S2 → S5 → S6 → S7 → S8
→ [可选/已有探针回流] D/E/F/G/H/I/J/K 的缺口行
```

理由：A/C 是只读或夹具写入、可最先暴露"起不来/世界错"；B 需要 2 个假玩家并会经历一次 90s 保席，放中段；L/C2–C5 都依赖"刚跑完一局/刚进 READY"的状态，紧跟 B；S 是纯命令边界，最后跑不影响别人；D–K 已有探针可直接复跑，放在尾部做回归。

**必须单独起停服的**：A1（干净世界首启，必须无 `run/world/cww`）；P5（M1–M5 两段式重启，`restart_probe` 的 before 段会主动 `stop`）。
**必须客户端实机的**：P1–P10、C6、E14、F13/F14、G3/G10、I8、L1/L2、B11、O1–O3、N3/N4、S4 的真 DISCONNECT 变体、B6/L1 的观战视角。
**可插空并行的**：Q1–Q8（离线扫描）；R5 soak（只读、对服务端无干扰）。

### 5.3 建议的补测落地顺序（对并发补测流）

1. 先把两个 P0 级探针问题（4.2 `p3_probe` 墙钟、4.3 夹具清理）修掉，否则 P2 的前置"干净 LOBBY"会被污染。
2. 跑一遍并发新增的 `m6_config_probe.py`（A2–A8/C1）、`m6_round_probe.py`（B/L3/L5/L6，**先修 4.9 的 B11/B5/日志增量**）、`check_resources.py`（Q1–Q8）、`m6_world_repair.py`，跑绿后提交并勾掉 §1 对应行。
3. 按 P1→P2→P3→P4→P5→P6 推进，最后做 P7 客户端清单。
4. 每发现真 bug 按「真 bug / 文档不一致 / 已知未结案 / 测试脚本过期」四类归档（沿用 `M6-FULL-TEST-PLAN.md` 末尾的记账方式）。
