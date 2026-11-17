# 全流程检查与稳定性修复记录（2026-11-13）

本记录承接用户“重新检查所有流程，发现 bug 直接修复”的要求，由主 Agent 完成。先定位已有所有者、调用方和定向测试；不回读旧聊天，不重新实现 D01–D14，不把测试数量或文件数量折算成功能数。

这里的“源码已核对”仅表示已检查列出的调用链及其关键边界。“定向验证通过”表示指定的本地用例通过；两者均不等于手机、真实服务或每种组合都已经验收，也不代表全软件零 bug。旧架构文档中的目标状态不代替当前源码事实。

## 本轮修复

| 流程 | 修复前问题 | 当前行为与所有者 |
|---|---|---|
| 自动任务接收 Runtime 结果 | 收到结果即确认清除 Runtime 待接收记录，任务事务失败时只剩悬空结果 ID | DefaultAgentTaskRunner 先将完整 RunResult 原子写入 AgentTaskResultStore；Coordinator 提交任务终态后才确认。保存/提交失败、取消、结果 ID 不符或引用未解析时不确认 |
| 自动任务结果查看 | 成功记录缺少可读输出；只保留有限摘要会丢掉长结果 | SUCCEEDED 事件持久化最多 12,000 字预览；AutomationRepository 校验任务归属后读取完整私有回执，运行详情优先显示完整内容 |
| 工作流步骤结算 | 步骤结果可能在持久断点之前清除；外层总超时可能被当作单步骤超时 | 已结算断点落盘后才确认对应 Runtime 结果。检查当前协程是否仍有效，区分步骤超时、工作流总超时和调用方取消；未确认步骤不自动重放 |
| 自动任务取消与租约 | 只请求取消 Runtime，无法及时停止会议或 WAIT 等执行；过期租约仍可能提交终态 | Coordinator 登记当前执行 Job，先提交取消状态再停止执行；心跳续租失败立即取消当前执行；完成、失败、延迟及等待提交同时检查租约所有者和期限 |
| 重复恢复输入 | 恢复资格检查前先插入租约，重复点击可出现无主租约或主键冲突 | resumeWaiting 在一个事务中先切换合法等待状态，成功后才建立租约并处理提醒 |
| 定时唤醒 | 执行任务的 deadline work 会在刷新同名 REPLACE 工作时取消自己 | 独立 AgentTaskDeadlineWorker 只负责将真实执行入队并等待入队完成；真实执行留在 check-now 队列。排除被同定义运行阻塞的排队项，满容量时保留租约到期唤醒 |
| 自动会议准入 | 自动会议绕过手动房间所有者，恢复检查/停止/排队可能不一致 | MeetingRunCoordinator.runScheduled 与手动入口共用生命周期锁、房间互斥、前台执行租约与运行查询；活动自动会议不被恢复为中断 |
| 自动会议成功状态 | “请求已处理”被当作任务成功，成员失败或主持汇总失败也可能报完成 | MeetingTurnOutcome 区分拒绝、已处理但未完成、部分完成、完整成功。仅完整成功记自动任务成功；汇总失败保留成员结果并明确标为部分完成 |
| 从会议室停止自动会议 | 会议子协程取消后，后台任务可能一直显示运行直到租约超时 | DefaultAgentTaskRunner 将房间主动停止结算为 MEETING_STOPPED；后台任务自身取消和超时仍向上层传播。已有结果保留 |
| 会议恢复计数 | 跳过仍运行的房间后仍返回全部房间数量 | MeetingRepository 仅累计实际恢复的房间，不将活动自动会议计作已恢复 |
| A2A 自动任务资源 | 派发、轮询或取消的退出路径未统一关闭调用所属 HTTP 会话 | executeA2A 使用 finally，cancelRemoteA2ATask 使用 use 释放当前会话；不关闭应用共享连接池，不自动重发未知派发 |
| 任务事件 JSON | jsonString 已带引号，调用者再次加引号；控制字符也可能破坏事件 JSON | 使用 JSONObject.quote，移除外层重复引号，事件中的 owner、code、agentId、messageId、状态和原因可正确解析 |
| 删除自动任务 | 检查是否运行与删除分离，旧入口还可直接删除活动任务 | 所有产品删除入口收敛至 Repository → DAO 单事务的 deleteDefinitionIfIdle，活动、排队、等待及待核对任务必须先结束 |
| API OAuth 刷新 | 多客户端同时刷新同一个令牌；断开授权或改配置期间的迟到响应可写回凭据 | 每连接刷新串行；共享短状态锁只保护配置/凭据快照与提交，网络不占用该锁；提交前核对配置绑定和原凭据，撤销后不复活 |
| API OAuth 回调 | 旧回调可能覆盖新会话或清掉新 pending；可空凭据读取存在编译错误 | 授权发起原子保存会话绑定；回调换取令牌前后检查当前配置、原凭据及 pending 原文，只清理自己的 pending；修复非空读取 |
| OAuth 空字段 | JSON null 的 refresh token、secret 或 access token 可能变成字符串 “null” | 仅接收真实字符串，缺失刷新令牌正常提示重新授权，不向服务器发送伪造的 null 字符串 |
| API 冻结工具快照 | 匿名连接删除后仍可通过旧快照调用；401 刷新期间的撤销未再核对 | 每次调用要求当前连接仍存在、启用、配置及操作相同；发起请求前、401 刷新与重试前再次检查。删除匿名连接也立即使后续调用失效 |
| 项目工作副本列表 | 一个损坏或缺字段的 binding.json 导致整个副本列表加载失败 | ProjectWorkspaceRepository 逐副本隔离坏清单，校验真实字符串、归属和路径，跳过准备目录及相关符号链接；正常副本照常展示 |
| 记忆复核 | 审核中间态时把其他项目/会话作用域的条目纳入冲突 | MemoryReviewRepository.review/decide 只比较相同作用域，保留原确认、替代与冲突版本检查 |

