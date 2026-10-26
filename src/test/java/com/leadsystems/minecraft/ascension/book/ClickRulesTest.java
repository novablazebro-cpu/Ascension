package com.leadsystems.minecraft.ascension.book;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickRulesTest {

    /** Builder-ish helper: defaults to a plain left click in the player's own inventory view. */
    static ClickRules.ClickFacts facts(boolean externalTop, boolean clickedTop, InventoryAction action, ClickType click,
                                       boolean current, boolean cursor, boolean hotbar, boolean offhand) {
        return new ClickRules.ClickFacts(externalTop, clickedTop, false, action, click, current, cursor, hotbar, offhand);
    }

    @Test
    void clicksWithoutTheBookAreNeverTouched() {
        assertFalse(ClickRules.deny(facts(true, true, InventoryAction.PLACE_ALL, ClickType.LEFT,
                false, false, false, false)));
    }

    @Test
    void shiftClickIntoAContainerIsDenied() {
        assertTrue(ClickRules.deny(facts(true, false, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT,
                true, false, false, false)));
    }

    @Test
    void shiftClickInsideOwnInventoryIsAllowed() {
        assertFalse(ClickRules.deny(facts(false, false, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT,
                true, false, false, false)));
    }

    @Test
    void placingTheBookInAContainerSlotIsDenied() {
        assertTrue(ClickRules.deny(facts(true, true, InventoryAction.PLACE_ALL, ClickType.LEFT,
                false, true, false, false)));
        assertTrue(ClickRules.deny(facts(true, true, InventoryAction.SWAP_WITH_CURSOR, ClickType.LEFT,
                false, true, false, false)));
    }

    @Test
    void placingTheBookInOwnInventoryIsAllowed() {
        assertFalse(ClickRules.deny(facts(true, false, InventoryAction.PLACE_ALL, ClickType.LEFT,
                false, true, false, false)));
    }

    @Test
    void ownCraftingGridIsTopAndDenied() {
        assertTrue(ClickRules.deny(facts(false, true, InventoryAction.PLACE_ALL, ClickType.LEFT,
                false, true, false, false)));
    }

    @Test
    void numberKeySwapIntoContainerIsDenied() {
        assertTrue(ClickRules.deny(facts(true, true, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY,
                false, false, true, false)));
    }

    @Test
    void numberKeySwapWithinOwnInventoryIsAllowed() {
        assertFalse(ClickRules.deny(facts(true, false, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY,
                false, false, true, false)));
    }

    @Test
    void offhandSwapIntoContainerIsDenied() {
        assertTrue(ClickRules.deny(facts(true, true, InventoryAction.HOTBAR_SWAP, ClickType.SWAP_OFFHAND,
                false, false, false, true)));
    }

    @Test
    void droppingTheBookIsDenied() {
        assertTrue(ClickRules.deny(facts(false, false, InventoryAction.DROP_ONE_SLOT, ClickType.DROP,
                true, false, false, false)));
        assertTrue(ClickRules.deny(facts(false, false, InventoryAction.DROP_ALL_CURSOR, ClickType.LEFT,
                false, true, false, false)));
    }

    @Test
    void droppingSomethingElseWhileHoldingTheBookIsAllowed() {
        assertFalse(ClickRules.deny(facts(false, false, InventoryAction.DROP_ONE_SLOT, ClickType.DROP,
                false, true, false, false)));
    }

    @Test
    void clickingOutsideWithTheBookIsDenied() {
        assertTrue(ClickRules.deny(new ClickRules.ClickFacts(false, false, true, InventoryAction.NOTHING,
                ClickType.LEFT, false, true, false, false)));
    }

    @Test
    void bundleActionsWithTheBookAreDenied() {
        assertTrue(ClickRules.deny(facts(false, false, InventoryAction.PICKUP_ALL_INTO_BUNDLE, ClickType.RIGHT,
                true, false, false, false)));
        assertTrue(ClickRules.deny(facts(false, false, InventoryAction.PLACE_ALL_INTO_BUNDLE, ClickType.LEFT,
                false, true, false, false)));
    }

    @Test
    void cloningTheBookIsDenied() {
        assertTrue(ClickRules.deny(facts(false, false, InventoryAction.CLONE_STACK, ClickType.MIDDLE,
                true, false, false, false)));
    }

    @Test
    void pickingTheBookUpFromOwnSlotIsAllowed() {
        assertFalse(ClickRules.deny(facts(true, false, InventoryAction.PICKUP_ALL, ClickType.LEFT,
                true, false, false, false)));
    }

    @Test
    void takingAStrayBookOutOfAContainerIsAllowed() {
        assertFalse(ClickRules.deny(facts(true, true, InventoryAction.PICKUP_ALL, ClickType.LEFT,
                true, false, false, false)));
    }
}
