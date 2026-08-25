---
title: Maintenance Freeze
audience: admin
topic: maintenance
summary: Pause all rent and auction timers for a server-closure maintenance window, and how player transactions behave during the freeze.
keywords: [maintenance, freeze, rent, auction, timers, shutdown]
related: [config, troubleshooting, release-checklist]
updated: 2026-08-01
---

# Maintenance Freeze

`/em maintenance freeze|unfreeze|status` (permission `enthusiamarket.admin.maintenance`, op by default) pauses **all rent and auction timers** for a server-closure maintenance window. It is the supported way to take the market offline without evicting anyone or expiring any auction.

## When to use it

Days the server closes for maintenance (patch day, migration, hardware). Without a freeze, a stall whose rent comes due during the outage would progress through GRACE → emergency auction while nobody can pay, and open auctions would expire with no bids possible.

## Operator flow

1. Before shutdown: `/em maintenance freeze` — timers pause immediately.
2. Stop the server. The freeze is **DB-backed** (`maintenance_freeze` table) and survives the restart — the server typically comes back up still frozen, with a warning logged.
3. After maintenance: `/em maintenance unfreeze` — every rent deadline (`stalls.next_rent_at`) and OPEN auction end time (`auctions.end_at`) is shifted forward by the frozen wall-clock duration in one transaction, then the freeze clears.
4. `/em maintenance status` — check freeze state any time.

Players lose no time: a stall overdue 2 days at freeze time stays overdue 2 days after unfreeze (pre-existing delinquency is preserved, freeze time is not added). Emergency auctions waiting for a first bid store a `Long.MAX_VALUE` sentinel and are skipped by the shift (no timer to move).

## Player transactions during a freeze

Per REQ-304, the freeze gates **timers only** — it does NOT gate player-initiated transactions:

- **Rent payments** (right-click the purchase sign) and **stall buyouts** remain live during the freeze.
- Their newly-set rent deadlines receive **both** the normal interval credit **and** the unfreeze shift — a player who pays or buys during the freeze window gets the frozen duration added on top. This is player-favorable and admin-gated (only ops can freeze), and is the documented contract.
- Shop trading is never affected by the freeze — only the rent/auction scheduler ticks and the periodic sign countdown refresh pause. Event-driven sign refreshes stay live, so a rent payment made during the freeze still updates its sign immediately.

## What freezes

| System | During freeze |
|---|---|
| Rent scheduler | No scheduler-driven GRACE / eviction / emergency-auction transitions |
| Auction scheduler | No settlement, no bid reminders |
| Purchase-sign countdown | Periodic 3s re-render paused (display freezes; corrected on unfreeze) |
| Rent payments / buyouts / shop trades | **Live** (REQ-304) |

## Notes

- No `/reload` needed — the freeze is DB-backed and command-driven.
- If the server restarts while frozen, the boot log warns `MAINTENANCE FREEZE IS ACTIVE …` until an admin runs `/em maintenance unfreeze`.
- See REQ-302/REQ-303 in `docs/requirements.md` for the shop-freeze invariant and freeze-row self-healing behaviour.
