package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.ShopTransaction
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Repository
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@Repository
class ShopTransactionRepositorySql(private val ds: DataSource) : ShopTransactionRepository {

    override fun record(tx: ShopTransaction): ShopTransaction {
        ds.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO shop_transactions
                   (shop_id, owner, buyer, direction, item, quantity, total_price, created_at, notified)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, tx.shopId)
                ps.setString(2, tx.owner.toString())
                ps.setString(3, tx.buyer.toString())
                ps.setString(4, tx.direction.name)
                ps.setString(5, tx.item)
                ps.setInt(6, tx.quantity)
                ps.setLong(7, tx.totalPrice)
                ps.setLong(8, tx.createdAt)
                ps.setBoolean(9, tx.notified)
                ps.executeUpdate()
                val id = ps.generatedKeys.use { if (it.next()) it.getLong(1) else 0L }
                return tx.copy(id = id)
            }
        }
    }

    override fun findByOwner(owner: UUID, limit: Int, offset: Int): List<ShopTransaction> {
        ds.connection.use { c ->
            c.prepareStatement(
                """SELECT * FROM shop_transactions WHERE owner = ?
                   ORDER BY created_at DESC LIMIT ? OFFSET ?"""
            ).use { ps ->
                ps.setString(1, owner.toString())
                ps.setInt(2, limit)
                ps.setInt(3, offset)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ShopTransaction>()
                    while (rs.next()) out += map(rs)
                    return out
                }
            }
        }
    }

    override fun countUnnotified(owner: UUID): Int {
        ds.connection.use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM shop_transactions WHERE owner = ? AND notified = 0").use { ps ->
                ps.setString(1, owner.toString())
                ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override fun markNotified(owner: UUID) {
        ds.connection.use { c ->
            c.prepareStatement("UPDATE shop_transactions SET notified = 1 WHERE owner = ? AND notified = 0").use { ps ->
                ps.setString(1, owner.toString())
                ps.executeUpdate()
            }
        }
    }

    override fun prune(beforeMs: Long): Int {
        ds.connection.use { c ->
            c.prepareStatement("DELETE FROM shop_transactions WHERE created_at < ?").use { ps ->
                ps.setLong(1, beforeMs)
                return ps.executeUpdate()
            }
        }
    }

    private fun map(rs: ResultSet) = ShopTransaction(
        id = rs.getLong("id"),
        shopId = rs.getLong("shop_id"),
        owner = UUID.fromString(rs.getString("owner")),
        buyer = UUID.fromString(rs.getString("buyer")),
        direction = SignDirection.valueOf(rs.getString("direction")),
        item = rs.getString("item"),
        quantity = rs.getInt("quantity"),
        totalPrice = rs.getLong("total_price"),
        createdAt = rs.getLong("created_at"),
        notified = rs.getBoolean("notified"),
    )
}
