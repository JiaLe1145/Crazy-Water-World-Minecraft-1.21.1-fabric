# M6 全量测试 · Q 域「资源完整性」报告（Q1–Q8）

- **域**：Q 资源完整性（`docs/dev/M6-FULL-TEST-PLAN.md` §Q，8 项）
- **执行日期**：2026-11-21
- **执行方式**：**纯静态扫描**（不启动/停止服务端、不跑 gradle、不碰 RCON；未修改 `src/main/resources/` 下任何文件）
- **扫描器**：`tools/check_resources.py`（Python 3.11，仅标准库；只读 `src/main/resources/` 与 `src/main/java/`）
- **结论**：**Q1/Q2/Q3/Q7/Q8 + JSON 全量解析 PASS；Q4/Q5/Q6 各 1 项需人工裁决（无紫黑块类硬缺陷）**。
  脚本本次退出码 **0**（无 ERROR）；如要 WARN 也算失败，用 `--strict` 跑会返回 1。

> 关键背景：2026-11-21 集成第一批美术资源后曾出过两个致命渲染 bug（`b23a1c6`：模型旋转角非法 / `textures` 键名多 `#`），
> 本域把这两类问题连同元素越界、贴图缺失/损坏、blockstate 悬空引用、语言键缺失、JSON 坏档等一起做成了可复跑的静态检查。

---

## ★ 2026-11-21 修复记录（本报告结论已变更）

本报告首次跑出 Q4/Q5/Q6 三个「需人工裁决」项后，用户裁定「自行补充缺失处即可」，
于是当场按下面的口径修掉，并**加强了扫描器**（原来的 Q1 只校验 PNG 头 33 字节）：

| 项 | 原结论 | 现在的状态 | 改动 |
|---|---|---|---|
| Q4 获取链 | 7 件「设计有来源、实际拿不到」 | **已修** | `domain/loot/LootRoll` 新增**设施池**（tier1 基础件 / tier2+ 进阶件），`LootService.placeCrate` 按 `FACILITY_CHANCE=0.22` 每件独立掷骰；`pike` 进 RARE 稀有度池。新增 3 条单测锁住「10 件缺口全部可达 + 阶梯不可越档」 |
| Q4 口径 | `oar` / `raft_core` 无来源，未声明 | **已声明** | 扫描器加 `SYSTEM_ONLY_ITEMS`（含理由），命中时记 INFO 而不是 WARN；理由同时写进 §6.1 B 档 |
| Q5 死表 | 4 张 loot table 无代码引用（文档漂移） | **已删** | 删掉 `data/nbw/loot_table/*.json` 4 个文件（它们正是"往死表里加东西"那次事故的载体），同步改 `M3-RESULTS.md`；扫描器把「目录不存在」从 WARN 降为 INFO |
| Q6 标签 | 11 个 `requiresTool()` 方块缺 `#minecraft:mineable/*`（生存破坏无掉落） | **已修** | 新增 `data/minecraft/tags/block/mineable/axe.json`（帆/桨/堆肥/厨台/修理台/医疗箱/筏心）与 `pickaxe.json`（弩炮/投石机/撞角/发电机/过滤器） |
| Q1 贴图 | 60 张全部 PASS | **修了一个漏网的真 bug** | `item/water_pistol.png` 是**截断 PNG**（82 字节，像素数据 944 < 1040），客户端日志 `Using missing texture, unable to load nbw:item/water_pistol`、游戏里显示紫黑格。已用 `tools/gen_water_pistol_icon.py` 重画，并把 `png_ok()` 强化为「chunk CRC + IDAT 解压 + 像素长度」三段校验 |
| 帆（B 档升 A 档） | `nbw:sail` 只能 `/cww sail rig`（权限 2）挂；玩家拿不到帆、也挂不上 | **已修（关键玩法缺口）** | 帆是这套玩法**唯一**的推进手段（桨台只剩装饰），而原来真实对局里整局都动不了。两处一起修：① `nbw:sail` 进设施池（tier2+）；② `SailBlock.onPlaced` 手放即登记进 `Sailing`（`registerPlaced`），`onStateReplaced` 摘记录（`unregisterPlaced`）；顺带修掉 `deRig` 边遍历边改集合的 CME 隐患 |

修完重跑：`ERROR=0  WARN=0  INFO=30  OOS=76`，`RESULT: PASS —— 无 ERROR/WARN`。

---

## 0. 结论速览

