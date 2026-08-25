package net.badgersmc.em.infrastructure.listeners

import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.Test

class ShopFreezeStateListenerTest {

    private lateinit var server: ServerMock
    private lateinit var shops: ShopRepository
    private lateinit var listener: ShopFreezeStateListener

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        shops = mockk(relaxed = true)
        listener = ShopFreezeStateListener(shops)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    // --- leaving a penalty state → unfreeze ---

    @Test
    fun `GRACE to OWNED rent payment unfreezes shops`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall39", StallState.GRACE, StallState.OWNED))
        verify { shops.freezeByStall("stall39", false) }
    }

    @Test
    fun `EMERGENCY_AUCTIONING to OWNED settlement unfreezes shops`() {
        listener.onStallStateChanged(
            StallStateChangedEvent("stall4", StallState.EMERGENCY_AUCTIONING, StallState.OWNED)
        )
        verify { shops.freezeByStall("stall4", false) }
    }

    @Test
    fun `EMERGENCY_AUCTIONING to UNOWNED revert unfreezes shops`() {
        listener.onStallStateChanged(
            StallStateChangedEvent("stall5", StallState.EMERGENCY_AUCTIONING, StallState.UNOWNED)
        )
        verify { shops.freezeByStall("stall5", false) }
    }

    @Test
    fun `GRACE to UNOWNED eviction unfreezes shops`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall9", StallState.GRACE, StallState.UNOWNED))
        verify { shops.freezeByStall("stall9", false) }
    }

    @Test
    fun `UNOWNED to OWNED buyout unfreezes shops`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall12", StallState.UNOWNED, StallState.OWNED))
        verify { shops.freezeByStall("stall12", false) }
    }

    // REQ-302 — ANY non-OWNED source landing on OWNED/UNOWNED unfreezes
    // (audit L-1: AUCTIONING/RE_AUCTIONING sources were missing from the
    // whitelist, so an admin force-auction of a GRACE stall that settled or
    // reverted left its shops frozen forever).

    @Test
    fun `AUCTIONING to OWNED settlement unfreezes shops`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall10", StallState.AUCTIONING, StallState.OWNED))
        verify { shops.freezeByStall("stall10", false) }
    }

    @Test
    fun `RE_AUCTIONING to OWNED settlement unfreezes shops`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall11", StallState.RE_AUCTIONING, StallState.OWNED))
        verify { shops.freezeByStall("stall11", false) }
    }

    @Test
    fun `AUCTIONING to UNOWNED revert unfreezes shops`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall13", StallState.AUCTIONING, StallState.UNOWNED))
        verify { shops.freezeByStall("stall13", false) }
    }

    @Test
    fun `RE_AUCTIONING to UNOWNED revert unfreezes shops`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall14", StallState.RE_AUCTIONING, StallState.UNOWNED))
        verify { shops.freezeByStall("stall14", false) }
    }

    // --- same-state / non-penalty transitions → leave shop freeze alone ---

    @Test
    fun `OWNED to OWNED rent extension does not clobber manual shop freeze`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall39", StallState.OWNED, StallState.OWNED))
        verify(exactly = 0) { shops.freezeByStall("stall39", any()) }
    }

    @Test
    fun `OWNED to GRACE does not touch shop freeze state (RentCollectionService owns it)`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall9", StallState.OWNED, StallState.GRACE))
        verify(exactly = 0) { shops.freezeByStall("stall9", any()) }
    }

    @Test
    fun `GRACE to EMERGENCY_AUCTIONING does not touch shop freeze state`() {
        listener.onStallStateChanged(
            StallStateChangedEvent("stall35", StallState.GRACE, StallState.EMERGENCY_AUCTIONING)
        )
        verify(exactly = 0) { shops.freezeByStall("stall35", any()) }
    }

    @Test
    fun `UNOWNED to AUCTIONING does not touch shop freeze state`() {
        listener.onStallStateChanged(StallStateChangedEvent("stall1", StallState.UNOWNED, StallState.AUCTIONING))
        verify(exactly = 0) { shops.freezeByStall("stall1", any()) }
    }
}
