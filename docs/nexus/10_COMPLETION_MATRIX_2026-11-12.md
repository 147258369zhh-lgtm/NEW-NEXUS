# Nexus V6 完成与未完成矩阵

日期：2026-11-12  
口径：源码实现、生产接线、最小验证、真实设备/外部服务分别判断。`PARTIAL` 不等于整体完成，`已实现` 不等于长期真机通过。

## 交付状态

| 项目 | 状态 | 证据 | 未完成/限制 |
|---|---|---|---|
| 0.6.9 正式包 | 已交付 | 桌面 APK、签名与发布核验 | 未安装手机，未做 Android 16/17 长期验收 |
| 10/10 统一批次 | 已交付 | 计划与滚动进度 | 10 项是最近累计门槛，不是 D01-D14 全完成 |
| 源码回滚 | 已建立 | `NEW-NEXUS-main_v6.9-source-recovery_0.6.9_20261112-final.zip` | GitHub 同步需处理本机凭据/网络 |

## D01-D14

| 编号 | 当前状态 | 已落实 | 明确未完成 |
|---|---|---|---|
| D01 UI | PARTIAL | Classic/Omni/Aurora 基础、能力中心/统计风格、会话设置入口、若干深层 UI | 全软件二至四级页面、弹窗、主题、字体、图标、间距、动效和真机可访问性逐页审计 |
| D02 模型 | PARTIAL | 提供商/模型注册、能力快照、主备/用途/压缩模型、本地模型基础、成本账本 | 全部真实供应商契约、HTTP 400 非工具错误、所有入口选择一致、本地模型真实质量 |
| D03 执行恢复 | PARTIAL | Run/Item/checkpoint、预算、工具 Gate、输入恢复、审批重启对账、子 Agent（默认每会话 3，全局 6 准入） | 持久事件等待、自动任务/A2A 续跑、未知外部副作用恢复、完整恢复策略 |
| D04 文件/Linux/媒体 | PARTIAL | Markdown/公式/图片/视频/音频/附件、文件根、Artifact Ledger、Linux 首链、终端产物回执 | 全媒体真机、真实 Linux 文件任务、后台取消/daemon 清理、长期存储边界 |
| D05 记忆/自学习 | PARTIAL | 分层记忆、文档返回修复、自动提取/去重/冲突基础、空闲学习基础 | 混合检索质量、长期记忆策略、冲突确认、自学习质量与回滚实测 |
| D06 Skill | PARTIAL+ | 自动 Skill 基础、多厂商规则兼容、GitHub/GitLab/Gitee、HTTPS 目录、多镜像、ZIP/文件夹、版本/回滚/来源 | 真实外部市场与镜像、依赖自动处理、真实模型提炼质量、多 Skill 协同和卸载全矩阵 |
| D07 自动任务 | PARTIAL | 时间/设备/网络/蓝牙/位置/文件/任务链触发和状态历史 | ColorOS 后台存活、真实端到端任务、漏跑恢复提醒、并发/超时/重试全矩阵 |
| D08 权限/设置 | PARTIAL+ | 三级权限、资金硬边界、Root 按模式、审批队列/恢复/统计、软件控制面、自定义源设置 | 所有设置 Descriptor/Grant 审计、自学习锁定全链、撤销/回滚全量核对 |
| D09 手机操作 | PARTIAL | 无障碍健康/恢复、虚拟屏、屏幕工具、系统能力基础 | OPPO/ColorOS 17 真机权限持久、连续操作、错误恢复、多开隔离 |
| D10 本地模型/语音 | PARTIAL | LiteRT/ONNX/GGUF、CPU/GPU/NPU 策略、本地文字/ASR/TTS、资源优先级、指标 | 真实机型启动、音质、流式识别、双工打断、多模型并存、TTS 音色和长期性能 |
| D11 协作 | PARTIAL | 普通临时子 Agent、会议派发修复、候选模型与 A2A 基础 | 真实会议分工样本、电脑主动调用手机、远端文件/权限、A2A 恢复、tsnet 门槛 |
| D12 连接/插件 | PARTIAL | MCP、REST、OAuth、DSH、A2A、连接器、插件隔离、卸载生命周期基础 | 每类连接真实服务、取消/断开/更新/损坏配置/凭据清理全矩阵 |
| D13 统计/预算 | PARTIAL+ | 模型请求账本、费用估算、官方价格、运行/工具/审批统计、轨迹、日期筛选 | 统一跨模块轨迹、真实供应商计费、更多价格源、图表与导出细节 |
| D14 备份/迁移 | PARTIAL+ | 逻辑/完整备份、自动备份、凭据隔离、DB 迁移、配置备份 v5、Linux/更新源恢复 | 用户真实备份包、跨大版本、超大文件、全数据库/插件/权限恢复和长期回归 |

## 本轮新增代码入口

| 能力 | 关键源码 | 测试 |
|---|---|---|
| 三级权限/资金边界 | `agent/permission/AgentPermissionPolicy.kt`、`agent/capability/NexusExecutionGate.kt` | `AgentPermissionPolicyTest`、`NexusExecutionGateTest` |
| 审批恢复/队列 | `agent/control/EtaAppControlConfirmation.kt`、`data/repository/NexusExecutionStore.kt` | `NexusApprovalLifecycleTest`、`NexusExecutionStoreTest` |
| 审批轨迹/统计 | `agent/runtime/AgentEvent.kt`、`AgentRunMetrics.kt`、`AgentMetricsDatabase.kt`、`AgentMetricDetailScreen.kt` | `AgentRunMetricsTest`、`AgentMetricsMigrationTest`、`AgentRunMetricsStoreTest` |
| 多厂商 Skill 导入 | `agent/skill/SkillManifestCompatibility.kt`、`SkillPackageInstaller.kt` | `SkillManifestCompatibilityTest`、`SkillPackageInstallerTest` |
| Skill 市场来源 | `agent/capability/SkillSourceStore.kt`、`SkillMarketService.kt`、`CapabilityCenterScreen.kt` | `SkillSourceStoreTest`、`SkillUpdateStoreTest`、`SkillCatalogParserTest` |
| GitLab/Gitee | `agent/skill/PublicGitLabSkillSource.kt`、`PublicGiteeSkillSource.kt` | `GitLabSkillRepositoryParserTest`、`GiteeSkillRepositoryParserTest` |
| Linux 多源 | `agent/terminal/LinuxSourceSettings.kt`、`AlpineEnvironmentInstaller.kt`、`DebianEnvironmentInstaller.kt`、`LinuxSourceSettingsRow.kt` | `LinuxSourceSettingsTest` |
| 软件更新源 | `data/update/AppUpdateSourceStore.kt`、`AppUpdateChecker.kt`、`ui/SettingsScreen.kt` | `AppUpdateCheckerTest` |
| 源配置备份 | `data/repository/EtaFullBackupArchive.kt` | `EtaFullBackupArchiveTest.configurationBackupRestores...` |

## 下一步建议

优先级建议：D03 持久事件等待与恢复 → D01 全软件 UI 深层收口 → D10 真实本地语音/双工 → D11 会议与电脑协作 → D12 全连接生命周期 → D07/D09 ColorOS 真机长测。每次只选一个主工作包和一个验收链，避免继续形成超大会话。
