# AI 交接文档 v2 · 当前真实状态（2026-11-17 · M5-8 + Gate G5）

> **这份文档取代旧版 `AI-交接与工作指引.md`（2026-11-07，多处已过期）。**
>
> **权威顺序（冲突时从高到低）：**
> 1. **代码**（`src/main/java` + `src/test/java`，**304 条单测**是现状的硬证据）
> 2. `docs/dev/M2-RESULTS.md` / `M3-RESULTS.md` / `M4-RESULTS.md` / `M5-RESULTS.md` / `M3-AUDIT.md`
> 3. `docs/dev/M5-GATE5.md` / `M5-ANTICHEAT.md` / `M5-G5-BOUNDARY.md` / `M5-HUMAN-ACCEPTANCE.md` / `M5-SOAK.md`
> 4. `docs/dev/M5-OPEN-DECISIONS.md`（**待你裁决，未批准不许实现**）
> 5. `docs/dev/00-决策与版本.md`（已批准的裁决）
> 6. `docs/content/*.md`（01/02/03/09，写的是**设计意图**，不是实现现状）
> 7. 本文
> 8. 旧版 `AI-交接与工作指引.md`（只读，别当参考）
>
> **一句话**：Minecraft 1.21.1 + Fabric 的海上木筏生存竞技项目。
> 先花 5–9 人日证明 Sable 2.0.6 可用，再按 M1→M5 逐层做玩法。
> **现在代码已推进到 M5-8 + Gate G5 收口，计划中的 A–D 批次全部交付，304 条单测 0 失败。**

---

## 1. 当前进度（真实，非计划）

| 里程碑 | 状态 | 证据 |
|---|---|---|
| M0 Sable 验证 | ✅ 完成 | `M0-RESULTS.md`，Gate G0 通过 |
| M1 对局骨架 | ✅ 完成 | `M1` + Gate G1（5 局无残留/重启恢复/坏配置不崩） |
| M2 木筏闭环 | ✅ 代码完成 | `M2-RESULTS.md`（98KB 最厚）+ `rope_probe` 8/8 |
| M3 黑海+物资+竞技核心 | ✅ ~95% | `M3-RESULTS.md` + `M3-AUDIT.md`（6 错已修） |
| M4 生存+生产+重武器 | 🟡 ~92% | `M4-RESULTS.md`；修理台已补（6/6）、平衡护栏已实现（15 配方）、水弹枪已加；缺弩炮/投石机/撞角 |
| M5 幸存者+事件+运营 | 🟡 ~90% | `M5-RESULTS.md`（§5.1–§5.18）；海怪已删除（2026-11-19），缺外观解锁 |

**单测：304 条，0 失败**（`./gradlew test`）。

**最近提交**（`main` 分支）：
```
835b9f9 协作模式：主干直推 main
89b0698 协作基建：CONTRIBUTING + GitHub Actions 单测 CI
4b2b08b Gate G5：待裁决清单 + 真人验收清单 + 48h 耐久监测器
2853f3e Gate G5：边界清单 B-01～B-94 + 包洪泛/旁观者长按探针
8f07428 安全：输入包 40/s 限速（hold/move 共用）
37ed81e 安全硬化：旁观者全链路 + 远程遮挡 + Gate G5 反作弊清单
297ac57 ops：补 restore.ps1，回滚演练脚本化
53c0857 Gate G5：崩溃恢复 ×3 + 回滚演练 + M5 探针落库
da70fcf 文档：海怪可行性 spike（原版实体不跟 sub-level）
8219081 M5 事件权重：02 §10 的高/中/低 = 3/2/1
b1b4f8a M5 悬赏令：公开标记 + 击杀领赏 + 120s 到期
a6e5080 M5 事件硬化：8s 预告 + 战绩落盘 + 黑海倒流外扩
```

---

## 2. 环境与运行（照抄就能跑，含必踩的坑）

