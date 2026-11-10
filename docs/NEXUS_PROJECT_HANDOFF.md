# Nexus 项目完整开发档案与交接指南

> 快照日期：2026-11-10（Asia/Shanghai）  
> 上游来源：Eta 4.11.8（历史基线提交 `8f005e3`）  
> 当前产品：Nexus  
> 当前仓库：<https://github.com/147258369zhh-lgtm/NEW-NEXUS.git>  
> Android applicationId：`io.github.zhh_lgtm.nexus`  
> 当前版本：`0.1.0`，versionCode `1`，minSdk `33`，targetSdk `37`

本文是 Nexus 的权威总交接入口。它记录项目从 ETA 转到 Nexus 的原因、产品要求、参考项目、已实施能力、验证证据、未完成事项、风险、发布边界和继续开发方式。

更细的阶段设计见 `docs/NEXUS_PORTABLE_MASTER_PLAN.md` 和 `docs/nexus/`；源码和测试是最终事实源。

## 1. 当前结论

- Nexus 已作为独立 Android 应用和独立 Git 仓库建立。
- 旧 Eta 与 Nexus 使用不同 applicationId、权限 authority、OAuth scheme 和数据目录，可以并存安装。
- 旧 `D:\AI\ETA` 是只读历史基线；旧 `D:\AI\Nexus` 不是本项目，不能混用。
- 当前源码已通过架构检查、Debug Kotlin 编译、目标测试和一次 Release 构建验证。
- 当前源码可以生成可安装的 debug APK；正式签名 APK 尚未生成，因为当前环境没有 release keystore。
- 项目不再是 ETA 的常规分支同步模式。后续只独立评估并手工移植必要的安全修复或严重兼容修复。
- DSH 已完成清单与槽位预检，但 DSH Host、Effect/View/Invoke 执行桥和全部 UI 插槽尚未完成，稳定版不得宣称“完整兼容 DSH”。

## 2. 仓库与模块

| 路径/模块 | 作用 |
|---|---|
| `app/` | Android 应用、Runtime、UI、平台能力、A2A、MCP、语音、终端等 |
| `application-api/` | 稳定的应用服务合同、工作流、Host、设置、语音、迁移和资源策略 |
| `kernel-api/` | 不依赖 Android/Compose/Room/Miuix 的纯 Kotlin 核心合同 |
| `legacy/pre-upstream-20261105/` | 转入当前上游前保留的 ETA 代码快照，只用于迁移和差异参考 |
| `docs/nexus/` | 阶段设计、依赖审计、验收记录和差距清单 |
| `docs/NEXUS_PORTABLE_MASTER_PLAN.md` | 可在其他电脑继续开发的单文件总计划 |
| `scripts/` | 身份验证、依赖审计、构建和工具模拟脚本 |
| `.github/` | Android 构建、PR 检查和发布流程说明 |

当前约 962 个主源码 Kotlin 文件、498 个测试文件、122 个文档文件。数量只表示规模，不代表全部已通过真机验收。

## 3. 为什么从 ETA 转成 Nexus

原 Eta 已积累完整 Android Agent 能力，但继续叠加会导致：

- Runtime、UI、Data、Fork、Host 之间循环依赖。
- 会话、会议室、Subagent、自动化、A2A 的执行事实源不统一。
- 工具数量膨胀，模型每轮收到过多 Schema，增加 token 和误调用概率。
- 第三方插件、Agent Skills、MCP、A2A 和 DSH 的协议类型直接污染内核。
- UI 与执行状态耦合，崩溃恢复、后台执行和跨 Host 延续困难。

Nexus 的目标是保留已有产品资产，同时逐步建立：

1. Session / Run / Turn / Item 分层。
2. 唯一会话身份、影子事件账本和后续可恢复 checkpoint。
3. Kernel、Application API、Host、Feature UI 的明确依赖方向。
4. 按需工具和 Skill 加载。
5. 工具暴露与执行共用一套权限和 effect 策略。
6. Classic/Omni 两套 UI 共享领域能力。
7. 本地优先、A2A、MCP、Connector、DSH、Linux 统一 Host 适配。

## 4. 参考项目与采纳边界

