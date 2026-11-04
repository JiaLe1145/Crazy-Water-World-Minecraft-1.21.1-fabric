# M2 · Sable 适配层接口草案

> 这是 `03-里程碑.md:20` 承诺的 M0 产出物：**我们到底需要 Sable 的哪几个能力，写成代码接口，后续只实现不返工。**
> 每个签名都带 `文件:行`，全部来自 `C:\Users\殷子竣\Desktop\sable-main`（2.0.6）。
> 纪律：`sable/` 是**唯一**允许出现 `dev.ryanhcode.sable`  import 的包（`01-技术架构.md`）。
> 上层（round / raft / zone / loot）只认本文件的接口，不认 Sable。

---

## 0. 一句话结论

**我们不需要"移动木筏"，我们需要"施加力"。**
Sable 里 sub-level 的位姿由 Rapier 积分产生，没有任何 `setPose`；
`teleport` 会先 `resetVelocity`（`SableSubLevelCommands.java:158`），所以它是**瞬移**，只配用于开局摆位和纠错，不能用于航行。
→ 航行 = 冲量/力/升力；停泊 = 什么都不做。

---

## 1. 容器与生命周期

```java
// 取容器（每维度一个）
ServerSubLevelContainer c = SubLevelContainer.getContainer(ServerLevel level);   // SubLevelContainer.java:103
// 注意：返回 @Nullable，Level 不是 sub-level 承载维度时为 null —— 必须判空

SubLevel  allocateNewSubLevel(Pose3d pose);                                       // :232
SubLevel  allocateSubLevel(UUID id, int x, int z, Pose3d pose);                   // 指定 id/槽位，用于可复现的开局筏
void      removeSubLevel(SubLevel sl, SubLevelRemovalReason reason);              // :517  ← 优先用这个重载
void      removeSubLevel(int x, int z, SubLevelRemovalReason reason);             // :481 / ServerSubLevelContainer:130
List<ServerSubLevel> getAllSubLevels();
SubLevelPhysicsSystem physicsSystem();
SubLevelTrackingSystem trackingSystem();                                          // ServerSubLevelContainer:121
void addObserver(SubLevelObserver obs);                                           // 筏体增删的生命周期回调
```

**局后清理走 `removeSubLevel(..., REMOVED)`，这是工程收尾，不是玩法。**
玩法侧的"沉筏"是另一回事（见 §6）。

## 2. 加载票：对局期间禁止筏子卸载

```java
// 类型可以自己注册（公开 create），我们注册一个 nbw:round_active
SubLevelLoadingTicketType<Unit> ROUND =
    SubLevelLoadingTicketType.create(NBW.id("round_active"), Unit.CODEC);        // SubLevelLoadingTicketType.java:19-25

boolean addForceLoadTicket(ServerSubLevel sl, SubLevelLoadingTicketType<T> type, T key);    // ServerSubLevelContainer:199
boolean removeForceLoadTicket(ServerSubLevel sl, SubLevelLoadingTicketType<T> type, T key); // :225
Map<ServerSubLevel, Set<SubLevelLoadingTicket<?>>> collectForceLoadTickets();               // :277
```

为什么必须要：`sub_level_tracking_range` 默认 320 格（`SableConfig.java:33-35`），
超距的筏不再收快照、也可能被卸载。缩圈后期筏与观察者相距很远，**没票就会"消失"**。
开局发票、结算收票，与回合状态机同生命周期。

## 3. 创建筏体：装配 vs 直接分配

```java
// A. 从世界方块装配（玩家自己搭的筏 → 变成一艘会动的筏）
ServerSubLevel assembleBlocks(ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks, BoundingBox3ic bounds);
                                                              // SubLevelAssemblyHelper.java:69
GatherResult gatherConnectedBlocks(BlockPos origin, ServerLevel level,
                                   int maxBlocks, FrontierPredicate predicate);   // :191

// B. 程序化生成（开局发筏 / 空投残骸）：直接分配 + 往 plot 写方块
SubLevel sl = c.allocateNewSubLevel(pose);
LevelPlot plot = sl.getPlot();
plot.newEmptyChunk(plot.getCenterChunk());
plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, state, 3);
sl.updateLastPose();                                                              // 用法见 SableSpawnCommands.java:460-521
```

**M2 用 B**（开局筏形状要可控可复现），玩家后续在筏上加块由 Sable 的 `block_placement` mixin 自动进 plot。

### 3.1 装配的三条硬约束（都是实测/源码踩出来的）

1. **`connected` 在水上必炸**：连通判定只排除空气，**水算连通方块**（`SubLevelAssemblyHelper.java:251-253`），
   而命令层传 `frontierPredicate = null`（`SableAssembleCommands.java:106, :332`）→ BFS 漏进海里爆 256000。
   → **我们的调用必须自带拒绝流体的 `FrontierPredicate`**（签名 `:496-508`）。Sable 留了口子，只是它自己的命令没用。
2. **BE 会随迁**：`saveWithFullMetadata` → 新 plot 里 `loadWithComponents` → 原位置清掉
   （`SubLevelAssemblyHelper.java:380-415`），实测质量算式吻合（49×0.5+0.5=25.0）。
3. **装配后不能用世界坐标寻址容器**：方块进了 plot，`/data modify block <原坐标>` 与
   `level.getBlockEntity(pos)` 都拿不到 → **战利品/筏心/设施读写一律走 plot 访问器**（`getEmbeddedLevelAccessor()`）。
   这条直接影响 M3 的战利品结算与 M4 的生产链写法。

## 4. 施加运动（航行的唯一正解）

