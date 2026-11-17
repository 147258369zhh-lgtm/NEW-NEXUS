package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 只声明真实云执行入口；凭据、输入授权和收费请求去重由执行宿主复核。 */
internal object AgentCloudToolCatalog {
    const val DOCUMENT = "cloud_document"
    const val MEDIA = "cloud_media"
    val names: Set<String> = setOf(DOCUMENT, MEDIA)

    private val documentOperations = linkedMapOf(
        "ocr" to "识别文档文字", "file_upload" to "上传云文档", "file_status" to "查询云文档状态",
        "file_list" to "列出当前范围云文档", "file_delete" to "删除云文档", "file_question" to "云文档问答",
        "vision" to "理解图片", "baidu_ocr" to "高精度文字识别", "document_parse" to "解析复杂文档",
        "document_status" to "查询文档解析状态", "table_recognize" to "识别表格", "embedding" to "生成文本向量",
        "rerank" to "重排检索文档", "translate" to "翻译文本", "moderate" to "审核文本",
    )
    private val mediaOperations = linkedMapOf(
        "image_generate" to "生成图片", "image_edit" to "编辑图片", "image_status" to "查询图片生成状态",
        "video_understand" to "理解视频", "video_generate" to "生成视频", "video_status" to "查询视频生成状态",
        "media_tasks" to "列出当前范围媒体任务", "speech_transcribe" to "转写音频", "speech_synthesize" to "合成语音",
    )

    fun operationLabel(toolName: String, operation: String): String? = when (toolName) {
        DOCUMENT -> documentOperations[operation]
        MEDIA -> mediaOperations[operation]
        else -> null
    }

    fun appendTo(tools: JSONArray) {
        tools.put(AgentToolSchema.function(DOCUMENT,
            "执行已启用并配置独立凭据的云文档能力：文字识别、上传/查询/删除当前范围云文档、文档与图片问答、复杂文档解析、表格、向量、重排、翻译及文本审核。" +
                "提交内容可能向供应商传输并计费。除 file_status/file_list/document_status 外必须提供 request_id；恢复同一请求复用原 ID，状态查询使用原 file_id/task_id，不要重新提交。" +
                "file/image 只能使用用户已授权且本工具宿主可读取的输入引用，禁止猜测 URL、其他应用私有路径或未授权文件。云 ID 及列表只属于当前对话/项目 scope 和供应商账号。",
            documentSchema()))
        tools.put(AgentToolSchema.function(MEDIA,
            "执行已启用并配置独立凭据的云媒体能力：生成/编辑图片、理解/生成视频、查询当前范围媒体任务及 DeepSeek 音频转写/语音合成。" +
                "在线图片模型已启用时，image_generate 使用该独立接口；否则可使用普通供应商中已启用的 OpenAI 或百炼图片模型。普通供应商图片接口同步返回成果，不接受异步模式。生成和理解操作可能计费，必须提供 8–128 位 request_id；中断恢复复用原 ID。返回 pending/task_id 后使用 image_status/video_status 查询，禁止为等待结果重新付费提交。" +
                "输入路径和引用必须已由用户授权，不接受网络 URL，不得猜测其他应用私有路径。task_id/media_tasks 仅限当前对话/项目 scope 和账号。",
            mediaSchema()))
    }

