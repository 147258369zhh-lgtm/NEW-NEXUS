package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PromptImportCodecTest {
    @Test
    fun nativeExportRoundTripsWithoutLoss() {
        val firstBlock = PromptBlock(
            id = "block-b",
            title = "后置",
            content = "  keep surrounding whitespace  ",
            enabled = false,
            position = PromptBlockPosition.POST_HISTORY,
            order = 20,
        )
        val secondBlock = PromptBlock(
            id = "block-a",
            title = "前置",
            content = "system text",
            enabled = true,
            order = 10,
        )
        val template = PromptTemplate(
            id = "template-a",
            name = "Round Trip",
            description = "native",
            enabled = true,
            blocks = listOf(firstBlock, secondBlock),
            createdAt = 1234,
            updatedAt = 5678,
            source = "manual",
            sourceVersion = 3,
        )
        val config = PromptInjectionConfig(
            templates = listOf(template),
            bindings = listOf(
                PromptScopeBinding(template.id, PromptTarget.GLOBAL),
                PromptScopeBinding(template.id, PromptTarget.model("provider", "model")),
                PromptScopeBinding(template.id, PromptTarget.character("character"), enabled = false),
            ),
        )

        val exported = PromptImportCodec.exportNative(config)
        val preview = success(exported.toByteArray(), "prompt.eta.json")

        assertEquals(PromptImportFormat.ETA_NATIVE, preview.format)
        assertEquals(config.templates, preview.templates)
        assertEquals(config.bindings, preview.bindings)
        assertEquals(2, preview.blockCount)
        assertEquals(secondBlock.content.length, preview.characterCount)
        assertEquals(64, preview.sourceSha256.length)

        val reexported = PromptImportCodec.exportNative(
            PromptInjectionConfig(preview.templates, preview.bindings),
        )
        val secondPreview = success(reexported.toByteArray(), "again.json")
        assertEquals(config.templates, secondPreview.templates)
        assertEquals(config.bindings, secondPreview.bindings)
    }

    @Test
    fun nativeDuplicateTemplateIdsKeepFirstAndAreVisibleInPreview() {
        val raw = """
            {
              "schema": "eta.prompt-injection",
              "version": 1,
              "templates": [
                {"id":"same","name":"first","blocks":[{"title":"A","content":"first body"}]},
                {"id":"same","name":"second","blocks":[{"title":"B","content":"second body"}]}
              ],
              "bindings": [
                {"template_id":"same","target":{"type":"global"}}
              ]
            }
        """.trimIndent().toByteArray()

        val preview = success(raw, "duplicate.json")

        assertEquals(1, preview.templates.size)
        assertEquals("first", preview.templates.single().name)
        assertEquals(1, preview.bindings.size)
        assertTrue(preview.notices.any { it.message.contains("重复模板 ID") })
    }

    @Test
    fun markedMarkdownAndTextExportsRoundTripBlockState() {
        val template = PromptTemplate(
            id = "template-roundtrip",
            name = "Round trip",
            description = "keeps transfer metadata",
            enabled = false,
            blocks = listOf(
                PromptBlock(
                    id = "prefix",
                    title = "Prefix",
                    content = "first body",
                    enabled = true,
                    position = PromptBlockPosition.SYSTEM_PREFIX,
                    order = 10,
                ),
                PromptBlock(
                    id = "post",
                    title = "Post",
                    content = "second body",
                    enabled = false,
                    position = PromptBlockPosition.POST_HISTORY,
                    order = 20,
                ),
            ),
        )

        val markdown = success(
            PromptImportCodec.exportMarkdownTemplate(template).toByteArray(),
            "roundtrip.md",
        ).templates.single()
        assertEquals(template.name, markdown.name)
        assertEquals(template.description, markdown.description)
        assertFalse(markdown.enabled)
        assertEquals(listOf("Prefix", "Post"), markdown.blocks.map { it.title })
        assertEquals(listOf(true, false), markdown.blocks.map { it.enabled })
        assertEquals(PromptBlockPosition.POST_HISTORY, markdown.blocks[1].position)
        assertFalse(markdown.blocks.any { it.content.contains("eta-block") })

        val text = success(
            PromptImportCodec.exportPlainTextTemplate(template).toByteArray(),
            "roundtrip.txt",
        ).templates.single()
        assertEquals(template.name, text.name)
        assertEquals(template.description, text.description)
        assertFalse(text.enabled)
        assertEquals(listOf("Prefix", "Post"), text.blocks.map { it.title })
        assertEquals(listOf(true, false), text.blocks.map { it.enabled })
        assertEquals(PromptBlockPosition.POST_HISTORY, text.blocks[1].position)
        assertFalse(text.blocks.any { it.content.contains("eta-block") || it.content.contains("enabled=") })
    }

    @Test
    fun oversizedPlainTextIsSplitAtParagraphsInsteadOfRejected() {
        val firstParagraph = "A".repeat(PromptImportCodec.MAX_BLOCK_CHARS - 10)
        val source = firstParagraph + "\n\nsecond paragraph"

        val preview = success(source.toByteArray(), "large.txt")

        assertTrue(preview.templates.single().blocks.size >= 2)
        assertTrue(preview.templates.single().blocks.all { it.content.length <= PromptImportCodec.MAX_BLOCK_CHARS })
        assertTrue(preview.notices.any { it.message.contains("按段落拆分") })
    }

    @Test
    fun bracePrefixedNonJsonFileFallsBackToPlainText() {
        val source = "{this is plain prompt text, not JSON"

        val preview = success(source.toByteArray(), "json-like.txt")

        assertEquals(PromptImportFormat.PLAIN_TEXT, preview.format)
        assertEquals(source, preview.templates.single().blocks.single().content)
    }

    @Test
    fun smallProSchemaTwoJsonMapsPromptBlocks() {
        val preview = success(
            """
            {
              "schema": 2,
              "name": "小 PRO 示例",
              "description": "两层提示",
              "prompts": [
                {"name": "规则", "content": "保持简洁"},
                {"title": "补充", "text": "中文回答", "role": "user"}
              ]
            }
            """.trimIndent().toByteArray(),
            "pro.json",
        )

        assertEquals(PromptImportFormat.PRO_PRESET, preview.format)
        assertEquals("小 PRO 示例", preview.name)
        assertEquals(2, preview.blockCount)
        assertEquals(listOf("规则", "补充"), preview.templates.single().blocks.map { it.title })
        assertTrue(preview.notices.any { it.kind == PromptImportNoticeKind.CONVERSION })
    }

    @Test
    fun smallProZipReadsNestedPresetJson() {
        val preset = """
            {
              "schema": 2,
              "name": "ZIP PRO",
              "blocks": [{"name": "A", "content": "alpha"}]
            }
        """.trimIndent().toByteArray()
        val archive = zip(
            "folder/preset.json" to preset,
            "readme.txt" to "ignored".toByteArray(),
        )

        val preview = success(archive, "preset.zip")

        assertEquals(PromptImportFormat.PRO_PRESET, preview.format)
        assertEquals("ZIP PRO", preview.name)
        assertEquals(1, preview.blockCount)
        assertTrue(preview.notices.any { it.message.contains("folder/preset.json") })
    }

    @Test
    fun sillyTavernPresetMapsOrderPositionAndMarkers() {
        val preview = success(
            """
            {
              "name": "ST Preset",
              "prompts": [
                {"identifier": "main", "name": "Main", "content": "main prompt", "system_prompt": true},
                {"identifier": "marker", "name": "Marker", "marker": true, "content": ""},
                {"identifier": "post", "name": "Post", "content": "post prompt", "injection_position": 1}
              ],
              "prompt_order": [
                {"order": [
                  {"identifier": "post", "enabled": false},
                  {"identifier": "main", "enabled": true}
                ]}
              ],
              "temperature": 0.8
            }
            """.trimIndent().toByteArray(),
            "st.json",
        )

        assertEquals(PromptImportFormat.SILLYTAVERN_PRESET, preview.format)
        assertEquals(2, preview.blockCount)
        val post = preview.templates.single().blocks.first { it.title == "Post" }
        assertEquals(PromptBlockPosition.POST_HISTORY, post.position)
        assertFalse(post.enabled)
        assertTrue(preview.notices.any { it.kind == PromptImportNoticeKind.IGNORED && it.message.contains("Marker") })
        assertTrue(preview.notices.any { it.message.contains("prompt_order") })
    }

    @Test
    fun genericJsonMapsSystemFieldsAndNeverAppliesToolDeclarations() {
        val preview = success(
            """
            {
              "name": "Generic",
              "instructions": "instruction text",
              "system_prompt": "system text",
              "messages": [
                {"role": "system", "content": "message system"},
                {"role": "developer", "content": [{"type": "text", "text": "developer text"}]},
                {"role": "user", "content": "do not import"}
              ],
              "tools": [{"type": "function", "name": "shell"}],
              "tool_choice": "auto"
            }
            """.trimIndent().toByteArray(),
            "generic.json",
        )

        assertEquals(PromptImportFormat.GENERIC_JSON, preview.format)
        assertEquals(4, preview.blockCount)
        assertTrue(preview.templates.single().blocks.any { it.content == "developer text" })
        assertFalse(preview.templates.single().blocks.any { it.content.contains("shell") })
        assertTrue(
            preview.notices.any {
                it.kind == PromptImportNoticeKind.UNTRUSTED_TOOL_DECLARATION && it.message.contains("未授权")
            },
        )
        assertTrue(preview.notices.any { it.message.contains("非 system/developer") })
    }

    @Test
    fun externalJsonNeverImportsScopeBindings() {
        val preview = success(
            """
            {
              "instructions": "instruction text",
              "bindings": [
                {"template_id":"external","target":{"type":"global"}}
              ]
            }
            """.trimIndent().toByteArray(),
            "external.json",
        )

        assertTrue(preview.bindings.isEmpty())
        assertTrue(preview.notices.any { it.message.contains("作用域绑定未导入") })
    }

    @Test
    fun markdownFrontMatterAndHeadingsBecomeBlocksWithoutSplittingFences() {
        val preview = success(
            """
            ---
            name: 自定义规则
            description: markdown import
            tags: ignored
            ---
            # System Rules
            Be concise.

            ~~~text
            # this is code, not a heading
            ~~~

            ## Style
            Use Chinese.
            """.trimIndent().toByteArray(),
            "prompt.md",
        )

        assertEquals(PromptImportFormat.MARKDOWN, preview.format)
        assertEquals("自定义规则", preview.name)
        assertEquals(2, preview.blockCount)
        assertEquals(listOf("System Rules", "Style"), preview.templates.single().blocks.map { it.title })
        assertTrue(preview.templates.single().blocks[0].content.contains("# this is code"))
        assertTrue(preview.notices.any { it.message.contains("tags") })
    }

    @Test
    fun plainTextUsesFileNameAsName() {
        val preview = success("plain prompt body".toByteArray(), "rules.txt")

        assertEquals(PromptImportFormat.PLAIN_TEXT, preview.format)
        assertEquals("rules", preview.name)
        assertEquals(1, preview.blockCount)
        assertEquals("plain prompt body", preview.templates.single().blocks.single().content)
    }

    @Test
    fun characterCardPngMapsPromptFields() {
        val card = CharacterCardCodec.create("角色甲").withEdits(
            systemPrompt = "card system prompt",
            postHistoryInstructions = "card post prompt",
            description = "ignored description",
        )
        val png = CharacterCardPng.write(onePixelPng(), card)

        val preview = success(png, "character.png")

        assertEquals(PromptImportFormat.CHARACTER_CARD_PNG, preview.format)
        assertEquals("角色甲", preview.name)
        assertEquals(2, preview.blockCount)
        assertEquals(PromptBlockPosition.POST_HISTORY, preview.templates.single().blocks[1].position)
        assertTrue(preview.notices.any { it.message.contains("description") })
    }

    @Test
    fun invalidUtf8AndInvalidJsonAreRejected() {
        assertFailure(
            PromptImportErrorCode.INVALID_UTF8,
            byteArrayOf(0xC3.toByte(), 0x28),
            "bad.txt",
        )
        assertFailure(
            PromptImportErrorCode.INVALID_JSON,
            "{\"instructions\":".toByteArray(),
            "bad.json",
        )
    }

    @Test
    fun maliciousZipPathsAndDuplicatesAreRejected() {
        val preset = "{\"schema\":2,\"blocks\":[{\"content\":\"ok\"}]}".toByteArray()
        assertFailure(
            PromptImportErrorCode.ZIP_UNSAFE_PATH,
            zip("../escape.txt" to "bad".toByteArray(), "preset.json" to preset),
            "traversal.zip",
        )
        assertFailure(
            PromptImportErrorCode.ZIP_DUPLICATE_ENTRY,
            zip("preset.json" to preset, "preset.json" to preset),
            "duplicate.zip",
        )
        assertFailure(
            PromptImportErrorCode.ZIP_PRESET_MISSING,
            zip("prompt.txt" to "nothing".toByteArray()),
            "missing.zip",
        )
        assertFailure(
            PromptImportErrorCode.INVALID_UTF8,
            zip("preset.json" to byteArrayOf(0xC3.toByte(), 0x28)),
            "utf8.zip",
        )
    }

    @Test
    fun zipEntryCountAndEntrySizeLimitsAreRejected() {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        entries += "preset.json" to "{\"schema\":2,\"blocks\":[{\"content\":\"ok\"}]}".toByteArray()
        repeat(64) { index -> entries += ("entry-" + index + ".txt") to byteArrayOf(1) }
        assertFailure(PromptImportErrorCode.ZIP_TOO_MANY_ENTRIES, zip(*entries.toTypedArray()), "many.zip")

        assertFailure(
            PromptImportErrorCode.ZIP_ENTRY_TOO_LARGE,
            zip(
                "preset.json" to "{\"schema\":2,\"blocks\":[{\"content\":\"ok\"}]}".toByteArray(),
                "big.bin" to ByteArray(4 * 1024 * 1024 + 1),
            ),
            "big.zip",
        )
    }

    @Test
    fun zipCompressionRatioAndExtractedTotalLimitsAreRejected() {
        val preset = "{\"schema\":2,\"blocks\":[{\"content\":\"ok\"}]}".toByteArray()
        assertFailure(
            PromptImportErrorCode.ZIP_COMPRESSION_RATIO,
            zip("preset.json" to preset, "bomb.bin" to ByteArray(1024 * 1024)),
            "bomb.zip",
        )
        assertFailure(
            PromptImportErrorCode.ZIP_TOTAL_TOO_LARGE,
            zip(
                "preset.json" to preset,
                "one.bin" to mixedBytes(3 * 1024 * 1024, 1),
                "two.bin" to mixedBytes(3 * 1024 * 1024, 2),
                "three.bin" to mixedBytes(3 * 1024 * 1024, 3),
            ),
            "total.zip",
        )
    }

    @Test
    fun blockAndInputLimitsAreRejected() {
        val tooManyMessages = (0 until 101).joinToString(prefix = "{\"messages\":[", postfix = "]}") { index ->
            "{\"role\":\"system\",\"content\":\"block " + index + "\"}"
        }
        assertFailure(
            PromptImportErrorCode.TOO_MANY_BLOCKS,
            tooManyMessages.toByteArray(),
            "many.json",
        )

        val tooLargeBlock = "{\"instructions\":\"" + "x".repeat(PromptImportCodec.MAX_BLOCK_CHARS + 1) + "\"}"
        assertFailure(
            PromptImportErrorCode.BLOCK_TOO_LARGE,
            tooLargeBlock.toByteArray(),
            "large.json",
        )

        assertFailure(
            PromptImportErrorCode.INPUT_TOO_LARGE,
            ByteArray(PromptImportCodec.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() },
            "large.txt",
        )
    }

    @Test
    fun gatewayStopsReadingTextAtTheBound() {
        val result = PromptImportGateway.inspect(
            openStream = {
                ByteArrayInputStream(ByteArray(PromptImportCodec.MAX_TEXT_BYTES + 100) { 'a'.code.toByte() })
            },
            sourceName = "stream.txt",
        )

        assertTrue(result is PromptImportResult.Failure)
        assertEquals(PromptImportErrorCode.INPUT_TOO_LARGE, (result as PromptImportResult.Failure).code)
    }

    private fun success(bytes: ByteArray, sourceName: String): PromptImportPreview =
        when (val result = PromptImportCodec.inspect(bytes, sourceName)) {
            is PromptImportResult.Success -> result.preview
            is PromptImportResult.Failure -> {
                fail("import failed: " + result.code + " " + result.message)
                error("unreachable")
            }
        }

    private fun assertFailure(code: PromptImportErrorCode, bytes: ByteArray, sourceName: String) {
        when (val result = PromptImportCodec.inspect(bytes, sourceName)) {
            is PromptImportResult.Success -> fail("expected failure " + code + " but import succeeded")
            is PromptImportResult.Failure -> assertEquals(code, result.code)
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun mixedBytes(size: Int, seed: Int): ByteArray {
        var state = seed
        return ByteArray(size) { index ->
            state = state * 1103515245 + 12345
            if (index % 4 == 0) 0.toByte() else (state ushr 16).toByte()
        }
    }

    private fun onePixelPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    )
}
