# Nexus 本地可开发差距继续收口（2026-11-10）

本轮继续处理不依赖新增外部服务的事项。

## 已实现

- `迁移与备份`页新增旧 Eta 安全迁移向导，明确导出、校验、恢复、重启四个阶段，并复用现有加密备份、SAF、staging、回滚链路。
- 外观设置新增全局字体选择：跟随系统、无衬线、衬线、等宽、JetBrains Mono。字体通过 Miuix `TextStyles` 应用于聊天、设置、Markdown 和其他 Miuix 页面；终端代码区域继续强制使用等宽字体。
- 软件管家新增 `settings.appearance` 接口，可读取和修改皮肤、主题、配色、字体、模糊、手势、界面缩放，并通过 before/after 审计真实回滚。
- MCP 服务、MCP 工具、Skill 开关和自学习设置增加参数化快照与真实回滚；设置控制面现在可以为这些非全局参数项获取变更前状态。
- `AgentMessageUi` 支持结构化 `ContentPart`，普通会话与会议室共享 `NexusContentPartRenderer` 渲染 Markdown、公式、代码、图片、音频、文件、轨迹、计划、用户输入和审批。
- 新增 DSH Go plugin-kit protocol v1 清单校验器，校验 16 KiB 上限、槽位、贡献 ID、capability、相对资产路径和重复项；未知或超版本清单失败关闭。
- Web 工具运行配置、Provider 认证异常与凭据绑定、角色会话状态和 Linux 环境设置仓库已迁移到更低的 data/terminal 层，继续降低 `data -> agent` 反向依赖。

## 验证

- `checkNexusArchitecture`：通过
- `:app:compileDebugKotlin`：通过
- `SettingsRollbackCapabilityTest`：通过
- `DshPluginManifestValidatorTest`：通过
- import 审计：3315 条；`data -> agent` 从 53 降至 37，禁止反向依赖从 84 降至 68，`data -> ui` 保持 0。

## 仍然存在的边界

- DSH 当前完成的是 plugin-kit v1 清单预检，不等同于 Host 和全部 UI 插槽完成；真正执行插件 Effect、View 和 Invoke 仍需独立 DSH Host。
- `agent -> ui` 仍有 17 处，集中在语音和悬浮窗表现层；迁移需要把对应 Service/Panel 装入 UI 表现层，不能只改包名。
- `data -> agent` 仍有 37 处，主要是 Provider、角色卡、备份和模型目录的领域类型；下一批应在不破坏备份格式的情况下迁移到稳定 DTO。
