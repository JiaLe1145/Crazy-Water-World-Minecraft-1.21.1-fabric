# M6 剩余测试计划（待执行）

- **制定日期**：2026-11-21
- **基线提交**：`ae001fa`（`main` = `origin/main`）
- **本文用途**：`M6-FULL-TEST-PLAN.md` 是 130 项的**总清单**；本文只列**还没做完的部分**，
  每项都写清「怎么跑 / 看什么算过 / 为什么现在还没跑」。执行完一项就在第 8 节打勾。
- **当前总体状态**：A/B/C/D/E/F/G/H/I/J/K/L/M/N/O/Q/S 域的**自动化部分已全绿**；
  剩下的主要是「必须真人上手」和「必须长时间/换形态」的项。

## 本轮新增（2026-11-21 下午）

| 节 | 内容 | 备注 |
|---|---|---|
| §1.4 | **怎么自动启动客户端并自动进服**（`Start-McClient`） | 一键，含 `pauseOnLostFocus` 与 quickPlay |
| §1.5 | **怎么自动截图并看图**（`Save-McShot`） | 含 DPI 那个坑的说明 |
| §3.8 | **真人客户端多轮对局观察** | ★最高优先；**硬性要求 ≥15 轮 / ≥2 时段（含凌晨）/ ≥2 张图** |
| §3.9 | **功能性方块全量 12 项** | 每块都有「人类动作 + 读回证据 + 算过」 |
| §3.10 | **幸存者系统 9 项** | 6 种职业逐个验 |
| §3.11 | **死亡→复活→观战 回归** | 凌晨实际遇到的"死亡不复活"，单列并给应急恢复 |
| §3.12 | **我自己补的 7 条** | 满员并发 / 事件风暴视觉 / 跨局残留 / 低光可读 / 大厅交互 / 结算文案 / 重连压力 |

配套新增工具：`tools/play_loop_probe.py`（多轮对局 + 自动截图 + `summary.json`）、
`tools/mc-shots.ps1` 新增 `Start-McClient` / `Stop-McClient` / `Wait-McJoined` / `Save-McShot`。


---

## 0. 上一次执行的结论（作为本轮起点）

> ### ✅ 2026-11-22 21:1x：航行"速度过快 / 不平稳 / 木筏翻倒入水"——推力口径改了
>
> 用户实机："木筏的移动速度过快，导致移动不平稳，出现木筏翻倒入水"，
> 并判断"可能是 Sable 给的力过大 / 力不平行于水面"，要求下调推力，同时问"Sable 怎么驱动木筏、怎么工作"。
>
> **先量再改（dev 客户端 + `cww remote/sail/wind` 读数，`cww raft all` 新增"斜="度数）：**
>
> | 场景 | 修复前 | 修复后 |
> |---|---|---|
> | 遥控器 W（无帆）稳态 | 0.149 格/tick ≈ **3.0 格/秒** | 0.092 ≈ **1.83 格/秒** |
> | 7 帆 + 风暴（风 4.0） | 0.33 ≈ **6.6 格/秒**，倾斜峰值 **12.9°** | 0.16 ≈ **3.2 格/秒**，倾斜 ≤ **3.0°** |
> | 转向（A/D，满速） | 倾斜峰值 **12.9°**、稳态 8~9° | ≤ **5.6°** |
>
> **根因不是"力不平行于水面"**：`RaftMotionService` 给的推力一直是纯水平的
> （`SableRafts.push(..., 0.0, ...)`，Y 分量恒为 0）。真正的问题有三个：
> 1. **限幅是绝对数 0.8**，而所有档位目标只有 0.11~0.22 —— 限幅永远先撞上，
>    实际航速由"限幅 ÷ 水阻"决定、与档位无关（遥控/划桨/帆全被推成 6.6 格/秒）；
> 2. 每 tick 一记 `dv=0.8`（一拍加 16 格/秒）的猛踢，水阻在船底、推力在质心，
>    力矩把船头压下去 —— 这就是"不平稳/要翻"的来源；
> 3. 转向角冲量 1.5/tick 在满速下把横滚顶到 12.9°。
>
> **改法（`SpeedGovernor` + `RemoteService` + `SableRafts.stabilize`）：**
> - 限幅改成**相对目标**：`dv ≤ 1.8 × 目标`（`DV_CAP_RATIO`），绝对上限 0.45 只做兜底 ——
>   低速档按低速推、高速档按高速推，稳态仍≈0.9×目标（阻尼率 k≈2）；
> - 档位整体下调：`MAX_TRAVEL_SPEED` 0.35→0.22、`REMOTE_SPEED` 0.1665→0.11、
>   `ROW_SPEED_PER_ROWER` 0.0333→0.022、`SAIL_SPEED_PER_SAIL_PER_WIND` 0.025→0.0165（保持桨:帆=2:3）；
> - `GAIN` 18→10（瞬态更软）；`TURN_IMPULSE` 1.5→0.9（≈18°/s）；
> - 新增**姿态稳定**：每 tick 对每艘筏夹俯仰/横滚角速度（>0.8 rad/s 就阻尼）、
>   倾斜超过 8° 按超出量回正（单 tick 上限 0.5），偏航完全不碰。实测正常航行时
>   `稳姿=0`（不需要干预），它是防"撞墙/浪顶翻"的兜底。
>
> 顺带修：**落位格从帆族换到桨族**。实测事故：站在落位格上 `/cww sail rig 0 7`，
> 第 7 面帆正好放进玩家身体里 → 人被顶下海、沉到 y=53 淹死。现在落位格用
> `oarCell(side, i)` 里第一格不在中线上的格子（桨族没有自动放置路径），单测钉住"落位格不是任何帆位"。
>
> **本轮新发现（待查）：**
> - **7 面帆会让 4×4 筏打转**：帆力沿船头方向，但 7 帆在 z 方向不对称 → 偏航力矩，
>   实测筏子会自己转 90°，顺风分量掉到 0 就不动了。是否要按"帆面中心配平"重排帆位待定。
> - **帆的模型把甲板整个罩住**：7 帆时玩家第一人称看到的是黑色帆布内壁（截图 `run/_v20_sailing.png`），
>   之前记的"浅灰/深色面板"就是它。观感需要重做（帆布太厚/太大）。
> - 圈层收缩提示 `黑海将在 X 秒后收缩` 每秒一条、连刷 6 条，聊天栏被刷满。


