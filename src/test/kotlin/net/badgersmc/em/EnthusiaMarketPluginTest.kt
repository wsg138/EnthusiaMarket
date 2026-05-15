package net.badgersmc.em

import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnthusiaMarketPluginTest {
    @BeforeTest fun setUp() { MockBukkit.mock() }
    @AfterTest fun tearDown() { MockBukkit.unmock() }

    @Test fun `plugin loads and reports as enabled`() {
        val plugin = MockBukkit.load(EnthusiaMarket::class.java)
        assertNotNull(plugin)
        assertTrue(plugin.isEnabled)
    }
}
