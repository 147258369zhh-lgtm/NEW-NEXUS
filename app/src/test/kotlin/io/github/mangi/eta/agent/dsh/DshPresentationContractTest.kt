package io.github.mangi.eta.agent.dsh

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.ui.screens.capability.DshPanelImages
import io.github.mangi.eta.ui.screens.capability.dshInlineText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshPresentationContractTest {
    @Test fun selectedOptionsPreserveJsonTypesAndStructuredEquality() {
        val one = JSONObject().put("a", 1).put("b", true)
        val reordered = JSONObject().put("b", true).put("a", 1)
        val selected = DshProtocol.optionValues(JSONArray().put(3).put("3").put(true).put(one))
        val result = JSONArray(selected.values.toList())
        assertEquals(3, result.getInt(0)); assertTrue(result.get(0) is Number)
        assertEquals("3", result.get(1)); assertEquals(true, result.get(2))
        assertTrue(selected.containsKey(DshProtocol.optionKey(reordered)))
        assertTrue(DshProtocol.supportedSurfaces.contains("turn.note"))
    }

    @Test fun limitedInlineFormattingCannotCreateClickableLinksOrHtml() {
        val text = dshInlineText("**Title** `value` [site](https://example.com) ![image](asset:x.png) <script>no</script>", Color.LightGray)
        assertEquals("Title value site image <script>no</script>", text.text)
        assertTrue(text.spanStyles.isNotEmpty())
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test fun imagesShareAPixelBudgetAndAreCachedOnlyWithinTheirPanelOwner() = runBlocking {
        val source = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output -> source.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        var reads = 0
        DshPanelImages(this) { reads++; bytes }.use { images ->
            val first = images.image("asset:first.png")!!
            assertEquals(512, first.width)
            assertSame(first, images.image("asset:first.png")); assertEquals(1, reads)
            for (index in 1..6) assertNotNull(images.image("asset:$index.png"))
            assertNull(images.image("asset:over.png")); assertNull(images.image("asset:still-over.png"))
            assertSame(first, images.image("asset:first.png"))
        }
        DshPanelImages(this) { reads++; bytes }.use { other -> assertNotNull(other.image("asset:first.png")) }
        assertEquals(10, reads)
    }
}
