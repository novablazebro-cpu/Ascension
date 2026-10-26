package com.leadsystems.minecraft.ascension.gui;

import com.leadsystems.minecraft.ascension.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link Menu.Screen} as a 54-slot chest menu. Every slot's action is recorded in the
 * {@link MenuHolder}; the items themselves are decoration and never trusted.
 */
public final class JavaMenuRenderer {

    /** Four content rows; the top row is the header and the bottom row is navigation. */
    private static final int[] CONTENT = java.util.stream.IntStream.range(9, 45).toArray();
    /** Bottom-row navigation slots, center first. */
    private static final int[] NAV = {49, 48, 50, 47, 51};
    private static final int HEADER = 4;

    public void render(Player player, Menu.Screen screen, long token) {
        MenuHolder holder = new MenuHolder(player.getUniqueId(), token, Map.of());
        Inventory inv = Bukkit.createInventory(holder, 54, Messages.withoutColor(screen.title()));
        holder.attach(inv);
        draw(holder, screen, token, System.currentTimeMillis());
        player.openInventory(inv);
    }

    private void draw(MenuHolder holder, Menu.Screen screen, long token, long now) {
        Map<Integer, Menu.Button> actions = new HashMap<>();
        Map<Integer, ItemStack> items = new HashMap<>();

        items.put(HEADER, item(Material.BOOK, screen.title(), List.of()));
        int next = 0;
        for (int i = 0; i < screen.body().size(); i++) {
            if (next >= CONTENT.length) break;
            Component line = screen.countdowns().containsKey(i) ? screen.countdowns().get(i).at(now) : screen.body().get(i);
            items.put(CONTENT[next++], item(Material.PAPER, line, List.of()));
        }

        if (screen.confirm() && screen.buttons().size() == 2) {
            place(items, actions, 21, screen.buttons().get(0), now);
            place(items, actions, 23, screen.buttons().get(1), now);
        } else {
            for (Menu.Entry entry : screen.entries()) {
                if (next >= CONTENT.length) {
                    break;
                }
                items.put(CONTENT[next++], item(entry.icon(), entry.label(), entry.lore()));
            }
            int nav = 0;
            for (Menu.Button button : screen.buttons()) {
                if (button.nav()) {
                    if (nav < NAV.length) {
                        place(items, actions, NAV[nav++], button, now);
                    }
                } else if (next < CONTENT.length) {
                    place(items, actions, CONTENT[next++], button, now);
                }
            }
        }

        holder.update(screen, token, actions);
        Inventory inv = holder.getInventory();
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, items.getOrDefault(i, filler));
        }
    }

    private static void place(Map<Integer, ItemStack> items, Map<Integer, Menu.Button> actions, int slot, Menu.Button b, long now) {
        items.put(slot, item(b.icon(), b.label(), lore(b, now)));
        actions.put(slot, b);
    }

    private static List<Component> lore(Menu.Button button, long now) {
        var lines = new java.util.ArrayList<>(button.lore());
        button.countdowns().forEach((index, countdown) -> {
            if (index >= 0 && index < lines.size()) lines.set(index, countdown.at(now));
        });
        return lines;
    }

    /** Called once per second on the main thread. Only the current viewer's shop is touched. */
    public void tick(Player player, long currentToken, Runnable refreshStock) {
        tick(player, currentToken, refreshStock, System.currentTimeMillis());
    }

    void tick(Player player, long currentToken, Runnable refreshStock, long now) {
        MenuHolder holder = owned(player, currentToken);
        if (holder == null || !isShopPage(holder.screenId())) return;
        Menu.Screen screen = holder.screen();
        Inventory inventory = holder.getInventory();
        screen.countdowns().forEach((index, countdown) -> {
            if (index >= 0 && index < screen.body().size() && index < CONTENT.length)
                inventory.setItem(CONTENT[index], item(Material.PAPER, countdown.at(now), List.of()));
        });
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            Menu.Button button = holder.button(slot);
            if (button != null && !button.countdowns().isEmpty())
                inventory.setItem(slot, item(button.icon(), button.label(), lore(button, now)));
        }
        boolean due = screen.countdowns().values().stream().anyMatch(timer -> timer.deadline() <= now)
                || screen.buttons().stream().flatMap(button -> button.countdowns().values().stream())
                .anyMatch(timer -> timer.deadline() <= now);
        if (due && holder.requestRefresh(now)) refreshStock.run();
    }

    public boolean owns(Player player, long token) { return owned(player, token) != null; }

    public String shopPage(Player player, long token) {
        MenuHolder holder = owned(player, token);
        return holder != null && isShopPage(holder.screenId()) ? holder.screenId() : null;
    }

    private static boolean isShopPage(String id) {
        return id.equals("market_shop") || id.startsWith("market_shop:");
    }

    /** Accept fresh stock and its newly issued action token, without closing/reopening the chest. */
    public boolean refresh(Player player, long expectedToken, Menu.Screen screen, long freshToken) {
        MenuHolder holder = owned(player, expectedToken);
        if (holder == null || !isShopPage(holder.screenId()) || !holder.screenId().equals(screen.id())) return false;
        draw(holder, screen, freshToken, System.currentTimeMillis());
        return true;
    }

    public void refreshFailed(Player player, long expectedToken) {
        MenuHolder holder = owned(player, expectedToken);
        if (holder != null) holder.refreshFailed(System.currentTimeMillis());
    }

    public void refreshFailedIfTimedOut(Player player, long expectedToken) {
        MenuHolder holder = owned(player, expectedToken);
        if (holder != null) holder.refreshFailedIfTimedOut(System.currentTimeMillis());
    }

    private MenuHolder owned(Player player, long token) {
        if (!player.isOnline()) return null;
        var view = player.getOpenInventory();
        Inventory top = view == null ? null : view.getTopInventory();
        if (top == null || !(top.getHolder() instanceof MenuHolder holder)
                || !holder.viewer().equals(player.getUniqueId()) || holder.token() != token) return null;
        return holder;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(Messages.upright(Messages.withoutColor(name).color(NamedTextColor.WHITE)));
            meta.lore(lore.stream().map(line -> Messages.upright(Messages.withoutColor(line).color(NamedTextColor.WHITE))).toList());
            meta.addItemFlags(ItemFlag.values());
        });
        return stack;
    }
}
