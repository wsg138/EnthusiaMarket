package net.badgersmc.em.interaction.gui

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GuildTradePolicyMenuTest {
    @Test fun `step up adds 5 capped at MAX`() {
        assertEquals(20, GuildTradePolicyMenu.stepUp(15))
        assertEquals(99, GuildTradePolicyMenu.stepUp(97))   // cap
    }
    @Test fun `step down subtracts 5 floored at 5`() {
        assertEquals(10, GuildTradePolicyMenu.stepDown(15))
        assertEquals(5, GuildTradePolicyMenu.stepDown(5))   // floor
    }
    @Test fun `menu constructs without throwing`() {
        val menu = GuildTradePolicyMenu(
            actor = java.util.UUID.randomUUID(), ownerGuildId = "g1",
            policyService = mockk(relaxed = true), guildProvider = mockk(relaxed = true), lang = mockk(relaxed = true),
        )
        assertNotNull(menu)
    }
}
