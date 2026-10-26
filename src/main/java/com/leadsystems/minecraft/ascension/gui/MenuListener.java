package com.leadsystems.minecraft.ascension.gui;

import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Chest-menu input: every click and drag inside a menu is cancelled (nothing can be taken out or
 * put in), and a click on an action slot is handled next tick - opening another inventory from
 * inside a click event is unsafe.
 */
public final class MenuListener implements Listener {

    private final MenuService menus;
    private final MainThread main;

    public MenuListener(MenuService menus, MainThread main) {
        this.menus = menus;
        this.main = main;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.viewer())) {
            return;
        }
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) {
            return;
        }
        Menu.Button button = holder.button(raw);
        if (button != null) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            long token = holder.token();
            main.later(1, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) == holder
                        && holder.token() == token) menus.handle(player, token, button);
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { menus.forget(e.getPlayer().getUniqueId()); }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof MenuHolder holder
                && e.getPlayer().getUniqueId().equals(holder.viewer())) {
            menus.closed(holder.viewer(), holder.token());
        }
    }
}
