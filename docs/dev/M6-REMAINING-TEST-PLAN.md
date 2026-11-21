# M6 剩余测试计划（待执行）

- **制定日期**：2026-11-21
- **基线提交**：`ae001fa`（`main` = `origin/main`）
- **本文用途**：`M6-FULL-TEST-PLAN.md` 是 130 项的**总清单**；本文只列**还没做完的部分**，
  每项都写清「怎么跑 / 看什么算过 / 为什么现在还没跑」。执行完一项就在第 8 节打勾。
- **当前总体状态**：A/B/C/D/E/F/G/H/I/J/K/L/M/N/O/Q/S 域的**自动化部分已全绿**；
  剩下的主要是「必须真人上手」和「必须长时间/换形态」的项。

---

## 0. 上一次执行的结论（作为本轮起点）

| 项 | 结果 |
|---|---|
| 批量探针（30 个） | 全 PASS（含 p3 / spectator / fairness / security / bounty） |
| M6 域探针 | `m6_config_probe` PASS / `m6_round_probe` PASS(198s) / `m6_edge_probe` PASS(32 PASS + 1 INCONCLUSIVE) |
| `loot_pool_probe` | 6/0 PASS（设施件实测 30%，期望 22%） |
| 单测 | **319 / 0** |
| 资源静态扫描 | `ERROR=0 WARN=0`（`tools/check_resources.py`） |
| 地形审计 | `default_sea` / `test_sea` / `flat_test` 全 **0 列问题** |
| 客户端目视 | 28 件物品图标、12 个方块模型、HUD 营养水分条、黑海提示、2×2 器械描边、帆贴图 —— 均正常 |

**上轮修掉的真 bug**（细节见 commit `eb1df12` / `ae001fa`）：内置图清单漏 4 张 /
11 个方块缺 `mineable` 标签（破块无掉落）/ 11 件物品局内拿不到 / 帆无法手放（唯一推进手段）/
`water_pistol.png` 截断 / `cww state` 不标 `[失联]`。

---

## 1. 执行前置（每次开跑前照做）

```powershell
# 1) 起服（工作目录 = 仓库根）
cd C:\Users\殷子竣\Desktop\NB-Water-World\NB-Water-World
Start-Process cmd -ArgumentList '/c','gradlew.bat runServer --offline --console=plain > run\server-console.log 2>&1' -WindowStyle Hidden
# 等到日志出现 "Done ("（约 20–40s）

# 2) RCON 环境变量（必须设，tools/rcon.py 的默认值指向旧仓库）
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"

# 3) 健康检查
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "cww state" "list" "time query daytime"
#   期望：phase=LOBBY queue=0 teams=0 / daytime=6000 / 无残留假玩家
```

**清场**（探针前必做，残留假玩家会让 `wait_clean_lobby` 卡住）：

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "player g1 kill" "player g2 kill" "player g3 kill" "player G0 kill" "kill @e[type=minecraft:item]"
```

**夹具地形复位**（改过地形后跑对局类探针前）：

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\m6_world_repair.py
```

---

## 2. 待跑的自动化探针（P0，都可以无人值守）

### 2.1 `sea_probe` —— 唯一从未跑通过的探针

- **为什么还没跑**：要现场建 `test_sea`（约 135 万方块，服务端会被拖住 2s 级），
  上轮批跑给 240s 超时不够，已改成 900s 但还没重跑。
- **跑法**：

```powershell
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"
.\run\py-embed\python.exe -I -X utf8 tools\sea_probe.py            # 常规
.\run\py-embed\python.exe -I -X utf8 tools\sea_probe.py --big      # 额外完整建 default_sea（约 75s）
```

- **算过**：`RESULT: PASS`，且断言里包含「建海后 tick 落后」这一项（它把卡顿当断言，不是跑完就算）。
- **注意**：它会**重写** `default_sea` / `test_sea` 覆盖的区块；跑完要重新
  `cww arena terrain <id>` 复核 0 列问题。

### 2.2 `restart_probe` —— 两段式，必须人工夹一次重启

