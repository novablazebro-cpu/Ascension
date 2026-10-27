package com.leadsystems.minecraft.anglertest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Standalone Angler test plugin for Paper 26.3 (copy of the 26.2 test plugin). Copies only the Angler path from Ascension:
 * relic right-click primes a 30 second window, the next cast gets setWaitTime(wait-ticks),
 * and a treasure catch starts a 20 second cooldown. Every cast is timed and logged so a live
 * test shows whether the wait really shrank.
 */
public final class AnglerTestPlugin extends JavaPlugin implements Listener {

    private static final String TITLE_ID = "angler";
    private static final long PRIME_MILLIS = 30_000L;
    private static final long COOLDOWN_MILLIS = 20_000L;

    private final Set<UUID> equipped = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> primedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> deepLureHooks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> castAt = new ConcurrentHashMap<>();
    private NamespacedKey relicKey;
    private int waitTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        waitTicks = Math.max(1, getConfig().getInt("wait-ticks", 60));
        relicKey = new NamespacedKey(this, "angler_relic");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("equip").setExecutor(this::onEquip);
        getLogger().info("Angler test loaded: wait-ticks=" + waitTicks + ". Use /equip angler.");
    }

    private boolean onEquip(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase(TITLE_ID)) {
            player.sendMessage(Component.text("Usage: /equip angler (only Angler is in this test plugin)",
                    NamedTextColor.YELLOW));
            return true;
        }
        equip(player);
        return true;
    }

    private void equip(Player player) {
        equipped.add(player.getUniqueId());
        ItemStack relic = new ItemStack(Material.STICK);
        relic.editMeta(meta -> {
            meta.displayName(Component.text("Ascension Relic (Angler)", NamedTextColor.AQUA));
            meta.getPersistentDataContainer().set(relicKey, PersistentDataType.BYTE, (byte) 1);
        });
        player.getInventory().addItem(relic);
        player.sendMessage(Component.text("Angler equipped. Right-click the relic, then cast within 30 seconds.",
                NamedTextColor.GREEN));
        getLogger().info("[AnglerTest] equipped " + player.getName() + " with angler only");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRelicClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player owner = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !isRelic(item) || !equipped.contains(owner.getUniqueId())) return;
        long now = System.currentTimeMillis();
        long ready = cooldownUntil.getOrDefault(owner.getUniqueId(), 0L);
        if (now < ready) {
            owner.sendMessage(Component.text("Angler on cooldown for " + (ready - now) / 1000 + "s",
                    NamedTextColor.RED));
            return;
        }
        primedUntil.put(owner.getUniqueId(), now + PRIME_MILLIS);
        owner.sendMessage(Component.text("Deep Lure primed for 30 seconds. Cast now.", NamedTextColor.AQUA));
        getLogger().info("[AnglerTest] primed " + owner.getName());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player owner = event.getPlayer();
        UUID id = owner.getUniqueId();
        long now = System.currentTimeMillis();

        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH
                && event.getCaught() instanceof Item caught
                && event.getHook().getUniqueId().equals(deepLureHooks.get(id))
                && equipped.contains(id)) {
            ItemStack treasure = rollTreasure(event.getHook().getLocation());
            caught.setItemStack(treasure);
            deepLureHooks.remove(id);
            cooldownUntil.put(id, now + COOLDOWN_MILLIS);
            owner.sendMessage(Component.text("Treasure caught: " + treasure.getType(), NamedTextColor.GOLD));
            getLogger().info("[AnglerTest] treasure catch for " + owner.getName() + ": " + treasure.getType());
            return;
        }

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            castAt.put(id, now);
            Long until = primedUntil.get(id);
            if (!equipped.contains(id) || until == null || now > until || now < cooldownUntil.getOrDefault(id, 0L)) {
                if (until != null && now > until) primedUntil.remove(id);
                return;
            }
            primedUntil.remove(id);
            deepLureHooks.put(id, event.getHook().getUniqueId());
            event.getHook().setWaitTime(waitTicks);
            getLogger().info("[AnglerTest] cast by " + owner.getName() + " with setWaitTime(" + waitTicks + ")"
                    + " hookState=" + event.getHook().getState());
            owner.sendMessage(Component.text("Wait set to " + waitTicks + " ticks. Timing this cast.",
                    NamedTextColor.AQUA));
            return;
        }

        if (event.getState() == PlayerFishEvent.State.LURED) {
            logTiming(owner, "LURED", now);
            return;
        }

        if (event.getState() == PlayerFishEvent.State.BITE) {
            logTiming(owner, "BITE", now);
        }
    }

    private void logTiming(Player owner, String phase, long now) {
        Long start = castAt.get(owner.getUniqueId());
        if (start == null) return;
        long elapsedMs = now - start;
        long ticks = Math.round(elapsedMs / 50.0);
        getLogger().info("[AnglerTest] " + owner.getName() + " " + phase + " at " + elapsedMs + " ms (~" + ticks + " ticks)");
        owner.sendMessage(Component.text(phase + " after " + elapsedMs + " ms (~" + ticks + " ticks)",
                NamedTextColor.GRAY));
    }

    private boolean isRelic(ItemStack item) {
        return item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .has(relicKey, PersistentDataType.BYTE);
    }

    private static ItemStack rollTreasure(Location location) {
        Collection<ItemStack> loot = LootTables.FISHING_TREASURE.getLootTable().populateLoot(
                ThreadLocalRandom.current(), new LootContext.Builder(location).build());
        return loot.stream().filter(stack -> stack != null && !stack.getType().isAir())
                .findFirst().map(ItemStack::clone).orElseGet(() -> new ItemStack(Material.NAUTILUS_SHELL));
    }
}
