---
title: Frequently Asked Questions
audience: player
topic: faq
summary: Common questions and quick answers about EnthusiaMarket.
keywords: [faq, questions, help, troubleshooting]
related: [welcome, walkthrough]
updated: 2026-06-06
---

# Frequently Asked Questions

## General

**Q: How do I check my stall's rent?**
A: Click the stall sign. It shows the rent amount, time until next collection, and time remaining in the current period.

**Q: What happens if I can't pay rent?**
A: Your stall enters a 3-day grace period. You can still pay during grace. After grace expires, the stall is re-auctioned and you lose ownership.

**Q: Can I own multiple stalls?**
A: Yes, up to your ownership limit. Check with `/em limit`. Default limits depend on your permission group.

**Q: Can my guild own a stall?**
A: Yes. Guild members with the `MANAGE_SHOPS` permission can manage guild-owned stalls. Rent can be paid from the guild bank (configurable).

## Auctions

**Q: How do I find active auctions?**
A: Use `/em auctions` to open the auction browser menu.

**Q: What's the anti-snipe timer?**
A: If a bid is placed within the last 30 seconds of an auction, the timer extends by 30 seconds. This prevents last-millisecond sniping.

**Q: Can I cancel my bid?**
A: No. Bids are final. Only an admin can cancel an auction.

## Shops

**Q: How do I create a shop sign?**
A: Hold the item you're trading, place a sign on a container inside your stall, and put a direction token — `[SELL]`, `[BUY]`, or `[TRADE]` — on line 1, the quantity on line 2, and the price on line 3. See [Shops](../players/shops.md) for full details.

**Q: Where does the money from sales go?**
A: Into your shop vault. Open it with `/shopvault open` (or `/svault open`).

**Q: Can I buy items from players (not just sell)?**
A: Yes. Use a BUY-direction shop. See [Trade Directions](../players/shops.md#trade-directions).

**Q: What's the tax on trades?**
A: By default, 2% tax on shop transactions. The tax destination is configurable by admins.

## Rent & Ownership

**Q: How is rent calculated?**
A: Default: 1% of your winning bid per day. Admins can also use a flat-rate mode.

**Q: What happens to my shops when my stall is re-auctioned?**
A: All shops in the stall are deleted. Items in shop containers are lost — withdraw them before grace expires.

**Q: Can I sell my stall to another player?**
A: Yes. Use `/em sellback <stall>` to relinquish ownership for a refund, or use the sell offer system to sell to a specific player.

## Troubleshooting

**Q: My shop sign isn't working.**
A: Make sure: (1) the sign is on a container inside your stall, (2) you were holding the item and line 1 has a valid direction token (`[SELL]`, `[BUY]`, or `[TRADE]`), (3) lines 2-3 have valid numbers, (4) the container has stock.

**Q: I can't build in my stall.**
A: You need to be the stall owner, a member, or have build permissions. Ask the stall owner to add you as a member.

**Q: My items disappeared after my stall was re-auctioned.**
A: Items in containers are lost when a stall is re-auctioned. Always withdraw valuables before grace expires.
