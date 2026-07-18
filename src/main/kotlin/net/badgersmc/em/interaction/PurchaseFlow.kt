package net.badgersmc.em.interaction

import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/** Shared application adapter used by both Java and Bedrock stall-purchase UIs. */
object PurchaseFlow {
    data class GuildOption(val id: String, val name: String)

    fun eligibleGuild(player: Player, guildProvider: GuildProvider?): GuildOption? {
        val guild = guildProvider?.guildOf(player.uniqueId) ?: return null
        if (!guildProvider.hasShopPermission(
                player.uniqueId, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS,
            )) return null
        return GuildOption(guild.id, guild.name)
    }

    fun execute(player: Player, stallId: StallId, price: Long, guild: Boolean, buyout: StallBuyoutService) =
        if (guild) buyout.buyForGuild(stallId, player.uniqueId, price, playerIp(player))
        else buyout.buy(stallId, player.uniqueId, price, playerIp(player))

    fun message(result: StallBuyoutService.Result, stallId: StallId, lang: LangService): Component = when (result) {
        is StallBuyoutService.Result.Purchased -> lang.msg(
            if (result.owner.type == OwnerType.GUILD) "purchase_sign.msg.purchased_guild" else "purchase_sign.msg.purchased",
            "stall" to stallId.value, "price" to result.price,
        )
        is StallBuyoutService.Result.NotFound -> lang.msg("purchase_sign.msg.stall_missing", "stall" to stallId.value)
        is StallBuyoutService.Result.AuctionLive -> lang.msg("purchase_sign.msg.auction_live", "stall" to stallId.value)
        is StallBuyoutService.Result.AlreadyOwned -> lang.msg("purchase_sign.msg.already_owned", "stall" to stallId.value)
        is StallBuyoutService.Result.NotInGuild -> lang.msg("purchase_sign.msg.not_in_guild")
        is StallBuyoutService.Result.NoGuildPermission -> lang.msg("purchase_sign.msg.no_guild_permission")
        is StallBuyoutService.Result.Rejected -> lang.msg("purchase_sign.msg.rejected", "reason" to result.reason)
    }

    private fun playerIp(player: Player): String = player.address?.address?.hostAddress ?: "unknown"
}
