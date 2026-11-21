# AI 自主测试手册（NB-Water-World）

> 面向对象：**别的 AI / 别的机器上的协作者**。目标是你拿到这份仓库后，**不用问人**就能把服务端跑起来、
> 把客户端画面调出来、跑测试、看懂结果、并在出问题时知道下一步查什么。
>
> 版本基线：`main` @ `22d8567`（2026-11-21）。若你看到的提交比这更新，本文的"已知基线"一节可能已过期，
> 但流程与坑基本不变。
>
> 配套文档（本文是"怎么动手"，那几份是"测什么"）：
> - `docs/dev/M6-FULL-TEST-PLAN.md` —— 130 项总清单
> - `docs/dev/M6-REMAINING-TEST-PLAN.md` —— 还没做完的项（含每项的判据）
> - `docs/dev/M6-Q-RESOURCES.md` —— 资源完整性静态扫描结论与修复记录

---

## 0. 30 秒版（先跑通这一条）

```powershell
# 1) 起服
cd <仓库根>
Start-Process cmd -ArgumentList '/c','gradlew.bat runServer --offline --console=plain > run\server-console.log 2>&1' -WindowStyle Hidden
# 等 run\server-console.log 里出现 "Done ("（约 20–40s）

# 2) 配 RCON（每次开新终端都要设）
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"

# 3) 确认活着
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "cww state" "list" "time query daytime"
#  期望：phase=LOBBY queue=0 teams=0 / daytime=6000 / 无残留假玩家

# 4) 跑全量探针（约 25–35 分钟）
Remove-Item run\probe_logs\*.log -Force -ErrorAction SilentlyContinue
.\run\py-embed\python.exe -I -X utf8 tools\run_all_probes.py
Get-Content run\all_probes_summary.txt -Tail 40
#  期望：30/30 PASS
```

跑通这四步，你就已经能开始独立测试了。剩下的章节都是"为什么"和"出问题怎么办"。

---

## 1. 这是什么项目（你只需要知道这些）

- **Minecraft 1.21.1 + Fabric** 的「海上木筏生存竞技」模组。进入对局后每队一艘筏，
  靠帆/风移动、靠补给箱拿装备、靠打掉对方**筏心**或最后存活取胜。
- **技术栈**：Java 21 / Yarn `1.21.1+build.3` / Fabric Loader `0.19.3` / Fabric API `0.116.17+1.21.1` /
  Loom `1.17.21`；木筏的物理用 **Sable 2.0.6**（sub-level）。
- **代码分层（改代码前必须知道）**：
  - `src/main/java/dev/nbw/domain/` —— 纯函数，**零** `net.minecraft.*` import，单测覆盖的主战场；
  - `src/main/java/dev/nbw/sable/` —— 唯一允许碰 Sable 的适配层（另有 `content/RaftCoreBlockEntity`、
    `content/SailBlock` 两个文档化的例外）；
  - 其它 `content/ round/ raft/ cmd/ cfg/ ...` —— 服务端实现；`client/` —— 客户端 HUD/输入。
- **对局阶段机**：`LOBBY → ENROLL(30s) → READY(10s) → PLAY → SETTLE(20s) → RETURN(5s) → LOBBY`。
  所有"现在能不能做某事"都要先看阶段（`cww phase`）。
- **配置位置**：`run/world/cww/{lobby.json, arenas/, formats/, weapons.json, state/round.json, career.json}`。
  模组对这个世界目录**只读**；文件缺失时回退到 jar 内置 `src/main/resources/data/nbw/`。
  所以"改了内置图不生效"十有八九是 `run/world/cww/` 下有同名文件把它盖住了（`cww arena list` 会报这个）。

**关键路径一览**

| 路径 | 是什么 |
|---|---|
| `run/server-console.log` | 服务端完整日志（RCON 也能读，但看日志更快） |
| `run/client-console.log` | 客户端日志（**客户端侧**报错只看这里，服务端日志里没有） |
| `run/world/` | 测试存档 |
| `run/world/cww/` | 配置与状态（`state/round.json`、`state/matches/<n>.json`、`career.json`） |
| `run/py-embed/python.exe` | 本机自带的嵌入式 Python（`run/` 不入库；换机器用系统 `python`，探针只用标准库） |
| `run/probe_logs/<name>.log` | 批跑时每个探针的完整输出 |
| `run/play_shots/` | 多轮对局探针的截图与 `summary.json` |
| `tools/` | 全部测试脚本 |
| `src/main/resources/data/nbw/` | 内置 arena/format/lobby/weapons JSON |

