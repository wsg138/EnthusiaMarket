package net.badgersmc.em.interaction

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui

/**
 * Block all raw item movement in a menu (anti-dupe).
 *
 * IFramework 0.11.6 cancels only **top-inventory** clicks by default. That leaves the
 * double-click *collect-to-cursor* sweep (which vacuums matching items out of the menu's
 * displayed slots into the player's cursor) and item *drags* uncancelled — so a player can pull
 * the real `ItemStack`s a menu renders (vault contents, sell/cost previews, search icons) into
 * their own inventory for free. This is an item dupe / theft.
 *
 * Cancelling every click + drag closes all of those vectors. Intended menu actions are driven by
 * `GuiItem` `onClick` consumers (plain code), which IFramework still invokes even when the event
 * is cancelled — so buttons, toggles, paging, and click-to-withdraw keep working; only vanilla
 * item movement is blocked. None of EM's Java menus accept item drop-in, so a blanket cancel is
 * always correct here.
 */
fun ChestGui.blockItemTheft() {
    setOnGlobalClick { it.isCancelled = true }
    setOnGlobalDrag { it.isCancelled = true }
}
