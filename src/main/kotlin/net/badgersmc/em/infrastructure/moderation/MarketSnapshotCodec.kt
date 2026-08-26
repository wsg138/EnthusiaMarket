package net.badgersmc.em.infrastructure.moderation

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.SortedMap
import java.util.TreeMap
import java.util.UUID

internal data class MarketSnapshot(
    val stall: ModeratedStallSnapshot,
    val shops: List<ModeratedShopSnapshot>,
    val blacklist: ModeratedBlacklistSnapshot?,
)

internal data class CapturedMarketSnapshot(
    val snapshot: MarketSnapshot,
    val stallRevision: Long,
    val json: String,
    val checksum: String,
)

internal data class ModeratedStallSnapshot(
    val id: String,
    val regionId: String,
    val world: String,
    val state: String,
    val ownerType: String,
    val ownerId: String,
    val ownerSince: Long?,
    val winningBid: Long,
    val rentMode: String,
    val rentPct: Double,
    val rentFlat: Long,
    val members: List<String>,
    val maxMembers: Int,
    val nextRentAt: Long?,
    val kind: String,
    val extraEntities: SortedMap<String, Int>,
    val extraTotal: Int,
)

internal data class ModeratedShopSnapshot(
    val id: Long,
    val stallId: String,
    val owner: String,
    val signWorld: String,
    val signX: Int,
    val signY: Int,
    val signZ: Int,
    val containerWorld: String,
    val containerX: Int,
    val containerY: Int,
    val containerZ: Int,
    val sellItem: String,
    val sellAmount: Int,
    val costItem: String,
    val costAmount: Int,
    val trusted: List<String>,
    val hopperAllowIn: Boolean,
    val hopperAllowOut: Boolean,
    val frozen: Boolean,
    val adminShop: Boolean,
    val direction: String,
    val searchEnabled: Boolean,
    val sellMaterial: String?,
    val stockCount: Int,
)

internal data class ModeratedBlacklistSnapshot(
    val playerId: String,
    val status: String,
    val expiresAt: Long?,
    val caseId: String,
    val operationId: String,
    val revision: Long,
    val updatedAt: Long,
)

