package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.PermissionChecker
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Red tests for TDD-210 — LimitResolutionService merges the limit
 * groups whose `enthusiamarket.limit.<name>` permission a player
 * holds, taking the best value per dimension (REQ-211), and grants
 * unlimited limits via `enthusiamarket.admin.bypasslimit` (REQ-213).
 * `-1` in config means unlimited (REQ-210) and beats any finite cap.
 */
class LimitResolutionServiceTest {

    private val player = UUID.randomUUID()

    private fun configWithGroups(vararg groups: Pair<String, EnthusiaMarketConfig.LimitGroup>) =
        EnthusiaMarketConfig().apply {
            for ((name, group) in groups) limits[name] = group
        }

    private fun group(total: Int, regionkinds: Map<String, Int> = emptyMap()) =
        EnthusiaMarketConfig.LimitGroup().apply {
            this.total = total
            this.regionkinds.putAll(regionkinds)
        }

    @Test fun `player with a single granted limit group inherits its values`() {
        val config = configWithGroups(
            "vip" to group(total = 5, regionkinds = mapOf("shop" to 3, "farm" to 2))
        )
        val perms = mockk<PermissionChecker>()
        every { perms.has(player, "enthusiamarket.admin.bypasslimit") } returns false
        every { perms.has(player, "enthusiamarket.limit.vip") } returns true

        val service = LimitResolutionService(config, perms)
        val effective = service.effectiveLimits(player)

        assertEquals(5, effective.total)
        assertEquals(3, effective.regionkinds["shop"])
        assertEquals(2, effective.regionkinds["farm"])
    }

    @Test fun `player with no granted group is unlimited (no-group fix)`() {
        val config = configWithGroups("vip" to group(total = 5))
        val perms = mockk<PermissionChecker>()
        every { perms.has(any(), any()) } returns false

        val service = LimitResolutionService(config, perms)
        val effective = service.effectiveLimits(player)

        assertTrue(effective.isUnlimited)
        assertEquals(-1, effective.total)
        assertEquals(emptyMap(), effective.regionkinds)
    }

    @Test fun `player in no granted group is unlimited`() {
        val cfg = EnthusiaMarketConfig().apply {
            limits["vip"] = EnthusiaMarketConfig.LimitGroup().apply { total = 3 }
        }
        val perms = mockk<PermissionChecker> {
            every { has(any(), any()) } returns false
        }
        val svc = LimitResolutionService(cfg, perms)
        val p = UUID.randomUUID()
        assertTrue(svc.effectiveLimits(p).isUnlimited)
        assertEquals(
            LimitResolutionService.ClaimDecision.Allowed,
            svc.canClaim(p, "default", currentTotal = 99, currentForKind = 99),
        )
    }

    @Test fun `empty config leaves everyone unlimited`() {
        val perms = mockk<PermissionChecker> { every { has(any(), any()) } returns false }
        val svc = LimitResolutionService(EnthusiaMarketConfig(), perms)
        assertTrue(svc.effectiveLimits(UUID.randomUUID()).isUnlimited)
    }

    @Test fun `multiple groups merge by taking the highest value per dimension`() {
        val config = configWithGroups(
            "bronze" to group(total = 5, regionkinds = mapOf("shop" to 2, "farm" to 1)),
            "silver" to group(total = 8, regionkinds = mapOf("shop" to 4)),
            "gold" to group(total = 3, regionkinds = mapOf("farm" to 6)),
        )
        val perms = mockk<PermissionChecker>()
        every { perms.has(player, "enthusiamarket.admin.bypasslimit") } returns false
        every { perms.has(player, "enthusiamarket.limit.bronze") } returns true
        every { perms.has(player, "enthusiamarket.limit.silver") } returns true
        every { perms.has(player, "enthusiamarket.limit.gold") } returns true

        val service = LimitResolutionService(config, perms)
        val effective = service.effectiveLimits(player)

        // total: max(5, 8, 3) = 8
        assertEquals(8, effective.total)
        // shop: max(2, 4) = 4
        assertEquals(4, effective.regionkinds["shop"])
        // farm: max(1, 6) = 6
        assertEquals(6, effective.regionkinds["farm"])
    }

    @Test fun `unlimited (-1) in any group propagates to the merged result`() {
        val config = configWithGroups(
            "bronze" to group(total = 5, regionkinds = mapOf("shop" to 3)),
            "patron" to group(total = -1, regionkinds = mapOf("shop" to -1)),
        )
        val perms = mockk<PermissionChecker>()
        every { perms.has(player, "enthusiamarket.admin.bypasslimit") } returns false
        every { perms.has(player, "enthusiamarket.limit.bronze") } returns true
        every { perms.has(player, "enthusiamarket.limit.patron") } returns true

        val service = LimitResolutionService(config, perms)
        val effective = service.effectiveLimits(player)

        // -1 (unlimited) beats every finite value per REQ-210.
        assertEquals(-1, effective.total)
        assertEquals(-1, effective.regionkinds["shop"])
    }

    @Test fun `admin bypass returns unlimited regardless of granted groups`() {
        val config = configWithGroups("vip" to group(total = 5))
        val perms = mockk<PermissionChecker>()
        every { perms.has(player, "enthusiamarket.admin.bypasslimit") } returns true

        val service = LimitResolutionService(config, perms)
        val effective = service.effectiveLimits(player)

        assertEquals(-1, effective.total)
        assertEquals(true, effective.isUnlimited)
    }

    @Test fun `canClaim is true under cap and false at cap`() {
        val config = configWithGroups(
            "vip" to group(total = 3, regionkinds = mapOf("shop" to 2))
        )
        val perms = mockk<PermissionChecker>()
        every { perms.has(player, "enthusiamarket.admin.bypasslimit") } returns false
        every { perms.has(player, "enthusiamarket.limit.vip") } returns true

        val service = LimitResolutionService(config, perms)

        // Under both caps → ok
        assertEquals(
            LimitResolutionService.ClaimDecision.Allowed,
            service.canClaim(player, kind = "shop", currentTotal = 2, currentForKind = 1),
        )
        // At kind cap → rejected
        assertEquals(
            LimitResolutionService.ClaimDecision.Rejected.KindCapReached("shop", 2),
            service.canClaim(player, kind = "shop", currentTotal = 2, currentForKind = 2),
        )
        // At total cap (and kind cap not yet reached) → rejected
        assertEquals(
            LimitResolutionService.ClaimDecision.Rejected.TotalCapReached(3),
            service.canClaim(player, kind = "farm", currentTotal = 3, currentForKind = 0),
        )
    }

    @Test fun `canClaim treats admin bypass as unconditionally allowed`() {
        val config = configWithGroups("vip" to group(total = 0))
        val perms = mockk<PermissionChecker>()
        every { perms.has(player, "enthusiamarket.admin.bypasslimit") } returns true

        val service = LimitResolutionService(config, perms)
        assertEquals(
            LimitResolutionService.ClaimDecision.Allowed,
            service.canClaim(player, kind = "shop", currentTotal = 999, currentForKind = 999),
        )
    }
}
