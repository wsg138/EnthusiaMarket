package net.badgersmc.em.infrastructure.lumaguilds

import org.bukkit.event.Event
import java.util.UUID

/** Keeps Market decoupled from LumaGuilds event package moves. */
internal object LumaGuildsEventAccess {
    private val packages = listOf(
        "net.lumalyte.lg.api.events",
        "net.lumalyte.lg.domain.events",
    )

    fun candidates(simpleName: String): List<String> = packages.map { "$it.$simpleName" }

    fun resolve(classLoader: ClassLoader, simpleName: String): Class<out Event>? =
        candidates(simpleName).firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className, false, classLoader) }
                .getOrNull()
                ?.takeIf(Event::class.java::isAssignableFrom)
                ?.asEventClass()
        }

    fun guildId(event: Any): UUID? {
        val direct = invokeGetter(event, "getGuildId")
        if (direct is UUID) return direct

        val guild = invokeGetter(event, "getGuild") ?: return null
        return invokeGetter(guild, "getId") as? UUID
    }

    private fun invokeGetter(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun Class<*>.asEventClass(): Class<out Event> = this as Class<out Event>
}
