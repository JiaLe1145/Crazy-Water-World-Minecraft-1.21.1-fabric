# AI 交接文档 · 剩余内容、关键点、怎么做

> 写给**下一个接手的 AI 会话**（换模型、换上下文都要读这份）。
> 编写时间：2026-11-07，代码状态 = 提交 `6cb100e`（M2-8 第二段：伸手救援 + 长按通道坐标空间修复）。
>
> **先读这三份，再动手**：
> 1. `docs/dev/03-里程碑.md` —— 里程碑范围与 Gate 判据（唯一进度基准）
> 2. `docs/dev/M2-RESULTS.md` —— 已完成部分的**实测记录**（含所有翻车点，别重复翻）
> 3. 本文档 —— 进度快照、硬事实、剩余内容的做法与验收
>
> 玩法与数值的唯一权威是 `docs/content/`（`01-玩法设计.md` / `02-数值与节奏.md`），
> 里面带 `[新增·待批 Ax]` 标记的内容**没有用户批准就不许实现**。

---

## 1. 项目一句话与六条硬约束

**项目**：Minecraft 1.21.1 + Fabric 的海上木筏生存竞技小游戏；木筏移动依赖 **Sable** 模组（sub-level / 多方块结构物理），玩法逻辑全部自研。

| # | 硬约束 | 违反的后果 |
|---|---|---|
| C1 | **旧仓库 `Crazy-Water-World` 已废用，不得参考、不得移植**（2026-11-05 用户明确指令） | 会把已推翻的自研移动路线又带回来 |
| C2 | `~/Desktop/sable-main` 是 Sable 源码，**只读**；命令语法/API 语义/覆盖范围一律从这里取证 | 靠猜 API 写过 → 静默失效反复出现 |
| C3 | **允许修改 Sable 源码**（旧的"只读不改"已作废），但改前必须说清改哪里、为什么 | 这条是许可，不是鼓励 |
| C4 | **不许自己发明玩法机制**。自己加的东西必须标 `[待批]` 并写进文档，用户会审 | 历史上被否过多次（体力门槛、玩家随筏缺陷等） |
| C5 | 分层规矩：`domain/` 零 Minecraft 依赖的纯函数；只有 `sable/` 包允许 `import dev.ryanhcode.sable.*` | 一旦漏出 Sable 类型，"隔离层"当场失效 |
| C6 | 交付前必须真验证（单测 + 探针），**断言必须能失败**；测不出就报 INCONCLUSIVE，不许凑绿灯 | 见 §6，这类假成功已进过文档 |

`RicordRailExpress-BasedOnStarRailExpress`（星铁 Express 项目）可作**只读参考**，不许复制进来当依赖。

---

## 2. 环境与运行（照抄就能跑，含四个必踩的坑）

```
Minecraft 1.21.1 / yarn 1.21.1+build.3 / Fabric loader + fabric-api 0.116.17+1.21.1
Java 21.0.9（LTS）    Gradle wrapper（./gradlew）    Loom 1.17.21
依赖 jar：libs/sable-fabric-1.21.1-2.0.6.jar（modImplementation）
          libs/sable-companion-fabric-1.21.1-1.6.0.jar（compileOnly，运行时由 sable 内嵌解出）
交付目录：dist/（jar 不入库）
dev 服务端：run/server.properties → rcon.port=25576、rcon.password=nbwm0、level-name=world
```

### 起服 / 验证的标准动作

```bash
# 1) 端口占用必须先确认（上一轮的旧 jar 在跑会让验证结果全是假的）
netstat -ano | grep 25576

# 2) 如果有人在跑：走 RCON 停，不要杀进程
RCON_PORT=25576 RCON_PASSWORD=nbwm0 python tools/rcon.py "stop"

# 3) 构建/单测前先停服（runServer 会占住 Gradle 项目锁）
./gradlew test -q

# 4) 起服：★ 必须重定向到 run/server-console.log ★
(./gradlew runServer > run/server-console.log 2>&1 &)
sleep 90 && grep -n "Done (\|rescue] 落水\|FAILURE" run/server-console.log

# 5) 跑探针
PYTHONIOENCODING=utf-8 RCON_PORT=25576 RCON_PASSWORD=nbwm0 python tools/rescue_probe.py
```

