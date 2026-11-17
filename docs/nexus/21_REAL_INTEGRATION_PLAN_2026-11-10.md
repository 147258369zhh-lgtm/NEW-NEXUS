# Nexus 实装收口与后续开发计划

> 2026-11-11 用户调整：本文保留为历史需求与审计依据。新的执行顺序、最小必要验证、真人验收分工和正式签名桌面交付以 `F:\eta\new-nexus-snapshot\NEW-NEXUS-main\docs\nexus\30_ETA_REUSE_DELIVERY_PLAN_2026-11-11.md` 为准。用户负责 Android 16/17 真机和长期使用验收，不再要求助手完成原 L4/L5 设备矩阵后才交包。

> 依据：NEW-NEXUS `main@8e7092e` 源码、`Nexus-v0.1.0` 真机/APK 对比文档、仓库内交接与阶段档案、2026-11-10 代码接线审计。  
> 原则：Nexus 是移动端本地优先的 Agent 平台，A2A/DSH/Linux/远端 Host 是扩展能力，不是替代手机内 Agent 的单一方向。

## 1. 实施前审计基线

下表保留制定计划时发现，不能用它覆盖后续修复证据。最新接线与验收状态记录在仓库 `docs/nexus/22_IMPLEMENTATION_PROGRESS_2026-11-10.md`；N1 准入与事务执行库的本轮实现详见 `23_CANONICAL_EXECUTION_STORE_2026-11-10.md`。计划全部目标和阶段门槛保持不变。

Nexus 不是只换了名字，也不是已经完全重构。当前处于“旧 Eta 业务主干 + 新 Nexus 契约和部分适配层”并行阶段。

| 能力 | 当前状态 | 代码证据 | 结论 |
|---|---|---|---|
| 独立安装身份 | 已落地 | applicationId 为 `io.github.zhh_lgtm.nexus`，应用名为 Nexus | L3 源码/构建级完成，正式签名和真机发布未完成 |
| Kernel/Application API | 已落地 | `kernel-api`、`application-api` 独立模块 | L2 合同级完成 |
| 统一 Run API | 未接线 | `RunApplicationApi` 无生产实现，Kernel `RunRequest/RunState` 未进入主 Runtime | L0/L1 |
| 执行账本 | 部分接线 | `ExecutionShadowRecorder` 由 `AgentRuntimeService` 创建 | 只记录事件类名和终态摘要，不能投影、对照或恢复 |
| Lazy Tool Catalog | 部分接线 | 主 Session 和 Subagent 已创建 `NexusLazyToolCatalog` | 工具发现已进入主链路 |
| ToolGrant/EffectPolicy | 部分接线 | activation 使用 `NexusCapabilityPolicyAdapter` | Grant 固定 `allowedTools=*`、`allowedEffects=所有`、永不过期；执行器未使用同一授权 |
| SettingsControlPlane | 未接线 | `NexusSettingsControlPlane` 无生产调用方 | 审计/回滚 UI 与 Agent 尚未走 Nexus 控制面 |
| ArtifactLedger | 未接线 | `NexusArtifactLedger` 无生产调用方 | Artifact 索引仍是旧 WorkspaceArtifactStore |
| HostAdapterRegistry | 未接线 | 只有应用合同和测试 | MCP/A2A/Linux/DSH 未进入统一 Host 生命周期 |
| A2A HostTransport | 部分完成 | `LanDirectA2ATransport`、`TailscaleA2ATransport` 已由 `A2AInboundService` 使用 | 对比文档中“没有任何 HostTransport 实现”的判断已过时；跨设备验收仍缺 |
| ContentPart | 界面有渲染器，生产链未接 | `NexusContentPartRenderer` 被聊天组件调用，但 `AgentMessageUi.contentParts` 没有非空赋值 | 实际运行仍以纯字符串消息为主 |
| Classic/Omni Profile | 只有上下文注入 | `LocalNexusPresentationProfile` 只由主题提供，无页面布局消费者 | 没有真实双 UI |
| Goal/Plan/Notepad | 部分真实完成 | 有持久化 Store、模式切换、输入区和工具 | 已接主 UI，但仍需与统一 Run/恢复接线 |
| VoiceStateMachine | 部分接线 | `LocalVoiceSessionCoordinator` 使用 canonical 状态 | 全双工、音频仲裁和资源让位仍缺设备证据 |
| ResourcePriorityPolicy | 未接线 | 只有合同和测试 | 前台对话不会据此实际压制后台任务 |
| Migration Validator | 未接线 | `NexusMigrationValidator` 无生产调用方 | 当前向导只是调用现有 Eta 备份导入，不是结构化 Nexus Migration |
| DSH | 只有清单校验 | 无 DSH Host、Effect/Invoke/View 执行桥和 UI 插槽 | 不能宣称 DSH 兼容 |
| 自动化/记忆/技能/本地模型 | 承接 Eta 基线 | 业务代码存在 | 尚未全部通过统一 Run、权限和资源策略重构 |

