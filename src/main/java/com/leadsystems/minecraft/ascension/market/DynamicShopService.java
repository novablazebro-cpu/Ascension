package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.gui.Menu;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Main-thread screens and inventory planning around persistent SQL-only shop state. */
public final class DynamicShopService {
    private static final long QUOTE_TTL_MS = 60_000;
    private final JavaPlugin plugin;
    private final StorageExecutor storage;
    private final MainThread main;
    private final MarketService market;
    private final DynamicShopRepository repo = new DynamicShopRepository();
    private final Map<UUID, Quote> quotes = new HashMap<>();
    private final Map<UUID, Object> screens = new HashMap<>();
    private boolean ready;
    private boolean restocking;
    public static final int PAGE_SIZE = 25;
    private final Map<String, String> categories;
    private final Map<String, String> productCategories;

    private record Quote(DynamicShopRepository.Offer offer, long at) {}

    public DynamicShopService(JavaPlugin plugin, StorageExecutor storage, MainThread main, MarketService market) {
        this.plugin = plugin; this.storage = storage; this.main = main; this.market = market;
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.isFile()) plugin.saveResource("shop.yml", false);
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); }
        catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("Cannot read shop.yml; existing configuration was left untouched", e);
        }
        try (var input = plugin.getResource("shop.yml")) {
            if (input == null) throw new IllegalStateException("Missing bundled shop.yml");
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (upgrade(yaml, defaults)) {
                Files.copy(file.toPath(), new File(plugin.getDataFolder(), "shop.yml.before-catalog-v2-" + System.currentTimeMillis()).toPath());
                var staged = Files.createTempFile(plugin.getDataFolder().toPath(), "shop-catalog-", ".yml");
                try {
                    yaml.save(staged.toFile());
                    try {
                        Files.move(staged, file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(staged, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(staged); }
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot safely upgrade shop.yml", e); }
        categories = categoryLabels(yaml);
        productCategories = productCategories(yaml);
        List<DynamicShopRepository.Product> products = load(yaml);
        long now = System.currentTimeMillis();
        main.onComplete(storage.submit("shop.seed", c -> {
            repo.seed(c, products, now, DynamicShopService::randomSeconds); return true;
        }), (value, error) -> {
            ready = error == null;
            if (error != null) plugin.getLogger().log(Level.SEVERE, "Dynamic shop initialization failed", error);
        });
    }

    public void open(Player player, String screenId, Consumer<Menu.Screen> show) {
        if (!ready) { MarketService.tell(player, "Shop stock is loading. Try again shortly."); return; }
        UUID owner = player.getUniqueId();
        Object token = new Object();
        screens.put(owner, token);
        quotes.remove(owner);
        if (screenId.equals("market_shop_categories")) {
            screens.remove(owner);
            show.accept(categoryMenu(categories, productCategories));
        } else if (screenId.equals("market_shop") || screenId.startsWith("market_shop:")) {
            String[] parts = screenId.split(":", -1);
            if (parts.length != 1 && parts.length != 3) throw new IllegalArgumentException("Invalid shop page");
            String category = parts.length == 3 ? parts[1] : "all";
            int page = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            if ((!category.equals("all") && !categories.containsKey(category)) || page < 0 || page > 1000)
                throw new IllegalArgumentException("Invalid shop page");
            main.onComplete(storage.submit("shop.browse", repo::offers), (offers, error) -> {
                if (screens.get(owner) != token) return;
                screens.remove(owner);
                if (!player.isOnline()) return;
                if (error != null) { MarketService.tell(player, "Shop storage is unavailable."); return; }
                show.accept(browse(offers, System.currentTimeMillis(), category, page, categories, productCategories));
            });
        } else if (screenId.startsWith("market_stock:")) {
            String[] parts = screenId.split(":", -1);
            if ((parts.length != 2 && parts.length != 4) || !parts[1].matches("[a-z0-9_]{1,48}"))
                throw new IllegalArgumentException("Invalid shop product");
            String id = parts[1];
            String category = parts.length == 4 ? parts[2] : "all";
            int page = parts.length == 4 ? Integer.parseInt(parts[3]) : 0;
            if ((!category.equals("all") && !categories.containsKey(category)) || page < 0 || page > 1000)
                throw new IllegalArgumentException("Invalid shop return page");
            main.onComplete(storage.submit("shop.quote", c -> repo.find(c, id)), (offer, error) -> {
                if (screens.get(owner) != token) return;
                screens.remove(owner);
                if (!player.isOnline()) return;
                if (error != null) { MarketService.tell(player, "Shop storage is unavailable."); return; }
                if (offer == null || offer.stock() < 1 || offer.reserved() || offer.nextRestock() <= System.currentTimeMillis()) {
                    MarketService.tell(player, "This bundle is unavailable. Refresh the shop."); return;
                }
                quotes.put(owner, new Quote(offer, System.currentTimeMillis()));
                show.accept(confirm(offer, category, page));
            });
        } else screens.remove(owner);
    }

    public void buy(Player player, String arg, Runnable after) {
        Quote quote = quotes.remove(player.getUniqueId());
        if (!ready || quote == null || arg == null || !arg.equals(reference(quote.offer()))
                || System.currentTimeMillis() - quote.at() > QUOTE_TTL_MS) {
            MarketService.tell(player, "Open this shop bundle again to confirm its current price."); return;
        }
        UUID owner = player.getUniqueId();
        Object token = new Object();
        screens.put(owner, token);
        String id = quote.offer().product().id();
        main.onComplete(storage.submit("shop.buy-quote", c -> repo.find(c, id)), (live, error) -> {
            if (screens.get(owner) != token) return;
            screens.remove(owner);
            if (!player.isOnline()) return;
            if (error != null) { MarketService.tell(player, "Shop storage is unavailable."); return; }
            if (live == null || !live.equals(quote.offer()) || live.stock() < 1 || live.reserved()
                    || live.nextRestock() <= System.currentTimeMillis()
                    || System.currentTimeMillis() - quote.at() > QUOTE_TTL_MS) {
                MarketService.tell(player, "Shop stock or price changed. Review the bundle again."); return;
            }
            ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
            ItemStack[] next = MarketInventory.debit(before, "EMERALD_BLOCK", live.currentPrice());
            if (next == null) { MarketService.tell(player, "You do not have enough ordinary emerald blocks."); return; }
            DynamicShopRepository.Product p = live.product();
            ItemStack output = new ItemStack(Material.valueOf(p.material()), p.quantity());
            market.transact(player, "SHOP", reference(live), before, next, output,
                    p.quantity() + " x " + p.label(), "EMERALD_BLOCK", live.currentPrice(), after);
        });
    }

    /** Invalidates asynchronous shop screens and outstanding quotes when a viewer leaves. */
    public void cancel(UUID owner) { screens.remove(owner); quotes.remove(owner); }

    /** Scheduler calls on the main thread; at most one bounded replenishment task is in flight. */
    public void tick() {
        if (!ready || restocking || storage.isShutdown()) return;
        restocking = true;
        long now = System.currentTimeMillis();
        main.onComplete(storage.submit("shop.restock", c -> repo.restock(c, now, DynamicShopService::randomSeconds)),
                (count, error) -> {
                    restocking = false;
                    if (error != null) plugin.getLogger().log(Level.WARNING, "Dynamic shop restock failed", error);
                    quotes.entrySet().removeIf(entry -> now - entry.getValue().at() > QUOTE_TTL_MS);
                });
    }

    static List<DynamicShopRepository.Product> load(YamlConfiguration yaml) {
        var section = yaml.getConfigurationSection("products");
        if (section == null) throw new IllegalArgumentException("shop.yml needs a products section");
        if (section.getKeys(false).size() > 1000) throw new IllegalArgumentException("Shop supports at most 1000 products");
        List<DynamicShopRepository.Product> products = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            var p = section.getConfigurationSection(id);
            if (p == null) throw new IllegalArgumentException("Invalid shop product: " + id);
            String name = p.getString("material", "").toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(name);
            if (material == null || material.isAir() || !material.isItem() || MarketInventory.CURRENCIES.contains(name))
                throw new IllegalArgumentException("Unsupported shop material: " + name);
            int quantity = p.getInt("quantity", 1);
            if (quantity > material.getMaxStackSize()) throw new IllegalArgumentException("Shop bundle exceeds stack size: " + id);
            products.add(new DynamicShopRepository.Product(id, material.name(), quantity,
                    p.getString("label", MarketService.pretty(name)), p.getInt("base-price", 1),
                    p.getInt("min-price", 1), p.getInt("max-price", 8),
                    p.getInt("purchase-step", yaml.getInt("price.purchase-step", 1)),
                    p.getInt("restock-drop", yaml.getInt("price.restock-drop", 1)),
                    p.getInt("max-stock", 16), p.getInt("restock-amount", 8),
                    p.getInt("min-restock-seconds", yaml.getInt("restock.min-seconds", 7200)),
                    p.getInt("max-restock-seconds", yaml.getInt("restock.max-seconds", 14400))));
        }
        return List.copyOf(products);
    }

    static Menu.Screen browse(List<DynamicShopRepository.Offer> offers, long now) {
        return browse(offers, now, "all", 0, Map.of(), Map.of());
    }
    static Menu.Screen browse(List<DynamicShopRepository.Offer> offers, long now, String category, int requestedPage,
                              Map<String, String> categories, Map<String, String> productCategories) {
        List<DynamicShopRepository.Offer> filtered = offers.stream()
                .filter(o -> category.equals("all") || category.equals(productCategories.get(o.product().id()))).toList();
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        String browseId = browseId(category, page);
        List<Menu.Button> buttons = new ArrayList<>();
        DynamicShopRepository.Offer next = filtered.stream().min(java.util.Comparator.comparingLong(
                DynamicShopRepository.Offer::nextRestock)).orElse(null);
        Map<Integer, Menu.Countdown> headerTimers = next == null ? Map.of()
                : Map.of(0, new Menu.Countdown("Next Restock: ", next.nextRestock(), next.reserved()));
        for (var offer : filtered.subList(Math.min(page * PAGE_SIZE, filtered.size()), Math.min((page + 1) * PAGE_SIZE, filtered.size()))) {
            var p = offer.product();
            Menu.Countdown timer = new Menu.Countdown("Restock: ", offer.nextRestock(), offer.reserved());
            String availability = offer.reserved() ? "Purchase completing…" : offer.stock() + " bundles in stock";
            buttons.add(new Menu.Button(Component.text(p.quantity() + " x " + p.label(), NamedTextColor.WHITE),
                    List.of(Component.text(offer.currentPrice() + " emerald blocks", NamedTextColor.WHITE),
                            gray(availability), timer.at(now)),
                    Material.valueOf(p.material()), Menu.Action.OPEN, "market_stock:" + p.id()
                    + (category.equals("all") && page == 0 ? "" : ":" + category + ":" + page), false, Map.of(2, timer)));
        }
        if (page > 0) buttons.add(navigation("Previous Page", Material.ARROW, browseId(category, page - 1)));
        if (page + 1 < pages) buttons.add(navigation("Next Page", Material.ARROW, browseId(category, page + 1)));
        if (!categories.isEmpty()) buttons.add(navigation("Choose Category", Material.CHEST, "market_shop_categories"));
        buttons.add(new Menu.Button(Component.text("Refresh Stock & Timers", NamedTextColor.WHITE),
                List.of(gray("Update prices, availability and countdowns.")), Material.CLOCK, Menu.Action.OPEN, browseId, true));
        buttons.add(new Menu.Button(gray("Back"), List.of(), Material.ARROW, Menu.Action.OPEN, "market", true));
        return new Menu.Screen(browseId, Component.text("Server Shop"),
                List.of(next == null ? gray("Next Restock: No products available") : headerTimers.get(0).at(now),
                        gray("Demand raises prices; replenishment lowers them."),
                        gray("Paid bundles wait in Collect Items & Payments."),
                        gray(categories.getOrDefault(category, "All items") + " | Page " + (page + 1) + "/" + pages + " | " + filtered.size() + " products")),
                List.of(), buttons, false, headerTimers);
    }
    static Menu.Screen confirm(DynamicShopRepository.Offer offer) {
        return confirm(offer, "all", 0);
    }
    static Menu.Screen confirm(DynamicShopRepository.Offer offer, String category, int page) {
        var p = offer.product();
        return new Menu.Screen("market_stock:" + p.id() + (category.equals("all") && page == 0 ? "" : ":" + category + ":" + page), Component.text("Confirm Shop Purchase"),
                List.of(Component.text(p.quantity() + " x " + p.label(), NamedTextColor.WHITE),
                        gray("Price: " + offer.currentPrice() + " emerald blocks"),
                        gray("Collect your paid bundle from Items & Payments.")), List.of(), List.of(
                new Menu.Button(Component.text("Buy Bundle", NamedTextColor.WHITE), List.of(), Material.LIME_CONCRETE,
                        Menu.Action.MARKET_SHOP_BUY, reference(offer), false),
                new Menu.Button(Component.text("Cancel", NamedTextColor.WHITE), List.of(), Material.RED_CONCRETE,
                        Menu.Action.OPEN, browseId(category, page), true)), true);
    }
    static String browseId(String category, int page) {
        return category.equals("all") && page == 0 ? "market_shop" : "market_shop:" + category + ":" + page;
    }
    private static Menu.Button navigation(String text, Material icon, String id) {
        return new Menu.Button(Component.text(text), List.of(), icon, Menu.Action.OPEN, id, true);
    }
    static Map<String, String> categoryLabels(YamlConfiguration yaml) {
        Map<String, String> result = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("categories");
        if (section != null) for (String key : section.getKeys(false)) {
            if (!key.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Invalid shop category: " + key);
            result.put(key, section.getString(key, MarketService.pretty(key)));
        }
        var products = yaml.getConfigurationSection("products");
        if (products != null) for (String id : products.getKeys(false)) {
            String key = yaml.getString("products." + id + ".category", "other");
            if (!key.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Invalid shop category: " + key);
            result.putIfAbsent(key, MarketService.pretty(key));
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    static Map<String, String> productCategories(YamlConfiguration yaml) {
        Map<String, String> result = new LinkedHashMap<>();
        var products = yaml.getConfigurationSection("products");
        if (products != null) for (String id : products.getKeys(false)) result.put(id, yaml.getString("products." + id + ".category", "other"));
        return Map.copyOf(result);
    }
    static Menu.Screen categoryMenu(Map<String, String> categories, Map<String, String> productCategories) {
        List<Menu.Button> buttons = new ArrayList<>();
        buttons.add(new Menu.Button(Component.text("All items (" + productCategories.size() + ")"), List.of(),
                Material.CHEST, Menu.Action.OPEN, "market_shop", false));
        categories.forEach((key, label) -> buttons.add(new Menu.Button(Component.text(label + " (" + productCategories.values().stream()
                .filter(key::equals).count() + ")"), List.of(), Material.CHEST, Menu.Action.OPEN, browseId(key, 0), false)));
        buttons.add(navigation("Back", Material.ARROW, "market"));
        return new Menu.Screen("market_shop_categories", Component.text("Shop Categories"), List.of(), List.of(), buttons, false);
    }
    /** Merge missing catalog goods once; preserve every existing product setting and custom product. */
    static boolean upgrade(YamlConfiguration yaml, YamlConfiguration defaults) {
        if (yaml.getInt("catalog-version", 0) >= 2) return false;
        var products = defaults.getConfigurationSection("products");
        for (String id : products.getKeys(false)) {
            String path = "products." + id;
            if (!yaml.contains(path)) {
                for (var entry : products.getConfigurationSection(id).getValues(false).entrySet()) yaml.set(path + "." + entry.getKey(), entry.getValue());
            } else if (!yaml.contains(path + ".category")) yaml.set(path + ".category", defaults.getString(path + ".category"));
        }
        for (var entry : defaults.getConfigurationSection("categories").getValues(false).entrySet()) {
            String path = "categories." + entry.getKey();
            if (!yaml.contains(path)) yaml.set(path, entry.getValue());
        }
        // Only migrate the former bundled interval; deliberately customized timings survive.
        if (yaml.getInt("restock.min-seconds", 300) == 300 && yaml.getInt("restock.max-seconds", 900) == 900) {
            yaml.set("restock.min-seconds", 7200); yaml.set("restock.max-seconds", 14400);
        }
        yaml.set("catalog-version", 2);
        load(yaml); categoryLabels(yaml); // Validate before the caller writes the file.
        return true;
    }
    private static String reference(DynamicShopRepository.Offer offer) { return offer.product().id() + "|" + offer.currentPrice(); }
    private static int randomSeconds(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }
    private static Component gray(String text) { return Component.text(text, NamedTextColor.WHITE); }
}

