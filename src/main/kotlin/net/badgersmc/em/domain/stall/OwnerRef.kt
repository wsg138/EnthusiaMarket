package net.badgersmc.em.domain.stall

import java.util.UUID

data class OwnerRef(val type: OwnerType, val id: String) {
    init {
        if (type == OwnerType.NONE) require(id.isEmpty()) { "Unowned ref must have empty id" }
        else require(id.isNotBlank()) { "Owned ref requires non-blank id" }
    }

    companion object {
        fun unowned() = OwnerRef(OwnerType.NONE, "")
        fun solo(uuid: UUID) = OwnerRef(OwnerType.SOLO, uuid.toString())
        fun guild(guildId: String) = OwnerRef(OwnerType.GUILD, guildId)
    }
}
