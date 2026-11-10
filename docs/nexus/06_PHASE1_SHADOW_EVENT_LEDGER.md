# Nexus 阶段 1：影子执行事件账本

## 1. 目标

阶段 1 在不改变现有 Agent 行为的情况下，把普通聊天、会议室、A2A 和定时任务记录到统一执行账本。

影子模式必须满足：

- 现有 UI、结果、会话和工具顺序不变。
- 账本写入失败不阻塞 Agent。
- 所有入口复用相同 RunSource。
- 可从已有 Metrics、Checkpoint 和 Event 交叉校验。
- 暂不把恢复切换为账本驱动。

## 2. 现有数据来源

- `AgentEvent`：实时模型、工具、压缩、子 Agent 等事件。
- `AgentRunTelemetry`：Token、模型、请求、耗时、trace、children。
- `AgentRunCheckpointStore`：在途事件、transcript、上下文快照。
- `conversation_messages`：用户可见消息。
- `metric_requests`：单模型请求级事实。

阶段 1 只新增旁路账本和投影，不修改这些来源的语义。

## 3. 逻辑表

具体表名在阶段 0 最终冻结；候选结构如下。

### `execution_runs`

```text
run_id             TEXT PK
source             TEXT
session_id         TEXT?
project_id         TEXT?
host_id            TEXT
parent_run_id      TEXT?
mode               TEXT
state              TEXT
started_at         INTEGER
finished_at        INTEGER?
last_sequence      INTEGER
budget_json        TEXT
error_code         TEXT?
schema_version     INTEGER
```

### `execution_events`

```text
run_id             TEXT
sequence           INTEGER
event_type         TEXT
turn_id            TEXT?
item_id            TEXT?
status             TEXT?
created_at         INTEGER
payload_json       TEXT
schema_version     INTEGER
PRIMARY KEY(run_id, sequence)
```

### `execution_items`

```text
item_id            TEXT PK
run_id             TEXT
turn_id            TEXT?
kind               TEXT
status             TEXT
started_at         INTEGER
completed_at       INTEGER?
data_json          TEXT
schema_version     INTEGER
```

### `execution_artifacts`

```text
artifact_id        TEXT PK
run_id             TEXT
item_id            TEXT?
kind               TEXT
uri                TEXT?
sha256             TEXT
bytes              INTEGER
created_at         INTEGER
```

### `execution_checkpoints`

```text
run_id             TEXT
sequence           INTEGER
state_json         TEXT
created_at         INTEGER
PRIMARY KEY(run_id, sequence)
```

这些是逻辑模型，不要求阶段 1 一次建立全部实体。先建立 run/event，再逐步加入 item/artifact/checkpoint projection。

## 4. AgentEvent 映射

| 现有事件 | 账本事件 | 是否创建 Item |
|---|---|---|
| RunStarted | `run.started` | 否 |
| RoundStarted | `turn.started` | 可创建 Turn 投影 |
| ProviderRequestStarted | `model.request.started` | 否 |
| ProviderResponseStarted | `model.response.started` | 否 |
| AssistantBlockStart/Delta/End | `assistant.block.*` | End 可形成 Assistant Item |
| AssistantReceived | `assistant.received` | 可形成 Assistant Item |
| UsageReceived | `usage.updated` | 否 |
| ToolStarted | `tool.started` | 是 |
| ToolFinished | `tool.finished` | 更新同一 Item |
| HostedToolStarted/Finished | `hosted_tool.*` | 是 |
| SubagentProgress | `subagent.progress` | 是或更新 Child Item |
| ContextCompaction | `context.compaction.*` | 是 |
| AccessibilityWaiting | `capability.waiting` | 可形成等待 Item |
| UserSupplementReceived | `user.supplement` | 是 |
| RunFinished | `run.succeeded` | 否 |
| RunFailed | `run.failed` | 否 |

阶段 0 需要补齐的事件：

- 用户输入请求。
- 审批请求与结果。
- 工具权限拒绝。
- Goal/Plan 变更。
- Host 连接与断开。
- 插件权限授予与撤销。

## 5. 单一事件入口

优先在 `AgentEvent` 消费位置增加统一 `ExecutionRecorder`，避免在模型循环和每个工具内手工埋三类重复探针。

建议流程：

```text
AgentEvent
  → Telemetry projection（保留）
  → Checkpoint projection（保留）
  → ExecutionRecorder（新增影子记录）
  → UI event stream（保留）
```

ExecutionRecorder 必须拥有独立有界队列和单写线程。

## 6. 写入规则

- 事件顺序以 Runtime 提供的单调 sequence 为准。
- `(run_id, sequence)` 唯一。
- 不允许把数据库自增 PK 作为执行顺序。
- 工具事件使用 `toolCallId` 关联开始与结束。
- 重试创建事件，不重用已被中断的 sequence。
- 单事件 payload 有大小上限。
- 超限内容写 Artifact，事件只保存哈希和引用。
- 事件写失败记录本地诊断，不向 Agent 抛出。
- 队列溢出时记录 `event_overflow`，不能阻塞模型和工具。

## 7. 数据脱敏

默认不记录：

- API Key、Bearer Token、Cookie。
- Secret 明文。
- 未经授权的完整文件内容。
- 用户隐藏推理。

可记录：

- ID、工具名、状态、错误类别、耗时、Token。
- 参数摘要或哈希。
- Artifact 引用。

## 8. 交叉校验

开发与观察阶段必须比较：

- Run 的模型轮次与 Metrics 的 request/round 数。
- ToolStarted/Finished 与 conversation_messages 的工具顺序。
- Run 总耗时与 Metrics duration。
- Token 汇总与 Metrics usage。
- 子 Agent Run 与 Telemetry children。

任何偏差都记录诊断，不直接修改旧数据。

## 9. 开关与回滚

建议内部开关：

```text
executionLedger.shadow.enabled
executionLedger.capturePayloads
executionLedger.retentionDays
```

生产测试阶段默认只在高频调试会话或选定来源开启。

关闭开关后：

- 不新增账本事件。
- 现有执行、UI、Metrics 和 Checkpoint 不变。
- 已有账本只读，不自动删除。

## 10. 阶段 1 交付顺序

1. 新增契约和 schema。
2. Run/Event 记录器与有界 writer。
3. 在 AgentEvent 旁路映射。
4. Item 投影。
5. Artifact 引用。
6. 只读 timeline reader。
7. 影子模式对照测试。

## 11. 验收

- 普通聊天 20 个 Run 有完整事件。
- 会议室 host/participant Run 可区分。
- A2A 入站和出站 Run 可区分。
- 定时任务 Run 与 TaskId 可关联。
- 账本写失败时任务成功率不下降。
- 关闭开关时零账本写入。
- 原 UI 顺序、工具顺序和输出与改造前一致。
- kill -9 后可看到最后已提交事件；不把它误判为已完成。

阶段 1 完成后仍不切换恢复入口。恢复切换属于后续阶段。
