package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.gui.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Screens for the player-to-player market.  This class deliberately contains no economy logic. */
public final class MarketMenus {

    private static final int PAGE_SIZE = 10;
    private static final int MIN_PRICE = 1;
    private static final int MAX_PRICE = 4096;

    private MarketMenus() {}

    public static Menu.Screen home() {
        return new Menu.Screen("market", gold("Auction House"),
                List.of(gray("Trade safely with other players.")), List.of(), List.of(
                open("Buy Auctions", "Browse items other players are selling.", Material.CHEST, "market_browse:0"),
                open("Sell Items", "List up to 10 items for sale.", Material.EMERALD, "market_sell:0"),
                open("My Listings", "Manage your items currently for sale.", Material.WRITABLE_BOOK, "market_mine:0"),
                open("Server Shop", "Limited stock; prices respond to demand.", Material.IRON_BLOCK, "market_shop"),
                open("Collect Items & Payments", "Claim purchases and sale payments.", Material.HOPPER, "market_deliveries:0"),
                open("Exchange Currency", "Convert currency in either direction.", Material.DIAMOND, "market_exchange"),
                nav("Back to Ascension main", Material.ARROW, "main")), false);
    }

    public static Menu.Screen browse(List<MarketRepository.Listing> listings, int page, boolean next, boolean own) {
        int safePage = Math.max(0, page);
        List<Menu.Button> buttons = listingButtons(listings, own);
        addPaging(buttons, safePage, next, own ? "market_mine" : "market_browse");
        buttons.add(nav("Back", Material.ARROW, "market"));
        String heading = own ? "My Listings" : "Buy Auctions";
        String empty = own ? "You have no active listings." : "There are no auctions on this page.";
        return new Menu.Screen((own ? "market_mine:" : "market_browse:") + safePage, gold(heading),
                List.of(gray(listings.isEmpty() ? empty : "Page " + (safePage + 1))), List.of(), buttons, false);
    }

    public static Menu.Screen detail(MarketRepository.Listing listing, boolean own) {
        String price = listing.price() + " " + currencyName(listing.currency());
        List<Component> body = List.of(
                Component.text(listing.itemName(), NamedTextColor.WHITE),
                gray("Seller: " + listing.sellerName()),
                gold("Price: " + price),
                gray(own ? "Cancelling returns the item to your deliveries." : "Purchase is final once confirmed."));
        Menu.Button confirm = own
                ? new Menu.Button(Component.text("Cancel Listing", NamedTextColor.RED), List.of(), Material.RED_CONCRETE,
                Menu.Action.MARKET_CANCEL, listing.id(), false)
                : new Menu.Button(Component.text("Buy for " + price, NamedTextColor.GREEN), List.of(), Material.LIME_CONCRETE,
                Menu.Action.MARKET_BUY, listing.id(), false);
        Menu.Button cancel = new Menu.Button(Component.text("Back", NamedTextColor.GRAY), List.of(), Material.ARROW,
                Menu.Action.OPEN, own ? "market_mine:0" : "market_browse:0", false);
        return new Menu.Screen("market_listing:" + listing.id(), gold(own ? "Manage Listing" : "Confirm Purchase"),
                body, List.of(), List.of(confirm, cancel), true);
    }

