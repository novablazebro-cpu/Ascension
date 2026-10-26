package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.AscensionPlugin;
import com.leadsystems.minecraft.ascension.gui.Menu;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real async SQLite/escrow with mock player inventories. Codec substitutes MockBukkit's missing NBT. */
class MarketFlowTest {
    ServerMock server;
    AscensionPlugin plugin;
    MarketService market;
    MarketRepository repo = new MarketRepository();
    MemoryCodec codec = new MemoryCodec();
    @BeforeEach void setup() {
        server = MockBukkit.mock(); plugin = MockBukkit.load(AscensionPlugin.class);
        market = new MarketService(plugin.sessions(), plugin.storage(), plugin.health(), new MainThread(plugin),
                plugin.getLogger(), repo, codec);
    }
    @AfterEach void close() { MockBukkit.unmock(); }
    void settle() {
        for (int i = 0; i < 8; i++) { plugin.storage().submit("test-barrier", c -> null).join(); server.getScheduler().performTicks(2); }
    }
    PlayerMock join(String name) {
        PlayerMock player = server.addPlayer(name); settle();
        PlayerMock wrapped = spy(player); doNothing().when(wrapped).saveData(); return wrapped;
    }
    List<MarketRepository.Listing> listings(UUID seller) {
        return plugin.storage().submit("test-listings", c -> repo.listings(c, seller, 0, 20)).join();
    }
    List<MarketRepository.Delivery> mail(UUID owner) {
        return plugin.storage().submit("test-mail", c -> repo.deliveries(c, owner, 0, 20)).join();
    }
    String sell(PlayerMock player, int amount, int price) {
        ItemStack arrows = new ItemStack(Material.ARROW, amount);
        arrows.editMeta(meta -> meta.displayName(Component.text("Tournament arrows")));
        player.getInventory().setItem(1, arrows);
        market.open(player, "market_currency:1", s -> {});
        market.handle(player, Menu.Action.MARKET_SELL, "1:emerald:" + price, () -> {});
        assertTrue(market.busy(player.getUniqueId()));
        settle(); assertNull(player.getInventory().getItem(1));
        return listings(player.getUniqueId()).getFirst().id();
    }
    @Test void itemAndPaymentFlowPreservesMetadataAndRejectsDoublePurchase() {
        PlayerMock seller = join("Seller"), buyer = join("Buyer");
        String id = sell(seller, 16, 4);
        buyer.getInventory().setItem(1, new ItemStack(Material.EMERALD_BLOCK, 8));
        market.open(buyer, "market_listing:" + id, s -> {}); settle();
        AtomicInteger completed = new AtomicInteger();
        market.handle(buyer, Menu.Action.MARKET_BUY, id, completed::incrementAndGet);
        market.handle(buyer, Menu.Action.MARKET_BUY, id, completed::incrementAndGet);
        settle(); assertEquals(1, completed.get()); assertEquals(4, buyer.getInventory().getItem(1).getAmount());
        assertTrue(listings(seller.getUniqueId()).isEmpty());
        var purchased = mail(buyer.getUniqueId()).getFirst();
        assertEquals(16, codec.item(purchased.item()).getAmount());
        market.handle(buyer, Menu.Action.MARKET_CLAIM, purchased.id(), () -> {}); settle();
        assertTrue(mail(buyer.getUniqueId()).isEmpty());
        assertTrue(java.util.Arrays.stream(buyer.getInventory().getStorageContents()).anyMatch(item -> item != null
                && item.getType() == Material.ARROW && item.getItemMeta().displayName().equals(Component.text("Tournament arrows"))));
        var payment = mail(seller.getUniqueId()).getFirst();
        market.handle(seller, Menu.Action.MARKET_CLAIM, payment.id(), () -> {}); settle();
        assertEquals(4, count(seller, Material.EMERALD_BLOCK)); assertTrue(mail(seller.getUniqueId()).isEmpty());
        verify(seller, atLeastOnce()).saveData(); verify(buyer, atLeastOnce()).saveData();
    }
    @Test void changingSelectedItemBeforeConfirmationCannotSellReplacement() {
        PlayerMock seller = join("StaleSeller");
        seller.getInventory().setItem(1, new ItemStack(Material.DIAMOND_SWORD));
        market.open(seller, "market_currency:1", s -> {});
        seller.getInventory().setItem(1, new ItemStack(Material.NETHERITE_SWORD));
        market.handle(seller, Menu.Action.MARKET_SELL, "1:emerald:1", () -> {}); settle();
        assertEquals(Material.NETHERITE_SWORD, seller.getInventory().getItem(1).getType());
        assertTrue(listings(seller.getUniqueId()).isEmpty());
    }
    @Test void fullInventoryCannotLoseDeliveryAndLargePaymentCanBeCollectedInParts() {
        PlayerMock player = join("Collector");
        ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
        var exchange = journal(player, "EXCHANGE", "test", before, before, new ItemStack(Material.EMERALD_BLOCK, 150));
        plugin.storage().submit("test-mail-create", c -> { assertTrue(repo.prepare(c, exchange)); return repo.commit(c, exchange.id()); }).join();
        String id = mail(player.getUniqueId()).getFirst().id();
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        market.handle(player, Menu.Action.MARKET_CLAIM, id, () -> {}); settle();
        assertEquals(150, codec.item(mail(player.getUniqueId()).getFirst().item()).getAmount());
        player.getInventory().setItem(1, null);
        market.handle(player, Menu.Action.MARKET_CLAIM, id, () -> {}); settle();
        assertEquals(64, count(player, Material.EMERALD_BLOCK));
        assertEquals(86, codec.item(mail(player.getUniqueId()).getFirst().item()).getAmount());
        player.getInventory().setItem(1, null); player.getInventory().setItem(2, null);
        market.handle(player, Menu.Action.MARKET_CLAIM, id, () -> {}); settle();
        assertTrue(mail(player.getUniqueId()).isEmpty()); assertEquals(86, count(player, Material.EMERALD_BLOCK));
    }
    @Test void exactBeforeSnapshotAbortsWhileExactAfterSnapshotCompletesRecovery() {
        PlayerMock player = join("Recover");
        player.getInventory().setItem(1, new ItemStack(Material.EMERALD_BLOCK, 4));
        ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
        ItemStack[] next = MarketInventory.debit(before, "EMERALD_BLOCK", 4);
        var untouched = journal(player, "EXCHANGE", "emerald_to_diamond", before, next, new ItemStack(Material.DIAMOND_BLOCK));
        assertTrue(plugin.storage().submit("test-prepare", c -> repo.prepare(c, untouched)).join());
        market.recover(player); settle(); assertEquals(4, count(player, Material.EMERALD_BLOCK));
        assertTrue(mail(player.getUniqueId()).isEmpty());
        var applied = journal(player, "EXCHANGE", "emerald_to_diamond", before, next, new ItemStack(Material.DIAMOND_BLOCK));
        assertTrue(plugin.storage().submit("test-prepare", c -> repo.prepare(c, applied)).join());
        MarketInventory.apply(player.getInventory(), next);
        market.recover(player); settle(); market.recover(player); settle();
        assertEquals(0, count(player, Material.EMERALD_BLOCK)); assertEquals(1, mail(player.getUniqueId()).size());
        assertFalse(market.blocked(player.getUniqueId()));
    }
    @Test void ambiguousRecoveryQuarantinesInsteadOfGuessingOrMintingItems() {
        PlayerMock player = join("Ambiguous");
        ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
        ItemStack[] next = MarketInventory.copy(before); next[1] = new ItemStack(Material.DIAMOND);
        var op = journal(player, "EXCHANGE", "test", before, next, new ItemStack(Material.DIAMOND_BLOCK));
        assertTrue(plugin.storage().submit("test-prepare", c -> repo.prepare(c, op)).join());
        player.getInventory().setItem(1, new ItemStack(Material.GOLD_INGOT));
        market.recover(player); settle();
        assertTrue(market.blocked(player.getUniqueId())); assertFalse(market.busy(player.getUniqueId()));
        assertEquals(1, plugin.storage().submit("test-pending", c -> repo.pending(c, player.getUniqueId())).join().size());
        assertTrue(mail(player.getUniqueId()).isEmpty());
    }
    @Test void exchangesConsumeExactlySpecifiedCurrencyAndPreserveOutputInMail() {
        PlayerMock player = join("Exchange");
        player.getInventory().setItem(1, new ItemStack(Material.EMERALD_BLOCK, 5));
        confirmExchange(player, "emerald_to_diamond"); settle();
        assertEquals(1, count(player, Material.EMERALD_BLOCK));
        var first = mail(player.getUniqueId()).getFirst();
        assertEquals(Material.DIAMOND_BLOCK, codec.item(first.item()).getType());
        market.handle(player, Menu.Action.MARKET_CLAIM, first.id(), () -> {}); settle();
        // Claiming may already use slot 2; add three instead of replacing the claimed block.
        player.getInventory().addItem(new ItemStack(Material.DIAMOND_BLOCK, 3));
        assertEquals(4, count(player, Material.DIAMOND_BLOCK));
        confirmExchange(player, "diamond_to_netherite"); settle();
        assertEquals(0, count(player, Material.DIAMOND_BLOCK));
        assertEquals(Material.NETHERITE_INGOT, codec.item(mail(player.getUniqueId()).getFirst().item()).getType());
    }
    @Test void inventorySaveFailureNeverBlindlyRefundsAndCanRecoverAppliedSnapshot() {
        PlayerMock player = join("SaveFailure");
        player.getInventory().setItem(1, new ItemStack(Material.EMERALD_BLOCK, 4));
        doThrow(new IllegalStateException("simulated player-data write failure")).when(player).saveData();
        confirmExchange(player, "emerald_to_diamond"); settle();
        assertTrue(market.blocked(player.getUniqueId())); assertEquals(0, count(player, Material.EMERALD_BLOCK));
        assertTrue(mail(player.getUniqueId()).isEmpty());
        doNothing().when(player).saveData();
        market.recover(player); settle();
        assertFalse(market.blocked(player.getUniqueId())); assertEquals(1, mail(player.getUniqueId()).size());
        assertEquals(0, count(player, Material.EMERALD_BLOCK));
    }
    @Test void allSixConversionsUsePreviewQuantitiesAndCannotReplayAfterCompletion() {
        PlayerMock player = join("SixRoutes");
        for (CurrencyExchange route : CurrencyExchange.ALL) {
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, null);
            player.getInventory().setItem(1, new ItemStack(route.input(), route.inputAmount() + 1));
            AtomicInteger completed = new AtomicInteger();
            market.open(player, "market_exchange_confirm:" + route.id(), preview -> {
                assertTrue(preview.confirm()); assertEquals(2, preview.buttons().size());
                assertTrue(preview.body().stream().anyMatch(line -> com.leadsystems.minecraft.ascension.text.Messages.plain(line)
                        .equals("You receive: " + route.receive())));
            });
            market.handle(player, Menu.Action.MARKET_EXCHANGE, route.id(), completed::incrementAndGet);
            market.handle(player, Menu.Action.MARKET_EXCHANGE, route.id(), completed::incrementAndGet);
            settle();
            market.handle(player, Menu.Action.MARKET_EXCHANGE, route.id(), completed::incrementAndGet);
            settle();
            assertEquals(1, completed.get()); assertEquals(1, count(player, route.input()));
            var delivery = mail(player.getUniqueId()).getFirst();
            assertEquals(1, mail(player.getUniqueId()).size());
            ItemStack output = codec.item(delivery.item());
            assertEquals(route.output(), output.getType()); assertEquals(route.outputAmount(), output.getAmount());
            assertEquals("Exchange: " + route.receive(), delivery.description());
            market.handle(player, Menu.Action.MARKET_CLAIM, delivery.id(), () -> {}); settle();
            assertEquals(route.outputAmount(), count(player, route.output()));
            assertTrue(mail(player.getUniqueId()).isEmpty());
        }
    }
    @Test void currencyRoundTripsPreserveValueThroughAllThreeDenominations() {
        PlayerMock player = join("RoundTrip");
        player.getInventory().setItem(1, new ItemStack(Material.EMERALD_BLOCK, 16));
        for (String route : List.of("emerald_to_netherite", "netherite_to_diamond", "diamond_to_emerald",
                "diamond_to_emerald", "diamond_to_emerald", "diamond_to_emerald")) {
            confirmExchange(player, route); settle();
            var delivery = mail(player.getUniqueId()).getFirst();
            market.handle(player, Menu.Action.MARKET_CLAIM, delivery.id(), () -> {}); settle();
        }
        assertEquals(16, count(player, Material.EMERALD_BLOCK));
        assertEquals(0, count(player, Material.DIAMOND_BLOCK)); assertEquals(0, count(player, Material.NETHERITE_INGOT));
    }
    @Test void everyRouteRejectsInsufficientCurrencyAndLeavingPreviewInvalidatesItsConfirmation() {
        PlayerMock player = join("ShortCurrency");
        for (CurrencyExchange route : CurrencyExchange.ALL) {
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, null);
            if (route.inputAmount() > 1)
                player.getInventory().setItem(1, new ItemStack(route.input(), route.inputAmount() - 1));
            confirmExchange(player, route.id()); settle();
            assertEquals(route.inputAmount() - 1, count(player, route.input()));
            assertTrue(mail(player.getUniqueId()).isEmpty());
            player.getInventory().setItem(1, new ItemStack(route.input(), route.inputAmount()));
            market.open(player, "market_exchange_confirm:" + route.id(), preview -> {});
            market.open(player, "market_exchange", list -> {});
            market.handle(player, Menu.Action.MARKET_EXCHANGE, route.id(), () -> fail("preview was closed")); settle();
            assertEquals(route.inputAmount(), count(player, route.input()));
            assertTrue(mail(player.getUniqueId()).isEmpty());
        }
    }
    @Test void exchangeRevalidatesOrdinaryCurrencyAndKeepsFullInventoryOutputInCollection() {
        PlayerMock player = join("ExchangeValidation");
        market.open(player, "market_exchange_confirm:netherite_to_emerald", preview -> {});
        ItemStack named = new ItemStack(Material.NETHERITE_INGOT);
        named.editMeta(meta -> meta.displayName(Component.text("A relic")));
        player.getInventory().setItem(1, named);
        market.handle(player, Menu.Action.MARKET_EXCHANGE, "netherite_to_emerald", () -> {}); settle();
        assertEquals(1, count(player, Material.NETHERITE_INGOT)); assertTrue(mail(player.getUniqueId()).isEmpty());
        player.getInventory().setItem(1, new ItemStack(Material.NETHERITE_INGOT));
        confirmExchange(player, "netherite_to_emerald"); settle();
        var delivery = mail(player.getUniqueId()).getFirst();
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        market.handle(player, Menu.Action.MARKET_CLAIM, delivery.id(), () -> {}); settle();
        assertEquals(16, codec.item(mail(player.getUniqueId()).getFirst().item()).getAmount());
        assertEquals(0, count(player, Material.EMERALD_BLOCK));
    }
    @Test void newSessionDuringOldRecoveryAutomaticallyRetriesInsteadOfStrandingTrade() {
        PlayerMock player = join("Reconnect");
        var sessionManager = mock(com.leadsystems.minecraft.ascension.session.SessionManager.class);
        var oldSession = plugin.sessions().get(player);
        var newSession = mock(com.leadsystems.minecraft.ascension.session.PlayerSession.class);
        when(newSession.usable()).thenReturn(true);
        var current = new java.util.concurrent.atomic.AtomicReference<>(oldSession);
        when(sessionManager.get(any(org.bukkit.entity.Player.class))).thenAnswer(invocation -> current.get());
        MarketService recovering = new MarketService(sessionManager, plugin.storage(), plugin.health(),
                new MainThread(plugin), plugin.getLogger(), repo, codec);
        player.getInventory().setItem(1, new ItemStack(Material.EMERALD_BLOCK, 4));
        ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
        ItemStack[] next = MarketInventory.debit(before, "EMERALD_BLOCK", 4);
        var op = journal(player, "EXCHANGE", "emerald_to_diamond", before, next, new ItemStack(Material.DIAMOND_BLOCK));
        assertTrue(plugin.storage().submit("test-prepare", c -> repo.prepare(c, op)).join());
        MarketInventory.apply(player.getInventory(), next);
        // Bukkit's registered mock is the unwrapped player; route the reconnect lookup
        // to the same player-data writer substitute used by all inventory-flow tests.
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            recovering.recover(player);
            assertTrue(recovering.busy(player.getUniqueId()));
            current.set(newSession);
            recovering.onLoaded(player, newSession, false);
            settle();
        }
        assertTrue(plugin.storage().submit("test-pending", c -> repo.pending(c, player.getUniqueId())).join().isEmpty());
        assertEquals(1, mail(player.getUniqueId()).size()); assertFalse(recovering.busy(player.getUniqueId()));
    }
    MarketRepository.Operation journal(PlayerMock player, String kind, String ref, ItemStack[] before, ItemStack[] after, ItemStack output) {
        return new MarketRepository.Operation(UUID.randomUUID().toString(), player.getUniqueId(), kind, ref,
                codec.inventory(before), codec.inventory(after), codec.item(output), "Output", "EMERALD_BLOCK", 4, player.getName(), 10);
    }
    void confirmExchange(PlayerMock player, String route) {
        market.open(player, "market_exchange_confirm:" + route, screen -> assertTrue(screen.confirm()));
        market.handle(player, Menu.Action.MARKET_EXCHANGE, route, () -> {});
    }
    static int count(PlayerMock player, Material material) {
        return java.util.Arrays.stream(player.getInventory().getStorageContents()).filter(i -> i != null && i.getType() == material)
                .mapToInt(ItemStack::getAmount).sum();
    }
    static final class MemoryCodec implements MarketInventory.Codec {
        int next;
        Map<String, ItemStack> items = new HashMap<>();
        Map<String, ItemStack[]> inventories = new HashMap<>();
        String key(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        public byte[] item(ItemStack item) { String key = "i" + (++next); items.put(key, item.clone()); return key.getBytes(StandardCharsets.UTF_8); }
        public ItemStack item(byte[] bytes) { return items.get(key(bytes)).clone(); }
        public byte[] inventory(ItemStack[] array) { String key = "s" + (++next); inventories.put(key, MarketInventory.copy(array)); return key.getBytes(StandardCharsets.UTF_8); }
        public ItemStack[] inventory(byte[] bytes) { return MarketInventory.copy(inventories.get(key(bytes))); }
    }
}
