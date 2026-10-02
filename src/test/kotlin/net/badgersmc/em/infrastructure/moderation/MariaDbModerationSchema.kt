package net.badgersmc.em.infrastructure.moderation

import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

internal fun createV27UpgradeBaseline(dataSource: DataSource, now: Instant) {
    dataSource.connection.use { connection ->
        dropModerationTables(connection)
        connection.prepareStatement(STALLS_BASELINE_SQL).use { it.executeUpdate() }
        connection.prepareStatement(SHOPS_BASELINE_SQL).use { it.executeUpdate() }
        connection.prepareStatement(MIGRATION_BASELINE_SQL).use { it.executeUpdate() }
        seedMigrationBaseline(connection, now)
    }
}

private fun dropModerationTables(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.addBatch("DROP TABLE IF EXISTS market_moderation_locks")
        statement.addBatch("DROP TABLE IF EXISTS market_moderation_operations")
        statement.addBatch("DROP TABLE IF EXISTS market_stall_blacklists")
        statement.addBatch("DROP TABLE IF EXISTS market_player_fences")
        statement.addBatch("DROP TABLE IF EXISTS shop_transactions")
        statement.addBatch("DROP TABLE IF EXISTS shop_items")
        statement.addBatch("DROP TABLE IF EXISTS stalls")
        statement.addBatch("DROP TABLE IF EXISTS schema_migration")
        statement.executeBatch()
    }
}

private fun seedMigrationBaseline(connection: Connection, now: Instant) {
    connection.prepareStatement(
        "INSERT INTO schema_migration(version, name, applied_at) VALUES (?, ?, ?)",
    ).use { statement ->
        (1..27).forEach { version ->
            statement.setInt(1, version)
            statement.setString(2, "baseline_$version")
            statement.setLong(3, now.toEpochMilli())
            statement.addBatch()
        }
        statement.executeBatch()
    }
}


private const val STALLS_BASELINE_SQL = """CREATE TABLE stalls (
    id VARCHAR(128) PRIMARY KEY,
    region_id VARCHAR(128) NOT NULL,
    world VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    owner_since BIGINT,
    winning_bid BIGINT NOT NULL DEFAULT 0,
    rent_mode VARCHAR(16) NOT NULL,
    rent_pct DOUBLE NOT NULL DEFAULT 0,
    rent_flat BIGINT NOT NULL DEFAULT 0,
    members TEXT NOT NULL,
    max_members INTEGER NOT NULL DEFAULT -1,
    next_rent_at BIGINT,
    kind VARCHAR(64) NOT NULL DEFAULT 'default',
    extra_entities TEXT NOT NULL,
    extra_total INTEGER NOT NULL DEFAULT 0,
    UNIQUE(world, region_id)
)"""

private const val SHOPS_BASELINE_SQL = """CREATE TABLE shop_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    stall_id VARCHAR(128) NOT NULL,
    owner VARCHAR(36) NOT NULL,
    sign_world VARCHAR(128) NOT NULL,
    sign_x INTEGER NOT NULL,
    sign_y INTEGER NOT NULL,
    sign_z INTEGER NOT NULL,
    container_world VARCHAR(128) NOT NULL,
    container_x INTEGER NOT NULL,
    container_y INTEGER NOT NULL,
    container_z INTEGER NOT NULL,
    sell_item TEXT NOT NULL,
    sell_amount INTEGER NOT NULL DEFAULT 1,
    cost_item TEXT NOT NULL,
    cost_amount INTEGER NOT NULL DEFAULT 1,
    trusted TEXT NOT NULL,
    hopper_allow_in BOOLEAN NOT NULL DEFAULT TRUE,
    hopper_allow_out BOOLEAN NOT NULL DEFAULT TRUE,
    frozen BOOLEAN NOT NULL DEFAULT FALSE,
    admin_shop BOOLEAN NOT NULL DEFAULT FALSE,
    direction VARCHAR(16) NOT NULL DEFAULT 'SELL',
    search_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sell_material VARCHAR(128),
    stock_count INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
)"""

private const val MIGRATION_BASELINE_SQL = """CREATE TABLE schema_migration (
    version INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    applied_at BIGINT NOT NULL
)"""
