# N1 审批与暂停生命周期实装

## 结论

本批将本机审批和现有 GUI 用户暂停接入正式 Run 账本。它是 N1 的一组生产接线，N1 整阶段仍为 PARTIAL；恢复事实源未切换。不是新增第二套执行循环，也没有放宽本机高风险确认。

## 生产链路

```text
AgentRuntimeService / NexusRunApplicationApi.openChild
 → 创建本 Run 的 AgentRunController，绑定 NexusExecutionRecorder
 → 根工具执行器 / SubagentExecutor 安装可信 RuntimeInvocationScope
 → NexusExecutionGate / eta_control / ConnectorTool
 → EtaAppControlConfirmation.awaitApproval
 → Controller 审批通知 → SQLite Event + Item + RunState 事务提交
 → 现有本机确认 UI
 → 批准 / 拒绝 / 超时 / 取消 / 记录失败
 → 同一 Item 的解决事件 → 再检查停止、暂停、权限和有效期
```

`RuntimeInvocationScope` 来自运行执行器，模型参数不能设置它。子 Agent 覆盖为自己的 Controller，退出恢复父作用域；同步入口创建的 coroutine context 在切换线程后恢复同一个所有者，防止内层设置确认或连接器确认错记到父任务或另一任务。

关键代码：

- `agent/runtime/AgentRunController.kt`：审批和暂停通知、取消资源、阻塞检查点。
- `agent/runtime/NexusExecutionRecorder.kt`：正式事件、稳定审批 ItemId、Run 状态归并和终态解绑。
- `agent/runtime/RuntimeInvocationScope.kt`：同步所有者作用域与协程传播。
- `agent/control/EtaAppControlConfirmation.kt`：本机确认唯一入口及终态清理。
- `agent/control/EtaAppControlTool.kt`、`agent/connector/ConnectorTool.kt`：保留可信协程上下文。
- `agent/runtime/RuntimeRunRegistry.kt`：实时快照增加 `waiting_for_authorization`。
- `data/repository/NexusExecutionStore.kt`、`NexusExecutionProjection.kt`：审批关联检查和同事务 Item 投影。
- `agent/runtime/GuiControlCoordinator.kt`：先确认 Controller 暂停/恢复提交成功，再发布 GUI 状态。

## 行为与完整性

1. `APPROVAL_REQUIRED` 写成功之后才能出现确认窗口。可见审批 ID 与正式事件关联同一个请求，不保存命令正文、API Key 或确认参数到元数据账本。
2. 批准、拒绝、超时、运行取消、审批异常分别写解决事件。调用方协程被取消与确认窗口自身超时分开处理；不将外层取消误写成审批超时。
3. 拒绝、超时和记录失败都不能获得执行授权。已批准但解决事件写失败时，仍终止调用，保留待核对的持久事实。
4. SQLite 拒绝无请求的审批结果、重复结果、不同工具的结果；存在未解决审批时，Run 不可提交成功终态。
5. 审批 Item 等待时为 WAITING_AUTH；被取消为 CANCELLED。Run 失败或取消时，尚无可靠结果的 Item 仍为 INTERRUPTED，不能假造已批准或已执行。
6. 同一 Run 有多个等待时，解决一个不恢复另一个；子任务审批不改变父任务状态。
7. 原 GUI 用户暂停和恢复写入 `RUN_PAUSED` / `RUN_RESUMED`。同时有审批和手动暂停时，优先显示审批等待；审批结束后仍为 PAUSED，用户恢复后才继续。
8. 暂停记录失败时 Controller 和 GUI 不报暂停成功；终态后移除观察者，迟到通知不能把持久 Run 再改回运行状态。

本批不变更 SQLite 表结构和数据库版本，新增语义事件与现有 v2 表兼容。旧 v1→v2 显式迁移继续保留。

## 验证记录

第一轮扩展回归 20 项中有 1 项失败：新增内层 app-control 测试向空参数 schema 传入 `confirmed`，严格校验在确认窗口之前拒绝请求，测试因此等不到窗口。修正测试为真实合法参数；没有放宽生产 schema，模型伪造 `confirmed=true` 绕过确认的原测试继续保留。原始失败日志/XML 保存于 `build/reports/nexus-baseline/20261110-approval-initial-failure/`。

调整后目标回归：App 8 组、58 项全部通过；Application API 10 项、Kernel API 4 项通过。架构门禁与 Kotlin 编译通过。证据：`build/reports/nexus-baseline/20261110-approval-pause-targeted/`。

覆盖真实组件：本机 Gate、内层 app-control 确认、Dispatcher 切换、真实 Subagent Loop 与独立子 Run、GUI Coordinator、SQLite 事务。模型为受控 Provider，不调用真实云端模型，不操作用户记忆数据；子任务确认用拒绝路径验证隔离。

追加外层取消和审批结果写入失败两项故障测试后，完整 `checkNexusBaseline` 已通过，耗时 3 分 25 秒：

- App：500 组、3,274 项，3,124 通过、150 平台跳过、0 失败/错误。
- Application API：10 项通过；Kernel API：4 项通过。本轮 API 源码已在目标回归实跑；完整回归使用相同源码的有效缓存结果。
- 架构门禁、API 边界与 Kotlin 编译通过；独立身份检查也通过。
- 原始日志/XML：`build/reports/nexus-baseline/20261110-approval-pause-full/`。目标回归证据已单独留存，没有被本次全量输出覆盖。
- ADB 当前没有连接设备，未进行或宣称本机手机验证。Lint 的旧未完成状态没有因基线通过而改为通过。

以上为 L2 自动化接线证据。没有生成/安装 APK、修改手机数据、创建签名身份或执行 Git 写操作。

## 仍需继续

- 用户输入请求与恢复机制、软预算追加/暂停、费用和父子模型轮次总预算尚未完成。
- 所有运行入口、统一权限描述、授权撤销和参数/Host/效果范围仍需逐项接线。
- 审批内容全面展示、过期后重新确认等属于 N2；本批不能代表完整 N2 验收。
- Ledger、Metrics、Checkpoint 和 UI Item 的 20 个真实 Run 对照未完成，`recoveryEnabled=false`。
- 真机取消竞态、进程死亡、锁屏审批及 Android 16/17 验证尚未完成。
