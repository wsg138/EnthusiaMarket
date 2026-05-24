package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File

object Database {
    fun open(
        type: String = "sqlite",
        sqliteFile: String = "enthusiamarket.db",
        dataFolder: File,
        mariadbHost: String = "localhost",
        mariadbPort: Int = 3306,
        mariadbDatabase: String = "enthusiamarket",
        mariadbUsername: String = "em",
        mariadbPassword: String = ""
    ): HikariDataSource {
        val hc = HikariConfig()
        when (type) {
            "sqlite" -> {
                hc.jdbcUrl = "jdbc:sqlite:${File(dataFolder, sqliteFile).absolutePath}"
                hc.maximumPoolSize = 1
            }
            "mariadb" -> {
                hc.jdbcUrl = "jdbc:mariadb://$mariadbHost:$mariadbPort/$mariadbDatabase"
                hc.username = mariadbUsername
                hc.password = mariadbPassword
            }
            else -> error("Unknown database.type: $type")
        }
        return HikariDataSource(hc)
    }
}