- **为什么还没跑完**：它由外部重启，不能塞进批量列表（会把它后面的探针全变成
  `ConnectionRefused`）。上轮只跑了 `before` 段。
- **跑法**：

```powershell
# ① before：开一局到 PLAY，落盘 run\restart_handoff.json，然后停服
.\run\py-embed\python.exe -I -X utf8 tools\restart_probe.py before
# ② 人工重启服务端（等 "Done ("）
# ③ after：逐字段比对恢复前后
.\run\py-embed\python.exe -I -X utf8 tools\restart_probe.py after
```

- **算过**：`after` 段 `RESULT: PASS`，且 `run\restart_handoff.json` 的每个字段都在服务端日志里
  有对应的 `[state] 重启恢复：...` 行。

### 2.3 全量批跑一遍（收口）

```powershell
Remove-Item run\probe_logs\*.log -Force -ErrorAction SilentlyContinue
.\run\py-embed\python.exe -I -X utf8 tools\run_all_probes.py
Get-Content run\all_probes_summary.txt -Tail 40
```

- **算过**：30/30 PASS。**不要**把 `restart_probe` 加进这个列表。
- 顺带跑的两条（不在批列表里，单独执行）：

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\check_resources.py            # 期望 RESULT: PASS —— 无 ERROR/WARN
.\run\py-embed\python.exe -I -X utf8 tools\loot_pool_probe.py --crates 24 # 期望 6/0 PASS
```

---

## 3. 待人工 / 客户端验收（P0，必须真人上手）

> 这几项**不能**用 RCON 造：要么需要真右键、要么需要真断网、要么需要真权限 0 的玩家。
> 每项都给了「摆场命令 + 看什么」。

### 3.1 帆：玩家手动放置（本轮新加代码，只做过代码级验证）★最高优先

- **背景**：`SailBlock.onPlaced` 是本轮新增的路径（此前帆只能靠权限 2 的 `/cww sail rig`），
  意味着**真实对局里能不能开船，取决于这一条**。它现在还只有代码证据。
- **摆场**：

```powershell
# 开一局（真人客户端 + 1 个假玩家）
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch `
  "player s1 spawn" "cww force s1 1" "cww start test_3min flat_test"
# PLAY 之后：
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch `
  "give <你的ID> nbw:sail 4" "cww sail read"
```

- **操作**：手持 `nbw:sail` 对着自己甲板右键放 3–4 块。
- **算过**：
  1. `cww sail read` 的「帆=N」随放置**逐个 +1**（日志另有 `[sail] 手放登记 队=… 局部=(…)`）。
  2. 拆掉一块后「帆=N」**−1**（日志 `[sail] 手拆注销`）。
  3. 风起时 `cww sail read` 的速率随帆数变化（与 `sail_probe` 的结论方向一致）。
- **不过就是 bug**：手放不登记 → 整局推不动。

### 3.2 2×2 器械的真实放置与碰撞

- **背景**：`/cww ballista place` 走的是 `placeAt()`；玩家用物品右键走的是 `BlockItem.onPlaced`。
  后者上轮只用 `/setblock`（只放主板）看过模型，**没验过真放置**。
- **摆场**：`give <你的ID> nbw:ballista 1`、`nbw:catapult 1`、`nbw:ram 1`（在 PLAY 里）。
- **操作**：在自己筏上右键放置；然后绕到 -X/-Z 侧试图往「看不见的 3 格」里塞方块。
- **算过**：
  1. 放完后该器械占满 **2×2 四格**（`cww ballista status` 有登记；`/execute if block` 四格都是 `nbw:ballista`）。
  2. 那 3 个从方块格**不可被塞入**（右键放别的方块失败）。
  3. 拆任意一格 → **整组一起拆掉**（不留半截）。
  4. 描边/碰撞是 2×2 大盒（不是 1×1 立方）。

### 3.3 生存模式破设施方块要**掉回物品**（本轮加的 mineable 标签）

- **背景**：`mineable/axe|pickaxe` 标签是本轮新加的数据包；上轮只验过
  `execute if block … #minecraft:mineable/pickaxe` 的 tag 谓词，**没验过真掉落**。
