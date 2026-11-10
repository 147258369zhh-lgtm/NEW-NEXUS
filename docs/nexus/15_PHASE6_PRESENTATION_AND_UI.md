# Nexus 阶段 6：统一渲染与 Classic/Omni Presentation Profile

## 本轮实现

- Kernel 已提供 Text、Markdown、Math、Code、Image、Audio、File、ToolTrace、PlanCard、UserInput、Approval 和 Artifact 的 ContentPart 合同。
- 新增纯 `PresentationProfileResolver`，把 `classic` 和 `omni` 映射为正式 Presentation Profile。
- 未知 Profile 自动回退 Classic，并标记 `unknown-profile-fallback`。
- `AgentAppTheme` 现在通过 `LocalNexusPresentationProfile` 向整个 UI 树提供当前 Profile。
- Omni 声明 Classic fallback，现有设计 token、布局和外观设置保持不变。
- 新增 `NexusContentPartRenderer`，为 Text、Markdown、Math、Code、Image、Audio、File、ToolTrace、PlanCard、UserInput 和 Approval 提供同构渲染入口。
- 音频 ContentPart 使用异步 MediaPlayer 准备，避免网络音频阻塞 UI 主线程。
- 用户输入和审批 ContentPart 已具备选项、自由文本、批准和拒绝交互回调，供普通会话、会议室和 DSH Host 复用。
- AgentMessageUi 已可携带结构化 ContentPart，普通会话和会议室会优先通过统一 adapter 渲染。
- 外观页增加全局字体选择，支持系统、无衬线、衬线、等宽和随包 JetBrains Mono。

## 当前边界

- 当前 Miuix 页面已经消费设计 token，但尚未全部迁移为只依赖 Presentation Profile 的独立布局模块。
- ContentPart adapter 已完成；会议和远端 DSH 的历史 Item 投影仍需在实际 Host 接线后接入。
- Classic/Omni 的功能一致性回归、字体分区域设置和所有手势仍需真机验收。