### 六个已经踩过、必须避开的坑

1. **`seat_probe`（以及任何读日志的探针）的证据通道是 `run/server-console.log`**，按字节游标只读新增段。
   起服时把 stdout 重定向到别的文件 → WARN 明明在日志里，探针读不到 → **报假 FAIL**。
2. **`cww state` 里 `teams=2` 的空队壳会卡死 `build_probe` 的前置**（`queue=0 teams=0`）。
   LOBBY 里没有任何路径清队壳，**必须真的打一局再中止**（走局后清理）才会 `teams=0`。
   所以 `build_probe` 不能紧跟在 seat/rescue 类探针后面跑。
3. **Git-Bash 会把以 `/` 开头的 RCON 参数当路径转换**（`/cww phase` → `C:/Program Files/Git/cww phase`）。
   需要前导斜杠时加 `MSYS_NO_PATHCONV=1`；不带斜杠送进去会被当控制台文本，报错文案完全不同。
4. **`run/server-console.log` 是 Gradle 缓冲的 stdout**，成批到达。
   **不要用它的时间戳量耗时**；`Can't keep up! Running Nms` 里的 N 是**累计欠账**不是单次阻塞时长
   （误读一次，立过一条不存在的"6.1 秒冻结"缺陷）。判卡顿看 `ArenaChunkLoader.maxTickGapMs`。
5. **RCON 传输层有三条坑，`sea_probe.P` 已经处理掉了——新探针请复用它，不要自己拿 `rcon.py` 裸连**：
   - 鉴权会**多回一个空包**，只读一个包就开工 → 从第一条命令起**永久错位一包**，读数会串到上一步；
     `P.attach()` 用 `select` 确认数据已到再读完整一包排干净（带短 timeout 硬读会把流读歪，实测直接卡死）。
   - **一条命令 ≠ 一个包**：被 `schedule` 的 function 会在后续 tick 补发多个包；
     等待期间必须持续 `pump()` 排空，否则服务端写缓冲被撑满**直接掐线**（实测探针死在开局中途）。
     也别在同一条连接上连着发"命令 + 哨兵"，实测同样会被掐。
   - 世界目录这件事：`worldRoot()`（`RoundManager`）与**所有探针都把世界写死成 `run/world`**，
     临时改 `level-name` 跑探针，夹具赛制会被写进旧目录，**整段读数变成假的**。
6. **`PYTHONIOENCODING=utf-8` 是必需的**：Windows 控制台默认 GBK，不加它探针输出全是乱码，
   而**很多断言就是靠匹配中文回包做的**（`已登记伸手`、`报名期放掉`），乱码会让断言静默失效。

> **本文件里所有"N/N PASS"的结论都不带证据文件**：`run/` 整个被 `.gitignore` 忽略，探针输出不进版本库。
> 所以要引用某个读数，**先复跑再写进文档**；只看到本文的数字，请当作"当时成立"。

---

## 3. 代码地图（哪些文件是权威，改东西落在哪）

