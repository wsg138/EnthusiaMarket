package net.badgersmc.em.domain.stall

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.ports.GuildProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StallTest {

    companion object {
        private const val MANAGE_PERM = "manage"
        private const val TEST_GUILD = "guild_01"
        private const val TEST_STALL = "stall_01"
    }
    private val baseStall = Stall(
        id = StallId(TEST_STALL),
        regionId = TEST_STALL,
        world = "world",
        state = StallState.UNOWNED,
        owner = OwnerRef.unowned(),
        ownerSince = null,
        winningBid = 0L,
        rentTerms = RentTerms.formula(1.0)
    )

    @Test fun `a fresh stall is UNOWNED with no owner`() {
        assertEquals(StallState.UNOWNED, baseStall.state)
        assertEquals(OwnerType.NONE, baseStall.owner.type)
        assertEquals(0L, baseStall.winningBid)
    }

    @Test fun `awarding a stall transitions to OWNED with bid and timestamp`() {
        val now = Instant.parse("2026-05-15T10:00:00Z")
        val owner = OwnerRef.solo(UUID.randomUUID())
        val awarded = baseStall.awardTo(owner, winningBid = 1000L, at = now)
        assertEquals(StallState.OWNED, awarded.state)
        assertEquals(owner, awarded.owner)
        assertEquals(1000L, awarded.winningBid)
        assertEquals(now, awarded.ownerSince)
    }

    @Test fun `awarding requires a non-unowned owner`() {
        assertFailsWith<IllegalArgumentException> {
            baseStall.awardTo(OwnerRef.unowned(), winningBid = 1L, at = Instant.now())
        }
    }

    @Test fun `awarding requires a positive winning bid`() {
        assertFailsWith<IllegalArgumentException> {
            baseStall.awardTo(OwnerRef.solo(UUID.randomUUID()), winningBid = 0L, at = Instant.now())
        }
    }

    // --- canManage tests ---

    @Test fun `solo owner can manage own stall`() {
        val ownerUuid = UUID.randomUUID()
        val stall = baseStall.copy(owner = OwnerRef.solo(ownerUuid))
        val guildProvider = mockk<GuildProvider>(relaxed = true)

        val result = stall.canManage(ownerUuid, guildProvider, MANAGE_PERM)

        assertTrue(result)
    }

    @Test fun `solo owner cannot manage another player's stall`() {
        val ownerUuid = UUID.randomUUID()
        val otherUuid = UUID.randomUUID()
        val stall = baseStall.copy(owner = OwnerRef.solo(ownerUuid))
        val guildProvider = mockk<GuildProvider>(relaxed = true)

        val result = stall.canManage(otherUuid, guildProvider, MANAGE_PERM)

        assertFalse(result)
    }

    @Test fun `guild member with required rank can manage stall`() {
        val playerUuid = UUID.randomUUID()
        val guildId = TEST_GUILD
        val stall = baseStall.copy(owner = OwnerRef.guild(guildId))
        val guildProvider = mockk<GuildProvider>()

        every { guildProvider.isMember(playerUuid, guildId) } returns true
        every { guildProvider.hasPermission(playerUuid, guildId, MANAGE_PERM) } returns true

        val result = stall.canManage(playerUuid, guildProvider, MANAGE_PERM)

        assertTrue(result)
        verify { guildProvider.isMember(playerUuid, guildId) }
        verify { guildProvider.hasPermission(playerUuid, guildId, MANAGE_PERM) }
    }

    @Test fun `guild member with insufficient rank cannot manage stall`() {
        val playerUuid = UUID.randomUUID()
        val guildId = TEST_GUILD
        val stall = baseStall.copy(owner = OwnerRef.guild(guildId))
        val guildProvider = mockk<GuildProvider>()

        every { guildProvider.isMember(playerUuid, guildId) } returns true
        every { guildProvider.hasPermission(playerUuid, guildId, MANAGE_PERM) } returns false

        val result = stall.canManage(playerUuid, guildProvider, MANAGE_PERM)

        assertFalse(result)
        verify { guildProvider.isMember(playerUuid, guildId) }
        verify { guildProvider.hasPermission(playerUuid, guildId, MANAGE_PERM) }
    }

    @Test fun `non-member cannot manage guild stall`() {
        val playerUuid = UUID.randomUUID()
        val guildId = TEST_GUILD
        val stall = baseStall.copy(owner = OwnerRef.guild(guildId))
        val guildProvider = mockk<GuildProvider>()

        every { guildProvider.isMember(playerUuid, guildId) } returns false

        val result = stall.canManage(playerUuid, guildProvider, MANAGE_PERM)

        assertFalse(result)
        verify { guildProvider.isMember(playerUuid, guildId) }
        verify(exactly = 0) { guildProvider.hasPermission(any(), any(), any()) }
    }

    @Test fun `unowned stall cannot be managed`() {
        val playerUuid = UUID.randomUUID()
        val guildProvider = mockk<GuildProvider>(relaxed = true)

        val result = baseStall.canManage(playerUuid, guildProvider, MANAGE_PERM)

        assertFalse(result)
    }
}