    private fun documentSchema(): JSONObject {
        val properties = commonProperties(documentOperations.keys)
            .put("file", inputReference("已授权文档或图片引用；用于 ocr/file_upload/baidu_ocr/document_parse/table_recognize，也是 vision 的 image 备用字段。"))
            .put("image", inputReference("vision 的已授权 JPEG/PNG/GIF/WebP 图片引用；也可改用当前范围的 file_id。"))
            .put("file_id", resourceId("当前 scope 和供应商账号中 file_upload 返回的文件 ID，用于状态、删除、问答或图片理解。"))
            .put("file_ids", arrayOf(resourceId("当前 scope 已登记的云文件 ID。"), 1, 20, "file_question 引用的 1–20 个已登记文件；也可提供单个 file_id。"))
            .put("task_id", resourceId("document_parse 返回且登记在当前 scope 的任务 ID；仅用于 document_status。"))
            .put("prompt", text(100_000, "file_question/vision 必填：针对已授权云文件或图片的问题。"))
            .put("text", text(100_000, "translate/moderate 必填。翻译最多 100000 字符；审核最多 10000 个 Unicode 字符且 UTF-16 长度不超过 20000。"))
            .put("texts", arrayOf(text(500_000, "待向量化的非空文本。"), 1, 20, "embedding 必填：1–20 条文本，单条最多 500000 字符；模型还可能有更低限制。"))
            .put("documents", arrayOf(text(120_000, "待重排文档正文。"), 1, 500, "rerank 必填：1–500 篇文档；query 与所有文档总计最多 480000 字符。"))
            .put("query", text(16_000, "rerank 必填的检索问题。"))
            .put("top_n", integer(1, 500, "rerank 返回数量，不得超过 documents 数量；默认最多 10。"))
            .put("dimensions", integer(32, 4096, "embedding 可选向量维数，须由所选模型支持。"))
            .put("source_lang", text(80, "translate 源语言；省略为 auto。"))
            .put("target_lang", text(80, "translate 必填目标语言，如 English 或 Chinese。"))
            .put("terms", arrayOf(objectSchema(JSONObject()
                .put("source", text(200, "源术语。"))
                .put("target", text(200, "固定译法。")), listOf("source", "target")), 0, 100, "translate 可选术语对照表。"))
            .put("domains", text(4096, "translate 可选领域说明。"))
            .put("biz_type", text(128, "moderate 可选腾讯审核策略标识；省略沿用已配置策略。"))
            .put("purpose", enumeration(listOf("user_data", "text_recognition", "rich_layout_recognition"),
                "file_upload 默认 text_recognition；PDF/Office 文档必须用识别用途；ocr 不接受 user_data；file_list 可按用途筛选。"))
            .put("expires_seconds", integer(3600, 2_592_000, "file_upload 云文件有效期：1 小时至 30 天；默认 7 天。"))
            .put("after", resourceId("file_list 可选游标，只能引用当前 scope 的已登记文件 ID。"))
            .put("limit", integer(1, 1000, "file_list 单页数量，默认 20；列表始终按当前 scope 过滤。"))
            .put("order", enumeration(listOf("asc", "desc"), "file_list 按创建时间排序，默认 desc。"))
            .put("detail", enumeration(listOf("low", "high", "original", "auto"), "vision 图片细节等级，默认 auto。"))
            .put("max_tokens", integer(1, 32768, "file_question/vision 输出 token 上限，默认 4096。"))
            .put("safety_check", bool("file_question/vision 使用供应商高等级内容检查，默认 true。"))
            .put("language_type", text(40, "百度文字识别可选语言类型。"))
            .put("detect_direction", bool("百度文字识别是否检测文字方向，默认 true。"))
            .put("return_excel", bool("table_recognize 是否返回 Excel，默认 true。"))
            .put("page", integer(1, 1000, "table_recognize 的 PDF/OFD 页码，从 1 开始。"))
            .put("output_prefix", text(64, "可选输出文件名前缀，不是目录或目标路径。"))
        mapOf(
            "analysis_chart" to "解析图表", "merge_tables" to "合并跨页表格", "relevel_titles" to "重整标题层级",
            "recognize_seal" to "识别印章", "return_span_boxes" to "返回文本定位框，默认 true",
            "angle_adjust" to "校正方向", "erase_seal" to "擦除印章", "erase_watermark" to "去除水印", "unwarp" to "矫正文档弯曲",
        ).forEach { (key, description) -> properties.put(key, bool("document_parse 可选：$description。")) }
        val alternatives = JSONArray()
        fun operation(name: String, vararg required: String, references: List<String> = emptyList()) {
            alternatives.put(operationConstraint(name, required.toList(), references))
        }
        operation("ocr", "request_id", "file")
        operation("file_upload", "request_id", "file")
        operation("file_status", "file_id")
        operation("file_list")
        operation("file_delete", "request_id", "file_id")
        operation("file_question", "request_id", "prompt", references = listOf("file_ids", "file_id"))
        operation("vision", "request_id", "prompt", references = listOf("image", "file", "file_id"))
        operation("baidu_ocr", "request_id", "file")
        operation("document_parse", "request_id", "file")
        operation("document_status", "task_id")
        operation("table_recognize", "request_id", "file")
        operation("embedding", "request_id", "texts")
        operation("rerank", "request_id", "query", "documents")
        operation("translate", "request_id", "text", "target_lang")
        operation("moderate", "request_id", "text")
        return objectSchema(properties, listOf("operation")).put("anyOf", alternatives)
    }

