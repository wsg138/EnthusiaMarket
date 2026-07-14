package net.badgersmc.em.websync

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

@EnabledIfEnvironmentVariable(named = "RUN_MARKET_REMOTE_TEST", matches = "true")
class MarketRemoteAuthenticationTest {
    @Test
    fun `actual Java client authenticates against only the nonmutating test route`() {
        val secret = requireNotNull(System.getenv("MARKET_SYNC_SECRET"))
        val config = WebsiteSyncConfig(
            configuredEnabled = false,
            endpoint = URI("https://market-api.enthusia.info"),
            serverId = "enthusia-main",
            secret = secret,
            startupDelay = Duration.ZERO,
            debounce = Duration.ofMillis(250),
            maximumDebounce = Duration.ofSeconds(2),
            reconciliation = Duration.ofMinutes(15),
            connectTimeout = Duration.ofSeconds(10),
            requestTimeout = Duration.ofSeconds(15),
            maximumConcurrentRequests = 1,
            initialRetry = Duration.ofSeconds(5),
            maximumRetry = Duration.ofMinutes(5),
            logStatusChanges = false,
            logSuccessfulStallUpdates = false,
        )
        assertEquals(DeliveryOutcome.Success,
            MarketHttpClient(config, "EnthusiaMarket/1.0.0").authenticatedTest("remote-audit"))
    }
}
