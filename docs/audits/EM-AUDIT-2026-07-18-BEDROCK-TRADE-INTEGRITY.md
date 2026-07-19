# EM-AUDIT-2026-07-18: Bedrock and trade integrity

## Executive summary

The investigation confirmed nine related transaction-integrity regressions. Bedrock shop buttons used the player's vocabulary while callbacks used the shop's vocabulary, silently routed `SELL` and `BUY` in reverse, never reached automatic barter, and discarded every `ContainerTradeResult`. Bedrock stall ownership also fell through to Java chest menus. On Java, empty `BUY` containers were treated as zero availability, the TRADE placement exception did not cover global collection/drag actions, and player-owned payment stacks had no close-time owner.

The highest-risk defects were the placement-item lifecycle and vault persistence ordering: they could lose payment, leave product in the wrong inventory, or duplicate value after a database exception. The IP limiter could leave a failed bid or purchase bound until restart or lifecycle cleanup. The direction and native-form defects primarily caused failed interaction and platform-parity failures. Tests missed them because the Bedrock form test only constructed a form, menu tests did not exercise dangerous Bukkit actions or close transitions, persistence tests stopped at repository atomicity, and limiter tests did not model acquisition ownership.

These fixes are grouped because they establish one rule across every surface: UI adapters select a domain action, application services own validation and mutation, persistence completes before success/events, and every acquired resource or moved item has an explicit rollback owner.

Base commit: `ba369b261cb8067c08f598addf8171ce2af9da96` (`upstream/main`, 2026-07-18).

## Issue matrix

| ID | Severity | Platform | Feature | Player-visible symptom | Technical root cause | Data or item risk | Introducing commit or PR | Fix commit in this PR | Regression tests |
|---|---|---|---|---|---|---|---|---|---|
| EM-TI-001 | High | Bedrock | Shop direction | BUY on a SELL shop enters `dir=buy` | Player-facing callback names were wired to shop-facing methods | Failed interaction | `fa54c6d4`, exposed by `24ebcc6b` and PR #149 (`efeb16df`) | `8ae63a3` | `BedrockPurchaseFormTest`, `ShopInteractListenerTest` |
| EM-TI-002 | High | Bedrock | Trade feedback | No success, failure, or compensation message | Callback returned `Unit` and discarded `ContainerTradeResult` | Hidden compensation failure | `24ebcc6b` | `8ae63a3` | `BedrockPurchaseFormTest.all result details are reported` |
| EM-TI-003 | High | Bedrock | Barter | TRADE displays/executes the wrong action | TRADE fell through the non-BUY branch | Failed interaction | contract in `fa54c6d4`, deterministic in PR #149 | `8ae63a3` | direction-label and listener-routing tests |
| EM-TI-004 | Medium | Bedrock | Stall ownership | Java chest GUI opens | `PurchaseSignClickListener` bypassed `MenuFactory` | Platform parity | PR #79 (`d122d66a`) | `422fb58` | `PurchaseFlowTest` and listener coverage |
| EM-TI-005 | High | Java | BUY shop | Empty receiving chest reports out of stock | `stockCount` became an execution clamp | Shop unusable | PR #73 (`9b6f692b`), promoted to blocker by PR #158 (`03cace58`) | `d280f65` | application suite and display/multiplier coverage |
| EM-TI-006 | Critical | Java | TRADE GUI | GUI icons can be targeted by global movement actions | placement guard removed global drag/bottom coverage | Item theft/duplication | PR #100 (`31fb367a`), weakened by PR #102 (`db300935`) | `d280f65` | guard action coverage |
| EM-TI-007 | Critical | Java | TRADE GUI lifecycle | Placed payment disappears on close/replacement/disconnect | no close-time ownership/return contract | Item loss | PR #100 (`31fb367a`) | `d280f65` | idempotent return and close-transition coverage |
| EM-TI-008 | Critical | Both | Barter vault | DB exception occurs after items move | vault deposit was outside compensation boundary | Payment/product loss or duplication | barter pipeline consolidated by PR #79 (`d122d66a`); placement path added by PR #100 | `5eec654` | `vault persistence exception rolls automatic barter inventory back` |
| EM-TI-009 | High | Both | IP limiter | Failed operation locks later valid action | limiter mutated during validation without acquisition ownership | Temporary lockout | PR #104 (`527f2857`); auction placement later moved in `f4d696b1` | `bf99933` | `IpLimiterTest` rollback tests and service suite |

