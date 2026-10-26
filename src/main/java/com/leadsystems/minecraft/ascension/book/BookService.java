package com.leadsystems.minecraft.ascension.book;

import com.leadsystems.minecraft.ascension.audit.AuditLog;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.book.BookIdentity;
import com.leadsystems.minecraft.ascension.domain.book.BookReconciler;
import com.leadsystems.minecraft.ascension.market.MarketService;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Keeps exactly one valid Chronicle in each player's inventory.
 * <p>
 * Reconciliation runs on join, respawn, world/gamemode change, inventory close, any inventory
 * slot change involving a Chronicle, and a slow round-robin sweep. Issuing a book first rotates the
 * nonce in the database; only after that commit is the new book created, so every older copy -
 * from a crash rollback, a grave plugin, a creative clone - is invalid from then on.
 */
public final class BookService implements SessionHook {

    private static final int PLAYER_INVENTORY_SLOTS = 41; // 0-35 storage, 36-39 armor, 40 offhand

    private final BookItems items;
    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final StorageHealth health;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final AuditLog audit;
    private final List<UUID> sweepQueue = new ArrayList<>();
    private MarketService market;

    public BookService(BookItems items, SessionManager sessions, StorageExecutor storage, PlayerRepository repo,
                       StorageHealth health, MainThread main, Supplier<ConfigSnapshot> config, Messages messages,
                       AuditLog audit) {
        this.items = items;
        this.sessions = sessions;
        this.storage = storage;
        this.repo = repo;
        this.health = health;
        this.main = main;
        this.config = config;
        this.messages = messages;
        this.audit = audit;
    }

    public BookItems items() {
        return items;
    }