```
src/main/java/dev/nbw/
  domain/     ★ 纯规则：零 MC/Sable import，全部有单测
    round/     RoundMachine（阶段状态机、分队、判胜、releaseAbsent）
    raft/      SinkRule / RaftLayout（甲板几何唯一来源）/ OarModel / SpeedGovernor / WindModel
    construct/ BuildRules（建造判定）
    net/       HoldPolicy（长按可达判定，含 localDistance 坐标口径）
    rescue/    ClingRule（扒边自救 + 局部帧三轴插值与落点）/ ReachRule（伸手救援，两扇门）
    arena/     SeaAudit / ChunkPacing
    ★ ZoneModel 直接放在 dev.nbw.domain 根包（没有 domain/zone 子包），配 ZoneModelTest
  cfg/        ConfigModels + ConfigService（arena/format JSON，只读回退到 jar 内置）
  round/      RoundManager（tick 编排 + 落盘 + 兜底清扫）——★ tick 顺序在这儿，别在别处加时序
  raft/       RaftCoreService / OarService / RaftMotionService / Sailing / WindService / RaftSinking
  rescue/     RescueService（落水与救援适配层）
  construct/  BuildService（挂方块事件）
  content/    自定义方块与 BE（OarBlock/OarBlockEntity/RaftCoreBlock/RaftCoreBlockEntity）
  net/        HoldPayload（nbw:hold）+ NbwNetworking（服务端接收与判定）
  client/     HoldInputClient（★ 只能在客户端入口注册，服务端类不许引用它）
  sable/      SableRafts ——★ 全工程唯一允许 import dev.ryanhcode.sable.* 的包
  cmd/        NbwCommands（/cww 命令树；权限 admin()=等级 2；失败一律回字符串不抛异常）
src/test/java/dev/nbw/domain/...   JUnit5，一个纯规则一个 *Test
tools/*.py    RCON 端到端探针（Carpet 假玩家驱动）；共享夹具在 sea_probe.py（P/keep_alive/sweep_stale_bots/write_long_format）
docs/content/ 玩法与数值权威（带待批标记）
docs/dev/     开发计划 + 实测记录（M2-RESULTS.md 是主日志）
```

**tick 里的固定顺序**（`RoundManager.onEndTick`，PLAY 阶段）：
`RaftSinking → RaftCoreService → WindService（登记目标）→ OarService（登记目标）→ RescueService（只挪玩家）→ RaftMotionService（合成目标，每 tick 只施一发冲量）`
新增动力源或位移源时必须按这个次序插入，**两个写位置的地方不能同 tick 都写**。

---

## 4. 进度快照（对照 03-里程碑 的人日盘）

| 里程碑 | 状态 | 已完成 | 还缺 |
|---|---|---|---|
| **M0** 可行性 | ✅ 收官（G0 通过） | Sable 四条判定全绿 | — |
| **M1** 对局骨架 | ✅ 收官（G1 三条全关） | 六阶段状态机、配置校验、**状态落盘 + 重启恢复** | — |
| **M2** 木筏玩法闭环 | 🟡 **约 78%** | 见下 | **PVP 武器表与伤害口径**、**Gate G2 真人验收**、`m2_round.py` 全量演练探针 |
| **M3** 黑海+物资+竞技核心 | ⬜ 0% | — | 圈层压缩、圈外伤害与附加效果、预告与可视化、环带品质、物资刷新、防蹲点、埋点配平回路 |
| **M4** 生存养成 | ⬜ 0% | — | 营养/水分/受伤 + HUD 状态条、生产链 6 设施 + 筏级功率池、武器 12 件 + 平衡护栏 |
| **M5** 幸存者+事件+运营 | ⬜ 0% | — | 幸存者 6 职业、事件池 6 个、战绩持久化/赛后面板/观战空投、部署运维与开服演练 |

**M2 已交付的六块**（都有实测记录）：
沉筏与筏心残骸修复（A2/A2-v2）· 开局发筏（4×4 + 设施位每 5 格 1 位）· 帆（风）推进 · 桨台推进 ·
航行速度控制器（**位移口径**，实测落目标的 90%）· 真实海域 arena（内置 `default_sea` r=140 + `test_sea` r=60，
function 分批建海 + `cww arena terrain` 审计 + READY 前逐区块预热）· 长按输入通道 · 建造规则 ·
落水扒边自救 + 队友伸手救援。

### 测试资产现状（分两类，别当成一次全量绿灯）

**本轮（2026-11-07）在 dev 服务端实跑过的**：

| 项 | 最近结果 |
|---|---|
| 单测（`./gradlew test`） | **223 个，0 失败** |
| `rescue_probe.py` | 44 条 PASS + 1 条 INCONCLUSIVE（假玩家站不住筏面） |
| `oar_probe.py` | 43/43 |
| `core_probe.py` | 19/19 |
| `seat_probe.py` | 8/8 |
| `build_probe.py` | 60/60 |
| `sea_probe.py` | 39/39 |

