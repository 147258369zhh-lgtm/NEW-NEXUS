# Nexus 阶段 1：影子执行账本（已接入）

## 已实现

- 在 `AgentRuntimeSession` 的统一事件出口挂接 `ExecutionShadowRecorder`，覆盖普通聊天、会议、Subagent、A2A、定时任务等共用 Runtime。
- 每个 Run 写入独立 JSONL：Run metadata、单调 sequence、事件类型和终态。
- 使用 2048 项有界队列和单 daemon writer；队列溢出只增加 dropped 计数。
- 文件写入失败、线程失败或初始化失败均不会向 Agent 主流程抛出。
- 不记录正文、工具参数、推理内容、Bearer Token 或文件内容；终态仅记录长度和错误摘要。
- 原有 Metrics、Checkpoint、UI Event 和恢复判断保持不变。

## 尚未完成

- 当前是影子记录，不驱动恢复。
- Item/Artifact/Checkpoint 投影尚未从账本切换为主事实源。
- 还未建立账本与旧 Metrics/Checkpoint 的 20 Run 对照观察报表。

## 本轮补充

- 新增 30 天/最多 200 Run 的自动 retention 清理。
- 新增 `readTimeline(runId, afterSequence)` 调试读取入口；仍不输出提示词、工具参数、推理或正文。

## 验证

- `ExecutionShadowRecorderTest` 验证 run started、Agent event、terminal 写入，以及正文不会进入账本。

