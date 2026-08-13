package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.PurchaseSignRenderer
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent

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
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class PurchaseSignCreateListener(
    private val stalls: StallRepository,
    private val signs: PurchaseSignRepository,
    private val renderer: PurchaseSignRenderer,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) : Listener {
    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(ignoreCancelled = true)
    fun onSignPlace(event: SignChangeEvent) {
        val lines = event.lines()
        val firstLine = plain.serialize(lines[0]).trim()
        if (!firstLine.equals(config.signs.triggerToken, ignoreCase = true)) return

        val player = event.player
        if (!player.hasPermission("enthusiamarket.sign.create")) {
            player.sendMessage(lang.msg("purchase_sign.msg.no_permission"))
            event.isCancelled = true
            return
        }

        val input = parse(event) ?: return

        val stall = stalls.findById(StallId(input.stallName))
        if (stall == null) {
            player.sendMessage(lang.msg("purchase_sign.msg.invalid_stall", "stall" to input.stallName))
            event.isCancelled = true
            return
        }

        val block = event.block
        val worldName = block.world.name

        // SignChangeEvent fires on both placement AND re-edit. If a
        // PurchaseSign already exists at this block, the player is
        // re-editing an existing sign. Non-admins must destroy the
        // existing sign first (the existing delete flow handles
        // un-registering). Admins (enthusiamarket.sign.admin) can
        // overwrite freely for repair / migration scenarios.
        if (signs.findAt(worldName, block.x, block.y, block.z) != null &&
            !player.hasPermission("enthusiamarket.sign.admin")
        ) {
            player.sendMessage(lang.msg("purchase_sign.msg.already_exists"))
            event.isCancelled = true
            return
        }

        save(event, stall.id, input)
    }

    private fun parse(event: SignChangeEvent): ParsedSign? {
        val lines = event.lines()
        val stallName = plain
            .serialize(lines.getOrElse(1) { net.kyori.adventure.text.Component.empty() })
            .trim()
        if (stallName.isBlank()) {
            event.player.sendMessage(lang.msg("purchase_sign.msg.invalid_stall", "stall" to ""))
            event.isCancelled = true
            return null
        }
        val priceText = plain
            .serialize(lines.getOrElse(2) { net.kyori.adventure.text.Component.empty() })
            .trim()
        val price = priceText.toLongOrNull()
        if (price == null || price <= 0) {
            event.player.sendMessage(lang.msg("purchase_sign.msg.invalid_price", "price" to priceText))
            event.isCancelled = true
            return null
        }
        return ParsedSign(stallName, price)
    }

    private fun save(event: SignChangeEvent, stallId: StallId, input: ParsedSign) {
        val block = event.block
        val sign = PurchaseSign(
            stallId = stallId,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            price = input.price,
        )
        signs.save(sign)
        val rendered = renderer.render(sign)
        repeat(SIGN_LINES) { index ->
            event.line(index, rendered.getOrElse(index) { net.kyori.adventure.text.Component.empty() })
        }
        event.player.sendMessage(
            lang.msg("purchase_sign.msg.created", "stall" to input.stallName, "price" to input.price)
        )
    }

    private data class ParsedSign(val stallName: String, val price: Long)

    private companion object {
        const val SIGN_LINES = 4
    }
}