> ### ✅ 2026-11-22 21:0x：开局"先掉水里再被弹回筏上"——根因找到并修掉
>
> 用户实机描述："开局时玩家直接掉入水中，上方木筏刷新后才又传送回木筏上方"，
> 并给了正确的修法方向：**重置地图 → 先生成木筏 → 木筏出来了再传人**。
>
> **根因有两层，缺一不可：**
>
> 1. **落点 Y 用错了坐标系。** 出生锚点 `{x,y,z}` 的 y 是"筏底基准"——筏体生成在 `y+1`，
>    甲板占 `y+1..y+2`。把玩家放到锚点的 y 上，等于让他的脑袋正好埋进甲板方块里，
>    原版沿最小穿透轴把他往**下**顶 → 穿到筏底、掉进水里。日志实证（20:30:37）：
>    ```
>    [sable] 生成筏 'nbw_raft_0_111' … 落点=(1000.5 154.0 952.5)
>    [round] moved Player264 team=0 -> anchor=1000,153,952 nowAt=(1000.5,153.0,952.5)
>    ```
>    玩家脚 153、脑袋 154.8，而甲板是 154..155。
>    而且 2026-11-21 那次"把出生点抬 3 格"是**无效修法**：锚点抬高时筏子跟着抬高，
>    相对关系一点没变，只是让筏子凭空出现在水面之上三四格、落水要好几秒。
> 2. **发筏与传人在同一 tick。** Sable 把 sub-level 推进物理管线、再把方块同步给客户端都要 tick，
>    发筏当 tick 就传人，客户端那一刻脚下什么都没有。
>
> **修法（按用户要求的顺序做成了硬闸门）：**
> - `READY` 进入时只做"重置地图 + 发筏"，**不传人**（人留在大厅）；
> - 每 tick 判 `tickEntryGate()`：模板复制结束 **且** 每队筏都 `present` **且** 竖直速度 `< 0.05`
>    **且** 过了 16 tick 观察期，才 `teleportParticipants()`；超时 200 tick 兜底放人并写 ERROR；
> - 落点改成 **plot 局部坐标正变换**：甲板可站面局部 y=0.30（实测值）+0.10 余量，
>   取中心右边一格（局部 x=1.5）避开筏心 `(0,1,0)` 与物资箱 `(0,1,-1)`；
> - `PLAY` 进场只保留兜底 `pullFallenParticipants()`（人比水线还低才补传，且每人每局只 WARN 一次）；
> - 六张图的 `spawns.teams[].y` 全部改回 `sea.waterLevel`（= 筏底基准，筏子正好浮在水面），
>   并补了单测 `BuiltinArenaJsonTest.teamSpawnYIsTheWaterLineNotThePlayerFeet` 钉死这条不变量。
>
> **实机读数（dev 客户端 + 服务端日志，三局一致）：**
> ```
> [round] 入图闸门放行（等了 16 tick）：2 人落到各自木筏的甲板上
> [entry] Player404 队=0 脚底Y=150.32 落点Y=150.35 筏位姿Y=150.05 差(脚底-位姿)=0.28
> [entry] Player404 队=0 脚底Y=63.32  落点Y=63.35  筏位姿Y=63.05  差(脚底-位姿)=0.28   ← default_sea
> ```
> 落点与实际脚底只差 **0.03 格**（原来是从 4 格高处落下 / 直接被顶进水里）；
> 客户端截图确认：READY 与 PLAY 两个时刻人都站在木甲板上，镜头里是木筏 + 海面，
> 没有落水帧、没有"木筏在脚底下慢慢浮起来"。
> 差(脚底−位姿) 稳定在 0.28~0.30，是**甲板可站面**的标定值（见 `RaftLayout.DECK_SURFACE_Y`）。
>
> **顺带修掉的同源 bug：** 非对局阶段（LOBBY/SETTLE/RETURN）有人死着时没人管，
> 客户端会永久停在"你死了"（实测：SETTLE 里淹死 → 回不了大厅、下一局也进不去）。
> 新增 `respawnDeadOutsidePlay()`（每秒一次，非 PLAY 阶段强制复活），实测 `/kill` 后
> `非对局阶段发现死亡实体（phase=LOBBY）→ [C21] respawn → force respawn → placed in lobby`，
> 血=20.0、人回到大厅。
>
> **本轮仍未解决（新记录，下一轮处理）：**
> - 每次大距离传送后服务端会刷 2~10 行 `moved too quickly! <delta>`：客户端把**位移增量**
>   当绝对坐标发回来，服务端拒收后重新同步。最终位置正确（F3 读数与服务端一致），
>   但属于既有的 Sable/Veil 传送后遗症，**不是本次改动引入**（RETURN 回大厅那条老路径同样有）。
> - 筏面上方 2~3 格有**深色矩形面片**（俯仰抬头可见，每局都有）；同一位置幸存者的名字牌
>   也浮在甲板上方约 0.7 格（`SurvivorService` 用的是局部 y=1.0，比实测可站面高 0.7）。
>   疑为同一套"局部 Y 偏高"问题，下轮一起查。

