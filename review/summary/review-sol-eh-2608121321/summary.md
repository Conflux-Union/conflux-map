# 客户端子世界检测审计总结

## 1. 审计结论

**结论：阻止合并（BLOCK）。总体风险：High。**

两份审计报告共同确认：当前分支的子世界识别、地图隔离、档案管理和管理 UI 已具备较完整的功能骨架，但数据边界和失败路径仍不可靠。在身份尚未确认、读取未完成、删除恢复失败或客户端关闭等场景下，系统可能把数据写入错误档案、覆盖完整历史、遗漏删除内容或丢失最后状态。因此不建议直接合并到 `master`，更不建议晋级 `release`。

本总结合并来源：

- 功能代码审计：`review/functionality/review-sol-eh-2608121327-func/review-sol-eh-2608121327-func.md`
- PR、资产、兼容性与 UX 审计：`review/assets/review-sol-eh-2608121327-assets/review-sol-eh-2608121327-asset.md`

## 2. 风险总览

| 分级 | 数量 | 处理结论 |
|---|---:|---|
| P0 | 0 | 未发现确定性的全局灾难问题，但存在会造成历史数据污染或丢失的高风险路径 |
| P1 / High | 9 项明确 P1，另有 7 项 High 功能风险 | 合并前必须修复，并为每项增加回归或故障注入测试 |
| P2 / Medium | 15 项 P2，另有 6 项 Medium 功能风险 | 应在合并前处理，或明确限制发布范围 |
| P3 / Low | 8 组维护与一致性问题，另有 1 项命名问题 | 建账拆分为后续维护任务 |

## 3. 合并阻断问题

### 3.1 身份与数据隔离

1. **临时 `provisional` 身份进入正式持久化路径。** `provisional` 与 `RESOLVED` 共用状态，访问记录、路径点、死亡点、注记和地图可能在候选未确认前写入档案；候选被否决后，内存地图仍可能 flush 到错误的 `.cfr`。
2. **同维度远距离切换可能漏检。** 当前位置跳跃和区块替换信号在坐标不重叠时可能同时失效，导致新世界继续使用旧 profile。
3. **快照路由边界不一致。** 捕获链路只检查部分 provisional 条件，可能使 `PROBING`、`WAITING` 或 grace 阶段快照进入当前地图。

### 3.2 持久化、删除与关闭

4. **region 读取队列满时可能覆盖完整历史。** 未完成读取的部分 region 被 flush，可能将旧 `.cfr` 中尚未加载的 chunk 永久写成未知内容。
5. **关闭时可能丢失最后一次 visit、checkpoint 和地图写入。** in-flight 写入没有统一 drain 协议，后续 tick 停止后 pending 状态不会自动提交。
6. **后台任务共用固定 5 秒关闭期限。** worker 消耗完时间后 IO 没有独立预算、重试或未完成告警，最终档案和地图任务可能丢失。
7. **删除前 flush/marker 无超时且失败不 fail-closed。** 慢盘、executor 终止或异常路径可能永久冻结 UI；超时或恢复失败时不应继续移动/删除目录。
8. **删除 profile 遗漏 profile-owned 轨迹 checkpoint。** 地图、预测、结构、路径点和注记虽进入删除事务，含精确位置的轨迹 checkpoint 仍可能留在活跃目录。
9. **删除恢复失败仍可能被标记为成功。** 损坏 journal、部分 move 或恢复异常未返回结构化失败结果，系统可能在数据未恢复时重新开放 profile。

### 3.3 兼容性、配置与发布门禁

10. **配置 schema 从 6 回退到 4。** 现有配置可能被当作 future schema，保存时丢失未知字段；显式 60 秒配置还可能被错误迁移为 3 秒。
11. **四个版本最终产物漏注册 `ChatScreenMixin`。** 1.18.2、1.20.1、1.21.3、1.21.4 的聊天切换命令无法建立精确 profile 锁。
12. **1.20.1 自动连接 smoke 参数不匹配。** CI 可能因此无法证明真实连接，`CANCELLED` 不能视为通过。
13. **当前 CI 未全绿。** Minecraft matrix 为 9 PASS / 1 FAILURE / 2 CANCELLED；1.21.3 连接 smoke 超时，1.20.1 参数问题尚未关闭。

### 3.4 性能与 UX

14. **稳定状态每 3 秒深拷贝并重写整个 registry。** 在接近 4 MiB 的 registry 下，最坏写放大约为 4.7 GiB/小时，并增加主线程分配和 GC 压力。
15. **低高度视口控件越界。** 320×180（例如 1280×720、GUI Scale 4）下 footer 和主操作按钮可能超出屏幕，新建、重命名、解绑和删除无法完成。

## 4. 其他重要风险

