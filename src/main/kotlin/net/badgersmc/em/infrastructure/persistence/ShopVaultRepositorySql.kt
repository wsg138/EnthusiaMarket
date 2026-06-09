package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.ShopVaultRepository
import net.badgersmc.em.domain.shop.VaultItem
import net.badgersmc.nexus.annotations.Repository
import java.util.UUID
import javax.sql.DataSource

@Repository
class ShopVaultRepositorySql(private val ds: DataSource) : ShopVaultRepository {

    // Portable UPDATE-then-INSERT (M-3): SQLite's native upsert syntax breaks
    // MariaDB, which the config also offers. If the INSERT loses a first-deposit
    // race, the unique key throws — fold the amount into the row that won.
    override fun deposit(owner: UUID, itemBytes: String, amount: Int) {
        require(amount > 0) { "Deposit amount must be positive, got $amount" }
        ds.connection.use { c ->
            if (addToExisting(c, owner.toString(), itemBytes, amount) > 0) return
            try {
                insertRow(c, owner.toString(), itemBytes, amount)
            } catch (e: java.sql.SQLException) {
                if (addToExisting(c, owner.toString(), itemBytes, amount) == 0) throw e
            }
        }
    }

    private fun addToExisting(c: java.sql.Connection, owner: String, itemBytes: String, amount: Int): Int =
        c.prepareStatement("UPDATE shop_vault SET amount = amount + ? WHERE owner = ? AND item = ?").use { ps ->
            ps.setInt(1, amount); ps.setString(2, owner); ps.setString(3, itemBytes)
            ps.executeUpdate()
        }

    private fun insertRow(c: java.sql.Connection, owner: String, itemBytes: String, amount: Int) {
        c.prepareStatement("INSERT INTO shop_vault (owner, item, amount) VALUES (?, ?, ?)").use { ps ->
            ps.setString(1, owner); ps.setString(2, itemBytes); ps.setInt(3, amount)
            ps.executeUpdate()
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
        if (amount <= 0) return 0
        ds.connection.use { c ->
            // Wrap the read-modify-write in a transaction so two concurrent withdraws of the
            // same owner/item can't both read the old balance and double-remove.
            val previousAutoCommit = c.autoCommit
            c.autoCommit = false
            try {
                val removed = withdrawInTransaction(c, owner.toString(), itemBytes, amount)
                c.commit()
                return removed
            } catch (e: java.sql.SQLException) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = previousAutoCommit
            }
        }
    }

    private fun withdrawInTransaction(c: java.sql.Connection, owner: String, itemBytes: String, amount: Int): Int {
        val current = readAmount(c, owner, itemBytes)
        if (current <= 0) return 0
        val remove = minOf(current, amount)
        if (remove >= current) deleteRow(c, owner, itemBytes) else decrement(c, owner, itemBytes, remove)
        return remove
    }

    private fun readAmount(c: java.sql.Connection, owner: String, itemBytes: String): Int {
        c.prepareStatement("SELECT amount FROM shop_vault WHERE owner = ? AND item = ?").use { ps ->
            ps.setString(1, owner); ps.setString(2, itemBytes)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun deleteRow(c: java.sql.Connection, owner: String, itemBytes: String) {
        c.prepareStatement("DELETE FROM shop_vault WHERE owner = ? AND item = ?").use { ps ->
            ps.setString(1, owner); ps.setString(2, itemBytes); ps.executeUpdate()
        }
    }

    private fun decrement(c: java.sql.Connection, owner: String, itemBytes: String, remove: Int) {
        c.prepareStatement("UPDATE shop_vault SET amount = amount - ? WHERE owner = ? AND item = ?").use { ps ->
            ps.setInt(1, remove); ps.setString(2, owner); ps.setString(3, itemBytes); ps.executeUpdate()
        }
    }
}
