package io.github.mangi.eta.agent.packages

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class PackageDesktopWidget(val record: CapabilityPackage, val id: String, val title: String,
    val route: String, val renderRoute: String, val order: Int)

/** Launcher IDs are local to this installation. They never confer tools or survive a package revision. */
internal class PackageDesktopWidgetService(context: Context) {
    private val context = context.applicationContext
    private val ui = PackageUiService(this.context)
    private val file = AtomicFile(File(this.context.filesDir, "capability-packages/widget-bindings.json"))
    private val manager get() = AppWidgetManager.getInstance(context)
    fun available(packageId: String? = null): List<PackageDesktopWidget> {
        val modules = ui.modules(packageId)
        return modules.map { it.record }.distinctBy { it.id }.flatMap { record ->
            val rows = JSONArray(record.registrations)
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.getJSONObject(index).takeIf { it.optString("kind") == "DesktopWidget" } ?: return@mapNotNull null
                val route = row.optString("route"); val render = row.optString("render").ifBlank { route }
                if (modules.count { it.record.id == record.id && it.route == route } != 1 ||
                    modules.count { it.record.id == record.id && it.route == render } != 1) return@mapNotNull null
                val title = row.opt("title").let { value -> when (value) {
                    is String -> value
                    is JSONObject -> value.optString("zh").ifBlank { value.optString("en") }
                    else -> ""
                } }.ifBlank { record.title }.take(120)
                PackageDesktopWidget(record, row.getString("id"), title, route, render, row.optInt("order"))
            }
        }.sortedWith(compareBy<PackageDesktopWidget> { it.order }.thenBy { it.title })
    }
    fun resolve(id: Int): PackageDesktopWidget? = synchronized(LOCK) {
        val row = read().optJSONObject(id.toString()) ?: return@synchronized null
        available(row.getString("packageId")).singleOrNull { it.id == row.getString("widgetId") &&
            it.record.digest == row.getString("digest") && it.route == row.getString("route") && it.renderRoute == row.getString("renderRoute") }
    }
    fun module(widget: PackageDesktopWidget, render: Boolean): PackageUiModule = ui.modules(widget.record.id).single {
        it.record.digest == widget.record.digest && it.route == if (render) widget.renderRoute else widget.route
    }
    fun bind(id: Int, value: PackageDesktopWidget) = synchronized(LOCK) {
        require(isOwned(id)) { "桌面组件已失效，请重新添加" }
        check(available(value.record.id).any { it.id == value.id && it.record.digest == value.record.digest &&
            it.route == value.route && it.renderRoute == value.renderRoute }) { "插件组件已更新，请重新选择" }
        val rows = read()
        require(rows.has(id.toString()) || rows.length() < 32) { "桌面组件数量已达上限" }
        write(rows.put(id.toString(), JSONObject().put("packageId", value.record.id).put("digest", value.record.digest)
            .put("widgetId", value.id).put("route", value.route).put("renderRoute", value.renderRoute)))
        refresh(context)
    }
    fun unbind(ids: IntArray) = synchronized(LOCK) {
        val rows = read(); ids.forEach { rows.remove(it.toString()) }; write(rows)
    }
    fun removePackage(packageId: String) = synchronized(LOCK) {
        val rows = read()
        val bindings = rows.keys().asSequence().filter { id ->
            rows.optJSONObject(id)?.optString("packageId") == packageId
        }.toList()
        if (bindings.isEmpty()) return@synchronized
        bindings.forEach(rows::remove)
        write(rows)
        refresh(context)
    }
    fun remap(oldIds: IntArray, newIds: IntArray) = synchronized(LOCK) {
        require(oldIds.size == newIds.size)
        val rows = read(); val old = oldIds.map { rows.optJSONObject(it.toString()) }
        oldIds.forEach { rows.remove(it.toString()) }
        newIds.forEachIndexed { index, id -> old[index]?.let { rows.put(id.toString(), it) } }
        write(rows); refresh(context)
    }
    fun pin(value: PackageDesktopWidget): Boolean {
        if (!manager.isRequestPinAppWidgetSupported) return false
        check(available(value.record.id).any { it.id == value.id && it.record.digest == value.record.digest }) { "插件组件已变化" }
        val callback = Intent(context, PackageDesktopWidgetReceiver::class.java).setAction(ACTION_PINNED)
            .setData(Uri.parse("nexus-widget:pin/${UUID.randomUUID()}"))
            .putExtra("packageId", value.record.id).putExtra("widgetId", value.id).putExtra("digest", value.record.digest)
        val pending = PendingIntent.getBroadcast(context, 0, callback, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        return manager.requestPinAppWidget(component(context), null, pending)
    }
    fun isOwned(id: Int) = id != AppWidgetManager.INVALID_APPWIDGET_ID && manager.getAppWidgetInfo(id)?.provider == component(context)
    fun ids(): IntArray = manager.getAppWidgetIds(component(context))
    fun launch(id: Int): Intent = Intent(context, io.github.mangi.eta.ui.widget.PackageWidgetActivity::class.java)
        .setAction(ACTION_OPEN).setData(Uri.parse("nexus-widget:open/$id"))
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        return file.openRead().use { input -> val bytes = input.readNBytes(64 * 1024 + 1)
            require(bytes.size <= 64 * 1024) { "桌面组件配置过大" }; JSONObject(bytes.toString(Charsets.UTF_8)) }
    }
    private fun write(rows: JSONObject) {
        file.baseFile.parentFile!!.mkdirs(); val output = file.startWrite()
        try { output.write(rows.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    companion object {
        private val LOCK = Any()
        const val ACTION_OPEN = "io.github.zhh_lgtm.nexus.OPEN_PACKAGE_WIDGET"
        const val ACTION_PINNED = "io.github.zhh_lgtm.nexus.PACKAGE_WIDGET_PINNED"
        fun component(context: Context) = ComponentName(context, PackageDesktopWidgetReceiver::class.java)
        fun refresh(context: Context) {
            if (AppWidgetManager.getInstance(context).getAppWidgetIds(component(context)).isEmpty()) return
            WorkManager.getInstance(context).enqueueUniqueWork("package-desktop-widgets", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PackageDesktopWidgetWorker>().build())
        }
        fun initialize(context: Context, scope: CoroutineScope) {
            scope.launch { CapabilityPackageStore.changes.collectLatest { refresh(context) } }
        }
        fun validate(rows: JSONArray) {
            val modules = (0 until rows.length()).mapNotNull { PackageUiRegistration.describe(rows.getJSONObject(it)) }
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); if (row.optString("kind") != "DesktopWidget") continue
                require(row.optString("id").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) { "桌面组件编号无效" }
                val route = row.optString("route"); val render = row.optString("render").ifBlank { route }
                require(route.length in 1..512 && render.length in 1..512 && modules.count { it.route == route } == 1 &&
                    modules.count { it.route == render } == 1) { "桌面组件缺少唯一的页面和渲染路由" }
            }
        }
    }
}
