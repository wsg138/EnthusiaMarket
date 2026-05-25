package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.ItemProvider
import net.badgersmc.em.domain.shop.ShopSign
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.shop.SignRepository
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.domain.stall.RentTerms
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertIs
import java.util.UUID

class ShopTradeServiceTest {

    private val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val stallId = StallId("stall_01")
    private val defaultAmount = 1

    private val sampleSign = ShopSign(
        id = 1L,
        stallId = stallId,
        direction = SignDirection.BUY,
        itemKey = "DIAMOND",
        price = 100L,
        signLocation = "world,10,64,10",
        containerLocation = "world,10,63,10"
    )

    private val sampleStall = Stall(
        id = stallId,
        regionId = "stall_01",
        world = "world",
        state = StallState.OWNED,
        owner = OwnerRef.solo(ownerUuid),
        ownerSince = null,
        winningBid = 1000L,
        rentTerms = RentTerms.formula(1.0)
    )

    private fun config(taxPct: Double = 0.02): EnthusiaMarketConfig {
        val cfg = EnthusiaMarketConfig()
        cfg.shop.taxPct = taxPct
        return cfg
    }

    /** Mocks holder returned by [buildService]. Allows verification on injected mocks. */
    private data class ServiceWithMocks(
        val service: ShopTradeService,
        val signRepo: SignRepository,
        val stallRepo: StallRepository,
        val economy: EconomyProvider,
        val items: ItemProvider
    )

    /** Build service with mock overrides. Every param has a sensible default. */
    private fun buildService(
        sign: ShopSign = sampleSign,
        stall: Stall = sampleStall,
        taxPct: Double = 0.02,
        playerBalance: Long = 1000L,
        ownerBalance: Long = 1000L,
        withdrawOk: Boolean = true,
        depositOk: Boolean = true,
        playerHasItem: Boolean = true,
        takeItemOk: Boolean = true,
        giveItemOk: Boolean = true
    ): ServiceWithMocks {
        val signRepo = mockk<SignRepository>()
        every { signRepo.findById(sign.id) } returns sign

        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns stall

        val economy = mockk<EconomyProvider>()
        every { economy.balance(player) } returns playerBalance
        every { economy.balance(ownerUuid) } returns ownerBalance
        every { economy.withdraw(player, any()) } returns withdrawOk
        every { economy.deposit(player, any()) } returns depositOk
        every { economy.withdraw(ownerUuid, any()) } returns withdrawOk
        every { economy.deposit(ownerUuid, any()) } returns depositOk

        val items = mockk<ItemProvider>()
        every { items.playerHasItem(player, any(), any()) } returns playerHasItem
        every { items.takeItemFromPlayer(player, any(), any()) } returns takeItemOk
        every { items.giveItemToPlayer(player, any(), any()) } returns giveItemOk

        return ServiceWithMocks(
            service = ShopTradeService(signRepo, stallRepo, economy, items, config(taxPct)),
            signRepo = signRepo,
            stallRepo = stallRepo,
            economy = economy,
            items = items
        )
    }

    // ===== BUY: player sells item to shop =====

