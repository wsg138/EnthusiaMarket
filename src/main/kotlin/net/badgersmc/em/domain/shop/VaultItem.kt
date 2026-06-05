package net.badgersmc.em.domain.shop

import java.util.UUID

/** A stack of collected barter payment held in an owner's vault (ItemShops parity SP3). */
data class VaultItem(val owner: UUID, val itemBytes: String /* Base64 NBT */, val amount: Int)