---

## 2. 环境准备

| 依赖 | 要求 | 怎么确认 |
|---|---|---|
| JDK | **21**（`java -version` 出 `21.x`） | `java -version` |
| Gradle | 用仓库自带的 `gradlew.bat` / `gradlew`（不要用系统 gradle） | `.\gradlew.bat --version` |
| 网络 | 首次需要拉依赖；之后一律加 `--offline` | `--offline` 能跑起来就行 |
| Python | **任意 3.9+ 均可**（所有探针只用标准库）。本机习惯用 `run/py-embed/python.exe` | `python -V` 或 `.\run\py-embed\python.exe -V` |
| 客户端自测（可选） | 能开图形界面。纯服务端测试不需要客户端 | §6.3 |

> **`run/` 和 `run-client/` 都被 `.gitignore` 排除了**，所以：
> - `run/py-embed/python.exe`：本机自带的嵌入式 Python，**不在仓库里**。换机器就用你系统上的 `python`；
> - `run/world/`、`run/*.log`：运行期产物，别人的机器上不会有（第一次起服会重建世界）；
> - 截图/起客户端的脚本已入库在 **`tools/mc-shots.ps1`** + `tools/mc-window.ps1`
>   （早期放在 `run/` 里的同名文件只是本地转发壳，别依赖它们）。


> **Windows 上的 PowerShell 版本**：可能是 5.1（不是 7）。5.1 读 UTF-8 文件**默认按 ANSI 解**，
> 会把中文变乱码、还可能出现"两行被合并成一行"的诡异语法错误。改脚本请用编辑器/`write` 工具，
> 用 `Get-Content` 时**必须**带 `-Encoding utf8`。见 §12。

---

## 3. 三条控制通道（以及各自的能力边界）

| 通道 | 能做什么 | **不能**做什么 |
|---|---|---|
| **RCON**（25576） | 跑任何 `/命令`、读写实体/方块数据、spawn/kill 假玩家、强制开局、读一切状态 | 不能模拟玩家右键；`execute as` 不降权限；不能截图 |
| **探针脚本**（`tools/*_probe.py`） | 把上面这些编排成"场景 + 断言"，输出 PASS/FAIL | 同样不能右键；不能真正断线（假玩家语义不同） |
| **客户端 + 截图** | 唯一能回答"玩家看到的是不是对的" | 这台机器上**键鼠注入进不了 MC**（见 §6.3），所以只能"服务端摆场 + 截图看" |

**结论（很重要）**：凡是"必须真人点一下"的东西（右键使用方块、开背包、按 F2、真正断网），
不要试图用脚本硬造，直接在报告里写成「需真人验收」。**被迫造假比漏测更糟**。

---

## 4. 服务端生命周期

### 4.1 起服

```powershell
cd <仓库根>
Start-Process cmd -ArgumentList '/c','gradlew.bat runServer --offline --console=plain > run\server-console.log 2>&1' -WindowStyle Hidden
```

就绪判据（**唯一可信的**）：`run/server-console.log` 出现 `Done (`。启动约 20–40s。

### 4.2 停服

```powershell
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py "stop"
# 等 25565 端口不再监听（约 5–15s），再重启
```

### 4.3 **绝对不要**按进程名/内存杀 java

服务端和客户端都是 `java.exe`。`Get-Process java | Stop-Process` 会连服务端一起杀掉；
更糟的是这台机器上可能同时开着**别人的** Minecraft（实测出现过 `Minecraft* Forge 1.20.1`），
`MainWindowTitle -like "*Minecraft*"` 会误杀别人的游戏。

- 要停服务端 → 用 RCON `stop`（唯一正解）。
- 要停我们自己的客户端 → 用 `tools/mc-shots.ps1` 的 `Stop-McClient`，它只杀**窗口所属 PID**。

### 4.4 起服后的健康检查（每次都要做）

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch `
  "cww state" "list" "time query daytime" "gamerule doMobSpawning" "cww arena list"
```

