package net.badgersmc.em.application

/**
 * Pure helper for bulk-purchase sizing (REQ-289 Buy Max, REQ-291 affordability status).
 *
 * Application layer: stdlib only, no Bukkit — the infrastructure caller resolves the raw
 * unit counts (container stock, payer holdings, buyer free space) and passes them in.
 *
 * Skeleton for IS2-1 — filled by `spear:engine` to flip the red [TradeQuantityTest] green.
 */
object TradeQuantity {

    /**
     * The largest number of whole trades executable, = `max(0, min(stockCap, walletCap, spaceCap))`
     * where each cap is an integer-division floor:
     *  - stockCap  = [stockUnits] / [sellPerTrade]
     *  - walletCap = [payerUnits] / [costPerTrade]
     *  - spaceCap  = [freeSpaceUnits] / [sellPerTrade]
     *
     * @param stockUnits     units of the sell item available in the container
     * @param payerUnits     units of the cost item/currency the payer holds
     * @param freeSpaceUnits units of the sell item that fit in the buyer's inventory
     * @param sellPerTrade   sell amount per trade (>= 1)
     * @param costPerTrade   cost amount per trade (>= 1)
     */
    fun maxTrades(
        stockUnits: Int,
        payerUnits: Int,
        freeSpaceUnits: Int,
        sellPerTrade: Int,
        costPerTrade: Int,
    ): Int = maxOf(
        0,
        minOf(
            stockUnits / sellPerTrade,
            payerUnits / costPerTrade,
            freeSpaceUnits / sellPerTrade,
        ),
    )
}