### 2.1 技术栈
- Java 21 / Gradle（Loom）/ Minecraft **1.21.1** / Yarn `1.21.1+build.3` / Fabric Loader 0.19.3
- **Sable 2.0.6**（木筏浮力/子层级，唯一的"重依赖"，碰它的代码只能在 `sable/` 包）
- 构建产物：`build/libs/nbw-0.1.0.jar` → 部署到 `dist/nbw-0.1.0.jar`

### 2.2 依赖准备（新 clone 必做）
`libs/` 被 `.gitignore` 排除，**必须自己放两个 jar** 才能编译：
1. `libs/sable-fabric-1.21.1-2.0.6.jar`（16.37 MB）
   - 来源：`https://cdn.modrinth.com/data/T9PomCSv/versions/OLG7vDD6/sable-fabric-1.21.1-2.0.6.jar`
2. `libs/sable-companion-fabric-1.21.1-1.6.0.jar`（37 KB）
   - 来源：从上面那个 jar 里 `unzip` 出 `META-INF/jars/sable-companion-fabric-1.21.1-1.6.0.jar`

> CI 已自动做这两步（见 `.github/workflows/test.yml`），本地开发手动放一次即可。

### 2.3 常用命令（Windows / PowerShell）
```powershell
# 单测（必须先停服，runServer 会占 Gradle 项目锁）
./gradlew.bat test

# 构建 jar
./gradlew.bat build
Copy-Item build\libs\nbw-0.1.0.jar dist\nbw-0.1.0.jar -Force

# 起服（★ 必须重定向日志）
gradlew.bat runServer > run\server-console.log 2>&1
# 等日志出现 "Done (" 才算就绪（约 20–30s）

# 停服：走 RCON，不要杀进程
python -c "import sys;sys.path.insert(0,'tools');import rcon;s=rcon.connect();print(rcon.run(s,'stop'))"
```

### 2.4 RCON 探针
```powershell
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"
python tools/zone_probe.py       # 圈层 8 断言
python tools/pvp_probe.py        # PVP 9 断言
python tools/security_probe.py   # 安全 14 PASS / 2 INCONCLUSIVE
python tools/event_probe.py      # 事件 18 断言
```

### 2.5 必踩的坑（代价换来的）
1. **旧服务端残留会让验证全是假的**：起服前 `netstat -ano | findstr :25565`；有就 RCON `stop`，别直接杀进程。
2. **日志必须重定向**：`gradlew.bat runServer > run\server-console.log 2>&1`。
3. **单测前先停服**：否则 Gradle 项目锁冲突。
4. **假玩家 `player kill` 后不重生**：验证"淘汰/观战"必须用真客户端。
5. **`execute if block ... run cww phase` 的输出 RCON 抓不到**：改用 `data get block`。
6. **PowerShell 5.1 读 `.ps1` 要 UTF-8 with BOM**；`.bat` 注释必须 ASCII。
7. **`libs/` 不入库**：新 clone 编译前必须补两个 Sable jar（见 §2.2）。

---

## 3. 代码地图（100 个 main 文件 / 14,636 行）

