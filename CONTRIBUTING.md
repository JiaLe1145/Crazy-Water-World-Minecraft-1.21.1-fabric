# CONTRIBUTING · 协作开发规范

> 本项目是 Minecraft 1.21.1 + Fabric 的海上木筏生存竞技小游戏。
> 代码由多人 + AI 协作开发，这份文档把「怎么改、怎么测、怎么提交」写死，避免各写各的。

---

## 1. 先读什么

| 顺序 | 文档 | 用途 |
|---|---|---|
| 1 | [`docs/dev/01-技术架构.md`](docs/dev/01-技术架构.md) | 分层与两条硬纪律 |
| 2 | [`docs/dev/03-里程碑.md`](docs/dev/03-里程碑.md) | M0–M5 的范围与 Gate 判据 |
| 3 | `docs/dev/M2/M3/M4/M5-RESULTS.md` | 各阶段的**实测记录**（翻车点都在里面） |
| 4 | [`docs/dev/M5-OPEN-DECISIONS.md`](docs/dev/M5-OPEN-DECISIONS.md) | 待裁决项（**不要自己拍板**） |

> **权威顺序**：代码 > RESULTS 文档 > 内容设计文档（`docs/content/`）> 其他。
> 内容文档写的是「设计意图」，不是「实现现状」。

---

## 2. 两条硬纪律（必须遵守）

### 2.1 `domain/` 不得 import `net.minecraft.*` 或 Sable

玩法判定必须留在 `domain/` 的纯函数里，这样才能用 JUnit 单测覆盖。
适配层（`round/` `raft/` `combat/` 等）只负责搬运事实与执行表现。

### 2.2 Sable API 只能出现在 `sable/` 包

`sable/SableRafts.java` 是**唯一**允许 `import dev.ryanhcode.sable.*` 的文件。
Sable 升级或替换时，改动面被限制在这一个文件里。

---

## 3. 分支规范（主干直推）

**本仓库只有一个长期分支：`main`。所有人直接提交到 `main`，不拉长期 feature 分支。**

| 项 | 规则 |
|---|---|
| 主干 | `main`（GitHub 默认，唯一真源） |
| 提交 | 直接 `git push origin main` |
| 临时分支 | 仅用于实验/大改；合并前必须 rebase 到最新 `main`，合并后立刻删除 |

**每次开工的标准动作**：

```bash
git checkout main
git pull --rebase origin main   # 先把别人的进度拿下来
# ... 改代码 ...
./gradlew test                   # 必须全绿
git add -A
git commit -m "M5-9：..."
git push origin main
```

**为什么用 `--rebase`**：主线保持线性，避免一堆 merge commit；冲突在本地解，别把冲突推给别人。

**避免冲突的约定**：
- 动大文件（`RoundManager` / `NbwCommands` / `SableRafts` / `RescueService`）前先在群里说一声。
- 一个提交只改一件事，改完立刻推。
- 每天至少 `pull --rebase` 一次，别攒一整天。
- 推送被拒（`non-fast-forward`）时**不要 `--force`**，先 `git pull --rebase origin main` 再推。

**允许的例外**：大改可以开 `feat/<主题>`，但要求——从最新 `main` 切出、最多存在 2–3 天、合并前 rebase、合并后删除。

---

## 4. 提交信息规范

格式：

```
<范围>：<一句话做了什么>

<可选：为什么这么做 / 实测读数 / 已知边界>
```

**范围**用里程碑或模块前缀，与本仓库历史一致：

`M0` `M1` `M2` `M3` `M4` `M5` `P2` `P3` `Gate G5` `安全` `ops` `文档` `回归`

**示例**（摘自真实历史）：

```
M5-8：反偷家 ×0.25（筏心 HP + 破拆）
M5 事件硬化：8s 预告 + 战绩落盘 + 黑海倒流外扩
安全：输入包 40/s 限速（hold/move 共用）
ops：补 restore.ps1，回滚演练脚本化
```

