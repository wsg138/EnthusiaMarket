package net.badgersmc.em.interaction.help

/**
 * Source of truth for player-facing help topics.
 *
 * Every entry has a matching wiki page at `wiki/docs/players/<slug>.md`.
 * CI fails if any slug here lacks a wiki page, or vice versa.
 */
object HelpTopics {

    /** Base URL for wiki deep-links surfaced in the in-game help. */
    const val WIKI_BASE_URL = "https://badgersmc.github.io/EnthusiaMarket/players"

    val all: List<HelpTopic> = listOf(
        HelpTopic(
            slug = "stalls",
            displayName = "Stalls",
            summary = "Buy, claim, sell back, and manage member access to market stalls.",
            commands = listOf(
                HelpCommandEntry("/em limit", "See your owned stalls and limits.", "/em limit"),
                HelpCommandEntry("/em stall info", "View details of the stall you're standing in.", "/em stall info"),
                HelpCommandEntry("/em stall members add <player>", "Add a member to your stall.", "/em stall members add "),
                HelpCommandEntry("/em stall members remove <player>", "Remove a member from your stall.", "/em stall members remove "),
                HelpCommandEntry("/em stall members list", "List members of your stall.", "/em stall members list"),
                HelpCommandEntry("/em stall offer <price>", "Put your stall up for sale/auction.", "/em stall offer "),
                HelpCommandEntry("/em stall buy", "Buy the stall you're standing in.", "/em stall buy"),
                HelpCommandEntry("/em sellback", "Sell your stall back to the market.", "/em sellback"),
            ),
        ),
        HelpTopic(
            slug = "shop-creation",
            displayName = "Creating Shops",
            summary = "Place a sign on a container and start trading — shift-click for the GUI, or write [BUY]/[SELL]/[TRADE] on the sign.",
            commands = listOf(
                HelpCommandEntry(
                    syntax = "(no command — shift+click a blank sign)",
                    blurb = "Shift + left-click a blank wall sign on a container to open the creation menu.",
                    prefill = "",
                ),
                HelpCommandEntry(
                    syntax = "(no command — write [BUY] on sign)",
                    blurb = "Place a sign with [BUY], [SELL], or [TRADE] on line 1 for text-based creation.",
                    prefill = "",
                ),
            ),
        ),
        HelpTopic(
            slug = "buy-sell-trade",
            displayName = "Buy, Sell & Trade",
            summary = "Use other players' shops as a customer — right-click a shop sign to open the trade GUI.",
            commands = listOf(
                HelpCommandEntry(
                    syntax = "(right-click a shop sign)",
                    blurb = "Right-click any shop sign to buy, sell to, or trade with that shop.",
                    prefill = "",
                ),
            ),
        ),
        HelpTopic(
            slug = "shop-management",
            displayName = "Managing Shops",
            summary = "Edit prices, delete, trust friends, and search for shops.",
            commands = listOf(
                HelpCommandEntry("/shop list", "List all your shops.", "/shop list"),
                HelpCommandEntry("/shop edit", "Open the shop settings menu.", "/shop edit"),
                HelpCommandEntry("/shop delete [all]", "Delete a shop or all your shops.", "/shop delete "),
                HelpCommandEntry("/shop trust <player>", "Trust a player on your shops.", "/shop trust "),
                HelpCommandEntry("/shop untrust <player>", "Remove a player's trust.", "/shop untrust "),
                HelpCommandEntry("/shop search <item>", "Find shops selling an item.", "/shop search "),
                HelpCommandEntry("/shop history", "View your transaction history.", "/shop history"),
            ),
        ),
        HelpTopic(
            slug = "rent",
            displayName = "Rent",
            summary = "Pay rent to keep your stall, or risk eviction. Rent is collected automatically on schedule.",
            commands = listOf(
                HelpCommandEntry(
                    syntax = "(automatic)",
                    blurb = "Rent is deducted automatically. Keep your economy balance topped up to avoid eviction.",
                    prefill = "",
                ),
                HelpCommandEntry("/em stall info", "Check your stall's rent status and next due date.", "/em stall info"),
            ),
        ),
        HelpTopic(
            slug = "barter-vault",
            displayName = "Barter Vault",
            summary = "Collect items earned from TRADE (item-for-item) shops.",
            commands = listOf(
                HelpCommandEntry("/em vault", "Open your barter vault to collect earned items.", "/em vault"),
            ),
        ),
        HelpTopic(
            slug = "auctions",
            displayName = "Auctions",
            summary = "Bid on stalls up for auction, or put your own stall on the auction block.",
            commands = listOf(
                HelpCommandEntry("/em bid <amount>", "Bid on the stall you're standing in.", "/em bid "),
                HelpCommandEntry("/em auctions", "Browse all active auctions.", "/em auctions"),
                HelpCommandEntry("/em auction start <bid>", "Start a single-stall auction (admin).", "/em auction start "),
                HelpCommandEntry("/em auction startall <bid>", "Mass-auction all unowned stalls (admin).", "/em auction startall "),
                HelpCommandEntry("/em auction cancel <id>", "Cancel an auction (admin).", "/em auction cancel "),
            ),
        ),
        HelpTopic(
            slug = "guild-stalls",
            displayName = "Guild Stalls",
            summary = "Guild-owned stalls with member access and trade policies.",
            commands = listOf(
                HelpCommandEntry("/em guildpolicy", "Manage guild trade policies (tariffs, embargoes).", "/em guildpolicy"),
            ),
        ),
        HelpTopic(
            slug = "bedrock",
            displayName = "Bedrock Differences",
            summary = "Where EnthusiaMarket behaves differently on Bedrock/Geyser.",
            commands = listOf(
                HelpCommandEntry(
                    syntax = "(see wiki)",
                    blurb = "Bedrock shops open as forms; sign interactions map to taps.",
                    prefill = "",
                ),
            ),
        ),
    )

    private val bySlugMap: Map<String, HelpTopic> = all.associateBy { it.slug }

    fun bySlug(slug: String): HelpTopic? = bySlugMap[slug.lowercase()]
}
