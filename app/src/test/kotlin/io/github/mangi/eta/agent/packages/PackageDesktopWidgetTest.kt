package io.github.mangi.eta.agent.packages

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.McpSecretStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [WidgetAtomicFileShadow::class])
class PackageDesktopWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun install(): PackageDesktopWidget {
        val module = PackageUiServiceTest().install()
        val rows = JSONArray(module.record.registrations).put(JSONObject().put("kind", "UiRoute").put("id", "summary").put("route", "summary")
            .put("screen", JSONObject().put("nexus_callback", "UiRoute/summary/screen")))
            .put(JSONObject().put("kind", "DesktopWidget").put("id", "home").put("route", module.route).put("render", "summary")
                .put("title", JSONObject().put("zh", "今日摘要")))
        CapabilityPackageStore(context).saveRegistration(module.record, rows)
        return PackageDesktopWidgetService(context).available().single()
    }
    @Test fun inlineScreenAndWidgetShareRegisteredRoutesAndCannotResolveDisabledOrMissingCode() {
        val widget = install()
        val module = PackageDesktopWidgetService(context).module(widget, render = true)
        assertEquals("main.js", module.tool.entry)
        assertEquals("UiRoute/summary/screen", module.screenCallback)
        val store = CapabilityPackageStore(context)
        val invalid = JSONArray(widget.record.registrations)
        invalid.getJSONObject(2).put("render", "foreign:another-package")
        assertTrue(runCatching { store.saveRegistration(widget.record, invalid) }.isFailure)
        assertEquals(1, PackageDesktopWidgetService(context).available().size)
        store.configure(widget.record.id, enabled = false)
        assertTrue(PackageDesktopWidgetService(context).available().isEmpty())
    }
    @Test fun bindingsAreLauncherOwnedVersionBoundRemappableAndRemovable() {
        val widget = install()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val manager = AppWidgetManager.getInstance(context)
        val info = AppWidgetProviderInfo().apply { provider = PackageDesktopWidgetService.component(context); initialLayout = R.layout.package_widget_root }
        shadowOf(manager).addInstalledProvider(info)
        shadowOf(manager).setAllowedToBindAppWidgets(true)
        assertTrue(manager.bindAppWidgetIdIfAllowed(41, info.provider))
        val service = PackageDesktopWidgetService(context)
        assertTrue(runCatching { service.bind(999, widget) }.isFailure)
        service.bind(41, widget)
        assertEquals(widget.id, service.resolve(41)?.id)
        service.remap(intArrayOf(41), intArrayOf(42))
        val bindings = File(context.filesDir, "capability-packages/widget-bindings.json").readText()
        assertFalse(bindings, JSONObject(bindings).has("41"))
        assertNull(service.resolve(41)); assertEquals(widget.id, service.resolve(42)?.id)
        CapabilityPackageStore(context).configure(widget.record.id, enabled = false)
        assertNull(service.resolve(42))
        service.unbind(intArrayOf(42))
        assertNull(service.resolve(42))
    }
    @Test fun uninstallRemovesWidgetBindingsPackageStateEnvironmentAndVersionFiles() = runBlocking {
        val widget = install()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        val manager = AppWidgetManager.getInstance(context)
        val info = AppWidgetProviderInfo().apply { provider = PackageDesktopWidgetService.component(context); initialLayout = R.layout.package_widget_root }
        shadowOf(manager).addInstalledProvider(info)
        shadowOf(manager).setAllowedToBindAppWidgets(true)
        assertTrue(manager.bindAppWidgetIdIfAllowed(51, info.provider))
        val service = PackageDesktopWidgetService(context)
        service.bind(51, widget)
        val environment = PackageEnvironmentStore(context,
            McpSecretStore(context) { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") })
        environment.put(widget.record.id, "TOKEN", "widget-secret")
        PackageStateStore(context).invoke(widget.record.id, JSONObject().put("action", "put").put("key", "count")
            .put("expectedRevision", 0).put("value", 1), writable = true)
        val version = File(context.filesDir, "capability-packages/versions/${widget.record.id}")
        assertTrue(version.isDirectory)

        CapabilityPackageStore(context, environment).delete(widget.record.id, widget.record.digest)

        assertNull(service.resolve(51))
        val bindings = JSONObject(File(context.filesDir, "capability-packages/widget-bindings.json").readText())
        assertFalse(bindings.has("51"))
        assertFalse(File(version.path).exists())
        assertNull(environment.value(widget.record.id, "TOKEN"))
        assertTrue(PackageStateStore(context).invoke(widget.record.id,
            JSONObject().put("action", "get").put("key", "count").put("expectedRevision", 0), writable = false).isNull("value"))
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeLauncherViewsRenderActualTextProgressAndOnlyOpenTheAssociatedPage() {
        val raw = PackageUiServiceTest().document()
        raw.getJSONObject("tree").getJSONArray("children").put(JSONObject().put("__composeNode", true).put("type", "LinearProgressIndicator")
            .put("props", JSONObject().put("progress", 0.6)).put("children", JSONArray()))
        val root = PackageWidgetRemoteViews(context, 41).build("摘要", PackageUiDocument.parse(raw)).apply(context, FrameLayout(context))
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val views = descendants(root)
        assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "次数 0" })
        val progress = views.filterIsInstance<ProgressBar>().single()
        assertEquals(60, progress.progress); assertFalse(progress.isIndeterminate)
        views.filterIsInstance<TextView>().single { it.text.toString() == "增加" }.performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(PackageDesktopWidgetService.ACTION_OPEN, intent.action)
        assertEquals(41, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        val density = context.resources.displayMetrics.density
        val width = (300 * density).toInt(); val height = (220 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
        assertTrue("Widget background was blank", bitmap.getPixel(width / 2, height / 2) != 0)
        val output = File("../build/reports/nexus-delivery/20261111-r5-desktop-widget/widget-native.png")
        output.parentFile.mkdirs(); output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}

// Windows File.renameTo cannot replace a file; emulate Android's replace semantics in this host test.
@Implements(android.util.AtomicFile::class)
class WidgetAtomicFileShadow {
    companion object {
        @JvmStatic @Implementation
        fun rename(source: File, destination: File) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