期望：
- `phase=LOBBY`，`queue=0`，`teams=0`
- `daytime` = **6000**（世界规则锁正午；如果不是，说明 `WorldRules` 没生效，这是真 bug）
- `doMobSpawning` = **false**（同理）
- `cww arena list` 的 `内置=[...]` 应有 6 张图（`default_sea, flat_test, test_build, test_sail7, test_sea, test_side7`）

---

## 5. RCON 速查

### 5.1 连接参数

| 项 | 值 |
|---|---|
| 主机 | `127.0.0.1` |
| 端口 | **25576**（`tools/rcon.py` 的**默认值是旧仓库的 25575，必须用环境变量覆盖**） |
| 密码 | `nbwm0` |
| 环境变量 | `RCON_PORT` / `RCON_PASSWORD` / `PYTHONIOENCODING=utf-8` |

### 5.2 命令行用法

```powershell
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py "cww state"                  # 单条
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "list" "cww phase"   # 多条
```

### 5.3 在 Python 里用（探针都这么写）

```python
import sys; sys.path.insert(0, "tools")
import rcon
sock = rcon.connect()                 # 带重试；认证失败抛 RuntimeError
out  = rcon.run(sock, "cww state")    # 每次调用一个请求/响应；出错抛 ConnectionError
sock.close()
```

### 5.4 `/cww` 命令分组（按用途记，不背全量）

| 组 | 命令 | 用途 |
|---|---|---|
| 阶段 | `cww phase` / `cww state` / `cww audit [n]` / `cww save` / `cww statefile` | 读数与审计（`audit` 是阶段转移的唯一权威记录） |
| 对局 | `cww join` / `quit` / `start [format] [arena]` / `stop` / `abort` / `force <p> <team>` | 玩家可用：`join/quit/phase`；其余需权限 2 |
| 图/赛制 | `cww arena (list\|test\|reload\|terrain\|pool)` / `cww format (test\|reload)` / `cww lobby (test\|reload)` | 配置有效性、地形审计 |
| 筏 | `cww raft (status\|all\|gen\|bind\|sink\|weight)` / `sail (read\|rig\|strip)` / `wind (read\|set)` | 航行读数与夹具 |
| 战斗/设施 | `cww core status` / `cww ballista (status\|place\|ammo)` / `catapult (status\|place)` / `ram (status\|place)` / `raftcore (status\|destroy\|hold)` | 攻城与筏心 |
| 生存/幸存者 | `cww survival (status\|set)` / `cww survivor [job <team> <job>]` / `cww guard` / `cww supply` / `cww power (status\|add)` | 营养水分、职业、电量 |
| 其它 | `cww zone` / `cww event [fire]` / `cww metrics` / `cww stats` / `cww loot (status\|spawn)` / `cww weapon ...` / `cww net (probe\|flood)` / `cww build ...` | 事件、指标、物资、网络 |

> 完整命令树：游戏内 `/help cww`，或 RCON `help cww`（一条长字符串）。

### 5.5 夹具：假玩家（Carpet）

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch `
  "player g1 spawn" "cww force g1 0" `
  "player g2 spawn" "cww force g2 1" `
  "cww start test_3min flat_test"
```

- 造人：`player <name> spawn`；删人：**`player <name> kill`**（`/kick` 对假玩家是 **no-op**）。
- 假玩家不会右键（`onUse` 触发不到），也造不出"真断线"。
- 用假玩家跑对局时建议续命：`effect give <name> minecraft:night_vision 900 0 true` +
  `water_breathing`，否则它们在水下窒息会干扰读数。

---

## 6. 怎么观测

### 6.1 读数（首选，最便宜）

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py "cww state"
```

一行里包含：阶段/阶段内 tick/总局数/规格/下限/硬上限/是否自动开局；队列与队伍；
上一局结果（胜者/原因/时长/参战/存活）；最后一次阶段转移；当前图与赛制；锚点数；在线人数。
**A 到 Z 的很多东西都能从这一行看出来**，先看它再看别的。

### 6.2 日志（第二优选）

| 位置 | 看什么关键字 |
|---|---|
| `run/server-console.log` | `[round]`（阶段/淘汰/观战）、`[AUDIT]`、`[S10]`（局后残留复检）、`[loot]`、`[sail]`、`[C21] respawn`、`ERROR`/`Exception`/`Can't keep up` |
| `run/client-console.log` | `Missing textures` / `Using missing texture`（贴图缺失）、`JsonParseException`（模型坏）、`[cww]`（客户端提示） |
| `run/probe_logs/<name>.log` | 批跑时每个探针的完整输出（FAIL 时先看这里） |

