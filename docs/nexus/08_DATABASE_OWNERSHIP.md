# Nexus 数据库与数据所有权

第一阶段不合并现有数据库。每个数据库指定唯一写入 owner；跨库关系使用稳定逻辑 ID，禁止依赖另一数据库的自增主键。

| 数据域 | 当前数据库 | 当前版本 | 唯一写入 Owner | 读取边界 | Nexus 目标 |
|---|---|---:|---|---|---|
| 核心会话、消息、分支、MCP、Skills | `eta.db` | 27 | `data/db/EtaDatabase` + Repository facade | UI/Feature 通过 Repository，不得直接 DAO | 保留为兼容 Store；新执行事实迁入 Event Ledger |
| 会话文件夹、归档、扩展 | `eta_fork.db` | 5 | `fork/conversations/ConversationExtensionRepository` | 会话 Feature 通过 facade | 归入 Conversation Store；归档事件可投影到 Event Ledger |
| 会议室 | `eta_meeting.db` | 5 | `fork/meeting/MeetingRepository` | Meeting Feature 与主持人 Runtime | 保留会议室状态；成员执行统一写 Run/Event |
| 记忆、自学习、Skill 经验 | `eta_learning.db` | 3 | `LearningDatabase` 对应 Repository | Memory Review、Nightly Learning | 候选与批准分层；只有审核通过的规则进入长期记忆 |
| 定时任务与触发器 | `eta_task.db` | 2 | `AgentTaskRepository` | Automation Feature、Worker | Task 调度状态；每次触发另建 Run |
| A2A Peer、任务、事件、Artifact | `eta_a2a.db` | 2 | `A2ADatabase` / Repository | A2A Feature 与独立进程 | Peer 白名单和协议任务保留；执行结果投影为 Run/Event |
| 项目与绑定 | `eta_projects.db` | 2 | `ProjectRepository` | Project/File Feature | 保留 ProjectId；文件和 Host Mount 通过 WorkspaceBinding |
| 运行指标 | `eta_run_metrics.db` | 3 | `AgentRunMetricsStore` | Metrics UI 只读 | 作为 telemetry projection，不成为恢复事实来源 |
| 本地模型指标 | `eta_local_model_metrics.db` | 1 | `LocalModelMetricsDatabase` owner | Local Model UI 只读 | 保留资源与性能统计 |
| 通知历史 | `eta_notification_history.db` | SQLite 独立实现 | `AgentNotificationHistoryService` | 只读 Device Tool | 保留脱敏策略与保留期 |
| 外部宿主数据库 | 宿主私有 | 宿主决定 | 宿主进程 | 仅通过 HostAdapter | Nexus 不直接挂载第三方私有数据库 |

## 新增 Nexus 存储边界

阶段 1 计划新增独立的 Execution Ledger store，但执行写入先保持影子模式。其唯一 owner 将是 `ExecutionRecorder` 的串行 writer，UI 通过只读 Timeline Reader 查询。

| 实体 | 唯一 owner | 事实层级 | 是否允许 UI 直接写 |
|---|---|---|---|
| `execution_runs` | ExecutionRecorder | 运行生命周期事实 | 否 |
| `execution_events` | ExecutionRecorder | 不可变追加事件 | 否 |
| `execution_items` | ItemProjector | 语义投影 | 否 |
| `execution_checkpoints` | CheckpointProjector | 安全恢复点 | 否 |
| `execution_artifacts` | ArtifactIndexer | 产物索引 | 否 |

## 跨库规则

1. SessionId、RunId、TaskId、GoalId、ArtifactId、HostId 都是稳定逻辑 ID。
2. 一个实体只能有一个写入 owner；其他域通过用例服务或事件订阅更新自己的投影。
3. 备份清单按数据库、DataStore、文件目录和 schemaVersion 分类记录。
4. 迁移时先在 staging 校验全部数据库和哈希，再原子切换；失败不修改目标应用现有数据。
5. 凭据只能以目标设备 Keystore 重新包裹的 envelope 导入，迁移清单不包含明文密钥。
6. 数据库 schema 变更必须提供前向迁移、失败回滚和备份兼容矩阵。

