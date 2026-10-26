package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.gui.Menu;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.SqlTask;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Main-thread inventory coordinator; SQL transactions and escrow run on the storage executor. */
public final class MarketService implements SessionHook {
    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final StorageHealth health;
    private final MainThread main;
    private final Logger log;
    private final MarketRepository repo;
    private final MarketInventory.Codec codec;
    private final Set<UUID> busy = new HashSet<>();
    private final Set<UUID> blocked = new HashSet<>();
    private final Set<UUID> recoverAgain = new HashSet<>();
    private final Map<UUID, Map<String, Runnable>> inventoryReady = new HashMap<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Map<UUID, MarketRepository.Listing> quotes = new HashMap<>();
    private final Map<UUID, String> exchangeQuotes = new HashMap<>();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private DynamicShopService shop;

    public MarketService(SessionManager sessions, StorageExecutor storage, StorageHealth health,
                         MainThread main, Logger log, MarketRepository repo, MarketInventory.Codec codec) {
        this.sessions = sessions; this.storage = storage; this.health = health; this.main = main;
        this.log = log; this.repo = repo; this.codec = codec;
    }

    public void setShop(DynamicShopService shop) { this.shop = shop; }
    public boolean busy(UUID player) { return busy.contains(player); }
    public boolean blocked(UUID player) { return blocked.contains(player); }
    public boolean inventoryLocked(UUID player) {
        return busy(player) || blocked(player) || recoverAgain.contains(player);
    }

    /** Coalesces bound-item work until the exact journal inventory has been checked and released. */
    public void afterInventoryReady(UUID player, String key, Runnable action) {
        if (inventoryLocked(player)) {
            inventoryReady.computeIfAbsent(player, ignored -> new HashMap<>()).put(key, action);
        } else action.run();
    }
    public void combat(UUID player) { combatUntil.put(player, System.currentTimeMillis() + 10_000L); }

