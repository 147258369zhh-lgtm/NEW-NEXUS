package io.github.mangi.eta.agent.cloud

import android.app.Application
import android.util.AtomicFile
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CloudBailianConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** In-memory HTTP fixtures: these tests never reach a real API or create billable work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [CloudAtomicFileRenameShadow::class])
class CloudMediaRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val config get() = CloudToolsRuntimeConfig(CloudToolsSettings(enabled = true,
        bailian = CloudBailianConfig(workspaceId = "fixture")), CloudToolsCredentials(bailianApiKey = "fixture-key"))

    @Test fun restartedRunCanFindItsOwnTaskButAnotherSubjectOrProjectCannot() {
        val scope = "subject-${UUID.randomUUID()}"
        val first = context(scope, "project-a")
        val store = CloudMediaTaskStore(first)
        val task = "task-${UUID.randomUUID()}"
        store.create(task, "video", "https://fixture.cn-beijing.maas.aliyuncs.com", "account-hash", "wan2.7-t2v", pending(task))
        assertEquals(task, CloudMediaTaskStore(context(scope, "project-a")).load(task).getString("task_id"))
        assertEquals(1, CloudMediaTaskStore(context(scope, "project-a")).list().length())
        assertTrue(runCatching { CloudMediaTaskStore(context("other-$scope", "project-a")).load(task) }.isFailure)
        assertTrue(runCatching { CloudMediaTaskStore(context(scope, "project-b")).load(task) }.isFailure)
        val text = app.noBackupFilesDir.resolve("cloud-media-tasks-v1").walkTopDown().filter { it.extension == "json" }.joinToString { it.readText() }
        assertFalse(text.contains("fixture-key"))
    }

    @Test fun repeatingVideoSubmissionRestoresTaskWithoutCreatingAnotherBillableJob() = runBlocking {
        val task = "task-${UUID.randomUUID()}"
        val posts = AtomicInteger(); val gets = AtomicInteger()
        val transport = transport { request ->
            if (request.method == "POST") { posts.incrementAndGet(); assertEquals("enable", request.header("X-DashScope-Async")); pending(task) }
            else { gets.incrementAndGet(); pending(task) }
        }
        val ctx = context("repeat-${UUID.randomUUID()}")
        val args = JSONObject().put("operation", "video_generate").put("request_id", "request-${UUID.randomUUID()}").put("prompt", "a tree moves")
        val first = CloudMediaTools.executeConfigured(ctx, args, config, transport)
        val second = CloudMediaTools.executeConfigured(ctx, args, config, transport)
        assertTrue(first.toString(), first.getBoolean("ok"))
        assertTrue(first.getBoolean("pending"))
        assertTrue(second.toString(), second.getBoolean("ok"))
        assertEquals(task, second.getString("task_id"))
        assertEquals(1, posts.get())
        assertEquals(2, gets.get())
        assertTrue(second.getBoolean("reused_request"))
    }

    @Test fun cancelledPollingStopsImmediatelyAndKeepsSubmittedTaskForRecovery() = runBlocking {
        val task = "task-${UUID.randomUUID()}"
        val gets = AtomicInteger()
        val transport = transport { request -> if (request.method == "GET") gets.incrementAndGet(); pending(task) }
        val ctx = context("cancel-${UUID.randomUUID()}")
        val args = JSONObject().put("operation", "video_generate").put("request_id", "request-${UUID.randomUUID()}")
            .put("prompt", "a tree moves").put("wait_seconds", 180)
        val result = async(Dispatchers.IO) { runCatching { CloudMediaTools.executeConfigured(ctx, args, config, transport) } }
        withTimeout(3_000) { while (gets.get() == 0) delay(10) }
        ctx.controller.cancel()
        assertTrue(withTimeout(2_000) { result.await() }.isFailure)
        assertEquals(task, CloudMediaTaskStore(context(ctx.scopeKey)).list().getJSONObject(0).getString("task_id"))
    }

    @Test fun savedResultsSurviveRunRestartAndDoNotQueryAgain() = runBlocking {
        val scope = "saved-${UUID.randomUUID()}"
        val task = "task-${UUID.randomUUID()}"
        val keyHash = java.security.MessageDigest.getInstance("SHA-256").digest("fixture-key".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val ctx = context(scope)
        val store = CloudMediaTaskStore(ctx)
        val record = store.create(task, "video", config.bailianBaseUrl(), keyHash, "wan2.7-t2v", pending(task))
            .put("task_status", "SUCCEEDED")
            .put("result_urls", JSONArray().put("https://dashscope-result-sh.oss-accelerate.aliyuncs.com/result.mp4"))
            .put("files", JSONArray().put(JSONObject().put("ok", true).put("path", "/verified/result.mp4")
                .put("content_uri", "content://fixture/result").put("mime_type", "video/mp4").put("bytes", 100)))
        store.save(record)
        val persisted = CloudMediaTaskStore(context(scope)).load(task)
        assertEquals("SUCCEEDED", persisted.getString("task_status"))
        assertEquals(1, persisted.getJSONArray("files").length())
        val never = transport { error("saved results must not call HTTP") }
        val result = CloudMediaTools.executeConfigured(context(scope), JSONObject().put("operation", "video_status").put("task_id", task), config, never)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("content://fixture/result", result.getString("content_uri"))
    }

    @Test fun atomicReplacementCommitsSecondWriteAndFailedWritePreservesCommittedValue() {
        val target = app.noBackupFilesDir.resolve("atomic-fixture-${UUID.randomUUID()}.json")
        fun commit(value: String) {
            val file = AtomicFile(target)
            val output = file.startWrite()
            output.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        }
        commit("first-committed")
        commit("second-committed")
        assertEquals("second-committed", AtomicFile(target).readFully().toString(Charsets.UTF_8))
        val file = AtomicFile(target)
        val incomplete = file.startWrite()
        incomplete.write("uncommitted-partial".toByteArray(Charsets.UTF_8))
        file.failWrite(incomplete)
        assertEquals("second-committed", AtomicFile(target).readFully().toString(Charsets.UTF_8))
    }

    @Test fun ambiguousSubmitCannotBeRepeatedWithTheSameRequestId() = runBlocking {
        val calls = AtomicInteger()
        val ctx = context("unknown-${UUID.randomUUID()}")
        val args = JSONObject().put("operation", "video_generate").put("request_id", "request-${UUID.randomUUID()}").put("prompt", "a tree moves")
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw java.io.IOException("fixture timeout") }.build()
        val first = CloudMediaTools.executeConfigured(ctx, args, config, CloudApiTransport(client))
        assertTrue(first.getBoolean("outcome_unknown"))
        val second = CloudMediaTools.executeConfigured(ctx, args, config, CloudApiTransport(client))
        assertEquals("request_outcome_unknown", second.getString("error"))
        assertFalse(second.getBoolean("retry_safe"))
        assertEquals(1, calls.get())
    }

    private fun context(scope: String, project: String? = null): CloudToolContext {
        val controller = AgentRunController()
        controller.bindExecutionIdentity(RunRequest(RunId("run-${UUID.randomUUID()}"), RunSource.CHAT, RunMode.CHAT, HostId("local"), projectId = project))
        return CloudToolContext(app, controller, { error("No file input expected") }, { _, _, _ -> error("No output expected") }, scope)
    }

    private fun pending(task: String) = JSONObject().put("output", JSONObject().put("task_id", task).put("task_status", "PENDING"))

    private fun transport(answer: (okhttp3.Request) -> JSONObject): CloudApiTransport = CloudApiTransport(OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(answer(chain.request()).toString().toResponseBody("application/json".toMediaType())).build() }).build())
}
