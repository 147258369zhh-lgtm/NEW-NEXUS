# Nexus 阶段 4：Goal、Plan、会议室与会话记事本

## 已实现

- 新增 Goal 状态机：草稿、计划批准、运行、验证、等待用户、预算暂停、完成、失败和取消。
- 目标完成必须先进入验证/等待用户，并显式要求 `confirmed=true`。
- 新增 Plan 文档与乐观 revision 校验；步骤包含依赖、能力、Effect、验证方式和可逆性。
- 新增会话记事本 Revision，内容修改必须携带 `expected_revision`，每次写入单调递增。
- 新增 `goal_manage`、`plan_manage`、`notepad_manage` 三个按需工具，已进入 lazy catalogue。
- Goal/Plan/Notepad 绑定当前会话、会议或定时任务作用域，按 scope hash 原子写入。
- 输入框上方已加入“会话 / 计划 / 目标”切换，模式随 Run Handoff 进入 Runtime system instruction。
- 工作流模式按会话持久化到 GoalPlanNotepadStore；重新打开会话会恢复上次模式。
- 输入框上方增加可折叠 Goal/Plan 状态卡，显示目标状态、计划 revision、步骤、验证与预算。
- 会话顶部增加下拉把手和记事本抽屉，支持半屏/全屏、900ms 自动保存 Revision，以及整理、总结、生成计划、执行四个 Agent 操作。
- 会议室已具备的受控拓扑保持不变：主持人监督器、单 @ 直接、多 @ 并行、无 @ 主持人分派、临时专家和独立模型。

## 当前边界

- 会议主持计划已经支持创建临时专家；并行数量仍由会议室设置限制。
- Goal 当前按会话作用域保存；提升到项目作用域尚未接入。
- 夜间记事本整理与低风险提醒尚未接入。

## 验证

- `WorkflowStateEngineTest` 覆盖目标必须先验证再完成、记事本 revision 单调递增。
- `GoalPlanNotepadStoreTest` 覆盖 Goal、Plan、Notepad 持久化与 revision 冲突约束。

