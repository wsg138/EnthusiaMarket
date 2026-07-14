package net.badgersmc.em.websync

import java.util.UUID

object PublicNameResolver {
    fun player(uuid: UUID, lookup: (UUID) -> String?): String = lookup(uuid) ?: "Unknown Player"
    fun guild(id: String, lookup: (String) -> String?): String = lookup(id) ?: "Unknown Guild"
    fun delegatedMember(uuid: UUID, lookup: (UUID) -> String?): String? = lookup(uuid)
}
