# Eta 4.8 能力中心专项交付

## 1. 研究边界与结论

本次以本地固定快照和公开资料核对 ChatGPT、Hermes、WorkBuddy 及小 PRO 的能力设计。ChatGPT、Hermes、WorkBuddy 的线上内部实现并不公开，因此只把可观察的交互、公开协议和公开代码当作证据，不把产品说明反推成源码事实。小 PRO 以 `F:\eta\.reference\eta-pro-20261107` 固定快照为准，源码对照见 `PRO_CORE_RESEARCH_2026-11-07.md`。

可确认的共同方向是：能力发现与管理、明确的调用合同、安装与更新事务、来源追踪、权限边界和真实执行链路必须连成一体。单纯增加搜索页或安装按钮不构成 Agent 能力中心。

本项目继续遵守 PolyForm Noncommercial 1.0.0 与 Mangi-11 Required Notice。参考仓库用于设计与行为研究；本轮没有整包复制小 PRO 的 Runtime、GUI、Root 或隐藏系统服务。

## 2. 统一能力架构

能力中心把 Skill、MCP、REST API、OAuth 应用和能力包归到同一入口，但保留各自不同的执行与授权模型：

- Skill 是可加载的专业说明、参考资料和脚本资产。Agent 先看到轻量目录，命中后再通过 `skills_read` 和 `skills_read_resource` 按需读取正文与资源，以控制上下文成本。
- MCP 提供远端工具、资源与提示模板。每次运行冻结服务器和工具快照，协议协商、Bearer 认证、取消、超时和结果大小限制在运行内完成。
- REST API 连接器把 OpenAPI 操作转换为受限工具。连接器、操作和执行器逐层授权，请求锁定原主机，重定向关闭，参数按 Schema 校验。
- OAuth 2.0/PKCE 用于 REST API 账户授权。访问令牌只保存在本机凭据存储中，不写入能力定义、日志或能力包。
- 能力包 `eta-plugin/v1` 可组合 Skill、OpenAPI 连接和 MCP 连接，但不能携带明文令牌或 OAuth 凭据。

真实执行链路如下：

```text
Skill 匹配 / MCP 快照 / API 快照
              ↓
      本轮冻结工具目录
              ↓
      AgentToolPolicy 交集校验
              ↓
 RoutingToolExecutor -> MCP / API / 本地工具
              ↓
       有界结果回传 Agent
```

关键入口：

- `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeRunExecutor.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/mcp/McpRunContext.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/capability/ApiConnectorRuntime.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentSkillToolCatalog.kt`
- `app/src/main/kotlin/io/github/mangi/eta/ui/screens/capability/CapabilityCenterScreen.kt`

## 3. Skill 生态

### 来源与发现

- 内置 GitHub 公共仓库来源、OpenAI 精选 Skill 来源、HTTPS 目录来源和用户自定义来源。
- 来源采用 Adapter 结构，单个来源超时或故障不会中断整个聚合搜索。
- 搜索结果标明来源、信任等级、版本、更新时间、依赖、权限和最少量校验信息。
- GitHub 搜索解析到固定 commit；安装时再次按同一个 commit 下载，避免分支在查看与安装之间漂移。
- HTTPS 目录可选 SHA-256。无校验和来源必须经过显式信任确认。

### 导入、安装与质量门

- 支持 ZIP、本地文件夹、公共 GitHub 仓库和多 Skill 批量选择。
- 安装器拒绝路径穿越、绝对路径、大小写重复、嵌套 Skill、符号链接、特殊文件、过多条目、过深目录和超限解压。
- 安装前检查目标冲突；内置 Skill 不可被覆盖，用户 Skill 只有明确确认后才可替换。
- ZIP 替换确认绑定核心归档 SHA-256，确认后内容变化会被拒绝。
- 安装后检查 Python、Node、命令行工具等依赖。缺失时 Skill 自动暂停并显示具体缺项，避免“安装成功但调用必败”。
- 90 天未使用、连续失败或较高失败率会进入复核建议；检查只提示，不会擅自删除用户能力。

### 生命周期

- 支持启用、禁用、卸载、更新检查、手动更新、版本历史、恢复、导出 ZIP 和来源追踪。
- 更新前自动备份当前版本；备份失败即停止更新，不覆盖现有 Skill。
- 恢复先校验归档 SHA-256 和大小，再备份当前版本，避免版本清理与恢复互相删除。
- 默认最多保留 3 个版本，单次恢复归档上限 160 MiB。
- 导出的 ZIP 可在另一台设备重新导入；导出器拒绝符号链接、特殊文件、嵌套 Skill 和超限目录。

### 自动形成 Skill

- 任务经验先进入证据队列，不会因为一次模型自述“成功”就直接发布为 Skill。
- 同一模式需要至少 3 个独立任务证据才能进入待验证状态。
- 用户可合并重复候选、编辑正文、删除来源和确认“验证并启用”。
- 自动生成的 Skill 与外部安装 Skill 共用索引、来源存储、版本服务、依赖检查和运行时匹配，不存在两套互不相通的系统。

