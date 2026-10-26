package com.leadsystems.minecraft.ascension.market;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.*;

class MarketInventoryTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void close() { MockBukkit.unmock(); }

    @Test void debitSpansStacksAndNeverMutatesOriginal() {
        ItemStack[] before = {new ItemStack(Material.EMERALD_BLOCK, 2), new ItemStack(Material.EMERALD_BLOCK, 3)};
        ItemStack[] after = MarketInventory.debit(before, "EMERALD_BLOCK", 4);
        assertNotNull(after); assertNull(after[0]); assertEquals(1, after[1].getAmount());
        assertEquals(2, before[0].getAmount()); assertEquals(3, before[1].getAmount());
        assertNull(MarketInventory.debit(before, "EMERALD_BLOCK", 6));
    }
    @Test void renamedCurrencyCannotPayAndProtectedChronicleCannotSell() {
        ItemStack named = new ItemStack(Material.DIAMOND_BLOCK, 4);
        named.editMeta(meta -> meta.displayName(Component.text("Relic")));
        assertNull(MarketInventory.debit(new ItemStack[]{named}, "DIAMOND_BLOCK", 1));
        ItemStack bound = new ItemStack(Material.BOOK);
        bound.editMeta(meta -> meta.getPersistentDataContainer().set(new NamespacedKey("ascension", "owner"),
                PersistentDataType.STRING, "player"));
        assertFalse(MarketMenus.isSellable(bound)); assertTrue(MarketMenus.isSellable(named));
    }
    @Test void largeMailPaymentSplitsIntoLegalStacksWithoutOverflow() {
        ItemStack[] after = MarketInventory.credit(new ItemStack[3], new ItemStack(Material.EMERALD_BLOCK, 150));
        assertNotNull(after); assertEquals(64, after[0].getAmount()); assertEquals(64, after[1].getAmount());
        assertEquals(22, after[2].getAmount());
        assertNull(MarketInventory.credit(new ItemStack[2], new ItemStack(Material.EMERALD_BLOCK, 150)));
    }
    @Test void fullInventoryCapacityIncludesOnlyMatchingPartialStacks() {
        ItemStack[] before = {new ItemStack(Material.STONE, 64), new ItemStack(Material.EMERALD_BLOCK, 60)};
        assertEquals(4, MarketInventory.capacity(before, new ItemStack(Material.EMERALD_BLOCK)));
        assertNull(MarketInventory.credit(before, new ItemStack(Material.EMERALD_BLOCK, 5)));
        assertEquals(60, before[1].getAmount());
    }
    @Test void sellConfirmationShowsWholeStackQuantityAndCurrency() {
        var screen = MarketMenus.sellConfirm(3, new ItemStack(Material.ARROW, 32), "netherite", 2);
        String content = screen.body().stream().map(com.leadsystems.minecraft.ascension.text.Messages::plain)
                .reduce("", (a, b) -> a + b);
        assertTrue(content.contains("32")); assertTrue(content.contains("Netherite Ingots"));
        assertEquals(2, screen.buttons().size()); assertTrue(screen.confirm());
    }
}