## Detailed issues

### EM-TI-001 — reversed Bedrock routing

Users saw `TRADE_FAIL shop=412 dir=buy ... You don't have the items to sell` after pressing BUY on a `SELL` shop. `BedrockPurchaseForm` exposed `onBuy`/`onSell`, but `ShopInteractListener` wired those names directly to `executeBuy`/`executeSell`. The UI name was player-facing; the service name was shop-facing. `fa54c6d4` introduced the ambiguous callback contract, `24ebcc6b` wired it, and PR #149 reduced the form to one direction-specific button, making the mismatch deterministic.

The form now has one `onConfirm: () -> ContainerTradeResult`. `ShopInteractListener.executeBedrockTransaction` is the single router: `SELL -> executeSell`, `BUY -> executeBuy`, `TRADE -> executeTrade`. Back and unknown indexes do not call it. No inventory mutation occurs in the form.

### EM-TI-002 — discarded results

The former `Unit` callbacks threw away success text, failure reason, and compensation detail. The confirmation callback now returns the domain result. The form maps Success to `shop.trade.success`, Failure to `shop.trade.failure`, and CompensationFailed to both compensation keys. Compensation failures remain visible rather than becoming a generic error.

### EM-TI-003 — missing automatic barter

TRADE previously shared the fallback BUY button and callback. It now uses `bedrock.purchase.button_trade` and calls `executeTrade` exactly once. It deliberately does not call `executeTradeWithItem`, which remains owned by the Java placement-slot workflow.

### EM-TI-004 — native Bedrock stall purchasing

PR #79 added personal/guild method and confirmation menus only as InventoryFramework chests. `PurchaseSignClickListener` now routes through `MenuFactory`. Bedrock receives `BedrockPurchaseMethodForm` followed by `BedrockPurchaseConfirmForm`; Java and Cumulus-unavailable players retain `PurchaseMethodMenu`. `PurchaseFlow` centralizes guild eligibility, IP extraction, buy/buyForGuild dispatch, and stall-buyout result messages, so forms do not duplicate limits, auction, delay, permission, economy, persistence, or state rules. `BedrockPurchaseForm` separately maps shop `ContainerTradeResult` values to success, failure, and compensation messages. A successful service result still fires `StallStateChangedEvent`, which drives the existing purchase-sign refresh listener.

The confirmation content includes stall, price, ownership mode, and guild name where applicable. Cancel/back/invalid responses perform no purchase.

### EM-TI-005 — BUY availability

`ShopDisplay.tradesAvailable` used `stockCount` for BUY, and PR #158 later used that display metric as an execution clamp. An empty receiving container therefore returned “Out of stock” before `executeBuy`. BUY selection now uses the existing global cap of 64 and execution attempts the requested count one transaction at a time. The service remains authoritative for player items, receiving capacity, shop funds, policy, and stall state. SELL and TRADE retain stock bounds and partial-completion reporting.

### EM-TI-006 — placement-slot anti-theft guard

The PR #100 guard originally considered global drags and bottom interactions. PR #102 removed handlers after InventoryFramework interference, but its claim that top-only cancellation covered collect-to-cursor and shift movement was incomplete. The scoped guard now cancels `MOVE_TO_OTHER_INVENTORY`, `COLLECT_TO_CURSOR`, hotbar swaps/re-adds, cloning, and unknown actions globally. Top clicks remain cancelled except the owned placement slot. Global drags are cancelled when any top raw slot other than the placement slot is targeted. Ordinary bottom-inventory pickup/place remains usable.

### EM-TI-007 — placement payment lifecycle

The payment stack is player-owned state, not a decorative `GuiItem`. Each rendered TRADE inventory installs a close callback scoped to that exact inventory. Internal rerender marks the close as replacement and transfers slot 15 into the new render. A real close, unrelated inventory replacement, or disconnect-driven close returns the stack once. Inventory overflow is dropped naturally at the player's location. The idempotence flag prevents double return.

### EM-TI-008 — vault persistence compensation

