# Nexus 入口与 RunSource 映射

本文件冻结第一阶段的目标映射。当前代码尚未全部接入 `ExecutionRecorder`；阶段 1 只能在旁路记录，不能先改变任何入口的行为。

## 统一原则

每次模型执行必须拥有明确的 `SessionId`、`RunId`、`HostId`、`RunMode`、`ToolPolicy` 和 `RunBudget`。用户消息、会议室分派、Subagent、A2A、定时任务、夜间整理与插件调用都是不同 `RunSource`，不能各自维护互不兼容的状态机。

| 产品入口 | RunSource | 默认 RunMode | Session 规则 | Host 规则 | 工具与预算 | 当前基线入口 |
|---|---|---|---|---|---|---|
| 普通聊天 | `CHAT` | `CHAT` | 每个用户可见会话一个 Session | 显式绑定 Native Android；可创建到远端 Host 的新 Run | 当前会话工具策略；前台预算优先 | `MainActivity` → `AgentExecutionPort` → `AgentRuntimeService` |
| 目标模式 | `GOAL` | `GOAL` | 会话主 Run 之外最多一个后台 Goal Run | 创建时固定 Host；跨 Host 继续必须新建 Run | 软预算暂停；硬预算不可跨越 | 新契约，尚未接入 |
| 会议室主持人 | `MEETING_HOST` | `MEETING_HOST` | 与 Meeting Room 一对一 | 主持人绑定配置中的模型和 Host | 可派发成员任务、汇总，不直接冒充成员 | `MeetingRuntime` 主持路径 |
| 会议室成员 | `MEETING_PARTICIPANT` | `MEETING_PARTICIPANT` | 继承 Meeting Room，拥有独立参与者上下文 | 每个成员可绑定不同 Host/模型 | 只接收主持人分派摘要和必要文件 | `MeetingRuntime` 参与者路径 |
| Subagent | `SUBAGENT` | `TASK` | 必须设置 `ParentRunId`，可有独立 Session 或上下文快照 | 每个 Agent 单独配置模型与 Host | 权限取父 Run、Agent 配置和用户上限的交集 | `AgentRunTelemetry.children` 与子 Agent 运行路径 |
| A2A 入站 | `A2A_INBOUND` | `A2A` | 每个远端任务映射稳定本地 Task/Session 关联 | A2A 独立进程只通过 Runtime API 发起 Run | 按对端 token/节点白名单；高危逐次本机确认 | `A2AInboundService`、`A2AInboundTaskCoordinator` |
| A2A 出站 | `A2A_OUTBOUND` | `A2A` | 发起会话保留本地 Run，远端 Task 使用关联 ID | 远端 Peer 为独立 Host | 远端不可读取本地凭据 | `A2AHttpClient` 与任务协调路径 |
| 定时任务 | `AUTOMATION` | `TASK` | 每次触发产生独立 Run，可关联 TaskId | 在任务中固定 Host 与模型策略 | 后台预算和系统资源限额 | `AgentTaskWorker`、`AgentTaskRuntimeRunner` |
| 夜间记事本整理 | `NOTE_CONSOLIDATION` | `TASK` | 关联会话或项目，不污染普通聊天时间线 | 默认本地模型；无本地模型时遵循数据授权 | 仅记事本、计划和提醒草稿；重要变更待确认 | 新契约，尚未接入 |
| 插件任务 | `PLUGIN` | `TASK` | 插件调用属于宿主任务，不创建无主 Session | Sandbox Host；通过声明式调用进入 | Grant 与当前任务同寿命；失败只隔离插件 | 新契约，尚未全部接入 |

## 生命周期要求

1. 入口先生成 `RunRequest`，再完成 `CREATED → ADMITTED → RUNNING`。
2. 工具白名单在进入模型上下文前计算，执行前再次获取 `CapabilitySnapshot`。
3. UI 只订阅 Item/Event 投影；入口不得直接写消息表和运行指标表。
4. 子 Run 与跨 Host Run 必须保留 `ParentRunId`，但不得复用父 Run 的 sequence。
5. 进程死亡后的自动恢复只处理只读、幂等或有 verifier 的步骤。
6. A2A、定时任务和夜间任务的会话可见性必须明确，默认不插入主聊天时间线。

## 第一阶段核对结论

- 现有 `AgentExecutionPort` 已提供统一入口方向，应作为阶段 1 的首个适配点。
- A2A 独立进程不能持有第二套执行逻辑，只能通过稳定的 Runtime Client 发起 Run。
- 会议室主持人和成员必须记录为不同 RunSource，避免主持人的协调成本混入成员模型统计。
- 定时任务拥有自己的 TaskId，但每次触发仍需新的 RunId。

