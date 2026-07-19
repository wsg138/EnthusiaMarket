package net.badgersmc.em.interaction.bedrock

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.ContainerTradeResult
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class BedrockPurchaseFormTest {
    private fun shop(direction: SignDirection) = Shop(
        id = 1L, stallId = "s1", owner = UUID.randomUUID(), direction = direction,
        signWorld = "w", signX = 1, signY = 2, signZ = 3,
        containerWorld = "w", containerX = 4, containerY = 5, containerZ = 6,
        sellItem = "diamond", sellAmount = 1, costItem = "10", costAmount = 10,
    )

    @Test fun `directions use player-facing action labels`() {
        assertEquals("bedrock.purchase.button_buy", BedrockPurchaseForm.actionButtonKey(SignDirection.SELL))
        assertEquals("bedrock.purchase.button_sell", BedrockPurchaseForm.actionButtonKey(SignDirection.BUY))
        assertEquals("bedrock.purchase.button_trade", BedrockPurchaseForm.actionButtonKey(SignDirection.TRADE))
    }

    @Test fun `action executes exactly once while back and invalid indexes do nothing`() {
        var calls = 0
        val form = form { calls++; ContainerTradeResult.Success("done") }
        form.handleButton(1)
        form.handleButton(99)
        assertEquals(0, calls)
        form.handleButton(0)
        assertEquals(1, calls)
    }

    @Test fun `all result details are reported`() {
        val player = mockk<Player>(relaxed = true)
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } returns mockk(relaxed = true)
        val results = listOf(
            ContainerTradeResult.Success("done"),
            ContainerTradeResult.Failure("bad"),
            ContainerTradeResult.CompensationFailed("error", "manual repair"),
        )
        results.forEach { result -> form(player, lang) { result }.handleButton(0) }
        verify { lang.msg("shop.trade.success", "message" to "done") }
        verify { lang.msg("shop.trade.failure", "reason" to "bad") }
        verify { lang.msg("shop.trade.compensation_failed", "error" to "error") }
        verify { lang.msg("shop.trade.compensation_note", "compensation" to "manual repair") }
        verify(exactly = 4) { player.sendMessage(any<net.kyori.adventure.text.Component>()) }
    }

    private fun form(onConfirm: () -> ContainerTradeResult) =
        form(mockk(relaxed = true), mockk(relaxed = true), onConfirm)

    private fun form(player: Player, lang: LangService, onConfirm: () -> ContainerTradeResult) =
        BedrockPurchaseForm(player, shop(SignDirection.SELL), onConfirm, mockk<Logger>(relaxed = true), lang)
}
