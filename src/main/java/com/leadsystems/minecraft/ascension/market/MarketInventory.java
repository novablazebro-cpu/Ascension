package com.leadsystems.minecraft.ascension.market;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Arrays;
import java.util.Set;
import java.nio.ByteBuffer;

/** Pure inventory planning; no live inventory changes until the journal has committed. */
public final class MarketInventory {
    public static final Set<String> CURRENCIES = Set.of("EMERALD_BLOCK", "DIAMOND_BLOCK", "NETHERITE_INGOT");
    public static final int MAX_PRICE = 4096;

    private MarketInventory() {}

    public static ItemStack[] copy(ItemStack[] contents) {
        return Arrays.stream(contents).map(i -> i == null || i.getType().isAir() ? null : i.clone())
                .toArray(ItemStack[]::new);
    }

    /** Currency must be ordinary vanilla items: named/relic items never count as money. */
    public static ItemStack[] debit(ItemStack[] before, String currency, int amount) {
        if (!CURRENCIES.contains(currency) || amount < 1 || amount > MAX_PRICE) return null;
        ItemStack money = new ItemStack(Material.valueOf(currency));
        ItemStack[] after = copy(before);
        int remaining = amount;
        for (int slot = 0; slot < after.length && remaining > 0; slot++) {
            ItemStack stack = after[slot];
            if (stack == null || !stack.isSimilar(money)) continue;
            var meta = stack.getItemMeta();
            if (meta != null && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                    || !meta.getPersistentDataContainer().isEmpty())) continue;
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() == 0) after[slot] = null;
            remaining -= take;
        }
        return remaining == 0 ? after : null;
    }

    /** All-or-nothing capacity planning, including payments larger than one stack. */
    public static ItemStack[] credit(ItemStack[] before, ItemStack incoming) {
        if (incoming == null || incoming.getType().isAir() || incoming.getAmount() < 1) return null;
        ItemStack[] after = copy(before);
        int remaining = incoming.getAmount();
        int max = incoming.getMaxStackSize();
        for (ItemStack stack : after) {
            if (stack == null || !stack.isSimilar(incoming)) continue;
            int take = Math.min(remaining, Math.max(0, max - stack.getAmount()));
            stack.setAmount(stack.getAmount() + take);
            remaining -= take;
        }
        for (int slot = 0; slot < after.length && remaining > 0; slot++) {
            if (after[slot] != null) continue;
            ItemStack part = incoming.clone();
            int take = Math.min(max, remaining);
            part.setAmount(take);
            after[slot] = part;
            remaining -= take;
        }
        return remaining == 0 ? after : null;
    }

    public static boolean same(ItemStack[] a, ItemStack[] b) {
        return Arrays.equals(copy(a), copy(b));
    }

    public static void apply(PlayerInventory inventory, ItemStack[] contents) {
        if (contents.length != inventory.getStorageContents().length) throw new IllegalArgumentException("Storage size changed");
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            inventory.setItem(slot, stack == null ? null : stack.clone());
        }
    }

    public static int capacity(ItemStack[] inventory, ItemStack item) {
        int result = 0;
        for (ItemStack stack : inventory) {
            if (stack == null || stack.getType().isAir()) result += item.getMaxStackSize();
            else if (stack.isSimilar(item)) result += Math.max(0, item.getMaxStackSize() - stack.getAmount());
        }
        return result;
    }

    /** Injectable for tests; production stores Paper NBT with its built-in data version. */
    public interface Codec {
        byte[] item(ItemStack stack);
        ItemStack item(byte[] bytes);
        byte[] inventory(ItemStack[] stacks);
        ItemStack[] inventory(byte[] bytes);
    }

    public static final Codec PAPER = new Codec() {
        public byte[] item(ItemStack stack) {
            // Mail payments can exceed a vanilla stack. Store a valid one-item NBT prototype
            // and a separate count, then split it into legal stacks when collecting.
            ItemStack prototype = stack.clone();
            prototype.setAmount(1);
            byte[] nbt = prototype.serializeAsBytes();
            return ByteBuffer.allocate(8 + nbt.length).putInt(0x4153434d)
                    .putInt(stack.getAmount()).put(nbt).array();
        }
        public ItemStack item(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (bytes.length < 8 || buffer.getInt() != 0x4153434d) return ItemStack.deserializeBytes(bytes);
            int amount = buffer.getInt();
            if (amount < 1 || amount > MAX_PRICE) throw new IllegalArgumentException("Invalid mail quantity");
            byte[] nbt = new byte[buffer.remaining()];
            buffer.get(nbt);
            ItemStack stack = ItemStack.deserializeBytes(nbt);
            stack.setAmount(amount);
            return stack;
        }
        public byte[] inventory(ItemStack[] stacks) { return ItemStack.serializeItemsAsBytes(Arrays.asList(stacks)); }
        public ItemStack[] inventory(byte[] bytes) { return ItemStack.deserializeItemsFromBytes(bytes); }
    };
}