新增回执在私有目录 `files/workflow-state/automation-results`，文件名为 Runtime ID 的 SHA-256；包含任务归属和完整结果。此目录复用现有 FULL 配置备份的 workflow-state 范围，全文件备份也会包含它；CHATS/MODELS 等逻辑导出不因此增加任务结果。当前不按年龄清理回执，避免丢弃尚未核对的结果。JSON 回执是应用私有数据，不能作为公共日志输出。

## 流程覆盖表

列出实际入口 → 准备 → 执行 → 保存 → 取消/恢复的所有者。下表覆盖本次检查到的产品流程族，相关子路径列在同一行；没有为未证实的问题重写所有者。详细 UI 字段和全部设备/服务组合不在本地定向验证的证明范围内。

| 流程及子路径 | 入口与主要所有者 | 本次重点核对的边界 | 证据与尚需验证 |
|---|---|---|---|
| 普通会话：新建、发送、排队、打断、停止 | AgentAppState → AgentExecutionPort / AgentRuntimeClient → AgentRuntimeService | 模型/附件准备异常；提交即登记 controller；停止后的复核；输入保留与会话归属 | 上轮 task-flow 定向验证通过；手机后台停止仍待验 |
| 会话消息：重生成、候选、编辑、角色回复 | AgentAppState、RoleplayConversationReducer、AgentPendingResultRecovery | 操作类型/目标消息匹配；恢复去重；运行中的编辑与删除限制 | 源码核对；未重新跑既有整套角色/编辑测试 |
| 会话管理：切换、分页、分支、删除 | AgentAppState、ConversationBranchSetupCoordinator、AgentConversationStore | 分支前保存并复核源版本；取消/清理；删除等待运行结算；持久化成功后清链接 | 源码核对；大量历史和低存储设备待验 |
| 分享、文本草稿与附件草稿 | MainActivityLaunchRequests、ConversationDraftRepository、ConversationAttachmentDrafts | 已消费 Intent 不重放；按 subject/epoch/generation 隔离迟到写；附件图片由应用保存 | 源码核对；外部应用撤权与系统重建组合待验 |
| 会议室：手动轮次、排队、附件 | MeetingRunCoordinator、MeetingTurnScheduler、MeetingRepository | 同房间唯一所有者；最多 8 条排队；先持久化再清输入；稳定 ID 去重；未执行输入保留 | 上轮 task-flow 定向验证通过 |
| 会议室：分工、成员、汇总 | MeetingRuntime、MeetingParticipantRuntime、MeetingExecutionTranscript | 主持人仅分工/汇总；成员独立上下文；任务/消息/上下文保存后确认；部分失败和汇总失败 | 历史工具链与本轮相关定向验证通过；真实模型质量待验 |
| 会议室：自动执行与停止 | DefaultAgentTaskRunner → MeetingRunCoordinator.runScheduled | 与手动准入互斥；恢复时跳过活动房间；房间停止能及时结算自动任务 | 本轮实际 Coordinator → Runner → 房间链用例通过 |
| Runtime：首次连接、重连、附着 | AgentRuntimeConnection、AgentRuntimeClient、AgentRuntimeService | 每次独立 Attempt；旧回调隔离；中断/断连释放 lease；UI 投影异常不阻断终态交付 | 上轮 task-flow 定向验证通过 |
| Runtime：结果待接收、分页恢复 | AgentRuntimeResultStore、AgentAppState、入口专属恢复者 | source 隔离；全部分页读完才 Known；成功持久化后确认；未知状态保留 | 上轮定向验证通过；会议全部终态跨重启自动接纳尚未完成 |
| 工具公共链：首次发现、权限、调用 | NexusLazyToolCatalog、NexusRunHosts、RoutingToolExecutor、AgentLocalTools | 授权快照、并发与排队期限、取得执行资格后复核、关闭与取消 | 工具首次调用批次 16 个定向用例通过，不在本轮重跑 |
| MCP：HTTP/stdio、发现、工具/资源/提示词 | McpRepository、McpToolExecutor、协议与传输所有者 | 首次初始化与协商；先只读探测再发送实际操作一次；缓存绑定复核；取消传播；保留用户选择 | 历史两批定向验证通过；真实第三方 MCP 服务待验 |
| REST/API：调用、文件响应、401 | ApiConnectorService / Store、ApiToolExecutor、CapabilityHttpSession | 快照撤销、请求参数约束、大小限制、不跟重定向、所属请求取消、401 一次刷新 | 本轮本地 HTTP 服务定向验证通过 |
| API OAuth：PKCE、回调、刷新、断开 | ApiOAuth2Client、CapabilityOAuthCallbackActivity | state/配置/凭据绑定；新旧会话隔离；刷新串行；null 字段；迟到响应不得恢复撤销凭据 | 本轮本地 OAuth 服务定向验证通过；真实浏览器授权待验 |
| 模型提供商登录与设置 | ProviderSettingsService、ProviderCredentialSessions、ProviderOAuthClient | 登录 generation、刷新锁、断开不等待网络且使旧提交失效、设置 expected fingerprint | 源码核对；真实 OpenAI/xAI 登录及账号权限待验 |
| 模型请求、重试、回退 | AgentModelClient、AgentModelFallbackPolicy、ModelFallbackProgress | 只在尚无输出/工具/进展且用户启用时回退；认证及参数错误不盲重试；回退限制能力 | 源码核对；真实限流/网络和模型兼容性待验 |
| 自动任务：时间/设备/文件/任务链触发 | AutomationTriggers、AutomationRepository、AgentTaskWorkScheduler / Worker | 定义/触发原子保存；同定义互斥与并发额度；deadline 唤醒隔离；等待/恢复不复制运行 | 本轮 DAO 与真实 WorkManager 入队替换验证通过；ROM 唤醒精度待验 |
| 自动任务：结果、取消、删除、租约 | AgentTaskCoordinator、DefaultAgentTaskRunner、AgentTaskResultStore | 完整回执 → 事务 → 确认；事务失败不确认；停止当前执行；过期拒绝提交；删除单事务 | 本轮定向验证通过 |
| 多步骤工作流与等待 | WorkflowEngine、WorkflowCheckpoint、AgentTaskDao | 已结算断点再确认；绝对 WAIT 期限；定义 fingerprint；三类取消/超时区分；不重放未确认步骤 | 本轮断点写失败与超时定向验证通过；未重跑既有所有分支组合 |
| A2A 入站：批准、派发、轮询、取消 | A2AInboundService、A2AInboundTaskCoordinator / Engine | 稳定任务 ID、准入状态复核、本机批准绑定、未知状态不盲重放、取消与关闭 | 源码核对及已有实现证据；真实跨设备端到端待验 |
| A2A 出站及远端会议成员 | A2AAgentRepository、A2AHttpClient、MeetingRuntime、DefaultAgentTaskRunner | 先保存派发身份；继续查询原任务；有界等待/取消；各退出路径关闭所属请求 | 上轮远端会议取消用例通过；本轮资源释放源码核对；真实对端待验 |
| 项目绑定、工作副本、发布 | ProjectRepository、ProjectWorkspace / Repository、SafProjectTree | 项目归属；源目录只读副本；选择文件/指纹后发布；原子备份恢复；坏清单隔离 | 本轮坏清单与正常副本共存验证通过；其他发布路径源码核对 |
| 终端、后台任务、共享文件夹、Linux | ShellProcessSupervisor、DetachedTaskSupervisor、SharedFolderMounts、LinuxEnvironmentSettingsRepository | 明确工作目录/挂载；进程所属权、取消与状态；首次使用读真实持久设置 | 相关源码核对及前工具链证据；Root/Rootless/不同系统长期运行待验 |
| 浏览器与虚拟设备会话 | BrowserSessionRegistry、AgentBrowserSession、VirtualDisplaySessionRegistry | run 引用计数、按 owner/lease 解析、待执行主线程动作过期后不迟到执行、销毁释放 | 既有工具链浏览器用例通过；虚拟屏幕/网页真实交互待验 |
| 语音识别、播报、双工、唤醒 | SpeechInputController、SpeechPlaybackController、DuplexSpeechController、SpeechAudioLease | disabled/cancel 释放，分段音频/迟到回调 generation，输入与播放分离，前台引用 | 源码核对；未发现可确认的新问题，不为重复测试重写；音频焦点/AEC/蓝牙待验 |
| 本地模型下载、导入、启动、停止、删除 | LocalModelRepository / Worker、LocalModelApplicationService、LocalInferenceScheduling | 断点下载、长度/hash、取消、切源、启动后选择复核；模型 owner 与后台让位 | 源码核对；原生推理、驱动、低内存/温控待验 |
| 自动记忆、人工复核、学习晋升 | MemoryMaintenanceWorker、MemoryReviewRepository、LearningRepository | 额度/空闲/授权；来源版本复核；同作用域冲突；人工决定；清理失败不能阻断后续 | 本轮跨作用域复核用例通过；真实抽取质量待验 |
| 目标、计划、记事本与提醒草稿 | GoalPlanNotepadStore、WorkflowRevisionStore、预算所有者 | 执行前严格快照与 revision；同步写入；预算与后续动作核对 | 源码核对；不按旧蓝图另建第二执行器；长目标恢复组合待验 |
| Skill 安装、更新、卸载 | SkillPackageInstaller、SkillUpdateService、SkillIndexService | 私有暂存、路径/大小/hash 限制、替换版本绑定、失败回滚、启用范围 | 源码核对；远端市场与网络中断待验 |
| 能力包与插件生命周期/消息钩子 | CapabilityPackageStore、PackageEventDispatcher、PackageSandboxService | 隔离进程、持久 journal、按启用版本复核、未知回调不自动重放、所属服务释放 | 源码核对；真实第三方包和系统回收待验 |
| 备份、迁移、恢复、自动保留 | EtaBackupRepository、EtaFullBackupArchive、AutoBackupDocuments、BackupSettingsControlProvider | SAF 暂存发布；清单/hash/schema/目录/符号链接；staging 与回滚；保留策略；私密凭据 envelope | 源码核对；完整配置备份包含新回执目录；跨设备/断电恢复待验 |
| 会话归档、导出、生成文件 | ConversationArchiveWorker、ConversationExtensionRepository、ConversationMarkdownExporter、GeneratedFilesArchive | Runtime 状态未知时不归档；固定/活动排除；归档与生成文件独立；导出读取所属内容 | 源码核对；大量文件与外部存储故障待验 |
| 设置、权限及统计展示 | NexusSettingsControlPlane、各设置 Repository、NexusExecutionStore / 指标投影 | 设置继续走原控制面与版本检查；工具展示/执行共用权限；财务必须本机确认；统计非恢复事实 | 源码核对；本轮未修改皮肤布局，也未以 UI 测试代替业务 |
| 图片/“绘画”任务 | 图片附件 → 公共模型/工具链；外部 MCP/API 能力 | 本次源码未定位到独立绘画入口或内置 generate_image 所有者；附件按共享链执行 | 已核对现存图片输入链；不能据此宣称一个不存在的绘画页面通过验收，也不擅自新建 |

