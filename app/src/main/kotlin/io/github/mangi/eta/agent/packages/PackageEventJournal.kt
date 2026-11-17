package io.github.mangi.eta.agent.packages

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.mangi.eta.data.model.ConversationSubject
import org.json.JSONObject
import java.io.File

internal enum class PackageEventStatus(val label: String) {
    PENDING("待执行"), RUNNING("执行中"), SUCCEEDED("已完成"), FAILED("失败"), REVOKED("已撤销"), UNKNOWN("结果待核对")
}
internal data class PackageEventReceipt(val runId: String, val packageId: String, val name: String,
    val status: PackageEventStatus, val createdAt: Long, val updatedAt: Long)
internal data class PackageQueuedEvent(val receipt: PackageEventReceipt, val digest: String, val tool: PackageTool,
    val arguments: JSONObject, val subject: ConversationSubject?)

/** A bounded inbox for plugin notifications, not a second execution ledger. Real results stay in Runtime. */
internal class PackageEventJournal(context: Context) : SQLiteOpenHelper(context.applicationContext,
    File(context.applicationContext.filesDir, "capability-packages/events.db").also { it.parentFile!!.mkdirs() }.absolutePath, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE deliveries (
            run_id TEXT PRIMARY KEY NOT NULL, package_id TEXT NOT NULL, digest TEXT NOT NULL, callback_json TEXT NOT NULL,
            event_name TEXT NOT NULL, arguments_json TEXT, subject_kind TEXT, subject_id TEXT, state TEXT NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX delivery_state ON deliveries(state,created_at,run_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported plugin event journal version")
    fun enqueue(values: List<PackageEventDelivery>): Boolean = transaction { db ->
        if (values.isEmpty()) return@transaction true
        val payloads = values.map { it.arguments.toString() }
        val existing = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(arguments_json AS BLOB))),0) FROM deliveries WHERE arguments_json IS NOT NULL", null)
            .use { it.moveToFirst(); it.getLong(0) to it.getLong(1) }
        if (existing.first + values.size > 128 || existing.second + payloads.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > 4 * 1024 * 1024)
            return@transaction false
        val now = System.currentTimeMillis()
        values.forEachIndexed { index, value ->
            db.insertOrThrow("deliveries", null, ContentValues().apply {
                put("run_id", value.runId); put("package_id", value.record.id); put("digest", value.record.digest)
                put("callback_json", encodeTool(value.tool).toString()); put("event_name", value.arguments.getString("eventName"))
                put("arguments_json", payloads[index]); put("subject_kind", value.subject?.kind?.value); put("subject_id", value.subject?.id)
                put("state", PackageEventStatus.PENDING.name); put("created_at", now); put("updated_at", now)
            })
        }
        true
    }
    fun unsettled(): List<PackageQueuedEvent> = readableDatabase.query("deliveries", null, "state IN ('PENDING','RUNNING','UNKNOWN') AND arguments_json IS NOT NULL",
        null, null, null, "created_at,run_id").use { rows -> buildList { while (rows.moveToNext()) {
            val tool = JSONObject(rows.text("callback_json"))
            val subjectId = rows.textOrNull("subject_id")
            val subject = if (subjectId == null) null else when (rows.text("subject_kind")) {
                "chat" -> ConversationSubject.chat(subjectId)
                "meeting" -> ConversationSubject.meeting(subjectId)
                else -> error("PACKAGE_EVENT_SUBJECT_INVALID")
            }
            add(PackageQueuedEvent(receipt(rows), rows.text("digest"), PackageTool(tool.getString("name"), tool.getString("description"),
                tool.getString("entry"), tool.getString("schema"), tool.getString("callback")), JSONObject(rows.text("arguments_json")), subject))
        } } }
    fun claim(runId: String): Boolean = writableDatabase.update("deliveries", ContentValues().apply {
        put("state", PackageEventStatus.RUNNING.name); put("updated_at", System.currentTimeMillis())
    }, "run_id=? AND state=?", arrayOf(runId, PackageEventStatus.PENDING.name)) == 1
    fun finish(runId: String, state: PackageEventStatus) {
        require(state !in setOf(PackageEventStatus.PENDING, PackageEventStatus.RUNNING))
        writableDatabase.update("deliveries", ContentValues().apply {
            put("state", state.name); put("updated_at", System.currentTimeMillis())
            if (state != PackageEventStatus.UNKNOWN) { putNull("arguments_json"); putNull("subject_id"); putNull("subject_kind") }
        }, "run_id=? AND state IN ('PENDING','RUNNING','UNKNOWN')", arrayOf(runId))
        writableDatabase.execSQL("""DELETE FROM deliveries WHERE arguments_json IS NULL AND run_id NOT IN
            (SELECT run_id FROM deliveries WHERE arguments_json IS NULL ORDER BY updated_at DESC,run_id DESC LIMIT 256)""")
    }
    fun history(packageId: String): List<PackageEventReceipt> = readableDatabase.query("deliveries", null, "package_id=?", arrayOf(packageId),
        null, null, "created_at DESC,run_id DESC", "32").use { rows -> buildList { while (rows.moveToNext()) add(receipt(rows)) } }
    fun clearHistory(packageId: String) { writableDatabase.delete("deliveries", "package_id=? AND arguments_json IS NULL", arrayOf(packageId)) }
    fun revoke(packageId: String) { writableDatabase.update("deliveries", ContentValues().apply {
        put("state", PackageEventStatus.REVOKED.name); putNull("arguments_json"); putNull("subject_kind"); putNull("subject_id")
        put("updated_at", System.currentTimeMillis())
    }, "package_id=? AND arguments_json IS NOT NULL", arrayOf(packageId)) }
    fun archiveUnknown(packageId: String, runId: String): Boolean = writableDatabase.update("deliveries", ContentValues().apply {
        putNull("arguments_json"); putNull("subject_id"); putNull("subject_kind"); put("updated_at", System.currentTimeMillis())
    }, "run_id=? AND package_id=? AND state=?", arrayOf(runId, packageId, PackageEventStatus.UNKNOWN.name)) == 1
    private fun receipt(row: Cursor) = PackageEventReceipt(row.text("run_id"), row.text("package_id"), row.text("event_name"),
        PackageEventStatus.valueOf(row.text("state")), row.getLong(row.getColumnIndexOrThrow("created_at")), row.getLong(row.getColumnIndexOrThrow("updated_at")))
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.textOrNull(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun encodeTool(value: PackageTool) = JSONObject().put("name", value.name).put("description", value.description)
        .put("entry", value.entry).put("schema", value.inputSchema).put("callback", requireNotNull(value.callbackKey))
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
}
