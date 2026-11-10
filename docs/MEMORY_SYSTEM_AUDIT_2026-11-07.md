# Eta 记忆系统审查与改进报告

日期：2026-11-07

结论：当前记忆功能已经具备可用的第一版基础，安全性和并发写入处理比普通“把聊天摘要塞进提示词”的实现更完整；但它还不能算主流 Agent 级别的完整记忆系统。当前评分约为 **6/10**。建议继续改造，但不需要推翻现有实现，也不建议现在直接把所有聊天自动写入记忆。

## 一、当前实现到底是什么

### 1. 现实记忆

当前主记忆由 `AgentMemoryRepository` 管理，实际是应用私有目录下的一个 `memory/MEMORY.md` 文件：

- 原子写入，避免写到一半损坏。
- SHA-256 revision，写入时要求携带最新 revision，能够发现并发修改。
- 最大 1 MiB 文件限制。
- `memory_get` 支持按行分页和大小写不敏感的文本搜索。
- `memory_write` 支持 `replace_range`、`append`、`clear`。
- 记忆开关是全局设置，关闭后不向模型注入，也不开放记忆工具。
- 备份和恢复已经包含 `MEMORY.md`。

相关实现：

- [AgentMemoryRepository.kt](../app/src/main/kotlin/io/github/mangi/eta/data/repository/AgentMemoryRepository.kt)
- [AgentMemoryToolCatalog.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentMemoryToolCatalog.kt)
- [AgentLocalTools.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentLocalTools.kt)

### 2. 现有“分层”

当前支持六个 Markdown 标题层：

- 核心记忆
- 项目记忆
- 任务记忆
- 长期记忆
- 工作记忆
- 其他记忆

旧版没有标题的内容会被当作核心记忆。模型启动时自动注入核心记忆、标题索引和一部分其他层；核心记忆预算约为上下文窗口的十六分之一，并限制在 4,000 到 32,000 字符之间。

相关实现：

- [LayeredMemoryDocument.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/memory/LayeredMemoryDocument.kt)
- [AgentMemoryContext.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/memory/AgentMemoryContext.kt)
- [AgentPromptBuilder.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentPromptBuilder.kt)

### 3. 会话和角色记忆

- 普通会话历史由会话数据库和上下文压缩系统保存，这是短期会话记忆，不等同于长期记忆。
- `ConversationMemoryPins` 会在一个会话内固定记忆快照，只有外部修改、压缩或模型切换时才重新注入，目的是保护提示词缓存。
- 角色会话有独立的角色剧情记忆目录，不会写入现实 `MEMORY.md`。
- 角色记忆复用了同一套文件存储和 revision 机制。

相关实现：

- [ConversationMemoryPins.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/memory/ConversationMemoryPins.kt)
- [RoleplayRunContext.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/roleplay/RoleplayRunContext.kt)
- [CharacterMemoryRepository.kt](../app/src/main/kotlin/io/github/mangi/eta/data/repository/CharacterMemoryRepository.kt)

## 二、做得好的地方

### 安全边界比较清楚

系统提示明确告诉模型记忆是用户可编辑的背景资料，不是高优先级指令；同时禁止保存密钥、验证码、凭据和一次性请求。角色剧情记忆和现实记忆也做了隔离。这一点比很多简单的“自动记忆”实现更稳妥。

### 写入不会静默覆盖他人修改

revision 校验、原子写入和大小上限已经形成完整的文件级一致性保护。多个会话同时改记忆时，后写入的一方会得到冲突，而不是把前一方内容悄悄覆盖。

### 已经考虑了上下文成本

核心记忆自动注入，详细内容按需调用 `memory_get`，并且使用固定快照保护缓存。这与主流 Agent 的“启动摘要 + 按需展开”方向一致。

### 迁移和可读性较好

使用 Markdown，用户可以直接阅读、编辑和备份，也为以后与 Obsidian 交换数据保留了可移植格式。

## 三、与主流系统的差距

### Codex 的主要做法

OpenAI 官方文档把 Codex 的持久上下文拆成互补的层：项目指导文件（`AGENTS.md`）、记忆、Skills、MCP 和子 Agent。Codex 记忆是本地生成的记忆文件，支持按聊天控制“读取现有记忆”和“参与未来记忆生成”，后台闲置后再生成，且会跳过活跃或过短会话并做秘密字段脱敏。官方还强调：必须长期生效的规则放在 `AGENTS.md`，记忆只作为有用的回忆层。

Eta 目前已经有“记忆文件 + 工具 + 启动注入”，但还缺少：

- 规则与记忆的明确分离机制。
- 每个会话是否读取、是否贡献记忆的控制。
- 空闲后的候选记忆提取和后台整合。
- 记忆生成历史、来源和删除控制。

