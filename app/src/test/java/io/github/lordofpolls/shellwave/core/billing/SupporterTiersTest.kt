package io.github.lordofpolls.shellwave.core.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class SupporterTiersTest {

    @Test
    fun `tiers are sorted cheapest first regardless of input order`() {
        val priced =
            listOf(
                PricedOption("supporter3", 9_990_000, "£9.99"),
                PricedOption("supporter1", 990_000, "£0.99"),
                PricedOption("supporter2", 2_990_000, "£2.99"),
            )

        val tiers = supporterTiers(priced)

        assertEquals(listOf("supporter1", "supporter2", "supporter3"), tiers.map { it.purchaseOptionId })
        assertEquals(listOf("£0.99", "£2.99", "£9.99"), tiers.map { it.priceLabel })
    }
}
