# P07 分层记忆

新增 `LayeredMemoryDocument`，以 Markdown 标题承载核心、项目、任务、长期、工作和其他记忆层。旧的单段 `MEMORY.md` 会作为核心记忆读取，新的内容仍然是普通 Markdown，可被 Obsidian 直接打开；注入模型时按层级和字符预算排序，避免一次性塞入全部历史。

本批已接入 memory_write 的 layer 参数：写入会合并到对应标题，旧 Markdown 自动迁移为核心层；读取仍遵循核心优先和预算截断规则。安装包见 P07–P08 双批出口记录。