## 2. 完成等级

沿用仓库定义：

- L0：设计完成。
- L1：源码实现完成。
- L2：自动测试通过。
- L3：Release 构建、签名和包元数据通过。
- L4：目标真机端到端通过。
- L5：长时间、重启、进程死亡和异常恢复通过。

以后不再用“页面存在”“接口存在”“文件夹存在”表示完成。普通功能至少 L4；Root、终端、迁移、A2A、DSH、语音和恢复能力至少 L5。

## 3. 总体顺序

```text
N0 只读 Runtime 审计、基线冻结与验收夹具
 → N0.5 Nexus Orchestrator 职责边界
 → N1 统一执行主干与 Event Ledger
 → N2 统一权限与工具执行
 → N3 设置、工件、结构化消息与真实 UI Profile
 → N4 聊天/会议/子 Agent/自动任务全面回归
 → N5 记忆、自学习、自动任务与本地模型闭环
 → N6 Host：MCP/Connector/A2A/Linux/DSH
 → N7 语音全双工与资源调度
 → N8 迁移、备份、签名与正式发布
 → N9 Android 16/17、ColorOS 17 长时间回归
```

每两个批次完成后生成一个正式构建候选；先自动测试与签名校验，再按用户安排安装真机。

## 4. N0：只读 Runtime 审计、基线冻结与可重复验收

该批次先审计、不修改 Runtime。必须用源码、调用链、测试和故障注入证明现状，禁止边审计边改代码掩盖问题。

### Runtime 调用链审计

至少完整追踪：

```text
MainActivity / Voice / Meeting / Task / A2A
 → AgentExecutionPort / Runtime Client
 → AgentRuntimeService
 → AgentRuntimeSession
 → AgentRuntimeRunExecutor
 → AgentRunController
 → AgentModelClient / Model Router
 → AgentLocalTools / MCP / Connector / GUI / Root
 → Checkpoint / Metrics / Shadow Ledger / UI projector
```

审计必须回答：

- `while` 循环是否有最大轮次、工具次数、时间和费用硬上限。
- 暂停、恢复、取消、插话、等待输入、等待审批、超时和重试是否贯穿模型请求、工具批次与 Subagent。
- 工具参数是否执行前统一 JSON Schema 校验，权限是否执行前再次读取 CapabilitySnapshot。
- 多个工具是否按依赖并行，失败和部分成功如何返回。
- Activity 销毁、进程死亡、手机重启、Doze、断网和前台服务结束时状态如何变化。
- 旧 Checkpoint、Metrics、Shadow Ledger、UI Item 的关联与冲突处理。

审计输出必须包括：问题编号、严重度、文件/方法/行号、调用链、复现方法、影响、修复方案、回滚和验收方式。

### 工作

1. 固化 `NEW-NEXUS/main` 为唯一产品仓库；旧 Eta 只读。
2. 建立四类自动验收夹具：
   - 普通聊天 → 会议室 → Subagent → 自动任务 → A2A 的统一 Run 链。
   - 本地工具 → MCP → Connector → GUI/Root 高风险工具。
   - 设置变更 → 审计 → 回滚。
   - 备份 → 迁移 → 重启 → 数据一致性。
