package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖下载落盘决策（200/206/416、Content-Range、大小上限）——这些分支决定了断点续传是否会写出损坏文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalModelDownloadPlanTest {
    @Test
    fun contentRangeParsesStandardAndUnknownTotal() {
        assertEquals(HttpContentRange(100, 199, 1_000), parseContentRange("bytes 100-199/1000"))
        assertEquals(HttpContentRange(0, 9, null), parseContentRange("bytes 0-9/*"))
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("items 0-1/2"))
        assertNull(parseContentRange("bytes 9-1/100"))
    }

    @Test
    fun fullResponseRestartsFromZero() {
        val plan = planDownloadResponse(
            statusCode = 200,
            requestedOffset = 400,
            contentRange = null,
            contentLength = 1_000,
            expectedSize = 1_000,
        )
        assertEquals(DownloadResponsePlan.Restart(1_000, "服务器未提供可续传区间，已从头下载"), plan)
    }

    @Test
    fun partialResponseAppendsAtRequestedOffset() {
        val plan = planDownloadResponse(
            statusCode = 206,
            requestedOffset = 400,
            contentRange = HttpContentRange(400, 999, 1_000),
            contentLength = 600,
            expectedSize = 1_000,
        )
        assertEquals(DownloadResponsePlan.Append(offset = 400, totalBytes = 1_000), plan)
    }

    @Test
    fun partialResponseWithWrongOffsetDiscardsPartial() {
        val plan = planDownloadResponse(
            statusCode = 206,
            requestedOffset = 400,
            contentRange = HttpContentRange(0, 599, 1_000),
            contentLength = 600,
            expectedSize = 1_000,
        )
        val failure = plan as DownloadResponsePlan.Fail
        assertTrue(failure.retryable)
        assertTrue(failure.discardPartial)
    }

    @Test
    fun partialResponseWithoutContentRangeDiscardsPartial() {
        val plan = planDownloadResponse(
            statusCode = 206,
            requestedOffset = 400,
            contentRange = null,
            contentLength = 600,
            expectedSize = null,
        )
        val failure = plan as DownloadResponsePlan.Fail
        assertTrue(failure.retryable)
        assertTrue(failure.discardPartial)
    }

    @Test
    fun rangeNotSatisfiableRestartsInsteadOfFailing() {
        val plan = planDownloadResponse(
            statusCode = 416,
            requestedOffset = 1_000,
            contentRange = null,
            contentLength = null,
            expectedSize = 1_000,
        )
        assertTrue(plan is DownloadResponsePlan.Restart)
    }

    @Test
    fun presetSizeMismatchIsTerminalAndDiscardsPartial() {
        val plan = planDownloadResponse(
            statusCode = 200,
            requestedOffset = 0,
            contentRange = null,
            contentLength = 999,
            expectedSize = 1_000,
        )
        val failure = plan as DownloadResponsePlan.Fail
        assertFalse(failure.retryable)
        assertTrue(failure.discardPartial)
    }

    @Test
    fun serverErrorsRetryButClientErrorsDoNot() {
        val transient = planDownloadResponse(503, 0, null, null, null) as DownloadResponsePlan.Fail
        assertTrue(transient.retryable)
        val missing = planDownloadResponse(404, 0, null, null, null) as DownloadResponsePlan.Fail
        assertFalse(missing.retryable)
    }

    @Test
    fun oversizedArtifactIsRejected() {
        val plan = planDownloadResponse(
            statusCode = 200,
            requestedOffset = 0,
            contentRange = null,
            contentLength = MAX_LOCAL_MODEL_BYTES + 1,
            expectedSize = null,
        )
        assertFalse((plan as DownloadResponsePlan.Fail).retryable)
    }

    @Test
    fun safeFileNameKeepsExtensionWhenTruncated() {
        val long = "a".repeat(400) + ".gguf"
        val name = LocalModelFiles.safeFileName(long)
        assertTrue(name.length <= 180)
        assertTrue(name.endsWith(".gguf"))
        assertEquals("model.gguf", LocalModelFiles.safeFileName("../../model.gguf"))
    }

    @Test
    fun downloadUrlRejectsPlainHttpOutsidePrivateNetwork() {
        assertEquals(
            "https://example.com/model.gguf",
            LocalModelRepository.validateDownloadUrl(" https://example.com/model.gguf "),
        )
        assertTrue(
            runCatching { LocalModelRepository.validateDownloadUrl("http://example.com/model.gguf") }.isFailure,
        )
        assertEquals(
            "http://127.0.0.1:8080/model.gguf",
            LocalModelRepository.validateDownloadUrl("http://127.0.0.1:8080/model.gguf"),
        )
        assertTrue(
            runCatching { LocalModelRepository.validateDownloadUrl("https://example.com/page.html") }.isFailure,
        )
    }

    @Test
    fun htmlPayloadIsDetectedAsText() {
        assertTrue(looksLikeTextPayload("<!DOCTYPE html><html>".toByteArray()))
        assertFalse(looksLikeTextPayload("GGUF".toByteArray()))
    }
}