| 来源 | 采纳内容 | 边界 |
|---|---|---|
| Eta 4.11.8 | Runtime、会话、会议室、Subagent、A2A、MCP、Skills、语音、Linux、备份 | 只作历史基线和迁移来源 |
| Eta-por | 数据备份、上下文压缩、Linux 工具、记忆自学习、灵屏、子 Agent、提示词边界、统计、分支、文件管理 | 按 Nexus 合同重做 |
| OmniBot | 第二套 UI Profile、轨迹、外观、终端、MCP/Skills 思路 | Classic 与 Omni 功能保持一致 |
| Operit / Operit2 | 曾用于 ToolPkg 与沙盒包调研 | 用户已决定停止该模块，不继续扩展 |
| dsh-Go | 插件槽位、plugin-kit、本机/远程 Host、原生 UI 适配思路 | 必须遵守 AGPL/MIT 和再发布注意事项 |
| Hermes | 记忆、自学习、归档和审核式沉淀 | 垃圾经验不得未经管家和用户审核直接沉淀 |
| Codex / Pi / 主流 Agent | Goal、Plan、事件时间线、能力目录、恢复与权限模式 | 只吸收可验证架构 |
| EdgeGallery | 本地视觉/语音模型和设备侧部署参考 | 进入统一模型资源策略 |
| MCP / A2A / Agent Skills | 生态协议 | 通过 Host Adapter 接入内核 |

DSH Go 仓库说明其 `sdk/plugin-kit` 为 MIT，主体项目为 AGPL-3.0，并在 README 中要求衍生发布前列明来源和遵守授权。未来正式发布 DSH 兼容能力前必须复核许可证、名称/标识和再发布条款。

## 5. 已实现的产品与架构能力

### 5.1 产品身份与安装隔离

- 名称：Nexus。
- applicationId：`io.github.zhh_lgtm.nexus`。
- 独立权限 authority、无障碍健康 Provider、OAuth callback scheme。
- 不允许 Nexus 自动读取旧 Eta 私有数据，迁移必须走用户选择并校验的导出包。
- 旧 Eta 与 Nexus 可并存安装。

### 5.2 核心合同

- `kernel-api` 包含 Session、Run、Turn、Item、Task、Goal、Plan、Artifact、Capability、Settings、Migration 和 Presentation 合同。
- `application-api` 包含 Workflow、Settings Control Plane、Host Transport、Voice、Resource、Migration、Presentation Profile 等应用层合同。
- Gradle 架构门禁禁止 Runtime/Data/Fork 直接依赖 UI，并禁止 `kernel-api` 依赖 Android/Compose/Room/Miuix。

### 5.3 执行账本与指标

- `AgentRuntimeSession` 已接入非阻塞 JSONL 影子事件账本。
- 使用有界队列和单 writer；写失败或队列溢出不影响 Agent。
- 不写正文、工具参数、推理、Token 或文件内容。
- 支持 30 天/最多 200 Run retention。
- 支持 `readTimeline(runId, afterSequence)` 调试读取。
- 现有 Metrics、Checkpoint、UI Event 和恢复流程仍保留；账本尚未切换为唯一恢复事实源。

### 5.4 按需工具、能力和设置控制面

- 主 Agent Loop 和 Subagent Loop 首轮只暴露 `tool_search`、`tool_describe`、`tool_activate`。
- 激活后下一轮才注入完整 Schema；单 Run 最多保持 16 个领域工具。
- 未知工具默认 `HIGH_RISK`。
- Settings Control Plane 支持本地 Agent、软件管家、本地用户和远端 Agent 的差异权限。
- Remote Agent 只能提交设置请求，不能读取敏感设置或直接回滚。
- 设置审计保存 before/after 和 auditId。
- 当前模型、备用模型、记忆、外观、MCP 服务、MCP 工具、Skill 和自学习设置支持真实回滚。
- 软件管家可通过 `settings.appearance` 直接操作皮肤、主题、配色、字体、模糊、手势和缩放。

### 5.5 Goal、Plan 与会话记事本

- Goal 具有 DRAFT、计划批准、运行、验证、等待用户、预算暂停、完成、失败和取消状态。
- Goal 完成必须由验证和用户确认。
- Plan 使用 revision、依赖、能力要求、Effect、成本和验证方式。
- 工作流模式按会话持久化，重启后恢复。
- 输入框上方有会话/计划/目标切换和可折叠 Goal/Plan 卡。
- 会话记事本属于当前会话附件，不自动进入长期记忆。
- 记事本支持顶部下拉、半屏/全屏、900ms 自动 revision 保存。
- 支持把记事本显式交给 Agent 整理、总结、生成计划或执行低风险待办。

