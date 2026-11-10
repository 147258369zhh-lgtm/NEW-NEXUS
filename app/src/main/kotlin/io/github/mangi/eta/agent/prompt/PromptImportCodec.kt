package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal enum class PromptImportFormat(val wireValue: String, val label: String) {
    ETA_NATIVE("eta", "Nexus 原生"),
    PRO_PRESET("pro", "小 PRO 预设"),
    SILLYTAVERN_PRESET("sillytavern", "SillyTavern 预设"),
    GENERIC_JSON("json", "通用 JSON"),
    MARKDOWN("markdown", "Markdown"),
    PLAIN_TEXT("text", "纯文本"),
    CHARACTER_CARD_PNG("character-card", "角色卡 PNG"),
}

internal enum class PromptImportNoticeKind(val wireValue: String) {
    MAPPING("mapping"),
    CONVERSION("conversion"),
    IGNORED("ignored"),
    UNTRUSTED_TOOL_DECLARATION("untrusted-tool-declaration"),
}

internal data class PromptImportNotice(
    val kind: PromptImportNoticeKind,
    val message: String,
)

internal enum class PromptImportErrorCode {
    INPUT_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    UNSUPPORTED_FORMAT,
    NO_PROMPT_CONTENT,
    TOO_MANY_BLOCKS,
    TOO_MANY_TEMPLATES,
    TOO_MANY_BINDINGS,
    BLOCK_TOO_LARGE,
    ZIP_INVALID,
    ZIP_TOO_MANY_ENTRIES,
    ZIP_ENTRY_TOO_LARGE,
    ZIP_TOTAL_TOO_LARGE,
    ZIP_UNSAFE_PATH,
    ZIP_DUPLICATE_ENTRY,
    ZIP_COMPRESSION_RATIO,
    ZIP_PRESET_MISSING,
    ZIP_PRESET_MULTIPLE,
    CHARACTER_CARD_INVALID,
    READ_FAILED,
}

internal class PromptImportException(
    val code: PromptImportErrorCode,
    message: String,
) : Exception(message)

internal sealed interface PromptImportResult {
    data class Success(val preview: PromptImportPreview) : PromptImportResult

    data class Failure(
        val code: PromptImportErrorCode,
        val message: String,
    ) : PromptImportResult
}

internal data class PromptImportPreview(
    val format: PromptImportFormat,
    val name: String,
    val description: String,
    val templates: List<PromptTemplate>,
    val bindings: List<PromptScopeBinding> = emptyList(),
    val notices: List<PromptImportNotice> = emptyList(),
    val sourceSha256: String,
    val sourceBytes: Int,
) {
    val blockCount: Int get() = templates.sumOf { it.blocks.size }
    val enabledBlockCount: Int get() = templates.sumOf { template -> template.blocks.count { it.enabled } }
    val characterCount: Int get() = templates.sumOf { it.characterCount }
    val template: PromptTemplate? get() = templates.singleOrNull()
}

/**
 * Converts prompt-oriented files into preview data. Nothing in this codec applies bindings,
 * creates tool permissions, or changes runtime behavior.
 */
internal object PromptImportCodec {
    const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    const val MAX_ZIP_ARCHIVE_BYTES = 8 * 1024 * 1024
    const val MAX_PNG_BYTES = MAX_TEXT_BYTES
    const val MAX_ZIP_ENTRIES = 64
    const val MAX_ZIP_ENTRY_BYTES = 4 * 1024 * 1024
    const val MAX_ZIP_TOTAL_BYTES = 8 * 1024 * 1024
    const val MAX_BLOCKS = PromptInjectionStore.MAX_BLOCKS_PER_TEMPLATE
    const val MAX_BLOCK_CHARS = PromptInjectionStore.MAX_BLOCK_CHARS
    const val MAX_TEMPLATES = PromptInjectionStore.MAX_TEMPLATES
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val RATIO_CHECK_MIN_BYTES = 64L * 1024L
    private val roundTripAttributePattern = Regex("([a-zA-Z_]+)\\s*[:=]\\s*([^\\s]+)")
    private val roundTripBlockPattern = Regex(
        "<!--\\s*eta-block([^>]*)-->([\\s\\S]*?)<!--\\s*/eta-block\\s*-->",
    )
    private val roundTripTemplatePattern = Regex("<!--\\s*eta-template([^>]*)-->")

    private val genericFields = listOf(
        "instructions" to "Instructions",
        "system_prompt" to "System Prompt",
        "developer_prompt" to "Developer Prompt",
        "prompt" to "Prompt",
        "system" to "System",
        "developer" to "Developer",
    )
    private val proBlockKeys = listOf("prompts", "prompt", "blocks", "sections", "presets", "items", "entries", "messages")
    private val toolDeclarationKeys = listOf(
        "tools",
        "functions",
        "tool_choice",
        "function_call",
        "parallel_tool_calls",
        "mcp",
        "mcp_servers",
        "tool_resources",
    )

    fun inspect(bytes: ByteArray, sourceName: String? = null): PromptImportResult = try {
        PromptImportResult.Success(inspectOrThrow(bytes, sourceName))
    } catch (failure: PromptImportException) {
        PromptImportResult.Failure(failure.code, failure.message.orEmpty())
    } catch (failure: Exception) {
        PromptImportResult.Failure(
            PromptImportErrorCode.UNSUPPORTED_FORMAT,
            failure.message ?: "无法识别提示词文件",
        )
    }

    fun exportNative(config: PromptInjectionConfig): String {
        require(config.templates.size <= MAX_TEMPLATES) { "模板数量超过上限" }
        val templates = JSONArray()
        config.templates.forEach { template ->
            require(template.name.length <= PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS)
            require(template.description.length <= PromptInjectionStore.MAX_DESCRIPTION_CHARS)
            require(template.blocks.size <= MAX_BLOCKS)
            val blocks = JSONArray()
            template.blocks.forEach { block ->
                require(block.title.length <= PromptInjectionStore.MAX_BLOCK_TITLE_CHARS)
                require(block.content.length <= MAX_BLOCK_CHARS)
                blocks.put(
                    JSONObject()
                        .put("id", block.id)
                        .put("title", block.title)
                        .put("content", block.content)
                        .put("enabled", block.enabled)
                        .put("position", block.position.wireValue)
                        .put("order", block.order),
                )
            }
            templates.put(
                JSONObject()
                    .put("id", template.id)
                    .put("name", template.name)
                    .put("description", template.description)
                    .put("enabled", template.enabled)
                    .put("blocks", blocks)
                    .put("created_at", template.createdAt)
                    .put("updated_at", template.updatedAt)
                    .put("source", template.source)
                    .put("source_version", template.sourceVersion),
            )
        }
        val bindings = JSONArray()
        config.bindings.forEach { binding ->
            bindings.put(
                JSONObject()
                    .put("template_id", binding.templateId)
                    .put("target", binding.target.toJson())
                    .put("enabled", binding.enabled),
            )
        }
        return JSONObject()
            .put("schema", PromptInjectionConfig.SCHEMA)
            .put("version", PromptInjectionConfig.VERSION)
            .put("templates", templates)
            .put("bindings", bindings)
            .toString(2)
    }

    fun exportNative(template: PromptTemplate): String = exportNative(
        PromptInjectionConfig(templates = listOf(template)),
    )

