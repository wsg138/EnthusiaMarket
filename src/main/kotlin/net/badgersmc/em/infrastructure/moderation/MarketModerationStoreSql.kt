package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketOperationRecord
import net.enthusia.market.api.moderation.MarketOwnership
import net.enthusia.market.api.moderation.MarketStallRecord
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Low-level JDBC row/binding work for [JdbcMarketModerationStore]. */
internal object MarketModerationStoreSql {
    fun restoreStall(connection: Connection, stall: ModeratedStallSnapshot, expectedRevision: Long) {
        connection.prepareStatement(RESTORE_STALL_SQL).use { statement ->
            bindRestoredStall(statement, stall, expectedRevision)
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market stall changed during restoration")
            }
        }
    }

    fun restoreShopFlags(connection: Connection, shops: List<ModeratedShopSnapshot>) {
        connection.prepareStatement(
            "UPDATE shop_items SET frozen = ? WHERE id = ? AND stall_id = ?",
        ).use { statement ->
            shops.forEach { shop -> restoreShopFlag(statement, shop) }
        }
    }

    fun updateOperation(
        connection: Connection,
        operation: MarketOperationRow,
        update: OperationUpdate,
    ): MarketOperationRow {
        connection.prepareStatement(UPDATE_OPERATION_SQL).use { statement ->
            bindOperationUpdate(statement, operation, update)
            if (statement.executeUpdate() != 1) {
                throw MarketModerationConflict("Market operation journal changed concurrently")
            }
        }
        return checkNotNull(connection.findMarketOperation(operation.operationId))
    }

    fun stallRecord(result: ResultSet): MarketStallRecord {
        val ownerType = MarketOwnership.Type.valueOf(result.getString("owner_type"))
        val ownerId = result.getString("owner_id").takeIf { ownerType != MarketOwnership.Type.NONE }
        return MarketStallRecord(
            result.getString("id"),
            result.getString("world"),
            result.getString("state"),
            MarketOwnership(ownerType, Optional.ofNullable(ownerId)),
            result.getLong("moderation_revision"),
            result.getString("review_due_at") != null,
            Optional.ofNullable(nullableLong(result, "review_due_at")?.let(Instant::ofEpochMilli)),
        )
    }

    private fun bindRestoredStall(
        statement: PreparedStatement,
        stall: ModeratedStallSnapshot,
        expectedRevision: Long,
    ) {
        statement.setString(1, stall.regionId)
        statement.setString(2, stall.world)
        statement.setString(3, stall.state)
        statement.setString(4, stall.ownerType)
        statement.setString(5, stall.ownerId)
        statement.setNullableLong(6, stall.ownerSince)
        statement.setLong(7, stall.winningBid)
        statement.setString(8, stall.rentMode)
        statement.setDouble(9, stall.rentPct)
        statement.setLong(10, stall.rentFlat)
        statement.setString(11, stall.members.joinToString(","))
        statement.setInt(12, stall.maxMembers)
        statement.setNullableLong(13, stall.nextRentAt)
        statement.setString(14, stall.kind)
        statement.setString(15, stall.extraEntities.entries.joinToString(",") { "${it.key}:${it.value}" })
        statement.setInt(16, stall.extraTotal)
        statement.setString(17, stall.id)
        statement.setLong(18, expectedRevision)
    }

    private fun restoreShopFlag(statement: PreparedStatement, shop: ModeratedShopSnapshot) {
        statement.setBoolean(1, shop.frozen)
        statement.setLong(2, shop.id)
        statement.setString(3, shop.stallId)
        if (statement.executeUpdate() != 1) {
            throw MarketModerationConflict("A market shop disappeared during restoration")
        }
    }

    private fun bindOperationUpdate(
        statement: PreparedStatement,
        operation: MarketOperationRow,
        update: OperationUpdate,
    ) {
        statement.setString(1, update.state.name)
        statement.setString(2, update.currentChecksum)
        if (update.reviewerId == null) statement.setNull(3, Types.VARCHAR)
        else statement.setString(3, update.reviewerId.toString())
        statement.setString(4, update.detail)
        statement.setLong(5, update.updatedAt)
        statement.setString(6, operation.operationId.toString())
        statement.setLong(7, operation.revision)
    }

    private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun nullableLong(result: ResultSet, column: String): Long? {
        val value = result.getLong(column)
        return if (result.wasNull()) null else value
    }

    private const val RESTORE_STALL_SQL = """UPDATE stalls SET region_id = ?, world = ?, state = ?, owner_type = ?, owner_id = ?,
        owner_since = ?, winning_bid = ?, rent_mode = ?, rent_pct = ?, rent_flat = ?,
        members = ?, max_members = ?, next_rent_at = ?, kind = ?, extra_entities = ?,
        extra_total = ?, moderation_revision = moderation_revision + 1
        WHERE id = ? AND moderation_revision = ?"""

    private const val UPDATE_OPERATION_SQL = """UPDATE market_moderation_operations
        SET state = ?, current_checksum = ?, reviewer_uuid = ?, detail = ?,
            revision = revision + 1, updated_at = ?
        WHERE operation_id = ? AND revision = ?"""
}

internal data class OperationUpdate(
    val state: MarketOperationRecord.State,
    val currentChecksum: String,
    val reviewerId: UUID?,
    val detail: String,
    val updatedAt: Long,
)
