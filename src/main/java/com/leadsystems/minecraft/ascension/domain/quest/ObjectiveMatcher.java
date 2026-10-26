package com.leadsystems.minecraft.ascension.domain.quest;

import com.leadsystems.minecraft.ascension.domain.progress.Fact;

import java.util.Set;

/**
 * Decides how much a validated {@link Fact} contributes to one objective. Pure function.
 */
public final class ObjectiveMatcher {

    /**
     * What a fact adds to an objective.
     *
     * @param delta amount to add (0 for mark-only contributions such as distinct mob types)
     * @param mark  distinct mark to record (victim UUID, mob type), or null
     */
    public record Contribution(long delta, String mark) {}

    private ObjectiveMatcher() {}

    /** Returns the contribution of {@code fact} to {@code objective}, or null if it does not apply. */
    public static Contribution match(ObjectiveDefinition objective, Fact fact) {
        ObjectiveType type = objective.type();
        return switch (fact) {
            case Fact.LevelsGained f -> type == ObjectiveType.GAIN_LEVELS && f.levels() > 0
                    ? new Contribution(f.levels(), null) : null;
            case Fact.MobKilled f -> {
                if (!matchesMob(objective, f)) {
                    yield null;
                }
                if (type == ObjectiveType.KILL_MOBS) {
                    yield new Contribution(1, null);
                }
                yield type == ObjectiveType.KILL_DISTINCT_MOBS ? new Contribution(0, f.entityType()) : null;
            }
            case Fact.BlockMined f -> type == ObjectiveType.MINE_BLOCKS && targets(objective, f.material())
                    ? new Contribution(1, null) : null;
            case Fact.ItemSmelted f -> type == ObjectiveType.SMELT_ITEMS && targets(objective, f.material())
                    ? positive(f.amount()) : null;
            case Fact.ItemObtained f -> type == ObjectiveType.OBTAIN_ITEMS && targets(objective, f.material())
                    ? positive(f.amount()) : null;
            case Fact.ItemSubmitted f -> type == ObjectiveType.SUBMIT_ITEMS && targets(objective, f.material())
                    ? positive(f.amount()) : null;
            case Fact.PlayerKilled f -> type == ObjectiveType.PLAYER_KILLS
                    ? new Contribution(1, f.victimId()) : null;
            case Fact.HuntCompleted f -> type == ObjectiveType.HUNTS ? new Contribution(1, null) : null;
            case Fact.RaidCompleted f -> type == ObjectiveType.RAIDS ? new Contribution(1, null) : null;
            case Fact.BossDefeated f -> {
                if (type != ObjectiveType.DEFEAT_BOSS || !targets(objective, f.boss())) {
                    yield null;
                }
                if (objective.requireFinalHit() && !f.finalHit()) {
                    yield null;
                }
                if (objective.requireSummon() && !f.summonedByPlayer()) {
                    yield null;
                }
                yield new Contribution(1, null);
            }
            case Fact.Statistic ignored -> null;
        };
    }

    private static Contribution positive(int amount) {
        return amount > 0 ? new Contribution(amount, null) : null;
    }

    private static boolean targets(ObjectiveDefinition objective, String name) {
        return objective.targets().isEmpty() || objective.targets().contains(name);
    }

    private static boolean matchesMob(ObjectiveDefinition objective, Fact.MobKilled fact) {
        if (!targets(objective, fact.entityType())) {
            return false;
        }
        Set<String> wanted = objective.categories();
        if (wanted.isEmpty()) {
            return true;
        }
        for (String c : fact.categories()) {
            if (wanted.contains(c)) {
                return true;
            }
        }
        return false;
    }
}
