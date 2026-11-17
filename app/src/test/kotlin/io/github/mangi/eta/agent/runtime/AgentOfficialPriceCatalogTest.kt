package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.data.provider.BuiltinProviders
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentOfficialPriceCatalogTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        AgentMetricsDatabase.closeForTests()
        context.deleteDatabase(AgentMetricsDatabase.NAME)
        context.getSharedPreferences("official_token_price_tombstones", 0).edit().clear().commit()
        AgentTokenPriceStore.resetForTests()
    }

    @After fun close() {
        AgentMetricsDatabase.closeForTests()
        AgentTokenPriceStore.resetForTests()
    }

    @Test fun verifiedOfficialRatesSeedWithoutReplacingManualRatesOrDeletedPrices() = runBlocking {
        val seeded = AgentTokenPriceStore.resolve(context, BuiltinProviders.OPENAI_ID, "gpt-6.1-sol")!!
        assertEquals("USD", seeded.currency)
        assertEquals("2", seeded.input)
        assertEquals("10", seeded.output)
        assertEquals("0.1", seeded.cacheRead)
        assertEquals("official:builtin-openai:2026-11-12:v1", seeded.revision)
        assertEquals(LocalDate.of(2026, 11, 12), AgentOfficialPriceCatalog.verifiedOn(seeded))

        val manual = AgentTokenPrice.create("CNY", "3", "9", now = 123)
        AgentTokenPriceStore.save(context, BuiltinProviders.OPENAI_ID, "gpt-6.1-sol", manual)
        AgentTokenPriceStore.resetForTests()
        val afterManual = AgentTokenPriceStore.resolve(context, BuiltinProviders.OPENAI_ID, "gpt-6.1-sol")!!
        assertEquals(manual, afterManual)
        assertNotEquals(seeded.revision, afterManual.revision)

        AgentTokenPriceStore.remove(context, BuiltinProviders.OPENAI_ID, "gpt-6-luna")
        AgentTokenPriceStore.resetForTests()
        assertNull(AgentTokenPriceStore.resolve(context, BuiltinProviders.OPENAI_ID, "gpt-6-luna"))
    }

    @Test fun officialPageParserExtractsInputCachedInputAndOutputForKnownModels() {
        val html = """
            <astro-island props="{&quot;tier&quot;:[0,&quot;standard&quot;],&quot;rows&quot;:[
              [1,[[0,&quot;gpt-6.1-sol&quot;],[0,7],[0,0.7],[0,3.5],[0,28]]],
              [1,[[0,&quot;gpt-6-luna&quot;],[0,0.2],[0,0.02],[0,0.25],[0,1.2]]],
              [1,[[0,&quot;gpt-6-astra&quot;],[0,20],[0,2],[0,25],[0,100]]]
            ]}"></astro-island>
        """.trimIndent()
        val parsed = AgentOfficialPriceRefresh.parseOpenAiPage(html, LocalDate.of(2026, 12, 1))
        val sol = parsed.single { it.modelId == "gpt-6.1-sol" }
        assertEquals("7", sol.input)
        assertEquals("0.7", sol.cacheRead)
        assertEquals("28", sol.output)
        assertEquals(LocalDate.of(2026, 12, 1), sol.verifiedOn)
    }

    @Test fun deepSeekPageParserKeepsPeakAndOffPeakRates() {
        val html = """
            <table>
              <th>deepseek-flash</th><th>deepseek-v4-pro</th>
              <tr><td>PRICING</td></tr>
              <tr><td>$0.003</td><td>$0.022</td><td>$0.006</td><td>$0.044</td></tr>
              <tr><td>$0.15</td><td>$0.66</td><td>$0.3</td><td>$1.32</td></tr>
              <tr><td>$0.6</td><td>$1.98</td><td>$1.2</td><td>$3.96</td></tr>
              <tr><td>Concurrency Limit</td></tr>
            </table>
        """.trimIndent()
        val schedule = AgentOfficialPriceRefresh.parseDeepSeekPage(html, LocalDate.of(2026, 12, 2))
        val flash = schedule.models.getValue("deepseek-flash")
        assertEquals(DeepSeekRate("0.003", "0.15", "0.6"), flash.offPeak)
        assertEquals(DeepSeekRate("0.006", "0.3", "1.2"), flash.peak)
        val pro = schedule.models.getValue("deepseek-v4-pro")
        assertEquals(DeepSeekRate("0.022", "0.66", "1.98"), pro.offPeak)
        assertEquals(DeepSeekRate("0.044", "1.32", "3.96"), pro.peak)
        assertEquals("0.006", schedule.rate("deepseek-flash", Instant.parse("2026-11-12T02:00:00Z").toEpochMilli())?.cacheHit)
        assertEquals("0.003", schedule.rate("deepseek-flash", Instant.parse("2026-11-12T05:00:00Z").toEpochMilli())?.cacheHit)
        assertEquals("0.003", schedule.rate("deepseek-flash", Instant.parse("2026-11-14T02:00:00Z").toEpochMilli())?.cacheHit)
    }

    @Test fun deepSeekOfficialScheduleIsDynamicButManualAndDeletionStillWin() = runBlocking {
        val peak = AgentDeepSeekPriceSchedule.currentPrice(
            context,
            BuiltinProviders.DEEPSEEK_ID,
            "deepseek-flash",
            Instant.parse("2026-11-12T02:00:00Z").toEpochMilli(),
        )!!
        assertEquals("0.3", peak.input)
        assertEquals("1.2", peak.output)
        assertEquals("0.006", peak.cacheRead)
        assertTrue(peak.revision.contains(":peak:"))
        val offPeak = AgentDeepSeekPriceSchedule.currentPrice(
            context,
            BuiltinProviders.DEEPSEEK_ID,
            "deepseek-flash",
            Instant.parse("2026-11-14T02:00:00Z").toEpochMilli(),
        )!!
        assertEquals("0.15", offPeak.input)
        assertTrue(offPeak.revision.contains(":offpeak:"))

        val manual = AgentTokenPrice.create("CNY", "1", "2", now = 1)
        AgentTokenPriceStore.save(context, BuiltinProviders.DEEPSEEK_ID, "deepseek-flash", manual)
        assertEquals(manual, AgentTokenPriceStore.resolve(context, BuiltinProviders.DEEPSEEK_ID, "deepseek-flash"))
        AgentTokenPriceStore.remove(context, BuiltinProviders.DEEPSEEK_ID, "deepseek-flash")
        assertNull(AgentTokenPriceStore.resolve(context, BuiltinProviders.DEEPSEEK_ID, "deepseek-flash"))
    }
}
