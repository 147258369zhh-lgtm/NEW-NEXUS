# 项目、备份与统一归档 — 2026-11-15

## 本批授权

用户批准项目与文件工作区合并、移除ETA迁移入口、手动备份/恢复/自动备份整理，以及普通会话和会议室统一归档中心。允许本批模块内部布局优化。今天每批完成后覆盖安装手机；上一批统计、项目文件与浏览器精修一并交付。不修改其他界面、不上传GitHub、不卸载或清除手机数据。

## 项目与文件

Settings仅保留“项目与文件”一个入口，搜索关键词同时覆盖项目与工作区。ProjectsWorkspaceScreen作为统一页面，项目/文件分段导航使用一个顶栏，两个子页面保留各自滚动状态。旧Workspace路由从Linux环境和应用控制进入时仍可用，并打开同一合并页面的文件分段。

项目保留新建、目录授权、详情、任务、记忆、会话及会议室关联等既有能力。文件范围可选独立工作区或具体项目；具体项目集中关联文件浏览器和工作副本。副本读写、列表、搜索、复制和移动限制在选中副本的真实目录，保护副本根；不将文件范围切换当作改变文件归属。独立工作区保留通用导入及原工作区设置，项目关联导入仍使用已存在的项目文件入口。

修复：普通目录返回先回父目录；项目归档/删除失败显示错误而非无声失败；产物加载异常显示反馈；副本目录不能越界访问其他项目/全局工作区，不提供会导入全局imports的副本快捷导入。

## 备份与恢复

去掉迁移第三页与迁移向导。保留手动备份、备份校验与确认恢复、自动备份；保留现有逻辑/完整格式、加密、内容选择、凭据保护、安全快照、恢复哈希复验及重启应用恢复流程。没有删除旧备份格式兼容与本应用升级数据库迁移。

界面使用真实WorkManager运行与立即任务排队状态。自动备份运行中禁并发备份/恢复，仍允许离开页面；立即备份避免重复安排。修正“完整备份已恢复”成功分类以及校验失败、回退、暂存失败、操作异常的错误分类；区分系统稍后重试和最终失败。

新自动备份文件使用NEXA名称，保留旧Nexus命名识别与清理。保留顺序按时间而非产品前缀，避免混合旧/新文件时误删较新文件。自动备份仍受Android调度、充电/网络条件、目录授权和执行空闲条件影响，设置时间不是硬实时承诺。

## 统一归档中心

普通会话及会议室的归档合并，固定“归档记录/归档设置”两个分段，分别保留滚动位置。记录提供日期分组时间线、类型筛选、标题及真实消息内容搜索、摘要、打开查看和恢复；自动归档、豁免、检查设置在设置分段。

打开查看使用原聊天舞台：普通会话selectConversation、会议室选中room后返回Home，不调用取消归档。恢复单独调用原repository事务，成功后刷新列表，失败保留记录。类型和ID都参与稳定键，避免同ID跨类型冲突。

读取在IO，300ms搜索防抖，40条keyset分页；搜索对全部归档匹配后分页，不只最近200条。普通会话正文沿SQLite分块流逐块读取，会议正文SQL EXISTS匹配；新增只读DAO无需schema版本变化。设置订阅只读单行设置，不为设置加载完整扩展表。代理静态SQL检查350个混合归档/同毫秒时间戳分页无漏项重复，并命中最旧会议正文；这不等于Android真机功能验收。

## 关键所有者

- ui/SettingsScreen.kt、ui/app/AgentAppRoot.kt：合并入口、搜索与归档查看路由。
- ui/screens/projects/ProjectsWorkspaceScreen.kt、ProjectsScreen.kt、ui/screens/terminal/WorkspaceScreen.kt、ui/app/WorkspaceFileStore.kt：项目与文件合并及副本边界。
- ui/screens/backup/BackupCenterScreen.kt、BackupViewModel.kt、data/repository/AutoBackupScheduler.kt、AutoBackupDocuments.kt：备份与真实状态。
- ui/ConversationArchiveSettingsScreen.kt、data/repository/UnifiedConversationArchiveRepository.kt、ConversationSearchRepository.kt、相关DAO：统一归档及分页全文搜索。

## 验证与交付

待本批最小回归及assemblePerformance结果后追加。计划versionCode 2026111510，沿用applicationId io.github.zhh_lgtm.nexus和现有证书覆盖安装；旧1509包保留。

### 本批最小回归结果

集中检查首次应用源码编译通过，但新副本测试调用漏传includeContents参数导致测试源码编译失败（1m 6s）；只补该参数后重跑同三类：BUILD SUCCESSFUL in 45s。WorkspaceFileStoreTest 10例、AutoBackupDocumentsTest 6例、BackupOperationPresentationTest 4例，共20例，失败/错误/跳过均0。日志build/project-backup-archive-check-20261115.log与build/project-backup-archive-check-final-20261115.log。未扩展到其他测试，也未操作手机页面或发模型消息。

### 2026-11-15 已打包并覆盖安装（2026111510）

本批连同46号文档上一节统计/文件卡片/浏览器精修，一起执行一次assemblePerformance：BUILD SUCCESSFUL in 1m 51s，118任务（31执行、87最新），日志build/project-backup-archive-package-20261115.log。版本0.7.7-smooth / 2026111510，applicationId io.github.zhh_lgtm.nexus；ARM64、非debuggable，64187821字节。

原Android Debug开发证书SHA256 f28db5318e785a928d93cec570519767fb0662309749fc2afab08e37763e8ac4保持一致，apksigner v2验证通过（v3未启用）、ZIP 16KiB对齐检查通过；不是正式生产签名，也未做逐库ELF验收。APK SHA256：9DE5865D2B2B01C42E003ED6FB1E160BFAE91C3E8937F5943993EF0A67BA5E01。

ADB设备1fe75f23（OPPO PHY110）覆盖安装返回Success；系统包确认versionCode=2026111510、versionName=0.7.7-smooth。保留数据，没有手机页面操作/功能测试，没有模型消息或MCP调用。

桌面：C:\Users\29136\Desktop\NEXA-项目备份归档-2026-11-15/NEXA-v0.7.7-2026111510.apk，附SHA256、安装与包检查.json、交付说明.md。旧1509包保留。今日每批完成后同步手机按用户明确要求执行。
