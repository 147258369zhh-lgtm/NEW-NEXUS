# Nexus 阶段 3：能力、工具权限与设置控制面（启动批次）

## 目的

阶段 3 的目标是让模型看到的工具、执行器接受的调用、当前设备能力和任务授权使用同一套合同。启动批次先建立 fail-closed 的权限内核，再逐步接入现有 Agent Loop。

## 已实施

### 能力目录

- 新增 `CapabilityDirectory`：目录摘要、完整描述和 activation 分离。
- 新增 `InMemoryCapabilityDirectory`，验证目录激活行为。
- 新增 `NexusCapabilityDirectory`，从现有本地工具 Schema 构建 Nexus ToolDescriptor。
- ToolDescriptor 包含 namespace、Schema 引用、HostKind、EffectClass、ReplayPolicy 和 requiredCapabilities。
- `activate` 在激活前重新检查当前设备能力，不用启动时缓存。
- `NexusLazyToolCatalog` 已接入主 Agent Loop：模型首轮只看到 `tool_search`、`tool_describe`、`tool_activate`，不再直接收到全部本地或运行时附加工具 Schema。
- 激活后的完整 Schema 在下一轮进入请求；单次 Run 最多保持 16 个领域工具为活动状态。
- Root、LSPosed、无障碍、通知、Usage、位置和日历能力会参与 activation；`CapabilityPolicyAdapter` 对高危工具执行同一 Grant 校验。
- MCP、Connector、历史、记忆和 Subagent 入口也作为本次 Run 的附加工具源进入同一 lazy catalogue；未知外部工具默认 HIGH_RISK。

### 工具效果分类

- 读取、GUI、可逆写入、幂等写入、外部副作用和高风险工具分别映射到 Kernel `EffectClass`。
- 未登记或未知工具默认 `HIGH_RISK`。
- Read 才允许自动重放；GUI、外部副作用、非幂等写入和高风险操作要求用户。
- Root、LSPosed、无障碍、通知、Usage、位置、日历和厂商能力转成 required capability。

### Tool Grant

`CapabilityPolicyEngine` 在授权时检查：

- Grant 是否过期。
- Tool HostKind 是否匹配。
- Tool 是否位于任务白名单。
- Effect 是否位于任务授权。
- HighRisk 是否已有本机批准。
- 每个 required capability 是否 available、granted、authorized 且未过期。
- Capability 是否属于 Grant 指定 Host。

该策略为纯 Kotlin，并已有单元测试。

### Settings Control Plane

- 新增本地 Agent、软件管家、本地用户和远端 Agent 的 actor 类型。
- Remote Agent 永远只能提交请求。
- Security/Destructive 设置需要本地确认。
- Secret 读取只返回 configured、fingerprint 和更新时间，不提供明文 getter。
- 新增 `NexusSettingsControlPlane` 适配现有 `EtaAppControlRegistry`。
- 现有审计写入现在会返回 auditId。
- 审计现在保存变更前后快照；当前模型、备用模型、记忆、外观、MCP 服务/工具、Skill 和自学习设置提供真实声明式回滚。
- `settings.appearance` 允许软件管家直接读取和修改皮肤、主题、配色、字体、模糊、手势与缩放。
- 不可逆或未声明回滚的能力仍明确拒绝撤销，不执行猜测性恢复。

## 尚未接入，不能宣称完成

- 主 Agent Loop 和 Subagent Loop 已按需加载工具源；内置嵌入文本模型仍需单独验收。
- ToolDescriptor 尚未替换执行器内部的工具注册表；它目前控制目录、activation 和授权。
- 实际调用前已有任务黑名单、Skill 策略、设备可用性检查和 activation Grant 校验；完整 verifier/concurrencyKey 仍未逐工具补齐。
- Settings rollback 仍按能力逐项声明；尚未覆盖的设置不能伪造通用回滚。
- 插件 Sandbox Host、MCP stdio/HTTP 生命周期、DSH Host 和 Root 插件审批尚未实现。
- 热门工具自适应预加载尚未实现；默认仍沿用现有工具集合。

## 下一批工作

1. 将 Subagent 内部 Loop 和内置嵌入文本模型接入同一 lazy catalogue。
2. 将 `AgentLocalTools.beforeToolExecution` 与 `runToolExecutor` 的现有检查合并到统一 ToolDescriptor gate。
3. 为现有工具补齐 verifierId、concurrencyKey 和更细的 effect 分类。
4. 继续为 MCP、Skill、自动化等可逆设置补充 rollback 声明。
5. 接入 Settings 能力分类 UI 和按来源显示的授权范围。

## 本轮验证

- `CapabilityPolicyEngineTest` 通过：高风险必须有本机批准，并要求可用 capability。
- `SettingsMutationPolicyTest` 通过：Remote 只能提交请求，安全设置需要本地确认。
- `NexusToolEffectClassifierTest` 通过：未知工具默认 HIGH_RISK，终端与读取工具恢复策略不同。
- `NexusLazyToolCatalogTest` 通过：目录搜索、本地/附加工具 activation、权限限制和授权拒绝均生效。
- App Debug Kotlin 编译通过。
