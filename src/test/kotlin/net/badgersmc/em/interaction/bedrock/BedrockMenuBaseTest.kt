package net.badgersmc.em.interaction.bedrock

import io.mockk.*
import net.badgersmc.em.interaction.Menu
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
        val menu = object : BedrockMenuBase(player, mockk<Logger>(relaxed = true)) {
            override fun buildForm() = SimpleForm.builder().title("Test").content("test").button("OK").build()
            override fun sendForm(form: Form) { formSent = true }
        }

        menu.open()

        assert(formSent) { "sendForm() must be called on open()" }
    }

    @Test
    fun `menu does not throw when Floodgate is absent`() {
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { name } returns "TestPlayer"
            every { sendMessage(any<String>()) } returns Unit
        }

        val menu = object : BedrockMenuBase(player, mockk<Logger>(relaxed = true)) {
            override fun buildForm() = SimpleForm.builder().title("Test").content("test").button("OK").build()
            override fun sendForm(form: Form) { throw RuntimeException("Floodgate not found") }
        }

        // Should not throw
        menu.open()

        verify { player.sendMessage("§cUnable to open menu. Please try again or use the Java interface.") }
    }

    @Test
    fun `menu calls buildForm on open`() {
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { name } returns "TestPlayer"
        }

        var buildCalled = false
        val menu = object : BedrockMenuBase(player, mockk<Logger>(relaxed = true)) {
            override fun buildForm(): SimpleForm {
                buildCalled = true
                return SimpleForm.builder().title("Test").content("test").button("OK").build()
            }
            override fun sendForm(form: Form) { }
        }

        menu.open()

        assert(buildCalled) { "buildForm() must be called on open()" }
    }
}