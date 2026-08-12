# 客户端子世界功能代码审计报告

## 1. 审计结论

**总体风险等级：High。当前分支不建议按现状直接合并或发布。**

本次审计由 3 个 Agent 并行完成，仅依据当前分支代码、测试代码和实际构建产物进行分析，未参考 README、`docs/` 或其他说明文档。

- 审计分支：`fix/no-ticket-detect-client-subworlds`
- 业务代码基线：`ec4dcc81`
- 审计日期：2026-08-12
- 审计范围：子世界识别、切换检测、自动创建、自动命名、档案读写、地图预览来源、评分标准、数据破洞、退出保存和跨版本接入
- 可编辑流程图：`subworld-code-audit.drawio`（审计产物提交 `45614dd0`）

代码中已经实现了 registry 损坏隔离、persist-before-publish、同种子创建保护、删除事务、轨迹与地形辅助识别等正向设计。但目前仍存在错误档案写入、真实历史区域被覆盖、切换漏检和退出丢状态等高风险问题。

## 2. 风险汇总

| ID | 等级 | 结论 | 主要影响 | 归属 |
|---|---|---|---|---|
| H-01 | High | provisional 候选被否决后，内存地图仍可能写进错误档案 | 跨子世界地图永久污染 | 当前分支新增链路 |
| H-02 | High | 同维度、同模式的远距离切换可能完全检测不到 | 新世界地图继续写入旧档案 | 当前分支检测链路 |
| H-03 | High | provisional 被作为正式身份暴露，且可能长期不结束 | 路径点、死亡点、注记绑定错误档案 | 当前分支新增链路 |
| H-04 | High | 1.18.2、1.20.1、1.21.3、1.21.4 未实际注册命令监听 Mixin | 精确命令切换在这些版本不可用 | 当前分支跨版本接入遗漏 |
| H-05 | High | region 读取队列满后，最终 flush 可能用不完整 region 覆盖完整历史 | `.cfr` 永久真实破洞 | `origin/master` 遗留 |
| H-06 | High | 退出时 in-flight visit/checkpoint 后面的最新状态不会入队 | 重启后识别连续性回退 | 当前分支异步写入链路 |
| H-07 | High | 所有后台任务共用 5 秒关闭期限 | 最终地图和档案任务可能直接丢失 | `origin/master` 遗留，当前分支放大 |
| M-01 | Medium | 待识别快照超过 8192 个后淘汰最旧块 | 早期行走路线形成缺块 | 当前分支新增链路 |
| M-02 | Medium | 列表预览超过 2048 个 region 后抽样，但 UI 不提示 | 缩略图出现假破洞 | 当前分支新增功能 |
| M-03 | Medium | 列表预览只读落盘 `.cfr` | 最近约 30 秒地图显示为空或过期 | 当前分支新增功能 |
| M-04 | Medium | Velocity 能力信号稍晚到达时永久放弃精确查询 | 同种子候选更容易误判或重复创建 | 当前识别链路 |
| M-05 | Medium | 旧缓存 `PREDICTED` 被提升为 `REAL_CACHED` | 预测层可能被错误挖空 | `origin/master` 遗留 |
| M-06 | Medium | profile/checkpoint/delete journal 缺少断电级 fsync | 已报告成功的数据可能在断电后回退 | 部分为既有风险 |
| L-01 | Low | `World N` 和手动重命名均允许重名 | UI 和人工选择难以区分 | 既有命名逻辑 |

## 3. 高风险发现

### H-01：provisional 被否决后仍会污染真实地图档案

#### 证据链