> ### ✅ 2026-11-21 18:50 起：真人终于能站在筏上了（三个 bug 已修，`41fc943`）
>
> 之前"筏没有碰撞"的判断是**错的**：盔甲架停在 `y=150.32`、掉落物停在 `y=151.25`，说明碰撞一直在。
> 真正的问题是三件事叠在一起：
> 1. **`ServerPlayerEntity.teleport()` 不搬真人客户端** —— 日志里同一行
>    `moved Player700 … nowAt=(2.5,201.0,1.5)`（真人纹丝不动）vs `moved aa … nowAt=(1000.5,149.0,1048.5)`。
>    修：`teleportToAnchor` / `placeInLobby` 之后再补一次 `requestTeleport`（绳索传送一直这么干，所以它是通的）。
> 2. **6 张图的出生点全在甲板下方** —— 实测 `raftY = generateY + 1`、甲板顶 ≈ `generateY+2.35`，
>    而出生点写的是 `generateY`。修：全部改成 `generateY + 3`。
> 3. **READY 阶段传送时筏还没实体化**（Sable 建筏是延后的）→ 玩家穿筏落水。
>    修：PLAY 入口补一次 `teleportParticipants()`。
>
> 修后实测：真人 `position` 从 `(2.5,201,1.5)` 变成 `(1000.5, 151.38, 952.5)` 并稳定不动；
> 截图确认站在木甲板上，HUD 营养/水分、黑海倒计时、海面补给箱、失温提示都正常。
>
> ### 新发现（待修/待定）
>
> | # | 现象 | 证据 | 性质 |
> |---|---|---|---|
> | 1 | **幸存者刷在玩家身上**，脸直接怼在镜头前 | 截图 `run/_v12_facilities2.png`；日志 `[survivor] … 落位 local=(0.8,1.0,0.0)` —— 离筏心只有 0.8 格，而玩家也站在中心 | 真问题（视觉/体验）：落位要挪到甲板边缘 |
> | 2 | **Carpet 假玩家站不上筏**，会沉到池底淹死（`ff 淹死了`） | 真人稳定在 151.38，同一局假玩家从 145 掉到 140 | 假玩家与玩家的移动/碰撞路径不同；夹具限制，**不是模组 bug**，但"每局 60 秒就因假玩家淹死而结束"，凡是需要第二队的测试必须用第二个真客户端 |
> | 3 | 设施（投石机/弩炮）模型上出现**浅灰白大面板** | 同一张截图 | 待确认：可能是帆布 `sail_canvas_0` 侧面，也可能是占位贴图；下次单独摆一个设施靠近看 |

