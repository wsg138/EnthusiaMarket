package net.badgersmc.em.websync

import io.mockk.every
import io.mockk.mockk
import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class WebsiteSyncExecutorSaturationTest {
    private val gate = CountDownLatch(1)
    private lateinit var service: WebsiteSyncService

    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
        val canonical = mockk<CanonicalMarketMap>()
        every { canonical.stalls } returns mapOf("stall1" to CanonicalStall("stall1", "building-1", 1))
        service = WebsiteSyncService(
            MockBukkit.createMockPlugin("EnthusiaMarket"),
            mockk(),
            mockk(),
            mockk(),
            canonical,
        )
        setField("active", true)
        setField("paused", false)
        setField("config", config())
        saturateExecutor()
    }

    @AfterEach
    fun tearDown() {
        gate.countDown()
        service.close()
        MockBukkit.unmock()
    }

    @Test
    fun `all important submission paths recover from a saturated executor`() {
        val stall = stall()
        invoke("submitStallForDelivery", arrayOf(String::class.java, PublicStall::class.java, Long::class.javaPrimitiveType!!),
            "stall1", stall, 0L)
        val dirty = field("dirty") as TrailingDebounce
        assertEquals("stall1", dirty.firstReady(System.currentTimeMillis() + 3_000))

        val capture = IncrementalFullCapture(listOf("stall1"), { 0L }, { stall }).also { it.tick() }
        invoke("finishFullCapture", arrayOf(IncrementalFullCapture::class.java), capture)
        assertSame(capture, field("fullCapture"))

        setField("paused", false)
        invoke("pump", emptyArray<Class<*>>())
        assertFalse((field("deliveryInFlight") as java.util.concurrent.atomic.AtomicBoolean).get())
        assertEquals("executor_saturated", service.status().errorCategory)

        assertFalse(service.retry())
        invoke("refreshStatus", emptyArray<Class<*>>())
        var testResult: Pair<Boolean, String>? = null
        service.authenticatedTest { success, category -> testResult = success to category }
        assertEquals(false to "executor_saturated", testResult)
        assertEquals("executor_saturated", service.status().errorCategory)
    }

    private fun saturateExecutor() {
        val executor = field("executor") as ThreadPoolExecutor
        executor.execute { runCatching { gate.await() } }
        repeat(128) { executor.execute { runCatching { gate.await() } } }
    }

    private fun invoke(name: String, parameterTypes: Array<Class<*>>, vararg arguments: Any?) {
        WebsiteSyncService::class.java.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            .invoke(service, *arguments)
    }

    private fun field(name: String): Any? = WebsiteSyncService::class.java.getDeclaredField(name).apply { isAccessible = true }
        .get(service)

    private fun setField(name: String, value: Any?) {
        WebsiteSyncService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun config() = WebsiteSyncConfig(
        false, URI("https://example.test"), "enthusia-main", "test-secret",
        Duration.ZERO, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMinutes(15),
        Duration.ofSeconds(1), Duration.ofSeconds(1), 1, Duration.ofSeconds(1), Duration.ofSeconds(8), false, false,
    )

    private fun stall() = PublicStall(
        "stall1", "building-1", 1, PublicLocation("world", 1, 64, 2),
        PublicOwner("NONE", null, null, "Unowned", null, PublicAvatar("NONE")),
        null, null, emptyList(), emptyList(),
    )
}
