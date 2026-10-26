package com.leadsystems.minecraft.ascension.progress;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.config.Settings;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.domain.xp.XpMath;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Conduit;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Converts Bukkit events into validated {@link Fact}s. All handlers run at MONITOR with
 * {@code ignoreCancelled}, so anything another plugin (protection, anti-cheat) cancelled never
 * counts, and nothing here changes the outcome of an event.
 */
public final class ProgressListener implements Listener {

    private static final Set<org.bukkit.Material> CROPS = Set.of(
            org.bukkit.Material.WHEAT, org.bukkit.Material.CARROTS, org.bukkit.Material.POTATOES,
            org.bukkit.Material.BEETROOTS, org.bukkit.Material.NETHER_WART, org.bukkit.Material.COCOA);
    private static final String PLACED_END_FRAME = "ascension.placed_end_frame";

    /** Recent breaks, keyed because nested plugin-driven breaks can fire before an outer drop event. */
    private record LastBreak(UUID player, boolean placed) {}
    private record BlockKey(UUID world, int x, int y, int z) {}
    private record RecentBrewer(UUID player, long at) {}

    private final ProgressTracker tracker;
    private final PlacedBlockTracker placed;
    private final MobClassifier mobs;
    private final Supplier<ConfigSnapshot> config;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final Map<UUID, Double> travelRemainder = new HashMap<>();
    private final Set<UUID> pendingAnvilChecks = new HashSet<>();
    private final Map<BlockKey, RecentBrewer> brewers = new HashMap<>();
    private final Map<BlockKey, LastBreak> recentBreaks = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, LastBreak> eldest) {
            return size() > 512;
        }
    };

    public ProgressListener(ProgressTracker tracker, PlacedBlockTracker placed, MobClassifier mobs,
                            Supplier<ConfigSnapshot> config, StorageExecutor storage,
                            PlayerRepository repo, MainThread main) {
        this.tracker = tracker;
        this.placed = placed;
        this.mobs = mobs;
        this.config = config;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
    }

    private Settings settings() {
        return config.get().settings();
    }

    // ------------------------------------------------------------------ experience

    /**
     * Orb pickups only. Count cumulative level crossings, including levels re-earned after spending.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExp(PlayerExpChangeEvent e) {
        int amount = e.getAmount();
        if (amount <= 0) {
            return;
        }
        Player p = e.getPlayer();
        int levels = XpMath.levelsGained(p.getLevel(), p.getExp(), amount);
        if (levels > 0) {
            tracker.record(p, new Fact.LevelsGained(levels));
        }
    }

    // ------------------------------------------------------------------ mobs

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead instanceof Player) {
            return; // player kills are validated by the combat system
        }
        Player killer = dead.getKiller();
        if (killer == null) {
            return;
        }
        if (!settings().countSpawnerMobs() && dead.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        tracker.record(killer, new Fact.MobKilled(dead.getType().name(), mobs.categories(dead)));
        obtained(killer, e.getDrops());
    }

    // ------------------------------------------------------------------ movement and blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent || e.getTo() == null
                || !e.getFrom().getWorld().equals(e.getTo().getWorld())) {
            return;
        }
        double distance = e.getFrom().distance(e.getTo());
        if (!Double.isFinite(distance) || distance <= 0 || distance > 16) {
            return;
        }
        UUID id = e.getPlayer().getUniqueId();
        double total = travelRemainder.getOrDefault(id, 0.0) + distance;
        long whole = (long) total;
        travelRemainder.put(id, total - whole);
        if (whole > 0) {
            tracker.record(e.getPlayer(), new Fact.Statistic("travel.blocks", whole));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (tracked(e.getBlockPlaced()) || e.getBlockPlaced().getType() == Material.DRAGON_HEAD
                || e.getBlockPlaced().getType() == Material.DRAGON_WALL_HEAD) {
            placed.mark(e.getBlockPlaced());
        }
        Material type = e.getBlockPlaced().getType();
        if (type == Material.CONDUIT || type == Material.PRISMARINE
                || type == Material.PRISMARINE_BRICKS || type == Material.DARK_PRISMARINE
                || type == Material.SEA_LANTERN) {
            Block placedBlock = e.getBlockPlaced();
            UUID playerId = e.getPlayer().getUniqueId();
            main.later(5, () -> checkConduits(placedBlock, playerId));
        }
    }

    private void checkConduits(Block center, UUID playerId) {
        Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player == null || !tracker.canRecord(player)) return;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block block = center.getRelative(x, y, z);
                    if (block.getType() != Material.CONDUIT
                            || !(block.getState() instanceof Conduit conduit) || !conduit.isActive()) continue;
                    String identity = block.getX() + ":" + block.getY() + ":" + block.getZ();
                    main.onComplete(storage.submit("conduit.credit", c -> repo.creditUniqueLocationAndStat(c,
                            playerId, "conduit", block.getWorld().getUID(), identity,
                            "conduits.activated", 1, System.currentTimeMillis())), (credited, error) -> {
                        Player online = org.bukkit.Bukkit.getPlayer(playerId);
                        if (error == null && Boolean.TRUE.equals(credited) && online == player) {
                            tracker.recordCommitted(online, new Fact.Statistic("conduits.activated", 1));
                        }
                    });
                }
            }
        }
    }

    /** Lava/water forming stone or deepslate is never "natural" (generators). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (settings().trackedPlacedBlocks().contains(e.getNewState().getType().name())) {
            placed.mark(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if ((b.getType() == Material.DRAGON_HEAD || b.getType() == Material.DRAGON_WALL_HEAD)
                && !placed.isPlaced(b)) {
            creditEndCityItem(e.getPlayer(), b.getLocation(), Material.DRAGON_HEAD,
                    b.getX() + ":" + b.getY() + ":" + b.getZ());
        }
        boolean tracked = tracked(b);
        boolean wasPlaced = (tracked || b.getType() == Material.DRAGON_HEAD
                || b.getType() == Material.DRAGON_WALL_HEAD) && placed.isPlaced(b);
        if (wasPlaced) {
            placed.unmark(b);
        }
        Player p = e.getPlayer();
        recentBreaks.put(key(b), new LastBreak(p.getUniqueId(), wasPlaced));
        if (tracked && !wasPlaced) {
            tracker.record(p, new Fact.BlockMined(b.getType().name()));
        }
        if (CROPS.contains(b.getType()) && b.getBlockData() instanceof Ageable crop
                && crop.getAge() == crop.getMaximumAge()) {
            tracker.record(p, new Fact.Statistic("crops.harvested", 1));
        }
    }

    /** Drops of a natural block count as obtained (silk-touch-and-replace farming does not). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Block b = e.getBlock();
        LastBreak lb = recentBreaks.remove(key(b));
        if (lb != null && lb.placed() && lb.player().equals(e.getPlayer().getUniqueId())) {
            return;
        }
        for (Item item : e.getItems()) {
            obtained(e.getPlayer(), item.getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        placed.move(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        placed.move(e.getBlocks(), e.getDirection().getOppositeFace());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            frame.addScoreboardTag(PLACED_END_FRAME);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemFrameChange(io.papermc.paper.event.player.PlayerItemFrameChangeEvent event) {
        ItemFrame frame = event.getItemFrame();
        if (event.getAction() == io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE) {
            frame.addScoreboardTag(PLACED_END_FRAME);
            return;
        }
        if (event.getAction() != io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE
                || frame.getScoreboardTags().contains(PLACED_END_FRAME)) return;
        ItemStack item = event.getItemStack();
        if ((item == null || item.getType() != Material.ELYTRA)
                && frame.getItem().getType() != Material.ELYTRA) return;
        Player player = event.getPlayer();
        Location location = frame.getLocation().clone();
        String identity = frame.getUniqueId().toString();
        main.later(1, () -> {
            if (!frame.isValid() || frame.getItem().getType() != Material.ELYTRA) {
                creditEndCityItem(player, location, Material.ELYTRA, identity);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)
                || frame.getItem().getType() != Material.ELYTRA
                || frame.getScoreboardTags().contains(PLACED_END_FRAME)) return;
        Player player = Attribution.player(event.getDamager());
        if (player == null) return;
        Location location = frame.getLocation().clone();
        String identity = frame.getUniqueId().toString();
        main.later(1, () -> {
            if (!frame.isValid() || frame.getItem().getType() != Material.ELYTRA) {
                creditEndCityItem(player, location, Material.ELYTRA, identity);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        forget(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        forget(e.blockList());
    }

    private void forget(List<Block> blocks) {
        for (Block b : blocks) {
            if (tracked(b)) {
                placed.unmark(b);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        placed.evict(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) {
        placed.evict(e.getWorld());
        UUID worldId = e.getWorld().getUID();
        brewers.keySet().removeIf(key -> key.world().equals(worldId));
        recentBreaks.keySet().removeIf(key -> key.world().equals(worldId));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        travelRemainder.remove(event.getPlayer().getUniqueId());
    }

    private boolean tracked(Block b) {
        return settings().trackedPlacedBlocks().contains(b.getType().name());
    }

    // ------------------------------------------------------------------ items

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent e) {
        String mat = e.getItemType().name();
        int amount = e.getItemAmount();
        tracker.record(e.getPlayer(), new Fact.ItemSmelted(mat, amount));
        if (settings().trackedObtainItems().contains(mat)) {
            tracker.record(e.getPlayer(), new Fact.ItemObtained(mat, amount));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory().getHolder() instanceof BrewingStand stand) {
            rememberBrewer(stand.getBlock(), player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof BrewingStand stand) {
            rememberBrewer(stand.getBlock(), player);
        }
        if (event.getView().getTopInventory().getType() == InventoryType.ANVIL
                && event.getRawSlot() == 2 && event.getCurrentItem() != null
                && !event.getCurrentItem().getType().isAir()
                && event.getAction() != org.bukkit.event.inventory.InventoryAction.NOTHING) {
            UUID playerId = player.getUniqueId();
            if (!pendingAnvilChecks.add(playerId)) return;
            Inventory anvil = event.getView().getTopInventory();
            ItemStack first = copy(anvil.getItem(0));
            ItemStack second = copy(anvil.getItem(1));
            main.later(1, () -> {
                pendingAnvilChecks.remove(playerId);
                Player online = org.bukkit.Bukkit.getPlayer(playerId);
                if (online != null && (consumed(first, anvil.getItem(0))
                        || consumed(second, anvil.getItem(1)))) {
                    tracker.record(online, new Fact.Statistic("anvil.operations", 1));
                }
            });
        }
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.clone();
    }

    private static boolean consumed(ItemStack before, ItemStack after) {
        return before != null && !before.getType().isAir()
                && (after == null || after.getType() != before.getType()
                || after.getAmount() < before.getAmount());
    }

    private void rememberBrewer(Block block, Player player) {
        long now = System.currentTimeMillis();
        if (brewers.size() >= 256) {
            brewers.entrySet().removeIf(entry -> now - entry.getValue().at() > 300_000);
        }
        brewers.put(key(block), new RecentBrewer(player.getUniqueId(), now));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        Player brewer = null;
        List<org.bukkit.entity.HumanEntity> viewers = event.getContents().getViewers();
        if (viewers.size() == 1 && viewers.getFirst() instanceof Player player) {
            brewer = player;
        } else {
            RecentBrewer recent = brewers.get(key(event.getBlock()));
            if (recent != null && System.currentTimeMillis() - recent.at() <= 300_000) {
                brewer = org.bukkit.Bukkit.getPlayer(recent.player());
            }
        }
        if (brewer == null || !brewer.getWorld().equals(event.getBlock().getWorld())
                || brewer.getLocation().distanceSquared(event.getBlock().getLocation()) > 64) {
            return;
        }
        long brewed = event.getResults().stream().filter(item -> item != null && !item.getType().isAir()).count();
        if (brewed > 0) {
            tracker.record(brewer, new Fact.Statistic("potions.brewed", brewed));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item item) {
            tracker.record(e.getPlayer(), new Fact.Statistic("fish.caught", 1));
            obtained(e.getPlayer(), item.getItemStack());
        }
    }

    /** Loot is generated once per container (first open), so re-opening never counts twice. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        if (e.getEntity() instanceof Player p && e.getInventoryHolder() != null) {
            tracker.record(p, new Fact.Statistic("loot.structure_containers", 1));
            String table = e.getLootTable().getKey().getKey();
            if (table.contains("ancient_city")) {
                tracker.record(p, new Fact.Statistic("loot.ancient_city_containers", 1));
            }
            if (table.contains("end_city")) {
                tracker.record(p, new Fact.Statistic("loot.end_city_containers", 1));
                for (ItemStack item : e.getLoot()) {
                    if (item != null && (item.getType() == Material.ELYTRA || item.getType() == Material.DRAGON_HEAD)) {
                        tracker.record(p, new Fact.Statistic("loot.end_city." + item.getType().name(), item.getAmount()));
                    }
                }
                creditEndCity(p, e.getInventoryHolder() instanceof BlockState state ? state : null);
            }
            obtained(p, e.getLoot());
        }
    }

    private void obtained(Player p, List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            obtained(p, s);
        }
    }

    private void obtained(Player p, ItemStack stack) {
        if (stack != null && settings().trackedObtainItems().contains(stack.getType().name())) {
            tracker.record(p, new Fact.ItemObtained(stack.getType().name(), stack.getAmount()));
        }
    }

    private void creditEndCity(Player player, BlockState holder) {
        if (holder == null || !tracker.canRecord(player)) return;
        GeneratedStructure city = holder.getWorld()
                .getStructures(holder.getChunk().getX(), holder.getChunk().getZ(), Structure.END_CITY).stream()
                .filter(structure -> structure.getBoundingBox().contains(holder.getLocation().toVector()))
                .findFirst().orElse(null);
        if (city == null) return;
        var box = city.getBoundingBox();
        String identity = box.getMinX() + ":" + box.getMinY() + ":" + box.getMinZ();
        UUID playerId = player.getUniqueId();
        UUID worldId = holder.getWorld().getUID();
        main.onComplete(storage.submit("end-city.credit", c -> repo.creditUniqueLocationAndStat(c, playerId,
                "end_city", worldId, identity, "loot.end_cities", 1, System.currentTimeMillis())), (credited, error) -> {
            Player online = org.bukkit.Bukkit.getPlayer(playerId);
            if (error == null && Boolean.TRUE.equals(credited) && online == player) {
                tracker.recordCommitted(online, new Fact.Statistic("loot.end_cities", 1));
            }
        });
    }

    private void creditEndCityItem(Player player, Location location, Material material, String identity) {
        if (!tracker.canRecord(player) || endCityAt(location) == null) return;
        UUID playerId = player.getUniqueId();
        String stat = "loot.end_city." + material.name();
        main.onComplete(storage.submit("end-city.item", c -> repo.creditUniqueLocationAndStat(c, playerId,
                stat, location.getWorld().getUID(), identity, stat, 1, System.currentTimeMillis())), (credited, error) -> {
            Player online = org.bukkit.Bukkit.getPlayer(playerId);
            if (error == null && Boolean.TRUE.equals(credited) && online == player) {
                tracker.recordCommitted(online, new Fact.Statistic(stat, 1));
            }
        });
    }

    private static GeneratedStructure endCityAt(Location location) {
        return location.getWorld().getStructures(location.getBlockX() >> 4, location.getBlockZ() >> 4,
                        Structure.END_CITY).stream()
                .filter(structure -> structure.getBoundingBox().contains(location.toVector()))
                .findFirst().orElse(null);
    }

    private static BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}