> ### ⚠ 当前最高优先级阻塞：玩家站不上筏（会掉进水里淹死）
>
> 2026-11-21 18:xx 实测（服务端日志）：
> - `[sable] 生成筏 … 落点=(1000.5 151.0 952.5)` —— **raftY = generateY + 1**；
> - 甲板在 plot 局部 y=0，筏心在局部 (0,1,0) ⇒ 甲板方块占 `raftY..raftY+1`，**甲板顶面 = raftY+1**；
> - 而 `flat_test.json` 的出生点 y=149、`generateY=149` ⇒ raftY=150、甲板顶=151，
>   **出生点比甲板低 2 格**，玩家一出生就在水里，沉到池底 `y=140` → `血=0.0` → 双方 [淘汰]。
>
> 已经把 `generateY` 与出生点 y 改成 150 试过一轮，**没有解决问题**（raftY 变 151，甲板顶变 152，
> 出生点仍低于甲板顶）。同一轮里 `tp` 到 `y=152/154` 的假玩家仍然掉到 `y≈143`，
> 但**那两次是在 SETTLE 之后测的**——那一局筏已被回收，不能据此断定"没有碰撞"。
>
> **下一次必须先做这个实验（务必在 `phase=PLAY` 时做）**：
> 1. 开局进 PLAY，`cww raft all` 记下 raftY；
> 2. `tp <bot> <中心X+1.5> <raftY+2> <中心Z+0.5>`，等 3 秒读 `Pos`：
>    - 停在 `raftY+1` 附近 ⇒ 碰撞正常，**修法是把出生点 y 改成 `generateY+2`**（并把 `default_sea`
>      的 62/63 一并修正），同时把出生点 XZ 从"筏心那格"偏开 1 格（筏心占局部 (0,1,0)）；
>    - 仍然掉到水里 ⇒ 是 Sable sub-level 对玩家的碰撞没生效，属于集成问题，要查 Sable 侧
>      （不能靠改配置解决）。
> 3. 结论写回本节，再继续 §3 的真人/客户端项。
>
> ### 实验已做（2026-11-21 18:40，`phase=PLAY`，raftY=150.03）
>
> ```
> cww raft all  -> 队0 Y=150.03  队1 Y=150.03
> tp zx 1002.0 152.0 953.0      （甲板顶应在 151，玩家给在 152）
> 4 秒后 Pos     -> (1002.61, 144.40, 953.48)   ← 穿过甲板落到池底
> ```
>
> **结论：Sable sub-level 对玩家/实体没有生效碰撞**（不是出生点差一两格的问题）。
> 表现是任何人只要被放到筏上就会穿下去、沉到 `y=140` 然后淹死。
> 这不是配置能修的，需要查 Sable 侧（sub-level 的 collision/`canCollideWith`、或玩家与
> sub-level 的实体追踪）。**在它解决之前，所有"站在筏上"的测试项（HUD/帆/器械/幸存者/PvP）
> 都测不出真结果**，任何"通过"都是假的。
>
> ### 同时发现的第二个问题：真人玩家没有进图
>
> 同一局 `phase=PLAY` 时，真人客户端 `Player700` 的坐标是 `(11.5, 200.0, -0.5)` —— **还在大厅**，
> 但 `cww state` 里它在 `T0 赤队` 队席上。也就是说"队席有它、但没被送进图"。
> 假玩家能被送到锚点（然后穿筏落水），真人却留在大厅，两者走的是同一条 `placeByPhase`，
> 差别只在"真人客户端在线/会话可能带着上一局的淘汰标记"。这条要单独查。

>
> 在上述问题解决前，**所有"玩家站在筏上"的项都不成立**：HUD、帆、器械、幸存者交互、
> 双人 PvP 都会被"一出生就淹死"污染。

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

### 1.4 怎么自动启动客户端（并自动进服）★

客户端**必须**从仓库根用 `gradlew runClient` 起——直接跑 jar 会缺 dev 环境里的
Sable/Veil 依赖。`tools/mc-shots.ps1` 里已经把这套封装成 `Start-McClient`，它做了四件事：

1. 把 `run-client\options.txt` 里的 `pauseOnLostFocus` 改成 `false`
   （**必须**：截图时焦点会离开 MC，否则它会自己弹暂停菜单，画面就不是你要看的那一屏）；
2. 用 `--quickPlayMultiplayer 127.0.0.1:25565` 启动，**跳过主菜单直接进服**；
3. 等 MC 窗口出现（按标题匹配 `Minecraft`）；
4. 轮询服务端 `list`，确认玩家真的进来了才算"可以开始测"。

```powershell
cd C:\Users\殷子竣\Desktop\NB-Water-World\NB-Water-World
# .ps1 不能被直接执行（策略拦截），必须先 iex 进来
iex (Get-Content ".\tools\mc-shots.ps1" -Raw -Encoding utf8)

Start-McClient                 # 启动 + 等进服（最多 180s）
Start-McClient -Fresh          # 先杀掉已有客户端再启动（换资源/换代码后必须用它）
Start-McClient -TimeoutSec 300 # 机器慢就加时长
Stop-McClient                  # 只杀客户端（按窗口标题匹配，不会误杀服务端）
Wait-McJoined "Player627" 90   # 只想等某个具体玩家进服
```

> **为什么不能按内存大小杀 java 进程**：服务端和客户端都是 `java.exe`，
> 按内存筛会连服务端一起杀掉。`Stop-McClient` 只杀**窗口所属的那个 PID**
> （`GetWindowThreadProcessId`），不按进程名/内存筛。

