
---

## 8. 详细附件索引

- docs/nexus/00_PROGRAM_CHARTER.md：产品章程、不可破坏约束、关键决策。
- docs/nexus/01_BASELINE_INVENTORY.md：基线代码、数据库、入口和风险。
- docs/nexus/02_CORE_CONTRACTS.md：对象、工具、权限、Presentation 契约。
- docs/nexus/03_SEQUENCED_ROADMAP.md：完整阶段顺序与门禁。
- docs/nexus/04_PHASE0_WORK_BREAKDOWN.md：第一阶段工作分解。
- docs/nexus/05_BASELINE_DEPENDENCY_MATRIX.md：顶层依赖与循环。
- docs/nexus/06_PHASE1_SHADOW_EVENT_LEDGER.md：影子账本设计。
- docs/nexus/07_BASELINE_IMPORTS.csv：3165 条逐文件 import 审计。
- docs/nexus/07_ENTRYPOINT_AND_RUN_SOURCE_MAP.md：统一 RunSource 映射。
- docs/nexus/08_DATABASE_OWNERSHIP.md：数据库唯一 owner。
- docs/nexus/09_PROCESS_AND_SERVICE_BOUNDARIES.md：Android 进程、服务和后台恢复边界。
- docs/nexus/10_PHASE0_ACCEPTANCE.md：阶段 0 验收证据与遗留项。
- docs/nexus/schemas/nexus-migration-v1.schema.json：迁移清单 JSON Schema。
- scripts/generate_nexus_dependency_audit.py：重新生成 import 审计。
- scripts/verify_nexus_identity.py：安装身份碰撞检查。

## 9. 移交时不得误报的事项

- “代码能编译”只代表 L1/L2 基线，不代表 UI、A2A 或迁移已在真机工作。
- 当前远程仓库即使存在地址，也不代表本地工作已提交或推送。
- DSH 未完成全部插槽时，任何稳定版不得宣称 DSH 插件兼容。
- A2A 入站必须复用同一 Agent Loop 与工具解析器，不能恢复成一次简化模型调用。
- 根权限、删除、外部提交和 GUI 高危动作不能由 Agent 自主批准或自动恢复。
