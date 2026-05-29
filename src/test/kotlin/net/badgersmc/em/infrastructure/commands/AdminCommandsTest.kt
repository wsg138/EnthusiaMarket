package net.badgersmc.em.infrastructure.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.application.StallMemberService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AdminCommandsTest {

    private val sender = mockk<CommandSender>(relaxed = true)
    private val config = EnthusiaMarketConfig().apply {
        market.world = "world"
        market.regionPrefix = "stall_"
    }

    @Test fun `import delegates to service and reports counts`() {
        val service = mockk<ImportStallsService>()
        val repo = mockk<StallRepository>()
        every { service.import("world", "stall_") } returns ImportStallsService.Result(3, 1)

        val cmd = AdminCommands(service, repo, config, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        cmd.import(sender)

        verify { service.import("world", "stall_") }
        verify { sender.sendMessage(any<Component>()) }
    }

    @Test fun `list prints one line per stall`() {
        val service = mockk<ImportStallsService>()
        val repo = mockk<StallRepository>()
        every { repo.all() } returns listOf(
            Stall(StallId("s1"), "s1", "world", StallState.UNOWNED, OwnerRef.unowned(),
                  null, 0L, RentTerms.formula(1.0))
        )

        val cmd = AdminCommands(service, repo, config, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        cmd.list(sender)

        verify { sender.sendMessage(any<Component>()) }
    }

    // --- REQ-202 — member command routing through StallMemberService ---

    /**
     * Member commands resolve player names via Bukkit.getOfflinePlayer
     * (intentional — no need to invent a port for a one-line lookup).
     * MockBukkit isn't on the classpath for this suite, so stub the
     * static call once per test class.
     */
    private val stubPlayer = mockk<OfflinePlayer>(relaxed = true).also {
        every { it.uniqueId } returns UUID.randomUUID()
        every { it.name } returns "Alice"
        every { it.hasPlayedBefore() } returns true
    }

    @BeforeTest fun mockBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer(any<String>()) } returns stubPlayer
        every { Bukkit.getOfflinePlayer(any<UUID>()) } returns stubPlayer
    }

    @AfterTest fun unmockBukkit() = unmockkStatic(Bukkit::class)

    @Test fun `members add delegates to service with sender uuid as actor`() {
        val player = mockk<Player>(relaxed = true)
        val actorUuid = UUID.randomUUID()
        every { player.uniqueId } returns actorUuid

        val members = mockk<StallMemberService>(relaxed = true)
        every { members.addMember(any(), any(), any()) } returns
            StallMemberService.Result.Success(
                Stall(StallId("s1"), "s1", "world", StallState.UNOWNED, OwnerRef.unowned(),
                      null, 0L, RentTerms.formula(1.0))
            )

        val cmd = AdminCommands(
            mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            members,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        cmd.membersAdd(player, "s1", "Alice")

        verify { members.addMember(StallId("s1"), actorUuid, any<UUID>()) }
    }

    @Test fun `members add surfaces NotAuthorised back to sender`() {
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()

        val members = mockk<StallMemberService>()
        every { members.addMember(any(), any(), any()) } returns
            StallMemberService.Result.NotAuthorised

        val cmd = AdminCommands(
            mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            members,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        cmd.membersAdd(player, "s1", "Alice")

        // i18n migration in flight (handoff #22 — owned by Hermes) means
        // sendMessage takes Component, not String. We just verify a
        // message was sent — content assertion is a lang-key test that
        // belongs in the lang suite, not here.
        verify { player.sendMessage(any<Component>()) }
    }

    @Test fun `members list delegates to service with sender uuid`() {
        val player = mockk<Player>(relaxed = true)
        val actorUuid = UUID.randomUUID()
        every { player.uniqueId } returns actorUuid

        val members = mockk<StallMemberService>()
        every { members.listMembers(StallId("s1"), actorUuid) } returns
            StallMemberService.Result.Success(
                Stall(StallId("s1"), "s1", "world", StallState.UNOWNED, OwnerRef.unowned(),
                      null, 0L, RentTerms.formula(1.0))
            )

        val cmd = AdminCommands(
            mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            members,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        cmd.membersList(player, "s1")

        verify { members.listMembers(StallId("s1"), actorUuid) }
    }
}