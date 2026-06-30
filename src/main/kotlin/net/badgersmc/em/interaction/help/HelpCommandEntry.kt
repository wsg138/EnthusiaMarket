package net.badgersmc.em.interaction.help

/**
 * One command line in a [HelpTopic]'s command list, shown in-game and
 * referenced by the corresponding wiki page.
 */
data class HelpCommandEntry(
    val syntax: String,
    val blurb: String,
    val prefill: String,
)