## 4. MCP 与连接器

- MCP 支持 Streamable HTTP 与旧版 HTTP/SSE 协商，支持工具、资源、提示模板和显式工具调试。
- 工具目录冻结机制保证运行中被编辑的服务器配置不会悄然改变本次权限。
- 工具名、输入 Schema、结果类型和大小都有边界；图片、文本和结构化结果使用不同上限。
- 高风险工具语义进入复核状态。每次调用还要求本轮 MCP 快照和工具策略同时允许。
- MCP 配置可导入导出，凭据不进入普通导出。凭据失效时保留最后一版可用密文，重新填写失败不会破坏原凭据。
- REST API 支持 OpenAPI JSON 导入、动作选择、凭据、OAuth 2.0/PKCE、逐操作授权和真实调用。
- MCP、API 与本地工具通过统一 `CapabilityToolProvider` 路由，避免 Agent Runtime 为每种协议各写一套分叉调用逻辑。

## 5. 本轮补强

- Skill 版本恢复增加归档读取校验、自动备份、失败原因和清理边界保护。
- Skill 包增加安全导出闭环和 SAF 写文件流程，成功显示文件数、大小和摘要。
- 手动 ZIP 替换、市场更新和 Agent 自建 Skill 更新全部加入更新前备份。
- 新增备份、恢复、导出、嵌套 Skill、符号链接、冲突和回滚测试。
- Skill 管理页在中文字体放大和窄屏尺寸下完成截图回归，导出、版本、删除和复核状态使用统一 Miuix 组件与图标。

## 6. 当前明确限制

- MCP 目前支持 Bearer；带动态客户端注册的 MCP OAuth 授权流程尚未完成。不要把 REST API 的 OAuth 完成度等同于 MCP OAuth。
- Skill 依赖目前负责识别、暂停和说明，不会无视 Android 环境自动安装 Python、Node 或系统包。
- 没有经过验证的国内公共镜像源，因此没有预置虚假镜像。用户可添加合规 HTTPS 目录源作为 fallback。
- OpenAPI 当前支持 JSON、JSON 请求体和路径/查询/Header 参数；YAML、multipart、cookie 和远程 `$ref` 尚未支持。
- 能力包升级成功后的完整旧版快照尚未实现；失败安装有恢复日志和回滚，卸载也有级联清理。
- 搜索结果不能证明第三方 Skill 的内容绝对安全。安装门禁降低包结构风险，用户仍需对来源信任与 Skill 内脚本负责。
- 本轮没有以模拟器替代真机验证。设备在线时只向真实 OPPO 手机安装；离线则保留桌面 APK。

## 7. 使用步骤

### 搜索和安装 Skill

1. 进入“设置 → 能力中心 → Skill 发现”。
2. 输入用途关键词，例如“Excel 数据处理”或“PPT 制作”。
3. 查看来源、版本、依赖和校验信息，打开详情核对用途与安装说明。
4. 点击安装；若来源无校验和或存在用户 Skill 冲突，确认页会明确说明影响。
5. 安装后到“Skill 管理”检查依赖和启用状态。Agent 后续会在相关任务中自动匹配，也可要求它先列出适用 Skill。

### 管理版本与迁移

1. 在 Skill 行菜单中选择“版本记录”。
2. 恢复旧版前确认会自动备份当前版本。
3. 选择“导出 Skill 包”，保存为 ZIP。
4. 在目标设备选择“从 ZIP 导入”或“从本地文件夹导入”。

### 添加 MCP

1. 进入“能力中心 → MCP”。
2. 添加 HTTPS MCP 地址并刷新工具。
3. 只启用确实需要的工具；需要 Bearer 时在服务器详情填写令牌。
4. 可先使用“测试此工具”检查参数和结果，再用聊天中的 Agent 调用。

### 添加 REST API

1. 导入 JSON 格式 OpenAPI 文档。
2. 选择基础地址和要暴露的操作，逐项启用。
3. 选择无认证、Bearer、Header Key 或 OAuth 2.0/PKCE。
4. OAuth 连接需在浏览器完成授权，回到 Eta 后刷新连接状态。
5. 回到聊天要求 Agent 调用该连接。MCP/API 工具结果默认视为敏感内容，不会进入普通公开统计。

### 安装能力包

1. 选择“导入能力包”。
2. 检查包内 Skill、API 操作和 MCP 连接数量。
3. 安装后逐项配置凭据并启用需要的工具。
4. 在能力包列表卸载时，系统会清理其关联 Skill、API、MCP 和凭据；任何一步失败会保留恢复状态。

## 8. 验证口径

- Kotlin 主代码与单元测试代码编译通过。
- 能力中心、Skill、ZIP 替换保护和双尺寸 UI 专项 133 项通过，7 项仅因 Windows 无符号链接特权按平台跳过。
- 最终 APK 仍需完成全量单元测试、Lint 和 Debug 构建后才可交付。
- 真机上的网络可达性、OAuth 回调、MCP 服务器兼容、ColorOS 后台限制和具体 Skill 依赖必须由用户在实际设备上确认。
