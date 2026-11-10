# Fork 与上游同步方案

当前工作区没有 Git 元数据，所有上游同步都必须能够在不调用 Git 的情况下完成审查。固定的上游基线提交保存在根目录 `upstream-fork.json`，完整基线文件保存在 `upstream-current/`。

## 日常检查

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-upstream.ps1
```

脚本只读取 GitHub API 和本地文件，不写入源码。它会区分：

- 上游新增或删除的文件。
- 本地相对于固定基线修改过的上游文件。
- 只属于 Eta fork 的文件。

需要留下记录时追加 `-WriteReport`，脚本会更新 `docs/UPSTREAM_SYNC_REPORT.md`。

## 三方审查导入

1. 用脚本的基线提交与最新提交建立三个只读版本：`upstream-old`、`upstream-new`、`local-current`。
2. 对上游新增且本地不存在的文件，先审查权限、网络、依赖和默认开关，再复制；完整文件补齐后仍需编译。
3. 对上游修改而本地未修改的文件，可以按上游版本更新，但要在同一批变更中运行受影响的测试。
4. 对上游和本地都修改过的文件，先比较 `old → new` 与 `old → local`。只把上游改进移植到 fork 的同一接缝，禁止整文件覆盖。
5. fork-only 目录包括 `io/github/mangi/eta/fork/*` 和新增的 A2A、任务、学习界面。其公共接线文件仍是冲突高发区，更新上游时必须先核对路由、数据库迁移和 AgentAppState。
6. 完成后运行全量单测、Kotlin 编译、lint 和 debug 构建，再更新 `baselineCommit` 与 `upstream-current/`。

## 目录边界

- `upstream-current/`：固定提交的上游快照，仅作三方参考。
- `legacy/pre-upstream-*`：改造前的 fork 快照。
- `fork/a2a`：A2A 客户端、持久化和安全边界。
- `fork/meeting`：受控主持人/参与者拓扑。
- `fork/tasks`：持久定时任务。
- `fork/learning`：候选、证据、晋升与回滚。
- `fork/conversations`：文件夹、归档、导出数据层。

任何 Git 初始化、拉取、分支、合并和提交都需要用户明确授权。