- **摆场**：开一局进 PLAY（模组会把参赛者切 SURVIVAL），`give <你的ID> minecraft:iron_pickaxe 1`、`minecraft:iron_axe 1`。
- **操作**：用镐破 `nbw:generator` / `nbw:seawater_filter`，用斧破 `nbw:sail` / `nbw:kitchen`。
- **算过**：破完**地上有同名掉落物**（`kill @e[type=item]` 前先看）。换错工具（拿斧破发电机）应当**无掉落**。
- **不过就是 bug**：物品凭空消失。

### 3.4 权限门（O1–O4）用真人客户端验

- **背景**：`execute as <假玩家>` **不降权限等级**（源仍是 RCON 的 level 4），所以运行期权限
  从服务端侧根本造不出来；`m6_edge_probe` 只能做静态核对（25 个管理字面量是否都经
  `CmdSupport.admin`）并显式记 INCONCLUSIVE。
- **操作**：用一个**非 OP** 的客户端（`op` 列表里没有它）依次输入：

| 命令 | 期望 |
|---|---|
| `/cww join` / `/cww quit` / `/cww phase` / `/cww state` | **能用** |
| `/cww force <别人> 0` | 被拒（无权限） |
| `/cww stop` / `/cww abort` / `/cww start` | 被拒 |
| `/cww ballista place 0` / `catapult place 0` / `ram place 0` | 被拒，且 status 读数**不变** |
| `/cww loot spawn 10` / `/cww arena reload` / `/cww format reload` | 被拒 |
| `/help cww` | 完整命令树可见（与 `m6_edge_probe` 的 O4 清单一致） |

### 3.5 掉线保席位 90s（A13 / B10+B11）

- **背景**：Carpet 假玩家造不出「真断线」——`/kick` 是 no-op，`player <name> kill` 直接把假玩家
  从服务端删掉（会话都不留）。规则本身有单测
  `RoundMachineTest.disconnectKeepsSeatThenExpiresIntoElimination()` 覆盖，但**实机链路**没验过。
- **操作**：PLAY 中让真人客户端**直接关游戏窗口**（或断网）。
- **算过**：
  1. `cww state` 里该玩家后面出现 `[失联]`，**席位仍在**（不是直接消失）。
  2. 60s 内重连 → `[失联]` 消失、回到原队。
  3. 超过 90s → 变 `[淘汰]`，若因此只剩 1 队 → 走 `WINNER_DECIDED → SETTLE`。

### 3.6 客户端表现（P 域剩下的视觉项）

一次性摆场（服务端命令即可，无需输入）：

```powershell
# 28 个物品图标一墙看全
.\run\py-embed\python.exe -I -X utf8 tools\frame_showcase.py
# 截图（会自动最小化 DSH、按 DPI 正确取窗口矩形）
iex (Get-Content .\run\_shots.ps1 -Raw -Encoding utf8)
$mc = Show-Mc; Start-Sleep -Milliseconds 1500
Capture-Mc "$PWD\run\_shot_items.png" $mc | Out-Null
Restore-Dsh
```

逐项确认：

| 项 | 怎么看 | 期望 |
|---|---|---|
| 28 件物品图标 | 上面的画框墙 | 无紫黑格、无水枪那种缺失贴图 |
| 第一人称手持模型 | `give <ID> nbw:musket 1`（会自动进 0 号格） | 枪身/枪管可见，不穿模 |
| 5 件近战 | 同上 `cutlass/pike/warhammer/twin_blade/paddle` | 斜握方向正确 |
| HUD | 进 PLAY 后看左下 | 绿=营养 / 蓝=水分 两条都在，且随战斗衰减 |
| 失温提示 | 泡水 10s+ | 收到「你失温了」提示，`cww survival status <ID>` 同步为 `失温=是` |
| 幸存者 | `cww survivor`，走到圈心附近 | 村民外观、有职业名牌、能交互 |
| 帆布面 | 放 7 块帆 | 4 段 canvas 从上到下颜色渐变，绳索/木架不糊 |
| 投石机/弩炮开火 | `cww ballista ammo 0 16` 后等它打 | 有投射物/命中反馈，不刷屏报错 |

