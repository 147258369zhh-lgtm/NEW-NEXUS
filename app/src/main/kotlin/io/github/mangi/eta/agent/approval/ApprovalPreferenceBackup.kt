package io.github.mangi.eta.agent.approval

import android.content.Context
import io.github.mangi.eta.agent.control.SettingsControlSnapshots
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import kotlinx.serialization.Serializable
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Portable evidence only. There is deliberately no Run policy, grant or executable outbox. */
@Serializable
internal data class ApprovalPreferenceBackup(
    val version: Int = 1,
    val exportedAt: Long,
    val validityDays: Int = 7,
    val records: List<ApprovalPreferenceEvidence> = emptyList(),
) {
    fun validate() {
        require(version == 1 && exportedAt >= 0 && validityDays in setOf(1, 7, 30) && records.size <= 200) { "审批证据备份格式无效" }
        require(records.map { it.key }.distinct().size == records.size) { "审批证据范围重复" }
        records.forEach { it.validate() }
    }
}

@Serializable
internal data class ApprovalPreferenceEvidence(
    val key: String, val tool: String, val project: String, val effect: String, val scopeJson: String,
    val target: String = "", val approvals: Int = 0, val historicalApprovals: Int = 0,
    val refusals: Int = 0, val expiresAt: Long, val revoked: Boolean = false, val lastEvidenceAt: Long,
) {
    fun validate() {
        val digest = Regex("[a-f0-9]{64}")
        require(digest.matches(key) && tool.matches(Regex("[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}")) &&
            project.length <= 1_000 && target.length <= 1_000 && scopeJson.length <= 8_192 &&
            approvals in 0..1_000_000 && historicalApprovals in 0..1_000_000 && refusals in 0..1_000_000 &&
            expiresAt >= 0 && lastEvidenceAt >= 0 && EffectClass.entries.any { it.name == effect }) { "审批证据范围无效" }
        val scope = JSONObject(scopeJson)
        require(scope.optString("tool") == tool && scope.optString("project") == project && scope.optString("effect") == effect &&
            scope.optInt("effect_version") == 1 && digest.matches(scope.optString("schema")) &&
            digest.matches(scope.optString("arguments")) && scope.optString("host").length in 1..256 &&
            scope.optString("target") == target && SettingsControlSnapshots.hash(scope) == key) { "审批证据身份校验失败" }
    }
    fun json(pending: Boolean) = JSONObject().put("key", key).put("tool", tool).put("project", project)
        .put("effect", effect).put("scope", JSONObject(scopeJson)).put("target", target)
        .put("approvals", if (pending) 0 else approvals)
        .put("historical_approvals", if (pending) maxOf(historicalApprovals, approvals) else historicalApprovals)
        .put("refusals", refusals).put("expires_at", expiresAt).put("revoked", revoked)
        .put("last_evidence_at", lastEvidenceAt).put("restored_pending", pending)

    companion object {
        fun fromJson(row: JSONObject) = ApprovalPreferenceEvidence(row.getString("key"), row.getString("tool"),
            row.getString("project"), row.getString("effect"), row.getJSONObject("scope").toString(),
            row.optString("target"), row.optInt("approvals"), row.optInt("historical_approvals"),
            row.optInt("refusals"), row.getLong("expires_at"), row.optBoolean("revoked"), row.optLong("last_evidence_at"))
    }
}

internal object ApprovalPreferenceBackupTransfer {
    /** Read only negative local evidence without opening SharedPreferences before the full swap. */
    fun localBlockersBeforeFullRestore(context: Context): ApprovalPreferenceBackup {
        val primary = File(context.applicationContext.dataDir, "shared_prefs/nexa_execution_approval_v1.xml")
        val file = File(primary.path + ".bak").takeIf { it.isFile } ?: primary
        val records = if (!file.isFile) emptyList() else readPreferenceRows(file)
            .filter { it.revoked || it.refusals > 0 }
        return ApprovalPreferenceBackup(exportedAt = System.currentTimeMillis(), records = records).also { it.validate() }
    }

    private fun readPreferenceRows(file: File): List<ApprovalPreferenceEvidence> = file.inputStream().buffered().use { input ->
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string" && parser.getAttributeValue(null, "name") == "preferences") {
                val raw = parser.nextText()
                require(raw.length <= 4 * 1024 * 1024) { "审批证据超过恢复限额" }
                val rows = org.json.JSONArray(raw)
                require(rows.length() <= 200)
                return@use (0 until rows.length()).map { ApprovalPreferenceEvidence.fromJson(rows.getJSONObject(it)).also { row -> row.validate() } }
            }
        }
        emptyList()
    }
}
