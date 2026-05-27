package net.badgersmc.em.infrastructure.i18n

import net.badgersmc.nexus.i18n.LangFile

/**
 * Marker class that locates EnthusiaMarket's bundled locale files for
 * `nexus-i18n`'s `LangService`. Lives in the same module as the
 * `src/main/resources/lang/*.yml` files so the service can resolve them via
 * this class's [ClassLoader].
 *
 * Adding new locales: drop another `lang/<locale>.yml` next to `en_US.yml` and
 * change `EnthusiaMarketConfig.lang.locale` to its id.
 */
@LangFile(resourcePrefix = "lang", defaultLocale = "en_US")
class EnthusiaMarketLang
