package com.leadsystems.minecraft.ascension.gui;

import com.leadsystems.minecraft.ascension.text.Contrast;
import com.leadsystems.minecraft.ascension.text.Messages;
import com.leadsystems.minecraft.ascension.text.Wrap;
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
 * <p>
 * Rows 1-4 hold content. Screens that fit 28 slots get a framed 7-wide area; larger ones (shop
 * pages, the Bedrock fallback title list) use the full width. Partial rows are centered.
 */
public final class JavaMenuRenderer {

    /** Bottom-row navigation slots, center first. */
    private static final int[] NAV = {49, 48, 50, 47, 51};
    private static final int[] CONFIRM = {21, 23};
    private static final int HEADER = 4;
    private static final int CONTENT_ROWS = 4;
    private static final int LORE_WIDTH = 42;

    /** Content slot assignment, derived from the screen alone so redraws and ticks agree. */
    record Layout(int[] body, int[] items, boolean framed) { }

    public void render(Player player, Menu.Screen screen, long token) {
        MenuHolder holder = new MenuHolder(player.getUniqueId(), token, Map.of());
        Inventory inv = Bukkit.createInventory(holder, 54,
                Contrast.on(Contrast.Surface.CHEST_TITLE, screen.title()));
        holder.attach(inv);
        draw(holder, screen, token, System.currentTimeMillis());
        player.openInventory(inv);
    }

    static Layout layout(Menu.Screen screen) {
        boolean confirm = screen.confirm() && screen.buttons().size() == 2;
        int items = confirm ? 0 : screen.entries().size()
                + (int) screen.buttons().stream().filter(button -> !button.nav()).count();
        int body = screen.body().size();
        boolean framed = confirm || body + items <= 7 * CONTENT_ROWS;
        int width = framed ? 7 : 9;
        int firstColumn = framed ? 1 : 0;
        if (confirm) {
            return new Layout(row(1, Math.min(body, width), width, firstColumn), new int[0], true);
        }
        int bodyRows = (body + width - 1) / width;
        int itemRows = (items + width - 1) / width;
        if (bodyRows + itemRows <= CONTENT_ROWS) {
            return new Layout(rows(1, body, width, firstColumn), rows(1 + bodyRows, items, width, firstColumn), framed);
        }
        // Too many for separate rows: fill left to right and drop what does not fit.
        int capacity = width * CONTENT_ROWS;
        int[] all = new int[Math.min(capacity, body + items)];
        for (int i = 0; i < all.length; i++) {
            all[i] = (1 + i / width) * 9 + firstColumn + i % width;
        }
        int shownBody = Math.min(body, all.length);
        return new Layout(java.util.Arrays.copyOfRange(all, 0, shownBody),
                java.util.Arrays.copyOfRange(all, shownBody, all.length), framed);
    }

    private static int[] rows(int firstRow, int count, int width, int firstColumn) {
        int[] slots = new int[count];
        for (int placed = 0, row = firstRow; placed < count; row++) {
            int inRow = Math.min(width, count - placed);
            System.arraycopy(row(row, inRow, width, firstColumn), 0, slots, placed, inRow);
            placed += inRow;
        }
        return slots;
    }

    /** Centers {@code count} slots in one row, leaving a gap between them while they still fit. */
    private static int[] row(int row, int count, int width, int firstColumn) {
        int[] slots = new int[count];
        boolean spaced = count > 1 && count * 2 - 1 <= width;
        int span = spaced ? count * 2 - 1 : count;
        int offset = (width - span) / 2;
        for (int i = 0; i < count; i++) {
            slots[i] = row * 9 + firstColumn + offset + (spaced ? i * 2 : i);
        }
        return slots;
    }

    private void draw(MenuHolder holder, Menu.Screen screen, long token, long now) {
        Map<Integer, Menu.Button> actions = new HashMap<>();
        Map<Integer, ItemStack> items = new HashMap<>();
        Layout layout = layout(screen);

        items.put(HEADER, item(Material.BOOK, screen.title(), List.of()));
        for (int i = 0; i < layout.body().length; i++) {
            Component line = screen.countdowns().containsKey(i) ? screen.countdowns().get(i).at(now) : screen.body().get(i);
            items.put(layout.body()[i], item(Material.PAPER, line, List.of()));
        }

        if (screen.confirm() && screen.buttons().size() == 2) {
            place(items, actions, CONFIRM[0], screen.buttons().get(0), now);
            place(items, actions, CONFIRM[1], screen.buttons().get(1), now);
        } else {
            int next = 0;
            for (Menu.Entry entry : screen.entries()) {
                if (next >= layout.items().length) break;
                items.put(layout.items()[next++], item(entry.icon(), entry.label(), entry.lore()));
            }
            int nav = 0;
            for (Menu.Button button : screen.buttons()) {
                if (button.nav()) {
                    if (nav < NAV.length) {
                        place(items, actions, NAV[nav++], button, now);
                    }
                } else if (next < layout.items().length) {
                    place(items, actions, layout.items()[next++], button, now);
                }
            }
        }

        holder.update(screen, token, actions);
        Inventory inv = holder.getInventory();
        ItemStack frame = frame();
        for (int i = 0; i < 54; i++) {
            ItemStack stack = items.get(i);
            inv.setItem(i, stack != null ? stack : isFrame(i, layout.framed()) ? frame : null);
        }
    }

    private static boolean isFrame(int slot, boolean framed) {
        int row = slot / 9;
        int column = slot % 9;
        return row == 0 || row == 5 || (framed && (column == 0 || column == 8));
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
        int[] bodySlots = layout(screen).body();
        screen.countdowns().forEach((index, countdown) -> {
            if (index >= 0 && index < bodySlots.length)
                inventory.setItem(bodySlots[index], item(Material.PAPER, countdown.at(now), List.of()));
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

    private static ItemStack frame() {
        ItemStack stack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        stack.editMeta(meta -> {
            meta.displayName(Component.text(" "));
            meta.setHideTooltip(true);
        });
        return stack;
    }

    /**
     * Names default to white and lore to gray; every color is kept readable on the dark tooltip, and
     * long lore lines wrap so a description never stretches across the screen.
     */
    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(Messages.upright(Contrast.on(Contrast.Surface.TOOLTIP, name)));
            meta.lore(lore.stream()
                    .map(line -> line.color() == null ? line.color(NamedTextColor.GRAY) : line)
                    .flatMap(line -> Wrap.lines(line, LORE_WIDTH).stream())
                    .map(line -> Messages.upright(Contrast.on(Contrast.Surface.TOOLTIP, line)))
                    .toList());
            meta.addItemFlags(ItemFlag.values());
        });
        return stack;
    }
}
