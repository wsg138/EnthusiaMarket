package net.badgersmc.em.interaction

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopInfoCardTest {

    @Test fun `builds four lines from shop data`() {
        val lang = mockk<LangService>()
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        val lines = ShopInfoCard.lines(
            lang, direction = "SELL", item = "diamond", qty = 5, price = 100L, owner = "Steve", stock = 8,
        )
        assertEquals(4, lines.size)
    }
}
