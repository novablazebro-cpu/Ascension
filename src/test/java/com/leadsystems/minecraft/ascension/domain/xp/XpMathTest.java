package com.leadsystems.minecraft.ascension.domain.xp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XpMathTest {

    @Test
    void pointsNeededFollowVanillaCurve() {
        assertEquals(7, XpMath.pointsForNextLevel(0));
        assertEquals(35, XpMath.pointsForNextLevel(14));
        assertEquals(37, XpMath.pointsForNextLevel(15));
        assertEquals(107, XpMath.pointsForNextLevel(29));
        assertEquals(112, XpMath.pointsForNextLevel(30));
        assertEquals(121, XpMath.pointsForNextLevel(31));
    }

    @Test
    void exactlyOneLevelsWorthLevelsUpOnce() {
        assertEquals(1, XpMath.levelsGained(0, 0f, 7));
    }

    @Test
    void oneShortOfALevelGainsNothing() {
        assertEquals(0, XpMath.levelsGained(0, 0f, 6));
    }

    @Test
    void largeGainCrossesSeveralLevels() {
        // 7 (0->1) + 9 (1->2) = 16 points
        assertEquals(2, XpMath.levelsGained(0, 0f, 16));
    }

    @Test
    void existingProgressWithinTheLevelCounts() {
        // level 5 needs 17 points; half-way there + 9 points crosses the level
        assertEquals(1, XpMath.levelsGained(5, 0.5f, 9));
    }

    @Test
    void highLevelsCostMore() {
        assertEquals(1, XpMath.levelsGained(30, 0f, 112));
        assertEquals(0, XpMath.levelsGained(30, 0f, 111));
    }

    @Test
    void nonPositiveAmountsNeverCount() {
        assertEquals(0, XpMath.levelsGained(10, 0.9f, 0));
        assertEquals(0, XpMath.levelsGained(10, 0.9f, -50));
    }

    @Test
    void fullRunFromZeroReachesVanillaLevelThirty() {
        // 1395 points is the vanilla total for level 0 -> 30.
        assertEquals(30, XpMath.levelsGained(0, 0f, 1395));
    }
}
