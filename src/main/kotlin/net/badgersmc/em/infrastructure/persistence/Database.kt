package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.Configuration
import java.io.File

object Database {
    fun open(config: Configuration, dataFolder: File): HikariDataSource {
        val type = config.getString("database.type", "sqlite")!!
        val hc = HikariConfig()
        when (type) {
            "sqlite" -> {
                val file = config.getString("database.sqlite-file", "enthusiamarket.db")!!
                hc.jdbcUrl = "jdbc:sqlite:${File(dataFolder, file).absolutePath}"
                hc.maximumPoolSize = 1
            }
            "mariadb" -> {
                val host = config.getString("database.mariadb.host")
                val port = config.getInt("database.mariadb.port", 3306)
                val db = config.getString("database.mariadb.database")
                hc.jdbcUrl = "jdbc:mariadb://$host:$port/$db"
                hc.username = config.getString("database.mariadb.username")
                hc.password = config.getString("database.mariadb.password")
                hc.maximumPoolSize = 10
            }
            else -> error("Unknown database.type: $type")
        }
        return HikariDataSource(hc)
    }
}
