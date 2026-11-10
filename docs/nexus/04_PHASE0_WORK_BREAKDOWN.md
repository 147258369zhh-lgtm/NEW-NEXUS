# Nexus 阶段 0 工作分解

阶段 0 只做基线、契约、迁移设计和依赖规则，不改变现有执行行为。

## A. 产品身份与安装隔离

### N0-A01 冻结 Nexus 身份

输入：

- 旧包 `fuck.andes`。
- Kotlin namespace `io.github.mangi.eta`。
- 新产品名 Nexus。

输出：

- 最终 applicationId。
- 用户可见 label。
- 通知、FileProvider、A2A、备份和导出的产品命名。
- 独立数据目录与兼容边界。

验收：

- Nexus 与 Eta 可共存安装。
- 不依赖旧应用私有目录。
- 所有用户可见品牌名称一致。

### N0-A02 迁移包协议

输出：

- `nexus-migration` 清单版本。
- 来源 Eta 版本和数据库版本。
- 条目哈希、总大小和加密参数。
- 会话、会议、项目、记忆、学习、任务、配置和凭据分类。
- 大型模型/Linux/DSH 可选包引用。

验收：

- 无 Root 时可通过 SAF 导入。
- 迁移包损坏不会改变 Nexus 现有数据。
- 凭据重新绑定目标设备 Keystore。

## B. 依赖与入口清单

### N0-B01 模块依赖图

检查：

- UI -> Data/Runtime 的直接引用。
- Runtime -> Activity/UI model 的反向引用。
- Data -> UI model 的反向引用。
- Feature 之间的直接引用。
- MCP/A2A/Task/Meeting 对 Runtime 内部的依赖。

输出：

- 依赖矩阵。
- 循环依赖列表。
- 首批可安全拆分的模块。
- 必须通过接口反转的模块。

### N0-B02 入口映射

将以下入口映射到统一 RunSource：

- 普通聊天。
- 会议室主持人与参与者。
- Subagent。
- A2A 入站与出站。
- 定时任务。
- 夜间记事本整理。
- 插件调用。

验收：

- 每个入口都有确定的 Session、Run、Host、ToolPolicy 和 Budget。
- 没有入口绕过统一事件账本。

## C. 核心契约冻结

### N0-C01 对象模型

- Session。
- Run。
- Turn。
- Item。
- Task。
- Goal。
- Plan。
- Artifact。
- Host。
- Extension。

验收：

- 所有类型有 schemaVersion。
- ID 规则明确。
- TaskId 与 RunId 分离。
- 跨 Host 继续使用新 Run 与 ParentRunId。

### N0-C02 工具与权限契约

- ToolDescriptor。
- CapabilitySnapshot。
- EffectClass。
- ApprovalRequest。
- PluginGrant。
- SettingsDescriptor。

验收：

- 工具暴露和执行共用同一白名单。
- 权限在执行前重新确认。
- 密钥无 getter。
- Agent 不能绕过审计与回滚。

### N0-C03 内容与 UI 契约

- ContentPart。
- PlanCard。
- UserInputRequest。
- ToolTrace。
- Artifact。
- Presentation Profile。

验收：

- Chat 与 Meeting 共用渲染协议。
- Classic 与 Omni 共用 presentation model。
- 插件只返回声明式视图。

## D. 数据所有权与迁移

### N0-D01 数据库所有权表

为以下数据库指定唯一 owner：

- `eta.db`。
- `eta_fork.db`。
- `eta_meeting.db`。
- `eta_learning.db`。
- `eta_task.db`。
- `eta_a2a.db`。
- `eta_projects.db`。
- `eta_run_metrics.db`。
- `eta_local_model_metrics.db`。
- `eta_notification_history.db`。

验收：

- 每个实体只有一个写入 owner。
- 跨库关系使用稳定 ID，不直接依赖物理外键。
- 备份清单能覆盖新增数据库和 schemaVersion。

### N0-D02 Event/Item 持久化策略

决策将明确：

- 事件账本所在数据库。
- 运行状态与指标是否同库。
- 完整 transcript 与 summary 的关系。
- 产物路径与文件索引。
- 事件保留和归档策略。

禁止：

- 在阶段 0 同时引入两套主状态。
- 用数据库自增 id 作为执行顺序。
- 直接修改旧会话表替代迁移。

## E. 运行时与恢复

### N0-E01 Run 生命周期测试设计

- CREATED -> ADMITTED -> RUNNING。
- WaitingUser/Auth。
- 取消。
- 进程死亡。
- 安全恢复。
- Crash loop 上限。

### N0-E02 效果分类

为现有工具标出：

- Read。
- ReversibleWrite。
- IdempotentWrite。
- NonIdempotentWrite。
- Destructive。
- ExternalSideEffect。
- GuiAction。
- HighRisk。

要求：

- 未分类工具默认 `HighRisk`。
- 恢复只自动执行 Read、纯操作或具备 verifier/idempotency 的节点。

## F. Android 与进程边界

### N0-F01 Process Map

现有进程：

- 主进程。
- `:restore`。
- `:voice`。
- `:recognition`。
- `:a2a`。

目标评估：

- Agent Runtime 是否独立进程。
- Extension Sandbox 使用哪个进程。
- Linux Host 使用哪个进程。
- DSH Local Host 使用哪个进程。
- 共享数据库如何跨进程访问。

验收：

- 不新增与服务功能无关的进程。
- 进程失败有可见状态和恢复策略。
- 跨进程数据有版本化协议。

### N0-F02 WorkManager/前台服务边界

- 实时执行使用前台服务。
- 定时与延迟触发使用 WorkManager。
- 精确提醒单独处理，并展示 Android 限制。
- 后台恢复不自动重放高危操作。

## G. 测试与发布门禁

阶段 0 最低产物：

- 架构决策记录。
- 模块依赖图。
- 入口映射表。
- 数据库所有权表。
- 迁移包 schema。
- Contract Test 清单。
- 阶段 1 的迁移回滚设计。

阶段 0 不要求大规模代码重构，但必须能通过：

- Kotlin 编译。
- 现有相关单元测试。
- Release 构建。
- `git diff --check` 或等价的文本检查；仅在用户授权 Git 后才执行 Git 命令。

## H. 第一段完成后的下一段

阶段 0 完成后进入阶段 1 的“影子事件账本”：

1. 现有行为不变。
2. 新 Event/Item 记录与现有 Metrics/Checkpoint 并行。
3. 对照普通聊天、会议、A2A 和定时任务的真实 Run。
4. 验证失败写入不影响主任务。
5. 观察期通过后才切换恢复依据。
