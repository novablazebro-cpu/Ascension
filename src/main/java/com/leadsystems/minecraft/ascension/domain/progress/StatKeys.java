package com.leadsystems.minecraft.ascension.domain.progress;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps facts to lifetime statistic increments (title requirements and the statistics page).
 * Keys are bounded: per-material/per-type keys only arise from facts, and facts are only emitted
 * for tracked materials, so a player accumulates at most a few hundred rows.
 */
public final class StatKeys {

    private StatKeys() {}

    public static Map<String, Long> of(Fact fact) {
        Map<String, Long> out = new HashMap<>();
        switch (fact) {
            case Fact.LevelsGained f -> out.put("levels", (long) f.levels());
            case Fact.MobKilled f -> {
                out.put("kills", 1L);
                out.put("kills.type." + f.entityType(), 1L);
                for (String c : f.categories()) {
                    out.put("kills." + c, 1L);
                }
            }
            case Fact.BlockMined f -> {
                out.put("mined", 1L);
                out.put("mined." + f.material(), 1L);
            }
            case Fact.ItemSmelted f -> {
                out.put("smelted", (long) f.amount());
                out.put("smelted." + f.material(), (long) f.amount());
            }
            case Fact.ItemObtained f -> out.put("obtained." + f.material(), (long) f.amount());
            case Fact.ItemSubmitted f -> out.put("submitted." + f.material(), (long) f.amount());
            case Fact.PlayerKilled f -> out.put("pvp.kills", 1L);
            case Fact.HuntCompleted f -> out.put("hunts", 1L);
            case Fact.RaidCompleted f -> out.put("raids", 1L);
            case Fact.BossDefeated f -> out.put("bosses." + f.boss(), 1L);
            case Fact.Statistic f -> out.put(f.key(), f.amount());
        }
        return out;
    }
}
