---
title: Rent
audience: player
topic: rent
summary: How stall rent works — formula vs flat, collection, grace period, eviction, and extension.
keywords: [rent, eviction, grace, extension, formula, flat]
related: [stalls, shop-creation]
updated: 2026-06-25
---

# Rent

Every stall you own costs rent. If you don't pay, you lose the stall. Here's how it works.

## How rent is calculated

Rent depends on the server's config:

### Formula mode (default)

```text
dailyRent = purchasePrice × rentPct%
```

Example: you bought a stall for $10,000. At 1%, your daily rent is $100.

### Flat mode

```text
dailyRent = flatAmount
```

Same amount per period regardless of what the stall cost. Example: $500/day flat.

## When rent is collected

Rent is collected automatically once per **collection interval** (default: every 24 hours). The system checks your economy balance and deducts the rent amount.

## What happens if you can't pay

If your balance is too low when rent is collected:

1. Your stall enters **GRACE** state — a warning period.
2. During grace, your stall is still yours. Shops still work. You can still use the stall.
3. You have a **grace period** (default 3 days) to pay rent.
4. If you pay rent during grace, you return to OWNED in good standing.
5. If the grace period expires without payment: **eviction**. All your shops are wiped and the stall goes back to UNOWNED.

## How to pay or extend rent

**Right-click the purchase sign twice** within 10 seconds (the confirmation window). Each double-click extends rent by one period.

You can pre-pay multiple periods ahead — the config option `rent.maxPrepaidPeriods` controls the cap (0 = unlimited).

## Check your rent status

Use the purchase sign — the fourth line shows your remaining rent time in `dd:hh:mm:ss` format.

Or run:

```text
/em stall info <stallId>
```

## Config summary

| Key | Default | Meaning |
|-----|---------|---------|
| `rent.mode` | `formula` | `formula` (percentage) or `flat` (fixed) |
| `rent.formulaPct` | `1.0` | Percentage of purchase price (1.0 = 1%) |
| `rent.flatAmount` | `0` | Flat amount per period |
| `rent.collectionInterval` | `P1D` | How often rent is collected (1 day) |
| `rent.gracePeriod` | `P3D` | Grace period before eviction (3 days) |
| `rent.maxPrepaidPeriods` | `0` | Max periods you can pre-pay (0 = unlimited) |
