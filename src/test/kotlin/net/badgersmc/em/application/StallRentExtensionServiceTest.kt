package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StallRentExtensionServiceTest {

    private val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val stallId = StallId("stall_01")
    private val guildId = "guild-1111"
    private val now = Instant.parse("2026-05-24T10:00:00Z")

    // Flat 50-coin rent on a 1000-coin stall -> dailyRent = 50L, amount = max(50, 1) = 50L
    private val expectedAmount = 50L

    private val soloStall = Stall(
        id = stallId,
        regionId = "stall_01",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(playerUuid),
        ownerSince = now.minus(Duration.ofDays(5)),
        winningBid = 1000L,
        rentTerms = RentTerms.flat(50L)
    )

    private val guildStall = Stall(
        id = stallId,
        regionId = "stall_01",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.guild(guildId),
        ownerSince = now.minus(Duration.ofDays(5)),
        winningBid = 1000L,
        rentTerms = RentTerms.flat(50L)
    )

    private fun config(): EnthusiaMarketConfig {
        val cfg = EnthusiaMarketConfig()
        cfg.rent.mode = "flat"
        cfg.rent.formulaPct = 1.0
        cfg.rent.flatAmount = 50L
        cfg.rent.collectionInterval = "P1D"
        cfg.rent.gracePeriod = "P3D"
        return cfg
    }

    private data class ServiceWithMocks(
        val service: StallRentExtensionService,
        val stallRepo: StallRepository,
        val economy: EconomyProvider,
        val guildProvider: GuildProvider,
        val config: EnthusiaMarketConfig
    )

    /**
     * Build a [StallRentExtensionService] with mocked dependencies.
     *
     * The four flags control the happy-path branches the production code
     * exercises for `canManage`, `bankWithdraw`, `economy.withdraw` and
     * `stalls.save`. Default values match the SOLO success case.
     */
    private fun buildService(
        stall: Stall = soloStall,
        canManageGuild: Boolean = true,
        bankWithdrawOk: Boolean = true,
        economyWithdrawOk: Boolean = true
    ): ServiceWithMocks {
        val stallRepo = mockStallRepo(stall)
        val economy = mockk<EconomyProvider>()
        every { economy.withdraw(any(), any()) } returns economyWithdrawOk
        val guildProvider = mockGuildProvider(canManageGuild, bankWithdrawOk)
        val cfg = config()
        return ServiceWithMocks(
            service = StallRentExtensionService(stallRepo, economy, guildProvider, cfg),
            stallRepo = stallRepo,
            economy = economy,
            guildProvider = guildProvider,
            config = cfg
        )
    }

    private fun mockStallRepo(stall: Stall): StallRepository {
        val stallRepo = mockk<StallRepository>(relaxUnitFun = true)
        every { stallRepo.findById(stallId) } returns stall
        every { stallRepo.save(any()) } returns Unit
        return stallRepo
    }

    private fun mockGuildProvider(canManageGuild: Boolean, bankWithdrawOk: Boolean): GuildProvider {
        val guildProvider = mockk<GuildProvider>(relaxed = true)
        // canManage for a GUILD owner needs both isMember + MANAGE_SHOPS to be true.
        every { guildProvider.isMember(playerUuid, guildId) } returns canManageGuild
        every {
            guildProvider.hasShopPermission(playerUuid, guildId, GuildProvider.GuildPermission.MANAGE_SHOPS)
        } returns canManageGuild
        every { guildProvider.bankWithdraw(any(), any()) } returns bankWithdrawOk
        return guildProvider
    }

    // --- extend: guild stall draws from the guild bank ---

    @Test
    fun `extend guild stall draws the guild bank`() {
        val svc = buildService(stall = guildStall, canManageGuild = true, bankWithdrawOk = true)

        val result = svc.service.extend(stallId, playerUuid)

        val extended = assertIs<StallRentExtensionService.Result.Extended>(result)
        assertEquals(expectedAmount, extended.amountPaid)

        // Guild bank was charged exactly once for the rent amount.
        verify(exactly = 1) { svc.guildProvider.bankWithdraw(guildId, expectedAmount) }

        // The actor's personal economy must NOT be touched for guild stalls.
        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify(exactly = 0) { svc.guildProvider.bankDeposit(any(), any()) }

        // The stall was persisted with a pushed timer.
        verify { svc.stallRepo.save(match { it.state == StallState.OWNED && it.nextRentAt != null }) }
    }

    // --- extend: guild stall with insufficient bank is rejected ---

    @Test
    fun `extend guild stall with insufficient bank is rejected`() {
        val svc = buildService(stall = guildStall, canManageGuild = true, bankWithdrawOk = false)

        val result = svc.service.extend(stallId, playerUuid)

        val rejected = assertIs<StallRentExtensionService.Result.Rejected>(result)
        assertTrue(
            rejected.reason.contains("guild bank has insufficient funds"),
            "Expected reject reason to mention guild bank insufficient funds, got: ${rejected.reason}"
        )

        // Bank was tried (and failed) — actor's wallet was untouched.
        verify(exactly = 1) { svc.guildProvider.bankWithdraw(guildId, expectedAmount) }
        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }

        // Nothing was persisted on a rejection.
        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }

    // --- extend: solo stall still uses the personal economy (no regression) ---

    @Test
    fun `extend solo stall still uses personal economy`() {
        val svc = buildService(stall = soloStall, economyWithdrawOk = true)

        val result = svc.service.extend(stallId, playerUuid)

        val extended = assertIs<StallRentExtensionService.Result.Extended>(result)
        assertEquals(expectedAmount, extended.amountPaid)

        // Actor's wallet was charged.
        verify(exactly = 1) { svc.economy.withdraw(playerUuid, expectedAmount) }

        // Guild bank must NOT be touched for solo stalls.
        verify(exactly = 0) { svc.guildProvider.bankWithdraw(any(), any()) }
        verify(exactly = 0) { svc.guildProvider.bankDeposit(any(), any()) }

        // The stall was persisted with a pushed timer.
        verify { svc.stallRepo.save(match { it.state == StallState.OWNED && it.nextRentAt != null }) }
    }
}
