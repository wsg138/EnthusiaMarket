@file:Suppress("InvalidPackageDeclaration")
package net.badgersmc.em.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue as assertTrueKt

/**
 * Runtime-wiring rules (audit 2026-06-09, W-1/W-2).
 *
 * Nexus DI is lazy: beans are only constructed on getBean(), and
 * registerNexusListeners only instantiates classes annotated
 * `@net.badgersmc.nexus.paper.listeners.Listener`. A Bukkit listener that
 * relies on `@Component` + `@PostConstruct` self-registration is therefore
 * never constructed and silently dead. Same for schedulers: nothing requests
 * them, so onEnable must getBean() them explicitly.
 */
class ListenerWiringTest {

    private companion object {
        const val NEXUS_LISTENER = "net.badgersmc.nexus.paper.listeners.Listener"
    }

    @Test
    fun `every Bukkit event listener carries the nexus Listener annotation`() {
        Konsist.scopeFromProduction()
            .classes()
            .filter { cls -> cls.functions().any { it.hasAnnotationWithName("EventHandler") } }
            .assertTrue(additionalMessage = "Bukkit listeners must be annotated @$NEXUS_LISTENER or they are never registered (lazy DI)") { cls ->
                // Konsist can't resolve the FQN of annotations from external (jar) sources,
                // so match the declared name: inline-qualified or imported usage.
                cls.annotations.any { it.name == NEXUS_LISTENER || it.name == "Listener" }
            }
    }

    @Test
    fun `no listener self-registers via PostConstruct`() {
        Konsist.scopeFromProduction()
            .classes()
            .filter { cls -> cls.functions().any { it.hasAnnotationWithName("EventHandler") } }
            .assertTrue(additionalMessage = "@PostConstruct self-registration never runs under lazy DI — use @$NEXUS_LISTENER") { cls ->
                cls.functions().none { it.hasAnnotationWithName("PostConstruct") }
            }
    }

    @Test
    fun `onEnable eagerly constructs the rent and auction schedulers`() {
        val mainFile = Konsist.scopeFromProduction()
            .files
            .single { it.name == "EnthusiaMarket" && it.packagee?.name == "net.badgersmc.em" }
        assertTrueKt(
            mainFile.text.contains("getBean<RentScheduler>") &&
                mainFile.text.contains("getBean<AuctionScheduler>"),
            "onEnable must getBean() RentScheduler and AuctionScheduler — nothing else constructs them, so rent collection and auction settlement never run",
        )
    }
}
