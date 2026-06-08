package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class GuildDissolutionServiceTest {

    private val guildUuid: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val guildIdString: String = guildUuid.toString()
    private val otherGuildUuid: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")
    private val otherGuildIdString: String = otherGuildUuid.toString()
    private val soloPlayerUuid: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun ownedStall(
        idValue: String,
        ownerType: OwnerType,
        ownerId: String,
    ): Stall = Stall(
        id = StallId(idValue),
        regionId = idValue,
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef(type = ownerType, id = ownerId),
        ownerSince = Instant.now(),
        winningBid = 1000L,
        rentTerms = RentTerms.formula(1.0),
        members = emptySet(),
        nextRentAt = Instant.now(),
    )

    private fun guildShop(id: Long, guildId: UUID = guildUuid): Shop = Shop(
        id = id,
        stallId = "stall_$id",
        owner = soloPlayerUuid,
        signWorld = "world",
        signX = 10, signY = 64, signZ = 10,
        containerWorld = "world",
        containerX = 10, containerY = 63, containerZ = 10,
        sellItem = "DIAMOND",
        sellAmount = 1,
        costItem = "EMERALD",
        costAmount = 1,
        guildId = guildId,
        creatorId = soloPlayerUuid,
    )

    private data class Wiring(
        val service: GuildDissolutionService,
        val stalls: StallRepository,
        val eviction: StallEvictionService,
        val shops: ShopRepository,
        val policies: net.badgersmc.em.domain.guild.GuildTradePolicyRepository,
    )

    private fun buildService(
        allStalls: List<Stall> = emptyList(),
        guildShops: List<Shop> = emptyList(),
    ): Wiring {
        val stalls = mockk<StallRepository>(relaxed = true)
        every { stalls.all() } returns allStalls

        val eviction = mockk<StallEvictionService>(relaxed = true)
        every { eviction.evict(any()) } returns StallEvictionService.Result.Evicted

        val shops = mockk<ShopRepository>(relaxed = true)
        every { shops.findByGuildId(guildUuid) } returns guildShops
        every { shops.removeGuildOwnership(any()) } returns null

        val policies = mockk<net.badgersmc.em.domain.guild.GuildTradePolicyRepository>(relaxed = true)

        return Wiring(
            service = GuildDissolutionService(stalls, eviction, shops, policies),
            stalls = stalls,
            eviction = eviction,
            shops = shops,
            policies = policies,
        )
    }

    @Test fun `handle evicts the guild's stalls and unbinds its shops`() {
        val guildStallA = ownedStall("guildA", OwnerType.GUILD, guildIdString)
        val guildStallB = ownedStall("guildB", OwnerType.GUILD, guildIdString)
        val otherGuildStall = ownedStall("otherGuild", OwnerType.GUILD, otherGuildIdString)
        val soloStall = ownedStall("solo1", OwnerType.SOLO, soloPlayerUuid.toString())

        val shop1 = guildShop(id = 1L)
        val shop2 = guildShop(id = 2L)

        val wiring = buildService(
            allStalls = listOf(guildStallA, guildStallB, otherGuildStall, soloStall),
            guildShops = listOf(shop1, shop2),
        )

        wiring.service.handle(guildIdString)

        // Two matching guild stalls are evicted.
        verify { wiring.eviction.evict(StallId("guildA")) }
        verify { wiring.eviction.evict(StallId("guildB")) }
        // The other-guild stall is left alone.
        verify(exactly = 0) { wiring.eviction.evict(StallId("otherGuild")) }
        // The solo stall is left alone.
        verify(exactly = 0) { wiring.eviction.evict(StallId("solo1")) }
        // Each returned shop is unbound.
        verify { wiring.shops.removeGuildOwnership(1L) }
        verify { wiring.shops.removeGuildOwnership(2L) }
    }

    @Test fun `handle with a corrupt guild id skips shop unbind but still scans stalls`() {
        val corruptId = "not-a-uuid"
        val guildStallA = ownedStall("guildA", OwnerType.GUILD, corruptId)
        val guildStallB = ownedStall("guildB", OwnerType.GUILD, corruptId)
        val soloStall = ownedStall("solo1", OwnerType.SOLO, soloPlayerUuid.toString())

        val wiring = buildService(
            allStalls = listOf(guildStallA, guildStallB, soloStall),
            guildShops = emptyList(),
        )

        wiring.service.handle(corruptId)

        // The stalls that match the corrupt id string are still evicted —
        // string match is independent of UUID validity.
        verify { wiring.eviction.evict(StallId("guildA")) }
        verify { wiring.eviction.evict(StallId("guildB")) }
        verify(exactly = 0) { wiring.eviction.evict(StallId("solo1")) }

        // The corrupt id never reaches findByGuildId because UUID parsing fails first.
        verify(exactly = 0) { wiring.shops.findByGuildId(any()) }
        verify(exactly = 0) { wiring.shops.removeGuildOwnership(any()) }
    }

    @Test fun `one failing eviction does not abort the rest`() {
        val guildStallA = ownedStall("guildA", OwnerType.GUILD, guildIdString)
        val guildStallB = ownedStall("guildB", OwnerType.GUILD, guildIdString)
        val guildStallC = ownedStall("guildC", OwnerType.GUILD, guildIdString)

        val stalls = mockk<StallRepository>(relaxed = true)
        every { stalls.all() } returns listOf(guildStallA, guildStallB, guildStallC)

        val eviction = mockk<StallEvictionService>(relaxed = true)
        // First call blows up; the rest must still run.
        every { eviction.evict(StallId("guildA")) } throws RuntimeException("boom")
        every { eviction.evict(StallId("guildB")) } returns StallEvictionService.Result.Evicted
        every { eviction.evict(StallId("guildC")) } returns StallEvictionService.Result.Evicted

        val shops = mockk<ShopRepository>(relaxed = true)
        every { shops.findByGuildId(guildUuid) } returns emptyList()

        val policies = mockk<net.badgersmc.em.domain.guild.GuildTradePolicyRepository>(relaxed = true)
        val service = GuildDissolutionService(stalls, eviction, shops, policies)
        service.handle(guildIdString)

        // First stall was attempted (then threw), and the second stall
        // was still evicted despite the first one's failure.
        verify { eviction.evict(StallId("guildA")) }
        verify { eviction.evict(StallId("guildB")) }
        verify { eviction.evict(StallId("guildC")) }
    }

    @Test fun `handle deletes the disbanded guild's trade policies`() {
        val guildStallA = ownedStall("guildA", OwnerType.GUILD, guildIdString)
        val wiring = buildService(allStalls = listOf(guildStallA))

        wiring.service.handle(guildIdString)

        io.mockk.verify { wiring.policies.deleteAllInvolving(guildIdString) }
    }
}
