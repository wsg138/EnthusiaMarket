package net.badgersmc.em.domain.ports

import java.util.UUID

interface EconomyProvider {
    fun balance(player: UUID): Long
    fun withdraw(player: UUID, amount: Long): Boolean
    fun deposit(player: UUID, amount: Long): Boolean
}
