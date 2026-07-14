package net.badgersmc.em.websync

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebsiteSyncContractFixtureTest {
    @Test
    fun `generate exact plugin payloads for the Worker contract suite`() {
        val epoch = "contract-epoch"
        val sentAt = "2026-07-14T12:00:00Z"
        val item = PublicItem("DIAMOND", "Diamond", 1, null, PublicItemMetadata())
        val currency = PublicItem("PAPER", "Currency", 1, null, PublicItemMetadata())
        val shop = PublicShop(
            1, PublicIdentity("00000000-0000-4000-8000-000000000001", "P2wn"), "SELL",
            item, 64, currency, 100_000,
            PublicInteraction("world", 1, 64, 2, "SHOP_SIGN"), 128, 2, true,
        )
        val stalls = (1..71).map { number ->
            when (number) {
                1 -> stall("stall1", unowned(), listOf(shop))
                2 -> stall("stall2", solo())
                3 -> stall("stall3", guild())
                else -> stall("stall$number", unowned())
            }
        }
        val test = WebsiteSyncJson.bytes(TestRequest(1, "enthusia-main", epoch, TEST_EVENT, sentAt, "website-sync-test"))
        val update = WebsiteSyncJson.bytes(StallUpdateRequest(1, "enthusia-main", epoch, STALL_EVENT, sentAt, 7, stalls[0]))
        val full = WebsiteSyncJson.bytes(FullSyncRequest(1, "enthusia-main", epoch, FULL_EVENT, sentAt, 9, sentAt,
            stalls.map { RevisionedStall(6, it) }))

        assertTrue(test.size <= 32 * 1024)
        assertTrue(update.size <= WebsiteSyncOutbox.STALL_BODY_LIMIT)
        assertTrue(full.size <= WebsiteSyncOutbox.FULL_BODY_LIMIT)
        assertContentEquals(update, WebsiteSyncJson.bytes(
            StallUpdateRequest(1, "enthusia-main", epoch, STALL_EVENT, sentAt, 7, stalls[0])))
        val signature = MarketRequestSigner.sign(SECRET, "PUT", "/internal/v1/stalls/stall1", "enthusia-main",
            "1720958400000", STALL_EVENT, update)
        assertEquals(signature, MarketRequestSigner.sign(SECRET, "PUT", "/internal/v1/stalls/stall1", "enthusia-main",
            "1720958400000", STALL_EVENT, update))

        val root = JsonParser.parseString(update.toString(Charsets.UTF_8)).asJsonObject
        val publicStall = root.getAsJsonObject("stall")
        assertTrue(publicStall.getAsJsonObject("owner").get("id").isJsonNull)
        assertTrue(publicStall.getAsJsonObject("owner").get("uuid").isJsonNull)
        assertTrue(publicStall.getAsJsonObject("owner").get("avatarUrl").isJsonNull)
        assertTrue(publicStall.get("ownerSince").isJsonNull)
        assertTrue(publicStall.get("nextRentAt").isJsonNull)
        assertTrue(publicStall.getAsJsonArray("shops")[0].asJsonObject
            .getAsJsonObject("sellItem").get("icon").isJsonNull)

        val output = Path.of(System.getProperty("contractOutputDir", "build/contract/website-sync"))
        Files.createDirectories(output)
        write(output, "test.json", test)
        write(output, "stall.json", update)
        write(output, "full.json", full)
        write(output, "unowned-stall.json", WebsiteSyncJson.bytes(stalls[0]))
        write(output, "solo-stall.json", WebsiteSyncJson.bytes(stalls[1]))
        write(output, "guild-stall.json", WebsiteSyncJson.bytes(stalls[2]))
    }

    private fun write(directory: Path, name: String, bytes: ByteArray) {
        Files.write(directory.resolve(name), bytes)
    }

    private fun stall(id: String, owner: PublicOwner, shops: List<PublicShop> = emptyList()) = PublicStall(
        id, "building-1", 1, PublicLocation("world", 1, 64, 2), owner,
        null, null, emptyList(), shops,
    )

    private fun unowned() = PublicOwner("NONE", null, null, "Unowned", null, PublicAvatar("NONE"))
    private fun solo() = PublicOwner(
        "PLAYER", PROXY_STYLE_UUID, UUID.fromString(PROXY_STYLE_UUID).toString(),
        "P2wn", null, PublicAvatar("MINECRAFT_HEAD", "JAVA", true),
    )
    private fun guild() = PublicOwner("GUILD", "guild-1", null, "Example Guild", null, PublicAvatar("GUILD"))

    companion object {
        private const val SECRET = "contract-test-secret"
        private const val TEST_EVENT = "00000000-0000-4000-8000-000000000010"
        private const val STALL_EVENT = "00000000-0000-4000-8000-000000000011"
        private const val FULL_EVENT = "00000000-0000-4000-8000-000000000012"
        private const val PROXY_STYLE_UUID = "00000000-0000-0009-0000-000000000001"
    }
}
