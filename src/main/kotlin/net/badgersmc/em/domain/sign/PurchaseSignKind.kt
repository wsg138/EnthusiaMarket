package net.badgersmc.em.domain.sign

/**
 * Action a purchase sign performs when right-clicked. See REQ-250.
 *
 * - [BUY]: triggers `/em stall buy` against any open sell offer. If no
 *   offer is open the click renders a "not for sale" message.
 * - [RENT]: pays one rent period for the sign's stall on the clicker's
 *   behalf (must be the current owner).
 * - [EXTEND]: alias for RENT, intended for visual differentiation on
 *   long-term stalls. Same handler.
 * - [INFO]: displays the REQ-230 region info card to the clicker. Until
 *   TDD-230 lands this falls back to a minimal state-summary message.
 */
enum class PurchaseSignKind {
    BUY,
    RENT,
    EXTEND,
    INFO;

    companion object {
        fun parse(raw: String): PurchaseSignKind? =
            values().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}
