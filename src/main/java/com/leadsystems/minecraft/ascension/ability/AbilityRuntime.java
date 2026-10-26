package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player, in-memory ability state (never persisted; cooldowns live in the session's
 * {@code CooldownBook}). Main-thread only.
 */
public final class AbilityRuntime {

    /**
     * An effect Ascension applied, remembered so only our own effects are removed later.
     *
     * @param endTick server tick when a finite effect ends; ignored if infinite
     */
    public record AppliedEffect(int amplifier, boolean infinite, int endTick) {}

    private int generation;
    private List<RankAbility> active = List.of();
    private final Map<PotionEffectType, AppliedEffect> applied = new HashMap<>();
    private long lastCombatAtMs;
    private long lastSneakAtMs;
    private boolean healthBonusApplied;

    /** Incremented whenever abilities are re-applied; stale pulse entries compare against it. */
    public int generation() {
        return generation;
    }

    public int nextGeneration() {
        return ++generation;
    }

    public List<RankAbility> active() {
        return active;
    }

    public void setActive(List<RankAbility> packages) {
        this.active = List.copyOf(packages);
    }

    public Map<PotionEffectType, AppliedEffect> applied() {
        return applied;
    }

    public long lastCombatAtMs() {
        return lastCombatAtMs;
    }

    public void setLastCombatAtMs(long ms) {
        this.lastCombatAtMs = ms;
    }

    public long lastSneakAtMs() {
        return lastSneakAtMs;
    }

    public void setLastSneakAtMs(long ms) {
        this.lastSneakAtMs = ms;
    }

    /** True while our transient max-health modifier is on the player. */
    public boolean healthBonusApplied() {
        return healthBonusApplied;
    }

    public void setHealthBonusApplied(boolean applied) {
        this.healthBonusApplied = applied;
    }
}
