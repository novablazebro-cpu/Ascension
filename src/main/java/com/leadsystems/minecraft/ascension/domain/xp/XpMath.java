package com.leadsystems.minecraft.ascension.domain.xp;

/**
 * Experience-level arithmetic that mirrors vanilla exactly.
 * <p>
 * Paper fires {@code PlayerLevelChangeEvent} later in the player's tick (it diffs against the last
 * seen level), so quest level-ups are computed at {@code PlayerExpChangeEvent} time instead: given the
 * player's current level/progress and the final orb amount, replay {@code Player.giveExperiencePoints}
 * (1.21.4, decompiled) with the same float arithmetic so the result matches what the XP bar will show.
 */
public final class XpMath {

    private XpMath() {}

    /** Points needed to go from {@code level} to {@code level + 1} ({@code Player.getXpNeededForNextLevel}). */
    public static int pointsForNextLevel(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        return level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
    }

    /**
     * Levels gained if {@code amount} points are added to a player at {@code level} with {@code progress}
     * (0..1 within the level). Non-positive amounts never gain levels.
     */
    public static int levelsGained(int level, float progress, int amount) {
        if (amount <= 0 || level < 0) {
            return 0;
        }
        int current = level;
        float p = progress + (float) amount / (float) pointsForNextLevel(current);
        int gained = 0;
        while (p >= 1.0f) {
            p = (p - 1.0f) * (float) pointsForNextLevel(current);
            current++;
            gained++;
            p /= (float) pointsForNextLevel(current);
        }
        return gained;
    }
}
