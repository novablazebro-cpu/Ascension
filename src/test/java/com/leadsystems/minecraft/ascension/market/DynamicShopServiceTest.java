package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.gui.Menu;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class DynamicShopServiceTest {
    YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("shop.yml")), StandardCharsets.UTF_8));
    }
    @Test void bundledShopHas150PlainGoodsWithTwoThroughFourHourRestocks() {
        var products = DynamicShopService.load(bundled());
        assertEquals(150, products.size());
        assertEquals(150, products.stream().map(DynamicShopRepository.Product::id).distinct().count());
        for (var product : products) {
            assertEquals(7200, product.minRestockSeconds());
            assertEquals(14400, product.maxRestockSeconds());
            assertFalse(MarketInventory.CURRENCIES.contains(product.material()));
            assertTrue(Material.valueOf(product.material()).isItem());
            assertTrue(product.quantity() <= Material.valueOf(product.material()).getMaxStackSize());
        }
        var obsidian = products.stream().filter(p -> p.id().equals("obsidian")).findFirst().orElseThrow();
        assertEquals(16, obsidian.quantity());
        assertEquals(4, obsidian.basePrice());
        assertEquals(2, obsidian.minPrice());
        assertEquals(8, obsidian.maxPrice());
        var elytra = products.stream().filter(p -> p.id().equals("elytra")).findFirst().orElseThrow();
        assertEquals(64, elytra.basePrice());
        assertEquals(64, elytra.minPrice());
    }
    @Test void sixPagesReachEveryProductAndTenCategoriesReachTheSameCatalog() {
        var yaml = bundled();
        var categories = DynamicShopService.categoryLabels(yaml);
        var assignments = DynamicShopService.productCategories(yaml);
        assertEquals(10, categories.size());
        var categoryMenu = DynamicShopService.categoryMenu(categories, assignments);
        assertEquals(11, categoryMenu.buttons().stream().filter(b -> !b.nav()).count());
        assertEquals(1, categoryMenu.buttons().stream().filter(Menu.Button::nav).count());
        var offers = DynamicShopService.load(yaml).stream()
                .map(p -> new DynamicShopRepository.Offer(p, 16, p.basePrice(), 7_200_000, false)).toList();
        var reached = new java.util.HashSet<String>();
        for (int page = 0; page < 6; page++) {
            var screen = DynamicShopService.browse(offers, 0, "all", page, categories, assignments);
            var goods = screen.buttons().stream().filter(b -> b.arg().startsWith("market_stock:")).toList();
            assertEquals(25, goods.size());
            assertTrue(screen.buttons().size() <= 30);
            goods.forEach(b -> assertTrue(reached.add(b.arg().split(":")[1])));
            String currentId = DynamicShopService.browseId("all", page);
            assertTrue(screen.buttons().stream().anyMatch(b -> b.arg().equals(currentId)));
        }
        assertEquals(150, reached.size());
        for (String category : categories.keySet()) {
            var screen = DynamicShopService.browse(offers, 0, category, 0, categories, assignments);
            var goods = screen.buttons().stream().filter(b -> b.arg().startsWith("market_stock:")).toList();
            assertEquals(15, goods.size());
            goods.forEach(b -> assertEquals(category, assignments.get(b.arg().split(":")[1])));
            assertFalse(screen.buttons().stream().anyMatch(b -> plain(b.label()).equals("Next Page")));
        }
        assertEquals("market_shop:armor:0", DynamicShopService.confirm(offers.get(0), "armor", 0).buttons().get(1).arg());
        assertEquals("market_shop:all:5", DynamicShopService.confirm(offers.get(0), "all", 5).buttons().get(1).arg());
    }
    @Test void upgradeAddsMissingProductsButPreservesCustomGoodsPricesAndChosenTimers() {
        var defaults = bundled();
        var old = new YamlConfiguration();
        old.set("restock.min-seconds", 300); old.set("restock.max-seconds", 900);
        for (String id : List.of("obsidian", "iron_block", "oak_log", "quartz_block", "glowstone")) {
            defaults.getConfigurationSection("products." + id).getValues(false).forEach((key, value) -> old.set("products." + id + "." + key, value));
        }
        old.set("products.obsidian.base-price", 7);
        assertTrue(DynamicShopService.upgrade(old, defaults));
        assertEquals(150, DynamicShopService.load(old).size());
        assertEquals(7, old.getInt("products.obsidian.base-price"));
        assertEquals(7200, old.getInt("restock.min-seconds"));
        assertEquals(14400, old.getInt("restock.max-seconds"));
        assertFalse(DynamicShopService.upgrade(old, defaults));
        old.set("catalog-version", 0);
        old.set("restock.min-seconds", 10000); old.set("restock.max-seconds", 12000);
        old.set("products.my_stone.material", "STONE");
        old.set("products.my_stone.base-price", 2);
        assertTrue(DynamicShopService.upgrade(old, defaults));
        assertEquals(151, DynamicShopService.load(old).size());
        assertEquals(10000, old.getInt("restock.min-seconds"));
        assertEquals("STONE", old.getString("products.my_stone.material"));
    }
    @Test void configurationRejectsCurrencyGoodsOversizedBundlesAndInvertedPriceBounds() {
        var currency = bundled(); currency.set("products.obsidian.material", "EMERALD_BLOCK");
        assertThrows(IllegalArgumentException.class, () -> DynamicShopService.load(currency));
        var large = bundled(); large.set("products.obsidian.quantity", 65);
        assertThrows(IllegalArgumentException.class, () -> DynamicShopService.load(large));
        var price = bundled(); price.set("products.obsidian.min-price", 9);
        assertThrows(IllegalArgumentException.class, () -> DynamicShopService.load(price));
    }
    @Test void bothRenderersReceiveTheSameBundleQuoteAndTwoButtonConfirmation() {
        var product = DynamicShopService.load(bundled()).getFirst();
        var offer = new DynamicShopRepository.Offer(product, 3, product.basePrice(), 301_000, false);
        var screen = DynamicShopService.confirm(offer);
        assertTrue(screen.confirm());
        assertEquals(2, screen.buttons().size());
        assertEquals(Menu.Action.MARKET_SHOP_BUY, screen.buttons().getFirst().action());
        assertEquals(product.id() + "|" + product.basePrice(), screen.buttons().getFirst().arg());
        var list = DynamicShopService.browse(List.of(offer), 1_000);
        assertFalse(list.confirm());
        assertEquals(Material.valueOf(product.material()), list.buttons().getFirst().icon());
        assertEquals("market_stock:" + product.id(), list.buttons().getFirst().arg());
    }
    @Test void shopSnapshotHasProminentAndPerProductTimersAndAnExplicitRefreshAction() {
        var products = DynamicShopService.load(bundled());
        var offers = List.of(new DynamicShopRepository.Offer(products.get(0), 3, 4, 301_000, false),
                new DynamicShopRepository.Offer(products.get(1), 0, 2, 1_000, true));
        var screen = DynamicShopService.browse(offers, 1_000);
        assertEquals("Next Restock: Purchase completing…", plain(screen.body().getFirst()));
        assertEquals("Restock: 05:00", plain(screen.buttons().get(0).lore().get(2)));
        assertEquals("Restock: Purchase completing…", plain(screen.buttons().get(1).lore().get(2)));
        assertTrue(screen.buttons().stream().anyMatch(button -> plain(button.label()).equals("Refresh Stock & Timers")
                && button.action() == Menu.Action.OPEN && button.arg().equals("market_shop")));
        assertFalse(DynamicShopService.confirm(offers.get(0)).countdowns().containsKey(0));
    }
    @Test void overdueStockShowsRestockingInsteadOfZeroAndMinuteFormatIsRoundedUp() {
        var product = DynamicShopService.load(bundled()).getFirst();
        var offer = new DynamicShopRepository.Offer(product, 0, 4, 1_000, false);
        var screen = DynamicShopService.browse(List.of(offer), 1_000);
        assertEquals("Next Restock: Restocking…", plain(screen.body().getFirst()));
        assertEquals("Restock: Restocking…", plain(screen.buttons().getFirst().lore().get(2)));
        assertEquals("00:01", Menu.Countdown.format(1));
        assertEquals("01:00", Menu.Countdown.format(59_001));
        assertEquals("15:00", Menu.Countdown.format(900_000));
    }
    private static String plain(net.kyori.adventure.text.Component text) {
        return com.leadsystems.minecraft.ascension.text.Messages.plain(text);
    }
}
