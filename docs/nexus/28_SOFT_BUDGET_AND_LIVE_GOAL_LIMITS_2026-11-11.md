# N1 软预算确认与当前运行的 Goal 预算

## 本批结果

继续原计划的 Runtime 实装，不重复全项目审计。上批记录的“本轮新建 Goal 预算只能下一轮生效”已经补齐：实际 Goal 工具回执携带可信 GoalId 和预算约束，先提交正式事件与有效请求，再收紧当前 Guard；已消耗的次数、模型回合和时间不清零。

## 真实执行链路

### 软预算

```text
实际 ModelClient / AgentLoop / 子 Agent
 → BudgetGuard 检查模型回合、工具调用和运行时长
 → 原软额度用尽，需要继续执行
 → RunBudgetConfirmation → 现有本机确认窗口
 → 同一 Run 的 APPROVAL_REQUIRED / APPROVAL_RESOLVED
 → 批准后继续到原硬上限；拒绝、取消、超时不执行下一步
```

- 模型回合和工具调用允许用完软额度，下一次执行前等待确认；时间软预算在安全执行边界检查。
- 同一维度、同一软阈值只确认一次；重试或备用模型沿用同一 Guard，不重置额度。阈值进一步收紧后，旧确认不能覆盖新的较小阈值。
- 并发工具预留共享线程安全计数，拒绝整批预留时不消费计数。确认期间不持有计数锁；硬超时仍可以取消阻塞资源。
- 审批使用现有本机窗口和真实等待状态。取消会释放审批；用户手动暂停仍然有效。
- Hosted Tool 已在服务端开始时只结算额度，不在流式回调中弹软预算确认；下一安全执行边界再判断，避免持有流式请求等待 UI。
- 继续确认只允许使用原硬额度内的剩余部分，不增加硬上限或工具权限。任意扩大硬预算的设置/续跑入口仍需后续单独实现。

### 当前运行的 Goal 约束

```text
AgentLocalTools → WorkflowToolExecutor → Goal 状态保存
 → GOAL_UPDATED（真实 GoalId + 预算约束 + 不可变版本引用）
 → SQLite 同事务更新有效 RunRequest
 → 当前 Guard 取交集并重新安排硬超时
 → 真实下一模型请求 / 工具调用受新限额约束
```

有效预算只能收紧，不能通过创建更宽的 Goal 放大已有任务授权。新限额比已消耗额度更小或已经过期时，后续执行被终止；已经完成的 Goal 工具结果仍保留在 transcript/Checkpoint。

`NexusRunApplicationApi` 维护活动 Recorder 的父子关系，目标预算变化会约束父 Run 和仍在运行的兄弟/子 Run。传播记录使用 `BUDGET_RESTRICTED`，任一传播提交失败会停止该组活动任务。传播时不持有来源 Recorder 的锁去调用兄弟 Recorder，避免交叉锁等待。

预算传播和 `openChild` 共用准入边界；传播期间新增子 Run 等待更新完成，再读取收紧后的父预算。已结束任务不被重开。

## 执行库 v4

- `request_json` 表示当前有效请求；新增 `admitted_request_json` 保留最初准入身份与预算。
- Goal 约束与派生预算事件的 `budget_json`、`goal_id` 是结构化执行元数据，和序号、事件 ID、Run 投影在同一事务提交。
- 重复 `create` 使用最初准入身份去重，不因运行中预算收紧而错误地判断为不同请求。
- 显式迁移 v1/v2/v3→v4；旧事件、版本引用、Item、Checkpoint 和原准入预算保留，不删除旧库。
- 检查点写入当时的有效预算；新子任务读取当前有效父预算。
- 备份数据库清单跟随 v4。定向测试实际导出、staging、关闭数据库、激活还原后读取最初准入和有效预算。

## 代码位置

- `agent/model/AgentRunBudgetGuard.kt`：线程安全预留、软预算确认、动态交集与硬超时重设。
- `agent/model/AgentLoop.kt`、`AgentModelClient.kt`：真实模型循环和 Hosted Tool 结算。
- `agent/runtime/RunBudgetConfirmation.kt`：现有本机确认入口。
- `agent/runtime/AgentRunController.kt`：提交后预算通知与独立资源取消。
- `agent/runtime/NexusExecutionRecorder.kt`、`NexusRunApplicationApi.kt`：正式事件与活动父子任务传播。
- `agent/workflow/WorkflowToolExecutor.kt`：从真实 Goal 回执提取约束，不读取模型伪造的批准字段。
- `data/repository/NexusExecutionStore.kt`：原始/有效请求、预算事件事务与非破坏迁移。

## 验证与剩余边界

初轮 10 组 App 111 项：110 通过、1 个 Windows 符号链接权限相关用例跳过；API 10+8 项通过。补齐活动父子传播、Hosted Tool 安全边界和动态预算备份后，相关 App 115 项：114 通过、1 平台跳过，0 失败/错误；API 10+8 项通过。

受控 Provider 驱动的真实 ModelClient/AgentLoop 测试证明：第一轮实际工具创建小预算 Goal 后，不会发送超额的第二轮模型请求，已经成功的工具回执和有效预算检查点保留。另覆盖本机批准/取消、并发预留、计数不重置、缩短超时、父子/兄弟同步、传播写失败、旧库升级及备份还原。

最后补充“传播期间新建子 Run”故障夹具：人为阻塞父预算提交，同时请求创建新子任务，证明新任务等待提交结束后才读取小预算。最终最小相关回归 4 组、29 项全部通过（32 秒），0 失败/错误/跳过；对应修改只涉及预算传播与子任务准入互斥，没有重新跑完整项目审计。

证据目录：

- `build/reports/nexus-baseline/20261110-soft-budget-initial/`：初轮 Guard、真实 Loop、事务迁移与备份。
- `build/reports/nexus-baseline/20261111-soft-budget-family/`：父子/兄弟传播、传播失败、Hosted Tool 与备份预算，App 114 通过、1 平台跳过，API 10+8 项通过。
- `build/reports/nexus-baseline/20261111-soft-budget-race/`：最终 29 项相关回归。与前一组有重叠，不将两组计数相加冒充独立用例数量。

架构门禁、Kotlin 编译和 Nexus 身份检查通过，两份主计划一致。没有执行 Git 写操作、生成/安装 APK、调整签名或读写手机数据。测试证据是 L2，不代表真实云模型、手机长时运行或恢复主事实源切换已完成；Lint 仍保留原来的未完成状态。

## 实际使用

对话、会议或自动任务达到软额度后，现有确认窗口会询问是否继续本次任务。批准后继续使用剩余硬额度，拒绝或取消则停止后续执行，已完成回执保留。

在同一个运行里创建较小预算的 Goal 后，当前任务马上采用更严的额度，不需要再发一条消息才生效。额度包含已经消耗的次数和时间；若新额度已经用尽，创建 Goal 的成功结果仍会保存，下一执行步骤被阻止。

N1 仍为 PARTIAL，尚有费用总预算、父子模型请求总额度、硬预算追加、可恢复的用户输入等待、其他入口及 20 个真实 Run 对照。动态限额和软预算等待已经可执行，不把这部分通过当成整个阶段完成。
