package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShopGuildServiceTest {

    private val guildId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val creatorId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val playerId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val strangerId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    private val baseShop = Shop(
        id = 1L,
        stallId = "stall_01",
        owner = playerId,
        signWorld = "world",
        signX = 10, signY = 64, signZ = 10,
        containerWorld = "world",
        containerX = 10, containerY = 63, containerZ = 10,
        sellItem = "DIAMOND",
        sellAmount = 1,
        costItem = "EMERALD",
        costAmount = 1
    )

    private val guildShop = baseShop.copy(guildId = guildId, creatorId = creatorId)

    private val updatedGuildShop = baseShop.copy(guildId = guildId, creatorId = playerId)

    private val updatedPlayerShop = baseShop.copy(guildId = null, creatorId = null)

    /** Data class holding service and mocks for verification. */
    private data class ServiceWithMocks(
        val service: ShopGuildService,
        val shopRepo: ShopRepository,
        val guildProvider: GuildProvider,
    )

    private fun buildService(
        findByIdResult: Shop? = baseShop,
        setGuildOwnershipResult: Shop? = updatedGuildShop,
        removeGuildOwnershipResult: Shop? = updatedPlayerShop,
        findByGuildIdResult: List<Shop> = emptyList(),
        isMemberResult: Boolean = true,
    ): ServiceWithMocks {
        val shopRepo = mockk<ShopRepository>(relaxUnitFun = true)
        every { shopRepo.findById(any()) } returns findByIdResult
        every { shopRepo.setGuildOwnership(any(), any(), any()) } returns setGuildOwnershipResult
        every { shopRepo.removeGuildOwnership(any()) } returns removeGuildOwnershipResult
        every { shopRepo.findByGuildId(any()) } returns findByGuildIdResult

        val guildProvider = mockk<GuildProvider>(relaxUnitFun = true)
        every { guildProvider.isMember(any(), any()) } returns isMemberResult

        return ServiceWithMocks(
            service = ShopGuildService(shopRepo, guildProvider),
            shopRepo = shopRepo,
            guildProvider = guildProvider,
        )
    }

    // --- registerGuildShop ---

    @Test
    fun `registerGuildShop creates guild ownership on player-owned shop`() {
        val svc = buildService(
            findByIdResult = baseShop,
            setGuildOwnershipResult = updatedGuildShop
        )

        val result = svc.service.registerGuildShop(1L, guildId, playerId)

        assertTrue(result.isSuccess)
        val shop = result.getOrThrow()
        assertEquals(guildId, shop.guildId)
        assertEquals(playerId, shop.creatorId)

        verify { svc.shopRepo.findById(1L) }
        verify { svc.shopRepo.setGuildOwnership(1L, guildId, playerId) }
    }

    @Test
    fun `registerGuildShop on already guild-owned shop returns failure`() {
        val svc = buildService(findByIdResult = guildShop)

        val result = svc.service.registerGuildShop(1L, guildId, playerId)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<IllegalStateException>(exception)
        assertTrue(exception.message?.contains("already guild-owned") == true)

        verify { svc.shopRepo.findById(1L) }
        verify(exactly = 0) { svc.shopRepo.setGuildOwnership(any(), any(), any()) }
    }

    @Test
    fun `registerGuildShop on non-existent shop returns failure`() {
        val svc = buildService(findByIdResult = null)

        val result = svc.service.registerGuildShop(999L, guildId, playerId)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<IllegalArgumentException>(exception)
        assertTrue(exception.message?.contains("not found") == true)

        verify { svc.shopRepo.findById(999L) }
        verify(exactly = 0) { svc.shopRepo.setGuildOwnership(any(), any(), any()) }
    }

    @Test
    fun `registerGuildShop by non-owner returns failure (C5)`() {
        val svc = buildService(findByIdResult = baseShop) // owned by playerId

        val result = svc.service.registerGuildShop(1L, guildId, strangerId)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<IllegalAccessException>(exception)
        assertTrue(exception.message?.contains("does not own") == true)

        verify { svc.shopRepo.findById(1L) }
        verify(exactly = 0) { svc.shopRepo.setGuildOwnership(any(), any(), any()) }
    }

    @Test
    fun `registerGuildShop by non-member returns failure (C5)`() {
        val svc = buildService(
            findByIdResult = baseShop, // owned by playerId
            isMemberResult = false,    // but not in the guild
        )

        val result = svc.service.registerGuildShop(1L, guildId, playerId)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<IllegalStateException>(exception)
        assertTrue(exception.message?.contains("not a member") == true)

        verify { svc.shopRepo.findById(1L) }
        verify(exactly = 0) { svc.shopRepo.setGuildOwnership(any(), any(), any()) }
    }

    // --- unregisterGuildShop ---

    @Test
    fun `unregisterGuildShop removes guild ownership when actor owns the shop (C5)`() {
        val svc = buildService(
            findByIdResult = guildShop.copy(owner = playerId),
            removeGuildOwnershipResult = updatedPlayerShop
        )

        val result = svc.service.unregisterGuildShop(1L, playerId)

        assertTrue(result.isSuccess)
        val shop = result.getOrThrow()
        assertEquals(null, shop.guildId)
        assertEquals(null, shop.creatorId)

        verify { svc.shopRepo.findById(1L) }
        verify { svc.shopRepo.removeGuildOwnership(1L) }
    }

    @Test
    fun `unregisterGuildShop removes guild ownership when actor is a guild member (C5)`() {
        val svc = buildService(
            findByIdResult = guildShop,
            removeGuildOwnershipResult = updatedPlayerShop
        )

        val result = svc.service.unregisterGuildShop(1L, creatorId)

        assertTrue(result.isSuccess)

        verify { svc.shopRepo.findById(1L) }
        verify { svc.shopRepo.removeGuildOwnership(1L) }
    }

    @Test
    fun `unregisterGuildShop by stranger returns failure (C5)`() {
        val svc = buildService(findByIdResult = guildShop)

        val result = svc.service.unregisterGuildShop(1L, strangerId)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<IllegalAccessException>(exception)
        assertTrue(exception.message?.contains("not the shop owner nor a member") == true)

        verify { svc.shopRepo.findById(1L) }
        verify(exactly = 0) { svc.shopRepo.removeGuildOwnership(any()) }
    }

    @Test
    fun `unregisterGuildShop on player-owned shop returns failure`() {
        val svc = buildService(findByIdResult = baseShop)

        val result = svc.service.unregisterGuildShop(1L, playerId)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<IllegalStateException>(exception)
        assertTrue(exception.message?.contains("not guild-owned") == true)

        verify { svc.shopRepo.findById(1L) }
        verify(exactly = 0) { svc.shopRepo.removeGuildOwnership(any()) }
    }

    @Test
    fun `unregisterGuildShop on non-existent shop returns failure`() {
        val svc = buildService(findByIdResult = null)

        val result = svc.service.unregisterGuildShop(999L, playerId)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertIs<IllegalArgumentException>(exception)
        assertTrue(exception.message?.contains("not found") == true)

        verify { svc.shopRepo.findById(999L) }
        verify(exactly = 0) { svc.shopRepo.removeGuildOwnership(any()) }
    }

    // --- findGuildShops ---

    @Test
    fun `findGuildShops returns shops for guild`() {
        val shop2 = baseShop.copy(id = 2L, stallId = "stall_02")
        val shop3 = baseShop.copy(id = 3L, stallId = "stall_03")
        val guildShops = listOf(shop2.copy(guildId = guildId), shop3.copy(guildId = guildId))
        val svc = buildService(findByGuildIdResult = guildShops)

        val result = svc.service.findGuildShops(guildId)

        assertEquals(2, result.size)
        assertEquals(shop2.copy(guildId = guildId), result[0])
        assertEquals(shop3.copy(guildId = guildId), result[1])

        verify { svc.shopRepo.findByGuildId(guildId) }
    }

    @Test
    fun `findGuildShops returns empty list when no shops for guild`() {
        val svc = buildService(findByGuildIdResult = emptyList())

        val result = svc.service.findGuildShops(guildId)

        assertEquals(0, result.size)

        verify { svc.shopRepo.findByGuildId(guildId) }
    }

    // --- isGuildShop ---

    @Test
    fun `isGuildShop returns true for guild-owned shop`() {
        val svc = buildService(findByIdResult = guildShop)

        val result = svc.service.isGuildShop(1L)

        assertTrue(result)

        verify { svc.shopRepo.findById(1L) }
    }

    @Test
    fun `isGuildShop returns false for player-owned shop`() {
        val svc = buildService(findByIdResult = baseShop)

        val result = svc.service.isGuildShop(1L)

        assertFalse(result)

        verify { svc.shopRepo.findById(1L) }
    }

    @Test
    fun `isGuildShop returns false for non-existent shop`() {
        val svc = buildService(findByIdResult = null)

        val result = svc.service.isGuildShop(999L)

        assertFalse(result)

        verify { svc.shopRepo.findById(999L) }
    }
}
