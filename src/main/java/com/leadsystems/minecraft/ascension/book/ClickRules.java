package com.leadsystems.minecraft.ascension.book;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

/**
 * Pure decision: may this inventory click move a Chronicle?
 * <p>
 * Rule: a Chronicle may only ever sit in the player's own storage, hotbar or offhand. So any click
 * that would put it into the top inventory (a container, a crafting grid - including the 2x2 grid
 * of the player's own inventory screen - a trade, an anvil...), drop it, clone it, or put it into a
 * bundle is denied. Moving it around inside the player's own inventory is allowed, and taking a
 * stray copy <i>out</i> of a container is allowed (the reconciler removes it afterwards).
 */
public final class ClickRules {

    /**
     * Everything the decision needs, extracted from an {@code InventoryClickEvent}.
     *
     * @param externalTop      the top inventory is not the player's own inventory screen
     * @param clickedTop       the clicked raw slot belongs to the top inventory
     * @param outside          the click was outside the window (drops the cursor)
     * @param action           Bukkit's resolved action
     * @param click            the click type
     * @param currentIsBook    the clicked slot holds a Chronicle
     * @param cursorIsBook     the cursor holds a Chronicle
     * @param hotbarItemIsBook for number-key clicks: the hotbar slot holds a Chronicle
     * @param offhandIsBook    the offhand holds a Chronicle
     */
    public record ClickFacts(boolean externalTop, boolean clickedTop, boolean outside, InventoryAction action,
                             ClickType click, boolean currentIsBook, boolean cursorIsBook,
                             boolean hotbarItemIsBook, boolean offhandIsBook) {}

    private ClickRules() {}

    /** True if the click must be cancelled. */
    public static boolean deny(ClickFacts f) {
        // HOTBAR_MOVE_AND_READD is marked for removal in Paper; compare by name so this keeps
        // compiling (and keeps working) whether or not the constant exists.
        boolean numberKey = f.action() == InventoryAction.HOTBAR_SWAP
                || f.action().name().equals("HOTBAR_MOVE_AND_READD");
        boolean hotbarBook = numberKey && f.click() != ClickType.SWAP_OFFHAND && f.hotbarItemIsBook();
        boolean offhandBook = f.click() == ClickType.SWAP_OFFHAND && f.offhandIsBook();
        if (!f.currentIsBook() && !f.cursorIsBook() && !hotbarBook && !offhandBook) {
            return false;
        }

        switch (f.action()) {
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR -> {
                return f.cursorIsBook();
            }
            case DROP_ALL_SLOT, DROP_ONE_SLOT -> {
                return f.currentIsBook();
            }
            case CLONE_STACK -> {
                return f.currentIsBook();
            }
            default -> {
                // handled below
            }
        }
        if (f.outside()) {
            return f.cursorIsBook();
        }
        if (f.action().name().contains("BUNDLE")) {
            return true;
        }
        if (f.clickedTop()) {
            // Anything that ends with the book in a top slot: cursor placement, number-key or
            // offhand swap. Taking a book out of the top inventory is fine.
            return f.cursorIsBook() || hotbarBook || offhandBook;
        }
        // Clicked the player's own inventory: only shift-clicking into an external container is bad.
        return f.externalTop() && f.currentIsBook() && f.action() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
    }
}
