package net.badgersmc.em.websync

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicSnapshotProjectorTest {
    @BeforeTest
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterTest
    fun tearDown() {
        if (MockBukkit.isMocked()) MockBukkit.unmock()
    }

    @Test
    fun `capture keeps sorted valid shops and derived rent metadata`() {
        val owner = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val ownerSince = Instant.parse("2026-09-18T12:00:00Z")
        val stall = stall(owner, ownerSince)
        val serializedItem = ItemStackSerializer.serialize(ItemStack(Material.STONE, 1))
        val first = shop(1, owner, serializedItem, SignDirection.BUY)
        val malformed = shop(0, owner, serializedItem, SignDirection.SELL)
        val third = shop(3, owner, serializedItem, SignDirection.SELL)
        val availability = mockk<ShopAvailabilityCalculator>()
        every { availability.availableTrades(first, stall) } returns 0
        every { availability.availableTrades(third, stall) } returns 2
        val projector = projector(
            stalls = listOf(stall),
            persistedShops = listOf(third, malformed, first),
            bounds = mapOf("stall1" to RegionProvider.RegionBounds(-2, 64, -1, -1, 65, 0)),
            availability = availability,
        )
        val diagnostics = PublicSnapshotProjector.Diagnostics()

        val snapshot = projector.capture("stall1", diagnostics)

        assertEquals(PublicLocation("world", -2, 64, -1), snapshot.location)
        assertEquals("2026-09-18T14:00:00Z", snapshot.nextRentAt)
        assertEquals("LEGACY_DERIVED", snapshot.rentTimingStatus)
        assertEquals(listOf(1L, 3L), snapshot.shops.map { it.id })
        assertEquals(1, diagnostics.malformedShops)
        assertEquals(1, diagnostics.unavailableBuyContainers)
    }

    @Test
    fun `live validation retains ordered labels and bounds diagnostic`() {
        val owner = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val invalidRegion = stall(owner, Instant.parse("2026-09-18T12:00:00Z"))
            .copy(id = StallId("stall2"), regionId = "other-region")
        val invalidCenter = stall(owner, Instant.parse("2026-09-18T12:00:00Z"))
            .copy(id = StallId("stall3"), regionId = "stall3")
        val projector = projector(
            stalls = listOf(invalidRegion, invalidCenter),
            bounds = mapOf(
                "stall2" to RegionProvider.RegionBounds(2, 64, 0, 1, 64, 0),
                "stall3" to RegionProvider.RegionBounds(-30_000_001, 64, 0, -30_000_001, 64, 0),
            ),
            canonicalStalls = mapOf(
                "stall1" to CanonicalStall("stall1", "building-1", 1),
                "stall2" to CanonicalStall("stall2", "building-2", 1),
                "stall3" to CanonicalStall("stall3", "building-3", 1),
            ),
            canonicalErrors = listOf("canonical_error"),
            existingRegions = setOf("stall3"),
        )

        val report = projector.validateLiveReport()

        assertEquals(
            listOf(
                "canonical_error",
                "persisted_stall_ids",
                "missing_stall:stall1",
                "missing_region:stall2",
                "invalid_bounds:stall2",
                "invalid_center:stall3",
            ),
            report.errors,
        )
        assertEquals(
            listOf(
                "canonical_duplicate_visible_geometry:stall60,stall62",
                "stall60_stall62_live_bounds:unavailable",
            ),
            report.diagnostics,
        )
    }

    private fun projector(
        stalls: List<Stall>,
        persistedShops: List<Shop> = emptyList(),
        bounds: Map<String, RegionProvider.RegionBounds> = emptyMap(),
        availability: ShopAvailabilityCalculator = mockk(),
        canonicalStalls: Map<String, CanonicalStall> = mapOf(
            "stall1" to CanonicalStall("stall1", "building-1", 1),
        ),
        canonicalErrors: List<String> = emptyList(),
        existingRegions: Set<String> = bounds.keys,
    ): PublicSnapshotProjector {
        val stallRepository = mockk<StallRepository>()
        every { stallRepository.findById(any()) } answers {
            stalls.firstOrNull { it.id.value == invocation.args[0].toString() }
        }
        every { stallRepository.all() } returns stalls
        val shopRepository = mockk<ShopRepository>()
        every { shopRepository.findByStall(any()) } returns persistedShops
        val regions = mockk<RegionProvider>()
        every { regions.bounds(any(), any()) } answers {
            bounds[invocation.args[1].toString()]
        }
        every { regions.exists(any(), any()) } answers {
            invocation.args[1].toString() in existingRegions
        }
        val canonical = mockk<CanonicalMarketMap>()
        every { canonical.stalls } returns canonicalStalls
        every { canonical.stallIds } returns canonicalStalls.keys.toList()
        every { canonical.validate() } returns canonicalErrors
        val guilds = mockk<GuildProvider>(relaxed = true)
        val ownerProjection = PublicOwnerProjection(guilds, PublicOwnerAvatarResolver { false }) { "Owner" }
        val config = EnthusiaMarketConfig().apply { rent.collectionInterval = "PT2H" }
        return PublicSnapshotProjector(
            stallRepository,
            shopRepository,
            regions,
            guilds,
            availability,
            canonical,
            config,
            ownerProjection = ownerProjection,
        )
    }

    private fun stall(owner: UUID, ownerSince: Instant) = Stall(
        id = StallId("stall1"),
        regionId = "stall1",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(owner),
        ownerSince = ownerSince,
        winningBid = 1,
        rentTerms = RentTerms.flat(1),
    )

    private fun shop(id: Long, owner: UUID, serializedItem: String, direction: SignDirection) = Shop(
        id = id,
        stallId = "stall1",
        owner = owner,
        signWorld = "world",
        signX = id.toInt(),
        signY = 64,
        signZ = 0,
        containerWorld = "world",
        containerX = id.toInt(),
        containerY = 63,
        containerZ = 0,
        sellItem = serializedItem,
        sellAmount = 1,
        costItem = serializedItem,
        costAmount = 1,
        direction = direction,
        stockCount = 2,
    )
}
