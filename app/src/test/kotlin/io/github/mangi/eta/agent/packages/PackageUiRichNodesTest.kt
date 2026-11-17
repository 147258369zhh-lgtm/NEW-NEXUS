package io.github.mangi.eta.agent.packages

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageUiRichNodesTest {
    @Test fun packageImagesUseOnlyTheEnabledVersionAndRejectTraversalOversizeAndForeignUris() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = byteArrayOf(0x12, 0x34)
        val module = PackageUiServiceTest().install(assets = mapOf("images/preview.png" to bytes, "images/large.png" to ByteArray(1024 * 1024 + 1)))
        val service = PackageUiService(context)
        assertArrayEquals(bytes, service.image(module, "images/preview.png"))
        for (path in listOf("../preview.png", "file:///data/private.png", "https://example.com/image.png", "images/large.png", "main.js"))
            assertTrue(path, runCatching { service.image(module, path) }.isFailure)
        CapabilityPackageStore(context).configure(module.record.id, enabled = false)
        assertTrue(runCatching { service.image(module, "images/preview.png") }.isFailure)
    }
    @Test fun canvasCommandValidationAcceptsThePublishedShapesAndRefusesUnknownCommandsAndUnboundedPaths() {
        val path = JSONArray().put(JSONObject().put("type", "moveTo").put("x", 0).put("y", 0))
            .put(JSONObject().put("type", "lineTo").put("x", 20).put("y", 30))
        val valid = JSONObject().put("commands", JSONArray().put(JSONObject().put("type", "drawPath").put("path", path)))
        PackageCanvasContract.validate(valid)
        for (invalid in listOf(JSONObject().put("commands", JSONArray().put(JSONObject().put("type", "evaluateScript"))),
            JSONObject().put("commands", JSONArray((1..129).map { JSONObject().put("type", "line") })),
            JSONObject().put("commands", JSONArray().put(JSONObject().put("type", "text").put("text", "x".repeat(8001))))))
            assertTrue(runCatching { PackageCanvasContract.validate(invalid) }.isFailure)
    }
}