```powershell
Select-String -Path run\server-console.log -Pattern "ERROR|Exception|Can't keep up" | Select-Object -Last 20
Select-String -Path run\client-console.log -Pattern "missing texture|JsonParseException" -Encoding utf8
```

### 6.3 客户端画面（唯一能验"玩家看到的对不对"）

**先说边界**：本机实测 `SendInput` / `keybd_event` / `PostMessage` **都进不了 MC**
（调用返回成功，游戏毫无反应）。所以：

- ✅ 能做：自动起客户端、自动进服、**服务端摆场景**、自动截图、人工看图
- ❌ 不能做：按 E 开背包、按 F2 截图、按 ESC、打字、右键方块

#### 起客户端

> **可以同时开两个客户端（内存允许）**。双客户端是测"真 PvP / 双队都有真人 / 双方各自死亡复活"
> 这类链路的唯一办法，也能绕开"假玩家一死整队全灭"的夹具缺陷。要点：
> - 第二个实例走独立的 run 配置（`gradlew runClient2`）+ 独立 gameDir `run-client2\`，
>   否则两个实例抢 logs/options.txt；
> - 用户名必须不同（`build.gradle` 的 client2 里注入 `--username Tester2`），
>   同名会拿到同一 UUID 被服务端当重复登录踢掉；
> - **新 gameDir 第一次启动会卡在"无障碍引导/多人游戏警告"**，quickPlay 不会生效。
>   先把这几行写进 `run-client2\options.txt`：`onboardAccessibility:false`、`skipMultiplayerWarning:true`。
>
> ```powershell
> Start-McClient                 # 客户端 1（run-client\, 用户名 Loom 生成）
> Start-McClient -Client 2       # 客户端 2（run-client2\, 用户名 Tester2）
> [McWin]::FindAll("Minecraft* 1.21.1")   # 两个窗口的 hwnd，按枚举顺序取
> ```

```powershell
cd <仓库根>
iex (Get-Content ".\tools\mc-shots.ps1" -Raw -Encoding utf8)   # .ps1 不能直接执行，必须 iex
Start-McClient                  # 启动 + 等进服（最多 180s）
Start-McClient -Fresh           # 先关掉已有客户端
Stop-McClient                   # 只杀我们这个窗口的 PID
Wait-McJoined "Player627" 90    # 只等某个名字进服
```

它内部做的四件事（你要自己写脚本时必须照做）：
1. 把 `run-client\options.txt` 的 `pauseOnLostFocus` 改成 `false`
   （**必须**：截图时焦点会离开 MC，否则它自己弹暂停菜单）；
2. `--arguments "--quickPlayMultiplayer 127.0.0.1:25565"`，跳过主菜单直连；
3. 等窗口标题出现；
4. 轮询 RCON `list`，确认玩家真的进来了。
   > 注意 `runClient` 的 gameDir 是 **`run-client\`**，不是 `run\`。

#### 截图

```powershell
iex (Get-Content ".\tools\mc-shots.ps1" -Raw -Encoding utf8)
Save-McShot "C:\...\run\_shot_x.png"     # 摆窗口 → 截图 → 还原 DSH，一步到位
```

必须知道的三个坑（都已内置在 `mc-shots.ps1` 里）：
- **DPI**：不调 `SetProcessDPIAware()`，`GetWindowRect` 返回逻辑像素、`CopyFromScreen` 返回物理像素，
  截出来是错位的一小块（实测 1920×1080 的窗口只截到 1280×720）。
- **窗口匹配不能模糊**：只能匹配我们 dev 客户端的标题 `Minecraft* 1.21.1`。
  用 `*Minecraft*` 会匹配到别人的 Forge 客户端。
- **"最小化 DSH"是可选项**：脚本会去找标题含 `DeepSeek Harness` 的窗口并临时最小化，
  那只是为了让"自动化宿主"别盖在 MC 上面；你不在 DSH 里跑时它找不到就会自动跳过，不影响截图。
- **最小化窗口的矩形是假的**（`-21333`），必须先 `ShowWindow(SW_RESTORE)` 再取矩形；
  `Capture-Mc` 有 `Test-McRect` 兜底，不合格会明确报错而不是存一张废图。

#### 用画面做验收的思路（不需要键盘）

摆场景 + 截图，一次看全一批东西，例如：

```powershell
# 28 件物品图标一墙看全（item frame 展示墙）
.\run\py-embed\python.exe -I -X utf8 tools\frame_showcase.py
# 12 个方块模型摆一排
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "setblock -8 201 -5 nbw:raft_core" ... "tp <ID> -2.5 201.9 1.5 180 12"
```

**多轮对局 + 自动截图**用现成的：

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\play_loop_probe.py --rounds 5
# 截图与结论落在 run\play_shots\（r01_01_play_start.png … summary.json）
```