    fun exportMarkdownTemplate(template: PromptTemplate): String = buildString {
        append("# ").append(singleLine(template.name)).append('\n')
        if (template.description.isNotBlank()) {
            append("> ").append(singleLine(template.description)).append('\n')
        }
        append("<!-- eta-template enabled:").append(template.enabled).append(" -->\n")
        template.blocks.sortedWith(PromptTemplate.BLOCK_ORDER).forEachIndexed { index, block ->
            append('\n')
            append("<!-- eta-block enabled:").append(block.enabled)
                .append(" position:").append(block.position.wireValue)
                .append(" order:").append(block.order.takeIf { it != 0 } ?: index)
                .append(" -->\n")
            append("## ").append(singleLine(block.title.ifBlank { "Block ${index + 1}" })).append('\n')
            append(block.content.trimEnd()).append('\n')
            append("<!-- /eta-block -->\n")
        }
    }

    fun exportPlainTextTemplate(template: PromptTemplate): String = buildString {
        append("Template: ").append(singleLine(template.name)).append('\n')
        append("Description: ").append(singleLine(template.description)).append('\n')
        append("Enabled: ").append(template.enabled).append('\n')
        template.blocks.sortedWith(PromptTemplate.BLOCK_ORDER).forEachIndexed { index, block ->
            append('\n')
            append("[Block ").append(index + 1).append(']')
                .append(" enabled=").append(block.enabled)
                .append(" position=").append(block.position.wireValue)
                .append(" order=").append(block.order.takeIf { it != 0 } ?: index)
                .append('\n')
            append("Title: ").append(singleLine(block.title)).append('\n')
            append("---\n")
            append(block.content.trimEnd()).append('\n')
        }
    }

    internal fun inputLimitFor(header: ByteArray): Int = when {
        CharacterCardPng.isPng(header) -> MAX_PNG_BYTES
        looksLikeZip(header) -> MAX_ZIP_ARCHIVE_BYTES
        else -> MAX_TEXT_BYTES
    }

    private fun inspectOrThrow(bytes: ByteArray, sourceName: String?): PromptImportPreview {
        val sourceHash = sha256(bytes)
        val baseName = fileStem(sourceName)
        if (bytes.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "提示词文件为空")
        val isPng = CharacterCardPng.isPng(bytes)
        val isZip = looksLikeZip(bytes)
        val limit = when {
            isPng -> MAX_PNG_BYTES
            isZip -> MAX_ZIP_ARCHIVE_BYTES
            else -> MAX_TEXT_BYTES
        }
        if (bytes.size > limit) {
            fail(
                PromptImportErrorCode.INPUT_TOO_LARGE,
                if (isPng) "角色卡 PNG 超过 2 MiB 限制"
                else if (isZip) "ZIP 文件超过 8 MiB 限制"
                else "提示词文件超过 2 MiB 限制",
            )
        }
        return when {
            isZip -> importZip(bytes, baseName, sourceHash)
            isPng -> importCharacterCard(bytes, baseName, sourceHash)
            else -> importText(bytes, baseName, sourceName, sourceHash)
        }
    }