    private fun mediaSchema(): JSONObject {
        val properties = commonProperties(mediaOperations.keys)
            .put("provider_id", text(256, "image_generate 可选的供应商 ID；存在多个匹配的图片模型时与 model 一起指定。"))
            .put("prompt", text(8000, "生成/编辑/理解必填。图片 Wan 最多 2000、Qwen 4000；图生视频 1500、文生视频 5000；视频理解 8000 字符。"))
            .put("text", text(10_000, "speech_synthesize 必填；最多 10000 计费字符，汉字按两个字符计。"))
            .put("image_path", inputReference("image_edit 的已授权图片，或 video_generate 图生视频的起始图片；也可使用 path。"))
            .put("image_paths", arrayOf(inputReference("已授权图片引用。"), 1, 4, "image_edit 图片列表；Qwen 最多 3 张，Wan 最多 4 张。"))
            .put("video_path", inputReference("video_understand 的已授权视频引用；也可使用 path，内容最大 30 MiB 并受配置上限约束。"))
            .put("audio_path", inputReference("speech_transcribe 的已授权 16 位 PCM WAV 或 Ogg Opus，最长 120 秒、最大 8 MiB；也可使用 path。"))
            .put("path", inputReference("单个 image_path/video_path/audio_path 的兼容别名，按 operation 解释。"))
            .put("paths", arrayOf(inputReference("已授权图片引用。"), 1, 4, "image_paths 的兼容别名，仅用于 image_edit。"))
            .put("task_id", text(128, "image_status/video_status 必填：当前 scope 和账号中原提交返回的任务 ID。")
                .put("pattern", "^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$"))
            .put("wait_seconds", integer(0, 180, "生成或状态查询最多等待的秒数，默认 0；超时仍 pending 时继续查询原 task_id。"))
            .put("negative_prompt", text(500, "图片或视频的可选负向提示词。"))
            .put("n", integer(1, 6, "生成/编辑图片数量，默认 1；Wan 和在线图片接口最多 4，Qwen 最多 6。"))
            .put("size", text(9, "图片宽*高，如 1280*1280；Wan 编辑另可使用 1K/2K，最终仍受模型像素和宽高比限制。")
                .put("pattern", "^([1-9][0-9]{2,3}\\*[1-9][0-9]{2,3}|[12]K)$"))
            .put("seed", integer(0, Int.MAX_VALUE, "图片或视频的可选随机种子。"))
            .put("prompt_extend", bool("图片或视频是否自动扩展提示词，默认 true。"))
            .put("watermark", bool("图片或视频是否添加水印，默认 false。"))
            .put("async", bool("Wan 图片是否使用异步接口，默认 false；Qwen 不接受 true。视频始终异步。"))
            .put("resolution", enumeration(listOf("720P", "1080P"), "视频分辨率，默认 720P。"))
            .put("duration", integer(2, 15, "视频时长（秒），默认 5；具体取值仍须符合所选模型。"))
            .put("ratio", enumeration(listOf("16:9", "9:16", "1:1", "4:3", "3:4"), "文生视频宽高比，默认 16:9。"))
            .put("shot_type", enumeration(listOf("single", "multi"), "图生视频的可选镜头类型。"))
            .put("audio", bool("仅 wan2.6-i2v-flash：是否生成音频。"))
            .put("fps", JSONObject().put("type", "number").put("minimum", 0.1).put("maximum", 10.0)
                .put("description", "video_understand 的抽帧率，默认 2.0。"))
            .put("max_tokens", integer(1, 8192, "video_understand 输出 token 上限，默认 2048。"))
            .put("save", bool("视频理解或音频转写是否保存完整文本，默认 true。"))
            .put("voice_id", enumeration(listOf("stella", "tide"), "DeepSeek 合成音色，默认 stella。"))
            .put("voice", enumeration(listOf("stella", "tide"), "voice_id 的兼容别名；同时提供时 voice_id 优先。"))
            .put("filter_markdown", bool("DeepSeek 合成前是否过滤 Markdown，默认 true。"))
            .put("output_name", text(100, "合成语音的可选文件名，不含目录；输出固定为 24 kHz WAV。"))
        val alternatives = JSONArray()
            .put(operationConstraint("image_generate", listOf("request_id", "prompt")))
            .put(operationConstraint("image_edit", listOf("request_id", "prompt"), listOf("image_paths", "image_path", "paths", "path")))
            .put(operationConstraint("image_status", listOf("task_id")))
            .put(operationConstraint("video_understand", listOf("request_id", "prompt"), listOf("video_path", "path")))
            .put(operationConstraint("video_generate", listOf("request_id", "prompt")))
            .put(operationConstraint("video_status", listOf("task_id")))
            .put(operationConstraint("media_tasks", emptyList()))
            .put(operationConstraint("speech_transcribe", listOf("request_id"), listOf("audio_path", "path")))
            .put(operationConstraint("speech_synthesize", listOf("request_id", "text")))
        return objectSchema(properties, listOf("operation")).put("anyOf", alternatives)
    }

