package io.github.mangi.eta.agent.runtime

import io.github.zhh_lgtm.nexus.kernel.api.RunId
import java.security.MessageDigest

internal object NexusExecutionIds {
    fun item(runId: RunId, key: String): String = "item-" + MessageDigest.getInstance("SHA-256")
        .digest("${runId.value}:$key".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun tool(legacyRunId: String, round: Int, callId: String, hosted: Boolean = false): String =
        item(NexusRunMapper.canonicalRunId(legacyRunId), "${if (hosted) "hosted" else "tool"}:$round:$callId")
}
