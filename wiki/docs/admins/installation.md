---
title: Installation & config.yml
audience: admin
topic: installation
summary: Install EnthusiaMarket on Paper 1.21.x, drop in dependencies, config overview.
keywords: [installation, install, setup, config, config.yml, paper, dependencies]
related: [config, permissions, integration]
updated: 2026-06-25
---

# Installation & config.yml

Install EnthusiaMarket on Paper 1.21.x, drop in dependencies, and walk through the config.

## Quick reference

| Dependency | Type | Required | Notes |
|-----------|------|----------|-------|
| Paper 1.21.11+ | Server | Yes | API version checked at load |
| WorldGuard 7.x | Plugin | Yes | Stall region management |
| Vault | Plugin | Yes | Economy abstraction layer |
| EnthusiaCurrency | Plugin | Yes | Actual Vault economy provider |
| LumaGuilds | Plugin | Yes | Guild integration |
| PlaceholderAPI | Plugin | Soft | Player/guild placeholder hooks |
| WorldEdit | Plugin | Soft | Stall schematic snapshots |
| FastAsyncWorldEdit | Plugin | Soft | Async schematic operations |
| Geyser/Floodgate | Plugin | Soft | Bedrock client support & forms |

> **Note:** WorldGuard, Vault, EnthusiaCurrency, and LumaGuilds are **hard** dependencies. The plugin refuses to enable without them.

## How it works

Drop the EnthusiaMarket JAR into `plugins/`, start the server, and it auto-creates `plugins/EnthusiaMarket/enthusiamarket.yaml` with defaults. The plugin uses SQLite by default (file-based, no external DB needed). On server restart, config changes are reloaded.

## Install steps

1. **Verify your Paper version:**

   ```text
   /version
   ```

   Confirm Paper 1.21.11 or later.

2. **Download dependencies into `plugins/`:**
   - WorldGuard 7.x
   - Vault
   - EnthusiaCurrency
   - LumaGuilds
   - Optionally: PlaceholderAPI, WorldEdit/FAWE, Geyser/Floodgate

3. **Download the EnthusiaMarket JAR** and place it in `plugins/`.

4. **Start the server.** The plugin creates `plugins/EnthusiaMarket/` and generates config with all defaults.

5. **Verify it loaded:**

   ```text
   /version EnthusiaMarket
   ```

   You should see the plugin version. Check console for errors.

6. **Open `plugins/EnthusiaMarket/enthusiamarket.yaml`** and review each section below. Use `/em reload` after supported config changes; restart only when a change requires it.

## Database

Controls how the plugin persists stall and shop data.

```yaml
database:
  type: sqlite               # sqlite or mariadb
  sqliteFile: "enthusiamarket.db"
  mariadb:
    host: "localhost"
    port: 3306
    database: "enthusiamarket"
    username: "em"
    password: ""
  pool:
    maxSize: 10              # 1 for SQLite, 10+ for MariaDB
```

For production servers with multiple stalls, use MariaDB.

## Post-install

After the plugin is running:

1. **Configure your market world** — set `market.world` and `market.regionPrefix`.
2. **Set up WorldGuard regions** — create regions matching your prefix (e.g. `stall1`, `stall2`).
3. **Import stalls** — run `/em import` to register all matching regions.
4. **Set rent mode** — formula or flat (see [Config reference](config.md)).
5. **Run `/em rent resync`** — push config rent terms to existing stalls.

## Reloading

```text
/em reload
```

Reloads `enthusiamarket.yaml` and language files without a full server restart.

```text
/em rent resync
```

Push current config rent terms to all existing stalls. Use this after changing rent settings.