    private fun commonProperties(operations: Set<String>): JSONObject = JSONObject()
        .put("operation", enumeration(operations, "选择要执行的云操作；所选能力及其独立凭据必须已启用配置。"))
        .put("request_id", text(128, "收费提交及文档删除必填的 8–128 位稳定请求 ID。恢复或重试同一请求复用原 ID 和原参数，不能换 ID 盲目重提；仅状态/列表操作可省略。")
            .put("minLength", 8).put("pattern", "^[A-Za-z0-9][A-Za-z0-9_.-]{7,127}$"))
        .put("model", text(128, "可选模型名称；省略沿用此云能力独立配置的模型。不得用模型名切换未配置供应商。"))

    private fun objectSchema(properties: JSONObject, required: List<String>): JSONObject = JSONObject()
        .put("type", "object").put("properties", properties).put("required", JSONArray(required))
        .put("additionalProperties", false)

    private fun operationConstraint(operation: String, required: List<String>, references: List<String> = emptyList()): JSONObject = JSONObject()
        .put("properties", JSONObject().put("operation", enumeration(listOf(operation), "所选操作。")))
        .put("required", JSONArray().put("operation"))
        .put("allOf", JSONArray().put(JSONObject().put("required", JSONArray(required))))
        .also { schema ->
            if (references.isNotEmpty()) schema.put("anyOf", JSONArray(references.map { key ->
                JSONObject().put("required", JSONArray().put(key))
            }))
        }

    private fun text(maximum: Int, description: String): JSONObject = JSONObject()
        .put("type", "string").put("minLength", 1).put("maxLength", maximum).put("description", description)

    private fun inputReference(description: String): JSONObject = text(8192,
        "$description 仅接受已授权输入，禁止网络 URL、data URI、猜测的路径和其他应用私有文件。")

    private fun resourceId(description: String): JSONObject = text(256, description)
        .put("pattern", "^[A-Za-z0-9][A-Za-z0-9_.-]{0,255}$")

    private fun integer(minimum: Int, maximum: Int, description: String): JSONObject = JSONObject()
        .put("type", "integer").put("minimum", minimum).put("maximum", maximum).put("description", description)

    private fun bool(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)

    private fun enumeration(values: Collection<String>, description: String): JSONObject = JSONObject()
        .put("type", "string").put("enum", JSONArray(values)).put("description", description)

    private fun arrayOf(items: JSONObject, minimum: Int, maximum: Int, description: String): JSONObject = JSONObject()
        .put("type", "array").put("items", items).put("minItems", minimum).put("maxItems", maximum).put("description", description)
}
