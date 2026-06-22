package net.badgersmc.em.application

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IS2-7 (REQ-294): pure audit decision for periodic shop repair.
 * Red until [ShopAuditDecision.evaluate] is implemented.
 */
class ShopAuditDecisionTest {

    @Test
    fun `world unloaded returns SKIP even if block would not be a container`() {
        assertEquals(ShopAuditDecision.Decision.SKIP, ShopAuditDecision.evaluate(false, false))
    }

    @Test
    fun `world unloaded returns SKIP regardless of block state`() {
        assertEquals(ShopAuditDecision.Decision.SKIP, ShopAuditDecision.evaluate(false, true))
    }

    @Test
    fun `world loaded and block is container returns KEEP`() {
        assertEquals(ShopAuditDecision.Decision.KEEP, ShopAuditDecision.evaluate(true, true))
    }

    @Test
    fun `world loaded and block is not container returns REMOVE`() {
        assertEquals(ShopAuditDecision.Decision.REMOVE, ShopAuditDecision.evaluate(true, false))
    }
}
