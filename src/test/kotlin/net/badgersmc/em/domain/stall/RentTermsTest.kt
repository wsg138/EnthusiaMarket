package net.badgersmc.em.domain.stall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RentTermsTest {
    @Test fun `formula-based rent computes from winning bid`() {
        val terms = RentTerms.formula(pct = 1.0)
        assertEquals(50L, terms.dailyRent(winningBid = 5000L))
    }

    @Test fun `flat rent overrides the formula`() {
        val terms = RentTerms.flat(amount = 200L)
        assertEquals(200L, terms.dailyRent(winningBid = 99999L))
    }

    @Test fun `formula rejects negative pct`() {
        assertFailsWith<IllegalArgumentException> { RentTerms.formula(pct = -0.5) }
    }
}
