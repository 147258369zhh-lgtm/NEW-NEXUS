# N1 检查点、Item 投影和独立子 Run 实装

## 当前结论

生产运行已使用独立执行库的检查点和 Item 投影，Subagent 内部 Loop 也通过统一准入 Owner 创建了独立 Run。当前证据仍是 L2，N1 保持 PARTIAL：未切换恢复事实源，没有把可读快照等同于安全续跑。

## 1. 新库 v1→v2 非破坏迁移

- `NexusExecutionStore.VERSION=2`。
- v1 已有 `execution_runs` 与 `execution_events` 保留原样；新增 `execution_items`、`execution_checkpoints`、`execution_checkpoint_chunks`。
- 升级事务按原事件顺序重建 Item，不改写原 EventId 或 sequence。
- 更高版本仍失败关闭，不删除数据库，不破坏性重建。
- 已用真实 v1 表结构与事件进行升级测试，检查迁移后的完整 timeline 相等、工具 Item 状态正确。
- 备份迁移版本清单引用当前库版本，未来 schema 继续在 staging 校验前拒绝。

## 2. Checkpoint 真实接线

```text
AgentRuntimeRunExecutor / SubagentExecutor
 → prepareInput（历史、原始任务、图片省略数量，不含模型密钥）
 → AgentModelClient 的 onTranscript / onContextSnapshot
 → AgentRuntimeSession 的持久化回调 / 子 Run Recorder
 → NexusExecutionRecorder.saveCheckpoint
 → 当前已提交 lastSequence
 → 元数据 + SHA-256 + 分块内容 + Run 游标同一 SQLite 事务提交
```

检查点包含实际输入、增量 transcript 和可选压缩上下文。它不是一个空引用文件；新库重开后能读回同一快照。事件行不含用户正文，私有 Checkpoint 表单独保存恢复所需内容，按既有 ConversationCodec 过滤图片等非持久内容。模型 API Key/配置凭据不进入该快照。

约束：

- 游标不能指向未提交的事件，不能倒退。
- 大快照以 32 Ki 字符块保存，避开 UTF-16 代理对边界；读取逐块验证序号、总长度和 SHA-256。
- 任意块写入/游标更新失败，旧快照与旧游标事务回滚保留。
- 损坏快照会报校验失败，不返回不完整内容。
- `toString()` 只显示哈希与长度，避免无意把私有内容写入诊断日志。
- 图片原图未持久进新快照，诊断标记 `inputImagesRequireReattach`。未来恢复流程必须重新核验附件，不得默认为视觉上下文完整。
- Checkpoint 游标代表“已提交事件位置”，不等同“所有副作用都已验证”。未结算工具另行记录为未知。
- 旧 Checkpoint、正式执行快照仍分库写入；尚未宣称跨库原子性或完成四方对照。

## 3. Item 同事务投影

`NexusExecutionProjection.apply` 在 Event 事务内部运行。工具开始/结果使用相同 ItemId/TurnId，投影开始时间、完成时间和成功/失败/未知状态；Assistant、Compaction、Plan/Goal、Approval 对应已声明的语义类型。

投影失败会回滚事件和 Run sequence，避免“有事件却无投影”。没有结果的工具不会被标成功；失败/取消/中断的 Run 把未完成 Item 标为 INTERRUPTED，仍保留未结算状态。

当前 Item 是元数据投影，不含完整富消息或 ToolResult 正文。`ContentPart` 生产和 UI 持久化仍属于后续 N3，不能凭 Item 表出现就宣称富消息已完成。Plan/Goal/Approval 各生产事件仍需继续接线。

## 4. 独立 Subagent Run

```text
父 Service Session
 → NexusRunApplicationApi.openChild
 → 继承 Host / Project / Task / Goal，生成独立 RunId、ParentRunId 和稳定子会话身份
 → 当前父预算与子预算取上限交集
 → 真实 SubagentExecutor / AgentModelClient Loop
 → 子 Recorder 的事件、Item、输入、transcript、context、终态
```

- 子 Agent 的开始、成功、失败、取消不再只有父消息中的进度 JSON。
- 父/子工具预算使用同一个线程安全 Guard 结算总调用；子任务不能通过独立预算绕过父任务总上限。
- 父预算耗尽以预算错误向子 Controller 传播，不伪装为用户取消。
- 子 Controller 的独立取消仍由统一 API 路由；不取消无关的父任务。
- 取消/失败保留子快照；终态回调释放活动子 Controller；未完成持久 Run 不被隐式重放。
- Subagent 成功计量在正式终态提交后才确认，避免账本失败而统计提前报成功。

集成测试使用可控 Provider 驱动真实 Subagent Loop、工具执行器、SQLite 和检查点，不调用远端模型。它证明生产接线和组件行为；不能冒充真实云端、本地模型或手机设备证据。

## 5. 可读取的证据对照

软件管家 `state.runtime.executionEvidence` 读取正式库与 Metrics：

- 完整页读取事件，检查 sequence、请求数量和 Tool Item 数量。
- 校验私有 Checkpoint 内容格式、Run 身份、游标和哈希；输出只包含校验结果，不包含正文。
- Metrics 缺失、错配、快照损坏和未知副作用分别报告。
- 对照期间 Run 改变会标为待核对，不能给出固定成功值。
- `recoveryEnabled=false`、`fullComparisonComplete=false`，UI 投影对照尚未完成。

该报告为 20 Run 观察建立了真实读取通道，不把一次测试里的记录数量视为 20 次真机运行。

## 6. 验证与证据

- 完成 Checkpoint/Item/独立子 Run 后的完整 `checkNexusBaseline`：BUILD SUCCESSFUL，3 分 29 秒。
- App：497 组、3,257 项，3,107 通过、150 平台跳过、0 失败/错误。
- Application API：10 项通过；Kernel API：4 项通过（未改源码，使用有效缓存结果）。
- 后续补齐输入快照与证据诊断后，相关目标回归通过；完整基线对应上面的稍早快照，不能混称为最后改动后的再次全量测试。
- 原始证据：`build/reports/nexus-baseline/20261110-checkpoint-child/`；后续目标日志：`build/nexus-checkpoint-evidence.log` 与 `build/nexus-execution-evidence-final.log`。
- 本轮没有生成或安装 APK，没有创建 Nexus 正式证书，没有修改用户手机数据，没有执行 Git 写操作。

## 7. N1 仍需继续

1. 将审批等待、软预算暂停、用户输入和恢复状态生产事件完整接入统一 RunState。
2. A2A 出站、插件、夜间整理等其他实际入口逐项接线与核对。
3. 全部父子 Run 的模型轮次与费用预算交集，预算追加/恢复的持久策略。
4. 把新库、旧 Checkpoint、Metrics、UI Item 做真实 20 Run 对照，保存每个差异及异常证据。
5. 为只读、幂等、可验证、外部副作用等建立 verifier 和重放判定。当前仍禁止未知副作用自动重放。
6. 真机进程死亡、重启、断网、权限撤销和长期运行；Lint 和 Release 门禁仍待完成。
7. 上述通过后才能决定恢复事实源切换，而不是在本批测试通过后直接启用。