3. 清理文档冲突：迁移向导、Release、代码规模和当前完成状态的描述只保留一个事实源。
4. 将 `Verify Nexus identity`、架构检查、合同测试、Runtime tests、Lint 组成 PR 必需门禁。
5. 建立每个能力的 L0-L5 状态清单，PR 必须更新状态和证据链接。
6. 对静态代码做“孤儿新架构”审计：找出接口存在但无生产调用方、重复事实源和绕过新架构的旧路径。

### 验收

- 从干净 checkout 可一条命令完成身份、架构、核心合同、Runtime 目标测试和 Debug 编译。
- 文档不再把未接线接口标为“已完成”。
- 普通会话、会议室、子 Agent、自动任务、A2A 都有稳定的测试入口。
- Runtime 审计报告没有“无法验证”项被当作完成；P0 问题全部有明确修复与验收方案。

## 4.1 N0.5：Nexus Orchestrator 职责边界

不新增第二套 Runtime。把已有职责收敛为明确边界：

```text
Nexus Orchestrator
 → 选择 RunMode / Agent / ModelPolicy
 → 请求 CapabilityDirectory 与 ToolGrant
 → 请求 Resource Manager 准入
 → 提交统一 RunRequest
 → Execution Kernel
    → Agent Runtime Loop
    → Model Router
    → Tool Runtime
    → HostAdapter
 → Run/Turn/Item/Evidence 汇总
```

职责规则：

- Orchestrator 负责协调和决策，不直接执行工具。
- Execution Kernel 负责状态机、预算、事实记录和恢复。
- Agent Runtime 负责模型与工具循环，不充当历史数据库。
- Host 负责连接环境、发现能力、执行和回传证据，不复制任务规划逻辑。
- Model Router 只负责模型选择与请求适配，不拥有工具权限。

验收：普通聊天、会议、Subagent、自动化、A2A、语音均经同一 Orchestrator 入口；不存在并行第二套调度器。

## 5. N1：统一执行主干

### 目标

让所有 Agent 入口真正使用同一套 Run/Turn/Item 语义，而不是只共享旧 `AgentRuntimeWire.RunRequest`。

### 代码落点

- 新增 `NexusRunApplicationApi`，实现 `RunApplicationApi`。
- 在 `AgentRuntimeWire.RunRequest` 与 Kernel `RunRequest` 之间建立单向适配；旧入口逐步迁移。
- 修改 `AgentRuntimeService`、`AgentRuntimeRunExecutor`、`AgentRuntimeWire`，统一生成 RunId、SessionId、HostId、RunMode、RunSource、Budget 和 ApprovalScope。
- 将聊天、会议室主持/参与者、Subagent、自动任务、A2A 入站/出站、语音和夜间整理全部映射到明确的 RunSource。
- 扩展 `ExecutionShadowRecorder` 为持久 `ExecutionLedgerStore`，写入真实的 Run 状态、Turn、ToolCall/ToolResult、Compaction、Approval、Artifact 和终态。
- 建立 Item projector，不记录正文中的敏感内容；正文只写会话 Store，并以稳定 ItemId 关联。
- 增加恢复游标、幂等键、crash loop 上限和 EffectClass 驱动的恢复策略。

### Event Ledger 完整性不变量

1. Run 内 sequence 严格递增；稳定 EventId 用于写入去重。
2. ToolCall 与 ToolResult 通过稳定 toolCallId 一一关联；未知结果单独标记为 uncertain。
3. 业务副作用与事件提交定义一致性策略；不能把“账本写失败”误判为“工具未执行”。
4. 断电或进程死亡后可识别 incomplete Run；最后已提交 sequence 与 Checkpoint sequence 必须一致。
5. 恢复前重新检查实际副作用、权限、Host 和幂等键，禁止仅凭事件类型自动重放。
6. 正式执行事件、Checkpoint、Artifact、安全审计与临时诊断日志使用不同 retention，不共享 30 天/200 Run 清理策略。
7. 正式执行事实达到用户保留策略后才可清理；恢复所需 Checkpoint 在安全关闭前禁止淘汰。

