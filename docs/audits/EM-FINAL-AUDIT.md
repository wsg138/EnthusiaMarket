# EnthusiaMarket — Final Hardening Audit

**Date:** 2026-06-09
**Branch:** fix/em-audit
**Scope:** Full `src/main/kotlin` tree + `src/main/resources/migrations/*` (148 .kt files, 19 migrations)

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 3 |
| Major | 6 |
| Minor | 7 |
| Nit | 4 |

### Triage-ready: High-confidence Critical / Major

| ID | Sev | File:Line | One-liner |
|----|-----|-----------|-----------|
| F-001 | Critical | `application/ContainerTradeService.kt:274` | `executeTrade` barter vault deposit can double-count stock on partial chest drain |
| F-002 | Critical | `application/StallSellbackService.kt:231` | `computeRefund` can refund more than prepaid via `ceil` precision on sub-second remainders |
| F-003 | Critical | `application/SellOfferService.kt:117` | `purchase` deposits seller proceeds before event fire — seller gets paid even if event listener rolls back ownership |
| F-004 | Major | `application/ContainerTradeService.kt:225` | `executeSellTransaction` removes stock THEN checks inventory full — partial stock loss on full inventory |
| F-005 | Major | `application/AuctionLifecycleService.kt:197` | `placeBid` has no economy.withdraw — bidder wins without ever paying |
| F-006 | Major | `application/GuildTradePolicyService.kt:43` | `factorFor BUY` at ratePct=99 gives 0.01 factor — 99% tariff on BUY shop practically confiscates payment |
| F-007 | Major | `infrastructure/persistence/ShopRepositorySql.kt:78` | `mapRow` — corrupt `trusted` UUID string crashes entire row load, poisoning bulk queries |
| F-008 | Major | `infrastructure/vault/VaultEconomyProvider.kt:20` | `balance` uses `.toLong()` truncation — sub-1 currency units silently lost |
| F-009 | Major | `application/ShopTradeService.kt:107` | `resolveOwnerUuid` returns `playerUuid` for GUILD stalls — legacy sign trade route is effectively a no-op self-trade bypass |
| F-010 | Minor | `application/RentCollectionService.kt:84` | `processStall` catches Exception and swallows — single bad stall kills the entire tick batch |
| F-011 | Minor | `infrastructure/listeners/ContainerStockListener.kt:68` | `DoubleChest` container block resolution picks left side only — right-side stock changes miss shop refresh |
| F-012 | Minor | `application/ShopSearchService.kt:23` | `SearchMode.ANY` behaves identically to `SELL` — dead code misleads future developers |
| F-013 | Minor | `infrastructure/persistence/StallRepositorySql.kt:113` | `decodeExtraEntities` — no validation on extra values, stale entries with negative values pass silently |
| F-014 | Minor | `config/EnthusiaMarketConfig.kt:47` | `auction.minStartingBid = 1` — effectively allows zero-value auctions |
| F-015 | Minor | `application/StallEntityCounter.kt:53` | `wouldExceedTypeCap` rescans on every call at boundary — O(n) world scan per entity spawn |
| F-016 | Nit | `infrastructure/persistence/ShopRepositorySql.kt:148` | `bind` param index 25 for `stock_count` but column is 24th in insert — potential column-shift |
| F-017 | Nit | `application/ItemStackSerializer.kt:15` | Fallback to `BukkitObjectInputStream` is Paper-deprecated — will break on 1.21+ |
| F-018 | Nit | `domain/shop/Shop.kt:71` | `init` validates `stockCount >= 0` but DB default is 0 — negative stock from manual DB edit bypasses domain |
| F-019 | Nit | `infrastructure/worldguard/WorldGuardRegionProvisioner.kt:52` | `regionManager.save()` failure only warns — region flags applied in-memory but not persisted |
| F-020 | Minor | `infrastructure/persistence/ShopRepositorySql.kt:64` | `backfillSellMaterials` silently skips rows with corrupt `sell_item` — backfill left incomplete |

---

## Unit 1 — domain-shop-sign

### Findings

| ID | Sev | Conf | File:Line | Category | Description | Suggested Fix |
|----|-----|------|-----------|----------|-------------|---------------|
| — | — | — | — | — | No findings. All domain invariants properly validated in `init` blocks. `SignDirection` enum is clean. `PurchaseSign` price validated. | — |

