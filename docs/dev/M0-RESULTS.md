# M0 结果 · Sable 可行性验证

环境：本机 dev 服务端，MC 1.21.1 + Fabric Loader 0.19.3 + Fabric API 0.116.17 + Loom 1.17.21 + Gradle 9.8.2 + Java 21
日期：2026-11-04 23:15（本地）

---

## M0-1 依赖装配 —— 通过

- `./gradlew build` **BUILD SUCCESSFUL in 55s**，Sable 进 classpath 并完成 mixin 重映射。
- 我们的 jar `nbw-0.0.1.jar` = **2,299 字节**，确认**没有把 Sable 打进我们的 jar**（依赖单独安装，许可与体积都更干净）。
- 构建期警告：`Cannot remap lambda$stopSleeping$12 because it does not exist in any of the targets [class_1309]`
  → Sable 的 mixin 挂钩玩家睡眠逻辑，是它「侵入性」的直接体现。本次不影响构建，但**换 MC 小版本时这类 remap 失败会变成硬错误**（版本锁死风险 F14 的实证）。

### 依赖真相（修正 `04-依赖与运维.md`）

| 依赖 | 类型 | 结论 |
|---|---|---|
| fabric-api | **required** | 必装 |
| veil 4.3.2 | **embedded** | 已内嵌在 sable jar 内，**不需要单独下载** |
| forge-config-api-port | **embedded** | 同上 |
| imguimc | **optional** | 不装也能起（其 GUI 功能可能受限，M0-4 顺带观察） |
| sable_rapier 2.0.6 / sablecompanion 1.6.0 | 内嵌子模组 | 加载日志可见，无需我们管理 |

→ **实际只需要两个 jar：`sable-fabric-1.21.1-2.0.6.jar` + `fabric-api-0.116.17+1.21.1.jar`**，比旧评估简单一个量级。

---

## M0-2 专用服务端可用性 —— 通过

```
Loading Minecraft 1.21.1 with Fabric Loader 0.19.3
  - nbw 0.0.1
  - sable 2.0.6
      sable_rapier 2.0.6
          sablecompanion 1.6.0
          veil 4.3.2
(Sable) Creating physics pipeline for minecraft:overworld using RapierPhysicsPipelineProvider
(Sable) Using game directory relative Sable Rapier native directory ...\run\.sable\natives
[INFO] (sable_rapier) Rapier scene initialized
(Sable) Adding UDP server channel future / Server UDP channel active
(Minecraft) Done (3.476s)!
```

- 服务端侧**物理管线成功初始化**，Rapier 原生库自动释放到 `run/.sable/natives`，起服 3.4–4.0 秒，无崩溃。
- 待处理噪音：`(Sable) Failed to apply tag physics properties. Unknown block: create:flywheel` 重复出现 —— Sable 自带对 Create 的兼容数据，不装 Create 就刷 ERROR 日志。**生产环境必须处理**（要么装对应兼容模组，要么确认它只是噪音），否则真错误会被噪音掩盖。

---

## M0-3 API 调用路径 —— 通过

javap 解包得到的公开 API（intermediary 名，Loom 会映射成 Yarn 名）：

```java
ServerSubLevelContainer c = SubLevelContainer.getContainer(ServerWorld);   // class_1937
c.initialize(); c.tick();
SubLevel s = c.allocateNewSubLevel(Pose3d);                     // 创建（开局发初始筏）
SubLevel s = c.allocateSubLevel(UUID, int x, int z, Pose3d);    // 指定 id/坐标创建
c.removeSubLevel(int x, int z, SubLevelRemovalReason);          // 销毁（结算毁筏）
c.physicsSystem(); c.trackingSystem(); c.getAllSubLevels();
c.addObserver(SubLevelObserver);                                // 生命周期回调
c.getHoldingChunkMap();                                         // 方块暂存
```

- 另有 `api/schematic/SubLevelSchematicSerializationContext` → **可以从 schematic 批量生成 sub-level 内容**（初始筏模板靠它）。
- `api/physics/{force, constraint, mass, collider, handle, callback}` → 推进/转向用它的力学接口表达，不必我们自己算位移。

### Sable 自带一整套游戏内命令（M0-4/5/6 可零代码先试）

> ⚠️ 本节原先写的 `/sable sublevel list`、`/sable assemble start` **不存在**，是早期从服务端错误文本反推的产物。
> 定稿命令树（12 个顶层子命令，源码 + 客户端 Tab 双重确认）见 `M0-TEST-PLAN.md` §1：
> `assemble / spawn / name / teleport / remove / physics / info / paused / forceload / engage_gizmo / storage / debug`

