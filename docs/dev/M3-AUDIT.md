# M3 审计报告（2026-11-08）

> 范围：`docs/content/02-数值与节奏.md` §1–§11、`01-玩法设计.md` §8/§10、`03-地图与配置规范.md`，
> 对照 `ZoneService` / `ZoneModel` / `LootService` / `MetricsService` / `EventService` 与全部 format/arena 配置。
> 结论：**6 处明确错误已修**，另有 5 类"大块缺失"（不是 bug，是没做的系统）。

---

## 一、已修（6 处明确错误）

| # | 问题 | 文档口径 | 原实现 | 修法 |
|---|---|---|---|---|
| 1 | **standard_12min 圈层时刻错位** | §2：P1 2:30 / P2 6:30 / P3 9:30 / P4 11:30 = `150/390/570/690` | 配置写 `150/210/390/570`（P2–P4 各提前 180s）；`ZoneModelTest` 也照错数写，测试"假绿" | 改配置 + 测试对齐 150/390/570/690 |
| 2 | **圈外伤害单位不一致** | §2 表单位是 ♥（0.5♥/1.0♥/2.0♥/4.0♥ = 1/2/4/8 HP） | 配置 `damage` 是 ♥，但 `ZoneService` 直接当 HP 扣 → 实际只有一半 | `ZoneService` 换算 `damage × 2`（♥→HP） |
| 3 | **每轮刷新点数硬编码 2** | §4：quick 3 / standard 4 / big 5 | `LootService.RESPAWN_BATCH = 2` | 改为读 `format.loot.respawnBatch`（缺省 4），standard=4 / test=3 |
| 4 | **过滤器产出 1 份** | §7：30s → **2 淡水** | 30s → 1 淡水 | 产出 2 |
| 5 | **堆肥农场产出 1 份** | §7：45s → **2 营养原料** | 45s → 1 面包 | 产出 2 |
| 6 | **厨台周期 60s** | §7：**20s** → 1 熟食 | 60s | 改 20s |

**实测复核**：圈外伤害翻倍（13→1，符合 0.5♥=1HP）；过滤器产 `count:2` 淡水、堆肥产 `count:2` 面包；
`/cww loot status` 带 `上限/存活` 读数。

---

## 二、已补（M3 缺失项）

| # | 缺失 | 文档口径 | 修法 |
|---|---|---|---|
| 7 | **同屏最大漂浮物** | §4：quick 16 / standard 24 / big 32 | `LootService` 加 `MAX_FLOATING`（读 `format.loot.maxFloating`，缺省 24）；`spawn` 满即停 |
| 8 | **漂浮物存活 120s** | §4：standard 120s | `LootService` 记每个箱子的撒出 tick，超时把 barrel 还原成水（沉没） |
| 9 | **每轮刷新点数可配** | §4 | `LootJson.respawnBatch` 字段 + format 配置 |

**实测复核**：test_3min（maxFloating=16）撒 40 个 → 只落 16；等 125s 后箱子沉没（barrel → 水）。

---

## 三、仍缺（大块系统，非 bug）

| # | 缺失 | 文档口径 | 现状 |
|---|---|---|---|
| A | **稀有度权重**（Common/Rare/Epic/Legendary） | §4 三档权重表（如 tier1：78/20/2/0） | 只有 3 张固定物品表（t1/t2/t3），没有"稀有度"概念 |
| B | **Legendary 只在 P3 后 + tier3** | §4 | 没有 P3 时间门槛 |
| C | **防透视**（只向视距内玩家下发实体） | §4 / 05 R6 | 箱子是方块，对所有人生效 |
| D | **功率池 / 耗电** | §7 各设施耗电、不足效率减半 | 未做（已知暂缓） |
| E | **事件的实际效果** | §10（风暴禁航、海怪 600♥、黑海倒流外扩…） | 只广播；`supply_rain` 只撒 4 箱（无 Rare 限定/无 30s 持续） |
| F | **水中受伤 −50%/−25%** | §8 | Fabric 这版没有改伤害数值的钩子（M2 已记） |
| G | **营养 <20 攀爬失败** | §8 | `ClingRule` 不读营养 |
| H | **结构化埋点落盘** `data/cww/state/matches/<id>.json` | §11.2 | 只有日志行，没有 JSON 落盘 |
| I | **幸存者招募指标 / 胜负方差** | §11.3 | 幸存者只是队级计数，无招募 |
| J | **医疗箱产出"绷带物品"** | §7：60s → 1 绷带 | 直接治疗+止血（不是物品）——**偏差，非 bug** |