    public void setMarket(MarketService market) { this.market = market; }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        Runnable reconcile = () -> {
            if (player.isOnline() && sessions.get(player) == session) reconcile(player, session, true);
        };
        if (market == null) reconcile.run();
        else market.afterInventoryReady(session.uuid(), "book", reconcile);
    }

    // ------------------------------------------------------------------ reconcile

    /** Queues one reconcile for the next tick (coalesces bursts of slot-change events). */
    public void scheduleReconcile(Player player) {
        PlayerSession s = sessions.get(player);
        if (s == null || s.reconcileQueued()) {
            return;
        }
        s.setReconcileQueued(true);
        main.later(1, () -> {
            s.setReconcileQueued(false);
            if (player.isOnline() && sessions.get(player) == s) {
                reconcile(player, s, false);
            }
        });
    }

    /**
     * Enforces the one-valid-book invariant for this player.
     *
     * @param deep also look inside bundles (slower; used on join and by the sweep)
     * @return the plan that was applied, or null if the session is not usable
     */
    public BookReconciler.Plan reconcile(Player player, PlayerSession session, boolean deep) {
        if (!session.usable() || player.isDead() || market != null && market.inventoryLocked(session.uuid())) {
            return null;
        }
        PlayerInventory inv = player.getInventory();
        List<BookReconciler.Found> found = new ArrayList<>();
        int size = Math.min(PLAYER_INVENTORY_SLOTS, inv.getSize());
        for (int slot = 0; slot < size; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null) {
                continue;
            }
            BookIdentity id = items.read(item);
            if (id != null) {
                found.add(new BookReconciler.Found(slot, false, id));
            } else if (deep && isBundle(item) && item.getItemMeta() instanceof BundleMeta bundle) {
                for (ItemStack inside : bundle.getItems()) {
                    BookIdentity bid = items.read(inside);
                    if (bid != null) {
                        found.add(new BookReconciler.Found(slot, true, bid));
                    }
                }
            }
        }
        BookIdentity cursorId = items.read(player.getItemOnCursor());
        if (cursorId != null) {
            found.add(new BookReconciler.Found(BookReconciler.CURSOR, false, cursorId));
        }
        purgeOwnCraftingGrid(player);

        BookReconciler.Plan plan = BookReconciler.plan(found, session.uuid(), session.bookNonce(),
                BookIdentity.CURRENT_SCHEMA);
        for (BookReconciler.Found f : plan.remove()) {
            if (f.insideBundle()) {
                stripFromBundle(inv, f.slot());
            } else if (f.slot() == BookReconciler.CURSOR) {
                player.setItemOnCursor(null);
            } else {
                inv.setItem(f.slot(), null);
            }
        }
        if (plan.foreign() + plan.stale() + plan.duplicates() > 0) {
            audit.record("system", "book.purge", player.getName(),
                    "foreign=" + plan.foreign() + " stale=" + plan.stale() + " duplicates=" + plan.duplicates()
                            + " misplaced=" + plan.misplaced());
            warn(player, session, "book-invalid");
        }
        if (plan.needIssue()) {
            issue(player, session, false);
        } else {
            for (int slot = 0; slot < size; slot++) {
                ItemStack existing = inv.getItem(slot);
                ItemStack styled = items.restyle(existing, session, config.get().settings());
                if (styled != existing) inv.setItem(slot, styled);
            }
        }
        return plan;
    }

    private void stripFromBundle(PlayerInventory inv, int slot) {
        ItemStack bundleItem = inv.getItem(slot);
        if (bundleItem == null || !(bundleItem.getItemMeta() instanceof BundleMeta meta)) {
            return;
        }
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack inside : meta.getItems()) {
            if (!items.isChronicle(inside)) {
                kept.add(inside);
            }
        }
        meta.setItems(kept);
        bundleItem.setItemMeta(meta);
        inv.setItem(slot, bundleItem);
    }

    /** The 2x2 grid of the player's own inventory screen must never hold a Chronicle. */
    private void purgeOwnCraftingGrid(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        // Paper always returns the player's own crafting view here; mocks may return null.
        if (top != null && top.getType() == InventoryType.CRAFTING) {
            for (int i = 0; i < top.getSize(); i++) {
                if (items.isChronicle(top.getItem(i))) {
                    top.setItem(i, null);
                }
            }
        }
    }

    /** Removes every Chronicle from a non-player inventory. Returns how many were removed. */
    public int purge(Inventory inventory) {
        int removed = 0;
        for (int i = 0; i < inventory.getSize(); i++) {
            if (items.isChronicle(inventory.getItem(i))) {
                inventory.setItem(i, null);
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ issue

    /**
     * Issues a new book: rotates the nonce (DB), then gives the item. If storage is down the player
     * is told and nothing changes; if the inventory is full delivery becomes pending.
     *
     * @param announce tell the player even when delivery is merely pending again
     */
    public void issue(Player player, PlayerSession session, boolean announce) {
        if (market != null && market.inventoryLocked(session.uuid())) {
            market.afterInventoryReady(session.uuid(), "book-issue", () -> {
                if (player.isOnline() && sessions.get(player) == session) issue(player, session, announce);
            });
            return;
        }
        if (session.bookIssuing() || !session.usable()) {
            return;
        }
        if (!health.isHealthy()) {
            warn(player, session, "storage-degraded");
            return;
        }
        if (firstFreeStorageSlot(player.getInventory()) < 0) {
            if (!session.bookPending()) {
                session.setBookPending(true);
                storage.submit("book.pending", c -> {
                    repo.setBookPending(c, session.uuid(), true);
                    return null;
                });
                messages.send(player, "book-pending");
            } else if (announce) {
                messages.send(player, "book-pending");
            }
            return;
        }
        session.setBookIssuing(true);
        String newNonce = UUID.randomUUID().toString();
        UUID uuid = session.uuid();
        main.onComplete(storage.submit("book.rotate", c -> {
            repo.rotateNonce(c, uuid, newNonce);
            repo.setBookPending(c, uuid, false);
            return null;
        }), (v, error) -> {
            session.setBookIssuing(false);
            if (error != null) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    messages.send(online, "storage-degraded");
                }
                return;
            }
            session.setBookNonce(newNonce);
            session.setBookPending(false);
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || sessions.get(uuid) != session) {
                return; // committed; the next join reconciles and delivers
            }
            int slot = firstFreeStorageSlot(online.getInventory());
            if (slot < 0) {
                session.setBookPending(true);
                storage.submit("book.pending", c -> {
                    repo.setBookPending(c, uuid, true);
                    return null;
                });
                messages.send(online, "book-pending");
                return;
            }
            // Any copy still present carries the old nonce and is now invalid; remove it first.
            reconcileAfterRotation(online, session);
            online.getInventory().setItem(slot, items.create(session, config.get().settings(), messages));
            messages.send(online, "book-given");
        });
    }

    private void reconcileAfterRotation(Player player, PlayerSession session) {
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < Math.min(PLAYER_INVENTORY_SLOTS, inv.getSize()); slot++) {
            ItemStack item = inv.getItem(slot);
            if (items.isChronicle(item) && !items.isValidFor(item, session)) {
                inv.setItem(slot, null);
            }
        }
        if (items.isChronicle(player.getItemOnCursor()) && !items.isValidFor(player.getItemOnCursor(), session)) {
            player.setItemOnCursor(null);
        }
    }

    /** /titlebook: deliver a missing or pending book. */
    public void deliver(Player player, PlayerSession session) {
        if (!session.usable()) {
            messages.send(player, "data-unavailable");
            return;
        }
        BookReconciler.Plan plan = reconcile(player, session, true);
        if (plan != null && plan.keep() != null) {
            messages.send(player, "book-already");
        } else if (plan != null && session.bookPending() && !session.bookIssuing()) {
            issue(player, session, true);
        }
    }

    /** Admin: invalidate every existing copy and issue a fresh book. */
    public void forceReissue(Player player, PlayerSession session) {
        if (market != null && market.inventoryLocked(session.uuid())) {
            market.afterInventoryReady(session.uuid(), "book-reissue", () -> {
                if (player.isOnline() && sessions.get(player) == session) forceReissue(player, session);
            });
            return;
        }
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < Math.min(PLAYER_INVENTORY_SLOTS, inv.getSize()); slot++) {
            if (items.isChronicle(inv.getItem(slot))) {
                inv.setItem(slot, null);
            }
        }
        issue(player, session, true);
    }

    /** Cheap pre-check (enum name) so the deep scan only clones meta for actual bundles. */
    private static boolean isBundle(ItemStack item) {
        return item.getType().name().endsWith("BUNDLE");
    }

    private static int firstFreeStorageSlot(PlayerInventory inv) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ sweep

    /** Reconciles a few players per call (round-robin safety net; no per-tick full scans). */
    public void sweep(int count) {
        if (count <= 0) {
            return;
        }
        if (sweepQueue.isEmpty()) {
            for (PlayerSession s : sessions.all()) {
                sweepQueue.add(s.uuid());
            }
        }
        Iterator<UUID> it = sweepQueue.iterator();
        int done = 0;
        while (it.hasNext() && done < count) {
            UUID uuid = it.next();
            it.remove();
            Player p = Bukkit.getPlayer(uuid);
            PlayerSession s = sessions.get(uuid);
            if (p != null && s != null) {
                reconcile(p, s, true);
                done++;
            }
        }
    }

    /** Sends a warning at most every 2 seconds per player. */
    public void warn(Player player, PlayerSession session, String key) {
        long now = System.currentTimeMillis();
        if (now - session.lastBookWarningMs() > 2000) {
            session.setLastBookWarningMs(now);
            messages.send(player, key);
        }
    }
}
