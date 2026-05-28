package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.PurchaseSignRenderer
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.sign.PurchaseSign
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
 * Registers a [PurchaseSign] when a player writes a sign with:
 *
 * ```
 * line 1: <triggerToken>   (default "[em]")
 * line 2: <stall id>
 * line 3: <price>           (positive integer)
 * line 4: (ignored)
 * ```
 *
 * Permission `enthusiamarket.sign.create` required (REQ-251).
 * Invalid stall id / non-positive price / blank fields → event
 * cancelled with a translated lang message; no binding persisted.
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
        val plain = PlainTextComponentSerializer.plainText()
        val lines = event.lines()
        val firstLine = plain.serialize(lines[0]).trim()
        if (!firstLine.equals(config.signs.triggerToken, ignoreCase = true)) return

        val player = event.player
        if (!player.hasPermission("enthusiamarket.sign.create")) {
            player.sendMessage(lang.msg("purchase_sign.msg.no_permission"))
            event.isCancelled = true
            return
        }

        val stallName = plain
            .serialize(lines.getOrElse(1) { net.kyori.adventure.text.Component.empty() })
            .trim()
        if (stallName.isBlank()) {
            player.sendMessage(lang.msg("purchase_sign.msg.invalid_stall", "stall" to ""))
            event.isCancelled = true
            return
        }

        val priceRaw = plain
            .serialize(lines.getOrElse(2) { net.kyori.adventure.text.Component.empty() })
            .trim()
        val price = priceRaw.toLongOrNull()
        if (price == null || price <= 0) {
            player.sendMessage(lang.msg("purchase_sign.msg.invalid_price", "price" to priceRaw))
            event.isCancelled = true
            return
        }

        val stall = stalls.findById(StallId(stallName))
        if (stall == null) {
            player.sendMessage(lang.msg("purchase_sign.msg.invalid_stall", "stall" to stallName))
            event.isCancelled = true
            return
        }

        val block = event.block
        val sign = PurchaseSign(
            stallId = stall.id,
            world = block.world.name,
            x = block.x, y = block.y, z = block.z,
            price = price,
        )
        signs.save(sign)

        val rendered = renderer.render(sign)
        for (i in 0 until 4) {
            event.line(i, rendered.getOrElse(i) { net.kyori.adventure.text.Component.empty() })
        }
        player.sendMessage(
            lang.msg("purchase_sign.msg.created", "stall" to stallName, "price" to price)
        )
    }
}