    public void open(Player player, String screenId, Consumer<Menu.Screen> show) {
        if (!available(player)) return;
        if (shop != null) shop.cancel(player.getUniqueId());
        exchangeQuotes.remove(player.getUniqueId());
        try {
            String[] parts = screenId.split(":", 4);
            String screen = parts[0];
            switch (screen) {
                case "market" -> show.accept(MarketMenus.home());
                case "market_exchange" -> show.accept(MarketMenus.exchange());
                case "market_exchange_confirm" -> {
                    CurrencyExchange exchange = CurrencyExchange.find(parts[1]);
                    exchangeQuotes.put(player.getUniqueId(), exchange.id());
                    show.accept(MarketMenus.exchangeConfirm(exchange));
                }
                case "market_shop", "market_stock", "market_shop_categories" -> {
                    if (shop != null) shop.open(player, screenId, show);
                }
                case "market_sell" -> show.accept(MarketMenus.sellSlots(player, page(parts)));
                case "market_currency" -> {
                    int slot = number(parts[1], 0, 35);
                    ItemStack stack = player.getInventory().getItem(slot);
                    if (!MarketMenus.isSellable(stack) || stack.getAmount() > stack.getMaxStackSize()) {
                        tell(player, "That item cannot be listed."); return;
                    }
                    Draft draft = new Draft(slot, stack.clone());
                    drafts.put(player.getUniqueId(), draft);
                    show.accept(MarketMenus.sellCurrency(slot, draft.item()));
                }
                case "market_price", "market_sell_confirm" -> {
                    int slot = number(parts[1], 0, 35);
                    currency(parts[2]);
                    int price = number(parts[3], 1, MarketInventory.MAX_PRICE);
                    Draft draft = drafts.get(player.getUniqueId());
                    if (draft == null || draft.slot() != slot
                            || !draft.item().equals(player.getInventory().getItem(slot))) {
                        tell(player, "The selected item changed. Choose it again."); return;
                    }
                    show.accept(screen.equals("market_price")
                            ? MarketMenus.sellPrice(slot, draft.item(), parts[2], price)
                            : MarketMenus.sellConfirm(slot, draft.item(), parts[2], price));
                }
                case "market_browse", "market_mine" -> {
                    int page = page(parts);
                    boolean own = screen.equals("market_mine");
                    read(player, "market.browse", c -> repo.listings(c,
                            own ? player.getUniqueId() : null, page * 10, 11), list ->
                            show.accept(MarketMenus.browse(list.subList(0, Math.min(10, list.size())),
                                    page, list.size() > 10, own)));
                }
                case "market_listing" -> {
                    String id = screenId.substring("market_listing:".length());
                    read(player, "market.detail", c -> repo.listing(c, id), listing -> {
                        if (listing == null) { tell(player, "This listing is no longer available."); return; }
                        quotes.put(player.getUniqueId(), listing);
                        show.accept(MarketMenus.detail(listing, listing.seller().equals(player.getUniqueId())));
                    });
                }
                case "market_deliveries" -> {
                    int page = page(parts);
                    read(player, "market.deliveries", c -> repo.deliveries(c, player.getUniqueId(), page * 10, 11), list ->
                            show.accept(MarketMenus.deliveries(list.subList(0, Math.min(10, list.size())),
                                    page, list.size() > 10)));
                }
                case "market_delivery" -> {
                    String id = screenId.substring("market_delivery:".length());
                    read(player, "market.delivery", c -> repo.delivery(c, id, player.getUniqueId()), delivery -> {
                        if (delivery == null) { tell(player, "This delivery is no longer available."); return; }
                        show.accept(MarketMenus.claimDetail(delivery));
                    });
                }
                default -> show.accept(MarketMenus.home());
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            tell(player, "This market screen expired. Open the book again.");
        }
    }

    public void handle(Player player, Menu.Action action, String arg, Runnable after) {
        if (!available(player)) return;
        switch (action) {
            case MARKET_SELL -> sell(player, arg, after);
            case MARKET_BUY -> buy(player, arg, after);
            case MARKET_CANCEL -> cancel(player, arg, after);
            case MARKET_CLAIM -> claim(player, arg, after);
            case MARKET_EXCHANGE -> exchange(player, arg, after);
            case MARKET_SHOP_BUY -> { if (shop != null) shop.buy(player, arg, after); }
            default -> { }
        }
    }

    public void sell(Player player, String arg, Runnable after) {
        try {
            String[] parts = arg.split(":");
            int slot = number(parts[0], 0, 35);
            String currency = currency(parts[1]);
            int price = number(parts[2], 1, MarketInventory.MAX_PRICE);
            Draft draft = drafts.remove(player.getUniqueId());
            ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
            if (draft == null || draft.slot() != slot || !draft.item().equals(before[slot])
                    || !MarketMenus.isSellable(before[slot])) {
                tell(player, "The selected item changed. Choose it again."); return;
            }
            ItemStack item = before[slot].clone();
            ItemStack[] next = MarketInventory.copy(before);
            next[slot] = null;
            transact(player, "SELL", UUID.randomUUID().toString(), before, next, item,
                    item.getAmount() + " x " + Messages.plain(MarketMenus.name(item)), currency, price, after);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) { tell(player, "Invalid listing."); }
    }

    private void buy(Player player, String id, Runnable after) {
        MarketRepository.Listing quote = quotes.remove(player.getUniqueId());
        if (quote == null || !id.equals(quote.id()) || quote.seller().equals(player.getUniqueId())) {
            tell(player, "Open the listing again to confirm its price."); return;
        }
        ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
        ItemStack[] next = MarketInventory.debit(before, quote.currency(), quote.price());
        if (next == null) { tell(player, "You do not have enough ordinary " + pretty(quote.currency()) + "."); return; }
        transact(player, "BUY", id, before, next, new ItemStack(Material.valueOf(quote.currency()), quote.price()),
                quote.itemName(), quote.currency(), quote.price(), after);
    }

    private void cancel(Player player, String id, Runnable after) {
        ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
        transact(player, "CANCEL", id, before, before, null, "Cancelled listing", null, 0, after);
    }

    private void claim(Player player, String id, Runnable after) {
        read(player, "market.claim-read", c -> repo.delivery(c, id, player.getUniqueId()), delivery -> {
            if (delivery == null) { tell(player, "This delivery is no longer available."); return; }
            ItemStack item = codec.item(delivery.item());
            ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
            int take = Math.min(item.getAmount(), MarketInventory.capacity(before, item));
            if (take == 0) { tell(player, "Make room in your inventory. Your delivery stays safe here."); return; }
            ItemStack remainder = item.getAmount() > take ? item.clone() : null;
            if (remainder != null) remainder.setAmount(item.getAmount() - take);
            item.setAmount(take);
            ItemStack[] next = MarketInventory.credit(before, item);
            if (next == null) { tell(player, "Make room in your inventory. Your delivery stays safe here."); return; }
            if (remainder != null) tell(player, "Collecting " + take + " now; the remainder stays in collection.");
            transact(player, "CLAIM", id, before, next, remainder, delivery.description(), null, 0, after);
        });
    }

    private void exchange(Player player, String exchange, Runnable after) {
        if (exchange == null || !exchange.equals(exchangeQuotes.remove(player.getUniqueId()))) {
            tell(player, "Review the currency exchange again before confirming."); return;
        }
        CurrencyExchange route;
        try { route = CurrencyExchange.find(exchange); }
        catch (IllegalArgumentException e) { tell(player, "Unknown exchange."); return; }
        ItemStack[] before = MarketInventory.copy(player.getInventory().getStorageContents());
        ItemStack[] next = MarketInventory.debit(before, route.input().name(), route.inputAmount());
        if (next == null) { tell(player, "You need " + route.give() + " of ordinary currency."); return; }
        transact(player, "EXCHANGE", exchange, before, next, new ItemStack(route.output(), route.outputAmount()),
                route.receive(), route.input().name(), route.inputAmount(), after);
    }

    /** Package access lets the dynamic shop use the same durable inventory journal. */
    void transact(Player player, String kind, String reference, ItemStack[] before, ItemStack[] next,
                  ItemStack item, String itemName, String currency, int price, Runnable after) {
        if (!available(player) || !MarketInventory.same(before, player.getInventory().getStorageContents())) return;
        UUID uuid = player.getUniqueId();
        PlayerSession session = sessions.get(player);
        busy.add(uuid);
        player.closeInventory();
        MarketRepository.Operation op;
        try {
            op = new MarketRepository.Operation(UUID.randomUUID().toString(), uuid, kind, reference,
                    codec.inventory(before), codec.inventory(next), item == null ? null : codec.item(item),
                    itemName, currency, price, player.getName(), System.currentTimeMillis());
        } catch (RuntimeException e) { release(uuid); log.log(Level.SEVERE, "Cannot encode market inventory", e); return; }
        main.onComplete(storage.submit("market.prepare", c -> repo.prepare(c, op)), (prepared, error) -> {
            if (error != null) { quarantine(player, op, error); return; }
            if (!Boolean.TRUE.equals(prepared)) {
                release(uuid);
                if (current(player, session)) tell(player, "Unavailable, price changed, or your 10-listing limit was reached. No items taken.");
                return;
            }
            if (!current(player, session) || player.isDead()
                    || !MarketInventory.same(before, player.getInventory().getStorageContents())) {
                finish(player, op, false, null); return;
            }
            try {
                MarketInventory.apply(player.getInventory(), next);
                // Persist inventory BEFORE publishing the escrow/claim transaction.
                player.saveData();
            } catch (RuntimeException e) { quarantine(player, op, e); return; }
            finish(player, op, true, () -> {
                if (current(player, session)) {
                    tell(player, kind.equals("SELL") ? "Item listed for sale."
                            : kind.equals("CLAIM") ? "Delivery collected."
                            : "Trade completed. Collect items or payment in the market.");
                    after.run();
                }
            });
        });
    }

    private void finish(Player player, MarketRepository.Operation op, boolean commit, Runnable after) {
        main.onComplete(storage.submit(commit ? "market.commit" : "market.abort",
                c -> commit ? repo.commit(c, op.id()) : repo.abort(c, op.id())), (done, error) -> {
            if (error != null || !Boolean.TRUE.equals(done)) { quarantine(player, op, error); return; }
            blocked.remove(op.actor());
            release(op.actor());
            if (after != null) after.run();
        });
    }

    private void quarantine(Player player, MarketRepository.Operation op, Throwable error) {
        blocked.add(op.actor());
        release(op.actor());
        log.log(Level.SEVERE, "Market operation needs recovery: " + op.id() + " player=" + op.actor()
                + " kind=" + op.kind() + ". Do not refund blindly; use /auction pending and inspect inventory.", error);
        if (player.isOnline()) tell(player, "A trade needs recovery. Your market is paused; ask an administrator.");
    }

    @Override public void onLoaded(Player player, PlayerSession session, boolean reload) {
        if (reload) return;
        if (busy(player.getUniqueId())) recoverAgain.add(player.getUniqueId());
        else recover(player);
    }

    @Override public void onUnload(Player player, PlayerSession session) {
        if (shop != null) shop.cancel(player.getUniqueId());
        drafts.remove(session.uuid()); quotes.remove(session.uuid()); exchangeQuotes.remove(session.uuid());
        combatUntil.remove(session.uuid()); inventoryReady.remove(session.uuid());
    }

    public void recover(Player player) {
        UUID uuid = player.getUniqueId();
        if (busy(uuid)) return;
        busy.add(uuid);
        PlayerSession session = sessions.get(player);
        main.onComplete(storage.submit("market.recover", c -> repo.pending(c, uuid)), (pending, error) -> {
            if (error != null || pending == null) { blocked.add(uuid); release(uuid); return; }
            if (!current(player, session) || player.isDead()) { release(uuid); return; }
            if (pending.isEmpty()) { blocked.remove(uuid); release(uuid); return; }
            // A single operation per actor is normally enforced in memory. Never guess across multiple snapshots.
            if (pending.size() != 1) { quarantine(player, pending.getFirst(), null); return; }
            MarketRepository.Operation op = pending.getFirst();
            try {
                ItemStack[] current = player.getInventory().getStorageContents();
                boolean before = MarketInventory.same(current, codec.inventory(op.beforeInventory()));
                boolean next = MarketInventory.same(current, codec.inventory(op.afterInventory()));
                if (next) {
                    player.saveData();
                    finish(player, op, true, () -> tell(player, "Your interrupted market trade was recovered."));
                } else if (before) {
                    finish(player, op, false, () -> tell(player, "Your interrupted trade was cancelled; no items taken."));
                } else quarantine(player, op, null);
            } catch (RuntimeException e) { quarantine(player, op, e); }
        });
    }

    /** Explicit administrator decision after examining inventory and server backups; no blind refund. */
    public void resolve(org.bukkit.command.CommandSender admin, String id, boolean commit) {
        main.onComplete(storage.submit("market.resolve-read", c -> repo.operation(c, id)), (op, error) -> {
            if (error != null || op == null) { admin.sendMessage("Operation not found or storage unavailable."); return; }
            if (busy(op.actor())) { admin.sendMessage("Player has a transaction in flight. Retry later."); return; }
            busy.add(op.actor());
            main.onComplete(storage.submit("market.admin-resolve", c -> commit ? repo.commit(c, id) : repo.abort(c, id)),
                    (done, failure) -> {
                        if (failure == null && Boolean.TRUE.equals(done)) {
                            blocked.remove(op.actor());
                            log.warning("Market administrator " + admin.getName() + " resolved " + id + " as " + (commit ? "commit" : "abort"));
                            admin.sendMessage("Operation resolved. Inventory was not changed by this command.");
                        } else admin.sendMessage("Could not resolve operation; check storage and its status.");
                        release(op.actor());
                    });
        });
    }

    public void pending(org.bukkit.command.CommandSender admin) {
        main.onComplete(storage.submit("market.pending", repo::pending), (ops, error) -> {
            if (error != null) { admin.sendMessage("Storage unavailable."); return; }
            if (ops.isEmpty()) admin.sendMessage("No interrupted market operations.");
            for (var op : ops) admin.sendMessage(op.id() + " " + op.actorName() + " " + op.actor() + " " + op.kind());
        });
    }

    private <T> void read(Player player, String label, SqlTask<T> task, Consumer<T> result) {
        PlayerSession session = sessions.get(player);
        main.onComplete(storage.submit(label, task), (value, error) -> {
            if (!current(player, session)) return;
            if (error != null) { tell(player, "Market storage is unavailable. Try again later."); return; }
            result.accept(value);
        });
    }

    private boolean available(Player player) {
        PlayerSession session = sessions.get(player);
        if (!player.hasPermission("ascension.market")) { tell(player, "You do not have market permission."); return false; }
        if (session == null || !session.usable() || player.isDead()) { tell(player, "Your player data is not ready."); return false; }
        if (blocked(player.getUniqueId())) { tell(player, "Your market needs recovery. Ask an administrator."); return false; }
        if (inventoryLocked(player.getUniqueId()) || session.inFlight("quest.submit") || session.bookIssuing()) {
            tell(player, "Wait for your current item transaction or recovery."); return false;
        }
        if (combatUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) {
            tell(player, "Wait 10 seconds after combat before trading."); return false;
        }
        if (!health.isHealthy()) { tell(player, "Market storage is unavailable. Try again later."); return false; }
        return true;
    }

    private boolean current(Player player, PlayerSession session) {
        return player.isOnline() && session != null && sessions.get(player) == session && session.usable();
    }
    private void release(UUID uuid) {
        busy.remove(uuid);
        if (recoverAgain.contains(uuid)) {
            main.later(1, () -> {
                recoverAgain.remove(uuid);
                Player player = Bukkit.getPlayer(uuid);
                PlayerSession session = player == null ? null : sessions.get(player);
                if (session != null && session.usable()) recover(player);
            });
            return;
        }
        if (!blocked(uuid)) main.later(1, () -> {
            if (inventoryLocked(uuid)) return;
            Player player = Bukkit.getPlayer(uuid);
            PlayerSession session = player == null ? null : sessions.get(player);
            if (session == null || !session.usable()) return;
            Map<String, Runnable> waiting = inventoryReady.remove(uuid);
            if (waiting != null) waiting.values().forEach(Runnable::run);
        });
    }
    private static int page(String[] parts) { return parts.length < 2 ? 0 : number(parts[1], 0, 100_000); }
    private static int number(String value, int min, int max) {
        int number = Integer.parseInt(value);
        if (number < min || number > max) throw new IllegalArgumentException("Out of range");
        return number;
    }
    public static String currency(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "emerald", "emerald_block" -> "EMERALD_BLOCK";
            case "diamond", "diamond_block" -> "DIAMOND_BLOCK";
            case "netherite", "netherite_ingot" -> "NETHERITE_INGOT";
            default -> throw new IllegalArgumentException("Unknown currency");
        };
    }
    static String pretty(String material) { return material.toLowerCase(Locale.ROOT).replace('_', ' '); }
    static void tell(Player player, String message) {
        player.sendMessage(Component.text("[Market] " + message, NamedTextColor.WHITE));
    }
    private record Draft(int slot, ItemStack item) {}
}

