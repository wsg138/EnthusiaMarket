package net.badgersmc.em.infrastructure.commands

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.badgersmc.nexus.paper.commands.arguments.PaperArgumentResolver
import net.badgersmc.nexus.paper.commands.arguments.PaperArgumentResolvers

/** The exact, unnormalized remainder of `/em websync secret`. */
data class WebsiteSyncSecretArgument(val value: String)

object WebsiteSyncSecretArgumentRegistration {
    fun register() {
        PaperArgumentResolvers.register(object : PaperArgumentResolver<WebsiteSyncSecretArgument> {
            override val type = WebsiteSyncSecretArgument::class

            @Suppress("UNCHECKED_CAST")
            override fun argumentType(): ArgumentType<WebsiteSyncSecretArgument> =
                StringArgumentType.greedyString() as ArgumentType<WebsiteSyncSecretArgument>

            override fun extract(context: CommandContext<*>, name: String): WebsiteSyncSecretArgument =
                WebsiteSyncSecretArgument(StringArgumentType.getString(context, name))
        })
    }
}