**沿用 `M2-RESULTS.md` 的历史结果，本轮没复跑**：`raftgen_probe` 18/18、`sink_probe` 15/15、
`g1_guards` 9/9、`wind_probe` 9/9、`oar_restart_probe` before 4/4 + after 11/11、`m1_round` 5 局 120/120。
**动到沉筏、开局发筏、配平守卫、风模型、重启恢复任何一处，这些必须复跑再宣称通过。**

**改完代码要复跑的最小回归集**：`test` + 受影响的那个探针 + `oar_probe`（它最依赖环境干净度，能顺带暴露夹具污染）。

---

## 5. 关键硬事实（重新查很贵，别重查）

这些是从 Sable 源码 / 映射 / 实机取证得来的结论，**已经写进代码注释与 M2-RESULTS**，此处汇总：

### 5.1 三套坐标空间，混用不报错、只会"永远判不够"

| 空间 | 量级 | 谁在用 | 换算 |
|---|---|---|---|
| **父世界显示坐标** | 几百 | 实体 `getX/Y/Z`、玩家位置、`requestTeleport` | `world = origin + q·local` |
| **存储坐标** | **≈2×10^7** | 方块交互事件、客户端准星、`HoldPayload.pos`、BE 自己的 `getPos()` | `local = storage − plot.getCenterBlock()` |
| **甲板局部帧** | 围绕 0 的小数 | 所有玩法判定（间隙、落点、可达距离） | `local = q⁻¹·(world − origin)` |

- 实体世界坐标过位姿逆变换**出来就是甲板局部，不能再减 plot 中心**；
  交互事件给的存储坐标**要减中心**、不做旋转。写反的表现是"人贴着船壳、服务端算出 28,966,064 格间隙"。
- **sub-level 的方块实体物理上就在父世界里**（`ServerSubLevelContainer.java:152` 把父 `ServerLevel` 交给 sub-level），
  所以 `overworld.getBlockEntity(存储坐标)` **读得到**我们的 BE —— 认方块那条分派一直是对的。
- **可达距离必须在甲板局部帧量**：父世界玩家坐标 vs 存储坐标直算 = 28,964,583 格 → `HoldPolicy` 会把
  **每一个**筏上长按判 REJECT（这就是 M2-8 查出来的那条缺陷）。取证命令：`cww net probe <玩家> deck|oar|at …`。

### 5.2 Sable 侧的行为结论

1. **浮力有两条路径**：Java 侧 `FloatingBlockController` 不读世界流体；**Rust 侧 `sable_rapier/.../buoyancy.rs` 逐格采样父世界方块**
   （`do_float = 10.5 × overlap × voxel`、`do_drag = −v × 1.7 × overlap`）→ **造海会改变航行读数**。
2. **外层区块不足加载时整艘筏会被踢出物理管线**（`PhysicsChunkTicketManager:165-191`），
   但**持有强制加载票的 sub-level 由 Sable 自己下 inhabit 票**；我们的筏已持 `nbw:round_active`，**不要再加第二张票**。
3. **1.21.1 datapack/命令约定**：目录单数 `data/<ns>/function/`；函数宏存在；
   `return` 只接受裸 `return fail`（**写 `return fail 0` 会让整个函数静默加载失败**）；mod jar 的 `data/` 自动挂成 datapack。
4. **Sable 的"sub-level 梯子可爬"在 Fabric 侧不可用**：`SablePlatformImpl.isBlockstateLadder` 直接 `return false`
   （注释 "handled already for fabric" 是别人的说明，不是我们的实测）→ 扒边/拉回是我们自己的服务端状态机。
5. **交互距离不用我们自己校验**：`interaction_distance/PlayerMixin:38` 已把玩家眼睛反投影进筏空间比过。
6. **本版本 Fabric API 没有"改伤害数值"的钩子**：`ServerLivingEntityEvents` 只有
   `ALLOW_DAMAGE`（能不能打）与 `AFTER_DAMAGE`（打完告知多少）。"拦掉再补半刀"会重复触发事件链——
   这是 **M2 尾巴 PVP 武器表必须先解决的问题**（见 §7.1）。
   `fabric-events-interaction-v0 0.7.14` **没有** `PlayerBlockPlacementEvents`，放置只能挂 `UseBlockCallback`。

