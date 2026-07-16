@file:Suppress("FunctionNaming")

package net.badgersmc.em.websync

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.OwnerRef
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicOwnerProjectionTest {
    private val guilds = mockk<GuildProvider>()
    private val guildId = UUID.randomUUID()
    private val leaderId = UUID.randomUUID()

    @Test
    fun `guild with active banner projects bounded ordered design`() {
        every { guilds.guildById(guildId.toString()) } returns GuildProvider.GuildRef(guildId.toString(), "Synthetic Guild")
        every { guilds.visualById(guildId.toString()) } returns GuildProvider.GuildVisual(
            leaderId,
            GuildProvider.BannerDesign(
                "BLUE",
                listOf(
                    GuildProvider.BannerPattern("STRIPE_TOP", "WHITE"),
                    GuildProvider.BannerPattern("CROSS", "RED"),
                ),
            ),
        )
        val projection = PublicOwnerProjection(guilds, PublicOwnerAvatarResolver { false }) { "SyntheticLeader" }

        val owner = projection.project(OwnerRef.guild(guildId.toString())).owner

        assertEquals("GUILD_BANNER", owner.avatar.kind)
        assertEquals("BLUE", owner.avatar.banner?.baseColor)
        assertEquals(listOf("STRIPE_TOP", "CROSS"), owner.avatar.banner?.patterns?.map { it.type })
        assertNull(owner.avatarUrl)
    }

    @Test
    fun `guild without banner uses leader head including outer layer`() {
        every { guilds.guildById(guildId.toString()) } returns GuildProvider.GuildRef(guildId.toString(), "Synthetic Guild")
        every { guilds.visualById(guildId.toString()) } returns GuildProvider.GuildVisual(leaderId, null)
        val projection = PublicOwnerProjection(guilds, PublicOwnerAvatarResolver { false }) { "SyntheticLeader" }

        val owner = projection.project(OwnerRef.guild(guildId.toString())).owner

        assertEquals("MINECRAFT_HEAD", owner.avatar.kind)
        assertEquals(true, owner.avatar.includesOuterLayer)
        assertTrue(owner.avatarUrl!!.contains(leaderId.toString()))
        assertEquals(owner.avatarUrl, owner.avatar.url)
    }

    @Test
    fun `guild without resolvable leader keeps generic guild fallback`() {
        every { guilds.guildById(guildId.toString()) } returns GuildProvider.GuildRef(guildId.toString(), "Synthetic Guild")
        every { guilds.visualById(guildId.toString()) } returns GuildProvider.GuildVisual(null, null)
        val projection = PublicOwnerProjection(guilds, PublicOwnerAvatarResolver { false }) { null }

        val owner = projection.project(OwnerRef.guild(guildId.toString())).owner

        assertEquals("GUILD", owner.avatar.kind)
        assertNull(owner.avatarUrl)
    }

    @Test
    fun `unresolved player keeps explicit fallback metadata`() {
        val playerId = UUID.randomUUID()
        val projection = PublicOwnerProjection(guilds, PublicOwnerAvatarResolver { false }) { null }

        val owner = projection.project(OwnerRef.solo(playerId)).owner

        assertEquals("MINECRAFT_HEAD", owner.avatar.kind)
        assertEquals("JAVA", owner.avatar.source)
        assertEquals(true, owner.avatar.includesOuterLayer)
    }

    @Test
    fun `captured Bedrock URL is projected consistently for solo and guild leader heads`() {
        val playerId = UUID.randomUUID()
        val headUrl = "https://market-api.enthusia.info/v1/player-heads/${"b".repeat(64)}.png"
        val resolver = PublicOwnerAvatarResolver(capturedHead = { headUrl }, floodgatePlayer = { true })
        every { guilds.guildById(guildId.toString()) } returns GuildProvider.GuildRef(guildId.toString(), "Synthetic Guild")
        every { guilds.visualById(guildId.toString()) } returns GuildProvider.GuildVisual(playerId, null)
        val projection = PublicOwnerProjection(guilds, resolver) { "SyntheticBedrock" }

        listOf(
            projection.project(OwnerRef.solo(playerId)).owner,
            projection.project(OwnerRef.guild(guildId.toString())).owner,
        ).forEach { owner ->
            assertEquals("BEDROCK_CAPTURED", owner.avatar.source)
            assertEquals(headUrl, owner.avatarUrl)
            assertEquals(headUrl, owner.avatar.url)
            assertEquals(true, owner.avatar.includesOuterLayer)
        }
    }
}
