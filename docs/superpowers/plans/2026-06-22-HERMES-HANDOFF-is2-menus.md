# HERMES HANDOFF — ItemShops parity menu cluster (IS2-9/12/13/14/15)

**Job:** `is2-menus`  **Base:** `origin/main`  **Branch:** `fix/is2-menus`
**Executor:** autonomous coordinator. These are INFRA (GUI/command) tasks — no failing-test
driver; each is verified by the build + your reading. Touch ONLY the files named per task.

You are in `/opt/data/EnthusiaMarket`. This plan is self-contained — do not read other docs.

---

## Gradle invocation (EVERY gradle command)

```
export JAVA_HOME=/opt/data/jdk-21.0.11+10
./gradlew <tasks> -Plumaguilds.jar=/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain
```
`java` is NOT on PATH — the export is mandatory.

---

## CONFIRMED API SYMBOLS (verified on origin/main — do not invent)

- **Menu base:** `interface net.badgersmc.em.interaction.Menu { fun open(player: Player) }`.
  Java menus build an IFramework `ChestGui` and call `gui.show(player)`.
- **Anti-dupe (MANDATORY for every menu that shows items):** call
  `net.badgersmc.em.interaction.blockItemTheft()` on the `ChestGui` right before `gui.show(player)`
  — `import net.badgersmc.em.interaction.blockItemTheft`. It cancels global click+drag so displayed
  items can't be extracted. Every NEW menu in this plan MUST call it. (See any existing menu under
  `src/main/kotlin/net/badgersmc/em/interaction/gui/` for the exact placement, e.g. ShopVaultMenu.)
- **IFramework:** `com.github.stefvanschie.inventoryframework.gui.type.ChestGui(rows, ComponentHolder.of(lang.msg(key)))`,
  `pane = StaticPane(9, rows)`, `gui.addPane(pane)`, `pane.addItem(GuiItem(itemStack) { it.isCancelled = true }, x, y)`.
  `ComponentHolder` import: `com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder`.
- **Vault:** `net.badgersmc.em.application.ShopVaultService` — `contents(owner: UUID): List<Pair<ItemStack, Int>>`,
  `withdraw(owner: UUID, item: ItemStack, amount: Int): Int` (returns amount actually removed),
  `deposit(owner: UUID, item: ItemStack, amount: Int)`. Existing `ShopVaultMenu(owner: Player,
  vaultService: ShopVaultService, page: Int, lang: LangService)` shows these — read it before IS2-14.
- **Item serialize:** `net.badgersmc.em.application.ItemStackSerializer.deserialize(base64: String): ItemStack?`.
- **Shop:** `Shop.sellItem: String` (base64), `containerWorld/containerX/Y/Z`, `owner: UUID`, `id: Long`.
  `ShopRepository.findByOwner(owner): List<Shop>`, `.all()`, `.findBySign(world,x,y,z): Shop?`.
- **Command class pattern (auto-registered by `registerPaperCommands(basePackage="net.badgersmc.em")`):**
  ```kotlin
  @net.badgersmc.nexus.commands.annotations.Command(name = "store", description = "...", aliases = ["..."])
  class StoreCommands(private val config: EnthusiaMarketConfig, private val lang: LangService) {
      @net.badgersmc.nexus.paper.commands.annotations.Subcommand("show")
      @net.badgersmc.nexus.paper.commands.annotations.Permission("enthusiamarket.shop.store")
      fun show(@net.badgersmc.nexus.commands.annotations.Context sender: CommandSender) { ... }
  }
  ```
  (Mirror `infrastructure/commands/VaultCommands.kt` exactly — it is the proven pattern.)
- **`/shop admin` subcommands** live in `infrastructure/commands/ShopCommands.kt` as
  `@Subcommand("admin <x>")` + `@Permission("enthusiamarket.admin.shop")` + `@Context sender: CommandSender`.
  Offline-player arg: `@Arg("player") name: String` (`net.badgersmc.nexus.commands.annotations.Arg`),
  resolve `Bukkit.getOfflinePlayer(name)`, reject when `target.name == null && !target.hasPlayedBefore()`
  with `lang.msg("shop.cmd.unknown_player", "name" to name)`. Admin look-at shops: see `adminFix`/`lookAtShop`.
- **Config:** nested `class`es in `config/EnthusiaMarketConfig.kt` with `@net.badgersmc.nexus.config.Comment`
  and `var field = default`; add a top-level `var x = X()` field. (See `class ShopAudit` for the shape.)
- **Lang:** add keys to `infrastructure/i18n/EnthusiaMarketLang` + the lang resource; access via `lang.msg("key", "k" to v)`.

> For every new lang key and permission node, follow the existing files' conventions. If a `@Permission`
> node is new, add it to the nexus-permissions DSL the same way the existing `enthusiamarket.shop.*` nodes are declared.

---

## Task A — IS2-14: "Redeem All" button on the vault menu (REQ-300)