## 定向验证

本轮最终 **29 个不同用例通过，0 个失败**。证据为 `build/reports/all-flows-tests.json` 及其中每条 XML 路径；主 Kotlin 与测试 Kotlin 编译成功。

- 首次编译发现 ConcurrentHashMap 的 Kotlin contains 歧义，改为 containsKey；未进入测试。
- 第一批实际执行 27 项，25 通过、2 失败。会议恢复计数是源码问题并已修复；WorkManager 替换会解除旧唯一链/清理旧 work，测试改为核对新 deadline 与真实执行不被取消。
- 后续只运行失败项和新增关联项；新增删除用例曾缺少 assertNotNull 导入，修正后继续。没有因编译错误重复已通过的业务用例。
- 最终的会议停止用例进一步贯穿真实 Coordinator → DefaultAgentTaskRunner → MeetingRunCoordinator，验证停止会议后任务立即以 MEETING_STOPPED 结算；仅重复该直接受改动影响的用例。
- 工作流步骤超时用例最短有效配置为 10 秒，只在本轮集中验证执行一次。没有跑全量回归、没有重新跑历史 16/23 项整批用例、没有安装 APK。
- 日志：`build/all-flows-minimal.log`、`build/all-flows-compile-repair.log`、`build/all-flows-final.log`、`build/all-flows-repair.log`、`build/all-flows-deadline.log`、`build/all-flows-room-stop.log`。初始及修复阶段 XML 均保留，不隐藏失败记录。