### 5.3 夹具（测试环境）的固定限制

- **Carpet 假玩家站不住筏面**，会穿甲板沉进水里 → 任何"站上甲板"的结论不能用它的落地读数当证据；
  有效做法：**取过程中的峰值**，或量**水平方向**（它只竖直下沉，水平分量干净）。
- **Carpet 假玩家在水里会溺死**：`sea_probe.keep_alive(p, bots)` 给他们 `water_breathing infinite` 是**必需的**，
  不是装饰。漏掉它 → 某队全灭 → `RoundMachine` 按 `aliveTeamCount()==1` 判胜提前收局，
  于是后面读到的"推力/航速"全是"场上没筏子了"（当年据此误判过一次"帆没有推力"）。**新探针开局一定要调它。**
- **假玩家发不出自定义包**（`nbw:hold` 只有真客户端能发）→ 长按路径只能靠服务端诊断命令取证，
  最终手感必须真人验。
- Carpet 把假玩家名**一律转小写**；`kill` 不触发 DISCONNECT（幽灵席位由此而来，靠 `releaseAbsent` + 每秒兜底扫）；
  **kill+spawn 后同名但 UUID 会变** → 按 UUID 绑定的夹具会假失败。
- 探针必须**自己清场**并复核（收尾后再读一次 `list`），别把污染留给下一支。

---

## 6. 验证纪律（本轮又一次代价换来的四条）

1. **断言必须能失败**。写之前先问"这个条件在什么情况下会失败"，答不出来就是没断言。
2. **瞬时读数不能当断言对象**：只存在 1 秒的状态行，采样窗口比被测量还短（RCON 一次往返 0.1~0.3 秒），
   功能真的成功了探针却报 FAIL。**改成断言"过程之后还留下的东西"**（独立量的世界坐标、服务端自己记的事件行），
   瞬时读不到只能标 INCONCLUSIVE。
3. **归因必须有直接证据**，尤其"我上一条结论是错的"要写进文档（#41 与 §3.15 各有一次实例：
   一次是没读 `force` 的返回值就下结论，一次是断定父世界读不出 BE 而被实测否掉）。
4. **环境残留会造出假成功**：同名的历史 sub-level、上一支探针的会话与队壳，都会让断言"看起来通过"。
   先量环境（数同名对象、在线人数、`cww state`）再下结论，并把环境量打印进输出。

### 6.1 新探针的骨架（照 `rescue_probe.py` 抄，别自创一套）

```python
import os, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sea_probe import P, keep_alive, sweep_stale_bots, write_long_format, SEA   # 共享夹具，别裸连 rcon.py

ok, bad, inc = [], [], []

def expect(cond, msg):                       # 每条断言都要带"为什么这条重要"
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))

def inconclusive(msg):                        # 夹具限制导致的测不出：不算失败，但必须说清原因
    inc.append(msg)
    print("  INCONCLUSIVE - %s" % msg)

def main():
    sys.stdout.reconfigure(line_buffering=True)     # 全缓冲的话被杀掉一行进度都留不下
    p = P()
    # 开局三件套：清残留假玩家 → abort 回真正干净的 LOBBY → 写夹具赛制并 reload
    print("  清残留：" + repr(sweep_stale_bots(p)))
    ...
    keep_alive(p, bots)                             # 不给 water_breathing 就会中途全灭、提前判胜
```

结尾固定是**三态汇总**：先报 `satisfied` 数，再把每条 FAIL、每条 INCONCLUSIVE 逐行打出来，
`RESULT: PASS|HAS-FAILURES`，**返回值只在 `bad` 为空时才是 0**（`return 0 if not bad else 1`）。
`INCONCLUSIVE` 不拉红整轮，但**必须写清楚"为什么测不了"和"这条由哪个单测担保"**——
`rescue_probe` 里"假玩家站不住筏面"那条就是这么处理的。

