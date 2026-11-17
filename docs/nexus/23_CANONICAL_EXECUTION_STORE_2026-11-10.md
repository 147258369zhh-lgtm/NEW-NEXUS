# N1 统一 Run 准入与持久执行记录实施证据

## 本轮范围

基线仍是 `NEW-NEXUS/main@8e7092e` 的本地开发副本。旧 Eta 没有修改，Git 没有初始化、提交或推送。

本轮实现 Service 入口到 Application API 的真实调用，以及独立事务执行库。**N1 尚未完成**：恢复权仍在旧 Checkpoint/outbox，尚未完成 20 个真实 Run 的四方对照。

## 真实生产链

```text
普通会话 / 会议 / 自动任务 / A2A / 语音 / 系统助手
  → 已鉴权 Runtime IPC / 内部继续请求
  → AgentRuntimeService.admitRun
  → NexusRunApplicationApi.admit → RunApplicationApi.submit
  → RuntimeRunRegistry 准入与去重
  → NexusExecutionStore.create / append（事务）
  → AgentRuntimeSession.beginExecution / emit
  → NexusExecutionRecorder
  → execution_events + execution_runs 同事务提交
  → 旧模型/工具执行和 UI 事件发布
  → Session.complete → 正式终态记录 → outbox 持久化 → 客户端结果
```

外部输入仍通过原有已鉴权 IPC 携带 Prompt、模型配置与附件，不把密钥塞入公共 Run 元数据。`RunApplicationApi.submit` 不接受一份没有实际执行输入的空元数据来假启动任务。它由 Service 在验证执行 Envelope 后调用；尚未形成供所有 Feature 直接使用的新公共内容提交协议。

## 生产行为与测试

| 要求 | 已落实行为 | 验证 |
|---|---|---|
| 统一准入 | Service 中创建并调用 `NexusRunApplicationApi`，不再直接完成 Registry 准入 | API 准入、元数据无输入拒绝、精确取消测试 |
| 重复请求 | 同一个活动 Run 返回原运行；持久终态拒绝再次执行；进程死亡留下的未完成记录要求先恢复核对 | active duplicate、completed duplicate、unfinished recovery-required |
| 会话并发 | Registry 与持久库使用同一 canonical Session，会议同一成员 Session 不并行写入 | 双会议 Run 同 Session 被拒绝 |
| 持久事务 | 事件、唯一 eventId、Run 内 sequence 和 RunState 一次 SQLite 事务提交；`synchronous=FULL` | 重开读取、4 并发 writer、重复投递、触发器中途写失败回滚 |
| 工具配对 | ToolStarted/Finished 使用相同稳定 ItemId/TurnId，结束必须匹配名称与开始；有未知工具结果时不能记成功 | 孤立结果、名称伪造、未完成工具不能成功测试 |
| 正式事实保留 | 新库 `nexus_execution.db` 独立于 JSONL shadow retention，无 30 天/200 Run 自动删除 | 新库路径和 schema；未实现用户归档/清理策略 |
| 执行前记录失败 | Session 不再吞掉正式 ToolStarted 持久化异常，该调用不会越过记录失败继续执行 | Session→Recorder 的故障注入测试 |
| 终态失败 | 正式终态无法提交时，给实时客户端及 outbox 的结果是同一份“记录未完成”，保留已发生操作信息，提示先核对 | Terminal recorder 抛错仍唯一发布且不虚报成功 |
| 手动压缩 | 不依赖模型 RunStarted 事件，也有明确 beginExecution；最终记录可正常终止 | compact-only 无模型 RunStarted 测试 |
| 敏感数据 | 事件只存类型、标识、状态、工具名和关联信息；不存 Prompt、参数、结果正文或模型 API Key | 检查实际录入/输出与私密夹具文本不泄漏 |
| 备份兼容 | 数据库列入迁移版本清单、备份 checkpoint、运行记录数；完整/快速备份包含该库 | 实际导出→staging→关闭库→恢复→查 Run/事件，未来 schema 在替换前拒绝 |
| 可观测入口 | 软件管家 `state.runtime.durableExecutionRuns` 返回最近 Run 状态、seq 和未接管恢复说明 | 真实 Repository 读取；不是固定成功值 |

## 测试证据

最新源码执行 `checkNexusBaseline`，BUILD SUCCESSFUL，耗时 4 分钟。

- App：496 组、3,248 项；3,098 通过、150 平台跳过、0 失败/错误。
- Application API：10 项通过。
- Kernel API：4 项通过，源码未变，使用有效 Gradle 测试缓存。
- 修正后的架构门禁、App Kotlin 编译通过。
- 身份验证脚本通过，安装 ID 仍是 `io.github.zhh_lgtm.nexus`。
- 本地原始日志、逐组 XML 与统计：`build/reports/nexus-baseline/20261110-canonical-execution/`。

这些是 JVM/Android SQLite 模拟环境和组件级 L2 证据。它们不是 20 个真实模型 Run，也不证明真机断电、系统杀进程、多手机或 Android 16/17 全量通过。

## 依赖审计修正

审计生成脚本原先把新 API import 分类为未知、覆盖历史 CSV 且没有基线/生成信息。现默认输出到构建报告，保留历史清单，附带来源标签、UTC 时间、扫描文件数、源码树 SHA-256、脚本 SHA-256、CSV SHA-256 与分类统计。

本轮结果：968 个主 Kotlin 文件、3,349 条 import；同层 1,473，跨层待审查 968，基线 UI 直连 840，反向依赖债务 68。68 条依赖债务没有因通过编译而被消除。扫描范围仅 import，不覆盖所有完全限定名和反射。

## 本轮未完成项与继续顺序

1. 正式账本的 Checkpoint 游标目前为空，不作为可恢复 Checkpoint 使用；需要把已提交 sequence 与真实上下文/工具状态快照关联。
2. 还没有从账本重建 `ExecutionItem` 的持久投影，也没有完成 ContentPart 生产与持久化。
3. Subagent 的内部模型执行目前仍主要投影到父 Run；需要独立 ParentRunId/子 Run、父子预算交集与同一账本接线。A2A 出站、插件和夜间整理同样逐入口补齐。
4. 审批、等待用户、暂停/继续、预算暂停和所有 Compaction 细节仍需与统一 RunState 关联；当前未把“中断记录可读”宣传成“任务可续跑”。
5. 未对数据库执行破坏性迁移；新 schema 为未发布 v1。未来修改已发布版本必须提供明确 Migration，不能删除重建。
6. 全部恢复仍保持旧 Checkpoint/outbox；完成 20 Run 对照与故障恢复证据前，不切换主事实源。
7. 先前 Lint 超时仍是未完成门禁；新代码尚未做 Release 构建与签名验收。
8. ADB 当前没有设备，真机 L4/L5 证据尚未产生；不将其记为通过，也不因此停止仍可推进的源码工作。
