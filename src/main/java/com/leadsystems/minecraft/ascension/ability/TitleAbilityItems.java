package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.book.ClickRules;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityResolver;
import com.leadsystems.minecraft.ascension.market.MarketService;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityRegistry;
import com.leadsystems.minecraft.ascension.gui.MenuHolder;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.text.Contrast;
import com.leadsystems.minecraft.ascension.text.Messages;
import com.leadsystems.minecraft.ascension.text.Wrap;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Issues and protects the owner-bound item used to activate equipped title abilities. */
public final class TitleAbilityItems implements Listener, SessionHook {

    /** Schema 2 introduced the configurable Ascension Relic in place of the legacy stick. */
    private static final int SCHEMA = 2;

    private final SessionManager sessions;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final BiConsumer<Player, PlayerSession> activator;
    private final MainThread main;
    private final NamespacedKey marker;
    private final NamespacedKey schema;
    private final NamespacedKey owner;
    private final NamespacedKey title;
    private final Set<UUID> pendingNotified = new HashSet<>();
    private final TitleAbilityResolver levelResolver;
    private MarketService market;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, Integer> lastUseTick = new HashMap<>();

    public TitleAbilityItems(Plugin plugin, SessionManager sessions, Supplier<ConfigSnapshot> config,
                             Messages messages, BiConsumer<Player, PlayerSession> activator, MainThread main) {
        this.sessions = sessions;
        this.config = config;
        this.messages = messages;
        this.activator = activator;
        this.main = main;
        this.levelResolver = new TitleAbilityResolver(config);
        marker = new NamespacedKey(plugin, "title_ability_item");
        schema = new NamespacedKey(plugin, "title_ability_schema");
        owner = new NamespacedKey(plugin, "title_ability_owner");
        title = new NamespacedKey(plugin, "title_ability_title");
    }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        reconcile(player, session, reload);
    }

    @Override
    public void onUnload(Player player, PlayerSession session) {
        if (market == null || !market.inventoryLocked(session.uuid())) {
            removeAll(player.getInventory());
            clearCursorAndTop(player);
        }
        pendingNotified.remove(player.getUniqueId());
        lastUse.remove(player.getUniqueId());
        lastUseTick.remove(player.getUniqueId());
    }

    public void setMarket(MarketService market) { this.market = market; }

    public void refreshLevel(Player player, PlayerSession session) {
        reconcile(player, session, false);
    }

    public boolean isActivator(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    public boolean isValidFor(ItemStack item, PlayerSession session) {
        if (session == null || !TitleAccess.canUse(session, config.get(), session.equippedTitle())
                || !isActivator(item) || item.getType() != relicMaterial() || item.getAmount() != 1
                || session.equippedTitle() == null || !TitleAbilityRegistry.isManual(session.equippedTitle())) {
            return false;
        }
        Byte storedMarker = item.getPersistentDataContainer().get(marker, PersistentDataType.BYTE);
        String storedOwner = item.getPersistentDataContainer().get(owner, PersistentDataType.STRING);
        String storedTitle = item.getPersistentDataContainer().get(title, PersistentDataType.STRING);
        Integer storedSchema = item.getPersistentDataContainer().get(schema, PersistentDataType.INTEGER);
        return Byte.valueOf((byte) 1).equals(storedMarker)
                && session.uuid().toString().equals(storedOwner)
                && session.equippedTitle().equals(storedTitle)
                && Integer.valueOf(SCHEMA).equals(storedSchema);
    }

    public void reconcileOnline() {
        for (PlayerSession session : sessions.all()) {
            Player player = org.bukkit.Bukkit.getPlayer(session.uuid());
            if (player != null && session.usable()) {
                reconcile(player, session, false);
            }
        }
    }

    private void reconcile(Player player, PlayerSession session, boolean refresh) {
        if (market != null && market.inventoryLocked(session.uuid())) {
            market.afterInventoryReady(session.uuid(), "relic", () -> {
                if (player.isOnline() && sessions.get(player) == session) reconcile(player, session, refresh);
            });
            return;
        }
        boolean kept = false;
        clearCursorAndTop(player);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isActivator(item)) {
                continue;
            }
            if (!refresh && !kept && isValidFor(item, session)) {
                kept = true;
                TitleDefinition effective = levelResolver.resolved(session, session.equippedTitle());
                if (effective != null) {
                    ItemStack styled = create(session, effective);
                    var before = item.getItemMeta();
                    var after = styled.getItemMeta();
                    if (!java.util.Objects.equals(before.displayName(), after.displayName())
                            || !java.util.Objects.equals(before.lore(), after.lore())
                            || !before.hasEnchantmentGlintOverride()
                            || before.getEnchantmentGlintOverride() != after.getEnchantmentGlintOverride()) {
                        player.getInventory().setItem(slot, styled);
                    }
                }
            } else {
                player.getInventory().setItem(slot, null);
            }
        }
        if (!TitleAccess.canUse(session, config.get(), session.equippedTitle())
                || !TitleAbilityRegistry.isManual(session.equippedTitle())) {
            pendingNotified.remove(player.getUniqueId());
            return;
        }
        if (kept) {
            pendingNotified.remove(player.getUniqueId());
            return;
        }
        TitleDefinition definition = levelResolver.resolved(session, session.equippedTitle());
        if (definition == null) {
            return;
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(create(session, definition));
        if (leftovers.isEmpty()) {
            pendingNotified.remove(player.getUniqueId());
            messages.send(player, "ability-item-received", Messages.ph("ability", definition.abilityName()));
        } else if (pendingNotified.add(player.getUniqueId())) {
            messages.send(player, "ability-item-pending");
        }
    }

    private void clearCursorAndTop(Player player) {
        InventoryView open = player.getOpenInventory();
        ItemStack cursor = open.getCursor();
        if (isActivator(cursor)) {
            open.setCursor(null);
        }
        Inventory top = open.getTopInventory();
        if (top != null && top != player.getInventory() && top.getType() != InventoryType.CRAFTING
                && top.getType() != InventoryType.PLAYER) {
            removeAll(top);
        }
    }

    private ItemStack create(PlayerSession session, TitleDefinition definition) {
        ItemStack item = new ItemStack(relicMaterial());
        item.editMeta(meta -> {
            TextColor titleColor = TextColor.fromHexString(definition.color());
            meta.displayName(tooltip(Component.text(config.get().settings().activationItemDisplayName(),
                    titleColor == null ? NamedTextColor.WHITE : titleColor)));
            List<Component> lore = new ArrayList<>();
            lore.add(tooltip(Component.text(definition.name() + " · Level " + levelResolver.level(session, definition.id()),
                    NamedTextColor.AQUA)));
            lore.add(tooltip(Component.text(definition.abilityName(), NamedTextColor.GOLD)));
            for (Component line : Wrap.lines(Component.text(definition.abilityDescription(), NamedTextColor.GRAY), 42)) {
                lore.add(tooltip(line));
            }
            lore.add(tooltip(Component.text("Right-click to activate.", NamedTextColor.GREEN)));
            lore.add(tooltip(Component.text("Owner-bound and cannot be stored or dropped.", NamedTextColor.DARK_GRAY)));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            meta.setEnchantmentGlintOverride(config.get().settings().activationItemGlint());
            var pdc = meta.getPersistentDataContainer();
            pdc.set(marker, PersistentDataType.BYTE, (byte) 1);
            pdc.set(schema, PersistentDataType.INTEGER, SCHEMA);
            pdc.set(owner, PersistentDataType.STRING, session.uuid().toString());
            pdc.set(title, PersistentDataType.STRING, definition.id());
        });
        return item;
    }

    private static Component tooltip(Component line) {
        return Contrast.on(Contrast.Surface.TOOLTIP, line).decoration(TextDecoration.ITALIC, false);
    }

    private Material relicMaterial() {
        Material material = Material.matchMaterial(config.get().settings().activationItemMaterial());
        // ConfigLoader rejects invalid values before this runtime receives a snapshot.
        return material == null ? Material.AMETHYST_SHARD : material;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || !isActivator(event.getItem())) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && isActivator(player.getInventory().getItemInMainHand())) {
            return;
        }
        activate(player, event.getItem());
    }

    private void activate(Player player, ItemStack item) {
        PlayerSession session = sessions.get(player);
        if (session == null || !session.usable() || !isValidFor(item, session)) {
            if (session != null && session.usable()) {
                reconcile(player, session, false);
            }
            return;
        }
        long now = System.currentTimeMillis();
        int tick = org.bukkit.Bukkit.getCurrentTick();
        UUID playerId = player.getUniqueId();
        if (lastUseTick.getOrDefault(playerId, Integer.MIN_VALUE) == tick
                || now - lastUse.getOrDefault(playerId, 0L) < 200L) {
            return;
        }
        lastUse.put(playerId, now);
        lastUseTick.put(playerId, tick);
        activator.accept(player, session);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isActivator(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "ability-item-cannot-move");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (!isActivator(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && isActivator(player.getInventory().getItemInMainHand())) {
            return;
        }
        activate(player, item);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (isActivator(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemFrame(PlayerItemFrameChangeEvent event) {
        if (event.getAction() == PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE
                && isActivator(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        if (top.getHolder(false) instanceof MenuHolder) {
            return;
        }
        if (event instanceof InventoryCreativeEvent creative) {
            if (isActivator(creative.getCursor()) || isActivator(creative.getCurrentItem())) {
                schedule(player);
            }
            return;
        }
        int hotbar = event.getHotbarButton();
        ClickRules.ClickFacts facts = new ClickRules.ClickFacts(
                top.getType() != InventoryType.CRAFTING,
                event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize(),
                event.getSlotType() == InventoryType.SlotType.OUTSIDE,
                event.getAction(), event.getClick(),
                isActivator(event.getCurrentItem()), isActivator(event.getCursor()),
                hotbar >= 0 && isActivator(player.getInventory().getItem(hotbar)),
                isActivator(player.getInventory().getItemInOffHand()));
        if (ClickRules.deny(facts)) {
            event.setCancelled(true);
            messages.send(player, "ability-item-cannot-move");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!isActivator(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                messages.send(player, "ability-item-cannot-move");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isActivator);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        if (isActivator(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (isActivator(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (isActivator(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        schedule(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            schedule(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        if (isActivator(event.getOldItemStack()) || isActivator(event.getNewItemStack())) {
            schedule(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || top.getHolder(false) instanceof MenuHolder) {
            return;
        }
        removeAll(top);
        if (event.getPlayer() instanceof Player player) {
            schedule(player);
        }
    }

    private void schedule(Player player) {
        main.later(1, () -> {
            PlayerSession session = sessions.get(player);
            if (session != null && session.usable()) {
                reconcile(player, session, false);
            }
        });
    }

    private int removeAll(Inventory inventory) {
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isActivator(contents[slot])) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }
}
