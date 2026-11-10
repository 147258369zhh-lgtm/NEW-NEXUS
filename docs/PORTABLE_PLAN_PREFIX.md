# Nexus 完整架构大改计划与可移交开发指南

> 更新日期：2026-11-10  
> 当前产品：**Nexus**  
> 新仓库：https://github.com/147258369zhh-lgtm/NEW-NEXUS.git  
> 历史基线：Eta 4.11.8，旧基线提交 8f005e3  
> 当前实施阶段：阶段 0 完成；阶段 1 影子账本、retention 与 Timeline Reader 已接入；阶段 2/3 已进入源码；阶段 4 的模式持久化、Goal/Plan 卡和记事本抽屉已接入；阶段 5 的 Host/MCP/Connector/A2A 主干已完成；阶段 6 的统一 ContentPart adapter 已完成；阶段 7/8 已接线 canonical VoiceStateMachine，DSH Host 与真机验收仍受外部条件约束  
> 本文是从任意电脑继续开发的单文件入口；仓库内副本为 docs/NEXUS_PORTABLE_MASTER_PLAN.md。

## 0. 任意电脑恢复开发

1. 克隆新仓库：git clone https://github.com/147258369zhh-lgtm/NEW-NEXUS.git
2. 先读根目录 AGENTS.md 和本文件，再读 docs/nexus/ 下的详细附件。
3. 配置本机 Android SDK 的 local.properties；该文件不得提交。
4. 运行：cmd.exe /c gradlew.bat checkNexusArchitecture :kernel-api:testDebugUnitTest :app:compileDebugKotlin
5. 如果当前仓库还没有首个提交，先检查远程默认分支与本地状态；不要在未获用户授权时自行 init、commit 或 push。
6. 阶段 1 影子账本已接入但不驱动恢复；阶段 2/3 已有 Application API、反向依赖门禁、按需工具目录、ToolGrant 和 Settings 控制面。后续重点是恢复主事实源切换、DSH Host 与真机链路。

Git 硬约束：除只读检查外，任何 init、remote、fetch、pull、branch、merge、rebase、cherry-pick、commit、push、tag、reset、stash 和 history rewrite 都需要用户当次明确授权。

## 1. 当前实际状态

### 已完成到源码层