| 项 | 内容（测试计划原文） | 结论 | 说明 |
|---|---|---|---|
| Q1 | 所有模型引用的贴图存在 | **PASS** | 42 个模型 / 2208 处纹理变量引用 / 60 张贴图全部存在且可解析；0 缺失、0 损坏、0 非法旋转、0 元素越界 |
| Q2 | 所有 blockstate 指向的模型存在 | **PASS** | 12 个 blockstate / 21 个模型引用全部命中；12/12 方块做了「variant ↔ Java 属性」交叉校验且全覆盖 |
| Q3 | `zh_cn` / `en_us` 键完整且一一对应 | **PASS** | 两文件各 29 键、键集合完全一致；28 个注册内容的翻译键齐全；占位符一致。1 个非注册键为 INFO（见 §12 第 4 条） |
| Q4 | 物品均有配方或明确不可合成 | **需人工裁决（1 个确认缺口）** | 28 个物品里仅 1 个有配方、14 个有配方外实际来源；**7 个疑似漏接来源**、6 个仅命令/系统放置。逐项见 §6.1 |
| Q5 | 战利品表可被补给箱引用 | **PASS（表有效）+ 1 项文档不一致** | 4 张表结构/物品 id 全部合法；**但当前没有任何代码引用它们**，补给箱改由 `LootRoll` 直接生成。见 §7 |
| Q6 | 方块/物品标签（`minecraft:mineable/axe` 等） | **PASS（无悬空引用）+ 1 项待裁决** | 项目没有任何标签 JSON；11 个 `requiresTool()` 方块缺 `#minecraft:mineable/*`（生存破坏无掉落），见 §8 |
| Q7 | 物理属性 tag（`physics_block_properties`） | **PASS** | 1 个定义文件：`selector=nbw:sail`（已注册方块）+ `sable:mass=0.0`（数字），结构符合项目文档记录 |
| Q8 | 声音事件（若有自定义） | **PASS（无自定义声音）** | 无 `assets/nbw/sounds.json`；代码只复用 3 种原版 `SoundEvents`，无自定义 `SoundEvent` 注册 |
| 附 | 所有 JSON 可解析 + `fabric.mod.json` 合法 | **PASS** | 90/90 个 JSON 解析通过、无重复键；`fabric.mod.json` 的 id/version/入口点/依赖均合法 |

分域脚本结论（本次实际输出）：`Q1 PASS、Q2 PASS、Q3 PASS、Q4 PASS-with-WARN(13 WARN)、Q5 PASS-with-WARN(1)、Q6 PASS-with-WARN(1)、Q7 PASS、Q8 PASS、JSON PASS、REG PASS；ERROR=0 / WARN=15 / INFO=16 / OOS=97`。

---

## 1. 扫描范围与资源清单

范围：`src/main/resources/`（`run/`、`build/`、`dist/` 里的生成物按纪律不是测试对象，未扫）；
Java 侧只用于提取注册表与属性定义（`src/main/java/dev/nbw/`）。

| 类别 | 数量 | 位置 |
|---|---|---|
| blockstate | 12 | `assets/nbw/blockstates/` |
| 模型 | 42（block 14 + item 28） | `assets/nbw/models/` |
| 贴图 PNG | 60（block 44 + item 16），**全部 16×16 / 78–746 B** | `assets/nbw/textures/` |
| 语言文件 | 2（zh_cn / en_us 各 29 键） | `assets/nbw/lang/` |
| 战利品表 | 4 | `data/nbw/loot_table/` |
| 配方 | 16（自有 1 + 原版武器移除 15） | `data/nbw/recipe/`、`data/minecraft/recipe/` |
| 物理属性定义 | 1 | `data/nbw/physics_block_properties/sail.json` |
| 标签 | **0** | `data/**/tags/**` 不存在 |
| 自定义声音 | **0** | `assets/nbw/sounds.json` 不存在 |
| JSON 总数 | 90（含 `fabric.mod.json`） | 全部可解析 |
| 已注册方块 / 物品 | 12 / 28（12 BlockItem + 11 普通物品 + 5 近战武器） | 从 `NbwContent.java`、`NbwWeapons.java` + `data/nbw/weapons.json` 提取 |

---

## 2. 脚本怎么跑

工作目录 `C:\Users\殷子竣\Desktop\NB-Water-World\NB-Water-World`，解释器用仓库自带的嵌入式 Python（**无需装任何依赖**）：

```powershell
# 默认：打印 ERROR/WARN（0 = 无 ERROR，1 = 有 ERROR）
.\run\py-embed\python.exe -I -X utf8 tools\check_resources.py

# 详细：把 INFO/OOS 明细也打出来（本轮报告用的就是这个输出）
.\run\py-embed\python.exe -I -X utf8 tools\check_resources.py --verbose

# 严格：有 WARN 也算失败（CI/门禁建议用这个）
.\run\py-embed\python.exe -I -X utf8 tools\check_resources.py --strict

# 可选：--root <项目根> 扫别的副本（变异测试就是拿临时副本跑的）
```

