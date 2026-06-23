package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.nexus.annotations.Service

/**
 * Live provider for the current rent terms from config (fixes #64).
 *
 * Formerly [RentTerms] was computed once at plugin startup and registered as a
 * fixed DI bean. After `/em reload` the config changed but the injected `defaultRent`
 * was stale. This provider reads [EnthusiaMarketConfig] on every call so
 * [RentTermsResyncService] and [ImportStallsService] always use the live config.
 */
@Service
class DefaultRentTermsProvider(
    private val config: EnthusiaMarketConfig,
) {
    fun current(): RentTerms =
        when (config.rent.mode.lowercase()) {
            "flat" -> RentTerms.flat(config.rent.flatAmount)
            "formula" -> RentTerms.formula(config.rent.formulaPct)
            else -> throw IllegalStateException(
                "Unknown rent mode '${config.rent.mode}'. Expected 'flat' or 'formula' in enthusiamarket.yaml"
            )
        }
}