前置两批证据分别为 `build/reports/tool-first-call-delivery/tests.json`（16 项）和 `build/reports/task-flow-tests.json`（23 项）；这些是独立批次，不能简单求和为整个软件覆盖率。

## 后续明确边界

| 项目 | 当前事实 | 下一步 |
|---|---|---|
| 会议失败/取消/未知结果跨重启接纳 | 待接收 outbox 保留；成功结果已经在会议消息/任务/上下文落盘后确认；尚未把所有终态都跨重启自动折叠进会议记录 | 基于 source、房间、成员、task/run 身份及已有保存标记，单独实现终态接纳；不得先确认或按年龄删除 |
| 自动任务在结果返回与事务提交间进程死亡 | 回执或 Runtime 待接收记录保留；租约恢复采取未确认/失败关闭，避免重放外部效果 | 单独实现任务所属结果与持久断点的对账采用；不能仅凭文件存在就把整个工作流记成功 |
| 回执保留与清理 | 当前不自动清理，完整配置/文件备份包含回执；逻辑会话备份不包含任务结果 | 与任务删除/归档和未确认对账共用清理策略，先保证可核对后再回收 |
| 真实设备与真实外部服务 | 本轮无手机安装/ADB、无真实账号、MCP/A2A、模型驱动或系统长期运行证据 | 用户装机后核对首次调用、后台唤醒/停止、断网重连、低内存、音频/系统权限和跨设备恢复 |