- 退出码：`0` = 无 ERROR；`1` = 有 ERROR（`--strict` 下 WARN 也返回 1）。
- 输出格式：每条都带 `[级别] (域) 文件路径 @ JSON path/Java 行号: 说明`；末尾给出分域结论、汇总与 OOS（out-of-scope）统计。
- 只读脚本：不写任何文件、不启动任何进程、不联网。

---

## 3. Q1 模型引用的贴图存在 —— PASS

**检查规则**（同时覆盖 11-21 的两类紫黑块 bug）：

1. 扫 `assets/nbw/models/**/*.json`（42 个），逐个解析 JSON（坏档会先记 JSON 域 ERROR）。
2. `parent`：`nbw:<path>` 递归校验 `assets/nbw/models/<path>.json` 是否存在、链路是否成环（深度/环检测）；
   `minecraft:` / 其他命名空间记为 **OOS**（本仓库无法验证原版 jar 内模型）。
3. `textures` 每个值：`#var` 在本模型 + `nbw` parent 链（子覆盖父）里找定义，找不到/成环 → ERROR；
   `nbw:<path>` → 必须存在 `assets/nbw/textures/<path>.png`；`minecraft:` → OOS。
4. **特殊键**：`particle`（以及任何键）一视同仁按贴图引用校验。
5. **`textures` 的键名不允许以 `#` 开头**（`b23a1c6` bug 1：MC 不报错，但整面静默变紫黑块）。
6. 元素 `faces.*.texture` 引用必须能解析到贴图；`uv` 必须 4 个数字、`cullface` 必须是 6 方向、`tintindex` 必须是整数。
7. 元素几何：`from`/`to` 各 3 个数字、坐标在 MC 允许的 `[-16, 32]`、`from ≤ to`；`rotation` 的 `axis` 必须 x/y/z、
   **`angle` 只允许 `-45 / -22.5 / 0 / 22.5 / 45`**（`b23a1c6` bug 2：其它角度会 JsonParseException 整个模型加载失败）。
8. PNG 存在性用纯 Python 校验：8 字节 PNG 签名 + 首个 chunk 为 IHDR + 宽高 > 0（不依赖 Pillow）。
9. 每个已注册物品必须有 `models/item/<id>.json`；`parent=minecraft:item/generated|handheld` 的物品模型必须有 `layer0`。

**结果**：

- 0 处缺失 / 0 处损坏；60 张 PNG 全部存在且文件头合法，尺寸均为 **16×16**。
- **151 处 `rotation` 的角度全部落在 MC 允许的 5 档内**（`-45`:7、`-22.5`:7、`22.5`:4、`45`:7、`0`:126）——`b23a1c6` 的修复已生效。
- **0 个模型出现 `textures` 键带 `#`**——`b23a1c6` 第二个 bug 的修复已生效。
- 368 个元素的坐标全部在 `[-16,32]`，无负尺寸。
- 纹理变量引用 2208 处全部可解析（最大模型 `sail.json` 54 KB，由原 11086 行版本经旋转角吸附后压成单行，仍承担大量纹理变量复用）。
- 物品模型 28/28 齐全（含 12 个方块物品复用方块模型）；无「注册了物品但没模型」。
- 无未被引用的贴图（60 张全部被至少一个模型引用）。
- OOS：**39 条非 nbw parent 引用**——12 个方块模型直接 `parent: block/block`、16 个物品模型 `parent: minecraft:item/generated|handheld`、
  11 个方块物品模型经 `nbw:block/*` 间接继承 `block/block`（`block/sail` 无 parent，独立 elements）。
  这些是原版模型，**不是 bug**，只是本仓库无法静态验证其存在性。

---

## 4. Q2 blockstate 指向的模型存在 —— PASS

**检查规则**：

1. 12 个已注册方块都必须有 `blockstates/<id>.json`；孤儿 blockstate 记 INFO。
2. `variants[*].model` 与 `multipart[*].apply.model` 都必须是存在的 `assets/nbw/models/<path>.json`（`minecraft:` 记 OOS）。
3. **variant ↔ Java 属性交叉校验**（脚本启发式，见 §14）：从 `src/main/java/dev/nbw/content/*.java` 提取方块的
   `BooleanProperty` / `DirectionProperty`（含 HORIZONTAL/VERTICAL/ALL 的合法值）/ `EnumProperty`（含枚举 `StringIdentifiable` 的字符串值），
   然后检查：
   - variant 键里出现的属性名必须是该方块真实拥有的属性（否则 MC 报 `Unknown property`，整块模型失效）；
   - 属性取值必须合法；variant 的 `x`/`y` 只能是 0/90/180/270（`BlockModelRotation` 硬约束），`uvlock` 布尔、`weight` 正整数；
   - **全覆盖**：该方块所有「属性组合」都必须命中至少一个 variant 键（否则该状态报 `Unable to load variant` / 缺失模型）。
4. 无属性方块必须给出 `""` 键的 variant。