**证据规则**：每条断言旁边要把原始读数打出来（Y、间隙、距离、计数、回包前 120 字），
只打 OK/FAIL 就看不见"这个 OK 是靠什么撑起来的"，下一个人只能重跑一遍才知道。
共享夹具常量在 `sea_probe.py`：`LOG`（服务端控制台）、`SEA=test_sea`、`SAIL_ARENA=test_sail7`、
`DRY_ARENA=flat_test`（旱地负对照）、`BIG=default_sea`、`WATER_LEVEL=62`。
**0.29 那个旱地终端速度基线已经作废**，不要再拿来当水阻判据。

---

## 7. 剩余内容：怎么做、验收标准、风险

### 7.1 M2 尾巴（优先做完，Gate G2 在这）

**① PVP 武器表与伤害口径**（表已经写好了：**`docs/content/09-武器与美术管线.md`**）

那份文档已经有：§2 首发 **12 件武器表**（近战 / 远程 / 控制工具 / 筏上设施武器）标着 `[新增·待批 A17]`、
§3 破拆与生命值对照、§4 实现路径（**ADR-001：玩家安装我们的 Fabric 客户端模组 → 武器直接注册自定义 `Item`**，
数值走 `minecraft:tool` 组件 + `minecraft:attribute_modifiers` + 我们的 `weapons.json`，
**攻速与合法性一律服务端校验并写审计**）、§5 六条平衡护栏（持有上限、稀有度不叠面板、弹药经济、
风暴削远程、选用率审计、禁局外成长）。

**接手时要处理的两件事**：

1. **A17 表还没批**。数值、件数、弹药经济都是 `[待批]`，先拿这份表去要裁决，别另起一套。
2. **ADR-001 的前提在本仓库里没有记录**：`README.md` 与 `docs/dev/00-决策与版本.md` 都找不到 ADR-001
   （那句"理由见 README ADR-001"是从旧仓库带过来的引用，而旧仓库已废用）。
   **"玩家是否安装我们的客户端模组"这个前提必须重新向用户确认** —— 它同时决定 M4 的 HUD 能不能做、
   反作弊假设成不成立、以及武器外观管线走不走自定义模型。

技术上一条已经踩实的边界：**本版本 Fabric 没有"改伤害数值"的钩子**（§5.2-6），
所以"水中受伤 −50%/−25%"这类规则**只能走 `attribute_modifiers` 或条件属性修正**，
不要用"拦掉再补半刀"（会重复触发事件链）。伤害的权威性放在逻辑服务端结算。

验收：`pvp_probe.py`（新写）—— 每种武器一次命中后的血量差、击退位移、水中/筏上差异、攻速超频是否被服务端拒绝并写审计，
**每一项都要能独立失败**。

**② `m2_round.py` 无人值守全量演练探针**（任务 #45）

- 目标：一局里把**建造 → 航行 → 落水 → 自救 → 伸手救援 → 筏心摧毁 → 沉筏 → 结算 → 局后清理**串起来，
  全程零人工，末尾断言**票/筏/会话/席位/救援状态零残留**。
- 做法：复用 `sea_probe.py` 的 `P/keep_alive/sweep_stale_bots/write_long_format` 夹具；
  每步都用"另一条代码路径读回"的方式验（方块进没进 plot、人 Y 有没有真的动、计数有没有变），
  **不要拿服务端自己的 status 当唯一证据**。
- 这条过了，Gate G2 的"自动化那半边"就齐了，只剩真人那半边。

**③ Gate G2 真人验收清单**（要用户实机跑，见 `M2-RESULTS.md §6`）

- 长按桨台是否持续推进、长按筏心残骸是否推进修复、贴壳长按能否把人拽上船（客户端预测 vs 服务端权威位移会不会打架）；
- 伸手救援的三件事：动作好不好按（会不会跟放方块冲突）、**3.5 格伸手上限够不够**、被拉上来是"被拽上船"还是"凭空闪现"；
- 筏上建造的手感、加速/转向时人是否跟得住、残骸是否可辨认。