```
src/main/java/dev/nbw/
├── domain/          ★ 纯函数、零 MC import（40+ 文件，可单测）
│   ├── round/       阶段机 RoundMachine(775) / Transitions / RoundConfig
│   ├── raft/        SinkRule / ChannelRule / SpeedGovernor / WindModel / HelmsmanRule
│   ├── rescue/      ClingRule / ReachRule / LineRule
│   ├── combat/      CombatRule / GuardRule / WeatherCombatRule
│   ├── survivor/    SurvivorJob / SurvivorFormation / CraftsmanRule
│   ├── survival/    SurvivalRule / HypothermiaRule
│   ├── arena/       ArenaGeometry / SeaAudit / HazardRule / ChunkPacing
│   ├── loot/        LootRoll / LootExclusion / FlotsamMotion（洋流+起伏，纯函数）
│   ├── construct/   BuildRules / ExpansionRule
│   └── net/         HoldPolicy / PacketRateLimit
├── sable/           ★ 唯一允许 import Sable 的包（SableRafts 652 行）
├── round/           ★ 阶段机运行时 + 各 Service
│   ├── RoundManager(1011) 主循环
│   ├── ZoneService / LootService / EventService / SurvivorService
│   ├── GuardService / PowerService / MetricsService
│   └── RoundStateStore(248) 落盘/恢复
├── loot/            LootService(436) / FloatingCrate(241) 海面补给箱实体（2026-11-25 新增）
├── raft/            RaftSpawner(451) / RaftCoreService(346) / RaftSinking(252)
│                    RemoteService / WindService / RaftMotionService
├── rescue/          RescueService(578) / RopeService(171)
├── construct/       BuildService(483)
├── combat/          WeaponService(286) / CombatStats(195) / SpearProjectileEntity(投矛实体)
├── survival/        SurvivalService(256)
├── content/         NbwContent(161) / NbwEntities(实体类型) / SpearItem(石矛) / RangedWeaponItem(162) / 各 Block
├── cfg/             ConfigService(343) / ConfigModels(181) / WeaponConfig(102)
├── cmd/             NbwCommands(1159) 命令树
├── arena/           MapResetService / ArenaChunkLoader / SeaTerrain / ArenaPool
├── net/             NbwNetworking(246) / 各 Payload
└── client/          StatusHudClient / HoldInputClient / MoveInputClient / FloatingCrateRenderer / SpearProjectileRenderer
```

**权威文件（改之前先读）**：
- 阶段权限唯一源：`Phase.allowsPvp()`
- 圈层：`ZoneModel`（domain）+ `ZoneService`
- 物资：`LootRoll`（稀有度权重）+ `LootService`
- 武器：`CombatRule` + `WeaponService`
- 筏心：`RaftCoreService.breakCore`（HP/反偷家）+ `RaftSinking`
- 幸存者：`SurvivorJob` + `SurvivorService` + `GuardService`

---

## 4. 当前已交付能力（实测过）

### 4.1 核心循环
- 阶段机 `LOBBY → ENROLL(30s) → READY(10s) → PLAY(≤targetTotal+180s) → SETTLE(20s) → RETURN(5s)`
- 分队 / 队伍唯一命名 / 复活 / 掉线保席 90s
- 圈层收缩（P1–P4）+ 圈外伤害（♥→HP 已修正）+ 黑海视野压暗
- 物资：预铺 24 / 每轮 4 / 同屏 24 / 存活 120s / 稀有度抽卡 / Legendary 仅 P3 后 tier3
- 防蹲点（同坐标 2 轮废弃）+ 补给箱净空/局后还原
- 埋点：局时长/首战/末段交战/生产参与率 → `world/cww/metrics.jsonl` + `state/matches/<id>.json`
- 事件池 **5 个**：supply_rain / storm / black_sea_surge / survivor_drift / bounty（全部有实际效果）。
  **海怪（kraken）已于 2026-11-19 用户裁决删除**（原因见 `M5-RESULTS §5.18` 与 `M5-OPEN-DECISIONS D-1`）。

### 4.2 生存 + 生产
- 营养/水分 HUD + 扣减；吃食物回营养；淡水（+40）
- 流血（弯刀）+ 骨折（铁头锤）+ 绷带（止血/骨折 −30s）+ **失温**（湿身 10s 后消耗 ×1.5）
- 生产链 5/6：过滤器（30s→2 淡水）、堆肥（45s→2 面包）、厨台（20s→1 熟食）、医疗箱（60s→1 绷带）、发电机（+5 电）
- **筏级功率池**：每队 0–20，没电设施只产一半
- **队伍物资箱**：开局 1 木桨 + 8 木板 + 2 淡水（已知边界：敌人登筏也能开）
- **扩建成本**：甲板每格 4 木板 + 1 线

### 4.3 幸存者（6 职业）
- 水手（过滤器）/ 厨师（厨台/堆肥）/ 医护（医疗箱）：生产 −30%
- 工匠：残骸修复 8s→6s
- 舵手：目标速度与转向 ×1.15
- 卫兵：筏心 12 格内自动射击，20 tick 一发 6.5 HP（Normal 难度）
- 招募：钩竿优先拉漂流幸存者（+1 人）
- **反偷家 ×0.25**：有幸存者时筏心破拆 ×0.25

