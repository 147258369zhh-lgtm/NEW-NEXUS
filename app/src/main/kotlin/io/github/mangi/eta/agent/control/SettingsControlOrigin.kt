package io.github.mangi.eta.agent.control

import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Trusted caller metadata, supplied by application code rather than tool parameters. */
internal class SettingsControlOrigin(
    val actor: SettingsActorKind,
    val sourceAgent: String,
    val sourceRunId: String? = null,
    val reason: String = "settings_change",
    /** Id of the settings grant that authorised this change, if any. */
    val grantId: String? = null,
    /** A one-use grant was atomically consumed before entering this mutation. */
    val singleUseClaimed: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SettingsControlOrigin>
}

internal object SettingsControlSnapshots {
    fun equal(left: JSONObject, right: JSONObject): Boolean = canonical(left) == canonical(right)
    fun hash(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.opt(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Number -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.toString()
        else -> value.toString()
    }
}