→ **不用写代码就能先验证手感**：游戏内用 `/sable assemble` 造一艘筏、`/sable physics impulse` 让它动起来，比自研原型更接近最终形态。

---

## M0-4 玩家随动 + 移动手感 —— 通过

环境：用户启动器实例 + `dist/` 模组包（sable + fabric-api），单机集成服务端；录屏 2480×1440 @59.88fps，22.3s。

### 测试体构造（关键：甲板必须滞空）

21×21 石砌水池（水面 y=64，水深 4）+ 围墙到 y=75；7×7 橡木木板甲板**悬在 y=68**，与水面之间空 3 格。

- 甲板滞空后 `/sable assemble connected` **一次成功**；此前甲板贴水时必爆 `too many blocks (maximum 256000)`
  —— 因为连通判定只排除空气、水被当成连通方块，BFS 从筏板漏进整片海（详见 `M0-TEST-PLAN.md` T0）。
- 装配后甲板方块被搬进 plot、世界位置变 AIR，筏子**自行自由落体入水并漂在水面** —— 这是第一次看到 Sable 的物理落地，不需要我们写任何下落逻辑。

### 客观读数

```
/sable info @l →  Found 1 sub-levels: raft
  Position: 10.4595 64.9869 10.4594
  Orientation: -0.00357 -0.00158 0.00356 0.99999   （≈ 水平，无侧倾）
  Mass: 25.0
  Linear Velocity: 0.0 0.0 0.0
  Angular Velocity: 0.0 0.0 0.0
```

**质量算式对得上**：49 块木板 × 0.5（`#sable:light`）+ 1 个箱子 0.5 = **25.0**
→ 证明 50 格全部进了**同一个** sub-level（没分裂），且**方块实体被计入质量**（BE 确实随迁进来了）。

### 随动断言

```
/sable info @t →  Found 1 sub-levels: raft      ✅ 随动绑定生效
/sable info @i →  No sub-levels found           ⚠️ 预期内，非失败
```

- **`@t` 才是真正的随动断言**：它读 `getTrackingSubLevel(player)`，即 `ServerPlayerMixin` 每 tick 施加刚体增量的前提。它解析出 raft ⇒ 随动链路整条活着。
- **`@i` 不是"我站在什么上面"**：`getContaining` 实际是**按 chunk 槽位查 plot**（`ActiveSableCompanion.java:92-104` → `container.getPlot(chunkX>>4, chunkZ>>4)`），而 plot 在 16384 槽 plot 网格里的槽位与它的世界坐标无必然关系。测试方案 T1 里"两条都要通"的要求是我写错了，已更正。

### 手感

`/sable physics impulse @n linear 0 0 12` 连续 3 次，动作栏回显 `Applied global linear impulse to raft of 0.0, 0.0, 12.0`。
**用户判定：移动顺畅**（旧自研方案的"跳格感"没有复现）。
抽帧复核 t=0.5 / 8 / 20s 三帧：玩家始终站在甲板上、甲板始终在水面 —— **旧 bug"人没跟着平台一起走"未复现**。

→ 因此不需要跑 T3 的抖动归因（`substeps` / `damping` / UDP 三条旋钮仍保留在测试方案里，等真出现抖动再用）。

### 顺带发现（要进 M2 数值）

甲板顶面几乎与水面齐平（Position y=64.99，水面顶层 y=64）→ **干舷≈0**。
50 格的筏已经快沉到甲板吃水，玩法上会出现"海水漫上甲板 / 站在筏边就泡水"。
调法两条：`data/sable/floating_materials/*.json` 提 `lift_strength`，或给筏体方块降 `sable:mass`。

---

## 结论

| 项 | 状态 |
|---|---|
| M0-1 依赖装配 | 通过（依赖比预期少两个） |
| M0-2 专用服务端 | 通过（物理管线初始化 + 起服 3.5s） |
| M0-3 API 路径 | 通过（容器/分配/销毁/物理/回调齐全 + schematic + 全套命令） |
| M0-4 玩家随动 + 手感 | **通过**（`@t` 绑定成功 + 入水自漂 + 用户判定顺畅 + 抽帧复核无人货分离） |
| M0-5 移动中交互与 BE 保真 | 进行中（BE 已确认随迁并计入质量，**开箱可交互尚未测**） |
| M0-6 移动中建造 | 进行中 |
| M0-7~10 性能 / 共存 / 崩溃恢复 / 公平 | 待做 |

**路线判定：Sable 这条路成立**，M0-4 这个最大的不确定点已经落地，且**一行代码没写**。
