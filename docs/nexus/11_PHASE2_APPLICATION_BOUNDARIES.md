# Nexus 阶段 2：Application API 与边界解耦（启动批次）

## 目的

阶段 2 先把稳定入口和依赖方向建立起来，再移动大体量业务文件。启动批次不改变聊天、会议或 A2A 的用户结果。

## 已实施

### Application API

新增无 Android、Compose、Room 和 Miuix import 的 `application-api` 模块，冻结：

- Run 提交、取消与事件监听合同。
- Conversation Summary 查询合同。
- Conversation Artifact 查询合同。
- Settings Control Plane 合同。
- Capability Directory、Tool Grant、Policy Decision 与 HostAdapter 合同。

`kernel-api` 继续保存最小共享类型；`application-api` 只依赖 `kernel-api`。架构检查已同时扫描两个模块。

### Runtime 与 UI 启动隔离

- 新增 `NexusUiContract`，统一定义 UI action、extra 与 launcher Intent。
- Runtime、A2A、Skill 更新、后台终端、自动任务、朗读和语音入口不再在源码中引用具体 `MainActivity`。
- 新增 `NexusUiPresence`，UI 只更新前台状态；Runtime 不再读取 `MainActivity` 静态字段。
- 通知和结果卡仍能打开 Nexus，但启动通过 launcher contract 完成。

### 附件模型

- `AgentFileReference`、图片草稿和文件草稿移动到 `kernel-api`。
- 旧 `agent.model` 和 `ui.model` 名称通过 typealias 保留，避免一次性改动 UI 调用面。
- `ConversationAttachmentDrafts` 不再依赖 `ui.model`。

### 自动归档

- `ConversationArchiveWorker` 不再加载 UI `AgentConversationStore`。
- Pinned 状态直接查询会话数据库。
- Running 状态通过 Runtime IPC 的 active-run 快照获取。
- Runtime 状态不可用时失败关闭并重试，避免把运行中的会话自动归档。

## 当前审计结果

- `data -> ui`：从 2 降为 0。
- `fork -> ui`：从 2 降为 0。
- 明确禁止的反向 import 总数：从 97 降为 84。
- `agent/runtime -> ui`：0，并加入 Gradle 架构门禁。

## 仍需完成

- `agent/voice` 与 `agent/overlay` 仍有 17 个 Compose/UI model import；后续应把 voice/overlay presentation 移到 UI 层，Runtime 只发 declarative state。
- `data -> agent` 仍有 53 个 import。Provider、角色、终端和备份数据模型需要逐步上移为 Provider API、Capability API 或 Store DTO。
- `data -> fork` 仍有 14 个 import，主要集中在完整备份和会话分支协调器。
- UI 仍有 835 个直接 import agent/data/fork 的基线依赖；这些需要在 Application API 实现和 ViewModel 改造后逐组迁移。
- `application-api` 目前是合同与首层适配，不代表 UI 已全部改为调用该门面。

## 当前门禁

Gradle `checkNexusArchitecture` 已强制执行：

- `kernel-api` 和 `application-api` 禁止 Android、Compose、Room、Miuix 和旧 App import。
- `data`、`fork` 和 `agent/runtime` 禁止 import `io.github.mangi.eta.ui.*`。

新增违规会直接使构建失败。

## 本轮验证

- `checkNexusArchitecture` 通过。
- `application-api`、`kernel-api` 和 App Kotlin 编译通过。
- 新增 `NexusToolEffectClassifierTest` 通过。
- 配置缓存成功复用。