---

## 7. 探针目录

### 7.1 批量跑（30 个）

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\run_all_probes.py
.\run\py-embed\python.exe -I -X utf8 tools\run_all_probes.py --only p3_probe,sail_probe
.\run\py-embed\python.exe -I -X utf8 tools\run_all_probes.py --skip sea_probe
```

每项独立子进程 + 超时（默认 300s；`sea_probe` 900s、`m6_round_probe` 700s、`build_probe` 400s）。
汇总写 `run/all_probes_summary.txt`，单个日志写 `run/probe_logs/`。

| 探针 | 覆盖 | 用时参考 |
|---|---|---|
| `p3_probe` | 队伍物资箱 / 扩建扣费 / 失温 | ~60s |
| `raftgen_probe` | 筏生成、锚点、质量、沉筏 | ~90s |
| `build_probe` | 建造限制（面积/方块数/高度/归属）+ 真实放置 | ~200s |
| `core_probe` | 筏心 HP、破拆 ×0.25、破拆链路 | ~60s |
| `seat_probe` | 队席分配/改派/满员 | ~15s |
| `sea_probe` | **建 1.35M 方块海域** + 航行端到端（唯一"重活"） | 5–15min |
| `wind_probe` / `sail_probe` | 风场、挂帆/拆帆、帆数对受力的影响 | ~60s |
| `rope_probe` / `rescue_probe` | 绳索、落水救援、伸手/自救 | ~60–120s |
| `remote_probe` | 遥控器 WASD 输入链路 | ~40s |
| `sink_probe` | 沉筏判定与残骸 | ~40s |
| `survivor_probe` / `guard_probe` / `helmsman_probe` / `craftsman_probe` | 幸存者四种职业行为 | 各 ~60s |
| `event_probe` / `zone_probe` / `hazard_probe` / `storm_probe` | 事件、圈层收缩、灾害、风暴对武器的影响 | 各 ~60–90s |
| `loot_clearance_probe` | 补给箱不刷在筏体附近 | ~60s |
| `pvp_probe` | 五件近战/远程伤害与冷却、PvP 闸门 | ~60s |
| `stats_probe` | 战绩统计（击杀/阵亡/伤害） | ~40s |
| `spectator_probe` / `fairness_probe` / `bounty_probe` | 观战、公平性、悬赏 | 各 ~50–220s |
| `security_probe` | 限速、伪造包、越权尝试 | ~90s |
| `m6_config_probe` | A 域：内置图/赛制/lobby 可加载、坏 JSON 隔离、热重载门 | ~70s |
| `m6_round_probe` | B/L 域：全阶段链路、审计、战绩落盘、生涯累加 | **~200–330s** |
| `m6_edge_probe` | O/S 域：权限静态核对、0/1/满员、幂等、并发 | ~170s |

### 7.2 单独跑（不进批列表，**原因很重要**）

| 探针 | 为什么单独 | 怎么跑 |
|---|---|---|
| `restart_probe` | **两段式**：`before` 会主动停服；塞进批跑会把后面所有探针变成 `ConnectionRefused` | `restart_probe.py before` → 人工重启 → `restart_probe.py after` |
| `loot_pool_probe` | 需要"刚开过一局"的干净状态（`spawned` 上限 24） | `loot_pool_probe.py --crates 24` |
| `play_loop_probe` | 需要客户端在线 + 真人观感 | `play_loop_probe.py --rounds 5` |
| `check_resources` | 纯静态，不碰服务端 | `check_resources.py [--strict]` |
| `soak_monitor` | 长时间挂机采样 | `soak_monitor.py --minutes 10` / `--hours 48` |
| `worldbuild` | 重建世界（大厅/水池/两张海域/残骸模板） | `worldbuild.py <lobby\|flattest\|arena\|template>` |
| `frame_showcase` | 摆物品展示墙（配合截图） | `frame_showcase.py` |

### 7.3 夹具地形（改了地形之后要复位）

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\m6_world_repair.py      # 复位 flat_test 锚点
.\run\py-embed\python.exe -I -X utf8 tools\worldbuild.py arena     # 重建 default_sea + test_sea（约 1–3 分钟）
```

