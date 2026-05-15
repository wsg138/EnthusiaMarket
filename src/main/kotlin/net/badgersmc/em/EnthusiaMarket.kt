package net.badgersmc.em

import org.bukkit.plugin.java.JavaPlugin

open class EnthusiaMarket : JavaPlugin() {
    override fun onEnable() {
        saveDefaultConfig()
        logger.info("EnthusiaMarket enabled (v${description.version})")
    }

    override fun onDisable() {
        logger.info("EnthusiaMarket disabled")
    }
}
