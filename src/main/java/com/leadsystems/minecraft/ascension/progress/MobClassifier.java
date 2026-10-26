package com.leadsystems.minecraft.ascension.progress;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Illager;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Mob categories used by quests: {@code hostile} (every {@link Enemy} plus configured extras minus
 * exclusions), {@code nether} (configured list), {@code illager} ({@link Illager}).
 */
public final class MobClassifier {

    private final Supplier<ConfigSnapshot> config;

    public MobClassifier(Supplier<ConfigSnapshot> config) {
        this.config = config;
    }

    public boolean isHostile(Entity entity) {
        var s = config.get().settings();
        String type = entity.getType().name();
        if (s.hostileExclude().contains(type)) {
            return false;
        }
        return entity instanceof Enemy || s.hostileExtra().contains(type);
    }

    public Set<String> categories(Entity entity) {
        Set<String> out = new HashSet<>(3);
        if (isHostile(entity)) {
            out.add("hostile");
        }
        if (config.get().settings().netherMobs().contains(entity.getType().name())) {
            out.add("nether");
        }
        if (entity instanceof Illager) {
            out.add("illager");
        }
        return out;
    }
}
