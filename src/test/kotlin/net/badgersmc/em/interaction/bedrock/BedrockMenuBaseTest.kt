package net.badgersmc.em.interaction.bedrock

import io.mockk.*
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.Form
import org.geysermc.cumulus.form.SimpleForm
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test

class BedrockMenuBaseTest {

    @Test
    fun `menu opens without throwing when Floodgate is available`() {
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { name } returns "TestPlayer"
        }

        var formSent = false
        val menu = object : BedrockMenuBase(player, mockk<Logger>(relaxed = true), mockk(relaxed = true)) {
            override fun buildForm() = SimpleForm.builder().title("Test").content("test").button("OK").build()
            override fun sendForm(form: Form) { formSent = true }
        }

        menu.open(player)

        assert(formSent) { "sendForm() must be called on open()" }
    }

    @Test
    fun `menu does not throw when Floodgate is absent`() {
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { name } returns "TestPlayer"
            every { sendMessage(any<Component>()) } returns Unit
        }

        val menu = object : BedrockMenuBase(player, mockk<Logger>(relaxed = true), mockk(relaxed = true)) {
            override fun buildForm() = SimpleForm.builder().title("Test").content("test").button("OK").build()
            override fun sendForm(form: Form) {
                @Suppress("TooGenericExceptionThrown")
                throw RuntimeException("Floodgate not found")
            }
        }

        // Should not throw
        menu.open(player)

        verify { player.sendMessage(any<Component>()) }
    }

    @Test
    fun `menu calls buildForm on open`() {
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { name } returns "TestPlayer"
        }

        var buildCalled = false
        val menu = object : BedrockMenuBase(player, mockk<Logger>(relaxed = true), mockk(relaxed = true)) {
            override fun buildForm(): SimpleForm {
                buildCalled = true
                return SimpleForm.builder().title("Test").content("test").button("OK").build()
            }
            @Suppress("EmptyFunctionBlock")
            override fun sendForm(form: Form) { }
        }

        menu.open(player)

        assert(buildCalled) { "buildForm() must be called on open()" }
    }
}