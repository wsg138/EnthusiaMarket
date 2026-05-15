package net.badgersmc.em.domain.stall

data class RentTerms(
    val mode: Mode,
    val pct: Double,
    val flatAmount: Long
) {
    enum class Mode { FORMULA, FLAT }

    init {
        when (mode) {
            Mode.FORMULA -> require(pct >= 0.0) { "Formula pct must be >= 0" }
            Mode.FLAT -> require(flatAmount >= 0) { "Flat rent must be >= 0" }
        }
    }

    fun dailyRent(winningBid: Long): Long = when (mode) {
        Mode.FORMULA -> (winningBid * pct / 100.0).toLong()
        Mode.FLAT -> flatAmount
    }

    companion object {
        fun formula(pct: Double) = RentTerms(Mode.FORMULA, pct, 0L)
        fun flat(amount: Long) = RentTerms(Mode.FLAT, 0.0, amount)
    }
}
