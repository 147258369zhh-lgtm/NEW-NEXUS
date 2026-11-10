# Nexus 核心契约

## 1. 身份模型

```text
SessionId
RunId
TurnId
ItemId
TaskId
GoalId
PlanId
ArtifactId
HostId
PeerId
PluginId
```

规则：

- RunId 由 Nexus 创建，所有入口复用。
- `TaskId` 表示调度/编排实例，不等于 RunId。
- 子 Run 拥有独立 RunId 和 ParentRunId。
- 跨 Host 继续创建新 Run，并保留来源 Session 与 Host 记录。
- 所有 ID 经过长度和字符白名单校验。

## 2. Run 请求

```text
RunRequest
- runId
- source
- sessionId?
- projectId?
- hostId
- mode: Chat|Plan|Goal|MeetingHost|MeetingParticipant|Task|A2A
- prompt/contentParts
- historyRef?
- modelPolicy
- contextPolicy
- toolPolicy
- budget
- approvalScope
```

## 3. Run 状态

```text
CREATED -> ADMITTED -> RUNNING
RUNNING -> WAITING_USER | WAITING_AUTH | PAUSED | VERIFYING
WAITING_USER | WAITING_AUTH | PAUSED -> RUNNING | CANCELLED
VERIFYING -> SUCCEEDED | FAILED | WAITING_USER
RUNNING -> FAILED | CANCELLED | INTERRUPTED
```

每次转换写 Event，后发事件必须拥有更大 sequence。

## 4. Goal

```text
Goal
- id
- sessionId
- projectId?
- objective
- constraints
- completionCriteria
- planId
- state
- softBudget
- hardBudget
- approvals
- lastVerifiedItemId
```

目标属于会话，可显式提升到项目。用户确认最终完成。

## 5. Plan

```text
PlanStep
- id
- title
- description
- dependsOn
- requiredCapabilities
- effectClass
- estimatedCost
- verification
- reversible
```

计划模式只能创建 Plan、Note Revision 和 Reminder Draft，不得修改项目、设置或系统。

## 6. Event 与 Item

每个 Event 至少包含：

```text
eventId
runId
sessionId?
turnId?
itemId?
sequence
type
source
hostId
status
createdAt
durationMs?
payloadRef?
artifactId?
errorCode?
schemaVersion
```

Item 是持久语义投影，例如 UserMessage、AssistantMessage、ToolCall、ToolResult、Approval、ContextCompaction、PlanRevision 和 Artifact。

## 7. ToolDescriptor

```text
ToolDescriptor
- id
- namespace
- shortDescription
- modelDescription
- inputSchema
- outputSchema
- requiredCapabilities
- effectClass
- idempotency
- timeoutMs
- verifierId?
- concurrencyKey?
- replayPolicy
- presentationSchema?
- hostKind
```

模型暴露与 Executor 校验都必须使用同一个 ToolDescriptor。

## 8. CapabilitySnapshot

```text
CapabilitySnapshot
- hostId
- available
- granted
- authorized
- scope
- backend
- reason
- validUntil?
```

执行前重新取得快照，不能只使用 Run 开始时的缓存。

## 9. EffectClass

```text
READ
REVERSIBLE_WRITE
IDEMPOTENT_WRITE
NON_IDEMPOTENT_WRITE
DESTRUCTIVE
EXTERNAL_SIDE_EFFECT
GUI_ACTION
HIGH_RISK
```

恢复策略必须由 EffectClass 和 verifier 决定。

## 10. HostAdapter

```text
HostAdapter
- id
- status()
- capabilities()
- listTools()
- invoke()
- cancel()
- subscribeEvents()
- dispose()
```

Host 实现：NativeAndroid、Linux、MCP、A2A、DSH、Skills。

## 11. SettingsDescriptor

```text
SettingsDescriptor
- id
- title
- valueType
- scope
- allowedValues
- riskLevel
- sensitive
- requiresRemoteApproval
- undoable
- getter
- validator
- setter
```

普通会话 Agent 可通过 SettingsControlPlane 修改；远端 Agent 只能提交请求。

## 12. Secret 契约

- 只提供 `isConfigured`、来源、最后更新时间与不可逆指纹。
- 支持 set、replace、clear。
- 不提供 getPlaintext。
- 不把 Secret 写入普通日志、会话 Item 或诊断包。

## 13. Extension Manifest

```text
ExtensionManifest
- id
- version
- source
- hash/signature
- hostType
- declaredCapabilities
- contributionSlots
- views
- requestedEffects
- compatibility
```

## 14. Plugin Grant

```text
PluginGrant
- pluginId
- hostId
- taskId
- capabilities
- effectLimits
- dataScope
- validUntil
- approvedBy
```

Root 不能由 Agent 自动批准；默认每次任务重新授权。

## 15. WorkspaceBinding

```text
WorkspaceBinding
- projectId
- conversationId?
- hostId?
- inputRoot
- outputRoot
- artifactRoot
- uri/path
- accessMode
- permissionState
```

优先级：Run 临时 > 会话覆盖 > 项目默认 > App 默认。

## 16. PresentationContract

```text
ContentPart
- Text
- Markdown
- Math
- Code
- Image
- Audio
- File
- ToolTrace
- Artifact
- PlanCard
- UserInputRequest
- ApprovalRequest
```

插件仅返回受控 JSON 视图和 Effect，不执行 Compose 代码。