**结果**：

- 12/12 方块有 blockstate；21 个模型引用全部命中文件；0 处 OOS。
- 交叉校验覆盖 12/12 方块，属性情况：`ballista.part=main|ext`、`catapult.part=main|ext`、
  `oar.facing=north|south|east|west`、`sail.facing=north|south|east|west`、`raft_core.wreckage=false|true`，
  其余 7 个方块无属性（`""` 单 variant）。**所有状态组合都有对应 variant，0 缺失。**
- `sail/oar` 的 4 个朝向 variant 用 `y: 90/180/270`，均为合法档；`ballista/catapult` 的 `part=ext` 指向
  `models/block/multiblock_ext.json`（合法空模型 `{"textures":{},"elements":[]}`；且这两个方块的 `getRenderType()`
  对 `Part.EXT` 返回 `BlockRenderType.INVISIBLE`，不会渲染，无紫黑块风险）。

---

## 5. Q3 语言键 —— PASS

**检查规则**：`assets/nbw/lang/zh_cn.json` 与 `en_us.json` 必须可解析、键集合一一对应；
每个已注册方块 → `block.nbw.<id>`；每个 BlockItem 的翻译键也是 `block.nbw.<id>`；普通物品 → `item.nbw.<id>`；
中英占位符（`%1$s` 等）集合一致；非注册键单独列 INFO 供核对死键。

**结果**：

- 两文件各 **29 键**，键集合完全一致（0 缺失）。
- 28 个注册内容键齐全：12 个 `block.nbw.*` + 16 个 `item.nbw.*`。
- 占位符一致（唯一的死亡消息键中英都带 `%1$s`）。
- 非注册键 1 个：`death.attack.nbw_raft_core`。仓库里**没有** `data/**/damage_type/nbw_raft_core.json`，
  代码里也没有引用该死亡消息（`grep death.attack` 无命中）→ 静态看是**死键**，但它是纯资源、不影响加载，列为 INFO 待人工确认（见 §12 第 4 条）。
- OOS：无（语言文件是本仓库资源，全部可验证）。

---

## 6. Q4 配方 —— 需人工裁决（1 个确认缺口 + 需明确「不可合成」口径）

**检查规则**：

1. 逐个解析 `data/nbw/recipe/*.json`（1 个）与 `data/minecraft/recipe/*.json`（15 个）。
2. `result.id` / 所有材料 id：`nbw:*` 必须在已注册列表里；`minecraft:*` 记 OOS。
3. `data/minecraft/recipe/*` 是平衡护栏（把原版武器合成改成不可达）：必须仍是「barrier → barrier」，
   如果有人把它改活（出现非 barrier 引用）脚本会 WARN。
4. 对 28 个注册物品做「是否可合成 + 运行期获取路径」审计：来源只认**代码里实际执行的路径**
   （`LootRoll` 补给箱池、生产/发放代码、开局物资箱、悬赏奖励、配方产出）；
   **仅写在未被代码引用的 `data/nbw/loot_table/*.json` 里的来源不算可达**（原因见 Q5）。

**结果**：

- 16 个配方全部可解析；1 个自有配方 `nbw:ballista_bolt`（铁锭 + 2 木板 → 4 发），result/材料 id 全部合法。
- 15 个原版武器覆盖配方**全部仍是 barrier 不可达配方**，数量与文档一致（`M2-RESULTS.md:1175` 记 14 个 + 补 mace = 15）。
- 28 个物品中：**1 个有配方**（ballista_bolt）、**14 个有配方外实际来源**、**2 个只剩「死表」来源**（compost_farm/kitchen）、**11 个暂无任何运行期来源**（其中 6 个可判为“仅命令/系统放置”）。

### 6.1 获取链缺口（确认问题，按证据分两档）

**A. 疑似「设计有来源、但换源时漏迁移」的 7 件（建议优先裁决）**

| 物品 | 设计/文档证据 | 现状 |
|---|---|---|
| `nbw:pike`（长矛） | `docs/content/09-武器与美术管线.md` §2.1：首发 5 件近战之一（Rare） | `LootRoll` 池与 4 张 loot table 均无 pike；只有 `/cww weapon give`（管理员）能发 |
| `nbw:rope` / `nbw:grapnel`（绳索/钩竿） | `09` §2.3 列 Common 控制工具；`01-玩法设计.md` §5「工具：斧/钩竿/绳」 | 4 张表与 `LootRoll` 均无；`ToolItem` 功能已实现但局内拿不到 |
| `nbw:generator` / `nbw:repair_table` | `01-玩法设计.md:230`「设施图纸：过滤器/发电机/农场/医疗箱/修理台」 | 两张表与 `LootRoll` 均无 |
| `nbw:compost_farm` / `nbw:kitchen` | `docs/dev/M4-RESULTS.md:148`：「（堆肥/厨台）进 t2 补给箱」 | 只写进了 **`data/nbw/loot_table/sea_crate_t2.json:69/74`**，而这张表**没有被任何代码引用**（见 Q5） |

