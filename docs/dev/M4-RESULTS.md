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

---

## 4.2 恢复物品：淡水 + 吃食物回营养（2026-11-08）

### 落地

- **淡水 `nbw:fresh_water`**（`content/FreshWaterItem`）：右键喝一口，水分 +40（02 §2）；
  显式扣 1 个（Carpet 假玩家的 use 不走原版消耗流程，靠 `consume` 返回值会漏扣）。
  模型/贴图/语言键齐；进补给箱 t1/t2/t3 三档物资表。
- **吃原版食物 → 营养回复**：`SurvivalService.register` 挂 `UseItemCallback`，
  食物 `FoodComponent.nutrition() × 8` 折算（熟鳕鱼 5→+40，生鳕鱼 2→+16，接近 02 的 35/15）。
- `SurvivalService.recoverNutrition/recoverHydration` 是唯一入口，物品只管调。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 喝水回水分 | `水分=10.0(低)` → 喝一口 → `水分=49.9(正常)`（+40） |
| 消耗物品 | 手上 3 个 → 用后 `count: 2` |
| 物品可获取 | 补给箱 loot table 三档都含 `nbw:fresh_water` |

### 还没做的

- **过滤器/净水器**：淡水应从"海水过滤"产出（生产链），现在先从补给箱出。
- **营养食物分化**：熟食/生食的 35/15 还没按物品细分，现在统一按饥饿点 ×8。

---

## 4.3 受伤：流血 / 骨折（2026-11-08）

### 落地

- `SurvivalService` 新增两份到期 tick：`bleedingUntil` / `fractureUntil`，由 `onWeaponHit` 写入：
  - **弯刀命中 → 流血**：10s 内每秒 2♥（4 HP），**掉到 1 血自停**（Gate G4：不致死）。
  - **铁头锤命中 → 骨折**：45s 内每 5 tick 强制 `setSprinting(false)`（无法冲刺）。
- `WeaponService.onAttack` 的有效分支调用 `SurvivalService.onWeaponHit(victim, weaponId)`（弯刀/铁头锤 id 映射）。
- `/cww survival status` 增加 `血 / 流血 / 骨折` 三个读数。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 弯刀命中 → 流血 | `血=8.5 流血=是` |
| 流血**不致死**（掉到 1 血自停） | 3s 后 `血=1.3 流血=是`（未到 0，未死） |
| 铁头锤命中 → 骨折 | `骨折=是` |

### 还没做的

- **绷带/夹板**：02 §2 的"绷带止血 / 夹板 −30s"还没做（需要物品 + 交互）。
- **骨折禁划桨/攀爬**：目前只禁冲刺；划桨/攀爬（遥控器/扒边）要单独接。
- **流血视觉**：没有粒子/音效提示，只有 actionbar 文字。

---

## 4.4 生产链第一件：海水过滤器（2026-11-08）

### 落地

- `content/SeawaterFilterBlock`（`nbw:seawater_filter`）：放在筏上右键运转一次，产出 1 份 `nbw:fresh_water`；
  冷却按方块位置记 30s（`lastUse`），局后 `reset` 清表。
- 方块/物品注册 + blockstate/model（复用原版 `iron_block`/`blue_ice` 贴图，不新增 PNG）+ 语言键；
  进 t2 补给箱（设施图纸）。
- 接进 `RoundManager.auditResidue` 的清理。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 右键过滤器产出淡水 | 玩家背包出现 `{count: 1, id: "nbw:fresh_water"}` |
| 30s 冷却 | 连续右键第二次不再产出 |

### 还没做的

- **功率池/耗电**：01 §10 的"发电机供 N 功率、设施各耗固定值"本轮不做（先落最小闭环）。
- **其余 4 件设施**：堆肥农场 / 厨台 / 医疗箱 / 修理台。
- **贴图**：现在复用原版方块贴图，等美术资源到位再换。

---

## 4.5 生产链第二件：医疗箱（2026-11-08）

### 落地

- `content/MedicalKitBlock`（`nbw:medical_kit`）：右键止血 + 接骨 + 治疗 5 HP，60s 冷却（按方块位置）。
- `SurvivalService` 新增 `cureBleeding` / `cureFracture` / `heal` 三个接口。
- 方块/物品注册 + blockstate/model（复用 `white_wool`/`redstone_block`）+ 语言键；进 t2 补给箱。
- `auditResidue` 里 `MedicalKitBlock.reset()`。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 弯刀后被医疗箱止血 | `血=7.8 流血=是` → 用药箱 → `血=10.7 流血=否` |
| 治疗生效 | 7.8 → 10.7（+5 治疗，扣除期间流血） |

### 还没做的

- **其余 3 件设施**：堆肥农场 / 厨台 / 修理台 / 发电机（见 §4.4 的同一清单）。

---

## 4.6 生产链第三、四件：堆肥农场 + 厨台（2026-11-08）

### 落地

- `content/ItemProductBlock`（通用产出设施）：右键给固定物品，按方块位置冷却。
  - `nbw:compost_farm` → 面包 1 个（45s）
  - `nbw:kitchen` → 熟鳕鱼 1 个（60s）
- 方块/物品注册 + blockstate/model（复用 `composter_*` / `smoker_*` 贴图）+ 语言键；进 t2 补给箱。
- `auditResidue` 里两个设施 `resetCooldown()`。

### 验证（起服实测）

| 断言 | 实测 |
|---|---|
| 堆肥农场产出面包 | 背包出现 `{count:1, id:"minecraft:bread"}` |
| 厨台产出熟鳕鱼 | 背包出现 `{count:1, id:"minecraft:cooked_cod"}` |

### 还没做的

- **修理台 / 发电机**：筏心修理已由长按通道覆盖，修理台重复；发电机要等功率池（本轮不做电路）。
- **堆肥农场的"营养原料"语义**：现在直接产面包（可食），等营养分化再改。
- **贴图**：仍复用原版方块贴图。