### 5.6 普通会话、会议室和 Subagent

- 普通会话与会议室保留独立业务控制器，共享消息、输入、附件、文件、权限和渲染基础组件。
- 会议室采用“主持人监督器 + Agent 作为工具”拓扑。
- 单个 `@Agent` 直接执行；多个 `@Agent` 并行执行独立任务；无 `@` 由主持人分派。
- 默认最多 4 个并行参与者，按资源预算自动降低。
- 参与者只取得自己的任务和必要上下文，主持人负责汇总。
- Subagent 可独立设置模型、权限、工具、上下文和预算；父子 Run 独立取消和验证。

### 5.7 Host、A2A、MCP 与 Connector

- 新增统一 `HostTransport`：`start`、`stop`、`currentAddress`、`status`。
- A2A 已实现 LAN 直连与外部 Tailscale 两种 Transport。
- A2A 支持标准 `message/send` 等名称和 PascalCase 别名。
- Agent Card URL 按显式配置或请求 Host 动态生成。
- A2A 独立前台服务、Bearer Token、ToolAccess 白档和入站工具白名单已存在。
- MCP 已实现 stdio/HTTP、进程管理、诊断、工具治理和 HostAdapter 投影。
- Connector SDK 已覆盖 REST/OpenAPI、JSON-RPC、WebSocket、GraphQL、SSE、Unix Socket、Shell/STDIO、Android 本地连接等。
- DSH plugin-kit protocol v1 清单校验已完成；Host 执行桥未完成。

### 5.8 内容渲染

- `ContentPart` 覆盖 Text、Markdown、Math、Code、Image、Audio、File、ToolTrace、PlanCard、UserInput 和 Approval。
- `NexusContentPartRenderer` 提供统一渲染。
- 音频使用异步 MediaPlayer 准备，避免阻塞主线程。
- `AgentMessageUi` 可携带结构化 ContentPart，普通会话和会议室共享渲染路径。
- 现有 Markdown 渲染支持 GFM、公式、代码、代码高亮、表格和图片安全加载。

### 5.9 UI 与外观

- Classic 与 Omni Presentation Profile 已接入全 UI 树。
- 支持主题、Monet、配色、皮肤、纯黑、模糊、顶栏模糊、预测性返回、横移返回和界面缩放。
- 新增全局字体：系统、无衬线、衬线、等宽、JetBrains Mono。
- 终端和代码区域保持等宽字体。
- 会话栏支持文件夹、折叠、置顶、归档、分支和拖入文件夹。
- 主界面支持会话/会议室切换与多方向抽屉手势。

### 5.10 备份、归档与迁移

- 支持完整/分类备份、压缩、自动备份、加密密码和保留份数。
- 完整恢复使用 staging、校验、回滚和重启提交。
- 迁移到 Nexus 的专用向导已进入“迁移与备份”页，复用 SAF、加密导出、清单/密码/空间校验和恢复。
- 会话可自动归档；归档保留导出能力。
- 凭据只有加密迁移包可以包含，跨设备时使用新设备 Keystore 重新包裹。

### 5.11 语音、本地模型、Linux 与终端

- canonical VoiceStateMachine 覆盖 IDLE、LISTENING、TRANSCRIBING、THINKING、SPEAKING、INTERRUPTED、FAILED。
- AudioOwner 和资源优先级策略已实现。
- 现有云端/本地 ASR、TTS、播放、打断和 Overlay 继续保留。
- 本地模型支持 LiteRT-LM、GGUF、MNN、ASR/TTS/视觉/Embedding 等分类与按需加载。
- Linux Host 支持 Debian/Alpine、PROOT，Root 仅在显式授权后可用。
- 终端支持 PTY、命令、后台 Job 和 Daemon，输出有界并支持停止。

### 5.12 记忆和自学习

- 记忆按会话 → 项目 → 全局分层。
- 自动学习产生候选，不直接成为稳定规则。
- 候选经过垃圾/敏感信息过滤、冲突检测、软件管家审核、用户批准、试用和版本化。
- 规则支持回滚并保留历史版本。
- 夜间整理支持充电、息屏、网络等条件；低风险提醒可自动建立，计划和重要操作需确认。

