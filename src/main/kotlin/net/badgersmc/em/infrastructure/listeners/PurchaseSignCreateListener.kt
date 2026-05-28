package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.PurchaseSignRenderer
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignKind
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Registers a [PurchaseSign] when a player writes a sign whose first
 * line matches `config.signs.triggerToken` (default `[em]`) and whose
 * second line is an existing stall id. Third line (optional) selects
 * the sign kind (`BUY` / `RENT` / `EXTEND` / `INFO`); defaults to
 * `BUY`. See REQ-251.
 *
 * Permission `enthusiamarket.sign.create` required.
 */
@Component
open class PurchaseSignCreateListener(
    private val stalls: StallRepository,
    private val signs: PurchaseSignRepository,
    private val renderer: PurchaseSignRenderer,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin
            ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onSignPlace(event: SignChangeEvent) {
        val lines = event.lines()
        val plain = PlainTextComponentSerializer.plainText()
        val firstLine = plain.serialize(lines[0]).trim()
        if (!firstLine.equals(config.signs.triggerToken, ignoreCase = true)) return

        val player = event.player
        if (!player.hasPermission("enthusiamarket.sign.create")) {
            player.sendMessage(lang.msg("purchase_sign.msg.no_permission"))
            event.isCancelled = true
            return
        }

        val stallName = plain.serialize(lines.getOrElse(1) { net.kyori.adventure.text.Component.empty() }).trim()
        if (stallName.isBlank()) return

        val stall = stalls.findById(StallId(stallName))
        if (stall == null) {
            player.sendMessage(lang.msg("purchase_sign.msg.invalid_stall", "stall" to stallName))
            event.isCancelled = true
            return
        }

        val kindRaw = plain.serialize(lines.getOrElse(2) { net.kyori.adventure.text.Component.empty() }).trim()
        val kind = if (kindRaw.isBlank()) PurchaseSignKind.BUY
        else PurchaseSignKind.parse(kindRaw) ?: PurchaseSignKind.BUY

        val block = event.block
        val sign = PurchaseSign(
            stallId = stall.id,
            world = block.world.name,
            x = block.x, y = block.y, z = block.z,
            kind = kind,
        )
        signs.save(sign)

        val rendered = renderer.render(sign)
        for (i in 0 until 4) {
            event.line(i, rendered.getOrElse(i) { net.kyori.adventure.text.Component.empty() })
        }
        player.sendMessage(lang.msg("purchase_sign.msg.created", "stall" to stallName, "kind" to kind.name))
    }
}
