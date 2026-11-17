package io.github.mangi.eta.agent.voice

import android.app.Application
import io.github.mangi.eta.data.repository.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SpeechModelPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun ttsPassesPackagedPhonemizerDirectoriesToNativeEngine() {
        val root = temporary.newFolder("sherpa-kokoro")
        listOf("model.onnx", "tokens.txt", "lexicon.txt", "voices.bin").forEach { root.resolve(it).writeText("fixture") }
        root.resolve("espeak-ng-data").mkdir()
        root.resolve("dict").mkdir()
        val spec = checkNotNull(SpeechModelPackage.inspect(root))
        val native = SherpaSpeechRuntime.ttsConfig(spec, 2).model.kokoro
        assertEquals(root.resolve("espeak-ng-data").absolutePath, native.dataDir)
        assertEquals(root.resolve("dict").absolutePath, native.dictDir)
    }
    private fun archive(files: Map<String, ByteArray>, tar: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        if (tar) (TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)) as org.apache.commons.compress.archivers.ArchiveOutputStream<TarArchiveEntry>).use { output ->
            files.forEach { (name, content) ->
                val entry = TarArchiveEntry(name).apply { size = content.size.toLong() }
                output.putArchiveEntry(entry); output.write(content); output.closeArchiveEntry()
            }
        } else ZipOutputStream(bytes).use { output -> files.forEach { (name, content) ->
            output.putNextEntry(ZipEntry(name)); output.write(content); output.closeEntry()
        } }
        return bytes.toByteArray()
    }
    private fun fixture(root: String = "sherpa-sensevoice", extra: Map<String, ByteArray> = emptyMap()) =
        mapOf("$root/tokens.txt" to "a 0".toByteArray(), "$root/model.int8.onnx" to byteArrayOf(8, 1, 2, 3)) + extra

    @Test fun zipAndBzip2BundlesUseTheSameValidatedModelAndRealNativeConfiguration() {
        for (tar in listOf(false, true)) {
            val root = SpeechModelPackage.extract(ByteArrayInputStream(archive(fixture(), tar)),
                if (tar) "model.tar.bz2" else "model.zip", File(temporary.root, "staging-$tar"), 1024)
            val model = SpeechModelPackage.inspect(root)!!
            assertEquals(LocalSpeechKind.ASR, model.kind); assertEquals("sensevoice", model.architecture)
            val native = SherpaSpeechRuntime.offlineConfig(model, 3)
            assertEquals(model.path("model"), native.modelConfig.senseVoice.model)
            assertEquals(model.path("tokens"), native.modelConfig.tokens)
            assertEquals(3, native.modelConfig.numThreads); assertEquals("cpu", native.modelConfig.provider)
            assertTrue(native.modelConfig.senseVoice.useInverseTextNormalization)
        }
    }

    @Test fun whisperQuantizedFileNamesAreRecognizedAndManifestCannotReferenceOutsideItsPackage() {
        val files = mapOf("whisper-tiny/tiny-tokens.txt" to "a 0".toByteArray(),
            "whisper-tiny/tiny-encoder.int8.onnx" to byteArrayOf(8, 1), "whisper-tiny/tiny-decoder.int8.onnx" to byteArrayOf(8, 1))
        val root = SpeechModelPackage.extract(ByteArrayInputStream(archive(files, false)), "whisper.zip", File(temporary.root, "whisper"), 1024)
        val model = SpeechModelPackage.inspect(root)!!
        assertEquals("whisper", model.architecture)
        assertEquals(model.path("decoder"), SherpaSpeechRuntime.offlineConfig(model, 2).modelConfig.whisper.decoder)
        root.resolve("nexus-speech.json").writeText("""{"version":1,"architecture":"sensevoice","files":{"model":"../outside.onnx","tokens":"tokens.txt"}}""")
        assertThrows(IllegalArgumentException::class.java) { SpeechModelPackage.inspect(root) }
    }

    @Test fun archivesRejectTraversalDuplicateModelsAndOversizedPayloadsBeforeNativeLoading() {
        for ((index, files) in listOf(fixture(extra = mapOf("../escape" to byteArrayOf(1))),
            fixture() + fixture("other-sensevoice"), fixture(extra = mapOf("large.bin" to ByteArray(4096)))).withIndex()) {
            assertThrows(IllegalArgumentException::class.java) {
                SpeechModelPackage.extract(ByteArrayInputStream(archive(files, false)), "bad.zip", File(temporary.root, "bad-$index"), 1024)
            }
        }
        assertFalse(temporary.root.parentFile.resolve("escape").exists())
    }

    @Test fun actualHttpDownloadInstallsSpeechMetadataDeduplicatesAndVerifiesTheWholeDirectory() = runBlocking {
        val bytes = archive(fixture(), true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.tar.bz2") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowStatFs.registerStats(context.filesDir.absolutePath, 1024 * 1024, 512 * 1024, 512 * 1024)
        try {
            suspend fun install(id: String) = LocalModelRepository.performDownload(context, id, "Fixture ASR",
                "http://127.0.0.1:${server.address.port}/model.tar.bz2", bytes.size.toLong(), null, "fixture",
                listOf("audio"), listOf("text")) { _, _ -> }
            val first = install("speech-one")
            assertEquals(LocalModelFormat.ONNX, first.format)
            assertEquals(listOf("audio"), first.inputModalities)
            assertEquals(LocalSpeechKind.ASR, localSpeechKind(first))
            assertNotNull(first.archiveSha256)
            assertEquals(first.id, install("speech-two").id)
            assertNotNull(LocalModelRepository.verify(first.id).lastVerifiedAt)
            val folder = LocalModelRepository.artifactFile(context, first)
            assertTrue(folder.isDirectory)
            folder.resolve("model.int8.onnx").appendBytes(byteArrayOf(9))
            assertTrue(runCatching { LocalModelRepository.verify(first.id) }.isFailure)
        } finally { server.stop(0) }
    }

    @Test fun officialChineseTtsBundleUsesItsRealLexiconSpeakersAndFstFiles() {
        val path = System.getenv("NEXUS_SPEECH_TEST_ARCHIVE")
        assumeTrue(path != null && File(path).isFile)
        val root = FileInputStream(path!!).use { SpeechModelPackage.extract(it, "official.tar.bz2", File(temporary.root, "official"), 256L * 1024 * 1024) }
        val spec = SpeechModelPackage.inspect(root)!!
        assertEquals("vits", spec.architecture); assertEquals(LocalSpeechKind.TTS, spec.kind)
        assertTrue(File(spec.path("lexicon")).length() > 0)
        val config = SherpaSpeechRuntime.ttsConfig(spec, 2)
        assertEquals(spec.path("model"), config.model.vits.model)
        assertEquals(spec.path("lexicon"), config.model.vits.lexicon)
        assertTrue(config.ruleFsts.contains("number.fst"))
        assertEquals("SSB0005", spec.voices.first())
        assertEquals(spec.path("rule_fars"), config.ruleFars)
    }

    @Test fun manifestSupportsKokoroLexiconListsAndExtractionStopsOnCancellation() {
        val root = temporary.newFolder("kokoro")
        for (name in listOf("model.onnx", "voices.bin", "tokens.txt", "lexicon-zh.txt", "lexicon-en.txt")) root.resolve(name).writeBytes(byteArrayOf(1, 2))
        root.resolve("nexus-speech.json").writeText("""{"version":1,"architecture":"kokoro","language":"zh","voice_names":["Voice A","Voice B"],"files":{"model":"model.onnx","voices":"voices.bin","tokens":"tokens.txt","lexicon":"lexicon-zh.txt,lexicon-en.txt"}}""")
        val spec = SpeechModelPackage.inspect(root)!!
        val config = SherpaSpeechRuntime.ttsConfig(spec, 4)
        assertEquals(2, spec.voices.size)
        assertEquals("zh", config.model.kokoro.lang)
        assertEquals(spec.path("lexicon"), config.model.kokoro.lexicon)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SpeechModelPackage.extract(ByteArrayInputStream(archive(fixture(), false)), "cancel.zip", File(temporary.root, "cancel"), 1024) {
                throw kotlinx.coroutines.CancellationException("fixture")
            }
        }
    }
}
