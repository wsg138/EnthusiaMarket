package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpLimiterTest {

    private fun config(oneAuction: Boolean = true, oneStall: Boolean = true): EnthusiaMarketConfig {
        val cfg = EnthusiaMarketConfig()
        cfg.ipLimiter.oneAuctionPerIp = oneAuction
        cfg.ipLimiter.oneStallPerIp = oneStall
        return cfg
    }

    @Test fun `allows first auction binding`() {
        val limiter = IpLimiter(config())
        assertTrue(limiter.tryBindAuction("1.2.3.4", "auc-1"))
    }

    @Test fun `allows rebid on same auction`() {
        val limiter = IpLimiter(config())
        limiter.tryBindAuction("1.2.3.4", "auc-1")
        assertTrue(limiter.tryBindAuction("1.2.3.4", "auc-1"))
    }

    @Test fun `rejects bid on different auction`() {
        val limiter = IpLimiter(config())
        limiter.tryBindAuction("1.2.3.4", "auc-1")
        assertFalse(limiter.tryBindAuction("1.2.3.4", "auc-2"))
    }

    @Test fun `releaseAuctionBindings frees all IPs for that auction`() {
        val limiter = IpLimiter(config())
        limiter.tryBindAuction("1.1.1.1", "auc-1")
        limiter.tryBindAuction("2.2.2.2", "auc-1")
        limiter.tryBindAuction("3.3.3.3", "auc-2")
        limiter.releaseAuctionBindings("auc-1")
        // IPs from auc-1 are free, auc-2 IP still bound
        assertTrue(limiter.tryBindAuction("1.1.1.1", "auc-3"))
        assertFalse(limiter.tryBindAuction("3.3.3.3", "auc-1"))
    }

    @Test fun `disabled oneAuctionPerIp always allows`() {
        val limiter = IpLimiter(config(oneAuction = false))
        limiter.tryBindAuction("1.2.3.4", "auc-1")
        assertTrue(limiter.tryBindAuction("1.2.3.4", "auc-2"))
    }

    @Test fun `allows first stall claim`() {
        val limiter = IpLimiter(config())
        assertTrue(limiter.tryClaimStall("1.2.3.4", "owner-uuid-1"))
    }

    @Test fun `rejects second stall claim from same IP`() {
        val limiter = IpLimiter(config())
        limiter.tryClaimStall("1.2.3.4", "owner-uuid-1")
        assertFalse(limiter.tryClaimStall("1.2.3.4", "owner-uuid-2"))
    }

    @Test fun `releaseStallByOwnerId allows claim again`() {
        val limiter = IpLimiter(config())
        limiter.tryClaimStall("1.2.3.4", "owner-uuid-1")
        limiter.releaseStallByOwnerId("owner-uuid-1")
        assertTrue(limiter.tryClaimStall("1.2.3.4", "owner-uuid-3"))
    }

    @Test fun `releaseStall allows claim again`() {
        val limiter = IpLimiter(config())
        limiter.tryClaimStall("1.2.3.4", "owner-uuid-1")
        limiter.releaseStall("1.2.3.4")
        assertTrue(limiter.tryClaimStall("1.2.3.4", "owner-uuid-2"))
    }

    @Test fun `disabled oneStallPerIp always allows`() {
        val limiter = IpLimiter(config(oneStall = false))
        limiter.tryClaimStall("1.2.3.4", "owner-uuid-1")
        assertTrue(limiter.tryClaimStall("1.2.3.4", "owner-uuid-2"))
    }
}
