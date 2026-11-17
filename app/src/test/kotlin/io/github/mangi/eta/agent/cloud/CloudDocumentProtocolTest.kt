package io.github.mangi.eta.agent.cloud

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CloudBailianConfig
import io.github.mangi.eta.data.model.CloudToolsCredentials
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.model.CloudToolsSettings
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudDocumentProtocolTest {
    @Test fun providerErrorsInsideHttpSuccessAreNotTreatedAsResultsOrEchoed() {
        val fixtures = listOf(
            """{"error":{"message":"private-key-echo"}}""",
            """{"error":"invalid_client","error_description":"private-key-echo"}""",
            """{"error_code":110,"error_msg":"private-key-echo"}""",
            """{"code":"InvalidApiKey","message":"private-key-echo"}""",
            """{"Response":{"Error":{"Code":"AuthFailure","Message":"private-key-echo"}}}""",
        )
        fixtures.forEach { fixture ->
            val failure = assertThrows(CloudApiException::class.java) { CloudApiTransport.parseJson(fixture.toByteArray()) }
            assertEquals("provider_rejected", failure.code)
            assertFalse(failure.toJson().toString().contains("private-key-echo"))
        }
        val pages = CloudApiTransport.parseJson("\n\n{\"pages\":[{\"finish_reason\":\"length\"}]}".toByteArray())
        assertEquals("length", pages.getJSONArray("pages").getJSONObject(0).getString("finish_reason"))
    }

    @Test fun tc3ModerationSignatureMatchesIndependentHmacFixture() {
        val headers = CloudDocumentTools.tencentHeaders("tms.tencentcloudapi.com", "{}", "test-id", "test-secret",
            "ap-guangzhou", 1551113065)
        assertEquals("TC3-HMAC-SHA256 Credential=test-id/2019-02-25/tms/tc3_request, SignedHeaders=content-type;host, Signature=789f90abc917228991503efcf50007a75734a23477937bf885c5e0ab39d46fde",
            headers["Authorization"])
        assertEquals("TextModeration", headers["X-TC-Action"])
        assertEquals("2020-12-29", headers["X-TC-Version"])
        assertFalse(headers.toString().contains("test-secret"))
    }

    @Test fun cancelledControllerNeverSubmitsAndHttpFailureNeverRetries() {
        val calls = AtomicInteger()
        val transport = CloudApiTransport(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("fixture")
                .body("private-key-echo".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val stopped = AgentRunController().apply { cancel() }
        assertThrows(AgentRunCancelledException::class.java) {
            transport.json(stopped, "POST", "https://fixture.example/tools", JSONObject())
        }
        assertEquals(0, calls.get())
        val failure = assertThrows(CloudApiException::class.java) {
            transport.json(AgentRunController(), "POST", "https://fixture.example/tools", JSONObject())
        }
        assertEquals(1, calls.get())
        assertEquals(503, failure.httpStatus)
        assertTrue(failure.outcomeUnknown)
        assertFalse(failure.toJson().toString().contains("private-key-echo"))
    }

    @Test fun multipartPurposePrecedesStreamingFileAndHonorsByteLimit() {
        var captured = ""
        val transport = CloudApiTransport(OkHttpClient.Builder().addInterceptor { chain ->
            captured = Buffer().apply { chain.request().body!!.writeTo(this) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"id\":\"file-fixture\"}".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val file = CloudInputFile("scan.pdf", "application/pdf", 4) { "test".byteInputStream() }
        transport.multipart(AgentRunController(), "https://fixture.example/files", linkedMapOf("purpose" to "text_recognition"), file)
        assertTrue(captured.indexOf("name=\"purpose\"") < captured.indexOf("filename=\"scan.pdf\""))
        val unknownSize = file.copy(sizeBytes = null, open = { "12345".byteInputStream() })
        val failure = assertThrows(CloudApiException::class.java) {
            transport.multipart(AgentRunController(), "https://fixture.example/files", mapOf("purpose" to "text_recognition"),
                unknownSize, maxFileBytes = 4)
        }
        assertEquals("file_too_large", failure.code)
        assertTrue(failure.outcomeUnknown)
    }

    @Test fun credentialsAreRedactedAndWorkspaceRoutesAndFingerprintAreStable() {
        val credentials = CloudToolsCredentials(deepSeekApiKey = "private-key")
        val config = CloudToolsRuntimeConfig(CloudToolsSettings(bailian = CloudBailianConfig(workspaceId = "workspace-1")), credentials)
        assertEquals("https://workspace-1.cn-beijing.maas.aliyuncs.com", config.bailianBaseUrl())
        assertFalse(credentials.toString().contains("private-key"))
        assertFalse(config.toString().contains("private-key"))
        assertThrows(IllegalStateException::class.java) { config.route("ocr") }
        assertEquals(CloudRequestLedger.fingerprint(JSONObject("{\"a\":1,\"b\":[2,3]}")),
            CloudRequestLedger.fingerprint(JSONObject("{\"b\":[2,3],\"a\":1}")))
        assertNotEquals(CloudRequestLedger.fingerprint(JSONObject("{\"text\":\"a\"}")),
            CloudRequestLedger.fingerprint(JSONObject("{\"text\":\"b\"}")))
    }
}