- 新建独立工程目录，没有修改旧 Eta；原有 D:\AI\Nexus 也未复用。
- 新 applicationId：io.github.zhh_lgtm.nexus，与旧 Eta 并存安装。
- Gradle 根项目、版本、应用标签与 APK 文件名切换为 Nexus。
- 独立自定义权限、无障碍健康 Provider authority、OAuth scheme。
- 新增无 Android、Compose、Room、Miuix 和旧 App 依赖的 kernel-api。
- 固化 Session、Run、Turn、Item、Task、Goal、Plan、Capability、Settings、Migration 契约。
- 完成入口映射、数据库所有权、进程边界和迁移 schema。
- 生成 3315 条逐文件 import 审计：1462 same-layer、945 cross-layer-review、840 UI 直连基线债务、68 forbidden reverse dependencies；data/fork/runtime 对 UI 的禁入门禁已通过，data -> agent 已从 53 降至 37。
- 身份验证、架构检查、Kernel 契约单测、App Kotlin 编译和 Release 构建已通过。
- Nexus Migration v1 JSON Schema 已落盘。
- Release 元数据已确认 applicationId 为 io.github.zhh_lgtm.nexus，输出名为 Nexus-v0.1.0.apk；该包未签名，只用于构建验证，尚未真机验收。
- 新增无 Android、Compose、Room、Miuix 依赖的 application-api 模块。
- data 与 fork 对 ui 的反向 import 已降为 0；agent/runtime 对 ui 已降为 0，并加入 Gradle 门禁。
- Runtime、通知、A2A、Skill 和任务入口使用 NexusUiContract，不再直接引用 MainActivity。
- 附件草稿文件引用模型移入 kernel-api，同时保留旧包名 typealias 兼容调用面。
- 自动归档改为直接查询 pinned 状态和 Runtime active-run 快照；状态不可用时失败关闭。
- 新增能力目录、ToolGrant、CapabilityPolicyEngine、NexusCapabilityDirectory 和默认高风险 effect 分类；主 Agent Loop 已切换为本轮按需加载本地与附加工具 Schema。
- 新增 Settings Control Plane 合同与适配器；Remote Agent 只能提交请求，Secret 不返回明文，审计返回 auditId。
- Goal、Plan、会话记事本已进入持久状态机、revision 和按需工具。
- 工作流模式按会话持久化，Goal/Plan 折叠卡已进入输入框上方，记事本支持顶部下拉、半屏/全屏、自动 revision 保存与 Agent 操作。
- 迁移与备份页已接入旧 Eta 安全迁移向导，复用加密导出、SAF 校验、staging 恢复和重启。
- A2A Transport 已实现统一 HostTransport；Artifact Store 已从 UI 迁入 Data 并增加 Application Ledger。
- MCP Server 已投影到统一 HostAdapterRegistry 和 ToolDescriptor。
- 主会话已加入会话/计划/目标模式切换和模块化记事本 Revision 编辑器。
- 可逆设置审计保存 before/after，并支持当前模型、备用模型和记忆开关真实回滚。
- Release R8 构建已在单次 3 GB Gradle / 2 GB Kotlin daemon 下通过。
- Classic/Omni 已通过 PresentationProfileResolver 和 CompositionLocal 提供正式 Profile。
- 统一 ContentPart renderer 已覆盖文本、Markdown、公式、代码、图片、异步音频、文件、轨迹、计划、用户输入和审批。
- AgentMessageUi 已可携带结构化 ContentPart，普通会话与会议室共享同一渲染路径。
- 外观页增加系统、无衬线、衬线、等宽和 JetBrains Mono 字体选择。
- 统一 AgentRuntimeSession 已接入非阻塞影子 JSONL 账本；不记录正文和敏感参数。
- 影子账本已增加 30 天/200 Run retention 与安全的调试 Timeline Reader。
- 新增 VoiceStateMachine、AudioOwner、WorkloadType 和资源抢占策略。
- LocalVoiceSessionCoordinator 已接入 canonical VoiceStateMachine，Overlay 暴露 canonical snapshot。
- 软件管家新增 `settings.appearance`，并为外观、MCP、Skill 和自学习设置补齐真实回滚快照。
- 新增 DSH Go plugin-kit protocol v1 清单校验器，完成 Host 之前的槽位、ID、capability 与路径预检。
- 新增 Nexus Migration Validator，凭据迁移必须加密，并拒绝重复、过大和错误 schema 的包。

### 尚未完成，不能宣称完成

- 旧 Eta 到 Nexus 的专用迁移向导 UI；现有加密备份、staging 恢复和回滚后端仍可过渡使用。
- 影子 Event Ledger 尚未切换为恢复和 UI 的唯一主事实源，20 Run 对照观察未完成。
- Classic/Omni 的逐页面视觉一致性仍需真机走查；UI 迁移与专用字体分区仍非全部完成。
- 夜间记忆整理、低风险提醒和项目级 Goal 提升。
- DSH Host 本体、全部 DSH UI 插槽、Linux Host 和双向 A2A 真实对端联调。
- 语音和 overlay presentation 从 agent 包迁往 UI 层。
- 更多 Settings 项回滚声明和 DSH Host 本体；MCP Host 注册已实现。
- 真实设备端到端和长时间后台验证。
- 新仓库的提交与推送；当前必须服从“不得随意 Git”。

## 2. 需求追踪矩阵