```java
RigidBodyHandle h = c.physicsSystem().getPhysicsHandle(ServerSubLevel sl);
h.applyLinearImpulse(Vector3dc);          // SablePhysicsCommands.java:119-121
h.applyAngularImpulse(Vector3dc);         // :146-148
h.getLinearVelocity(Vector3d dest);       // SableCommand.java:242
h.getAngularVelocity(Vector3d dest);      // :246

PhysicsPipeline p = c.physicsSystem().getPipeline();
p.teleport(sl, Vector3dc pos, Quaterniond orientation);   // 会 resetVelocity → 只用于摆位/纠错
p.resetVelocity(sl);
p.addConstraint(slA, slB, PhysicsConstraintConfiguration);  // 筏间连接（钩索/登船）留作 M5 备选
```

参考实现入口（命令已验证可用）：`/sable physics impulse @n linear <x y z> global|local`。
`global` 会先 `logicalPose().transformNormalInverse(...)` 换到筏局部系（`SablePhysicsCommands.java:116`）
→ **"朝筏头方向推"要用 local，别自己算旋转。**

## 5. 帆 / 桨 / 转向：Sable 给接口，内容我们写

| 我们的玩法件 | 要实现的接口 | 证据 |
|---|---|---|
| 帆（多块自动成一组） | `BlockSubLevelLiftProvider` | `api/block/BlockSubLevelLiftProvider.java:22`；邻接块 flood-fill 分组 `:45-70`；聚合结果 `LiftProviderGroup{totalLift,totalDrag,liftCenter,dragCenter}` `:211-241` |
| 桨 / 螺旋桨 | `BlockEntityPropeller` + `BlockEntitySubLevelPropellerActor` | `api/block/propeller/` 整目录只有这两个文件，**全是 interface，无实现** |
| 转向 | `BlockEntitySubLevelReactionWheel`（角动量） | `api/block/BlockEntitySubLevelReactionWheel.java:11` |
| 任意设施对筏施加行为 | `BlockEntitySubLevelActor` | `api/block/BlockEntitySubLevelActor.java:13` |
| 浮力表现 | `data/sable/floating_materials/*.json` + `FloatingBlockController` | 加载器 `FloatingBlockMaterialDataHandler.java:29-33` |
| 方块质量 | `data/sable/physics_block_properties/*.json` + `#sable:light/heavy/...` 标签 | 加载器 `PhysicsBlockPropertiesDefinitionLoader.java:32-40` |

→ **P-01 的"推力/最高航速/转向速率"数值范式正好落在这批接口上，不需要先造闭环速度控制器。**
数值全部外置到 datapack JSON，改平衡不重启。

## 6. 读位姿（圈内外判定、伤害判定、观战跟随都要用）

```java
Pose3dc logicalPose();   // 当前 tick 的权威位姿（服务端）
Pose3dc lastPose();      // 上一 tick → 随动增量就是 pose∘lastPose¹
BoundingBox3ic boundingBox();
UUID getUniqueId();  String getName();  void setName(String);
MassTracker getMassTracker();   // .getMass() / .getCenterOfMass() / .isInvalid()
Vector3d latestLinearVelocity;  // 直接字段，选择器的 speed= 用的就是它
LevelPlot getPlot();
```

**判定一律用 `logicalPose()`（服务端权威），绝不用客户端插值位置。**
`KinematicContraption.sable$getPosition(partialTick)` 那种带 `partialTick` 的是**渲染插值**接口，
它的真实用途是"把外来 contraption 嵌进 plot"（`plot.getContraptions()` + `PhysicsPipeline.add/remove`，
`PhysicsPipeline.java:79-84`），**不是**我们的移动通道，M2 用不到。

沉筏（`docs/content/01-玩法设计.md:107`）的实现路径 = 破坏筏体方块 / 关掉浮力，让 Rapier 自己把它淹掉，
**不是**调 `removeSubLevel`。

## 7. 明确不要做的事

1. **不要在 `sable/` 之外 import `dev.ryanhcode.sable.*`。** 违反即返工。
2. **不要自己搬玩家。** 随动是 Sable 原生 mixin（`ServerPlayerMixin.java:30-51` + `ServerEntityMixin.java:41-69`），
   旧仓库 `RaftPrototype` 那套七步搬迁算法在这条路线上是纯负担，已随 spike/ 一起不移植。
3. **不要用 `teleport` 做航行**，也不要循环调 `translation add` —— 那是我们上一轮被否掉的跳格方案换了个入口。
4. **不要改 Sable 源码**。桌面 `sable-main` 只读，用于定位问题；要适配就写在我们这侧。
5. **不要依赖 `/sable` 命令做玩法**：根节点 `hasPermission(2)`（`SableCommand.java:40`），普通玩家用不了。
   命令树只用于测试与运维。
6. **不要假设 `engage_gizmo` 是玩法交互**：它进入即 `setPaused(true)`（`:98`），是调试工具。

## 8. 开工前仍需读通的点

- [ ] `LiftProviderContext` 的字段（面积/法向/攻角怎么喂）—— 做帆之前必须读通
- [ ] `BlockEntityPropeller` 的调用时机与 `substepsPerTick` 的关系（每 tick 调几次）
- [ ] `SubLevelRemovalReason` 的枚举取值与各自后果（尤其 REMOVED 是否把方块写回世界）
- [ ] `PhysicsConstraintConfiguration` 家族（`Rotary`/`Free`/…）→ 钩索登船能不能白捡
- [ ] `dimension_physics/DimensionPhysics` —— 海面重力/流体阻力是否可按维度调
