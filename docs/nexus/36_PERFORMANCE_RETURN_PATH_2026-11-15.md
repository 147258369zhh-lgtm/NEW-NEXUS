# 公共返回与主线程负载整改记录（2026-11-15）

## 范围与验收边界

用户连续反馈页面进入、连贯操作、侧滑返回存在停顿、重影和掉帧，本轮暂停功能开发，重点处理公共返回链路及与转场争用主线程的后台工作。保持现有页面布局、层级、尺寸、间距、材质、文字和操作入口；转场修复属于用户已授权的流畅度范围。

用户最后明确要求“不用测，继续修”，因此停止手机导航、业务消息、模型调用和性能基准。这里只记录源码整改、只读审查及构建/包检查；不能把编译或安装成功写成真机不卡顿。

## 已定位问题与对应处理

以下路径均相对于 `app/src/main/kotlin/io/github/mangi/eta/`。

| 流程 | 所有者 | 实际问题 | 本轮处理 | 证据边界 |
| --- | --- | --- | --- | --- |
| 公共导航及侧滑返回 | `ui/app/AgentAppRoot.kt`、miuix-nav 0.9.4 | 上轮很小的装饰位移不能及时露出目标页，视觉上仍像原地等待后突换 | 旧页按页面宽度随手势移出；RTL 镜像；进度只在图形层读取；提交/取消沿同一进度收尾；程序导航使用同一几何 | 已核对依赖源码的手势、提交及绘制路径；未重新真机验证 |
| 返回手势起步的系统监听清理 | `agent/accessibility/AgentAccessibilityHealth.kt`、`ui/app/DeviceCapabilitiesUi.kt` | 页面进入返回手势时从 RESUMED 降为 STARTED，取消订阅正好在主线程注销 ContentObserver；取消手势又重新注册 | callbackFlow 的注册、注销与历史订阅移到 IO；仍保留生命周期门禁和系统状态更新 | 明确涉及设置、工具、系统增强和 Linux 环境；不能解释或保证所有页面 |
| 页面恢复的 Root 和权限读取 | `ui/app/DeviceCapabilitiesUi.kt`、`SettingsRuntimeSnapshotStore.kt`、`PermissionHealthStore.kt` | 页面恢复重复启动 Root 探测；并发权限检查增加系统调用与状态发布 | 真正 Activity 恢复仍刷新 Root；页面复用共享快照；并发请求合并采样并保留必要的尾随刷新；权限健康事件短延迟合并 | 强制刷新不会被缓存命中吞掉；权限变化仍会重新采样 |
| 经典背景、卡片、图标绘制 | `ui/components/EtaUiFoundation.kt` | 绘制重复创建渐变、几何与大量点阵命令 | drawWithCache 缓存；静态点阵一次 drawPoints；保留颜色、位置、光照与玻璃效果 | 源码绘制路径审查；没有通过降低材质质量规避性能问题 |
| 设置首帧及保存 | `ui/SettingsScreen.kt` | 首帧查询版本触发 Binder；部分 RemotePreferences commit 在 Main | 版本用 BuildConfig；跨进程设置提交和电源助手目标保存放 IO；保存门禁及成功后状态确认 | 保留原失败提示和提交顺序 |
| 本地模型进入、返回及恢复 | `data/repository/LocalModelPerformance.kt`、`ui/screens/localmodel/LocalModelsScreen.kt` | 首次同步采样与广播/热状态监听争用转场；早期默认资源值不可信 | 完整设备采样和监听注册/清理放 IO；仅 RESUMED 采样；刷新合并；未测 RAM 显示既有占位，不错误判断限流 | 设备资源保护仍使用真实采样；未实测不能写为无限资源 |
| 语音页面本地模型目录 | `ui/voice/LocalModelRecordsState.kt` 及识别/连接/本地语音组件 | 组合期间同步目录初始化、解析和修复 | 轻量 bind，LaunchedEffect 在 IO 初始化，继续订阅现有模型 Flow | 不改变模型目录格式和安装逻辑 |
| 会话列表摘要 | `ui/app/AgentAppState.kt`、`ConversationTimeLabels.kt` | 多次格式化日期、重复计算最后一条消息预览 | 每次摘要投影共用时间上下文；按不可变消息引用缓存预览；语言变化失效；删除后清缓存 | 不改变分组、文字格式或列表设计 |
| 历史运行事件恢复 | `ui/app/AgentRunMessageProjector.kt`、`AgentAppState.kt` | 逐事件发布消息列表和摘要，恢复时大量主线程重组 | 有序本地 replay，合并相邻 delta，最后一次更新消息与摘要 | 保留补充消息、GUI 状态、停止请求和时间戳；未把共享投影器移到后台制造顺序竞争 |
| 子 Agent 活动卡 | `ui/components/AgentToolActivity.kt` | 组合期间同步解析完整 snapshot JSON | Default 解析；按消息保留最近有效结果；取消过期解析 | 保留完整归档，不裁剪用户数据 |
| MCP 设置与会话的服务列表 | `data/repository/McpServerRepository.kt` | Room 列表转换中 schema JSON 解析可能回到 Main；重复 entities 再投影 | 原始实体去重，转换流放 Default | 工具发现、权限和执行语义不变；不是 MCP 端到端验收 |
| 隐藏终端影响其他页面 | `ui/app/ConsoleStore.kt`、`ui/screens/terminal/ConsoleScreen.kt` | PTY 输出即使终端隐藏仍在主线程构造最多 1000 行；键入、主题等操作可能等 buffer 锁 | 单 Default worker 与合并信号；隐藏时不投影画面，仍处理输出/协议；buffer 工作移后台；generation/session 门禁 | 修复旧会话信号覆盖新会话刷新及跨会话帧误相等；输入继续有序 |
| 连接中心与边界工具 | `ConnectorCenterScreen.kt`、`ConnectorSettingsService.kt`、`BoundaryToolsScreen.kt` | 首帧同步读取；提交或重新加载争用主线程 | 读取、保存与重载 IO；未加载/正在保存时禁止重复提交 | 保持原错误区和“保存成功后确认”语义 |
| 备份中心 | `ui/screens/backup/BackupCenterScreen.kt` | SAF persistedUriPermissions 系统读取可能在恢复帧执行 | 检查放 IO，权限与目录变化仍刷新 | 不改备份内容、加密格式与恢复流程 |
| 模型提供商列表、详情 | `ui/pages/providers/ModelProviderListScreen.kt`、`ModelProviderDetailScreen.kt` | 首帧解析健康记录；测试结果及删除清理同步 commit | 首帧只订阅 state，IO 初始化；成功/失败记录及删除清理 IO | 不改提供商菜单和连接测试功能 |
| A2A 设置、凭据及权限观察 | `fork/a2a/A2AInboundConfig.kt`、`A2ASecretStore.kt`、`A2AAccessPolicy.kt`、`ui/screens/a2a/A2ASettingsScreen.kt` | 首帧/监听同步解密、枚举网卡、写配置；后台化后首次 Token 和 KeyStore 初始化有并发窗口 | 监听只发刷新信号，IO 快照；读取一次 Token；端点使用快照地址；提交后才启停服务；进程内锁防重复初始化/轮换竞争 | 跨进程一致性仍沿用此前实现；本次锁不宣称为跨进程事务 |
| 声纹读取及录入保存 | `VoiceprintProfileStore.kt`、`VoiceprintEnrollmentController.kt`、`ui/voice/SpeechRecognitionScreen.kt` | 组合时解码最多 256 KiB 声纹；录入结束主动切 Main 编码与同步落盘 | 页面读取与录入/删除通过进程共享 IO 临界区；版本门禁阻止取消后的回调；实时校验读单份偏好快照 | 已接受的提交可完成；新页面读取/删除排在其后，避免旧档案重新出现；未运行语音设备用例 |
| 运行时根组件重组 | `ui/app/AgentAppRoot.kt` | 仅读取是否流式运行也可能订阅整份高频文本状态 | derivedStateOf 仅在所需布尔值变化时更新根使用者 | 其他消息区域继续接收正常流式内容 |
| 交付构建 | `app/build.gradle.kts`、`app/src/performance/AndroidManifest.xml` | 当前安装 Debug 包不能代表优化构建的实际成本 | 单独 performance 变体，R8/资源收缩、非 Debug、可 shell profile；保持应用 ID，使用与当前开发包一致的签名 | 只形成可对照的优化包；不以构建模式替代修复或体验验收 |

