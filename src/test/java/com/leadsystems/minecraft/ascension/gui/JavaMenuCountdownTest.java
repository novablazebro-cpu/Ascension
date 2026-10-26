package com.leadsystems.minecraft.ascension.gui;

import com.leadsystems.minecraft.ascension.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JavaMenuCountdownTest {
    ServerMock server;
    JavaMenuRenderer renderer = new JavaMenuRenderer();
    @BeforeEach void setup() { server = MockBukkit.mock(); }
    @AfterEach void close() { MockBukkit.unmock(); }

    Menu.Screen shop(long deadline, String product) {
        return shop("market_shop", deadline, product);
    }

    Menu.Screen shop(String id, long deadline, String product) {
        var timer = new Menu.Countdown("Restock: ", deadline, false);
        return new Menu.Screen(id, Component.text("Shop"), List.of(timer.at(0)), List.of(), List.of(
                new Menu.Button(Component.text(product), List.of(timer.at(0)), Material.OBSIDIAN,
                        Menu.Action.OPEN, "market_stock:" + product, false, Map.of(0, timer))), false, Map.of(0, timer));
    }

    @Test void timerChangesDecorationInPlaceAndRequestsOnlyOneRefreshWhilePending() {
        var player = server.addPlayer();
        long now = System.currentTimeMillis();
        renderer.render(player, shop(now + 20_000, "old"), 10);
        var inventory = player.getOpenInventory().getTopInventory();
        AtomicInteger refreshes = new AtomicInteger();
        renderer.tick(player, 10, refreshes::incrementAndGet, now + 19_000);
        assertSame(inventory, player.getOpenInventory().getTopInventory());
        assertEquals("Restock: 00:01", Messages.plain(inventory.getItem(9).getItemMeta().displayName()));
        assertEquals("Restock: 00:01", Messages.plain(inventory.getItem(10).getItemMeta().lore().getFirst()));
        renderer.tick(player, 10, refreshes::incrementAndGet, now + 20_000);
        renderer.tick(player, 10, refreshes::incrementAndGet, now + 21_000);
        assertEquals(1, refreshes.get());
        assertEquals("Restock: Restocking…", Messages.plain(inventory.getItem(9).getItemMeta().displayName()));
        assertTrue(renderer.refresh(player, 10, shop(now + 120_000, "new"), 11));
        assertSame(inventory, player.getOpenInventory().getTopInventory());
        var holder = (MenuHolder) inventory.getHolder();
        assertEquals(11, holder.token()); assertEquals("market_stock:new", holder.button(10).arg());
        assertFalse(renderer.owns(player, 10)); assertTrue(renderer.owns(player, 11));
        assertFalse(renderer.refresh(player, 10, shop(now + 120_000, "stale"), 12));
        assertEquals("market_stock:new", holder.button(10).arg());
    }

    @Test void closingOrChangingMenuRejectsOldRefreshAndNeverTouchesConfirmation() {
        var player = server.addPlayer();
        long now = System.currentTimeMillis();
        renderer.render(player, shop(now - 1, "old"), 1);
        var confirmation = new Menu.Screen("market_stock:old", Component.text("Confirm"),
                List.of(Component.text("Keep this quote stable")), List.of(), List.of(), true);
        renderer.render(player, confirmation, 2);
        var inventory = player.getOpenInventory().getTopInventory();
        AtomicInteger requests = new AtomicInteger();
        renderer.tick(player, 2, requests::incrementAndGet, now + 10_000);
        assertEquals(0, requests.get());
        assertFalse(renderer.refresh(player, 1, shop(now + 120_000, "new"), 3));
        assertSame(inventory, player.getOpenInventory().getTopInventory());
        player.closeInventory();
        assertFalse(renderer.owns(player, 2));
        assertFalse(renderer.refresh(player, 2, shop(now + 120_000, "new"), 3));
    }

    @Test void categoryPageRefreshKeepsCurrentPageAndRejectsDifferentPage() {
        var player = server.addPlayer();
        long now = System.currentTimeMillis();
        String page = "market_shop:building:2";
        renderer.render(player, shop(page, now + 20_000, "old"), 10);
        var inventory = player.getOpenInventory().getTopInventory();
        assertEquals(page, renderer.shopPage(player, 10));
        AtomicInteger refreshes = new AtomicInteger();
        renderer.tick(player, 10, refreshes::incrementAndGet, now + 20_000);
        assertEquals(1, refreshes.get());
        assertFalse(renderer.refresh(player, 10, shop("market_shop", now + 120_000, "wrong"), 11));
        assertTrue(renderer.refresh(player, 10, shop(page, now + 120_000, "new"), 11));
        assertSame(inventory, player.getOpenInventory().getTopInventory());
        assertEquals(page, ((MenuHolder) inventory.getHolder()).screenId());
    }

    @Test void hoursCountdownIsReadableAndDoesNotOverflow() {
        assertEquals("00:00", Menu.Countdown.format(-1));
        assertEquals("00:01", Menu.Countdown.format(1));
        assertEquals("59:59", Menu.Countdown.format(3_599_000));
        assertEquals("01:00:00", Menu.Countdown.format(3_600_000));
        assertEquals("04:12:34", Menu.Countdown.format((4 * 3600L + 12 * 60 + 34) * 1000));
        assertFalse(Menu.Countdown.format(Long.MAX_VALUE).startsWith("00:00"));
    }

    @Test void wrongViewerOrTokenDoesNotTickAnotherMenu() {
        var player = server.addPlayer();
        var other = server.addPlayer();
        long now = System.currentTimeMillis();
        renderer.render(player, shop(now + 20_000, "old"), 1);
        var inventory = player.getOpenInventory().getTopInventory();
        String original = Messages.plain(inventory.getItem(9).getItemMeta().displayName());
        renderer.tick(player, 2, () -> fail("wrong token"), now + 19_000);
        assertEquals(original, Messages.plain(inventory.getItem(9).getItemMeta().displayName()));
        other.openInventory(inventory);
        assertFalse(renderer.owns(other, 1));
        renderer.tick(other, 1, () -> fail("wrong viewer"), now + 25_000);
        assertEquals(original, Messages.plain(inventory.getItem(9).getItemMeta().displayName()));
    }

    @Test void lostRefreshCallbackTimesOutAndRetryDoesNotHammerStorage() {
        var player = server.addPlayer();
        long now = System.currentTimeMillis() + 6_000;
        renderer.render(player, shop(now - 10_000, "old"), 1);
        var holder = (MenuHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertTrue(holder.requestRefresh(now));
        holder.refreshFailedIfTimedOut(now + 4_999);
        assertFalse(holder.requestRefresh(now + 4_999));
        holder.refreshFailedIfTimedOut(now + 5_000);
        assertFalse(holder.requestRefresh(now + 9_999));
        assertTrue(holder.requestRefresh(now + 10_000));
    }
}
