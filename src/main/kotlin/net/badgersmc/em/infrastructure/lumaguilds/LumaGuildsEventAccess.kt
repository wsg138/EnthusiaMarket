package net.badgersmc.em.infrastructure.lumaguilds

import org.bukkit.event.Event
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/** Bounded compatibility layer for LumaGuilds event package moves. */
internal object LumaGuildsEventAccess {
    private val packages = listOf(
        "net.lumalyte.lg.api.events",
        "net.lumalyte.lg.domain.events",
    )

    fun candidates(simpleName: String): List<String> = packages.map { "$it.$simpleName" }

    fun resolve(classLoader: ClassLoader, simpleName: String): Class<out Event>? {
        for (className in candidates(simpleName)) {
            val candidate = try {
                Class.forName(className, false, classLoader)
            } catch (_: ClassNotFoundException) {
                continue
            }
            require(Event::class.java.isAssignableFrom(candidate)) {
                "LumaGuilds class $className is not a Bukkit Event"
            }
            return candidate.asEventClass()
        }
        return null
    }

    fun guildId(event: Any): UUID? {
        val direct = invokeGetter(event, "getGuildId")
        if (direct is UUID) return direct

        val guild = invokeGetter(event, "getGuild") ?: return null
        return invokeGetter(guild, "getId") as? UUID
    }

    private fun invokeGetter(target: Any, name: String): Any? {
        val method = try {
            target.javaClass.getMethod(name)
        } catch (_: NoSuchMethodException) {
            return null
        }
        return try {
            method.invoke(target)
        } catch (exception: IllegalAccessException) {
            throw IllegalStateException("Cannot access ${target.javaClass.name}#$name", exception)
        } catch (exception: InvocationTargetException) {
            throw IllegalStateException("LumaGuilds accessor ${target.javaClass.name}#$name failed", exception.cause ?: exception)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Class<*>.asEventClass(): Class<out Event> = this as Class<out Event>
}
