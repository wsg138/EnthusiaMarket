---
title: Rent
audience: player
topic: rent
summary: How rent works — calculation, payment, grace period, and eviction.
keywords: [rent, payment, grace, eviction, daily]
related: [stalls, auctions]
updated: 2026-06-06
---

# Rent

Rent is the daily cost of keeping your stall. Pay it on time or lose your stall.

## How rent is calculated

Rent is recalculated each time a new auction is won. The default formula:

```
daily rent = winning bid × 1%
```

For example, if you won an auction at 10,000 coins, your daily rent is 100 coins.

Admins can also configure a **flat rate** mode where every stall pays the same fixed amount.

## Checking rent

Click your stall sign. It shows:

- Rent amount due.
- Time until next collection.
- Time remaining in current period (if in grace).

## Paying rent

Click the stall sign and confirm the payment. The amount is deducted from your wallet.

You can also see rent info via the admin info command if you have permissions.

## Grace period

If rent isn't paid on time, your stall enters **grace** for **3 days**. During grace:

- You can still pay rent to recover the stall.
- Your shops still work.
- A warning appears on the sign.

## Eviction

After grace expires, the stall is **re-auctioned**:

1. All shops inside are **deleted**.
2. Items in containers are **lost**.
3. A new auction starts for the stall.
4. You receive no refund.

> **Warning:** Withdraw all valuable items before grace expires. Items left in containers are gone forever.

## Rent collection interval

Rent is collected every **24 hours** by default. The interval is configurable by admins.

## Guild-owned stalls

If a guild owns a stall, rent can be paid from the **guild bank** (configurable). Any guild member can pay rent during grace to save the stall.