前两轮稳定性功能组计数为 2/20；本轮作为一个完整的“自动任务及关联流程稳定性”功能组，收尾后计 **3/20**，不按上表 bug 数量凑 20。版本保持 **0.7.5 / 2026111303**，本轮未构建 APK。桌面已有 0.7.5 安装包不包含后续这些稳定性源码修复；达到约定功能组门槛后再统一打包。

## 后续安装包交付（2026-11-13，0.7.6）

用户随后要求“出个安装包”，已将当前源码提前打包为 **0.7.6 / 2026111304**，包含本文修复；上文未打包描述保留为当时状态。累计功能组仍为 **3/20**，打包不折算新增功能。

桌面产物：`C:\Users\29136\Desktop\Nexus-v0.7.6-stability-20261113.apk`，**62,274,584 bytes**，SHA-256 **`2d5c83a380a4b9673c81d0480d1fc33028b1bc9e4982affc82ecd753c3b660d2`**。旁附 hash、完整校验报告和中文说明。正式 Release 构建成功，使用 `-SkipTests`，未重复上述用例；正式 v2/v3 签名与此前证书相同，非 Debug/testOnly，身份/版本、ZIP 对齐及 16 个 ARM64 原生库的 16KiB 兼容性通过，桌面副本完整性一致。证据：`build/stability-release-delivery.log`、`build/reports/nexus-delivery/release-0.7.6/verification.json`。

docs/31 与 docs/22 同步记录。未进行手机安装或真实外部服务长期验证；“后续明确边界”中的未完成项保持原状态。

