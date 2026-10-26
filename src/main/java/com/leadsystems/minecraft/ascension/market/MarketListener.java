package com.leadsystems.minecraft.ascension.market;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** Prevents normal input changing inventory while its journaled snapshot is in flight. */
public final class MarketListener implements Listener {
    private final MarketService market;
    public MarketListener(MarketService market) { this.market = market; }
    private boolean locked(Player player) { return market.busy(player.getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void interact(PlayerInteractEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void entity(PlayerInteractEntityEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void place(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e) {
        if (locked(e.getPlayer())) {
            e.setCancelled(true);
            MarketService.tell(e.getPlayer(), "Wait for your inventory transaction to finish.");
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getFinalDamage() > 0) market.combat(p.getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void attack(EntityDamageByEntityEvent e) {
        if (e.getFinalDamage() <= 0) return;
        if (e.getDamager() instanceof Player p) market.combat(p.getUniqueId());
        else if (e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p)
            market.combat(p.getUniqueId());
    }
}
