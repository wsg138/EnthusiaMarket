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
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.guildpicker.title")))
        val pages = PaginatedPane(0, 0, 9, 5)
        val perPage = 45
        val pageCount = maxOf(1, (targets.size + perPage - 1) / perPage)
        for (p in 0 until pageCount) {
            val pane = OutlinePane(0, 0, 9, 5, Pane.Priority.LOWEST)
            targets.drop(p * perPage).take(perPage).forEach { ref ->
                pane.addItem(GuiItem(named(Material.PAPER, lang.msg("gui.guildpicker.entry", "name" to ref.name))) {
                    it.isCancelled = true
                    policyService.setTariff(actor, ownerGuildId, ref.id, DEFAULT_NEW_TARIFF)
                    GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
                })
            }
            pages.addPane(p, pane)
        }
        gui.addPane(pages)
        val bar = StaticPane(0, 5, 9, 1)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.prev"))) { it.isCancelled = true; if (pages.page > 0) { pages.page -= 1; gui.update() } }, 0, 0)
        bar.addItem(GuiItem(named(Material.BARRIER, lang.msg("gui.common.back"))) {
            it.isCancelled = true; GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
        }, 4, 0)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.next"))) { it.isCancelled = true; if (pages.page < pageCount - 1) { pages.page += 1; gui.update() } }, 8, 0)
        gui.addPane(bar)
        gui.show(player)
    }

    private fun named(material: Material, name: Component): ItemStack =
        ItemStack(material).apply { itemMeta = itemMeta?.also { it.displayName(name) } }

    companion object {
        const val DEFAULT_NEW_TARIFF = 10
        /** Guilds eligible as new policy targets: all guilds minus the owner and already-policied ids. */
        fun selectable(all: List<GuildProvider.GuildRef>, ownerGuildId: String, existingTargets: Set<String>): List<GuildProvider.GuildRef> =
            all.filter { it.id != ownerGuildId && it.id !in existingTargets }
    }
}
