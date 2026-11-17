# Nexus 实装收口进度

当前交付索引（2026-11-16）：**0.7.7-smooth / 2026111603 已构建，手机未连接，尚未安装**；十四项需求与新增记忆读取故障的实现、276通过/1跳过及验收边界见[63号实施记录](63_RUNTIME_MODELS_MEMORY_UI_2026-11-16.md)。1602已安装的历史能力中心审计见[62号记录](62_CAPABILITY_CENTER_AUDIT_2026-11-16.md)。以下较早的“最新”标题保留为历史。

## 2026-11-15 最新增量：会话/会议记录短点选择修复

已修复标题子节点的原生拖拽源吞短点导致列表记录无法打开。会话、会议共用修复，保持长按整理、鼠标处理、UI设计及后台结果归属。5项事件用例和1项既有运行隔离用例通过；仅对新测试夹具修正后失败项复跑，未做全量或真机交互验证。

**0.7.7-smooth / 2026111506**已构建并通过包身份、签名及16KiB ZIP对齐检查；用户确认当前任务可中断后，保留数据覆盖OPPO PHY110并读取系统版本确认。APK 62,931,229 bytes，SHA-256 `AD1101DC7A5C6FA51D1D4CA79BC3392B3850F8F46618A804DBC234F8B553D5CF`；桌面 `NEXA-会话切换修复-2026-11-15`含包与说明。

按用户要求完成运行/审批、Codex、会议协作及排版的源码盘点，详见[41 审计与建议](41_RUNTIME_MODES_MEETINGS_RENDER_AUDIT_2026-11-15.md)。发现的权限语义不一致与CONFIRM缺口仅报告，会议场景和渲染改进仅建议，等待用户决定，不当作本轮已批准实施。

## 2026-11-15 最新增量：会话/会议媒体与内置网页

已接通模型、工具及用户附件的图片/音频/视频展示和多成果投影，HTML/SVG/Canvas作品有独立预览、源码与ZIP保存分享；播放器点播创建、后台暂停和退出释放，消息列表不常驻网页实例。内置浏览器复用系统WebView，接通原会话登录接管、文件上传、相机/相册扫码；网页作品与浏览器登录态分开。

远程媒体通过显式匿名下载或原MCP连接的资源读取登记，不把文字链接冒充已下载文件。会议文件夹及校验链路与聊天隔离，清理旧错误归属；浅色列表底层与白色条目恢复层次。用户已明确底部拖动彩光不用处理，本轮未改。功能范围、测试/安装记录及实际限制见[40 本轮实施说明](40_MEDIA_WEB_ARTWORK_BROWSER_2026-11-15.md)；不将安装成功写作真实登录、扫码、播放或全部流畅度验收。

收口结果：媒体重放附件顺序及MCP本地读取器命名/配额缺陷已修复；109个唯一定向用例的多轮合并记录无未关闭失败，只复跑受影响类和最后失败单项，未全量重测。最终优化构建、包身份/签名/16KiB ZIP对齐通过；**0.7.7-smooth / 2026111505**已保留数据覆盖OPPO PHY110，系统版本确认。APK 62,926,621 bytes，SHA-256 `78A13F1D731F38A03D82B437B843095F32DFE308FA7B3CD92059866E5D6F5AFD`。桌面`NEXA-媒体与内置网页-2026-11-15`含安装包、校验/回归记录及说明；设备交互仍待用户验收。

## 2026-11-14 界面与性能整改（进行中，0.7.7）

已按 Astra 审查、源码修复及独立复查推进：优化会话列表手势与加载、设置搜索和模型服务商层级，改进会议、项目、备份、权限和插件列表的加载，修复子 Agent 工具授权列表高度；技能与 MCP 菜单采用级联展示。会议提及和并行分工、MCP 同名工具与配置/凭据快照、终端环境切换和启动恢复也做了定向修复。会议输入栏接入会话同款静态玻璃表层，尚无会议消息动态透视。详细范围、证据及后续待办见 [31 执行计划](31_V6_EXECUTION_PLAN_2026-11-12.md) 最新章节。

42 项相关定向用例通过；最终终端和会议路由变更再经定向用例及 Debug 构建通过。手机 OPPO PHY110 已覆盖安装 `0.7.7-debug / 2026111401` 并启动，未清除数据；桌面同时放置可覆盖手机的调试包与正式签名包，二者签名不同。正式包构建和签名/16 KiB 校验通过。逐页体验、全部动态玻璃、跨进程 A2A、真实服务及设备帧率仍待继续，不能以构建通过宣称已全部完成。

最新交付（2026-11-13）：0.7.5 / 2026111303 已落实用户选择的“分组导航”和柔雾玻璃、清透液晶皮肤；设置首页完整纵向展示。此前“待选择”预览和 0.7.4 首页四类切片已被本次取代。详细范围、最小验证和桌面正式安装包见文末最新条目。

## 2026-11-14 四组体验改进（源码完成，新批次 4/20）

按用户确认开始落实会话、会议室、自动任务、项目与文件体验，并补齐设置中的语音测试入口及自动备份授权状态。最终新增 `AutomationRunPresentationTest` 3 项通过，主 Kotlin 与定向测试源码编译成功；此前本组 ProjectRepository 4、任务数据库 1、WorkflowEngine 1 和 MeetingUiState 7 项直接用例已通过。未重复全量回归，未在真机验收，未新打包。

| 范围 | 源码结果 |
|---|---|
| 普通会话 | 运行中可排队或中断后发送；输入区显示思考、技能和上下文摘要；用户消息可带入目标和关联项目创建手动自动任务，并明确不继承会话历史、模型、技能与附件 |
| 会议室 | 本轮分工列表、成员结果定位、最新轮次主持回复、现有会议室复制与角色组合编辑；成员详情不再在工作轮次尚无回复时显示上一轮活动 |
| 自动任务 | 结果/文件/步骤优先展示，技术记录折叠；列表显示最近运行、下次时间和待处理事项；创建页含保存摘要、触发预览和配置检查；完整结果/文件读取失败可重试；待办区分补充输入与授权说明 |
| 项目与文件 | 项目详情可关联会话、会议室、自动任务、记忆及文件；可搜索关联项；跨项目移动明确确认；任务运行文件统一展示来源、时间并支持打开/分享 |
| 设置与语音 | 设置搜索覆盖常用表达/路径，服务商显示模型及检测时间；自动备份显示目录授权、上次成功及预计下次并阻止失效授权执行；语音测试可从服务页启动且识别结果保留可见 |

证据边界：当前轮次按最新用户消息时间近似划分，历史数据没有独立轮次编号；运行前检查不代表真实服务已连通；任务文件索引最多检查现有 10,000 条；未发现现成蓝牙录音设备选择所有者，不能宣称完整蓝牙路由。真机、ColorOS、真实模型/MCP/A2A、第三方文件提供器和长期性能仍由用户环境验收。桌面 0.7.6 安装包不含本轮源码。

## 2026-11-13 提交至完成的流程衔接修复（源码完成，新批次 2/20）

继续按用户要求检查普通会话、会议室、图片附件/图片工具、MCP、远端成员、停止、结果保存及恢复，主 Agent 独立完成，不回读旧聊天、不重复上轮工具首调验证。实际阶段、检查层级、源码所有者和完整边界见 [31 执行计划](31_V6_EXECUTION_PLAN_2026-11-12.md) 的同日最新章节。

| 环节与已发现问题 | 当前源码结果 |
|---|---|
| 会话准备异常卡在运行中；准备时停止后仍可能启动 | 外层统一结算准备失败；提交即登记取消控制器，配置读取前后及 IPC 提交前复核停止，Runtime/本地回退共用；发出后的未知结果保留引用，不自动重放 |
| Runtime 绑定超时/中断注册悬挂，旧连接污染重连 | 每次 bind 独立 Attempt；最后等待/lease 释放清理，迟到旧回调与旧 lease 不影响新连接；绑定失败也释放结果邮箱/图片临时资源 |
| 会议排队与退出竞争、停止后续跑、退出丢输入 | 单 room owner 在同一 lease 排空最多 8 条队列；停止关闭接收并取消当前 Job。pending 输入/附件先持久保存再清输入，稳定 ID 去重；未执行项停止/重启后可找回，不自动执行；恢复与 admission 串行并跳过正在运行的 room |
| 本地停止未取消远端会议任务、固定轮询违背超时设置 | 派发前持久身份，先取得远端 task，再服从会议超时轮询；收尾一次最多 5 秒 cancel。未知 send/cancel 分别保留 SEND_UNCONFIRMED/CANCEL_UNCONFIRMED，不自动重发 |
| MCP 取消变成空目录；迟到发现回执覆盖新设置 | 取消/暂停继续传播；回写事务复核连接/凭据，只更新发现缓存，保留当前停用/工具选择；协议/认证等变化立即使冻结 binding 失效 |
| 会议图片历史协议错误、假图片占位、原图无界读取 | 正确发送 content 数组；文件读取最多 12MiB，走既有压缩编码；历史最多 8 张且编码预算合计 3MiB，缺失/超限用文字说明 |
| 事件展示异常阻断 attach 确认和终态 | 投影异常隔离后继续交付确认及完整结果；恢复失败保持未知，不凭连接是否存活推断运行终态 |
| 最早 8 条结果跨入口阻塞恢复；页外完成结果被当成中断 | DAO 按来源筛选并按时间/ID 游标分页；Client 读完清单及完整结果后才报告 Known，失败保留 Unavailable；同时间戳 17 条结果也不会遗漏或提前 ACK |
| 正常会议结果持久后没有确认领取，积压待交付列表 | 主持规划回执、成员消息/任务/上下文、主持汇总保存成功后才 ACK；保存失败、取消或仍是引用的结果保留，不为清积压提前确认 |
| 记忆维护异常妨碍运行清理 | 初始化纳入 try，收尾维护异常隔离，工具/GUI/浏览器/deadline 清理继续执行 |

最小验证：**23 个不同用例最终通过，0 失败/错误/跳过**，主 Kotlin 与测试源码编译通过。包含会话准备 2、连接 3、会议队列 5、排队输入/附件持久化 2、图片历史 2、MCP 快照 2、远端取消 1、事件投影 1、会议中断显示 1、结果来源隔离/真实 Messenger 分页恢复 2、会议保存后领取/写入失败保留 2。首轮 18 项中 2 失败；之后只运行失败及新增关联项，修正本地 logger/图像/Windows URI/Room 服务夹具和两处测试编译问题，最后通过。完整初始失败 XML 仍保留，未跑全量回归或重复上轮 16 项。汇总 `build/reports/task-flow-tests.json`，日志与分阶段 XML 位置见 31。

当前以一个完整稳定性功能组累计 **2/20**；版本仍 0.7.5 / 2026111303，未构建/安装新 APK，桌面包不含本轮及上轮工具首调修复。没有独立绘画入口或内置 generate_image 执行器的源码证据，不能把图片附件/图片工具链检查描述成内置绘图全链验收。会议失败、取消和未确认完成的 outbox 保留，跨重启自动采纳全部会议终态尚未新增；真实手机后台、模型/MCP/远端服务及长期性能仍待用户环境确认。

## 2026-11-13 普通会话/会议室工具首次执行稳定性（源码完成，新批次 1/20）

按用户要求同时核对普通会话、会议主持/工作成员和子任务。普通会话与会议 WORK 成员都通过 `AgentExecutionPort` 进入 `AgentRuntimeRunExecutor`，继而由 `AgentModelClient`/`AgentLoop` 发布工具和产生调用，经执行授权进入本地工具或 `RoutingToolExecutor`/`NexusRunHosts`，回执再进入会话/会议记录。会议 PLANNING/SUMMARY 仍无执行工具，WORK 保留房间、成员与请求工具策略。主 Agent 独立完成，没有派子 Agent、回读旧聊天或重做已完成工作包。

| 已发现问题 | 本轮源码结果 |
|---|---|
| 签名链锁定按需发现目录，激活后工具不可见 | Anthropic/Gemini 根据实际 provider endpoint 首轮提供经过相同授权/能力和请求/Profile/全局边界过滤的稳定完整目录；签名固定规则保留，其余 provider 继续按需目录 |
| 下一轮忘记已激活工具、发现回执缺参数 | 从调用 ID 匹配、成功且工具 ID 一致的历史中恢复最近最多 16 个 schema，重新检查当前授权，不恢复旧 grant 或执行历史；describe/activate 返回实际 parameters |
| 首次 Linux 工具可能固定默认发行版/后端 | 工具构造前读持久选择；选择/初始化串行，rootfs 显式按 distribution/backend 构造，避免启动预热竞态 |
| 宿主槽满立即失败 | 同一 Run 的宿主有界公平等待，最多 15 秒且不超过工具 timeout；取消、停用、关闭及时退出，入场复查授权期限、请求/Skill 与全局边界，执行器只调用一次；状态增加 waiting_calls |
| MCP 无协议首调、旧缓存抢占显式模式、回执误判 | 未知 AUTO 先只读协商再调用；显式协议优先；按 client 实际协商版本校验回执，失败的现代发现不被记为成功协商 |
| MCP stdio 首次资源/提示未初始化，取消与未知结果处理有误 | read/get 先初始化；取消/暂停继续上抛；设置页工具探测遇 I/O 失败不重放。只读有限重试与既有明确 session 404 恢复保留，不增加未知副作用自动重试 |
| 浏览器首次启动时限过短、超时动作迟到执行 | WebView 首建单独等待 25 秒，普通桥接保留 8 秒；未开始的超时/中断动作不能迟到执行，已开始未返回明确为结果未知并提示先观察 |

最小验证最终 **16 个不同用例通过，0 失败/错误/跳过**，主 Kotlin 与单元测试源码编译成功：模型链 2、历史恢复 1、宿主链 3、Linux 持久选择 1、MCP 协议 3、MCP 首次使用/取消/不重放/实际版本回执 4、浏览器迟到动作 1、会议实际规划/派工/汇总编排 1。会议成员使用注入 Runtime，stdio 注入进程流，只验证对应本地编排和协议顺序。

首轮 14 项中 12 项通过；修正新增内置参数 schema 读取层级及旧协议夹具后，仅重跑 2 个失败项通过。收尾补充入场授权和实际协议回执 2 项，参数/测试枚举引用编译问题修正后只执行对应 2 项；签名目录过滤用例补强后只重跑该 1 项通过。未全量回归。证据位于 `build/tool-first-call-minimal.log`、`build/tool-first-call-repair.log`、`build/tool-first-call-admission-final.log`、`build/tool-first-call-policy.log`；各阶段 XML 保留于 `build/reports/tool-first-call-{initial,repair,admission,policy}/`，汇总 `build/reports/tool-first-call-delivery/tests.json`。

完整目录使带签名 provider 首轮 schema 输入可能增加，但解决了激活后目录仍被固定的问题；资金确认、执行授权与副作用边界未放宽。普通会话/会议公共工具链作为 **1 个功能组**计新批次 **1/20**，不以缺陷条数或测试数凑包。版本仍 0.7.5 / 2026111303，未构建或安装新 APK，桌面既有包不含本轮源码。真实手机冷启动、外部服务/网络/认证、Root/无障碍/PRoot、WebView 与长期稳定性仍需用户装机确认；不能据此声称全部工具失败已解决。细则与所有者见 [31 执行计划](31_V6_EXECUTION_PLAN_2026-11-12.md)。

## 2026-11-13 后续新计划：D09→D14→D01

- D02–D08 作为当前已完成源码基线；后续不回读旧聊天，只读取源码、`docs/nexus/31` 和本文件。
- 执行顺序：D09 手机操作与权限健康 → D10 本地模型与语音 → D11 会议/电脑/A2A 协作 → D12 连接/MCP/API/OAuth/插件 → D13 统计/预算 → D14 备份迁移 → D01 UI 深层收口；用户已授权完整推进。
- 每包先实现完整链路，再做一次最小验证；不重复跑已通过矩阵，不用真机外部验收替代源码证据。每 20 个可验证功能生成一次桌面安装包。

## 2026-11-13 D09 开始：虚拟屏健康恢复竞态

- 源码定位发现健康检查任务可在取消后迟到，并对新建替代会话发起恢复。已为任务绑定 sessionId、displayId 与调度代数；资源释放时递增代数使排队任务失效，恢复入口在持锁状态重新核对，防止误拆新会话。增加针对替代会话、显示 ID 与代数变化的定向单测。
- 最小验证 `:app:testDebugUnitTest --tests io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySessionTest` 首次受共享构建目录锁冲突影响；空闲后重跑通过（82 tasks 中 7 executed，75 up-to-date）。D09 其余权限/连续操作链仍在推进，不能据此关闭整包。虚拟屏截图调用现绑定捕获时的 sessionId 与 displayId，避免读取替代会话帧。
- 后续源码核对发现进程级单虚拟屏缺少 Run 所有权：不同 Run 可能看到或操作同一会话。现为快照增加 ownerRunId，并引入不可变 `(ownerRunId, sessionId, displayId)` `VirtualDisplayLease`；启动、观察/截图、手动触摸、交接、恢复和释放校验准确租约，RootShell 在无权时不回退读取无障碍截图。拥有者关闭用完整租约释放，保留显示供下一 Run 原子领取；迟到关闭不能释放同 Run 的新会话租约。健康恢复只由原 Run 在新会话重新领取，不接受旧租约继续操作。
- Root 健康卡新增 DENIED 专用说明，并补齐英文、简体中文、繁体中文；checking/granted/unavailable/timed-out 原已有独立说明。
- 无障碍健康卡补充持久连接标记、断开次数和原因；新进程若发现前次记录为已连接却没有正常断开回调，会记为 `PROCESS_EXIT_UNREPORTED`，显示“进程结束，具体原因未知”，不误报为强停。已知离线历史没有可跨进程保留的单调时钟，因此时间显示为不可用；英文/简体/繁体资源齐全。新增 UI 断开原因可见用例。
- 当前最小验证最终通过：`VirtualDisplaySessionTest` 9 项通过（XML 结果 0 failures/0 errors，覆盖跨 Run 截图/所有权、租约 session 替换/所有权转移、同 Run 迟到关闭保护、所有者切换时迟到触摸拒绝、旧健康探测失效）。这只证明受控源码链；真机权限寿命、ColorOS 行为及完整连续操作恢复仍未验证。当前设计仍是单 display 单例并以精确租约防串 Run，多 display 本身未实现，D09 仍进行中。
- `AgentAccessibilityHealthTest` 定向链通过 33 项（0 failures/0 errors），覆盖连接状态分类、未授权/未知设置、旧进程状态恢复、持久断开原因可见以及生命周期回调与观察订阅。两组定向测试不代表 ColorOS/强停真机验收。

## 2026-11-13 D02→D08 收口（本批已确认 5/20，按用户要求暂停）

- 持久工作流复用 `eta_task.db` 与既有 Worker/协调器。v4→v5 新增 `workflow_checkpoint_json`，保存已确认的步骤、循环迭代、输出、变量、绝对等待期限和定义指纹；断点写入受当前未过期租约保护。租约过期只恢复已确认边界，未知副作用不重放；定义变更、游标损坏或无绑定旧游标明确失败关闭。
- WAIT 立即释放运行租约并按原绝对期限重新排队；流程总时限包含等待，等待不受单步骤超时影响。`COUNT/WHILE/UNTIL` 复用有界次数；条件分支不接受循环模式。显式变量支持 `{{task}}`、`{{previous}}`、`{{步骤ID}}`，不会再次解释输出中的模板；空输出覆盖旧值。触发文件按同一逻辑 Run 保留首次导入副本，WAIT 恢复不重读已变化的外部文件。
- 多来源任务完成触发支持 ALL/ANY，复用持久完成游标、冷却、幂等和循环依赖检查；ALL 每个来源各消费一条新完成，ANY 每次消费最早一条。递归条件编辑、来源多选、循环模式和中文日志已接入原自动任务界面。
- 多步骤流程只执行本地 CHAT/MAINTENANCE Agent。会议和 A2A 独立任务继续使用原派发链，可通过完成触发组合；保存或执行远端类型的多步骤定义会明确拒绝，避免静默改派到本地 Runtime。
- 已核对的合并前证据：精确 WAIT 协调器 1 项、依赖配置 5 项、Room ALL/ANY 2 项、中文日志 1 项共 9 项通过，原 XML 保存于 `build/reports/d07-preintegration/`。最终定向验证：WorkflowEngine 17 项、租约恢复 1 项、v4→v5 迁移 1 项、v1 升级 2 项、非本地工作流拒绝 1 项、文件输入恢复 2 项通过。相关主源码与测试编译通过，未跑全量测试。D06 HTTP 依赖安装现在在依赖准入前读取稳定归档字节，避免临时下载路径被恢复清理；主源码编译通过。
- D07 当前源码链已通过最小验证；按用户要求在本轮生成 0.7.3 桌面包。D08 已补齐持久 Grant（主体、范围、期限、版本、一次性消费、撤销传播）并接入统一设置控制面；自学习锁/请求/临时解锁继续保留。D01、D14 主工作继续等待确认。真实 ColorOS 后台、系统强杀、WorkManager 唤醒和真实外部触发→成果仍由用户装机验收。

### D04 已有产物容量策略补记（仅补证据，不增加功能计数）

- `WorkspaceArtifactStore` 对明确登记的本地已验证产物执行 512 MiB 累计登记上限与 64 MiB 可用空间门槛，超限明确拒绝登记，不删除用户文件。该策略不能代表整个磁盘使用上限或替代实际写文件前的磁盘检查。
- `WorkspaceArtifactStoreTest.verifiedArtifactsEnforceManagedSizeAndFreeSpace` 单条通过；未新增 D04 开发，不跑文件全套测试。


### D08 自学习锁、Grant 与统一设置控制（5/20）

- 锁定的自学习设置现在持久保存锁状态、原因、请求队列和有限审计；锁定期间的修改只生成请求，不改变设置。临时解锁最多 1 小时，到期检查自动重新锁定；设置页显示锁定状态、剩余时间和待处理请求入口。
- 改动所有者：`fork/learning/LearningSettingLock.kt`、`LearningRepository.kt`、`ui/screens/learning/LearningSettingsScreen.kt`、`LearningRepositoryTest.kt`、`agent/control/SettingsGrantStore.kt`、`NexusSettingsControlPlane.kt`、`application-api/SettingsGrantContracts.kt`。
- 证据：源码已落盘；针对请求/到期的定向测试因源码工作目录含非 ASCII 路径未能进入编译。本轮不再重复测试。主源码编译通过；设置 Grant 定向测试暴露测试间共享时钟/单例隔离问题，作为测试夹具修正项记录。

### D02 候选模型能力约束用户编辑（1/20）

- 用户现在点击候选模型即可在原设置弹窗内调整适用用途、是否要求工具、是否要求视觉和最低上下文窗口，不再只能查看能力摘要或通过控制面修改。最低上下文窗口可留空表示不限制；输入在保存前校验为 `1..10,000,000`，保存继续复用 `RuntimeConfigRepository.setModelCandidateSettings()` 与既有规范化/去重链。
- 弹窗保留同一候选的启用、优先级和最近 30 天可靠性统计，用途变更继续按原 `providerId + modelId + purpose` 键去重，不新建模型配置中心或平行路由。
- 最小证据：`:app:compileDebugKotlin` 成功；`ProviderComponentsTest.candidateMinimumContextWindowAllowsNoConstraintAndRejectsInvalidValues` 单条定向测试通过。未做真机点击、未跑全量测试、未生成 APK。当前新一轮批次 1/20。

### D06 Skill 依赖与根安装跨事务回滚（2/20）

- 父事务现在在任何依赖正式写入前创建并持久化，支持先建立空计划、再按准入顺序追加依赖。GitHub、GitLab、Gitee、HTTP 与本地 ZIP 自动补依赖均复用同一父事务；根安装成功后只提交一次，根安装未提交前失败会清除所有已提交依赖，并恢复 registry、owner 边及 provenance/usage/version 元数据。
- 依赖子安装只登记 `markDependencyCommitted`，不再误调 `markRootCommitted` 或提前 `complete`；只有根 Skill 成功提交才完成父事务。依赖安装进入既有跨进程变更锁时排除当前活动父日志，持久崩溃日志仍按父日志、依赖子日志、普通根子日志的顺序恢复。
- 已提交父事务与根/子日志同时残留时，父日志声明的依赖优先，避免重复恢复或误回滚。`SkillDependencyStore.removeParentRollback()` 空集合不再写文件，依赖解析即使没有新增依赖也保留准入 owner 边。
- 改动所有者：`SkillMarketService`、`SkillPackageInstaller`、`SkillDependencyTransactionJournal`、`SkillRecoveryJournal`、`SkillMutationLock`、`SkillRuntime`、`SkillDependencyStore`；测试为 `SkillLocalZipDependencyImportTest` 与 `SkillRecoveryJournalTest`。
- 最小证据：`:app:compileDebugKotlin` 成功；`SkillLocalZipDependencyImportTest.parentJournalExistsBeforeFirstDependencyAndSecondFailureRollsBackFirst` 单条通过，覆盖父日志先于第一依赖落盘、第一依赖提交、第二依赖中途失败后文件/registry/owner 边完整回滚。按本轮两条测试上限，`installsVerifiedCatalogDependencyAndFailsClosedWhenSourceIsMissing` 未再次执行；其唯一一次运行在测试工作目录残留检查阶段返回 ZIP 暂存 I/O 错误，失败点早于本次依赖提交/完成逻辑，已补 `.eta-skill-installer` 清理，后续开发窗口只需复验这一成功路径。未跑全量测试，未做真机、真实市场或镜像验收，未生成 APK。
- 当前下一批次 2/20。

## 2026-11-12 V6 下一批次：D02–D07 模型、恢复、文件、记忆与自动化（20/20）

### D07 有界多任务并发与运行超时（20/20）

- 修复 claim 层用 `executingCount() > 0` 全局单任务的真实断点。不同任务定义可按持久配置并行 1–4 路，同一任务定义仍排除已有活动实例；Worker 与前台监控服务读同一配置，默认 1 保持原行为。
- 新增单次运行统一期限：超时取消 Runtime、写 `RUN_TIMEOUT` 失败关闭且不自动重试，避免未知副作用重复。已有有界退避重试、事件 idempotency/cursor/冷却/每日上限和漏跑单决策合并继续复用，没有重做。
- 调用链：Worker/Service → `AgentTaskCoordinator.tick` 领取配置数量 → 原 lease/STARTED/Runtime → complete/fail/wait；超时在同一租约内 fail 后尽力取消。最小证据：Room/协调器定向 2 项通过，覆盖三个不同任务并行、同任务不重入、超时取消且无重试。真实 ColorOS/系统强杀/多进程仍外部验收。
- 当前下一批次 20/20。

### D07 有界组合条件树与可解释等待（19/20）

- `AutomationCondition` 扩展为持久化有界树，节点保留原扁平字段并支持 `groups`，最多 3 层/16 节点/32 项；增加耳机、飞行模式、电量上下限。旧 JSON 兼容，保存/解析严格拒绝未知字段，避免拼错条件退化成永远满足。
- 求值返回明确原因，写入既有 `WAITING_CONDITIONS` 与工作流 `BRANCH` 事件；同一活动实例延后，不重放、不建平行引擎。外部触发载荷/文本不参与条件状态或授权，24 小时 `CONDITION_EXPIRED` 与原等待上限保持。
- 最小证据：`WorkflowEngineTest` 12 项 + `AutomationRepositoryTest` 10 项一次通过。组合子条件当前可通过 JSON/已存定义创建，编辑 UI 显示数量/组合方式；界面完整树编辑和真实端到端触发仍后续验收。
- 当前下一批次 19/20。

### D06 Skill 依赖 checksum 准入与共享卸载（18/20）

- 依赖声明支持 `skill:<id>@<version>?source=<sourceId>&sha256=<hex>`，普通 root/Linux/bash 能力仍走原 readiness 检查。市场安装按依赖优先顺序复用满足版本/来源/摘要的已安装 Skill，缺失项只从固定提交或 SHA-256 来源自动安装。
- catalog 与声明摘要不一致失败关闭；catalog 未给摘要时在实际归档阶段复核；已安装 Skill 的 ledger ID、来源、版本、真实 archive SHA 与 provenance 全程复核。安装事务计算并保存实际摘要，错误摘要返回 `CHECKSUM_MISMATCH`/`PACKAGE_CHANGED`。
- 共享依赖记录 owner→dependency；仍有其他 owner 时拒绝删除，删除 owner 只清理自身边、不级联删依赖或用户产物。最小证据：`SkillDependencyAdmissionTest` 定向通过，主源码编译通过。本项当时的开放边界已由后续 D06 父子事务收口项处理。
- 当前下一批次 18/20。

### D06 多 Skill 协同、冲突与工具上限（17/20）

- Runtime 在同一任务中从已启用 Skill 选出最多 3 个相关项，提示模型每项的 source/version/priority/工具上限。冲突先按用户本轮点名，再按声明 priority；无法裁决时失败关闭并请求本机输入，被排除项读取返回稳定错误，不拼接冲突流程。
- SKILL.md 工具声明只作为上限与父策略取交集，获得/读取 Skill 不扩权；子 Agent 复用同一 `authorizeToolCall` 门禁。
- 最小证据：`SkillMatcherTest` + `AgentPromptBuilderTest` 共 20 项通过。冲突只识别 SKILL.md 显式声明，不解析正文语义矛盾；名称子串歧义和最多 3 个自动协同仍为明确边界。
- 当前下一批次 17/20。

### D06 Skill 更新与回滚保留停用状态（16/20）

- 替换安装登记原先把用户 Skill 无条件设为 enabled，导致更新、ZIP 替换或版本回滚重新启用用户明确停用的 Skill。现替换路径保留原 `enabled`，只有首次安装默认启用。
- 该修复覆盖市场更新、本地替换和版本回滚共用的登记入口，不改变校验和、版本归档、其他 Skill 或用户文件；原子安装和失败关闭保持。最小证据：`SkillPackageInstallerTest.replacementPreservesUserDisabledState` 通过。真实外部源与 SAF 目录仍外部验收。
- 当前下一批次 16/20。

### D05 空闲学习资源让位与不可变版本回滚（15/20）

- 空闲门禁继续由 `MaintenanceEligibility` 唯一拥有，新增应用前台、每日学习额度和设备资源压力条件；手动整理仍跳过空闲条件。Worker 和 Skill 进化共用该判断，模型调用在既有 Runtime 中维持最低优先级。
- 前台开始或条件失效时看门狗取消并让来源回 `queued`；预算拒绝码统一视为可再试让位，恢复 attempts、不丢原文、不标永久失败。只读现有资源状态与应用生命周期，没有新调度器。
- `LearningRepository` 规则版本增加不可变 `source_ref`，晋升/回滚追加只增不改的变更记录。回滚撤销较新变更，恢复目标旧正文及当时归档的用户规则；版本、用户规则和产物不删除，结果经应用内状态通知。
- 最小证据：`MaintenanceEligibilityTest` 4 项 + `LearningRepositoryTest` 10 项一次通过。3→4 数据库迁移测试未在本轮预算内执行；真实前台竞态、设备资源与长期学习效果仍外部验收。
- 当前下一批次 15/20。

### D05 冲突确认、用户纠正与来源传播（14/20）

- 普通冲突继续写 `butler_approved/conflict` 待用户确认，不静默覆盖。提取结果新增 `correction` 标记，并且只有用户原话包含明确纠正措辞、模型标记与逐字证据同时成立时才传播，降低误覆盖。
- 明确纠正只在同作用域活动冲突上把新事实设 `active`、旧事实设 `superseded`，保留旧 source/evidence/content；后续上下文与 `memory_search` 只读 active，旧错误不再作为事实，跨作用域不越权。
- 已替代值可以被再次纠正回来，纠正仍经过现有质量门、TTL、归档和撤销边界。`MemoryAutomationTest` 最终定向 38 项全部通过，覆盖混合检索与纠正/来源传播，0 失败/错误。自然但未命纠正措辞的情况仍走人工确认，真实模型漏/误标需外部质量验收。
- 当前下一批次 14/20。

### D05 可解释混合记忆检索（13/20）

- 复用原 `memory_entries` 和 `MemorySearchTool`，不新增数据库。关键词支持中文二元词、英文/数字标识符和完整短语；来源、确认时间衰减、会话/任务/会议→项目→全局作用域分层加权，每条结果返回得分、命中字段、词和原因。
- 当前源码没有真实文本 embedding/记忆索引服务，生产模式明确标 `keyword_only` 并说明语义不可用；保留 `MemorySemanticScorer` 接口，服务抛错、空值、非法或不完整分数时整体回退，不消费部分语义结果、不伪造相似度。
- 项目/会话隔离在 repository 与检索器双重校验，过期/inactive 条目和越界 scope 不返回。`MemoryAutomationTest` 38 项最终一次通过，包含降级、语义失败回退、可选语义叠加和纠正传播。权重/新鲜度仍为启发式，真实长期记忆集收益需外部验收。
- 当前下一批次 13/20。

### D04 Linux 输入映射与真实工作区挂载（12/20）

- 现有链路 owner 已定位：`AgentFileReferenceGateway` 把附件导入私密工作区，Linux 命令由 `RootShellTerminalController`→`ShellProcessSupervisor`/`ProotCommandBuilder` 执行，产物回执/Artifact Ledger/可见根发布已有。真实断点是私密输入目录从未进入 PRoot/chroot namespace。
- 定义唯一 `/nexus-inputs` 入口和 `TerminalInputPathMapping`：仅接受工作区内 Android 普通文件路径并安全重基，拒绝 `content://`、相对路径、`..` 越界和工作区外路径；PRoot 与 chroot 分别真实绑定同一目录，前台/后台/daemon 复用构造链。
- 终端上下文明确要求 Linux 使用 `/nexus-inputs/<相对路径>`，手机 URI 不是 Linux 路径；绑定不可读时只能复制到 `/workspace`。`deliver_file` 仍只接受私密工作区文件，不把输入映射误当交付/写回或放宽成功回执。
- 最小证据：`TerminalInputPathMappingTest` 3 项通过，覆盖 imports/项目路径换算、URI/外部路径拒绝、chroot 载荷真实挂载。真实 RootFS、SELinux、长跑和写回仍由设备验收。
- 当前下一批次 12/20。

### D04 后台 job/daemon 停止验证与 Runtime 取消回收（11/20）

- 修复组长先退出但同组/换组子进程仍存活时旧脚本返回成功的问题。停止链改为组内 TERM → ownership token 扫描残留 → KILL → 再扫描验证；只有验证为空才清 pid/log/exit 并算 stopped，仍残留或未知退出码保留 running 并明确失败。
- `BackgroundTerminalJobs.stopAll` 逐任务隔离失败并继续清理其余任务；Runtime 取消登记并异步收回本 Run 通过 `terminal_job start` 启动的 job，状态记 `cancelled`，正常回复完成仍允许跨 Run 继续运行。服务停止继续经 lease drain 清用户身份 job/daemon，Root daemon 保持独立设计。
- 最小证据：`DetachedTaskStopVerificationTest` 2 项通过，覆盖只有验证通过才算停止和 cancelled 不误记成功。Windows 主机没有执行 POSIX 设备用例，Android/Root/Linux 进程组与 SELinux 行为仍外部验收；主动 `env -i` 换组进程无法靠 token 识别。
- 当前下一批次 11/20。

### D03 Ledger / Metrics / Checkpoint / UI 四方恢复对照（10/20）

- 扩展原只读 `executionLedger` 检查器，不新增数据库或恢复源。逐 Run 对照 Ledger/events、Metrics、Checkpoint/transcript/context 与现有持久 `execution_items` UI 投影；报告有界输出 `missingSources`、序号/终态、请求数、工具关联、checkpoint digest、transcript 数量和内容一致性。
- 旧 checkpoint/归档未保留、读取失败、digest 或内容不符都标记 `not_retained/corrupt/invalid_content/unreadable`，不会计为通过。`fullComparisonComplete` 只在样本达到 `min(limit,20)`、全部 Run 四方通过且没有未配对 Metrics 时置 true；默认生产报告仍要求实际 20 Run，本次单 Run 只验证全通过路径。
- 恢复决策仍明确为 legacy checkpoint，四方对照只提供证据，不凭检查通过自动切换主源，也不重放工具。最小证据：`NexusExecutionDiagnosticsTest.fourWayComparisonRequiresLedgerMetricsCheckpointAndUiItems` 1 项通过，`compileDebugUnitTestKotlin` 通过。真实 20 Run 与实时 Compose 像素/真机 UI 未验。
- 当前下一批次 10/20。

### D03 A2A compact 暂停详情持久恢复（9/20）

- 根因是 compact drain 的紧凑结果丢弃 `pause`，终态 outbox 也没有暂停列；断连后只剩失败码，AUTH 丢失待批身份，INPUT 只能降级成通用文案。
- compact 排空始终携带有界 `pause_json`（问题 2000 字、8 个选项、原因 600 字、待批身份 128 字、结算调用 32 条；总长超 16 KiB 时退回失败码，不发送半截 JSON）。`runtime_results` 复用同一暂停编解码并新增 `pause_json`，数据库 28→29 迁移；客户端以引用读回完整结果。
- peer 仍只看到“需要设备所有者提供输入”或既有脱敏 AUTH 摘要；本机错误正文改为固定对端文案，原错误只留本机日志。最小证据：5 个定向类 97 项通过，覆盖紧凑往返、outbox 引用还原、错误文本不外泄和迁移。未做真机多进程 A2A 往返。
- 当前下一批次 9/20。

### D03 目标暂停、继续与修订长流程（8/20）

- Goal 状态增加显式 `PAUSED` 与 `revision`。`goal_manage(pause)` 后所有写类/副作用在 Gate 与 Workflow 兜底门禁返回 `WORKFLOW_PAUSED_SIDE_EFFECT_DENIED`；本机审批等待结束后再次检查，避免等待期间暂停后继续写。只读、目标修订和本机恢复继续。
- `goal_manage(revise)` 使用 `expected_revision` 生成不可变新版本并回到计划批准；运行/验证中必须先暂停。继续要求当前计划存在、本机确认和指纹匹配；批准期间目标/计划变化会使旧批准失败。新 checkpoint 身份绑定 goal/plan revision，修订后旧恢复失败关闭。
- 最小证据：`AgentContextLinkTest` 2/2；`WorkflowToolExecutorTest` 发现并修复首轮 Run 的 goalId 绑定缺口后，最终失败方法单独重跑 1/1。未跑全量。`WAITING_USER` 保留回答后继续的既有语义，不作为强制暂停；升级前无 revision 的旧 checkpoint 继续历史兼容路径。
- 当前下一批次 8/20。

### D03 工具批次并发、顺序回执与迟到取消（7/20）

- `concurrencyKey` 原先只在 kernel-api 声明、执行侧零消费，是真实缺口；参数 Schema、Effect 复核、精确审批和 ToolStarted/ToolFinished 回执已有，未重做。
- 本地只读工具补资源互斥键；只有连续、已发布、只读、资源键互不相同的调用可组成最多三路并行。相同键、写入、GUI、外部副作用、未知工具和插件/包 Hook 都形成串行屏障，模型顺序仍是唯一依赖顺序。取消后尚未启动的调用留下 `TOOL_CANCELLED` 回执且不再执行，结果按模型原顺序写回。
- 最小证据：`ToolBatchConcurrencyTest` 2 项通过，覆盖同键/写屏障/未发布工具串行，以及真实 Loop 两路只读重叠和顺序回执。外部 Host/插件仍保守串行，未做真机/多进程。
- 当前下一批次 7/20。

### D02 旧时间精度失败关闭与历史清洗（6/20）

- 个人上下文、Provider OAuth 等已有明确秒/毫秒单位与归一化；真正缺口在旧运行统计：`completed_at` 未经格式、单位和范围校验即被当作毫秒，并复制到开始/更新/完成时间，固定耗时 0。
- 新增 `AgentMetricsTime`：只接受整型 JSON 数字且位于 2000-01-01 至 2100-01-01 的 epoch 毫秒；10 位秒值、数字字符串、小数、负数、越界和缺字段保持未知，不做启发式乘 1000。旧导入不再伪造 `startedAt/updatedAt`，无法证明的 `durationMs` 不当成真实零耗时。
- 已有 `legacy_imported` 库不再跳过坏记录：迁移事务内幂等清洗 `ownerSession=legacy` 的导入行，只清除不可信时间，保留用量并跳过当前记录；无变化不写库。日历/趋势不再以 1970 撑大窗口；列表/详情显示“时间未知/时长未知”，平均耗时和吞吐只使用已知时长。
- 最小证据：`AgentRunMetricsStoreTest` 定向 15 项全部通过，新增覆盖合法毫秒、秒值、字符串、小数、越界、负数和重复清洗幂等。未做真机、未构建 APK。
- 当前下一批次 6/20。

### D02 四入口能力、用途与输入预算交叉一致（5/20）

- 四入口继续共享 `RuntimeConfigRepository.buildRuntimeConfig`、`ProviderSetting.hasUsableConnection()` 与 `ModelCandidateSelector`，不建平行能力表。修复显式/Profile/全局绑定绕过可用凭据，以及会议室可选模型列表只检查 `isEnabled` 的两处不一致。
- 候选选择器的能力判定抽成共享 `capabilityRejection`；会议工作阶段仍要求工具，只有有图片时才要求视觉，图片交给无视觉模型会在 Runtime 写出前失败，不再落到 Provider 请求层。入口特有工具降级语义保持不变。
- 主聊天、子 Agent、会议和自动任务现在把实际会发送的内容传到同一输入预算：子 Agent 含 prompt/续接历史/Profile 系统提示，会议含 prompt/历史/阶段系统提示/已知图片，自动任务只计算一次准备后的 `resumePrompt` 并用于路由及最终请求；不携带的 history/图片不会被伪造。
- 最小证据：能力一致性 3 项通过；`SubagentRuntimeConfigResolutionTest`、`MeetingDelegationTest`、`ModelCandidateRoutingTest` 共 19 项通过；D02 两项合并后的 `compileDebugKotlin` 汇合编译成功。一次无关的旧审批等待超时在回退输入估算后仍可复现，未归因本次改动。未跑全量、未构建 APK。
- 当前下一批次 5/20。

### D02 四层上下文持久关联与分层恢复（4/20）

- 复用既有四层 owner，不复制数据：原始事件/Run 账本继续由 `NexusExecutionStore` + recorder 持有；压缩摘要/游标由 `AgentContextSnapshot` 持有；工作集由 `AgentRunCheckpointStore`/archive 持有；任务/工作流 identity 由 Goal/Workflow 状态持有。新增的只是一条有界关联元数据。
- `AgentContextSnapshot` 新增可选 link：`runId`、稳定账本 `ledgerRunId`、会话与 scope、`compactedThrough`、摘要 SHA-256、checkpoint 引用和 task identity（scope/模式/goalId 的哈希）。事件元数据不保存标题或正文；旧快照没有 link 时继续保持原 `operationId` 安全路径。
- 恢复顺序固定为事件账本 → 摘要/checkpoint 游标与哈希 → 工作集 → 任务状态。会话、Run 或 Goal/task identity 任一不匹配时拒绝复用快照，调用方只回退到持久 transcript；错误码区分 scope/run/summary/checkpoint/task 不匹配。
- Runtime 只在能够确定 scope 时落关联；本地 fallback、作用域缺失的会议/定时入口不写 link，继续走旧安全回退。同会话中途新建 Goal 会拒绝旧摘要以换取不串任务。
- 最小证据：`AgentContextLinkTest` 1 项通过，覆盖编码后成功恢复、跨会话拒绝、跨 Run 拒绝和同会话换 Goal 拒绝；同一构建完成 `compileDebugKotlin`。未构建 APK、未跑全量。
- 当前下一批次 4/20。

### D02 超长口述有界分段整理（3/20）

- 开启“润色识别文字”后，2,001～8,000 字的口述按句号、问号、换行优先分段，再退到逗号/空格/安全硬切；最多 4 段并串行调用模型，不新增并行资源。超过 8,000 字保持旧行为，直接提交 ASR 原文。
- 每段输出继续经过原文比例与合理性校验；单段超时或失败只回退该段原文，其余段照常整理，最终严格按源顺序拼接，不丢字、不重复或重排。整段失败继续进入原有原文回退；用户取消作为取消传播，不等待也不采用迟到结果。
- 边界：2,000 字以内仍一次调用、超时 8 秒；长文本总预算 12 秒，剩余不足 1 秒的段不再送模型并保留原文。ASR 源文本、录音生命周期和普通失败回退未改。
- 最小证据：只运行 `SpeechTranscriptRefinerTest` 7 项，全部通过，覆盖 2,001 字句末切分、两段调用顺序、单段失败原文回退、取消传播；同一构建编译通过。未做真实 ASR/真机音质验收。
- 当前下一批次 3/20。

### D02 候选模型真实可靠性统计与可选排序（2/20）

- 复用现有 `AgentRunMetricsStore/AgentMetricsDatabase`，按精确 `providerId + modelId` 聚合最近 30 天终态请求，不新增平行遥测。统计只纳入 `succeeded/failed`，取消、运行中和中断不混入；成功率来自请求结果，时延使用请求 `totalMs` 中位数，避免把整个 Run 的耗时冒充模型响应时延。
- 成本只聚合 `priceCaptured=true`、价格存在且用量可完整估算的请求，按币种分别累计并显示价格覆盖率；未知价格不记零，也不因“零成本”获得排序优势。现有数据没有可证明的回答质量评测，因此界面不伪造质量分，只把成功率称为可靠性。
- 模型候选设置页增加最近 30 天样本、成功率、成功请求中位时延、价格覆盖率和低成本摘要；新增“按可靠性排序”开关，默认关闭，手动 priority 顺序不变。开启后仅对样本至少 5 的候选按成功率、延迟、样本数和手动 priority 排序，数据不足候选排在后面。排序只影响候选设置页展示，未改变实际路由选择。
- 最小证据：新增 `AgentCandidateReliabilityTest` 2 项通过，覆盖精确 provider/model、时间窗、成功/失败、未捕获价格和样本门槛/排序；同一 debug 主源码编译包含 Room KSP 查询与 Compose。测试在 ASCII 临时副本执行，未构建 APK，未跑全量。
- 当前下一批次 2/20。

### D02 自动任务复用候选模型路由（1/20）

- 自动任务新增独立、默认关闭的持久候选路由开关，复用原候选集合、Provider、凭据、预算和工具链，不建平行模型配置。开启后每个自动任务步骤在执行入口按 `AUTOMATION` 用途重新解析，候选须通过启用、授权、工具、视觉与上下文窗口约束；用途不匹配或无合格项时在创建 Runtime 前失败并返回 `AUTOMATION_MODEL_UNAVAILABLE`。
- 开关关闭时继续沿原子 Agent Profile、全局子 Agent 模型和主会话模型优先级。开关位于现有“模型与供应商 > 运行与容灾 > 候选模型路由”配置区，与主聊天/子 Agent/会议路由分开控制。
- 边界：调度器调用 runner 前可能已创建 `RUNNING` 记录；本功能保证不会创建 Runtime 或静默改用候选外模型，失败由运行记录明确收口。若要求连该记录也不产生，需要调整调度预检，留作后续结构项。
- 最小证据：`:app:compileDebugKotlin` 通过；`ModelCandidateRoutingTest` 定向 4 项全部通过，覆盖开关关闭原优先级、`AUTOMATION` 候选可用和用途不匹配失败关闭。未跑全量、未构建 APK。
- 当前下一批次 1/20。

## 2026-11-12 V6 0.7.1 正式签名包交付（20 项）

- 桌面安装包：`C:\Users\29136\Desktop\Nexus-v0.7.1-release.apk`，62,085,512 字节，SHA-256 `588617ae32563ab048a58869779496336937f10daa4d39d3539b36a8852ebe84`；`.sha256` 与 `Nexus-v0.7.1-release-notes.txt` 同目录。
- 版本与身份：`0.7.1 / 2026111232`，applicationId `io.github.zhh_lgtm.nexus`；Release 非 Debug/TestOnly，v2/v3 签名有效，证书 SHA-256 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。
- 结构验证：ARM64-only、16 个原生库 16 KiB 页对齐、关键组件/隔离插件/无障碍权限与 ZIP 16 KiB 对齐通过。报告：`build/reports/nexus-delivery/release-0.7.1/`。
- 本包 20 项：D02 自动任务路由、可靠性统计、长口述、四层上下文、四入口能力/输入预算、旧时间精度；D03 工具批次并发、暂停/修订、compact 暂停详情、四方对照；D04 job 停止/Runtime 回收、`/nexus-inputs` Linux 挂载；D05 混合记忆、纠正传播、空闲学习回滚；D06 停用状态保留、多 Skill 协同、依赖 checksum 准入；D07 组合条件、有界并发/超时。
- 构建：Gradle Release 118 tasks 成功，R8/签名/结构核验通过；交付包装脚本内置 PowerShell 核验因 `Get-FileHash` 未加载而中止复制，随后对同一最终 APK 直接执行 `verify-release.ps1` 成功，未重新构建。未在构建中重跑单元测试；各链最小证据见本文件各项。
- 回滚包：`F:\eta\source-backups\NEW-NEXUS-main_v0.7.1_20-features_final_docs_20261112.zip`，27,132,497 字节，SHA-256 `2F8DC25F5F8521445ABEF737DF814FCABF8CA6F6882509281AA6FEBDD22093AF`。
- 真人验收：未连接手机，未安装；Android 16/17、ColorOS、真实供应商/会议/A2A、系统强杀/多进程、真实 RootFS/SELinux、媒体播放与 SAF 失效仍由用户安装后验收。D06 依赖事务边界与 D07 持久 WAIT 留到下一批。

## 2026-11-12 V6 0.7.0 正式签名包交付（20 项）

- 桌面安装包：`C:\Users\29136\Desktop\Nexus-v0.7.0-release.apk`，62,025,248 字节，SHA-256 `2a127f8704b2ce929ead821cde9e2860f2f31e9c9ed95cf67386ccf18ea703cc`；同目录 `.sha256` 已生成。
- 版本与身份：`versionName=0.7.0`、`versionCode=2026111231`、applicationId `io.github.zhh_lgtm.nexus`；Release 非 Debug/非 TestOnly，v2/v3 正式签名有效，证书 SHA-256 继续为 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。
- 结构核验通过：只含 ARM64、16 个原生库均为 16 KiB 对齐，关键 Activity/Service/Receiver、隔离插件进程、无障碍绑定权限和 ZIP 16 KiB 对齐齐全。报告位于 `build/reports/nexus-delivery/release-0.7.0/`。
- 本包汇总新批次 20 项：D03 恢复安全/自动完成/终态停止/进程内 fallback 账本，D02 400 诊断/候选模型/子 Agent 与会议路由，D03 持久等待/A2A 恢复/PLAN 边界/未知副作用，以及 D02 角色保存与 D08 Root 审批等本批前项。没有在构建时再次运行单元测试；功能证据保留各自定向测试，正式包构建完成全量编译、R8、签名和发布结构核验。
- 源码回滚包：`F:\eta\source-backups\NEW-NEXUS-main_v0.7.0_20-features_final_20261112.zip`；同目录 `.sha256` 记录最终包哈希，快照排除 build、Gradle/Kotlin 缓存和 APK。
- 真机边界：未连接手机，因此未安装、未跑 Android 16/17、ColorOS 17、真实供应商、真实会议、真实 A2A peer、系统强杀或多进程长期验收。用户负责安装后的正式体验验收；发现问题时保留上一 `0.6.9` 包与本文件证据。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（20/20）

### D02 会议参与者复用候选模型路由（20/20）

- 会议设置复用现有“主持人路由”配置区新增持久“会议参与者候选路由”开关，默认关闭。开启后，会议参与者的工作阶段以 `MEETING` 用途和工具能力过滤候选，只有存在图片时才要求视觉能力；上下文与凭据授权继续由共享 `ModelCandidateSelector` 判断。
- 会议调用链从 `MeetingRuntime` 读取开关并传给 `MeetingParticipantRuntime`，再进入原 `RuntimeConfigRepository.subagentRuntimeConfig` 解析链，而不是另建模型仓库；无合格候选时在构造会议 `RunRequest` 前失败关闭，候选外模型不会被自动发现。关闭开关时保持原有显式模型、Profile、全局模型和主模型优先级。自动任务本批不变。
- 最小证据：新增会议候选路由用例单独运行通过，`BUILD SUCCESSFUL in 15s`，同一构建的 `:app:compileDebugKotlin` 成功。该构建同时覆盖此前自动停止收尾代码的全应用编译；未重复运行历史测试类。已知同测试类旧用例 `profileContextCannotRaiseProviderWindow` 的期望漂移不属于本功能，未扩展追跑。
- 新 20 项批次达到 20/20，进入一次正式签名包构建与桌面交付。真实会议、真实供应商契约和长期多入口一致性仍由设备/真实服务验收，D02 不据此整包关闭。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（19/20）

### D03 目标终态自动停止后续副作用（19/20）

- Goal 成功写入 `COMPLETED/FAILED/CANCELLED` 后，当前 Run 设置进程内 workflow-terminal 闩锁；持久 Goal 状态也作为权威判断。后续 plan/notepad/goal 写入在落盘前拒绝，所有写类、破坏性、外部副作用、GUI 和高风险工具在审批与执行前返回稳定失败码 `WORKFLOW_TERMINAL_SIDE_EFFECT_DENIED`；只读查询继续按既有策略执行。
- 自动停止没有调用 `cancel`/`abort`，当前 Run 不会被伪造为 CANCELLED，已提交的 assistant 结果保持成功语义；已真正开始且结果未知的副作用仍沿原“不得自动重放”规则处理。持久判断绑定当前 Run 的 Goal ID，避免同一会话中上一次已完成目标阻塞新 Run。
- 最小证据：目标完成后的迟到写入拒绝和只读继续用例单条通过；最终持久 Goal 绑定调整后，由会议候选路由的后续 `:app:compileDebugKotlin` 完整构建确认可编译。没有单独扩测 FAILED/CANCELLED 分支，二者与 COMPLETED 共用同一终态集合与 Gate 路径。
- 当前新批次 19/20。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（18/20）

### D02 子 Agent 复用候选模型路由（18/20）

- 子 Agent 增加全局与 Profile 两级“候选模型路由”开关，持久化在原子 Agent 设置内。开启后，显式模型、Profile、全局默认和主会话模型仍按原顺序解析，随后由共享 `ModelCandidateSelector` 以 `SUBAGENT` 用途、工具能力、授权和上下文约束筛选当前模型、用户候选及既有单备用模型；候选外模型不会被扫描。
- 路由关闭时保持原子 Agent 优先级和手工模型语义。路由开启但无合格候选时，在创建子 Agent Runtime 或调用模型前失败关闭；子 Agent 工具入口显式传递允许路由标记，不依赖 UI 隐式行为。
- 设置页新增默认配置和单个 Profile 的开关，模型候选信息区域可直接把用途改为“子 Agent”或“全部用途”。会议、自动任务未因本批改变。
- 最小证据：`:app:compileDebugKotlin` 通过。新增一条定向测试覆盖 `SUBAGENT` 候选可用与用途不匹配失败关闭，但项目现有 `LearningRepository` Kotlin 代码生成在测试任务进入执行前发生 `OutOfMemoryError`；没有把未执行测试写成通过。D02 仍保持 PARTIAL，后续由会议入口和正式包构建提供一次新的链路核验。
- 当前新批次 18/20，不生成 APK。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（17/20）

### D03 进程内无工具兜底纳入同一持久账本（17/20）

- 跨进程 Runtime 在“明确证明模型/工具尚未开始”的交接失败后进入无工具文本兜底时，现在会创建独立的 `<原runId>:local-fallback` canonical Run，并复用 `NexusExecutionRecorder` 持久记录创建、准入、开始、模型请求/响应、assistant 完成和终态；UI、取消与返回仍使用原 Run ID，避免覆盖可能已失败的交接 Run。
- fallback 的每个语义事件先提交账本再通知 UI。建账、追加或终态写入失败时返回 `LOCAL_FALLBACK_LEDGER_FAILED`，建账失败不会调用 Provider，也不会返回成功；终态后的迟到事件被忽略。
- fallback 继续关闭终端、浏览器、设备、敏感读取、子 Agent、搜索和自定义请求体，并且不向模型发布任何工具。若意外收到工具开始/完成事件，仍沿现有 Run/Item 契约成对落账，随后失败关闭，不把工具副作用误报成安全纯文本完成。
- 最小证据：只运行一条 `AgentRuntimeLocalFallbackTest` 生产路径用例通过；使用实际 `NexusExecutionStore`/recorder 验证独立 Run 时间线完整、工具 Item 为零、事件到 UI 前已可查询，并验证建账失败时 Provider 调用为零。未运行取消/意外工具分支测试，保留为真实异常验收边界。
- 当前新批次 17/20，不生成 APK。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（16/20）

### D03 目标核心条件验证后自动完成（16/20）

- 目标完成改为验证结论：`goal_manage(transition=complete)` 只允许从 `VERIFYING` 进入 `COMPLETED`，已完成为幂等；`WAITING_USER/DRAFT/RUNNING/BUDGET_PAUSED` 不能借一句模型声明跳过验证。必须同时提交已核验核心条件、非关键缺项、影响和证据。
- 删除“确认目标完成”的本机弹窗，完成仍经过目标归属、完成说明、状态机、先记录意图后写入、不可变修订引用和结果事件原子链；开始与恢复的本机审批、预算追加及外部副作用审批完全不变。
- 模型工具说明同步为：开始/恢复/提高预算需本机确认；完成须先进入验证状态并提交核验结论。持久事件仍只记录目标版本引用，不写入 Notepad 正文。
- 最小证据：应用工作流目录一次定向运行通过（`WorkflowToolExecutorTest` 8 项、`GoalPlanNotepadStoreTest` 4 项），状态引擎定向测试通过；覆盖验证后无审批完成、待审批队列为空、完成事件与版本引用、非验证状态拒绝且不写完成说明。未重复历史测试或运行全量。
- 当前新批次 16/20，不生成 APK。自动停止后续写入与迟到工具仍由下一独立功能处理，不把“完成状态已写入”误报成整个 D03 已关闭。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（15/20）

### D03 入站 A2A 启动前 WORKING CAS 与审批续接隔离（15/20）

- 入站 Runtime 在真正调用执行器前再次以 `taskId + state='WORKING'` 做条件更新；远端取消或另一所有者先将任务终结时，本次 Runtime 不再启动。调用后重启仍会重新读取持久状态，不允许仅凭内存中的旧状态继续。
- `AUTH_REQUIRED` 只能经设备所有者审批的原子续接进入 `WORKING`；普通 peer continuation 仅允许 `INPUT_REQUIRED -> WORKING`。若 peer continuation 与 Runtime 审批暂停并发，暂停事务先落库时 continuation 被拒绝并回滚，任务保持 `AUTH_REQUIRED`，不会绕过审批恢复待执行调用。
- 审批摘要、continuation、task identity 或 fingerprint 任一不匹配均失败关闭，不回落普通续跑。规范参数摘要的两条入口共用同一 JSON 规范化与边界检查，空白/转义不产生第二身份；无法在边界内规范化的参数不生成授权 key，因而不能借审批恢复。
- 最小证据：`A2AInboundTaskCoordinatorTest` 一次定向运行 24 项，0 失败/错误；新增“远端取消抢先 claim 不启动 Runtime”和“peer continuation 并发审批暂停不启动 Runtime”两条竞态覆盖。此前同一功能链的规范摘要测试已通过，本轮未重复全量或历史测试。证据边界仍仅为受控并发与 Room 持久状态，真实在线 peer、系统强杀和多进程由设备验收。
- 当前新批次 15/20，不生成 APK；下一功能继续 D03 的进程内 fallback 同一账本与目标验证后自动完成。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（14/20）

### D02 主聊天候选模型集合与失败关闭路由（14/20）

- 新增持久化候选集合，每个候选使用稳定 `providerId + modelId`，保存启用状态、用途、工具/视觉/最小上下文约束和优先级；写入时去空格、去重、限量并规范化数值。
- 实际主聊天先评估当前模型，满足会话要求时保持原模型；仅在能力不满足时按用户候选、既有单备用模型的确定性顺序选择。候选必须已启用、有可用连接和对应能力，不能扫描候选之外的其他模型；全部不合格时在写入 Runtime 前明确失败。
- 候选设置复用原 Provider/凭据/预算/工具链，并接入统一设置控制面的 `state.model_candidates`、`settings.model_candidates`；模型设置页增加候选路由开关、候选列表、添加、启停、移除和能力约束摘要，未新增平行配置中心。
- 最小证据：`ModelCandidateRoutingTest` 3 项通过，覆盖主模型优先、能力/授权过滤、确定性顺序、单备用模型纳入、持久化规范化和无候选失败关闭；`:app:testDebugUnitTest` 编译及该链任务成功。未运行全量测试。
- 证据边界：当前完成的是主聊天与设置控制面首链。质量/时延/费用的真实评分、会议/自动任务/子 Agent 的候选用途 UI、全部入口一致性和真实供应商模型质量仍开放，D02 保持 PARTIAL。当前新批次 14/20，不生成 APK。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（13/20）

### D02 Anthropic HTTP 400 有界参数诊断（13/20）

- Anthropic 生产 Provider 的 `HTTP 400` 现在启用统一参数诊断：用户可看到错误类型和已知请求字段位置，例如 `messages[0].content`，并得到“消息历史格式无效 / 工具定义不受支持 / 图片格式不受支持”等可操作解释。
- 诊断继续只接受白名单请求路径和有限消息关键词，不回显供应商 `message` 正文；测试使用私有提示样本确认用户内容不会出现在错误信息中。非 400 的认证、配额、上下文溢出和普通 HTTP 错误分类保持不变。
- 最小证据：`AnthropicMessagesProviderTest.http400ExplainsKnownParameterWithoutEchoingProviderMessage` 单条生产 HTTP 回归通过，Gradle `BUILD SUCCESSFUL in 2m 32s`。本轮未连接真实 Anthropic 服务，未重复运行模型组或全量测试。
- D02 后续主链是候选模型集合与路由；真实供应商全部契约和本地模型质量继续由设备/真实服务验收。当前新批次 13/20，不生成 APK。

## 2026-11-12 V6 新 20 项批次：执行恢复与计划边界（12/20）

### D03 入站 A2A scoped 本机审批与精确调用安全恢复（12/20）

- 入站运行命中审批时进入 `AUTH_REQUIRED`，原子保存唯一 `AUTH_REQUIRED_PENDING` continuation。远端后续消息只能作为续接输入；本机设置页要求同 peer、原授权为当前授权子集、continuation 与 task/context 一致，任一变化都失败关闭。
- A2A 私有库不再保存完整 transcript、工具结果正文或参数原文。v5 恢复记录仅含 `slot`、`callId`、工具名、效果、规范参数摘要和已结算调用 key，正文上限 8,192 字符、结算 key 上限 32 条并有摘要校验。
- 暂停明确标记待批准调用尚未执行；本机批准只释放一次同一 `tool + effect + canonical arguments digest`。不同参数、不同效果或第二次使用同一 grant 都重新进入审批门，不能借用本机普通审批或其他 Run 的授权。
- Runtime 已把保存的已结算 key 传入恢复运行，并在所有路由工具执行前按 `tool|effect|digest` 硬拦截。工具执行器返回的失败或未知结果也视为可能已经生效；只要暂停前已有非 READ 结算，恢复记录就不建立并转人工核对。
- 恢复记录缺失、损坏、截断、超限、槽位不符或旧进程遗留 `WORKING` 都失败关闭，执行器不会用原 prompt 重跑。`SUBMITTED` 任务可安全执行；`WORKING` 已有完整 artifact 时直接采纳，否则失败为待人工核对；等待中的任务保持等待。
- 远端 `GetTask` 的 history/status/artifacts 不返回审批记录、工具名或恢复 key。A2A 数据库升至 v5，迁移链和备份版本清单一致，并启用多实例失效通知。
- 最小证据：15 个定向测试类、82 项，0 失败/错误/跳过，覆盖真实参数摘要、单次 grant、不同参数/效果、已结算硬拦截、失败/未知结算、损坏/超限台账、二次暂停新槽位、远端不可见、v2→v5 迁移及重启规则。另有 wire 与数据库版本清单往返检查。未做真机强杀、多进程、真实模型副作用或在线 peer 验收。
- 当前新批次 12/20；按用户新节奏，自动测试只用于一次最小链路核验，主要资源继续投入功能实现和真机前可交付路径，不生成 APK。

### D03 出站自动任务 A2A 持久期限与到期人工核对（11/20）

- 自动任务进入 `WAITING_REMOTE` 时持久 24 小时截止时间；进程重建仍沿原截止时间判定。已越过截止或派发结果未知时进入 `A2A_REVIEW`，保存原因及原 message ID，原 prompt 不重发。
- 任务中心显示截止时间和待核对原因，只提供“已核对 / 重新发起 / 取消”。决定一次性写入，重新发起只记录用户意图，由用户显式触发下次派发。
- 自动任务数据库 v3→v4 为既有等待/未知派发任务补可核对状态；定向链覆盖同 tick 到期、跨重启期限、未知派发和一次性决定。当前未做系统强杀或真实外部 peer 验收。

### D03 入站 A2A INPUT_REQUIRED 状态隐私收口（10/20）

- 远端 A2A `INPUT_REQUIRED` 状态仅返回固定通用提示“需要设备所有者提供输入。”；不把 `request_user_input` 的问题、选项或模型内容拼入状态。原问题/选项仍由 Runtime pause 数据承载，本机普通 `request_user_input` 路径保持原样。
- 定向证据：`A2AInboundRuntimeOutcomeTest` 与 `A2AInboundTaskCoordinatorTest` 覆盖远端状态不含问题、选项、邮箱、非标准秘密/PII 样本，同时覆盖输入等待、同任务续接及 `AUTH_REQUIRED`。
- 证据边界：只执行相关单测与 Kotlin 测试编译、XML 解析；未构建 APK、未做真实 peer 或设备验收。当前新批次 10/20，不生成 APK。

### D03 PLAN 执行侧硬边界与子运行继承（6/20）

- 用户选择计划模式后，Linux 只读调研仍可执行；Linux 写入、终端、插件、A2A、MCP/API 等非只读外部能力会由唯一执行门禁拒绝。外部宿主把写入自称 `READ` 也不再绕过；原生工具效果由本地目录决定，`goal_manage`、`plan_manage`、`notepad_manage` 保留计划草稿例外。
- PLAN 父运行创建子运行时，子运行账本现在继承 `RunMode.PLAN` 并绑定子控制器；即使 wire handoff 未写 PLAN，写入仍被执行门禁拒绝。
- 定向证据：`NexusExecutionGateTest` 13 项、`NexusRunApplicationApiTest` 8 项，共 21 项通过；覆盖 Linux/package/A2A 自称 READ、伪造 READ、管理工具例外和 PLAN 子运行。未在真实 Linux、插件进程或在线 A2A 对端做设备端验收。
- 证据边界：这是执行侧首链；计划模式调研同时保持保守，A2A/插件读取在目前不能由本地目录证明时也会拒绝。当前新批次 6/20，不生成 APK。

### D03 入站 A2A 持久等待与幂等续接（5/20）

- 入站任务现在持久保存 `SUBMITTED`/`WORKING`/`INPUT_REQUIRED`/`AUTH_REQUIRED`、原 context、原授权和 continuation；进程重开后等待任务保持等待，不会用原 prompt 自动重跑，已完成但尚未提交终态的 artifact 会直接采纳。
- 标准 `SendMessage` 携带原 `taskId` 时进入续接：保持同一 `taskId`/`contextId`，把新文本作为下一回合；同一 `messageId` 只占位并执行一次。任务创建时的授权是上限，续接使用原授权与当前授权交集；peer 或工具范围变化要求重新授权。
- A2A 数据库从 v2 升至 v3；`A2AInboundTaskCoordinatorTest` 11 项、`A2AInboundMigrationTest` 1 项通过，覆盖输入等待、HTTP 续接、权限不扩张、崩溃恢复和旧数据迁移。
- 证据边界：该节记录最初落地时的边界；后续 Runtime 适配、本机审批 UI、持久期限/待核对和精确调用恢复已在第 11、12 项补齐。未做真机强杀或真实在线 peer 验收。该历史项为批次第 5 项。

### D03 出站自动任务 A2A 同逻辑 Run 恢复（4/20）

- 自动任务 A2A 现在把逻辑 Run 与远端 Agent、task、context、发起 message 持久绑定，不再用每次进程重建都会变化的 dispatch Runtime ID。短轮询窗口结束进入 `WAITING_REMOTE`；下一次唤醒和进程重建只查询原远端 task，原 prompt 只发送一次。
- `INPUT_REQUIRED`/`AUTH_REQUIRED` 保留同一逻辑 Run；补充输入发往原 task/context，message ID 由逻辑 Run 与序号稳定生成。派发结果未知时保留消息 ID并失败关闭，不会自动重发未知外部副作用；可取消已绑定远端 task。
- 自动任务数据库从 v2 升至 v3；`AgentTaskA2ADurabilityTest` 4 项、任务包与迁移链合计通过。最终统一回归覆盖 27 个测试类、124 项，0 失败、0 错误、0 跳过，使用真实 Room 文件库、OkHttp 和本地 JSON-RPC HTTP 对端模拟重启。
- 证据边界：远端长期不可查目前持续可恢复等待，可取消但没有最长等待时限；“派发未知/续接未确认”已有代码和人工核对文案，但尚无专用待核对操作入口。未做系统强杀和真实外部 peer 验收。当前新批次 4/20，不生成 APK。

### D02 模型角色完整保存（2/20）

- 用户现在保存任一模型用途后，辅助、压缩、记忆、自学习和语音整理五类 `providerId + modelId` 都会完整保留；只修改记忆模型不会清空学习或语音整理绑定。
- `RuntimeConfigRepository.setModelRoleSettings` 继续经统一设置控制面写入，所有 10 个角色字段一次提交；没有新增并行设置库或第二条解析链。
- 定向证据：`RuntimeConfigRepositoryTest` 9 项通过，覆盖全部用途写入后读回、局部编辑后其余绑定保留，以及既有压缩/维护用途继承链。未调用真实外部模型供应商。
- 证据边界：关闭的是角色保存丢字段缺陷；D02 的模型列表同步、能力快照、HTTP 400 字段解释、真实供应商契约和候选路由仍开放。当前新批次 2/20，不生成 APK。

### D08 Root 受保护文件操作审批（3/20）

- 用户以 Root 身份请求文件写入、复制或移动时，SMART/MANUAL 模式现在会弹出本机确认；拒绝后工具不执行并返回 `LOCAL_CONFIRMATION_DENIED`。非 Root 同类操作和 FULL_ACCESS 的既有行为保持不变。
- 修复位于现有 `AgentPermissionPolicy.requiresManualApproval`：执行门禁原本已计算 `protectedRootRequest`，现在该标志真正参与审批；资金操作仍在所有模式预先强制确认，Root 读取不新增确认。
- 定向证据：`AgentPermissionPolicyTest` 8 项与 `NexusExecutionGateTest` 10 项通过，覆盖 SMART/MANUAL、Root/非 Root、三档写操作、FULL_ACCESS、读取和资金硬边界；撤回关键条件时门禁用例会失败，恢复后通过。
- 证据边界：JVM 策略和真实执行门禁通过，未在设备 `su` 环境验证弹窗与授权；D08 的完整 Descriptor/Grant、撤销传播、软件管家和高风险 A2A 仍开放。当前新批次 3/20，不生成 APK。

### D03 无障碍持久事件等待与恢复（1/20）

- 用户现在看到：无障碍服务断开时，Run 详情会进入“等待事件”；服务恢复后同一等待项完成，超时/取消有稳定结果；进程被杀后重开，遗留等待会显示安全中断，旧屏幕动作不会自动重放。
- 执行契约新增 `WAITING_EVENT`、`EVENT_WAIT_REQUIRED/RESOLVED` 和 `EVENT_WAIT` Item；真实 `AccessibilityRunGate` 的 `WAITING/RESTORED/TIMED_OUT/CANCELLED` 通过生产 `AgentRuntimeSession` 写入原 SQLite 事实账本，不保存屏幕正文或私有调用参数。
- 账本要求等待事件和结果事件使用同一 Run/Item/事件引用成对出现；未决事件等待会阻止成功终态；遗留等待由启动对账以 `EVENT_PROCESS_RESTARTED` 失败关闭并转为 `INTERRUPTED`，从不执行旧 GUI 动作。
- 对账顺序先关闭事件等待，再关闭审批，并保留仍未回答的用户输入。定向测试覆盖事件/审批/输入同时遗留：审批关闭后 Run 保持 `WAITING_USER`，输入项仍未完成，事件与审批均安全失败关闭。
- 定向证据：`NexusExecutionRecorderTest` 5 项、`NexusExecutionStoreTest` 24 项，共 29 项通过；`:app:testDebugUnitTest` 当前链路构建成功。项目路径含中文，命令显式使用 `--project-prop android.overridePathCheck=true`。
- 证据边界：这是无障碍事件等待的真实持久化与重启安全恢复首链，不是 A2A/自动任务旧 Run 自动续跑，也不认证未知外部副作用可重放。相应缺口继续留在 D03。此项为批次第 1 项；当前批次已推进至 3/20，不生成 APK。

## 2026-11-12 V6 交付：0.6.9 正式签名包

- 10/10 更新统一构建完成，桌面文件为 `C:\Users\29136\Desktop\Nexus-v0.6.9-release.apk`，61,954,136 字节。
- SHA-256：`7eca846373d9088d32335132c07554a6ee2050c9a6c239f4d4b4b9e35844ab4f`；签名证书 SHA-256 继续为 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。
- 正式 v2/v3 签名、非 Debug、包名 `io.github.zhh_lgtm.nexus`、ARM64-only、16 KiB 对齐、16 个原生库、插件隔离和必需组件均通过发布核验。
- 本次未安装真机；Android 16/17、ColorOS 17、自定义源真实访问和长期稳定性继续由用户验收。

## 2026-11-12 V6 未打包批次：源配置备份与统一交付（10/10）

- 完整配置备份 v5 新增 `files/linux-environment`，自定义 Linux RootFS/APK/APT 源可跨设备恢复；共享设置根继续覆盖自定义更新源与本地模型默认下载地址。
- 恢复器兼容旧版 v4 配置备份范围，不因新增 Linux 源目录拒绝历史备份。
- 定向证据：配置备份回归通过，确认 Linux 源文件被导出、设备私有文件保持排除且恢复后内容正确。
- 累计 10 个大更新已满足统一打包门槛；开始构建 `0.6.9 / 2026111230` 正式签名包。

## 2026-11-12 V6 未打包批次：可替换 Linux 与更新来源（9/10）

- Linux 环境新增用户源设置：Alpine RootFS、APK 镜像、Debian RootFS 和 Debian APT archive/security 均可添加或删除，自定义源优先于内置源。
- Linux 源地址只接受无凭据 HTTPS；RootFS 支持完整文件地址或镜像根地址，APK/APT 地址直接进入现有安装脚本，固定 SHA-256 校验仍不可绕过。
- 软件更新检查新增持久化自定义 Release JSON 源，可恢复 GitHub 官方源；更新源拒绝 HTTP、凭据、片段和非标准端口，并禁止重定向。
- 现有本地模型直链下载、模型提供商、MCP/API/A2A、DSH 与插件主机继续保留各自独立配置入口；官方价格不可用时仍可手工覆盖价格。
- 定向证据：`LinuxSourceSettingsTest`、`AlpineEnvironmentInstallerTest`、`DebianEnvironmentInstallerTest`、`AppUpdateCheckerTest` 通过，Kotlin 编译通过。未访问真实自定义源，未做真机验收，未生成 APK。
- 本批完成后未打包计数为 9/10。

## 2026-11-12 V6 未打包批次：Gitee Skill 来源（8/10）

- Skill 来源新增公共 `gitee.com` 仓库与 `/tree/<ref>/<path>` URL 解析；搜索结果固定 commit，安装归档按该 commit 下载。
- Gitee 请求拒绝重定向和超限响应，目录最多 1000 项、候选最多 500 个；缓存、ZIP 安装、依赖、版本和权限继续复用现有统一链路。
- 来源配置、更新记录、市场搜索与安装分派均已识别 Gitee；添加来源界面现支持 GitHub、GitLab、Gitee 和 HTTPS 目录。
- 定向证据：`GiteeSkillRepositoryParserTest`、`SkillSourceStoreTest`、`SkillUpdateStoreTest` 全通过，Kotlin 编译通过。未访问真实 Gitee，未做真机验收，未生成 APK。
- 本批完成后未打包计数为 8/10。

## 2026-11-12 V6 未打包批次：GitLab Skill 来源（7/10）

- Skill 来源新增公共 `gitlab.com` 仓库与子分组 URL 解析；分支搜索结果固定到 commit，安装归档按不可变 commit 下载。
- GitLab 发现、归档下载均拒绝重定向和超大响应，目录最多扫描 1000 项、候选最多 500 个；继续复用现有 Skill 安装、依赖、版本、回滚和权限链。
- 来源配置、更新记录、市场搜索与安装分派已识别 GitLab；添加来源页面支持 GitHub、GitLab、HTTPS 目录三种类型。
- 定向证据：`GitLabSkillRepositoryParserTest`、`SkillSourceStoreTest`、`SkillUpdateStoreTest` 全通过，Kotlin 编译通过。未访问真实 GitLab，未做真机验收，未生成 APK。
- 本批完成后未打包计数为 7/10。

## 2026-11-12 V6 未打包批次：Skill 多镜像下载与统一校验（6/10）

- HTTPS Skill 目录条目新增 `archiveMirrors`，主归档失败后最多按 8 个镜像依次重试；单个镜像失败、重定向、超限、损坏或校验不符都不会终止其他可用镜像。
- 镜像地址统一限制为 HTTPS，禁止凭据、片段和非标准端口；每个成功的镜像文件都必须匹配目录声明的同一 SHA-256，无校验条目仍要求显式信任确认。
- 更新记录会持久化镜像列表，目录解析遇非法镜像会明确拒绝该条目。
- 完整备份迁移清单同步把 `eta_run_metrics.db` 的预期版本从 3 更新为 4，避免新指标数据库被备份恢复误判。
- 定向证据：`SkillArchiveSourcesTest`、`SkillCatalogParserTest`、`SkillUpdateStoreTest`、`EtaFullBackupArchiveTest` 全通过，Kotlin 编译通过。未访问真实第三方镜像，未做真机验收，未生成 APK。
- 本批完成后未打包计数为 6/10。

## 2026-11-12 V6 未打包批次：审批生命周期轨迹与统计（5/10）

- Runtime 会话直接观察真实审批状态，请求、通过、拒绝、超时、取消和记录失败进入同一事件与运行轨迹，不依赖 UI 是否可见。
- 运行统计按审批 ID 保留最新阶段，重复事件不会重复计数；总览按运行记录展示审批次数。
- 单次运行详情显示请求、通过、拒绝、超时、取消和记录失败六类结果；市场卡片同时标出可用镜像数。
- 统计数据库升级到 v4，为运行、模型和子 Agent 三类记录新增 6 个审批计数列，并提供 v3→v4 迁移；v1 基线实测迁移到 v4 后旧运行、模型、轨迹、价格与未知计费保持有效。
- 定向证据：`AgentRunMetricsTest`、`AgentMetricsMigrationTest`、`AgentRunMetricsStoreTest`、`NexusApprovalLifecycleTest` 全通过，Kotlin 编译通过。未做真机验收，未生成 APK。
- 本批完成后未打包计数为 5/10。

## 2026-11-12 V6 未打包批次：多厂商 Skill 清单兼容导入（4/10）

- 标准 `SKILL.md` 仍是最高优先级，不会被同目录的厂商规则文件覆盖；只有归档中完全没有标准 Skill 时，安装器才进入兼容转换。
- 兼容 `AGENTS.md`、`CLAUDE.md`、`GEMINI.md`、`CODEBUDDY.md`、`QWEN.md`、`.github/copilot-instructions.md`、Windsurf `.windsurfrules`、Cline `.clinerules`、Cursor `.cursor/rules/*.mdc`、Roo `.roo/rules/*.md` 和 Continue `.continue/rules/*.md`。
- 转换只在私有暂存目录生成带 `nexus_import_format` 与原始清单名的新 `SKILL.md`，正文保持 Markdown 且按惰性 Skill 内容处理，不执行原文件、不扩大工具权限。
- 原有 ZIP 安全上限、路径穿越防护、单一/批量候选限制、冲突识别、备份回滚、版本与来源机制继续共用，没有为厂商兼容新增旁路安装器。
- 定向证据：`SkillManifestCompatibilityTest` 3 项通过，既有 `SkillPackageInstallerTest` 21 项通过、2 项平台跳过；主模块 Kotlin 编译通过。未做真实仓库联网或真机安装，未生成 APK。
- 本批完成后未打包计数为 4/10。

## 2026-11-12 V6 未打包批次：审批恢复、权限放宽与会话设置入口（3/10）

- 进程重启时，执行账本会找出上一进程遗留、仍处于 `WAITING_AUTH` 的审批，先逐项记为 `APPROVAL_PROCESS_RESTARTED`，再把对应运行转为 `INTERRUPTED`。该路径从不自动批准旧操作，也不会重放外部副作用；再次执行必须由用户重新发起。
- 遗留审批对账单测覆盖单审批、并行审批、重复对账幂等、不通过旧授权放行，以及运行终态；与既有超时、用户暂停、取消、落库失败组共 45 项定向测试通过。
- 按用户最新授权，完全访问现在只把付款、转账、购买、充值、预算/费用等资金类操作保留为人工确认；Root 与非资金高风险操作直接放行。智能审批和手动审批也扩大到中风险写操作自动执行，对高风险、不可逆和外部副作用仍转人工。
- 会话输入框左下角原记事本按钮改为设置入口；会话记事本只保留聊天内容区顶部下拉/点击手势，减少底部重复入口。
- 证据：`NexusExecutionStoreTest`、`NexusApprovalLifecycleTest`、`NexusExecutionGateTest`、`AgentPermissionPolicyTest` 全通过；主模块 Kotlin 编译通过。未做真机安装，未生成 APK。
- 本批完成后未打包计数为 3/10。

## 2026-11-12 V6 未打包批次：权限控制面一致性（2/10）

- `eta_control` 的 list/search/get/audit 根据实际 action 降为只读；execute/undo 不再在外层与内层重复审批，而由统一软件控制面按目标能力确认一次。
- 新增 `state.agent_permission` 和 `settings.agent_permission`：Agent 可读取当前模式；修改权限始终是 CONFIRM 能力，任何模式都不能静默自我升级。
- Gate 测试覆盖只读直通、写操作内层委托和权限模式安全边界；相关策略与控制面测试通过。
- 根据用户新要求，本批不生成 APK，累计达到 10 个大的功能更新后统一打包。

## 2026-11-12 V6 执行批次：Root 默认身份与审批信息

- 终端/文件工具在 Root 可用且未显式选择 user 身份时，即使参数省略 identity，也强制人工确认。
- 审批参数从整段 JSON 展开为命令、路径、网址、应用、目标、环境、身份和金额字段；敏感字段隐藏。
- 三级权限、FIFO 审批队列与权限模式备份保持有效；0.6.8 正式签名包已生成。

## 2026-11-12 V6 执行批次：权限审批中心闭环

- 三级权限统一控制工具 Gate 和软件控制面；完全访问仅受资金与 Root 边界限制。
- 智能审批按副作用等级自动/人工分流；手动审批除只读外全部等待用户。
- 并发审批改成 FIFO 队列，支持多个子 Agent/会议成员同时请求，不再抛 `CONFIRMATION_BUSY`。
- 审批弹窗显示队列总量、风险、工具、环境、参数和期限；权限模式持久化并进入备份。
- 定向策略、Gate、控制面、审批队列与恢复测试通过；0.6.7 正式签名包已生成。

## 2026-11-12 V6 执行批次：三级权限与审批页

- 新增 `FULL_ACCESS`、`SMART_APPROVAL`、`MANUAL_APPROVAL` 三级持久化权限模式。
- 工具 Gate 与 `eta_control` 软件控制面共用策略；资金与 Root 操作在所有模式人工确认。
- 完全访问自动放行其他非资金操作；智能审批对不可逆、外部副作用和危险操作转人工；手动审批仅放行只读。
- 权限页加入三级模式选择；审批弹窗改为风险横幅、操作摘要、工具/环境/参数/期限字段化展示。
- 权限模式和现有设置一起进入备份恢复；策略、Gate、控制面和备份定向测试通过。
- 0.6.6 / 2026111227 正式签名包已生成。

## 2026-11-12 V6 执行批次：旧 Runtime IPC 能力收紧

- `AgentRuntimeWire.runRequestFromBundle` 在缺少 `supports_tools`/`supports_image_input` 时由默认开启改为默认关闭。
- 与模型数据库和 Provider 请求边界统一，避免旧会话恢复、语音重放和跨进程请求重新启用未验证能力。
- `AgentRuntimeWireTest` 通过；0.6.5 正式签名包已生成。

## 2026-11-12 V6 执行批次：终端快照差分

- 前台 terminal 在执行前保存有界工作区快照，执行后按新路径或 size/mtime 变化识别产物，覆盖解压/复制保留旧时间的情况。
- 后台 terminal_job/daemon 继续按真实 started_at 扫描；环境、deliver_path、上限与只读发布规则保持一致。
- 0.6.4 / 2026111225 正式签名包已生成，快照与后台文件回执测试通过。

## 2026-11-12 V6 执行批次：全局并发状态反馈

- 全局 6 个子 Agent 名额不足时立即记录排队位置；取得名额后记录准入和等待时间。
- 单个与并行派发都会向现有运行轨迹发送 `subagent_queued`/`subagent_admitted`，避免静默卡住。
- 排队、准入、并发上限、取消等待测试和 Agent Loop 回归通过；0.6.3 正式签名包已生成。

## 2026-11-12 V6 执行批次：子 Agent 全局并发闸门

- `AgentLoop` 单 Run 的默认 3、可调 1–8 并发限制保留；新增进程级 6 个实时子 Agent 名额，避免多个会话叠加失控。
- 普通、计划和目标入口均经同一 AgentLoop；取消等待中的子任务会立即退出，工具与费用预算仍由原 Guard 管理。
- `SubagentConcurrencyGateTest` 和 `SubagentTest` 通过；0.6.2 正式签名包已生成。

## 2026-11-12 V6 执行批次：多环境文件交付回执

- 前台 terminal、后台 terminal_job/daemon 的文件回执补上 `environment` 和 `deliver_path`；Linux 映射 `/workspace/...`，Android 使用工作区相对路径。
- 系统提示与终端工具契约要求模型原样把回执路径交给 `deliver_file`，回执本身仍不发布文件。
- `RunArtifactCaptureTerminalTest` 覆盖 Android 路径、Linux 路径、旧文件排除、后台真实启动时间与失败保持。
- 0.6.1 / 2026111222 正式签名包已放桌面：`C:\Users\29136\Desktop\Nexus-v0.6.1-release.apk`，61,926,272 字节，SHA-256 `468a93a906818e698ec14a3248a9b9ea81f143285f111c9a965ec787fdec33f4`；ADB 无设备。

## 2026-11-12 V6 执行批次：模型能力快照与上下文迁移

- 用户已确认 V6 计划并建立持续目标；本批从 P1/D02 的模型主链开始，先修“目录声明了能力但 Runtime 没有拿到”的根因。
- 官方模型目录修订升级到 5：所有内置模型把已核对的上下文窗口同步为有效运行窗口；旧安装迁移会补齐内置目录旧模型和服务端明确返回的远程窗口，不覆盖用户已有覆盖，也不替手工模型或未知模型猜测窗口。
- 内置模型完整性门禁现在逐模型检查正数上下文、工具能力、文本输入输出，以及图片模态与视觉能力的一致性；工具能力的未知状态仍关闭，只有明确支持才向 Provider 发送工具定义。
- 定向证据：`OfficialModelCatalogTest`、`ProviderRepositoryTest`、`RuntimeConfigRepositoryTest`、`RemoteModelFetcherTest`、`GeminiModelCatalogHttpTest` 共 56 项，全部通过；使用 `--no-configuration-cache` 得到成功退出。常规运行曾因既有 `verifySherpaRuntime` 任务不能序列化配置缓存而使测试命令以基础设施错误结束，测试本身无失败，配置缓存问题仍待单独修复。
- 当前状态：源码级模型能力与迁移首链通过，尚无真实 Provider/Android 16/17 真机证据；本节不关闭 D02，也不宣称普通会话或会议室工具调用已在外部服务验证。
- 后续补充：运行与回退选择现统一要求服务商具备 API 凭据或可接受的本地/OAuth 传输，未配置在线连接不会再进入可用模型链；提供商选择本身仍可保留用于配置和登录。相关选择器、提供商迁移和运行时三组 40 项通过。
- 构建基础设施：`verifySherpaRuntime` 改为从 TaskInputs 读取已验证文件，Gradle 配置缓存可正常存储；这是发布构建可靠性修复，不代表 Android 真机验证。
- 0.5.5 / 2026111216 正式签名包已放桌面：`C:\Users\29136\Desktop\Nexus-v0.5.5-release.apk`，字节 61,917,572，SHA-256 `9631d3183bb82c3e9e080839c316902405b202046f1bcfdd6e4ccad18eb965e9`；其余核验与限制见交付说明。
- 目录修订 6 补齐旧内置模型缺失的工具、视觉、推理和结构化输出能力。迁移只填 `null`；用户显式关闭继续为 false，手工模型不扩权。该链直接修复“升级后内置模型目录显示支持工具、Runtime 却始终不发送工具”的旧数据问题。
- 0.5.6 / 2026111217 正式签名包已放桌面：`C:\Users\29136\Desktop\Nexus-v0.5.6-release.apk`，字节 61,918,040，SHA-256 `8f86a74cf765004bb0cdb609a906d095b584d4b1731be2d17d1fd282ce8098fc`；正式签名、非 Debug、v2/v3、ARM64、16 KiB、关键组件和 16 个原生库通过，ADB 无设备。
- 兼容回退修复：Chat Completions 与 Responses 原先第二次 400 时会无条件移除工具，可能把图片、思考或结构化输出错误伪装成工具降级。现仅当错误明确涉及 tool/function/tool_choice 时移除；非工具错误保留工具并返回诊断。新增图片输入与结构化输出诊断提示。
- 0.5.7 / 2026111218 正式签名包已放桌面：`C:\Users\29136\Desktop\Nexus-v0.5.7-release.apk`，字节 61,918,744，SHA-256 `974c30959973c1d1c466bdf43d1324ea7ef3ad9f6bb3ad56a326c17d676da227`；正式签名、非 Debug、v2/v3、ARM64、16 KiB、关键组件和 16 个原生库通过，ADB 无设备。
- 后台用途模型路由：`ModelRoleSettings` 新增记忆、自学习、语音整理三组独立 provider/model 绑定；运行时按“用途专用 → 全局辅助 → 当前聊天”解析。记忆 Worker、SkillEvolution Worker、SpeechTranscriptRefiner 已使用对应配置；操作台与完整备份包含新字段；提供商设置页新增三行模型选择。
- 0.5.8 / 2026111219 正式签名包已放桌面：`C:\Users\29136\Desktop\Nexus-v0.5.8-release.apk`，字节 61,926,128，SHA-256 `e56eaf2a6b1e016067ce86f2efcf45707e396bc0809fecf2b17527e9f08f923f`；正式签名、非 Debug、v2/v3、ARM64、16 KiB、关键组件和 16 个原生库通过，ADB 无设备。
- 终端/Linux 前台命令完成后新增受控文件回执：按命令开始时间筛选工作区新文件，最多 24 项、深度 8、扫描 4000 条目，排除构建/依赖/隐藏工具目录；回执仅含相对路径、大小和时间，不自动发布，不绕过 `deliver_file`。
- 0.5.9 / 2026111220 正式签名包已放桌面：`C:\Users\29136\Desktop\Nexus-v0.5.9-release.apk`，字节 61,922,112，SHA-256 `19a5de7b69cd45653a28cfdcd614bc8eb008cdb3aba15507a56738e933000427`；正式签名、非 Debug、v2/v3、ARM64、16 KiB、关键组件和 16 个原生库通过，ADB 无设备。
- 后台终端文件回执：`terminal_job` 的 start/read/wait 与 `terminal_daemon` 的 start/logs 可用任务 JSON 中的真实 `started_at` 扫描产物；仍不自动发布。前台和后台回执用例均通过。
- 0.6.0 / 2026111221 正式签名包已放桌面：`C:\Users\29136\Desktop\Nexus-v0.6.0-release.apk`，字节 61,922,432，SHA-256 `81577709ba2d0602b4ea1ee965575c5252f16c02e5d534b4afdf0e04173307e6`；正式签名、非 Debug、v2/v3、ARM64、16 KiB、关键组件和 16 个原生库通过，ADB 无设备。
- 下一步：核对主模型、备用模型、独立压缩模型、辅助任务、子 Agent、普通会话和会议室是否全部从同一运行时能力快照读取，并继续处理可复现的请求字段兼容失败。

## 2026-11-12 V6 正式签名候选交付（0.5.4）

- 0.5.4 / 2026111215 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.5.4-release.apk，61,917,352 字节；SHA-256 `decb9fd088fd3c16bed4acbd9385f367991a9861035383f447d2792148ab1b3d`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.5.4/`。
- 修复模型工具能力未知时的配置矛盾：界面把未知显示为关闭，Runtime 原先却按支持处理并发送工具字段。现在只有 `toolCall=true` 才启用工具；未知和关闭均不发工具定义，避免手动模型首次请求出现 Provider HTTP 400。
- 模型设置说明同步为“只有明确开启才发送”，内置目录、社区目录和本地模型已有的明确能力继续生效。
- 定向证据：`RuntimeConfigRepositoryTest` 7 项、`OpenAiChatCompletionsProviderTest` 19 项、`OpenAiResponsesProviderTest` 12 项，共 38 项通过，覆盖配置映射、Chat Completions 工具移除与 Responses 工具结构。
- 源码备份位置和最终 SHA-256 见 `F:\eta\source-backups\README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机；真实 Provider 400 行为与 Android 16/17 真机由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.5.3）

- 0.5.3 / 2026111214 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.5.3-release.apk，61,917,560 字节；SHA-256 `c9231a8ff1d5a257f67eaa41e9186b4c1d0c85df68a1220634418737e6fc21d6`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.5.3/`。
- 修复完整备份被误拒：会话扩展数据库和会议室数据库当前版本为 6，迁移清单仍写 5。清单已同步，并新增测试直接比较实际 Room 数据库版本与迁移清单，防止后续再次漂移。
- 便携秘密改为分类恢复：Provider、MCP、REST、包环境、连接器、A2A、语音各自独立；一项损坏不会阻断其他类别。失败时临时凭据文件保留到下一次启动重试。
- 定向证据：`EtaMigrationPlanVersionTest` 2 项、`PortableSecretsRestoreTest` 1 项、`ApiConnectorPortableRestoreTest` 2 项、`EtaFullBackupArchiveTest` 34 项，共 39 项；38 项通过、1 项按既有平台条件跳过、0 失败/错误。中途发现的版本清单和过时测试断言均在同一轮修正后才通过。
- 源码备份位置和最终 SHA-256 见 `F:\eta\source-backups\README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机；真实完整归档、跨设备 SAF、Android 16/17 重启恢复由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.5.2）

- 0.5.2 / 2026111213 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.5.2-release.apk，61,914,980 字节；SHA-256 `fe498a23043fa770db8517f3cf54899c3c9a5d6ef5c45754249cd2a44d8f2bfe`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.5.2/`。
- 自动任务蓝牙触发支持指定设备：留空匹配任意蓝牙设备；填写设备地址或名称时，只运行对应连接/断开任务。WorkManager 事件数据会把设备地址和名称传给后续 Agent/Skill 工作流。
- 定向证据：`AutomationTriggerConfigTest`、`AutomationEventMatcherTest` 和 `AutomationNetworkTrackerTest` 全部通过，覆盖设备匹配、错误来源拒绝及多网络切换。
- 源码备份位置和最终 SHA-256 见 `F:\eta\source-backups\README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机；真实蓝牙设备、ColorOS 17 后台保持和系统杀进程恢复由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.5.1）

- 0.5.1 / 2026111212 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.5.1-release.apk，61,915,780 字节；SHA-256 `07f7cd6c699fe6ee60372bd5ce6013a7d4c69757cb5209444d581219f7d31050`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.5.1/`。
- 记忆与自学习修复：文档、自学习、策略和记忆库保持同一层标签；离开记忆库时保存首项索引与偏移，返回后等待条目加载并恢复，不再跳回顶部或旧设置页。文档与自学习子树使用独立保存容器。
- UI 回归新增真实大量记忆数据、滚动到底部、进入文档、系统返回并确认原位置仍可见；同一测试还覆盖文档、自学习、策略和返回栈。`MemoryLearningPresentationTest` 5 项全部通过。
- 源码备份位置和最终 SHA-256 见 `F:\eta\source-backups\README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机；手机字体缩放、系统返回手势和长期使用由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.5.0）

- 0.5.0 / 2026111211 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.5.0-release.apk，61,915,840 字节；SHA-256 `059dc16ca87859e71388b256d042c9180ef758bd31d7d381217655c4423c72cc`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.5.0/`。
- REST 连接元数据损坏时，凭据备份层降级为跳过并计数，完整备份继续包含原始损坏文件和其他可恢复数据；不再因为该文件损坏阻断整份备份。
- 停用 MCP 改为先等待进程树退出，确认停止后才更新数据库状态；停止失败时保留原启用状态，避免后台服务与界面状态不一致。
- 定向证据：`ApiConnectorPortableRestoreTest`、`McpProcessLifecycleTest`、`McpServerLifecycleServiceTest`、`EtaAppControlRegistryTest` 和 `SettingsRollbackCapabilityTest` 全部通过；此前 MCP 进程与凭据恢复 10 项也已通过。未重复全量回归。
- 源码备份位置和最终 SHA-256 见 `F:\eta\source-backups\README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机。跨设备完整恢复、ColorOS 后台限制和设备重启后的残留进程由用户真机验收。

## 2026-11-12 V6 正式签名候选交付（0.4.9）

- 0.4.9 / 2026111210 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.9-release.apk，61,915,704 字节；SHA-256 `98569d27f4d4d9be47bf5e3e45f48f9dfa66066759a80bace83f65a69f568d36`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.4.9/`。
- MCP 启动现在记录实际 PID。删除 MCP 服务时从 `/proc` 递归识别子进程，先发送正常终止信号，再强制清理；只有全部退出后才删除数据库记录和加密凭据，否则保留配置供重试。
- MCP 的导入、导出、发现、刷新、调试调用和删除改为保留协程取消语义；删除失败会在页面提示，不再由未捕获异常中断页面。
- 定向证据：`McpProcessLifecycleTest` 2 项、`McpServerLifecycleServiceTest` 2 项、`McpCredentialRecoveryTest` 6 项，共 10 项通过。没有重复全量回归。
- 源码备份位置和最终 SHA-256 见 `F:\eta\source-backups\README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机。ColorOS 后台限制、真实厂商进程回收和长期运行由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.4.8）

- 0.4.8 / 2026111209 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.8-release.apk，61,910,732 字节；SHA-256 `10295a1f32ada043e0a1526a7ceead516eca1c4c177f3a395d71906b9c554399`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.4.8/`。
- REST 连接配置损坏时现在明确失败并保留原文件；普通修改通过共享锁串行化，不会再以空列表静默覆盖。OAuth 凭据清理失败会传播并触发元数据回滚；Bearer/OAuth 可以仅清除本地凭据而保留已审核的连接与操作配置。
- 删除 MCP 服务会先停止 App 拥有的本地进程，再删除数据库记录及加密凭据；UI、软件管家和便携配置回滚统一走同一生命周期入口。
- 定向证据：连接相关的 OAuth、工具主机、便携配置、MCP 凭据恢复等 39 项中，37 项首轮通过；两项重复 SDK 用例只因测试直接调用 `save(emptyList())`，改为真实 `upsert` 后 `ApiConnectorStoreTest` 全部通过。未重复全量回归。
- 源码备份位置和最终 SHA-256 见 `F:\eta\source-backups\README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机；桌面包未安装。Android 16/17、ColorOS 17、真实 OAuth 服务端撤销和 MCP 长期后台行为由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.4.7）

- 0.4.7 / 2026111208 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.7-release.apk，61,911,904 字节；SHA-256 `502782f8b6f63a5fedc258a371d747d53d9c7bcfa544f2d8525a9ffcf1c476b8`。
- 正式核验通过：包名 `io.github.zhh_lgtm.nexus`、Release 非 Debug、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库和关键组件齐全；签名证书 SHA-256 与前版相同：`0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。报告位于 `build/reports/nexus-delivery/release-0.4.7/`。
- 本版补齐插件/沙盒包完整卸载：清理包创建的 Provider、凭据、模型选择和绑定文件，清理桌面 Widget 绑定、通知、环境变量、状态和版本目录。清理先于包索引删除，外部步骤失败会保留记录供重试。
- 定向检查：首次四组 21 项中，20 项通过；两项新增用例暴露 Robolectric 无手机密钥库，随后把包环境存储改为可注入依赖，剩余 `PackageAiProviderTest` 所属 9 项全部通过，包含真实 Room Provider、API Key、模型选择、环境、状态、绑定和版本文件清理。未重复全量回归。
- 源码备份：本批最终快照保存在 `F:\eta\source-backups`，文件版本与 SHA-256 见该目录的 `README.md`；快照不含 build/.gradle/.git/.kotlin/.analysis/.cxx/APK/AAB。
- ADB 当前为空，没有可用手机；桌面包未安装。Android 16/17、ColorOS 17 和实际 UI 操作由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.4.6）

- 0.4.6 / 2026111207 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.6-release.apk，61,909,076 字节；SHA-256 bef52abcfa773252e6ec74966b0efa65559862d050fef32f6884967c745b5d32。
- 正式核验通过：包名、版本、Release 非 Debug/非 TestOnly、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库与关键组件齐全；签名证书 SHA-256 与前版相同。报告位于 build/reports/nexus-delivery/release-0.4.6/。
- 本版包含 DeepSeek 峰时/非峰时官方价格和 OpenAI/DeepSeek 双源每周刷新。ADB 当前没有可用手机，包保留桌面；后台到达和节假日价格例外由用户验收。

## 2026-11-12 DeepSeek 官方峰时价格

- DeepSeek 官方价格页已实际读取并核对：Flash 与 V4 Pro 分别保存缓存命中、缓存未命中、输出的峰时和非峰时费率，单位为 USD/百万 Token。
- Runtime 按官方 UTC 规则动态选择：周一至周五 01:00–04:00、06:00–10:00 使用峰时价，其他时间使用非峰时价。手动价格始终优先，用户删除后不会恢复官方值。
- 每周 WorkManager 现在同时检查 OpenAI 和 DeepSeek 固定官方页面；任一来源失败时保留对应旧值。开发时实际读取 DeepSeek 页面并修正了“表格每行先 Flash、后 Pro”的列序，避免两模型峰时费率交叉。
- 已知限制：中国法定节假日例外尚未在本地日历校准，节假日 UTC 工作日峰时可能按峰时估算；手机每周任务到达仍需真机验收。
- 定向证据：AgentOfficialPriceCatalogTest 4 项与 AgentTokenPricingTest 9 项通过，覆盖峰时/非峰时选择、官方解析、手动覆盖、删除墓碑和费用计算。

## 2026-11-12 V6 正式签名候选交付（0.4.5）

- 0.4.5 / 2026111206 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.5-release.apk，61,907,504 字节；SHA-256 61ffc8206710bbbee004071eca4fcdd28071f18644798603e76992068a3e83f2。
- 正式核验通过：包名、版本、Release 非 Debug/非 TestOnly、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库与关键组件齐全；签名证书 SHA-256 与前版相同。报告位于 build/reports/nexus-delivery/release-0.4.5/。
- 本版包含 OpenAI 官方价格预置、手动覆盖保护、删除墓碑与每 7 天官方页面检查。ADB 当前没有可用手机，包保留桌面；后台调度到达与长期页面稳定性由用户验收。

## 2026-11-12 官方 Token 价格预置与每周刷新

- 按 OpenAI 官方 API 价格页核对了内置 `gpt-6.1-sol`、`gpt-6-luna`、`gpt-6-astra` 的输入、缓存输入和输出 USD 单价，内置回退值记录核验日期与来源 `https://developers.openai.com/api/docs/pricing`。

- 价格页现在区分“官方 YYYY-MM-DD”和“手动”。官方预置只在没有手动价格时写入，绝不覆盖用户手动价格；用户删除官方价格后使用本地墓碑阻止自动恢复。

- 新增每 7 天一次、需要联网的 WorkManager 检查。只访问固定官方 HTTPS 页面，限制响应大小、超时和主机；页面布局变化、网络失败或模型行缺失时保留旧价格并显示失败状态，不会写猜测值。

- 定向证据：`AgentOfficialPriceCatalogTest` 2 项、`AgentTokenPricingTest` 9 项通过，覆盖官方值、手动价格保护、删除墓碑、页面结构解析和未知/部分费用计算。开发时实际抓取了当前官方页面，三个内置模型的输入/缓存/输出行与预置一致；手机后台每周到达与真实 WorkManager 执行仍待用户验收。

## 2026-11-12 V6 正式签名候选交付（0.4.4）

- 0.4.4 / 2026111205 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.4-release.apk，61,903,332 字节；SHA-256 80e9c7424408383a22b7d375444cba0789ad0791513a8dc0d7bdf7455db4c55c。
- 正式核验通过：包名、版本、Release 非 Debug/非 TestOnly、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库与关键组件齐全；签名证书 SHA-256 与前版相同。报告位于 build/reports/nexus-delivery/release-0.4.4/。
- 本版包含 `deliver_file` 受控交付工具。ADB 当前没有可用手机，包保留桌面；手机 SAF 与 Agent 主动交付稳定性仍由用户验收。

## 2026-11-12 终端成果显式交付工具

- 新增 `deliver_file` 工具：Agent 可把终端、Linux 或文件工具在 Nexus 私密工作区生成的普通文件登记为 Artifact，并复制到用户已启用的系统可见目录/项目/会话。

- 工具只接受私密工作区内文件、拒绝符号链接和目录、限制单文件 32 MiB；自动归档关闭、总目录未配置或授权失效时返回明确错误，不把“已登记 Artifact”冒充“已在系统文件可见”。

- 提示词要求 Agent 在终端或 Linux 命令生成用户成果后主动调用该工具；结果会返回 Artifact ID、真实相对路径、系统可见 URI 和可见路径。此路径不扩大文件权限，也不能用于导出任意设备敏感文件。

- 定向证据：`GeneratedFilesArchiveTest` 3 项、`AgentLocalToolsPermissionTest` 22 项通过，覆盖实际工具调用、受控 DocumentsProvider 写盘/读回、Artifact Ledger、可见路径返回和完整本地工具权限目录。任意终端输出仍不会按时间扫描猜测归属。

## 2026-11-12 V6 正式签名候选交付（0.4.3）

- 0.4.3 / 2026111204 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.3-release.apk，61,902,336 字节；SHA-256 78a994ea3644eb9d4700f6ad751e38fc4c19d8d200a3f7859c5bd46dc9ad8bda。
- 正式核验通过：包名、版本、Release 非 Debug/非 TestOnly、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库与关键组件齐全；签名证书 SHA-256 与前版相同。报告位于 build/reports/nexus-delivery/release-0.4.3/。
- 本版包含会议目标与子 Agent 父会话恢复。ADB 当前没有可用手机，包保留桌面；真实进程强杀、会议模型和长期使用仍由用户验收。

## 2026-11-12 会议与子 Agent 输入恢复

- Runtime 输入请求现在同时保存父会话、会议目标、恢复表面和子 Agent 身份。会议运行的问题按原会议室目标恢复，回答后由会议运行协调器启动新一轮，不会错误进入普通聊天。

- 子 Agent 的问题记录父会话与子 Agent 身份；进程回收后用户回答会回到父会话，提示先核对已完成工作，并仅在已保存上下文存在时恢复子 Agent。Runtime 子运行创建时由父运行注入受信任的会话、目标和恢复表面，不接受模型参数。

- `request_user_input` 恢复范围从普通会话/目标扩展到会议主持、会议参与者及其子 Agent；自动任务和 A2A 仍不自动续跑，因为断点后的外部副作用无法由输入恢复本身证明安全。

- 定向证据：`RunUserInputTest` 8 项、`MeetingRunCoordinatorQueueTest` 2 项通过，覆盖普通、目标、会议目标重建、子 Agent 返回父会话、排队与停止语义。未做真机强杀进程、真实会议模型和外部服务验收。

## 2026-11-12 V6 正式签名候选交付（0.4.2）

- 0.4.2 / 2026111203 已生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.2-release.apk，61,898,400 字节；SHA-256 323538c4fb6c3802f1f256067cec26c4353d1be21a7cedb3be635217a9f65cf2。
- 正式核验通过：包名、版本、Release 非 Debug/非 TestOnly、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库与关键组件齐全；签名证书 SHA-256 与前版相同。报告位于 build/reports/nexus-delivery/release-0.4.2/。
- 本版包含普通会话/目标输入进程恢复、SAF 统一可见文件根、Artifact Ledger 自动归档、已验证 Skill 自动发布与通知。ADB 当前没有可用手机，包保留桌面；真机安装与长期使用仍由用户验收。

## 2026-11-12 V6 正式签名候选交付（0.4.1）

- 0.4.1 / 2026111202 已从包含输入恢复首链的源码生成正式签名候选并放至桌面：C:\Users\29136\Desktop\Nexus-v0.4.1-release.apk；SHA-256 b4f713e20f7e0f97580ad1b40ddc31f5085b156f0bcf440260969a3c6f0c589e，APK 61,889,140 字节。
- 正式核验通过：包名、版本、Release 非 Debug/非 TestOnly、v2/v3 签名、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库与关键组件齐全；签名证书 SHA-256 与 0.4.0 相同，为 0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd。报告位于 build/reports/nexus-delivery/release-0.4.1/。
- ADB 在打包后检查为空，没有可用手机；本版仅保留桌面 APK、SHA256 和交付说明，没有宣称已安装或真机通过。

## 2026-11-12 统一可见文件根首链

- 设置“项目与数据”增加“文件工作区”真实入口。用户可选择一个可写 SAF 总目录，并独立启用或暂停自动归档；授权使用 `takePersistableUriPermission`，只有读写权限都持久化后才保存成功。
- 原 Artifact Ledger 的每个已验证文件在登记时都会尝试额外发布到“总目录/项目标题/会话标题/文件”。项目与会话优先取真实数据库标题；无项目进入“未归类会话”，无标题进入“未命名会话”。同名文件使用带序号的可见名称，不覆盖已有文件。
- 发布使用临时 `.part` 文档、流式复制和发布后重命名，单文件上限 32 MiB；私密工作副本、原 Artifact 路径、执行回执和媒体渲染链保持原样。可见目录授权与 URI 属于本机状态，不进入跨设备配置备份。
- 定向证据共 7 项通过：名称与重名规划 2 项、受控 DocumentsProvider 的真实建目录/写文件/重命名/读回和 Artifact Ledger 接入 2 项、原外部附件捕获相关 3 项；Debug Kotlin 与资源编译通过。尚未在手机系统文件管理器选择真实目录，也未覆盖仅由终端命令创建且没有登记 Artifact 的文件。

## 2026-11-12 已验证 Skill 自动发布首链

- 经验 Skill 保留原有门槛：至少 3 个独立任务、实际成功工具结果、来源边界、去重合并、软件管家质量审核和敏感内容检查。达标候选默认自动安装并启用最新版本，不需要用户再点一次确认。

- 自动发布设置已进入统一设置控制面，默认开启；关闭后候选保持在“已验证，等待自动发布”，仍可手动发布。发布前继续备份当前版本，成功后保存新版本正文、来源 Run、工具边界和版本号，旧版可回滚。

- 发布结果使用应用内持久提示，技能页会显示“已自动发布《技能》· vN”；重新进入页面仍可看到，关闭后不再打扰。自动发布会先恢复未完成发布日志，并只在持有原备份互斥锁的后台整理流程内执行，避免与用户手动发布并发。

- 定向证据：`SkillEvolutionTest` 9 项、`EtaAppControlRegistryTest` 5 项全部通过，覆盖自动发布开关、三条独立证据、真实安装与索引启用、版本历史、来源、回滚基础和应用内通知。真实外部模型的提炼质量、手机后台调度长期到达率仍需用户验收。

## 2026-11-12 V6 正式签名候选交付（0.4.0）

- `0.4.0 / 2026111201` 已从当前源码生成正式签名候选包并放至桌面：`C:\Users\29136\Desktop\Nexus-v0.4.0-release.apk`；SHA-256 文件同名以 `.sha256` 结尾。APK 为 61,883,144 字节，SHA-256 `961237cb60af0436edf345a1dae0fe64369c2be5aac19bc8486dbe429661007d`。
- 交付核验已实际通过：包名 `io.github.zhh_lgtm.nexus`、版本 `0.4.0` / `2026111201`、Release 非 Debug、非 TestOnly、v2 与 v3 正式签名、证书 SHA-256 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`、ZIP 16 KiB 对齐、仅 ARM64、16 个原生库及关键服务/无障碍/语音/插件隔离组件齐全。核验报告位于 `build/reports/nexus-delivery/release-0.4.0/`。
- 本次包含模型实际 `providerId + modelId` 选择保护、DeepSeek 用户模型 ID 原样发送与工具 Schema 保留、模型图片能力本机守卫、主备模型紧凑选择、提供商真实连接测试与健康状态、全局/会话/会议独立压缩模型及失败回退、统计自定义范围与图表聚合、自动备份非计费网络条件、视频/音频播放保存分享、记忆标签返回和备份操作状态等已记录改动。
- 首次打包的 Gradle Release 编译与 `assembleRelease` 成功；随后交付脚本因 PowerShell 未加载 ZIP 类型库而中止。已只修正 `verify-release.ps1` 的加载语句，并对同一个 APK 重新跑完整核验和桌面复制，未更换签名、包名或重新改写业务代码。
- 证据级别为 Release 编译与 APK 交付核验；没有连接当前手机安装，也没有替用户宣称真实外部模型、真实媒体解码/保存分享、无障碍长期存活、工具实际调用或 Android 16/17 真机兼容通过。P1 与 D01-D14 总体目标仍未完成。

### 普通会话与目标等待输入的进程恢复首链

- `request_user_input` 的问题正文、选项、是否允许自由文本、来源、运行和会话身份现在一起写入既有私有修订存储；执行账本继续作为未解决请求的权威来源。进程被回收后，应用先恢复 runtime checkpoint/history，再从执行账本重建未回答问题，不会把用户取消或已回答的问题重新弹回。
- Runtime 控制器接管受信任的会话身份并写入输入请求；恢复后由用户明确回答，以可见的新一轮输入从已保存 transcript/context 继续，不使用隐藏的伪造工具回执。回答只在新一轮运行实际获准启动后写入本地恢复标记；启动失败或会话正忙时请求保留。恢复标记最多保留 256 条，只记录请求 ID、阶段和时间，不保存回答正文。
- 当前恢复范围明确为 `CHAT` 与 `GOAL`。会议、子 Agent、自动任务、A2A 等专用执行上下文仍需各自接入续跑所有权，不能把会议任务错误续成普通聊天，因此没有用普通会话路径冒充全入口完成。
- 定向证据：`RunUserInputTest` 与 `AgentRunRecoveryCoordinatorTest` 共 12 项通过，覆盖原问题等待/超时/取消、checkpoint 对账，以及“问题被进程中断后重新出现、回答前不能被旧路径消费、续跑开始后消费、再次恢复不重复弹出”。首次测试发现执行条目 ID 与问题请求 ID 混用，已修正后重跑通过；未做真机强杀进程和外部模型长任务验收。

## 2026-11-12 v6 正式执行基线与模型选择修复

- 独立压缩模型主链已落地：全局可配置辅助模型与压缩模型，压缩优先使用显式模型，未设置时继承辅助模型，再无有效配置时使用当前聊天模型。自动压缩和手动压缩都从 Runtime 解析同一角色配置；摘要分块预算按独立压缩模型窗口计算，摘要长度目标仍受当前聊天模型窗口约束。
- 会话和会议室覆盖已落地。普通会话长按上下文圆圈可在同一弹窗内逐级选择提供商和模型，会议室管理菜单新增“本会议压缩模型”；选择都可恢复“跟随全局”。Runtime 按“会议覆盖→会话覆盖→全局压缩→辅助模型→当前聊天模型”解析，跨进程 Runtime 和进程内兜底共用这一顺序。
- 会话扩展库与会议室库升级到 v6，新增压缩模型/提供商列并保留原表数据；绑定持久化、迁移、会话/会议往返和覆盖优先级均完成定向检查。
- 独立压缩模型请求失败时，会在原上下文仍完整的条件下使用当前聊天模型重做一次摘要，并产生 `FALLBACK_TO_CHAT_MODEL` 完成事件，界面明确显示“独立压缩模型不可用，已使用当前聊天模型完成压缩”。本地上下文不可压缩、范围非法或无法缩小等非模型故障不会误触发换模型。
- 模型提供商页新增“处理模型”分组，提供辅助模型和上下文压缩选择，沿用同一套提供商→模型层级选择器。压缩行显示继承来源；清除显式压缩模型会恢复继承，不会隐藏实际生效模型。
- 角色绑定已接入统一设置控制面、快照/回滚、模型有效性校验，并写入 JSON 备份；旧备份缺少该字段时保留本机现有角色设置。
- 定向证据：`RuntimeConfigRepositoryTest`、`AgentContextCompactionTest`、`EtaAppControlRegistryTest`、`EtaBackupRepositoryTest.modelRoleBindingsRoundTripWithoutChangingProviderSecrets`、`ConversationExtensionDatabaseTest`、`MeetingArchiveDatabaseTest`、`AgentRuntimeLocalFallbackTest`、`ProviderConnectionHealthTest`、`ProviderRepositoryTest`、媒体产物/消息投影/会议时间线相关检查通过；`:app:compileDebugKotlin` 通过。真实外部模型压缩、连接探测、真实媒体文件保存分享和手机界面验收仍未完成。
- 模型图片能力不再是只影响编辑页的孤立字段：`supportsImageInput` 已进入运行时配置和 Runtime IPC。文本模型收到图片时会在本机返回 `MODEL_IMAGE_INPUT_UNSUPPORTED`，不会把不兼容内容发送给服务端后再得到 HTTP 400。`RuntimeConfigRepositoryTest`、`AgentRuntimeWireTest`、`AgentModelClientLoopTest` 相关回归通过。
- 模型提供商页的主模型与备用模型改为同一套紧凑行：角色、模型、提供商分三层排布，不再重复显示角色或使用两套高矮不同的组件。备用模型只从当前已启用且连接可用的提供商中选择，避免选到未配置 API/失效账号。`:app:compileDebugKotlin` 通过。
- 修复 DeepSeek 官方连接提前改写用户模型 ID 的问题：选择 `deepseek-flash`、V4 Pro 等模型时，请求现在保持用户实际选择的 ID 和工具目录；只有服务端明确返回模型不存在时才尝试长期兼容的 `deepseek-chat`，不再预先替换成可能不支持相同工具合同的 reasoner。专项回归同时验证模型 ID 与函数工具 Schema 都出现在请求中。
- 运行统计补齐自定义周期：支持今天、最近 7 天、最近 30 天、自定义起止日期和全部记录；自定义范围按本地日历日包含首尾。Token 折线等长区间图表聚合为最多 10 个时间桶，标签显示真实起止日期，不再把十几到几十个日期全部挤在窄屏。范围边界、自定义日期、窗口天数和既有活动/趋势测试通过。
- 自动备份增加“仅非计费网络”条件：开启后 WorkManager 只在 Wi‑Fi、以太网等非计费网络执行周期备份；条件不满足时任务保留等待，不会直接失败或伪造成功。设置已接入 JSON 控制面、旧配置兼容、计划签名和备份概览标签；手动立即备份仍由用户主动触发。
- 视频与音频补齐保存和分享动作：都从已验证 Artifact Ledger 读取，保存使用用户选择的 SAF 目标，分享使用安全 FileProvider/内容 URI；失败状态不提前提示成功。视频播放、静音、全屏、保存、分享以及音频加载、播放、保存、分享改为紧凑图标，普通会话和会议室共用同一渲染器。
- 模型提供商连接健康状态已落地：详情页“测试”会使用当前提供商的首个启用模型发起一次短文本检查，禁用工具、搜索和自动压缩；结果按提供商和实际模型 ID 持久化为已验证或故障。列表区分停用、待配置、待检查、检查中和故障；首个启用模型变化后旧结果自动视为待检查，健康记录只保存短状态信息，不保存密钥或完整响应。
- 记忆与自学习现有二级模块经 `MemoryLearningPresentationTest` 一次真实 Compose 操作通过：文档、记忆库、自学习之间可返回稳定标签行，系统返回不会把用户留在旧记忆设置页。中文系统下使用中文资源；测试默认英文环境仍含项目既有硬编码中文，未据此宣称完整多语言审计通过。
- 备份页现有导出/校验/恢复三种操作指示映射已由 `BackupOperationPresentationTest` 覆盖，导入不会再复用导出转圈状态；逻辑 JSON 的提供商、会话和记忆已完成一次实际导出→导入回归。自动备份已有间隔、时间、目录、份数、格式、内容、充电和非计费网络条件。完整大文件归档、取消、失败回滚和手机 SAF 流程仍需在后续 D14 继续核对。
- 正式执行计划已写入 [31_V6_EXECUTION_PLAN_2026-11-12.md](31_V6_EXECUTION_PLAN_2026-11-12.md)。它以 v6 蓝图、已确认决策和当前源码为依据，统一 D01-D14 的依赖、状态词汇、分阶段发布与验收；旧 R1-R8 仅作历史范围参照。
- 修复模型目录升级覆盖用户模型的问题：`ensureBuiltInsMerged()` 原先会对所有被识别为 OpenAI 来源的连接执行预设刷新，导致自定义和 OAuth 连接中的模型 ID 被删除或替换。现在目录预设刷新只作用于 Nexus 内置官方 OpenAI 提供商；外部连接的远程窗口元数据补全仍保留，但不会改写其模型身份。
- 该问题会直接影响真实用户的模型选择，不只是单元测试。显式选择未登录账号模型时曾回退到第一个 API；修复后账号连接、自定义连接和远程上下文窗口行为均保持。
- 本段证据：`ProviderRepositoryTest` 19 项通过；随后以 `--no-configuration-cache` 确认同类执行绿灯。工程现有 `verifySherpaRuntime` 不兼容 Gradle 配置缓存，属于独立构建配置问题，不计为功能失败。
- 同段已落地的 Aurora 第三皮肤基础、模型能力开关、新建模型自动选中、提供商可用性统一与选择修复互斥此前已有局部测试和全量 Kotlin 编译证据，但尚无最终 APK、真机长期验收或完整 P1 交付。

## v6 执行目标（2026-11-11 起）

用户已确认把桌面 v6 蓝图设为正式开发目标并开始执行。旧 R1–R8 保留为历史交付视图；v6 使用 D01–D14 唯一执行包，不建立两套并行任务清单。当前先做首包 D01/D02/D03/D04/D08/D13，真机长期验收仍由用户完成。

首个大块已落地，证据级别为生产接线、最小编译与定向单元流程：

- 普通会话临时子 Agent 每会话默认并行 3，设置范围 1–8；同名设置页可调，模型 Schema 与实际 `Semaphore` 使用同一值。
- 超出名额的同一轮调用保留原顺序排队；新增 `SubagentQueued`/`SubagentAdmitted` 事件经 Runtime Bundle、主会话投影和悬浮状态，界面能显示等待名额与获得名额。
- 计划模式在执行 Gate 硬性只允许 READ 和 `goal_manage`/`plan_manage`/`notepad_manage`；写文件、终端、GUI、设置、安装、外部副作用均在审批前拒绝，普通工具/Skill/插件共用。
- Goal 新增结构化收尾记录（核心条件已核验、非关键缺项、影响、证据），完成必须由现有本机审批路径写入；“核心完成但缺非关键项”现在可以追溯，旧 Goal 无该字段仍能读取，但不会再无证据自动完成。
- Goal 完成路径已收紧：先解析并校验完整收尾记录，再通过本机真人批准，最后一次性写入最终状态；直接调用或伪造 `confirmed` 无法写完成记录，审批前的内容变化仍会被乐观哈希拒绝。`WorkflowToolExecutorTest` 覆盖批准后成功完成、缺记录拒绝和直接完成绕过拒绝，`GoalPlanNotepadStoreTest` 覆盖缺记录不落盘；同组定向回归通过。
- 验证：`:app:compileDebugKotlin` 通过；`SubagentSettingsTest`、`SubagentTest`（4 子 Agent 峰值 3、排队/准入事件）、`NexusExecutionGateTest`、`GoalPlanNotepadStoreTest`、Application API `WorkflowStateEngineTest` 通过。未做真机界面/多会话长期压力验收。
- 源码大块备份：`F:\eta\source-backups\NEW-NEXUS-main_v6-subagent-queue_verified_20261111-233533.zip`（SHA256 `51FC1EDF507946C57FE350973E5232F5A83F186F9408B07699914FD59F60A33D`）；计划模式源备份 `...plan-mode-gate...zip`。

原生媒体大块（2026-11-12）已形成生产接线与基本证据：ContentPart 新增视频并保持旧消息兼容；Media3 1.9.0 官方 ExoPlayer/Compose 接入 `PlayerSurface`，视频具备播放/暂停、进度、静音、加载/错误与离开释放；音频补真实时长/进度；图片预览改为应用内 Dialog，支持 1–4 倍缩放/拖动并保留加载失败状态；工具产物按安全扩展映射图片/音频/视频，未知类型保留普通文件。`:app:compileDebugKotlin` 与 ContentPart/媒体生产者/Artifact 访问测试通过。尚缺真机样本播放、全屏/旋转/解码器矩阵、复制/保存 UI 和文件浏览器内完整统一动作，不宣称整条 16.7 验收关闭。

源码备份：`F:\eta\source-backups\NEW-NEXUS-main_v6-native-media_verified_20261112-001007.zip`（SHA256 `B824B28D958127FAC9FD8FD658D73D734CECDCE27A24F4D24924C502FFC2B13D`）。

下一步仍按 v6：完成媒体保存/分享与真机样本验证路径，再做三版 UI、模型注册统一、设置/统计和确定故障。

## 当前执行计划（2026-11-11）

最新用户指令：此前全量目标已由用户清除，改为优先收口重要内容并打包。本轮交付版本为 Nexus 0.2.0 / 2026111101；未完成扩展继续明确列出，不把提前正式发布等同原计划全部完成。复杂插件 UI 中间帧的未验证草稿已撤回，只保留已通过基本链的消息插件流式输出与现有稳定页面。

后续更新：0.2.0 已正式交付后，用户要求继续完成余项。当前基于同一源码实施 0.3.0 / 2026111102，已补插件页面中间帧、常用控件/包内图片/受限画布、DSH 交互图表和诊断，继续文件产物与新版本交付。下方“未开始/未完成”属于对应历史快照，最新证据以本节新增内容为准；未把原全部余项改成已完成。

用户已要求重新分析并生成后续计划、设为新目标执行。新版 [30_ETA_REUSE_DELIVERY_PLAN_2026-11-11.md](30_ETA_REUSE_DELIVERY_PLAN_2026-11-11.md) 调整顺序为 R2→R4→R6→R3→R1→R5→R7→R8，原范围、已有代码和已验证结果保留。当前先收口 Nexus 执行核心，不再连续陷入参考项目兼容细节。UI 明确保留 ETA 现有 Miuix 风格、主要导航/布局/位置，重大布局调整先给用户看。Android 16/17 真机与长期使用验收由用户负责，助手交付真实实现、必要基本检查及桌面正式签名 Release。下方旧 N0–N9/L4/L5 和各小批次状态保留为历史证据。

| 批次 | 当前状态 |
|---|---|
| R1 设置/产物/聊天呈现 | 设置记录/冲突撤销、原模型/提供商/备用模型/语音/记忆/学习/Skill/MCP/自动备份/网页及本机开关已接；工具详情关联正式 Item 与不可变版本、真实文件和富消息保存已接。MCP/API 收到的附件与新语音文件已接原产物库；更多模块操作与其他外部产物仍继续，不宣称全部设置已统一 |
| R2 执行/权限 | 用户补充输入、统一参数校验和后台模型任务准入/预算已接；回执保护、按声明窗口的费用预留和活动任务本机批准追加已基本验证；Host/交互引用随对应模块继续 |
| R3 记忆/Skill/自动任务 | 经验→候选→验证/人工发布→使用/新版本隔离已有基本链；本地离线维护、技能排序/已结算质量与去重已接，事件先入队后等条件、任务链历史游标与定位精度保护已基本验证；真实提炼质量与系统事件到达交用户验收 |
| R4 模型/资源 | 文本/预热统一调度、定向停止/删除、内存优先级已接；新增 sherpa-onnx 语音包下载/导入/运行/音色和音频统计，有原生桌面 CPU 证据；更多模型与实际设备资源余项继续 |
| R5 外部能力/包管理 | MCP/API/A2A Host、隔离包/WASM、Linux 工作副本、DSH、消息/生命周期/私有状态/输入开关有基本证据；页面/消息窗口/函数式 screen/桌面组件、插件模型与消息实时流、通知恢复均已接。0.3.0 再补页面中间帧、包内图片/基础画布/常用选择控件、DSH 交互图表/字段诊断；WebView/选择器、完整样式和 Java 宿主专属能力仍有余项 |
| R6 系统/虚拟屏/语音 | 胶囊补充、AEC 交接、语音工具取消、专用 KWS/训练声纹已接；新增连续 AEC 采集与播报独立、识别文字打断/任务补充模式；系统/虚拟屏与真人声学验收继续 |
| R7 备份/迁移 | 来源清单/加密凭据/预览一致性/准入/激活回滚及配置文件选择性恢复已基本验证；后续新 Host/设置随模块补入 |
| R8 本轮正式包交付 | Nexus 0.2.0 / 2026111101 已实际生成，正式 v2/v3 签名、身份/版本/非调试、12 个组件、16 个 ARM64 库 16 KiB 与 ZIP 对齐通过；APK、SHA256、说明已放桌面。原计划其余功能未宣称全部完成 |

### 0.2.0 之后的功能链完善（2026-11-11）

- 插件页面状态变化现在从原 Compose context 的状态订阅器，经隔离进程和原无模型 Run 的 IPC 传递到现有 Miuix 页面。中间树只作不可交互预览，最终回调返回后才提交 state/memo；失败回到已提交页面并保留原错误，不重放操作。初始渲染、最终重画和预览渲染均受到只读宿主限制，不因为正在执行用户动作而获得隐式写权限。
- 中间树有包摘要/模块/序号与 60 KiB 单帧、128 帧、2 MiB 总量限制；旧帧、异包、晚到及停用后的帧不采用。它不进入执行历史、检查点、聊天消息或重连回放，不让大量界面 JSON 污染上下文/备份。原消息插件流式输出沿用 0.2.0 实现。
- 首次编译发现新增事件需要补原浮窗/聊天 projector 的穷尽分支，已明确为不改变其界面。之后原页面服务 4 项、新流式/隔离执行链 3 项、实际窄屏深色加载页 1 项通过；Node 也实际执行了异步回调中的 Loading→Ready。证据 `20261111-post-release-ui-stream/`，PNG 已查看。新渲染只读阶段随后随富控件链定向补验。
- DSH 图表改为共享类别坐标，缺失/无效值保留断点；正负大值先归一化，避免溢出；单点有可见位置。折线/纵横柱支持本地选点和前后点浏览、按真实值读取、多系列图例隐藏/恢复；环图按类别显示真实数值/单位/占比和选中环宽，超过 8 类合并尾项为“其他”，使用 BigDecimal 累计避免总和溢出。图表点选不调用远端动作。
- v2 图表使用当前实际背景下的至少 3:1 类别色，文字保持可读；真实渲染采用原 Miuix 文字与控件。解析诊断输出精确路径、忽略的样式/非法值/禁止 span 动作及图表数据限制，诊断不包含绑定正文。信息图标在原插件署名行，默认收起，同页展开而不叠窗口。4 项数据/诊断与 2 项实际窄屏明暗渲染交互通过，PNG 已查看，证据 `20261111-post-release-charts/`。不宣称此刻已复制全部上游诊断或桌面图表框架。
- 新增插件 Image、Slider、RadioButton、SelectionContainer、DropdownMenu/Item、TabRow/ScrollableTabRow/Tab 和 Canvas 白名单。保留已有控件，加入原 Modifier 的有限尺寸、间距与点击消费者，以及真实确定值进度条。选择菜单使用原页面内部展开/关闭，避免二次弹窗遮挡。滑块合并快速输入，运行期间不可再次发命令；没有引入第二套 UI 库。
- 插件图片只从启用包的固定摘要版本读取，经过来源完整性、路径/格式/1 MiB 限制，并复用已有图片缓存、解码尺寸和像素预算。未放开任意设备路径或网络地址。Canvas 支持公开的基础线/形状/文字/贝塞尔路径、有限平移缩放和真实尺寸回调；128 指令/1024 路径项/8000 文字上限，未知命令与 Brush 明确拒绝，未把空白兜底当全量支持。Canvas 和菜单已实际绘制/点击，图片资源、画布合同和渲染期间禁止写入有相关基本证据，见 `20261111-post-release-rich-nodes/`。
- 外部实际二进制结果通过原 ToolResult 附件通道进入现有 NexusRunHosts，保存到私有工作区后登记原 ArtifactLedger，关联真实 Run/项目/会话/会议并输出原工具的 artifact_id。MCP 内嵌文字/blob/音频/图片和 API 二进制/attachment 响应保留原字节；远端 resource_link 不自动下载或冒充已验证本地文件。失败结果不建产物，文件名规范化、最多 8 个/单个 8 MiB/合计 16 MiB，API 仍受原 1 MiB 响应上限限制。
- `voice_synthesize` 接入原文件回执登记，新音频保存到统一 workspace/audio，FileProvider 为原/现两种工作区路径显式登记分享范围，使完整备份覆盖新音频。没有搬移旧文件。首次相关检查中 API/MCP 实链通过，语音用例的 Windows 绝对路径被原 Android 路径校验拒绝；测试改为真实工作区路径，并随合成取消/文件名/音频格式用例定向补验，不为了测试放松生产路径限制。
- 本轮没有修改主导航、设置分组和主要布局，没有操作 Git 或真机。WebView、系统文件选择器、全量 Modifier/Canvas、依赖原 Java 宿主的插件、多独立虚拟显示和更多模型后端等仍未全部完成；新版本只声明已落地能力和当前证据。

发布前收口证据：包内 PNG 已经过真实原页面载入并查看截图，新的画布尺寸回调只在真实尺寸变化时提交，避免一次动作结束重新启用页面后不断触发同尺寸回调。相关新增/失败项均定向补验通过，语音文件名、WAV 格式与取消释放同链通过。原加密备份的真实文件/结构化消息/工作流/执行记录恢复用例通过，结果在 `20261111-post-release-final-chain/`；不同轮存在重复用例，不相加成完成率。外部附件检查有实际本机 HTTP，不是只构造返回 JSON；没有声称真实第三方 API、ROM 存活或模型质量经过手机验收。0.3.0 正在使用 0.2.0 同一正式签名构建，交付状态待最终 APK 校验。

本轮最终交付：0.3.0 / 2026111102 已完成正式混淆/签名构建并复制桌面，APK 61,349,309 字节，SHA-256 `9056aebc5c785ceca511fd43b9adcac63b51c906ea3a2ad1cbabeb491bf82096`。证书沿用 0.2.0 的 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`；最终 v2/v3、版本/包名/非调试、12 个必要组件、16 个 ARM64 库 LOAD 段 16 KiB 与 ZIP 对齐通过。桌面说明包含四条完成链、实际入口和剩余能力。发布核验现按版本保存到 `build/reports/nexus-delivery/release-0.3.0/`，不覆盖 0.2.0 原证据。没有新建持续目标或修改 Git，没有装机；原计划余项仍未全部完成。

### R2 本轮执行结果与费用收口（2026-11-11）

- 用户再次确认保留 ETA 风格和大体布局。已写入主计划和活动目标；本轮没有修改应用 UI、导航、页面分组或常用控件位置。
- 修复能力包直接操作在执行器返回后先检查取消、导致真实完成回执丢失的问题。现在先保存实际成功/失败回执，再尊重 Run 取消；格式错误的结果关闭为失败，不伪造成功，也不重发已经执行的操作。
- 模型请求账本在现有 JSON 中加入单笔费用预留，不新增数据库或替换旧 Owner。发送前以本次冻结价格和配置的模型上下文窗口作保守预留，包含输入/缓存读较高价格、可能附加的缓存写和输出；剩余额度容不下下一次预留时，尚未发送即拒绝。实际结算后释放预留，父子共享限制、断开重开读取及备份恢复沿用原账本；旧 JSON 缺少预留字段仍可读取，旧未结请求不能被当成免费请求绕过。
- 有限费用请求的输出上限真正进入 Chat Completions、Responses、Anthropic 和 Gemini 的最终请求体，位于自定义字段合并之后，并在兼容重试前再次施加。保留用户原来更小的输出限制；限额请求拒绝多候选生成、无法限定额外费用的服务端工具和不能约束输出的账号连接。不设费用限额的原请求不强加新输出参数。
- 明确的 invalid_request_error 参数拒绝可按零用量结算后再走原兼容路径；普通未知错误和自带用量的错误不假定免费。请求尚未进入传输就失败时按零用量关闭预留。服务端真实费用若超过单次预留，先保存真实用量，再停止后续请求，不能抹掉费用或把它截到预算数值。
- 费用预留依据用户配置的模型窗口和价格，属于声明上限下的客户端控制，不宣称能保证任意外部服务的实际账单绝不超额；错误窗口、错误价格或服务端忽略输出参数仍需要通过真实回执发现。嵌入式本地推理仍为零云端费用，统计中的预留与实际费用分开。
- 第一轮 29 项相关检查全部通过，含真实 HTTP 参数兼容重试、输出参数、取消后真实工具结果、父子预算竞争、整数溢出边界、重新打开账本及一次实际备份恢复。编译与检查共 51 秒，证据 `build/reports/nexus-delivery/20261111-r2-cost-reservations/`。之后为多候选/服务端工具参数绕过补最小相关检查，见后续结果；没有重复整批回归或生成 APK。
- R2 未整批完成。下一步继续本机批准的硬预算追加和活动父子任务预算传播，再按主计划进入模型/语音资源余项。

### R2 本机批准的预算追加

- 原 `goal_manage` 增加 `increase_budget`，复用现有本机确认窗口。确认前验证真实 Goal、仍活动的状态、有限单调上调和原快照；窗口列出前后软/硬请求数、工具数、总时长和费用额度，模型传入 `confirmed=true` 无法代替批准。只允许同币种上调，不用移除上限冒充追加。
- 批准后更新原 Goal 文件、不可变修订和执行记录，沿同一个 Runtime 所有者通知活动父子任务。已经消耗的请求数、工具数、时间和费用全部保留；原来比 Goal 更窄的独立限制不放宽，之后新建子任务读取提交后的父预算。未获得批准、等待期间计划/状态变化，以及非所属任务批准均拒绝。
- 执行库为追加增加独立写入入口，普通事件 append 不能伪造上调；写入前核对当前预算、同一任务家族及真实本机批准记录。家族传播某处失败即停止整个家族并报告已发生的修改，不能按“没有做过”自动重发。原模型账本继续累计，不另起预算计数器。
- 修复预算延长后的旧超时任务可能误停当前任务：按计时版本拒绝已撤换的计时回调。此版本支持任务活动期间提前申请追加；已停止的 Run 不被重新打开，不自动重复未知工具操作。需要已终止任务继续时仍走原检查点核对/新请求，不宣称已实现无条件原地恢复。
- 首轮 29 项检查中 28 项通过，1 个新用例仍触发原软预算确认；修正为明确同时上调软/硬额度。随后 15 项中 14 项通过，另一个伪造批准用例因为故意删除其他硬上限先被更早的有限性校验拒绝；改为完整保留旧预算、只伪造轮数追加后单独通过，证明真正拒绝的是缺失本机批准。初轮和受影响补跑证据分别位于 `20261111-r2-budget-increase-initial/`、`20261111-r2-budget-increase-guard/`、`20261111-r2-budget-increase-final/`，不相加当作独立覆盖量。Kernel/Application 合同检查同轮通过。没有进行装机或全软件回归。
- 上一段费用参数额外 6 项检查通过，证据 `20261111-r2-cost-payload-bounds/`。下一步进入 R4 模型/资源协作余项；R2 其余 Host 权限/交互引用随实际模块收口，不假称八个批次已经完成。

### R4 本地语音停止与资源通知

- 沿用现有 MediaPipe 语音引擎、两个 Mutex 和 CPU/GPU/NPU 回退。新登记的是每次语音操作的现有协程，不是另一套语音引擎：停止一个模型只取消它的流、预热和排队调用，不再先等无关 TTS 的锁；ASR 等音频的 ReceiveChannel 能随取消退出。取消后仍在原所属锁内释放引擎，避免并发关闭正在使用的原生对象。
- 模型停止、内存回收和用户取消走同一释放路径。互斥等待有 5 秒期限，未退出显示失败/等待原生调用结束，不能假报停止成功；协程取消不能强杀卡住的原生函数或驱动。退出后晚到的取消清理继续释放本次引擎，不覆盖其他模型的新状态。ASR 预热失败时关闭已创建的转录器后再尝试回退，避免失败路径遗留 GPU/CPU 资源。
- 语音状态表的修改加同步保护，防止 ASR 与 TTS 同时更新时丢掉另一方向。内存释放接入 Application，即使助手浮窗不在也可释放空闲缓存，仍按先 TTS、再空闲 ASR 的原策略，正在识别不会因普通内存通知被误停；原文本引擎仍保留自己的资源所有者。
- 本地文本调度增加应用生命周期内的系统温度回调和省电变化接收，变化后复用原 pressureChanged 让后台整理让位；不建立持续轮询任务。已有前台任务不会被当成后台整理从头重做。
- UI 只让已有模型概览、语音连接和音色页状态标签显示实际“正在停止”，位置、尺寸、图标和主要布局保留。没有增加一级入口、说明性小字或重新设计页面。
- 本轮两次编译失败分别发现原文本状态枚举没有 STOPPING，以及音色页漏接新增的语音 STOPPING 分支，已修正并补齐所有现有语音状态消费者。随后 7 项基本检查中 6 项通过，1 个新用例错误断言“父协程被取消”；实际被取消的是定向语音子操作，外层收到取消结果。改为验证实际取消结果后单独通过，未重复已经通过的 6 项。证据 `20261111-r4-speech-stop-initial/`、`20261111-r4-speech-stop-state-compile/`、`20261111-r4-speech-lifecycle-initial/`、`20261111-r4-speech-lifecycle-fixed/`。
- 已验证的是实际生产登记器的双方向并存、ASR 流与排队取消、停止屏障/恢复、原文本调度让位和真实 Android 省电广播的注册/卸载；温度监听编译已接。Windows 检查没有运行 MediaPipe 原生模型、麦克风或 GPU/NPU，也没有证明跨进程音频资源、声学全双工或真实手机性能。R4/R6 仍活动，后续继续这些明确余项，不将登记器用例等同所有语音能力通过。

### R4 本地模型入口、主模型优先和语音崩溃保护（后续继续）

- 上一目标回合属于实际开发进展，不是等待或只复述计划。本回合继续从 R4 真实调用链切入，没有重做只读全项目审计。
- 模型页面的启动、停止、删除，以及应用启动后的自动预热，统一由 `LocalModelApplicationService` 复用原三个文本引擎和本地语音 Owner。UI 控件布局不动；手动启动/自动预热也进入原推理调度，不再绕开正在回复的前台任务。自动预热可给前台对话让位，手动启动按用户任务排队，排队取消不加载原生模型。
- 调度加入真实模型 ID 和定向停止屏障；停止某个模型取消它活动与排队的 Controller，不取消其他模型。删除全过程保持同一屏障，先关闭相应引擎再删除文件。停止锁等待有期限，不通过返回一个“停止成功”掩盖仍在运行的推理；不能强杀卡死原生函数的限制保留。
- 启动成功后才切换默认文字模型/语音绑定，加载过程中用户另选了模型或设置时不覆盖后来选择。失败不会先把一个不能运行的语音模型保存成默认服务。三套文本引擎在同一调度槽内切换前，关闭其他引擎的空闲实例，避免三份文字权重同时驻留；ASR/TTS 保留独立 CPU/GPU/NPU 设置及原回退机制。
- 内存通知不再同时并发驱逐文字和语音。先走原 TTS/ASR 释放，再重新测量系统内存；本地主模型被选中且内存确实紧张时，TTS 最先让位，若还不足则识别让位，最后才允许原文本 Owner 释放。语音释放失败不继续驱逐主模型；未运行完的原生调用保持明确失败状态。系统普通后台通知并不等同已经低内存。
- 系统 `:voice`、`:recognition` 是已有入口桥，不执行本地推理；移除这些进程启动时重复打开模型目录和语音崩溃日志的行为，原生语音仍由主进程的输入、浮窗、播放 Owner 持有。此项是现有消费者核对后的收敛，不宣称已实现可任意跨进程部署的推理池。
- 修复 ASR/TTS 共用一条崩溃记录的问题，按方向和独立 attempt ID 保存；旧任务迟到的阶段/完成不能覆盖或删掉新任务。记录真实 CPU/GPU/NPU 后端和进程，崩溃匹配过滤相应进程；回退后故障不再归到原请求设备。相同 `asr-stream-frame` 阶段连续更新不再每帧同步写盘，只有阶段改变才更新，不删除崩溃保护。
- 启停初轮 12 项中 11 通过，1 项发现排队取消被 Runtime 的普通取消异常带出、影响协程父任务；在预热边界转为对应协程取消后该项通过，同轮 API 4 项通过。资源顺序 9 项、崩溃日志与进程策略 4 项通过；日志初轮只有新夹具的位置参数传错导致编译失败，修正为命名参数后通过。证据 `20261111-r4-model-lifecycle-initial/`、`20261111-r4-model-lifecycle-fixed/`、`20261111-r4-resource-priority/`、`20261111-r4-speech-journal-initial/`、`20261111-r4-speech-journal-fixed/`。
- 主模型资源顺序与活动语音登记的 4 项检查及 `checkNexusArchitecture` 通过，证据 `20261111-r4-primary-memory-order/`。最后对“主模型内存不足时连正在播报的 TTS 也先让位”的实际条件补编译见 `nexus-r4-final-compile.log`。这些证据不是原生模型/真实声音/厂商内存行为通过；不同轮有重叠，不相加算独立覆盖量。没有 APK、装机或 Git 修改。

### R6 不丢开头音频的语音打断与工具取消

- 原来开口检测停止监测麦克风，再启动 ASR，会丢开头。现在 `SpeechBargeInMonitor` 保留约 800 毫秒的有界 PCM 环形缓冲；触发后交给原 `SpeechInputController`，沿同一个启用 AEC 的麦克风继续采集，淡出播放期间的音频也保留。云端/本地 ASR 仍走原入口，未另开一套识别服务或第二支麦克风。
- 麦克风前台声明由原监测 Owner 保留，直到 ASR 在原 `beforeCapture` 获取自己的声明才释放；取消、超时、无权限、识别关闭、任务不接受补充等路径会归还实际采集器。麦克风错误通过音频队列交给识别，不把读取失败伪装成完整结果。PCM 总量仍受原 60 秒上限约束。
- 没有 AEC 时保留原点击打断路径，不用播放回声冒充用户。当前实现为 AEC 采集的连续交接与可打断会话，不宣称已有语义级完整全双工、所有 ASR 服务均能真正同时听说或真机声学效果已经通过。
- 原 Agent `voice_synthesize` 绑定当前根/子 Controller 和嵌套资源作用域；任务取消会中止实际合成协程/HTTP，本地引擎按原取消释放。保存音频前再次检查，临时文件失败会清理，取消后不发布一个完成文件。软件管家的 `state.local_models` 现在读取实际语音方向/后端/启停状态和文字调度队列，未引入另一份运行状态数据库。
- 一轮 69 项相关检查全部通过，包含生产会话协调器→原输入 Controller→交接音频流的开头顺序、原打断/听写生命周期和资源顺序。其后增加麦克风错误和语音文件取消检查，7 项中 6 项通过，1 项是 Windows 测试没有 AndroidKeyStore；改用测试提供凭据、仍经过生产取消与合成入口，该项单独通过。额外前台声明交接检查通过。证据 `20261111-r6-capture-handoff/`、`20261111-r6-voice-tool-cancel-initial/`、`20261111-r6-voice-tool-cancel-fixed/`、`20261111-r6-capture-foreground/`。没有因一项夹具失败重跑全部生命周期检查。
- 下一段继续 R4 更多语音模型格式/统计接线，以及 R6 真正后台唤醒、声纹后端和完整双工。当前唤醒词主要是在主动录音结束后匹配文本，不能因存在开关就声称已有后台常驻唤醒；当前声纹特征比较也不能当成可靠生物身份认证。屏幕/虚拟屏余项及 R3/R1/R5/R7/R8 原范围保留。真机、跨设备、GPU/NPU、声学效果和长期使用仍交用户，不以此阻挡完成其他代码和最终正式交包。

### R4 ONNX 专用语音运行与模型包闭环

- 上一回合有代码和实际检查，归类为进展。本回合沿现有模型库、下载 Worker、目录和语音 Owner 接完整 ONNX 语音包，没有把模型包路由到聊天推理器。参考核对 Operit 的 sherpa NCNN/MNN 适配后，使用 sherpa-onnx 自身官方 Android 2.0.1 发布包与公开 Kotlin/JNI 合同；不复制另一个 App 的界面或重复引入另一套主 Runtime。
- 固定官方源提交 `af2d8d81dbfa61c4cea4c568da78f3982edca005`，依赖为 `sherpa-onnx-static-link-onnxruntime:2.0.1@aar`，构建前 SHA256 必须为 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`。官方制品单独限定仓库，不改其他依赖来源；许可证/来源存入原 licenses 目录，JNI 配置字段加入正式混淆保留。ARM64 ELF 的所有 LOAD 段对齐值为 `0x4000`，已保留 readelf 结果；最终 APK 中的整体组件仍留 R8 校验。
- 新适配实际支持：Zipformer/Paraformer 流式 ASR、Whisper/SenseVoice/Paraformer 文件或停顿后 ASR、带词表/词典的 VITS、Kokoro 和 Matcha TTS 配置。模型不同目录格式逐项判断，单个 ONNX 图不被冒充完整语音模型。普通 Piper 的单图+JSON、Pocket/Moonshine/Qwen3-ONNX 等尚未声称支持；LiteRT 中已有同名模型仍走原适配。未缩减后续模型范围。
- ONNX 当前明确使用官方包支持的 CPU 后端和实际线程数，启动状态报告 CPU，不声称 GPU/NPU 参数已经加速此适配；原 LiteRT 语音的 CPU/GPU/NPU 选择/回退保留。两种语音引擎共用之前已接的 ASR/TTS 各自 Mutex、取消、定向停止、内存让位和崩溃保护。旧模型方向切换时释放同方向旧引擎，不影响另一方向，保留识别/播报同时驻留能力。
- 下载支持完整 ZIP、tar.bz2、tar.gz、tar.xz/tgz，SAF 原目录导入入口同样识别 MNN/ONNX 语音包。解压拒绝越界/绝对路径/重复路径/软硬链接/设备文件，限制数量和实际总字节，循环检查取消；缺失必要文件或一次含多个语音模型会拒绝。目录校验包含相对路径及文件内容，解压后先完整核对再登记，失败不留下可启用的半包。保留原 MNN 导入主流程。
- 模型库增加 Zipformer 14M 中文移动包、SenseVoice Small 多语种 int8、Whisper Tiny、VITS 中文 174 音色和 Kokoro 多语种包；全部固定预期体积与 SHA256。其中 Zipformer/VITS/Whisper 在本机从官方地址实际下载计算校验值，SenseVoice/Kokoro 与官方发布 API 的 digest 对照。SHA 只说明字节完整性，不代表权重身份或模型效果绝对可信。额外 ghproxy.net 仅作为第三方备用源，按相同固定 SHA 验证；HEAD 验证返回二进制 200 不等于保证中国各网络均能长期访问。
- 压缩包 SHA 与解压目录 SHA 分开保存，修复本地导入已知包后模型库不能识别“已安装”的问题。下载完成状态使用实际去重后 recordId，避免重复下载复用旧记录后页面找不到安装项；实际校验继续使用目录 SHA。下载后要求启动时，语音包也走原一键启动链，成功后才保存语音绑定。
- 音色来自运行器真实 `numSpeakers`/`listVoices` 和包内 speakers.txt/清单，支持数字 ID、语速和原语音合成工具。超过 12 个音色复用已有 Miuix 选择菜单，不把 174 个按钮铺满页面。运行页可选择 ONNX 语音线程数，不显示对它无效的 LLM 上下文/思考/采样参数；原三页、概览启动/停止与目录入口位置保持原样，只有目录入口改为“MNN · ONNX 语音包”的真实范围。
- 同一 CPU ONNX 模型发生原生崩溃后，后续自动 ASR/TTS 会拒绝重拉，保留记录提示由用户在原本地模型概览点击启动重试；不永久禁用其他模型、方向、不同引擎或新版本。不是用每次都重启掩盖崩溃循环。
- 本地语音真实操作已记录首结果/首音频延迟、处理耗时、输入/输出音频时长、实际后端/引擎、模型已驻留状态、音频实时率、失败码。原软件管家 `state.local_models` 返回这些字段，仍使用原状态流，不新增统计数据库；Token 缓存计数缺失时为 null，不把音频字节当 Token，也不把“模型已缓存”混同 KV 命中。当前供后续统计读取的是最近一次状态，不宣称已完成历史语音统计图。

验证与实际限制：

- 初次构建发现新 Gradle 版本禁止已弃用的 task 委托写法和 java 扩展遮挡包名，随后发现下载协程的非局部 return，均修正后编译通过。首次 18 项检查有 3 项失败：Robolectric 自带旧 Commons Compress 与新版 Tar 写入夹具 ABI 不同、旧测试仍要求 ZIP URL 被拒绝；修正夹具走公共 ArchiveOutputStream 与更新真实支持范围后，只补失败项。HTTP 新夹具随后因 Robolectric 默认 0 可用空间被拒，补实际测试存储状态后该链通过，没有放宽产品存储限制。
- 已有 ZIP/MNN 导入、非法包/完整配置与官方中文 TTS 实包检查沿用本轮通过结果；后续实际 HTTP 下载→完整包安装→重复去重→目录校验→篡改拒绝通过。模型源和本地归档身份检查 3 项、清单多词典/取消及官方包真实 speakers/FST/FAR 3 项、CPU 崩溃隔离 1 项、音频统计单位/缺失值 2 项均通过，相关编译和架构门禁通过。各轮有重叠，不叠加成独立覆盖量。证据目录：`20261111-r4-sherpa-build-initial/`、`20261111-r4-speech-packages-initial/`、`20261111-r4-speech-packages-fixtures/`、`20261111-r4-speech-download-basic/`、`20261111-r4-speech-catalog-final/`、`20261111-r4-speech-installed-identity/`、`20261111-r4-speech-ui-final/`、`20261111-r4-onnx-crash-quarantine/`、`20261111-r4-speech-telemetry/`。
- 补了一次官方 Windows 2.0.1 CPU 原生执行：同版 Kotlin API、完整 VITS 中文包真实生成 WAV，174 音色、8000 Hz、34162 个样本（约 4.27 秒）、峰值约 0.1996；Zipformer 中文移动包读取官方测试 WAV 得到非空转录。首次桌面运行误加载 Windows System32 的旧 ONNX Runtime 1.17.1，明确预加载官方配套 1.28.2 后两条原生链一次通过。桌面过程的 Java 探针、原失败/成功日志和 WAV 保存在 `20261111-r4-native-speech/`。Android 使用官方静态链接 AAR，不沿用 Windows DLL；此证据能确认模型/API/JNI 的基本可运行性，不能证明 Android 驱动、速度、音质或手机录音通过。
- 使用路径：本地模型→模型库→下载新增 ONNX 包，或原“导入文件”选择完整压缩包、原“导入模型目录”选择完整目录；概览选该模型→启动，成功后自动绑定识别/播报，无 API Key。音色到原语音合成页选择，线程到原运行页调整。启动后正常聊天输入、助手语音和 `voice_synthesize` 复用同一绑定。自定义非标准包可提供 `nexus-speech.json`，例如 `{"version":1,"architecture":"sensevoice","files":{"model":"model.int8.onnx","tokens":"tokens.txt"}}`；不能把文件外部路径写进清单。
- R4/R6 仍未整批完成：后台常驻唤醒、可靠声纹模型后端、语义级完整双工及剩余屏幕/虚拟屏协作继续，其他计划范围与最终正式签名桌面 APK 均保留。本回合没有生成 APK、装机或操作 Git；手机、Android/ColorOS、声学及长期使用按用户要求留真人验收。

### R6 后台唤醒与训练声纹后端

- 本回合继续 R6 真实运行链。原“唤醒词”只在手动 ASR 结束后匹配文本，现增加原语音设置内启动的 microphone 前台服务，以专用 3.3M ONNX KWS 常驻做本地关键词检测，命中才打开原助手；语音、会话、Agent/工具执行仍复用现有 Owner。主动会话/连续聊天不再每句话重复要求唤醒词。
- 用户自定义最多 8 个词，按 Android ICU Han-Latin 转写、Unicode 归一和下载模型 tokens 表分词后编译成 sherpa 关键词；无法编码的词明确失败，不用任意字符串冒充已生效。词表范围随模型变化，中文模型不保证任意英文/多音字读音准确；后续可按需要提供其他语言模型，不能把错误分词算作支持。中文无空格“助手打开设置”和英文边界的原匹配也已修复。
- 后台服务从可见设置页经麦克风授权明确启动，必须有悬浮窗授权与已安装唤醒模型；持续常驻通知可停止，状态包括启动、聆听、让位、停止中、失败。进程/系统重启返回 NOT_STICKY，不能凭配置 enabled 就自动绕过 Android 的后台麦克风限制；用户回原设置点击“开始聆听”恢复，不要求一定成为默认系统助手。Root 不替代 Android FGS/用户权限，也未引入 LSPosed。ROM 长期存活仍交用户。
- 聊天录音、语音播报或声纹录入获取现有 SpeechAudioLease 时，唤醒先取消并真正等待录音线程退出，再开前台麦克风。正常对话占用期间暂停，释放后恢复；系统通话中暂停。每轮录音仍受原 60 秒限制并重新创建有界缓冲，后台用短期带超时 WakeLock 支持熄屏采集，取消/退出释放；没有无限无界 PCM 队列。热/功耗和 ROM 真实效果未做手机验证。
- 唤醒命中时用原助手 ACTION_WAKE：空闲打开原输入浮窗，已有任务则进入原 supplement 路径，避免重置正在执行的 Agent。唤醒过程只识别词，不把待执行命令通过不可信 Intent 自动执行。声纹开关启用时，命中音频也先经当前模型验证。识别等待/停止失败不伪报成功，旧 native 调用未退出则保留 STOPPING/错误状态。
- 新增本地 CAMPPlus 中英训练说话人模型，实际 192 维向量经归一/有界量化保存，原手写 MFCC 不再作为新增声纹主路径。档案保存模型哈希和向量版本；换模型/旧 MFCC 档案需要重录，不会把同维不同空间向量误判匹配。低语音能量/不足时长拒绝，原录入流程从麦克风真正 ready 起录 5 秒；退出/取消立即停止捕获，迟到结果不能保存。阈值放在原声纹设置，仍不等同防录音重放的高安全生物认证。
- 唤醒、说话人、ASR、TTS 崩溃日志分槽，记录真实 CPU 设备和引擎 ID；旧调用不能删新调用的日志。同一 native 崩溃会暂停自动唤醒/特征提取，用户显式重试/重录可解除相应模型保护，不扩散到其他引擎。后台严重内存压力先让位并释放声纹缓存；原文字主模型/识别优先级保留。
- 原模型库增加中文 KWS 移动完整包（15,292,881 字节，SHA `b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b`）和 CAMPPlus ONNX（28,281,164 字节，SHA `aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2`），官方源和相同 SHA 的备用代理源。两者使用专用 keyword/speaker_embedding 用途，不进入普通聊天或转写列表；原目录导入支持 KWS，单图命名 speaker 的 ONNX 可识别为说话人模型，其他自定义目录可用原清单声明。
- UI 保留原语音识别页内的唤醒/声纹组与模型三页，只增加对应模型选择、实际状态、阈值和原样式启停；模型库对应按钮变为“配置唤醒/配置声纹”，不以普通聊天启动误导。CPU 后端明确显示，线程范围实际生效为 KWS 1–2、声纹 1–4；没有显示无效上下文/思考开关或增加一级导航。删除模型先停止/等待所属 Owner，清除相应绑定，训练声纹在删除事务互斥后重新核验记录，避免重载已删权重。

本轮证据及限制：

- 编译首次通过；52 项基本检查全部通过，包含真实 Service 前台通知→假音频/解码后端→让位/恢复→原助手 Intent、空重启不录音、中文关键词编译与词表拒绝、声纹模型身份、原听写生命周期/模型源/崩溃日志。证据 `20261111-r6-wake-speaker-basic/`。它使用真实服务/生产控制逻辑，音频和解码器被控制，不能冒充设备声学通过。
- 进一步麦克风 await 检查首轮 7 项中 6 通过，1 项因测试主线程阻塞了服务收尾而超时；改为后台等待并推进 Android 主循环后单项通过。新增 STOPPING 真实状态后，该项先因在原生关闭回调前断言 STOPPED 失败，改为等待真实结束后通过。声纹录入取消单项通过。仅补失败或新增风险，未重跑全量；证据 `20261111-r6-wake-microphone-handoff-initial/`、`20261111-r6-wake-microphone-handoff-fixed/`、`20261111-r6-speaker-enrollment-cancel/`、`20261111-r6-wake-stopping-initial/`、`20261111-r6-wake-stopping-fixed/`。最终删除/线程设置相关代码编译通过，`20261111-r6-wake-speaker-final/`，架构门禁同链通过。
- 同版官方 Windows JNI 实跑 KWS+CAMPPlus：第一步发现原生构造必须提供关键词文件，已修正生产代码为应用 cache 内临时 UTF-8 文件并在关闭/失败删除；随后最初挑选的 0.wav 不含测试关键词，按官方 Python 测试明确的中文 3.wav 运行，检出 2 个词“文森特卡索”“法国”；CAMPPlus 同音频生成 192 维有限非零向量，范数约 7.5099。未调低关键词门槛强行通过。Java 探针、失败/成功日志、命中结果保存在 `20261111-r6-native-wake-speaker/`，模型 SHA 在最终目录保存。
- 使用：模型库下载“Zipformer 3.3M 中文唤醒”与可选“CAMPPlus 中英声纹”→点模型的配置进入原语音识别页→选择模型、填写多个唤醒词→开启唤醒并授权；有运行通知且状态“正在聆听”才是实际工作。声纹选择 CAMPPlus 后点原“声纹档案”自然说话录入，之后可选启用验证/调整阈值。通知停止会关闭 enabled，重启不会假装已经开麦。默认词 Nexus 不一定在中文模型词表可编码，需选中文唤醒词或匹配的语言模型，失败会显示具体原因。
- 未做手机/锁屏/厂商后台/真声纹误识率和重放攻击验收；不得把这一段当完整 GPT 式语义全双工。下一段继续 R6 全双工和屏幕/虚拟屏余项，再进入 R3 等原计划；R4 更多专业模型、R5 扩展、R7 最终备份、R8 正式签名包仍保留，无 APK/装机/Git 操作。

### R6 独立采集与播放的双工会话

- 本回合在完成 KWS/声纹后继续 R6 对话链。原设置增加默认关闭的“双工对话”开关，仍位于原语音识别结果/连续交流组，不增加页面或改变主导航。
- 助手收到用户主动语音请求且启用开关后，复用现有 `SpeechBargeInMonitor` 的 VOICE_COMMUNICATION+AEC 录音、原 `SpeechInputController` 和原 TTS 播放 Owner。麦克风可在 Agent 思考、执行和 TTS 播放时独立持续采集，以原 VAD 识别话语边界，约 800 毫秒有界 preroll 保住开头；当前话语送入原本地/云端 ASR，识别结束只关闭该段，不关闭长期采集器。
- 新共享音频组只允许同一可信助手会话的采集、ASR 和播放并存；其他聊天录音、朗读或外部音频 Owner 仍按原规则抢占并停止整组。播放不会因为同会话 ASR 开始就被误取消，检测到声音也不直接假定用户在说话，原 ASR 给出非空文字后才淡出 TTS。无 AEC/麦克风不可用时退回原点击/交接方式并显示错误，不把半双工标签改名冒充已经开启。
- 用户说完后，空闲时沿同一助手历史开下一轮；已有 Runtime 在执行时沿原 supplement Messenger/steering 加入当前 Run，不停止正在执行的工具，不从头重做任务。未确认/已结束拒绝保留文本供核对，不自动重发未知补充。声纹启用时，新任务和已有任务的语音补充都核对当前模型档案；旧向量和不匹配模型不能绕过验证。
- 对话期间切键盘、取消、通知停止、外部音频抢占、服务结束都会释放长期麦克风与每段 ASR。长按说话先等原采集退出才开普通录音；原屏幕工具暂时收起助手浮窗时可以保留已主动开启的双工采集，结束或真正关闭窗口后停止。没有绕过 Android 麦克风权限/FGS 限制。
- 停顿长度读取原 autoSendSilenceMs，而非新增另一套固定断句参数；每段仍受原 60 秒 PCM 上限。长时间空闲只保留有界 preroll，不无界缓存，也不在没有说话时持续创建云 ASR 请求。VAD 后 ASR 连接/推理仍可能产生延迟，局部识别能力取决于具体模型；未实现 GPT 原生端到端音频模型的语气/情绪协议或宣称同等实际效果。
- 原语音前台 Owner 的 monitoring 改为有计数的预留，避免旧打断监测或旧双工会话迟到关闭时撤销新会话的麦克风声明。每次启动/结束各释放一次，失败前未持有的声明不误释放其他 Owner。旧话语/旧监测回调按代次拒绝，采集真正可用才更新原 bargeInArmed 指示；外部普通录音还会等待旧 AEC 捕获退出，避免新旧 AudioRecord 同时争抢。
- 第一次编译发现两个 lazy 对象相互引用的 Kotlin 类型推导循环，声明明确类型后修正。48 项相关检查中 47 通过，1 个新用例错误地假设送入 preroll 后 ASR 还没返回预览；用可控识别门闩明确隔开“只有声音”和“已有文字”后该项单独通过。原听写/播放前台/开头音频生命周期其余检查沿用该轮结果，不全量重跑。额外无 AEC/外部抢占检查、旧采集延后结束的前台声明计数各 1 项通过；架构门禁和后续采集交接编译通过。证据 `20261111-r6-duplex-basic-initial/`、`20261111-r6-duplex-basic-fixed/`、`20261111-r6-duplex-final-status/`、`20261111-r6-duplex-reservations/`、`20261111-r6-duplex-final-compile/` 及最后声明保护编译日志。
- 关键用例经过真实 DuplexSpeechController→原输入 Controller→逐段 PCM/原音频租约；录音、ASR/TTS 使用受控实现，证明会话归属/并存/顺序/释放，不能证明手机 AEC 抑制效果、云服务实音频延迟或长期后台存活。上段 KWS/CAMPPlus 另有真实桌面 JNI 证据，不能拿来替代这里的声学双工证据。真实手机验收按用户要求保留，不阻挡继续其他开发。
- 使用路径：原语音识别设置→打开“双工对话”→主动发起助手语音任务；助手思考或播报时直接说话，停顿后继续对话/给当前任务补充。关闭开关保持原连续/点击打断模式；不能因此称整批 R6 已完成。下一段仍为系统/虚拟屏的真实余项，随后按 R3/R1/R5/R7/R8 顺序继续，原完整范围与最终正式签名桌面 APK 保留。

### R6 虚拟屏恢复期间的会话隔离

- 复用原 VirtualDisplayController、Root/无障碍输入后端及 GuiControlCoordinator，不另造多套屏幕执行器。发现旧 ImageReader 帧回调在新显示创建后仍可能覆盖新画面，当前在取帧前与解码后提交前均检查实际 ImageReader 所有权，迟到旧帧回收而不发布。
- 原手动点击/滑动队列只核对 displayId 和手动代次，恢复后 Android 复用相同数字编号且新用户接管代次相同时仍可能错误执行。现额外核对不透明 sessionId、ACTIVE 生命周期和实际 active 状态；无障碍回退到 Root 前再次核对，不向另一次恢复会话投递旧坐标。
- 截图 API 绑定预期 displayId/会话，恢复/停止或等待期间换会话立即拒绝；RootShellDeviceController 传入真实请求 displayId，不再静默取当前全局虚拟屏的另一帧。无障碍截图后端和真实截图失败仍保持原明确错误，不退化为物理主屏截图伪装成功。
- 手动无障碍手势返回 ACTION_OUTCOME_UNKNOWN 时不再继续 Root 重放，避免一次触摸可能已执行却又重复点击/滑动。此项为生产分支修复与编译证据，未声称实际 Android Root 注入已验证。
- 18 项相关检查全部通过，包括实际手动队列在获准执行前被恢复成同数字不同会话时拒绝、实际位图截图拒绝错显示/错会话/恢复中状态、原主/虚拟屏路由和 GUI 人工/Agent 互斥。证据 `20261111-r6-virtual-display-session/`；随后未知结果不重放的编译见 `nexus-r6-screen-unknown-compile.log`。旧帧来源校验已经在生产提交处接上，但没有 Windows 原生 ImageReader/真实 Root/手机多屏设备证据，不以两个会话用例冒称已验证实际屏幕恢复。
- 本段增强恢复可靠性，不宣称已实现任意多个独立虚拟显示同时运行；现有手机 UI 自动操作仍由原协调器串行拥有控制权。多实例应用/多显示的 OS 能力、屏幕长期稳定性和 ROM 限制仍需具体后端适配/用户验收，原需求保留。下一段按主计划进入 R3 记忆/Skill/自动任务余项，R6 其他系统边界随对应流程继续，未生成安装包或操作 Git。

### R1 当前落地

- 复用 ETA `EtaAppControlRegistry` 和原审计文件，实例化已有 `NexusSettingsControlPlane`。外观、记忆开关、模型选择、备用模型、语音和子 Agent 的现有 Repository 写入接控制面；Agent 的 settings get/execute/undo 使用同一入口。
- 修改记录包含真实 actor、RunId、前后值、PENDING/APPLIED/NO_CHANGE/UNCERTAIN；先写记录再修改。设置修改记录与临时读取记录分开保留，仍使用原有存储，不新增设置数据库。
- 撤销验证当前值与修改后的指纹一致，拒绝覆盖后续修改；快照经过截断/脱敏不能完整还原时不显示撤销，避免恢复损坏数据。旧审计缺少完整指纹的记录也不冒称可恢复。
- 软件管家增加“设置记录”窗口：展开前后值、来源/时间/结果、可撤销按钮。需要另行本机确认时隐藏记录窗口，避免二次弹窗在后面。UI 保留现有设计体系。
- 首次集中检查完成 Kotlin/架构检查，32 项中新增测试有 5 项失败：设置上下文仍持有前一 Application，导致修改与记录读取使用不同目录。修正上下文更新后只补跑受影响的 8 项，全部通过（31 秒），没有重复全量回归。Nexus 身份检查通过。证据 `build/reports/nexus-delivery/20261111-r1-settings/`。
- 本项是早一轮 R1 设置功能链进展，后续 MCP/Skill/学习、产物和富消息接线见下文。R1 整批尚未关闭；未打包、未装机、未执行 Git 写操作。

### R1 本轮文件与呈现接线

- 沿用 `workspace-artifacts.json`，调用方指定的 ArtifactId 不再被替换；索引跨实例共享写锁，重复真实回执使用稳定 ID。文件工具的实际 `write_file/edit_file` 成功结果登记 Run、会话/会议室、项目、文件类型和大小，工具事件、执行 Item 与消息文件卡保持同一引用；模型口头声称生成文件不算产物。
- 聊天和会议共用右侧文件入口与左划手势；导入、打开、分享及项目查询读取同一索引。打开/分享验证真实文件、工作区边界和 FileProvider 授权，分享缓存按时间清理。终端脚本和外部 Host 的产物适配仍需随 R5 接通，不把任意文本路径自动当可信文件。
- 核心 Room 数据库 27→28 显式迁移，为原消息增加结构化正文列并沿用分块存储和逻辑备份。完成消息及文件引用在普通聊天和会议共用渲染器，原流式展示保留；旧消息的空结构不被偷偷改写，未知格式保留可读正文。
- Classic/Omni Profile 已实际影响共用顶栏、页面宽度、消息边距和侧栏尺寸，复用原页面。更完整的计划/轨迹/输入/授权交互引用按 R2/R5 继续接入；不是另建一套聊天框架。
- 第一轮 135 项相关检查中有 5 项失败：一个 Windows 原生绝对路径判断问题，四个旧消息恢复时被补入默认结构造成的身份差异。修正后仅补跑受影响的 37 项，全部通过。其余原消息、会议、Wire 和迁移检查保留首轮结果；不重复全量测试。证据 `build/reports/nexus-delivery/20261111-r1-artifacts-initial/`、`20261111-r1-artifacts-fixed/`。
- MCP 服务/工具、Skill 开关、自学习、记忆整理策略和技能提炼设置已继续接入原控制面，沿用原库和 Worker。停用 MCP 的 UI/Agent/撤销路径共用关闭本地进程动作。策略界面删除多段解释备注，写入失败不先显示成功状态。
- 设置补充检查中修正了一个新增测试声明和一个把读取回执中的随机 auditId 当配置比较的测试断言；原始失败结果保留，未改掉实际存储/范围/撤销断言。8 项设置用例通过，记忆策略用例在下述 R2 相关检查中通过。

### R1 自动备份设置共用控制面（2026-11-11 继续）

- 原备份页面保存周期、时间、保留份数、目录和内容选项，现经 `AutoBackupSettingsRepository` 接入既有设置控制面与原 `AutoBackupStore`/WorkManager，不新增设置库、导航或页面。UI 读取实际保存后的值，不先把草稿当成功状态。
- 软件管家/Agent 可以从 `settings.backup.automatic` 读取同一配置并按完整参数修改；参数有类型、取值、内容和未知字段校验。修改前/后值、来源与结果进入原审计，撤销核对当前指纹，后续保存后不能再用旧记录覆盖。
- 启用时确认保存目录有 Android 持久读写授权，涉及凭据的备份必须有可解密的本机备份密码。Agent 不能凭路径授予目录权限，也没有读取/填写备份密码接口；密码沿用原本机设置与加密存储。已撤销目录权限仍能关闭原任务，重新启用须重新授权。
- 相关 19 项检查、Kotlin 编译及架构检查一次通过，0 失败/跳过。实际测试原页面所用保存入口→原设置存储→原 WorkManager 登记/取消、控制面回读/撤销、目录授权失效、非法参数和远端写入拒绝；不以此声称手机后台调度已验收。证据 `build/reports/nexus-delivery/20261111-r1-backup-settings/`。
- 本轮没有改 UI 布局、没有打包/装机。R1 继续处理消息计划/轨迹/交互引用和其余设置所有者；外部连接配置随 R5 一并收口，R7 最终备份覆盖与 R8 正式桌面交付保持原范围。

### R1 工具详情与历史版本引用（2026-11-11 继续）

- 普通会话和会议工具行现在关联同一正式执行 Item，仍使用原工具行、折叠和展开布局。Item 身份由原 Recorder 与 UI 投影共用确定规则生成，不按摘要猜关联。普通会话沿用已有结构化消息列保存引用，会议沿用已存的运行/轮次/调用编号还原；不另建消息库。
- 工具详情通过只读应用服务获取正式状态、时间及失败码；计划/目标/记事本写入的不可变引用由真实工具结果进入 Runtime 事件、IPC/归档、执行 Item，再交详情读取。读取当时版本，后续修改计划不会替换旧消息内容；版本缺失或校验损坏明确不可用，不拿最新状态冒充历史结果。
- 结构化轨迹/计划卡具备实际展开消费者，计划卡可带明确的执行 Item。历史补充输入和审批默认不可操作，真实待答窗口仍显式启用原回答链路，不能从历史卡重新批准或恢复任务。
- 初次编译发现跨模块可空属性不能直接智能转换，固定局部值后通过；新增 UI 夹具随后误用了测试库不存在的泛型方法，修正后首次实际运行 53 项，52 通过，1 项 UI 断言在文字节点上找按钮禁用标记失败。改为检查真实父按钮后仅补该项，架构检查和该项通过。没有重复运行此前通过的 52 项。
- 基本证据包含真实 AgentLoop→工作流写入→事件/Wire/归档→账本→工具行→会话保存还原→原版本读取；缺失/损坏正文不会回退为新计划，历史提问只读。Compose 在 320dp 深色/1.3 倍字体下展开原工具行，显示真实计划和验证内容，截图已检查无重叠。证据：`20261111-r1-execution-references-initial/`、`20261111-r1-execution-references-ui-fixed/`；属于本机基本验证，不是手机验收。
- UI 仅补原展开详情和引用操作，未搬动页面或主布局。R1 仍有其他设置所有者与外部产物余项，按原计划继续；未生成安装包或操作 Git。

### R1 模型参数保存与敏感配置隔离（2026-11-11 继续）

- 原模型编辑页的保存/新建入口现经过统一设置控制面，再调用 ETA 原 `ModelRepository`，不更改编辑弹窗、上下文/思考/多模态设置的位置。软件管家增加 `settings.model.configure`，按提供商与模型身份更新已存在模型的公开参数；模型目录仍从原状态接口读取。
- 公开模型参数保存前后进入审计，可冲突检测和撤销；新建模型也可撤销移除。提供商密钥、自定义头和自定义请求体不进入记录或工具返回，只保存私有配置指纹。普通参数撤销保留原私有配置；如果本次修改私有内容本身，明确不提供不能还原的撤销，不把脱敏占位符写回实际配置。
- 参数接口拒绝未声明字段、非法上下文和远端直接修改；后续编辑或私有配置变化不能被旧记录覆盖。本轮没有扩张 Agent 的密钥读取能力，也未改内部本地模型登记/远端目录同步的既有所有者。
- Kotlin/架构及 22 项相关检查一次通过，0 失败/跳过，包含原模型 Repository 基本检查、实际 UI 保存服务与 Agent 更新、密钥不泄露、私有配置保留、新建撤销和旧撤销冲突。证据 `build/reports/nexus-delivery/20261111-r1-model-settings/`。没有真机验收、打包或 Git 操作。

### R1 默认思考、压缩与本机工具开关（2026-11-11 继续）

- 模型提供商页的默认思考/自动压缩、工具与设备的六个现有开关，以及本机子 Agent/虚拟屏开关，继续保留原布局和偏好存储，保存经原设置控制面提交、回读和审计。界面在保存期间避免重复提交，失败不显示已保存；Hook 专属的旧远端开关仍走原 Hook 所有者，不伪造离线可用。
- `settings.runtime.<既有键>` 共九个固定能力，Agent 不能提交任意偏好键。思考与压缩为普通设置；工具边界变更需实际本机批准，不能由模型参数自行确认。UI 的实际用户开关操作经专用应用方法提交，不重复要求同一次点击再弹批准；撤销权限变化仍保留确认。远端请求不能直接修改本机设置。
- 实际 `AgentRuntimePolicy.permissions` 消费的就是原本机偏好，改变开关会同步到原协调逻辑；不授予 Android 权限、不替代 Root/高风险工具的执行确认。相关编译/架构及基本检查一次通过，证据 `build/reports/nexus-delivery/20261111-r1-runtime-settings/`。未做设备验收或正式打包。

### R1/R5 外部连接所有者、凭据隔离与撤销（2026-11-11 继续）

- 统一连接页和 DSH 主机页的新增、保存、启停与删除接同一设置审计，沿用原 Connector 配置和加密凭据存储。普通更名/启停可经本机批准撤销，令牌更换、删除或会丢失原凭据的修改不提供伪还原；地址含查询参数时不把其可能携带的密钥写入审计。Agent 可读取连接身份/修订，在本机确认后修改已有连接名称/启用；地址/协议与令牌由原本机页面管理，不能借配置变更转发旧凭据。
- 配置 JSON 损坏不再当空列表覆盖，限定配置数量/体积并验证重复编号；保存增加修订，旧页面不能覆盖新配置。凭据别名使用完整连接身份哈希，不再因大小写、非 ASCII 替换或截短共用令牌。旧唯一别名在不解密的情况下迁移；已存在归属冲突的旧别名标记隔离、要求重新填写，不在删除其中一个连接后再错误归给另一个。
- 保存连接调用不接受临时参数覆盖其凭据；原凭据解密失败明确终止。调用持有配置身份，配置/令牌/启停/删除变化会关闭实际传输；DSH 客户端、共享 UI 流绑定同一配置修订，Cookie 自动刷新保持原身份，旧客户端不能在关闭再开启后恢复旧授权。
- 首轮相关 17 项中 16 通过，1 项旧断言期望连接删除后返回 null；新边界明确拒绝已删除连接，更新断言后补单项。第一次单项筛选未运行：Kotlin 表达式测试因最后 assertThrows 返回异常对象而不再是 void，补显式 Unit 后该项和架构通过。后续提供商批次只复跑有新增数量/体积保护的六项连接检查，均通过，没有重跑整组或全量。
- 证据 `20261111-r1-r5-connector-initial/`、`20261111-r1-r5-connector-fixed/` 及下批 `20261111-r1-provider-settings/`。包含实际 HTTP 响应阻塞→停用→关闭请求、配置损坏不覆盖、旧别名冲突/迁移、真实设置保存/审计/本机确认撤销；不冒充真实外部服务或手机测试。UI 位置与原布局保持不变。

### R1 提供商编辑与登录前保存（2026-11-11 继续）

- 提供商原新建/编辑页和登录前保存接 `ProviderSettingsService`，继续调用 ETA 原 ProviderRepository/凭据会话处理，不另建提供商数据源。编辑基于原公开/私有配置指纹检查过期页面；原模型目录同步独立保留。
- 提供商名称、启用、专属提示和托管搜索可由 Agent 在实际本机确认后修改，连接地址、账号登录和密钥仍在原模型页。公开设置留审计与可验证撤销，模型目录用指纹防止后续模型变化被旧记录覆盖；原模型列表和私有请求头在普通撤销时保留。密钥、请求头和自定义请求体不进入记录，鉴权/地址相关修改不宣称可还原。
- 提供商新增/编辑/旧页面冲突、秘密不入审计、实际本机确认、含敏感查询地址不被更名意外截断，以及原登录 Store 相关基本检查与编译/架构一次通过。本轮共 18 项包含上批新增连接边界的 6 项，不能作为新的独立覆盖量累计。证据 `build/reports/nexus-delivery/20261111-r1-provider-settings/`。未更改 UI 结构、打包、装机或 Git 状态。

### R1 网页工具配置与凭据一致性（2026-11-11 继续）

- 原网页搜索/读取设置页接同一设置控制面和审计；原校验移入配置所有者，UI 和 Agent 切换后端共用校验，不在数据层依赖 UI。沿用原 DataStore 和 Android Keystore，不新增网页配置库。
- UI 保存凭据与配置集中执行，配置未写入时恢复原加密凭据快照；保存结果仍不确定时保留可核对状态。修改时校验此前配置和私有指纹，防止后来设置被旧提交覆盖。仅实际提交窗口不可中断，不把长时间外部请求置于不可取消区域。
- Agent 可以切换已配置的搜索/读取后端，不能通过该入口写 API Key、URL 或请求头。缺密钥/缺有效地址的后端在写入前拒绝；运行端读同一实际配置。URL 查询、请求头、请求体和加密凭据不进入审计或回读，普通后端切换可撤销并保留私有内容，私有字段修改不提供伪撤销。
- 相关 Kotlin/架构和基本检查一次通过，证据 `build/reports/nexus-delivery/20261111-r1-web-settings/`。覆盖原网页工具检查及保存→真实 RuntimeConfig→Agent 后端切换→撤销、秘密不入记录和非法配置拒绝；没有实际请求外部收费接口或替代真机验收。UI 布局保持原样。

### R2 用户补充输入

- 新增实际 `request_user_input` 工具，走原 AgentLoop 与本地工具执行器，不另启一轮对话。用户可以选项或文字作答；不把取消、超时或未提交当作默认同意。
- 问题和回答复用已有私有不可变版本存储，先保存再展示/唤醒；正式执行库以相同 ItemId 事务记录 USER_INPUT_REQUIRED/RESOLVED 和 WAITING_USER 状态。回答进入原工具结果、后续模型回合和检查点，元数据账本不保存正文。
- 同一任务的暂停、权限确认与补充输入分别保留状态；取消/硬超时会释放等待与界面。子 Agent 保留原有“问题交主 Agent 统一处理”的职责，不直接向终端用户争抢弹窗。重启后沿用已有人工恢复检查点机制，不自动重放提问或未知副作用。
- App 全局补充输入窗口使用共用 ContentPart 渲染器；高风险确认优先，设置记录/文件窗口暂隐，避免二次窗口互相遮挡。当前用户须在 App 内处理提问；屏幕接管期间的语音/浮窗协作继续列在 R6。
- 第一轮基本链路 61 项全部通过，包括真实 AgentModelClient→AgentLocalTools 提问→用户回答→同 Run 下一模型回合、暂停、取消、超时、孤立回执拒绝。架构检查与编译通过。证据 `build/reports/nexus-delivery/20261111-r2-user-input/`。
- 运行列表快照现区分 WaitingForInput，主/子 Agent 职责保持原边界。原生和本次注册的 MCP/API/子 Agent 等额外工具，在执行与审批前校验实际发布的参数 Schema；发现/激活操作也复用 ETA 原校验器。补齐 null、数组条目数/重复、元素类型、有限数字和深度限制，不另造一套参数校验。该校验器仍是已声明的 Schema 子集，不宣称支持全部 JSON Schema；路径/URL/设备等细粒度范围继续由原执行后端校验。
- 后一轮 104 项检查中 103 通过、1 项发现“关闭能力发现时返回参数错误”的顺序回归。恢复先检查授权/启用状态的顺序后，只补跑受影响的 7 项，全部通过。其余 97 项沿用该轮结果，不重复全量测试。证据 `build/reports/nexus-delivery/20261111-r2-input-schema-initial/`、`20261111-r2-input-schema-fixed/`。
- 此时 R2 仍未整批完成；后台模型调用的后续接线见下文。硬预算追加、单笔费用预留、其他 Host 的实际权限/取消和交互引用继续实施。此前 61 项与后续 104 项有重叠，不相加当作独立覆盖量。

### R2/R3 后台模型任务与技能版本闭环

- 记忆整理、Skill 提炼和语音识别后处理原先直接调用 Provider。现在复用现有 Runtime IPC/API 准入、执行库、AgentLoop、请求预算、统计、取消与 outbox；新增 `model_task` 操作明确禁止工具，只发送原专用提示与来源数据，不灌入普通聊天上下文、Skill、记忆或提示词模板。
- 保留各模块原解析器和 Worker。记忆/Skill 使用 NOTE_CONSOLIDATION 来源与 90 秒期限，语音纠错使用自己的 8 秒期限；入口取消会沿原 Binder 通道取消实际 Runtime。账号登录模型刷新本地连接配置时保留固定整理提示与禁用工具状态。失败/不完整结果不能进入记忆或技能候选。
- 一轮 138 项相关检查中 137 通过，新增用例的整体请求相等断言未考虑原 Wire 已有的 null 推理选项归一为 OFF。修正预期后只补跑该用例，通过三类后台任务的真实准入→执行器→模型→账本/预算/统计/结果链。原 HTTP 用例现经过该执行器，验证真实流式 Provider→记忆候选；原语音超时和生命周期用例保留。证据 `build/reports/nexus-delivery/20261111-r2-maintenance-initial/`、`20261111-r2-maintenance-fixed/`。未做真机 Binder/真实模型质量验收。
- 修复技能草稿覆盖已发布配置的问题：运行端与自动任务只读取已有版本表中的已发布正文/工具范围，草稿待验证或被拒绝不更改线上版本。新版至少新增 3 个匹配工具范围的独立成功来源，原版本证据不能重复充当新版验证；启用仍要人工确认，发布保留来源 Run 列表与版本备份。
- 提炼提交再次核对源记录仍存在且未被处理，防止恢复期间的迟到模型结果复活旧来源。新候选/暂停/拒绝写入由原 Skill 业务服务处理，移除该页面直接写 DAO 的操作。失败质量窗口只计本次已发布版本之后的调用结果。
- 21 项相关检查全部通过，包含成功任务经验采集→3 次实际 Runtime 提炼→候选复核→安装启用→新草稿隔离→3 个新来源→v2 发布，以及旧来源已移除的保护。原工作流与记忆页面检查通过。证据 `build/reports/nexus-delivery/20261111-r3-skill-evolution/`。自动任务触发/资源等余项仍继续，不将此项等同 R3 整批完成。
- 自动任务的新文件事件在上一任务运行时进入原持久队列，仍由原全局执行租约串行领取；流程超时通过可中断等待取消实际 Runtime。网络触发统一判断交接、失去验证/恢复和以太网/VPN，避免把网络切换误报完全断线或忽略重复恢复。
- 自动任务相关 19 项初轮有 1 项新增夹具失败：先使用了产品不允许的 0 秒冷却，修正后又发现用测试早前时间领取新插入队列，尚未到真实 scheduledFor。保留产品最少 60 秒冷却，按当前时刻验证领取后，该用例在下一批相关检查中通过；其他 18 项沿用初轮结果。证据 `build/reports/nexus-delivery/20261111-r3-automation-initial/`、`20261111-r3-automation-clock/`、下方 R4 结果。

### R3 离线维护、技能质量与触发可靠性（2026-11-11 继续）

- 记忆整理与技能提炼共用设备条件检查：本地文字模型在离线时可以整理，在线模型必须有已验证网络；自动维护遵守插电、空闲、熄屏、静默时段及非计费网络设置。手动维护跳过空闲/插电/静默要求，但不能绕过在线模型的网络需求。等待条件不消耗提炼次数；前台任务开始后取消后台模型请求并保留来源待重试。WorkManager 不再用统一“必须联网”误挡本地维护。
- Skill 数量少时也按任务相关性排序，排除未安装/已停用项；空名称不再获得匹配加分。质量区分成功、明确失败与结果未确认：取消、网络故障或没有结算结果不算技能失败。已知结果用于质量比率，未知结果不稀释失败率。调用记录以 Run+Skill 去重并共用写锁，自动任务显式选用技能也进入同一统计；连续停用判断只看已发布版本的已结算结果。
- 自动任务先把事件保存到原持久队列，再在模型/工具执行前等设备条件，不再在充电/网络条件尚未满足时丢弃一次性事件。等待后仍是同一个 Run，沿用原租约和串行执行。应用打开监听只推进到已接收的实际事件，避免一次扫描跳过后续打开事件。保存端拒绝同时写入时间与事件两种触发；启停同步处理关联时间规则。
- 任务链游标由单一历史编号改为完成时间与同一时间已处理编号集合。清理已消费历史不会从头重放，同毫秒新增的完成事件也能逐个接收。旧编号存在时平滑转换；旧编号已删除时明确重建基线，避免猜测并重放旧任务。位置触发检查定位实际采集时间和精度；定位误差圈跨过区域边界时保留之前状态并等待，避免过期定位或边缘漂移触发任务。
- 维护/匹配/质量相关 24 项通过；事件条件/文件输入/工作流相关 21 项通过；任务链/定位与相关保存逻辑 15 项通过，0 失败/跳过。各轮有交叠，不合计成独立覆盖量。最后一轮命令最初填错架构任务名，在执行代码检查前失败；改用现有 `checkNexusArchitecture` 后编译/架构和相关检查通过，没有重复全量回归。
- 证据：`build/reports/nexus-delivery/20261111-r3-maintenance-skill-quality/`、`20261111-r3-event-conditions/`、`20261111-r3-completion-location/`。配合前一轮经验→候选→人工发布→新版本隔离和文件触发→输入→工作流证据，继续进入 R1 设置与呈现余项。真实模型提炼质量、系统后台投递、位置移动与手机长期表现仍由用户验收；本轮没有改 UI、打包或装机。
- 再次确认用户的 UI 要求：基于 ETA 现有 Miuix 风格，保持主导航、页面分组、大致布局与常用操作位置；能力中心只参考细节质感，不替换全局布局。明显结构调整先给用户看。

### R4 本地推理让位

- 现有 `ResourcePriorityPolicy` 接入实际的 LiteRT-LM、llama.cpp 和 MNN Provider 包装层，原运行器和 CPU/GPU/NPU 设置保留。三个文本引擎共用推理队列，前台对话先于用户任务和后台整理；同优先级按入队顺序，暂停/取消不占用后续队列。
- 已经开始的用户任务不会为了排队而从头重做。正在进行的后台整理可以被前台对话中止，在原 Worker 中保留源任务等待下次空闲；RESOURCE_YIELD 错误码跨 Wire 和账本传递，不作为用户主动取消，也不把记忆整理的让位计为失败次数。
- 后台整理在低内存、省电和严重热状态下不进入本地引擎；Application 内存通知可取消正在整理的资源。排队发生在模型预算预占之前，未进入推理的排队不消耗模型请求额度。软件管家运行状态已可读取真实活动/排队 Run 元数据。
- 本轮 App 88 项、Application API 10 项全部通过。包含实际 Provider 包装层让位、压力准入、活动资源取消、三类整理任务、Wire 和原预算回归，以及修正的自动事件排队用例。证据 `build/reports/nexus-delivery/20261111-r4-local-scheduling/`。这不是本地模型性能或真机音频验收；ASR/TTS 保持现有独立 Owner，跨进程语音调度、热状态即时通知和手动预热余项继续 R4/R6。

### R5 外部 API 关闭与 OAuth

- 修复 `ApiToolExecutor.close()` 为空的问题。沿现有 RoutingToolExecutor 的取消/关闭路径，取消本次 API 与令牌刷新 HTTP；拒绝关闭后再发送请求。共享 HTTP 连接池保留，不取消其他 Agent 的连接。
- API 和 OAuth 使用同一个按 Owner 跟踪调用的 HTTP 会话，关闭会中断已经拿到响应头但还在等待正文的请求。保留重定向拒绝、响应大小上限和一次 401 刷新机制。
- 修复 OAuth 注册跳转地址是 `nexus://...`、回调处理却只认 `eta://...` 的实际错误。现在从同一回调常量验证，错误应用地址、错误 state 和带 fragment 的回调被拒绝。
- 相关 API/OAuth/MCP 基本检查通过，包含真实 HTTP 正文阻塞→Controller 取消→路由关闭→请求退出→拒绝后续调用；API/OAuth 分别覆盖 Robolectric 36/37。证据 `build/reports/nexus-delivery/20261111-r5-api-lifecycle/`。尚未完成全部 Host 注册、插件/沙盒和 DSH 余项，不因此宣称 R5 完成。

### R8 发布准备

- 发现原发布脚本仍读取 `ETA_RELEASE_*` 和 `.eta-signing`，与 Nexus Gradle 所需的 `NEXUS_RELEASE_*` 不匹配。已改为 Nexus 独立配置，构建结束恢复进程环境，不沿用 ETA 密钥路径。
- 默认只做正式构建，全量测试改为显式 `-RunTests`；支持当前 AAPT2 路径、单 Worker 和不驻留构建进程。验证器缺失、签名无效、Android Debug 证书、错误包名或 debuggable 包均拒绝交付；生成 SHA256 文件。
- 当前仅完成脚本语法解析检查，未运行正式构建、未创建密钥、未输出桌面包，不将发布准备记成 R8 完成。最终仍需递增版本、核验 ARM64/组件/签名和交付说明。

### R7 备份与迁移实装

- 复用原完整 ZIP/加密容器与 SAF 流程，完整清单升至 v4，加入来源应用、来源版本、实际数据库版本和 14 类迁移内容分类。`NexusMigrationValidator` 现在由真实导出、预览、暂存和启动恢复使用；区分 Eta 导入与 Nexus 自身备份恢复，不再把同应用恢复当成来源错误。旧 v1–v3 完整包与旧 JSON 仍由原兼容入口读取。
- 新格式校验来源、迁移协议、类别、数据库实际版本/清单一致性及原有每文件哈希/大小/路径；未知或更新格式在覆盖前拒绝。修正 Kernel 路径校验误拒绝普通 `report..final.md`，继续拒绝真正的上级目录、绝对路径、Windows 路径和空路径段；大小汇总防整数溢出。
- 修复完整包在无密码时写入可迁移登录凭据的问题。包含 API 密钥、可解密登录/连接/语音凭据或缓存模型密钥的完整导出需要密码；带 API 密钥的模型 JSON 导出同样需要加密。无凭据数据仍可不设密码。恢复前安全快照沿用本次输入的密码，避免恢复流程另产生明文凭据副本；导入旧明文包时如当前 Nexus 含凭据，也需输入密码保存当前安全快照。
- 导入预览保存所选文件 SHA256，真正恢复前重新读取的文件必须与预览相同；变化则在导入器读取数据前拒绝。界面展示来源产品/版本，导出窗口检查凭据后再允许选位置；自动备份启用/切换格式时检查当前内容，缺少必需密码则进入原密码设置，不先保存成一个会持续失败的计划。
- 手动导出/导入和自动备份占用原互斥保护时，统一 Runtime 准入暂时拒绝新任务；恢复已暂存或激活未完成也拒绝启动。无工具本地回复兜底遵循相同保护。记忆/Skill 的维护互斥仍允许自身模型任务，不会因复用同一锁自锁；被备份挡住的整理保留来源等待重试。
- 激活前原子保存本次涉及哪些根目录、哪些原本存在。中断回退既恢复已经移走的原数据，也删除恢复过程中新增、原来不存在的根目录；仍保留没有移动的原根目录。pending/applying/committed 控制状态改用 AtomicFile，失败回退后清理状态；旧恢复记录缺少新日志时保留原兼容处理。
- 第一轮 App 44 项、Application API 11 项、Kernel 9 项全部通过，含实际文件工具产物/结构化消息/运行事实/工作流笔记的加密导出→预览→暂存→启动恢复，新清单拒绝和旧包兼容，以及预览后文件变化保护。其后准入/兜底 12 项通过，激活日志相关 3 项通过；检查之间有重叠，不相加当作独立覆盖量。证据 `build/reports/nexus-delivery/20261111-r7-migration/`、`20261111-r7-admission-guard/`、`20261111-r7-activation-journal/`。新密码流程的 UI 基本检查进行中，未做手机/跨设备验收。
- 密码预检查和自动备份设置异步保存已编译，导入/导出进度分离及窄屏大字弹窗的 3 项现有 UI 检查通过（`build/nexus-r7-backup-ui.log`，当时 XML 未另存，后续构建已覆盖，不冒称保存有该轮 XML）。无密码备份仍需注意工作区/自定义文件可能含用户自行保存的敏感内容；新加密门禁针对软件掌握的凭据，不宣称能扫描任意文件所有秘密。
- 继续修复 `includeAppFiles=false` 把 files 下控制配置一起跳过的问题：现在默认保留原 DataStore、分层记忆、工作流/不可变版本、设置审计、产物索引、能力中心/Skill、角色/附件和模型目录索引；大模型权重、Linux 环境、工作区文件仍由“包括应用文件”选择。
- 配置包清单显式列出固定文件恢复单位，激活/中断回退沿用同一原子日志按单位替换，不整个覆盖 files 目录，避免导入小备份时删除现存模型权重或用户报告。恢复范围由软件固定白名单校验，不能用包内自填路径扩大覆盖范围；完整文件备份及旧包仍沿用原根目录激活方式。
- 此项 4 项相关检查全部通过，验证默认配置备份恢复工作流和模型索引时，当前大模型文件/工作区报告保持原内容，越界恢复单位被拒绝，原账本和完整加密恢复仍通过。证据 `build/reports/nexus-delivery/20261111-r7-configuration-files/`。R7 当前基本流程可运行，后续新增 Host/模型/语音状态仍随对应模块纳入；跨设备凭据重包裹和厂商启动细节未进行真机验收。

### R6 执行期间语音补充

- 沿现有 Runtime 胶囊增加紧凑麦克风图标。点按后由原助手语音服务和原 SpeechInputController 采集，沿已有前台麦克风声明处理权限；胶囊显示监听/识别、取消/发送和结果状态，无需返回 App 或重新弹全屏助手窗口。
- 新增已有 Runtime Messenger 的补充请求/确认消息，只有本应用 UID 可给已有 Run 补充。复用原 Controller steering 队列和 AgentLoop，补充在下一回合进入同一任务；不取消正在执行的工具、不从头重做任务。暂停任务保留补充等待继续，结束/已封口任务明确拒绝；8 秒未收到确认显示状态未知，不自动重发。
- 助手全屏面板在处理任务时允许文字输入并增加语音补充入口，仍保留停止按钮。外部语音 Run 的补充进入原 handoff/outbox 及会话导入，保留原消息身份；读取新字段的旧包缺省为空。
- 语音补充继续使用已配置的本地或云端 ASR。没有麦克风权限、识别关闭、服务启动失败或任务状态不可用时如实显示；迟到的旧任务状态不能覆盖新捕获，失败/未确认保留识别文字供用户核对。当前这是任务执行期间的语音输入与已有 TTS 打断协作，未宣称已经变成完整声学全双工。
- 一轮 App 78 项相关检查全部通过，含真实 Service IncomingHandler 接收/确认→现有 AgentModelClient Loop 消费→同一 Run 下一模型回合、跨 UID 拒绝、迟到补充拒绝、外部归档字段、原语音录音/播放/打断生命周期回归。证据 `build/reports/nexus-delivery/20261111-r6-voice-supplement/`。Robolectric 的声学与厂商后台行为不是真机结果，真实麦克风、屏幕接管、音色和 Android/ColorOS 使用验收仍交用户。

### R5 实际 Host 注册与连接取消（2026-11-11 继续）

- 生产 Runtime 实例化现有 HostAdapterRegistry：同一份 MCP/API 工具描述供发现、激活、根/子 Agent 权限和执行使用，注册表路由到原执行器，不新增 AgentLoop 或 Host 数据库。API 只读/写入效果保持一致；MCP 不将远端 readOnlyHint 当成可信授权。停用、撤销或连接配置变化后，当前 Run 后续 schema 和调用同步拒绝旧工具。
- 本机高风险确认显示实际 HostId、效果、有效期和所属根/子 Run；控制面实时状态沿原 Runtime Messenger 返回 Host 状态与工具 ID，不包含令牌。完整持久 Host 事实、Linux/A2A/DSH 接线仍待继续，不把状态快照当成完整账本。
- MCP 每次调用持有自己的传输连接，Controller 取消只关闭所属请求，避免一个子任务误断其他任务；API、JSON-RPC/GraphQL/SSE、Unix Socket、Shell 和 WebSocket 的既有关闭路径补齐请求/进程释放与响应上限。SSE 先检查事件数量再读网络，避免收够事件仍等待新数据。
- 修复 MCP 目录缓存命中后没有启动 launchCommand 服务的问题：实际工具执行、目录发现和详情检查统一复用原 McpProcessManager。服务按应用共享，由明确停止操作关闭，不因某次探测/调用失败停止；旧配置服务仍存活时拒绝静默替换。启动等待检查取消，连接探测可中断，失败日志只读取有界尾部。
- Host/API/子 Agent/模型任务相关 16 项通过（`20261111-r5-run-hosts-fixed/`）。原 103 项首轮中的 2 项失败定位为本机 HTTP 元数据校验误拒绝 loopback，已修正仅 loopback 允许 HTTP/任意有效服务端口，远端限制保留。
- MCP/传输首轮 7 项中，既有 MCP 3 项和新 HTTP/SSE 2 项通过；另 2 项进程检查因依赖异步 shell 日志标记失败，改为读取实际 Process 存活状态后 2 项通过（`20261111-r5-local-processes-initial/`、`20261111-r5-local-processes-fixed/`）。未反复执行全量检查，未做真机或远端服务验收。R5 仍未完成：插件/沙盒真实运行与默认包、DSH 协议插槽和其他 Host 余项继续实施。

### R5 A2A 实际工具入口与请求保留

- 复用已有 A2A Agent Card、人工允许的对端、HTTP 协议、任务/事件/隔离产物表，已配置且启用的对端进入同一 Host 注册与延迟工具目录。普通 Run 可发现并调用派发、查询和取消；子 Agent 沿既有工具白名单交集获得能力，未扩大默认白名单。发送和取消使用 HIGH_RISK 本机确认，查询使用 READ；远端来访 Run 仍不能触发高风险能力。
- 派发使用根/子 Run 与工具调用 ID 得到稳定请求标识，发送前在原 A2A 表原子占位。回执丢失保留 SEND_UNCONFIRMED，再次执行相同调用只返回已登记状态；不重发未知副作用。返回按文本/状态/产物元数据摘要，全文和隔离产物留在原存档，避免把大型远端回复全部塞入上下文。
- 查询/取消校验任务确属该对端，停用、移除或修改对端后当前 Run 立即拒绝旧工具；取消没有取得远端确认时不显示已取消。HTTP 复用现有调用会话，协程取消和 Controller 取消均中断正文读取；流只取一项时可退出仍在等待的 SSE，不关闭其他调用。拒绝自动跳转，增加连接/请求期限和有界事件行。
- 第一轮 10 项中协议/策略与真实 HTTP 取消、流消费取消、跳转拒绝共 8 项通过；2 项 Host 夹具使用相同唯一 Card URL 且未释放测试库而失败，修正夹具后这 2 项通过。证据 `20261111-r5-a2a-hosts-initial/`、`20261111-r5-a2a-hosts-fixed/`。实际通过的是本机对端和原任务库链路，跨设备、真实账户、锁屏和长期后台仍交用户验收。未把已有会议/任务独立入口一概宣称成已完成统一 Run 接线，R5 继续活动。

### R5 连接配额与终端取消继续接通

- 每个实际外部 Host 在同一 Run 家族内限制并发调用，超额直接返回本次未执行，不堆积无界等待。错误路径也归还名额；单个执行器意外异常转成可追踪的失败回执，取消仍保留取消语义。实时快照增加用户配置名称、活动数、并发上限和去敏错误码。API 与统一连接工具使用 CONNECTOR 类型，避免把外部 API 误标成原生设备能力。
- 外部 HTTP 关闭隐式连接重试，MCP 同时拒绝自动跳转并设置整体调用期限。继续保留原明确的协议兼容和 OAuth 401 刷新，不添加对未知副作用的自动重发。
- 原 ShellProcessSupervisor 的 Android/Linux 单次命令绑定当前根/子 Controller，取消只回收本次进程树；正在使用的持久 Shell 只关闭相应会话，文件读取/遍历也检查当前 Controller。没有关闭共享终端 Owner，也没有重新实现 Shell/PRoot/chroot；明确创建的跨运行后台任务仍沿用原独立生命周期。
- 取消链路 18 项：15 通过、3 项 POSIX 原生进程测试在 Windows 跳过，0 失败；覆盖已有 API/A2A/MCP/连接器实际 HTTP 链路和取消后不接纳 Shell/文件操作。证据 `20261111-r5-call-cancellation/`。真实 Android/Linux 进程树取消尚无手机证据，不将预取消检查说成已验证真实进程回收。
- Host 配额最后一轮 App 6 项及 API 合同检查通过，含并发拒绝、错误释放名额、故障状态和恢复状态；原始计数/XML 保存在 `20261111-r5-host-limits/`。这些检查与前面有重叠，不相加成独立覆盖率。未执行全量测试或真机反复调试。
- 下一段仍为 R5：Linux WorkspaceBinding/资源边界、已有会议/自动任务外部入口的 Run 归属、Operit 插件与沙盒运行、DSH 实际协议/插槽。Operit 的独立 QuickJS 模块可作为复用候选，但其 CMake 当前引用 master，且部分宿主接口使用反射；集成前需固定来源并收窄桥接，尚未复制或声称可运行这些包。R8 正式签名和桌面包尚未执行。

### R5 插件与沙盒包基础实装（2026-11-11 继续）

- 新增真实 QuickJS 原生运行模块，复用 Operit `16cffb98f52a11dc77bd0a789ffa0b01ed398ee4` 的 JNI/Kotlin/兼容层，QuickJS 固定在 Bellard `e585640ffa2cf88ce7c2d587583c6fe15928ec2f`，不在构建时追踪 master。加入实际堆内存/栈限制、受限 HostBridge 和中断；不引入上游任意 Java 反射接口。LGPL/GPL/MIT 许可及来源随模块与 APK assets 保留，第三方代码不改用应用的 PolyForm 许可证。
- 原生模块 `assembleDebug` 一次通过；实际 ELF 为 ARM64，LOAD 段均为 16 KiB 对齐，只依赖 Android 平台库，没有新增共享 C++ 运行库冲突。这是原生编译/二进制证据，尚未在 Android 上执行 JNI，不冒称手机插件已跑通。
- 增加非导出、独立 UID 的 `PackageSandboxService`，通过私有绑定、双向 Binder、只读文件描述符与 UID 握手调用。Application 在隔离 UID 中跳过存储/语音/模型初始化。每次调用独立 JS 堆，超时先中断再回收隔离进程；结束解绑并回收进程。全应用最多两个插件同时执行，内存紧张时不启动插件，不卸载主文字/ASR 模型。
- 插件的 Native 工具请求返回当前 Run 的原执行器、权限 Gate 和预算；子 Agent 使用自己的原执行器，所以插件不能借父执行器绕开子角色工具白名单。增加嵌套资源作用域，沙盒中止会释放它等待中的 HTTP/审批/文件/命令资源，不取消仍可继续的父 Run。单次原生调用数有上限，结果包含实际 JS 内存、峰值、宿主调用数和耗时。
- JS 插件和 ZIP/ToolPkg 采用暂存预览、清单/HJSON 解析、路径/重复项/软链接/体积校验、SHA256 不可变版本及单一原子索引。支持安装、更新、回退、停用、显式工具授权、内存/时限配置和卸载。更新后停用并清空新增授权；预览时的旧版本变化则拒绝覆盖；删除预置时间包后不会自动重新安装。没有执行导入包来读取元数据。
- 31 个 Operit 预置脚本完整分发、可在预置列表导入，未默认授权工具。原时间包经源码比对后作为只读能力进入同一 Host 目录；第三方包默认高风险确认。已在生产 Runtime 接入插件工具发现、启用检查、执行和关闭，不另建 AgentLoop。ToolPkg 的脚本/资源实际保留，尚未实现的主注册、状态工具、WASM/扩展界面及完整 Operit SDK 均保留待适配状态，不能把“31 个脚本已安装”说成“31 个插件完整运行”。
- 能力中心“连接”新增“插件与沙盒包”，沿用现有玻璃材质、图标、排版和状态组件。提供已安装/预置、插件/沙盒筛选、导入预览，以及同一详情窗口中的概览、权限和运行参数；回退/卸载在原窗口内切换确认内容，没有在窗口后面叠第二层选择框。配置修改接原设置控制面与冲突保护撤销，安装/卸载记录本机来源审计。UI 已编译，未做手机渲染验收。
- 默认配置备份加入 `files/capability-packages`，并归入原 mcp_skills 类别。隔离的配置备份→暂存→启动激活实际恢复包版本、启用与运行参数，同时保留未选中的模型权重和工作区文件；临时导入和正在执行的沙盒不进入备份。
- 首轮 App 29 项中 25 通过、4 个包存储检查失败；发现 Windows 下旧原子写入未替换旧索引，改用项目已有的同步落盘+原子替换模式后，第二轮 18 项中 17 通过。余下一项定位为旧 ZIP 类缺少 builder API，改用兼容构造入口并加入真实 ToolPkg 正向导入后，5 项包管理检查全部通过。期间使用了不存在的旧 SubagentExecutorTest 筛选名；已改为实际 SubagentTest/SubagentDurableExecutionTest，本轮真实执行并通过子循环、权限继承和共享预算检查，不用旧筛选名声称已覆盖。
- 源 JS SDK 的时间脚本、CommonJS 模块、宿主调用和拒绝传播在 Node 上通过；它证明 JS 适配逻辑，不等同 Android JNI/隔离进程证据。隔离启动与释放的最后修改仅补 Kotlin 编译，19 秒成功，没有重复全量检查。日志/XML：`20261111-r5-packages-initial/`、`20261111-r5-packages-index-fix/`、`20261111-r5-packages-archive-fixed/`；几轮有交叉，不相加成独立覆盖率。
- R5 仍未完成：完整 Operit SDK/ToolPkg 注册与 Compose/UI/WASM 能力、DSH 实际协议与插槽、Linux 项目工作区及资源边界、其他外部入口的完整 Run 归属继续实施。Linux 项目目前仍只有 SAF 目录身份，尚未绑定为真实工作区，不能靠新增一个路径字段宣称完成。R2 单笔费用/硬预算追加、R6 声学全双工等余项及 R8 正式签名仍保持原完整目标。

### R5 Operit 常用宿主接口实装

- 不把 JS 引擎可启动当成默认插件已兼容。继续阅读实际插件和 SDK 后，复用 Operit 原 `JsTools.kt`、ToolPkg 版本方法分派及 `OkHttp3.js`，保留来源与许可。Nexus 的原生工具入口和 Operit 兼容入口分别适配到同一 Run 执行器，不开放 Java 反射。工具权限仍按最终实际 Nexus 工具名核对，不能用别名获得额外权限。
- 文件接口支持真实读取/分页完整读取、覆盖/追加、精确编辑、查询、列举、搜索、创建目录、复制、移动和删除。后四项直接复用 ETA 原 FileToolBackend 的既有实现，补到已有模型目录、执行分发、子角色权限分类、图标和产物登记，未新增另一套文件引擎。根目录与当前工作区根目录拒绝变更，复制/移动保留目标存在保护；删除始终要求本机确认，远端 Run 不可触发。完整文本读取按版本逐页累计、最多 512 KiB，分页没有进展或超额明确失败，不假称已读取全文。
- 终端别名转换到原 terminal 的 open/exec/close，保留 Linux/Android 选择、会话 ID、超时、真实退出码、部分输出和取消，不用输出字符串代替成功判断。设备查询、位置、通知、应用启动、按键/文字等已列明的接口转换到原设备工具；尚未适配的接口返回 PACKAGE_OPERIT_API_UNAVAILABLE，不静默猜测调用。
- 统一 Connector 增加真实 REST 方式，复用原有 WebHttpTransport 的大小限制、连接期限、Cookie 隔离和取消，支持明确的 GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS 及 JSON/form/text 请求体。拒绝无效证书绕过、跳转不自动跟随、不隐式重试提交；敏感响应头不返回插件。HTTP 请求走原 connector_call 本机确认和当前子 Agent 白名单，原匿名网页读取行为保留。原网页传输补上按单请求 Controller 取消。
- 插件配置采用每包独立加密 JSON，借用现有 McpSecretStore，绝不回退读取系统环境或其他插件配置。详情“配置”页展示包声明的配置名/已配置状态，支持重填和删除，不回显密码；环境值仅供所属包执行时读取。卸载清理该包配置，完整备份将其加入原加密凭据迁移，凭据存在时沿用原密码门禁。
- 当前包的 API 版本从真实 manifest 保存并传入隔离执行环境，原 SDK 版本化方法据此选择实现。仍不宣称 ToolPkg 主注册/插件 UI/WASM/蓝牙等所有接口已完成；主注册和特殊接口余项继续保留。31 个脚本的预置分发不等于 31 个插件所有分支都已可运行。
- 首次编译在新测试 EntryHandoff 构造缺少 payload 处失败，修正后 App 25 项中 24 通过；余一项实际已返回根目录拒绝，但测试误断言为抛异常。改为检查真实失败回执并补充配置字段后，App 11 项全部通过，含真实文件后端读写/复制移动/删除、REST PUT/form 请求、跳转拒绝、TLS 拒绝、本机审批/远端拒绝、加密配置隔离与原默认配置备份恢复。原生 QuickJS 当前源码也在该轮 externalNativeBuildDebug 完成，未重复完整回归。
- Node 执行原时间包和原 Crossref DOI 查询包，验证原版本化 Tools/OkHttp 封装、受控 HTTP 回执解析、文件封装、版本分页、模块和权限失败传播；这是 JS 端逻辑证据，不是假造 Android JNI 真机结果。证据 `20261111-r5-operit-sdk-initial/`、`20261111-r5-operit-adapter-fixed/` 与 `scripts/check-package-sdk.mjs`。R5 和 R1–R8 总目标继续活动，正式包仍未生成。

### R5 ToolPkg 主注册、回调和无模型手动运行

- ToolPkg 的 mainEntry/API 版本保存在原包索引；隔离环境先加载真实 CommonJS 主入口并执行 registerToolPkg，捕获声明类型、ID、参数和可回调函数映射。同步注册在独立进程执行且拒绝全部原生副作用；不能在“扫描清单”时执行文件写入/网络操作。支持识别 NavigationEntry、ChatMessageMenuItem 及其函数回调，主入口重新执行后按稳定回调键找到函数，不把函数源码塞进模型提示词。
- 注册出的导航/消息动作进入现有包工具目录，可被 Agent 发现，也可在包详情里点按。原脚本工具同样有真实参数表单、运行/停止和结果入口。手动执行以 OP_PACKAGE_ACTION 进入原 Runtime/权限 Gate/预算/SQLite 事实，源为 PLUGIN；直接执行已选工具，不生成额外模型请求。插件内部工具仍经过同一 Runtime 交集和预算，不能借手动入口扩大权限。
- 包详情的界面继续在单窗口内切换参数、回退、卸载；需要本机权限确认时暂时隐藏本窗口，让原全局审批窗口显示在前面；审批结束后回到原操作。枚举参数采用选项、布尔参数采用开关、数字使用数字输入。运行中退出会取消本次操作；错误直接在当前窗口显示，不藏在背后。
- 原 Operit 的 sidebar_bing_action 示例已作为带来源的测试素材保存；Node 实际执行其主注册、捕获 action、重建运行环境并调用原 openBingFromSidebar，确认最终请求原 browser_navigate 并正确传递 entryId。这里只证明 JS/回调适配逻辑，不冒称已运行 Android 隔离服务或真实浏览器。
- 新入口第一轮编译发现 RoundStarted 新调用缺少 messageCount，补齐后 App 88 项全部通过，0 失败/错误，含实际 SQLite 的独立 PLUGIN Run/工具事实/无模型请求、错误与预算拒绝、包注册保存、Wire 和原维护任务链路。最后审批窗口层级调整只补编译，不重复测试。证据 `20261111-r5-package-actions/`。测试覆盖与之前有重叠，不累计成进度百分比。
- 尚未完成的注册表面仍明确列 EXTENSION_SURFACES_PENDING：Compose DSL 面板、页面路由、桌面小组件、提示词/聊天/工具钩子、额外 AI Provider、WASM 和 DSH 插槽等继续开发。没有把“捕获了注册声明”当成所有宿主事件和 UI 都已接通。完整 R5 和总目标仍活动。
- DSH 新只读参考来源已定位 `ccch1mneyyy/dsh-Go`，固定 revision `8f31b2ed8c3fe0b49451379af7af4ddd00f29eef`；源码包 SHA256 `7c7f73edf5645ef1f8fe25965651b4348010817f13a358ac2428db0f14949fd1`，位于 `F:\eta\research\dsh-go`。第一次 Windows tar 因 CLAUDE.md 符号链接失败，排除该无关链接和指令目录后源码解压成功，没有复制其指令。实际协议为 contributions/views HTTP、WS stream 和三秒用户点按约束的 invoke/effects，不等同普通 REST；新版本还有 deferred/media/voice 等协商字段。仓库主许可 AGPL-3.0，下一段按公开协议独立适配；尚未复制其应用实现，也未假报 DSH 兼容。

### R5 Linux 项目工作区与资源限制实装

- 将应用 API 的 WorkspaceBinding 接到生产 Runtime、原 ProjectRepository 与原 AgentLocalTools/TerminalExecutionContext。会话、会议和已有项目上下文沿 canonical Run 的 projectId 找到原 SAF 授权目录。普通聊天不先复制整个目录，第一次项目文件/命令操作才读取私有工作副本；取消和授权撤销可中断目录查询、打开描述符及流读取，不回退到无关全局工作区冒充成功。
- 私有副本按项目和 Run 保留，PRoot 的 /workspace 和 chroot bind mount 均使用同一实际路径，文件工具也使用该路径，子 Agent 复用同一原执行器。修改不会自动写回 SAF 原文件；原目录读权限与写权限分别核对，路径/重复文档/目录循环/软链接/文件数量和体积有检查。当前基本限额为 2048 项、单文件 16 MiB、总计 64 MiB；超限明确拒绝并清理未完成副本，不悄悄截掉文件。该限额尚未做可调 UI，不宣称任意规模项目均已覆盖。
- 新增真实 project_workspace_status / project_workspace_publish，使用现有模型工具目录、子角色分类、权限 Gate、图标和预算。状态按页返回文件变更及稳定指纹；发布只接受明确的 1–32 个文件，确认窗口后还会重查工作副本和原文件内容哈希。发布不会顺带删除原文件，原目录新增同名项、原件被替换或内容改变都会在写前拒绝。
- 发布先写临时文档并回读校验，再保留原件名称备份、发布新文件、校验与更新基线。每个已完成文件和未确定操作写原子日志；局部失败保留已完成回执，并尽力恢复尚未发布原件的名称。相同发布标识/参数再次调用返回原回执，不重放未知效果；同标识换参数拒绝。文档服务不支持保留原件后重命名替换时明确拒绝，不降级为先清空原文件。SAF 不提供跨多个文件的原子事务，未将它说成完整事务；部分完成和保留备份位置会如实返回。
- 项目详情增加工作副本列表，打开即复用原 WorkspaceScreen 的真实预览、管理和分享。副本页隐藏内部哈希路径与无关说明，产物只显示该副本范围；返回上级不跳到全局文件根。原项目详情与工作副本窗口交替显示，没有再叠后层弹窗。
- 生产 HostAdapterRegistry 注册 Linux Owner，终端/文件目录仍沿用原 schema；实际 environment=linux 或既有 Linux 会话 ID 在授权时解析为同一 Linux HostId/HostKind，不把 Linux 操作伪记成原生 Android。就绪状态同时检查原 rootfs、Root/PRoot 后端；状态快照包含真实工作区绑定、资源配置和活动 Shell 数。没有另建 Linux 执行器或 AgentLoop。
- 在现有 Agent Shell/PRoot/chroot 构建路径前加入可配置单进程 CPU 时间与地址空间限制，以及每个执行器的活动 Shell 上限。并发名额在启动进程之前预留，启动失败释放；不是先执行超额命令再回收。Linux 内存压力准入不卸载文字/ASR 模型。没有对共享 App UID 设置 RLIMIT_NPROC，以免连带阻断语音和主程序；这些是继承给子进程的限制及 Owner 启动额度，不是整机总内存/cgroup 沙盒，也不宣称能约束已获 Root 的任意命令。
- Linux 环境页新增紧凑“资源限制”二级窗口，设置经过原控制面审计、回读和冲突保护撤销；新 Run 冻结配置并真实传入执行器。后台命令/守护任务继续使用原 DetachedTaskSupervisor，记录实际 hostWorkspace 和后端，使回复结束或进程重建后仍能定位项目里的日志，避免读到旧全局目录。跨 Run 的后台生命周期保留，不因普通 Run 结束强行关闭。
- 首轮 42 项中 37 通过、5 失败，定位为新 SAF 查询调用旧查询签名，已改用当前 Bundle/CancellationSignal 入口。随后 18 项中 16 通过，另两处为新 JUnit 方法非 void 和 Windows 下旧默认目录被重复拼接；分别修正测试返回类型和默认目录归一化。之后 Host/项目相关 13 项全部通过，发布备份回执与 UI 调整后 9 项通过；进程启动前配额预留的 4 项通过；后台目录记录最后 6 项通过。仅对真实失败或后续代码变化补跑相关链路，没有重新做全量检查。
- 基本检查实际经过 Android DocumentsProvider/ContentResolver 和真实本机文件后端：原目录→首次 Agent 文件读取→副本编辑→选择发布→回读校验；原件冲突、预览过期、部分失败恢复、同调用不重放、权限/取消、超限不留半副本均验证。Host 授权解析、原设置审计撤销及 Linux 启动脚本语法也通过。PRoot/chroot 真实挂载、手机 ulimit 行为、后台持续运行和 UI 渲染仍由用户真机验收，没有冒称通过。
- 证据保存在 `20261111-r5-project-workspace-initial/`、`20261111-r5-linux-host-initial/`、`20261111-r5-linux-host-fixed/`、`20261111-r5-workspace-final/`、`20261111-r5-linux-admission/`、`20261111-r5-project-background-path/`；多轮有重叠，不合计成独立覆盖率。最后仅将两处相同的准备/加锁代码整理为 prepared 辅助函数，不重跑相同检查。
- 使用入口：先在项目里指定授权文件夹并绑定会话/会议；Agent 的文件/终端操作按上下文进入工作副本，用户可在“项目详情→工作副本”查看分享。需要原目录得到结果时，Agent 先读取变更，再请求发布并等待本机确认。运行参数位于“Linux 环境→资源限制”。工作副本与发布日志留在已有 workspace 范围，按原完整应用文件备份选项覆盖；配置小备份保留项目/资源设置但不假装包含未选中的工作文件。
- R5 仍未全部完成：DSH Invoke/View/实时流与原生插槽、完整 Operit 扩展事件/界面/WASM、部分外部入口统一 Run 等继续；R2 费用预留/硬预算追加、R6 声学全双工和 R8 正式签名桌面交付也保持活动目标。没有因本轮完成 Linux 基本链路提前打包或标记全目标完成。

### R5 DSH 协议、会话插件与面板实装（2026-11-11）

- 继续基于现有 Connector 配置、加密凭据、HTTP Owner 和 Run Host 目录接入 DSH；独立实现公开协议，没有复制 DSH App 的实现。只读参考为 `F:\eta\research\dsh-go`，版本 `8f31b2ed8c3fe0b49451379af7af4ddd00f29eef`，来源归档 SHA256 `7c7f73edf5645ef1f8fe25965651b4348010817f13a358ac2428db0f14949fd1`。原项目 AGPL3，未将其源码改用本项目许可证。
- 真正使用启动链接令牌换取同主机 Cookie，禁用重定向、隐式重试与跨主机 Cookie。读取 401 可重新登录，Invoke POST 不自动重发。手动更换令牌、地址或协议会撤销旧连接身份；自动更新 Cookie 保留当前身份。连接凭据纳入加密迁移，缺失密钥时不生成新密钥假装解密成功。
- 实装 contributions、scoped view、资源图片、单次 Invoke 与 WebSocket hello/welcome/sub/data/patch/stale/changed/end。补丁版本不匹配重新订阅；前台生命周期内断线退避重连，只恢复读取，不重放操作。延后 done/void 只投递给发起连接，处理先到结果、消费一次、超时/取消/离开作废，操作期间可以停止。
- 能力中心提供主机增删改、启停、真实目录和原生基础视图。普通会话与会议共用的输入附件菜单增加“插件操作”，已完成回复的操作行可打开该回复的插件动作；共享草稿/剪贴板/回复须由用户选择，按声明和实际 surface 筛选。插入复用原草稿 Owner，写入当前光标选区且不发送；本机会话 ID 不冒充远端 DSH sessionId。未指定真实远端会话的 scope 保持空；依赖远端会话的插件可能不可用，不伪造上下文。
- Invoke 的三秒窗口从真实点击开始，读取草稿或等待授权不重置。连接/面板/插件声明变化、页面停止或关闭后到达的旧回执不执行；延后效果仅 insert/copy/toast/patch。链接另由本机确认，命令与页面使用明确映射，不开放任意导航/执行。Agent 工具只可读取声明面板与目录，不能伪造用户点击触发这些 UI 效果。
- 原生 renderer 适配 16 种基础组件。面板图片按主机/插件/面板隔离缓存，最多两个解码并发、16 个来源、总 200 万像素，采样到不大于 768 像素边长；数据刷新不重复下载。图表先归一化避免有限大数溢出，环图按圆形居中绘制。多选保留布尔/数字/对象等 JSON 值；受限文字复用既有 IntelliJ Markdown 解析器，仅加粗/行内代码，链接不产生点击能力。
- 初轮架构/Kotlin 编译通过。随后 47 项相关检查中 45 通过，2 项发现 OkHttp WebSocket 不接收 ws scheme；改为标准 HTTP(S) 升级地址后，实际客户端与受控流 Owner 的 6 项通过。继续会话入口与图片/选项调整后 7 项中 6 通过，发现图片替代文字的 AST 层级遗漏；修正后只补跑该项，通过，编译亦通过。没有重新做全量检查，各轮有重叠，不能相加当覆盖率。
- 真实本机 HTTP 验证登录/Cookie、带远端 scope 的视图、Run 目录执行、POST 不重放、正文取消和消息上下文筛选；真实 AES-GCM 验证身份更新/凭据迁移及密钥缺失；草稿检查覆盖已挂载光标、会话/会议隔离与不发送。WebSocket 使用注入的受控连接验证实际 stream owner，不是网络握手或手机证据。图片缓存/总像素及受限富文本同样有基本检查；UI 真机观感交用户验收。
- 日志/XML 保存在 `20261111-r5-dsh-hosts-initial/`、`20261111-r5-dsh-stream-fixed/`、`20261111-r5-dsh-conversation-initial/`、`20261111-r5-dsh-conversation-fixed/`。当前只协商真实支持的 composer.actions/message.actions/composer.tools/panels；composer.strip、turn.note、session.actions 的实际插槽、v2 表单/样式及媒体/语音/Overlay/文件能力仍未完成，不宣称完整 DSH 兼容。R5 及总目标保持活动，正式签名与桌面安装包尚未执行。

### R5 DSH 文件选择与有界传输（2026-11-11 继续）

- 在原 HostClient/UiSession/StreamClient 上增加真正的 files 能力协商。HTTP 目录与流分别声明，实际 welcome 与插件声明取交集；需要未连接能力的动作在 UI 和执行入口同时拒绝。静态/延后效果及文件回调不能开启文件选择器，Agent 只读 DSH 工具也不能使用该通道。
- 用户真实点击后的三秒内，插件只能请求系统文件/图片选择器。Android Photo Picker 可用时优先选图片，否则走只读 ACTION_OPEN_DOCUMENT；不申请持久授权，不将手机 URI/路径上传。全 App 同一时刻只接一个选择窗口，页面取消后的迟到系统结果被丢弃，窗口实际返回或 Activity 销毁才释放选择槽。
- 选择器期间仅为本请求有界保留原实时连接，最长五分钟，结束后恢复原前后台规则；没有在后台无限续租。用户返回后核对原主机、流、插件声明及面板读取代次，切换/断线/停用后的结果不再读取或上传。点击停止、取消、超时和离开均接原调用取消，不自动重新弹选择器。
- 通过 ContentResolver/CancellationSignal 和只读 AssetFileDescriptor 在独立读取 Owner 中取文件；逐段 64 KiB 落私有临时文件，按实际数据计数，单文件最多 8 MiB、整次最多 16 MiB、最多四个。已知长度超额提前拒绝，虚报/不完整长度拒绝；未知长度同样检查额外首字节。源文件不改写，临时副本使用后删除，不进入备份。
- 原生查询/读取连续十秒没有进展会先取消并关闭原资源，界面结束等待。单一原生读取名额直到 worker 和 close 都实际退出才释放；即使厂商 Provider 卡住，也不会通过重复启动绕过资源限制。正常关闭与超时关闭争抢同一所有权，避免旧关闭回调释放后来任务的名额。
- 真正调用 files/<request>/upload 与 complete；上传使用受原 Owner 取消控制的 HTTP，每份三十秒、回执上限 4 KiB，完成回执上限 96 KiB。验证只读句柄与实际字节回执；批次出错/取消只结算一次，已发送完成请求但结果未知时不重发。文件回调只能 patch/refresh/toast，不能顺带复制、发消息、再次选择或执行命令。UI 有紧凑的读取/上传进度和原停止入口。
- 首次生产架构/Kotlin 编译通过，新增检查夹具的混合类型数组需要显式 Any?，修正后首次执行相关 14 项，12 通过、2 项因夹具只覆盖三参数 openAssetFile，而 Android 读取路径实际调用两参数入口而失败。补齐夹具入口后，只补跑这两项，均通过。随后收紧正常关闭与超时关闭的所有权，只补该原生读取用例，编译及该项通过；没有再跑全量检查。
- 基本证据经过真实 Android ContentResolver/Provider→私有副本→实际本机 HTTP 上传/完成；验证源文件只读、文件名清理、字节/句柄回执、取消/迟到结果、断线、错误回执、未知完成不重发和原生阻塞限流。系统选择槽使用真实 ActivityResultRegistry 合同加受控系统结果；流连接仍为生产 StreamClient 的受控 socket，不冒称手机选择器 UI、真实网络 WS 或厂商 Provider 已验收。先前登录/Cookie 检查继续沿用，本批上传夹具不另外验证认证。
- 日志/XML：`20261111-r5-dsh-files-compile/`、`20261111-r5-dsh-files-initial/`、`20261111-r5-dsh-files-provider-fixed/`、`20261111-r5-dsh-files-close/`。各轮有重叠，不相加当覆盖率。当前 files 已加入实际协商，先前段落“文件能力未完成”为本批之前的历史状态；DSH 剩余原生插槽、v2 输入/样式、媒体/语音/Overlay 以及 Operit 完整扩展/WASM 等继续实施。未生成 APK，未创建签名密钥，未操作 Git。

### R5 Operit 数值 WASM 扩展（2026-11-11 继续）

- 阅读 Operit 实际 `native_toolpkg_wasm.cpp`、ToolPkg manifest 解析器、NativeInterface 参数转换及 JS API，复用其 LGPL3 数值 JNI 实现并适配到已有 plugin-runtime。WAMR 固定稳定版 WAMR-2.4.5，提交 `25bd7eb63e828e4bd242cc9b38d260b4b31c6605`，源码归档 SHA256 `4fbc793e6a1171c969cb6f8094feb94b9338ea00944676a218a7e4aa3afac77a`；core/build-scripts 随库分发，Apache-2.0 WITH LLVM-exception 许可和 attribution 同时保留在源码和 APK assets，没有构建时追踪 main。
- 插件导入真正校验 wasm_modules 的唯一名称、相对路径、文件魔数/版本、大小、声明导出；最多四个、合计 4 MiB，未声明文件不装入执行载荷。沿用不可变版本 digest、原安装/回退/删除、脚本载荷和隔离服务，只把已验证声明模块带入同一调用，不增加另一套插件数据库或进程。
- `ToolPkg.wasm.call` 已接 JS→实际 PackageJsSession→WAMR JNI，支持 i32/i64/f32/f64 和多返回值；i64 使用字符串保留精度，拒绝越界、不安全整数、无限浮点、未声明模块和受限导出之外的调用。模块只在原调用期间缓存一次，结束同一生命周期销毁；取消、超时与原隔离进程回收共用，不允许 UI 进程执行第三方模块。
- 在上游实现上新增调用级固定 WAMR 分配池、opaque handle 表、最多 32 参数/16 返回值/256 调用和每次一亿指令上限，避免循环无限占用。内存额度由包已有总额一半给 JS、一半给 WAMR；含 WASM 的包最小 16 MiB，配置入口拒绝低于实际最低额。池覆盖 WAMR 运行器/模块/线性内存等分配，不等同 Android 整进程 RSS 上限，Java/载荷/库映射仍有额外开销。保留原隔离进程时限作为初始化/底层不响应的最终回收。
- 只启用解释执行，不开放 WASI、系统/网络文件导入、JIT 或本机 AOT。保留 AssemblyScript env.abort；支持范围与 Operit 原数值 ABI 相同，不宣称完整 WASI 或任意 ABI。操作工具仍需原包白名单、Run Gate 与本机高风险确认；WASM 本身没有新的宿主工具通道。运行回执增加 wasm_pool_bytes/wasm_calls 供统计，旧 JS 内存字段仍保留，不把池容量伪称为实时使用量。
- 原生 ARM64 库/应用编译一次通过；模块与包管理的八项基本检查全通过，随后最低内存设置补入后只检查对应导入/配置用例，通过。原 JS SDK 检查加入数值转换、精确 i64、桥接封装及错误传播，通过。JNI 库 ELF 的 LOAD 段均 0x4000 对齐，只依赖平台 android/log/m/dl/c，没有新共享 C++ 运行库。
- 尝试用同一 JNI 源做 Windows JVM 原生运行检查，但本机 Visual Studio 缺 Windows SDK/kernel32.lib，配置阶段未能启动；保留可重用 host-check 入口和夹具，不安装额外环境或反复尝试。不将这次失败、受控 bridge 用例或 Node JS 检查当成实际 WAMR/JNI 执行证据。Android 原生库实际运行、手机隔离进程和性能仍由用户验收。
- 证据在 `20261111-r5-wasm-basic/`、`20261111-r5-wasm-memory-setting/`，包含原生构建、相关 XML、JS SDK 和 host 配置失败日志。R5 继续：Operit 全部扩展插槽/状态工具及 DSH 原生插槽、v2 表单/样式、媒体/语音/Overlay 尚未完成。未签名打包、未操作 Git、未修改旧 ETA/参考源码。

### R5 DSH 会话关联与详情操作（2026-11-11 继续）

- 依赖远端会话的 DSH 插件不再只能传空 scope。普通会话/会议进入插件主机后，可在顶栏关联图标中指定真实 DSH 会话编号，清空即解除。关联按主机、凭据修订和本地 ConversationSubject 隔离；同名会话/会议不会串用，不把 Nexus 本地 ID 直接发给 DSH。会话关联及 catalog 复用原 capability-center 文件，已有配置/完整备份覆盖，不另建数据库。
- 面板读取、实时订阅、用户点击 Invoke 都使用该显式远端 scope。修改关联清理原面板/订阅代次；旧结果不应用到新目标。目录刷新保留同一主机身份的绑定，修复 catalog 缓存没有保存 capabilities 的遗漏。手动更换凭据修订/主机地址后，旧关联失效；自动 Cookie 更新保留已有身份。
- 生产 Runtime 为 DSH Run 绑定固定的会话/会议身份，面板只读工具省略 scope 时沿用用户显式关联；关联变化后旧 Host 目录不继续使用旧范围。模型仍可在本机授权的 Host 内按明确参数读取其他真实远端 scope，不能执行 UI 效果或伪造用户点击。
- 会话与会议详情增加同一设计体系的“会话插件”入口，实际展示/执行 session.actions，并继续复用原 DshHostsScreen/UiSession 的权限、三秒点击、文件请求、取消和效果边界。只向对端协商已经接入的 session.actions；composer.strip/turn.note 的实际位置仍需后续实现，不把详情入口说成全部原生插槽。
- 初次编译发现可空 subjectKey 在延后可用性 lambda 中不能自动收窄，改为固定非空局部绑定后，Kotlin/架构和 5 项客户端检查通过。真实本机 HTTP 验证显式 scope 进入实际 Run→Host→View 请求，目录刷新保留绑定/能力声明、主机/会话类型隔离、解除/非法编号及凭据更新失效；没有运行设备或重复全量检查。
- 证据为 `20261111-r5-dsh-session-compile/` 和 `20261111-r5-dsh-session-fixed/`。R5 尚未整批完成；当前剩余 Operit 扩展事件/界面/状态工具以及 DSH 原生条带/尾注、v2 输入样式、媒体/语音/Overlay 等。R2 费用预留/预算追加、R4/R6 资源与语音、R7 最终迁移和 R8 正式签名保持原完整活动目标，未提前打包或修改 Git。

### R5 DSH v2 表单、队列与基础样式（2026-11-11 继续）

- 在现有 View/UiSession/Invoke 上接入 v2 的 11 种输入，以及 form、tabs/collapsible/accordion/carousel/grid/timeline、badge/tag/avatar/codeBlock/divider/spacer。仍是一套现有面板 Owner，不新增独立编辑器数据库。组件声明参与真实 caps 协商；v1 保留原组件边界，不能省略 v2 却请求新输入。
- 表单字段按有界可写路径关联到当前面板草稿，最多 32 个；重复/父子冲突、原型字段、嵌套表单和冒充审批角色被拒绝。隐藏页签里的字段同样校验；零和 false 有效，数字保持数值、多选保持数组。长度使用 Unicode 码点，日期/时间与选项校验实际格式/范围。默认值、动态 readOnly/限制与普通数据绑定、显示公式共用当前数据，不改写收到的远端 JSON。
- 草稿和请求带字段修订，晚到回写不覆盖请求之后的新输入；提交失败保留本地内容。单面板串行队列最多 120 项，onChange 合并/全局限频 250ms；提交冻结本次快照并去掉同表单尚未发送的变化。离开/断线/重新读取代次会取消旧队列，恢复连接不重放写入；字段已删除/定义改变的旧提交不再发送。
- 点击时间在排队前记录，超出三秒或输入 onChange 均以 userGesture=false 发往实际 HTTP，只接受 patch/refresh，不接受剪贴板、文件选择、导航、命令或延后结果。真实点击保留原即时/延后效果与文件选择逻辑，仍由同一来源/主机/面板代次检查。按插件版本/声明刷新当前面板，不再在热更新后一直使用旧贡献；资源图片在重新读取后更新缓存，普通数据推送不反复下载。
- 算术复用 Apache2 exp4j 0.4.8，许可/来源随 assets 分发；适配层只允许十进制数、已存在的数值数据路径、基本运算和 abs/min/max/clamp，256 字符/128 token/16 层括号，非法函数、任意代码、溢出或除零不执行。没有另写 JS/eval 或通用表达式执行器。
- 基础样式支持字体档、字重/装饰/对齐、受限 spans、主题/命名颜色、容器底色、窄处收缩留白、行权重/换列与分隔。文字/span/控件前景按实际背景校正到 4.5:1，插件按钮保留描边与不小于 48dp 的触控区；两按钮同行等高，窄屏换行。固定“插件内容”标识置于作者树外，日期/时间/选项使用宿主管理的浮层。代码块复用已有高亮、选择复制和滚动，不执行 HTML/Mermaid/脚本。完整协议的更细样式诊断、图表交互与所有极窄/大字号组合尚未逐项验收，不宣称全量视觉兼容。
- 首次编译发现新的队列构造器尾部默认参数使 lambda 绑定到了时钟，改为显式 invoke 参数后，架构/Kotlin 与本轮 13 项相关检查通过。随后新增删除字段/无手势真实 HTTP/窄屏 UI 三项通过。调整输入框标签色时误用库中不存在的 textColor 参数，改用已有 TextStyle 后只补该 UI 用例，通过；动态只读/约束/嵌套样式用例通过。最后动态控件与 span 缓存跟随数据的修改仅补 Kotlin 编译，不重复整组测试。
- UI 基本检查真实挂载 Compose，在 320dp、深色、1.3 倍字体中绘制当前 renderer，实际点击保存并取得正确文字/布尔 JSON 与用户点击标记；PNG 已检查，无重叠。它是本机 Compose 证据，未替代手机系统选择器、真实远端插件或完整机型验收。之前 13 项包含原客户端/呈现旧用例，与后续增量不能简单相加成覆盖率。
- 证据在 `20261111-r5-dsh-v2-basic/`、`20261111-r5-dsh-v2-ui/`、`20261111-r5-dsh-v2-color-compile/`、`20261111-r5-dsh-v2-color-fixed/` 及后续绑定/最终编译目录。R5 仍未整批完成：DSH 条带/回合尾注、媒体/语音/Overlay 与样式余项、Operit 全部扩展事件/界面/状态工具继续；R2/R4/R6/R7/R8 维持原目标。没有生成安装包、安装手机或操作 Git。

### R5 DSH 原生状态条、尾注与连接共享（2026-11-11 继续）

- 输入区已实际消费 composer.actions/composer.strip，普通会话与会议共用：最多八个快捷项、一条空闲状态条，多条每五秒轮换，生成时状态条让位给任务反馈。状态条可直接打开本插件面板或执行其声明处理器；动态文本为 null/空时清除，不显示字面 null。没有可用插件时不暴露会话插件控件，不用一个空管理入口冒充原生集成。
- turn.note 经同一实时流订阅，只按明确的远端会话/回合→本地回复关联显示，禁止按本地排序猜远端回合。点击回复的插件图标→主机→关联图标，可同时保存 DSH 会话与回合编号；清空回合解除该回复，改/清会话清除旧回合关联。关联与校验在一次原有原子写入中完成，普通会话/会议/主机/凭据修订分别隔离，继续由现有 capability-center 备份范围覆盖。
- 状态条、快捷项、回复尾注和面板共用 DshUiSession 与原 HostClient。增加有引用计数/可见性归属的连接租用，同一主机/凭据身份只维护一个 UI 实时流；关闭一个视图不关闭其他消费者。只有可见消费者或有界文件选择 hold 才保持连接，最后释放会关闭 HTTP/流/等待资源；旧身份释放不影响新身份。
- 复用原 Connector 配置所有者增加变更通知，启停/凭据变化和会话关联变化立即更新订阅，无周期轮询。停用后移除原生内容，重新启用能够恢复；撤销后的新流帧不继续接收。相同目录刷新不会反复拆订阅，更改远端关联只替换读取订阅、不重连同一主机。每主机原生条带/尾注各最多八个，仍遵守流的 32 订阅上限。
- 把面板已有的效果执行分发抽为共用 DshEffectRouter，原生快捷项继续走相同三秒点击/上下文共享/白名单/文件选择/取消逻辑，草稿只插入不发送。普通会话的 stop/compact 和会议 stop 转给原 UI/业务所有者；不开放任意命令。面板停止只取消本视图调用，避免关闭一个共享视图误停其他调用。未映射的远端页面/命令仍明确反馈不支持，不宣称能复刻 DSH 专属页面。
- 首次编译因持续收集函数推导为 Nothing 失败，显式声明 Unit 后，架构/Kotlin 与本轮十八项相关检查通过。随后设置变更恢复及窄屏 UI 两项通过；动态 null 清除与空目录隐藏分别只补对应新增/变化用例，通过。各轮包含重叠，不相加冒充独立覆盖率，没有全量测试/重复装包。
- 基本证据经过真实本机 HTTP 目录、生产 UiSession/StreamClient 的受控 socket、原子关联保存、真实 Compose 挂载与点击。验证本地回复关联、不串会话/会议、切换 scope 不重连、断线清理、停用/恢复、引用计数/身份撤销、过期效果过滤和无插件零控件；PNG 已检查 320dp 深色/1.3 倍字体的快捷项、状态条、尾注与点击目标。不是实际远端网络 WebSocket、真实 DSH 主机或手机验收。
- 证据：`20261111-r5-dsh-native-slots-compile/`、`20261111-r5-dsh-native-slots-basic/`、`20261111-r5-dsh-native-slots-ui/`、`20261111-r5-dsh-native-slots-null/`、`20261111-r5-dsh-native-slots-empty/`。七种 v1 原生位置已有生产消费；媒体/语音/Overlay、v2 样式诊断/交互余项、Operit 全部扩展事件/界面/状态工具仍待继续，R5 及 R1–R8 总目标不因此完成。未打 APK、未创建签名身份、未修改 Git。

### R5 DSH 媒体、开口检测与应用内字幕（2026-11-11 继续）

- 阅读 DSH 实际 capability-runtime/capabilities、Android VoiceCapture/ClipPlayer 和公开协议，独立适配协议，未复制其 AGPL App 实现。继续复用 Nexus 的 SpeechAudioLease、BargeInMonitor、原 Host HTTP、共享 UiSession 和效果路由，未另建 ASR/TTS 引擎或系统悬浮窗服务。
- audio/voice/micro/overlay 加入实际可选能力协商与资源清单；媒体效果只接收有真实点击、活跃流、已声明能力与资源的即时 Invoke。静态、延后、无用户点击和文件回调不能开启媒体或麦克风。不同插件/实例/主机/流/凭据的声音会话隔离，全 App 只保留一个当前 Owner，新会话先在本地停止旧资源。
- 音频只从已配置主机的同源 media 路由下载，Cookie 不跨源、拒绝重定向，无用户 URI/远端 URL 直传播放器。生成/下载总时限 30 秒、最大 16 MiB、格式白名单和完整长度核对；整段成功后才交 Android MediaPlayer，解码准备另有十秒时限，支持准备期间暂停/继续。下载、错误、取消和自然结束都清理私有临时文件，不纳入备份。
- 声音会话复用原音频焦点/录音租约，输入栏点击语音时先停止插件会话。退后台、锁屏、换会话/主机、断线、插件变更或撤销时立即本地停止，不自动恢复。终态和事件序号按租约保留，短音频先结束时，后到的同批聆听/字幕不会重新启动；超时或中断批次会结算未启动角色，避免主机留下悬空租约，仍不重发未知效果。
- “听你开口”采用原 VOICE_COMMUNICATION 采集和回声处理，只向插件发送语音起止边沿，不上传录音或文字。开口先关闭同实例的本地播放，再发 voice.speechStart，随后等约 700ms 静音结束聆听；同一租约边沿有频率限制，终态一次。首次按主机/插件作本机同意，再请求系统麦克风；管理页可关闭。授权等待超过原点击窗口只保存选择并提示重新点击，不凭等候延长点击权；已取消的系统授权结果不能交给下一请求。
- 原 BargeInMonitor 增加可选连续边沿及播放阶段变化跟踪，原助手默认 AEC 策略不变。DSH 没有 AEC 时支持更保守门槛并显示回声降级；参数是实现策略，不是手机测量。采集线程单一准入，先短时等待旧线程释放，卡住则明确失败，避免多轮创建原生录音器；实际回声质量/小声识别/设备驱动仍由用户真机验收。
- micro 具有独立完整小模型、1 KiB/240 码点限制、同 epoch 严格递增 rev、8 总订阅/2 每插件限制；相同通道/范围由面板与字幕引用共享，最后一个释放才退订。错误或超额通道只结束对应字幕，不让子协程异常扩散。面板下实际消费 micro，overlay 为应用自管的一行纯文字/进度与关闭按钮，不覆盖其他 App、不申请悬浮窗权限；顶部有应用自管的聆听/播放、暂停/继续与停止入口。
- 首次 Kotlin 编译通过。一次相关基本检查为新媒体 5 项、原流 Owner 3 项、原语音打断/状态 33 项，全部通过。补短音频时序与真实 Compose 字幕交互两项通过；补批次过期清理和后端播放状态/降级接线后仅跑受影响用例，均通过。最后系统授权窗口归属、旧采集线程短时等待与只声明资源时不显示空控件的修改仅补 Kotlin 编译，通过；未重复全量回归或真机调试。
- 实际本机 HTTP 测试覆盖令牌→Cookie、媒体同源参数、完整 WAV 字节下载、坏 MIME/取消清理；生产媒体 Owner 的播放与麦克风以受控后端检查暂停/继续、先停声后事件、自然结束、撤权、后台取消和不复活。micro 用生产 StreamClient+受控 socket 检查引用计数、旧 epoch/seq 与非法模型。UI 在 320dp 深色/1.3 倍字体真实挂载并点击关闭字幕，截图已检查。它们不等同真实 MediaPlayer 声音、真实麦克风/AEC、远端 WS 或手机性能通过，亦未把它称为 GPT 式完整声学全双工；R4/R6 的 ASR/TTS 会话联动仍继续。
- 证据在 `20261111-r5-dsh-media-basic/`、`20261111-r5-dsh-media-ui/`、`20261111-r5-dsh-media-expiry/`、`20261111-r5-dsh-media-fallback/` 与最终编译目录。不同轮有重叠，不能相加当覆盖率。R5 继续 v2 样式诊断/图表交互等余项与 Operit 完整扩展事件/界面/状态工具；R2 费用预留/预算追加及 R4/R6/R7/R8 仍为原完整目标。未创建签名、未生成 APK、未安装手机、未操作 Git。

### R5 Operit 提示词扩展、工具回执与会话菜单（2026-11-11 继续）

- 深读 Operit 的 Prompt/ChatInput/ToolLifecycle/ChatMessageMenu bridge、Hook 事件类型和 thinking_guidance/context_limiter/message_insert 等实际示例后，把已有注册表接到生产调用。JS SDK 同时支持函数与具名导出回调，嵌套回调以完整字段路径区分，不再发生同名 function 字段覆盖；保留 screen 资源路径，不把 UI 文件名误判为导出函数。
- 普通 Runtime 会话/会议成员和子 Agent 已接七类主链：ChatInputHook、PromptInputHook、PromptHistoryHook、SystemPromptComposeHook、ToolPromptComposeHook、PromptFinalizeHook、ToolLifecycleHook 的拦截/结果。输入、系统/工具提示和历史变更实际进入原 AgentModelClient，未新建 AgentLoop；工具结果继续经原 Gate/预算/归档。后台记忆整理/转写、备用文字回复、改写和手动压缩不偷偷启用普通会话扩展。
- 插件详情增加“扩展”设置，按已声明且已适配的种类单独开启，默认关闭；与工具授权、内存/时限一起走原设置控制面、前后值审计和撤销。更新或重新同步注册会清空自动扩展启用，避免变更后的注册静默接管旧授权。已有 capability-packages 配置备份覆盖声明、启用和版本，不另建扩展数据库。
- 回调继续通过原 PackageSandboxClient/Service 的独立 UID、载荷 FD、QuickJS/WASM、取消和硬时限。输入到提示词组合共用十秒期限，工具事件使用各自有界期限，失败明确阻止本次变更；请求/结果审计带包版本、种类和当前 RunId，仅存正文哈希，不把提示或敏感回执直接写审计。回调内目前只允许包已授权且原 Descriptor 认定 READ 的原生工具，仍走本 Run/子 Profile 的原执行器与共享预算；任意写工具自动 Hook 侧调用仍属待扩展范围，不宣称上游所有副作用 Hook 已兼容。
- 提示词可以修改内容和裁剪历史，但不能新增工具授权：返回的工具集合只接受原始已授权名称，实际 Schema 保持原件；全局/角色/Skill 权限仍在原 Gate。工具上下文和系统前缀有结构保护，裁历史不能留下无结果调用或无调用结果；多模态与签名信息保留原消息，未完成签名工具链不改前缀。Root 触发提示重建时会重新接扩展，扩展关闭 hosted web_search 也会反映到实际 Provider 配置。
- 工具拦截与结果处理使用主/子共用 executeTool 包装器。拦截可拒绝执行，事后扩展失败或任务取消仍先返回已经完成的真实回执，由下一取消边界停止；不改成“工具未执行”或重试。子扩展有自己的 Controller/生命周期，通过子 Profile 的原执行器，不能借主 Agent 获得写入权限。扩展调用原生读取时在加锁之前阻止自身递归，修复隔离 Binder 回调线程等待父扩展锁的潜在死锁。
- 注册的 NavigationEntry/ChatMessageMenuItem 现在同时支持 action/function，并在输入附件菜单和回复操作行的同一“插件操作”入口真实出现；本地包与 DSH 共用入口，不增加并排重复按钮。动作继续走原无模型请求的 package_action Run，带真实会话/会议、项目和文件归属，包来源保持 PLUGIN。已有权限弹窗优先时隐藏原菜单，不叠在后面。特定 Compose DSL 对话/自定义 UI 仍未适配，声明含 dialog 时明确保留待适配问题，当前只返回可读回执，不把 JSON 回执说成自定义对话已实现。
- 初次 Kotlin/架构通过。首轮相关十五项中十四通过，一项夹具缺模型 API Key，被原配置校验拒绝；补测试 Key 后只跑该项，通过。后续五项运行身份/UI 中四通过，发现原 TabRow 窄屏只显示前三项，改为同风格可换行标签后，仅补失败的扩展开关保存检查，通过。随后只对真正新增的子 Profile、事后取消回执和独立 Binder 回调线程检查补跑，均通过；没有再全量检查或打包手机调试。
- 生产模型入口使用受控 Provider 验证真实处理后的输入/系统提示/工具集合，实际安装包存储→源载荷→受控隔离 runner 验证撤销、只读授权与审计，独立线程模拟真实 Binder 回调；不是 Android QuickJS/JNI 与真实模型验收。Node SDK 检查具名/嵌套回调、UI 路径保留及原内置脚本，通过。Compose 在 320dp 深色/1.3 倍字体中真实保存扩展开关并显示回复菜单，截图已看，无重叠。
- 证据：`20261111-r5-package-hooks-initial/`、`20261111-r5-package-hooks-ui-initial/`、`20261111-r5-package-hooks-ui-fixed/`、`20261111-r5-package-hooks-final/`。不同轮有重叠，不合计成覆盖率。R5 仍未关闭：MessageProcessing/消息持久化/应用和聊天生命周期、输入菜单开关、完整工具生命周期/自动写调用、自定义 UI/Widget/AI Provider、状态工具及 DSH 样式诊断/图表交互继续；R2/R4/R6/R7/R8 保持完整活动目标。未签名打包、未装机、未修改 Git。

### R5 消息处理与插件写操作边界（2026-11-11 继续）

- 深读参考项目的 MessageProcessingPluginRegistry、ToolPkgCommonBridgePlugin、消息/应用事件 Bridge 和类型定义后，在 Nexus 原 AgentModelClient 增加已启用消息插件的匹配/执行消费者。先发 probeOnly，只匹配一个处理器；未匹配继续原模型，匹配后结果进入原 assistant 事件、transcript、检查点与执行库，不另开一轮模型。执行失败不会被误判成模型临时网络故障并转备用模型重复处理。
- 处理器默认单独关闭，沿原插件详情“扩展”启用。匹配阶段不能修改自身状态或调用设备写工具；执行阶段的写工具须同时满足包授权、当前主/子任务权限和真实本机批准，再走原执行器与预算。提示变换和状态通知继续不能借此自动写设备。回调仍使用独立 UID 的 QuickJS 运行进程，沿原硬期限和取消，不把脚本搬到 UI 线程。
- 原生 Hook 调用补实际 ToolStarted/ToolFinished 回执，根/子共用正式 Recorder、统计和进度。取消若在实际工具返回后到达，先保存已结算结果再停止，不伪装没执行。运行状态 Hook 连接处理、连接中、接收、执行工具、处理回执、完成/错误等语义变化，不按每个字符重复通知；观察者失败不抹掉完成的操作结果。
- 首轮 Kotlin/架构及 35 项相关检查通过，包含实际 ModelClient 匹配→不调用 Provider→原 transcript/账本、未匹配继续原 Provider、匹配失败禁止重启、本机批准前不写、既有提示/工具 Hook 和原 AgentLoop。证据 `20261111-r5-message-processing/`。其后原生回执接线和网络错误不能转备用模型在下述九项增量中继续验证。
- 当前原 IPC 回调在结束时返回 text/content/chunks；chunks 经原 assistant 事件展示，但不是隔离进程推理期间实时逐帧传出的音视频/文本流。第三方依赖任意 Java/原应用 EnhancedAIService 对象的处理器仍需适配，不能据注册类型一致声称所有上游包已经能直接运行。

### R5 应用、会话与持久消息事件（2026-11-11 继续）

- 应用创建/前后台、Activity 生命周期、内存通知、会话页面打开/关闭，以及普通会话/会议消息成功保存后，接到既有无模型 package_action Run；保留包来源与真实会话/项目归属。自动回调仅暴露给所属事件请求，不混入模型工具目录。默认独立关闭，检查具体事件、包版本/启用和扩展授权后投递。
- 通知使用 64 项有界串行队列；排队时与执行时都核对当前包，不自动重试未知结果。状态观察和自动通知不能调用写设备的原生工具，包可维护自己的私有状态。队满/取消/进程结束存在通知未执行的可能，有明确日志；这不是承诺进程死亡后所有通知必达的持久消息总线，后续完整生命周期/恢复语义仍需收口。
- 会话保存只在数据库事务完成后发事件，流式内容以原结构化正文列的 pending 元数据区分完成，旧包/正文可读性保留。初轮 31 项中 30 通过、1 项实际发现全量保存会重复通知旧消息；改为对比原数据库记录后，只补保存链 26 项，全部通过。进一步覆盖流式最后文本不变时恰好一次完成通知，通过。
- 首次编译新增投递 lambda 返回了 RunResult，需显式 Unit，已修正；相关日志保留。证据 `20261111-r5-event-lifecycle-initial/`、`20261111-r5-event-persistence-fixed/` 及下述状态批次。包括真实 Application 多 Activity 前后台、实际会话保存、选择/关闭扩展、原无模型运行身份/回执；隔离 Android JNI 与全部手机生命周期未真人验收。

### R5 插件私有状态与输入开关（2026-11-11 继续）

- 新增包私有的有界 JSON 状态，位于原 capability-packages 目录，由同一宿主核对真实包身份后读写，SDK 暴露 ToolPkg.state.get/put/remove。原子保存、乐观 revision、大小/层级/条目与原型字段检查；不同包隔离，损坏不覆盖，卸载清理。API Key 等仍放原加密环境配置，不用普通状态代替密钥库；原配置备份范围已包含该目录，最终 R7 仍需实际覆盖验证。
- InputMenuTogglePlugin 接到原会话“插件操作”弹窗，使用已有 SwitchPreference；创建读取不能改状态，用户切换后运行原无模型请求并回读真实结果，失败不先改成成功。未声明/未启用不显示，停用和版本变化后拒绝旧按钮。没有新增一级导航或移动原聊天布局。
- 状态隔离/持久化/过期写拒绝/损坏保护、实际已安装开关查询→切换→重开→停用→卸载、原生工具取消后回执，以及前述消息/应用增量共 9 项通过，编译与架构通过。SDK 脚本实际检查调用状态宿主参数/修订及原既有示例，通过。最后 UI 和正文编码相关 6 项通过；Compose 在 320dp 深色/1.3 倍字体中打开原插件弹窗并实际切换，PNG 已查看，无重叠。
- 证据 `20261111-r5-state-toggles/`、`20261111-r5-toggle-ui/`。不同批次有交叠，不累加当覆盖率。R5 仍未关闭：更完整工具生命周期/消息持久通知恢复、实时插件输出、原 Java 专属示例、自定义 UI/路由/Widget/AI Provider，以及 DSH 样式诊断/图表交互继续。最终 R7/R8 和原其他范围不变；未签名打包、装机或修改 Git。

### R5 工具生命周期事件补齐（2026-11-11 继续）

- ToolLifecycleHook 新增 tool_call_requested、tool_permission_checked、tool_execution_started、tool_execution_error、tool_execution_finished，与原拦截/结果共七类。权限检查和真正开始由主/子原执行器发出，子 Profile/任务范围/预算拒绝不会发送开始事件，也不能由观察回调返回 allow 变成授权。
- 错误分“明确失败回执”和“抛异常、结果未确认”；事后错误/结束观察失败只记诊断，不抹掉已完成回执，不重新执行原工具。Hook 内原生调用继续有防递归与自己的正式回执；此前本机写批准及主/子权限交集保留。
- 编译、架构与工具 Hook/消息处理相关 8 项基本检查一次通过，验证事件顺序、权限拒绝不执行、成功/异常和旧回执保护；证据 `build/reports/nexus-delivery/20261111-r5-tool-lifecycle/`。该命令中一个子 Agent 测试类名实际不存在，未执行其测试，核对 XML 后已纠正：单独运行实际子扩展归属/Profile 拒绝、独立检查点/Item、真实审批归属 3 项，通过，证据 `20261111-r5-child-lifecycle/`。未把此项当插件全部协议/所有运行来源兼容完成，未生成 APK 或手机调试。
- 自动生命周期通知排除 request_user_input，并在请求策略和隔离宿主两处执行，避免插件因应用前后台变化主动争抢补充输入窗口。只对新边界补一项实际无模型请求/目录/权限检查和编译/架构，通过，证据 `20261111-r5-events-no-user-prompt/`。

### R5 插件自定义页面、消息窗口与路由（2026-11-11 继续）

- 读取参考项目 Compose DSL 类型、JsComposeDslBridge、运行包装器、原消息翻译/消息插入界面和宿主渲染依赖后，直接复用其未修改的 JavaScript context/state/memo/modifier/node 构造桥，而不是另写一份 useState 协议。固定 Operit `16cffb98f52a11dc77bd0a789ffa0b01ed398ee4`，导入文件 SHA256 `9e87eebc203e0b1d5a881f9daf1a3fbf7e19515b3b731d392a0c0aacbaa3ec06`，LGPL 许可证和来源同时保留在源码 Notice 与 APK 许可资产。
- 新调用适配复用原隔离 QuickJS、PackageSandboxService、无模型 package_action Run、权限、时限、取消和包版本。UiRoute/ToolboxUiModule 的 JavaScript screen 文件生成隐藏 UI 工具，仅在真实界面请求加入该次目录，不作为普通模型工具。原插件详情和会话“插件操作”能打开页面，使用 ETA 原 MiuixScaffoldPage 和控件；未改主导航或既有主要位置。
- 页面状态为有界 JSON，按钮先核对当前动作/树，再在隔离进程重建 callback 并更新状态；旧树、停用、重注册、非法节点/副作用/超大状态拒绝。页面初始渲染只可读原生工具，不能改设备或弹提问；用户动作的写入仍走原确认/权限关口，不允许自动重放 UI 操作。配置批量写入仅限包声明的本机加密环境项，原子提交；包路由仅限本包已注册页面，支持有界页面栈和返回恢复。
- ChatMessageMenuItem 的 screen 对话声明生成独立模块。用户选择消息菜单后先执行原回调，读取其 dialog.state/moduleSpec，再打开原样式宿主窗口，而非忽略回调直接猜状态。只把声明的窗口挂到对应回复，不将消息窗口混入普通插件页面列表。
- Tools.Chat.call 的文字请求适配到已授权、明确空工具列表的原子 Agent。使用真实当前模型及原请求预算/统计/子 Run；即使上游传 recordTokenUsage=false，也不关闭本机计费。当前 functionType 作为用途标签，尚未建立每种用途独立模型配置；不宣称对接了原项目的全部 EnhancedAIService/任意 Java 对象。
- 原字段输入使用原 Miuix TextField，350ms 合并输入；待保存文字阻止其他命令使用旧值。成功回读才接受归一化状态，失败保留草稿且不自动再发，用户可以修改或显式重新读取。异步按钮执行期间保持可见状态、错误和取消，离开关闭原 Controller。
- 当前已适配 33 种常用节点：布局、文字/Markdown、输入、开关、选择、按钮/图标、分隔/进度、页面和对话外观。复杂 Canvas/动画/图片资源、WebView、文件选择器、内联函数 screen、更多 Modifier/样式和长连接中间帧仍有差距；不支持的控件明确报错，不以空白或简单容器冒充全部原界面。原主界面架构保持，后续继续在这条链适配，不另开第二套 Agent 或设置库。
- 证据：初次生产 Kotlin/架构编译通过。首次测试编译新增夹具暴露 internal 类型/漏 import，修正后 7 项通过；路由新增夹具漏括号修正后 13 项通过；最后输入失败草稿、模块边界/消息窗口和文字模型适配 6 项通过。各轮有交叠，不累计成覆盖率。仅对编译失败或新行为补查，未跑全量/真机。
- SDK 实际执行原 context 生成树→回调→重建状态→旧树拒绝，并用固定原版 message_translation 界面原文件完成初始渲染和点击翻译→真实版本化 Tools.Chat 调用→受控返回→译文回显。Kotlin 检查从实际安装存储/注册→原隔离载荷/工具权限→结果解析；隔离 runner 受控，不冒称 Android 原生 QuickJS/真实模型已通过。Compose 在 320dp 深色/1.3 倍字体实际点击、路由返回和输入失败保留草稿，PNG 已查看无重叠。
- 日志/XML/PNG：`20261111-r5-compose-ui/`、`20261111-r5-compose-dialog-routing/`、`20261111-r5-compose-inputs-final/`。正式包尚未生成。R5 后续继续上述控件/资源差距、Widget/插件 AI Provider、真正实时输出与通知持久恢复、DSH 图表/样式余项；R7 最终备份及 R8 正式签名桌面交付保持原范围。

### 发布前消息流式输出收口（2026-11-11）

正式交付结果：`C:\Users\29136\Desktop\Nexus-v0.2.0-release.apk`，61,310,285 字节左右（最终精确大小以校验 JSON 为准）；对应 `.sha256` 与 `Nexus-v0.2.0-更新说明.md` 已提供。最终 SHA-256 为 `9b136dea95c98ba766d1422196063b0f332100362bd3151a43551d4ca74071a8`，证书 SHA-256 为 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`。实际签名/最终 manifest/逐库 LOAD 段校验见 `build/reports/nexus-delivery/20261111-release/verification.json`、`signature.txt`、`signature-v2.txt` 和 `AndroidManifest.xml`。v3 已在 Gradle 显式启用；因新系统验证会优先 v3，v2 使用单独的方案验证区间核对，这不代表降低 APK 的实际最低系统版本。没有重复功能测试或装机；用户负责真机验收，后续余项见桌面说明。

- 消息处理插件接入真实 `sendIntermediateResult({chunk})`，在回调尚未结束时沿原 AgentEvent 文本流显示，不再等最终返回后模拟分块。探测阶段禁止输出，正文 256,000 字符、单帧 32 KiB、1024 帧有界；流式输出不授予额外工具权限，也不伪造模型用量。
- 隔离回调可能在 Binder 线程到达。输出直接走原事件接收端，避免回头争抢仍由原线程持有的 Hook 锁；匹配后的运行状态、取消、超时与权限仍由原 Owner 负责。最终结果与已流内容相同时不重复显示；不同则用原 BlockEnd 的 replacementContent 替换为最终文本。
- 插件已接管消息后失败不改用另一个模型重做任务，已有流式文字作为部分 transcript 保留。基本链涵盖跨线程首段在回调结束前可见、最终去重/纠正、失败保留部分结果、探测输出拒绝及原插件调用 6 项，全部通过；SDK 脚本检查同轮通过。未做手机或真实外部深度搜索服务验收，仍不宣称 Java 专属原例可直接执行。
- 用户随后要求尽快交包，因此复杂页面中间帧不继续展开；新增事件/宿主的未验证草稿已经撤回，保留原稳定 UI。最新配置备份实链再次覆盖插件私有状态恢复，以及排除本机通知/组件编号、保留模型权重与工作文件，单项通过，证据在 `build/reports/nexus-delivery/20261111-release/core-checks/`。
- 正式发布已创建仓库外受当前账户权限保护的 Nexus 长期签名文件，没有借用 ETA 身份或把密钥写入源码。首个 Release 构建在 R8 检测到 Commons Compress 两个未安装的可选 Zstandard/Brotli 类；核对代码确认公开导入格式为 ZIP/gzip/bzip2/XZ，按可选依赖设计增加两个精确 dontwarn 后继续同一候选构建，未停用混淆或忽略其他构建错误。

### R5 插件通知持久恢复（2026-11-11 继续）

- 原应用/页面/消息/输入开关通知增加有界 SQLite 收件箱，触发时固定订阅包摘要、原工具声明和目标会话，落盘后才确认接收；不以重新扫描当前订阅者冒充恢复。每次投递生成稳定 `package-event-*` Run ID，真实执行仍由原 Runtime/Gate/预算/回执承担，没有替换执行账本。
- 待执行通知在应用启动或系统 WorkManager 拉起时继续。系统任务只负责唤醒并等待原队列，不另开一套执行器；新通知替换唤醒任务，不取消正在执行的回调。没有订阅者时不创建空后台任务；数据库读取/写入失败保留原文件并有限退避，不吞成成功。存活与后台到达时间继续由 Android/ROM 调度，不宣称永久常驻。
- 启动时对已经开始的通知优先核对原 Run 和精确的 `package-direct` 工具回执，必要时连接仍活着的原 Run，不再次执行。实际回调成功但外围任务随后取消仍恢复为已完成；既无确定回执、又无法确认当前运行结果时保留“结果待核对”，新通知可以继续处理。并不宣称任意外部副作用的 exactly-once；未知结果禁止自动重发。
- 收件箱最多保留 128 条含正文记录、合计 4 MiB 正文；满额整批拒绝、不挤掉旧待办。终态清理正文和会话标识，只保留最多 256 条元数据；未知记录保留供核对。插件停用/更新/撤销后旧版本通知不能调用新代码；卸载立即撤销并清除尚存正文。
- 原插件详情“运行”页增加现有 Miuix 风格的事件记录与刷新；查看记录在同一弹窗内切换，没有叠加遮挡窗口。未知记录由用户明确归档，只释放待核对正文，不停止、不重发、不伪造原任务成功或失败。已配置权限之外的写操作仍不向自动通知开放；只读通知经真实声明核对，可在原客户端绑定期间后台完成。
- 新收件箱与系统桌面 ID 都属于本机短期状态，已从导出中排除，导入也拒绝这些条目，防止跨设备恢复后重放通知或打开错误组件。实际配置导出→导入→激活链已证明这些文件不恢复，同时保留原模型权重和用户工作文件；最终 R7 其余完整覆盖仍需继续。
- 首轮架构检查与 14 项相关检查通过（通知恢复 5、原生命周期 3、原包运行 6），随后只对新增的卸载/空订阅保护及配置备份实链补 2 项，通过。证据位于 `build/reports/nexus-delivery/20261111-r5-event-recovery/`。没有重复全量回归、装机或打包；手机后台和长期使用交用户验收。
- 当前继续普通消息/插件界面的真正中间帧和复杂 UI/DSH 等余项，再进入最终 R7/R8；未把本次通知链通过当作 R5 全部完成。

### R5 系统桌面组件与函数式插件页面（2026-11-11 继续）

- 用户再次强调 UI 沿用 ETA 风格和大体布局。应用主导航、设置分组、常用入口、页面骨架未调整；新增组件操作放在原插件详情概览，选择页和打开后的插件页面继续复用原 Miuix 主题、控件与确认窗口。
- 核对参考实现后确认 DesktopWidget 指真正的 Android 启动器组件。Nexus 新增原生 AppWidget/RemoteViews 接收器、配置页、固定/绑定/刷新/删除及系统 ID 重映射，未把应用内展示框冒充桌面组件。支持每个组件独立绑定；定时更新由系统 30 分钟周期及 WorkManager 驱动，插件启停、更新、删除和尺寸变化也会触发刷新，不承诺 ROM 的准确后台到达时间。
- 原例 `registerUiRoute` 使用函数 screen，不能只支持文件路径。现支持固定来源的函数引用与原路径两种声明，执行前再次匹配模块/回调；主注册仍在隔离进程重建，无 Java 对象暴露。原例三个未修改脚本有版本、许可证和摘要记录，Node 已实际生成摘要、进度与完整页面树。
- 组件渲染沿用原无模型 Run、权限 Gate、回执及隔离运行器。初始页面在请求白名单与宿主双层限制为只读，不读取即写设备、不弹补充提问。经过实际已安装声明核对的只读页面可以在原客户端绑定期间完成，避免后台组件被前台执行通知的启动限制挡住；真正点击后的 JS 动作仍走原执行准入和本机确认。取消/20 秒超时继续释放原执行资源。
- 组件点击打开绑定插件的完整页面；不会悄悄在桌面后台重放按钮回调。包摘要/路由失配、停用或卸载后拒绝旧绑定；后台发布前重新核对版本，故障显示可打开的状态。配置保存在 `capability-packages/widget-bindings.json`，组件系统 ID 属本机数据，R7 需排除跨安装直接恢复，并提示重新添加。
- 原生支持常用行列、文字、按钮、分隔、间距和真正进度条，有明暗色资源、文字行数/字号及树深/数量限制。不是参考 Glance/Compose 全节点等价：复杂图片、Canvas/WebView、选择器、全量 Modifier、onLoad 动作和交互式桌面控件仍需继续适配，未知控件明确失败。没有声明实际手机桌面已经验收。
- 证据：首轮 7 项有 6 项通过，绑定重映射未通过；定位为 Windows 的 `File.renameTo` 不能覆盖文件，与 Android 原子替换语义不同，使用仅测试侧的 AtomicFile rename 适配后该项通过，没有为此修改生产原子存储。补查该项与真实 Runtime 包入口 6 项共 7 项通过。原生绘制另以 Native Graphics 生成并实际查看截图，文字、可点击按钮、真实 60% 进度与对应 Intent 均通过；最初默认绘图模式的空白图未当成通过证据。文件在 `build/reports/nexus-delivery/20261111-r5-desktop-widget/`，架构检查同轮通过。只补实际失败/新代码相关检查，没有装机、全量回归或打包。
- R5 仍未整批完成。接下来继续普通通知的持久恢复、真实中间帧及复杂插件 UI/DSH 等余项，后续 R7/R8 不删项。

### R5 插件模型提供商完整主链（2026-11-11 继续）

- 深读参考项目 AiProvider 的四个回调合同和原 custom_ai_provider 示例后，把嵌套 listModels/sendMessage/testConnection/calculateInputTokens 声明接入真实消费者。原插件详情启用“模型提供商”扩展后点“接入模型”，绑定固定包 ID/注册 ID/代码 digest；连接与模型仍使用原 ProviderRepository/原编辑页，不新建第二套模型配置。模型目录进入原拉取/选择/保存流程，会话、会议成员、子 Agent 和后台调用沿同一 ProviderClientFactory 使用插件提供商。
- 通过原独立 UID QuickJS 和 PackageSandboxClient 执行回调，API Key/提示正文仅进入有界本次私有载荷，审计只保存包版本、方法、Run 与输入哈希。包停用/卸载/代码更新/重注册关闭扩展后拒绝旧绑定；提供商连接/凭据变化或取消也会关闭真实 HTTP/沙盒并拒绝迟到结果。更换代码 digest 后必须回原页面明确重新接入，不能悄悄沿用旧凭据授权。
- 原 HTTP SDK 的 onIntermediateResult 接到有界 start/next 传输，原 sendIntermediateResult 接到实际 Provider 文本分片，继而进入原流式 assistant 事件。每次生成只允许一个 POST 到当前配置的确切地址，目录/测试只允许同源 models GET；拒绝重定向、其他域/地址和无账本重试，仍做 TLS/头/大小/取消检查。分片有独立有界额度，不因通用 64 次宿主工具限制在正常长回复时过早截断；真实终态标记结束读取，不等服务端保持连接直到超时。
- 回复请求继续由 BudgetedAgentProvider 在发送前计入原总预算和冻结价格预留；费用限制的输出参数在真正 HTTP 正文中重新施加，不能被脚本改模型/多候选/服务端工具绕过。用量以本机实际收到的 HTTP JSON/SSE 提取，插件自报零不覆盖。没有可信用量的返回仍可以显示正文，但保留未知费用并在有限费用任务中阻止下一次收费请求，不伪装免费。
- 合同原本只保证 text/usage；Nexus 同时接受明确结构化的 toolCalls，校验已发布名称/编号/参数形状后进入原 AgentLoop 执行，未声明时不从散乱文字猜工具。目录默认 tools=false，多模态未有可核验合同则明确拒绝，用户或目录明确开启工具后才发送真实工具目录。Token 估算接上下文判断且不弱化本机保守值，估算回调禁止 HTTP，不允许借“计数”隐藏另一条模型请求；它不是结算用量。
- 原提供商“测试连接”消费实际 testConnection 回调，目录拉取消费 listModels；估算消费 calculateInputTokens，而非仅保存声明。插件接入初始地址是待配置占位，不显示成已配置可用；删除原提供商后“已接入”也要重新读取真实存储。接入 UI 沿用原插件详情/图标/分组，不增加一级入口，成功状态避免重复备注。
- 证据：首轮生产编译/架构及相关 23 项通过，包含实际 HTTP 回复→服务端用量→冻结账本结算、输出限额、实际目录→原选择与工厂、外域拒绝、包撤销关闭正文。原 SDK 检查首次夹具把 JSON 对象当字符串 parse 失败，按实际结构纠正后，固定原 custom_ai_provider 脚本的注册/HTTP 分片解析/原生输出/最终正文与用量通过；未改原示例脚本。
- 增量三项中未知计费通过，工具链夹具误用 128 Token 窗口先被原压缩保护拒绝；改为正常窗口后通过。UI 另发现绑定文件仍在而提供商已删除时错误显示“已接入”，修复实际存储核对后，两项单独通过。真实 SSE 终态不等断线/Controller 取消的单项通过；测试/Token 估算接线及相关上下文检查另有通过证据。未重复运行已通过的整组/全量，未装机或签名打包。
- 日志/XML/PNG：`20261111-r5-ai-provider/`、`20261111-r5-ai-provider-final-initial/`、`20261111-r5-ai-provider-final-fixed/`、`20261111-r5-ai-provider-sse-cancel/`、`20261111-r5-ai-provider-token-probe/`。目录均保存实际测试范围，不把命令中过滤了不存在的 ProviderClientFactoryTest 当已执行；工厂消费者在新的实际目录用例中明确断言。UI 是 320dp 深色/1.3 倍字体真实点击，截图已查看；Android 隔离 JNI/真实外部供应商未做真机验收。
- 使用路径：插件详情同步注册→启用插件及“模型提供商”扩展并保存→回概览接入模型→原模型提供商页填写真实生成接口地址/可选密钥→测试/拉取并选择模型。包更新后同处重新确认接入；权限关闭或请求失败明确停止，不改走另一个未授权模型。原示例固定来源 `16cffb98f52a11dc77bd0a789ffa0b01ed398ee4`，fixture SHA256 `3151eecc96f324105e8a6edb1252941618633bddf813216ae8d30b73974a1b08`，LGPL 来源在 fixtures Notice 保留。
- 限制：单次回调/HTTP 仍有实际内存、输出和时限上限；自定义非可约束接口不能用于有限费用任务。任意多域多请求/Java 原宿主对象/更多音视频模型协议没有因此自动兼容。插件 Provider 分片已实时接通，不代表普通 MessageProcessing/Compose 中间帧也已完成。R5 的复杂 UI/Widget、通知恢复和 DSH 图表/样式继续，R7 最终备份与 R8 正式桌面交付保持完整目标。

## 范围与证据

工作副本：`F:\eta\new-nexus-snapshot\NEW-NEXUS-main`；历史导入基线：`main@8e7092e`，原始源码 ZIP 保留未覆盖。当前修改尚未提交或推送；旧 Eta 工作区保持只读。

原始审计见 `20_RUNTIME_AUDIT_2026-11-10.md`，完整执行计划见 `21_REAL_INTEGRATION_PLAN_2026-11-10.md`。计划与用户指定的 `F:\eta\NEXUS_REAL_INTEGRATION_PLAN_2026-11-10.md` 同步。

## 最新子批次：总请求预算与费用回执（2026-11-11）

统一 Runtime API 已给根/子 Controller 绑定同一组模型请求预算，真实 Provider 在发送前预占，压缩、备用模型、兼容性重试与订阅认证刷新共用总额度。正式库 v5 事务保存请求归属、目的、冻结价格版本与用量费用，支持旧库迁移、备份还原和取消后的迟到回执。未知费用不被当成零，费用限额按币种检查并在上一笔结算后允许后续请求。

严格单笔费用封顶仍未完成，当前阈值依据已返回费用回执；单次在途请求可能越过阈值，因此费用上限继续 PARTIAL。详见 [29_MODEL_REQUEST_BUDGET_2026-11-11.md](29_MODEL_REQUEST_BUDGET_2026-11-11.md)。本批实际父/子 Loop、压缩与备用模型验证只属于 L2，未做真实模型或真机验收。

初轮相关 App 112 项：111 通过、1 平台跳过；API 10+9 项通过。补齐真实子 Loop、压缩/备用模型和认证刷新后最终相关 App 73 项：72 通过、1 平台跳过、0 失败/错误，架构/Kotlin/身份检查通过。对应证据在 `build/reports/nexus-baseline/20261111-model-budget-final/`；两次有重叠，不能相加冒充独立用例数量。

进度口径更正：原“剩余约 85%”是缺少完整工作量分母的粗估，不能作为可验证进度。后续每半小时报告已做/未做/剩余；无可靠开发量权重时明确说明。N0–N9 目前没有整阶段完成验收；不把局部代码/测试数量换算成完成百分比。实施批次按完整功能链合并，不再重复完整审计。

## 前一子批次：软预算与即时 Goal 限额（2026-11-11）

真实 ModelClient/AgentLoop 现在会在软预算用尽后等待本机确认，批准后仅使用原硬上限内的剩余额度。工具并发预留、重试/备用模型的同一计数、取消审批和硬超时继续有效。Hosted Tool 不在流式回调里弹软预算窗口，而在下一安全边界检查。

本轮新建 Goal 的预算已经接入当前 Guard：先提交 Goal 事实与有效请求，再取预算交集、保留已消耗计数并重设超时。父子和兄弟 Run 会同步收紧；传播失败会停止该组任务。预算传播期间新子 Run 必须等候，不能使用旧的大额度准入。

正式执行库 v4 区分最初准入请求与当前有效请求，预算约束作为 typed fact 同事务保存；v1/v2/v3 升级和导出→staging→激活还原均有实际回归。Checkpoint 和新子 Run 使用有效限额。

初轮及父子链路相关回归：App 115 项，114 通过、1 个 Windows 符号链接权限相关用例跳过，0 失败/错误；API 10+8 项通过。最后补充预算传播/新建子任务竞态后，4 组 29 项全部通过。两次回归有重叠，不相加冒充独立用例数量。架构/Kotlin/身份检查通过。

详见 [28_SOFT_BUDGET_AND_LIVE_GOAL_LIMITS_2026-11-11.md](28_SOFT_BUDGET_AND_LIVE_GOAL_LIMITS_2026-11-11.md)，原始日志与 XML 已分别保存。N1 仍为 PARTIAL：费用总额、父子模型请求总额度、硬预算追加、用户输入与其他入口仍需实装；20 真机 Run 和恢复切换尚未完成。继续直接开发，不重复全项目审计。

## 前一子批次：工作流事实与有效工具授权

Goal 开始/恢复已接入真实本机确认，模型不能用 `confirmed=true` 自行批准；审批绑定当前状态指纹，旧计划的批准不能启动后来修改的计划。完成只在 `VERIFYING` 且核心条件已核验时自动提交，不再弹完成确认，也不能从 `WAITING_USER` 绕过验证。目标、计划、记事本从真实工具写入开始事件、不可变版本和结果事件，失败或中断保留未结算事实；历史版本可通过原工具读取。

正式库 v3 的显式迁移保留原序号/ID/Checkpoint，Event/Item 带相同版本引用；完整备份包含版本正文并实际验证还原。目录激活改为固定任务期限，根/子 Agent 下一轮 schema 会去掉过期或撤销工具。补齐中文 RunId 授权、非法工具标识拒绝、浏览器非只读分类，以及等待状态统计和未结算工作流显示。

最终定向回归：App 10 组、86 项，85 通过、1 个 Windows 符号链接权限相关用例跳过、0 失败/错误。Application API 10 项、Kernel API 8 项通过，架构/Kotlin/身份检查通过。原始证据在 `build/reports/nexus-baseline/20261110-workflow-grants-final/`。详见 [27_WORKFLOW_FACTS_AND_EXPIRING_TOOLS_2026-11-10.md](27_WORKFLOW_FACTS_AND_EXPIRING_TOOLS_2026-11-10.md)，其中保留本批编译与测试夹具失败及修正情况。

N1/N2 继续 PARTIAL。下一步是软预算/追加与费用总预算、Run 内新建目标的即时预算收缩、剩余入口和统一应用控制面接线。按用户要求继续直接实装，不重复完整审计，不将本次目标测试当作全量/真机验证。

## 前一子批次：会议室分工与 Run 上下文

用户反馈主持人代做全部工作后，优先修复分工请求错误开放工作工具、JSON 失败静默由主持人执行、@ 主持人绕过分工三条实际路径。本机主持人现在分工/汇总无执行工具，成员仍经独立 Runtime 真正执行；无效分工修正一次后明确停止，不假装已经派发。临时专家、成员权限交集、附件、失败状态与 @ 指定成员路径保留。

同时将真实项目、有效 GoalId 和持久 Goal 预算写入准入 Run，经 Session 传给执行器和 Metrics，再继承给子 Run；修复账本项目为空而执行器使用其他项目、统计晚读项目绑定造成历史错配的问题。损坏 Goal 状态不能默认扩大预算。

最终相关回归：App 11 组、67 项通过，Application API 10 项、Kernel API 6 项通过，0 失败/错误/跳过；架构、Kotlin 编译和身份检查通过。证据：`build/reports/nexus-baseline/20261110-meeting-context-final/`。详见 [26_MEETING_DELEGATION_RUN_CONTEXT_2026-11-10.md](26_MEETING_DELEGATION_RUN_CONTEXT_2026-11-10.md)。本批没有重新做全项目审计，也不冒用前批全量结果。

N1、N4 仍为 PARTIAL；手机当前未连接，尚无真机证据。下一步继续落实统一权限和剩余 Run/应用接线，不重复完整审计。最新节奏已同步进计划第 19 节，两份计划一致。

## 前一子批次：审批与用户暂停

根 Run 和子 Run 的 Controller 已绑定正式 Recorder；高风险 Gate、内层 app-control 和 Connector 的审批经同一可信运行作用域归属到自己的 Run。审批请求/结果、暂停/恢复均写入 SQLite 事务与 Item 投影，UI 继续使用现有确认窗口和状态快照。授权或暂停记录失败不能假报成功；批准不会解除用户暂停。

目标回归 App 58 项、Application API 10 项、Kernel API 4 项通过。新增两项故障测试后的完整基线通过：App 500 组、3,274 项，3,124 通过、150 平台跳过、0 失败/错误；API 10+4 项通过。独立身份检查通过。证据：`build/reports/nexus-baseline/20261110-approval-pause-full/`。

详细接线、第一轮失败原因和未完成项见 [25_APPROVAL_AND_PAUSE_LIFECYCLE_2026-11-10.md](25_APPROVAL_AND_PAUSE_LIFECYCLE_2026-11-10.md)。N1 保持 PARTIAL，全部阶段仍为活动目标；当前 ADB 无设备，未安装 APK，Lint 仍未完成。

## 第一批真实改动（历史快照）

| 子项 | 生产接线 | 当前证据 | 仍缺少 |
|---|---|---|---|
| 模型/工具硬预算 | RuntimeRunExecutor→共享 BudgetGuard→ModelClient→AgentLoop；Subagent 同样使用 Guard | 脚本模型无限调用、批次中断、重试轮次、显式大预算测试 | 软预算暂停/追加预算、费用硬上限、父子预算交集和持久续跑 |
| 阻塞请求期限 | BudgetGuard watchdog→RunController.abort→注册资源取消 | 无流式事件的阻塞 Provider 被释放；保留预算失败类型，不伪装为用户取消 | Android/原生模型及所有 Host 的真实取消、不可取消进程回收 |
| 批次结果持久回调 | 每个已结算工具结果立即写入 transcript/Checkpoint 回调 | 第一个工具成功、第二个触发预算时，保留第一个真实回执 | 完整事务与 Checkpoint/正式账本一致性 |
| 影子账本 | Session 事件、成功/失败/直接取消都进入 Recorder | 并发序号、队列溢出、初始化失败、未结算工具、损坏尾部测试 | 正式执行 Store、幂等事件摄取、fsync/事务和恢复游标 |
| 只读账本检查 | 软件管家 `state.runtime.executionLedger` 返回检查结果与 Metrics 局部对照 | 检测乱序/缺口、重复 ID、工具关联错误、无终态、丢事件 | 四方对照的真实 20 Run；当前 `fullComparisonComplete=false` |
| 架构门禁 | 修正路径分支，PR 调用 `checkNexusBaseline`，加入身份与 API 合同测试 | 故意注入 Data→UI import 被准确拒绝，探针已移除 | 其他反向依赖迁移和更完整静态边界验证 |

### 自动测试与回归发现

- Runtime、Controller、Budget、Session、Mapper、Shadow Recorder 6 组、65 项目标测试通过。
- 第一次完整 App 回归：3,220 项，17 失败、150 跳过。结果保留为本轮基线发现，不能记为通过。
- 其中品牌相关断言仍使用旧 Eta 名称；源码 ZIP 对照确认这些测试和对应品牌生产文件原本就未同步。更新为 Nexus 名称，并保留 Header 路由/隔离、文件名消毒和权限限制断言。
- 自动备份测试改用 Nexus 所有的备份名，并额外验证同目录旧 Eta 备份不被 Nexus 自动清理。
- 旧 UI 测试仍访问已移出筛选框的时间选项，或者未滚动就点击屏幕下方入口；按当前导航验证可达性、内嵌子选项和返回。增加显式周期选择改变日历范围的测试。
- 实际补齐 Goal/Plan/Notepad 等新增工具的语义图标，不删除原有“每个工具有具体图标”测试。
- 上述 11 组受影响测试已回归通过。
- 完整基线复核 `checkNexusBaseline`：BUILD SUCCESSFUL，耗时 3 分 30 秒。App 491 组、3,221 项测试：3,071 通过、150 平台跳过、0 失败/错误；Kernel 4 项、Application API 10 项全部通过（API 测试为同源码缓存结果）。Kotlin 编译与修正后的架构检查通过。
- 原始 Gradle 日志、逐组 XML 和计数保存在 `build/reports/nexus-baseline/20261110-full-regression/`。它们是本地构建证据，不是手机端端到端证据。
- 本机一条命令入口：`scripts/check-nexus-baseline.ps1 -Aapt2Override F:\devtools\android-sdk\build-tools\36.0.0\aapt2.exe`。脚本语法已验证；其底层身份检查与 Gradle 基线命令已分别实跑，不重复运行整套测试只为验证包装脚本。

### 后续执行授权与完整性复核

- Subagent 的基础执行器原先绕过新增 Gate；现由 `authorizeChildTool` 在真实子任务工具调用前接入同一 Gate，并绑定子 Controller，取消子任务会撤销等待中的审批。
- 任务授权过期时间在 Run 开始时固定，不因每次调用重新延期。
- 高风险调用不能通过参数 `confirmed=true` 获得软件侧批准；新增测试验证本机拒绝、运行取消、授权过期、无工具白名单与远端高危拒绝。
- 去掉按 `search_`/`list_` 名称前缀推断只读的逻辑；未登记工具继续按高风险处理。完整工具效果分类与参数作用域仍待 N2 专项完成。
- Canonical Mapper 修复 Goal/Plan 模式、会议室稳定 Session，以及包含中文或任意旧标识时的 ID 转换，防止合同校验直接打断现有请求。它仍只是过渡映射，不能算 RunApplicationApi 已实施。
- 最新修改后，修正的架构门禁、Kotlin 编译和 9 组 70 项目标回归全部通过，0 跳过。证据在 `build/reports/nexus-baseline/20261110-runtime-permission/`。
- 完整 Lint 运行约 15 分钟后仍在 `BidirectionalTextDetector → KotlinUFile.getAllCommentsInFile` 的 PSI 注释遍历中，两次线程快照确认没有产出报告；主动结束该次扫描，未禁用 detector、未降低严重度、未记为通过。日志和线程快照与目标测试证据一同保留。Lint 状态：未完成，需独立诊断；后续目标测试已在原构建进程结束后执行，没有并行重建同一目录。
- 上方 3,221 项完整回归属于同批较早快照；最新授权与 Mapper 修改由 70 项目标测试覆盖，不把旧的全量绿灯当成最新完整快照证据。

## 阶段状态

### N1 检查点与子 Run（本轮继续）

执行库升级至 v2，使用显式 v1→v2 迁移保留原事件并重建 Item。真实 transcript/context 回调现在写入有 committed sequence、SHA-256、分块内容的私有快照；Subagent 经统一 API 生成独立 ParentRunId/RunId 和自己的快照，工具预算同时计入父 Guard。软件管家新增真实执行证据对照读取。

该批完整基线：App 3,257 项，3,107 通过、150 跳过、0 失败；Application API 10 项、Kernel API 4 项通过。随后补齐输入快照/证据诊断有对应目标测试，详见 [24_CHECKPOINT_ITEMS_CHILD_RUNS_2026-11-10.md](24_CHECKPOINT_ITEMS_CHILD_RUNS_2026-11-10.md)。N1 仍为 PARTIAL，未自动续跑，未完成 20 真机 Run 对照。

### N1 准入与事务库（较早子批次）

`NexusRunApplicationApi` 已在 Service 中实例化并负责 canonical 准入；新 `NexusExecutionStore` 用独立 SQLite 事务持久化 Run/typed fact，工具开始记录失败不再吞掉。重复运行、未完成任务、工具回执关联、终态失败与快速/完整备份均有实际执行测试。详情见 [23_CANONICAL_EXECUTION_STORE_2026-11-10.md](23_CANONICAL_EXECUTION_STORE_2026-11-10.md)。

该批完整基线：App 3,248 项、3,098 通过、150 跳过、0 失败；Application API 10 项通过、Kernel API 4 项通过。其后 Checkpoint、独立子 Run、Item 已继续接线，见上方更新；本段保留当时证据，不作为最新完整快照。

| 阶段 | 当前结论 |
|---|---|
| N0 审计与验收基线 | PARTIAL：已保存问题与本轮故障证据；尚需完整基线、覆盖矩阵和各入口夹具核对 |
| N0.5 统一协调边界 | PARTIAL：Service 统一 Run API、真实项目/目标关联与独立子 Run 已接线；模型/Host/资源协调仍未完整归一 |
| N1 执行/账本 | PARTIAL：事务库 v5、Checkpoint/Item、子 Run、审批/暂停、工作流事实、软预算确认、即时 Goal 限额、父子模型请求总额度与费用回执已接线；单笔费用预留、硬预算追加、所有来源、输入等待与 20 Run 尚缺；恢复未切换 |
| N2 权限执行 | PARTIAL：执行 Gate、根/子有限期激活、撤销后的 schema 移除与 Goal 本机确认已实装；完整 Descriptor/Grant/Host/参数范围和所有入口仍需完成 |
| N4 聊天/会议/子 Agent/自动任务 | PARTIAL：已定向修复会议室主持人不派发，覆盖实际 Room 编排；真实模型/设备、多图压缩、全部入口与并发验收仍缺 |
| N3、N5–N9 | 仍按计划逐项实施；不因本轮测试通过提前标记完成 |

## 安全边界和回滚

初始预算/影子修复批次没有变更旧数据库；后续新增独立 `nexus_execution.db`，现为 v5，并提供从 v1/v2/v3/v4 的非破坏迁移。迁移测试使用隔离测试数据库，未读取、迁移或删除手机数据，没有安装 APK，没有创建或替换 Nexus 签名身份。恢复依据仍为旧 Checkpoint/outbox，不将完整性检查通过当作安全恢复证明。

回滚粒度为预算控制、影子记录/检查、架构门禁与测试修正四部分。未来正式切换持久事实源时必须先提供单独迁移与回滚方案。当前不通过 `reset` 或覆盖整个源码目录来回退。

## 下一步

1. 按 R1/R2 接完剩余设置/交互引用、工具参数边界、各运行来源、硬预算追加和单笔费用约束；已通过的功能链不重复跑全量检查。
2. 按 R3–R7 复用 ETA 的记忆/Skill、自动任务、模型、外部 Host、系统语音和备份流程，逐条接真实业务。
3. 按 R8 输出独立 Nexus 正式签名 APK、校验值和精简说明到桌面。Android 16/17 真机、长期和使用验收由用户负责，不作为助手交付阻塞项。
4. 恢复仍保持旧 Checkpoint/outbox 作为依据；不凭编译或局部账本检查自动改换恢复主存储，也不要求助手重新执行真机 20 Run 才交包。

## D09 恢复预算、错误回传与虚拟屏帧健康（2026-11-13）

Root 驱动的无障碍服务自动恢复此前将三次尝试上限保存在进程内；进程重启后可能重新获得完整预算。本批把尝试次数、墙上时间和 Android boot count 写入私有 SharedPreferences，并为每次重启后的冷却建立保守时间锚点。启动身份未知、时钟倒退、损坏字段和持久化失败都不能扩大自动恢复次数；只有用户显式重试或稳定连接达到既定条件并成功落盘才重置预算。负数及整数溢出同样视为预算耗尽。

最小链路验证：`AccessibilityRecoveryBudgetTest`、`AgentAccessibilityHealthTest`、`AccessibilityRunGateTest`、`VirtualDisplaySessionTest` 和 `GestureFallbackPolicyTest` 定向通过，Gradle `BUILD SUCCESSFUL`。覆盖进程重建、重启退避、时钟异常、坏记录、SharedPreferences 错误类型/负数/溢出、写入失败和显式重置；另覆盖恢复错误码传入 Run 的最终拒绝、手动恢复仍可继续、取消仍中止且不会执行屏幕操作，以及虚拟屏缓存帧过期、时钟倒退拒绝和帧恢复诊断清除。虚拟屏观察优先使用仍有效的系统截图回退；若没有可用新截图，则返回 `FRAME_STREAM_STALLED` 而不附上旧帧。坐标手势、节点点击/长按、滚动和用户手动触控都传递动作开始时捕获的租约；无障碍主线程副作用派发、Root 输入和租约转移通过同一锁栅栏串行化，过期操作返回 `DISPLAY_LEASE_EXPIRED` 并禁止 Root 重放。虚拟屏快照保留成功的 flags profile；Root 桥 READY 协议附带 display id 与所选 flags，画板和 `observe_screen` 展示请求了哪类焦点标记，同时明确实际焦点仍未验证。最近一次相关验证覆盖 `CanvasRoutingPolicyTest` 8 项及 `VirtualDisplaySessionTest` 11 项，均通过；没有运行整套测试或手机验证。D09 仍未关闭：实际窗口焦点、黑屏成因、完整操作回执与真正多 display 仍待实现或设备验收；ColorOS/OPPO 持续稳定性由用户真机验收。

## 剩余工作新计划（2026-11-13）

用户确认以 D02–D08 的源码可执行项已收口为基线；按最新授权，剩余包严格依次为 D09→D10→D11→D12→D13→D14→D01。计划及逐包功能链、稳定性/性能门槛、最小验证和每 20 个功能的桌面正式包规则已写入 `docs/nexus/31_V6_EXECUTION_PLAN_2026-11-12.md` 第 14 节。此段记录计划更新时的状态：当时未修改源码、未运行测试、未生成 APK。当前 D09 继续执行；只核对尚未闭合的多 display 与动作回执具体断点，不重测已通过的恢复预算、帧过期和单 display 租约链。旧蓝图建议顺序与本轮用户授权有差异时，以本轮顺序为准；手机安装、ColorOS/OPPO、真实模型/服务和长期性能保持真人验收边界。

## D09 实际窗口焦点观测接线（2026-11-13）

`observe_screen` 现在通过无障碍服务读取目标 display 的应用窗口焦点状态，并以三态区分“观察到应用窗口有焦点”“未观察到应用窗口焦点”和“当前不可观测”。状态只在原 display 的当前 `(Run, session, display)` lease 仍有效时写回快照；回执包含状态、elapsed 时间和 session ID，换 session 后旧 lease 的迟到观测会被拒绝。画板文案说明这是窗口焦点观测，输入接受情况仍未验证；请求的 flags 不再充当观测证据。

最小验证：`:app:testDebugUnitTest --tests io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySessionTest --tests io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicyTest` 通过，20 项通过、0 失败/错误/跳过；覆盖有效 lease 的焦点写回、session 替换后的迟到结果拒绝以及 flags 不能自行证明焦点。完整构建输出为 `BUILD SUCCESSFUL`。本项计为当前发行批次第 1/20 个可验证功能；未运行全量测试、未构建 release、未连接手机。ColorOS 实际焦点与输入派发可用性仍需设备验收，D09 的多 display 隔离仍未实现。

## D09 虚拟屏启动的会话身份栅栏（2026-11-13）

发现 `launch_app` 与 URI 启动先取得 displayId，随后在锁外调用 Android/Root 启动；期间若 session 被释放并替换，迟到调用可能把应用送到重用的 displayId，并把 `lastLaunch` 写进新会话。现在 App 启动、Root 回退和会话元数据提交均通过 `dispatchIfSessionCurrent` 对比 session、display 与 owner 后执行，身份变化时不派发、不覆盖新会话记录。URI 与应用启动复用同一栅栏；Root `am start` 仍有 6 秒上限。

最小验证：`:app:testDebugUnitTest --tests io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySessionTest` 通过，13 项、0 失败/错误/跳过；新增替代 session 上的迟到启动拒绝用例。该项为当前发行批次第 2/20 个可验证功能。未运行全量测试、未构建 release、未做真机启动；单例控制器目前为保证 Root 启动回退不跨 session，最长 6 秒持有全局控制器锁。多 display 实装需将此等待移至 session 级栅栏。



### D09 当前实现切片：会话注册与生产租约路由（2026-11-13）

已把单资源控制器提取为独立 `VirtualDisplaySessionController`，session ID 包含完整随机 controller 身份，注册表按 session 索引并按 owner 查询。生产 facade 开始按精确 lease 分派观察焦点、截图、输入栅栏、手动触控、交接和释放；不同 Run 的启动可分配不同 controller。每个实例独立持有帧源、Bitmap、Root 桥进程、健康恢复、启动栅栏与执行线程。`AgentLocalTools` 和 `RootShellDeviceController` 不再用全局快照判定本 Run 的屏幕；Runtime 画板按当前展示 Run 选取状态，关闭无运行浮层会清理所有屏幕。音频焦点屏蔽改为跨 session 引用计数与顺序队列，释放 A 不应提前解除 B 的屏蔽。销毁 controller 会停止专属线程，disposed 栅栏拒绝迟到创建。

最小验证只运行 `VirtualDisplaySessionRegistryTest`、`VirtualDisplaySessionTest`、`CanvasRoutingPolicyTest`，最终共 25 项通过，0 失败/错误/跳过，`BUILD SUCCESSFUL`。新增 facade 级双 controller 用例验证各自 lease、截图像素、焦点写回与释放隔离；这是受控源码证据，不是真实双 display 创建/恢复证据。

D09 仍开放，不进入 D10：下一切片补齐分配/恢复/销毁并发的完整生产回归、同 Run 多 session 的显式创建及选择入口、会话数量/内存上限和无主预览清理策略，并核对动作回执剩余断点。当前默认 Run 路由仍保存一个首选 controller，不得将注册表可容纳多个同 owner 会话冒称为已完成同 Run 多屏入口。当前发行批次仍为 2/20；本次架构接线暂不另计完整用户功能，未构建 APK、未安装手机。

### D09 多屏创建、选择与有界资源（2026-11-13，3/20）

`launch_app`/`open_uri` 现支持 `new_session=true` 创建同 Run 的额外虚拟屏，或用不透明 `session_id` 选择本 Run 已拥有的屏幕；两参数互斥，主屏目标不能混用虚拟会话参数。`observe_screen`/`present_to_user` 支持指定会话，启动及观察返回当前 session 和本 Run 屏幕清单。旧 session、其他 owner 与失效租约均拒绝，不自动切换主屏。画板出现多屏时提供画面选择，手动切换先暂停 Run；下一次执行重新绑定首选屏幕并清除旧观察，不能复用旧节点/坐标。

每 Run 最多 3 个、全局最多 6 个控制器，分配在锁内预留 owner；创建额外屏失败清理该实例并恢复原首选。无主、未手动接管且未交接中的屏幕闲置两分钟后释放。关闭工具只释放该实例实际捕获过的精确租约，避免旧 executor 关闭时释放替代 executor 的屏幕。租约新增 ownershipGeneration，解决同 owner 释放后重新领取同 session 时旧 capability 意外复活；Root/无障碍派发继续按完整 lease 栅栏。

交接主屏改为 session 内派发栅栏，Root 子进程等待和输出读取有界；结果未知不再继续执行前台兜底。交接失败保留虚拟屏并返回失败，避免先销毁用户尚未收到的画面。尚未以真机证明多 display 的 OS 行为、同应用实例和稳定性，不能将显示隔离等同 Android 应用克隆。

最终只运行 `VirtualDisplaySessionRegistryTest` 2 项、`VirtualDisplaySessionTest` 18 项、`AgentLocalToolsPermissionTest` 23 项，共 43 项，0 失败/错误/跳过，`BUILD SUCCESSFUL`。新增同 Run 两 session 的精确选择/跨 owner 拒绝、每 Run/全局分配上限、无主过期释放、参数冲突和旧 lease 同 owner 再领取后仍拒绝。编译/用例失败仅修复当前链路后重跑，未做全量回归或 release 构建。

本条为当前批次 3/20。D09 继续开放：下一步集中补齐真实创建/恢复/销毁的并发断点与操作回执，不重做恢复预算和已通过单屏链；D10 尚未启动。需继续检查无主预览在 Run 结果展示和服务生命周期之间的所有权保留，以及全局回收与新分配竞态；ColorOS、OPPO、长期内存/电量由用户真机验收。后续顺序保持 D10→D11→D12→D13→D14→D01。

### D09 生命周期与操作回执收口（2026-11-13，4/20）

新增分配 reservation（释放代数与所有权代数）在 controller 锁内校验：全局清理、同 Run 重领或 controller 销毁之后，旧调用不能重新创建/启动屏幕。失败额外屏清理只处理原释放代数及 owner；恢复在重新启动应用之前再次核对释放代数和 owner，避免销毁后迟到恢复继续发起启动。全局释放和分配同锁，controller 资源生命周期仍各自串行化，不把一个 display 的 Root 启动等待扩展为全局等待。

Run 结束后的画面保存 previewOwnerRunId，仅供原 Run 查看和重新领取；重新领取会发新租约，旧 capability 仍无效，其他 owner 不能用 session_id 取走该预览。服务停止按运行 owner 与预览 owner 清理。截图/动作的后续轻量观察若遇到 lease 或首选屏变化，保留原动作成功回执并标注 DISPLAY_SESSION_CHANGED，不用新屏观察替换旧屏动作结果、不把成功改成失败诱发重放。主屏交接返回 source_session_id/source_display_id 及剩余会话清单。

最小验证：VirtualDisplaySessionTest 20 项、VirtualDisplaySessionRegistryTest 2 项、AgentLocalToolsPermissionTest 24 项，共 46 项，0 失败/错误/跳过，BUILD SUCCESSFUL。新增 stale reservation、disposed、同 owner 重领、预览归属与后观察成功回执保留用例。没有全量回归、release 或真机安装。

D09 本计划列出的源码主链（权限健康→屏幕操作→结构化回执→取消/恢复、多屏会话隔离与资源回收）进入源码收口；当前批次 4/20。保留独立真机验收：真实多 display 创建和系统焦点/输入、同应用 OS 实例行为、ColorOS 权限持久/强停、长期内存电量、QQ/微信真实入口。下一工作包正式进入 D10，先补流式录音→识别的异常与取消归属；这些真机事项不冒称已通过，也不据此重复已有源码链测试。

### D10 语音异常、取消与打断生命周期（2026-11-13，5/20）

流式录音转发改为识别协程的子任务，麦克风帧通道异常传到识别错误终态；识别结束或取消会停止录音、转发和通道，旧会话的 ready/endpoint/preview 回调不能启动或更新新录音。沿用既有本地流式 ASR，不另做识别器。

平滑停止朗读按代数隔离旧音轨、音频租约与新播放状态，淡出期间的替代朗读不会被旧清理关闭或清空；前台播放责任只释放一次。音轨暂停/清空或广播注销失败时仍尝试释放音轨、焦点和所有权；输入取消仍重置界面，双工停止仍归还前台责任。语音协调器不再吞掉取消后继续开麦，取消收尾回到空闲。

最小验证仅当前语音链：SpeechLifecycleTest 39、SpeechPlaybackTest 42、SpeechPlaybackForegroundTest 12、SpeechPcmOutputTest 7、DuplexSpeechControllerTest 4、LocalVoiceBargeInTest 36，共 140 项通过，0 失败/错误/跳过，BUILD SUCCESSFUL。首次测试编译暴露 Shadow 方法签名及缺失 import，修复后通过；未跑全量或 D09 回归，未构建/安装 APK。

当前批次 5/20。D10 仍开放，下一步补齐本地模型资源仲裁/停止与运行指标到生产调用链的明确缺口；真实识别率、音质、AEC、GPU/NPU、热/内存/电量仍为用户真机验收。整体顺序维持 D10→D11→D12→D13→D14→D01。

### D10 本地向量运行时与有界语音流（2026-11-13，7/20）

接入 LiteRT-LM EmbeddingEngine 专用运行时：EmbeddingGemma 等 `embedding` 模型经过独立的加载、CPU/GPU/NPU 候选回退、原生崩溃保护、共享本地推理槽、取消/停止/释放和状态指标链，不进入聊天模型选择器或自动聊天启动。模型页提供“启动并检查”真实入口，只展示维数、耗时和后端，不回显向量；运行设置只保留后端与线程。现有记忆检索仍在没有语义索引服务时明确 `keyword_only`，本次不伪造语义评分。

语音流播放增加 1 MiB 待播字节上限：生产者在 IO 线程背压等待消费者释放，单块超限才报 `BACKPRESSURE`；取消可唤醒等待，队列未投递元素归还计数，不从头重放。此前已完成的 ASR 帧异常传播、双工打断、旧播放代数隔离和音频/前台释放继续有效。

验证：`compileDebugKotlin` 通过；`LocalEmbeddingRuntimeTest`（状态 JSON 不含向量）与 `LocalModelLaunchDecisionTest` 共定向通过；语音链已有 140 项通过，修改有界队列后额外运行 `streamingSessionReceivesWholeAnswerOnce` 通过。未运行全量回归、未构建/安装 APK；真实 LiteRT 模型、GPU/NPU、长期内存/热/电量、语义检索质量由用户设备验收。

当前批次 7/20。D10 源码主链已闭合，进入 D11：会议、子 Agent、电脑/手机协作。D11 继续按一次真实生产来源核对派发、授权、进度、取消/恢复和资源回收，不重做 D02–D10 已关闭链路。

### D11 会议失败可见性与子 Agent 并发门控（2026-11-13，9/20）

会议汇总输入现在包含每个失败或超时参与者及错误原因；成功参与者仍可汇总，但主持人必须说明失败与缺失，不能把部分成功冒充完整完成。新增回归覆盖一名参与者成功、另一名失败时的真实汇总提示。

修复 SubagentConcurrencyGate 取消竞态：排队中的子 Agent 在尚未取得许可时被取消，不再错误归还信号量；队列计数和许可数保持一致，父 Run 的取消仍能回收子控制器。新增取消排队回归用例。已有会话续接 compare-and-set、防重复运行、父子取消绑定和部分结果持久化链保持不变。

最小验证：MeetingDelegationTest 与 SubagentConcurrencyGateTest 定向通过，BUILD SUCCESSFUL；未跑全量回归、未构建/安装 APK。当前批次 9/20。D11 继续核对电脑/手机显式授权与 A2A 续接失败边界，之后进入 D12。

### D11 A2A 续接与授权边界核验（2026-11-13）

当前 A2A 生产链已有持久任务、取消、进程重启恢复、输入/授权暂停、续接去重、权限扩张拒绝、已结算调用摘要和 HTTP 生命周期清理；本轮未发现需要新增源码修复的断点，因此没有重做实现。定向运行 A2AInboundTaskCoordinatorTest 24、A2AInboundRuntimeOutcomeTest 7、A2AInboundApprovalSummaryTest 4，共 35 项通过，0 失败/错误/跳过。A2A 外部 peer、真实 HTTPS/身份和 tsnet 仍待真实环境验收。

## D12 连接撤销、事务暂存与沙盒重连（2026-11-13）

API 工具调用在每次外发前重新核对持久连接的启用状态、动作授权、端点和凭据；断开或撤销后，已创建的 Run 快照返回 `API_CREDENTIAL_REVOKED`，不会继续使用旧授权。未持久化的无认证测试快照及显式注入 OAuth provider 保留其运行边界。插件沙盒在服务进程断开、binding died 或空绑定时清除失效端点并重建连接等待，后续调用重新 bind；不会永久复用已完成但失效的 Binder。

Skill 依赖父事务日志在子依赖安装后的状态刷新期间保持排除，避免索引刷新误恢复并删除仍在使用的父 `dependency-operation-*` 目录。首个依赖成功后根 Skill 可以继续提交；缺失来源仍返回 `DEPENDENCY_SETUP_REQUIRED`，旧版本和目录保持可恢复。MCP 停止树、API 删除回滚和插件卸载释放链未发现新的源码缺口。

定向验证：`ApiToolExecutorTest`、`ApiConnectorDisconnectTest`、`McpProcessLifecycleTest`、`PackageSandboxClientTest`、`SkillLocalZipDependencyImportTest` 通过，0 失败/错误/跳过，`BUILD SUCCESSFUL`。未跑全量回归、未构建/安装 APK；真实外部服务、OAuth/HTTPS、Android 沙盒和长期资源稳定性由用户环境验收。当前批次 12/20，下一步进入 D13。

## D13 迟到用量合并与预算边界核验（2026-11-13）

请求级统计对同一打开请求的后续 UsageReceived 改为字段级合并。稀疏或累计回执补充已知计数，不再清空先前输入、输出、缓存、上下文或本地运行时信息；计费派生值按合并后的输入/缓存/输出重算，迟到回执不会覆盖请求级成本证据。新增回归覆盖完整回执后接稀疏回执，并核对请求级与运行级统计。

D13 对预算预留链做了定向核验：SQLite 原子校验与写入、父子 Run 家族共享、并发等待取消唤醒、未知价格/用量阻断、结算失败保留开放预留、provider 超支终态均已有覆盖，未发现可证实的新源码缺口，未做空泛修改。

定向验证：`AgentRequestMetricsTest` 通过，`BUILD SUCCESSFUL`。未跑全量回归、未构建/安装 APK；真实供应商价格和账单仍由用户环境验收。当前批次 13/20，下一步进入 D14。

## D13 统计主键完整性（2026-11-13）

本地模型统计样本主键改为对完整 `runId` 与 `requestId`（NUL 分隔）计算 SHA-256，不再截断长标识。跨 Run/请求不会因前缀相同而共用样本记录，迟到快照也不会覆盖其他账本项。新增稳定性和长键不碰撞回归。

定向验证：`LocalModelMetricsCheckpointTest` 通过，`BUILD SUCCESSFUL`。当前批次 14/20。

## D14 备份完整性与 SAF 目录边界（2026-11-13）

加密备份在认证结束帧后要求底层流立即 EOF，拒绝结束帧后的尾随拼接数据。生成文件归档选择要求 `content://` URI 为 DocumentsContract tree URI，文件文档 URI 在持久化授权前失败，不留下错误 grant 或配置。

定向验证：`GeneratedFilesArchiveTest` 与 `EtaFullBackupArchiveTest.encryptedContainerRejectsBytesAfterAuthenticatedEndMarker` 通过，`BUILD SUCCESSFUL`。当前批次 17/20，仍未构建/安装 APK；真实跨设备与超大归档由用户验收。

## D14 清单外目录拒绝（2026-11-13）

当前 manifest 归档解包会物化并校验目录 entry；验证要求目录属于已登记文件/链接父级并匹配 manifest 精确目录集合。清单外空目录不能绕过文件/哈希清单进入恢复。新增拒绝清单外空目录回归通过。

`preservesEmptyDirectoriesAndAcceptsPreviousManifestVersions` 在当前 Robolectric 数据库版本基线下仍失败，本次未修改旧版本迁移规则，也未将其计为新修复失败。当前发行批次 18/20。

## D14 SAF 项目创建权限闸门（2026-11-13）

SAF 项目创建在写入记录前要求 tree URI 和持久化读写 grant；文件文档 URI、无 grant 都返回明确错误，不会保存不可访问 root。与归档目录选择、失败恢复保留旧备份的回归一起通过。

定向验证：`GeneratedFilesArchiveTest.archiveDirectorySelectionRejectsAFileDocumentUri`、`ProjectRepositoryTest.safProjectCreationRequiresTreeUriAndDoesNotPersistFileDocumentIdentity`、`ProjectWorkspaceTest.providerFailureWhileRestoringOldNameRetainsBackupForManualRecovery` 通过，`BUILD SUCCESSFUL`。本轮批次达到 20/20，开始生成桌面正式 APK。

## 发行批次 20/20 包构建记录（2026-11-13）

完成 `:app:assembleRelease` 并将构建产物放到桌面：[Nexus-v0.7.3-d14-20261113-release.apk](C:/Users/29136/Desktop/Nexus-v0.7.3-d14-20261113-release.apk)，SHA-256 `211910831b5b534da8f0d7a483c5565dd8d6554d60ccfe750179db77771e6326`，大小 62,149,412 bytes。构建时环境没有 `NEXUS_RELEASE_*` 签名变量，APK 未通过 `apksigner` 签名核验（缺少 META-INF/MANIFEST.MF），所以不能冒称同签名正式可升级包；上一份已核验签名包保留作回退基线，未安装手机。

D14 可执行源码项已推进到收口，下一步进入 D01 UI 收口。

## D01 设置页加载/空/错状态收口（2026-11-13）

主设置页 Provider 配置拆分 Loading、Loaded(empty) 和 Failed 三态，避免数据库/Flow 尚未首值或异常被误显示为“未配置”。新增 `ProviderSettingsLoadStateTest.loadingAndEmptyAreDistinctStatesAndProviderFailuresStayVisible`，定向通过。

D01 仍在逐页推进；本项不代表全软件 UI、窄屏、TalkBack、字体放大和真实触控验收完成。

## D14 恢复失败保留旧文件（2026-11-13）

项目工作区在新名称提交和旧名称回退都失败时保留旧文件备份，状态为 INCOMPLETE；重复 operationId 返回冻结回执，不覆盖旧内容。`ProjectWorkspaceTest.providerFailureWhileRestoringOldNameRetainsBackupForManualRecovery` 通过。

## D01 权限健康页操作状态一致性（2026-11-13）

勿扰访问已授权时隐藏“设置”操作，只有 Missing 状态提供 action，避免重复打开系统设置。新增 `PermissionHealthStateTest.notificationPolicyActionOnlyAppearsWhileAccessIsMissing`，定向通过。

D01 当前未发现可证实的窄屏或运行详情生产缺口；真实字体、TalkBack、触控和返回行为仍需设备验收。

## D01 权限与备份状态语义、无障碍文案（2026-11-13）

权限健康页只在勿扰访问缺失时显示“设置”；备份暂存/待重启状态不再显示绿色已完成；Capability Center 刷新按钮使用中/英/繁体资源化 contentDescription。

`PermissionHealthStateTest`、`BackupOperationPresentationTest`、`LocaleResourcesTest` 定向通过，`BUILD SUCCESSFUL`。D01 继续逐页推进，真实字体、TalkBack、窄屏和返回行为仍待设备验收。

## D01 运行轨迹无障碍语义（2026-11-13）

运行详情轨迹行点击区域增加 Button 角色和点击标签，改善 TalkBack 语义；`compileDebugKotlin` 通过。真实语义树和焦点顺序仍需设备验收。

## D01 备份状态分类细化（2026-11-13）

备份横幅区分成功、错误、取消和待处理。签名校验不通过、操作未完成显示错误，取消显示已取消，暂存等待重启保持处理中。

`BackupOperationPresentationTest` 通过，`BUILD SUCCESSFUL`。

## D01 模型服务商列表加载状态（2026-11-13）

模型服务商列表区分 Loading、Loaded(empty) 与 Failed，不再把异步 Flow 初始空列表误报为“尚无服务商”。复用三态 helper。

`ProviderSettingsLoadStateTest`、`BackupOperationPresentationTest` 通过，`BUILD SUCCESSFUL`。

## D01 REST API 连接页加载失败态（2026-11-13）

REST API 连接页将加载异常显示为错误卡并提供重试，只有成功加载且列表为空才显示空态。ApiConnectorLoadStateTest.emptyStateIsShownOnlyAfterSuccessfulLoad 通过，BUILD SUCCESSFUL。

MCP Catalog、会议和任务页面已具备关键加载/错误/返回状态，本轮未重复修改。

## D01 模型服务商详情加载状态（2026-11-13）

模型服务商详情页区分加载中、读取失败和已完成但找不到目标，避免异步首值期间误报“服务商不存在”。复用 ProviderSettingsLoadState helper；定向测试通过。

## D01 社区模型目录无障碍本地化（2026-11-13）

社区模型目录刷新按钮改用多语言资源化 contentDescription，覆盖英文、简体中文和繁体中文。LocaleResourcesTest、主 Kotlin 编译通过。

## D01 会话分支错误空态与能力来源本地化（2026-11-13）

会话分支页失败时只显示失败状态，成功空结果才显示空态；能力中心来源删除按钮改为多语言资源化无障碍标签。两项定向测试通过，BUILD SUCCESSFUL。

## D01 Token 定价失败态与定时任务无障碍标签（2026-11-13）

Token 定价页失败时只显示失败语义，成功且确实无价格时才显示空态；定时任务删除按钮改用多语言 `action_delete` 资源。`AgentTokenPricingScreenStateTest`、`LocaleResourcesTest` 定向通过，BUILD SUCCESSFUL。

## D01 A2A 设置入口无障碍本地化（2026-11-13）

A2A 设置页四个图标操作（导入配对、添加 Agent、刷新 Agent Card、删除 Agent）改用英文/简中/繁中资源，法语回退英文。Package Center、DSH、Memory Review 的加载/错误/空态已按源码核查，本轮未见可证实互斥缺口。

`AgentTokenPricingScreenStateTest`、`LocaleResourcesTest` 与 `compileDebugKotlin` 均通过。真实 TalkBack 语义树仍待设备验收。
A2A LocaleResourcesTest 增补四组文案在英文/简中/繁中及法语回退断言，定向重跑通过。

## D01 主会话输入栏触控目标与定时任务无障碍标签（2026-11-13）

共享输入栏的六类主要操作命中区提升到至少 48dp，图标保持原视觉尺寸；`ChatInputTouchTargetTest` 定向通过。定时任务的新建、运行、启用/暂停图标改用英文/简中/繁中资源，`LocaleResourcesTest` 覆盖通过。

## D01 QQ 群入口修正（2026-11-13）

加入 QQ 群改用带 `card_type=group` 的群卡片 URI，群号经统一构造器校验；`QqGroupInviteTest`、`LocaleResourcesTest` 通过。QQ 客户端真实跳转与入群仍需设备确认。

## D01 工作区目录读取失败态（2026-11-13）

目录读取失败时改为独立错误说明和重试，不再并列显示“暂无文件”；仅加载成功后的空目录显示空态。`WorkspaceLoadStateTest` 通过。

## D01 关于页 QQ 群入口本地化（2026-11-13）

QQ 群入口、群号说明和复制反馈改为英文/简中/繁中资源，`LocaleResourcesTest` 通过。

## D01 记事本入口触控与屏幕阅读器语义（2026-11-13）

主会话顶部记事本拉手提升到 48dp 命中区，增加 Button 语义和多语言标签并保留下拉打开；`NexusNotepadPullHandleTest`、`LocaleResourcesTest` 定向通过。设备焦点顺序仍需实机验证。
会话消息流顶部 inset 与记事本点击区同步，避免扩大入口后遮挡首条消息。

## D01 本批 20 项门槛桌面包（2026-11-13）

完成上一包后的 20 个已验证 D01 用户可见改进后，生成桌面包 `C:\Users\29136\Desktop\Nexus-v0.7.3-d01-20261113-debug.apk`，99,882,102 bytes，SHA-256 `59BB7D5F5A64ABA94DF6916EA9E43118775CC7078C84E9BEC6374113D46FE87D`。`apksigner verify --print-certs` 通过，使用 Android Debug 证书；未配置 `NEXUS_RELEASE_*`，不能作为同发布证书覆盖安装包。未安装手机；新批次从 0/20 开始。

## 用户最新顺序确认与 D01 会话 Skill 选择（新批次 1/20，2026-11-13）

用户再次确认按 D09→D10→D11→D12→D13→D14→D01 顺序推进。D09–D14 的源码实现和最小直接验证均已按包记入本计划和此进度文档，设备、外部服务与长期稳定性证据继续独立待验；因此当前顺序推进至末位 D01，不重跑前序已通过测试。

主会话加号菜单增加 Skill 选择，最多三个已启用且兼容的 Skill。选择随对话保存在草稿存储，切换对话隔离；发送进入队列时捕获选择，队列撤回恢复原选择。Run handoff 与续接保留稳定 Skill ID。Runtime 按本次有效安装、启用、兼容和自动 Skill 作用域重新校验；不可用时给出失败而不静默回退，显式选择只向本次 Run 暴露所选 Skill，等优先级 Skill 冲突失败关闭，工具权限继续与父策略取交集。旧草稿/handoff 兼容空选择。

最小验证：`compileDebugKotlin` 通过；`SkillMatcherTest`、`AgentPromptBuilderTest`、`AgentUiHandoffPayloadTest`、`AgentContinuationBuilderTest`、`ConversationAttachmentDraftsTest`、`AgentMessageQueueReducerTest` 共 68 项通过；`LocaleResourcesTest` 2 项通过，0 失败/错误/跳过。没有全量回归、APK 构建或手机安装。新批次计为 1/20，继续完成 D01 剩余 UI 页和交互链。

### D01 主会话 Skill 列表加载失败可恢复（2026-11-13）

加号菜单的 Skill 候选列表现在区分加载中、真实空列表和读取失败；读取异常不再静默伪装成“没有可用 Skill”，失败时显示可本地化错误与重试入口，重试重新读取已安装且兼容的 Skill。列表读取仍在 IO 线程执行，取消会继续向上传播；当前已选但暂不可用的 Skill 仍保留为可移除项。英文、简体中文和繁体中文均有加载/错误提示。

最小验证：`:app:compileDebugKotlin` 成功；`LocaleResourcesTest` 定向通过，BUILD SUCCESSFUL。未运行全量测试、未构建 APK、未安装设备。Skill 选择功能的先前定向测试记录更正为 68 项，另有 `LocaleResourcesTest` 2 项；D01 新批次仍为 1/20，不重复计入该选择功能。
### D01 MCP 服务配置列表加载态与失败重试（2026-11-13）

MCP 服务器设置列表、服务器详情和资源目录此前都用 Flow 初始空列表作为已加载数据；数据库首值尚未到达或读取异常时，页面可能误报没有服务器/服务器不存在。现三处共用加载中/成功/失败状态：错误时显示可重试操作并重新订阅持久 Flow；错误期间隐藏概览统计、空列表和导出数量，不将未知数据呈现为零。Flow 在后台持续更新成功数据，取消仍会传播。英文、简体中文和繁体中文均提供状态文案。

最小验证：`:app:testDebugUnitTest --tests io.github.mangi.eta.ui.screens.mcp.McpServersLoadStateTest --tests io.github.mangi.eta.ui.app.LocaleResourcesTest` 成功；覆盖空态仅在成功空列表出现及三种语言提示；目录、详情和设置列表均通过同一持久 Flow 状态所有者，主 Kotlin 编译通过。未跑全量测试、未构建或安装 APK。D01 当前批次计为 2/20。
### D01 能力中心 MCP 统计保留未知状态（新批次第 3 项，2026-11-13）

能力中心概览、连接清单和健康区现在使用 MCP 服务器的统一持久加载状态。加载中不显示零，读取失败时总连接数显示未知、MCP 配置数量显示“—”，健康区提供重试；只有服务器列表成功读取后才显示“尚未连接”空态。避免把数据库故障或首值等待误报成用户未配置连接。

最小验证：共享 `McpServersLoadStateTest` 1 项通过（加载/失败不显示空态、成功空列表显示空态）；主 Kotlin 编译成功。未跑全量测试、未构建 APK。D01 当前批次 3/20。
### D01 能力中心本机数据加载与真实空态（新批次第 4 项，2026-11-13）

能力中心此前在本机 Skill、API 与 Skill 来源数据首次读取尚未完成时就按空集合绘制数量和空态；读取失败后也可能把未加载显示成“没有来源/没有市场安装记录”。现显式记录本机快照的 Loading/Loaded/Failed 状态：成功快照前的数量显示未知，读取中显示加载状态，失败显示可重试错误；只有成功读取后的确实为空才显示空态。已存在的快照在刷新失败时保留，避免因瞬时读取故障清空用户可见数据。MCP 与 API 连接总数仅在两侧数据都已知时显示数字。

最小验证：`CapabilityLocalDataStateTest` 1 项与 `LocaleResourcesTest` 2 项通过，`:app:compileDebugKotlin` 编译成功。未跑全量回归或构建 APK。D01 当前批次 4/20。
### D01 能力中心并发刷新取消与新结果优先（新批次第 5 项，2026-11-13）

能力中心启动读取与安装后刷新可能同时执行，旧请求较晚返回时会覆盖较新的 Skill/API/来源快照，并提前关闭加载状态。现在新刷新会取消前一个读取并递增代数；只允许当前代读取的成功/失败结果更新界面，旧读取的 `CancellationException` 保持传播，旧任务收尾不能清除新任务的 loading 状态。减少重复全量 Skill 索引刷新与无效重绘。

最小验证：`CapabilityLocalDataStateTest` 定向 1 项通过，并由同一任务编译主 Kotlin 源码，`BUILD SUCCESSFUL`。未跑全量回归、未构建 APK；D01 当前批次 5/20。
### D01 项目列表加载、失败重试与归档空态（新批次第 6 项，2026-11-13）

项目页此前把 Room Flow 的初始 `emptyList()` 直接当作真实数据，刚进入时可能短暂显示“暂无项目”；数据库读取失败则可能永久伪装为空。现在明确显示加载中、读取失败并提供重试；只在收到成功项目快照后渲染列表或空态，Flow 取消继续传播，空态计算保留“显示已归档项目”的筛选规则。英语、简体中文和繁体中文均提供加载与错误文案。

最小验证：`ProjectsLoadStateTest` 1 项通过；同一构建链编译主 Kotlin 源码成功。`LocaleResourcesTest` 2 项在本轮首次定向运行通过；未跑全量回归、未构建 APK。D01 当前批次 6/20。
### D01 项目详情加载、失败重试与未知数量（新批次第 7 项，2026-11-13）

项目详情弹窗的绑定与文件读取现在作为同一快照加载，区分 Loading、Loaded 和 Failed。加载中与失败时不显示虚假的零个文件/绑定；失败提供重试，只有成功空快照才显示空文件说明。读取取消继续传播，文件区域的状态文案覆盖英文、简体中文和繁体中文。

最小验证：`ProjectsLoadStateTest` 1 项和 `LocaleResourcesTest` 2 项通过，主 Kotlin 编译成功，`BUILD SUCCESSFUL`。未跑全量回归、未构建或安装 APK。D01 当前批次 7/20。


### D01 收尾清单与 D14 关联修复（2026-11-13，新批次 19/20）

本轮按明确需求收尾，不再以无边界的逐页搜索扩充目标。D09–D13 不重跑既有测试；D14 只处理已经留下失败证据的关联缺陷。三个窄任务子 Agent 分别负责皮肤、共用弹层/记事本、图片保存，主 Agent 负责文件页面、模式入口、合并和一次最小验证。

| 批次项 | 用户行为与当前源码结果 | 所有者 |
|---|---|---|
| 8 | 工作副本区分加载、真实空列表和失败；错误可重试，取消继续传播 | `ProjectWorkingCopiesDialog.kt` |
| 9 | 项目/会话文件浏览器区分读取/错误/空态；失败重试时重读项目绑定；导入操作串行且部分成功后刷新已导入文件；取消不显示普通失败 | `NexusArtifactBrowser.kt` |
| 10 | 切换皮肤立即刷新字重，避免记忆缓存沿用上一皮肤样式 | `AgentAppTheme.kt` |
| 11 | Runtime 浮层订阅同一持久外观并复用全局皮肤、颜色和字体，保留浮层固定尺寸与软件 Canvas 圆角回退 | `AgentRuntimeService.kt` |
| 12 | 共用选择器在当前弹窗内展开，返回先收起子选项；标题/值分行、选项完整换行、48dp 命中区，多语言无障碍描述 | `DialogChoicePreference.kt` |
| 13 | Nexus 内可见且可读取的非账本图片可选择 SAF 位置保存；复用有界来源策略，真实解码并写入关闭后才报成功，失败清理目标，取消传播 | `NexusImagePreview.kt`、`MarkdownImageLoader.kt` |
| 14 | 主会话加号整合普通会话/计划/目标与既有 Skill；复用原模式回调，运行中不新增模式切换，会议附件入口保持其现有语义 | `AgentChatFileAttachments.kt`、`AgentChatInputBar.kt` |
| 15 | 记事本接入共用录音、识别、完成/取消/失败反馈；识别结果只追加当前记事本，不自动发送；切换会话销毁旧控制器 | `NexusNotepadDialog.kt`、既有 `SpeechControls.kt` |
| 16 | 记事本自动保存不再因修改自身 saving 状态取消写入；自动、手动和关闭保存通过同一互斥锁串行，失败/取消归还 busy 状态 | `NexusNotepadDialog.kt` |
| 17 | 记事本关闭、下滑、返回和交给 Agent 前先保存；失败保留文字并提供继续编辑/明确放弃退出；键盘与长内容可滚动访问 | `NexusNotepadDialog.kt` |
| 18 | 学习数据库迁移清单与真实 schema 4 一致，新导出的完整备份不再被本机预览误拒绝为未来版本 | `EtaMigrationPlan.kt` |
| 19 | 备份目录索引每个归档只构建一次，消除每访问一个目录就重建整份清单的重复工作，目录拒绝规则不放宽 | `EtaFullBackupArchive.kt` |

D14 旧记录更正：此前将 `preservesEmptyDirectoriesAndAcceptsPreviousManifestVersions` 失败归因于测试环境数据库基线不准确。实际缺陷是迁移清单仍允许学习库版本 3，而生产库已经为 4。已修正清单、过时断言与硬编码的主库版本复位，并以实际 Room 数据库版本对照约束清单。本轮旧 manifest/空目录、清单外目录拒绝、未来数据库拒绝三条用例均已通过。

最小验证：项目详情链先前 3 项通过；本轮批量运行 11 个定向用例，除图片的无效字节用例外其余 10 项通过。图片用例首次使用 Robolectric 默认假图形解码器，改为仓库已有的 Native graphics 模式后，仅重跑 `MarkdownImageExportTest` 的 2 项，均通过；不修改生产规则绕过断言。本轮共 11 个不同用例最终通过，包括真实 PNG 字节写入与无效图片目标清理、迁移清单/实际库版本、三条归档路径、记事本持久修订和多语言资源。主 Kotlin 与单元测试源码编译成功。没有全量回归，没有重复 D09–D13 或语音整套测试，没有安装手机。

### 本轮源码范围的收尾边界

- 加号/模式/Skill：入口连接到既有会话业务状态；记事本顶部入口和紧凑顶栏沿用已落地实现，补上此前确实缺失的真实听写及保存退出链。
- 皮肤/页面组：CLASSIC、OMNI 和 AURORA 复用同一业务状态；新安装默认和显式旧偏好持久化沿用既有实现，本轮修复样式缓存与浮层消费者。设置/模型/连接/能力/项目/文件的加载和错误页面组按前述记录落地；共用深层选择器和记事本修复覆盖对应返回、长文本与键盘源码边界。
- 模型、工具与设备、连接和审批沿用既有导航与 Descriptor/Grant 消费者；不新增没有业务消费者的设置。QQ 群 URI 修复沿用先前实现；微信相关图片保存遵守现有可读来源边界，不绕过其他 App 私有数据权限。
- 当前已经确认的可执行源码缺口已按上述清单处理；不得因此宣称三皮肤逐设备等价、全软件所有页面真机验收或长期性能验收完成。窄屏/系统大字体/键盘实际布局、最深弹层返回、TalkBack 焦点、QQ 客户端跳转、真实录音与 ASR 质量、微信来源授权、后台/跨设备/外部服务及热/内存/电量仍由用户手机验收。
- 当前新批次保持 19/20，不为凑数拆分文案或重复计数。上轮已达 20/20 的桌面包存在未签名/Debug 交付记录，本轮使用本地已有正式签名配置补正那次交付；这属于既有交付缺陷修复，不提前触发新批次或重置其计数。以随后实际签名校验结果和 SHA-256 为准。


### 正式签名桌面交付补正完成（2026-11-13）

使用已存在的本地正式签名配置，经 `scripts/build-release.ps1 -SkipTests` 完成 Release 构建。桌面安装包：[Nexus-v0.7.3-d01-signed-20261113.apk](C:/Users/29136/Desktop/Nexus-v0.7.3-d01-signed-20261113.apk)，62,208,112 bytes，SHA-256 `a4217243e7a47a2a00581918af98a171060ada3c9317a94ca7f33682a16f0ece`。正式 v2/v3 签名通过，证书 SHA-256 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd` 与既有 0.7.2 正式交付记录一致；应用 ID、版本 0.7.3 / 2026111301、非 Debug/testOnly、隔离插件组件、ZIP 对齐及全部 16 个 ARM64 原生库的 16KiB 兼容性校验通过。桌面同时提供 `.sha256`、`.verification.json` 和 `.说明.txt`。

这次补正此前已达 20/20 后的未签名/Debug 交付缺口，未提前重置当前后续批次（仍 19/20）。未重新运行全量或已通过测试，没有安装手机。当前授权范围的明确源码收尾清单和直接验证已完成；真机、外部服务、跨设备与长期稳定性证据继续按上一节单列，不能将这份安装包描述为已经通过全部设备验收。


## 设置统一布局（2026-11-13，本批第 20/20 项）

按用户最新指令，将设置树采用的导航、分组、表单和弹窗操作统一为共用设计语言。本轮作为一个完整的跨页面布局功能组计入第 20 项，不按页面、分类标签或翻译条数重复计数。前三个子 Agent 分别处理提供商/角色/提示词、外观/备份/连接、记忆/语音/子 Agent；主 Agent 完成首页/工具栏目、能力/权限/Linux/任务及集成。

- 首页固定为智能、通用与数据、系统、关于；工具与设备六个外露开关收入独立栏目，功能开关和服务权限分开。
- 模型提供商列表分服务商、模型用途、候选路由、运行偏好；详情分连接、模型、行为、请求头，模型编辑继续分配置与能力。
- 外观、备份、能力/连接/MCP/A2A、能力包、权限、Linux、本地模型、记忆/学习、子 Agent、语音、角色/提示词、会议、任务和统计均复用固定顶部分类。单用途短页保留直接布局，完整覆盖表及规则见 `33_SETTINGS_LAYOUT_SYSTEM_2026-11-13.md`。
- 分类各自保留滚动位置；草稿、数据和控制器在分类外持有，活动/错误/停止/取消入口保持可达。根设置和工具开关写入提升到页面作用域，避免切分类取消等待中的持久写入；内嵌学习移除重复维护入口，避免三层导航。
- 长弹窗正文有界滚动、操作独立；共用操作区在窄屏或大字体下纵排，至少 48dp 命中区。新增分类提供英文、简中、繁中，三个皮肤共享业务和布局。

最小验证：主 Kotlin 与单元测试源码编译通过；`AppRouteSerializationTest` 2 项、`LocaleResourcesTest` 3 项、`ProviderComponentsTest` 11 项，共 16 项全部通过，0 失败/错误/跳过。只运行当前布局相关的路由、语言资源和提供商草稿链；构建时发现的重复资源与缺少 Text 导入已修复，未重复运行已通过的历史业务测试或全量套件。日志 `build/settings-layout-minimal.log`。

版本提升至 0.7.4 / 2026111302；达到本批 20/20 后按约定输出正式签名桌面包，实际产物与校验信息记在后续交付条目。证据限于源码、编译和上述定向检查；窄屏/大字体/键盘、三皮肤视觉和实际设备稳定性仍需用户手机验收，没有自动安装。


### 设置统一布局正式桌面交付（2026-11-13，20/20 完成）

已完成 0.7.4 / 2026111302 的正式 Release 构建，桌面安装包：`C:\Users\29136\Desktop\Nexus-v0.7.4-settings-layout-20261113.apk`，62,240,104 bytes，SHA-256 `bc483cf9b22d504dc34c7be9f96c4c5478c1daddaf16afe49f68b2ac8ad228b6`。正式 v2/v3 签名通过，证书 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd` 与先前正式包一致；应用 ID `io.github.zhh_lgtm.nexus`、非 Debug/testOnly、ZIP 对齐、16 个 ARM64 原生库 16KiB 兼容校验通过。桌面附 `.sha256`、`.verification.json` 和 `.说明.txt`，构建日志 `build/settings-layout-release.log`，原始交付报告 `build/reports/nexus-delivery/release-0.7.4/verification.json`。

本批 19 个既有功能加本轮统一设置布局一个功能组，达到 20/20 并完成正式包交付；后续批次从 0/20 计。构建使用 `-SkipTests`，没有重复已通过的 16 项定向检查或全量业务测试。临时构建环境修改已恢复；没有安装手机，设备视觉与长期稳定性边界保持独立待验。

## 设置网页预览补回原版内容（2026-11-13，待用户选择）

用户要求重做设置布局：首页不切片，复杂详情才分区，先看网页再选方案。此前预览内容过少，本轮按现有源码补回各层入口与固定设置，由三个子 Agent 分工整理，主 Agent 集成根设置、外观、系统和权限。新增清单与预览仅用于审阅，没有修改 Android 应用或适配新皮肤。

补回范围包括服务商连接/认证/模型/请求头、本地模型与运行参数、候选路由和统计计价；记忆文档/记忆库/自学习/审核冲突、子 Agent 及工具权限、角色/世界书/指令模板；语音识别/唤醒/声纹/合成/对象存储；能力/Skill/插件/各类连接及编辑子页；任务触发参数、条件与步骤；Linux/共享目录、项目/工作副本、工作区/文件操作、两种独立归档、备份范围与自动计划；语言、数字助理与系统手势、逐项权限、外观与原有关于入口。

`build/design-previews/full-settings-inventory.json` 记录 95 个页面或展开状态、948 项内容控件，所有链接目标有效；94 页从设置首页可达，会议设置作为会议界面相关页面保留。数字仅衡量预览内容，不用于功能完成计数。完整预览位于 `C:\Users\29136\.codex\visualizations\2026\11\12\01a1cdb1-0fc9-7e70-a63e-d67f33da653f\nexus-settings-complete.html`，包含“同页折叠”“分组导航”，两方案首页始终完整纵向显示。

最小检查限于 JSON、路由可达、脚本语法及离线生成；未运行 Android 编译/业务测试、未构建新包。本地浏览器被 Work 设置禁用，未进行浏览器视觉检查。当前源码及桌面 0.7.4 包仍是前次布局，不能视作本次返工的实现结果。下一步等用户选择后修改布局，再适配玻璃皮肤；本次预览不计交付批次。

## 分组导航与两套玻璃皮肤正式交付（2026-11-13，0.7.5）

用户已选择完整网页方案中的“分组导航”，并要求后续不再派子 Agent。本次由主 Agent 独立落地；此前“等待用户选择”仅为历史记录。本次是已交付布局的返工与皮肤适配，不用页面数、选项数或预览控件数增加 20 项交付计数，也不重做 D02–D14。

| 范围 | 当前源码行为 |
|---|---|
| 设置首页 | 移除四类切片，所有原栏目纵向完整显示；模型、记忆与角色、自动化与能力、系统助手、语音、外观、语言、项目、工作区、备份、会话归档、权限与关于入口保留。生成文件归档仍由工作区所有者管理，与会话自动归档分开 |
| 工具与设备 | 独立入口；短页同屏顺序呈现六个开关及工具目录、网页工具、Linux、权限四个入口，移除额外二级 Tab；继续使用原设置控制面 |
| 数字助理与搜索手势 | 新增真实可序列化路由；默认助理、模型接管、语音唤起三个分区保留独立滚动；搜索手势短页直接显示两开关；框架未连接时保留禁用选项和连接提示，不绕过权限 |
| 复杂详情 | 模型提供商、连接、能力、备份、语音等复用既有顶部导航及业务状态；统一常见四项以内按实际文字宽度决定等分，窄屏/大字体横向滚动并显示选中项，48dp 最小触控区；短页不增加分类 |
| 外观设置 | 主题、字体与显示、效果与手势三个分区；保留原三皮肤、Monet 九种风格/颜色、纯黑、五字体、80–110% 缩放、两种顶部模糊和两种返回设置。新增柔雾玻璃（frost_glass）、清透液晶（liquid_glass）；新安装默认 AURORA，已有显式选择保留 |
| 共用材质 | 两套皮肤共享原调色与可读前景色。柔雾用较高面覆盖和柔化，清透降低面覆盖并在选中导航、主会话模式胶囊及新会话胶囊使用原创圆角镜片边缘折射；设置分组、顶栏、会话输入区、内嵌选择器和通用弹窗操作区共用细边/高光/阴影 |
| 稳定与性能 | 复用已有 Miuix 依赖；设置/主会话顶栏与固定导航共用单一静态背景取样源，避免采样包含镜片自身的父层。会话底部复用原消息 backdrop。普通卡片仅静态材质，不建每卡模糊层；无常驻时间动画。关闭模糊、低内存设备、无硬件加速或不支持 RuntimeShader 时回退静态材质；软件 Canvas 绘制直接绕开 GPU 背景，文字与图标不经过折射 |
| 共同所有者 | 外观仍通过 AppearanceSettingsRepository / SettingsDataStore / 控制面持久化，展示 profile 与 Agent 外观 schema 同步增加两个真实选项；未新增 UI 直写 DAO、Runtime 或外部发送 |

最小验证共 **21 个不同用例最终通过**：路由 2、语言资源 3、外观模型 3、外观仓库 4、既有设计参数 3、玻璃降级/原生 Skia shader 编译 2、展示 profile 3、窄屏大字体实际外观页切换 1。首次持久化用例以尚未初始化的 provider=null 作基线，触发既有内置提供商初始化；修正夹具为真实已配置模型后，只重跑失败用例，并补运行当时未执行的 profile 与界面检查，未重复其余已通过用例。界面检查覆盖 320dp、1.3 倍字体、两玻璃浅/深色及静态回退，输出六张外观分区截图并抽查浅/深两张；不能据此声称手机 GPU 折射效果已经实测。最后缩短皮肤说明，避免大字体长说明占用数行。

主 Kotlin、单元测试源码与正式 Release 编译成功。测试记录：`build/grouped-glass-minimal.log`、`build/grouped-glass-repair.log`、`build/reports/grouped-glass-delivery/tests.json`；首次 XML 保留于 `build/reports/grouped-glass-initial/`。打包收尾修正静态背景的采样位置与绘制顺序后，只重建正式产物，没有重跑业务测试。最终打包日志 `build/grouped-glass-delivery.log`。未执行全量回归、未自动安装手机、未回读旧聊天。

最终桌面包：`C:\Users\29136\Desktop\Nexus-v0.7.5-grouped-liquid-glass-20261113.apk`，**62,251,408 bytes**，版本 **0.7.5 / 2026111303**，SHA-256 **`2338ea20a04be5d10a28dfea9a39f33a81332e8134df0aca57959b2184ad6a7b`**。应用 ID `io.github.zhh_lgtm.nexus`，正式 v2/v3 签名、非 Debug/testOnly、ZIP 对齐及全部 16 个 ARM64 原生库 16KiB 兼容性通过。证书 SHA-256 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`，与此前正式包一致。桌面附 `.sha256`、`.verification.json`、`.说明.txt`；原报告 `build/reports/nexus-delivery/release-0.7.5/verification.json`。

切换路径：设置 → 外观与主题 → 主题 → 界面皮肤 → 柔雾玻璃 / 清透液晶。两种玻璃按用户主动选择启用；设备上最终的背景折射观感、滚动帧率、系统大字体/键盘、最深弹层和长期功耗仍由用户装机体验确认，不把编译或静态截图描述为这些设备验收已经完成。

## 工具与全流程稳定性修复（2026-11-13，后续批次 3/20）

承接用户要求：先优化首次工具调用，再检查普通会话、会议、自动任务、模型、权限、MCP/API/A2A、文件/项目、语音、插件、记忆及备份等实际流程。本轮由主 Agent 独立完成，不回读旧聊天；不重新实现已经交付的工作包，不以测试/文件/bug 数量增加功能组计数。

1. **首次工具调用链（已有成果）**：工具延迟目录、候选暴露、Host 并发与准入后复核、MCP 首次初始化/协商、取消和不确定操作不重发、浏览器迟到动作抑制、Linux 持久设置首次读取。已有 **16 个不同定向用例通过**，证据 `build/reports/tool-first-call-delivery/tests.json`，本轮未重复整批。
2. **会话/会议与 Runtime 交付（已有成果）**：准备异常结算、停止后不迟到启动、独立连接 Attempt 与旧回调隔离、手动会议同房间唯一所有者/8 条队列、附件先保存及恢复不重放、A2A 会议取消、UI 投影异常隔离、结果按 source 分页读取、会议成功结果保存任务/消息/上下文后才确认。已有 **23 个不同定向用例通过**，证据 `build/reports/task-flow-tests.json`，本轮未重复整批。
3. **本轮自动任务及关联流程稳定性**：完整结果先存私有原子回执，任务终态或步骤断点提交后再确认 Runtime；运行详情读取完整结果。取消/租约丢失停止实际执行，过期租约拒绝提交，重复恢复不产生无主租约；deadline 唤醒与长执行隔离，任务删除所有入口统一为单事务检查。自动会议与手动共用准入/停止/恢复，部分成员或汇总失败不报完整成功，房间主动停止立即结算 MEETING_STOPPED。修复任务事件 JSON 引号/控制字符、坏项目副本清单拖垮列表、不同作用域记忆复核冲突，以及 OAuth 迟到回写/并发刷新/旧回调覆盖/null 字段和匿名 API 删除后旧快照仍可调用。

详细的入口 → 准备 → 执行 → 保存 → 取消/恢复所有者、修复清单、证据和真机边界统一记在 **`34_ALL_FLOW_REVIEW_2026-11-13.md`**。新回执目录为 `files/workflow-state/automation-results`，复用原完整配置备份范围；不向公共日志输出私有结果。

本轮最终 **29 个不同定向用例通过，0 失败**；主 Kotlin 与测试 Kotlin 编译成功。证据 `build/reports/all-flows-tests.json`；原始/修复 XML 分别在 `build/reports/all-flows-{initial,repair,deadline,room-stop}/`。只重跑失败项或直接受后续改动影响的用例，保留初始失败记录；未跑全量、未安装手机。

仍未完成的恢复边界：会议所有失败/取消/未知终态跨重启自动接纳；自动任务在结果返回与终态提交之间进程死亡后的完整结果对账；回执长期清理策略。当前保留结果和待接收记录并拒绝未知操作重放，不能把这些边界写成已经自动恢复完成。真实手机、外部服务、账号登录、原生模型/音频驱动与后台长期行为另行装机验证。

前两批计 2/20，本轮按一个功能组计 **3/20**；版本保持 **0.7.5 / 2026111303**，本轮未构建安装包。桌面已交付 0.7.5 包不包含后续稳定性源码修复，达到约定门槛后再统一打包。

## 用户指定提前打包交付（2026-11-13，0.7.6）

用户随后明确要求“出个安装包”，本次据此提前交付当前源码，覆盖上文“未构建安装包”的历史状态；本次打包不增加功能组数量，累计仍为 **3/20**。版本递增为 **0.7.6 / 2026111304**，包含上述三批稳定性修复及此前分组导航、两套玻璃皮肤。

桌面安装包：`C:\Users\29136\Desktop\Nexus-v0.7.6-stability-20261113.apk`，**62,274,584 bytes**；SHA-256 **`2d5c83a380a4b9673c81d0480d1fc33028b1bc9e4982affc82ecd753c3b660d2`**。旁附 `.sha256`、`.verification.json`、`.说明.txt`。

调用现有发布脚本并显式使用 `-SkipTests`，正式 Release 构建成功，未重复此前业务用例。最终包身份、版本、正式 v2/v3 签名、非 Debug/testOnly、ZIP 对齐及全部 16 个 ARM64 原生库的 16KiB 兼容性核对通过；桌面副本 hash 与构建产物及校验文件一致。证书 SHA-256 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`，与此前正式包一致。日志：`build/stability-release-delivery.log`；报告：`build/reports/nexus-delivery/release-0.7.6/verification.json`。

docs/31 与 docs/34 同步记录交付。未安装手机、未上传 GitHub；上文三项恢复/清理边界及真实设备、真实外部服务验证仍待后续，不因打包改记完成。

## UI 基础统一评审稿（2026-11-14，未改应用代码）

按用户最新指令停止继续逐页修改，先建立统一视觉与交互基础。已新增 `35_UI_FOUNDATION_SYSTEM_2026-11-14.md`，并生成可交互 HTML、PDF、JSON 令牌和当前界面证据；桌面包为 `C:\Users\29136\Desktop\NEXA-UI-Foundation-2026-11-14.zip`。范围包括材质、间距、字体、圆角、描边、触控、状态色、按钮/输入框/列表/设置/弹层、五皮肤、动效、无障碍、性能降级和源码迁移顺序。

本轮没有修改应用源码、业务行为、路由或身份，不增加功能组计数，不构建 APK。最小验证限于 HTML 脚本、资源引用、JSON 解析及 Edge 渲染/PDF；手机视觉选择和真实设备表现留待用户评审。

## UI 基础深化：组件字典、裸图标与双皮肤（2026-11-14）

按用户新增要求，把 UI 规范细化到真实源码组件与资产格式，并用 OPPO ColorOS 17 的 9 张截图校正材质方向。35_UI_FOUNDATION_SYSTEM_2026-11-14.md、交互 HTML、PDF、JSON 令牌同步升级为 v1.2。

只读盘点结果：146 条输入 API 调用（TextField 125、BasicTextField 8、InputField 10、SearchBar 3）、23 个用户可见搜索入口、401 个直接按钮调用（TextButton 257、IconButton 144）、58 个名称含 Button/Chip/Pill/Action 的自定义 Composable 命中、916 个图标引用与 13 个 drawable XML。调用量只证明碎片化，不当作功能完成度。

组件规则：

1. 输入收敛为 10 个角色：ComposerInput、InlineSearch、TextFieldSingle、TextFieldMultiline、SecretField、NumberField、LocatorField、CodeField、SelectField、TagEditor。
2. 动作收敛为 10 个角色：Primary、Secondary、Quiet、Destructive、IconAction、FloatingPill、Segmented、ChoiceChip、StatusPill、ActionRow。
3. 23 个搜索入口统一清空、聚焦、无结果、键盘提交和触控行为；工作区全文搜索、浏览器地址栏保留业务专有能力。
4. 功能图标统一使用 Material Symbols Rounded SVG，Android 落地 ImageVector/VectorDrawable；普通功能图标不允许 PNG。默认裸图标，设置、菜单和普通工具图标取消无意义底圈。
5. 材质分为 Canvas、Group Surface、Field、Focal Control、Overlay、Scrim。普通设置卡不实时模糊；每屏最多一个持续采样主输入区。
6. 最终只保留经典和液晶玻璃两套皮肤。液晶玻璃强化主输入区、浮动控制与浮层，不把整页所有卡片玻璃化。

本轮仍是设计文档与评审件，没有修改 Kotlin、Compose、资源、路由、持久化或产品身份，不增加功能组计数，不构建 APK。最小验证只检查 HTML 脚本、图片引用、JSON 解析和浏览器渲染/PDF，不运行 Android 测试。

最终桌面评审包：C:\Users\29136\Desktop\NEXA-UI-Foundation-v1.2-2026-11-14.zip，SHA-256 90D20686E50A6A062AFE1D9E75BB4615C2B37BDEA571B5AB9F2ECD46FD59D707。压缩包含 HTML、26 页 PDF、Markdown、JSON 令牌、7 张预览图和全部参考图。轻量检查确认脚本语法、JSON、资源引用、ZIP 结构与 PDF 渲染；没有运行 Android 编译或业务测试。

## UI 基础 v1.3：OPPO 珍珠底色与材质重校（2026-11-14）

根据用户对 OPPO 参考的最新纠正，重新直接取样原图：页面底色为 `#F0F1F3` 附近的冷白珍珠雾，上部有极弱冷光变化；内容卡主体为 `#FFFFFF`，局部约 `#FDFDFD`。旧稿把画布灰继续用于卡片和阴影，造成“一坨灰”，本版已拆开画布、纯白内容卡和光色层。

v1.3 评审稿同步完成：经典默认浅色入口；三套字体气质（系统原生、清润人文、书刊）；连续曲率输入框与动作控件；低饱和语义色；深炭发送键；可见实体高光/内阴影；30 个有中文能力名的裸图标；重做语音听状态；以及带真实背景采样感的玻璃实验室和静态降级说明。仍只改设计文档、HTML、PDF、JSON 和桌面评审包，没有修改 Kotlin、Compose、资源、路由、持久化或产品身份，不增加功能组计数，不构建 APK。

## 设置切换流畅度与小按钮明度修复（2026-11-14）

用户明确要求修复设置窗口进入时的“先闪一下再抽动”，并提高浅色界面普通按钮相对底色的白净明度。本轮只处理这两个已授权范围，没有改变页面结构、文字、圆角、尺寸、间距或交互方式。

- 根因一：`NavDisplay` 已负责整页导航转场，`MiuixScaffoldPage` 又在正文上同时执行透明度、10dp 上移和 0.992 缩放；顶栏附属区与正文分属不同动画层，形成双重入场。已移除共享骨架内的第二套正文动画及每帧 `graphicsLayer`，只保留导航转场，所有设置列表页首帧直接显示最终位置。
- 根因二：从设置总览进入 Provider 列表或详情时，已加载的 Provider Flow 没有同步首帧快照，先测一次 loading Scaffold，数据到达后再换成正式 Scaffold。`ProviderRepository` 现保存最近一次已完成映射的不可变 Provider 列表，设置总览、Provider 列表和详情优先用该快照作为首帧，同时继续用原 Flow 覆盖真实更新；清空、删除、编辑和模型变化仍由原数据所有者更新。
- 标签条恢复：分隔导航的 `LazyListState` 从已保存标签位置开始创建，不再先停在第一项后动画滑到恢复项。用户实际切换标签的动画保留。
- 按钮明度：浅色经典皮肤的 Miuix 次级按钮填充改为纯白；柔雾玻璃和液晶玻璃分别使用 94% 与 86% 不透明白色，深色皮肤维持原调色。只接管普通按钮使用的共享 `secondaryVariant`，普通设置卡、珍珠画布和选中主按钮不变。
- 最小验证：`:app:compileDebugKotlin` 与 `:app:assembleDebug` 各执行一次并成功；没有重复运行测试或启动基准。设备安装时电脑未识别到已授权 Android 设备，因此本次尚未覆盖手机，真机切换流畅度和按钮观感仍需用户安装确认。

## 会话、会议室与附件菜单修复（2026-11-14）

- 会话模型选择界面和发送配置统一过滤无连接的 API 模型；原会话模型不可用时可显示并使用其他可用模型，不覆盖原会话绑定。发送准备期间模型失效则提示配置问题，不在后台静默换成另一个模型。全局配置解析也能越过无连接的默认服务商找到已配置模型；OAuth 登录状态仍由实际请求鉴权处理。
- 会议室主持人请求、认证、配置、运行或保存失败不再伪装为“未生成有效分工”；提示按原因分类，不展示原始请求、地址或凭据。对规划中的参与者空白、大小写和合法 @ 写法规整后分派；无效分工最多修复一次，仍不允许主持人擅自代做。
- 附件一级菜单按运行与审批样式统一；MCP、技能保留原列表选择及返回。设置共享分组轻微增强与底色的区分，设置标签减少背景采样；共享按压弹簧调整为更快收敛。按钮卡顿的真实根因尚未通过受控帧分析确认。
- 最小验证：会话模型投影、提供商配置、会话状态、会议室分工和路由共 82 项定向测试通过；Debug 编译、打包、手机 `install -r` 和界面启动成功。手机实际打开过附件、MCP、技能及子级返回；真实会话测试发送后显示“已停止”，会议室实际模型调用按用户要求留待稍后验证，不能宣称两条链路真机运行成功。
- 本次调试 APK 为 `C:\Users\29136\Desktop\NEXA-v0.7.7-debug-20261114-runtime-ui-fix.apk`，SHA-256 `DF6D9BC8774A94DB9BD33A0A2FDE42A46D78EA04C2AAA25D8EFDED6176907542`。它与手机原调试版使用同一调试签名，可覆盖原调试版；不等同正式 Release 签名。

## 恢复边界闭合、按钮白度二次校正与手机覆盖安装（2026-11-14）

本轮在设置首帧与按钮明度首次修复之后，根据真机反馈继续校正浅色控件的白度，同时闭合此前明确保留的三项自动恢复边界。以下改动仍限定在用户已授权的后台稳定性和“按钮更白、与蓝灰底色分离”范围内，没有调整布局、层级、尺寸、间距、文案或交互。

- Runtime 停止时，排队中的任务不再只收到停止信号：服务直接交付已接收与失败结果；结果执行器改为有序关闭；执行器关闭后的结果回执继续通过 Binder 交付；服务销毁后完成准入的成功或拒绝都再次结算，避免迟到任务悬挂。
- 会议恢复不再只扫描房间状态为 running 的记录。只要房间存在 queued/running 或未来未知的非终态任务，重启后都会失败关闭为 waiting_for_user，不自动重放外部副作用；测试覆盖失败房间、未知状态、二次恢复不重复写消息。
- 自动任务新增 receipt-first 对账：运行租约过期时先读取完整持久回执，再决定 SUCCEEDED/FAILED，数据库终态提交成功后才 ACK Runtime；普通任务且无 A2A/Workflow 绑定时才走此路径。回执在 ACK 成功后按 90 天、最多 256 份清理，未确认结果保持不删。
- 浅色普通按钮进一步提升为更接近纯白的净色：经典纯白、柔雾玻璃 98%、液晶玻璃 94%；禁用态同步提高可见度。玻璃模式设置分组和可点击 Preference 行使用白色占比更高的表面，保留细边框、受光与少量透景，不再全面复制背景蓝灰。
- 最小验证：只运行 `AgentTaskDatabaseTest` 与 `AgentTaskResultStoreTest`，含新增的过期租约完整回执恢复和 90 天清理用例，构建成功；随后只执行一次 `:app:assembleDebug`。没有运行全量测试，也没有重复启动性能基准。
- 最新 Debug 包 `app/build/outputs/apk/debug/Nexus-v0.7.7-debug.apk` 已通过 ADB 覆盖安装到 PHY110 并启动，SHA-256 `E0F48440C60F4BBA41D8F55AB1307B89F356602CE6D826FAED7B82B56B68201A`。该包为调试交付，不增加 20 项功能批次计数，也不代表全部真机视觉、长期性能或外部服务验收完成。

## A2A 入站独立进程一致性（2026-11-14，新批次 5/20）

A2A 入站服务运行在独立 `:a2a` 进程，设置页位于主进程。此前使用普通单进程偏好快照和异步写入，设置后立即启动服务时可能读到旧的启用状态、传输方式、Token 或对端权限；存储失败还可能从按钮回调直接逃逸。

- 入站配置和加密凭据改用多进程偏好视图；启用、关闭、传输方式、对外地址、探测状态、Token 轮换和对端权限全部在返回前同步提交。主进程完成写入后才启动或重启独立服务。
- 服务每次启动、健康检查、认证和对端请求都读取当前持久快照；Token 轮换后下一请求立即使用新密文，权限从只读切换为受限时下一请求立即生效。
- 设置页的启动、停止、传输切换、Token 轮换和权限变更统一捕获持久化/服务启动异常，保留原状态并在现有错误区显示原因，不让按钮事件导致页面崩溃。
- 定向验证新增 2 项：配置与轮换凭据可被另一存储实例立即读取；对端权限提交后下一实例立即获得受限工具集合。主 Kotlin、测试源码和上述用例一次通过。
- 证据仅证明持久化与调用顺序，不代表已通过真实双进程强杀、ColorOS 后台限制、局域网或 Tailscale 端到端验收；当前新批次累计 **5/20**，未达到桌面正式包门槛。

## 会议成员身份与设置交互性能（2026-11-14，新批次 6–8/20）

- 修复会议创建对同名 A2A 远端成员仅按显示名去重导致角色丢失的问题。普通和唯一成员保留历史名称；同名远端成员在名称后追加由 Agent ID 计算的稳定短指纹，继续作为独立路由、上下文、任务和结果身份。旧会议室和历史记录不迁移、不改写。新增两个纯函数用例，分别验证同名双远端均保留和唯一远端兼容原名，均通过。
- 设置相关二级页改用约 180 ms 的纯淡入导航，目的不是改变页面设计，而是移除约 500 ms 横向位移期间顶栏、背景捕获和正文分属两层造成的闪烁/抽动。原布局、层级、尺寸、文字、材质和触控区域不变。
- 设置页首次进入不再在主线程同步读取 Root、无障碍、通知、日历、定位、悬浮窗、默认助手和保护开关。新增进程级快照，应用启动后台预热；设置与能力页共享结果，恢复或真实状态事件再刷新，并发刷新通过互斥合并，两秒内不重复查询。
- 共享行、选项胶囊和弹窗选项的按压缩放改由图形层 `Animatable` 驱动，保持原弹簧参数和缩放幅度，减少动画期间对整行内容的逐帧重组。
- 最小验证只做两项会议身份用例、一次 Debug Kotlin 编译和一次 Debug APK 构建，均成功；没有重复全量测试或运行性能基准。新 APK 为 105,336,959 bytes，SHA-256 `6676F5FADDCA528C815E9E0E9D727F964E6B53815B0C7FBEBC4B125ED399CEF5`，已通过 ADB Streamed Install 覆盖到 PHY110，应用进程短暂启动为 PID 23884。随后设备从 ADB 断开，因此只记录安装成功，不把手机视觉、持续触控、冷启动或双 A2A 同名执行记作已验证。当前新批次累计 **8/20**，未达到桌面正式包门槛。

## 会议同名远端成员稳定身份（2026-11-14，新批次 6/20）

- 创建会议室时不再对本地角色和 A2A 成员做仅按显示名的全局去重。普通成员及唯一远端成员继续使用原 `profileName`，同名远端成员会在名称后追加由其 Agent ID 计算的稳定短指纹，因此每个启用成员都能保留独立的路由、上下文、任务和结果身份。
- 冲突标签只在显示名重复时出现，未改变会议创建页的布局或交互。旧会议室及历史消息仍按原 `profileName` 读取，没有数据库迁移或历史重写；模板复制通过远端 Agent ID 重新匹配新选择。
- 定向最小验证覆盖“两个同名远端成员同时保留且名称稳定唯一”和“唯一远端成员兼容原名”两项。该结论不代表真实 A2A 网络成员端到端、双 Agent 同名执行或 ColorOS 后台行为已经通过手机验收；当前新批次累计 **6/20**。

## 设置返回停顿与首帧阻塞修复（2026-11-14）

- 设置导航不再以“无视觉转场 + 默认弹簧”冻结旧页约半秒。公共转场改为约 160 ms GPU 轻位移，并移除设置骨架内重复的 150 ms 缩放/上移动画；页面内容和视觉设计未改。
- “本地模型”不再在主线程同步读取目录、存储、电池和热状态。目录初始化与完整设备快照移到后台，首帧使用缓存或廉价快照，全部设置子页共享新的快速返回链路。
- 只执行一次 Debug Kotlin 编译和一次 Debug APK 构建，均成功；APK 已覆盖安装到 PHY110 并启动。SHA-256 `CF5D64859D75CD732F4696C8D3EFCDDAECECD3150F2F87BC577758F841DAA915`。连续交互、重影和动效手感等待用户真机验收。




## 2026-11-15：停止扩功能，集中修复公共返回与主线程负载

按照用户最新要求停止真机导航、模型/消息验证和基准，继续源码修复。公共返回从上轮装饰轻位移改为全宽跟手图形层转场；发现并修复侧滑起步时生命周期取消引发的主线程 ContentObserver 注销/重新注册。

后台整改同时覆盖共享权限快照与 Root 复用、静态绘制缓存、会话预览/日期上下文与事件恢复合并发布、子 Agent snapshot/MCP schema 后台解析、隐藏终端帧投影与锁争用、本地模型/语音目录及声纹、提供商健康记录、连接器/边界工具/SAF 权限、A2A 配置和凭据。异步化产生的 Token 初始化、终端跨会话帧及声纹跨页面写入顺序窗口已逐项处理并只读复核。

详细表单见 [36_PERFORMANCE_RETURN_PATH_2026-11-15.md](36_PERFORMANCE_RETURN_PATH_2026-11-15.md)。本次新增 performance 变体用于交付非 Debug 的 R8 优化包，应用 ID 不变并匹配当前安装签名；不更改用户界面设计，也不增加功能组计数。当前证据以源码审查、最终构建和包身份检查为限，不能宣称所有卡顿已消除。最终安装/哈希附后。


### 本轮优化包交付结果（2026-11-15）

最终源码变更后执行一次 `:app:assemblePerformance`，构建及 R8 成功，耗时 1m 27s；日志 `build/performance-final-20261115.log`。版本 **0.7.7-smooth / 2026111402**，应用 ID `io.github.zhh_lgtm.nexus`，非 Debug、非 testOnly，沿用当前开发包 Android Debug 证书以支持覆盖升级，**不是正式生产签名发布**。

APK：`app/build/outputs/apk/performance/Nexus-v0.7.7-smooth.apk`，**62,720,893 bytes**；SHA-256 **`9F46B9C77C59F0B8D1ED26033319AD1508BB82918FB94876160BDFE54759D6B6`**。v2 签名验证与 16KiB ZIP 对齐检查通过；证书 SHA-256 `f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4` 与现有 Debug 构建相同。本轮未宣称 v3 或逐库 ELF 验证。

桌面交付目录：`C:\Users\29136\Desktop\NEXA-流畅度优化-2026-11-15`，含 APK、哈希、校验 JSON、说明与逐流程整改表。交付时 ADB 设备列表为空，**尚未覆盖安装本轮优化包**。遵照最新要求，没有继续手机导航、消息、模型或基准验证；不把此前旧 Debug 的卡顿反馈当成本包测试结果。



## 2026-11-15：用户批准的设置层级与聊天菜单统一

用户确认本轮界面方案后实施，授权内容为附件/技能/运行审批菜单统一、浮动按钮按压方形灰底修复、会话条目左缘与搜索对齐，以及设置背景/卡片/图标/文字/间距分层。未借此改变聊天输入框玻璃、会话/会议执行逻辑或项目/角色编辑页设计。

- 新增入口局部设置主题，覆盖 43 个设置相关导航入口：浅色画布 `#F4F5F7`、白卡片、16dp 圆角与页边、无硬描边和极轻阴影；共享功能图标 24dp 单层单色、无光晕底块；辅助文字与分组标题 13sp，分组行使用内嵌细分割线。
- 外观、语音、模型提供商、本地模型、MCP、A2A、备份等页面通过共享包装器接入同一标准；品牌图标移除额外底板与阴影并灰度化。设置主题按导航条目隔离，返回时不覆盖保留中的聊天条目。
- 附件主菜单、技能/MCP 二级、运行审批与思考统一 ComposerPopup。保留原选择、返回、最多 3 个技能、重试及 MCP 引用行为；补充高度不足时的滚动与已选择无障碍状态。
- 新建会话、运行/审批圆徽标和上下文按钮的按压反馈按自身轮廓裁剪；会话条目仅向左加长，保留右侧留白、分支/文件夹缩进与滑动语义。
- 两名代理只读复核；已处理可变主题对象缓存、非设置页面机械间距外溢及菜单语义遗漏。初次构建因定位器 import 被误清理失败，补回后执行最终构建；没有新增单测、真机导航或性能基准。

完整规则与所有者见 [37_UI_LAYERING_REFINEMENT_2026-11-15.md](37_UI_LAYERING_REFINEMENT_2026-11-15.md)。最终包身份、哈希与覆盖安装结果附后；构建/安装不等于用户视觉验收。



### 2026-11-15 最终构建与真机覆盖安装

补回定位器 import 后，`:app:assemblePerformance` 成功，耗时 **1m 45s**；日志 `build/ui-layering-final-20261115.log`。版本 **0.7.7-smooth / 2026111501**，非 Debug、非 testOnly、R8 与资源收缩开启，应用 ID 保持 `io.github.zhh_lgtm.nexus`。

APK 大小 **62,735,153 bytes**，SHA-256 **`2B15850084ABF4BFA7D6FBFAB3E2AD1E32C83AA5268BE07DDCA81A437130CF9D`**。开发签名与手机原有包一致，v2 签名及 16KiB ZIP 对齐通过；本包不是正式生产签名发布，未声称通过 v3 或本轮逐库 ELF 核验。

已使用 ADB `install -r` 覆盖 OPPO PHY110（`1fe75f23`），返回 **Success**；安装后的系统包信息确认为 `versionCode=2026111501 / versionName=0.7.7-smooth`，保留覆盖升级，没有卸载或清数据。没有继续手机导航、消息、模型调用或性能基准；视觉与手感交给用户验收。

桌面备份：`C:\Users\29136\Desktop\NEXA-界面层级优化-2026-11-15\NEXA-v0.7.7-UI-20261115.apk`，附 SHA-256、包检查/安装 JSON 和本轮调整说明。旧的“流畅度优化”桌面文件夹保留为上一版，避免混淆。此次按用户明确要求打包安装，不计新增业务功能组。


## 2026-11-15：按用户反馈恢复彩色图标（取代此前单色规则）

用户明确要求把本轮改成黑白的图标换回彩色。仅调整两处颜色入口：`EtaUiFoundation.kt` 的设置图标恢复原有 `EtaIconTone` 分类色或调用者指定色，禁用状态仍沿用禁用色；`ProviderComponents.kt` 取消品牌图像灰度滤镜，恢复资源原色。保留 24dp 尺寸、无光晕/无底块/单层图标、设置背景/卡片、间距、材质及交互。只读审查未发现其他强制灰度入口。

一次 `:app:assemblePerformance` 成功（1m 36s），版本 `0.7.7-smooth / 2026111502`，APK 62,735,741 bytes；签名、包身份与 16KiB ZIP 对齐通过。SHA-256：`C592549F53445B455BABFC8C5BF4FCE52E7BBB71D0FEFA5514434E307E473C19`。已覆盖安装 OPPO PHY110（`1fe75f23`），安装返回 Success，系统确认新版本；未卸载或清数据，未导航手机或发送测试消息。

桌面新包：`C:/Users/29136/Desktop/NEXA-界面层级优化-2026-11-15/NEXA-v0.7.7-彩色图标恢复-20261115.apk`，附哈希与安装报告。构建日志：`build/icon-color-restore-20261115.log`；安装报告：`build/icon-color-restore-verification-20261115.json`。旧版单色包保留为历史交付，新包版本号更高。

## 2026-11-15：会话圆弧、透明语音球与会议界面细节（用户授权）

按用户本轮反馈完成七项：统一会话/会议列表行填色与边线轮廓，消除45°方向裁切不一致；聊天听写无面板、无蒙层，复用经典01/玻璃13球并将操作集中上方，partial/final在输入框同一选区更新，暂停按录音段完成后可继续；右侧详情改为下部右26%起手、40dp越阈即响应；移除会议顶部两项快捷按钮；参会者和时间线使用完整单字素且适配字号；会议管理弹窗复用设置标准；两种输入框共用同一材质，会议玻璃仅捕获非空消息背景。保留上一轮彩色图标、运行数据与业务回调。

三名代理只读定位/复核，主代理修改。修正了发送及异常后听写收尾、时间线头像遗漏和空会议多余捕获。详见 [38_CHAT_MEETING_POLISH_2026-11-15.md](38_CHAT_MEETING_POLISH_2026-11-15.md)。

一次最终优化构建成功（1m 46s）；版本 `0.7.7-smooth / 2026111503`。APK 62,737,009 bytes，SHA-256 `868BCA81824A10B785BC909A053304B942E340F7809E4DD33DD1E155A52DF79D`，包身份/签名/16KiB ZIP对齐通过。已覆盖安装 OPPO PHY110（`1fe75f23`）并读取系统版本确认，保留数据。桌面 `C:/Users/29136/Desktop/NEXA-会话会议室细节优化-2026-11-15` 含新包及记录。未运行手机导航、语音服务、模型消息或性能基准；不宣称用户视觉/手感验收已完成。

## 2026-11-15：列表拖动整理与球内语音操作（用户授权）

本轮六项已实施：会话和会议室条目中部长按，拖到置顶/共享文件夹及分组内排序并保存；移除CM与新建会话周围的整条底板及渐隐遮罩；经典01/玻璃13语音球局部精修，中央暂停/继续、底部完成，去顶部叉与操作排；会议三点菜单仅参数设置和完整导出；回到底部按钮全链路统一圆形；文件夹及底部会话/会议室切换统一会话圆弧。其他页面及执行链路未改。

保持真实日期分组，普通会话在同一日期组内排序；底部对应页签可移回普通列表。搜索保存采用全量成员，会议目录独立存储；整个移动命令串行，置顶保留最新目录。拖放只在稳定侧栏注册原生目标，回收行登记实时坐标，滚动中与DROP时重算落点，避免滚动后无法放置及快速操作覆盖。

最终构建成功（1m 34s），版本`0.7.7-smooth / 2026111504`，APK 62,743,425 bytes；SHA-256 `ABEF7F2FA6FC9188E05D32146486EE7A15D4FDAD46E93059183453B8D5B3CB49`。包身份/签名/16KiB ZIP对齐通过；OPPO PHY110（`1fe75f23`）覆盖安装Success且系统版本确认，数据保留。桌面`C:/Users/29136/Desktop/NEXA-拖动整理与语音球-2026-11-15`保存包及说明。未进行真机交互、语音或模型测试，手感与观感待用户验收。详见[39_PANE_DRAG_VOICE_POLISH_2026-11-15.md](39_PANE_DRAG_VOICE_POLISH_2026-11-15.md)。

## 2026-11-15：本轮九组已批准功能源码实施

执行目标仅限第42号报告及用户随后批准内容，详见[43号实施记录](43_APPROVED_FEATURE_IMPLEMENTATION_2026-11-15.md)。用户明确禁止顺带改其他UI，允许本批功能所需的小范围新增页面/控件；本轮不打包、不安装、不操作手机、不调用模型或MCP、不运行测试。全部源码编辑结束后只允许一次集中编译。

- 十二种会议用途接真实版本化阶段调度，按用途冻结并投递材料，独立首轮、公平讨论/定向复审、预算与失败保存；用户模型绑定优先，其他按实际启用模型能力和差异固定分配，临时Agent同样校验真实模型ID。
- 普通/计划与完全访问/智能/手动审批分离，原持久目标兼容。完全访问无应用执行审批例外；智能独立无工具审查；真实点击证据形成精确、到期可撤销偏好；Run快照和恢复不自动放宽权限。
- 外观与主题新增“消息排版”：消息字体、正文/思考、标题、代码、表格、预设、预览、原文/渲染与恢复默认；独立持久设置，不改模型原文或其他页面设计。
- 图片/音频/视频共享组件与资源预算，按格式增加动图/安全静态SVG/公式/脚注/合并表格/结构化文件预览和通用原文件兜底。
- 自有多题多选追问与原tool_result恢复接线；MCP按协商版本分别处理旧反向elicitation与最新MRTR，跨进程续传必须保有真实状态与原请求身份，未知副作用不自动重放。
- 会话状态为运行绿、未运行<=3天橙、>3且<=7天红、>7天灰；独立消息活动时间，查看和管理不改变新活动列。旧历史基线无法完整重建。
- MCP/Skill彩色稳定@引用、删除选择同步、聊天正文与浏览器长按复制已接线；引用不等于权限。

审批偏好范围、协议未支持特性、媒体设备边界、恢复限制和唯一编译结果，以43号记录末尾为准。不把源码实现当成手机验收。当前手机最近交付版本仍为0.7.7-smooth / 2026111506，本轮没有生成新APK。

本轮集中检查状态：仅执行一次 :app:compileDebugKotlin，33秒后失败；五个文件中的已报诊断已修正，按用户限制未复跑，最终编译状态未确认。日志 build/approved-feature-compile-20261115.log。没有测试、打包、安装或手机操作；本轮源码交付后停手。详见43号记录末尾。

## 2026-11-15：用户另行授权打包并覆盖手机

用户在上轮停手后明确要求“手机插上打包安装到手机”，本节取代上轮“尚未打包安装/最终编译未确认”的当前交付状态，保留上轮检查历史。未扩充功能或修改其他UI。

- versionCode递增为2026111507，版本名0.7.7-smooth，applicationId保持io.github.zhh_lgtm.nexus。
- 首次assemblePerformance发现MessageTypography路由缺标题分支；仅补该新增页面标题。随后最终assemblePerformance完整成功（2m 53s），R8与资源压缩通过；日志build/approved-feature-package-final-20261115.log。没有运行单测、真机功能或模型验证。
- APK 63,578,581 bytes，SHA-256：2D879E21BC4ED19E95681171E35F7BD186B4FCBE8BFBC0F85FCEB25B5F7F1B6C。签名v2有效，证书SHA-256 f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4，与手机现装一致；非Debug标志，但使用原开发证书，不是正式生产签名发布。ARM64及16KiB ZIP对齐通过，本轮未做逐库ELF验证。
- ADB install -r覆盖OPPO PHY110（1fe75f23）返回Success，系统确认versionCode=2026111507、versionName=0.7.7-smooth；没有卸载/清数据，没有启动或导航应用。安装记录build/approved-feature-install-20261115.json及build/approved-feature-adb-install-20261115.log。
- 桌面备份：C:/Users/29136/Desktop/NEXA-会议审批与消息展示-2026-11-15/NEXA-v0.7.7-2026111507.apk，附哈希、安装记录及功能/限制。安装通过不代表功能、两皮肤视觉或设备性能验收已通过。

完成本次打包和安装后停手。

## 2026-11-15：会话会议室局部修正与下一项方案

按用户明确授权完成顶部重复文字、会议专用鲜亮头像、运行/日期活动小点、图片适屏查看和右侧详情连续跟手。完整范围及内部文件支持表见[44号记录](44_CHAT_MEETING_VISUAL_GESTURES_2026-11-15.md)，未调整其他页面设计。

仅一次`:app:assemblePerformance`构建成功（2m 8s），版本`0.7.7-smooth / 2026111508`；APK 63,589,793 bytes，SHA-256 `D645807C10C49E5D2255AD30D79DFE7991F50A3AE872C4E968A2BB2B356C13D8`。包身份、原开发证书v2签名与16KiB ZIP对齐通过；v3未启用，未做逐库ELF或手机功能测试。桌面`C:/Users/29136/Desktop/NEXA-会话会议室局部修正-2026-11-15`保存安装包/哈希/记录。收尾时ADB无设备，本版尚未安装，上一已确认手机版本2026111507；保留数据覆盖安装待设备恢复。

用户下一项要求先分析总运行统计、单会话/会议统计与“关于”合集、固定更新源、去源代码入口及双面赞赏牌。当前仅只读分析和方案，不修改该项源码/UI，不混入1508；支付宝临时图片路径失效，未读取到其二维码。

## 2026-11-15：运行统计、监测、文件与关于（随后授权实施）

用户已确认并要求开始修改，本节取代上一节该项“仅方案”的当前状态。会话/会议详情采用运行统计、运行监测、项目文件；设置总统计采用运行统计、运行监测、管理。动态趋势按实际使用跨度小时/天/周/月显示，人民币估算费用与自动/手动汇率、同主体文件真实归集/预览/导出/分享、关于合集及微信/支付宝双面牌均已接源码。只修改本批明确授权的UI，其他页面不顺带调整。

查询按主体实时订阅，请求耗时SQL聚合而非读取全量请求，文件独立懒加载，数字无反复Crossfade。修正父子用量归一化、未知值、来源分布、自定义时间范围及计费回执合并。支持实际记录下钻；长期累计账本、远端A2A内部用量和成员精确费用归因不伪造，保留最近200条完成明细规则。

支付宝已读取桌面原图并接入资源，原图保存与安全展示裁图分离。第一次最小编译发现遗漏共享定义，补齐后最终`:app:compileDebugKotlin`成功（1m 56s；33 tasks、3执行），日志`build/metrics-about-compile-final-20261115.log`。未做测试、打包、安装或手机操作；旧2026111508 APK不含本批改动。实施边界和文件清单见[46号记录](46_METRICS_MONITORING_FILES_ABOUT_2026-11-15.md)。

## 2026-11-15 本批打包与覆盖安装完成（2026111509）

用户本次明确要求安装到手机。仅提升versionCode至2026111509并执行一次assemblePerformance：BUILD SUCCESSFUL in 2m 58s，118任务（32执行、86最新），日志build/metrics-about-package-20261115.log。本批源码已部署，取代前文当时“未打包安装”的当前状态，历史记录保留。

APK：0.7.7-smooth / 2026111509，applicationId io.github.zhh_lgtm.nexus，ARM64、非debuggable，64143837字节。原Android Debug开发证书SHA256 f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4；apksigner v2验证通过（v3未启用），zipalign -c -P 16 4通过。不是正式生产签名，也没有声称逐库ELF16KiB检查。APK SHA256：F943282C6461B021AB0F19BC9EDC196B3AA5FEC36D57799B77AE26BE36C73D7B。

ADB设备1fe75f23（OPPO PHY110）install -r返回Success；dumpsys确认versionCode=2026111509、versionName=0.7.7-smooth。未卸载、未清数据；未启动/导航应用、未发消息、未做额外功能测试或真机视觉验收。

桌面交付：C:\Users\29136\Desktop\NEXA-运行统计与关于-2026-11-15/NEXA-v0.7.7-2026111509.apk，附SHA256、安装与包检查.json、交付说明.md；旧1508包保留。功能范围及统计/文件边界以46号文档为准。

## 2026-11-15 统计、项目文件与浏览器局部精修（源码已更新）

授权依据：用户确认统计/运行明细/会议室详情的白色按钮边缘需要优化，并批准项目文件分类票夹、中央导入图标、媒体缩略图，以及会话/会议室预览、打开、保存操作和内置浏览器页面的局部细化。其他界面不在本批范围。

- 新NexaCompactControls仅由本批页面使用：13dp平滑圆角、无白色描边、浅色填充与静态柔和受光、轻阴影，40dp起高度、图标按钮44dp触区、事件触发弹性按压。Tabs使用可访问选中语义、小间距和紧凑文字；不修改原运行与审批控件本身，不增加逐按钮模糊/持续动画。
- 统计/明细及主体详情顶部切换统一；全部/工具/异常、时间周期、日历模式和相关重试/关闭操作接入同款控件。调整关键数字、图表文字层次和内块间距，不改变统计算法、人民币汇率、权限和执行行为。
- 项目文件保留导入/输出来源；中央导入图标仍可系统多选。按图片、视频、音频、文档、代码网页、其他分类，票夹类别入口和跟手横向分页，460dp有界内部LazyColumn。文件卡左侧76dp媒体缩略图，右侧名称、大小、来源和可用状态，下方统一预览/导出/分享。
- 缩略图只读取已有可访问的本地或内容URI；图片缩小、视频取实际帧、音频从PCM提取真实峰值并标明前最多12秒，不伪造波形。源大于64MiB或未知大小时预览占位；原文件操作不因此取消。后台最多两任务，成功缓存4MiB/64条、失败96条/30秒，验证与修改信息参与版本。PCM最多8MiB、解码循环最多2.5秒；原生媒体初始化与视频取帧不能被协程强制超时，尚需真实文件验收。
- 文件、图片和HTML作品的预览/打开/保存/复制/分享操作统一；浏览器顶栏地址与导航、标题/固定进度槽、错误重试、底部扫码与结束接管细化。原导航判定、菜单能力、登录、安全策略、生命周期及图片缩放功能保留。

仅运行一次:app:compileDebugKotlin：BUILD SUCCESSFUL in 12s，33任务（4执行、29最新），日志build/metrics-files-browser-refinement-20261115.log。没有额外功能测试、模型/MCP调用或手机页面导航。本批没有打包安装，versionCode仍2026111509；手机和桌面1509安装包仍为上一批运行统计与关于版本，不包含此节精修，不能宣称新UI已部署。

## 2026-11-15 项目、备份与统一归档完成并安装（2026111510）

用户授权本批三模块UI及后台整理：项目/文件工作区合并单入口，取消ETA迁移页并做好备份/恢复/自动备份，普通会话与会议室统一归档时间线/搜索/打开查看/恢复。修副本跨目录边界、普通目录返回、操作错误反馈、备份运行状态及恢复结果分类；具体实施与边界见47_PROJECT_FILES_BACKUP_ARCHIVE_2026-11-15.md。

三类最小回归20例全部通过（首次新测试缺参数，补齐后仅重跑同范围）；一次assemblePerformance成功（1m 51s）。APK0.7.7-smooth / 2026111510，SHA256 9DE5865D2B2B01C42E003ED6FB1E160BFAE91C3E8937F5943993EF0A67BA5E01，原开发证书/v2、非debuggable/ARM64、ZIP16KiB对齐通过。OPPO PHY110（1fe75f23）install -r成功，系统确认1510；未卸载清数据、未做手机功能验收。桌面交付目录：C:\Users\29136\Desktop\NEXA-项目备份归档-2026-11-15。

46号文档此前仅源码更新的统计、项目文件分类卡及浏览器精修已包含在1510，不再处于未部署状态。用户今日要求每批完成后覆盖安装；其他UI不在授权范围。不上传GitHub。

## 2026-11-15 外观排版、国内语音与项目首页（2026111512已安装）

本轮按用户授权完成备份手动/自动分区、项目页面整理、语言迁入外观与主题、固定消息排版实时预览、实际字重/字距/代码行高、外观效果及上部模式手势配置。语音常用设置直接展开，复杂连接/音色保留详情；新增Azure，再新增百度/讯飞/腾讯实际识别与播报，独立加密凭据、能力分流和备份加密检测。用户再要求项目首页重设，常用项目/文件管理与归档配置直接置于首页。细节和官方来源见48、49号记录。

1511集中26例修复后通过并已安装；1512仅四类22例一次通过，assemblePerformance成功2m15s。当前手机OPPO PHY110（1fe75f23）install -r成功、系统确认2026111512 / 0.7.7-smooth。APK SHA256 8EBE265D8A525803414046A607D2FD68C061064A1FECEB21793132B213921C4E，64245225字节；原开发证书、ARM64、非debuggable、签名/ZIP16KiB对齐通过。桌面C:/Users/29136/Desktop/NEXA-Voice-Projects-20261115。保留数据，不测试手机页面或收费模型，不上传GitHub。

小米官方确有企业AIVS SDK，用户最后明确没有SDK，1512未增加占位项；用户最新要求继续网上寻找SDK/API，调查进行中。云服务真实账号调用未验收。其他未经批准UI不得改动。

## 2026-11-15 小米MiMo已接入并安装（2026111513，当前最新）

继续官方资料调查发现小米MiMo个人/企业均可用按量公网语音API，无需小爱AIVS SDK。已新增小米MiMo服务：mimo-v2.5-asr文件转写、mimo-v2.5-tts 24kPCM流式合成、9内置音色、独立加密识别/播报Key、真实能力分流与完整备份凭据检测；官方资料及实施见50_XIAOMI_MIMO_SPEECH_2026-11-15.md。只两类10例最小回归通过，assemblePerformance一次成功1m51s。没有真实云账号调用。

手机OPPO PHY110（1fe75f23）已install -r成功，系统确认2026111513 / 0.7.7-smooth，保留数据。当前包包含1511外观排版备份/语音首页、1512项目首页重设和百度讯飞腾讯、1513小米MiMo。APK64252409字节，SHA256 8D92C8062E03243FD81534CFF59591F2221A349365998A4868FDBB2EF44E284F；原签名、ARM64、非debuggable、ZIP16KiB对齐检查通过。桌面最终交付C:/Users/29136/Desktop/NEXA-Voice-Projects-20261115，保留1511/1512。不上传GitHub，不擅改其他UI。


## 2026-11-15 系统与Linux工具箱（2026111514，已覆盖安装）

已按用户批准的51号方案合并系统与工具入口，Linux工具箱按概览/能力包/文件与服务组织；原权限和框架保护开关保留。21能力包加独立APK分析，自动依赖、真实版本、升级/修复、国内/海外/自定义优先源接入安装。新增官方Go/Rust/JDK/Maven/Gradle，Alpine保留兼容JDK25；新旧基础系统分别安装/升级。终端/Kimi绑定Root/PRoot实例，MCP新增Android/Debian/Alpine及后端绑定与Room29→30兼容迁移。初开只读缓存，当前可见已装包后台探测，窄屏动作可换行。详情见52_SYSTEM_LINUX_TOOLBOX_IMPLEMENTATION_2026-11-15.md。

最小检查44项中28通过、0失败；16项POSIX shell测试在Windows跳过，不算Linux实机安装验收。实际测试一遍38秒。编译修复一次参数命名，组合构建曾Kotlin堆不足，改本次命令8GiB编译堆后串行完成；assemblePerformance成功2m47s。没有重复跑已通过测试，不发送模型消息或做手机页面测试。

最终APK0.7.7-smooth/2026111514，64304749字节，SHA256 24E0C7D7CAEA47E773F0F46C9B7B5914EE19B5AED976EFBBBBADCC18D99BE496；原开发证书/v2、非debuggable、ARM64、ZIP16KiB对齐确认。设备后来恢复连接，OPPO PHY110（1fe75f23）install -r成功，系统确认1514，更新时间2026-11-15 18:58:30。保留数据。桌面C:/Users/29136/Desktop/NEXA-System-Linux-20261115包含APK、SHA、安装记录和说明。未上传GitHub，未修改本批以外UI。

最新版检查只读官方元数据，归档安装清单代表2026-11-15已核验正式版本，不承诺自动执行未来未核验更新。SDK/uv/Node保留旧代际目录、单入口原子替换，多入口不是完整事务。远程目标管理/任意多命名实例/项目独立工具锁文件未在本批完成；Linux实际下载、安装、系统框架Hook与服务运行仍需手机环境验收。


## 2026-11-15 Linux中文、紧凑工具与扩展能力（2026111515，已安装）

按最新授权补齐简繁脚本中文资源、工具版本显示不再直接露CLI英文证据；能力卡收紧，安装/升级/修复置于最右。精简能力卡及设置/外观/语音/备份重复解释文案，保留实际值、权限连接状态和错误。新增网络与安全/实用工具两类及10组真实按需工具，旧21保留总31，各发行版只显示支持项；包含Nmap、tcpdump/TShark、SQLMap/Nikto、取证、诊断/性能/数据库客户端/rclone/SVG。官方ARM64仓库包名已交叉核验，不自动执行扫描/抓包，不安装数据库服务。版本提取避开TShark前置警告。

新两类7项最小检查一次通过，最终assemblePerformance成功2m1s；初稿编译后收到追加需求再合并构建，未安装中间包。最终0.7.7-smooth/2026111515，64313361字节，SHA256 CBF7422BF1B7A52B72A6AB82DC76B01BBDA45A8F740448801BDEABED007199A2；原签名、非debuggable、ARM64、16KiB对齐确认。OPPO PHY110（1fe75f23）install-r成功，系统确认1515/2026-11-15 19:40:50，保留数据。没有模型调用、扫描/攻击/抓包或手机功能测试。桌面NEXA-System-Linux-20261115保留1514，新增1515。详见53_LINUX_CHINESE_COMPACT_TOOLS_2026-11-15.md。

LSPosed开启说明已核官方API102与staticScope：NEXA自带模块，兼容系统框架中启用NEXA、保留预设作用域、重启，再开数字助理/搜索手势；LSPatch不能替代系统进程注入。只发现KernelSU/LSPatch管理器，不据此冒称Root/LSPosed已生效。本批没有刷机或安装框架。新Linux工具真实下载安装与底层Root/raw socket/ptrace功能仍待设备验收。

## 2026-11-15 云API调研与数字助理入口修复（2026111516，已安装）

最新官方资料确认DeepSeek提供托管Search/Fetch、OCR、Files解析、图像理解、安全、ASR/TTS及会话标题参数；源码已经有searchDeepSeek/fetchDeepSeek，未重复实施。新增54号完整候选矩阵，补百度结构化文档/表格、阿里图像生成编辑/视频理解/生成、Embedding重排/翻译。云API本批仅研究，没有新增UI、读取凭据或收费调用。

数字助理实际绑定常量修正为Manifest中的EtaVoiceInteractionService；ASSIST桥接未ready/Session异常时复用现有浮窗，缺悬浮权限走已有Android设置；系统Session缺权限也引导授权并释放上下文。默认助手识别支持真实Service/Activity，旧按钮优先请求系统ASSISTANT角色，保留设置回退。其他UI未改。具体证据、文件与限制见55_ASSISTANT_ENTRY_RECOVERY_2026-11-15.md。

两类最小回归20项一次通过（37秒），一次assemblePerformance成功1m38s。APK0.7.7-smooth/2026111516、64317141字节，SHA256 0134B90A1AEFBA4B183C9A1CB5D9A2870A973615D4EF02A954810E3484AEB9CD，原开发签名/v2、ARM64、非debuggable、ZIP16KiB对齐确认。OPPO PHY110（1fe75f23）覆盖安装Success，系统确认1516、更新时间19:57:48，保留数据。桌面NEXA-Cloud-Assistant-20261115有APK、SHA、54/55文档和日志。

重要未完成：普通数字助理代码入口已修，未进行真机功能验收；手机1515基线assistant登记为NEXA但voice_interaction_service为空、系统No active implementation，用户应从旧按钮重新选择默认NEXA，不强写系统角色。现有导航条长按/双指长按圈选均为系统Hook；无框架时没有同等全局接收器，本批未假启用。若新增控制中心识屏搜索等替代交互，需先取得用户UI同意。未刷机、未装框架、未测手机页面或发送模型消息，不上传GitHub。


## 2026-11-15 云端工具与安装恢复（2026111517，已覆盖安装）

本批按用户明确授权将云端工具接入系统工具并配置界面，沿用现有双皮肤、卡片及设置控件；网页与语音保留原分类。实现范围、权限与成果边界以[56号交付记录](56_CLOUD_TOOLS_AND_INSTALL_RECOVERY_2026-11-15.md)为准，不再沿用54号调研文档当时“尚未实施”的状态。

- `cloud_document` / `cloud_media`共24种操作，覆盖OCR、Files文档解析问答、图片理解、百度复杂文档与表格、百炼向量化/重排/翻译/图片视频、腾讯审核及DeepSeek语音工具。供应商独立加密凭据与能力路由分离，不借用聊天Key；发现、激活、执行使用一致权限和配置检查。持久请求与媒体任务记录支持恢复查询，未知收费结果不自动重放；输入范围及成果登记按当前会话/会议与项目归属管理。
- 原语音识别与播报流程新增DeepSeek，ASR为完整文件WAV转写，TTS提供stella/tide；正常语音独立保存识别/播报凭据。解密失败保留旧密文，备份敏感字段与旧默认兼容已补。
- Linux安装、升级与修复补等待/检查/下载/校验/安装/验证/完成或失败阶段、真实进度、日志尾部与退出码反馈。字体就绪探测收紧，Alpine TeX补中文依赖与实际PDF验证，延长合理安装时限；本批未完成字体/TeX真实下载安装验收。

集中检查共56个不同用例通过。首轮55项中52项通过；Windows测试环境的AtomicFile覆盖重命名导致3项恢复检查失败，仅修测试shadow，生产Android原子写实现未改。随后只复验这3项与新增的1项原子提交/回滚用例，4项通过，未重跑其余52项。最终`:app:assemblePerformance`成功（2m46s）。本地HTTP fixture不构成真实付费API、账号权限或费用验收。

APK为`0.7.7-smooth / 2026111517`，64,415,761字节，SHA-256：`392151A87C831EE1528CB752E725C2BECC24944680414374A11E8D52E6282898`。原开发证书SHA-256 `f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4`一致，v2签名、ARM64、非debuggable及ZIP16KiB对齐通过。ADB `install -r`返回Success，OPPO PHY110（`1fe75f23`）系统确认`2026111517 / 0.7.7-smooth`；lastUpdateTime为2026-11-15 21:36:59，firstInstallTime保持2026-11-14 03:13:13。未卸载或清数据，保留原数据；未做手机功能验收。桌面交付目录为`C:/Users/29136/Desktop/NEXA-System-Tools-20261115`，最终交付状态及证据参见56号记录。

本轮最新只读设备基线为默认助手ChatGPT、Root已实际生效、兼容Hook框架尚未运行；1515时的默认助手与Root判断仅保留为历史，不用于当前结论。已备妥Vector及互斥Zygisk候选的官方件、摘要与[57号分阶段方案](57_SYSTEM_FRAMEWORK_PREPARATION_2026-11-15.md)，没有安装框架、启用模块或重启；框架及原手势实机兼容仍待验收。未上传GitHub，未扩改本批以外界面。

## 2026-11-15 默认助理、电源入口与图片预算修复（1518/1519已安装）

1518完成NEXA默认助理的明确选择、可逆OEM目标、按用户持久消费及本包更新恢复，导航条/双指搜索转现有NEXA入口。用户已经确认电源键唤起成功。已安装Zygisk Next1.5.3及Vector2.2/API102，作用域仅system/SystemUI/ColorDirect；标准角色命令已验证小布与NEXA往返。软件内往返、两种搜索手势未分别验收。再次soft-reboot后框架CLI连接异常仍待查，不能凭用户唤起成功掩盖，也不能凭CLI异常否定实际助理成功。当前临时Root保留，未解锁、未刷写。

用户反馈“上下文压缩失败”后，1519定位并修复自动截图Base64被误计文本token，以及没有完整历史时自动发起压缩的问题；真实文本与多图预算保留，实际图片请求不变，手动压缩与溢出force继续返回真实失败。确认助理对话执行和存储均属NEXA，完成的运行导入assistant-会话；只读快照查到3条助理记录，诊断副本已删除。源码没有改动任何UI，未篡改手机数据库。无法从统一提示证明该次失败唯一原因，不宣称所有压缩问题已解决。

两类最小回归合计28项一轮通过（17秒），1519 assemblePerformance成功（1分44秒）。APK 0.7.7-smooth/2026111519，64,416,865字节，SHA256 B74F2B30CE2C86EE48D42A4B37D89B6C7787D81DD5CFD16BE2261C3B3727D65B；原证书/v2、ARM64、非debuggable、16KiB对齐核验通过。OPPO PHY110（1fe75f23）install-r成功，系统确认1519及23:15:02更新时间，firstInstallTime保持2026-11-14 03:13:13，VIS仍NEXA。没有重启手机或发送真机模型请求，修复效果待使用验证。桌面交付NEXA-Assistant-Takeover-20261115，详情及剩余边界见58号记录。没有Git操作或上传。

## 2026-11-15/16 Linux普通模式、云端网页与记忆自学习（1520/1521已交付）

实际复现普通模式Python和Node安装失败，根因是URL模板占位符正则在Android ICU下报错，尚未下载归档；改成确定性扫描，来源白名单/HTTPS/固定摘要不变。1520手机Debian PRoot两项实际安装成功，Python 3.15.2及Node/npm真实探测、ready标记和界面确认；不是换源、重装Linux或Root绕过。1521补工具版本缓存缺shell变量边界，保留多工具标签和版本。

云端网页归类1520仅底部链接未达到用户要求；1521将“网页搜索与读取”作为云端能力分类首项，默认显示搜索，支持读取及内置/DeepSeek/自定义配置内联复用。保存按钮只写当前所有者，Cloud/Web各有快照，切换保留草稿，离开检查两者，不变更工具执行或让OCR总开关控制网页。真机确认搜索、读取两页及无待保存状态，最后停留搜索，没有编辑密钥或执行云请求。

记忆后台已修四项：layer错误覆盖显式scope、分层追加丢前言、单字经验查出全量、本地模型学习经验错误依赖记忆总开关。现有核心章节、作用域、结构化审核与角色隔离继续复用。自学习目前为任务开始注入全局active经验、默认2400字符，不是模型训练；学习规则本身尚无项目作用域。分层增强、完整条目预算、相关性检索与使用证据均见[59号报告](59_LINUX_WEB_MEMORY_REVIEW_2026-11-15.md)，尚未冒称实现，管理界面未擅改。

检查共83个不同用例最终通过：原74项一次通过；追加首轮6项中5项通过，网页3项通过；唯一新shell执行用例在Windows引用/临时目录环境失败后只修测试并单独复验通过5秒，未重跑已过项。1521一次assemblePerformance成功1m41s。APK `0.7.7-smooth / 2026111521`、64,423,213字节、SHA256 `6E17917E3C87BD3B1B9B71D97C591FF2763C83DCBDF2E59FD3F28D8AB8E9B1DE`，原证书/v2、ARM64、非debuggable、ZIP16KiB对齐核验通过。OPPO PHY110（1fe75f23）覆盖安装Success，系统确认1521及2026-11-15 23:58:47更新，firstInstallTime保持2026-11-14 03:13:13。桌面交付`C:/Users/29136/Desktop/NEXA-Linux-Memory-20261115`含两版APK、报告及证据；没有Git变更、上传、重启或付费请求。

## 2026-11-16 记忆、角色指令、会议Agent与软重启（2026111601，已覆盖安装）

用户在60号调研后批准本批四条链路。实际范围及剩余边界以[61号实施记录](61_MEMORY_BEHAVIOR_AGENT_IMPLEMENTATION_2026-11-16.md)为准；仅调整这些相关界面，不扩改其他UI。

- 记忆文档可查看全局、项目、会话、任务、会议、角色六类及真实对象，核心等章节可编辑，保留原文、草稿和版本冲突保护。主任务与子任务按合法范围、任务相关性、去重及共享总预算整条选择；核心少量常驻，其他内容不再无差别填满预算。角色继续独立剧情记忆。
- 自学习进入经验库，保留候选审核、确认、生效及版本回滚。候选/规则增加scope，Learning数据库4→5兼容迁移，旧条目仍为global；完整备份清单修正真实库版本并保留scope。没有自动猜测归属、迁移拆分旧全局内容，也不是训练模型。
- 角色与指令集中为行为方案，完整角色卡、人设、开场白、世界书、剧情记忆、头像、收藏及PNG/JSON导入导出等原功能保留。新增项目范围、普通/会议各阶段/子Agent/自动任务指令与追加/替换/停用；提供真实Resolver组装预览及Provider转换前的来源、位置、阶段、预算和公开工具记录，不保存输入或提示词正文。工具执行仍受真实边界与审批控制。
- 内置1主持和12专业岗位，保存全局默认主持，新会议默认使用；专家人工多选，临时主持职责仅保存在当前会议。旧会议明确主持与模型绑定不覆盖；主持职责接入各阶段，专业岗位使用不同工具/预算/能力偏好，子任务按自己的query重新选择合法记忆。
- 工具功能新增一次性软重启开关，确认并获Root后只调用受支持的KernelSU soft-reboot；不支持时说明原因，不降级整机reboot。本批没有执行真实重启。

集中相关检查25类170项首轮157通过/13失败；仅复验失败项及一个新增负例，最终按类和方法合并171个不同用例全部通过、无跳过。修复真实个人资料岗位工具遗漏、Prompt编辑返回滚动状态和记忆预算说明；各阶段XML保留。一次最终assemblePerformance成功1分58秒。没有重跑全套或调用真实付费模型。

APK为0.7.7-smooth / 2026111601，64,467,257字节，SHA-256为C74E25F9B0A97D6DAB85A820B4AE184FEA131807AA479514AC33C9377360F01E。原开发证书一致，v2签名、非debuggable、ARM64和ZIP16KiB对齐核验通过。OPPO PHY110（1fe75f23）install-r成功，系统确认1601及2026-11-16 01:01:01更新时间，firstInstallTime保持2026-11-14 03:13:13；未卸载、清数据或重启。启动返回Status ok、WARM、TotalTime235ms；一次UI树采集未成功，不当作全部手机页面验收。

桌面交付C:/Users/29136/Desktop/NEXA-Memory-Agents-20261116，附APK、摘要、60/61及更新的31/22文档、检查XML、构建/签名/安装/系统版本/启动证据和本批宿主截图。没有Git写操作或上传。长期未完项包括持久化记忆使用审计、向量检索、经验环境/过期适用模型、统一完整装配计划及普通子Agent跨重启resume；不将60号研究的全部建议标为已实现。本地紧凑模型世界书标记的既有静态风险未在本批专项修复。

## 2026-11-16 增量1602：能力中心审计与调用链修复

0.7.7-smooth / 2026111602 已保留数据覆盖安装至 OPPO PHY110。新增只读模型工具平台清单，移除能力中心重复的语音/本地模型/提示词设置入口；其他产品界面未顺带修改。修复 Skill 常用 YAML/工具声明兼容、长文分页和静态资源到 Linux 工作区桥接；MCP 发现改为2并发并记录失败，完整目录不再按64项静默截断，16激活槽支持明确替换；修复 A2A 明确任务地址、流式首句柄/续接/结果与凭证属主，以及父记录 REPLACE 误删关联任务/事件；DSH 订阅和鉴权恢复、REST/RPC/GraphQL/OpenAPI 结果真实性也已修复。

首轮186项，3项失败定位修正；仅复验3个失败和1个受目录变更影响的检查，4项通过。最终180项通过，6项Windows符号链接条件跳过；没有全量重测。一次最终优化包构建、包身份/ARM64/非debuggable/v2签名/ZIP16KiB对齐通过。APK SHA-256为 8B6326E2F3D42C83D74891AEB38AB2DAED15F36B2F25BD23BEE17CC2E1B488CC，桌面 NEXA-Capability-Audit-20261116。

电脑Hermes的LAN/Tailscale卡片均HTTP200且均公布Tailscale任务地址；已提供两种明确配置方式，但手机当时无局域网地址、两路请求均超时，未发送真实模型任务，因此不宣称手机到Hermes已验收。DSH模型仍只读、跨产品minApp限制、厂商Skill专有扩展等实际边界与完整模块矩阵见[62号审计记录](62_CAPABILITY_CENTER_AUDIT_2026-11-16.md)。


## 2026-11-16 增量1603：上下文、模型、记忆及指定界面

本批用户批准十四项并追加记忆读取失败：完善普通会话/会议压缩及重复扩额，允许主持人单独建会并受控招募；修复空处理模型JSON导致的选择崩溃、记忆目录惰性初始化与备份恢复；增加MiniCPM4纯文本GGUF核验与本地语音修复、在线Images-compatible生图、A2A精确工具授权和运行时撤权；重组已授权的角色指令/能力导航/会议创建，调整软件管家键盘跟随，加入自适应活动热力格、Token圆环、复合语音波形及按问题定位的只读分页时间轴。没有顺带改其他UI。

37个定向类合并277个唯一用例：276通过、0未关闭失败、1因缺外置官方TTS权重包跳过；实际失败修复后只复跑受影响类及最后单项。浅/深主题390dp实际组件原生渲染已查看。优化包0.7.7-smooth / 2026111603构建成功，64,551,113字节，SHA-256 `59DDC5CB4CF53BBD6702C862FD2724D82613D08F6599F0F45930DE35D39578F9`；身份、非debuggable、ARM64、v2、ZIP16KiB核验通过，与1602归档开发证书指纹一致，v3=false。桌面 `NEXA-Runtime-UI-20261116` 有包与报告。手机未连接，本批没有安装或真实模型/手机测试，不清数据、不重启。确切范围、来源和限制见[63号记录](63_RUNTIME_MODELS_MEMORY_UI_2026-11-16.md)。
