# Nexus 阶段 0 基线清单

## 1. 代码规模

基线提交 `8f005e3` 的实测结果：

- Kotlin 文件：926。
- Kotlin 行数：约 185,419。
- `agent/runtime` 文件：55。
- `ui` 文件：249。
- 单元测试文件：485。
- Android instrumentation test 目录：当前不存在。
- Gradle 模块：仅 `:app`。

代码规模已经超过单模块可可靠维护的范围。不能继续依靠目录命名和人工纪律约束依赖。

## 2. 当前安装与品牌

- applicationId：`fuck.andes`。
- Kotlin namespace：`io.github.mangi.eta`。
- App label 资源：Eta。
- 当前版本：4.11.8，versionCode `2026110914`。
- Manifest Application：`.EtaApp`。

Nexus 阶段必须使用新 applicationId、Nexus label、独立通知渠道和独立备份标识。Kotlin namespace 的全面改名不属于第一阶段，避免无业务收益的全局风险。

## 3. 当前数据库

备份版本清单中已经出现以下数据库：

| 数据域 | 文件 | 当前版本 |
|---|---:|---:|
| 核心会话/MCP/Skill | `eta.db` | `ETA_DATABASE_VERSION` |
| 会话扩展 | `eta_fork.db` | 5 |
| 会议室 | `eta_meeting.db` | 5 |
| 学习 | `eta_learning.db` | 3 |
| 定时任务 | `eta_task.db` | 2 |
| A2A | `eta_a2a.db` | 2 |
| 项目 | `eta_projects.db` | 2 |
| 运行指标 | `eta_run_metrics.db` | 3 |
| 本地模型指标 | `eta_local_model_metrics.db` | 1 |
| 通知历史 | `eta_notification_history.db` | 独立 SQLite |

阶段 0 不直接合并这些数据库。先建立逻辑身份和迁移映射，阶段 1 才决定哪些表复用、哪些迁移到执行账本。

## 4. 当前运行入口

- UI：`MainActivity`。
- Runtime 服务：`AgentRuntimeService`。
- 前台执行租约：`AgentExecutionService`。
- 跨入口执行门面：`AgentExecutionPort`。
- A2A 独立进程服务：`A2AInboundService`。
- 定时任务：WorkManager + `AgentTaskWorker`/Coordinator。
- 恢复进程：`BackupRestartActivity`。
- 语音进程：`:voice`、`:recognition`。

目标不是新增第三套入口，而是让这些入口统一映射到 Session/Run/Turn/Item 和 HostAdapter。

## 5. 当前布局风险

已确认的反向依赖：

- Runtime Service 依赖 `MainActivity`。
- 数据仓库依赖 UI model。
- Agent Voice/Overlay 依赖 Compose UI 类型。
- Feature 之间可以任意引用。
- 单模块无法用编译器阻止 UI/Data/Runtime 循环依赖。

## 6. 可复用资产

不需要重写的部分：

- `AgentEvent` 事件来源。
- `AgentRunTelemetry` 指标、请求、子 Agent trace。
- `AgentRunCheckpointStore` 检查点、上下文快照和 transcript。
- `AgentExecutionPort` 统一入口方向。
- Provider 层和模型目录。
- A2A 协议、传输和任务模型。
- MCP stdio/HTTP 客户端。
- Skill、PSS 安全和审计基础。
- 会议室主持与上下文隔离。
- Backup v5 分层归档方向。

## 7. 当前 UI 与功能风险

- Runtime 与服务逻辑直接引用 Activity。
- 会话历史点击、侧栏手势、重影和运行统计有已知问题。
- Classic/Omni 仅有部分外观能力，未形成正式布局 Profile。
- 字体只支持默认配置，尚无用户字体导入与分区域字体。
- 会话记事本尚不存在。
- 目标/计划模式尚不存在统一状态机。

## 8. 迁移结论

因为 Nexus 使用新 applicationId：

1. 不能依赖 Nexus 直接打开旧 Eta 私有数据库。
2. 旧 Eta 必须能导出一个明确的 Nexus Migration Package。
3. Nexus 首启通过 SAF 导入，不接受旧应用内绝对路径。
4. 迁移必须在 staging 校验后原子切换。
5. 迁移失败时 Nexus 保持空数据或已有数据，不能覆盖旧 Eta。
6. 用户不提供迁移包时，Nexus 以全新安装启动。
