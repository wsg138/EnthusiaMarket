package net.badgersmc.em.application

import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignKind
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component

/**
 * Renders the four lines of a [PurchaseSign] from the current stall
 * state + an open sell offer (when present). Lang keys live under
 * `purchase_sign.<kind>.<state>.lineN`. Falls back to a generic
 * template when a state-specific key isn't found.
 *
 * Returned list is always exactly 4 entries — the Bukkit Sign API
 * requires every line, blanks included.
 */
@Service
class PurchaseSignRenderer(
    private val stalls: StallRepository,
    private val offers: SellOfferRepository,
    private val lang: LangService,
) {

    fun render(sign: PurchaseSign): List<Component> {
        val stall = stalls.findById(sign.stallId)
        val keyKind = sign.kind.name.lowercase()

        return when (sign.kind) {
            PurchaseSignKind.BUY -> renderBuy(sign, stall)
            PurchaseSignKind.RENT, PurchaseSignKind.EXTEND ->
                renderRentExtend(sign, stall, keyKind)
            PurchaseSignKind.INFO -> renderInfo(sign, stall)
        }
    }

    private fun renderBuy(sign: PurchaseSign, stall: Stall?): List<Component> {
        if (stall == null) return missingStall(sign)
        val offer = offers.findByStall(sign.stallId)
        return when {
            offer != null -> listOf(
                lang.msg("purchase_sign.buy.for_sale.line1"),
                lang.msg("purchase_sign.buy.for_sale.line2", "stall" to sign.stallId.value),
                lang.msg("purchase_sign.buy.for_sale.line3", "price" to offer.price),
                lang.msg("purchase_sign.buy.for_sale.line4"),
            )
            stall.state == StallState.UNOWNED -> listOf(
                lang.msg("purchase_sign.buy.unowned.line1"),
                lang.msg("purchase_sign.buy.unowned.line2", "stall" to sign.stallId.value),
                lang.msg("purchase_sign.buy.unowned.line3"),
                lang.msg("purchase_sign.buy.unowned.line4"),
            )
            else -> listOf(
                lang.msg("purchase_sign.buy.owned.line1"),
                lang.msg("purchase_sign.buy.owned.line2", "stall" to sign.stallId.value),
                lang.msg("purchase_sign.buy.owned.line3"),
                lang.msg("purchase_sign.buy.owned.line4"),
            )
        }
    }

    private fun renderRentExtend(sign: PurchaseSign, stall: Stall?, key: String): List<Component> {
        if (stall == null) return missingStall(sign)
        return listOf(
            lang.msg("purchase_sign.$key.line1"),
            lang.msg("purchase_sign.$key.line2", "stall" to sign.stallId.value),
            lang.msg("purchase_sign.$key.line3", "state" to stall.state.name),
            lang.msg("purchase_sign.$key.line4"),
        )
    }

    private fun renderInfo(sign: PurchaseSign, stall: Stall?): List<Component> {
        if (stall == null) return missingStall(sign)
        return listOf(
            lang.msg("purchase_sign.info.line1"),
            lang.msg("purchase_sign.info.line2", "stall" to sign.stallId.value),
            lang.msg("purchase_sign.info.line3", "state" to stall.state.name),
            lang.msg("purchase_sign.info.line4"),
        )
    }

    private fun missingStall(sign: PurchaseSign): List<Component> = listOf(
        lang.msg("purchase_sign.missing.line1"),
        lang.msg("purchase_sign.missing.line2", "stall" to sign.stallId.value),
        Component.empty(),
        Component.empty(),
    )
}
