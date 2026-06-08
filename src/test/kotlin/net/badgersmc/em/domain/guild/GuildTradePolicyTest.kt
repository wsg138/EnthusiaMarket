package net.badgersmc.em.domain.guild

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuildTradePolicyTest {
    @Test fun `tariff policy holds its rate`() {
        val p = GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 20)
        assertEquals(PolicyKind.TARIFF, p.kind)
        assertEquals(20, p.ratePct)
    }
    @Test fun `embargo policy ignores rate`() {
        val p = GuildTradePolicy("g1", "g2", PolicyKind.EMBARGO, 0)
        assertEquals(PolicyKind.EMBARGO, p.kind)
    }
    @Test fun `self-targeting is rejected`() {
        assertFailsWith<IllegalArgumentException> { GuildTradePolicy("g1", "g1", PolicyKind.TARIFF, 10) }
    }
    @Test fun `rate out of range is rejected`() {
        assertFailsWith<IllegalArgumentException> { GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, -1) }
        assertFailsWith<IllegalArgumentException> { GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 1001) }
    }
}