**File (edit):** `src/main/kotlin/net/badgersmc/em/interaction/gui/ShopVaultMenu.kt`
Add an `EMERALD_BLOCK` "Redeem All" `GuiItem` to the bottom row (e.g. slot 4,5). On click
(`it.isCancelled = true`), iterate `vaultService.contents(owner.uniqueId)` and for each `(item, total)`
withdraw what fits into the player inventory (reuse the existing per-item withdraw + overflow re-deposit
logic — extract a private `redeem(item, requested)` helper and call it from both the per-item handler and
the new button so you don't duplicate the overflow handling). Re-open the menu to refresh. Keep
`blockItemTheft()` intact. Confirm build green.

## Task B — IS2-9: admin vault inspection (REQ-295)

**Files (new):** `src/main/kotlin/net/badgersmc/em/interaction/gui/VaultAdminMenu.kt`
**File (edit):** `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
New read-only `VaultAdminMenu(target: UUID, targetName: String, vaultService: ShopVaultService, lang)` :
`Menu` — paginated (45/page) view of `vaultService.contents(target)` rendered as item icons with an
amount lore line; NO withdraw/click action (read-only); MUST call `blockItemTheft()`. Add
`@Subcommand("admin vault")` + `@Permission("enthusiamarket.admin.shop")` to ShopCommands taking
`@Arg("player") name`, resolving the offline player as in `trust`, opening `VaultAdminMenu`. Build green.

## Task C — IS2-13: read-only shop-contents view (REQ-299)

**Files (new):** `src/main/kotlin/net/badgersmc/em/interaction/gui/ShopContentsMenu.kt`
**File (edit):** `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
New `ShopContentsMenu(shop: Shop, lang)` : `Menu` — a 54-slot read-only mirror of the shop's container
inventory: resolve `Bukkit.getWorld(shop.containerWorld)?.getBlockAt(x,y,z)?.state as? org.bukkit.block.Container`,
copy `container.inventory.contents` into the gui (skip if the block isn't a container / chunk unloaded —
mirror `EnthusiaMarket.loadedContainer`). All clicks cancelled; MUST call `blockItemTheft()`. Add
`@Subcommand("admin contents")` + `@Permission("enthusiamarket.admin.shop")` to ShopCommands using the
existing `lookAtShop(player)` admin targeting (mirror `adminFix`) to open it. Build green.

## Task D — IS2-12: shulker box preview (REQ-298)

**Files (new):** `src/main/kotlin/net/badgersmc/em/interaction/gui/ShulkerPreviewMenu.kt`
**File (edit):** `src/main/kotlin/net/badgersmc/em/interaction/gui/PurchaseMenu.kt`
New `ShulkerPreviewMenu(shulker: ItemStack, lang)` : `Menu` — read the shulker's contents via
`(shulker.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)?.blockState as? org.bukkit.block.ShulkerBox`,
then `?.inventory?.contents`; render them read-only (27 slots), all clicks cancelled; MUST call
`blockItemTheft()`. In PurchaseMenu, when the deserialized sell item
(`ItemStackSerializer.deserialize(shop.sellItem)`) is a shulker box (its meta is `BlockStateMeta` with a
`ShulkerBox` state), add a "Preview Contents" button that opens `ShulkerPreviewMenu`. Build green.

## Task E — IS2-15: help + store commands (REQ-301)

**Files (new):** `src/main/kotlin/net/badgersmc/em/infrastructure/commands/StoreCommands.kt`,
  `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopHelpCommands.kt`
**File (edit):** `config/EnthusiaMarketConfig.kt` (+ lang + permissions)
Mirror `VaultCommands.kt`. `@Command(name="store", aliases=["shopstore"])` → sends the configured store
URL; add `class Store { @Comment(...) var url: String = "https://example.com/store" }` + `var store = Store()`
to config. `@Command(name="shophelp", aliases=["shoptutorial","sht"])` → sends a multi-line tutorial from
lang keys. Perms `enthusiamarket.shop.store` / `enthusiamarket.shop.help`, default true. Build green.

---

## Final gate (must pass before pushing)

```
export JAVA_HOME=/opt/data/jdk-21.0.11+10
./gradlew clean detekt test shadowJar -Plumaguilds.jar=/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain
```
Must end `BUILD SUCCESSFUL`, detekt 0, all tests passing. If detekt flags any new method as too long
(limit 60) or too complex, extract a private helper — do NOT add blanket `@Suppress`.

Commit after EACH task with a `feat(shop): … (IS2-N, REQ-…)` message ending with
`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. When green, push to the **fork only**:
`git push fork fix/is2-menus`. Finish by printing `IS2-MENUS_DONE` + the commit short-hashes + the final
`BUILD SUCCESSFUL`. If a gate fails 3 times on one task, commit the green tasks, push, and print
`IS2-MENUS_HALT` with the failing task + error.