### 验收

- 每个入口都能在账本中看到相同 schema 的 Run/Turn/Item。
- 普通聊天、会议、子 Agent、自动任务、A2A 的 RunSource 可区分且父子关系正确。
- `kill -9` 后能识别最后安全 sequence，并拒绝自动重放非幂等操作。
- 连续 20 个真实 Run 的 Ledger、Metrics、Checkpoint 和 UI Item 零差异。
- 20 Run 对照通过前，Ledger 只读观察，不切换恢复主事实源。

## 6. N2：统一权限、工具暴露与执行

### 目标

消除“模型能看到工具”和“执行器实际允许工具”两套策略。

### 代码落点

- 统一 `NexusCapabilityDirectory`、`NexusLazyToolCatalog`、ToolDescriptor 和实际工具 Registry。
- 修复 `NexusCapabilityPolicyAdapter` 中永不过期的 wildcard Grant。
- 由用户设置、Agent/Subagent 配置、远端来源、当前 CapabilitySnapshot 和 RunBudget 生成真实 ToolGrant。
- `AgentLocalTools.beforeToolExecution`、`RoutingToolExecutor`、MCP、Connector、GUI、Root、Terminal、A2A 执行前统一调用同一 PolicyEngine。
- Root、终端、外部提交、安装插件、A2A 高危操作必须每次本机确认；Agent 的确认不能替代用户。
- 加入工具循环上限、超时、并发键、重试和 verifier。
- 工具执行结果增加结构化 Effect 结果、敏感标记、Artifact引用和可重放判定。

### 验收

- 未授权工具不会出现在模型 Schema；伪造工具名/参数在激活和执行两处均被拒绝。
- 普通会话、会议室、Subagent、MCP、A2A 使用相同 Grant 语义。
- 高风险工具没有本机批准时永不执行。
- 权限撤销、过期和 Capability 变化在下一次执行前重新生效。
- 审批必须包含实际命令、路径、Host、副作用、预算和过期时间；审批过期后重新询问。

## 7. N3：设置、工件、结构化消息与真实 UI Profile

### 工作

1. SettingsControlPlane：
   - 实例化并注册 `NexusSettingsControlPlane`。
   - 设置 UI 与 Agent 修改都走同一控制面。
   - 覆盖模型、备用模型、记忆、外观、MCP、Skill、自学习、自动任务、语音和 Host 设置。
   - 所有变更有 actor、before/after、auditId、撤销入口。
2. Artifact Ledger：
   - 实例化 `NexusArtifactLedger`。
   - 文件工具、终端、浏览器、项目目录、备份和 Host 产生的文件统一登记。
   - 会话文件页和项目页读取 Ledger，不另建事实源。
3. ContentPart：
   - 在 Runtime projector 和 `AgentConversationStore` 中真正填充 `contentParts`。
   - 文本、Markdown、公式、代码、图片、音频、文件、ToolTrace、PlanCard、UserInput、Approval 全部持久化并可恢复。
   - 普通会话和会议室使用同一渲染器。
4. Presentation Profile：
   - 让导航、首页、聊天、会议室、设置和卡片真正消费 `LocalNexusPresentationProfile`。
   - Classic 与 Omni 共享职责和状态，只分离视觉与布局。

### 验收

- 任意设置可由 Agent 修改、被用户回滚，并能在审计中还原变更前后值。
- 任何产出文件都能从 Run/会话/项目三个入口查到同一 ArtifactId。
- 结构化消息重启后保持同构渲染，会议室与普通会话一致。
- 切换 Classic/Omni 后布局真实变化，功能入口完整一致。

## 8. N4：聊天、会议、子 Agent 与自动任务回归

### 工作