**证据链（时间线）**：`809b70a`（M3 补齐）把补给箱从「原版 loot table」改成 `LootRoll` 稀有度池并移除了 `setLootTable`；
之后的 M4 提交（`05ae54f`/`ed60121`/`131780f`/`3892079`）只往 `data/nbw/loot_table/sea_crate_t2.json` 里加设施，
**没有同步加进 `LootRoll`**（`git show --stat` 可复验）。因此「进 t2 补给箱」实际上没有生效。
`LootRoll.java:31-62` 的现役池只含：`fresh_water、bandage、water_pistol、cutlass、hand_crossbow、javelin、warhammer、twin_blade、musket、harpoon_gun、seawater_filter、medical_kit`
——对比设计表可见 pike/绳/钩竿/发电机/修理台/堆肥/厨台 7 件缺失。

**B. 仅系统/管理员命令放置的 6 件（需在文档/代码里明确「不可合成」，不算 bug）**

| 物品 | 现状 |
|---|---|
| `nbw:raft_core` | 开局由 `RaftSpawner.generateForTeams` 直接放方块；物品形态仅供 dev |
| `nbw:sail` | 只能 `/cww sail rig`（权限 2，`RaftCommands.java:58-64`）挂帆；无物品获取路径 |
| `nbw:oar` | `NbwContent.java:90` 注释已写明「桨台（问题③起只剩装饰）…局内拿不到」 |
| `nbw:ballista` / `nbw:catapult` / `nbw:ram` | 只能 `/cww facility … place`（权限 2，`FacilityCommands.java:50-75`）放置；M6-FULL-TEST-PLAN §F1 也只要求 8 类生产/结构设施可放置，未含这 3 件设施武器 |

> 结论：Q4 的「物品均有配方或明确不可合成」目前**没有被满足**——A 档 7 件既无配方也无明确“不可合成”声明、且局内不可得；
> B 档 6 件可判为“仅系统/命令放置”，但建议把口径写进文档（或补显式注释），否则下一次测试仍会把它当缺口。
> **这是内容/配置缺口，不是资源文件损坏，不会造成紫黑块或启动失败。**

---

## 7. Q5 战利品表 —— PASS（表有效）+ 1 项文档不一致

**检查规则**：解析全部 `data/nbw/loot_table/*.json`；校验 `type`/`pools`/`entries` 结构、
entry type 合法性、`nbw:*` 物品 id 是否已注册、嵌套 `minecraft:loot_table` 引用是否存在、函数 id 是否原版；
并扫 `src/main/java`（`loot_table` / `LootTable` / 表名）确认“谁在引用这些表”。

**结果**：

- 4 张表全部可解析：`sea_crate`（6 entries）、`sea_crate_t1`（7）、`sea_crate_t2`（13）、`sea_crate_t3`（9）；
  类型均为 `minecraft:chest`；无嵌套表引用；函数均为 `minecraft:set_count`。
- 表内所有 `nbw:*` 物品 id 均已注册（`fresh_water、cutlass、seawater_filter、medical_kit、compost_farm、kitchen、hand_crossbow、javelin、warhammer、twin_blade、musket、harpoon_gun`）；
  `minecraft:*` 共 21 条记 OOS（原版物品，视为合法）。
- **`src/main/java` 中对 4 张表的引用数 = 0。**

**确认问题（文档/实现不一致，需人工裁决）**：

- 当前补给箱的真实内容是 `LootService.placeCrate()` 用 `LootRoll` 直接 `inv.setStack(...)` 塞进去的
  （`src/main/java/dev/nbw/loot/LootService.java:286-314`），**没有使用任何原版 loot table**。
- 但 `docs/dev/M3-RESULTS.md:53` 仍写：「每个箱子 `setLootTable(RegistryKey<LootTable> nbw:sea_crate, seed)`，右键开箱按 loot table 生成物品」
  —— 该行为在 `809b70a` 换源后已不存在，**属文档漂移**。
- 后果：4 张 loot table 是**死资源**（客户端不加载、服务端不引用）；它里面的 `compost_farm/kitchen` 等条目不会生效（联动 Q4 A 档）。
- 另：两套来源的内容已经不一致（表 t2 有 堆肥/厨台/过滤器/医疗箱，没有 绷带/水弹枪；`LootRoll` 正好相反）→ 需明确以哪套为准。

**建议二选一**：① 接回去（`placeCrate` 按 tier 选表 + `setLootTable`），并把 `LootRoll` 的独有内容合并进表；
② 删掉 4 张表并同步修正 `M3-RESULTS.md` / `M4-RESULTS.md` 的表述。**当前状态不影响启动，只影响“掉什么”。**