| 需求域 | 已冻结的产品行为 | 目标阶段 | 当前状态 |
|---|---|---:|---|
| Nexus 产品身份 | 新名称、新 applicationId、与 Eta 并存、独立权限和迁移包 | 0 | 源码基线完成 |
| 上游策略 | 停止常规同步；只人工评估安全或严重兼容修复 | 0/持续 | 已冻结 |
| 数据迁移 | 旧 Eta 加密导出，SAF 导入，staging 校验后原子切换 | 0/8 | 安全迁移向导已接现有加密备份、SAF、staging 与回滚；跨设备换机仍需设备验收 |
| 备份 | 分类备份；凭据加密；模型、Linux、DSH 独立可选包 | 8 | 现有分层备份/恢复后端完成；迁移 UI 与正式发布待验收 |
| 执行内核 | Session、Run、Turn、Item 分离；不可变事件账本；安全恢复 | 0/1/2 | 影子账本已接入，恢复未切换 |
| 工具加载 | 默认只加载能力目录，按需搜索、描述和激活 | 3 | 主 Agent Loop 与 Subagent Loop 已接入 lazy catalogue |
| 工具权限 | 暴露与执行共用白名单；读写查、终端、GUI、Root 分层 | 3 | 策略、分类与单测完成，执行接线未接 |
| 软件管家 | 独立入口和 Agent 可调用技能；统一 Settings Control Plane；全审计 | 3/4 | 控制面、before/after 审计和可逆项回滚已实现 |
| 角色与提示词注入 | 合并模块，Capability Center 风格，强过滤与作用域 | 4 | 设计完成 |
| 记忆与自学习 | 候选、垃圾过滤、管家审核、用户批准、试用、版本化 | 4 | 基线审计完成 |
| 普通会话 | 保持现有体验；修重影、点击、历史栏和运行统计 | 2/6 | 待实施 |
| 会议室 | 主持人监督器加 Agent 工具拓扑；单 @ 直接、多 @ 并行、无 @ 分派 | 4 | 已冻结 |
| 子 Agent | 独立模型、独立上下文、权限交集、状态和轨迹可见 | 3/4 | 已冻结 |
| Goal/Plan | 输入框上方模式切换；计划卡片；预算与用户确认完成 | 4 | 状态机、持久化、模式恢复和折叠卡已完成 |
| 会话记事本 | 会话附件、顶部下拉、半屏或全屏、Revision、Agent 可整理 | 4 | 抽屉、自动 revision 保存和 Agent 操作已完成 |
| 渲染 | 文本、Markdown、公式、代码、图片、音频、文件、轨迹统一 ContentPart | 6 | 统一 adapter 与 AgentMessageUi 接线完成；外部 Host Item 真机验收待做 |
| 上下文压缩 | 可追踪压缩 Item、失败降级、保留原始历史、环状态展示 | 1/6 | 设计完成 |
| UI | Classic/Omni 功能一致；精致材质；精确手势；字体可分设 | 6 | Presentation Profile 已接入，页面迁移待做 |
| 文件管理 | 项目固定输入、输出、Artifact 目录；导入、导出、分享和统计 | 3/5/6 | Artifact Ledger 完成；固定目录 UI 待接 |
| A2A | 双向、动态 Agent Card URL、标准与 PascalCase 别名、前台服务 | 5 | 标准/别名、动态 URL、独立前台服务与出站客户端已落源码；真实对端与锁屏验收待做 |
| A2A 安全 | Tailnet、Card、Token；24 小时会话；高危本机确认；工具高权限仍设限 | 5 | 已冻结 |
| MCP | stdio 与 HTTP 生命周期、诊断、重连、统一 Connector 分类 | 3/5 | stdio/HTTP、诊断、进程生命周期和 HostAdapter 已实现；跨进程真机验收待做 |
| Connector | WebSocket、OpenAPI、Unix Socket、SSE、GraphQL 等统一 SDK | 3/5 | SDK 与主要传输已实现；逐协议真实端点验收待做 |
| DSH 插件 | 远端与本机 Host；全部 UI 插槽验收后才宣称兼容 | 5 | Slot 合同与 plugin-kit v1 清单预检完成；Host 与执行桥待实施 |
| Linux | 独立 Host，多发行版，按需下载，固定项目挂载默认只读 | 5 | 待实施 |
| 语音 | 全双工状态机、音频仲裁、VAD、流式 ASR/TTS、打断 | 7 | canonical 状态已接现有协调器；真机全双工仍待验收 |
| 本地模型 | 分模态、资源预算、按需加载；新下载需确认 | 7 | 后端已有，资源优先级完成 |
| Android 生命周期 | A2A、Goal、定时任务后台持续，进程死亡安全恢复 | 2/5 | 设计完成 |
| 诊断统计 | 本地优先、匿名需授权、运行记录、图表和会话简版统计 | 1/6 | 本地指标与页面已落源码；长时真机数据验收待做 |
| 发布质量 | 每阶段独立验收；稳定版不以代码代替真机证据 | 全部 | 门禁已冻结 |