- 普通对话和会议室统一使用 Lazy Tool Catalog、实际 ToolGrant 和同一 Run 状态机。
- 修复多图处理：发送前缩放/重编码/预算控制；压缩阶段图片转占位符。
- 上下文压缩失败可部分成功、可取消、可降级，不把整轮回复判死。
- 模型请求参数按 Provider 能力生成，避免空内容、工具角色、思考参数和图片格式导致 HTTP 400。
- 会议室主持人、参与者、并行、工具调用和结果汇总全部进入同一账本。
- 子 Agent 权限、预算、模型和工具取交集；内部轨迹在 UI 与账本中一致。
- 自动任务每次触发创建新 Run，并验证工具、通知、重试和恢复。

### 验收

- 普通会话、会议室、子 Agent 对同一任务能调用同类工具。
- 6 图消息、长上下文压缩、缓存、重试和备用模型均有成功路径。
- 会议室并发不串消息、不重复扣费、不丢掉工具结果。
- 自动任务触发后可在历史中还原 TaskId→RunId→Turn→Tool→Artifact。

## 9. N5：记忆、自学习、自动任务与本地模型闭环

### 工作

- 记忆按会话、项目、全局分层，提取候选、去重、冲突、批准、试用、版本和淘汰都从真实 Run 证据产生。
- 自学习不只保存文本：形成 Skill 必须有来源、步骤、工具、输入输出、适用范围、验证记录和版本。
- 自动任务支持时间、连接、位置、电量、文件、系统、任务链和交互触发；每个触发都有权限、重复抑制、耗电和真实调度日志。
- 本地模型文件下载/导入、CPU/GPU/NPU、回退、卸载、崩溃恢复和指标全部走 Model Resource Manager。
- ASR/TTS/视觉/Embedding 按能力接入，不伪装成聊天模型。
- 接入 `ResourcePriorityPolicy`：前台对话 > 用户任务 > 夜间整理 > 插件预热 > Linux 空闲服务。

### 验收

- 删除记忆、回滚 Skill 和禁用自动任务会影响下一次真实 Run，而不只是界面变化。
- 自动生成的 Skill 未验证前不能成为稳定规则。
- 本地模型首次加载、取消、崩溃、超时卸载和 CPU 回退均可恢复。
- 前台对话期间后台任务真实让位，用户可见资源状态。

## 10. N6：MCP、Connector、A2A、Linux 与 DSH Host

### 工作

- 在生产 Runtime 初始化 `HostAdapterRegistry`，注册 Native、MCP、Connector、A2A、Linux 和 DSH。
- MCP 的 `hostAdapters()` 真正注册，工具发现、Grant、执行、关闭和故障隔离共用统一 Host 生命周期。
- A2A 保留已经存在的 LAN/Tailscale HostTransport，完成双节点、Token 轮换、锁屏、后台和重启测试。
- Connector 不直接绕过 ToolGrant；OAuth/Secret 只写不可读。
- Linux Host 使用 WorkspaceBinding，项目目录默认只读；CPU、内存、数量有资源预算。
- DSH 在统一 Host 完成后实现：清单安装、独立进程/沙盒、Effect/Invoke、View 插槽、流式事件、媒体/语音/Overlay 和撤销。
- 第三方插件不在 UI 主进程执行，失败只撤销自身权限和资源。

### 验收

- 所有 Host 在 Capability Center 显示真实状态、工具、权限和最近错误。
- 同一任务可以组合 Native+MCP+Connector，但每次执行都经过相同 Grant。
- DSH 全部插槽未通过前，UI 不显示“完整兼容”。
- 两个真实 A2A 节点完成双向调用、断线重连和高危本机确认。

统一 Host 生命周期为：

```text
DISCOVERING → CONNECTING → READY → BUSY → DEGRADED → STOPPED / FAILED
```

每个 Host 必须有心跳、健康检查、超时、熔断、并发上限、资源配额、取消、强制回收、断线重连和跨提交去重。MCP、A2A、Linux、DSH 的故障不得改变主 Agent 的 Run 状态。

## 11. N7：语音全双工与资源调度

### 工作