---

## 8. Q6 标签 —— PASS（无悬空引用）+ 1 项待裁决

**检查规则**：扫 `data/**/tags/**`（含 `#` 嵌套引用）；`nbw:` 条目按目录对应注册表校验（block/item/entity_type/fluid/game_event/damage_type），
`minecraft:` 条目记 OOS；同时检查代码里对标签的依赖（`TagKey` / `BlockTags` / `ItemTags`）。

**结果**：

- **项目没有任何自定义标签文件**（`data/**/tags/**` 不存在）→ 没有“标签引用了未注册 id”这类问题。
- 代码里只用到原版标签 `ItemTags.PLANKS`（`BuildService.java:133/289/301/346`，扩展甲板扣木板用）→ 原版标签，合法。
- `ConfigModels.java:124` 提到 `#sable:light` 只是注释，没有代码/资源依赖 NBW 自定义标签。

**待裁决问题（低严重度，非紫黑块）**：

- 12 个方块里 **11 个用了 `.requiresTool()`**（除 `compost_farm` 外的全部：`ballista、catapult、generator、kitchen、medical_kit、oar、raft_core、ram、repair_table、sail、seawater_filter`），
  但没有 `#minecraft:mineable/axe|pickaxe` 等标签。按原版 harvest 规则：`requiresTool()` 的方块若不在对应 `mineable/*` 标签里，
  **任何工具破坏都不会掉落**（方块消失、物品丢失）。
- 玩家在 PLAY 阶段是 `SURVIVAL`（`RoundManager.java:390`），`BuildService` 也允许在可建造阶段破坏（`BuildService.java:97/164-178`），
  所以「放错设施 → 想拆回 → 物品消失」的动作在理论上是可达的。
- 但：设施方块目前主要靠命令/系统放置，且 Sable sub-level 上的破坏是否仍走原版 harvest 判定**无法静态验证**（见 §13），
  因此本条列为“需人工裁决/实机确认”，不判 bug。
- 修法（若确认需要）：加 `data/nbw/tags/block/mineable/axe.json`（木质设施）与 `.../pickaxe.json`（金属设施）+ `needs_iron_tool` 等。

---

## 9. Q7 物理属性 tag —— PASS

**检查规则**（依据本项目文档记录的 Sable 数据驱动约定，见 §15 限制与纪律说明）：
扫 `data/**/physics_block_properties/*.json`；校验 `selector` 是已注册方块 id 或以 `#` 开头的方块标签（nbw 标签则要求标签文件存在）；
`properties`/`overrides` 的键必须是 `sable:mass|inertia|volume|restitution|friction|fragile|floating_material|floating_scale` 且值类型正确；
`priority` 必须整数；`overrides` 的条件 `prop=value` 必须匹配方块真实属性；未识别字段 WARN。

**结果**：

- 仅 1 个文件：`data/nbw/physics_block_properties/sail.json`
  → `{"selector": "nbw:sail", "properties": {"sable:mass": 0.0}}`。
- `selector` = `nbw:sail` 是已注册方块 ✓；`sable:mass` 是文档化的属性键、值为数字 0.0 ✓；无 `priority`（缺省 1000）✓；无 `overrides`。
- 结构可被文档所述 codec 解析，**PASS**。
- OOS：该 `mass=0.0` 的**实际物理效果**（帆应多轻？）以及 Sable 运行时是否接受 0.0 属运行期行为，静态无法验证；
  需要在实机用帆速/质量读数复核（可由 P 域/sea_probe 覆盖）。

---

## 10. Q8 声音 —— PASS（无自定义声音，复用原版）

**检查规则**：若存在 `assets/nbw/sounds.json` 则校验结构、事件名与 `sounds[]` 的 ogg 文件是否存在（`nbw:` 命名空间）；
同时扫代码里的 `SoundEvent` 注册 / `SoundEvents.*` / `PlaySoundS2CPacket` 引用。

**结果**：

- `assets/nbw/sounds.json` **不存在**；代码里**没有** `Registries.SOUND_EVENT` 注册、没有自定义 `SoundEvent`。
- 代码只复用 3 种原版声音：
  - `SoundEvents.BLOCK_ANVIL_LAND`、`SoundEvents.ENTITY_GENERIC_EXPLODE`（`combat/WeaponService.java:135/138`）
  - `SoundEvents.BLOCK_NOTE_BLOCK_PLING`（`round/EventService.java:206`，事件预告）
- 与 `docs/dev/美术资源需求.md` §四「音效沿用原版」一致 → **PASS**（无自定义声音资源可漏）。
- OOS：原版声音文件在客户端 jar 内，本仓库无法静态验证；但代码引用的都是原版常量（能编译即存在）。

---

