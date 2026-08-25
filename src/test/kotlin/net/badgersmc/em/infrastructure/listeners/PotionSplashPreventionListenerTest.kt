package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionProvider
import org.bukkit.Location
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.ThrownPotion
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.LingeringPotionSplashEvent
import org.bukkit.event.entity.PotionSplashEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * REQ-305 — potion effects must never reach entities inside market regions.
 * WG's POTION_SPLASH flag is provision-time-only and does not cover clouds
 * or tipped arrows; this listener is the runtime invariant for every stall.
 */
@Suppress("TooManyFunctions")
class PotionSplashPreventionListenerTest {

    private val config = EnthusiaMarketConfig().apply {
        market.world = "world"
        market.regionPrefix = "stall"
    }

    /** RegionProvider mock: (x, y, z) → region id, or null when not in a region. */
    private fun regions(vararg at: Pair<Triple<Int, Int, Int>, String?>): RegionProvider =
        mockk {
            every { regionAt(any(), any(), any(), any()) } answers {
                val x = secondArg<Int>()
                val y = thirdArg<Int>()
                val z = arg<Int>(3)
                at.firstOrNull { it.first == Triple(x, y, z) }?.second
            }
        }

    private fun location(x: Int = 100, y: Int = 64, z: Int = 200): Location {
        val loc: Location = mockk(relaxed = true)
        every { loc.world?.name } returns "world"
        every { loc.blockX } returns x
        every { loc.blockY } returns y
        every { loc.blockZ } returns z
        return loc
    }

    // --- PotionSplashEvent ---

    @Test
    fun `splash inside a market region is cancelled`() {
        val potion = mockk<ThrownPotion> { every { location } returns location(100, 64, 200) }
        val event = PotionSplashEvent(potion, emptyMap())
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall1"), config
        )