> **另一个更要紧的坑**：这台机器上可能同时开着**别人的** Minecraft（实测见到过
> `Minecraft* Forge 1.20.1 - 多人游戏（第三方服务器）`）。所以
> **绝对不能**用 `MainWindowTitle -like "*Minecraft*"` 去匹配窗口或杀进程 ——
> 那会截到别人的游戏、甚至把别人的游戏关掉。本套工具统一用
> `Minecraft* 1.21.1`（我们 dev 客户端的标题）来收紧匹配。


### 1.5 怎么自动截图并查看画面 ★

```powershell
iex (Get-Content ".\tools\mc-shots.ps1" -Raw -Encoding utf8)
Save-McShot "C:\...\NB-Water-World\NB-Water-World\run\_shot_x.png"   # 摆窗口+截图+还原 DSH 一步到位
```

它内部做的事（也是这套东西全部踩过的坑）：

| 步骤 | 为什么必须这么做 |
|---|---|
| `SetProcessDPIAware()` | 不调的话 `GetWindowRect` 返回**逻辑像素**、`CopyFromScreen` 返回**物理像素**，截出来是错的（实测窗口 1920×1080 却只截到 1280×720 的一块） |
| 最小化 DSH 窗口 | 否则 DSH 盖在 MC 上面，截到的是聊天界面 |
| `GetWindowRect` + `CopyFromScreen` | 比 `PrintWindow` 可靠（GL 窗口用 `PrintWindow` 常得到黑图） |
| 截完 `Restore-Dsh` | 不然人看不到 DSH 了 |
| 等 1.2s 再截 | MC 要几帧才把新画面画完 |

**看图**：截图落在 `run\*.png`，用 `read_image`（或任何看图工具）打开即可。
**不要**指望 `F2`（MC 自带截图）：那需要键盘输入，而本机的键鼠注入进不了 MC（见 §7.1）。

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
iex (Get-Content .\tools\mc-shots.ps1 -Raw -Encoding utf8)
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

### 3.8 【新增】真人客户端多轮对局观察（**必须多轮**）★最高优先

- **目的**：服务端读数全绿**不等于**玩家看到的是对的。这一项只看**客户端画面**：
  换局后画面对不对、HUD 对不对、帆/器械/幸存者在不在、死亡与观战是否正常。
- **工具**：`tools/play_loop_probe.py`（会自动摆局、做一串"真人会做的事"、每步截图、
  最后把每轮结论写进 `run/play_shots/summary.json`）。
- **跑法**：

```powershell
# 前置：服务端已起、客户端已进服（§1.4）
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"
.\run\py-embed\python.exe -I -X utf8 tools\play_loop_probe.py --rounds 5
```

- **每轮自动做的事**（每步一张图 `run\play_shots\rNN_0X_*.png`）：
  1. `01_play_start` 刚进 PLAY：站位、筏、HUD 基线
  2. `02_equipped` 发一整套装备后：热键栏图标、手持模型、营养/水分条
  3. `03_ballista` 筏上放一门弩炮 + 补 16 发弹：2×2 外形、描边
  4. `04_wet_hud` 泡水 11 秒：失温提示与状态读数
  5. `05_after_death` 被打死后：死亡/观战画面，`cww state` 里的 `[淘汰]`
  6. `06_spectator` 淘汰后 8 秒：**是否卡在死亡界面**（见 §3.11）
- **怎么判**：一轮里的 6 张图逐张对照 `01`（基线）看差异，重点看**不该变的东西变了没**
  （比如第二轮的 `01` 和第一轮应该长得一样，除非地形/时间不同）。
- **★ 轮次要求（这条是硬性的）**：

| 维度 | 最低要求 | 说明 |
|---|---|---|
| 单次会话轮数 | **≥ 5 轮** | 少于 5 轮不做结论；`--rounds 5` 是底线 |
| 累计轮数 | **≥ 15 轮** | 分 3 次会话跑，不要一次跑完 |
| 时段 | **≥ 2 个时段**（含**凌晨**一次） | 凌晨那次就是发现"死亡不复活"的场景，必须复现 |
| 地图 | **≥ 2 张**（`flat_test` + `test_sea` 或 `default_sea`） | 图不同，锚点/水位/区块加载都不同 |
| 赛制 | **≥ 2 种**（`test_3min` + `standard_12min`） | 12 分钟局才会遇到事件/风暴/黑海收圈 |
| 换代码/换资源后 | **重跑 ≥ 3 轮** | 免得"修一个坏一个" |

```powershell
# 第 2 批：换图换赛制（记得先建海域：tools\sea_probe.py 或 worldbuild.py arena）
.\run\py-embed\python.exe -I -X utf8 tools\play_loop_probe.py --rounds 5 --arena test_sea --format standard_12min
```

### 3.9 【新增】功能性方块全量：每个都要能真用