## 11. 顺手：全量 JSON 解析 / `fabric.mod.json` —— PASS

- `assets/nbw/**/*.json` + `data/nbw/**/*.json` + `data/minecraft/**/*.json` + `fabric.mod.json` 共 **90 个**，**90/90 解析通过**，
  无重复键（重复键会 WARN：Gson 取最后值、静默丢配置）。
- `fabric.mod.json`：`id=nbw` ✓、`version=${version}`（gradle 注入）✓、`main=dev.nbw.NBW` / `client=dev.nbw.NBWClient`
  两个入口点类都存在 ✓、`depends` 含 `fabricloader/minecraft(1.21.1)/java(21)/fabric-api/sable` ✓。
- 15 个 `data/minecraft/recipe/*.json` 全部是合法 barrier 不可达配方（平衡护栏有效，Q4 已述）。

---

## 12. 确认的问题清单（按严重度）

> 本域**没有**发现会导致紫黑块 / 模型加载失败 / 启动失败的问题。下面是 3 个确认项（严重度中/中/低）+ 1 个信息项。

| # | 严重度 | 问题 | 定位 | 证据 | 建议 |
|---|---|---|---|---|---|
| 1 | **中** | 7 件物品「设计有来源、实际拿不到」：`pike、rope、grapnel、generator、repair_table、compost_farm、kitchen` | `src/main/java/dev/nbw/domain/loot/LootRoll.java:31-62`；`src/main/resources/data/nbw/loot_table/sea_crate_t2.json:59/64/69/74` | 换源提交 `809b70a` 移除 `setLootTable`，M4 提交只改死表未改 `LootRoll`；`LootRoll` 现役池无这 7 件 | 补进 `LootRoll`（或配方/其他来源），否则局内不可得；若故意不给，写明“不可合成” |
| 2 | **中** | 4 张战利品表是死资源；`M3-RESULTS.md:53` 描述的 `setLootTable(nbw:sea_crate)` 已不存在 | `data/nbw/loot_table/*.json`；`docs/dev/M3-RESULTS.md:53`；`src/main/java/dev/nbw/loot/LootService.java:286-314` | `src/main/java` 全仓 `loot_table`/`LootTable` 0 命中；补给箱由 `LootRoll` 生成 | 接回 loot table，或删表并改文档；顺带统一两套掉落内容 |
| 3 | **低** | 11 个 `requiresTool()` 方块缺 `#minecraft:mineable/*`，生存破坏无掉落 | 无标签文件；方块定义见 `NbwContent.java:75-148` | 生存态 `RoundManager.java:390` + 破坏放行 `BuildService.java:97/164-178`；原版 harvest 规则 | 补 `mineable/{axe,pickaxe}.json`；先实机确认 Sable sub-level 破坏路径 |
| 4 | 信息 | `death.attack.nbw_raft_core` 翻译键无对应 `damage_type`、代码也无引用（疑似死键） | `assets/nbw/lang/{zh_cn,en_us}.json:3` | `data/**/damage_type/` 不存在；`grep death.attack` 无命中 | 补 damage_type 或删键；纯资源、无害 |

**已排除的“假问题”**（避免误报，供复核）：

- 39 条 `minecraft:` 模型 parent / 21 条原版战利品物品 / 37 条原版配方材料：原版引用，本仓库无法验证，**OOS 非 bug**。
- `models/block/multiblock_ext.json` 是空模型（`textures:{}`、`elements:[]`）：`ballista/catapult` 的 `part=ext` 占位用，
  `getRenderType()` 对 EXT 返回 `INVISIBLE`，合法。
- `models/block/sail.json` 无 parent（独立 elements）、`sail.json` 很大（54 KB / 2208 处纹理变量引用）：正常。
- `oar` 物品属于“桨台遗留、局内拿不到”的已知设计（`NbwContent.java:90` 注释），不是资源 bug。

---

## 13. 静态无法验证 / 需人工裁决 清单

**需人工裁决**：

1. Q4-A 档 7 件：补获取路径，还是在文档/代码里显式声明“不可合成/仅管理员命令”？（B 档 6 件建议一并声明）
2. Q5：4 张 loot table「接回」还是「删除」；以 `LootRoll` 还是 loot tables 作为掉落唯一来源。
3. Q6：是否补 `mineable/*` 标签（依赖实机确认 Sable sub-level 的破坏掉落行为）。
4. Q3：`death.attack.nbw_raft_core` 保留/补 `damage_type`/删除。

**静态无法验证**（本报告不判 PASS/FAIL，需实机或运行时覆盖）：