**要求**：
- 第一行 ≤ 50 字，说清「做了什么」，不要写「update」「fix bug」。
- 提交信息必须与实际改动一致；提交前先 `git diff --stat` 核对。
- 涉及数值改动的，把「文档值 → 实测值」写进正文。

---

## 5. 测试要求

### 5.1 提交前必须过

```bash
./gradlew test
```

- 当前基线：**304 条，0 失败**。
- **不允许**带着失败提交；如果确实要临时跳过，在 PR 里写明原因。

### 5.2 什么改动必须加测试

| 改动 | 要求 |
|---|---|
| 改了 `domain/` 的纯函数 | **必须**加/改对应单测，覆盖新边界 |
| 改了数值常量 | 单测断言要同步改（历史上出过「测试照错数写、绿但测的是错的」） |
| 只改适配层/表现 | 至少跑一次探针（见 5.3） |
| 只改文档 | 不需要测试 |

### 5.3 起服验证（适配层改动）

涉及 MC 世界的改动（方块/实体/战斗/传送）单测覆盖不到，必须起服用探针验：

```bash
# 1) 起服（日志必须重定向）
gradlew.bat runServer > run\server-console.log 2>&1
# 等日志出现 "Done ("

# 2) 跑对应探针
$env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"
python tools/pvp_probe.py
```

探针清单见 `tools/`（19 个）。**探针断言必须带证据**（例：`PASS - 实测 40.0`），不能只写 PASS。

---

## 6. 代码风格

- **缩进用 Tab**（与现有代码一致）。
- **注释用中文**，写「为什么」而不是「是什么」；复杂判断要写清边界。
- 类/方法用英文命名；面向玩家的字符串用中文。
- 一个方法只做一件事；超过 ~60 行考虑拆分。
- 纯函数优先：能放进 `domain/` 的判定不要放进适配层。

---

## 7. 常见的坑（代价换来的）

1. **起服前先查端口**（25565/25576），旧进程残留会让验证结果全是假的。
2. **单测前先停服**：`runServer` 会占用 Gradle 项目锁。
3. **假玩家只验证服务端数据**，依赖客户端同步的表现（随动/HUD/视角/手感）必须真人验。
4. **`libs/` 不入库**：Sable jar 由 CI 下载（见 `.github/workflows/test.yml`）；本地开发需要自己放 `libs/`。
5. **配置路径是 `<world>/cww/`**，不是 `config/`。
6. **单位陷阱**：圈层配置 `damage` 是 ♥，武器 `damage` 是 HP（1♥ = 2 HP）。

---

## 8. 提交前自检（主干直推）

因为直接推 `main`，没有 PR 做缓冲，所以每次推送前自己过一遍：

1. `git pull --rebase origin main` —— 确认基于最新代码。
2. `./gradlew test` —— 304 条全绿。
3. 涉及 `domain/` 数值的改动：确认单测同步更新（历史上出过「测试照错数写」）。
4. 涉及适配层的改动：至少跑一次对应探针。
5. `git diff --stat` —— 确认没有误提交 `run/`、`build/`、`libs/` 等。
6. 提交信息写清「改了什么 / 为什么 / 怎么验的」。

**可选**：大改或不确定的改动，开 `feat/<主题>` 分支 + PR，让 CI 先跑一遍再合。

---

## 9. 不要做的事

- ❌ 不要自己发明玩法机制——新机制先写进 `M5-OPEN-DECISIONS.md` 等用户批准。
- ❌ 不要把 Sable API 写进 `sable/` 以外的文件。
- ❌ 不要让客户端参与玩法判定（伤害/圈内外/淘汰必须服务端重算）。
- ❌ 不要提交 `run/`、`build/`、`dist/`、`libs/`、`.idea/`（已在 `.gitignore`）。
- ❌ 不要为了「快」跳过测试或伪造探针读数。
