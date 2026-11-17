package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.agent.runtime.*
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusModelBudgetLedgerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var store = NexusExecutionStore(context)
    @After fun close() { store.close() }
    private fun start(id: String = "parent", parent: RunId? = null, budget: RunBudget = RunBudget(hardRounds = 4)): RunId {
        val request = RunRequest(RunId(id), if (parent == null) RunSource.CHAT else RunSource.SUBAGENT,
            RunMode.CHAT, HostId("native-android"), parentRunId = parent, budget = budget)
        NexusExecutionRecorder(store, request).start()
        return request.runId
    }
    private fun model(id: String, owner: RunId, currency: String? = "USD") = ModelBudgetRequest(id, owner, "provider", "model", "CHAT",
        currency, currency?.let { "revision-1" }, "1", "2", "0.1", "0.2", currency?.let { 40 })
    private fun settle(id: String, run: RunId, cost: Long? = 10, complete: Boolean = true) =
        store.settleModelRequest(run, ModelBudgetSettlement(id, cost, complete, true, 10, 0, 0, 0), 10)

    @Test fun parentAndParallelChildrenConsumeOneDurableRequestAllowance() {
        val parent = start(budget = RunBudget(hardRounds = 3))
        val one = start("child-one", parent)
        val two = start("child-two", parent)
        val outcomes = Collections.synchronizedList(mutableListOf<String>())
        val workers = (1..8).map { index -> thread {
            try { store.reserveModelRequest(model("request-$index", if (index % 2 == 0) one else two), 5); outcomes += "reserved" }
            catch (denied: ModelBudgetDenied) { outcomes += denied.code }
        } }
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertEquals(3, outcomes.count { it == "reserved" })
        assertEquals(5, outcomes.count { it == "RUN_TOTAL_MODEL_REQUESTS_EXCEEDED" })
        assertEquals(3L, store.modelBudgetTotals(parent).requests)
        store.close(); store = NexusExecutionStore(context)
        assertEquals(3L, store.modelBudgetTotals(two).openRequests)
        assertEquals(3, store.modelBudgetRequests(one).size + store.modelBudgetRequests(two).size)
    }

    @Test fun requestAndSettlementRedeliveryAreIdempotentAndOwnerIsChecked() {
        val parent = start(); val other = start("unrelated")
        val request = model("request-1", parent)
        val reserved = store.reserveModelRequest(request, 4)
        assertEquals(reserved, store.reserveModelRequest(request, 99))
        assertThrows(IllegalArgumentException::class.java) { store.reserveModelRequest(request.copy(modelId = "changed"), 5) }
        assertThrows(IllegalArgumentException::class.java) { settle("request-1", other) }
        val result = settle("request-1", parent)
        assertEquals(result, settle("request-1", parent))
        assertThrows(IllegalArgumentException::class.java) { settle("request-1", parent, 11) }
        assertEquals(1L, store.modelBudgetTotals(parent).requests)
        assertEquals(10L, store.modelBudgetTotals(parent).knownCostMicros)
        assertEquals(1, store.items(parent).size)
        assertEquals(ItemKind.MODEL_REQUEST, store.items(parent).single().kind)
        assertEquals(RunState.SUCCEEDED, store.items(parent).single().state)
    }

    @Test fun costBudgetRejectsMissingPricesCurrencyMismatchAndUnknownCharges() {
        val parent = start(budget = RunBudget(hardRounds = 8, costLimitMicros = 100, costCurrency = "CNY"))
        assertEquals("MODEL_COST_PRICE_REQUIRED", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("missing", parent, null), 5)
        }.code)
        assertEquals("MODEL_COST_CURRENCY_MISMATCH", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("usd", parent), 5)
        }.code)
        store.reserveModelRequest(model("first", parent, "CNY"), 5)
        assertEquals("MODEL_COST_PENDING", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("parallel", parent, "CNY"), 6)
        }.code)
        settle("first", parent, null, false)
        assertEquals("MODEL_COST_UNVERIFIED", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("after-unknown", parent, "CNY"), 11)
        }.code)
        assertEquals(1L, store.modelBudgetTotals(parent).unknownCharges)
    }

    @Test fun exactlySpentCostCeilingPreventsNextChildOrParentRequest() {
        val parent = start(budget = RunBudget(hardRounds = 10, costLimitMicros = 100))
        val child = start("child", parent, RunBudget(hardRounds = 5, costLimitMicros = 100))
        store.reserveModelRequest(model("first", parent), 4); settle("first", parent, 40)
        store.reserveModelRequest(model("second", child).copy(reservedCostMicros = 60), 5); settle("second", child, 60)
        assertEquals(100L, store.modelBudgetTotals(child).knownCostMicros)
        assertEquals("RUN_COST_BUDGET_EXCEEDED", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("third", parent), 11)
        }.code)
    }

    @Test fun failedAtomicReservationLeavesNoCountOrItemAndSettledUsageSurvivesReopen() {
        val parent = start()
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_request BEFORE INSERT ON execution_model_requests
            BEGIN SELECT RAISE(ABORT,'fixture'); END""")
        assertThrows(Exception::class.java) { store.reserveModelRequest(model("first", parent), 4) }
        assertEquals(0L, store.modelBudgetTotals(parent).requests)
        assertTrue(store.items(parent).isEmpty())
        assertFalse(store.timeline(parent).any { it.event.type == RunEventType.MODEL_BUDGET_RESERVED })
        store.writableDatabase.execSQL("DROP TRIGGER reject_request")
        store.reserveModelRequest(model("first", parent), 5)
        val result = settle("first", parent)
        store.close(); store = NexusExecutionStore(context)
        assertEquals(listOf(result), store.modelBudgetRequests(parent))
    }

    @Test fun nextRequestMustFitTheRemainingCostBeforeAnyReservationIsCommitted() {
        val parent = start(budget = RunBudget(hardRounds = 8, costLimitMicros = 100))
        val child = start("child", parent)
        assertEquals("MODEL_COST_RESERVATION_REQUIRED", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("unbounded", child).copy(reservedCostMicros = null), 3)
        }.code)
        store.reserveModelRequest(model("first", parent).copy(reservedCostMicros = 70), 4)
        assertEquals(70L, store.modelBudgetTotals(child).reservedCostMicros)
        settle("first", parent, 60)
        assertEquals(0L, store.modelBudgetTotals(child).reservedCostMicros)
        assertEquals("RUN_COST_RESERVATION_EXCEEDED", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("too-large", child).copy(reservedCostMicros = 41), 5)
        }.code)
        assertEquals(1L, store.modelBudgetTotals(parent).requests)
        assertTrue(store.modelBudgetRequests(child).isEmpty())
        store.reserveModelRequest(model("fits-exactly", child), 6)
        store.close(); store = NexusExecutionStore(context)
        assertEquals(40L, store.modelBudgetTotals(parent).reservedCostMicros)
        assertEquals(40L, store.modelBudgetRequests(child).single().request.reservedCostMicros)
    }

    @Test fun childOnlyCostLimitAndNearMaximumLongCannotOverflowTheCheck() {
        val parent = start()
        val child = start("child", parent, RunBudget(hardRounds = 4, costLimitMicros = Long.MAX_VALUE, costCurrency = "CNY"))
        assertEquals(Long.MAX_VALUE, store.modelBudgetTotals(child).costLimitMicros)
        assertEquals("CNY", store.modelBudgetTotals(child).currency)
        store.reserveModelRequest(model("first", child, "CNY").copy(reservedCostMicros = Long.MAX_VALUE - 1), 4)
        settle("first", child, Long.MAX_VALUE - 1)
        assertEquals("RUN_COST_RESERVATION_EXCEEDED", assertThrows(ModelBudgetDenied::class.java) {
            store.reserveModelRequest(model("too-large", child, "CNY").copy(reservedCostMicros = 2), 5)
        }.code)
    }

    @Test fun lateUsageMaySettleAfterCancellationWithoutReopeningTheRun() {
        val parent = start()
        store.reserveModelRequest(model("first", parent), 4)
        assertThrows(IllegalStateException::class.java) {
            store.append(parent, ExecutionFact("success", RunEventType.RUN_SUCCEEDED, 5, state = RunState.SUCCEEDED))
        }
        store.append(parent, ExecutionFact("cancel", RunEventType.RUN_CANCELLED, 6, state = RunState.CANCELLED))
        settle("first", parent)
        assertEquals(RunState.CANCELLED, store.find(parent)?.state)
        assertEquals(0L, store.modelBudgetTotals(parent).openRequests)
        assertEquals(10L, store.modelBudgetTotals(parent).knownCostMicros)
        assertThrows(ModelBudgetDenied::class.java) { store.reserveModelRequest(model("again", parent), 11) }
    }

    @Test fun failedSettlementKeepsTheOpenReservationAndBlocksFurtherPricedRequests() {
        val parent = start(budget = RunBudget(hardRounds = 4, costLimitMicros = 100))
        store.reserveModelRequest(model("first", parent), 4)
        store.writableDatabase.execSQL("""CREATE TRIGGER reject_receipt BEFORE UPDATE ON execution_model_requests
            BEGIN SELECT RAISE(ABORT,'fixture'); END""")
        assertThrows(Exception::class.java) { settle("first", parent) }
        assertEquals(1L, store.modelBudgetTotals(parent).openRequests)
        assertNull(store.modelBudgetRequests(parent).single().settlement)
        assertFalse(store.timeline(parent).any { it.event.type == RunEventType.MODEL_BUDGET_SETTLED })
    }
}