参考：[OpenAI Codex Memories](https://learn.chatgpt.com/docs/customization/memories)、[Codex customization overview](https://learn.chatgpt.com/docs/customization/overview)。

### Claude Code 的主要做法

Claude Code 官方将 `CLAUDE.md`/`AGENTS.md` 与 auto memory 分开：前者由用户维护，用于规则、项目架构和工作流；后者由 Claude 记录纠正、偏好和项目经验。两者可以按用户、组织、项目、目录和路径匹配分层加载；auto memory 有独立存储、每仓库范围和长度限制，也可以通过 `/memory` 查看和编辑。

Eta 的六个标题层在“内容分类”上已经比单一文件更丰富，但仍有明显差距：

- “项目记忆”没有项目 ID，所有项目共用同一个文件。
- “任务记忆”没有任务 ID、完成状态或自动过期时间。
- “工作记忆”没有会话/运行 ID，无法自然清理。
- 没有目录或路径范围，不能做到只在相关项目/文件夹注入。
- 没有自动记忆与用户维护规则的双轨模型。

参考：[Claude Code memory](https://code.claude.com/docs/en/memory)。

### 主流 Agent 的共同趋势

主流实现通常不是单纯增加“第 3 层、第 4 层”标题，而是同时具备四个维度：

1. **作用域**：用户、项目、会话、任务、角色、组织或路径。
2. **生命周期**：永久、有效期、任务结束清理、手动归档和删除。
3. **检索策略**：启动摘要、关键词检索、语义检索、时间和作用域排序。
4. **来源与可信度**：用户明确确认、工具事实、模型推断、外部导入，并能回溯和纠错。

Eta 当前主要实现了“内容层级”和“文件级生命周期”，还没有完成另外三个维度。

## 四、当前最重要的实际问题

### P0：项目和任务记忆会互相污染

`layer=project` 和 `layer=task` 只是把内容追加到全局 `MEMORY.md` 的对应标题下，并没有保存项目、会话或任务标识。用户同时做多个项目时，模型启动时仍会看到这些项目内容的混合索引，详细内容也可以被全局搜索到。

这会导致：

- A 项目的配置被 B 项目误用。
- 已结束任务的临时信息长期占用记忆。
- 会议室和普通会话没有天然的记忆隔离。
- 删除一个项目时无法只删除该项目的记忆。

### P0：角色记忆的 layer 参数没有真正落地

角色记忆复用了现实记忆的 schema，但 `CharacterMemoryTools` 的 `append` 分支目前直接追加文本，没有处理 `layer` 参数。因此角色工具表面上支持分层，实际不会按核心、项目、任务等层写入。这是一个一致性 bug，应在下一轮修复或从角色 schema 中删除该参数。

### P1：没有自动提取和整合

当前模型只有在用户明确说“记住”或主动决定时才调用 `memory_write`。系统没有在会话空闲后提取候选事实、去重、合并、降级或提示用户审核的后台流程。因此用户不明确说“记住”的偏好、纠正和项目决策容易丢失。

### P1：检索仍然是文本行搜索

关键词检索适合精确查找，但不适合“我上次决定的方案”“之前那个错误的原因”这类语义问题。当前没有分词、同义词、时间排序、作用域排序、向量或混合检索，也没有“为什么命中这条记忆”的证据展示。

### P1：记忆条目没有元数据

每条记忆当前只有 Markdown 文本，没有结构化保存：

- 来源会话、项目、任务或工具。
- 创建时间、最后确认时间和最后使用时间。
- 用户确认状态。
- 可信度和冲突状态。
- 有效期或清理策略。
- 被哪条新记忆替代。

没有这些元数据，系统无法可靠地自动遗忘、冲突合并或解释记忆来源。

### P2：控制粒度不足

当前只有全局记忆开关。缺少：

- 当前会话读取记忆开关。
- 当前会话是否允许贡献新记忆。
- 项目级开关。
- 会议室是否共享记忆、按成员隔离或只读。
- 工具调用/MCP/社交连接等外部上下文是否禁止贡献记忆。

### P2：维护界面仍是单文档编辑器

当前 UI 适合查看和手工编辑 `MEMORY.md`，但不适合管理大量结构化记忆。没有按作用域、层、状态、来源筛选，也没有单条记忆的确认、纠错、过期和删除操作。

## 五、建议的目标架构

不建议简单把 Markdown 文件继续拆成五个文件，也不建议一开始就强制依赖云端向量数据库。建议保留 Markdown 导入导出，内部改成“结构化记忆索引 + Markdown 兼容投影”。

### 目标作用域

建议固定为以下六类：

1. 用户长期记忆：跨所有会话的稳定偏好和事实。
2. 项目记忆：绑定项目 ID 或项目文件夹，只在该项目相关会话注入。
3. 会话记忆：当前会话的决定、约束和已确认上下文。
4. 任务记忆：当前任务的计划、状态和临时证据，完成或过期后清理。
5. 角色/会议记忆：角色独立；会议默认独立，可选择共享项目记忆。
6. 工作缓存：运行期间的短期观察、工具结果索引和压缩辅助信息。

“核心、长期、项目、任务、工作”可以继续作为优先级或类型，但不要再把它们当成唯一的隔离方式。**作用域和记忆类型必须是两个独立字段。**

### 记忆条目最小字段

建议内部模型至少包含：

```text
id, scope_type, scope_id, layer, key, content,
source_kind, source_id, confidence, confirmation_state,
created_at, updated_at, last_used_at, expires_at,
supersedes_id, status, search_text
```

其中 `source_kind` 区分用户明确确认、工具真实结果、会话总结和模型推断；模型推断不能直接获得与用户确认相同的优先级。

### 检索和注入

建议采用渐进披露：

- 首轮只注入当前作用域的短摘要和少量高置信度核心条目。
- 模型调用 `memory_search` 获取候选条目，返回命中原因、来源、时间和作用域。
- 只有需要详细背景时才调用 `memory_get`。
- 初期先用 SQLite FTS/关键词 + 作用域和时间排序，后续再加可选本地 embedding；不把 embedding 当作第一阶段硬依赖。

### 自动记忆流程

建议参考 Codex 的后台闲置生成方式：

1. 会话结束或闲置后生成候选记忆，不在每条消息结束时立即调用模型。
2. 过滤秘密、验证码、凭据、精确敏感个人信息和一次性请求。
3. 与已有条目去重并检测冲突。
4. 用户偏好、项目规则和事实性决策可以进入“待确认”状态。
5. 用户可以在 UI 中确认、修改、拒绝或设置过期时间。
6. 高敏感或高影响内容默认不自动写入。

手机端要增加速率阈值、充电/空闲条件和失败重试上限，避免后台耗电和无感产生大量 token。

## 六、分阶段改正计划

### 第一阶段：正确性和隔离（必须做）

- 修复角色记忆 `layer` 参数不生效的问题。
- 引入 `scope_type/scope_id`，至少支持全局、项目、会话、任务、角色、会议。
- 普通会话和会议室默认只读取自身作用域，以及用户全局核心记忆。
- 任务层支持完成后清理和过期时间。
- 保留旧 `MEMORY.md` 自动导入为用户全局记忆，继续支持导出为 Markdown。
- 增加迁移、备份恢复和跨版本回滚测试。

### 第二阶段：检索和管理（强烈建议做）

- SQLite 结构化条目表和 FTS 索引。
- 新增 `memory_search`，保留 `memory_get` 作为详情读取。
- 返回来源、作用域、最后确认时间、过期时间和命中原因。
- UI 从单文档编辑扩展为“总览、项目、会话、待确认、已过期”视图。
- 增加单条记忆删除、归档、纠错、固定和恢复。

### 第三阶段：自动提取和整合（建议做）

- 后台闲置候选提取。
- 规则化脱敏和敏感内容拒绝。
- 去重、冲突检测、被替代关系和摘要整合。
- 会话级“允许读取/允许贡献”控制。
- MCP、网页、社交连接和外部文件上下文默认不自动贡献长期记忆，除非用户明确开启。

### 第四阶段：高级能力（可选）

- 本地 embedding 或混合语义检索。
- 项目文件夹和 Obsidian 双向同步，但要使用明确的导入/导出边界和冲突版本。
- 会议共享策略：只读项目记忆、成员隔离记忆、会议总结写入项目待确认区。
- 记忆质量评测：命中率、误用率、过期率、冲突率、每轮 token 增量和用户纠错次数。

## 七、是否需要现在修改

需要修改，但建议先做第一阶段，不要立即上“自动学习 + 向量数据库”。

当前版本可以继续使用，适合：

- 用户手工维护少量全局偏好。
- 小规模的核心、项目和长期 Markdown 记忆。
- 角色剧情的独立连续性。

当前版本不适合直接承诺：

- 多个项目同时工作时的严格记忆隔离。
- 自动记住所有重要决定。
- 大规模历史的语义回忆。
- 记忆来源和过期状态的可审计管理。

因此结论不是“现有记忆不用改”，而是：**现有底座可以保留，下一步必须先把作用域、条目元数据和检索边界补齐，再做自动提取。**

## 八、审查依据

- OpenAI 官方 Codex Memories：<https://learn.chatgpt.com/docs/customization/memories>
- OpenAI 官方 Codex customization：<https://learn.chatgpt.com/docs/customization/overview>
- Claude Code 官方 memory：<https://code.claude.com/docs/en/memory>
- 本地实现和测试：见本文引用的 Kotlin 文件及 `app/src/test/kotlin/io/github/mangi/eta/agent/memory/`、`app/src/test/kotlin/io/github/mangi/eta/data/repository/`。
