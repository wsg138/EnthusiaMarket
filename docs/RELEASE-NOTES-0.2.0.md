# EnthusiaMarket v0.2.0 — Release Notes

**Headline:** Guild economic warfare — guilds can now **tariff** or **embargo** other guilds at their shops — on top of a fully audited, hardened v0.1.0 trade core.

---

## ✨ New feature — Guild Trade Policies (tariffs & embargoes)

Guilds with stalls and shops gain economic control over rival guilds.

- **Tariffs (0–99%)** — make a targeted guild's members trade at a worse rate, both directions. On a SELL shop they pay more; on a BUY shop they're paid less. The difference flows to the **owning guild's bank** (the bank is already the trade counterparty, so the surcharge is captured automatically). Capped below 100% so a BUY tariff can never become confiscation.
- **Embargoes** — block a targeted guild entirely. Their members' trades at your shops are cleanly rejected; no items or money move.
- **Always exempt:** solo players and your own guild's members always pay the base price.
- **Management GUI — `/em guild policy`** (requires the in-guild `MANAGE_SHOPS` permission): a chest menu listing current policies with click-to-edit (left +5%, right −5%, shift-left embargo, shift-right remove) and a guild picker to add new targets.
- **Notifications:** a server-wide broadcast whenever any guild sets/changes/lifts a policy, plus a personal warning (with the exact rate) when you enter a guild stall that tariffs/embargoes your guild. Both toggleable in config (`guildPolicy.*`).

New config block `guildPolicy` (announce toggle + cooldown, entry-warning toggle), new permission `enthusiamarket.guild.policy` (default true), migration `V017__guild_trade_policies.sql`.

## 🏰 Guild ownership — now complete

- **Guild rent** — guild-owned stalls pay rent from the guild bank (auto-collection + voluntary extension) on the same default → grace → eviction path as solo stalls.
- **Guild shop revenue** — sign shops placed in a guild stall bind to the guild (revenue → guild bank), and sell-offer proceeds on a guild stall pay the guild bank, not the selling member.
- **Disband cleanup** — when a guild dissolves, its stalls are freed (returned to UNOWNED) and its shops unbound automatically. (Previously the Bukkit disband listener was never even registered.)
- **Auth fix** — unbinding a guild shop now requires `MANAGE_SHOPS`, not mere guild membership.

## 💰 Money & data integrity hardening (from two full audits)

- **Players can bid again** — `/em bid` was OP-gated; it now uses the player `enthusiamarket.auction.bid` node, so non-ops can actually win stalls.
- **No rent double-charge** — the rent ticker now honours a future `nextRentAt`, so a pre-paid (extended) period is no longer charged twice.
- **Compensation alerts** — when a trade's rollback/payout itself fails (a rare Vault hiccup mid-transaction), operators now get a SEVERE log + a `TradeCompensationFailedEvent` to reconcile, instead of silent loss.
- **NBT-safe item serialization** — shop items are stored with `serializeAsBytes` (data-component-safe on 1.21.x), with a legacy read fallback so existing rows still load.
- **Limit-cap fixes** — an unlimited *total* no longer bypasses a finite per-kind cap; a limit group that omits `total` no longer rejects every claim.

## ⚡ Performance

- **Bulk shop ops** — `deleteAll` issues one `DELETE` instead of N; `trustAll`/`untrustAll` stop re-reading shops they already loaded.

## 📋 Known limitations / backlog (post-0.2.0)

- `/shop search` still scans in memory + deserializes per row (SQL + denormalized column planned); the search-result trades count does main-thread chunk I/O.
- Auction hardening (minimum bid increment, self-bid guard, expired-bid guard) not yet applied.
- REQ-280 emergency auction state exists but is never triggered.
- A batch of defensive domain-invariant validations (`require(...)`) from the audit remains as a cleanup.

## 🔧 Ops notes

- Guild integration requires LumaGuilds; without it, guild stalls/shops/policies degrade gracefully (treated as unowned where applicable).
- The plugin runs single-threaded on the Bukkit main thread; both schedulers use `runTaskTimer`. The tax destination `system` is an intentional, documented money sink.
