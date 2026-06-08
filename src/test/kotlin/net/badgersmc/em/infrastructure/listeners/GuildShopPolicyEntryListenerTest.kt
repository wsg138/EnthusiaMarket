package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuildShopPolicyEntryListenerTest {
    private val player = UUID.randomUUID()
    private fun plain(c: Component) = PlainTextComponentSerializer.plainText().serialize(c)

    private fun guildStall() = Stall(
        id = StallId("s1"), regionId = "s1", world = "world", state = StallState.OWNED,
        owner = OwnerRef.guild("g1"), ownerSince = Instant.now(), winningBid = 100L, rentTerms = RentTerms.flat(1L),
    )
    private fun soloStall() = guildStall().copy(owner = OwnerRef.solo(UUID.randomUUID()))

    private fun lang(): LangService = mockk { every { msg(any(), *anyVararg()) } answers { Component.text(firstArg<String>()) } }
    private fun build(stall: Stall?, policy: GuildTradePolicy?): GuildShopPolicyEntryListener {
        val regions = mockk<RegionProvider>(relaxed = true)
        val stalls = mockk<StallRepository> { every { findByRegion("world", "s1") } returns stall }
        val policySvc = mockk<GuildTradePolicyService> { every { policyToward("g1", player) } returns policy }
        val gp = mockk<GuildProvider> { every { guildById("g1") } returns GuildProvider.GuildRef("g1", "Alpha") }
        return GuildShopPolicyEntryListener(regions, stalls, policySvc, gp, lang(), EnthusiaMarketConfig())
    }

    @Test fun `tariff at a guild stall warns`() {
        val c = build(guildStall(), GuildTradePolicy("g1","g2",PolicyKind.TARIFF,20)).warningFor("world", "s1", player)!!
        assertTrue(plain(c).contains("tariff"))
    }
    @Test fun `embargo warns`() {
        val c = build(guildStall(), GuildTradePolicy("g1","g2",PolicyKind.EMBARGO,0)).warningFor("world", "s1", player)!!
        assertTrue(plain(c).contains("embargo"))
    }
    @Test fun `no policy is silent`() { assertNull(build(guildStall(), null).warningFor("world", "s1", player)) }
    @Test fun `solo stall is silent`() { assertNull(build(soloStall(), null).warningFor("world", "s1", player)) }
    @Test fun `unknown region is silent`() { assertNull(build(null, null).warningFor("world", "s1", player)) }
}