- **为什么只能人工**：这些方块的效果都挂在 `onUse`（右键）上（`ItemProductBlock` /
  `SeawaterFilterBlock` / `GeneratorBlock` / `RepairTableBlock` / `MedicalKitBlock`），
  而 Carpet 假玩家的 `use` 触发不到方块 `onUse`，RCON 也发不出右键。
- **统一摆场**（进 PLAY 后，把自己队的筏当工作台）：

```powershell
# 一次性把 12 个方块摆在自己面前（y、z 按当前筏面自行微调）
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch `
  "gamemode creative <你的ID>" `
  "give <你的ID> nbw:seawater_filter 1" "give <你的ID> nbw:generator 1" `
  "give <你的ID> nbw:compost_farm 1"   "give <你的ID> nbw:kitchen 1" `
  "give <你的ID> nbw:medical_kit 1"    "give <你的ID> nbw:repair_table 1" `
  "give <你的ID> nbw:ballista 1"       "give <你的ID> nbw:catapult 1" `
  "give <你的ID> nbw:ram 1"            "give <你的ID> nbw:sail 7" `
  "give <你的ID> nbw:oar 1"            "cww power status" "cww supply"
```

- **逐块验收表**（右键一次 → 用右侧命令读回证据）：

| 方块 | 人类动作 | 读回证据 | 算过 |
|---|---|---|---|
| 海水过滤器 `seawater_filter` | 右键 | `data get entity <ID> Inventory` 里 `nbw:fresh_water` +2 | 一次产 **2 份淡水**，30s 内再点是"冷却中"提示 |
| 电鳗发电机 `generator` | 右键 | `cww power status` 的队内电量 | 一次 **+5**，30s 冷却 |
| 堆肥农场 `compost_farm` | 右键 | 背包多出作物类物品 | 产出 1 份，按功率倍率（有电满效/没电减半） |
| 厨台 `kitchen` | 右键 | 背包多出熟食 | 产出 1 份熟食 |
| 医疗箱 `medical_kit` | 先把自己打到半血，再右键 | `cww survival status <ID>` 的 `血=` | 回血；60s 冷却；有医护幸存者时冷却 −30% |
| 修理台 `repair_table` | 右键 | `cww core status` | 修筏心 / 重置沉筏倒计时；耗电 1；60s 冷却 |
| 弩炮 `ballista` | 手持 `nbw:ballista_bolt` 右键装填，等它自动开火 | `cww ballista status`（弹药）、`cww power status` | 装填 +1/次、全队共享弹药、每 2.0s 一发、耗电 1 |
| 投石机 `catapult` | 贴近敌方筏自动开火 | `cww catapult status` | 只打敌方**筏心**，落点 4 格内敌方玩家吃 40% |
| 撞角 `ram` | 让筏**动起来**撞敌方筏 | `cww ram status` | 筏速 > 0.05 才算撞；5♥/次；1s 一次；耗电 2 |
| 帆 `sail` | 手放到甲板（见 §3.1） | `cww sail read` | 帆数 +1；风向受力生效 |
| 桨台 `oar` | 放下来看模型 | — | **只剩装饰**（推进已删），能放、能拆、不报错即可 |
| 筏心 `raft_core` | 右键（长按修复） | `cww core status` + `cww raftcore status` | 长按修复有进度；打到 0 血 → 残骸 + 全队出局 |

- **另外要验的横切面**：
  1. 同上 12 个方块**破掉能不能掉回物品**（§3.3）。
  2. 每个方块在**没电**时是否按文档降效（`cww power status` 里把电扣到 0 再点一次）。
  3. 冷却提示文案是可读中文，不是 `translation.key` 原文。

### 3.10 【新增】幸存者系统

- **命令面**：`cww survivor`（总览）/ `cww survivor job <队> <职业>` / `cww guard` /
  `cww supply`。职业共 6 种：**水手 SAILOR / 厨师 COOK / 医护 MEDIC / 工匠 CRAFTSMAN /
  舵手 HELMSMAN / 卫兵 GUARD**。
- **摆场**：

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch `
  "cww survivor" "cww guard" "cww supply" `
  "cww survivor job 0 SAILOR" "cww survivor job 0 COOK" `
  "cww survivor job 0 MEDIC"  "cww survivor job 0 CRAFTSMAN" `
  "cww survivor job 0 HELMSMAN" "cww survivor job 0 GUARD" `
  "cww survivor"