---

## 四、文档自身矛盾（需你裁决）

1. **`开局预铺 48` vs `同屏最大漂浮物 24`**（§4）：standard 档两者冲突——预铺 48 已经超过"同屏 24"。
   现在的实现以"同屏上限"为准（预铺被截到 24）。要"预铺真的 48"，需要让预铺绕过上限（性能风险），
   或把文档改成"预铺 ≤ 上限"。
2. **`缩圈段数 5`**（§1）vs 配置 4 段：文档表 P0–P4 共 5 行，但 P0 是"不缩圈"的初始态，
   实际缩圈 **4 次**。配置的 4 段是对的，建议文档改成"缩圈 4 段（P1–P4）"。

---

## 五、下一步建议（按价值排序）

1. **稀有度权重 + Legendary 门槛**（A/B）：这是"内圈值得抢"的核心，也是 09 武器稀有度的落点。
2. **结构化埋点落盘**（H）：Gate G3 的配平回路全靠它，现在只有日志。
3. **功率池**（D）：生产链的"电力规划"维度。
4. **事件实际效果**（E）：风暴/海怪/倒流。

---

## 六、本轮已补齐（2026-11-08 第二轮）

| 原缺项 | 落地 |
|---|---|
| A 稀有度权重 | 新增 `domain/loot/LootRoll`（纯函数 + `LootRollTest` 5 条）：按 02 §4 的 78/20/2/0、60/32/8/0、40/35/20/5 抽 Common/Rare/Epic/Legendary；补给箱改为**按稀有度填 1–3 件**（不再用 loot table JSON） |
| B Legendary 门槛 | `LootRoll.rollRarity(tier, legendaryUnlocked, …)`；`LootService.legendaryUnlocked()` 取圈表第 3 段的触发秒数（P3 之后才开） |
| G 营养<20 攀爬失败 | `SurvivalService.isNutritionLow` + `RescueService.stepCling`：LANDED 前查营养，低则**失败 1 次**（掉回水里，第二次照常） |
| H 结构化埋点 | `MetricsService.appendJsonl`：SETTLE 时追加一行到 `<world>/cww/metrics.jsonl`（match/局时长/首战/末段交战/生产参与率/人数） |
| J 医疗箱产绷带 | 新增 `content/BandageItem`（右键止血 + 骨折 −30s + 治疗 3HP，消耗 1 个）；`MedicalKitBlock` 改为 60s 产 1 绷带；`SurvivalService.reduceFracture` |
| D 功率池 | 新增 `round/PowerService`（队级电量 0–20，`multiplierFor` 满效/减半）+ `content/GeneratorBlock`（右键 +5，30s 冷却）；过滤器/厨台/堆肥改走倍率（没电只产一半）；`/cww power` 读数 |

**实测**：补给箱 `Items` 出现 `string×3 + oak_planks×3`（Common 池）；医疗箱产出 `nbw:bandage`；
`run/world/cww/metrics.jsonl` 出现 `{"match":263,"durationSec":2.7,…}`；
发电机充能 0→5、过滤器满电产 2 份且电量 5→4；单测 252/0。

**仍未补**（依赖更大系统 / 平台限制）：C 防透视（箱子是方块）、E 事件实际效果（风暴/海怪/倒流）、
F 水中受伤（Fabric 无改伤害钩子）、I 幸存者招募指标（幸存者还是队级计数）。
