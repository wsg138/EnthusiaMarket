package net.badgersmc.em.domain.stall

import java.time.Instant

data class Stall(
    val id: StallId,
    val regionId: String,
    val world: String,
    val state: StallState,
    val owner: OwnerRef,
    val ownerSince: Instant?,
    val winningBid: Long,
    val rentTerms: RentTerms
) {
    fun awardTo(newOwner: OwnerRef, winningBid: Long, at: Instant): Stall {
        require(newOwner.type != OwnerType.NONE) { "Cannot award stall to nobody" }
        require(winningBid > 0) { "Winning bid must be positive" }
        return copy(
            state = StallState.OWNED,
            owner = newOwner,
            ownerSince = at,
            winningBid = winningBid
        )
    }
}
