package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GuildTradePolicyServiceTest {
    private val buyer = UUID.randomUUID()
    private fun svc(repo: GuildTradePolicyRepository, gp: GuildProvider) = GuildTradePolicyService(repo, gp)

    // Mock GuildRef rather than construct it — GuildRef may carry extra fields (tag/emoji);
    // we only need `.id`.
    private fun gp(buyerGuild: String?): GuildProvider = mockk(relaxed = true) {
        every { guildOf(buyer) } returns buyerGuild?.let { gid ->
            mockk<GuildProvider.GuildRef> { every { id } returns gid }
        }
    }

    @Test fun `solo buyer is allowed at factor 1`() {
        val s = svc(mockk(relaxed = true), gp(null)).stanceFor("g1", buyer, SignDirection.SELL)
        assertEquals(1.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(s).factor)
    }
    @Test fun `own-guild buyer is allowed at factor 1`() {
        val s = svc(mockk(relaxed = true), gp("g1")).stanceFor("g1", buyer, SignDirection.SELL)
        assertEquals(1.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(s).factor)
    }
    @Test fun `no policy is allowed at factor 1`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns null }
        val s = svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.SELL)
        assertEquals(1.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(s).factor)
    }
    @Test fun `embargo blocks`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.EMBARGO,0) }
        assertIs<GuildTradePolicyService.TradeStance.Embargoed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.SELL))
    }
    @Test fun `tariff raises a SELL shop`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.TARIFF,20) }
        assertEquals(1.2, assertIs<GuildTradePolicyService.TradeStance.Allowed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.SELL)).factor, 1e-9)
    }
    @Test fun `tariff cuts a BUY shop`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.TARIFF,20) }
        assertEquals(0.8, assertIs<GuildTradePolicyService.TradeStance.Allowed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.BUY)).factor, 1e-9)
    }
    @Test fun `BUY tariff over 100 floors at 0`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.TARIFF,150) }
        assertEquals(0.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.BUY)).factor, 1e-9)
    }
    @Test fun `setTariff persists when actor has MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true)
        every { gpm.hasShopPermission(buyer, "g1", GuildProvider.GuildPermission.MANAGE_SHOPS) } returns true
        val r = GuildTradePolicyService(repo, gpm).setTariff(buyer, "g1", "g2", 30)
        assertIs<GuildTradePolicyService.PolicyResult.Ok>(r)
        io.mockk.verify { repo.upsert(match { it.ownerGuildId=="g1" && it.targetGuildId=="g2" && it.kind==PolicyKind.TARIFF && it.ratePct==30 }) }
    }
    @Test fun `setTariff denied without MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true)
        every { gpm.hasShopPermission(buyer, "g1", any()) } returns false
        assertIs<GuildTradePolicyService.PolicyResult.Denied>(GuildTradePolicyService(repo, gpm).setTariff(buyer, "g1", "g2", 30))
        io.mockk.verify(exactly = 0) { repo.upsert(any()) }
    }
    @Test fun `self-target is rejected`() {
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        assertIs<GuildTradePolicyService.PolicyResult.Invalid>(GuildTradePolicyService(mockk(relaxed=true), gpm).setTariff(buyer, "g1", "g1", 10))
    }
    @Test fun `rate out of range is rejected`() {
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        val svc = GuildTradePolicyService(mockk(relaxed = true), gpm)
        assertIs<GuildTradePolicyService.PolicyResult.Invalid>(svc.setTariff(buyer, "g1", "g2", 2000))
        assertIs<GuildTradePolicyService.PolicyResult.Invalid>(svc.setTariff(buyer, "g1", "g2", -1))
    }
    @Test fun `tariff of 100 percent or more is rejected (confiscation guard)`() {
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        assertIs<GuildTradePolicyService.PolicyResult.Invalid>(GuildTradePolicyService(repo, gpm).setTariff(buyer, "g1", "g2", 100))
        io.mockk.verify(exactly = 0) { repo.upsert(any()) }
    }
    @Test fun `setEmbargo persists when actor has MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        assertIs<GuildTradePolicyService.PolicyResult.Ok>(GuildTradePolicyService(repo, gpm).setEmbargo(buyer, "g1", "g2"))
        io.mockk.verify { repo.upsert(match { it.kind == PolicyKind.EMBARGO && it.targetGuildId == "g2" }) }
    }
    @Test fun `setEmbargo denied without MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns false
        assertIs<GuildTradePolicyService.PolicyResult.Denied>(GuildTradePolicyService(repo, gpm).setEmbargo(buyer, "g1", "g2"))
        io.mockk.verify(exactly = 0) { repo.upsert(any()) }
    }
    @Test fun `clear deletes when actor has MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        assertIs<GuildTradePolicyService.PolicyResult.Ok>(GuildTradePolicyService(repo, gpm).clear(buyer, "g1", "g2"))
        io.mockk.verify { repo.delete("g1", "g2") }
    }
    @Test fun `clear denied without MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns false
        assertIs<GuildTradePolicyService.PolicyResult.Denied>(GuildTradePolicyService(repo, gpm).clear(buyer, "g1", "g2"))
        io.mockk.verify(exactly = 0) { repo.delete(any(), any()) }
    }
    @Test fun `list delegates to the repository`() {
        val repo = mockk<GuildTradePolicyRepository> {
            every { listByOwner("g1") } returns listOf(GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 10))
        }
        val out = GuildTradePolicyService(repo, mockk(relaxed = true)).list("g1")
        assertEquals(1, out.size); assertEquals("g2", out[0].targetGuildId)
    }
}