---

## 8. 标准测试流程（照抄即可）

### T0 · 冒烟（15 分钟，每次改动后先跑这个）

```powershell
# 0) 干净 LOBBY（清残留假玩家）
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "player g1 kill" "player g2 kill" "player g3 kill" "kill @e[type=minecraft:item]"
# 1) 静态扫描（不依赖服务端）
.\run\py-embed\python.exe -I -X utf8 tools\check_resources.py
# 2) 单元测试
.\gradlew.bat test --offline
# 3) 关键探针（快、覆盖广）
.\run\py-embed\python.exe -I -X utf8 tools\run_all_probes.py --only m6_round_probe,m6_edge_probe,p3_probe,sail_probe,pvp_probe,core_probe
```

判据：静态扫描 `ERROR=0 WARN=0`；单测 `failures=0`；探针全 PASS。

### T1 · 全量（30–40 分钟）

`run_all_probes.py` 全量 + `check_resources.py` + `loot_pool_probe.py --crates 24`，
最后补 `restart_probe` 两段式。

### T2 · 长稳

`soak_monitor.py --minutes 10`（冒烟）→ 需要的话 `--hours 48`（Gate G5）。

### T3 · 真人/客户端（见 `M6-REMAINING-TEST-PLAN.md` §3）

`play_loop_probe.py --rounds 5` 起手，然后按那份文档的逐项判据人工看截图。

---

## 9. 判据与结果记录

### 9.1 探针的输出契约（你写新探针必须遵守）

每个探针最后**必须**打印一行：

```
RESULT: PASS
```

失败时：

```
RESULT: HAS-FAILURES
   FAIL - <一句话说清哪条不成立>
satisfied: <通过的条数>
```

`run_all_probes.py` 靠这个判 PASS/FAIL。它的失败行正则只认：
行首 `FAIL`（后跟空白/`-`/`:`）、`- FAIL`、或 `RESULT:` 里没有 `PASS`。
所以**不要**把通过计数写成 `FAILED = 0` 之外的花样（`FAIL=0` 是白名单，其它写法可能被误判）。

### 9.2 结果记在哪

| 产物 | 位置 |
|---|---|
| 批量汇总 | `run/all_probes_summary.txt` |
| 单探针完整输出 | `run/probe_logs/<name>.log` |
| 多轮对局 | `run/play_shots/summary.json` + `*.png` |
| 静态扫描 | 终端输出（`--strict` 时有 WARN 也算失败） |
| 单测 | `build/test-results/test/*.xml` |

### 9.3 一轮测试结束要写什么

至少这五行：**基线提交 / 跑了什么 / 结果 / 新发现的真 bug / 未覆盖与原因**。
如果是给别人的报告，把"真 bug""探针失效判据""环境限制"分开写——三者混在一起会让下一个人重复踩坑。

---

## 10. 写一个新探针（模板）

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""<一句话：这个探针要回答什么问题>。

用法（服务端已起、RCON 在 25576）：
    .\\run\\py-embed\\python.exe -I -X utf8 tools/my_probe.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon                      # noqa: E402

ok, bad = [], []


def expect(cond, msg):
    (ok if cond else bad).append(msg)
    print("  %s - %s" % ("PASS" if cond else "FAIL", msg))


