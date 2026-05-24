package net.badgersmc.em.infrastructure.lumaguilds

import io.mockk.every
import io.mockk.mockk
import net.lumalyte.lg.application.services.BankService
import net.lumalyte.lg.application.services.GuildService
import net.lumalyte.lg.application.services.MemberService
import net.lumalyte.lg.application.services.RankService
import net.lumalyte.lg.domain.entities.Guild
import net.lumalyte.lg.domain.entities.Member
import net.lumalyte.lg.domain.entities.Rank
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LumaGuildsGuildProviderTest {

    private val guildId = UUID.randomUUID()
    private val playerId = UUID.randomUUID()
    private val guildService = mockk<GuildService>()
    private val memberService = mockk<MemberService>()
    private val rankService = mockk<RankService>()
    private val bankService = mockk<BankService>()
    private val provider = LumaGuildsGuildProvider(
        guildService = guildService,
        memberService = memberService,
        rankService = rankService,
        bankService = bankService,
    )

    // --- guildOf ---

    @Test
    fun `guildOf returns GuildRef for player's first guild`() {
        every { memberService.getPlayerGuilds(playerId) } returns setOf(guildId)
        every { guildService.getGuild(guildId) } returns Guild(
            id = guildId,
            name = "TestGuild",
            createdAt = Instant.now(),
        )

        val result = provider.guildOf(playerId)

        assertNotNull(result)
        assertEquals(guildId.toString(), result.id)
        assertEquals("TestGuild", result.name)
    }

    @Test
    fun `guildOf returns null when player has no guilds`() {
        every { memberService.getPlayerGuilds(playerId) } returns emptySet()

        assertNull(provider.guildOf(playerId))
    }

    @Test
    fun `guildOf returns null when guild lookup fails`() {
        every { memberService.getPlayerGuilds(playerId) } returns setOf(guildId)
        every { guildService.getGuild(guildId) } returns null

        assertNull(provider.guildOf(playerId))
    }

    // --- guildById ---

    @Test
    fun `guildById returns GuildRef for valid UUID string`() {
        val id = guildId.toString()
        every { guildService.getGuild(guildId) } returns Guild(
            id = guildId,
            name = "MyGuild",
            createdAt = Instant.now(),
        )

        val result = provider.guildById(id)

        assertNotNull(result)
        assertEquals(id, result.id)
        assertEquals("MyGuild", result.name)
    }

    @Test
    fun `guildById returns null for invalid UUID string`() {
        assertNull(provider.guildById("not-a-uuid"))
    }

    @Test
    fun `guildById returns null when guild not found`() {
        every { guildService.getGuild(guildId) } returns null
        assertNull(provider.guildById(guildId.toString()))
    }

    // --- isMember ---

    @Test
    fun `isMember returns true when member exists`() {
        every { memberService.getMember(playerId, guildId) } returns Member(
            playerId = playerId,
            guildId = guildId,
            rankId = UUID.randomUUID(),
            joinedAt = Instant.now(),
        )
        assertTrue(provider.isMember(playerId, guildId.toString()))
    }

    @Test
    fun `isMember returns false when member does not exist`() {
        every { memberService.getMember(playerId, guildId) } returns null
        assertFalse(provider.isMember(playerId, guildId.toString()))
    }

    @Test
    fun `isMember returns false for invalid guild ID`() {
        assertFalse(provider.isMember(playerId, "not-a-uuid"))
    }

    // --- hasPermission ---

    @Test
    fun `hasPermission returns true when player rank priority is higher`() {
        val playerRankId = UUID.randomUUID()
        val officerRankId = UUID.randomUUID()
        every { memberService.getPlayerRankId(playerId, guildId) } returns playerRankId
        every { rankService.listRanks(guildId) } returns setOf(
            Rank(id = playerRankId, guildId = guildId, name = "Owner", priority = 0),
            Rank(id = officerRankId, guildId = guildId, name = "Officer", priority = 2),
        )

        assertTrue(provider.hasPermission(playerId, guildId.toString(), "Officer"))
    }

    @Test
    fun `hasPermission returns true when player rank equals target rank`() {
        val playerRankId = UUID.randomUUID()
        every { memberService.getPlayerRankId(playerId, guildId) } returns playerRankId
        every { rankService.listRanks(guildId) } returns setOf(
            Rank(id = playerRankId, guildId = guildId, name = "Member", priority = 5),
        )

        assertTrue(provider.hasPermission(playerId, guildId.toString(), "Member"))
    }

    @Test
    fun `hasPermission returns false when player rank priority is lower`() {
        val playerRankId = UUID.randomUUID()
        val officerRankId = UUID.randomUUID()
        every { memberService.getPlayerRankId(playerId, guildId) } returns playerRankId
        every { rankService.listRanks(guildId) } returns setOf(
            Rank(id = officerRankId, guildId = guildId, name = "Officer", priority = 2),
            Rank(id = playerRankId, guildId = guildId, name = "Member", priority = 5),
        )

        assertFalse(provider.hasPermission(playerId, guildId.toString(), "Officer"))
    }

    @Test
    fun `hasPermission returns false when player has no rank`() {
        every { memberService.getPlayerRankId(playerId, guildId) } returns null
        assertFalse(provider.hasPermission(playerId, guildId.toString(), "Officer"))
    }

    @Test
    fun `hasPermission returns false for invalid guild ID`() {
        assertFalse(provider.hasPermission(playerId, "not-a-uuid", "Officer"))
    }

    @Test
    fun `hasPermission returns false when target rank not found`() {
        val playerRankId = UUID.randomUUID()
        every { memberService.getPlayerRankId(playerId, guildId) } returns playerRankId
        every { rankService.listRanks(guildId) } returns setOf(
            Rank(id = playerRankId, guildId = guildId, name = "Member", priority = 5),
        )

        assertFalse(provider.hasPermission(playerId, guildId.toString(), "NonExistentRank"))
    }

    @Test
    fun `hasPermission matches rank name case insensitively`() {
        val playerRankId = UUID.randomUUID()
        val officerRankId = UUID.randomUUID()
        every { memberService.getPlayerRankId(playerId, guildId) } returns playerRankId
        every { rankService.listRanks(guildId) } returns setOf(
            Rank(id = playerRankId, guildId = guildId, name = "Owner", priority = 0),
            Rank(id = officerRankId, guildId = guildId, name = "officer", priority = 2),
        )

        assertTrue(provider.hasPermission(playerId, guildId.toString(), "OFFICER"))
    }

    // --- bankBalance ---

    @Test
    fun `bankBalance delegates to bank service`() {
        every { bankService.getBalance(guildId) } returns 5000
        assertEquals(5000L, provider.bankBalance(guildId.toString()))
    }

    @Test
    fun `bankBalance returns zero for invalid guild ID`() {
        assertEquals(0L, provider.bankBalance("not-a-uuid"))
    }

    // --- bankWithdraw ---

    @Test
    fun `bankWithdraw returns true on success`() {
        every { bankService.withdraw(guildId, any(), 1000, any()) } returns mockk()
        assertTrue(provider.bankWithdraw(guildId.toString(), 1000L))
    }

    @Test
    fun `bankWithdraw returns false on failure`() {
        every { bankService.withdraw(guildId, any(), 1000, any()) } returns null
        assertFalse(provider.bankWithdraw(guildId.toString(), 1000L))
    }

    @Test
    fun `bankWithdraw returns false for invalid guild ID`() {
        assertFalse(provider.bankWithdraw("not-a-uuid", 1000L))
    }

    // --- bankDeposit ---

    @Test
    fun `bankDeposit returns true on success`() {
        every { bankService.deposit(guildId, any(), 500, any()) } returns mockk()
        assertTrue(provider.bankDeposit(guildId.toString(), 500L))
    }

    @Test
    fun `bankDeposit returns false on failure`() {
        every { bankService.deposit(guildId, any(), 500, any()) } returns null
        assertFalse(provider.bankDeposit(guildId.toString(), 500L))
    }

    @Test
    fun `bankDeposit returns false for invalid guild ID`() {
        assertFalse(provider.bankDeposit("not-a-uuid", 500L))
    }

    // --- onDissolved / handleDisbanded ---

    @Test
    fun `onDissolved registers callback called by handleDisbanded`() {
        var captured: String? = null
        provider.onDissolved { captured = it }
        provider.handleDisbanded(guildId.toString())
        assertEquals(guildId.toString(), captured)
    }

    @Test
    fun `multiple dissolve handlers all receive notification`() {
        val results = mutableListOf<String>()
        provider.onDissolved { results.add("a:$it") }
        provider.onDissolved { results.add("b:$it") }
        provider.handleDisbanded(guildId.toString())
        assertEquals(listOf("a:${guildId}", "b:${guildId}"), results)
    }
}