    public static Menu.Screen sellSlots(Player player, int page) {
        int safePage = Math.max(0, page);
        int from = safePage * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, 36);
        List<Menu.Button> buttons = new ArrayList<>();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = from; slot < to; slot++) {
            ItemStack item = slot < storage.length ? storage[slot] : null;
            if (!isSellable(item)) continue;
            buttons.add(new Menu.Button(name(item), itemLore(item, slot), item.getType(), Menu.Action.OPEN,
                    "market_currency:" + slot, false));
        }
        boolean next = to < 36;
        addPaging(buttons, safePage, next, "market_sell");
        buttons.add(nav("Back", Material.ARROW, "market"));
        return new Menu.Screen("market_sell:" + safePage, gold("Choose an Item to Sell"),
                List.of(gray(buttons.isEmpty() ? "No sellable items in these inventory slots." : "Choose an item from your inventory.")),
                List.of(), buttons, false);
    }

    public static Menu.Screen sellCurrency(int slot, ItemStack item) {
        List<Component> body = itemBody(slot, item);
        return new Menu.Screen("market_currency:" + slot, gold("Choose Currency"), body, List.of(), List.of(
                open("Emerald Blocks", "Set a price in emerald blocks.", Material.EMERALD_BLOCK, "market_price:" + slot + ":emerald:1"),
                open("Diamond Blocks", "Set a price in diamond blocks.", Material.DIAMOND_BLOCK, "market_price:" + slot + ":diamond:1"),
                open("Netherite Ingots", "Set a price in netherite ingots.", Material.NETHERITE_INGOT, "market_price:" + slot + ":netherite:1"),
                nav("Back", Material.ARROW, "market_sell:0")), false);
    }

    public static Menu.Screen sellPrice(int slot, ItemStack item, String currency, int price) {
        int safe = clampPrice(price);
        List<Menu.Button> buttons = new ArrayList<>();
        for (int delta : new int[] {-64, -10, -1, 1, 10, 64}) {
            int candidate = clampPrice(safe + delta);
            String sign = delta > 0 ? "+" : "";
            buttons.add(new Menu.Button(Component.text(sign + delta, delta > 0 ? NamedTextColor.GREEN : NamedTextColor.RED),
                    List.of(gray("Set price to " + candidate)), delta > 0 ? Material.LIME_DYE : Material.RED_DYE,
                    Menu.Action.OPEN, "market_price:" + slot + ":" + currency + ":" + candidate, false));
        }
        buttons.add(new Menu.Button(Component.text("List for " + safe + " " + currencyName(currency), NamedTextColor.GOLD),
                List.of(gray("Review your listing.")), Material.LIME_CONCRETE, Menu.Action.OPEN,
                "market_sell_confirm:" + slot + ":" + currency + ":" + safe, false));
        buttons.add(nav("Back", Material.ARROW, "market_currency:" + slot));
        List<Component> body = new ArrayList<>(itemBody(slot, item));
        body.add(gold("Price: " + safe + " " + currencyName(currency)));
        return new Menu.Screen("market_price:" + slot + ":" + currency + ":" + safe, gold("Set Sale Price"),
                body, List.of(), buttons, false);
    }

    public static Menu.Screen sellConfirm(int slot, ItemStack item, String currency, int price) {
        int safe = clampPrice(price);
        List<Component> body = new ArrayList<>(itemBody(slot, item));
        body.add(gold("Price: " + safe + " " + currencyName(currency)));
        return new Menu.Screen("market_sell_confirm:" + slot + ":" + currency + ":" + safe, gold("Confirm Listing"),
                body, List.of(), List.of(
                new Menu.Button(Component.text("List Item", NamedTextColor.GREEN), List.of(), Material.LIME_CONCRETE,
                        Menu.Action.MARKET_SELL, slot + ":" + currency + ":" + safe, false),
                new Menu.Button(Component.text("Cancel", NamedTextColor.RED), List.of(), Material.RED_CONCRETE,
                        Menu.Action.OPEN, "market_price:" + slot + ":" + currency + ":" + safe, false)), true);
    }

    public static Menu.Screen deliveries(List<MarketRepository.Delivery> deliveries, int page, boolean next) {
        int safePage = Math.max(0, page);
        List<Menu.Button> buttons = new ArrayList<>();
        for (MarketRepository.Delivery delivery : deliveries) {
            buttons.add(new Menu.Button(Component.text(delivery.description(), NamedTextColor.WHITE),
                    List.of(gray("Click to claim.")), Material.CHEST, Menu.Action.OPEN,
                    "market_delivery:" + delivery.id(), false));
        }
        addPaging(buttons, safePage, next, "market_deliveries");
        buttons.add(nav("Back", Material.ARROW, "market"));
        return new Menu.Screen("market_deliveries:" + safePage, gold("Collect Items & Payments"),
                List.of(gray(deliveries.isEmpty() ? "Nothing is waiting for you." : "Click an item or payment to claim it.")),
                List.of(), buttons, false);
    }

    public static Menu.Screen claimDetail(MarketRepository.Delivery delivery) {
        return new Menu.Screen("market_delivery:" + delivery.id(), gold("Claim Delivery"),
                List.of(Component.text(delivery.description(), NamedTextColor.WHITE), gray("The delivery goes to your inventory.")),
                List.of(), List.of(
                new Menu.Button(Component.text("Claim", NamedTextColor.GREEN), List.of(), Material.LIME_CONCRETE,
                        Menu.Action.MARKET_CLAIM, delivery.id(), false),
                new Menu.Button(Component.text("Back", NamedTextColor.RED), List.of(), Material.RED_CONCRETE,
                        Menu.Action.OPEN, "market_deliveries:0", false)), true);
    }

    public static Menu.Screen exchange() {
        List<Menu.Button> buttons = new ArrayList<>();
        for (CurrencyExchange exchange : CurrencyExchange.ALL) {
            buttons.add(new Menu.Button(Component.text(exchange.give() + " → " + exchange.receive(), NamedTextColor.GREEN),
                    List.of(gray("Review this conversion before paying.")), exchange.output(), Menu.Action.OPEN,
                    "market_exchange_confirm:" + exchange.id(), false));
        }
        buttons.add(nav("Back", Material.ARROW, "market"));
        return new Menu.Screen("market_exchange", gold("Exchange Currency"),
                List.of(gold("1 Netherite Ingot = 4 Diamond Blocks = 16 Emerald Blocks"),
                        gray("Choose a conversion. Only ordinary currency is accepted.")), List.of(), buttons, false);
    }

    public static Menu.Screen exchangeConfirm(CurrencyExchange exchange) {
        return new Menu.Screen("market_exchange_confirm:" + exchange.id(), gold("Confirm Currency Exchange"),
                List.of(Component.text("You give: " + exchange.give(), NamedTextColor.RED),
                        Component.text("You receive: " + exchange.receive(), NamedTextColor.GREEN),
                        gray("Collect your currency here: Auction House → Collect Items & Payments.")),
                List.of(), List.of(
                new Menu.Button(Component.text("Confirm Exchange", NamedTextColor.GREEN), List.of(), Material.LIME_CONCRETE,
                        Menu.Action.MARKET_EXCHANGE, exchange.id(), false),
                new Menu.Button(Component.text("Cancel", NamedTextColor.RED), List.of(), Material.RED_CONCRETE,
                        Menu.Action.OPEN, "market_exchange", false)), true);
    }

    /** Returns whether this stack may be offered in the player market. */
    public static boolean isSellable(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return item != null && !item.getType().isAir();
        return item.getItemMeta().getPersistentDataContainer().getKeys().stream()
                .noneMatch(key -> "ascension".equals(key.getNamespace()));
    }

    /** Human-facing item name which preserves a custom display name when present. */
    public static Component name(ItemStack item) {
        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.displayName() != null) return meta.displayName();
        }
        if (item == null) return Component.text("Unknown Item", NamedTextColor.GRAY);
        return Component.text(pretty(item.getType().name()), NamedTextColor.WHITE);
    }

    private static List<Menu.Button> listingButtons(List<MarketRepository.Listing> listings, boolean own) {
        List<Menu.Button> buttons = new ArrayList<>();
        for (MarketRepository.Listing listing : listings) {
            String owner = own ? "Your listing" : "Seller: " + listing.sellerName();
            buttons.add(new Menu.Button(Component.text(listing.itemName(), NamedTextColor.WHITE),
                    List.of(gold(listing.price() + " " + currencyName(listing.currency())), gray(owner)), Material.CHEST,
                    Menu.Action.OPEN, "market_listing:" + listing.id(), false));
        }
        return buttons;
    }

    private static void addPaging(List<Menu.Button> buttons, int page, boolean next, String prefix) {
        if (page > 0) buttons.add(nav("Previous", Material.ARROW, prefix + ":" + (page - 1)));
        if (next) buttons.add(nav("Next", Material.ARROW, prefix + ":" + (page + 1)));
    }

    private static List<Component> itemBody(int slot, ItemStack item) {
        List<Component> body = new ArrayList<>();
        body.add(Component.text("Item: ").append(name(item)));
        body.add(gray("Inventory slot " + (slot + 1)));
        body.add(gray("Quantity: " + item.getAmount() + " (the whole stack)"));
        return body;
    }

    private static List<Component> itemLore(ItemStack item, int slot) {
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Inventory slot " + (slot + 1) + " • " + item.getAmount()));
        if (item.hasItemMeta() && item.getItemMeta().hasEnchants()) lore.add(gold("Enchanted item"));
        return lore;
    }

    private static int clampPrice(int price) { return Math.max(MIN_PRICE, Math.min(MAX_PRICE, price)); }
    private static String currencyName(String currency) {
        return switch (currency.toLowerCase(Locale.ROOT)) {
            case "emerald", "emerald_block" -> "Emerald Blocks";
            case "diamond", "diamond_block" -> "Diamond Blocks";
            case "netherite", "netherite_ingot" -> "Netherite Ingots";
            default -> pretty(currency);
        };
    }
    private static String pretty(String value) {
        String[] words = value.replace('_', ' ').toLowerCase(Locale.ROOT).split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
    private static Component gold(String text) { return Component.text(text, NamedTextColor.GOLD); }
    private static Component gray(String text) { return Component.text(text, NamedTextColor.GRAY); }
    private static Menu.Button open(String text, String lore, Material icon, String target) { return new Menu.Button(gold(text), List.of(gray(lore)), icon, Menu.Action.OPEN, target, false); }
    private static Menu.Button nav(String text, Material icon, String target) { return new Menu.Button(Component.text(text, NamedTextColor.GRAY), List.of(), icon, Menu.Action.OPEN, target, true); }
}
