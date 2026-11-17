package io.github.mangi.eta.agent.control

import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaAppControlConfirmationQueueTest {
    @Test
    fun parallelDangerousOperationsAreQueuedInsteadOfFailing(): Unit = runBlocking {
        val info = EtaControlCapabilityInfo(
            id = "fixture.danger", title = "危险操作", category = "fixture", summary = "测试审批队列",
            danger = EtaControlDanger.CONFIRM, parameters = JSONObject(), canRead = false,
        )
        val firstController = AgentRunController()
        val secondController = AgentRunController()
        val first = async(Dispatchers.Default) {
            EtaAppControlConfirmation.awaitApproval(
                info, JSONObject().put("effect", "HIGH_RISK").put("index", 1), controller = firstController,
            )
        }
        val firstRequest = withTimeout(2_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
        val second = async(Dispatchers.Default) {
            EtaAppControlConfirmation.awaitApproval(
                info, JSONObject().put("effect", "HIGH_RISK").put("index", 2), controller = secondController,
            )
        }
        withTimeout(2_000) {
            EtaAppControlConfirmation.pendingCount.first { it == 2 }
        }
        assertEquals(firstRequest.id, EtaAppControlConfirmation.pending.value?.id)
        assertTrue(EtaAppControlConfirmation.approve(firstRequest.id))
        assertEquals(true, first.await())

        val secondRequest = withTimeout(2_000) {
            EtaAppControlConfirmation.pending.filterNotNull().first { it.id != firstRequest.id }
        }
        assertTrue(EtaAppControlConfirmation.cancel(secondRequest.id))
        assertEquals(false, second.await())
        withTimeout(2_000) { EtaAppControlConfirmation.pendingCount.first { it == 0 } }
        @Suppress("UNUSED_EXPRESSION")
        Unit
    }
}