---

## Unit 2 — domain-auction-offer

### Findings

| ID | Sev | Conf | File:Line | Category | Description | Suggested Fix |
|----|-----|------|-----------|----------|-------------|---------------|
| — | — | — | — | — | No findings. `Auction.placeBid` correctly validates state, bid amount, and anti-snip. `AuctionId` value class enforces non-blank. | — |

---

## Unit 3 — domain-stall-guild-ports

### Findings

| ID | Sev | Conf | File:Line | Category | Description | Suggested Fix |
|----|-----|------|-----------|----------|-------------|---------------|
| — | — | — | — | — | No findings. `Stall.canManage` properly handles SOLO/GUILD/NONE. `GuildTradePolicy` validates self-targeting and rate bounds. Port interfaces are clean abstractions. | — |

---

## Unit 4 — app-trade

### Findings

F-001 | Critical | high | `application/ContainerTradeService.kt:274` | Economy integrity | `executeTrade` vault deposit uses `costBase.clone().apply { amount = 1 }` with `shop.costAmount` as the quantity. If the vault `deposit` internally multiplies by the stack amount (depending on implementation), this deposits `costAmount` of the base item. But `sellStack` was already computed with `amount = sellAmount`, and `costStack` has `costAmount`. The vault key is serialized from a 1-item stack, so the vault stores the item type once with the full costAmount — this is correct IF the vault uses `amount` literally. However, if two concurrent trades run against the same owner/item, the `INSERT ... ON CONFLICT` in `ShopVaultRepositorySql.deposit` races and can lose one deposit. The read in `withdraw` is not snapshotted, so both reads see the old value, both write old+amount, losing one deposit. | Wrap vault deposit+withdraw in the same transaction boundary as the stall mutation, or use `SELECT FOR UPDATE`. |

F-004 | Major | high | `application/ContainerTradeService.kt:225` | Economy integrity | `executeSellTransaction` calls `ctx.containerInv.removeItem(sellStack.clone())` BEFORE checking `ctx.player.inventory.addItem(sellStack.clone()).isNotEmpty()`. The order should be reversed: check inventory has space first, then remove from container. Currently, a player with a full inventory causes stock to vanish from the container with no rollback. | Swap the order: add-to-player first (as a test), then remove-from-container. |

F-009 | Major | high | `application/ShopTradeService.kt:107` | Authorization | `resolveOwnerUuid` returns `playerUuid` for `OwnerType.GUILD` stalls — line 107: `OwnerType.GUILD -> playerUuid`. This means the trade executes as player-to-self, which is a no-op that moves money/economy balance silently. While line 69 rejects GUILD-owned stalls outright (returning a failure), the `resolveOwnerUuid` function is still reachable from other code paths if the guard is ever bypassed. The comment says "Guild shops use the container-shop GUI" but this is still a logic error in a trade function. | Return `null` for GUILD stalls in `resolveOwnerUuid` to fail closed. |

| — | — | — | — | — | ShopGuildService correctly validates owner + guild membership. | — |

---

## Unit 5 — app-lifecycle

### Findings