## 3. 需求来源与参考项目

| 来源 | 采纳内容 | 明确边界 |
|---|---|---|
| Eta 4.11.8 | Runtime、会话、会议室、工具、A2A/MCP、语音、Linux、备份资产 | 只作历史基线和迁移来源，不是常规上游 |
| Eta-por | 数据备份、上下文压缩、Linux 工具、记忆自学习、灵屏、子 Agent、提示词与工具边界、流体云、统计、分支图、顶部切换、文件管理 | 按 Nexus 契约重做，不直接堆叠代码 |
| OmniBot | 第二套 UI Profile、轨迹、外观设置、终端环境、MCP/Skills | Classic 与 Omni 共享业务，功能完整度一致 |
| Operit / Operit2 | 曾用于插件与沙盒包参考 | 用户已决定停止插件沙盒包模块，该方向不进入当前路线 |
| dsh-Go | Host 化、插件系统、按需能力、模块契约 | DSH 必须经 Nexus Extension 与 UI 插槽契约，不穿透内核 |
| Hermes | 记忆、自学习、归档与可审查沉淀 | 垃圾内容不得直接沉淀，必须管家审核后用户批准 |
| Codex / Pi / 主流 Agent | Goal、Plan、事件时间线、执行恢复、能力目录 | 只吸收可验证模式 |
| EdgeGallery | 本地语音与设备侧模型参考 | 统一进 VoiceSession 和模型资源管理 |
| Agent Skills / MCP / A2A | 生态协议 | 全部作为 Host Adapter，不污染 Kernel API |

## 4. 严格阶段顺序

1. 阶段 0：身份、基线、契约、数据所有权、依赖门禁、迁移 schema。
2. 阶段 1：统一影子事件账本、Item 投影、Checkpoint 与 Metrics 对照。
3. 阶段 2：Runtime、UI、Data 解耦，Application API，Store facade。
4. 阶段 3：工具权限、Settings Control Plane、MCP、Skills、插件 Host。
5. 阶段 4：Goal/Plan、会议、记事本、角色提示词、记忆学习。
6. 阶段 5：A2A、DSH、Linux、Connector、项目 Host Mount。
7. 阶段 6：统一渲染、文件管理、统计、Classic/Omni、手势和字体。
8. 阶段 7：语音、本地模型和资源调度。
9. 阶段 8：迁移、分层备份、恢复演练、真机与稳定发布。

禁止提前同时更换执行状态源、数据库主键和 UI 数据路径。每个阶段先建立迁移与回滚，再接 UI。

## 5. 阶段 0 完成定义

已完成身份与并存安装规则、纯 Kotlin 核心契约、禁止依赖检查、入口、数据库、进程边界文档、迁移 v1 schema、逐文件 import 基线，以及架构检查、契约测试和 Kotlin 编译。

全局 package 与类名改名被刻意留到后续，因为旧 Kotlin namespace 与 Eta 前缀类名属于兼容实现细节；新 applicationId、label、权限、authority、scheme、输出文件名和用户可见文案已经隔离。数据迁移与 UI/Runtime 重构也没有伪造完成。

## 6. 阶段 1 的唯一推荐起点

1. 在现有 AgentEvent 消费点增加独立有界队列和单写线程。
2. 建立 execution_runs 与 execution_events 影子存储。
3. 映射 RunStarted、模型请求、Assistant Item、Tool Item、ContextCompaction、RunFinished 和 RunFailed。
4. 普通聊天、会议、Subagent、A2A、定时任务各选真实用例做对照。
5. 写账本失败只记诊断，绝不阻塞 Agent。
6. 关闭开关时必须零新增写入，旧 UI 与 Metrics 行为不变。
7. 观察期通过后才接 Item、Checkpoint 投影，最后才考虑切换恢复依据。

## 7. 架构总纲全文

---