## 只读复核结果

两名审查代理分别核对性能热点与补丁语义。新发现的 A2A Token/KeyStore 竞争、终端会话帧竞争、声纹跨页面保存顺序窗口均已在源码中处理。公共 pop 路径没有同步 IO；miuix 手势直接更新进度，提交即弹栈。不能再把所有停顿泛称为“导航库在等待”。

## 尚不能宣称完成的部分

- 未取得当前优化包在手机上的进入/返回帧时间、连续触控延迟或视觉验收结果。
- 此前旧 Debug 的掉帧记录不是优化后结果；部分导航基线未完整返回，不能作严格对照。
- 已保存 Perfetto 轨迹，但分析工具下载受阻，尚无有效 CPU 切片结论。
- 大数据会话事件本地归约仍在 Main，现已减少状态发布与相邻 delta 成本；极大历史是否仍阻塞需证据后再决定分块，不能冒险并行共享投影器。
- 未逐项执行会议室、MCP、语音、本地推理和外部服务全量业务验收；本轮性能整改不代表这些功能通过正式测试。
- 未修改未经授权的界面设计，也未降低玻璃、阴影或其他视觉效果。

最终 APK、签名、哈希及安装结果以本轮交付记录为准；后续须以用户体验反馈继续收敛，不把“全部卡顿消除”标记为完成。
