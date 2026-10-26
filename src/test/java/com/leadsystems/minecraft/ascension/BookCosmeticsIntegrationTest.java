package com.leadsystems.minecraft.ascension;

import com.leadsystems.minecraft.ascension.market.MarketService;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.SqliteDialect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookCosmeticsIntegrationTest {
    ServerMock server;
    AscensionPlugin plugin;

    @BeforeEach void start() { server = MockBukkit.mock(); plugin = MockBukkit.load(AscensionPlugin.class); }
    @AfterEach void stop() { MockBukkit.unmock(); }
    void settle() {
        for (int i = 0; i < 8; i++) {
            plugin.storage().submit("test-cosmetics-sync", connection -> null).join();
            server.getScheduler().performTicks(2);
        }
    }
    PlayerMock join() { PlayerMock player = server.addPlayer(); settle(); return player; }
    int bookSlot(PlayerMock player) {
        for (int i = 0; i < 36; i++) if (plugin.books().items().isChronicle(player.getInventory().getItem(i))) return i;
        throw new AssertionError("Chronicle missing");
    }
    @SuppressWarnings("deprecation")
    ItemStack oldAppearance(PlayerMock player) {
        int slot = bookSlot(player);
        ItemStack old = player.getInventory().getItem(slot).clone();
        old.editMeta(meta -> {
            meta.setEnchantmentGlintOverride(false);
            meta.displayName(Component.text("Old Chronicle", NamedTextColor.RED));
            meta.lore(List.of(Component.text("Old instructions")));
            if (meta instanceof BookMeta bookMeta) bookMeta.setPages("§6Old instructions");
        });
        player.getInventory().setItem(slot, old);
        return old;
    }

    @SuppressWarnings("deprecation")
    @Test void oldBookGetsGlintAndInstructionsWithoutChangingIdentityOrDuplicating() {
        PlayerMock player = join();
        ItemStack old = oldAppearance(player);
        var identity = plugin.books().items().read(old);
        PlayerSession session = plugin.sessions().get(player);
        plugin.books().reconcile(player, session, true);
        ItemStack updated = player.getInventory().getItem(bookSlot(player));
        assertEquals(identity, plugin.books().items().read(updated));
        assertEquals(identity.nonce(), session.bookNonce());
        assertTrue(updated.getItemMeta().getEnchantmentGlintOverride());
        assertEquals(NamedTextColor.WHITE, updated.getItemMeta().displayName().color());
        assertEquals(List.of("Old instructions"), ((BookMeta) updated.getItemMeta()).getPages());
        long count = java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(plugin.books().items()::isChronicle).count();
        assertEquals(1, count);
    }

    @Test void unresolvedMarketInventoryIsUntouchedUntilRecoveryReleasesCosmetics() {
        PlayerMock player = join();
        ItemStack old = oldAppearance(player);
        var identity = plugin.books().items().read(old);
        PlayerSession session = plugin.sessions().get(player);
        MarketService gate = mock(MarketService.class);
        when(gate.inventoryLocked(player.getUniqueId())).thenReturn(true);
        AtomicReference<Runnable> deferred = new AtomicReference<>();
        doAnswer(call -> { deferred.set(call.getArgument(2)); return null; })
                .when(gate).afterInventoryReady(eq(player.getUniqueId()), eq("book"), any(Runnable.class));
        plugin.books().setMarket(gate);
        plugin.books().onLoaded(player, session, false);
        assertNull(plugin.books().reconcile(player, session, true));
        assertFalse(player.getInventory().getItem(bookSlot(player)).getItemMeta().getEnchantmentGlintOverride());
        assertNotNull(deferred.get());
        when(gate.inventoryLocked(player.getUniqueId())).thenReturn(false);
        deferred.get().run();
        ItemStack styled = player.getInventory().getItem(bookSlot(player));
        assertTrue(styled.getItemMeta().getEnchantmentGlintOverride());
        assertEquals(identity, plugin.books().items().read(styled));
    }

    @Test void gameplayLevelUpRefreshesTitleTagAndRelicAndPersistsWithoutLosingCooldown() {
        PlayerMock player = join();
        PlayerRepository repo = new PlayerRepository(new SqliteDialect());
        plugin.sessions().mutate(player.getUniqueId(), "test-grant-lightning", connection -> {
            assertTrue(repo.adminGiveTitle(connection, "stormbringer", player.getUniqueId(), System.currentTimeMillis()));
            return repo.equipTitle(connection, player.getUniqueId(), "stormbringer");
        }, (value, error) -> assertNull(error));
        settle();
        PlayerSession session = plugin.sessions().get(player);
        session.cooldowns().start("title.stormbringer.heavens_judgment", System.currentTimeMillis(), 35_000);
        long checkedAt = System.currentTimeMillis();
        long remaining = session.cooldowns().remainingMillis("title.stormbringer.heavens_judgment", checkedAt);
        plugin.progress().record(player, new Fact.ItemSmelted("IRON_INGOT", 1000));
        assertEquals(1000, session.stat("title.xp.stormbringer"));
        assertTrue(com.leadsystems.minecraft.ascension.text.Messages.plain(player.playerListName())
                .contains("Thunder God Lv.2"));
        ItemStack relic = java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(plugin.abilityItems()::isActivator).findFirst().orElseThrow();
        assertTrue(relic.getItemMeta().lore().stream().map(com.leadsystems.minecraft.ascension.text.Messages::plain)
                .anyMatch(line -> line.contains("Level 2")));
        assertEquals(remaining, session.cooldowns().remainingMillis("title.stormbringer.heavens_judgment", checkedAt));
        plugin.sessions().flushNow(session);
        settle();
        long persisted = plugin.storage().submit("test-read-title-xp", connection ->
                repo.loadStats(connection, player.getUniqueId()).get("title.xp.stormbringer")).join();
        assertEquals(1000, persisted);
    }
}
