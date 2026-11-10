# Nexus 阶段 5：A2A、MCP/DSH/Linux 与文件边界

## 已具备的基线

- A2A 已有局域网和外部 Tailscale 两种传输。
- MCP 已有 HTTP/stdio、生命周期、工具发现和关闭路径。
- Linux 已有 PRoot/Root 多发行版、终端、后台任务和共享目录。

## 本轮补充

- 新增统一 `HostTransport` 合同：`start/stop/currentAddress/status`。
- A2A Transport 改为实现 `HostTransport`，业务层无需依赖具体监听方式。
- 新增 DSH Slot Catalog 合同和 NATIVE/WEBVIEW_FALLBACK/UNSUPPORTED 兼容级别。
- 新增 `HostAdapterRegistry`，支持 Host 去重注册、移除和 READY 过滤。
- `McpRunSnapshot` 现在把每个启用的 MCP Server 投影为 HostAdapter 和 ToolDescriptor；执行继续走既有鉴权、治理与 MCP executor。
- 新增统一 `ArtifactLedger` 合同和 ArtifactRecord/Status。
- `WorkspaceArtifactStore` 从 UI 层迁到 Data 层，UI 通过 typealias 保持兼容。
- 新增 `NexusArtifactLedger` 适配器，为会话、项目文件统计和导出提供稳定 application 接口。

## 当前边界

- DSH Host 本体、全部 UI 插槽和本机 DSH 运行包尚未实现。
- MCP 已能投影并注册 HostAdapter，但仍需在 Capability UI 中展示 Host 状态并做跨进程生命周期验收。
- A2A Transport 已统一接口，但远程端双向出站发现仍需后续联调。
- 文件 ledger 已迁到 Data 层；项目固定输入/输出目录 UI 与会话文件页仍待接入。

