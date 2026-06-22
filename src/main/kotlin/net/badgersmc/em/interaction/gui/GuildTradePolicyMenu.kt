package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * MANAGE_SHOPS GUI listing a guild's tariff/embargo policies. Click a policy to
 * adjust it; the "+" button opens the guild picker. All mutations route through
 * [GuildTradePolicyService] (which re-validates + broadcasts).
 */
class GuildTradePolicyMenu(
    private val actor: UUID,
    private val ownerGuildId: String,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) = render(player)

    private fun render(player: Player) {
        val policies = policyService.list(ownerGuildId)
        val pageCount = maxOf(1, (policies.size + PER_PAGE - 1) / PER_PAGE)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.guildpolicy.title")))
        val pages = policyPages(player, policies, pageCount)
        gui.addPane(pages)
        gui.addPane(controls(player, gui, pages, pageCount))
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun policyPages(player: Player, policies: List<GuildTradePolicy>, pageCount: Int): PaginatedPane {
        val pages = PaginatedPane(0, 0, 9, 5)
        for (p in 0 until pageCount) {
            val pane = OutlinePane(0, 0, 9, 5, Pane.Priority.LOWEST)
            policies.drop(p * PER_PAGE).take(PER_PAGE).forEach { policy ->
                pane.addItem(GuiItem(icon(policy)) { ev ->
                    ev.isCancelled = true
                    // Only left/right clicks mutate; ignore middle/drop/number-key clicks.
                    if (ev.isLeftClick || ev.isRightClick) mutate(player, policy, ev.isLeftClick, ev.isShiftClick)
                })
            }
            pages.addPane(p, pane)
        }
        return pages
    }

    private fun controls(player: Player, gui: ChestGui, pages: PaginatedPane, pageCount: Int): StaticPane {
        val bar = StaticPane(0, 5, 9, 1)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.prev"))) {
            it.isCancelled = true; if (pages.page > 0) { pages.page -= 1; gui.update() }
        }, 0, 0)
        bar.addItem(GuiItem(named(Material.EMERALD, lang.msg("gui.guildpolicy.add"))) {
            it.isCancelled = true
            GuildPickerMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
        }, 4, 0)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.next"))) {
            it.isCancelled = true; if (pages.page < pageCount - 1) { pages.page += 1; gui.update() }
        }, 8, 0)
        return bar
    }

    private fun mutate(player: Player, policy: GuildTradePolicy, left: Boolean, shift: Boolean) {
        when (val result = applyClick(policy, left, shift)) {
            is GuildTradePolicyService.PolicyResult.Invalid ->
                player.sendMessage(lang.msg("gui.guildpolicy.invalid", "reason" to result.reason))
            GuildTradePolicyService.PolicyResult.Denied ->
                player.sendMessage(lang.msg("gui.guildpolicy.denied"))
            GuildTradePolicyService.PolicyResult.Ok -> Unit
        }
        render(player)
    }

    private fun applyClick(policy: GuildTradePolicy, left: Boolean, shift: Boolean): GuildTradePolicyService.PolicyResult =
        when {
            shift -> if (left) policyService.setEmbargo(actor, ownerGuildId, policy.targetGuildId)
                     else policyService.clear(actor, ownerGuildId, policy.targetGuildId)
            left -> policyService.setTariff(actor, ownerGuildId, policy.targetGuildId, stepUp(currentRate(policy)))
            else -> policyService.setTariff(actor, ownerGuildId, policy.targetGuildId, stepDown(currentRate(policy)))
        }

    private fun currentRate(policy: GuildTradePolicy): Int =
        if (policy.kind == PolicyKind.TARIFF) policy.ratePct else MIN_TARIFF_PCT

    private fun icon(policy: GuildTradePolicy): ItemStack {
        val target = guildProvider.guildById(policy.targetGuildId)?.name ?: policy.targetGuildId
        val (mat, line) = if (policy.kind == PolicyKind.EMBARGO) {
            Material.BARRIER to lang.msg("gui.guildpolicy.icon_embargo", "target" to target)
        } else {
            Material.GOLD_INGOT to lang.msg("gui.guildpolicy.icon_tariff", "target" to target, "rate" to policy.ratePct)
        }
        return named(mat, line, lang.msg("gui.guildpolicy.icon_controls"))
    }

    private fun named(material: Material, name: Component, vararg lore: Component): ItemStack =
        ItemStack(material).apply {
            itemMeta = itemMeta?.also { m -> m.displayName(name); if (lore.isNotEmpty()) m.lore(lore.toList()) }
        }

    companion object {
        const val MIN_TARIFF_PCT = 5
        private const val PER_PAGE = 45
        fun stepUp(current: Int): Int = (current + 5).coerceAtMost(GuildTradePolicyService.MAX_TARIFF_PCT)
        fun stepDown(current: Int): Int = (current - 5).coerceAtLeast(MIN_TARIFF_PCT)
    }
}
