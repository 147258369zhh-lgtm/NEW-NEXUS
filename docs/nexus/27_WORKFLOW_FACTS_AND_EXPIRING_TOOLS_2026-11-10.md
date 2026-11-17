# 工作流执行事实、目标确认与工具有效期

本页保留该批实装与验证快照。后续的软预算确认、当前 Run 即时收紧 Goal 预算及执行库 v4，见 [28_SOFT_BUDGET_AND_LIVE_GOAL_LIMITS_2026-11-11.md](28_SOFT_BUDGET_AND_LIVE_GOAL_LIMITS_2026-11-11.md)；下方“新建 Goal 下一轮生效”属于本页当时遗留，现已补齐。

## 本批生产改动

按既定计划继续 N1/N2，不重复全项目审计。真实入口为普通会话/会议/子 Agent 的 `AgentLocalTools.workflowTool`，现统一委托给 `WorkflowToolExecutor`。

### 目标、计划与记事本

- Goal 开始执行、从等待恢复、最终完成均通过现有本机确认窗口。模型提供的 `confirmed=true` 只作旧参数兼容，不能代表用户批准。
- 批准绑定目标、计划、记事本和模式的完整状态指纹。等待期间任何相关状态变动都会使旧批准失效；开始执行还要求当前目标有非空计划。
- 目标预算拒绝 0、负数、非整数等输入，不允许用异常参数去掉安全上限。只提供较小硬上限时，默认软阈值会同步收缩。
- 计划不能关联其他作用域的目标；自依赖、间接循环和无效依赖均被拒绝。
- `goal_manage`、`plan_manage`、`notepad_manage` 的写操作必须有真实 Run 及已绑定 Recorder。读取当前状态和历史版本不要求启动新 Run。

每次写操作采用：

```text
本机批准（仅需批准的状态变化）
 → WORKFLOW_UPDATE_STARTED 正式事务
 → 原工作流 Store 保存真实修改
 → 私有不可变版本文件（SHA-256）
 → GOAL_UPDATED / PLAN_UPDATED / NOTEPAD_UPDATED 正式事务
 → 同一 Item 成功与版本引用
```

参数冲突等可确定失败写为 `WORKFLOW_UPDATE_FAILED`，同一个 Item 结束为失败。若实际修改已保存、结果事件却写入失败，则返回 `mutation_applied=true`、停止后续执行并保留未结算 Item。进程中断留下开始事件时，不可提交成功终态，也不能据此自动重复修改。

版本正文保存在 `files/workflow-state/revisions/`，不放进元数据事件。`execution_reference` 是内容寻址引用；原工具支持 `action=revision`、`revision_ref=该引用`，验证作用域、类型和 SHA-256 后读取旧版本。修改当前文件不会覆盖旧版本。

### 正式执行库 v3

- Event/Item 增加 `payload_ref`，公开合同的 `ExecutionFact`、`ExecutionEvent`、`ExecutionItem` 传递相同引用。
- 显式 v1→v3 和 v2→v3 迁移保留原事件 ID、序号、Item 和 Checkpoint；不删除数据库。
- 工作流开始/结果同一 Item 必须匹配工具类型；孤立结果、重复结果、错误工具及未结算修改下的成功终态被拒绝。
- 备份的数据库清单随 `NexusExecutionStore.VERSION` 更新；完整文件备份包含私有版本文件。快速备份若明确排除文件，不宣称包含版本正文。

### 工具激活与执行

- 移除 Lazy Catalog 的永久激活期限，根 Run、子 Run 都使用固定任务期限。
- 构建下一轮 schema 前重新检查工具白名单、授权策略和激活有效期；撤销、能力变化和过期使相应工具退出活跃列表。
- 子 Agent 发现/激活也使用实际设备能力与来源限制；A2A 来源不能借子任务激活被禁止的高风险能力。
- 授权 TaskId 使用 canonical RunId，避免包含中文的旧 Run 标识使合法工具调用在合同校验处崩溃。
- `browser_use` 从 READ 修正为 GUI_ACTION；浏览器控制不能被当作纯读取自动重放。
- 非法工具标识直接返回 `INVALID_TOOL_ID`，避免伪造名称引发合同构造异常并拖垮整个 Runtime。

激活仍只加载 schema。实际调用继续经过执行 Gate 与高风险本机确认；本批没有将激活当作用户授权。

## 验证

首次编译发现跨模块 Plan 属性不能直接智能转换，已改为局部稳定引用。随后新增备份测试漏了 JSONObject 导入，已补齐；原始失败日志保存，未记为通过。

初轮定向测试 App 80 项，79 通过、1 平台跳过；API 10+8 项通过。随后补齐不可变版本文件的实际备份/还原、未知工具拒绝与等待状态统计。

新增等待状态统计测试的第一版先暂停当前线程再发起审批，会按 Controller 的正常暂停语义阻塞自己。通过实际工作线程栈确认是该测试顺序，并停止对应测试进程；修正为先验证暂停、恢复后再验证审批。该次主动中断保留为失败证据，不计为通过，没有放宽生产暂停行为。

最终定向回归通过（44 秒）：

- App：10 组、86 项，85 通过、1 平台跳过、0 失败/错误。
- Application API：10 项、Kernel API：8 项通过；同批 API 源码已在初轮实跑，后续未改变 API 源码。
- 唯一跳过项是 Windows 无创建符号链接权限的原有备份用例；不可变版本文件完整备份/校验/还原测试实际通过。
- 架构检查、Kotlin 编译、v1/v2→v3 迁移、版本引用持久化、未结算写操作阻止成功终态、Goal 本机批准/拒绝/状态变化、授权过期/撤销、中文 RunId 和非法工具拒绝均有定向覆盖。
- 运行证据现在显示未结算工作流修改；等待/暂停状态与仍为 running 的 Metrics 不会再被误报为终态不一致。
- 日志/XML：`build/reports/nexus-baseline/20261110-workflow-grants-final/`；初轮通过和编译失败保存在 `20261110-workflow-grants/`。
- Nexus 身份检查通过。没有重新做全项目审计，没有执行 Git 写操作、打包或安装，没有真机/真实 Provider 验收；Lint 原有未完成状态不变。

## 使用入口

会话中让 Agent 创建目标、制定计划后开始执行，会出现现有样式的本机目标确认。最终标记完成仍需本机确认；计划或目标在确认期间修改后，需要重新检查并确认。

写入工具返回 `execution_reference`。需要查看旧内容时，由同一作用域的原工具发送 `action=revision` 和 `revision_ref=该引用`；即使当前计划或记事本已更新，也会读取保存的旧版本。其他会话的引用、错类型引用和哈希损坏文件会被拒绝。

N1/N2 仍为 PARTIAL：软预算暂停与追加、费用总预算、Run 内新建 Goal 后的即时预算收缩、用户输入等待、完整参数/Host 范围、全部来源和 20 个真实 Run 对照尚未完成。已存在 Goal 的预算在准入时固定并用于执行，新建 Goal 的预算从下一次准入读取；本批不把字段保存当作即时动态预算已实现。恢复依据仍为旧 Checkpoint/outbox。
