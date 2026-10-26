package com.leadsystems.minecraft.ascension.domain.title;

import com.leadsystems.minecraft.ascension.domain.progress.Fact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable title progression rules. Upgrade deltas are relative to effective level one. */
public record TitleLevels(long level2Xp, long level3Xp, RewardWeights rewards,
                          Map<String, Map<Integer, Upgrade>> profiles) {
    public static final Set<String> BOSSES = Set.of("WARDEN", "WITHER", "ELDER_GUARDIAN", "RAVAGER");
    private static final TitleLevels DEFAULTS = createDefaults();

    public TitleLevels {
        if (level2Xp <= 0 || level3Xp <= level2Xp) throw new IllegalArgumentException("Invalid title XP thresholds");
        java.util.Objects.requireNonNull(rewards, "rewards");
        Map<String, Map<Integer, Upgrade>> copy = new LinkedHashMap<>();
        profiles.forEach((id, levels) -> copy.put(id, Map.copyOf(levels)));
        profiles = Map.copyOf(copy);
    }

    public record Upgrade(int cooldownDeltaSeconds, Map<String, Double> settingsDeltas) {
        public Upgrade { settingsDeltas = Map.copyOf(settingsDeltas); }
    }

    public record RewardWeights(long naturalBlock, long smeltedItem, long hostileMob, long fishingCatch,
                                long playerKill, long hunt, long raid, long boss) {
        public RewardWeights {
            for (long value : new long[]{naturalBlock, smeltedItem, hostileMob, fishingCatch, playerKill, hunt, raid, boss}) {
                if (value < 0 || value > 1_000_000) throw new IllegalArgumentException("Invalid XP reward");
            }
        }
    }

    public int level(long xp) { return xp >= level3Xp ? 3 : xp >= level2Xp ? 2 : 1; }
    public RewardWeights rewardWeights() { return rewards; }

    /** Consumes only validated facts; bosses are excluded from the generic hostile-mob reward. */
    public long reward(Fact fact) {
        if (fact instanceof Fact.BlockMined) return rewards.naturalBlock();
        if (fact instanceof Fact.ItemSmelted f) return multiply(rewards.smeltedItem(), f.amount());
        if (fact instanceof Fact.MobKilled f) return !BOSSES.contains(f.entityType()) && f.categories().contains("hostile") ? rewards.hostileMob() : 0;
        if (fact instanceof Fact.PlayerKilled) return rewards.playerKill();
        if (fact instanceof Fact.HuntCompleted) return rewards.hunt();
        if (fact instanceof Fact.RaidCompleted) return rewards.raid();
        if (fact instanceof Fact.BossDefeated f) return BOSSES.contains(f.boss()) ? rewards.boss() : 0;
        if (fact instanceof Fact.Statistic f && "fish.caught".equals(f.key())) return multiply(rewards.fishingCatch(), f.amount());
        return 0;
    }

    private static long multiply(long weight, long amount) {
        return amount <= 0 || weight == 0 ? 0 : amount > Long.MAX_VALUE / weight ? Long.MAX_VALUE : weight * amount;
    }

    public static TitleLevels defaults() { return DEFAULTS; }

    public boolean stockSettingDelta(String id, int level, String key) {
        Upgrade configured = profiles.getOrDefault(id, Map.of()).get(level);
        Upgrade stock = DEFAULTS.profiles.getOrDefault(id, Map.of()).get(level);
        return configured != null && stock != null && stock.settingsDeltas.containsKey(key)
                && java.util.Objects.equals(configured.settingsDeltas.get(key), stock.settingsDeltas.get(key));
    }

    public boolean stockCooldownDelta(String id, int level) {
        Upgrade configured = profiles.getOrDefault(id, Map.of()).get(level);
        Upgrade stock = DEFAULTS.profiles.getOrDefault(id, Map.of()).get(level);
        return configured != null && stock != null && configured.cooldownDeltaSeconds == stock.cooldownDeltaSeconds;
    }

    private static TitleLevels createDefaults() {
        Map<String, Map<Integer, Upgrade>> p = new LinkedHashMap<>();
        profile(p, "ascendant", 0, 0, "buff-duration-seconds", 6, 12);
        profile(p, "conqueror", 0, 0, "radius", 2, 4, "duration-seconds", 2, 4);
        profile(p, "stormbringer", 0, 0, "range", 10, 10, "all-targets", 0, 1);
        profile(p, "tempest_herald", 0, 0, "range", 4, 8, "extra-chain-targets", 1, 2);
        profile(p, "voidwalker", 0, 0, "aim-range", 8, 16, "charges", 1, 2);
        profile(p, "bloodlord", 0, 0, "lifesteal-ratio", .05, .10, "max-heal-health-points", .5, 1);
        profile(p, "chronomancer", -10, -20, "window-seconds", 1, 2);
        profile(p, "titan", 0, 0, "slam-radius", 1, 2);
        profile(p, "undying", -10800, -21600);
        profile(p, "end_city_conqueror", 0, 0, "slow-falling-seconds", 1, 2, "landing-speed-seconds", 1, 2);
        profile(p, "wardens_bane", 0, 0, "damage-multiplier", -.04, -.08, "heavy-hit-multiplier", -.05, -.10);
        profile(p, "frostbound", 0, 0, "radius", 1, 2, "slow-seconds", 1, 2);
        profile(p, "witherbound", -5, -10, "radius", 1, 2);
        profile(p, "soul_reaper", 0, 0, "maximum-souls", 5, 10, "heal-health-points", 1, 2);
        profile(p, "bounty_hunter", 0, 0, "range", 10, 20, "duration-seconds", 2, 4);
        profile(p, "raidbreaker", 0, 0, "radius", 1, 2, "raid-strength-seconds", 2, 4);
        profile(p, "tideborn", 0, 0, "horizontal-force", .2, .4);
        profile(p, "beastbane", 0, 0, "health-threshold-health-points", 1, 2);
        profile(p, "emberforge", 0, 0, "duration-seconds", 2, 4, "bonus-damage-health-points", 1, 2);
        profile(p, "alchemist", 0, 0, "duration-multiplier", .15, .30, "splash-radius", 1, 2);
        profile(p, "treasure_whisperer", 0, 0, "range", 4, 8, "bonus-chance", .025, .05);
        profile(p, "berserker", 0, 0, "maximum-bonus-damage", 1, 2, "decay-seconds", 2, 4);
        profile(p, "colossus", 0, 0, "bonus-max-health-points", 2, 4);
        profile(p, "ashborn", 0, 0, "radius", 1, 2, "burn-seconds", 1, 2);
        profile(p, "trailblazer", -3, -5, "horizontal-force", .2, .4);
        profile(p, "angler", -2, -4, "wait-ticks", -10, -20);
        profile(p, "mycelial_drifter", 0, 0, "radius", 1, 2, "heal-health-points", 1, 2);
        profile(p, "huntsman", 0, 0, "duration-seconds", 2, 4, "combat-damage-multiplier", .1, .2);
        profile(p, "stonebreaker", 0, 0, "maximum-extra-blocks", 2, 4, "auto-smelt-chance", .05, .10);
        profile(p, "new_arrival", 0, 0, "restorative-heal-health-points", 1, 2, "speed-seconds", 1, 2);
        return new TitleLevels(1000, 3000, new RewardWeights(1, 1, 5, 5, 50, 100, 100, 200), p);
    }

    private static void profile(Map<String, Map<Integer, Upgrade>> profiles, String id, int cooldown2, int cooldown3, Object... deltas) {
        Map<String, Double> second = new LinkedHashMap<>(), third = new LinkedHashMap<>();
        for (int i = 0; i < deltas.length; i += 3) {
            second.put((String) deltas[i], ((Number) deltas[i + 1]).doubleValue());
            third.put((String) deltas[i], ((Number) deltas[i + 2]).doubleValue());
        }
        profiles.put(id, Map.of(2, new Upgrade(cooldown2, second), 3, new Upgrade(cooldown3, third)));
    }
}