### 4.4 战斗 / 移动 / 救援
- 近战 5 件（攻速闸门 + 持有上限 + 击退）+ 远程 4 件（hitscan + 装填冷却 + 遮挡）
- 筏心 HP 200 + 武器破拆 + 沉筏（30s 倒计时 + 每局 1 次重修）
- 绳/钩竿/遥控/风向航行 + 危险区减速（×0.5）
- 旁观者全链路门禁 + 输入包 40/s 限速

### 4.5 运维
- `ops/deploy` / `start` / `backup` / **`restore`**（回滚脚本化，演练过）
- 崩溃恢复 ×3 已验；回滚演练 ×1 已验

---

## 5. 当前真正的剩余项

| 优先级 | 项 | 说明 |
|---|---|---|
| 高 | **48h 耐久** | 工具就绪（`M5-SOAK.md` + `soak_monitor.py`），**未跑** |
| 高 | **真人验收** | 清单就绪（`M5-HUMAN-ACCEPTANCE.md` H-01~H-30），**一次未跑** |
| 中 | 修理台（第 6 设施） | 未做 |
| 中 | 弩炮/投石机/撞角（3 件设施武器） | 未做 |
| 低 | 外观解锁 | 未做 |
| 低 | 平衡护栏（禁原版武器合成） | **已实现**：`data/minecraft/recipe/` 覆盖 15 个配方（剑/斧/弓/弩/三叉戟/锤 → barrier） |
| — | C 防透视 / F 水中受伤 | 平台限制，接受 |

**明确不要做**：录像回放（用户已明确"无需加入"）。

---

## 6. 待裁决项（`M5-OPEN-DECISIONS.md`）

| 编号 | 事项 | 推荐 |
|---|---|---|
| ~~D-1~~ | ~~海怪~~ | **已删除（2026-11-19 裁决）** |
| D-2 | 绳索/钩竿最小间隔 | **20 tick** |
| D-3 | 钩竿是否查方块遮挡 | **只给钩竿加 raycast** |
| D-4 | 黑海倒流"随后 ×1.5"的措辞 | **保持现状（事件期间）** |

> **裁决前不写产品代码**（项目规则 C4：不自己发明玩法机制）。

---

## 7. 验证纪律（每条都付过代价）

1. **改数值 → 必须起服实测**，不能只跑单测。
2. **单测绿 ≠ 测对了**：`ZoneModelTest` 曾照错误配置写断言（M3 审计发现）。
3. **探针断言必须带证据**（`PASS - 实测 40.0`），不能只写 PASS。
4. **失败要归因**：先判断"测试残留"还是"本轮引入"。
5. **起服日志是唯一真相**：`[cww]` 开头的启动日志能证明模块挂载成功。
6. **假玩家只验证服务端数据**，依赖客户端同步的表现必须真人验。

---

## 8. 协作规范

**主干直推 `main`**（唯一长期分支）：
```bash
git checkout main
git pull --rebase origin main
# ... 改代码 ...
./gradlew test
git add -A && git commit -m "M5-9：..." && git push origin main
```
详细规则见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。

---

## 9. 新会话建议的第一件事

```powershell
# 1) 确认环境干净
netstat -ano | findstr :25565        # 应该没有输出

# 2) 跑单测确认基线
./gradlew.bat test                   # 期望 304/0

# 3) 读顺序
#    本文 → CONTRIBUTING → M5-RESULTS → M5-GATE5 → M5-OPEN-DECISIONS
```

**开工前先问用户**：
- 这次做哪一项（第 5 节的剩余项）？
- 有争议的设计（`M5-OPEN-DECISIONS.md` 的 D-1~D-4）**先问，不要自己拍板**。

---

## 10. 修订记录

| 日期 | 变更 |
|---|---|
| 2026-11-14 | 创建 v2：M5-8、A–D 全交付、252 单测 |
| 2026-11-17 | 更新到 Gate G5 收口：304 单测、P2/P3（幸存者职业/失温/扩建/物资箱）、安全硬化、回滚演练、主干直推 main |
