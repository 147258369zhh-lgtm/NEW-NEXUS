package io.github.mangi.eta.ui.screens.localmodel

import io.github.mangi.eta.data.repository.LocalModelFiles
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelPreset
import io.github.mangi.eta.data.repository.LocalModelRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalModelFoundationTest {
    @Test
    fun presetSourcesPointToSupportedArtifactsAndHaveExpectedMetadata() {
        LocalModelPreset.all.forEach { preset ->
            assertEquals(preset, LocalModelRepository.presetFor(preset.url))
            assertEquals(preset.id, LocalModelRepository.presetFor(" ${preset.url} ")?.id)
            assertEquals(LocalModelFormat.fromFileName(preset.url), LocalModelFiles.formatFromUrl(preset.url))
            assert(preset.expectedSizeBytes == null || preset.expectedSizeBytes > 0)
            preset.fallbackUrls.forEach { fallback ->
                assertEquals(preset, LocalModelRepository.presetFor(fallback))
                assertEquals(LocalModelFiles.formatFromUrl(preset.url), LocalModelFiles.formatFromUrl(fallback))
                LocalModelRepository.validateDownloadUrl(fallback)
            }
        }
        LocalModelPreset.all.forEach { preset ->
            assertEquals(64, preset.expectedSha256?.length)
        }
        assertEquals(LocalModelFormat.LITERT_LM, LocalModelFiles.formatFromUrl(LocalModelPreset.Gemma4E2B.url))
        assertEquals(LocalModelFormat.GGUF, LocalModelFiles.formatFromUrl(LocalModelPreset.Qwen25Small.url))
        assertEquals(343_490_560L, LocalModelPreset.Qwen3DynamicInt4.expectedSizeBytes)
        assertEquals(
            "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0",
            LocalModelPreset.Qwen3DynamicInt4.expectedSha256,
        )
        assertEquals(584_417_280L, LocalModelPreset.Gemma3OneBInt4.expectedSizeBytes)
    }

    @Test
    fun installedCatalogRowsMatchPresetsByStableArtifactIdentity() {
        val preset = LocalModelPreset.Qwen3DynamicInt4
        val base = io.github.mangi.eta.data.repository.LocalModelRecord(
            id = LocalModelRepository.downloadIdFor(preset.fallbackUrls.first()),
            displayName = "Qwen3 LiteRT",
            fileName = "${preset.expectedSha256!!.take(16)}-" +
                preset.url.substringAfterLast('/'),
            format = LocalModelFormat.LITERT_LM,
            sizeBytes = preset.expectedSizeBytes!!,
            sha256 = preset.expectedSha256,
            sourceUrl = preset.fallbackUrls.first(),
        )
        assertEquals(preset.id, LocalModelRepository.presetForRecord(base)?.id)
        assertEquals(
            preset.id,
            LocalModelRepository.presetForRecord(
                base.copy(sourceUrl = "content://imported/model", sha256 = preset.expectedSha256),
            )?.id,
        )
        assertEquals(
            preset.id,
            LocalModelRepository.presetForRecord(
                base.copy(
                    sourceUrl = null,
                    sha256 = "unknown",
                    fileName = "custom-${preset.url.substringAfterLast('/')}",
                ),
            )?.id,
        )
        assertNull(
            LocalModelRepository.presetForRecord(
                base.copy(sourceUrl = null, sha256 = "unknown", fileName = "unrelated.gguf"),
            ),
        )
    }

    @Test
    fun recommendedModelsUseDomesticPrimaryAndAReachableMirrorFallback() {
        assertEquals(
            "modelscope.cn",
            java.net.URI(LocalModelPreset.Qwen3DynamicInt4.url).host,
        )
        assertTrue(LocalModelPreset.Qwen3DynamicInt4.fallbackUrls.any { "hf-mirror.com" in it })
        assertTrue(LocalModelPreset.Qwen25Small.fallbackUrls.any { "hf-mirror.com" in it })
        assertTrue(LocalModelPreset.Gemma4E2B.fallbackUrls.any { "hf-mirror.com" in it })
    }

    @Test
    fun supportedExtensionsAreRecognizedCaseInsensitively() {
        mapOf(
            "model.litertlm" to LocalModelFormat.LITERT_LM,
            "model.GGUF" to LocalModelFormat.GGUF,
            "model.task" to LocalModelFormat.TASK,
            "model.onnx" to LocalModelFormat.ONNX,
            "model.tflite" to LocalModelFormat.TFLITE,
            "model.safetensors" to LocalModelFormat.SAFETENSORS,
            "model.pth" to LocalModelFormat.OTHER,
        ).forEach { (name, expected) ->
            assertEquals(expected, LocalModelFormat.fromFileName(name))
        }
        assertNull(LocalModelFormat.fromFileName("model.exe"))
    }

    @Test
    fun downloadAddressRequiresSupportedHttpArtifactWithoutCredentialsOrFragment() {
        assertEquals(
            LocalModelPreset.Gemma4E2B.url,
            LocalModelRepository.validateDownloadUrl(" ${LocalModelPreset.Gemma4E2B.url} "),
        )
        listOf(
            "ftp://example.com/model.gguf",
            "https://example.com/model.zip",
            "https://user:secret@example.com/model.gguf",
            "https://example.com/model.gguf#fragment",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalModelRepository.validateDownloadUrl(invalid)
            }
        }
    }

    @Test
    fun localServiceEndpointAllowsLoopbackAndPrivateLanOnly() {
        listOf(
            "http://127.0.0.1:11434/v1",
            "http://localhost:1234/v1/",
            "http://192.168.1.8:8080/v1",
            "http://eta-box.local/v1",
        ).forEach { endpoint ->
            assertEquals(endpoint.trimEnd('/'), LocalModelRepository.validateServiceEndpoint(endpoint))
        }
        listOf(
            "https://api.openai.com/v1",
            "file:///sdcard/model.gguf",
            "http://"
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalModelRepository.validateServiceEndpoint(invalid)
            }
        }
    }

    @Test
    fun android17LocalNetworkPermissionIsOnlyNeededForNonLoopbackServices() {
        assertFalse(endpointRequiresLocalNetworkPermission("http://127.0.0.1:9379/v1"))
        assertFalse(endpointRequiresLocalNetworkPermission("http://localhost:11434/v1"))
        assertTrue(endpointRequiresLocalNetworkPermission("http://192.168.1.8:8080/v1"))
        assertTrue(endpointRequiresLocalNetworkPermission("http://eta-box.local:1234/v1"))
    }

    @Test
    fun safeFileNameRemovesDirectoryTraversalAndUnsupportedCharacters() {
        assertEquals("sha-model.gguf", LocalModelFiles.safeFileName("../sha-model.gguf"))
        assertEquals("a_model.gguf", LocalModelFiles.safeFileName("a model.gguf"))
        assertEquals("b.gguf", LocalModelFiles.safeFileName("C:\\models\\b.gguf"))
    }

    @Test
    fun storageAndContextLabelsStayCompact() {
        assertEquals("0 B", formatLocalModelBytes(0))
        assertEquals("512 B", formatLocalModelBytes(512))
        assertEquals("1.0 KB", formatLocalModelBytes(1024))
        assertEquals("2.4 GB", formatLocalModelBytes(2_588_147_712L))
        assertEquals("8K", formatCompactLocalContext(8_192))
        assertEquals("32K", formatCompactLocalContext(32_768))
        assertEquals("1M", formatCompactLocalContext(1_000_000))
    }

    @Test
    fun automaticAttachmentChoosesTheMatchingServerModel() {
        assertEquals(
            "qwen2.5:0.5b",
            chooseLocalServerModelId(
                listOf("llama3:8b", "qwen2.5:0.5b"),
                "Qwen 2.5 0.5B Instruct \u00b7 GGUF",
            ),
        )
        assertEquals(
            "gemma-4-E2B-it",
            chooseLocalServerModelId(
                listOf("gemma-4-E2B-it", "llama3:8b"),
                "Gemma 4 E2B",
            ),
        )
        assertEquals(
            "first",
            chooseLocalServerModelId(listOf("first", "second"), "unknown file"),
        )
        assertEquals("", chooseLocalServerModelId(emptyList(), "anything"))
    }
}