## 6. 关键安全和权限边界

- 第三方插件不得在 Compose/UI 主进程直接执行。
- 工具暴露、执行、A2A 白名单和设置审计必须使用同一权限语义。
- Root、终端、GUI、自动化、Subagent、插件安装和外部提交属于受限或高危能力。
- A2A 对端不能直接修改本机设置或自行批准高危工具。
- Tailnet 自动发现还需 Tailnet 地址、Agent Card 和 Bearer Token 匹配。
- 密钥不可读但允许授权写入；备份中的凭据必须加密。
- 远端审批只显示任务名和等待类型；在 Nexus 中展示命令、路径、影响、副作用和预算。
- 自动恢复只重放只读、幂等或可验证操作；其他操作等待确认。

## 7. 当前验证与构建证据

已在当前源码上执行：

```text
checkNexusArchitecture
:app:compileDebugKotlin
:app:testDebugUnitTest
--tests io.github.mangi.eta.agent.control.SettingsRollbackCapabilityTest
--tests io.github.mangi.eta.agent.connector.DshPluginManifestValidatorTest
```

以上均通过。

最近一次 Debug APK 构建：

- 文件：`Nexus-v0.1.0-debug.apk`
- 大小：81,832,396 字节
- SHA-256：`DF72B299E97EA2E66D48252B220AF2E32B8DBFE3013FA0A78EB5419BD188A856`
- 包名：`io.github.zhh_lgtm.nexus`
- 签名：Android Debug，APK v2 校验通过
- 用途：真机测试，不是正式发布签名包

此前 Release 构建也已通过，但当时产物未配置 release signingConfig，`apksigner verify` 返回未签名。不能把“Release 构建成功”理解为“正式发布包可安装”。

当前 import 审计：3,315 条记录。

- same-layer：1,462
- cross-layer-review：945
- UI 直连基线债务：840
- forbidden reverse dependency：68
- `data -> ui`：0
- `agent -> ui`：17
- `data -> agent`：37，较初版 53 已下降

## 8. 尚未完成的事项

### P0：发布前必须闭环

- 正式 release keystore、签名策略和升级连续性。
- 真机全量回归：后台、息屏、锁屏、最近任务划掉、Doze、重启、服务恢复。
- A2A 两台真实节点双向联调、Token 撤销、地址变化和锁屏回复。
- 迁移包跨设备恢复与 Keystore 重新包裹。
- Classic/Omni 双 UI 视觉和功能一致性验收。

### P1：架构主事实源

- Execution Shadow Ledger 尚未驱动恢复或替代 Metrics/Checkpoint。
- 需要至少 20 Run 的 Ledger、Metrics、Checkpoint 和 UI Item 零差异对照后再切换。
- Workflow 运行中进程死亡后的 Goal 自动恢复仍依赖旧 Checkpoint。

### P1：DSH 兼容

- 已完成 protocol v1 清单预检。
- 未完成 DSH Host、Effect、View、Invoke、流式事件、权限、素材 URL、媒体/语音/悬浮层插槽。
- 未完成全部 native slot 与隔离 WebView fallback。
- 在全部插槽通过前不得发布“完整 DSH 兼容”声明。

### P1：模块化

- `agent -> ui` 剩余 17 处，集中在语音、Overlay 和虚拟屏表现层。
- `data -> agent` 剩余 37 处，集中在 Provider、角色卡、备份、模型目录和部分存储服务。
- `data -> fork` 仍有依赖。
- 建议下一步拆出 `presentation-api`、`provider-api`、`host-*`，而不是简单移动包名。

### P2：产品完善

- 全双工语音和音频仲裁的真机长时验证。
- 更多设置能力的显式 snapshot/rollback。
- DSH/Cordis 全部插件插槽。
- 更细分字体区域和外观 Profile 的完整页面迁移。
- 计划审批、用户输入和审批卡在各 Host 的统一历史投影。
- Linux Host 多发行版、资源限制和项目挂载的端到端验收。

## 9. 已知风险与注意事项

