# M5 · 反作弊与泄露用例清单（Gate G5）

> 对应 `03-里程碑.md` Gate G5 的「反作弊与泄露用例全过」。
> 本文件是**清单**：每条写清攻击面、服务端防护点、验证状态与证据；未防护的明确写“未防护/待裁决”，
> 不把代码审查冒充满分。

状态图例：

- ✅ **已实跑**：有探针或命令实测，读数是绿的。
- 🟡 **代码审查/需真客户端**：防护已写，但 Carpet 假玩家造不出该攻击包，需真人客户端复验。
- ❌ **未防护/待裁决**：当前没有服务端限制，或需要新数值才能决定。

---

## 1. 本轮实测（2026-11-15）

`tools/security_probe.py` **14 PASS / 0 FAIL / 2 INCONCLUSIVE（待裁决，非缺陷）**：

| 断言 | 实测 |
|---|---|
| 近战攻速闸门：同一 tick 第一发允许、第二发拒绝 | `#1 允许：cutlass … 冷却=12tick｜#2 拒绝：攻速超频（距上次有效攻击仅 0 tick）` |
| **输入包洪泛（服务端入口级）** | `本 tick 送入 1000 包，放行=40 丢弃=960`；跨秒后 `10 包全部放行` |
| **旁观者伪造长按包** | `甲板局部帧=44.51格→REJECT｜上限=0.0格` |
| 远程装填冷却 | 第一发 `冷却剩余(执行前)=0tick`；立刻第二发 `=30tick`，第二发不造成伤害 |
| 远程隔墙正/负对照 | 无墙：`20.0 → 16.17`；有墙：`16.83 → 16.83`（不降） |
| 旁观者绳索/钩竿 | `旁观者不能用绳索/钩竿` |
| 输入包限速（纯函数） | `PacketRateLimitTest` 5 条：40/s 放行、第 41 包丢弃、跨秒重置、窗口边界、关闭限流 |
| 钩竿隔墙 / 连点（当前口径） | 隔墙 `已把 … 拉近到 2.0 格外`；同一 tick 连点两次都成功 → **待裁决** |
| 单测 | `./gradlew test` **304 / 0** |

> 远程用例用 dev 夹具 `/cww weapon ranged <玩家>` 走真实 `RangedWeaponItem.use`
> （冷却 + 命中 + 方块遮挡）。Carpet 的 `use once` 不会触发自定义 `Item.use`，不能拿它当证据。
>
> **包洪泛的证据边界**：`/cww net flood` 直接调用 `nbw:hold` / `nbw:move` 接收器共用的
> `NbwNetworking.admit`，证明限速接在入口上、跨秒会重置；它**不经过 Fabric 解码与网络传输**，
> 所以不能替代“自写客户端真实发 1000 包/s”的 wire-level 用例。真客户端伪造包仍列在 §4。
>
> **旁观者长按的证据边界**：`/cww net probe` 证明“包到了会判 REJECT”，
> 不证明“淘汰玩家客户端真的发不出包”；后者仍需真客户端复验。

---

## 2. 用例矩阵

| # | 用例 | 攻击面 | 服务端防护点 | 状态 | 证据 |
|---|---|---|---|---|---|
| 1 | 近战攻速超频 | 伪造攻击包 / 宏连点 | `WeaponService` 攻速闸门（按 UUID + tick） | ✅ | `security_probe` / `pvp_probe` |
| 2 | 远程装填超频 | 宏连点右键 | `RangedWeaponItem.lastUse`（按玩家 + 武器实例） | ✅ | `security_probe`（29 tick） |
| 3 | 远程隔墙打人 | 伪造瞄准 / 隔墙射击 | `findTarget` 方块 raycast（`COLLIDER`） | ✅ | `security_probe` 正/负对照 |
| 4 | 旁观者绳索/钩竿 | 淘汰后伪造右键/命令 | `RopeService.cast` 的 `isSpectator` 门 | ✅ | `security_probe` |
| 5 | 越界长按 / 旁观者长按 | 伪造 `nbw:hold` 坐标 | `HoldPolicy` 甲板局部帧距离 + `REACH_BLOCKS`；旁观者上限归零 | ✅ | `M2-RESULTS §3.15` / `cww net probe`；本轮旁观者 `上限=0.0格→REJECT` |
| 6 | 建造越权/越界/超限 | 伪造放置 | `BuildRules` 归属 + 建造区 + 方块/设施上限 | ✅ | `build_probe` 60/60 |
| 7 | 近战持有上限 | 拿第二件近战 | `CombatRule.holdViolation` + `weapon give` | ✅ | `pvp_probe` |
| 8 | 消耗品刷回血 | 宏连点淡水/绷带 | 物品 `use` 显式 `decrement(1)`（非创造） | ✅ | M4-RESULTS / 代码审查 |
| 9 | 生产设施刷产出 | 连点过滤器/厨台/医疗箱 | 按方块位置记冷却 | ✅ | M4-RESULTS 各节 |
| 10 | 队伍/阶段越权 | 伪造交互包 | `RoundManager.phase()` + 服务端 session 分队 | ✅ | `build_probe` / `RoundMachine` |
| 11 | 旁观者近战/破拆 | 淘汰后伪造攻击包 | `WeaponService.onAttack/onCoreAttack` 的 `isSpectator` 门 | 🟡 | 代码审查；真客户端复验 |
| 12 | 旁观者远程 | 淘汰后伪造 use 包 | `RangedWeaponItem.use` 的 `isSpectator` 门 | 🟡 | 代码审查；真客户端复验 |
| 13 | 旁观者遥控 | 淘汰后伪造 `nbw:move` | `RemoteService.onMove` 的 `isSpectator` 门 | 🟡 | 代码审查；真客户端复验 |
| 14 | 旁观者建造/交互 | 淘汰后伪造交互包 | `BuildService` 三个事件的 `isSpectator` 门 | 🟡 | 代码审查；真客户端复验 |
| 15 | 观战者吃圈伤/状态/被救援 | — | `ZoneService`/`SurvivalService`/`RescueService` 跳过旁观者 | 🟡 | 代码审查；真客户端复验 |
| 16 | 包重放/伪造 `nbw:hold`/`nbw:move` | 自写客户端 | 服务端重算坐标/阶段/主手/队伍；无重放 nonce | 🟡 | 状态型包，重放窗口有限；需真客户端用例 |
| 17 | 包洪泛（hold/move） | 自写客户端高频发包 | `NbwNetworking` 每玩家 **40 包/秒**（hold/move 共用）；超限丢弃 + 每秒最多一行 warn | 🟡 | `PacketRateLimitTest` 5 条；`/cww net flood` 1000→40（**服务端入口级**）；wire-level 未验 |
| 18 | 绳索/钩竿连点 | 宏连点 | **无冷却** | ❌ | 本轮实测量到同一 tick 连点两次都成功；设计未给冷却，待裁决 |
| 19 | 钩竿隔墙拉人 | 伪造右键 | 只查射程/队伍，不查方块遮挡 | 🟡 | 本轮实测隔墙仍能拉人；待玩法裁决是否加 raycast |
| 20 | 物品复制 | 容器/死亡/背包 | 未发现可利用路径：箱子用原版 loot table，消耗品显式扣减，局后清背包 | 🟡 | 代码审查；无自动化用例 |

