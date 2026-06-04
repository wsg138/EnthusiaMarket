package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.ShopVaultRepository
import net.badgersmc.em.domain.shop.VaultItem
import net.badgersmc.nexus.annotations.Repository
import java.util.UUID
import javax.sql.DataSource

@Repository
class ShopVaultRepositorySql(private val ds: DataSource) : ShopVaultRepository {

    override fun deposit(owner: UUID, itemBytes: String, amount: Int) {
        ds.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO shop_vault (owner, item, amount) VALUES (?, ?, ?)
                   ON CONFLICT(owner, item) DO UPDATE SET amount = amount + excluded.amount"""
            ).use { ps ->
                ps.setString(1, owner.toString()); ps.setString(2, itemBytes); ps.setInt(3, amount)
                ps.executeUpdate()
            }
        }
    }

    override fun findByOwner(owner: UUID): List<VaultItem> {
        ds.connection.use { c ->
            c.prepareStatement("SELECT item, amount FROM shop_vault WHERE owner = ? ORDER BY item").use { ps ->
                ps.setString(1, owner.toString())
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<VaultItem>()
                    while (rs.next()) out += VaultItem(owner, rs.getString("item"), rs.getInt("amount"))
                    return out
                }
            }
        }
    }

    override fun withdraw(owner: UUID, itemBytes: String, amount: Int): Int {
        ds.connection.use { c ->
            val current = c.prepareStatement("SELECT amount FROM shop_vault WHERE owner = ? AND item = ?").use { ps ->
                ps.setString(1, owner.toString()); ps.setString(2, itemBytes)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (current <= 0) return 0
            val remove = minOf(current, amount)
            if (remove >= current) {
                c.prepareStatement("DELETE FROM shop_vault WHERE owner = ? AND item = ?").use { ps ->
                    ps.setString(1, owner.toString()); ps.setString(2, itemBytes); ps.executeUpdate()
                }
            } else {
                c.prepareStatement("UPDATE shop_vault SET amount = amount - ? WHERE owner = ? AND item = ?").use { ps ->
                    ps.setInt(1, remove); ps.setString(2, owner.toString()); ps.setString(3, itemBytes); ps.executeUpdate()
                }
            }
            return remove
        }
    }
}
