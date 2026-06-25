---
title: Auctions
audience: player
topic: auctions
summary: How stall auctions work — bidding, anti-snipe, and winning.
keywords: [auctions, bidding, anti-snipe, winning]
related: [stalls, rent]
updated: 2026-06-06
---

# Auctions

Auctions are how you win stall ownership. When a stall becomes available (new, re-auctioned, or emergency-auctioned), an auction starts.

## Browsing

Open the auction browser with:

```text
/em auctions
```

This shows all active auctions with stall IDs, current high bids, and time remaining. The browser is **view-only** — note the auction ID you want, then bid with the command below.

## Bidding

```text
/em bid <auction-id> <amount>
```

Requires the `enthusiamarket.auction.bid` permission (granted to players by default). Your bid must exceed the current high bid. Bids are **final** — you cannot undo a bid.

When you bid, the previous high bidder gets their money back automatically.

## Winning

When the auction timer ends, the highest bidder wins. You'll receive a confirmation message. The stall sign updates to show your name, and rent collection begins.

## Anti-snipe

If a bid is placed within the last **30 seconds** of an auction, the timer extends by 30 seconds. This prevents last-second sniping and gives others a chance to counter-bid.

## Auction duration

Default auction duration is **24 hours**. Admins can configure:

- Minimum: 15 minutes
- Maximum: 7 days

## Auction fee

When you win an auction and later sell the stall (via sell offer), a **5% fee** is deducted from the sale price. This fee goes to the server.

## Cancelled auctions

Only admins can cancel an auction. If cancelled, all bids are refunded.
