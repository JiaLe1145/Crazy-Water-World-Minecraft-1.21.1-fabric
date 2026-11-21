# NB-Water-World · 疯狂水世界（重构版）

Minecraft **1.21.1 / Fabric** 海上木筏生存竞技小游戏。
本仓库是**推倒重来**的新起点：内容设计沿用旧方案，技术路线改为**优先依赖成熟模组**而非自研。

> **许可证**：本项目自研代码采用 **MIT**（见 [`LICENSE`](LICENSE) 与
> [`docs/dev/00-决策与版本.md`](docs/dev/00-决策与版本.md) ADR-002）。
> 依赖 Sable 是 PolyForm Shield 1.0.0（禁竞争性分发、不禁商用），分发须随附 Required Notice，
> 其余第三方依赖各自保持许可证（Fabric API Apache-2.0、ImGuiMC/Veil LGPL、Forge Config API Port MPL）。
> 完整清单见 `docs/dev/04-依赖与运维.md`。

## 核心决策（已定）

| # | 决策 |
|---|---|
| V1 | 版本保持 **Minecraft 1.21.1 + Fabric**（不降级、不换加载器） |
| V2 | **木筏移动直接依赖 Sable**（`sable-fabric-1.21.1-2.0.6`）—— 不自研多方块移动 |
| V3 | 总原则：**难点工程能关联/移植/复刻/仿照就尽量不自研** |
| V4 | 游戏内容设计沿用旧方案（`docs/content/`），**开发计划与风险评估全部重写**（`docs/dev/`） |
| V5 | 旧仓库 `Crazy-Water-World` 归档保留，不再在此之上继续开发 |

## 文档结构

```
docs/content/   内容方案（沿用，来自旧计划）
  01-玩法设计.md        核心循环、黑海、状态、幸存者、筏心、事件、社交
  02-数值与节奏.md      三档赛制、圈表、状态速率、生产周期、配平回路
  03-地图与配置规范.md  arena/format/lobby JSON 规范与校验（需按新架构复核字段）
  09-武器与美术管线.md  12 件自制武器配平 + mcgen 美术产线

docs/dev/       新开发计划（全部重写）
  AI-交接与工作指引.md  ★ 新会话/换模型先读这份：进度快照、硬事实、剩余内容与做法
  00-决策与版本.md      为什么选 1.21.1 + Sable，候选对比与证据
  01-技术架构.md        Sable 负责什么、我们负责什么、分层与数据流
  02-风险与M0验证.md    M0 的风险清单与验证设计（结论已回填，历史价值为主）
  03-里程碑.md          新 M0–M5，每档交付物与 Gate
  04-依赖与运维.md      整合包依赖清单、许可证义务、版本锁定
  M0-RESULTS.md / M2-RESULTS.md   逐条实测记录（翻车点都在这儿）
```

## 想自己跑一遍测试？（给别的 AI / 协作者）

> **先读 [`docs/dev/AI-自主测试手册.md`](docs/dev/AI-自主测试手册.md)** —— 起服、RCON、起客户端、
> 截图、探针目录、判据契约、故障排查、黑名单全在里面，照着做不用问人。
>
> 最短路径：
>
> ```powershell
> .\gradlew.bat runServer --offline --console=plain > run\server-console.log 2>&1   # 等 "Done ("
> $env:RCON_PORT="25576"; $env:RCON_PASSWORD="nbwm0"; $env:PYTHONIOENCODING="utf-8"
> .\run\py-embed\python.exe -I -X utf8 tools\rcon.py "cww state"
> .\run\py-embed\python.exe -I -X utf8 tools\run_all_probes.py        # 30 个探针，约 30 分钟
> ```
>
> 测什么、还差什么：`docs/dev/M6-FULL-TEST-PLAN.md`（总清单）+
> `docs/dev/M6-REMAINING-TEST-PLAN.md`（剩余项与判据）。

## 现在最重要的事

**新会话（含换模型、换 AI）先读 [`docs/dev/AI-交接-v2-当前状态.md`](docs/dev/AI-交接-v2-当前状态.md)**：
当前进度（304 单测 / Gate G5）、代码地图、剩余项、待裁决项都在那一份里。

**参与开发前再读 [`CONTRIBUTING.md`](CONTRIBUTING.md)**：分层纪律、主干直推规范、测试要求、常见的坑。

其它补充上下文：

1. [`docs/dev/01-技术架构.md`](docs/dev/01-技术架构.md) —— 分层与两条硬纪律
2. [`docs/dev/03-里程碑.md`](docs/dev/03-里程碑.md) —— M0–M5 范围与 Gate
3. `docs/dev/M2/M3/M4/M5-RESULTS.md` —— 各阶段实测记录（翻车点都在里面）
4. [`docs/dev/M5-OPEN-DECISIONS.md`](docs/dev/M5-OPEN-DECISIONS.md) —— 待裁决项（**不要自己拍板**）

> **权威顺序**：代码 > RESULTS 文档 > `docs/content/`（设计意图）> 其他。

## 持续集成

推送到 `main` 或提 PR 时，GitHub Actions 会自动跑 **304 条单测**
（[`.github/workflows/test.yml`](.github/workflows/test.yml)）。

> Sable 的 16 MB jar 不入库（`.gitignore` 排除 `libs/`），CI 里从 Modrinth 官方 CDN 下载
> 并解出 `sable-companion`。本地开发需要自己把两个 jar 放进 `libs/`。

> M0 的答案早已有结论：**Sable 能用**（木筏平滑移动、玩家随动、设施可交互、专用服务端稳定），
> Gate G0 通过。证据在 `M2-RESULTS.md`，别重新验一遍。