```

- **验收表**：

| 项 | 怎么做 | 算过 |
|---|---|---|
| 生成 | 进 PLAY 后 `cww survivor` | 每队 1 名，`口粮` 有读数，<br>`职业[...]` 显示正确中文名 |
| 外观 | 走到幸存者旁边看 | 村民外观、有名字牌、不穿模、不卡在筏体里 |
| 职业生效 | 切 `CRAFTSMAN` 后用任意生产方块右键 | 与不切职业相比，`口粮` 消耗/产出速度有差异（`SurvivorService.productionSpeedFor`） |
| 医护 | 切 `MEDIC` 后点医疗箱 | 冷却比 60s 短（−30%） |
| 卫兵 | 切 `GUARD`，让敌方靠近 | `cww guard` 读数变化；有攻击/拦截行为 |
| 舵手/水手 | 切 `HELMSMAN`/`SAILOR` 后开船 | `cww sail read` 速率或操控手感有变化（按文档口径） |
| 口粮消耗 | 连开 3 分钟 | `cww survivor` 的 `口粮` 单调下降；到 0 后应有后果（按文档：幸存者离开/失效） |
| 可被击杀 | `damage` 或让敌方打死幸存者 | 能被击杀（用户已裁决"幸存者可杀"），死亡后 `幸存者=` 归零，不刷屏报错 |
| 跨局不残留 | 打完一局再开一局 | 新一局幸存者重新生成、`口粮` 回满，上一局的尸体/状态不带过来 |

### 3.11 【新增】死亡 → 复活 → 观战 回归（**凌晨实际遇到，重点盯**）

- **现象**（2026-11-21 凌晨）：客户端死亡后**没有正常复活**，画面停在死亡状态。
  这会让后续所有"多轮"测试中断（客户端卡死在死亡屏，后面的摆局都在对空气截图）。
- **已知代码路径**（`RoundManager`）：
  - 死亡瞬间：先切 `SPECTATOR`（"淘汰后观战"）
  - `AFTER_RESPAWN` → `onRespawn()`：日志 `[C21] respawn: old=… phase=…`，
    补记淘汰（日志 `[round] respawn 兜底淘汰：…`），并加入 `pendingPlacement`
  - 下一 tick `placeByPhase()`：`[round] placed <名字> at team N anchor (phase=…, eliminated=true)`
- **最可能的成因**（待实机确认，**不要照着猜就改**）：
  MC 的死亡屏是**客户端 UI**，只有客户端发出 `Client Status: PERFORM_RESPAWN` 才会关掉。
  服务端把人切成旁观并传送到锚点，**并不会**替客户端关掉那一屏——
  于是"服务端认为他已经观战了，客户端还停在 You Died"。
  若确认是这条，修法是服务端主动复活（`PlayerManager.respawnPlayer`），
  但会与"淘汰后留图观战"的口径冲突，**需要先定口径再改**。
- **怎么复现**（每轮都做，`play_loop_probe` 已内置）：

```powershell
# PLAY 中让客户端玩家被打死
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch `
  "damage <你的ID> 200 minecraft:player_attack by <假玩家>" `
  "cww state"
# 8 秒后看三处证据
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "cww state" "list"
Select-String -Path run\client-console.log -Pattern "C21|respawn|placed .* eliminated" | Select-Object -Last 8
```

- **算过**：
  1. `cww state` 里该玩家标 `[淘汰]`，且**仍在 `list` 里**（没被踢）。
  2. 客户端日志出现 `[C21] respawn` 与 `[round] placed … eliminated=true`。
  3. **截图上看到的是观战视角（能自由飞、无 HUD 血条），不是 You Died 屏**。
  4. 连续 5 轮都成立（单轮通过不算）。
- **卡死时的应急恢复**（测试可以继续做下去，不算通过）：
  ```powershell
  iex (Get-Content .\tools\mc-shots.ps1 -Raw -Encoding utf8)
  Stop-McClient; Start-McClient -Fresh     # 重连最快；席位 90s 内还能保住
  # 或：cww abort → 回到 LOBBY 再开下一局
  ```

### 3.12 【新增】其它补充测试计划（我自己加的）

#### 3.12.1 满员并发（4×3 = 12 人）

```powershell
# 用 11 个假玩家 + 1 个真人客户端把 4×3 塞满
for ($i=0; $i -lt 11; $i++) { .\run\py-embed\python.exe -I -X utf8 tools\rcon.py "player b$i spawn" }
# 逐个 cww force 到 4 队，然后 cww start standard_12min default_sea
```
- **算过**：12 人都进 PLAY；客户端帧率不明显掉（记 F3 的 fps）、内存不持续涨；
  服务端 `soak_monitor --minutes 5` 无 `Can't keep up`；4 队筏心/帆/器械登记都对得上。

#### 3.12.2 事件与风暴的视觉验证

```powershell
.\run\py-embed\python.exe -I -X utf8 tools\rcon.py --batch "cww event" "cww event fire" "cww zone"
```
- **算过**：`cww event fire` 后客户端有可见事件（风雨/黑海提示/广播）；
  风暴期间远程武器失效、弩炮伤害 −50% 的**提示文案**能看到；`cww zone` 的 P1/P2/P3 时刻与倒计时一致。

#### 3.12.3 跨局残留（同一客户端连开 5 局）

