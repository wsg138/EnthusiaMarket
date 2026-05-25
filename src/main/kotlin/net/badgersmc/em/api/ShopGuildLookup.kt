package net.badgersmc.em.api

import org.bukkit.Location
import java.util.UUID

/**
 * Public API interface for LumaGuilds and other external plugins to
 * interact with EnthusiaMarket's guild shop system.
 *
 * Registered in Bukkit's ServicesManager at plugin enable.
 * Access via: Bukkit.getServicesManager().load(ShopGuildLookup::class.java)
 */
interface ShopGuildLookup {

    /**
     * Check if a location is a registered shop container.
     */
    fun isShopContainer(location: Location): Boolean

    /**
     * Get the guild ID for a shop at the given container location, or null if not guild-owned.
     */
    fun getGuildForContainer(location: Location): UUID?

    /**
     * Find a shop by its sign location and register it as guild-owned.
     * @return the shop ID if successful, null if no shop at that sign
     */
    fun registerGuildShopBySign(signLocation: Location, guildId: UUID, playerId: UUID): Boolean
}