        listener.onPotionSplash(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `splash outside any market region is allowed`() {
        val potion = mockk<ThrownPotion> { every { location } returns location(100, 64, 200) }
        val event = PotionSplashEvent(potion, emptyMap())
        val listener = PotionSplashPreventionListener(regions(Triple(100, 64, 200) to null), config)

        listener.onPotionSplash(event)

        assertFalse(event.isCancelled)
    }

    @Test
    fun `splash in a non-stall region is allowed`() {
        // Region id exists but does not start with the stall prefix — e.g. spawn safezone.
        val potion = mockk<ThrownPotion> { every { location } returns location(100, 64, 200) }
        val event = PotionSplashEvent(potion, emptyMap())
        val listener = PotionSplashPreventionListener(regions(Triple(100, 64, 200) to "safezone"), config)

        listener.onPotionSplash(event)

        assertFalse(event.isCancelled)
    }

    // --- LingeringPotionSplashEvent ---

    @Test
    fun `lingering splash inside a market region is cancelled`() {
        val potion = mockk<ThrownPotion> { every { location } returns location(100, 64, 200) }
        val event = LingeringPotionSplashEvent(potion, mockk<AreaEffectCloud>(relaxed = true))
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall2"), config
        )

        listener.onLingeringSplash(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `lingering splash outside any market region is allowed`() {
        val potion = mockk<ThrownPotion> { every { location } returns location(100, 64, 200) }
        val event = LingeringPotionSplashEvent(potion, mockk<AreaEffectCloud>(relaxed = true))
        val listener = PotionSplashPreventionListener(regions(Triple(100, 64, 200) to null), config)

        listener.onLingeringSplash(event)

        assertFalse(event.isCancelled)
    }

    // --- AreaEffectCloudApplyEvent ---

    @Test
    fun `cloud apply inside a market region is cancelled`() {
        val cloud = mockk<AreaEffectCloud> { every { location } returns location(100, 64, 200) }
        val event = AreaEffectCloudApplyEvent(cloud, listOf(mockk<LivingEntity>(relaxed = true)))
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall3"), config
        )

        listener.onCloudApply(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `cloud apply outside any market region is allowed`() {
        val cloud = mockk<AreaEffectCloud> { every { location } returns location(100, 64, 200) }
        val event = AreaEffectCloudApplyEvent(cloud, listOf(mockk<LivingEntity>(relaxed = true)))
        val listener = PotionSplashPreventionListener(regions(Triple(100, 64, 200) to null), config)

        listener.onCloudApply(event)

        assertFalse(event.isCancelled)
    }

    // --- EntityPotionEffectEvent (tipped arrows + belt-and-braces) ---

    private fun effectEvent(
        x: Int, y: Int, z: Int,
        cause: EntityPotionEffectEvent.Cause,
    ): EntityPotionEffectEvent {
        val entity = mockk<LivingEntity> { every { location } returns location(x, y, z) }
        return EntityPotionEffectEvent(entity, null, null, cause, EntityPotionEffectEvent.Action.ADDED, false)
    }

    private fun clearingEvent(
        x: Int, y: Int, z: Int,
        cause: EntityPotionEffectEvent.Cause,
    ): EntityPotionEffectEvent {
        val entity = mockk<LivingEntity> { every { location } returns location(x, y, z) }
        return EntityPotionEffectEvent(entity, null, null, cause, EntityPotionEffectEvent.Action.CLEARED, false)
    }

    private fun changedEvent(
        x: Int, y: Int, z: Int,
        cause: EntityPotionEffectEvent.Cause,
    ): EntityPotionEffectEvent {
        val entity = mockk<LivingEntity> { every { location } returns location(x, y, z) }
        return EntityPotionEffectEvent(entity, null, null, cause, EntityPotionEffectEvent.Action.CHANGED, false)
    }

    private fun removedEvent(
        x: Int, y: Int, z: Int,
        cause: EntityPotionEffectEvent.Cause,
    ): EntityPotionEffectEvent {
        val entity = mockk<LivingEntity> { every { location } returns location(x, y, z) }
        return EntityPotionEffectEvent(entity, null, null, cause, EntityPotionEffectEvent.Action.REMOVED, false)
    }

    @Test
    fun `tipped arrow effect on entity inside a market region is cancelled`() {
        val event = effectEvent(100, 64, 200, EntityPotionEffectEvent.Cause.ARROW)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall4"), config
        )

        listener.onEntityPotionEffect(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `splash effect on entity inside a market region is cancelled`() {
        val event = effectEvent(100, 64, 200, EntityPotionEffectEvent.Cause.POTION_SPLASH)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall5"), config
        )

        listener.onEntityPotionEffect(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `cloud effect on entity inside a market region is cancelled`() {
        val event = effectEvent(100, 64, 200, EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall6"), config
        )

        listener.onEntityPotionEffect(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `beacon effect on entity inside a market region is allowed`() {
        // Non-potion sources (beacons, drinking, milk) are NOT the exploit vector.
        val event = effectEvent(100, 64, 200, EntityPotionEffectEvent.Cause.BEACON)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall7"), config
        )

        listener.onEntityPotionEffect(event)

        assertFalse(event.isCancelled)
    }

    @Test
    fun `potion drink effect on entity inside a market region is allowed`() {
        val event = effectEvent(100, 64, 200, EntityPotionEffectEvent.Cause.POTION_DRINK)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall8"), config
        )

        listener.onEntityPotionEffect(event)

        assertFalse(event.isCancelled)
    }

    @Test
    fun `arrow effect on entity outside any market region is allowed`() {
        val event = effectEvent(100, 64, 200, EntityPotionEffectEvent.Cause.ARROW)
        val listener = PotionSplashPreventionListener(regions(Triple(100, 64, 200) to null), config)

        listener.onEntityPotionEffect(event)

        assertFalse(event.isCancelled)
    }

    @Test
    fun `effect removal inside a market region is allowed`() {
        // CLEARED/REMOVED actions must never be blocked — only new applications.
        val event = clearingEvent(100, 64, 200, EntityPotionEffectEvent.Cause.POTION_SPLASH)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall9"), config
        )

        listener.onEntityPotionEffect(event)

        assertFalse(event.isCancelled)
    }

    @Test
    fun `changed effect on entity inside a market region is cancelled`() {
        // CHANGED re-applies an effect (the additive-stacking vector) — same as ADDED.
        val event = changedEvent(100, 64, 200, EntityPotionEffectEvent.Cause.POTION_SPLASH)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall10"), config
        )

        listener.onEntityPotionEffect(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `effect removal REMOVED inside a market region is allowed`() {
        // REMOVED (e.g. milk / effect clear) must never be blocked.
        val event = removedEvent(100, 64, 200, EntityPotionEffectEvent.Cause.ARROW)
        val listener = PotionSplashPreventionListener(
            regions(Triple(100, 64, 200) to "stall11"), config
        )

        listener.onEntityPotionEffect(event)

        assertFalse(event.isCancelled)
    }
}