### 7.2 M3 黑海 + 物资 + 竞技核心

- **圈层压缩先写成纯函数**：压缩规则继续放在 `domain/ZoneModel.java`（`dev.nbw.domain` 根包，配 `ZoneModelTest`
  与 `cww format` 的校验链），扩它别另起一个新包。
- **圈外伤害与附加效果**：注意 §5.2-6 的伤害钩子限制，与 7.1 是同一个技术决策，**不要分两次拍**。
- **物资刷新 / 环带品质**：`02-数值与节奏.md §10` 有事件与刷新原则；防蹲点靠可见性与刷新位分布，
  先出纯规则再挂实体。
- **埋点与配平回路**是 M3 的真正交付物：局时长、首战时刻、生产参与率必须有可导出的统计，
  否则 Gate G3（"每局 15 分钟内结束、最后 3 分钟有交战、≥70% 对局用过生产设施"）无法判。
  建议在 M3 一开始就把审计/统计通道定下来（现有 `audit` 尾部 + `cww audit` 可扩展）。

### 7.3 M4 生存养成

- 营养/水分/受伤 + HUD：**HUD 需要客户端**（`NBWClient` 已有输入通道，显示是另一回事，注意 C5 分层）；
- 生产链 6 设施 + **筏级功率池**（不做电路；功率不足效率减半而非停机）；
- 武器 12 件全量接入 + 禁用原版武器合成配方；
- **建造的资源消耗仍欠**：02 §5 的"每 1 格甲板 = 4 木 + 1 布"没接，扩建目前不耗资源；
  01 §4.1 的**队伍物资箱**也未实现——这两条建议随 M4 的物资系统一起做。
- 注意：`LANDING_INSET`/设施位额度那套几何与 `maxRaftArea=96 → 半径 4 → 最多 81 列`是自洽的，
  **想让大筏再多设施位必须连 `maxRaftArea` 一起改**，单改系数无效。

### 7.4 M5 幸存者 + 事件 + 运营

- **幸存者要实体随动 sub-level**：这是 M0 没完全验过的区域，**开工前先做一次可行性 spike**
  （现有 `entity_sublevel_collision` 那套 mixin 支持到什么程度，实体在筏上被带走/跳跃/受击是否稳定）。
- 事件池 6 个：必须满足"全场同等生效 + 30 秒内可消化 + 提前 5–10 秒预告"（01 §9 的硬约束，
  RNG 不得决定胜负）。
- 运维：整合包依赖清单、版本握手、灰度回滚、备份恢复演练 → Gate G5 逐条签字。
  `04-依赖与运维.md` 里列了许可证义务（Sable 是 PolyForm Shield：禁竞争性分发，不禁商用）。

---

## 8. 等用户裁决的清单（不要自己拍板）

| # | 事项 | 现状 |
|---|---|---|
| 1 | 服务端是否商用、模组用什么许可证（建议 MIT/Apache，避开 GPL） | 未定 |
| 2 | **P-01 航行数值范式**正式批准（提案在 `docs/dev/PROPOSAL-P01-航行数值范式.md`）；桨与帆的连续推力实现都建立在它上面 | 未批 |
| 3 | `[A3]` "建材重修重置倒计时一次"与 A2-v2 重叠且更弱，是否整条删掉 | 未裁 |
| 4 | **`HAND_REACH = 3.5` 格**（伸手上限）与 **`LANDING_INSET = 0.75` 格**（落点内缩） | 待批（我为了让判定可测而定的） |
| 5 | 互救（1 秒）比自救（2 秒）快、以及"正在扒边的人同时能拉别人"要不要收紧 | 待裁 |
| 6 | 生产环境是否给筏体唯一名 `nbw_raft_<队>_<局>` 彻底避免重名歧义（#38 残留问题） | 待定 |
| 7 | #39 收尾：**"质量决定速度"这一维在闭环下不再自然涌现**，要不要让档位随质量打折（=新机制） | 待裁 |
| 8 | **`[A17]` 首发 12 件武器表**（`docs/content/09-武器与美术管线.md §2`）与弹药经济、平衡护栏数值 | **未批，M2 尾巴卡在这儿** |
| 9 | **ADR-001 的前提：是否让玩家安装我们的 Fabric 客户端模组**（该文档引用它，但本仓库没有任何记录） | **必须重新确认**；它决定 PVP 实现路线、M4 的 HUD、以及反作弊假设 |

