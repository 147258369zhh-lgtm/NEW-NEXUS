# Nexus Runtime 深度审计（2026-11-10）

> 基线：`NEW-NEXUS/main@8e7092e`  
> 方式：只读追踪源码、合同生产调用者、现有测试与构建门禁。  
> 结论等级：仅代表审计时证据，修复后必须重新评级。

## 1. 实际调用链

```text
MainActivity / Voice / Meeting / Task / A2A / Xposed entry
  → AgentRuntimeClient / AgentExecutionPort
  → AgentRuntimeService.ingestRunRequest
  → RuntimeRunRegistry.admit
  → AgentRuntimeSession (+ ExecutionShadowRecorder + AgentRunTelemetry)
  → AgentRuntimeRunExecutor
  → AgentModelClient.ToolExecutor
  → RoutingToolExecutor → AgentLocalTools / MCP / Connector
  → AgentRunCheckpointRecorder
  → AgentRuntimeSession.complete
  → AgentRuntimeResultStore / AgentRunArchiveStore / UI recovery
```

## 2. P0 问题

### P0-01：主 Agent Loop 没有本地硬预算

- 代码：`AgentLoop.run()` 使用 `while (true)`，注释明确“不设置本地轮次上限”。
- 当前保护：并发会话上限、单次 Provider 超时与 UI 取消。
- 缺口：模型持续返回工具调用时没有统一的轮次、工具次数、运行时长和费用硬上限。
- 风险：失控循环、重复副作用、持续 Token 消耗、设备长期占用。
- 修复：引入可持久化 `RunBudgetEnforcer`；普通会话使用安全默认值，Goal/Subagent 使用各自声明预算并取交集。
- 验收：模型无限请求工具时可被可控错误终止，账本记录最后安全步骤；软预算暂停、硬预算拒绝。

### P0-02：Kernel Run API 没有生产实现

- 代码：`RunApplicationApi`、Kernel `RunRequest`、`RunState` 只有合同和测试。
- 当前生产路径仍使用 `AgentRuntimeWire.RunRequest`。
- 缺口：入口没有统一映射到 Nexus RunSource、RunMode、HostId、Budget、ApprovalScope。
- 修复：增加生产 Adapter，先桥接现有 Wire request，再逐步替换入口；不重写现有 Runtime。
- 验收：聊天、会议、Subagent、自动化、A2A、语音都生成相同 canonical fields，且保留现有行为。

### P0-03：影子账本不是可恢复事实源

- 代码：`ExecutionShadowRecorder.record` 只写事件 Java 类名；没有 TurnId、ItemId、ToolCallId、状态、Tool 结果关系。
- 缺口：无法重建 Turn、工具调用/结果、审批、压缩和 Artifact；JSONL retention 与正式任务事实混用。
- 修复：稳定 eventId、严格 sequence、typed event、toolCallId 配对、Checkpoint sequence、incomplete run 检测；把调试快照和正式事实分区。
- 验收：普通聊天、会议、Subagent、自动任务、A2A 的 20 Run 可与 Metrics/Checkpoint/UI Item 对照；进程死亡后识别最后安全 sequence。

### P0-04：Capability 只控制激活，未统一控制执行

- 代码：`NexusLazyToolCatalog.activate` 调用 PolicyEngine。
- 缺口：`NexusCapabilityPolicyAdapter` 固定 `allowedTools=*`、`allowedEffects=所有`、`validUntil=MAX_VALUE`；非 A2A 请求把 `highRiskApproved` 固定为 true。
- 执行：`AgentLocalTools.runTool` 仍主要使用旧 Runtime 权限与工具自校验，没有调用同一 Grant。
- 风险：MCP、Connector、A2A、Subagent 或旧调用路径可能绕过 Nexus Policy。
- 修复：每 Run 生成真实 Grant；目录、激活、执行、审计共用；高危审批含范围、参数、Host、过期时间。
- 验收：伪造未授权 Root/终端/删除调用在激活和执行两层均拒绝；撤销权限后下一次调用立即失败。

### P0-05：多个 Nexus 适配器没有生产调用者

- `NexusSettingsControlPlane`：只有实现，没有实例化或业务调用。
- `NexusArtifactLedger`：只有实现，没有实例化或业务调用。
- `HostAdapterRegistry`：只有合同和测试，生产 Runtime 未安装注册表。
- `NexusMigrationValidator`：只有合同和测试，迁移向导没有调用。
- `ResourcePriorityPolicy`：只有合同和测试，没有实际资源调度。
- 修复原则：每项必须先找生产入口，再接 UI；对暂无可靠实现的入口隐藏或标注“未启用”。

### P0-06：ContentPart 与 Presentation Profile 是空转路径

- `AgentMessageUi.contentParts` 默认空列表，生产投影没有非空赋值。
- `NexusContentPartRenderer` 只有空输入时不会出现。
- `LocalNexusPresentationProfile` 只由 Theme 提供，没有布局消费者。
- 修复：Runtime projector 生成 ContentPart；会话 Store 持久化；Classic/Omni 至少首页、聊天、设置和卡片真实消费 Profile。

## 3. P1 问题

### P1-01：普通工具批次串行

普通工具按数组顺序执行；只有 Subagent 批次并行。不能盲目并行所有工具，应先按 ToolDescriptor 的 `concurrencyKey`、EffectClass、依赖和输出引用决定。

### P1-02：工具参数缺少统一依赖 Schema 校验

模型输出 ToolCall 后没有在 Nexus 层统一做 JSON Schema 校验；各工具自行解析参数。需要统一“结构校验 → 权限 → 执行 → 结果验证”。

