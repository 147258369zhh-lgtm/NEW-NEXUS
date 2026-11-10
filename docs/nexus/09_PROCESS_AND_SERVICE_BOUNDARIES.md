# Nexus 进程与服务边界

## 基线进程

| 进程 | 职责 | 当前风险 | 第一阶段决策 |
|---|---|---|---|
| 主进程 | UI、Room、主要 Agent Runtime、设备工具 | 编译体积大；UI 与 Runtime 生命周期耦合 | 保留；通过 Application API 隐藏 Store 与 Runtime 细节 |
| `:restore` | 数据库恢复前重启入口 | 只应处理 staged restore | 保留，禁止执行业务 Agent |
| `:voice` | 语音交互、Overlay、朗读 | 部分 Compose/UI 与数据依赖 | 保留；只通过 VoiceSession 状态机与主进程协议交互 |
| `:recognition` | Android RecognitionService | 需要独立服务生命周期 | 保留；不直接打开主数据库 |
| `:a2a` | A2A HTTP 长驻服务与入站任务 | 曾出现初始化、生命周期和工具标记泄漏 | 保留前台服务；通过 Runtime Client 发起统一 Run |

## 目标新增边界

| 边界 | 启动方式 | 数据访问 | 故障策略 |
|---|---|---|---|
| Extension Sandbox Host | 共享独立 Host，常用插件预热 | 仅 HostAdapter 协议与 Grant 数据范围 | 隔离插件、撤销权限，不终止主 Agent |
| Linux Host | 按需下载，独立运行环境 | 只访问显式 Project Mount | 资源限额；前台任务优先 |
| DSH Local Host | 按需下载独立运行包 | 仅配对 Host 和显式 Mount | 与普通 Linux Host 隔离 |
| DSH Remote Host | 网络连接 | 远端自管数据 | 断线重连；不自动重放高危步骤 |

## 服务与调度规则

1. 实时 Agent 执行使用前台服务与执行租约。
2. 延迟、周期和约束型任务使用 WorkManager；精确提醒单独处理系统限制。
3. A2A 服务 UI 只控制和展示状态；服务状态通过本地自连探测，不信任开关变量。
4. 同一普通会话最多一个前台主 Run 和一个后台 Goal Run。
5. UI Activity 可销毁，执行 Checkpoint 和前台服务状态独立持久化。
6. 从最近任务划掉应用后，已启用的 A2A、定时任务和运行中 Goal 按安全策略恢复。
7. 设备重启只自动恢复 A2A、已启用任务和目标；高危工具操作重新审批。
8. 资源优先级固定为：前台对话 > 用户主动任务 > 夜间整理 > 插件预热 > Linux 空闲服务。

## 跨进程协议

- 每个请求包含 protocolVersion、requestId、deadline、Session/Run 逻辑 ID 和权限摘要。
- 响应区分 accepted、progress、waiting、completed、failed、cancelled。
- 跨进程消息不携带明文凭据或完整私密文件。
- 事件 sequence 由统一 Runtime 分配，接收进程不能自行重排。
- 进程重启后先恢复服务状态和幂等步骤，再接受新请求。