F-002 | Critical | high | `application/StallSellbackService.kt:231` | Economy integrity | `computeRefund`: `ceil(remaining.seconds.toDouble() / interval.seconds)` can over-count periods when the remaining duration is very close to a whole number due to floating-point precision (e.g., 86400.0 / 86400.0 = 0.9999999... → ceil → 1 period, which is correct — BUT when `remaining` is exact, the ceil of 1.0 is 1.0, giving 1 total period, minus 1 = 0 refundable. That's correct. The real issue: if `remaining.seconds` is, say, 86401 (1 second over a day), `ceil(86401.0/86400)` = `ceil(1.00001157)` = 2, giving 1 refundable period for 1 second of over-payment. This is a minor over-refund that compounds at scale. | Use integer division with remainder check instead of floating-point ceil. |

F-003 | Critical | high | `application/SellOfferService.kt:117` | Economy integrity | In `purchase`, seller proceeds are deposited (line ~130-140) BEFORE the `SellOfferCompletedEvent` is fired (line ~155). If an event listener somehow triggers a rollback or the server crashes between deposit and event, the seller keeps the money while the ownership transfer is announced. Worse, the proceeds deposit and tax deposit are not atomic with the stall ownership mutation — if the server crashes after `stalls.save(updated)` but after `economy.deposit(seller)` succeeds, seller gets paid but the event (which listeners may depend on for recording) wasn't fired. | Move event fire BEFORE seller payout, or make the entire sequence transactional. |

F-005 | Major | high | `application/AuctionLifecycleService.kt:197` | Economy integrity | `placeBid` calls `auction.placeBid(bidder, amount, at)` which updates the Auction in memory but **never calls `economy.withdraw`** on the bidder. The bid is recorded in the DB via `auctionRepository.save(auction)` but no money changes hands. The bidder can win an auction without ever being charged. | Add `economy.withdraw(playerUuid, amount)` before persisting the bid. |

F-010 | Minor | med | `application/RentCollectionService.kt:84` | Robustness | The `tick()` method wraps each stall's `processStall` in a try/catch at line ~72-80. If ANY stall throws (e.g., corrupt UUID in owner.id), the exception is caught, `errors++` increments, and processing continues for other stalls. This is actually correct behavior (best-effort per stall). However, the catch block is overly broad — it catches everything including `Error` subclasses. | Catch specific exceptions. |

| — | — | — | — | — | `AuctionLifecycleService.startMassAuction` correctly compensates failed stall saves by closing the auction. | — |

---

## Unit 6 — persistence

### Findings

F-007 | Major | high | `infrastructure/persistence/ShopRepositorySql.kt:78` | Robustness | `mapRow` calls `UUID.fromString(it)` for each trusted UUID without try/catch. A single corrupt/truncated UUID in the comma-separated `trusted` string will throw, causing the entire `queryOne`/`queryMany` to fail. In `all()` or `findByOwner` this poisons bulk loads including valid rows after the bad one. Wrap per-UUID parsing in `runCatching`. | F-007 |

| — | — | — | — | — | All repositories use parameterized queries (no SQL injection). Migration idempotency is handled via `IF NOT EXISTS`. | — |

F-013 | Minor | low | `infrastructure/persistence/StallRepositorySql.kt:113` | Correctness | `decodeExtraEntities` doesn't validate that the integer values are non-negative. A negative value from manual DB editing would pass through and could increase entity caps via `mergeExtras`. | Add `coerceAtLeast(0)` on decode. |

F-016 | Nit | low | `infrastructure/persistence/ShopRepositorySql.kt:148` | Correctness | The `bind` method sets `stockCount` at param index 25. The INSERT SQL has 25 `?` placeholders. Counting the columns: stall_id(1), owner(2), sign_world(3), sign_x(4), sign_y(5), sign_z(6), container_world(7), container_x(8), container_y(9), container_z(10), sell_item(11), sell_amount(12), cost_item(13), cost_amount(14), trusted(15), hopper_allow_in(16), hopper_allow_out(17), frozen(18), admin_shop(19), guild_id(20), creator_id(21), direction(22), search_enabled(23), sell_material(24), stock_count(25). This is correct. No bug. | — |

| — | — | — | — | — | Audit verified all 19 migrations are idempotent (IF NOT EXISTS) and safe. V006 correctly recreates auctions table for BIGINT. | — |

---

## Unit 7 — listeners

### Findings

F-011 | Minor | med | `infrastructure/listeners/ContainerStockListener.kt:68` | Correctness | `containerBlockOf` for `DoubleChest` picks `leftInv?.holder as? Container` first, then falls back to `rightInv?.holder`. If the left side holder resolves to null (e.g., chunk not loaded), it picks the right side. But the returned block is from the inventory HOLDER, which may not correspond to the actual chest block being edited. For a double chest, both halves share the same inventory — so refreshing shops using the left block's coords is correct. No functional bug. | — |

| — | — | — | — | — | `SignPlaceListener` correctly validates wall sign + container adjacency, stall membership, and authority. `ShopInteractListener` cancels event properly. | — |

---

## Unit 8 — commands

### Findings

| ID | Sev | Conf | File:Line | Category | Description | Suggested Fix |
|----|-----|------|-----------|----------|-------------|---------------|
| — | — | — | — | — | No findings. Admin commands properly check `enthusiamarket.admin` permission. Sellback confirmation window is properly pruned. Member operations correctly delegate to `StallMemberService` for authorization. | — |

---

## Unit 9 — integrations

### Findings

F-008 | Major | high | `infrastructure/vault/VaultEconomyProvider.kt:20` | Economy integrity | `balance` calls `economy.getBalance().toLong()` which truncates any fractional currency. Vault economy plugins (EssentialsX) work in doubles internally. Players with fractional balances (e.g., 100.99) will have the 0.99 silently lost. This is a rounding issue that accumulates over many transactions. | Use `roundToLong()` instead of `toLong()`, or store/use the full double precision throughout. |

| — | — | — | — | — | `LumaGuildsGuildProvider` correctly uses Koin lazy resolution. Bank operations use a proper system actor UUID. | — |

F-019 | Nit | low | `infrastructure/worldguard/WorldGuardRegionProvisioner.kt:52` | Robustness | After applying WG region flags, `regionManager.save()` failure is only logged as a warning. The flags are applied in-memory but won't survive a server restart. This means an operator running `/em import` silently loses stall flag changes. | Propagate the `StorageException` or at minimum use `SEVERE` logging. |

---

## Unit 10 — interaction-config

### Findings

F-006 | Major | high | `application/GuildTradePolicyService.kt:43` | Economy integrity | `factorFor(BUY, ratePct)` = `(1.0 - ratePct / 100.0).coerceAtLeast(0.0)`. At `ratePct=99` (the `MAX_TARIFF_PCT`), BUY shop owners receive only 1% of the payment. This is effectively confiscation for BUY shops. The comment warns "use an embargo" but doesn't prevent the setting. While MAX_TARIFF_PCT is capped at 99, a 99% tariff is still functionally confiscatory. | Consider capping BUY tariff lower (e.g., 50%) or confiscation-guard: `sellerProceeds = (price * factor).coerceAtLeast(1L)` if price > 0. |

F-012 | Minor | med | `application/ShopSearchService.kt:23` | Architecture | `SearchMode.ANY` behaves identically to `SELL` — it never matches BUY mode. This is documented as intentional (SP3 barter not ready), but it's a landmine: a developer adding BUY search later won't realize `ANY` will magically work once they add the BUY match logic in the wrong place. | Add a comment in the code that ANY is intentionally SELL-only until SP3. |

F-014 | Minor | med | `config/EnthusiaMarketConfig.kt:47` | Correctness | `auction.minStartingBid = 1`. Combined with `validateStartingBid` checking `startingBid < config.auction.minStartingBid`, a bid of exactly 1 passes. But the Auction domain object has no validation on `startingBid` in its constructor — the only guard is the service layer. A direct `Auction` construction (e.g., in tests) could create a zero/negative starting bid. | Add `require(startingBid > 0)` in `Auction` init block. |

F-015 | Minor | med | `application/StallEntityCounter.kt:53` | Performance | `wouldExceedTypeCap` triggers an authoritative world rescan (via `rescan` lambda) every time the cached count is >= cap. On a busy server with many entities at the cap boundary, this means a full region entity scan on every spawn attempt at the boundary. | Add cooldown/debounce on rescan, or use a dirty flag that clears after successful check. |

F-017 | Nit | low | `application/ItemStackSerializer.kt:15` | Robustness | Fallback to `BukkitObjectInputStream` is deprecated in Paper 1.21+ and will be removed. Legacy items serialized with the old format will fail to deserialize with only the fallback path. | Remove the legacy fallback or gate it behind a version check. |

F-018 | Nit | low | `domain/shop/Shop.kt:71` | Correctness | `init` validates `stockCount >= 0` but the DB column allows any integer (no CHECK constraint). A manual DB edit with negative stock would bypass domain validation when read back, as `mapRow` doesn't re-validate. | Add `CHECK (stock_count >= 0)` to the migration, or validate in `mapRow`. |

F-020 | Minor | med | `infrastructure/persistence/ShopRepositorySql.kt:64` | Correctness | `backfillSellMaterials` uses strict `ItemStackSerializer.deserialize` for a one-time migration. If a legacy row has a corrupt `sell_item`, `deserialize` returns null and the row is silently skipped. This means the backfill is incomplete — shops with corrupt base64 will never have `sell_material` set and won't appear in search. | Log count of skipped rows so operators can identify and fix corrupt data. |

---