1. 不得把 Android Debug 证书当成正式发布证书。
2. 不得为了签名冲突直接覆盖安装；正式包必须保持连续签名身份。
3. 不得读取旧 Eta 私有目录；只能导入用户明确选择的导出包。
4. 不得删除或重写未经验证的历史数据库；迁移前必须备份和 staging 校验。
5. 不得让模型审批 Root、终端、外部提交、删除或其他高危操作。
6. 不得把敏感 Token、密码、私钥或明文凭据提交到仓库、日志或诊断包。
7. 不得让 DSH 或第三方插件代码直接运行在 UI 主进程。
8. 不得把完整模型、Linux rootfs 和 DSH Host 二进制默认塞入普通备份。
9. 不得声称“编译通过”等于“真机功能通过”。
10. 发布前必须复核 Eta-por、OmniBot、dsh-Go 等来源的许可证、署名和衍生发布要求。
11. Windows 上构建大项目时，Gradle 和 Kotlin daemon 堆叠加可能导致系统 OOM；建议 Gradle 1.5–2 GB、Kotlin 1.5–2.5 GB，按机器内存调整。
12. `legacy/` 是迁移参考，不应作为日常修改入口。

## 10. 继续开发顺序

1. 先读根 `AGENTS.md`、本文件、`docs/NEXUS_PORTABLE_MASTER_PLAN.md`。
2. 检查当前 commit、分支和构建环境，不在未知修改上强行覆盖。
3. 先跑架构检查与目标编译，再修改。
4. P0 优先完成签名、真机后台、A2A、迁移和双 UI 验收。
5. P1 完成 20 Run Ledger 对照后，再切换恢复主事实源。
6. DSH 兼容按 Host → Effect/Invoke → View → 全插槽逐项推进。
7. 每次模块迁移先建立稳定 DTO/接口，再移动实现；禁止仅为降低 import 计数而换包名。
8. 新功能必须同时定义权限、Effect、审计、恢复和测试策略。
9. 普通改动做一次目标编译和目标测试；涉及数据库、备份、签名或多进程时增加专门验收。

## 11. 任意电脑恢复开发

```powershell
git clone https://github.com/147258369zhh-lgtm/NEW-NEXUS.git
cd NEW-NEXUS
```

配置本机 Android SDK 的 `local.properties`，然后：

```powershell
.\gradlew.bat checkNexusArchitecture :app:compileDebugKotlin
```

需要签名 Release 时设置：

```text
NEXUS_RELEASE_STORE_FILE
NEXUS_RELEASE_STORE_PASSWORD
NEXUS_RELEASE_KEY_ALIAS
NEXUS_RELEASE_KEY_PASSWORD
```

任何 keystore、密码和 API Token 都不得提交。

## 12. 文档导航

- 总计划：`docs/NEXUS_PORTABLE_MASTER_PLAN.md`
- 阶段索引：`docs/nexus/README.md`
- 身份与基线：`docs/nexus/10_PHASE0_ACCEPTANCE.md`
- 执行边界：`docs/nexus/11_PHASE2_APPLICATION_BOUNDARIES.md`
- 能力与设置：`docs/nexus/12_PHASE3_CAPABILITY_AND_SETTINGS.md`
- Goal/Plan/记事本：`docs/nexus/13_PHASE4_GOAL_PLAN_NOTEPAD.md`
- Host 与 Artifact：`docs/nexus/14_PHASE5_HOST_AND_ARTIFACTS.md`
- 统一渲染与 UI：`docs/nexus/15_PHASE6_PRESENTATION_AND_UI.md`
- 影子账本：`docs/nexus/16_PHASE1_SHADOW_LEDGER_IMPLEMENTED.md`
- 语音、迁移与发布：`docs/nexus/17_PHASE7_8_VOICE_MIGRATION_RELEASE.md`
- 计划差距：`docs/nexus/18_GAP_CLOSURE_2026-11-10.md`
- 本地继续收口：`docs/nexus/19_LOCAL_GAP_CONTINUATION_2026-11-10.md`
- 逐文件 import 审计：`docs/nexus/07_BASELINE_IMPORTS.csv`

## 13. Git 与发布记录

- 本项目已明确脱离 Eta 常规同步。
- 当前 Push 目标：`https://github.com/147258369zhh-lgtm/NEW-NEXUS.git`。
- 不允许 force-push、改写历史或绕过保护分支。
- Debug APK 和正式 APK 必须清楚区分，不能混放或使用相同发布说明。
- 本文档会在每次稳定阶段结束后更新“已完成/未完成/验证证据”。