- 将 VoiceStateMachine 从 LocalVoiceSessionCoordinator 扩展到云端和本地 ASR/TTS 全链路。
- 麦克风、播放、AEC、VAD、打断、超时关闭和设备释放统一由 AudioOwner 管理。
- 用户说话可以中断 TTS，同时 Agent 可继续执行工具和更新 UI。
- 音频模型按优先级加载和回收；TTS 最后保证，ASR 次之，文本主 Agent 最高。
- 锁屏、息屏、蓝牙耳机、通话抢占、后台麦克风限制和崩溃恢复纳入真机矩阵。

### 验收

- 连续对话、打断、重新收听、工具执行和播报可重复 30 分钟无死锁。
- 任一模型启动/停止失败都能在超时后释放资源。
- Android 16/17、ColorOS 17 的前台服务和麦克风状态与界面一致。

## 12. N8：迁移、备份、签名与发布

### 工作

- 将 `NexusMigrationValidator` 真正接入迁移向导，区分 Eta 备份导入和 Nexus 结构化迁移。
- 迁移包包含来源版本、schema、哈希、大小、14 类内容、凭据加密和目标 Keystore 重包裹。
- 预览 → staging → 校验 → 原子激活 → 重启，任何失败保持 Nexus 原数据。
- 备份覆盖新增的执行账本、Goal/Plan/Notepad、Host、Artifact 和模型配置。
- 建立 Nexus 独立正式签名身份并配置 GitHub Actions Secrets；禁止继续把未签名 Release 当发布包。
- 所有 PR 与 Release 自动核验 applicationId、versionCode、签名、debuggable、targetSdk 和数据库版本。

### 验收

- 旧 Eta 4.11.8 → Nexus 完成换机、密码、部分内容、失败回滚四类恢复演练。
- 升级安装保留 Nexus 数据；与 Eta 可以并存，不互相覆盖。
- Release APK 通过 v2/v3 签名验证且 `debuggable=false`。

## 13. N9：平台兼容、UI 精修与长期稳定性

### 工作

- Android 16、Android 17、ColorOS 17、OPPO Find X7 Ultra 全量真机矩阵。
- 无障碍健康、系统助手注册、后台、Doze、划掉任务、重启、锁屏、通知、SAF、Root 和高风险授权。
- 所有一级到四级页面按能力中心规范整理：字体、图标、间距、材质、状态、空态、错误、弹窗层级和返回导航。
- 禁止解释性小字堆叠；低频设置进入二级；所有状态显示真实来源。
- 关键页面至少覆盖 360dp、412dp、600dp、浅色、深色、纯黑、大字体。

### 验收

- 普通聊天、会议室、设置、能力中心、模型、备份、记忆、自学习和语音没有死入口。
- 无弹窗层级错位、返回栈断裂、重影、重复卡片或状态虚报。
- 前台对话 30 分钟、后台任务 2 小时、重启恢复和进程死亡恢复全部通过。

## 14. 交付批次

| 候选版本 | 包含批次 | 必须演示的真实链路 |
|---|---|---|
| `0.2.x` | N0–N1 | 任意入口进入统一 Run；20 Run 账本对照；进程死亡恢复报告 |
| `0.3.x` | N2–N3 | 工具搜索/激活/授权/执行同一策略；设置审计回滚；Artifact 与 ContentPart 真正落库 |
| `0.4.x` | N4–N5 | 聊天/会议/子 Agent/自动任务统一工具调用；记忆到 Skill 的验证闭环；本地模型资源调度 |
| `0.5.x` | N6–N7 | MCP/A2A 组合任务；双节点联调；全双工语音和打断 |
| `0.6.x` | N8–N9 | Eta 迁移、正式签名、Android 16/17 长时间真机验收 |

## 15. 每批开发的硬规则

1. 先定义接口和生产调用方，再写 UI。
2. 每项功能必须列出输入入口、执行者、持久化、权限、失败恢复、日志和 UI 状态。
3. 同一个事实只能有一个 Owner；UI、Agent 和备份不得各自复制状态。
4. 工具 Schema、激活 Grant、执行 Grant 和审计使用同一 ToolDescriptor。
5. 第三方插件不得运行在 UI 主进程。
6. Root、终端、迁移、外部提交、插件安装和高风险 A2A 每次本机确认。
7. 新增设置必须同时定义 sensitive、risk、audit 和 rollback。
8. 新增 Run 必须同时写 Metrics、Ledger、Checkpoint 和 UI Item 的关联 ID。
9. 新增 Host 必须实现统一 Contract Test、超时、取消、故障隔离和状态 UI。
10. 未完成 L4/L5 的能力不得在 UI 或 README 中写成“已支持/完整兼容”。

