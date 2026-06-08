package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.events.GuildTradePolicyChangedEvent
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GuildPolicyAnnounceListenerTest {
    private fun listener(lang: LangService, gp: GuildProvider, cfg: EnthusiaMarketConfig = EnthusiaMarketConfig()) =
        GuildPolicyAnnounceListener(gp, lang, cfg)

    private fun lang(): LangService = mockk {
        every { msg(any(), *anyVararg()) } answers { Component.text(firstArg<String>()) } // echo the key for assertion
    }
    private fun gp(): GuildProvider = mockk {
        every { guildById("g1") } returns GuildProvider.GuildRef("g1", "Alpha")
        every { guildById("g2") } returns GuildProvider.GuildRef("g2", "Beta")
    }

    @Test fun `tariff set builds the tariff announce`() {
        val c = listener(lang(), gp()).buildMessage(GuildTradePolicyChangedEvent("g1","g2",PolicyKind.TARIFF,20,GuildTradePolicyChangedEvent.Action.SET))
        assertEquals("guildpolicy.announce.tariff", (c as Component).let { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) })
    }
    @Test fun `embargo set builds the embargo announce`() {
        val c = listener(lang(), gp()).buildMessage(GuildTradePolicyChangedEvent("g1","g2",PolicyKind.EMBARGO,0,GuildTradePolicyChangedEvent.Action.SET))!!
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).contains("embargo"))
    }
    @Test fun `cleared builds the cleared announce`() {
        val c = listener(lang(), gp()).buildMessage(GuildTradePolicyChangedEvent("g1","g2",null,0,GuildTradePolicyChangedEvent.Action.CLEARED))!!
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).contains("cleared"))
    }
    @Test fun `cooldown suppresses a second announce within the window`() {
        val l = listener(lang(), gp())
        assertFalse(l.onCooldown("g1", now = 1_000_000L))           // first → allowed (records)
        assertTrue(l.onCooldown("g1", now = 1_000_000L + 5_000L))   // 5s later, default 30s window → suppressed
        assertFalse(l.onCooldown("g1", now = 1_000_000L + 40_000L)) // 40s later → allowed again
    }
}
