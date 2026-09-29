package net.badgersmc.em.infrastructure.moderation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.infrastructure.persistence.StallRepositorySql
import net.enthusia.market.api.moderation.MarketConfiscationApproval
import net.enthusia.market.api.moderation.MarketOperationRecord
import net.enthusia.market.api.moderation.MarketOperationRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/** Shared SQLite fixture for focused Market moderation persistence tests. */
internal class MarketModerationStoreFixture {
    lateinit var dataSource: HikariDataSource
        private set
    lateinit var stallRepository: StallRepositorySql
        private set
    lateinit var store: JdbcMarketModerationStore
        private set

    val now: Instant = Instant.parse("2026-08-13T12:00:00Z")
    val ownerId: UUID = UUID.fromString("5a1fbbe4-b27f-4c75-832a-63e4ad1b7e35")
    val reviewerId: UUID = UUID.fromString("b703df70-8f85-4c10-b55a-1b51809b879a")

    fun setUp() {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:file:moderation-${UUID.randomUUID()}?mode=memory&cache=shared"
            minimumIdle = 1
            maximumPoolSize = 4
        })
        net.badgersmc.nexus.persistence.MigrationRunner(
            dataSource,
            "migrations",
            javaClass.classLoader,
        ).runAll()
        stallRepository = StallRepositorySql(dataSource)
        store = storeAt(now)
        stallRepository.create(ownedStall())
        createShop()
    }

    fun tearDown() = dataSource.close()

    fun request(
        operationId: UUID = DEFAULT_OPERATION_ID,
        caseId: String = "CASE-100",
        reviewDueAt: Instant = now.plusSeconds(7 * DAY_SECONDS),
        recoveryUntil: Instant = now.plusSeconds(30 * DAY_SECONDS),
        blacklistExpiresAt: Optional<Instant> = Optional.empty(),
    ): MarketOperationRequest = MarketOperationRequest(
        operationId,
        ownerId,
        caseId,
        "stall-1",
        reviewDueAt,
        recoveryUntil,
        blacklistExpiresAt,
    )

    fun approval(operation: MarketOperationRecord): MarketConfiscationApproval = MarketConfiscationApproval(
        operation.operationId(),
        reviewerId,
        operation.snapshotChecksum(),
        now.plusSeconds(DAY_SECONDS),
    )

    fun storeAt(instant: Instant): JdbcMarketModerationStore = JdbcMarketModerationStore(
        dataSource,
        Clock.fixed(instant, ZoneOffset.UTC),
    )

    fun createShop(signX: Int = 1, stallId: String = "stall-1") {
        dataSource.connection.use { connection ->
            connection.prepareStatement(SHOP_INSERT_SQL).use { statement ->
                statement.setString(1, stallId)
                statement.setString(2, ownerId.toString())
                statement.setInt(3, signX)
                statement.setInt(4, signX)
                statement.executeUpdate()
            }
        }
    }

    fun stallValue(column: String): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT $column FROM stalls WHERE id = 'stall-1'").use { statement ->
            statement.executeQuery().use { result ->
                check(result.next())
                result.getString(1)
            }
        }
    }

    fun shopFrozen(): Boolean = scalar("SELECT frozen FROM shop_items WHERE sign_x = 1") == 1

    fun scalar(sql: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
    }

    fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { it.executeUpdate() }
        }
    }

    private fun ownedStall(): Stall = Stall(
        id = StallId("stall-1"),
        regionId = "market-stall-1",
        world = "market",
        state = StallState.OWNED,
        owner = OwnerRef.solo(ownerId),
        ownerSince = now.minusSeconds(DAY_SECONDS),
        winningBid = 2_500L,
        rentTerms = RentTerms.formula(0.05),
        members = setOf(UUID.fromString("29cf4eb9-62ef-49bf-ae02-ab4e2146e099")),
        maxMembers = 4,
        nextRentAt = now.plusSeconds(DAY_SECONDS),
        kind = "bazaar",
        extraEntities = mapOf("armor_stand" to 2),
        extraTotal = 3,
    )

    companion object {
        const val DAY_SECONDS = 86_400L
        private val DEFAULT_OPERATION_ID = UUID.fromString("31cb0b96-992c-4678-b5d6-09d372f4ef12")
        private const val SHOP_INSERT_SQL = """INSERT INTO shop_items
            (stall_id, owner, sign_world, sign_x, sign_y, sign_z,
             container_world, container_x, container_y, container_z,
             sell_item, sell_amount, cost_item, cost_amount, trusted,
             hopper_allow_in, hopper_allow_out, frozen, admin_shop,
             direction, search_enabled, sell_material, stock_count)
            VALUES (?, ?, 'market', ?, 65, 1, 'market', ?, 64, 1,
                    'c2VsbA==', 1, 'Y29zdA==', 5, '', 1, 1, 0, 0, 'SELL', 1, 'DIAMOND', 10)"""
    }
}
