# Nexus 大改计划包

本目录承接 `ARCHITECTURE_OVERHAUL_MASTER_PLAN_2026-11-10.md`，把大改拆成可以直接执行和验收的文档。

## 当前基线

- 源码来源：Eta 4.11.8，提交 `8f005e3`。
- 新产品：Nexus。
- Nexus 与旧 Eta 使用不同安装身份，可以并存。
- 不进行常规上游同步。
- 阶段 0 已建立新的源码身份和无依赖契约模块；没有执行 Git 提交或推送。

## 文档顺序

1. [00_PROGRAM_CHARTER.md](00_PROGRAM_CHARTER.md)：产品目标、不可破坏约束和决策记录。
2. [01_BASELINE_INVENTORY.md](01_BASELINE_INVENTORY.md)：当前源码、服务、数据库和依赖风险。
3. [02_CORE_CONTRACTS.md](02_CORE_CONTRACTS.md)：必须先冻结的数据模型和接口。
4. [03_SEQUENCED_ROADMAP.md](03_SEQUENCED_ROADMAP.md)：按依赖排序的阶段、门禁和交付物。
5. [04_PHASE0_WORK_BREAKDOWN.md](04_PHASE0_WORK_BREAKDOWN.md)：阶段 0 的可执行任务、依赖与验收。
6. [05_BASELINE_DEPENDENCY_MATRIX.md](05_BASELINE_DEPENDENCY_MATRIX.md)：现有包依赖实测与解耦顺序。
7. [06_PHASE1_SHADOW_EVENT_LEDGER.md](06_PHASE1_SHADOW_EVENT_LEDGER.md)：阶段 1 影子事件账本实施设计。
8. [07_ENTRYPOINT_AND_RUN_SOURCE_MAP.md](07_ENTRYPOINT_AND_RUN_SOURCE_MAP.md)：普通会话、会议、子 Agent、A2A、定时任务的统一 RunSource 映射。
9. [08_DATABASE_OWNERSHIP.md](08_DATABASE_OWNERSHIP.md)：数据库、跨库关系与迁移写入边界。
10. [09_PROCESS_AND_SERVICE_BOUNDARIES.md](09_PROCESS_AND_SERVICE_BOUNDARIES.md)：Android 进程、前台服务和后台恢复规则。
11. [10_PHASE0_ACCEPTANCE.md](10_PHASE0_ACCEPTANCE.md)：阶段 0 证据、完成项与遗留项。
12. [schemas/nexus-migration-v1.schema.json](schemas/nexus-migration-v1.schema.json)：旧 Eta 到 Nexus 的迁移清单 schema。
13. `07_BASELINE_IMPORTS.csv`：3315 条逐文件 import 审计。
14. [11_PHASE2_APPLICATION_BOUNDARIES.md](11_PHASE2_APPLICATION_BOUNDARIES.md)：Application API、启动隔离与阶段 2 当前边界。
15. [12_PHASE3_CAPABILITY_AND_SETTINGS.md](12_PHASE3_CAPABILITY_AND_SETTINGS.md)：能力目录、工具授权和 Settings Control Plane 启动批次。
16. [13_PHASE4_GOAL_PLAN_NOTEPAD.md](13_PHASE4_GOAL_PLAN_NOTEPAD.md)：Goal、Plan 与会话记事本状态和工具。
17. [14_PHASE5_HOST_AND_ARTIFACTS.md](14_PHASE5_HOST_AND_ARTIFACTS.md)：HostTransport、DSH Slot、A2A/MCP/Linux 与 Artifact Ledger。
18. [15_PHASE6_PRESENTATION_AND_UI.md](15_PHASE6_PRESENTATION_AND_UI.md)：ContentPart 与 Classic/Omni Presentation Profile。
19. [16_PHASE1_SHADOW_LEDGER_IMPLEMENTED.md](16_PHASE1_SHADOW_LEDGER_IMPLEMENTED.md)：非阻塞影子执行账本。
20. [17_PHASE7_8_VOICE_MIGRATION_RELEASE.md](17_PHASE7_8_VOICE_MIGRATION_RELEASE.md)：语音状态机、资源优先级和迁移校验。
21. [18_GAP_CLOSURE_2026-11-10.md](18_GAP_CLOSURE_2026-11-10.md)：本轮计划差距收口、构建证据与外部门禁。
22. [19_LOCAL_GAP_CONTINUATION_2026-11-10.md](19_LOCAL_GAP_CONTINUATION_2026-11-10.md)：迁移向导、字体、设置回滚、结构化渲染、DSH 清单预检和模块债务继续收口。
23. [../NEXUS_PROJECT_HANDOFF.md](../NEXUS_PROJECT_HANDOFF.md)：ETA → Nexus 完整开发档案、全局注意事项、当前进度和后续入口。

## 当前进度

- 阶段 0A 身份与并存安装规则：已完成并通过身份检查。
- 阶段 0B 核心契约与依赖门禁：已完成并通过编译及 Release 构建。
- 阶段 0 工程检查、入口、数据所有权、进程边界和迁移 schema：已完成。
- 阶段 1 影子账本设计：已完成。
- 阶段 1 Runtime 接入：已完成非阻塞影子接入、retention 和 Timeline Reader；尚未切换到恢复主事实源。
- 阶段 2 启动批次：Application API、Runtime/UI 启动隔离、data/fork UI 反向依赖清零已完成。
- 阶段 3 第二批：主 Agent Loop 的本地与附加工具均已接入按需目录，ToolGrant 与 capability activation 校验生效；Settings 控制面适配完成。
- 阶段 4：Goal/Plan/Notepad 状态机、模式恢复、折叠卡、顶部抽屉、自动 revision 和 Agent 工具已接入；会议室原有受控拓扑保留。
- 阶段 5 首批：统一 HostTransport、DSH Slot 合同、Artifact Ledger 完成，Artifact Store 已迁出 UI。
- 阶段 6：Classic/Omni Presentation Profile 已通过 CompositionLocal 提供全 UI 树，ContentPart adapter 已覆盖主要内容类型。
- 阶段 1 首批：统一 Session 事件出口已接入影子 JSONL 账本，失败隔离且不保存正文。
- 阶段 7/8：VoiceStateMachine、资源优先级和 Migration Validator 已实现，canonical 语音状态已接现有协调器。
- Git 操作：未执行。

## 执行原则

- 阶段 0 不重构执行行为。
- 先建立事实源和依赖规则，再拆模块。
- 先做迁移与回滚，再切换数据库或 UI。
- 新包 Nexus 不复用旧 Eta 的安装目录或私有数据路径。
- 每个阶段单独验收，不等到全部完成后才发现架构偏差。
