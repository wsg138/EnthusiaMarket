package net.badgersmc.em.application

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IS2-1 (REQ-289 Buy Max, REQ-291 status): pure max-executable-trades calculation.
 * Red until [TradeQuantity.maxTrades] is implemented (currently TODO).
 */
class TradeQuantityTest {

    @Test
    fun `capped by container stock`() {
        // stock 10 / sell 5 = 2 trades; wallet + space are not the limit
        assertEquals(2, TradeQuantity.maxTrades(stockUnits = 10, payerUnits = 999, freeSpaceUnits = 999, sellPerTrade = 5, costPerTrade = 1))
    }

    @Test
    fun `capped by payer wallet`() {
        // payer 7 / cost 2 = 3 trades; stock + space are not the limit
        assertEquals(3, TradeQuantity.maxTrades(stockUnits = 999, payerUnits = 7, freeSpaceUnits = 999, sellPerTrade = 1, costPerTrade = 2))
    }

    @Test
    fun `capped by buyer free space`() {
        // freeSpace 4 / sell 2 = 2 trades; stock + wallet are not the limit
        assertEquals(2, TradeQuantity.maxTrades(stockUnits = 999, payerUnits = 999, freeSpaceUnits = 4, sellPerTrade = 2, costPerTrade = 1))
    }

    @Test
    fun `zero when any cap is zero`() {
        assertEquals(0, TradeQuantity.maxTrades(stockUnits = 0, payerUnits = 999, freeSpaceUnits = 999, sellPerTrade = 5, costPerTrade = 1))
    }

    @Test
    fun `integer-division floors each cap`() {
        // stock 9 / sell 4 = 2 (floor), not 2.25
        assertEquals(2, TradeQuantity.maxTrades(stockUnits = 9, payerUnits = 999, freeSpaceUnits = 999, sellPerTrade = 4, costPerTrade = 1))
    }
}