Before this repair, both barter paths moved product and then called `ShopVaultService.deposit`; a thrown repository exception escaped after mutation. Automatic barter now performs: remove payment, remove stock, give product, deposit vault, fire event. If deposit throws, it removes the delivered product, restores container stock, and returns payment. Placement barter leaves payment in the owned GUI slot, removes delivered product, and restores stock. Complete rollback returns Failure; incomplete rollback returns CompensationFailed and invokes `CompensationAlertService`. No completion event fires on either path.

Repository SQL already wraps deposit in a transaction and rolls back SQL failures; no schema change was necessary. The application service now treats that atomic repository failure as a transaction failure rather than success.

### EM-TI-009 — IP acquisition ownership

PR #104 introduced mutable in-memory claims without a token showing whether the current operation created the binding. The new `IpLimiter.Reservation` records kind, IP, target, and whether acquisition was new. Rollback removes only the exact `(IP,target)` entry and only when newly acquired, making repeated rollback safe. Auction bids roll back after domain rejection, withdrawal failure, or persistence failure. Stall purchases claim after non-mutating validation and roll back after withdrawal or award persistence failure. Successful operations retain bindings, and a failed same-auction rebid cannot erase an earlier valid binding.

## Transaction and compensation sequences

1. Currency SELL: validate -> withdraw player -> remove shop stock -> give player item -> deposit owner -> event. Any post-withdraw failure follows the existing currency/inventory rollback.
2. Currency BUY: validate player item/container capacity/shop funds -> move player item to container -> withdraw owner -> deposit player -> event. Existing rollback restores item and funds.
3. Automatic barter: validate -> remove payment -> remove stock -> give product -> persist payment in vault -> event. Vault failure reverses all three inventory mutations.
4. Placement barter: validate owned slot -> remove stock -> give product -> persist a clone of payment -> caller consumes slot -> event. Vault failure reverses product/stock and leaves slot untouched.
5. Failed vault deposit: repository SQL rolls back -> application compensates inventory -> Failure, or alert + CompensationFailed if any compensation step is incomplete.
6. Failed IP-reserved bid: acquire token -> validate/withdraw/save -> rollback token on any failure; keep on success.
7. Failed IP-reserved stall purchase: immutable validation -> acquire token -> withdraw/save -> refund and rollback token on persistence failure; keep on success.

## Validation

Commands use `-Plumaguilds.jar=<absolute-path-to-current-LumaGuilds-2.1.6.jar>`. Build LumaGuilds from its repository or use the current integration artifact; the documented legacy `2.1.0` artifact lacks the visual API used by this base. This placeholder is intentionally portable across maintainer workstations and CI agents.

- Baseline `gradlew test`: passed on 2026-07-18 at base `ba369b2`; 600 tests executed, 0 failed, 1 skipped. Stable report artifact: `build/reports/tests/test/index.html`.
- Focused application tests after implementation: passed.
- Final `gradlew clean test detekt shadowJar`: passed; 602 tests executed, 0 failed, 1 skipped; Detekt passed and the shadow JAR was produced under `build/libs/`. Stable test report artifact: `build/reports/tests/test/index.html`.
- Final `gradlew check`: passed with no failing verification task.

Manual Java runtime matrix: **Not completed**; no local Paper test server was started. Manual Bedrock matrix: **Blocked by environment**; no live Paper + Geyser + Floodgate client session was available. IP runtime checks: **Not completed**; covered by deterministic unit tests only. These statements must not be upgraded without an actual runtime session.

## Upgrade and compatibility notes

- Database migrations: none.
- Configuration migrations: none.
- New permissions: none.
- New language keys: `bedrock.purchase.button_trade`.
- New runtime dependencies: none.
- Public API breaks: none. Internal form constructors and limiter collaboration APIs changed.
- Java/Paper compatibility: unchanged.
- Geyser/Floodgate: native purchase forms are used when Cumulus is available; Java GUI fallback remains.

## Remaining limitations and rollback

InventoryFramework/Bukkit event ordering still requires runtime exercise across the supported Paper build, especially disconnect close ordering and offhand/hotbar variants. Database rollback cannot repair an already-incomplete inventory compensation automatically; those cases deliberately alert operators. IP state remains in-memory by design and resets on restart.

The change adds no schema or persisted-data format. Code rollback is a normal revert. If an operator received a compensation alert, inventory/economy reconciliation must be completed independently before or after rollback.
