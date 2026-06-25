---
title: Troubleshooting
audience: admin
topic: troubleshooting
summary: Common problems and their solutions.
keywords: [troubleshooting, debug, errors, common issues]
related: [installation, config, integration]
updated: 2026-06-25
---

# Troubleshooting

## Plugin won't enable

**Symptom:** Console shows errors on startup, plugin doesn't load.

Check:

1. Paper 1.21.11 or later — run `/version`.
2. All hard dependencies installed: WorldGuard, Vault, EnthusiaCurrency, LumaGuilds.
3. Console error messages — they name the missing dependency.

Enable debug logging:

```yaml
debug:
  logEconomy: true
  logMigrations: true
```

## Rent not working / stalls not imported

**Symptom:** No stalls show up, `/em list` is empty.

Run `/em import` to register WorldGuard regions. Check:

1. `market.world` matches your world name.
2. `market.regionPrefix` matches your region naming (e.g. `stall` for `stall1`).
3. WorldGuard regions exist and are loaded.

## Rent collecting too fast / stalls going to emergency auction

**Symptom:** Players lose stalls quickly, rent drains fast.

Check `rent.formulaPct` — it's a **percentage**, not a fraction.  
`1.0` = 1% per period. `100.0` = 100% per period (stall price drained daily).

Recommendation: use flat rent mode for predictable costs.

```yaml
rent:
  mode: flat
  flatAmount: 500
```

Then run `/em rent resync` to push terms to existing stalls.

## Shops not working

**Symptom:** Players can't create shops, transactions fail.

Check:

1. Player owns the stall or has member access.
2. For guild stalls: player has `MANAGE_SHOPS` guild permission.
3. Economy plugin (EnthusiaCurrency) is loaded — check `/plugins`.
4. The container has items (for SELL shops) or the owner has money (for BUY shops).

## Bedrock forms not opening

See [Geyser & Bedrock setup](geyser.md).

## Database errors

**Symptom:** SQL errors in console.

- SQLite: check `plugins/EnthusiaMarket/enthusiamarket.db` exists and is writable.
- MariaDB: verify host, port, credentials in `database.mariadb.*`.
- Connection pool: increase `database.pool.maxSize` for many concurrent stalls.

## Debug mode

To see detailed economy and migration logs:

```yaml
debug:
  logEconomy: true
  logMigrations: true
```

Run `/em reload` after changes. Check console for `[EnthusiaMarket]` tagged messages.
