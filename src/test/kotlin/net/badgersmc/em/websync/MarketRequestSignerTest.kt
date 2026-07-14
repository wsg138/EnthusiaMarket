package net.badgersmc.em.websync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MarketRequestSignerTest {
    private val body = """{"schemaVersion":1,"serverId":"enthusia-main","serverEpoch":"epoch","eventId":"00000000-0000-4000-8000-000000000001","sentAt":"2024-07-03T09:46:40Z","probe":"random"}"""

    @Test
    fun `matches complete Worker cross-language vector`() {
        assertEquals(
            "v1=afdc4cf12ead0bac0797b24fad10d26de7397545a7a77f1a4b6bd34e3970c751",
            sign("/internal/v1/test", body),
        )
    }

    @Test
    fun `signs exact raw bytes and pathname`() {
        val signature = sign("/internal/v1/test", body)
        assertNotEquals(signature, sign("/internal/v1/test", "$body "))
        assertNotEquals(signature, sign("/internal/v1/other", body))
    }

    private fun sign(path: String, value: String) = MarketRequestSigner.sign(
        "local-test-secret-with-sufficient-entropy", "POST", path, "enthusia-main", "1720000000000",
        "00000000-0000-4000-8000-000000000001", value.toByteArray(),
    )
}
