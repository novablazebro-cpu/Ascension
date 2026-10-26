package com.leadsystems.minecraft.ascension.progress;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers player-placed and fluid-formed blocks of tracked materials, so mining them again never
 * counts (no place-and-mine farming, no cobble/stone generators).
 * <p>
 * Storage: one {@code long[]} per chunk in the chunk's PersistentDataContainer (saved with the
 * chunk, no database traffic), holding chunk-local packed positions (BlockPos64-style packing, see
 * training note 02). Written through on every change; a read cache per loaded chunk uses fastutil
 * primitive sets and is evicted when the chunk unloads. Main-thread only.
 */
public final class PlacedBlockTracker {

    private final NamespacedKey key;
    private final Map<UUID, Long2ObjectOpenHashMap<LongOpenHashSet>> cache = new HashMap<>();

    public PlacedBlockTracker(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "placed");
    }

    /** Chunk-local packing: 12 bits of (y + 2048), 4 bits x, 4 bits z. */
    public static long pack(int x, int y, int z) {
        return ((long) (y + 2048) << 8) | ((long) (x & 15) << 4) | (z & 15);
    }

    public boolean isPlaced(Block block) {
        return set(block).contains(pack(block.getX(), block.getY(), block.getZ()));
    }

    public void mark(Block block) {
        LongOpenHashSet s = set(block);
        if (s.add(pack(block.getX(), block.getY(), block.getZ()))) {
            write(block, s);
        }
    }

    public void unmark(Block block) {
        LongOpenHashSet s = set(block);
        if (s.remove(pack(block.getX(), block.getY(), block.getZ()))) {
            write(block, s);
        }
    }

    /**
     * Moves marks with blocks pushed or pulled by a piston. The event direction is the movement
     * direction for extension. Retraction callers pass the opposite of the piston's facing.
     */
    public void move(List<Block> moved, BlockFace direction) {
        List<Block> placed = new ArrayList<>();
        for (Block b : moved) {
            if (isPlaced(b)) {
                placed.add(b);
            }
        }
        for (Block b : placed) {
            unmark(b);
        }
        for (Block b : placed) {
            mark(b.getRelative(direction));
        }
    }

    public void evict(Chunk chunk) {
        Long2ObjectOpenHashMap<LongOpenHashSet> world = cache.get(chunk.getWorld().getUID());
        if (world != null) {
            world.remove(chunk.getChunkKey());
        }
    }

    public void evict(World world) {
        cache.remove(world.getUID());
    }

    private LongOpenHashSet set(Block block) {
        World world = block.getWorld();
        int cx = block.getX() >> 4;
        int cz = block.getZ() >> 4;
        long chunkKey = Chunk.getChunkKey(cx, cz);
        Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = cache.computeIfAbsent(world.getUID(),
                k -> new Long2ObjectOpenHashMap<>());
        LongOpenHashSet s = byChunk.get(chunkKey);
        if (s == null) {
            long[] stored = world.getChunkAt(cx, cz).getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
            s = stored == null ? new LongOpenHashSet() : new LongOpenHashSet(stored);
            byChunk.put(chunkKey, s);
        }
        return s;
    }

    private void write(Block block, LongOpenHashSet s) {
        PersistentDataContainer pdc = block.getWorld().getChunkAt(block.getX() >> 4, block.getZ() >> 4)
                .getPersistentDataContainer();
        if (s.isEmpty()) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.LONG_ARRAY, s.toLongArray());
        }
    }
}