## 16. 立即可执行的第一批

建议从以下顺序开始，不先改视觉：

1. 实现 `NexusRunApplicationApi`，把普通聊天与会议室先接入 Kernel Run 语义。
2. 扩展 Ledger 为结构化 Turn/Item/Tool 事件，并增加 20 Run 对照报告。
3. 用真实 ToolGrant 替换 wildcard Grant，在执行器前增加统一 Gate。
4. 实例化 SettingsControlPlane 和 ArtifactLedger，打通两条当前完全断开的生产链。
5. 在 Runtime → UI projector 中生成 ContentPart，让统一渲染器不再空转。
6. 完成第一批故障注入测试：Provider 400、工具拒绝、压缩失败、进程死亡、设置回滚、Artifact 丢失。

编码前先提交 N0 只读 Runtime 审计和调用链报告。只有审计确认的 P0 缺陷和 N0.5 边界进入 N1 修改范围；不得以“重构”为名扩大改动面。

这一批完成后，Nexus 才第一次具备“新架构在真实任务中运行”的证据，之后才进入 Host、DSH、双全工语音和双 UI 扩展。

## 17. GPT 评估报告已纳入

2026-11-10 已取得本地导出 `ChatGPT-评估Eta改造计划.md`。该报告作为评估依据，其中示例提示词、定时跟踪建议和只读审计模板不等于新的用户操作指令。

采纳：Runtime 优先、先记录审计基线再修复、事件完整性与副作用核验、Checkpoint 关联已提交 sequence、调试/执行/审计 retention 分开、统一 Orchestrator 但不新增第二个 Agent Loop、统一权限执行检查、Host 故障隔离，以及 L0-L5 和 PASS/PARTIAL/FAIL/未验证的证据矩阵。

30 项用户验收 U01-U30、十种账本故障注入、软件供应链与 Release 检查纳入 N0-N9 的验收清单。模拟 Provider、JVM 测试、源码字符串和截图夹具不能替代真实手机、真实对端、真正的恢复与长时间运行证据。

## 18. 实施证据记录规则

- `docs/nexus/20_RUNTIME_AUDIT_2026-11-10.md` 保留审计时发现，后续修复不得删除原问题。
- 本计划在仓库内同步为 `docs/nexus/21_REAL_INTEGRATION_PLAN_2026-11-10.md`。
- `docs/nexus/22_IMPLEMENTATION_PROGRESS_2026-11-10.md` 持续登记当前改动、测试证据、未完成项和下一步。
- 所有阶段仍以其全部验收项为准；一个子任务通过测试只更新该子任务，不自动关闭整阶段。

## 19. 执行节奏与当前优先修复

按用户最新要求，完整审计只执行一遍并保留原始基线；后续直接实装缺项，只对当前修改进行必要的定向检查和测试，不重复启动全项目审计。N0–N9 的范围、证据标准和发布门槛不缩减。

会议室“主持人代做全部工作、不分派参与者”的实际故障优先修复：分工、成员执行、汇总必须分开；主持人规划/汇总不能获得执行工具，分工失败不能静默改为主持人代做；明确 @ 主持人也应进入分工，@ 其他成员继续指定执行者。修复后回到统一 Run、权限和剩余功能接线。

用户新增进度要求：工作期间每半小时在当前聊天报告已做、未做及剩余情况，每个整阶段完成时另行报告。此前“还剩 85%”没有可靠的工作量分母，不作为正式进度指标；报告必须区分代码接线、测试通过、Release 和真机验收。剩余开发量无可靠权重时直接说明，不能用目录/文件/测试数伪造百分比。实施按完整功能链合并批次，仅做必要定向回归并保存简要证据。
