package net.badgersmc.em.interaction.gui

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.ports.GuildProvider
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GuildPickerMenuTest {
    @Test fun `selectable excludes own guild and already-policied targets`() {
        val all = listOf(GuildProvider.GuildRef("g1","Self"), GuildProvider.GuildRef("g2","Beta"), GuildProvider.GuildRef("g3","Gamma"))
        val out = GuildPickerMenu.selectable(all, ownerGuildId = "g1", existingTargets = setOf("g2"))
        assertEquals(listOf("g3"), out.map { it.id })
    }
    @Test fun `menu constructs without throwing`() {
        val gp = mockk<GuildProvider>(relaxed = true); every { gp.listGuilds() } returns emptyList()
        val ps = mockk<GuildTradePolicyService>(relaxed = true); every { ps.list("g1") } returns emptyList()
        assertNotNull(GuildPickerMenu(UUID.randomUUID(), "g1", ps, gp, mockk(relaxed = true)))
    }
}
