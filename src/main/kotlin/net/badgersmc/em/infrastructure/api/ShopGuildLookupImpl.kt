package net.badgersmc.em.infrastructure.api

import net.badgersmc.em.api.ShopGuildLookup
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Implementation of [ShopGuildLookup] that delegates to EnthusiaMarket's
 * internal ShopRepository and ShopGuildService.
 *
 * Registers itself in Bukkit's ServicesManager so LumaGuilds and other
 * plugins can access it without a compile-time dependency.
 */
@Component
class ShopGuildLookupImpl(
    private val shopRepository: ShopRepository,
    private val shopGuildService: net.badgersmc.em.application.ShopGuildService
) : ShopGuildLookup {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin ?: return
        Bukkit.getServicesManager().register(ShopGuildLookup::class.java, this, plugin, org.bukkit.plugin.ServicePriority.Normal)
    }

    override fun isShopContainer(location: Location): Boolean {
        val shops = shopRepository.findByContainer(
            location.world?.name ?: return false,
            location.blockX, location.blockY, location.blockZ
        )
        return shops.isNotEmpty()
    }

    override fun getGuildForContainer(location: Location): UUID? {
        val shops = shopRepository.findByContainer(
            location.world?.name ?: return null,
            location.blockX, location.blockY, location.blockZ
        )
        return shops.firstOrNull { it.guildId != null }?.guildId
    }

    override fun registerGuildShopBySign(signLocation: Location, guildId: UUID, playerId: UUID): Boolean {
        val world = signLocation.world ?: return false
        val shop = shopRepository.findBySign(world.name, signLocation.blockX, signLocation.blockY, signLocation.blockZ)
            ?: return false
        return shopGuildService.registerGuildShop(shop.id, guildId, playerId).isSuccess
    }
}