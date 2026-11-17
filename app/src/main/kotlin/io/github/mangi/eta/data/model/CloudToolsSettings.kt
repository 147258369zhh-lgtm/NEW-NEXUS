package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
internal data class CloudDeepSeekConfig(
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
)

@Serializable
internal data class CloudBailianConfig(
    val workspaceId: String = "",
    val region: String = "cn-beijing",
    /** An explicit HTTPS endpoint overrides workspace/region host construction. */
    val baseUrl: String = "",
    val imageModel: String = "wan2.6-t2i",
    val imageEditModel: String = "qwen-image-2.0-pro",
    val videoUnderstandingModel: String = "qwen3-vl-plus",
    val videoGenerationModel: String = "wan2.7-t2v",
    val imageToVideoModel: String = "wan2.6-i2v-flash",
    val embeddingModel: String = "qwen3.7-text-embedding",
    val rerankModel: String = "qwen3.7-text-rerank",
    val translationModel: String = "qwen-mt-plus",
)

@Serializable
internal data class CloudBaiduConfig(val baseUrl: String = "https://aip.baidubce.com")

@Serializable
internal data class CloudTencentConfig(
    val baseUrl: String = "https://tms.tencentcloudapi.com",
    val region: String = "ap-guangzhou",
    val bizType: String = "",
)

@Serializable
internal data class CloudCapabilityRoute(
    val primary: String,
    val backup: String = "",
    val timeoutSeconds: Int = 180,
    val maxFileMiB: Int = 100,
    val enabled: Boolean = true,
)

@Serializable
internal data class OnlineImageGenerationConfig(
    val enabled: Boolean = false,
    /** API base including /v1, or the complete /images/generations endpoint. */
    val baseUrl: String = "",
    val model: String = "",
    val protocol: String = "openai_images",
    /** auto omits response_format (required by GPT image models); other values need provider support. */
    val responseFormat: String = "auto",
)

@Serializable
internal data class CloudToolsSettings(
    val enabled: Boolean = false,
    val onlineImage: OnlineImageGenerationConfig = OnlineImageGenerationConfig(),
    val deepSeek: CloudDeepSeekConfig = CloudDeepSeekConfig(),
    val bailian: CloudBailianConfig = CloudBailianConfig(),
    val baidu: CloudBaiduConfig = CloudBaiduConfig(),
    val tencent: CloudTencentConfig = CloudTencentConfig(),
    val routes: Map<String, CloudCapabilityRoute> = CloudCapabilities.defaultRoutes(),
)

/** These values are encrypted separately and must never be serialized into ordinary backups. */
@Serializable
internal data class CloudToolsCredentials(
    val onlineImageApiKey: String = "",
    val deepSeekApiKey: String = "",
    val bailianApiKey: String = "",
    val baiduApiKey: String = "",
    val baiduSecretKey: String = "",
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
) {
    fun configured(provider: String): Boolean = when (provider) {
        "deepseek" -> deepSeekApiKey.isNotBlank()
        "bailian" -> bailianApiKey.isNotBlank()
        "baidu" -> baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()
        "tencent" -> tencentSecretId.isNotBlank() && tencentSecretKey.isNotBlank()
        else -> false
    }

    override fun toString() = "CloudToolsCredentials(redacted)"
}

internal data class CloudCapabilityDefinition(
    val operation: String,
    val label: String,
    val providers: List<String>,
)

internal object CloudCapabilities {
    val definitions = listOf(
        CloudCapabilityDefinition("ocr", "文字识别", listOf("deepseek", "baidu")),
        CloudCapabilityDefinition("file_upload", "文档上传解析", listOf("deepseek")),
        CloudCapabilityDefinition("file_status", "文档状态", listOf("deepseek")),
        CloudCapabilityDefinition("file_list", "云端文档列表", listOf("deepseek")),
        CloudCapabilityDefinition("file_delete", "删除云端文档", listOf("deepseek")),
        CloudCapabilityDefinition("file_question", "文档问答", listOf("deepseek")),
        CloudCapabilityDefinition("vision", "图片理解", listOf("deepseek")),
        CloudCapabilityDefinition("baidu_ocr", "百度高精度识别", listOf("baidu")),
        CloudCapabilityDefinition("document_parse", "复杂文档解析", listOf("baidu")),
        CloudCapabilityDefinition("document_status", "复杂文档解析状态", listOf("baidu")),
        CloudCapabilityDefinition("table_recognize", "表格识别", listOf("baidu")),
        CloudCapabilityDefinition("embedding", "知识库向量化", listOf("bailian")),
        CloudCapabilityDefinition("rerank", "知识库重排", listOf("bailian")),
        CloudCapabilityDefinition("translate", "翻译", listOf("bailian")),
        CloudCapabilityDefinition("moderate", "独立文本审核", listOf("tencent")),
        CloudCapabilityDefinition("image_generate", "生成图片", listOf("bailian")),
        CloudCapabilityDefinition("image_status", "图片生成状态", listOf("bailian")),
        CloudCapabilityDefinition("image_edit", "编辑图片", listOf("bailian")),
        CloudCapabilityDefinition("video_understand", "视频理解", listOf("bailian")),
        CloudCapabilityDefinition("video_generate", "生成视频", listOf("bailian")),
        CloudCapabilityDefinition("video_status", "视频生成状态", listOf("bailian")),
        CloudCapabilityDefinition("media_tasks", "当前会话媒体任务", listOf("bailian")),
        CloudCapabilityDefinition("speech_transcribe", "DeepSeek 语音转写", listOf("deepseek")),
        CloudCapabilityDefinition("speech_synthesize", "DeepSeek 语音合成", listOf("deepseek")),
    )

    fun defaultRoutes(): Map<String, CloudCapabilityRoute> = definitions.associate {
        it.operation to CloudCapabilityRoute(primary = it.providers.first())
    }
}

internal class CloudToolsRuntimeConfig(
    val settings: CloudToolsSettings,
    val credentials: CloudToolsCredentials,
    val imageProviders: List<ProviderSetting> = emptyList(),
) {
    fun deepSeekBaseUrl(): String = settings.deepSeek.baseUrl.trim().trimEnd('/')

    fun bailianBaseUrl(): String {
        settings.bailian.baseUrl.trim().takeIf(String::isNotEmpty)?.let { return it.trimEnd('/') }
        val workspace = settings.bailian.workspaceId.trim()
        val region = settings.bailian.region.trim()
        require(workspace.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,127}"))) { "百炼 Workspace ID 未配置或无效" }
        require(region.matches(Regex("[a-z][a-z0-9-]{1,40}"))) { "百炼地域无效" }
        return "https://$workspace.$region.maas.aliyuncs.com"
    }

    fun route(operation: String): CloudCapabilityRoute {
        check(settings.enabled) { "云能力尚未启用" }
        val definition = CloudCapabilities.definitions.firstOrNull { it.operation == operation }
            ?: throw IllegalArgumentException("不支持的云能力")
        val route = settings.routes[operation] ?: CloudCapabilityRoute(definition.providers.first())
        check(route.enabled) { "此云能力已停用" }
        require(route.primary in definition.providers && (route.backup.isBlank() || route.backup in definition.providers)) {
            "此能力的供应商路由无效"
        }
        require(route.timeoutSeconds in 1..600 && route.maxFileMiB in 1..100) { "云能力限制无效" }
        return route
    }

    override fun toString() = "CloudToolsRuntimeConfig(redacted)"
}
