package net.badgersmc.em.infrastructure.lumaguilds

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LumaGuildsGuildProviderStubTest {
    private val provider = LumaGuildsGuildProvider()

    @Test fun `all guild operations throw NotImplementedError in foundation`() {
        val p = UUID.randomUUID()
        assertFailsWith<NotImplementedError> { provider.guildOf(p) }
        assertFailsWith<NotImplementedError> { provider.bankBalance("g") }
        assertFailsWith<NotImplementedError> { provider.bankWithdraw("g", 1L) }
        assertFailsWith<NotImplementedError> { provider.hasPermission(p, "g", "stall.manage") }
    }

    @Test fun `onDissolved callback registers without throwing`() {
        provider.onDissolved { _ -> }
    }
}