- 待识别快照固定上限 8192，淘汰旧记录会造成早期路线缺块；建议按字节预算并提供超时、分段提交或人工接管。
- 超过 2048 个 region 的预览会抽样，但 UI 不提示 `sampled/stale`，容易产生假破洞。
- 列表预览只读落盘 `.cfr`，不合并当前内存地图，最近探索可能显示为空或过期。
- Velocity 能力信号延迟到达时可能永久放弃精确识别；旧 `PREDICTED` 缓存可能被提升为真实缓存。
- profile、checkpoint 和删除 journal 缺少断电级 fsync；原子替换不能保证断电后数据持久。
- 自动名称和手动名称允许重复，增加人工选择歧义。
- 命令页焦点、键盘导航、滚动条命中区、错误换行、Narrator 等价路径仍不完整；工程诊断指标也过多暴露给普通用户。
- 维护成本偏高：策略和反序列化逻辑重复，核心服务、resolver 和选择界面类过大，建议先用 characterization test 固定行为再拆分。

## 5. 已验证与未验证

### 已验证

- `common` 定向测试：101 个用例通过。
- `1.21.1` 客户端定向测试：71 个用例通过。
- 合计 172 个定向测试通过；并发执行造成的 Gradle 结果目录碰撞在顺序 clean run 中消失。
- 已完成 Minecraft 1.21.1 的非破坏性管理 UI 操作：查看 profile、非法命令反馈、重命名入口、二阶段删除确认和当前 profile 删除保护。
- 审计流程图 XML 解析通过，5 页节点均在画布范围内。

### 未验证或未通过

- 未覆盖 provisional 拒绝后的隔离、队列满时 region 完整性、远距离同维度切换、关闭 drain、延迟能力信号和大 IO backlog。
- 未完成全量本地版本矩阵、Qodana、慢盘/磁盘故障注入、性能基准、Narrator 和 320×180 完整交互回归。
- GitHub matrix 仍有 1 FAILURE、2 CANCELLED，不能作为发布通过依据。

## 6. 建议修复顺序与验收

### 阶段 1：数据与状态边界

引入独立 `PROVISIONAL` 状态或统一 `confirmed()` 门禁；隔离 provisional 地图和所有有副作用的 profile 消费者；将轨迹 checkpoint 纳入删除/恢复事务；为 flush、marker、恢复和 executor 拒绝建立结构化失败与 fail-closed 行为；实现可观测的 shutdown persistence drain；未完成 region 读取前禁止覆盖旧文件。

验收重点：候选被否决不写入正式档案；删除/恢复失败不移动目录；所有 pending visit/checkpoint 最终可见或明确报告未保存。

### 阶段 2：版本接入与门禁

将配置 schema 单调升级并保留 future 字段；修正显式配置迁移；补齐四个版本最终 jar 的 Mixin 清单和运行时 smoke；修正 1.20.1 自动连接参数；所有 `CANCELLED` 必须重跑为确定结果。

### 阶段 3：性能、容量与 UX

将 registry 写入改为事件驱动/增量 checkpoint；为 pending snapshot、preview scan 和命令树建立预算；增加 sampled/stale 提示；补充 320×180、键盘、错误换行、焦点保持和 Narrator 验证。

### 阶段 4：维护与发布

在 characterization tests 保护下拆分大类、合并重复策略和反序列化 adapter；完成功能修复后使用 MINOR prerelease，并通过 Feature Flag 或灰度方式发布。

## 7. 发布、风险与回滚

- **当前发布建议：拒绝合并，暂停发布。** 至少完成阶段 1 和阶段 2，且所有 P1 清零、受影响版本真实连接回归通过后，再考虑合并。
- **安全影响：** 审计总结本身不增加权限、网络访问或数据访问；被审计功能存在跨 profile 污染、精确轨迹残留和数据丢失风险。
- **回滚前准备：** 备份 `client_worlds.json`、trajectory checkpoint 及受影响 `.cfr` 目录；保留旧 schema 和 registry 原始字节；关闭子世界检测时不要把未确认数据合并到旧地图。
- **回滚执行：** 停止继续写盘，回退应用/配置/识别逻辑到已知版本；必要时从备份恢复受影响档案。删除 journal 必须支持重复恢复，恢复失败时禁止重新开放目标 profile。
- **版本建议：** 本次仅新增审计摘要，不需要发布应用版本；功能修复完成后建议提升到 `0.2.0-beta.x` 或同等 MINOR prerelease，并记录 schema、迁移和降级策略。

## 8. 归档信息

- 建议分支：`docs/no-ticket-audit-client-subworld-detection`
- 建议提交：`docs(audit): summarize client subworld review`
- 文档变更风险：Low；仅新增总结，不修改运行时代码、配置、测试或 CI。
