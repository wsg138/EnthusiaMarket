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

        val result = stall.canManage(ownerUuid, guildProvider)

        assertTrue(result)
    }

    @Test fun `solo owner cannot manage another player's stall`() {
        val ownerUuid = UUID.randomUUID()
        val otherUuid = UUID.randomUUID()
        val stall = baseStall.copy(owner = OwnerRef.solo(ownerUuid))
        val guildProvider = mockk<GuildProvider>(relaxed = true)

        val result = stall.canManage(otherUuid, guildProvider)

        assertFalse(result)
    }

    @Test fun `guild member with MANAGE_SHOPS permission can manage stall`() {
        val playerUuid = UUID.randomUUID()
        val guildId = TEST_GUILD
        val stall = baseStall.copy(owner = OwnerRef.guild(guildId))
        val guildProvider = mockk<GuildProvider>()

        every { guildProvider.isMember(playerUuid, guildId) } returns true
        every {
            guildProvider.hasShopPermission(playerUuid, guildId, GuildProvider.GuildPermission.MANAGE_SHOPS)
        } returns true

        val result = stall.canManage(playerUuid, guildProvider)

        assertTrue(result)
        verify { guildProvider.isMember(playerUuid, guildId) }
        verify {
            guildProvider.hasShopPermission(playerUuid, guildId, GuildProvider.GuildPermission.MANAGE_SHOPS)
        }
    }

    @Test fun `guild member without MANAGE_SHOPS permission cannot manage stall`() {
        val playerUuid = UUID.randomUUID()
        val guildId = TEST_GUILD
        val stall = baseStall.copy(owner = OwnerRef.guild(guildId))
        val guildProvider = mockk<GuildProvider>()

        every { guildProvider.isMember(playerUuid, guildId) } returns true
        every {
            guildProvider.hasShopPermission(playerUuid, guildId, GuildProvider.GuildPermission.MANAGE_SHOPS)
        } returns false

        val result = stall.canManage(playerUuid, guildProvider)

        assertFalse(result)
        verify { guildProvider.isMember(playerUuid, guildId) }
        verify {
            guildProvider.hasShopPermission(playerUuid, guildId, GuildProvider.GuildPermission.MANAGE_SHOPS)
        }
    }

    @Test fun `non-member cannot manage guild stall`() {
        val playerUuid = UUID.randomUUID()
        val guildId = TEST_GUILD
        val stall = baseStall.copy(owner = OwnerRef.guild(guildId))
        val guildProvider = mockk<GuildProvider>()

        every { guildProvider.isMember(playerUuid, guildId) } returns false

        val result = stall.canManage(playerUuid, guildProvider)

        assertFalse(result)
        verify { guildProvider.isMember(playerUuid, guildId) }
        verify(exactly = 0) { guildProvider.hasShopPermission(any(), any(), any()) }
    }

    @Test fun `unowned stall cannot be managed`() {
        val playerUuid = UUID.randomUUID()
        val guildProvider = mockk<GuildProvider>(relaxed = true)

        val result = baseStall.canManage(playerUuid, guildProvider)

        assertFalse(result)
    }

    // --- member roster tests (REQ-200, REQ-201) ---

    @Test fun `a fresh stall has an empty member roster and unlimited cap`() {
        assertTrue(baseStall.members.isEmpty())
        assertEquals(-1, baseStall.maxMembers)
    }

    @Test fun `addMember adds a uuid to the roster`() {
        val player = UUID.randomUUID()
        val updated = baseStall.addMember(player)
        assertTrue(player in updated.members)
        assertEquals(1, updated.members.size)
    }

    @Test fun `addMember is idempotent when the player is already a member`() {
        val player = UUID.randomUUID()
        val once = baseStall.addMember(player)
        val twice = once.addMember(player)
        assertEquals(1, twice.members.size)
    }

    @Test fun `removeMember drops a uuid from the roster`() {
        val player = UUID.randomUUID()
        val stall = baseStall.addMember(player)
        val updated = stall.removeMember(player)
        assertFalse(player in updated.members)
        assertTrue(updated.members.isEmpty())
    }

    @Test fun `removeMember is a no-op when the player is not a member`() {
        val player = UUID.randomUUID()
        val updated = baseStall.removeMember(player)
        assertTrue(updated.members.isEmpty())
    }

    @Test fun `addMember rejects when the roster is at its configured cap`() {
        val capped = baseStall.copy(maxMembers = 2)
            .addMember(UUID.randomUUID())
            .addMember(UUID.randomUUID())
        assertFailsWith<IllegalStateException> {
            capped.addMember(UUID.randomUUID())
        }
    }

    @Test fun `addMember allows unlimited members when maxMembers is negative`() {
        var stall = baseStall // maxMembers = -1 by default
        repeat(50) { stall = stall.addMember(UUID.randomUUID()) }
        assertEquals(50, stall.members.size)
    }

    @Test fun `addMember at cap still accepts a uuid already in the roster (idempotent)`() {
        val player = UUID.randomUUID()
        val capped = baseStall.copy(maxMembers = 1).addMember(player)
        // Re-adding the same player must not throw — capacity already accounts for them.
        val result = capped.addMember(player)
        assertEquals(1, result.members.size)
    }
}