    @Test
    fun `BUY trade transfers item from player and credits economy`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY, price = 200L)
        val (svc, _, _, econ, items) = buildService(sign = sign, taxPct = 0.0)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Success>(result)
        verify { items.takeItemFromPlayer(player, sign.itemKey, defaultAmount) }
        verify { econ.withdraw(ownerUuid, 200L) }
        verify { econ.deposit(player, 200L) }
    }

    @Test
    fun `BUY trade applies tax to seller proceeds`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY, price = 100L)
        val (svc, _, _, econ, _) = buildService(sign = sign, taxPct = 0.10)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Success>(result)
        verify { econ.deposit(player, 90L) }
        verify { econ.withdraw(ownerUuid, 100L) }
    }

    @Test
    fun `BUY with insufficient item returns Failure`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY)
        val (svc, _, _, _, _) = buildService(sign = sign, playerHasItem = false)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Failure>(result)
    }

    @Test
    fun `BUY when owner cannot afford returns Failure`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY, price = 5000L)
        val (svc, _, _, _, _) = buildService(sign = sign, ownerBalance = 1000L)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Failure>(result)
    }

    // ===== SELL: player buys item from shop =====

    @Test
    fun `SELL trade debits player economy and gives item`() {
        val sign = sampleSign.copy(direction = SignDirection.SELL, price = 200L)
        val (svc, _, _, econ, items) = buildService(sign = sign, taxPct = 0.0)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Success>(result)
        verify { econ.withdraw(player, 200L) }
        verify { econ.deposit(ownerUuid, 200L) }
        verify { items.giveItemToPlayer(player, sign.itemKey, defaultAmount) }
    }

    @Test
    fun `SELL trade applies tax to seller proceeds`() {
        val sign = sampleSign.copy(direction = SignDirection.SELL, price = 100L)
        val (svc, _, _, econ, _) = buildService(sign = sign, taxPct = 0.10)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Success>(result)
        verify { econ.withdraw(player, 100L) }
        verify { econ.deposit(ownerUuid, 90L) }
    }

    @Test
    fun `SELL with insufficient balance returns Failure`() {
        val sign = sampleSign.copy(direction = SignDirection.SELL, price = 5000L)
        val (svc, _, _, _, _) = buildService(sign = sign, playerBalance = 1000L)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Failure>(result)
    }

    // ===== Self-trade =====

    @Test
    fun `self trade returns Failure`() {
        val sign = sampleSign.copy(direction = SignDirection.SELL)
        val stall = sampleStall.copy(owner = OwnerRef.solo(player))
        val (svc, _, _, _, _) = buildService(sign = sign, stall = stall)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Failure>(result)
        assertTrue { (result as ShopTradeService.TradeResult.Failure).reason.contains("self", ignoreCase = true) }
    }

    // ===== Sign not found =====

    @Test
    fun `sign not found returns Failure`() {
        val signRepo = mockk<SignRepository>()
        every { signRepo.findById(999L) } returns null

        val svc = ShopTradeService(signRepo, mockk(), mockk(), mockk(), config())

        val result = svc.execute(999L, player)
        assertIs<ShopTradeService.TradeResult.Failure>(result)
    }

    // ===== Rollback scenarios =====

    @Test
    fun `BUY economy failure after item taken rolls back item`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY, price = 100L)
        val signRepo = mockk<SignRepository>()
        every { signRepo.findById(sign.id) } returns sign

        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns sampleStall

        val economy = mockk<EconomyProvider>()
        every { economy.balance(player) } returns 1000L
        every { economy.balance(ownerUuid) } returns 1000L
        every { economy.withdraw(ownerUuid, any()) } returns true
        every { economy.deposit(player, any()) } returns false  // deposit fails!
        every { economy.deposit(ownerUuid, any()) } returns true  // for rollback refund

        val items = mockk<ItemProvider>()
        every { items.playerHasItem(player, any(), any()) } returns true
        every { items.takeItemFromPlayer(player, any(), any()) } returns true
        every { items.giveItemToPlayer(player, any(), any()) } returns true

        val svc = ShopTradeService(signRepo, stallRepo, economy, items, config())

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.RolledBack>(result)
        verify { items.giveItemToPlayer(player, sign.itemKey, defaultAmount) }
        verify { economy.deposit(ownerUuid, 100L) }
    }

    @Test
    fun `SELL economy success but item give fails rolls back economy`() {
        val sign = sampleSign.copy(direction = SignDirection.SELL, price = 100L)
        val signRepo = mockk<SignRepository>()
        every { signRepo.findById(sign.id) } returns sign

        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns sampleStall

        val economy = mockk<EconomyProvider>()
        every { economy.balance(player) } returns 1000L
        every { economy.balance(ownerUuid) } returns 1000L
        every { economy.withdraw(player, any()) } returns true
        every { economy.deposit(ownerUuid, any()) } returns true
        every { economy.deposit(player, any()) } returns true   // for rollback
        every { economy.withdraw(ownerUuid, any()) } returns true // for rollback

        val items = mockk<ItemProvider>()
        every { items.giveItemToPlayer(player, any(), any()) } returns false

        val svc = ShopTradeService(signRepo, stallRepo, economy, items, config())

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.RolledBack>(result)
        verify { economy.deposit(player, 100L) }
    }

    // ===== Compensation failure scenarios =====

    @Test
    fun `BUY deposit fails and owner refund fails returns CompensationFailed`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY, price = 100L)
        val signRepo = mockk<SignRepository>()
        every { signRepo.findById(sign.id) } returns sign

        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns sampleStall

        val economy = mockk<EconomyProvider>()
        every { economy.balance(player) } returns 1000L
        every { economy.balance(ownerUuid) } returns 1000L
        every { economy.withdraw(ownerUuid, any()) } returns true
        every { economy.deposit(player, any()) } returns false  // deposit fails
        every { economy.deposit(ownerUuid, any()) } returns false // rollback refund also fails!

        val items = mockk<ItemProvider>()
        every { items.playerHasItem(player, any(), any()) } returns true
        every { items.takeItemFromPlayer(player, any(), any()) } returns true
        every { items.giveItemToPlayer(player, any(), any()) } returns true

        val svc = ShopTradeService(signRepo, stallRepo, economy, items, config())

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.CompensationFailed>(result)
    }

    @Test
    fun `BUY deposit fails and item return fails returns CompensationFailed`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY, price = 100L)
        val signRepo = mockk<SignRepository>()
        every { signRepo.findById(sign.id) } returns sign

        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns sampleStall

        val economy = mockk<EconomyProvider>()
        every { economy.balance(player) } returns 1000L
        every { economy.balance(ownerUuid) } returns 1000L
        every { economy.withdraw(ownerUuid, any()) } returns true
        every { economy.deposit(player, any()) } returns false  // deposit fails
        every { economy.deposit(ownerUuid, any()) } returns true  // owner refund ok
        // items.giveItemToPlayer NOT mocked = returns false by default, so item return fails

        val items = mockk<ItemProvider>()
        every { items.playerHasItem(player, any(), any()) } returns true
        every { items.takeItemFromPlayer(player, any(), any()) } returns true
        every { items.giveItemToPlayer(player, any(), any()) } returns false  // item return fails

        val svc = ShopTradeService(signRepo, stallRepo, economy, items, config())

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.CompensationFailed>(result)
    }

    @Test
    fun `SELL item give fails and player refund fails returns CompensationFailed`() {
        val sign = sampleSign.copy(direction = SignDirection.SELL, price = 100L)
        val signRepo = mockk<SignRepository>()
        every { signRepo.findById(sign.id) } returns sign

        val stallRepo = mockk<StallRepository>()
        every { stallRepo.findById(stallId) } returns sampleStall

        val economy = mockk<EconomyProvider>()
        every { economy.balance(player) } returns 1000L
        every { economy.balance(ownerUuid) } returns 1000L
        every { economy.withdraw(player, any()) } returns true
        every { economy.deposit(ownerUuid, any()) } returns true
        every { economy.deposit(player, any()) } returns false  // rollback refund fails!
        every { economy.withdraw(ownerUuid, any()) } returns true

        val items = mockk<ItemProvider>()
        every { items.giveItemToPlayer(player, any(), any()) } returns false

        val svc = ShopTradeService(signRepo, stallRepo, economy, items, config())

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.CompensationFailed>(result)
    }

    // ===== Invalid tax percentage =====

    @Test
    fun `negative tax percentage returns Failure`() {
        val sign = sampleSign.copy(direction = SignDirection.BUY)
        val (svc, _, _, _, _) = buildService(sign = sign, taxPct = -0.1)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Failure>(result)
        assertTrue { (result as ShopTradeService.TradeResult.Failure).reason.contains("tax", ignoreCase = true) }
    }

    @Test
    fun `tax percentage over 100 returns Failure`() {
        val sign = sampleSign.copy(direction = SignDirection.SELL)
        val (svc, _, _, _, _) = buildService(sign = sign, taxPct = 1.5)

        val result = svc.execute(sign.id, player)

        assertIs<ShopTradeService.TradeResult.Failure>(result)
        assertTrue { (result as ShopTradeService.TradeResult.Failure).reason.contains("tax", ignoreCase = true) }
    }
}