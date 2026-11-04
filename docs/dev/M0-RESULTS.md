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

```
/sable sublevel list ...     存在，需参数
/sable sublevel create ...   存在，需参数
/sable assemble start ...    存在，需参数（把选中的方块装配成 sub-level）
/sable joint | spawn | storage | config 等命令组均存在
```

→ **不用写代码就能先验证手感**：你在游戏里用 `/sable assemble` 造一艘筏再让它动起来，比我们的自研原型更接近最终形态。

---

## 结论

| 项 | 状态 |
|---|---|
| M0-1 依赖装配 | 通过（依赖比预期少两个） |
| M0-2 专用服务端 | 通过（物理管线初始化 + 起服 3.5s） |
| M0-3 API 路径 | 通过（容器/分配/销毁/物理/回调齐全 + schematic + 全套命令） |
| M0-4 手感 | **待你真人判定**，命令入口已找到 |
| M0-5 移动中交互与 BE 保真 | 待做 |
| M0-6 移动中建造 | 待做 |
| M0-7~10 性能 / 共存 / 崩溃恢复 / 公平 | 待做 |

**路线判定：Sable 这条路目前成立**，三个先决条件全绿，尚未出现失败点。
