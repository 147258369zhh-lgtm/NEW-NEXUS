package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.provider.BuiltinProviders
import java.time.LocalDate

internal data class OfficialTokenPrice(
    val providerId: String,
    val modelId: String,
    val input: String,
    val output: String,
    val cacheRead: String? = null,
    val verifiedOn: LocalDate,
    val sourceUrl: String,
) {
    val revision: String get() = "official:$providerId:$verifiedOn:v1"
}

/** Bundled fallback from verified public provider pricing pages; never overwrites a manual rate. */
internal object AgentOfficialPriceCatalog {
    private const val OPENAI_PRICING_URL = "https://developers.openai.com/api/docs/pricing"
    private val verifiedOn = LocalDate.of(2026, 11, 12)
    val entries = listOf(
        OfficialTokenPrice(BuiltinProviders.OPENAI_ID, "gpt-6.1-sol", "2", "10", "0.1", verifiedOn, OPENAI_PRICING_URL),
        OfficialTokenPrice(BuiltinProviders.OPENAI_ID, "gpt-6-luna", "0.1", "0.5", "0.01", verifiedOn, OPENAI_PRICING_URL),
        OfficialTokenPrice(BuiltinProviders.OPENAI_ID, "gpt-6-astra", "10", "50", "1", verifiedOn, OPENAI_PRICING_URL),
    )

    fun asTokenPrice(entry: OfficialTokenPrice): AgentTokenPrice = AgentTokenPrice.create(
        currency = "USD",
        input = entry.input,
        output = entry.output,
        cacheRead = entry.cacheRead.orEmpty(),
        now = entry.verifiedOn.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
    ).copy(revision = entry.revision)

    fun source(price: AgentTokenPrice): String? = entries.firstOrNull { price.revision == it.revision }?.sourceUrl

    fun verifiedOn(price: AgentTokenPrice): LocalDate? = entries.firstOrNull { price.revision == it.revision }?.verifiedOn
        ?: price.revision.takeIf { it.startsWith("official:deepseek:") }?.split(':')
            ?.getOrNull(3)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
