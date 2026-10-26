package com.leadsystems.minecraft.ascension.domain.progress;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatKeysTest {

    @Test
    void levelsAreSummed() {
        assertEquals(Map.of("levels", 4L), StatKeys.of(new Fact.LevelsGained(4)));
    }

    @Test
    void mobKillsCountTotalTypeAndEachCategory() {
        assertEquals(Map.of("kills", 1L, "kills.type.BLAZE", 1L, "kills.hostile", 1L, "kills.nether", 1L),
                StatKeys.of(new Fact.MobKilled("BLAZE", Set.of("hostile", "nether"))));
    }

    @Test
    void miningCountsTotalAndMaterial() {
        assertEquals(Map.of("mined", 1L, "mined.STONE", 1L), StatKeys.of(new Fact.BlockMined("STONE")));
    }

    @Test
    void itemFactsCarryTheirAmount() {
        assertEquals(Map.of("smelted", 16L, "smelted.IRON_INGOT", 16L),
                StatKeys.of(new Fact.ItemSmelted("IRON_INGOT", 16)));
        assertEquals(Map.of("obtained.DIAMOND", 3L), StatKeys.of(new Fact.ItemObtained("DIAMOND", 3)));
    }

    @Test
    void bossesAreKeyedByType() {
        assertEquals(Map.of("bosses.WARDEN", 1L), StatKeys.of(new Fact.BossDefeated("WARDEN", true, false)));
    }
}
