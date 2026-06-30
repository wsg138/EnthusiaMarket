package net.badgersmc.em.interaction.help

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * Builds Adventure components for the in-game `/em help` UI.
 *
 * Two public surfaces:
 *  - [renderTopicMenu] - the top-level topic picker (no argument).
 *  - [renderTopicPage] - one topic's command list (`/em help <topic>`).
 *
 * All click events use `RUN_COMMAND` (for topic switches) or
 * `SUGGEST_COMMAND` (for command prefill) so Geyser maps them to taps.
 */
object HelpTopicsRenderer {

    fun renderTopicMenu(): Component {
        val header = Component.text("─── EnthusiaMarket Help ───", NamedTextColor.GOLD, TextDecoration.BOLD)
        val intro = Component.text("Pick a topic (click or type /em help <topic>):", NamedTextColor.GRAY)

        val entries = HelpTopics.all.map { topic -> renderTopicEntry(topic) }

        val wikiLink = Component.text()
            .append(Component.text("Full wiki: ", NamedTextColor.GRAY))
            .append(
                Component.text(HelpTopics.WIKI_BASE_URL, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(HelpTopics.WIKI_BASE_URL))
                    .hoverEvent(HoverEvent.showText(Component.text("Open in browser", NamedTextColor.GOLD))),
            )
            .build()

        val out = Component.text()
            .append(header).append(Component.newline())
            .append(intro).append(Component.newline())
        entries.forEach { out.append(it).append(Component.newline()) }
        out.append(wikiLink)
        return out.build()
    }

    private fun renderTopicEntry(topic: HelpTopic): Component {
        return Component.text()
            .append(Component.text("  [", NamedTextColor.DARK_GRAY))
            .append(Component.text(topic.displayName, NamedTextColor.YELLOW))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .append(Component.text(topic.summary, NamedTextColor.GRAY))
            .clickEvent(ClickEvent.runCommand("/em help ${topic.slug}"))
            .hoverEvent(HoverEvent.showText(Component.text("Open ${topic.displayName} help", NamedTextColor.GOLD)))
            .build()
    }

    fun renderTopicPage(topic: HelpTopic): Component {
        val header = Component.text("─── Help · ${topic.displayName} ───", NamedTextColor.GOLD, TextDecoration.BOLD)
        val summary = Component.text(topic.summary, NamedTextColor.GRAY)

        val commandLines = topic.commands.map { entry -> renderCommandEntry(entry) }

        val wikiUrl = "${HelpTopics.WIKI_BASE_URL}/${topic.slug}/"
        val wikiLine = Component.text()
            .append(Component.text("Read more: ", NamedTextColor.GRAY))
            .append(
                Component.text(wikiUrl, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(wikiUrl))
                    .hoverEvent(HoverEvent.showText(Component.text("Open in browser", NamedTextColor.GOLD))),
            )
            .build()

        val back = Component.text("[Back to topics]", NamedTextColor.YELLOW)
            .clickEvent(ClickEvent.runCommand("/em help"))
            .hoverEvent(HoverEvent.showText(Component.text("Open the topic menu", NamedTextColor.GOLD)))

        val out = Component.text()
            .append(header).append(Component.newline())
            .append(summary).append(Component.newline())
            .append(Component.text("How to:", NamedTextColor.YELLOW)).append(Component.newline())
        commandLines.forEach { out.append(it).append(Component.newline()) }
        out.append(Component.newline())
            .append(wikiLine).append(Component.newline())
            .append(back)
        return out.build()
    }

    private fun renderCommandEntry(entry: HelpCommandEntry): Component {
        val line = Component.text()
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(Component.text(entry.syntax, NamedTextColor.WHITE))
        if (entry.prefill.isNotEmpty()) {
            line.clickEvent(ClickEvent.suggestCommand(entry.prefill))
                .hoverEvent(
                    HoverEvent.showText(
                        Component.text()
                            .append(Component.text(entry.blurb, NamedTextColor.GOLD))
                            .append(Component.newline())
                            .append(Component.text("Click to prefill in chat", NamedTextColor.GRAY))
                            .build(),
                    ),
                )
        } else {
            line.hoverEvent(HoverEvent.showText(Component.text(entry.blurb, NamedTextColor.GOLD)))
        }
        return line.build()
    }
}
