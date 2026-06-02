package net.badgersmc.em.domain.ports

import java.util.UUID

interface GuildProvider {
    /**
     * @property tag the guild's display tag (may be MiniMessage-formatted);
     *   empty when the guild has none. Used by the purchase-sign renderer's
     *   `%guild_tag%` template token (REQ-250 extension).
     * @property emoji the guild's emoji/icon string; empty when unset.
     */
    data class GuildRef(
        val id: String,
        val name: String,
        val tag: String = "",
        val emoji: String = "",
    )

    fun guildOf(player: UUID): GuildRef?
    fun guildById(id: String): GuildRef?
    fun isMember(player: UUID, guildId: String): Boolean

    /**
     * Check whether [player] has [permission] in the guild identified by [guildId].
     * This is the primary permission check — uses LumaGuilds RankPermission enum
     * for reliable permission resolution regardless of rank names.
     */
    fun hasShopPermission(player: UUID, guildId: String, permission: GuildPermission): Boolean

    @Suppress("DeprecatedBlockTag")
    /**
     * Legacy string-based permission check by rank name.
     * @deprecated Use [hasShopPermission] with [GuildPermission] instead.
     */
    @Deprecated("Use hasShopPermission with GuildPermission", replaceWith = ReplaceWith("hasShopPermission(player, guildId, permission)"))
    fun hasPermission(player: UUID, guildId: String, node: String): Boolean

    enum class GuildPermission {
        /** Can create and manage guild shops (access shop chests, edit stock, modify prices) */
        MANAGE_SHOPS,
        /** Can access/open guild shop chests */
        ACCESS_SHOP_CHESTS,
        /** Can modify inventory in shop chests */
        EDIT_SHOP_STOCK,
        /** Can change prices on shop signs */
        MODIFY_SHOP_PRICES
    }

    fun bankBalance(guildId: String): Long
    fun bankWithdraw(guildId: String, amount: Long): Boolean
    fun bankDeposit(guildId: String, amount: Long): Boolean

    /** Register a callback invoked when a guild is dissolved. */
    fun onDissolved(handler: (guildId: String) -> Unit)
}