---

## 9. 持久记忆在哪

两个目录（Markdown + `MEMORY.md` 索引，改代码交付后要同步更新）：

- 用户级：`~/.qoder-cn/memory/` —— 协作偏好（先计划待批、交付前真验证、疑问先查源码禁假设、
  长解释要附最简短结论版）、测试断言纪律、以用户实机观察为准、能移植就不自研。
- 项目级：`~/.qoder-cn/projects/C--Users-----Desktop-Crazy-Water-World/memory/` ——
  里程碑状态与待裁决（`project-nbw-open-decisions.md`，含本文 §5 的取证结论）、
  航行物理标定、并发会话与服务端运维坑、Carpet UUID 坑、外部资料位置、Yarn 名怎么查、美术与武器自产要求。

> 记忆会过期。**每次会话开头都要用当前代码/文档核对记忆里的断言**，冲突时以现场为准并回头更新记忆。

---

## 10. 文档里的已知过期点（别当成待办）

| 位置 | 过期内容 | 现状 |
|---|---|---|
| `docs/dev/01-技术架构.md` 第 58–69 行的**能力矩阵** | 五行还写着"待 M0-3/M0-4/M0-6 判定"、"不许进入开发排期" | M0 早已收官：随动、移动中建造、设施可交互都通过；落水自救也已实现；**只有"接舷/登船"确实还没做**。这张表读作历史，分层与数据流那部分仍然有效 |
| `docs/content/09-武器与美术管线.md §4` | "理由见 README ADR-001" | **本仓库 README 与 `00-决策与版本.md` 都没有 ADR-001**（旧仓库带过来的引用）。前提要向用户重新确认（§8 第 9 条） |
| `docs/content/03-地图与配置规范.md` | 整节仍标 `[新增·待批 A15]` | `cfg/`（`ConfigModels` + `ConfigService` + `cww arena/format` 校验链）**已经实现并在跑**，字段以代码为准；这份规范要反向校对一遍，别照它重写一套 |
| `docs/dev/02-风险与M0验证.md` | "M0 验证完成前一律视为未知" | 已验证，读作历史；README 的"现在最重要的事"已改指向本文档 |
| `docs/dev/wip-parked/NBWM0Probe.java` | M0 期间的临时 spike | 停在 parked 目录、**不参与构建**，不要接回 `src/` |

---

## 11. 新会话建议的第一件事

1. `git log --oneline -3` + `git status` 确认现场（本文件写于 `6cb100e`，工作树应为干净）；
2. 读 `M2-RESULTS.md` 的 §3.15（本轮的坐标空间取证、以及两条"被动作本身否定"的门槛）；
3. **先要两个前提级裁决**（§8 第 8、9 条）：① **ADR-001 的前提**——玩家到底装不装我们的客户端模组？
   PVP 实现路线、M4 的 HUD、反作弊假设三件事全挂在它上面，先定它再谈别的；② **`[A17]` 12 件武器表**批不批。
   顺带把 §8 第 4、5 条（伸手 3.5 格 / 落点 0.75 格 / 互救比自救快要不要收紧）一起问了；
4. 拿到裁决后按 §7.1 的顺序做：武器表落地 → `m2_round.py` 演练 → Gate G2 真人清单。

**M2 收尾卡在数值与前提的决策上，不是卡在代码上**——这条判断也请一并带给用户复核。

**别做的事**：不要为了凑绿灯写弱断言；不要在 `domain/` 里 import MC；不要绕过 RCON 直接改 `run/world/`；
不要参考旧仓库；不要把没有用户批准的机制当成已定方案写进 `docs/content/`。