1. provisional 阶段的快照会进入待定缓冲，但同时以 `REAL_LIVE` 发布到当前 `MapWorld`：
   - [`ChunkCaptureService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/snapshot/ChunkCaptureService.java#L257)
   - [`ChunkCaptureService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/snapshot/ChunkCaptureService.java#L330)
2. 后续身份或地形证据否决候选时，只清空待定快照和锁：
   - [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L1203)
3. tracker 随后结束旧 session，region cache 无条件 flush 当前持久层：
   - [`WorldSessionTracker.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/WorldSessionTracker.java#L89)
   - [`RegionCacheService.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionCacheService.java#L49)
   - [`RegionDiskCache.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionDiskCache.java#L397)

#### 影响

新子世界的真实块会永久写进被否决候选 A 的 `.cfr`。之后这些块会作为真实缓存加载，既污染地图，也可能反过来污染后续地形判断和人工选择。

#### 建议

provisional 必须拥有独立且不可持久化的 `MapWorld`/overlay，或者 region flush 必须携带“已稳定确认”的 session token。候选被否决时应整体丢弃 provisional world，不能依靠清空另一套 pending buffer 来保证隔离。

### H-02：同维度远距离切换可能完全漏检

切换探测要求 20 tick 窗口内出现两个不同弱信号。远距离位置跳跃只贡献一个弱信号；区块替换又只计算相同 chunk 坐标同时出现在 unload/load 集合中的交集。两个子世界坐标完全不重叠时，交集反而是 0。

- [`ClientWorldChangeDetector.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientWorldChangeDetector.java#L18)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L2274)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L2314)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L2326)

触发条件为：没有新 `GameJoin`、客户端 world 对象没有替换、游戏模式不变、同一维度、远距离传送、新旧 chunk 坐标不重叠。此时服务会保持 `STABLE` 并继续复用旧 profile。

建议把“旧视口大量卸载 + 新视口大量加载”按集合变化规模计算，而不是只统计坐标交集；同时以服务端实际收到的 chunk 数作为分母，并为单个超大位置跳跃增加重新识别策略。

### H-03：provisional 被暴露成正式身份

`ClientWorldResolution.provisional()` 仍返回 `State.RESOLVED`，服务随后对外返回 `WorldIdentity` 并进入 `STABLE`。

- [`ClientWorldResolution.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/multiworld/ClientWorldResolution.java#L64)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L502)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L673)

地图捕获拥有部分 provisional 缓冲，但路径点、死亡点和注记等消费者会立即按这个 identity 加载、显示和写入：

- [`WaypointService.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/waypoint/WaypointService.java#L78)
- [`AnnotationService.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/annotation/AnnotationService.java#L31)
- [`DeathWatcher.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/DeathWatcher.java#L56)

地形最多尝试 5 次，后续又只有证据变化时才重新验证。因此地形一直不完整时，候选可能无限期保持 provisional，却在外围表现得像正式档案。

建议增加独立的 `PROVISIONAL` 会话状态；所有有副作用的 profile 消费者只能读取隔离视图，禁止持久写入。超时或地形重试耗尽后必须转人工选择或 fail-closed。

### H-04：多个支持版本没有接入命令精确切换

唯一命令入口是 `ChatScreenMixin.onChatSubmitted()`，但下列版本的最终 mixin 清单没有注册该类：

- [`versions/1.18.2/.../confluxmap.mixins.json`](../../../versions/1.18.2/src/main/resources/confluxmap.mixins.json#L9)
- [`versions/1.20.1/.../confluxmap.mixins.json`](../../../versions/1.20.1/src/main/resources/confluxmap.mixins.json#L9)
- [`versions/1.21.3/.../confluxmap.mixins.json`](../../../versions/1.21.3/src/main/resources/confluxmap.mixins.json#L9)
- 1.21.4 预处理后的最终资源同样缺失

这些版本中，用户输入配置好的切换命令也不会建立目标 profile 锁，只能退回通用变化检测，并与 H-02 叠加。

建议在每个版本的最终 `processResources` 产物上增加清单契约测试，而不是只检查 Mixin Java 源码存在。

### H-05：读取队列满时会产生永久真实破洞

`RegionDiskCache` 最多允许 64 个 region 读取待处理。队列满后，新的读取请求直接返回 `null`。实时捕获已经先把新 chunk 放入只包含部分内容的内存 region，再调用 `ensureRegionLoaded`，但没有检查返回值。若随后切换、断线或退出，session-end flush 会用这个部分 region 原子覆盖旧完整 `.cfr`。

- [`RegionDiskCache.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionDiskCache.java#L145)
- [`ChunkCaptureService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/snapshot/ChunkCaptureService.java#L375)
- [`RegionDiskCache.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionDiskCache.java#L397)

这里的“原子覆盖”只能保证文件不是半写状态，不能保证内容完整。旧文件中未加载进内存的其他 chunk 会永久变成 `UNKNOWN`。

建议为 region 建立明确的 `NOT_LOADED / LOADING / MERGED / DIRTY_COMPLETE` 状态。读取未成功合并前，禁止写回覆盖已有文件；队列满时应保留 dirty intent 并稍后重试，session-end 必须等待读取完成或跳过该 region。

### H-06：退出时可能丢最后一次 visit 和 trajectory checkpoint

visit 写入进行中时，更新后的 visit 只放在内存 `pendingStableVisits`。提交下一笔需要后续客户端 tick 调用 `applyCompletedVisitPersistence()`。shutdown 后不再有 tick。checkpoint 已经在写时，强制 flush 也直接返回。

- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L1255)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L1361)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L1402)
- [`ConfluxMapClient.java`](../../../src/main/java/cn/net/rms/confluxmap/ConfluxMapClient.java#L329)

影响是退出前最新维度、位置和轨迹没有进入 `client_worlds.json` 或 checkpoint，重启时识别连续性回退到旧状态。

建议提供显式 `drainPersistenceOnShutdown()`：停止接收新更新、等待当前写完成、在同一 IO 队列循环提交合并后的最后状态，直到 pending 为空或达到可观测的超时；超时必须报告未保存状态。

### H-07：最终保存共用固定 5 秒关闭期限

session 结束后才排队最终 region flush，随后 `MapExecutors.shutdown(5000L)` 让 worker 和 IO 共用同一个截止时间。worker 若耗尽时间，IO 等待时间为零。后台线程还是 daemon，超时后没有重试、终止状态检查或未完成告警。

- [`ConfluxMapClient.java`](../../../src/main/java/cn/net/rms/confluxmap/ConfluxMapClient.java#L329)
- [`MapExecutors.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/task/MapExecutors.java#L50)

建议关闭流程按依赖顺序执行：停止生产新任务 → 等待 worker → 提交最终档案/region flush → 单独 drain IO。每一阶段应有独立预算、未完成计数和日志。

## 4. 其他风险

### M-01：8192 个待定快照上限造成早期路线缺块

待识别或等待人工选择可无限持续；超过 8192 个不同 chunk/layer 后直接淘汰最旧记录。最终确认时只重放仍保留的记录，并重新采样当前视距，早期走过且已经卸载的路线无法补回。

- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L62)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L260)

### M-02：超过 2048 个 region 的预览会出现假破洞

预览按完整档案计算边界和缩放比例，但只挑选最多 2048 个 region 绘制。未选区域保持透明；`sampled=true` 又没有在 UI 展示，探索 chunk 数仍统计全量文件。

- [`RegionHistoryPreviewLoader.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionHistoryPreviewLoader.java#L35)
- [`RegionHistoryPreviewLoader.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionHistoryPreviewLoader.java#L136)
- [`RegionHistoryPreviewLoader.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionHistoryPreviewLoader.java#L257)

这只影响列表缩略图，不代表主地图数据真的丢失，但当前 UI 无法让用户区分。

### M-03：列表地图不是当前内存地图

子世界列表选择 profile 后，取 `lastVisitedAt` 最新的维度，并在 worker 中直接读取：

```text
<gameDir>/confluxmap/cache/
  <storageServerId>/<storageId>/<dimensionId>/<durable-layer>/r.<x>.<z>.cfr
```

- [`ClientWorldSelectScreen.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/ui/screen/ClientWorldSelectScreen.java#L163)
- [`ClientWorldMapPreview.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/ui/screen/ClientWorldMapPreview.java#L59)

它不读取 Minecraft 世界存档、不读取 `client_worlds.json` 中的地图，也不合并当前内存 `MapWorld`。常规地图约每 30 秒 sweep，或在 session-end 写入，因此新档案和最近探索可能显示为空或过期。

Nether 列表预览固定读取 `nether_roof`；地下当前层不是持久历史层，所以列表不会显示玩家当时在地下看到的内容。

### M-04：Velocity 精确识别存在能力到达竞态

第一次检查时，只要品牌包或 `/server` 命令树尚未到达，就会标记为 `UNAVAILABLE`，以后不会重试。建议在能力信号变化或有限 tick 窗口内重新评估，而不是第一次缺失便永久放弃。

- [`VelocityServerIdentityQuery.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/VelocityServerIdentityQuery.java#L47)
- [`ClientMultiworldService.java`](../../../src/main/java/cn/net/rms/confluxmap/mc/world/ClientMultiworldService.java#L2180)

### M-05：旧 `PREDICTED` 缓存可能挖空预测图层

磁盘加载只跳过 `UNKNOWN`，会把持久化的 `PREDICTED` 也放入世界并标成 `REAL_CACHED`。tile 合成看到“真实块”后会把预测像素清空，造成透明块。

- [`RegionDiskCache.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/cache/RegionDiskCache.java#L275)
- [`TileService.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/tile/TileService.java#L344)

### M-06：原子替换不等于断电持久

profile、trajectory checkpoint 和删除 journal 使用“写临时文件 → move”，但没有对文件和父目录完成 fsync。系统断电或存储异常时，已经向 UI 报告成功的状态仍可能回退。

- [`ClientWorldProfileIo.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/multiworld/ClientWorldProfileIo.java#L113)
- [`ClientWorldTrajectoryCheckpointIo.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/multiworld/ClientWorldTrajectoryCheckpointIo.java#L189)
- [`ClientWorldProfileDeletionService.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/multiworld/ClientWorldProfileDeletionService.java#L278)

### L-01：自动名称允许重复

自动名称固定为 `World {profiles.size()+1}`，手动重命名只检查非空。删除档案使列表缩短后，下一次创建可能复用已有显示名。

- [`ClientWorldProfileResolver.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/multiworld/ClientWorldProfileResolver.java#L2144)
- [`ClientWorldProfile.java`](../../../common/src/main/java/cn/net/rms/confluxmap/core/multiworld/ClientWorldProfile.java#L115)

存储 ID 使用 UUID，因此不会覆盖磁盘目录；问题主要是列表展示、人工选择和命令配置含义不清。

## 5. 子世界识别和切换流程

大白话流程如下：

1. 客户端接收进服、重生、出生点、服务端位置确认、区块加载/卸载和游戏模式变化。
2. 优先寻找确定答案：Velocity backend、用户刚提交的切换命令或用户手动选择。
3. 没有确定答案时先按 seed 过滤候选档案。
4. 使用轨迹、上次稳定档案、游戏模式、访问上下文、身份信号和地形指纹评分。
5. seed、关键身份、稳定访问上下文、最近维度或地形明显冲突的候选直接淘汰。
6. 分数、领先幅度和证据门槛足够时确认档案；证据只能弱支持时产生 provisional；证据不足时等待或人工选择。
7. 确认切换后生成新的 `WorldIdentity`，地图、路径点、注记等消费者切换到对应 profile。
8. 没有同 seed 档案且满足创建条件时，异步自动创建新 profile。

## 6. 评分标准

### 6.1 辅助证据

辅助分只对当前可用证据做归一化：

| 证据 | 权重 |
|---|---:|
| 玩家轨迹 | 0.60 |
| 上次稳定档案 | 0.20 |
| 游戏模式 | 0.15 |
| 稳定访问上下文 | 0.25 |
| profile 身份信号 | 0.25 |

身份信号原始分为 `min(1, 匹配数量 / 2)`。

### 6.2 最终分

- 有完整可比地形：`最终分 = 0.75 × 辅助分 + 0.25 × 地形分`
- 没有地形：`最终分 = 辅助分`

### 6.3 地形指纹

地形比较需要完整 3×3 chunk。每个 chunk 取 4×4 列，共 144 列。

每列评分：

- 高度差 ≤ 1：`+0.45`
- 高度差 ≤ 3：`+0.25`
- biome 相同：`+0.30`
- kind 相同：`+0.15`
- fluid 相同：`+0.10`
- fluid 相差 1：`+0.05`

### 6.4 候选队列与门槛

| 队列 | 条件 | 最低分 |
|---|---|---:|
| Q1 | 距离历史轨迹/位置近，并有对应中心地形 | 60% |
| Q2 | 距离历史轨迹/位置近 | 70% |
| Q3 | 主要依赖身份和上下文 | 80% |

- Overworld 距离半径：48 blocks
- Nether 距离半径：6 blocks
- 只有一个档案时仍至少要求 95%
- 一般候选必须严格领先第二名 3 个百分点
- Q3 必须严格领先 15 个百分点
- 稳定地形确认要求地形分 ≥ 85%
- 多候选时还需对每个兼容候选至少领先 10 个百分点

硬否决条件包括 seed 冲突、profile 身份冲突、稳定 visit 上下文冲突、地形分低于 60%、上次稳定档案冲突、已离开的 profile 和最近维度冲突。

## 7. 自动创建、命名与档案写入

### 7.1 自动创建条件

自动创建只应发生在以下条件同时满足时：

- 当前 seed 是新的或没有同 seed 可用档案；
- 没有禁用自动检测的冲突 profile；
- 没有命令/人工目标锁要求选择已有档案；
- 没有超过 profile 数量限制；
- 当前识别 generation 仍然有效。

创建采用“复制 registry → 在副本中创建 profile → IO 线程先写磁盘 → 成功后在客户端线程发布”的 persist-before-publish 流程。过期结果会回滚，这部分设计方向正确。

### 7.2 自动命名

- 显示名：`World {profiles.size()+1}`
- 首个存储 ID 通常为 `world`
- 后续存储 ID 使用 client UUID

显示名可能重复，存储 ID 不会因此冲突。

### 7.3 档案位置

```text
<configDir>/confluxmap/client_worlds.json
<gameDir>/confluxmap/trajectory-checkpoints/<serverHash>.json
<gameDir>/confluxmap/trajectory-checkpoints/<serverHash>.candidate.<profileHash>.json
<gameDir>/confluxmap/cache/<storageServerId>/<storageId>/<dimension>/<layer>/r.<x>.<z>.cfr
```

registry 大于 4 MiB、JSON 损坏或 schema 不支持时，会写 `.blocked` 并把原文件隔离为 `.bad.<time>`，保持 fail-closed，不会用空 registry 覆盖旧档。这是当前分支的重要正向变化。

### 7.4 地图写入

1. 主线程从客户端 chunk 生成不可变快照。
2. 快照先进入 `MapWorld`。
3. 单 IO 线程按需读取已有 `.cfr` 并合并。
4. 常规情况下约每 30 秒 sweep 写盘。
5. 切换、断线或退出时执行 session-end flush。
6. `.cfr` 以临时文件写入后原子替换旧文件。

正常排队时，读取和 flush 使用同一 IO 队列，因此顺序有保证；H-05 的问题在于读取请求根本没有成功入队。

## 8. 测试与评测结果

### 8.1 已执行测试

| 模块 | 套件数 | 用例数 | 结果 |
|---|---:|---:|---|
| `common` | 9 | 101 | 0 failures，0 errors |
| `1.21.1` 客户端 | 4 | 71 | 0 failures，0 errors |
| 合计 | 13 | 172 | 全部通过 |

覆盖的关键测试包括 resolver、profile IO、terrain visit、trajectory/checkpoint、region cache、region codec、dirty chunk、multiworld service、change detector、select screen 和 ChatScreenMixin 源码测试。

第一次并发执行时，多个 Agent 同时写 Gradle 测试结果目录，出现 `in-progress-results-generic.bin` 的 `NoSuchFileException`。停止并发并执行 clean sequential run 后，172 个测试完整通过，因此第一次结果属于测试基础设施碰撞，不是产品测试失败。

### 8.2 流程图校验

- XML 解析通过
- 共 5 页
- 中文 UTF-8 正常
- 所有节点均在页面画布范围内
- 本机未安装 draw.io CLI，因此没有执行原生 draw.io 渲染截图检查

### 8.3 关键测试缺口

现有测试通过不代表上述风险不存在。以下路径没有覆盖：

1. provisional 候选被否决后，session-end 不得把其 `MapWorld` 写入候选档案。
2. 64 个读取槽占满后，部分 region 不得覆盖完整旧 `.cfr`。
3. 同维度、同模式、远距离且 chunk 坐标不重叠的完整切换。
4. provisional 地形重试耗尽后的超时和人工降级。
5. provisional 期间路径点、死亡点和注记不得写入候选 profile。
6. 真正 shutdown 且 visit/checkpoint 正在写的 drain 流程。
7. 8192 个待定快照淘汰后的地图完整性。
8. 2048 个以上 region 的预览抽样和 UI 提示。
9. 每个支持版本最终 mixin 清单的契约测试。
10. Velocity 能力信号延迟一至数 tick 到达。
11. 大 IO backlog 下的 5 秒关闭行为。

## 9. 修复优先级建议

### P0：先阻止错误写盘和真实数据丢失

1. 修复 H-01：provisional 地图与正式持久层物理隔离。
2. 修复 H-05：未完成旧 region 加载时禁止覆盖已有 `.cfr`。
3. 修复 H-06/H-07：实现可 drain、可观测的关闭保存协议。

### P1：保证身份状态和切换检测可信

4. 修复 H-03：增加真正的 `PROVISIONAL` 状态和消费者写入门禁。
5. 修复 H-02：重写区块替换信号，覆盖不重叠的远距离切换。
6. 修复 H-04：补齐所有版本 Mixin 注册及产物级测试。

### P2：消除视觉假象和容量边界

7. 为 8192 快照上限设计磁盘暂存、分段提交或强制人工超时策略。
8. 在预览 UI 明确展示 sampled/stale 状态，或生成多级缩略图。
9. 修复 Velocity 延迟能力重试、`PREDICTED` 来源语义和自动命名唯一性。

## 10. 发布与回滚建议

在 H-01、H-02、H-03、H-05、H-06 完成修复并加入回归测试前，不建议合并到 `master`，更不建议晋级 `release`。

建议把修复拆为独立原子提交/PR：

1. provisional 数据隔离；
2. region 加载与写回完整性；
3. shutdown persistence drain；
4. 切换检测；
5. 跨版本 Mixin 接入；
6. 预览抽样和 freshness 展示。

这些修改涉及历史地图和 profile 数据，回滚时不能只回滚代码。上线前应备份 `client_worlds.json`、trajectory checkpoint 和对应 `.cfr` 目录；如发现污染，应停止继续写盘，回滚应用代码后从备份恢复受影响档案。
