package net.badgersmc.em.websync.heads

import java.util.UUID

fun interface PublishedHeadLookup {
    fun url(playerId: UUID): String?
}
