---
title: Ownership Limits
audience: player
topic: limits
summary: How stall ownership limits work — total caps, per-kind caps, and permission groups.
keywords: [limits, caps, ownership, permission groups]
related: [stalls]
updated: 2026-06-06
---

# Ownership Limits

By default, there's no limit to how many stalls you can own. Admins can configure limits using permission groups.

## Checking your limits

```
/em limit
```

Shows your current ownership count vs. cap for:

- **Total stalls** — overall maximum.
- **Per region kind** — caps for specific stall types (e.g., "small", "large", "premium").

## How limits work

Admins define **limit groups** in the config. Each group has:

- A total stall cap.
- Optional per-region-kind caps.

Players are assigned to groups via permissions:

```
enthusiamarket.limit.<group-name>
```

If you have multiple limit groups, the **best** (highest) cap applies for each dimension.

## Example

| Group | Total | Small stalls | Large stalls |
|-------|-------|-------------|-------------|
| default | 2 | -1 (unlimited) | 1 |
| vip | 5 | -1 | 3 |

A player with both `enthusiamarket.limit.default` and `enthusiamarket.limit.vip` gets: total=5, small=unlimited, large=3.

## Unlimited caps

A cap of `-1` means unlimited in that dimension.

## Region kinds

Stalls are categorized by **region kind** (e.g., "default", "premium"). Admins assign kinds to stall regions. This lets them limit how many premium vs. regular stalls a player can own.
