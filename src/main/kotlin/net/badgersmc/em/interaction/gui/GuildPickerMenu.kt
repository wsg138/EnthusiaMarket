package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.interaction.Menu
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Picks a target guild for a new policy; seeds a default tariff then returns to the policy menu. */
class GuildPickerMenu(
    private val actor: UUID,
    private val ownerGuildId: String,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) {
        val existing = policyService.list(ownerGuildId).map { it.targetGuildId }.toSet()
        val targets = selectable(guildProvider.listGuilds(), ownerGuildId, existing)
        val pageCount = maxOf(1, (targets.size + PER_PAGE - 1) / PER_PAGE)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.guildpicker.title")))
        val pages = pickerPages(player, targets, pageCount)
        gui.addPane(pages)
        gui.addPane(navBar(player, gui, pages, pageCount))
        gui.show(player)
    }

    private fun pickerPages(player: Player, targets: List<GuildProvider.GuildRef>, pageCount: Int): PaginatedPane {
        val pages = PaginatedPane(0, 0, 9, 5)
        for (p in 0 until pageCount) {
            val pane = OutlinePane(0, 0, 9, 5, Pane.Priority.LOWEST)
            targets.drop(p * PER_PAGE).take(PER_PAGE).forEach { ref ->
                pane.addItem(GuiItem(named(Material.PAPER, lang.msg("gui.guildpicker.entry", "name" to ref.name))) { ev ->
                    ev.isCancelled = true; pickTarget(player, ref)
                })
            }
            pages.addPane(p, pane)
        }
        return pages
    }

    private fun pickTarget(player: Player, ref: GuildProvider.GuildRef) {
        when (val result = policyService.setTariff(actor, ownerGuildId, ref.id, DEFAULT_NEW_TARIFF)) {
            is GuildTradePolicyService.PolicyResult.Invalid ->
                player.sendMessage(lang.msg("gui.guildpicker.invalid", "reason" to result.reason))
            GuildTradePolicyService.PolicyResult.Denied ->
                player.sendMessage(lang.msg("gui.guildpolicy.denied"))
            GuildTradePolicyService.PolicyResult.Ok ->
                GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
        }
    }

    private fun navBar(player: Player, gui: ChestGui, pages: PaginatedPane, pageCount: Int): StaticPane {
        val bar = StaticPane(0, 5, 9, 1)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.prev"))) { it.isCancelled = true; if (pages.page > 0) { pages.page -= 1; gui.update() } }, 0, 0)
        bar.addItem(GuiItem(named(Material.BARRIER, lang.msg("gui.common.back"))) {
            it.isCancelled = true; GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
        }, 4, 0)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.next"))) { it.isCancelled = true; if (pages.page < pageCount - 1) { pages.page += 1; gui.update() } }, 8, 0)
        return bar
    }

    private fun named(material: Material, name: Component): ItemStack =
        ItemStack(material).apply { itemMeta = itemMeta?.also { it.displayName(name) } }

    companion object {
        const val DEFAULT_NEW_TARIFF = 10
        private const val PER_PAGE = 45
        /** Guilds eligible as new policy targets: all guilds minus the owner and already-policied ids. */
        fun selectable(all: List<GuildProvider.GuildRef>, ownerGuildId: String, existingTargets: Set<String>): List<GuildProvider.GuildRef> =
            all.filter { it.id != ownerGuildId && it.id !in existingTargets }
    }
}
