package net.badgersmc.em.interaction.gui

import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.interaction.Menu
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.entity.Player
import java.util.UUID

/** Stub — full implementation in Task 4. */
class GuildPickerMenu(
    private val actor: UUID,
    private val ownerGuildId: String,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
) : Menu {
    override fun open(player: Player) { /* Task 4 */ }
}