> “泄露”在这里按“可利用漏洞/信息泄露”理解：悬赏目标、圈层半径、观战视角是**设计上公开**的信息，
> 不算泄露；未公开的会话/队伍状态只存在于服务端。

---

## 3. 本轮修复（不是文档，是代码）

1. **旁观者战斗/控制/建造全链路**：
   - `WeaponService.onAttack` / `onCoreAttack`：旁观者不能挥击/破拆。
   - `RangedWeaponItem.use`：旁观者不能开枪。
   - `RopeService.cast`：旁观者不能用绳索/钩竿；绳索目标也跳过旁观者。
   - `RemoteService.onMove`：旁观者不能遥控。
   - `BuildService` 的放置/起手破坏/实际破坏：旁观者一律拒绝。
   - `SurvivalService` / `RescueService` / `ZoneService`：旁观者不再吃状态、圈伤或被救援。
2. **远程 hitscan 遮挡**：`RangedWeaponItem.findTarget` 在选中目标前做方块 raycast，
   射线先撞到方块（且比目标中心更近）就跳过。实测有墙时 `目标=无目标`、血量不降。
3. **dev 夹具**：`/cww weapon ranged <玩家>` 走真实 `RangedWeaponItem.use`，给探针验冷却与遮挡；
   回包带 `冷却剩余(执行前)=Ntick`，不靠“等 1.5 秒再猜”。
4. **输入包限速**：`NbwNetworking` 对 `nbw:hold` / `nbw:move` 做每玩家 **40 包/秒**上限
   （正常客户端约 10/s）；超限直接丢弃、不回包，日志每秒最多一行。
   纯函数 `domain/net/PacketRateLimit` 有 5 条单测。这个数是安全上限，不是玩法参数。
5. **限速探针入口**：新增 dev 命令 `/cww net flood <玩家> <次数>`，调用 hold/move 接收器共用的
   `NbwNetworking.admit`，让探针能断言“同一 tick 1000 包只过 40、跨秒重置”。
   它**不经过 Fabric 解码/网络传输**，所以证据里明确标成“服务端入口级”，不冒充 wire-level。

---

## 4. 还没做的（Gate G5 仍未签字）

1. **真客户端复验**：旁观者近战/远程/遥控/建造四条目前是代码审查。需要真人客户端或自写包发送器，
   构造“已淘汰玩家发攻击/使用/移动/放置包”，确认服务端拒绝。
2. **包限流 wire-level 复验**：限速已实现（40/s，纯单测 5 条 + `/cww net flood` 服务端入口级
   1000→40）。仍没有“自写客户端真实发 1000 包/s、确认服务端只处理 40 包”的 wire-level 用例；
   Carpet 发不出自定义包。
3. **绳索/钩竿冷却**：目前是瞬发、无冷却，宏连点可以反复拉人。设计只给了射程，没给冷却；
   是否加“1 秒/20 tick 最小间隔”需要用户裁决，见 [`M5-OPEN-DECISIONS.md`](M5-OPEN-DECISIONS.md) D-2。
4. **钩竿视线**：当前不查方块遮挡；如果“隔墙钩人”被判为不合理，需要把远程武器的 raycast
   同样用到 `LineRule` 上，并补探针。裁决入口见 [`M5-OPEN-DECISIONS.md`](M5-OPEN-DECISIONS.md) D-3。
5. **物品复制全面审计**：本轮只做了代码审查，没有穷举容器/漏斗/发射器/死亡掉落组合；
   Gate G5 的“泄露用例全过”还不能签字。

---

## 5. 复跑

```bash
./gradlew test
RCON_PORT=25576 RCON_PASSWORD=nbwm0 ./run/py-embed/python.exe -I -X utf8 tools/security_probe.py
```

> 依赖 dev 服在跑；脚本自己清假玩家/收尾。远程用例会在 `flat_test` 的 y=150 铺一层石板，
> 因为该图水面在 y=149，直接 tp 到 149 会泡水漂走（实测 1s 偏 1 格，瞄准点会失效）。
