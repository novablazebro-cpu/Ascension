package com.leadsystems.minecraft.ascension.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;

/**
 * Marks an inventory as an Ascension menu and carries the server-side snapshot of what each slot
 * does. Clicks are resolved against this snapshot (never against the item the client shows), and
 * only while {@link #token()} is the viewer's current one-time menu token.
 */
public final class MenuHolder implements InventoryHolder {

    private final UUID viewer;
    private long token;
    private Map<Integer, Menu.Button> buttons;
    private Menu.Screen screen;
    private boolean refreshing;
    private long refreshRequestedAt;
    private long retryAt;
    private Inventory inventory;

    public MenuHolder(UUID viewer, long token, Map<Integer, Menu.Button> buttons) {
        this.viewer = viewer;
        this.token = token;
        this.buttons = Map.copyOf(buttons);
    }

    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    void update(Menu.Screen screen, long token, Map<Integer, Menu.Button> buttons) {
        this.screen = screen;
        this.token = token;
        this.buttons = Map.copyOf(buttons);
        refreshing = false;
        refreshRequestedAt = 0;
        retryAt = System.currentTimeMillis() + 5_000;
    }

    public String screenId() { return screen == null ? "" : screen.id(); }
    Menu.Screen screen() { return screen; }
    boolean requestRefresh(long now) {
        if (refreshing || now < retryAt) return false;
        refreshing = true;
        refreshRequestedAt = now;
        return true;
    }
    void refreshFailed(long now) { refreshing = false; refreshRequestedAt = 0; retryAt = now + 5_000; }
    void refreshFailedIfTimedOut(long now) {
        if (refreshing && now - refreshRequestedAt >= 5_000) refreshFailed(now);
    }

    public UUID viewer() {
        return viewer;
    }

    public long token() {
        return token;
    }

    /** The button at a top-inventory slot, or null. */
    public Menu.Button button(int slot) {
        return buttons.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
