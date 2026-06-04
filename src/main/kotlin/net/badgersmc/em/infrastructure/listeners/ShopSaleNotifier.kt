package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.events.PostShopTransactionEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/** Notifies an online shop owner of a sale and marks their pending rows seen (SP6). */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ShopSaleNotifier(
    private val transactions: ShopTransactionRepository,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) : Listener {

    @EventHandler
    fun onTransaction(event: PostShopTransactionEvent) {
        if (!config.shop.notifyEnabled) return
        val owner = Bukkit.getPlayer(event.landlordId) ?: return // offline → join notifier handles it
        owner.sendMessage(lang.msg(
            "shop.notify.sold",
            "qty" to event.quantity, "item" to event.item.type.name.lowercase(),
            "price" to event.pricePaid.toLong(), "buyer" to event.buyer.name,
        ))
        transactions.markNotified(event.landlordId)
    }
}
