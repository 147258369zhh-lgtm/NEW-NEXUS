# 小PRO 核心能力源码对照

研究固定版本：Oo0520/Eta-por `5ac565675cb958a073bf956d0cf0a469998b99f9`，本地 `.reference/eta-pro-20261107`。以下结论来自执行代码；文档与代码不一致时以代码为准。

## 复用与许可

双方根许可证均为 PolyForm Noncommercial 1.0.0，含 Mangi-11 Required Notice。允许许可范围内的个人非商业研究、修改、分发，不代表允许商业发布。原许可证及 Required Notice 保留；复用文件另标仓库和提交。PRoot/GPL、隐藏 API 与系统服务不随模块整包引入。

## 自动 Skill

`agent/runtime/AgentExperienceReview.kt` 在聊天成功后独立复盘，新前台运行取消旧复盘；180 秒、4 轮、12 次工具调用、一次技能写入限制。`ExperienceReviewPolicy` 以 8 次 GUI 操作、失败后成功或显式技能词作为信号。复盘只可读写记忆与技能，无设备操作权限。`agent/skill/SkillAuthoringService.kt` 通过已有安装器事务提交 SKILL.md，SHA-256 乐观版本检查，禁止改内置技能、符号链接、特殊文件、嵌套技能，保留有限资源。自学习内置 SKILL.md 记录失败与经验，但不存在跨任务成功次数的确定性质量门槛。

我方已有分层记忆、提取队列、证据去重、冲突审阅和规则版本，缺少真实执行证据到可调用 Skill 的通道。复用 authoring 安装事务；复盘适配现有空闲整理机制，增加多来源证据门槛、待验证状态、版本与用户审阅，不能把一次模型声称成功当成熟能力。

## 自动任务

`agent/automation/AgentTaskRules.kt` 固定 JSON 校验和本地事件匹配，时间 once/interval/daily，通知、前台应用、屏幕、电池、网络、蓝牙、耳机、开机、记忆更新和任务结果事件。充电/解锁/联网条件 AND，冷却与次数限制。`AgentTaskScheduler.kt` AlarmManager + 持久 JobScheduler + Room fireKey 去重；`AgentTriggerMonitorService.kt` 仅在有事件任务时开启 specialUse 前台服务。后台任务受工具限制，不是无条件无人值守控制主屏。`docs/AUTOMATION.md` 明确没有文件监控、复杂条件图或流程编辑器。

我方已有独立任务数据库、WorkManager、租约心跳、幂等运行键、等待授权、失败不盲重放、聊天/会议/A2A 路由。保留这些恢复机制，扩展事件游标、条件和流程执行，不并存第二套调度器。系统调度受 Doze/ROM 限制，不承诺精确到分钟或被强停后仍能执行。

## 临屏 / 灵屏 / 虚拟屏

实际模块为 `agent/display/VirtualScreen*`。Root app_process 持有 ImageReader/独立 display；`VirtualScreenUiTools.kt` 绑定 display 的节点树、截图、归一化坐标、文本和动作后观察，`VirtualScreenUiTreeAvailability.kt` 以窗口/会话/手动操作代次判断暂时缺树，`VirtualScreenRunLease.kt` 防迟到取消关闭下一轮，`VirtualScreenViewport.kt` 统一 FIT_CENTER 坐标和留白处理。

重要差异：该提交 `VirtualScreenRoutingPolicy.kt` 已改为 `launch_app` 主屏、`lingscreen_open_app` 显式开启灵屏，和仓库虚拟屏文档的自动路由描述不一致。不能照搬 README 当现状。Root 依赖、受信任 display、焦点/输入法/应用多屏兼容仍受 ROM 约束；没有普遍支持 ColorOS 17 的证据。

我方 `agent/virtualdisplay/VirtualDisplayController.kt` 已有 Root/Hook 后端、独立帧、预览、手动输入和主屏呈现；但启动失败会静默改用主屏，存在屏幕归属误判。先补明确失败和坐标边界，复用可独立的视口映射；不整包替换依赖大量新 GUI 合同、通知和隐藏服务的 VirtualScreenSession。完整移植需逐项适配观测代次、节点快照和取消所有权，不能仅复制类名。

## 提示词与工具边界

`agent/model/AgentPromptBuilder.kt` 分离基础提示、设备合同、记忆、Skill、角色与附加提示；`agent/prompt/PromptCustomization.kt` 提供 append/replace、指纹与最近 5 版；`PromptConflictAnalyzer` 仅静态辅助判断。`AgentToolPolicy.kt` 同时过滤目录和执行，解析失败却回到 ALLOW_ALL，应改为拒绝。`AgentModelClient.kt` 执行前检查，`PromptIntegrityGuard.kt` 另做本地完整性检测。提示词并非权限防火墙。

我方已有 AgentToolPolicy、MCP 授权、Subagent 范围和设备权限二次检查。增设持久全局边界并与运行/子 Agent 权限取交集，所有模型工具目录和执行都复核；可编辑行为层不覆盖设备安全合同。禁用高层工具不能阻止已获终端权限者执行等价命令，因此需要按实际能力组同时管理终端、GUI、敏感操作和 MCP，不能宣传为沙箱。

## 验收原则

变更必须有可达 UI 和真实执行入口；逐项记录通过的测试与未实现范围。示例、配置和研究结论不作为功能已完成证据。自动发布技能、外部文件监听、后台主屏操作须分别说明授权和系统限制。