- **算过**：每局开始时 背包清空、HUD 重置、`cww sail read` 帆=0、
  `cww ballista status` 无残留登记、`cww survivor` 口粮回满、`[S10] residue=CLEAN` 出现在日志里。

#### 3.12.4 可读性：低光 / 全状态 HUD

- 把时间调到夜里（`time set midnight`，世界规则会拉回正午，所以临时用 `gamerule doDaylightCycle` 前先 `cww` 侧确认）或进黑海圈内，
  看：营养/水分条是否可读、失温/流血/骨折三个状态提示是否同时显示不重叠、准星与描边是否看得清。

#### 3.12.5 大厅交互

- 报名 NPC、4 面队旗、观战锚点：站在大厅里逐个右键/靠近，确认 `cww join` 生效、
  队旗颜色正确、`cww state` 的 `anchors=` 数量对得上、观战锚点能飞到。

#### 3.12.6 结算与战绩文案

- 打完一局看结算广播：胜者名/原因/时长/存活人数四项齐全；
  `state/matches/<n>.json` 落盘；`career.json` 累加；文案里没有占位符或 `{}`。

#### 3.12.7 客户端重连压力（连续 3 次强杀）

- 连续 3 次 `Stop-McClient` + `Start-McClient -Fresh`，中间不做别的事。
- **算过**：每次都能重连回原队席位（90s 内），不出现"重复登录/幽灵玩家/`list` 里两个人"。

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
7. **机器上可能同时开着别人的 Minecraft**（实测见到 `Minecraft* Forge 1.20.1 - 多人游戏`）：
   凡是"按窗口标题匹配/按进程名杀"的动作都必须收紧到 `Minecraft* 1.21.1`，
   否则会截到别人的游戏、甚至把别人的游戏关掉。`tools/mc-shots.ps1` 已按这条改好
   （`Stop-McClient` 只杀窗口所属 PID）。
8. **最小化窗口的矩形是假的**：`GetWindowRect` 会返回 `-21333,-21333` 这种哨兵值，
   拿去 `CopyFromScreen` 会截出 158×26 的垃圾图。`Capture-Mc` 现在先用 `Test-McRect`
   校验并尝试还原窗口，不满足就**明确报错跳过**，不会静默存一张废图。
9. **`pwsh`（PS7）不在 PATH**：只有 `powershell.exe`（5.1）。脚本里要调 PowerShell 时
   必须两个都试（`play_loop_probe.py` 的 `ps_exe()` 就是干这个的）。

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
| **19** | **真人客户端多轮对局 第 1 批 5 轮（§3.8）** | **P0** | 半自动 | ☐ | | 截图在 `run/play_shots/` |
| **20** | **真人客户端多轮对局 第 2 批 5 轮（换图换赛制）** | **P0** | 半自动 | ☐ | | |
| **21** | **真人客户端多轮对局 第 3 批 5 轮（含凌晨时段）** | **P0** | 半自动 | ☐ | | 累计 ≥15 轮才做结论 |
| 22 | 功能性方块 12 项（§3.9） | P0 | 真人 | ☐ | | 逐块记录读数 |
| 23 | 幸存者 9 项（§3.10） | P0 | 真人 | ☐ | | |
| 24 | 死亡→复活→观战 回归 5 轮（§3.11） | **P0** | 半自动 | ☐ | | 凌晨问题，必须单独确认 |
| 25 | 满员并发 4×3（§3.12.1） | P1 | 半自动 | ☐ | | |
| 26 | 事件/风暴视觉（§3.12.2） | P1 | 真人 | ☐ | | |
| 27 | 跨局残留 连开 5 局（§3.12.3） | P1 | 半自动 | ☐ | | |
| 28 | 可读性：低光/全状态 HUD（§3.12.4） | P2 | 真人 | ☐ | | |
| 29 | 大厅交互（§3.12.5） | P1 | 真人 | ☐ | | |
| 30 | 结算与战绩文案（§3.12.6） | P1 | 真人 | ☐ | | |
| 31 | 客户端重连压力 连续 3 次（§3.12.7） | P1 | 半自动 | ☐ | | |

---

## 附：本次用到的工具速查

| 工具 | 用途 |
|---|---|
| `tools/run_all_probes.py [--only a,b]` | 批量跑探针（每项独立子进程 + 超时 + 汇总） |
| `tools/play_loop_probe.py --rounds N` | **真人客户端多轮对局**：摆局 + 脚本化操作 + 每步截图 + `summary.json` |
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
| `tools/mc-shots.ps1` · `Start-McClient` / `Stop-McClient` / `Wait-McJoined` | **自动起客户端 + 自动进服**（§1.4） |
| `tools/mc-shots.ps1` · `Save-McShot` / `Show-Mc` / `Capture-Mc` / `Restore-Dsh` | **自动截图**（已处理 DPI，§1.5） |
| `tools/mc-window.ps1` | 窗口查找/置前/按矩形截图（底层实现） |
