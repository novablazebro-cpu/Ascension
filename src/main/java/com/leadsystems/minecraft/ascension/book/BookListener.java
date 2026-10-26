package com.leadsystems.minecraft.ascension.book;

import com.leadsystems.minecraft.ascension.gui.MenuHolder;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.text.Messages;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every Chronicle protection path, plus opening the menu on use. Handlers that cancel run at
 * LOWEST so other plugins (graves, backpacks) never see a Chronicle move; reconcile triggers run at
 * MONITOR after the change has happened.
 */
public final class BookListener implements Listener {

    /** Blocks that would accept the book from a right-click. */
    private static final Set<Material> BOOK_SINKS = Set.of(Material.LECTERN, Material.CHISELED_BOOKSHELF,
            Material.DECORATED_POT);

    private final BookService books;
    private final BookItems items;
    private final SessionManager sessions;
    private final MainThread main;
    private final Messages messages;
    private final Consumer<Player> openMenu;
    private final long openCooldownMs;

    public BookListener(BookService books, SessionManager sessions, MainThread main, Messages messages,
                        Consumer<Player> openMenu, long openCooldownMs) {
        this.books = books;
        this.items = books.items();
        this.sessions = sessions;
        this.main = main;
        this.messages = messages;
        this.openMenu = openMenu;
        this.openCooldownMs = openCooldownMs;
    }

    // ------------------------------------------------------------------ use: open the menu

    // Not ignoreCancelled: right-clicking air arrives pre-cancelled.
    // BlockType#isInteractable is deprecated (not for removal) as "not comprehensive"; it is only
    // used to let common blocks (doors, chests) keep working while the book is held.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (!items.isChronicle(item) || !e.getAction().isRightClick()) {
            return;
        }
        Player p = e.getPlayer();
        e.setUseItemInHand(Event.Result.DENY);
        Block block = e.getClickedBlock();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && block != null) {
            if (BOOK_SINKS.contains(block.getType())) {
                e.setUseInteractedBlock(Event.Result.DENY);
            } else if (block.getType().asBlockType() != null && block.getType().asBlockType().isInteractable()
                    && !p.isSneaking()) {
                return; // let doors, chests, buttons work normally while holding the book
            }
        }
        // Only the main hand opens the menu when both hands hold something.
        if (e.getHand() == EquipmentSlot.OFF_HAND && items.isChronicle(p.getInventory().getItemInMainHand())) {
            return;
        }
        PlayerSession s = sessions.get(p);
        if (s == null || !s.usable()) {
            messages.send(p, "data-unavailable");
            return;
        }
        if (!items.isValidFor(item, s)) {
            books.reconcile(p, s, false); // removes the invalid copy, issues a fresh one if needed
            return;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastMenuOpenMs() < openCooldownMs) {
            return;
        }
        s.setLastMenuOpenMs(now);
        openMenu.accept(p);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLectern(PlayerInsertLecternBookEvent e) {
        if (items.isChronicle(e.getBook())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ inventory movement

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        InventoryView view = e.getView();
        Inventory top = view.getTopInventory();
        if (top.getHolder(false) instanceof MenuHolder) {
            return; // menu clicks are all cancelled by the menu listener
        }
        if (e instanceof InventoryCreativeEvent creative) {
            // Creative inventories are client-authoritative; cancelling desyncs the client.
            // Let it through and dedupe right after (a clone carries the same nonce).
            if (items.isChronicle(creative.getCursor()) || items.isChronicle(creative.getCurrentItem())) {
                books.scheduleReconcile(p);
            }
            return;
        }
        int raw = e.getRawSlot();
        int hotbar = e.getHotbarButton();
        ClickRules.ClickFacts facts = new ClickRules.ClickFacts(
                top.getType() != InventoryType.CRAFTING,
                raw >= 0 && raw < top.getSize(),
                e.getSlotType() == InventoryType.SlotType.OUTSIDE,
                e.getAction(),
                e.getClick(),
                items.isChronicle(e.getCurrentItem()),
                items.isChronicle(e.getCursor()),
                hotbar >= 0 && items.isChronicle(p.getInventory().getItem(hotbar)),
                items.isChronicle(p.getInventory().getItemInOffHand()));
        if (ClickRules.deny(facts)) {
            e.setCancelled(true);
            warn(p, "book-cannot-move");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!items.isChronicle(e.getOldCursor())) {
            return;
        }
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    warn(p, "book-cannot-move");
                }
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (items.isChronicle(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            warn(e.getPlayer(), "book-cannot-move");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity target = e.getRightClicked();
        if (!(target instanceof ItemFrame || target instanceof Allay || target instanceof ArmorStand)) {
            return;
        }
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (items.isChronicle(hand)) {
            e.setCancelled(true);
            warn(e.getPlayer(), "book-cannot-move");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (items.isChronicle(e.getPlayerItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemFrame(PlayerItemFrameChangeEvent e) {
        if (e.getAction() == PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE && items.isChronicle(e.getItemStack())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ crafting-style inventories

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (items.isChronicle(i)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (items.isChronicle(i)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        if (containsBook(e.getInventory())) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent e) {
        if (containsBook(e.getInventory())) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareSmithing(PrepareSmithingEvent e) {
        if (containsBook(e.getInventory())) {
            e.setResult(null);
        }
    }

    private boolean containsBook(Inventory inv) {
        for (ItemStack i : inv.getContents()) {
            if (items.isChronicle(i)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the world: purge strays

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (items.isChronicle(e.getEntity().getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent e) {
        removeIfBook(e.getItem(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        removeIfBook(e.getItem(), e);
    }

    private void removeIfBook(Item item, org.bukkit.event.Cancellable e) {
        if (items.isChronicle(item.getItemStack())) {
            e.setCancelled(true);
            item.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        if (items.isChronicle(e.getItem())) {
            e.setCancelled(true);
            Inventory source = e.getSource();
            main.later(1, () -> books.purge(source));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (items.isChronicle(e.getItem())) {
            e.setCancelled(true);
        }
    }

    /** Catches copies that ended up in containers some other way (grave/backpack plugins). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || top.getHolder(false) instanceof MenuHolder) {
            return;
        }
        int removed = books.purge(top);
        if (removed > 0 && e.getPlayer() instanceof Player p) {
            warn(p, "book-invalid");
        }
    }

    // ------------------------------------------------------------------ death & reconcile triggers

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        PlayerSession s = sessions.get(p);
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            if (items.isChronicle(it.next())) {
                it.remove(); // never in the death-drop list, whatever happens next
            }
        }
        if (s == null || !s.usable()) {
            return;
        }
        // Keep the valid copy through death. Paper's processKeep keeps inventory stacks equal to
        // an entry of getItemsToKeep() when keepInventory is off (verified in ServerPlayer.die).
        for (ItemStack i : p.getInventory().getContents()) {
            if (items.isValidFor(i, s)) {
                e.getItemsToKeep().add(i.clone());
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        books.scheduleReconcile(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        books.scheduleReconcile(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        books.scheduleReconcile(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            books.scheduleReconcile(p);
        }
    }

    /** Fires for every player-inventory slot change, including /clear and other plugins. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent e) {
        if (items.isChronicle(e.getOldItemStack()) || items.isChronicle(e.getNewItemStack())) {
            books.scheduleReconcile(e.getPlayer());
        }
    }

    private void warn(Player p, String key) {
        PlayerSession s = sessions.get(p);
        if (s != null) {
            books.warn(p, s, key);
        }
    }
}
