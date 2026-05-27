package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.confirmVerified
import net.badgersmc.em.application.StallMemberService.Result
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StallMemberServiceTest {

    private val owner = UUID.randomUUID()
    private val stallId = StallId("s1")

    private fun ownedStall() = Stall(
        id = stallId,
        regionId = "s1",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(owner),
        ownerSince = null,
        winningBid = 100L,
        rentTerms = RentTerms.formula(1.0),
    )

    @Test fun `addMember by owner mutates roster, mirrors to WG, and persists`() {
        val target = UUID.randomUUID()
        val stalls = mockk<StallRepository>(relaxed = true)
        val sync = mockk<RegionMemberSync>(relaxed = true)
        every { stalls.findById(stallId) } returns ownedStall()

        val service = StallMemberService(stalls, sync, mockk(relaxed = true))
        val result = service.addMember(stallId, owner, target)

        assertIs<Result.Success>(result)
        assertTrue(target in result.stall.members)
        verify(exactly = 1) { sync.addMember("world", "s1", target) }
        verify(exactly = 1) {
            stalls.save(match { it.id == stallId && target in it.members })
        }
    }

    @Test fun `addMember by non-owner is NotAuthorised — repo and sync untouched`() {
        val intruder = UUID.randomUUID()
        val target = UUID.randomUUID()
        val stalls = mockk<StallRepository>()
        val sync = mockk<RegionMemberSync>()
        val guilds = mockk<GuildProvider>(relaxed = true)
        every { stalls.findById(stallId) } returns ownedStall()

        val service = StallMemberService(stalls, sync, guilds)
        val result = service.addMember(stallId, intruder, target)

        assertEquals(Result.NotAuthorised, result)
        verify(exactly = 0) { stalls.save(any()) }
        verify(exactly = 0) { sync.addMember(any(), any(), any()) }
    }

    @Test fun `addMember on missing stall is NotFound`() {
        val stalls = mockk<StallRepository>()
        val sync = mockk<RegionMemberSync>()
        every { stalls.findById(stallId) } returns null

        val service = StallMemberService(stalls, sync, mockk(relaxed = true))
        val result = service.addMember(stallId, owner, UUID.randomUUID())

        assertEquals(Result.NotFound, result)
        verify(exactly = 0) { sync.addMember(any(), any(), any()) }
    }

    @Test fun `addMember at cap returns Rejected and does not mirror to WG`() {
        val target = UUID.randomUUID()
        val capped = ownedStall().copy(
            members = setOf(UUID.randomUUID(), UUID.randomUUID()),
            maxMembers = 2,
        )
        val stalls = mockk<StallRepository>(relaxed = true)
        val sync = mockk<RegionMemberSync>(relaxed = true)
        every { stalls.findById(stallId) } returns capped

        val service = StallMemberService(stalls, sync, mockk(relaxed = true))
        val result = service.addMember(stallId, owner, target)

        assertIs<Result.Rejected>(result)
        verify(exactly = 0) { sync.addMember(any(), any(), any()) }
        verify(exactly = 0) { stalls.save(any()) }
    }

    @Test fun `removeMember by owner drops from roster and WG`() {
        val target = UUID.randomUUID()
        val stalls = mockk<StallRepository>(relaxed = true)
        val sync = mockk<RegionMemberSync>(relaxed = true)
        every { stalls.findById(stallId) } returns ownedStall().copy(members = setOf(target))

        val service = StallMemberService(stalls, sync, mockk(relaxed = true))
        val result = service.removeMember(stallId, owner, target)

        assertIs<Result.Success>(result)
        assertTrue(target !in result.stall.members)
        verify(exactly = 1) { sync.removeMember("world", "s1", target) }
    }

    @Test fun `listMembers by owner returns the current stall`() {
        val a = UUID.randomUUID()
        val stalls = mockk<StallRepository>()
        val sync = mockk<RegionMemberSync>()
        every { stalls.findById(stallId) } returns ownedStall().copy(members = setOf(a))

        val service = StallMemberService(stalls, sync, mockk(relaxed = true))
        val result = service.listMembers(stallId, owner)

        assertIs<Result.Success>(result)
        assertEquals(setOf(a), result.stall.members)
        confirmVerified(sync) // listing must not touch WG
    }
}
