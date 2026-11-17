package io.github.mangi.eta.agent.cloud

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CloudDeepSeekConfig
import io.github.mangi.eta.data.model.CloudToolsCredentials
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.model.CloudToolsSettings
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Application interceptors answer every HTTP call locally; no real service or billing is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [CloudAtomicFileRenameShadow::class])
class CloudDocumentRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val config get() = CloudToolsRuntimeConfig(
        CloudToolsSettings(enabled = true, deepSeek = CloudDeepSeekConfig(baseUrl = "https://fixture.example")),
        CloudToolsCredentials(deepSeekApiKey = "fixture-key"),
    )

    @Test
    fun ocrPreservesPerPageTruncationAndFullArtifactsWhileSameRequestNeverResubmits() = runBlocking {
        val scope = "ocr-${UUID.randomUUID()}"
        val requests = AtomicInteger()
        val reads = AtomicInteger()
        val firstPage = "complete-page-" + "A".repeat(30_100)
        val truncatedPage = "partial-page-marker"
        val pages = JSONArray()
            .put(JSONObject().put("index", 0).put("finish_reason", "stop").put("text", firstPage))
            .put(JSONObject().put("index", 1).put("finish_reason", "length").put("text", truncatedPage))
        val transport = transport { request ->
            requests.incrementAndGet()
            assertEquals("POST", request.method)
            assertEquals("/v1/tools/ocr", request.url.encodedPath)
            assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data"))
            val body = requestBody(request)
            assertTrue(body.contains("fixture-pdf-bytes"))
            assertTrue(body.indexOf("name=\"purpose\"") < body.indexOf("filename=\"scan.pdf\""))
            assertTrue(body.contains("rich_layout_recognition"))
            JSONObject().put("id", "ocr-fixture").put("pages", pages)
                .put("usage", JSONObject().put("pages", 2))
        }
        val saved = mutableListOf<SavedOutput>()
        val firstContext = context(scope, saved) { reference ->
            assertEquals("authorized-scan", reference)
            CloudInputFile("scan.pdf", "application/pdf", 17) {
                reads.incrementAndGet()
                "fixture-pdf-bytes".byteInputStream()
            }
        }
        val args = JSONObject().put("operation", "ocr").put("request_id", "request-${UUID.randomUUID()}")
            .put("file", "authorized-scan").put("purpose", "rich_layout_recognition").put("output_prefix", "fixture")
        val first = CloudDocumentTools.executeConfigured(firstContext, args, config, transport)

        assertTrue(first.toString(), first.getBoolean("ok"))
        assertFalse(first.getBoolean("complete"))
        assertEquals("[1]", first.getJSONArray("incomplete_pages").toString())
        assertEquals(2, first.getJSONObject("result").getInt("page_count"))
        assertEquals(30_000, first.getString("text").length)
        assertFalse(first.getString("text").contains(truncatedPage))
        assertEquals(2, saved.size)
        val markdown = saved.single { it.name == "fixture-ocr.md" }
        assertEquals("text/markdown", markdown.mimeType)
        assertEquals("<!-- page 1 -->\n$firstPage\n\n<!-- page 2 -->\n$truncatedPage\n\n", markdown.bytes.toString(Charsets.UTF_8))
        val structure = saved.single { it.name == "fixture-ocr-pages.json" }
        assertEquals("application/json", structure.mimeType)
        val savedPages = JSONObject(structure.bytes.toString(Charsets.UTF_8)).getJSONArray("pages")
        assertEquals(firstPage, savedPages.getJSONObject(0).getString("text"))
        assertEquals("length", savedPages.getJSONObject(1).getString("finish_reason"))
        assertEquals(truncatedPage, savedPages.getJSONObject(1).getString("text"))
        assertEquals("fixture:fixture-ocr.md", first.getJSONObject("output").getString("artifact_id"))

        // A new run in the same scope restores the receipt, without input reads, HTTP or re-saving.
        val recovered = CloudDocumentTools.executeConfigured(context(scope, saved), JSONObject(args.toString()), config, transport)
        assertTrue(recovered.getBoolean("ok"))
        assertTrue(recovered.getBoolean("reused_request"))
        assertFalse(recovered.getBoolean("complete"))
        assertEquals(first.getJSONObject("output").toString(), recovered.getJSONObject("output").toString())
        assertEquals(1, requests.get())
        assertEquals(1, reads.get())
        assertEquals(2, saved.size)
    }

    @Test
    fun uploadedFileSurvivesSameScopeRestartForStatusListAndQuestionButAnotherScopeCannotReferenceIt() = runBlocking {
        val scope = "files-${UUID.randomUUID()}"
        val fileId = "file-${UUID.randomUUID()}"
        val calls = AtomicInteger()
        val uploads = AtomicInteger()
        val questions = AtomicInteger()
        val saved = mutableListOf<SavedOutput>()
        val remoteFile = JSONObject().put("id", fileId).put("purpose", "text_recognition")
            .put("filename", "notes.txt").put("created_at", 1_700_000_000)
            .put("expires_at", System.currentTimeMillis() / 1000 + 86_400)
        val transport = transport { request ->
            calls.incrementAndGet()
            when (request.method to request.url.encodedPath) {
                "POST" to "/files" -> {
                    uploads.incrementAndGet()
                    val body = requestBody(request)
                    assertTrue(body.contains("filename=\"notes.txt\""))
                    assertTrue(body.contains("authorized-file-content"))
                    assertTrue(body.contains("expires_after[seconds]"))
                    remoteFile
                }
                "GET" to "/files/$fileId" -> remoteFile
                "GET" to "/files" -> JSONObject().put("data", JSONArray().put(remoteFile)
                    .put(JSONObject().put("id", "file-another-owner").put("created_at", 1_700_000_001)))
                    .put("has_more", false)
                "POST" to "/chat/completions" -> {
                    questions.incrementAndGet()
                    val body = JSONObject(requestBody(request))
                    val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                    assertEquals("Summarize this file", content.getJSONObject(0).getString("text"))
                    assertEquals("file", content.getJSONObject(1).getString("type"))
                    assertEquals(fileId, content.getJSONObject(1).getString("file_id"))
                    JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                        .put("message", JSONObject().put("content", "fixture-answer"))))
                }
                else -> error("Unexpected local fixture request: ${request.method} ${request.url.encodedPath}")
            }
        }
        val original = context(scope, saved) { reference ->
            assertEquals("authorized-notes", reference)
            CloudInputFile("notes.txt", "text/plain", 23) { "authorized-file-content".byteInputStream() }
        }
        val upload = CloudDocumentTools.executeConfigured(original, JSONObject().put("operation", "file_upload")
            .put("request_id", "upload-${UUID.randomUUID()}").put("file", "authorized-notes"), config, transport)
        assertTrue(upload.toString(), upload.getBoolean("ok"))
        assertEquals(fileId, upload.getString("file_id"))
        assertTrue(upload.getBoolean("cloud_only"))
        assertEquals(1, uploads.get())

        val restarted = context(scope, saved)
        assertNotEquals(original.controller.executionIdentity!!.runId, restarted.controller.executionIdentity!!.runId)
        val statusArgs = JSONObject().put("operation", "file_status").put("file_id", fileId)
        val status = CloudDocumentTools.executeConfigured(restarted, statusArgs, config, transport)
        assertTrue(status.toString(), status.getBoolean("ok"))
        assertEquals(fileId, status.getJSONObject("result").getString("id"))
        val list = CloudDocumentTools.executeConfigured(restarted, JSONObject().put("operation", "file_list"), config, transport)
        assertEquals("current_owner", list.getString("scope"))
        val ownedFiles = list.getJSONObject("result").getJSONArray("data")
        assertEquals(1, ownedFiles.length())
        assertEquals(fileId, ownedFiles.getJSONObject(0).getString("id"))
        val questionArgs = JSONObject().put("operation", "file_question").put("file_ids", JSONArray().put(fileId))
            .put("prompt", "Summarize this file").put("request_id", "question-${UUID.randomUUID()}")
        val answer = CloudDocumentTools.executeConfigured(restarted, questionArgs, config, transport)
        assertTrue(answer.toString(), answer.getBoolean("ok"))
        assertEquals("fixture-answer", answer.getString("text"))
        assertEquals("fixture-answer", saved.single { it.name == "analysis.md" }.bytes.toString(Charsets.UTF_8))
        assertEquals(1, questions.get())

        val callsBeforeDenied = calls.get()
        val otherScope = context("other-$scope", saved)
        val deniedStatus = CloudDocumentTools.executeConfigured(otherScope, statusArgs, config, transport)
        val deniedQuestion = CloudDocumentTools.executeConfigured(otherScope, JSONObject(questionArgs.toString())
            .put("request_id", "question-${UUID.randomUUID()}"), config, transport)
        listOf(deniedStatus, deniedQuestion).forEach { denied ->
            assertFalse(denied.getBoolean("ok"))
            assertEquals("cloud_resource_not_owned", denied.getString("error"))
        }
        assertEquals(callsBeforeDenied, calls.get())
        assertEquals(1, uploads.get())
        assertEquals(1, questions.get())
        assertEquals(1, saved.size)
    }

    private data class SavedOutput(val name: String, val mimeType: String, val bytes: ByteArray)

    private fun context(scope: String, saved: MutableList<SavedOutput>,
        input: (String) -> CloudInputFile = { error("No new file read expected") }): CloudToolContext {
        val controller = AgentRunController().apply {
            bindExecutionIdentity(RunRequest(RunId("run-${UUID.randomUUID()}"), RunSource.CHAT,
                RunMode.CHAT, HostId("local"), projectId = "fixture-project"))
        }
        return CloudToolContext(app, controller, input, { name, mime, bytes ->
            saved += SavedOutput(name, mime, bytes.copyOf())
            JSONObject().put("ok", true).put("artifact_id", "fixture:$name").put("label", name)
                .put("path", "/fixture/$name").put("mime_type", mime).put("bytes", bytes.size)
        }, scope)
    }

    private fun requestBody(request: Request): String = Buffer().apply { request.body!!.writeTo(this) }.readUtf8()

    private fun transport(answer: (Request) -> JSONObject): CloudApiTransport = CloudApiTransport(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("fixture.example", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer(chain.request()).toString().toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )
}