### 3.7 客户端断线重连 / 崩溃恢复

- **操作**：PLAY 中直接强杀客户端进程，重新 `runClient` 并连回。
- **算过**：能重新进服；`cww state` 里席位仍属于原队；船、帆、器械、物资都还在；HUD 重新出现。

---

## 4. 性能与长稳（P1，需要长时间挂机）

### 4.1 冒烟（10 分钟，先跑这个）

```powershell
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"
.\run\py-embed\python.exe -I -X utf8 tools\soak_monitor.py --minutes 10 --interval 10
```

- **算过**：实测 TPS ≥ 19.5、无 `Can't keep up`、无 ERROR/Exception 新增。

### 4.2 长稳（48h，Gate G5）

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\soak_monitor.py --hours 48 --interval 30
```

- **算过**：脚本汇总全过（采样无缺口 / TPS 达标 / 无 ERROR）。
- **纪律**：48h 没跑完**不许**用它的中间输出宣称 G5 通过。

### 4.3 tick 耗时基线（人工看）

- 开一局满员（`4×3`）跑满 `standard_12min`，记录 `cww metrics` 与
  `/debug start` + 10s 后 `/debug stop` 的输出，确认没有单 tick > 50ms 的系统性尖峰。

---

## 5. 生产形态（P1）

| 项 | 做法 | 算过 |
|---|---|---|
| `server.jar` 打包启动 | `gradlew.bat build` → 把 jar 丢进干净 `mods/` + 官方 1.21.1 服务端 | 能起、`[cww] configs loaded` 正常、`cww state` 可读 |
| 崩溃自动重启 | 用 supervisor/计划任务托管，`kill -9` 模拟崩溃 | 自动拉起并恢复到崩溃前阶段（依赖 §2.2 的恢复能力） |
| 回滚演练 | 备份 `run/world` → 故意改坏 `cww/*.json` → 回滚 | 坏文件被隔离到 `_invalid/`；回滚后能正常开局 |

现有备份：`run/backups/world_pre_superflat_20261121_013407`（98.4 MB，超平坦重建前）。

---

## 6. 需要你裁决的内容口径（P2，不阻塞测试）

1. **27/28 个物品没有合成配方**：目前只有 `nbw:ballista_bolt` 有配方，其余靠补给箱 +
   开局物资箱。是「就这样（战利品驱动）」还是「要给主要设施补配方」？
   → 现状已在 `docs/dev/M6-Q-RESOURCES.md` §6.1 记为「已裁决口径待确认」。
2. **`nbw:oar` / `nbw:raft_core` 仅系统放置**：已在扫描器里用 `SYSTEM_ONLY_ITEMS` 显式声明，
   确认这个口径即可。
3. **帆的 4 段 `sail_canvas_0..3` 不是动画**：是帆面从上到下的 4 段布料（无 `.mcmeta`）。
   如果原本想要「飘动动画」，那是没做的美术项。
4. **27 件物品贴图仍是纯色/简笔占位**：`docs/dev/美术资源需求.md` 的正式稿到位后直接覆盖同名文件。
5. **CPS 反作弊开关 / 定时任务**（P2，你之前明确说先不做）：是否本轮解锁。

---

## 7. 已知环境限制（不要在下面这些点上浪费时间）

1. **键鼠注入在这台机器上进不了 MC**：`SendInput` / `keybd_event` 返回成功但游戏无响应，
   `PostMessage` 也一样。所以「按 E 开背包」「按 F2 截图」这类操作**无法自动化**；
   目视验收一律走「服务端摆场 + 截图」。
   - 截图工具已修好 DPI 缩放问题：不调 `SetProcessDPIAware()` 的话 `GetWindowRect` 返回逻辑像素、
     `CopyFromScreen` 返回物理像素，会截出错位图。
2. **Carpet 假玩家**：`/kick` 无效；`player <name> kill` 会连会话一起删。凡是「断线」相关只能真人测。
3. **`execute as` 不降权限**：权限门只能真人测（见 §3.4）。
4. **`/fill` 单次上限 32768 方块**：大范围操作要用 `tools/worldbuild.py:fill_box()` 分块，
   否则会**静默截断**。
5. **PowerShell 5.1 读 UTF-8 会乱码并可能吞换行**：改脚本/文档请用 `write`/`edit` 工具，
   或 `Get-Content -Encoding utf8`；曾因 `Get-Content -Raw | Set-Content` 把
   `run_all_probes.py` 的中文写坏、把 `_send.ps1` 的 C# 属性吞进注释里。
6. **Sable 客户端 ERROR `Unknown block: create:flywheel`**：Sable 自带的物理 tag 引用未安装的
   Create 方块，**不是本项目的缺陷**，可忽略。

---

## 8. 执行记录（跑完就打勾，写上日期与结论）

| # | 项 | 优先级 | 方式 | 状态 | 日期 | 结论/证据 |
|---|---|---|---|---|---|---|
| 1 | `sea_probe`（常规 + `--big`） | P0 | 自动 | ☐ | | |
| 2 | `restart_probe before/after` | P0 | 半自动 | ☐ | | |
| 3 | 全量批跑 30 个探针 | P0 | 自动 | ☐ | | |
| 4 | `check_resources` / `loot_pool_probe` | P0 | 自动 | ☐ | | |
| 5 | 帆手放 / 拆帆登记 | P0 | 真人 | ☐ | | |
| 6 | 2×2 器械真放置 + 碰撞 + 整组拆 | P0 | 真人 | ☐ | | |
| 7 | 生存破块掉落（mineable 标签） | P0 | 真人 | ☐ | | |
| 8 | 权限门 O1–O4 | P0 | 真人 | ☐ | | |
| 9 | 掉线保席位 90s（A13） | P0 | 真人 | ☐ | | |
| 10 | 客户端视觉 8 项（§3.6） | P1 | 真人 | ☐ | | |
| 11 | 客户端断线重连 / 崩溃恢复 | P1 | 真人 | ☐ | | |
| 12 | soak 冒烟 10 分钟 | P1 | 自动 | ☐ | | |
| 13 | soak 48h（Gate G5） | P1 | 自动 | ☐ | | |
| 14 | tick 耗时基线（满员 12min） | P1 | 半自动 | ☐ | | |
| 15 | `server.jar` 生产启动 | P1 | 人工编排 | ☐ | | |
| 16 | 崩溃自动重启 | P1 | 人工编排 | ☐ | | |
| 17 | 回滚演练 | P1 | 人工编排 | ☐ | | |
| 18 | 内容口径 5 条裁决（§6） | P2 | 你 | ☐ | | |

---

## 附：本次用到的工具速查

| 工具 | 用途 |
|---|---|
| `tools/run_all_probes.py [--only a,b]` | 批量跑探针（每项独立子进程 + 超时 + 汇总） |
| `tools/sea_probe.py [--big]` | 建海域 + 航行端到端 |
| `tools/restart_probe.py before\|after` | 重启恢复两段式 |
| `tools/loot_pool_probe.py --crates N` | 补给箱内容 / 设施池出现率 |
| `tools/frame_showcase.py` | 用 item frame 摆 28 件物品的展示墙 |
| `tools/m6_config_probe.py` / `m6_round_probe.py` / `m6_edge_probe.py` | A/B·L/O·S 域专用 |
| `tools/m6_world_repair.py` | 开局前复位 `flat_test` 锚点地形 |
| `tools/check_resources.py [--strict]` | Q 域静态扫描（模型/贴图/配方/标签/PNG 结构） |
| `tools/soak_monitor.py --minutes N\|--hours N` | TPS / 异常 / 采样缺口 |
| `tools/worldbuild.py <lobby\|flattest\|arena\|template>` | 重建大厅 / 水池 / 两张海域 / 残骸模板 |
| `tools/gen_water_pistol_icon.py` | 重画 `water_pistol.png` 并全量校验贴图 |
| `tools/rcon.py --batch "a" "b"` | 单条/多条 RCON |
| `run/_shots.ps1` | `Show-Mc` / `Capture-Mc` / `Restore-Dsh`（已处理 DPI） |
| `run/_win.ps1` | 窗口查找/置前/按矩形截图 |
