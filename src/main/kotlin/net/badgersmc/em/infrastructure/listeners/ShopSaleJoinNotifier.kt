package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** On join, summarise sales the owner missed while offline, then mark them seen (SP6). */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ShopSaleJoinNotifier(
    private val transactions: ShopTransactionRepository,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!config.shop.notifyEnabled) return
        val owner = event.player.uniqueId
        val unseen = transactions.countUnnotified(owner)
        if (unseen <= 0) return
        event.player.sendMessage(lang.msg("shop.notify.away_summary", "count" to unseen))
        transactions.markNotified(owner)
    }
}
