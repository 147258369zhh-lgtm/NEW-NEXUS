package io.github.mangi.eta.data.repository

internal enum class EtaBackupFormat(val suffix: String, val title: String) {
    FULL(".eta-backup", "完整迁移包"),
    JSON(".json", "可选数据 · JSON"),
}

@kotlinx.serialization.Serializable
internal enum class EtaBackupCompression(val title: String, val detail: String) {
    FAST("快速", "不压缩文件内容，速度更快，适合大目录和定期备份"),
    COMPACT("紧凑", "压缩文本与数据库，文件更小，但大目录可能需要更久"),
}

@kotlinx.serialization.Serializable
internal enum class EtaBackupContent(val title: String, val detail: String) {
    MODELS("模型配置", "服务商、模型及默认选择"),
    CHATS("会话与角色", "消息、分支、上下文检查点及角色资料"),
    MEMORY("分层记忆", "全局和项目等记忆文档、记忆条目"),
}

internal data class EtaBackupOptions(
    val format: EtaBackupFormat = EtaBackupFormat.FULL,
    val contents: Set<EtaBackupContent> = EtaBackupContent.entries.toSet(),
    val includeAppFiles: Boolean = false,
    val compression: EtaBackupCompression = EtaBackupCompression.FAST,
) {
    fun validated(): EtaBackupOptions {
        require(contents.isNotEmpty()) { "请至少选择一种备份内容" }
        return if (format == EtaBackupFormat.FULL) copy(contents = EtaBackupContent.entries.toSet()) else this
    }
}
