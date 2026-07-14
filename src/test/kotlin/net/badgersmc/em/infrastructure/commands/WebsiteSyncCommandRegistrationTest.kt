package net.badgersmc.em.infrastructure.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import net.badgersmc.nexus.paper.commands.PaperCommandScanner
import net.badgersmc.nexus.paper.commands.arguments.PaperArgumentResolvers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebsiteSyncCommandRegistrationTest {
    @Test
    fun `existing admin and website sync subcommands share one em root`() {
        WebsiteSyncSecretArgumentRegistration.register()
        val definitions = PaperCommandScanner().scanCommands(
            "net.badgersmc.em.infrastructure.commands",
            WebsiteSyncCommandRegistrationTest::class.java.classLoader,
        )
        val em = definitions.filter { it.annotation.name == "em" }
        assertEquals(1, em.size)
        val paths = em.single().subcommands.map { it.path.joinToString(" ") }.toSet()
        assertTrue("list" in paths)
        assertTrue("auction start" in paths)
        assertTrue("websync status" in paths)
        assertTrue("websync secret" in paths)
        assertTrue("websync retry" in paths)
    }

    @Test
    fun `secret argument preserves punctuation quotes and spaces exactly`() {
        WebsiteSyncSecretArgumentRegistration.register()
        val resolver = requireNotNull(PaperArgumentResolvers.get(WebsiteSyncSecretArgument::class))
        val dispatcher = CommandDispatcher<Unit>()
        var captured: String? = null
        dispatcher.register(
            LiteralArgumentBuilder.literal<Unit>("secret").then(
                RequiredArgumentBuilder.argument<Unit, WebsiteSyncSecretArgument>("value", resolver.argumentType())
                    .executes { context -> captured = resolver.extract(context, "value").value; 1 }
            )
        )
        val exact = "alpha,beta! \"quoted value\" tail with spaces"
        dispatcher.execute("secret $exact", Unit)
        assertEquals(exact, captured)
    }
}
