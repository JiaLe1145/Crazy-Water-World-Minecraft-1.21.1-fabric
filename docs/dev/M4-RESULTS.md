# M4 结果记录（生存养成）

> 对应里程碑 `docs/dev/03-里程碑.md` §M4，Gate G4。
> 本文件只记「落地了什么 + 怎么验的」，设计口径看 `docs/content/02-数值与节奏.md` §2。

---

## 4.1 营养 / 水分 + 状态 HUD（2026-11-08）

### 落地

- `domain/survival/SurvivalRule`（纯函数 + 单测）：满值 100、营养 1.0/min（战斗 1.6/min）、
  水分 3.5/min、低阈值 20、`decay`/`clamp`/`nutritionLow`/`hydrationLow`。
- `survival/SurvivalService`（运行时）：
  - `begin`：参赛者满状态；`onEndTick` 每秒结算一次衰减（战斗 = 最近 5s 内挨过打）。
  - 低营养 → `weakness` + `slowness`（攻击 −、移速 −15%）；低水分 → 禁冲刺（`setSprinting(false)`）。
  - **不致命**：两条到 0 只挂 debuff、不掉血（Gate G4）。
  - `recoverNutrition` / `recoverHydration` 留给 M4-2 的食物/淡水接。
- `net/StatusPayload`（S2C）：营养/水分推给客户端；`NbwNetworking` 注册 `playS2C`。
- `client/StatusHudClient`：收到包后在屏幕左下画「营养 / 水分」两条（低于 20% 变红）。
- 命令：`/cww survival status <玩家>`、`/cww survival set <玩家> <营养> <水分>`。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 开局满状态 | `营养=100.0 水分=99.9` |
| 65s 衰减 | `营养=98.7 水分=96.1`（≈1.0/min、3.6/min） |
| 低值 debuff | `set 10 10` 后 `active_effects` 出现 `weakness` + `slowness` |
| 包注册 | 启动日志 `[net] 已注册 … nbw:status（S2C 生存状态）` |
| 单测 | 247/0（新增 `SurvivalRuleTest` 4 条） |

### 还没做的

- **恢复物品**：淡水/熟食/生食还没接（`recover*` 已备好，等 M4-2 物品）。
- **HUD 真机**：探针环境没有客户端，`nbw:status` 走 `canSend` 保护；HUD 渲染需真客户端验收。
- **失温 / 流血 / 骨折**：见 02 §2，归后续（先不做，避免状态叠加过度）。
- **原版饥饿**：02 建议关闭"饥饿掉血致死"，本轮没动原版规则（需要时再加）。