internal class MarketSnapshotCodec(
    private val gson: Gson = Gson(),
) {
    fun capture(connection: Connection, stallId: String, targetId: UUID): CapturedMarketSnapshot {
        val (stall, revision) = readStall(connection, stallId)
            ?: throw MarketModerationRejected("Stall '$stallId' does not exist")
        val snapshot = MarketSnapshot(
            stall = stall,
            shops = readShops(connection, stallId),
            blacklist = readBlacklist(connection, targetId),
        )
        return captured(snapshot, revision)
    }

    fun captured(snapshot: MarketSnapshot, stallRevision: Long): CapturedMarketSnapshot {
        val json = gson.toJson(snapshot)
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAXIMUM_SNAPSHOT_BYTES) {
            throw MarketModerationRejected("Market snapshot exceeds the 1 MiB safety limit")
        }
        return CapturedMarketSnapshot(snapshot, stallRevision, json, sha256(json))
    }

    fun decode(json: String): MarketSnapshot {
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAXIMUM_SNAPSHOT_BYTES) {
            throw MarketModerationConflict("Stored market snapshot exceeds the safety limit")
        }
        val snapshotObject = parseSnapshotRoot(json)
        validateSnapshotShape(snapshotObject)
        return parseSnapshot(snapshotObject)
    }

    private fun parseSnapshot(snapshotObject: JsonObject): MarketSnapshot {
        val decoded = try {
            gson.fromJson(snapshotObject, MarketSnapshot::class.java)
        } catch (_: RuntimeException) {
            throw MarketModerationConflict("Stored market snapshot has an invalid structure")
        }
        return decoded ?: throw MarketModerationConflict("Stored market snapshot is empty")
    }

    fun decodeVerified(json: String, expectedChecksum: String): MarketSnapshot? {
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAXIMUM_SNAPSHOT_BYTES) return null
        val actual = sha256(json).toByteArray(StandardCharsets.US_ASCII)
        val expected = expectedChecksum.lowercase().toByteArray(StandardCharsets.US_ASCII)
        if (!MessageDigest.isEqual(actual, expected)) return null
        return try {
            decode(json)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun readStall(
        connection: Connection,
        stallId: String,
    ): Pair<ModeratedStallSnapshot, Long>? = connection.prepareStatement(
        "SELECT * FROM stalls WHERE id = ?",
    ).use { statement ->
        statement.setString(1, stallId)
        statement.executeQuery().use { result ->
            if (!result.next()) return null
            val revision = result.getLong("moderation_revision")
            readStallRow(result) to revision
        }
    }

    private fun readStallRow(result: ResultSet): ModeratedStallSnapshot = ModeratedStallSnapshot(
        id = result.getString("id"),
        regionId = result.getString("region_id"),
        world = result.getString("world"),
        state = result.getString("state"),
        ownerType = result.getString("owner_type"),
        ownerId = result.getString("owner_id"),
        ownerSince = nullableLong(result, "owner_since"),
        winningBid = result.getLong("winning_bid"),
        rentMode = result.getString("rent_mode"),
        rentPct = result.getDouble("rent_pct"),
        rentFlat = result.getLong("rent_flat"),
        members = normalizedList(result.getString("members")),
        maxMembers = result.getInt("max_members"),
        nextRentAt = nullableLong(result, "next_rent_at"),
        kind = result.getString("kind"),
        extraEntities = normalizedEntityMap(result.getString("extra_entities")),
        extraTotal = result.getInt("extra_total"),
    )

    private fun readShops(connection: Connection, stallId: String): List<ModeratedShopSnapshot> =
        connection.prepareStatement("SELECT * FROM shop_items WHERE stall_id = ? ORDER BY id").use { statement ->
            statement.setString(1, stallId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        if (size >= MAXIMUM_SHOPS) {
                            throw MarketModerationRejected("Stall has more than $MAXIMUM_SHOPS shops")
                        }
                        add(readShopRow(result))
                    }
                }
            }
        }

    private fun readShopRow(result: ResultSet): ModeratedShopSnapshot = ModeratedShopSnapshot(
        id = result.getLong("id"),
        stallId = result.getString("stall_id"),
        owner = result.getString("owner"),
        signWorld = result.getString("sign_world"),
        signX = result.getInt("sign_x"),
        signY = result.getInt("sign_y"),
        signZ = result.getInt("sign_z"),
        containerWorld = result.getString("container_world"),
        containerX = result.getInt("container_x"),
        containerY = result.getInt("container_y"),
        containerZ = result.getInt("container_z"),
        sellItem = result.getString("sell_item"),
        sellAmount = result.getInt("sell_amount"),
        costItem = result.getString("cost_item"),
        costAmount = result.getInt("cost_amount"),
        trusted = normalizedList(result.getString("trusted")),
        hopperAllowIn = result.getBoolean("hopper_allow_in"),
        hopperAllowOut = result.getBoolean("hopper_allow_out"),
        frozen = result.getBoolean("frozen"),
        adminShop = result.getBoolean("admin_shop"),
        direction = result.getString("direction"),
        searchEnabled = result.getBoolean("search_enabled"),
        sellMaterial = result.getString("sell_material"),
        stockCount = result.getInt("stock_count"),
    )

    private fun readBlacklist(connection: Connection, targetId: UUID): ModeratedBlacklistSnapshot? =
        connection.prepareStatement(
            "SELECT * FROM market_stall_blacklists WHERE player_uuid = ?",
        ).use { statement ->
            statement.setString(1, targetId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                ModeratedBlacklistSnapshot(
                    playerId = result.getString("player_uuid"),
                    status = result.getString("status"),
                    expiresAt = nullableLong(result, "expires_at"),
                    caseId = result.getString("case_id"),
                    operationId = result.getString("operation_id"),
                    revision = result.getLong("revision"),
                    updatedAt = result.getLong("updated_at"),
                )
            }
        }

    private fun normalizedList(raw: String?): List<String> = raw.orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()

    private fun normalizedEntityMap(raw: String?): SortedMap<String, Int> {
        val result = TreeMap<String, Int>()
        raw.orEmpty().split(',').filter(String::isNotBlank).forEach { entry ->
            val parts = entry.trim().split(':', limit = 2)
            result[parts[0]] = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
        }
        return result
    }

    private fun parseSnapshotRoot(json: String): JsonObject {
        val root = try {
            JsonParser.parseString(json)
        } catch (_: RuntimeException) {
            throw MarketModerationConflict("Stored market snapshot is not valid JSON")
        }
        if (!root.isJsonObject) throw MarketModerationConflict("Stored market snapshot root is not an object")
        return root.asJsonObject
    }

    private fun validateSnapshotShape(snapshot: JsonObject) {
        val shops = snapshot["shops"]
        val blacklist = snapshot["blacklist"]
        val problem = when {
            snapshot["stall"]?.isJsonObject != true -> "Stored market snapshot has no stall object"
            shops?.isJsonArray != true || shops.asJsonArray.any { !it.isJsonObject } ->
                "Stored market snapshot has an invalid shops array"
            blacklist != null && !blacklist.isJsonNull && !blacklist.isJsonObject ->
                "Stored market snapshot has an invalid blacklist object"
            else -> null
        }
        if (problem != null) throw MarketModerationConflict(problem)
    }

    private fun nullableLong(result: ResultSet, column: String): Long? {
        val value = result.getLong(column)
        return if (result.wasNull()) null else value
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAXIMUM_SHOPS = 100
        const val MAXIMUM_SNAPSHOT_BYTES = 1_048_576
    }
}

internal class MarketModerationRejected(message: String) : IllegalArgumentException(message)

internal class MarketModerationConflict(message: String) : IllegalStateException(message)
