package net.badgersmc.em.domain.stall

import kotlin.test.Test
import kotlin.test.assertEquals

class StallKindTest {

    private fun base() = Stall(
        id = StallId("stall1"), regionId = "stall1", world = "world",
        state = StallState.UNOWNED, owner = OwnerRef.unowned(),
        ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
    )

    @Test fun `new stall defaults to default kind and no extras`() {
        val s = base()
        assertEquals("default", s.kind)
        assertEquals(emptyMap(), s.extraEntities)
        assertEquals(0, s.extraTotal)
    }

    @Test fun `kind and extras are settable via copy`() {
        val s = base().copy(kind = "shop", extraEntities = mapOf("villager" to 3), extraTotal = 10)
        assertEquals("shop", s.kind)
        assertEquals(3, s.extraEntities["villager"])
        assertEquals(10, s.extraTotal)
    }
}