def main():
    sock = rcon.connect()

    def p(cmd):
        return rcon.run(sock, cmd)

    # 0) 清场：残留假玩家会让 wait_clean_lobby 卡住
    for n in ("g1", "g2", "g3"):
        p("player %s kill" % n)
    # 1) 断言：每一条都必须**能失败**（先想"什么情况下它会红"）
    expect("phase=LOBBY" in p("cww state"), "开局在 LOBBY")
    # 2) 收尾：清掉自己造的夹具
    p("cww abort")

    print("\n== 汇总")
    print("  PASS=%d FAIL=%d" % (len(ok), len(bad)))
    for m in bad:
        print("  FAIL - " + m)
    print("  RESULT: " + ("PASS" if not bad else "HAS-FAILURES"))
    sock.close()
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
```

**五条硬规矩**：
1. 每条断言都要能在某条件下失败；写完先问自己"它什么时候会红"。
2. 开局先清场（假玩家/掉落物），结束必须清掉自己造的夹具，别把世界弄脏。
3. 只用 `f"..."` 之外的标准库 + `rcon`，不引第三方包（嵌入式 Python 只有标准库）。
4. 会改地形的探针必须自己复位，或在文档里写清"跑完要 `m6_world_repair`"。
5. 输出用中文一句话说清"哪个断言不成立 + 实测值"，别只打 `assert failed`。

---

## 11. 故障排查手册

| 症状 | 最可能原因 | 处置 |
|---|---|---|
| `ConnectionRefusedError` / `WinError 10061` | 服务端没起 / 刚被 `restart_probe` 停了 / 端口不对 | 起服等 `Done (`；确认 `RCON_PORT=25576` |
| 探针卡住不动（> 分钟级） | 上一个探针把服务端留在 PLAY/SETTLE；或有残留假玩家 | `cww state` 看阶段 → `cww abort` → `player <n> kill` 清假玩家 |
| 一堆探针同时 FAIL 且都是连接错误 | 服务端掉了，或 `restart_probe` 被塞进了批跑 | 看 `run/server-console.log` 尾部；把 `restart_probe` 移出批列表 |
| `wait_clean_lobby` 一直不通过 | 残留假玩家 / `queue>0` | `player <候选名> kill`；`cww state` 确认 `queue=0 teams=0` |
| `cww start` 说"人数不足" | 下限没满足（`flat_test` 是 2） | 再 spawn 一个假玩家并 `cww force` |
| 地形审计报"水面不符" | 世界重建过 / 没建海域 / 被 `/fill` 截断 | `worldbuild.py arena`（两张海域）或 `m6_world_repair`（flat_test） |
| `server-console.log` 里 `format … 读取失败` | `run/world/cww/` 下坏了 JSON | 让它被隔离到 `_invalid/`，用内置图或修文件 |
| 客户端画面全是深蓝 | 相机在水下（`tp` 到了水面以下） | `tp` 到水面以上；或开 night_vision |
| 截图是黑的/一小块/错位 | DPI 没设 / 窗口最小化 / 匹配错窗口 | 用 `Save-McShot`（已处理）；确认标题是 `Minecraft* 1.21.1` |
| 客户端没响应任何键 | **本机已知限制**，不是你写错了 | 不要在这上面花时间，改用"摆场 + 截图" |
| `python.exe` 找不到 | `run/py-embed/` 没随仓库分发 | 用系统 Python 3.11+ 建同名目录，或见 `docs/dev/04-依赖与运维.md` |
| `gradlew` 报依赖缺失（离线） | 首次没联网拉过 | 去掉 `--offline` 跑一次，之后再加回来 |
| `Check failed`/`JsonParseException` 但服务端能起 | 客户端资源坏（模型/贴图） | 跑 `check_resources.py`，看 `run/client-console.log` |

---

## 12. 绝对不能做的事（黑名单）

1. **不要** `Get-Process java | Stop-Process`，也不要按内存筛杀进程——会杀服务端，甚至误杀别人的游戏。
   停服只走 RCON `stop`；关客户端只走 `Stop-McClient`（按窗口 PID）。
2. **不要**用 `*Minecraft*` 模糊匹配窗口/进程（这台机器上可能开着别人的 Forge 客户端）。
3. **不要**把 `restart_probe` 塞进批量列表。
4. **不要**用 `Get-Content -Raw | Set-Content` 改带中文的脚本（PS 5.1 会写坏，
   还可能把两行合并成一行造成诡异语法错误）。用编辑器/`write` 工具，读时带 `-Encoding utf8`。
5. **不要**用 `/fill` 一次性填大体积（单次上限 **32768** 方块，超出会**静默截断**）；
   用 `tools/worldbuild.py:fill_box()` 分块。
6. **不要**手改 `run/world/cww/state/round.json` 来"制造状态"——用命令。
7. **不要**测"权限门"时用 `execute as <玩家> run <管理命令>`：`execute as` 只换实体、
   **不降权限等级**，这个写法永远测不出权限。
8. **不要**用假玩家测"断线"：`/kick` 对假玩家 no-op，`player kill` 会把会话一起删掉。
9. **不要**在报告里把"没测到"写成"通过"。

---

## 13. 已知基线（2026-11-21，`22d8567`）

| 项 | 状态 |
|---|---|
| 单测 | **319 / 0**（`gradlew test`） |
| 静态扫描 | `ERROR=0 WARN=0`（`check_resources.py`，含 PNG 结构校验） |
| 批量探针 | 30/30 PASS |
| 地形审计 | `default_sea` / `test_sea` / `flat_test` 全 0 列问题 |
| 世界规则 | `daytime=6000`、`doMobSpawning=false`（启动即锁） |
| 未覆盖 | 真人右键类（§M6-REMAINING-TEST-PLAN §3）、48h 长稳、生产 `server.jar`、回滚演练 |

**最近修掉的坑（供你判断"这版是否已包含"）**：
内置图清单漏 4 张（`cww arena list` 漏报）、11 个方块缺 `mineable` 标签（破块无掉落）、
11 件物品局内拿不到（设施池漏迁）、帆无法手放（唯一推进手段）、
`water_pistol.png` 截断（紫黑格）、`cww state` 不标 `[失联]`。

---

## 14. 文档与源码导航

| 想了解 | 看哪里 |
|---|---|
| 总测试清单（130 项） | `docs/dev/M6-FULL-TEST-PLAN.md` |
| 还差什么、怎么判 | `docs/dev/M6-REMAINING-TEST-PLAN.md` |
| 资源/模型/贴图/配方/标签 | `docs/dev/M6-Q-RESOURCES.md` |
| 覆盖缺口分析（哪些项只有弱证据） | `docs/dev/M6-coverage-gap.md` |
| 架构与分层约束 | `docs/dev/01-技术架构.md`、`docs/dev/00-决策与版本.md` |
| 依赖与运维（起服/RCON/备份） | `docs/dev/04-依赖与运维.md` |
| 美术资源需求 | `docs/dev/美术资源需求.md` |
| 玩法数值（武器/设施/生存） | `docs/content/09-武器与美术管线.md` |
| 交接现状 | `docs/dev/AI-交接-v2-当前状态.md` |

**代码入口**（想加测试能挂哪里）：
`NBW.java`（注册入口）、`round/RoundManager.java`（阶段机与生命周期）、
`cmd/NbwCommands.java`（命令树根）、`loot/LootService.java`（补给箱）、
`raft/Sailing.java`（帆）、`survival/SurvivalService.java`（营养/水分/失温）、
`domain/round/RoundMachine.java`（纯函数阶段机，单测主战场）。

---

## 附：一页速查卡

```
起服    gradlew.bat runServer --offline --console=plain > run\server-console.log 2>&1   （等 "Done ("）
停服    rcon.py "stop"
RCON    127.0.0.1:25576 / nbwm0 / RCON_PORT RCON_PASSWORD PYTHONIOENCODING=utf-8
Python  .\run\py-embed\python.exe -I -X utf8
读数    cww state     命令树 help cww     审计 cww audit 200
清场    player g1 kill … ; kill @e[type=minecraft:item]
开局    player g1 spawn; cww force g1 0; cww start test_3min flat_test
全量    tools\run_all_probes.py        → run\all_probes_summary.txt
静态    tools\check_resources.py       → ERROR=0 WARN=0
单测    gradlew.bat test --offline     → 319/0
客户端  iex (Get-Content .\tools\mc-shots.ps1 -Raw -Encoding utf8); Start-McClient
截图    Save-McShot "<绝对路径>.png"
多轮    tools\play_loop_probe.py --rounds 5   → run\play_shots\
不复位地形就别怪探针   tools\m6_world_repair.py  /  tools\worldbuild.py arena
```