- 97 处 `minecraft:` / 外部命名空间引用（原版模型、原版物品、原版配方材料）在 1.21.1 客户端/服务端里确实存在——按原版处理。
- 客户端实际加载效果（模型/贴图/变体是否正确显示 = M6 的 P1–P3 实机项；本域只证明“引用完整、格式合法”）。
- Sable sub-level 上的方块破坏是否受原版 harvest 掉落规则约束（Q6 结论的前提）。
- `sable:mass = 0.0` 的实际物理表现（Q7 前提是文档记录的结构；实际数值效果需 sea_probe 类实机）。
- 若将来把 loot table 接回，补给箱开箱内容/权重行为需重新实机验证。

---

## 14. 脚本启发式规则与可信度说明（低误报调优记录）

1. **注册表提取不是写死清单**：从 `NbwContent.java` 的 `public static final String` 常量 +
   `Registry.register(Registries.BLOCK/ITEM, Identifier.of(NBW.MOD_ID, CONST), …)` 模式提取；
   近战武器按 `NbwWeapons.register()` 的真实逻辑从 `data/nbw/weapons.json` 只取 `kind=melee`（且校验 spec 错误——
   因为一条 spec 错误会让整表不注册，脚本会显式报这个“联动缺失”）。
2. **模型 parent 链只在本仓库内递归**：`nbw` parent 缺失/成环 → ERROR；`minecraft:` parent → OOS 并停止向上（避免把原版 jar 当本地文件误报）。
3. **纹理变量解析**：子模型覆盖父模型、`#var -> #var` 递归带环检测；同模型同一缺失贴图/未定义变量只报一次（去重，避免一个 60 元素的模型刷屏）。
4. **blockstate 属性校验**从 Java 源码提取而不是硬编码：支持 `BooleanProperty`、`DirectionProperty`（HORIZONTAL/VERTICAL/ALL）、
   `EnumProperty`（解析 `enum … implements StringIdentifiable { MAIN("main") … }` 的字符串值）；解析不出枚举值时降级为 WARN 并跳过值校验（不瞎判）。
5. **“运行期可达来源”判定**：只认代码实际执行路径（`LootRoll`、生产/发放代码、开局物资箱、配方）；
   `data/nbw/loot_table/*.json` 里的来源**先看该表是否被 Java 引用**，未被引用则标成“死表来源、实际不可得”。
   这样将来表被接回，Q4/Q5 的判定会自动更新，不用改脚本。
6. **退出码语义**：ERROR 才影响退出码（默认 0/1 可自动化）；WARN = 需人工裁决或文档不一致；`--strict` 时 WARN 也算失败。
7. **已知边界**：不解析 Java 语义（只看注册/属性声明模式）、不验证 `minecraft:` 资源、不解析 NBT/mcfunction 里的资源引用、
   不做客户端实机渲染校验；`fabric.mod.json` 只做关键字段与入口点类存在性校验。

**变异测试（自检可信度）**：把 `src/` 复制到 `%TEMP%\qfix`（**不碰仓库**）后注入 30+ 处缺陷（覆盖 20+ 类）再跑脚本，全部被捕获且各报一次：
`textures` 键带 `#`、非法 rotation 角、元素越界、`uv` 非法、贴图缺失、PNG 损坏、物品模型缺 `layer0`、缺物品模型、
blockstate 悬空模型、非法 `y` 旋转、variant 缺状态、非法属性值、中英缺键/占位符不一致、配方 result/材料 typo、
原版护栏被改活、loot entry 引用未注册 id/未知 entry type/嵌套表缺失、tag 引用未注册方块/缺失嵌套标签、
物理属性 selector/priority/值类型/未知属性错误、`sounds.json` 引用不存在 ogg、`fabric.mod.json` 入口点类缺失。
→ 脚本对「紫黑块类」与「key/引用类」缺陷有实际检出能力，不是空跑。

---

## 15. 限制与纪律说明

- 本轮**未启动/停止 Minecraft 服务端、未使用 RCON、未运行 gradle**；扫描期间服务端由其他测试占用。
- **未修改 `src/main/resources/` 下任何文件**；本轮只新增 `tools/check_resources.py` 与本报告。
- 关于 `sable-main/`：其 `AGENTS.md` 声明“不接受 LLM 参与其代码库”。因此本报告**没有分析 `sable-main` 源码**，
  Q7 的校验依据是**本项目自己文档**记录的 Sable 数据驱动约定（`docs/dev/M0-TEST-PLAN.md:139`、
  `docs/dev/M2-Sable适配层.md:113`，两者记录了 `physics_block_properties` 的 folder/schema 与 `sable:mass` 等键）。
  若需以 Sable 源码为最终准绳，需由人工/非 LLM 流程复核。
- 复跑建议：M6 记录方式按 `docs/dev/M6-FULL-TEST-PLAN.md` §记录方式，把 `check_resources.py --verbose` 的输出存到
  `run/probe_logs/` 或 CI 日志；本域结论以脚本输出为准，本报告数字对应当前 `HEAD`（含 `b23a1c6` 修复后）。
