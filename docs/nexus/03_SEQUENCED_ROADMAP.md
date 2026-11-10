# Nexus 分阶段实施路线

## 1. 依赖顺序

```text
身份与基线
  → 核心契约
  → 执行账本
  → 模块解耦
  → 工具与权限
  → 目标/会议/记事本
  → A2A/DSH/Linux/文件
  → 统一渲染与双 UI
  → 语音与本地模型
  → 迁移、备份和正式发布
```

不能先改 UI 再改状态模型，也不能先接 DSH 全插槽再建立插件权限契约。

## 2. 阶段 0A：Nexus 身份与仓库治理

交付：

- 产品标识、applicationId、签名方案、数据目录和安全边界确认。
- 旧 Eta 与 Nexus 并存安装策略。
- Eta 迁移包格式草案。
- 上游冻结规则。

门禁：

- 新包不会被误认为旧 Eta 的升级。
- 迁移不依赖私有目录越权访问。
- 未生成正式签名或发布包。

## 3. 阶段 0B：依赖审计与契约冻结

交付：

- Session/Run/Turn/Item/Task/Goal/Plan/Artifact 契约。
- Tool/Capability/Effect/Approval/Host/Settings/Presentation 契约。
- 现有入口映射表。
- 数据库映射与所有权表。
- 禁止反向依赖的架构规则。

门禁：

- 核心契约不依赖 Android/Compose/Room。
- 普通聊天、会议、A2A、定时任务都能映射到同一 RunSource。
- 不改变现有执行行为。

## 4. 阶段 1：统一持久执行账本

任务顺序：

1. 从现有 AgentEvent 接入 EventRecorder。
2. 建立 Item 投影和只读 Timeline Reader。
3. 关联现有 Checkpoint、Metrics 和 conversation ID。
4. 增加 effect policy、幂等键和 crash-loop 上限。
5. 保持现有 UI 读取路径不变，先影子运行。

交付：

- 统一 Run 状态。
- 不可变事件序列。
- 最后安全 checkpoint。
- 只读调试时间线。

门禁：

- 关闭观察功能时行为不变。
- 写账本失败不影响主执行。
- 进程死亡后能识别可恢复和不可恢复步骤。

## 5. 阶段 2：模块边界与 UI/Data/Runtime 解耦

任务顺序：

1. 提取 `kernel-api` 纯 Kotlin 契约。
2. 提取 `kernel-store` facade，隐藏 DAO。
3. Runtime 去除 Activity 和 UI model 依赖。
4. 引入 Application API 和事件查询接口。
5. 建立内层禁止依赖外层模块的构建检查。
6. 最后按 Feature 提取聊天、会议、任务和设置模块。

门禁：

- UI 不直接访问 DAO。
- Kernel 不引用 Compose/Activity。
- Feature 不相互导入。
- 行为对照测试通过。

## 6. 阶段 3：工具、权限与扩展平台

任务顺序：

1. 统一 ToolDescriptor 和 CapabilitySnapshot。
2. 能力目录、搜索、激活和 6～12 个热门工具。
3. 统一权限与效果校验。
4. 独立插件 Sandbox Host。
5. MCP 与 Skills Adapter。
6. A2A Host Adapter。
7. DSH Host Adapter 与完整插槽目录。
8. 插件审计、临时 Grant 和失败隔离。

门禁：

- 工具暴露和执行共用白名单。
- 未授权工具永不进入模型 Schema。
- 插件崩溃不影响主 Runtime。
- Root 权限逐次确认。

## 7. 阶段 4：目标、计划、会议和记事本

任务顺序：

1. Goal/Plan 数据模型和状态机。
2. 输入框上方模式切换和计划卡片。
3. WaitingUser 内联卡片和通知。
4. 会话级 Goal 与项目提升。
5. 会议室长期 Agent、临时专家和预算。
6. 会话记事本附件、Revision、下拉抽屉及 Agent 操作。

门禁：

- 计划模式白名单严格生效。
- 目标预算与完成确认正确。
- 同会话最多一个主 Run 和一个后台 Goal。
- 记事本可回滚，且默认不进入上下文。

## 8. 阶段 5：A2A、DSH、Linux 与文件

A2A：

- Tailnet 自动发现。
- Tailnet 地址 + Agent Card + Bearer Token 校验。
- 24 小时安全会话。
- 高危本机审批。
- 文件和工具权限。

DSH/Linux：

- 本机 DSH 独立按需运行包。
- 远端 DSH Host。
- DSH 全插槽兼容。
- Linux 多发行版 Host。
- 项目 File Mount 默认只读。

门禁：

- 高危审批无法被远端批准。
- 插件和 Host 失败互相隔离。
- DSH 全插槽未完成时稳定版不启用 DSH。

## 9. 阶段 6：统一内容渲染与双 UI

任务顺序：

1. 统一 ContentPart 与消息存储。
2. Markdown、公式、代码、图片、音频、文件和工具轨迹渲染。
3. 会话与会议室共用内容组件。
4. 修复上下文环、重影、历史点击和运行统计。
5. Classic/Omni UI Profile。
6. 字体系统与多尺寸回归。

门禁：

- Classic/Omni 功能完整度一致。
- 所有渲染模式在聊天和会议相同。
- 字体缺失可回退。
- 关键手势不与滚动、返回和通知栏冲突。

## 10. 阶段 7：语音与本地模型

任务顺序：

1. VoiceSession 状态机。
2. 音频焦点、麦克风和播放仲裁。
3. 流式 ASR/TTS 接口和打断。
4. 本地语音模型生命周期。
5. 统一模型注册表、运行后端和资源预算。
6. 低优先级任务暂停与恢复。

门禁：

- 前台 Agent 资源优先。
- 语音模型按需加载和闲置卸载。
- 新模型下载需确认。
- 音频失败可释放资源并恢复界面。

## 11. 阶段 8：迁移、备份与稳定发布

任务顺序：

1. Eta → Nexus 迁移包。
2. 分库、文件和配置 staging 校验。
3. 分层备份与可选大包。
4. 凭据加密和 Keystore 重包裹。
5. 数据库切换、回滚和失败恢复。
6. 全链路真机测试。
7. Nexus 稳定 Release。

门禁：

- 迁移失败不破坏 Nexus 现有数据。
- 旧 Eta 和 Nexus 可并存。
- 备份可验证、可导入、可回滚。
- 新包签名、Debug 标志、权限和通知检查通过。

## 12. 首批实施顺序

先说清楚再实现：

1. 完成 Nexus 身份与迁移决策。
2. 生成模块依赖图和现有入口映射。
3. 冻结核心契约。
4. 只读事件账本与影子观察。
5. 再做第一次模块拆分。

阶段 0B 未完成前，不开始大规模移动目录、不修改数据库主键或执行 UI 重构。
