package net.badgersmc.em.infrastructure.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.command.CommandSender
import kotlin.test.Test

class AdminCommandsTest {

    private val sender = mockk<CommandSender>(relaxed = true)

    @Test fun `import delegates to service and reports counts`() {
        val service = mockk<ImportStallsService>()
        val repo = mockk<StallRepository>()
        every { service.import("world", "stall_") } returns ImportStallsService.Result(3, 1)

        val cmd = AdminCommands(service, repo, world = "world", prefix = "stall_")
        cmd.runImport(sender)

        verify { service.import("world", "stall_") }
        verify { sender.sendMessage(match<String> { it.contains("created=3") && it.contains("skipped=1") }) }
    }

    @Test fun `list prints one line per stall`() {
        val service = mockk<ImportStallsService>()
        val repo = mockk<StallRepository>()
        every { repo.all() } returns listOf(
            Stall(StallId("s1"), "s1", "world", StallState.UNOWNED, OwnerRef.unowned(),
                  null, 0L, RentTerms.formula(1.0))
        )

        val cmd = AdminCommands(service, repo, world = "world", prefix = "stall_")
        cmd.runList(sender)

        verify { sender.sendMessage(match<String> { it.contains("s1") && it.contains("UNOWNED") }) }
    }
}