### P1-03：Retention 分类不完整

当前 JSONL 30 天/200 Run 清理用于影子调试可以接受，但不能扩展到正式 Run、Checkpoint、Artifact、安全审计。需要独立保留策略。

### P1-04：A2A HostTransport 已存在但文档报告不准确

`LanDirectA2ATransport`、`TailscaleA2ATransport` 已由 `A2AInboundService` 使用。后续应修正旧对比文档，并继续完成双节点、重连、去重和高危审批验收。

## 4. 当前可用与不可宣称完成

### 可用

- 现有 Runtime Loop、取消、steer、暂停、Provider 重试与 Checkpoint 主干。
- Lazy Tool Catalog 已进入主会话与 Subagent Loop。
- Goal/Plan/Notepad 持久化和 UI 主干。
- A2A LAN/Tailscale Transport。
- 现有 Metrics、Result outbox 和 UI checkpoint 恢复对账。

### 不可宣称完成

- 唯一执行事实源。
- Android 杀进程后的自动续跑。
- 全入口统一 ToolGrant 执行权限。
- 设置审计全链路、Artifact Ledger、结构化富消息。
- 真正的 Classic/Omni 双 UI。
- DSH Host、全插槽兼容。
- 全双工语音、正式签名发布和跨设备迁移。

## 5. 修复顺序

1. 加入 RunBudget 与硬退出测试。
2. 增加 canonical Run adapter，不替换现有 Wire transport。
3. 升级 Ledger 结构和 20 Run 对照工具。
4. 统一 ToolGrant 的激活与执行检查。
5. 接入 Settings/Artifact/ContentPart/Profile 的生产调用者。
6. 完成 Runtime P0 修复的 Release 与真机异常测试。

## 6. 后续复核发现（保留原始审计结论）

### ARC-007：架构门禁跳过了 App 反向依赖

- `CheckNexusArchitecture.checkArchitecture()` 的 `appSourceRoot` 已经位于 `io/github/mangi/eta`。
- 再调用 `substringAfter("io/github/mangi/eta/", "")` 会返回空字符串，导致 Data、Fork、Runtime 的扫描分支全部跳过。
- 原先的架构检查成功不能证明这些分支被检查。API 模块 import 检查不受此缺陷影响。
- 已修正为相对于配置根目录取首段路径，并用 `agent/runtime/` 识别 Runtime。
- 故意添加 Data→UI import 后，`checkNexusArchitecture` 已失败并定位到探针文件第 2 行；探针随后删除。不是以一次正向绿灯代替该负向验证。
- 当前门禁仍是 import 层检查，不证明所有模块已解耦，也不覆盖字符串反射和所有完全限定名访问。

### LED-007：终态入队失败可能遗留 Writer

- 旧实现用 `queue.offer(STOP)` 结束线程；队列已满时 STOP 丢失，Writer 排空后永久等待。
- 当前修复把关闭信号与有界队列分离；终态保存在保留槽位，先排空事件再写终态。
- 溢出计数、失败类型和最后已 flush 的 sequence 可查询；这仍不是 fsync/数据库事务级持久性保证。

### LED-008：直接取消未通知影子终态记录器

- `AgentRuntimeSession.complete()` 调用 terminalRecorder，而 `cancel()` 原先没有调用。
- 已补齐并测试完成/取消竞态后的唯一终态；取消记录使用控制器状态，不从错误字符串猜测。

### RUN-008：批次中途终止会丢失已完成工具结果

- 旧 `AgentLoop.run()` 只有整个工具批次返回后才把结果写入 transcript。
- 第二个工具触发预算或取消时，第一个工具虽然已经执行，却可能在恢复中被标为未知。
- 当前按已结算结果逐项发布；并行 Subagent 批次在 finally 中保留已结算结果，尚未获得结果的调用继续标记未知，禁止据此自动重放。

该节记录的是新增发现和局部修复，不将原始 N0 审计、N1 主事实源切换或完整 N2 权限闭环标为完成。

### RUN-009：内层审批与手动暂停缺少正式执行事实

- 原确认管理器只有进程内 Pending；Run 账本不知道 app-control/Connector 内层确认属于哪个任务，也没有批准、拒绝、超时和取消的完整事件。
- 原 GUI 用户暂停只改变 Controller 与 GUI 内存状态，未进入正式 RunState。
- 本批由可信工具作用域传播根/子 Controller，确认先持久化再打开 UI；暂停/恢复也先提交事件再发布成功状态。
- 叠加审批和用户暂停时分别保留原因；批准审批不能解除用户暂停。SQLite 拒绝无来源/错工具的审批结果和未解决审批下的成功终态。
- 具体证据见 `25_APPROVAL_AND_PAUSE_LIFECYCLE_2026-11-10.md`；尚不涵盖全部等待状态和 N2 权限范围。

### RUN-010：进程内无工具兜底仍未进入统一 Run API

- `ui/app/AgentRuntimeLocalFallback.run` 直接调用 `AgentModelClient.complete` 并记录独立 Telemetry，未使用正式执行库。
- 它只接受明确未开始执行的交接失败，强制清空工具 schema、Hosted Search 和自定义请求体；Binder 断开和发送失败等结果未知情形不进入兜底。现有测试验证这一保护，不能为合并架构而删除它。
- N1 后续需把此真实入口纳入同一 Application Owner、Run/Item/Checkpoint 与 Metrics 关联，同时保留无工具限制和拒绝未知结果重试；不能把 UI 直接写运行数据库当作完成接线。
- 本批仅记录该额外生产入口，没有修改其行为，也不把全部 RunSource 标为完成。