    private fun importText(
        bytes: ByteArray,
        baseName: String?,
        sourceName: String?,
        sourceHash: String,
    ): PromptImportPreview {
        val source = decodeUtf8(bytes).removePrefix("\uFEFF")
        val trimmed = source.trim()
        if (trimmed.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "提示词文件没有正文")
        val extension = extension(sourceName)
        if (extension == "json" || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return importJson(trimmed, baseName, sourceHash, bytes.size)
            } catch (failure: PromptImportException) {
                if (extension == "json" || failure.code != PromptImportErrorCode.INVALID_JSON) throw failure
            }
        }
        if (extension in setOf("md", "markdown", "mdown") || hasFrontMatter(trimmed)) {
            return importMarkdown(source, baseName, sourceHash, bytes.size)
        }
        return importPlainText(source, baseName, sourceHash, bytes.size)
    }

    private fun importJson(
        source: String,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val text = source.removePrefix("\uFEFF")
        val tokener = JSONTokener(text)
        val value = try {
            tokener.nextValue()
        } catch (failure: Exception) {
            fail(PromptImportErrorCode.INVALID_JSON, "提示词 JSON 格式无效", failure)
        }
        if (tokener.nextClean() != '\u0000') {
            fail(PromptImportErrorCode.INVALID_JSON, "JSON 之后存在多余内容")
        }
        return when (value) {
            is JSONObject -> importJsonObject(value, baseName, sourceHash, sourceBytes)
            is JSONArray -> importGenericArray(value, baseName, sourceHash, sourceBytes)
            else -> fail(PromptImportErrorCode.INVALID_JSON, "JSON 根节点必须是对象或数组")
        }
    }

    private fun importJsonObject(
        root: JSONObject,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        if (root.stringOrNull("schema") == PromptInjectionConfig.SCHEMA) {
            val version = root.optInt("version", -1)
            if (version != PromptInjectionConfig.VERSION) {
                fail(PromptImportErrorCode.UNSUPPORTED_FORMAT, "不支持的 Nexus 提示词版本：" + version)
            }
            return importNative(root, sourceHash, sourceBytes)
        }
        if (schemaNumber(root) == 2) {
            return ignoreExternalBindings(importPro(root, baseName, sourceHash, sourceBytes), root)
        }
        val prompts = root.optJSONArray("prompts")
        if (prompts != null && looksLikeSillyTavern(root, prompts)) {
            return ignoreExternalBindings(importSillyTavern(root, prompts, baseName, sourceHash, sourceBytes), root)
        }
        return ignoreExternalBindings(importGeneric(root, baseName, sourceHash, sourceBytes), root)
    }

    private fun ignoreExternalBindings(
        preview: PromptImportPreview,
        root: JSONObject,
    ): PromptImportPreview {
        val declared = listOf("bindings", "scope_bindings", "scopeBindings").sumOf { key ->
            root.optJSONArray(key)?.length() ?: 0
        }
        if (declared == 0) return preview.copy(bindings = emptyList())
        return preview.copy(
            bindings = emptyList(),
            notices = preview.notices + PromptImportNotice(
                PromptImportNoticeKind.IGNORED,
                "外部格式中的 " + declared + " 个作用域绑定未导入；请导入后手动配置。",
            ),
        )
    }

    private fun importNative(
        root: JSONObject,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val templateArray = root.optJSONArray("templates")
            ?: fail(PromptImportErrorCode.INVALID_JSON, "Nexus 提示词缺少 templates 数组")
        if (templateArray.length() > MAX_TEMPLATES) {
            fail(PromptImportErrorCode.TOO_MANY_TEMPLATES, "Nexus 提示词模板超过 200 个")
        }
        val notices = mutableListOf<PromptImportNotice>()
        val parsedTemplates = (0 until templateArray.length()).map { index ->
            val template = templateArray.optJSONObject(index)
                ?: fail(PromptImportErrorCode.INVALID_JSON, "Nexus templates 第 " + (index + 1) + " 项不是对象")
            val name = template.stringOrNull("name").orEmpty()
            val description = template.stringOrNull("description").orEmpty()
            if (name.length > PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS) {
                fail(PromptImportErrorCode.INVALID_JSON, "原生模板名称超过 200 个字符")
            }
            if (description.length > PromptInjectionStore.MAX_DESCRIPTION_CHARS) {
                fail(PromptImportErrorCode.INVALID_JSON, "原生模板说明超过 4000 个字符")
            }
            val blockArray = template.optJSONArray("blocks") ?: JSONArray()
            if (blockArray.length() > MAX_BLOCKS) {
                fail(PromptImportErrorCode.TOO_MANY_BLOCKS, "原生模板超过 100 个块")
            }
            val blocks = (0 until blockArray.length()).map { blockIndex ->
                val block = blockArray.optJSONObject(blockIndex)
                    ?: fail(PromptImportErrorCode.INVALID_JSON, "Nexus blocks 第 " + (blockIndex + 1) + " 项不是对象")
                val title = block.stringOrNull("title").orEmpty()
                val content = block.stringOrNull("content").orEmpty()
                if (title.length > PromptInjectionStore.MAX_BLOCK_TITLE_CHARS) {
                    fail(PromptImportErrorCode.INVALID_JSON, "原生提示块标题超过 200 个字符")
                }
                if (content.length > MAX_BLOCK_CHARS) {
                    fail(PromptImportErrorCode.BLOCK_TOO_LARGE, "原生提示块超过 200000 个字符")
                }
                PromptBlock(
                    id = block.stringOrNull("id").orEmpty().ifBlank { UUID.randomUUID().toString() },
                    title = title,
                    content = content,
                    enabled = block.optBoolean("enabled", true),
                    position = PromptBlockPosition.fromWire(block.stringOrNull("position")),
                    order = block.optInt("order", 0),
                )
            }
            PromptTemplate(
                id = template.stringOrNull("id").orEmpty().ifBlank { UUID.randomUUID().toString() },
                name = name,
                description = description,
                enabled = template.optBoolean("enabled", true),
                blocks = blocks,
                createdAt = template.longOr("created_at", System.currentTimeMillis()),
                updatedAt = template.longOr("updated_at", System.currentTimeMillis()),
                source = template.stringOrNull("source").orEmpty(),
                sourceVersion = template.optInt("source_version", 1),
            )
        }
        val duplicateIds = parsedTemplates.groupingBy(PromptTemplate::id)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            notices += PromptImportNotice(
                PromptImportNoticeKind.CONVERSION,
                "检测到重复模板 ID，已保留首次出现的模板并按 ID 去重：" + duplicateIds.joinToString(", "),
            )
        }
        val templates = parsedTemplates.distinctBy(PromptTemplate::id)
        val templateIds = templates.mapTo(hashSetOf()) { it.id }
        val bindingArray = root.optJSONArray("bindings") ?: JSONArray()
        if (bindingArray.length() > PromptInjectionStore.MAX_BINDINGS) {
            fail(PromptImportErrorCode.TOO_MANY_BINDINGS, "Nexus 提示词绑定超过 1000 个")
        }
        val bindings = (0 until bindingArray.length()).mapNotNull { index ->
            val binding = bindingArray.optJSONObject(index) ?: return@mapNotNull null
            val templateId = binding.stringOrNull("template_id").orEmpty()
            if (templateId !in templateIds) {
                notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "忽略引用不存在模板的绑定：" + templateId)
                return@mapNotNull null
            }
            val targetJson = binding.optJSONObject("target") ?: run {
                notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "忽略缺少目标的作用域绑定：" + templateId)
                return@mapNotNull null
            }
            val type = PromptScopeType.fromWire(targetJson.stringOrNull("type")) ?: run {
                notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "忽略未知作用域绑定：" + templateId)
                return@mapNotNull null
            }
            val target = try {
                PromptTarget(
                    type = type,
                    primaryId = targetJson.stringOrNull("primary").orEmpty(),
                    secondaryId = targetJson.stringOrNull("secondary").orEmpty(),
                )
            } catch (failure: IllegalArgumentException) {
                notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "忽略无效的作用域绑定：" + failure.message)
                return@mapNotNull null
            }
            PromptScopeBinding(
                templateId = templateId,
                target = target,
                enabled = binding.optBoolean("enabled", true),
            )
        }
        if (bindings.isNotEmpty()) {
            notices += PromptImportNotice(
                PromptImportNoticeKind.CONVERSION,
                "包含 " + bindings.size + " 个作用域绑定；仅在用户确认整库恢复后生效。",
            )
        }
        val name = if (templates.size == 1) templates.first().name else templates.size.toString() + " 个 Nexus 提示词模板"
        val description = if (templates.size == 1) templates.first().description else "包含多个模板，请在导入前逐项检查。"
        return PromptImportPreview(
            format = PromptImportFormat.ETA_NATIVE,
            name = name,
            description = description.take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            templates = templates,
            bindings = bindings,
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = sourceBytes,
        )
    }

    private fun importPro(
        root: JSONObject,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val data = root.optJSONObject("data") ?: root
        val notices = mutableListOf<PromptImportNotice>()
        toolDeclarationNotice(root)?.let(notices::add)
        val containers = listOfNotNull(
            root,
            root.optJSONObject("data"),
            root.optJSONObject("preset"),
            root.optJSONObject("data")?.optJSONObject("preset"),
        )
        val selected = containers.asSequence().flatMap { container ->
            proBlockKeys.asSequence().mapNotNull { key ->
                container.optJSONArray(key)?.takeIf { it.length() > 0 }?.let { JSONItem(key, it) }
            }
        }.firstOrNull()
        val pending = mutableListOf<PendingBlock>()
        if (selected != null) {
            val items = selected.array
            for (index in 0 until items.length()) {
                val item = items.opt(index)
                val objectItem = item as? JSONObject
                val content = when (item) {
                    is String -> item
                    is JSONObject -> objectItem?.stringOrNull("content")
                        ?: objectItem?.stringOrNull("text")
                        ?: objectItem?.stringOrNull("prompt")
                        ?: objectItem?.stringOrNull("instruction")
                        ?: objectItem?.stringOrNull("body")
                        ?: objectItem?.stringOrNull("value")
                        ?: ""
                    else -> ""
                }
                if (content.isBlank()) {
                    notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "小 PRO 第 " + (index + 1) + " 项没有正文，已忽略。")
                    continue
                }
                val title = objectItem?.stringOrNull("title")
                    ?: objectItem?.stringOrNull("name")
                    ?: objectItem?.stringOrNull("label")
                    ?: objectItem?.stringOrNull("identifier")
                    ?: (selected.key + " " + (index + 1))
                val enabled = when {
                    objectItem?.has("enabled") == true -> objectItem.optBoolean("enabled", true)
                    objectItem?.has("disabled") == true -> !objectItem.optBoolean("disabled", false)
                    else -> true
                }
                val order = objectItem?.optInt("order", index * 10) ?: index * 10
                pending += PendingBlock(
                    order = order,
                    index = index,
                    block = newBlock(title, content, enabled, proPosition(objectItem, notices), order, notices),
                )
            }
            notices += PromptImportNotice(
                PromptImportNoticeKind.CONVERSION,
                "小 PRO schema=2 的 " + selected.key + " 已映射为 Nexus 提示块；采样与运行参数未导入。",
            )
        } else {
            val direct = listOf("system_prompt", "instructions", "prompt", "content")
                .firstNotNullOfOrNull { key -> data.stringOrNull(key)?.takeIf { it.isNotBlank() }?.let { key to it } }
            if (direct != null) {
                pending += PendingBlock(
                    order = 0,
                    index = 0,
                    block = newBlock(direct.first, direct.second, true, PromptBlockPosition.SYSTEM_PREFIX, 0, notices),
                )
                notices += PromptImportNotice(PromptImportNoticeKind.MAPPING, "小 PRO 字段 " + direct.first + " 已映射为提示块。")
            }
        }
        val blocks = pending.sortedWith(compareBy<PendingBlock> { it.order }.thenBy { it.index })
            .mapNotNull { it.block }
        if (blocks.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "小 PRO 预设没有可导入的提示词正文")
        enforceBlocks(blocks)
        val template = PromptTemplate(
            name = displayName(data.stringOrNull("name") ?: data.stringOrNull("title"), baseName, "小 PRO 预设"),
            description = (data.stringOrNull("description") ?: "").take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            blocks = blocks,
            source = "pro-schema-2",
        )
        return PromptImportPreview(
            format = PromptImportFormat.PRO_PRESET,
            name = template.name,
            description = template.description,
            templates = listOf(template),
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = sourceBytes,
        )
    }

    private fun importSillyTavern(
        root: JSONObject,
        prompts: JSONArray,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val notices = mutableListOf<PromptImportNotice>()
        toolDeclarationNotice(root)?.let(notices::add)
        val order = mutableMapOf<String, Pair<Boolean, Int>>()
        val orderGroups = root.optJSONArray("prompt_order")
        val firstOrder = orderGroups?.optJSONObject(0)?.optJSONArray("order")
        if (firstOrder != null) {
            for (index in 0 until firstOrder.length()) {
                val item = firstOrder.optJSONObject(index) ?: continue
                val identifier = item.stringOrNull("identifier") ?: continue
                order[identifier] = item.optBoolean("enabled", true) to index
            }
            notices += PromptImportNotice(PromptImportNoticeKind.MAPPING, "已读取 prompt_order 的顺序与启用状态。")
        }
        val blocks = mutableListOf<PromptBlock>()
        for (index in 0 until prompts.length()) {
            val item = prompts.optJSONObject(index)
            if (item == null) {
                notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "SillyTavern 第 " + (index + 1) + " 项不是对象，已忽略。")
                continue
            }
            val identifier = item.stringOrNull("identifier") ?: item.stringOrNull("name")
            val name = item.stringOrNull("name") ?: identifier ?: ("提示 " + (index + 1))
            val content = item.stringOrNull("content")
                ?: item.stringOrNull("prompt")
                ?: item.stringOrNull("text")
                ?: ""
            if (item.optBoolean("marker", false) || content.isBlank()) {
                notices += PromptImportNotice(
                    PromptImportNoticeKind.IGNORED,
                    if (item.optBoolean("marker", false)) "SillyTavern 标记 " + name + " 不含正文，已忽略。"
                    else "SillyTavern 提示 " + name + " 没有正文，已忽略。",
                )
                continue
            }
            val orderEntry = identifier?.let(order::get)
            val enabled = orderEntry?.first ?: item.optBoolean("enabled", true)
            val position = if (item.optInt("injection_position", 0) == 1) {
                PromptBlockPosition.POST_HISTORY
            } else {
                PromptBlockPosition.SYSTEM_PREFIX
            }
            val role = item.stringOrNull("role").orEmpty()
            if (role.isNotBlank() && role != "system") {
                notices += PromptImportNotice(
                    PromptImportNoticeKind.CONVERSION,
                    "SillyTavern 提示 " + name + " 的 role=" + role + " 仅保留正文，按系统块导入。",
                )
            }
            if (!enabled) {
                notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "SillyTavern 提示 " + name + " 已禁用，将作为禁用块导入。")
            }
            val block = newBlock(
                name,
                content,
                enabled,
                position,
                orderEntry?.second?.times(10) ?: index * 10,
                notices,
            )
            if (block != null) blocks += block
        }
        if (blocks.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "SillyTavern 预设没有可导入的提示词正文")
        enforceBlocks(blocks)
        notices += PromptImportNotice(
            PromptImportNoticeKind.CONVERSION,
            "SillyTavern 的采样参数、正则与扩展设置未导入。",
        )
        val template = PromptTemplate(
            name = displayName(
                root.stringOrNull("name") ?: root.stringOrNull("preset_name"),
                baseName,
                "SillyTavern 预设",
            ),
            description = (root.stringOrNull("description") ?: "").take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            blocks = blocks,
            source = "sillytavern-prompt-preset",
        )
        return PromptImportPreview(
            format = PromptImportFormat.SILLYTAVERN_PRESET,
            name = template.name,
            description = template.description,
            templates = listOf(template),
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = sourceBytes,
        )
    }

    private fun importGeneric(
        root: JSONObject,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val notices = mutableListOf<PromptImportNotice>()
        toolDeclarationNotice(root)?.let(notices::add)
        val blocks = mutableListOf<PromptBlock>()
        var order = 0
        genericFields.forEach { (key, title) ->
            val value = root.stringOrNull(key)?.takeIf { it.isNotBlank() } ?: return@forEach
            newBlock(title, value, true, PromptBlockPosition.SYSTEM_PREFIX, order, notices)?.let(blocks::add)
            notices += PromptImportNotice(PromptImportNoticeKind.MAPPING, "字段 " + key + " 已映射为系统前置块。")
            order += 10
        }
        val messages = root.optJSONArray("messages")
        if (messages != null) {
            val ignored = messagesToBlocks(messages, order, notices, blocks)
            if (ignored > 0) {
                notices += PromptImportNotice(
                    PromptImportNoticeKind.IGNORED,
                    "忽略 " + ignored + " 条非 system/developer 消息。",
                )
            }
        }
        if (blocks.isEmpty()) {
            fail(PromptImportErrorCode.UNSUPPORTED_FORMAT, "没有找到 instructions、system_prompt、prompt 或 system/developer 消息")
        }
        enforceBlocks(blocks)
        val template = PromptTemplate(
            name = displayName(root.stringOrNull("name") ?: root.stringOrNull("title"), baseName, "通用提示词"),
            description = (root.stringOrNull("description") ?: "").take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            blocks = blocks,
            source = "generic-json",
        )
        return PromptImportPreview(
            format = PromptImportFormat.GENERIC_JSON,
            name = template.name,
            description = template.description,
            templates = listOf(template),
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = sourceBytes,
        )
    }

    private fun importGenericArray(
        root: JSONArray,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val notices = mutableListOf<PromptImportNotice>()
        val blocks = mutableListOf<PromptBlock>()
        var order = 0
        for (index in 0 until root.length()) {
            when (val item = root.opt(index)) {
                is String -> if (item.isNotBlank()) {
                    newBlock("Text " + (index + 1), item, true, PromptBlockPosition.SYSTEM_PREFIX, order, notices)?.let(blocks::add)
                    order += 10
                }
                is JSONObject -> {
                    val role = item.stringOrNull("role").orEmpty().lowercase()
                    if (role == "system" || role == "developer") {
                        val content = messageText(item)
                        if (!content.isNullOrBlank()) {
                            newBlock(role.replaceFirstChar { it.uppercase() }, content, true, PromptBlockPosition.SYSTEM_PREFIX, order, notices)?.let(blocks::add)
                            order += 10
                        }
                    } else {
                        notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "数组第 " + (index + 1) + " 条消息不是 system/developer，已忽略。")
                    }
                }
                else -> notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "数组第 " + (index + 1) + " 项格式未知，已忽略。")
            }
        }
        if (blocks.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "通用提示词数组没有可导入正文")
        enforceBlocks(blocks)
        val template = PromptTemplate(
            name = displayName(null, baseName, "通用提示词"),
            blocks = blocks,
            source = "generic-json-array",
        )
        return PromptImportPreview(
            format = PromptImportFormat.GENERIC_JSON,
            name = template.name,
            description = "",
            templates = listOf(template),
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = sourceBytes,
        )
    }

    private fun importMarkdown(
        source: String,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val notices = mutableListOf<PromptImportNotice>()
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        parseRoundTripMarkdown(normalized, baseName, notices)?.let { template ->
            return PromptImportPreview(
                format = PromptImportFormat.MARKDOWN,
                name = template.name,
                description = template.description,
                templates = listOf(template),
                notices = notices,
                sourceSha256 = sourceHash,
                sourceBytes = sourceBytes,
            )
        }
        val front = parseFrontMatter(normalized)
        if (front == null && normalized.startsWith("---\n")) {
            notices += PromptImportNotice(PromptImportNoticeKind.CONVERSION, "YAML front matter 未闭合，已按普通 Markdown 正文处理。")
        }
        val body = front?.body ?: normalized
        val metadata = front?.values.orEmpty()
        val heading = firstMarkdownHeading(body)
        val title = metadata["name"] ?: metadata["title"] ?: heading ?: baseName ?: "Markdown 提示词"
        val description = metadata["description"].orEmpty()
        val ignoredKeys = metadata.keys - setOf("name", "title", "description", "enabled")
        if (ignoredKeys.isNotEmpty()) {
            notices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "忽略 front matter 字段：" + ignoredKeys.sorted().joinToString(", "))
        }
        val enabled = metadata["enabled"]?.equals("false", ignoreCase = true)?.not() ?: true
        val parts = splitMarkdown(body, title)
        val blocks = parts.flatMapIndexed { index, part ->
            newBlocks(part.first, part.second, enabled, PromptBlockPosition.SYSTEM_PREFIX, index * 1_000, notices)
        }
        if (blocks.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "Markdown 没有可导入正文")
        enforceBlocks(blocks)
        notices += PromptImportNotice(
            PromptImportNoticeKind.CONVERSION,
            if (parts.size > 1) "Markdown 已按标题拆分为 " + parts.size + " 个提示块。"
            else "Markdown 正文已导入为单个提示块。",
        )
        val template = PromptTemplate(
            name = displayName(title, baseName, "Markdown 提示词"),
            description = description.take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            blocks = blocks,
            source = "markdown",
        )
        return PromptImportPreview(
            format = PromptImportFormat.MARKDOWN,
            name = template.name,
            description = template.description,
            templates = listOf(template),
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = sourceBytes,
        )
    }

    private fun importPlainText(
        source: String,
        baseName: String?,
        sourceHash: String,
        sourceBytes: Int,
    ): PromptImportPreview {
        val notices = mutableListOf<PromptImportNotice>()
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        parseRoundTripPlainText(normalized, baseName, notices)?.let { template ->
            return PromptImportPreview(
                format = PromptImportFormat.PLAIN_TEXT,
                name = template.name,
                description = template.description,
                templates = listOf(template),
                notices = notices,
                sourceSha256 = sourceHash,
                sourceBytes = sourceBytes,
            )
        }
        val content = source.trim()
        if (content.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "文本没有正文")
        val blocks = newBlocks("正文", content, true, PromptBlockPosition.SYSTEM_PREFIX, 0, notices)
        if (blocks.isEmpty()) fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "文本没有正文")
        enforceBlocks(blocks)
        val template = PromptTemplate(
            name = displayName(null, baseName, "纯文本提示词"),
            blocks = blocks,
            source = "text",
        )
        if (blocks.size == 1) {
            notices += PromptImportNotice(PromptImportNoticeKind.CONVERSION, "纯文本已导入为单个系统前置块。")
        } else {
            notices += PromptImportNotice(PromptImportNoticeKind.CONVERSION, "纯文本已按段落拆分为 " + blocks.size + " 个系统前置块。")
        }
        return PromptImportPreview(
            format = PromptImportFormat.PLAIN_TEXT,
            name = template.name,
            description = "",
            templates = listOf(template),
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = sourceBytes,
        )
    }

    private fun importCharacterCard(
        bytes: ByteArray,
        baseName: String?,
        sourceHash: String,
    ): PromptImportPreview {
        val card = try {
            CharacterCardPng.read(bytes)
        } catch (failure: CharacterCardException) {
            fail(PromptImportErrorCode.CHARACTER_CARD_INVALID, failure.message ?: "角色卡数据无效", failure)
        } catch (failure: IllegalArgumentException) {
            fail(PromptImportErrorCode.CHARACTER_CARD_INVALID, failure.message ?: "角色卡 PNG 无效", failure)
        }
        val notices = mutableListOf<PromptImportNotice>()
        val blocks = mutableListOf<PromptBlock>()
        card.systemPrompt.takeIf { it.isNotBlank() }?.let { content ->
            newBlock("角色系统提示词", content, true, PromptBlockPosition.SYSTEM_PREFIX, 0, notices)?.let(blocks::add)
            notices += PromptImportNotice(PromptImportNoticeKind.MAPPING, "角色卡 system_prompt 已映射为系统前置块。")
        }
        card.postHistoryInstructions.takeIf { it.isNotBlank() }?.let { content ->
            newBlock("角色后置提示词", content, true, PromptBlockPosition.POST_HISTORY, 10, notices)?.let(blocks::add)
            notices += PromptImportNotice(PromptImportNoticeKind.MAPPING, "角色卡 post_history_instructions 已映射为后置块。")
        }
        if (blocks.isEmpty()) {
            fail(PromptImportErrorCode.NO_PROMPT_CONTENT, "角色卡没有 system_prompt 或 post_history_instructions")
        }
        val ignored = listOfNotNull(
            card.description.takeIf { it.isNotBlank() }?.let { "description" },
            card.personality.takeIf { it.isNotBlank() }?.let { "personality" },
            card.scenario.takeIf { it.isNotBlank() }?.let { "scenario" },
            card.depthPrompt?.let { "depth_prompt" },
        )
        if (ignored.isNotEmpty()) {
            notices += PromptImportNotice(
                PromptImportNoticeKind.IGNORED,
                "角色卡字段未作为提示词导入：" + ignored.joinToString(", "),
            )
        }
        enforceBlocks(blocks)
        val template = PromptTemplate(
            name = displayName(card.name, baseName, "角色卡提示词"),
            description = card.creatorNotes.take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            blocks = blocks,
            source = "character-card-png",
        )
        return PromptImportPreview(
            format = PromptImportFormat.CHARACTER_CARD_PNG,
            name = template.name,
            description = template.description,
            templates = listOf(template),
            notices = notices,
            sourceSha256 = sourceHash,
            sourceBytes = bytes.size,
        )
    }

    private fun importZip(bytes: ByteArray, baseName: String?, sourceHash: String): PromptImportPreview {
        val archive = File.createTempFile("eta-prompt-import-", ".zip")
        try {
            archive.writeBytes(bytes)
            ZipFile(archive).use { zip ->
                val entries = Collections.list(zip.entries())
                if (entries.size > MAX_ZIP_ENTRIES) {
                    fail(PromptImportErrorCode.ZIP_TOO_MANY_ENTRIES, "ZIP 条目超过 64 个")
                }
                val seen = linkedMapOf<String, Boolean>()
                var totalBytes = 0L
                entries.forEach { entry ->
                    val normalized = validateZipPath(entry.name)
                    val key = normalized.lowercase()
                    if (seen.containsKey(key)) {
                        fail(PromptImportErrorCode.ZIP_DUPLICATE_ENTRY, "ZIP 包含重复条目：" + entry.name)
                    }
                    val isDirectory = entry.isDirectory
                    val segments = normalized.split('/')
                    val ancestors = (1 until segments.size).map {
                        segments.take(it).joinToString("/").lowercase()
                    }
                    if (ancestors.any { seen[it] == false }) {
                        fail(PromptImportErrorCode.ZIP_DUPLICATE_ENTRY, "ZIP 条目存在文件与目录冲突：" + entry.name)
                    }
                    if (!isDirectory && seen.keys.any { it.startsWith(key + "/") }) {
                        fail(PromptImportErrorCode.ZIP_DUPLICATE_ENTRY, "ZIP 条目存在文件与目录冲突：" + entry.name)
                    }
                    seen[key] = isDirectory
                    if (!isDirectory) {
                        if (entry.size < 0) fail(PromptImportErrorCode.ZIP_INVALID, "ZIP 条目大小无效：" + entry.name)
                        if (entry.size > MAX_ZIP_ENTRY_BYTES) {
                            fail(PromptImportErrorCode.ZIP_ENTRY_TOO_LARGE, "ZIP 单条目超过 4 MiB：" + entry.name)
                        }
                        if (entry.size > RATIO_CHECK_MIN_BYTES) {
                            val compressed = entry.compressedSize
                            if (compressed <= 0 || entry.size > compressed * MAX_COMPRESSION_RATIO) {
                                fail(PromptImportErrorCode.ZIP_COMPRESSION_RATIO, "ZIP 条目压缩比异常：" + entry.name)
                            }
                        }
                        totalBytes += entry.size
                        if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                            fail(PromptImportErrorCode.ZIP_TOTAL_TOO_LARGE, "ZIP 解压内容超过 8 MiB")
                        }
                    }
                }
                val presetEntries = entries.filter { entry ->
                    !entry.isDirectory && entry.name.substringAfterLast('/').equals("preset.json", ignoreCase = true)
                }
                when (presetEntries.size) {
                    0 -> fail(PromptImportErrorCode.ZIP_PRESET_MISSING, "ZIP 中没有 preset.json")
                    1 -> Unit
                    else -> fail(PromptImportErrorCode.ZIP_PRESET_MULTIPLE, "ZIP 中包含多个 preset.json")
                }
                val preset = presetEntries.single()
                val presetBytes = readZipEntry(zip, preset, preset.size)
                val text = decodeUtf8(presetBytes)
                val preview = importJson(text, baseName, sourceHash, bytes.size)
                if (preview.format != PromptImportFormat.PRO_PRESET) {
                    fail(PromptImportErrorCode.UNSUPPORTED_FORMAT, "ZIP 内 preset.json 不是小 PRO schema=2 预设")
                }
                val extraNotices = mutableListOf(
                    PromptImportNotice(PromptImportNoticeKind.CONVERSION, "已从 ZIP 条目 " + preset.name + " 读取 preset.json。"),
                )
                val unused = entries.count { !it.isDirectory } - 1
                if (unused > 0) {
                    extraNotices += PromptImportNotice(PromptImportNoticeKind.IGNORED, "ZIP 内另有 " + unused + " 个文件未使用。")
                }
                return preview.copy(notices = preview.notices + extraNotices)
            }
        } catch (failure: PromptImportException) {
            throw failure
        } catch (failure: ZipException) {
            fail(PromptImportErrorCode.ZIP_INVALID, "ZIP 格式无效或已损坏", failure)
        } catch (failure: Exception) {
            fail(PromptImportErrorCode.ZIP_INVALID, "无法读取 ZIP：" + (failure.message ?: "未知错误"), failure)
        } finally {
            archive.delete()
        }
    }

    private fun readZipEntry(zip: ZipFile, entry: java.util.zip.ZipEntry, declared: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(declared, 64L * 1024L).toInt())
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > declared || total > MAX_ZIP_ENTRY_BYTES) {
                    fail(PromptImportErrorCode.ZIP_ENTRY_TOO_LARGE, "ZIP 条目实际解压大小超过限制：" + entry.name)
                }
                output.write(buffer, 0, count)
            }
            if (total != declared) fail(PromptImportErrorCode.ZIP_INVALID, "ZIP 条目大小与声明不一致：" + entry.name)
        }
        return output.toByteArray()
    }

    private fun validateZipPath(name: String): String {
        if (name.isBlank() || name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) {
            fail(PromptImportErrorCode.ZIP_UNSAFE_PATH, "ZIP 条目路径不安全：" + name)
        }
        val normalized = name.removeSuffix("/")
        val segments = normalized.split('/')
        if (segments.isEmpty() || segments.any { it.isBlank() || it == "." || it == ".." || it.any { ch -> ch.code < 0x20 } }) {
            fail(PromptImportErrorCode.ZIP_UNSAFE_PATH, "ZIP 条目路径不安全：" + name)
        }
        if (segments.first().contains(':') || segments.first().startsWith("~")) {
            fail(PromptImportErrorCode.ZIP_UNSAFE_PATH, "ZIP 条目路径不安全：" + name)
        }
        return normalized
    }

    private fun messagesToBlocks(
        messages: JSONArray,
        startOrder: Int,
        notices: MutableList<PromptImportNotice>,
        blocks: MutableList<PromptBlock>,
    ): Int {
        var ignored = 0
        var order = startOrder
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
            if (message == null) {
                ignored += 1
                continue
            }
            val role = message.stringOrNull("role").orEmpty().lowercase()
            val content = messageText(message)
            if ((role == "system" || role == "developer") && !content.isNullOrBlank()) {
                newBlock(role.replaceFirstChar { it.uppercase() }, content, true, PromptBlockPosition.SYSTEM_PREFIX, order, notices)?.let(blocks::add)
                notices += PromptImportNotice(
                    PromptImportNoticeKind.MAPPING,
                    "messages[" + index + "]（" + role + "）已映射为系统前置块。",
                )
                order += 10
            } else {
                ignored += 1
            }
        }
        return ignored
    }

    private fun messageText(message: JSONObject): String? {
        val content = message.opt("content")
        return when {
            content is String -> content
            content is JSONArray -> (0 until content.length()).mapNotNull { index ->
                val part = content.opt(index)
                when {
                    part is String -> part
                    part is JSONObject && part.stringOrNull("type") in setOf("text", "input_text") ->
                        part.stringOrNull("text")
                    else -> null
                }
            }.joinToString("\n\n").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun toolDeclarationNotice(root: JSONObject): PromptImportNotice? {
        val present = toolDeclarationKeys.filter { key ->
            val value = root.opt(key) ?: return@filter false
            if (value === JSONObject.NULL) return@filter false
            when (value) {
                is String -> value.isNotBlank()
                is JSONArray -> value.length() > 0
                is JSONObject -> value.length() > 0
                else -> true
            }
        }
        if (present.isEmpty()) return null
        return PromptImportNotice(
            PromptImportNoticeKind.UNTRUSTED_TOOL_DECLARATION,
            "检测到外部工具声明（" + present.joinToString(", ") + "）。它们只是未授权提示，不会创建工具权限或改变运行策略。",
        )
    }

    private fun looksLikeSillyTavern(root: JSONObject, prompts: JSONArray): Boolean {
        if (prompts.length() == 0) return false
        if (root.has("prompt_order")) return true
        for (index in 0 until prompts.length()) {
            val item = prompts.optJSONObject(index) ?: continue
            if (item.has("identifier") || item.has("system_prompt") || item.has("injection_position") || item.has("marker")) {
                return true
            }
        }
        return false
    }

    private fun proPosition(item: JSONObject?, notices: MutableList<PromptImportNotice>): PromptBlockPosition {
        if (item == null) return PromptBlockPosition.SYSTEM_PREFIX
        val position = item.stringOrNull("position")?.lowercase()
        if (position in setOf("post_history", "post-history", "after_history", "post")) {
            return PromptBlockPosition.POST_HISTORY
        }
        val numeric = item.opt("position").takeUnless { it === JSONObject.NULL } ?: item.opt("injection_position")
        if (numeric is Number && numeric.toInt() == 1) return PromptBlockPosition.POST_HISTORY
        val role = item.stringOrNull("role").orEmpty()
        if (role.isNotBlank() && role != "system") {
            notices += PromptImportNotice(
                PromptImportNoticeKind.CONVERSION,
                "小 PRO 块的 role=" + role + " 仅保留正文，按系统前置块导入。",
            )
        }
        return PromptBlockPosition.SYSTEM_PREFIX
    }

    /**
     * Recognizes the marked Markdown produced by [exportMarkdownTemplate]. These files are complete
     * transfer artifacts, so block state must survive a round trip instead of being treated as
     * ordinary headings.
     */
    private fun parseRoundTripMarkdown(
        source: String,
        baseName: String?,
        notices: MutableList<PromptImportNotice>,
    ): PromptTemplate? {
        val templateAttributes = roundTripTemplatePattern.find(source)?.groupValues?.get(1)
        val matches = roundTripBlockPattern.findAll(source).toList()
        if (templateAttributes == null && matches.isEmpty()) return null
        val lines = source.lines()
        val name = lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()
            ?.takeIf(String::isNotBlank)
            ?: baseName
            ?: "Markdown 提示词"
        val description = lines.firstOrNull { it.startsWith("> ") }
            ?.removePrefix("> ")?.trim().orEmpty()
        val templateEnabled = roundTripAttributes(templateAttributes).boolean("enabled", true)
        val blocks = matches.flatMapIndexed { index, match ->
            val attributes = roundTripAttributes(match.groupValues[1])
            val bodyLines = match.groupValues[2].trim('\n').lines()
            val titleIndex = bodyLines.indexOfFirst { it.startsWith("## ") }
            val title = if (titleIndex >= 0) bodyLines[titleIndex].removePrefix("## ").trim() else ""
            val content = if (titleIndex >= 0) bodyLines.drop(titleIndex + 1).joinToString("\n").trim() else bodyLines.joinToString("\n").trim()
            newBlocks(
                title = title,
                content = content,
                enabled = attributes.boolean("enabled", true),
                position = PromptBlockPosition.fromWire(attributes["position"]),
                order = attributes["order"]?.toIntOrNull() ?: index * 10,
                notices = notices,
            )
        }
        if (blocks.isEmpty()) return null
        enforceBlocks(blocks)
        notices += PromptImportNotice(
            PromptImportNoticeKind.MAPPING,
            "已识别 Nexus Markdown 往返标记，恢复模板说明、块顺序、插入位置与启用状态。",
        )
        return PromptTemplate(
            name = displayName(name, baseName, "Markdown 提示词"),
            description = description.take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            enabled = templateEnabled,
            blocks = blocks,
            source = "markdown-roundtrip",
        )
    }

    /** Recognizes the block headers emitted by [exportPlainTextTemplate]. */
    private fun parseRoundTripPlainText(
        source: String,
        baseName: String?,
        notices: MutableList<PromptImportNotice>,
    ): PromptTemplate? {
        val lines = source.lines()
        val starts = lines.indices.filter { index ->
            lines[index].startsWith("[Block ") || lines[index].startsWith("[块 ")
        }
        if (starts.isEmpty()) return null
        var name = baseName.orEmpty()
        var description = ""
        var templateEnabled = true
        lines.take(starts.first()).forEach { line ->
            when {
                line.startsWith("Template:", ignoreCase = true) || line.startsWith("名称:") ->
                    name = line.substringAfter(':').trim().ifBlank { name }
                line.startsWith("Description:", ignoreCase = true) || line.startsWith("描述:") ->
                    description = line.substringAfter(':').trim()
                line.startsWith("Enabled:", ignoreCase = true) || line.startsWith("状态:") ->
                    templateEnabled = line.substringAfter(':').trim().toBooleanLenient()
            }
        }
        val blocks = starts.flatMapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: lines.size
            val attributes = roundTripAttributes(lines[start].substringAfter(']', ""))
            val segment = lines.subList(start + 1, end)
            val title = segment.firstOrNull { it.startsWith("Title:", true) || it.startsWith("标题:") }
                ?.substringAfter(':')?.trim().orEmpty()
            val divider = segment.indexOfFirst { it.trim() == "---" }
            val content = if (divider >= 0) {
                segment.drop(divider + 1).joinToString("\n").trim()
            } else {
                segment.dropWhile { it.startsWith("Title:", true) || it.startsWith("标题:") }
                    .joinToString("\n").trim()
            }
            newBlocks(
                title = title,
                content = content,
                enabled = attributes.boolean("enabled", true),
                position = PromptBlockPosition.fromWire(attributes["position"]),
                order = attributes["order"]?.toIntOrNull() ?: index * 10,
                notices = notices,
            )
        }
        if (blocks.isEmpty()) return null
        enforceBlocks(blocks)
        notices += PromptImportNotice(
            PromptImportNoticeKind.MAPPING,
            "已识别 Nexus TXT 往返标记，恢复模板说明、块顺序、插入位置与启用状态。",
        )
        return PromptTemplate(
            name = displayName(name, baseName, "纯文本提示词"),
            description = description.take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
            enabled = templateEnabled,
            blocks = blocks,
            source = "text-roundtrip",
        )
    }

    private fun newBlocks(
        title: String?,
        content: String,
        enabled: Boolean,
        position: PromptBlockPosition,
        order: Int,
        notices: MutableList<PromptImportNotice>,
    ): List<PromptBlock> {
        val body = content.trim()
        if (body.isBlank()) return emptyList()
        val chunks = splitPromptContent(body)
        if (chunks.size > 1) {
            notices += PromptImportNotice(
                PromptImportNoticeKind.CONVERSION,
                (title?.trim().orEmpty().ifBlank { "提示块" }) + " 超过单块上限，已按段落拆分为 " + chunks.size + " 块。",
            )
        }
        val rawTitle = title.orEmpty().trim().ifBlank { "提示" }
        if (rawTitle.length > PromptInjectionStore.MAX_BLOCK_TITLE_CHARS) {
            notices += PromptImportNotice(PromptImportNoticeKind.CONVERSION, "提示块标题过长，已截断到 200 个字符。")
        }
        return chunks.mapIndexed { index, chunk ->
            val suffix = if (chunks.size == 1) "" else " (" + (index + 1) + "/" + chunks.size + ")"
            val titleLimit = (PromptInjectionStore.MAX_BLOCK_TITLE_CHARS - suffix.length).coerceAtLeast(1)
            PromptBlock(
                title = rawTitle.take(titleLimit) + suffix,
                content = chunk,
                enabled = enabled,
                position = position,
                order = order + index,
            )
        }
    }

    private fun splitPromptContent(content: String): List<String> {
        if (content.length <= MAX_BLOCK_CHARS) return listOf(content)
        val chunks = mutableListOf<String>()
        var remaining = content
        while (remaining.length > MAX_BLOCK_CHARS) {
            val limit = MAX_BLOCK_CHARS
            val minimum = (limit * 3) / 5
            val paragraph = remaining.lastIndexOf("\n\n", limit).takeIf { it >= minimum }
            val line = remaining.lastIndexOf('\n', limit).takeIf { it >= minimum }
            val space = remaining.lastIndexOf(' ', limit).takeIf { it >= minimum }
            val cut = paragraph ?: line ?: space ?: limit
            val chunk = remaining.substring(0, cut).trim()
            if (chunk.isNotEmpty()) chunks += chunk
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return chunks
    }

    private fun roundTripAttributes(raw: String?): Map<String, String> = raw
        ?.let { attributes ->
            roundTripAttributePattern.findAll(attributes).associate { match ->
                match.groupValues[1] to match.groupValues[2]
            }
        }
        .orEmpty()

    private fun Map<String, String>.boolean(key: String, fallback: Boolean): Boolean =
        this[key]?.equals("true", ignoreCase = true) ?: fallback

    private fun String.toBooleanLenient(): Boolean =
        equals("true", ignoreCase = true) || contains("启用") || contains("enabled", ignoreCase = true)

    private fun singleLine(value: String): String = value.replace("\n", " ").trim()

    private fun newBlock(
        title: String?,
        content: String,
        enabled: Boolean,
        position: PromptBlockPosition,
        order: Int,
        notices: MutableList<PromptImportNotice>,
    ): PromptBlock? {
        val body = content.trim()
        if (body.isBlank()) return null
        if (body.length > MAX_BLOCK_CHARS) {
            fail(PromptImportErrorCode.BLOCK_TOO_LARGE, "单个提示块超过 200000 个字符")
        }
        val rawTitle = title.orEmpty().trim()
        if (rawTitle.length > PromptInjectionStore.MAX_BLOCK_TITLE_CHARS) {
            notices += PromptImportNotice(PromptImportNoticeKind.CONVERSION, "提示块标题过长，已截断到 200 个字符。")
        }
        return PromptBlock(
            title = rawTitle.take(PromptInjectionStore.MAX_BLOCK_TITLE_CHARS),
            content = body,
            enabled = enabled,
            position = position,
            order = order,
        )
    }

    private fun enforceBlocks(blocks: List<PromptBlock>) {
        if (blocks.size > MAX_BLOCKS) {
            fail(PromptImportErrorCode.TOO_MANY_BLOCKS, "提示块超过 100 个")
        }
        blocks.forEach { block ->
            if (block.content.length > MAX_BLOCK_CHARS) {
                fail(PromptImportErrorCode.BLOCK_TOO_LARGE, "单个提示块超过 200000 个字符")
            }
        }
    }

    private data class JSONItem(val key: String, val array: JSONArray)
    private data class PendingBlock(val order: Int, val index: Int, val block: PromptBlock?)

    private fun schemaNumber(root: JSONObject): Int? {
        val raw = root.opt("schema")
        return when {
            raw is Number -> raw.toInt()
            raw is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when {
            value is String -> value
            value is Number || value is Boolean -> value.toString()
            else -> null
        }
    }

    private fun JSONObject.longOr(key: String, fallback: Long): Long =
        if (!has(key) || isNull(key)) fallback else optLong(key, fallback)

    private fun displayName(raw: String?, fallback: String?, default: String): String {
        val value = raw?.trim().orEmpty().ifBlank { fallback?.trim().orEmpty() }.ifBlank { default }
        return value.take(PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS)
    }

    private fun fileStem(sourceName: String?): String? {
        val name = sourceName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        if (name.isBlank()) return null
        val dot = name.lastIndexOf('.')
        return (if (dot > 0) name.substring(0, dot) else name).ifBlank { null }
    }

    private fun extension(sourceName: String?): String {
        val name = sourceName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0 && dot + 1 < name.length) name.substring(dot + 1).lowercase() else ""
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            (bytes[2] == 3.toByte() || bytes[2] == 5.toByte() || bytes[2] == 7.toByte()) &&
            (bytes[3] == 4.toByte() || bytes[3] == 6.toByte() || bytes[3] == 8.toByte())

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        fail(PromptImportErrorCode.INVALID_UTF8, "提示词文件必须使用有效的 UTF-8 编码", failure)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val output = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (value < 0x10) output.append('0')
            output.append(value.toString(16))
        }
        return output.toString()
    }

    private data class FrontMatter(val values: Map<String, String>, val body: String)

    private fun hasFrontMatter(source: String): Boolean = source.startsWith("---\n")

    private fun parseFrontMatter(source: String): FrontMatter? {
        if (!source.startsWith("---\n")) return null
        val lines = source.split('\n')
        val values = linkedMapOf<String, String>()
        var closing = -1
        for (index in 1 until lines.size) {
            val line = lines[index].trim()
            if (line == "---") {
                closing = index
                break
            }
            if (line.isBlank() || line.startsWith("#")) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            values[key] = value
        }
        if (closing < 0) return null
        return FrontMatter(values, lines.drop(closing + 1).joinToString("\n"))
    }

    private fun firstMarkdownHeading(source: String): String? {
        var inFence = false
        source.split('\n').forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("~~~") || trimmed.startsWith("\u0060\u0060\u0060")) {
                inFence = !inFence
                return@forEach
            }
            if (inFence) return@forEach
            val match = MARKDOWN_HEADING.matchEntire(line)
            if (match != null && match.groupValues[1].length == 1) {
                return match.groupValues[2].trim().removeSuffix("#").trim()
            }
        }
        return null
    }

    private fun splitMarkdown(source: String, defaultTitle: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val buffer = StringBuilder()
        var title = defaultTitle
        var inFence = false
        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) result += title to text
            buffer.setLength(0)
        }
        source.split('\n').forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("~~~") || trimmed.startsWith("\u0060\u0060\u0060")) {
                inFence = !inFence
            }
            val match = if (inFence) null else MARKDOWN_HEADING.matchEntire(line)
            if (match != null) {
                flush()
                title = match.groupValues[2].trim().removeSuffix("#").trim().ifBlank { defaultTitle }
            } else {
                buffer.append(line).append('\n')
            }
        }
        flush()
        if (result.isEmpty()) {
            val text = source.trim()
            if (text.isNotEmpty()) result += defaultTitle to text
        }
        return result
    }

    private val MARKDOWN_HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*$")

    private fun fail(
        code: PromptImportErrorCode,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw PromptImportException(code, message).also { if (cause != null) it.initCause(cause) }
}
