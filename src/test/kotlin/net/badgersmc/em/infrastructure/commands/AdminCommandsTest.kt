package net.badgersmc.em.infrastructure.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.application.StallMemberService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.GuildProvider
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
        every { service.import("world", "stall_") } returns ImportStallsService.Result(3, 1, 0)

        val cmd = AdminCommands(service, repo, config, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), playerNameResolver = mockk(relaxed = true))
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

        val cmd = AdminCommands(service, repo, config, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), playerNameResolver = mockk(relaxed = true))
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

        val playerNameResolver = mockk<net.badgersmc.em.infrastructure.bedrock.PlayerNameResolver>()
        every { playerNameResolver.resolve("Alice") } returns stubPlayer

        val cmd = AdminCommands(
            mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            members,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            playerNameResolver,
        )
        cmd.membersAdd(player, "s1", "Alice")

        verify { playerNameResolver.resolve("Alice") }
        verify { members.addMember(StallId("s1"), actorUuid, any<UUID>()) }
    }

    @Test fun `members add surfaces NotAuthorised back to sender`() {
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()

        val members = mockk<StallMemberService>()
        every { members.addMember(any(), any(), any()) } returns
            StallMemberService.Result.NotAuthorised

        val playerNameResolver = mockk<net.badgersmc.em.infrastructure.bedrock.PlayerNameResolver>()
        every { playerNameResolver.resolve("Alice") } returns stubPlayer

        val cmd = AdminCommands(
            mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            members,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            playerNameResolver,
        )
        cmd.membersAdd(player, "s1", "Alice")

        verify { playerNameResolver.resolve("Alice") }
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
            mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        cmd.membersList(player, "s1")

        verify { members.listMembers(StallId("s1"), actorUuid) }
    }

    @Test fun `refreshSigns reports result counts`() {
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()

        val shop = net.badgersmc.em.domain.shop.Shop(
            id = 1L, stallId = "s1", owner = UUID.randomUUID(),
            signWorld = "world", signX = 10, signY = 64, signZ = 10,
            containerWorld = "world", containerX = 50, containerY = 64, containerZ = 60,
            sellItem = "base64item", sellAmount = 1,
            costItem = "base64cost", costAmount = 100,
            direction = net.badgersmc.em.domain.shop.SignDirection.SELL,
        )

        val shopRepo = mockk<net.badgersmc.em.domain.shop.ShopRepository>()
        every { shopRepo.all() } returns listOf(shop)

        val signState = mockk<org.bukkit.block.Sign>(relaxed = true)
        val side = mockk<org.bukkit.block.sign.SignSide>(relaxed = true)
        every { signState.getSide(org.bukkit.block.sign.Side.FRONT) } returns side
        every { signState.update() } returns true
        val world = mockk<org.bukkit.World>(relaxed = true)
        every { world.getBlockAt(10, 64, 10) } returns mockk<org.bukkit.block.Block>(relaxed = true).also {
            every { it.state } returns signState
        }
        every { Bukkit.getWorld("world") } returns world

        val signRenderer = mockk<net.badgersmc.em.application.ShopSignRenderer>(relaxed = true)
        every { signRenderer.lines(any(), any(), any(), any(), any()) } returns listOf(
            net.kyori.adventure.text.Component.text("line1"),
            net.kyori.adventure.text.Component.text("line2"),
            net.kyori.adventure.text.Component.text("line3"),
            net.kyori.adventure.text.Component.text("line4"),
        )

        val cmd = AdminCommands(
            mockk(relaxed = true), mockk(relaxed = true), config,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            shopRepo,
            signRenderer,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        cmd.refreshSigns(player)

        verify { player.sendMessage(any<Component>()) }
    }

    // --- maintenance freeze commands (CR 3694370395) ---

    /** Build AdminCommands with a controllable LangService + MaintenanceFreezeService. */
    private fun freezeCommands(
        lang: net.badgersmc.nexus.i18n.LangService,
        freeze: net.badgersmc.em.application.MaintenanceFreezeService,
    ): AdminCommands = AdminCommands(
        service = mockk(relaxed = true),
        stalls = mockk(relaxed = true),
        config = config,
        auctionService = mockk(relaxed = true),
        configManager = mockk(relaxed = true),
        auctions = mockk(relaxed = true),
        plugin = mockk(relaxed = true),
        lang = lang,
        nexusScheduler = mockk(relaxed = true),
        stallMembers = mockk(relaxed = true),
        sellOffers = mockk(relaxed = true),
        sellback = mockk(relaxed = true),
        regionMembers = mockk(relaxed = true),
        regionProvisioner = mockk(relaxed = true),
        entityCounter = mockk(relaxed = true),
        regionProvider = mockk(relaxed = true),
        stallInfo = mockk(relaxed = true),
        particleBorders = mockk(relaxed = true),
        stallEviction = mockk(relaxed = true),
        limits = mockk(relaxed = true),
        ownership = mockk(relaxed = true),
        policyService = mockk(relaxed = true),
        guildProvider = mockk(relaxed = true),
        rentResync = mockk(relaxed = true),
        shopRepository = mockk(relaxed = true),
        signRenderer = mockk(relaxed = true),
        maintenanceFreeze = freeze,
        playerNameResolver = mockk(relaxed = true),
    )

    private val freezeSince = java.time.Instant.parse("2026-06-01T10:00:00Z")

    @Test fun `maintenance freeze activated uses the activated key`() {
        val freeze = mockk<net.badgersmc.em.application.MaintenanceFreezeService>()
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>()
        every { freeze.begin(any()) } returns
            net.badgersmc.em.application.MaintenanceFreezeResult.Activated(freezeSince)
        every { lang.msg("admin.maintenance.freeze.activated") } returns
            net.kyori.adventure.text.Component.text("activated")

        freezeCommands(lang, freeze).maintenanceFreeze(sender)

        verify { lang.msg("admin.maintenance.freeze.activated") }
        verify { sender.sendMessage(any<Component>()) }
    }

    @Test fun `maintenance freeze already active injects the since value`() {
        val freeze = mockk<net.badgersmc.em.application.MaintenanceFreezeService>()
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>()
        every { freeze.begin(any()) } returns
            net.badgersmc.em.application.MaintenanceFreezeResult.AlreadyActive(freezeSince)
        every { lang.msg("admin.maintenance.freeze.already_active", "since" to freezeSince.toString()) } returns
            net.kyori.adventure.text.Component.text("already")

        freezeCommands(lang, freeze).maintenanceFreeze(sender)

        verify { lang.msg("admin.maintenance.freeze.already_active", "since" to freezeSince.toString()) }
        verify { sender.sendMessage(any<Component>()) }
    }

    @Test fun `maintenance unfreeze lifted injects stalls auctions and duration`() {
        val freeze = mockk<net.badgersmc.em.application.MaintenanceFreezeService>()
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>()
        every { freeze.end(any()) } returns net.badgersmc.em.application.MaintenanceFreezeResult.Lifted(
            stalls = 3, auctions = 2, elapsed = java.time.Duration.ofHours(26)
        )
        // formatFreezeDuration(26h) -> "1d 2h 0m"
        every { lang.msg("admin.maintenance.unfreeze.done", "stalls" to 3, "auctions" to 2, "duration" to "1d 2h 0m") } returns
            net.kyori.adventure.text.Component.text("lifted")

        freezeCommands(lang, freeze).maintenanceUnfreeze(sender)

        verify { lang.msg("admin.maintenance.unfreeze.done", "stalls" to 3, "auctions" to 2, "duration" to "1d 2h 0m") }
        verify { sender.sendMessage(any<Component>()) }
    }

    @Test fun `maintenance unfreeze not frozen uses the not_frozen key`() {
        val freeze = mockk<net.badgersmc.em.application.MaintenanceFreezeService>()
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>()
        every { freeze.end(any()) } returns net.badgersmc.em.application.MaintenanceFreezeResult.NotFrozen
        every { lang.msg("admin.maintenance.unfreeze.not_frozen") } returns
            net.kyori.adventure.text.Component.text("not frozen")

        freezeCommands(lang, freeze).maintenanceUnfreeze(sender)

        verify { lang.msg("admin.maintenance.unfreeze.not_frozen") }
        verify { sender.sendMessage(any<Component>()) }
    }

    @Test fun `maintenance status active injects since and duration`() {
        val freeze = mockk<net.badgersmc.em.application.MaintenanceFreezeService>()
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>()
        every { freeze.status() } returns
            net.badgersmc.em.application.MaintenanceFreezeStatus(frozen = true, since = freezeSince)
        // duration is wall-clock (Instant.now()) — match shape, not value
        every {
            lang.msg(
                "admin.maintenance.status.active",
                "since" to freezeSince.toString(),
                match<Pair<String, Any?>> { it.first == "duration" && it.second is String }
            )
        } returns net.kyori.adventure.text.Component.text("active")

        freezeCommands(lang, freeze).maintenanceStatus(sender)

        verify {
            lang.msg(
                "admin.maintenance.status.active",
                "since" to freezeSince.toString(),
                match<Pair<String, Any?>> { it.first == "duration" && it.second is String }
            )
        }
        verify { sender.sendMessage(any<Component>()) }
    }

    @Test fun `maintenance status inactive uses the inactive key`() {
        val freeze = mockk<net.badgersmc.em.application.MaintenanceFreezeService>()
        val lang = mockk<net.badgersmc.nexus.i18n.LangService>()
        every { freeze.status() } returns
            net.badgersmc.em.application.MaintenanceFreezeStatus(frozen = false, since = null)
        every { lang.msg("admin.maintenance.status.inactive") } returns
            net.kyori.adventure.text.Component.text("inactive")

        freezeCommands(lang, freeze).maintenanceStatus(sender)

        verify { lang.msg("admin.maintenance.status.inactive") }
        verify { sender.sendMessage(any<Component>()) }
    }
}
