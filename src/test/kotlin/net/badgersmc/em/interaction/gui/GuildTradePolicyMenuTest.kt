package net.badgersmc.em.interaction.gui

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.nexus.i18n.LangService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import java.util.UUID

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
            actor = UUID.randomUUID(), ownerGuildId = "g1",
            policyService = mockk(relaxed = true), guildProvider = mockk(relaxed = true), lang = mockk(relaxed = true),
        )
        assertNotNull(menu)
    }

    @Test fun `right-click at min tariff calls clear instead of setTariff`() {
        val actor = UUID.randomUUID()
        val ownerGuildId = "guild_01"
        val targetGuildId = "guild_02"
        val policyService = mockk<GuildTradePolicyService>()
        val guildProvider = mockk<GuildProvider>()
        val lang = mockk<LangService>(relaxed = true)

        every { guildProvider.guildById(targetGuildId) } returns GuildProvider.GuildRef(targetGuildId, "Target")
        every { policyService.clear(actor, ownerGuildId, targetGuildId) } returns GuildTradePolicyService.PolicyResult.Ok

        val menu = GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang)
        val policy = GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct = 5)

        // Right-click (left=false, shift=false) at 5% = MIN_TARIFF_PCT → should call clear
        val result = menu.applyClick(policy, left = false, shift = false)
        assertIs<GuildTradePolicyService.PolicyResult.Ok>(result)
        verify(exactly = 1) { policyService.clear(actor, ownerGuildId, targetGuildId) }
        verify(exactly = 0) { policyService.setTariff(any(), any(), any(), any()) }
    }

    @Test fun `right-click above min tariff calls setTariff with stepDown`() {
        val actor = UUID.randomUUID()
        val ownerGuildId = "guild_01"
        val targetGuildId = "guild_02"
        val policyService = mockk<GuildTradePolicyService>()
        val guildProvider = mockk<GuildProvider>()
        val lang = mockk<LangService>(relaxed = true)

        every { guildProvider.guildById(targetGuildId) } returns GuildProvider.GuildRef(targetGuildId, "Target")
        every { policyService.setTariff(actor, ownerGuildId, targetGuildId, 10) } returns GuildTradePolicyService.PolicyResult.Ok

        val menu = GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang)
        val policy = GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct = 15)

        // Right-click (left=false, shift=false) at 15% → stepDown to 10% → setTariff
        menu.applyClick(policy, left = false, shift = false)
        verify(exactly = 1) { policyService.setTariff(actor, ownerGuildId, targetGuildId, 10) }
        verify(exactly = 0) { policyService.clear(any(), any(), any()) }
    }
}
