package com.leadsystems.minecraft.ascension.domain.title;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import com.leadsystems.minecraft.ascension.config.ConfigLoader;
import com.leadsystems.minecraft.ascension.config.NameResolver;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.Objects;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleDomainTest {

    static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** A context backed by a stat map. */
    record Ctx(Map<String, Long> stats, int rankIndex, boolean mastery, int streak, Boolean possess)
            implements RequirementContext {
        @Override public long stat(String key) { return stats.getOrDefault(key, 0L); }
        @Override public int bestStreakDays() { return streak; }
        @Override public Boolean possesses(String material, String enchantment) { return possess; }
    }

    static TitleDefinition title(String id, int number, Requirement... reqs) {
        return new TitleDefinition(id, number, id, "#ffffff", "desc", List.of(reqs),
                "Ability", "does things", 30, false);
    }

    @Test
    void allThirtyBundledTitleQuestsRequireEveryConfiguredObjective() {
        Map<String, YamlConfiguration> files = new HashMap<>();
        for (String file : ConfigLoader.FILES) {
            var input = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(file));
            files.put(file, YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8)));
        }
        var loaded = new ConfigLoader(new NameResolver() {
            public boolean isMaterial(String name) { return true; }
            public boolean isEntityType(String name) { return true; }
            public boolean isEffect(String name) { return true; }
        }).load(files);
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(30, loaded.snapshot().titles().ordered().size());
        for (TitleDefinition title : loaded.snapshot().titles().ordered()) {
            assertFalse(title.requirements().isEmpty(), title.id());
            assertFalse(RequirementEvaluator.claimable(title, new Ctx(Map.of(), -1, false, 0, false)), title.id());
            Map<String, Long> completed = new HashMap<>();
            for (Requirement requirement : title.requirements()) {
                if (requirement instanceof Requirement.StatAtLeast stat) {
                    stat.keys().forEach(key -> completed.put(key, stat.amount()));
                }
            }
            assertTrue(RequirementEvaluator.claimable(title, new Ctx(completed, 11, true, 100, true)), title.id());
            for (Requirement missing : title.requirements()) {
                Map<String, Long> partial = new HashMap<>(completed);
                if (missing instanceof Requirement.StatAtLeast stat) stat.keys().forEach(key -> partial.put(key, 0L));
                Ctx context = new Ctx(partial,
                        missing instanceof Requirement.RankAtLeast ? -1 : 11,
                        !(missing instanceof Requirement.RankAtLeast),
                        missing instanceof Requirement.StreakAtLeast ? 0 : 100,
                        !(missing instanceof Requirement.Possess));
                assertFalse(RequirementEvaluator.claimable(title, context), title.id() + ": " + missing.label());
            }
        }
    }

    @Test
    void upgradedAbilitySettingsReplaceOnlyStockLegacyValues() {
        TitleDefinition stock = new TitleDefinition("ashborn", 24, "Ashborn", "#ffffff", "", List.of(),
                "Flame Purge", "", 45, Map.of("damage-health-points", 4.0), false);
        TitleDefinition custom = new TitleDefinition("ashborn", 24, "Ashborn", "#ffffff", "", List.of(),
                "Flame Purge", "", 45, Map.of("damage-health-points", 7.0), false);
        TitleDefinition upgraded = new TitleDefinition("ashborn", 24, "Ashborn", "#ffffff", "", List.of(),
                "Flame Purge", "", 45, Map.of("combat-damage-health-points", 9.0), false);

        assertEquals(8.0, stock.upgradedAbilitySetting("combat-damage-health-points", "damage-health-points", 4, 8));
        assertEquals(7.0, custom.upgradedAbilitySetting("combat-damage-health-points", "damage-health-points", 4, 8));
        assertEquals(9.0, upgraded.upgradedAbilitySetting("combat-damage-health-points", "damage-health-points", 4, 8));
    }

    @Test
    void statRequirementSumsItsKeys() {
        Requirement r = new Requirement.StatAtLeast("Mine stone", List.of("mined.STONE", "mined.DEEPSLATE"), 500);
        RequirementProgress p = RequirementEvaluator.evaluate(r,
                new Ctx(Map.of("mined.STONE", 300L, "mined.DEEPSLATE", 150L), 0, false, 0, null));
        assertEquals(450, p.current());
        assertEquals(500, p.required());
        assertFalse(p.met());
    }

    @Test
    void progressIsCappedAtTheRequirement() {
        Requirement r = new Requirement.StatAtLeast("Kills", List.of("kills.hostile"), 250);
        RequirementProgress p = RequirementEvaluator.evaluate(r, new Ctx(Map.of("kills.hostile", 999L), 0, false, 0, null));
        assertEquals(250, p.current());
        assertTrue(p.met());
    }

    @Test
    void rankRequirementUsesIndexOrMastery() {
        Requirement platinum = new Requirement.RankAtLeast("Reach Platinum", 9, false);
        assertFalse(RequirementEvaluator.evaluate(platinum, new Ctx(Map.of(), 8, false, 0, null)).met());
        assertTrue(RequirementEvaluator.evaluate(platinum, new Ctx(Map.of(), 9, false, 0, null)).met());
        Requirement mastery = new Requirement.RankAtLeast("Mastery", 11, true);
        assertFalse(RequirementEvaluator.evaluate(mastery, new Ctx(Map.of(), 11, false, 0, null)).met());
        assertTrue(RequirementEvaluator.evaluate(mastery, new Ctx(Map.of(), 11, true, 0, null)).met());
    }

    @Test
    void possessionIsUnknownUntilChecked() {
        Requirement r = new Requirement.Possess("Channeling trident", "TRIDENT", "channeling");
        assertFalse(RequirementEvaluator.evaluate(r, new Ctx(Map.of(), 0, false, 0, null)).met());
        assertTrue(RequirementEvaluator.evaluate(r, new Ctx(Map.of(), 0, false, 0, true)).met());
    }

    @Test
    void eligibilityIgnoresPossessionButClaimNeedsIt() {
        TitleDefinition t = title("tempest", 4,
                new Requirement.StatAtLeast("Storm kills", List.of("kills.storm"), 25),
                new Requirement.Possess("Channeling trident", "TRIDENT", "channeling"));
        Ctx noTrident = new Ctx(Map.of("kills.storm", 25L), 0, false, 0, false);
        assertTrue(RequirementEvaluator.eligible(t, noTrident));
        assertFalse(RequirementEvaluator.claimable(t, noTrident));
        assertTrue(RequirementEvaluator.claimable(t, new Ctx(Map.of("kills.storm", 25L), 0, false, 0, true)));
    }

    @Test
    void streakRequirementUsesBestStreak() {
        Requirement r = new Requirement.StreakAtLeast("50 deathless days", 50);
        assertFalse(RequirementEvaluator.evaluate(r, new Ctx(Map.of(), 0, false, 49, null)).met());
        assertTrue(RequirementEvaluator.evaluate(r, new Ctx(Map.of(), 0, false, 50, null)).met());
    }

    @Test
    void stateReflectsOwnershipBeforeEligibility() {
        assertEquals(TitleState.OWNED, TitleState.of(true, ME, ME, false));
        assertEquals(TitleState.TAKEN, TitleState.of(true, OTHER, ME, false));
        assertEquals(TitleState.CLAIMING, TitleState.of(true, null, ME, true));
        assertEquals(TitleState.ELIGIBLE, TitleState.of(true, null, ME, false));
        assertEquals(TitleState.UNAVAILABLE, TitleState.of(false, null, ME, false));
    }

    @Test
    void catalogResolvesIdsNumbersAndStatUsers() {
        TitleCatalog c = new TitleCatalog(List.of(
                title("stonebreaker", 29, new Requirement.StatAtLeast("x", List.of("mined.STONE"), 1)),
                title("huntsman", 28, new Requirement.StatAtLeast("x", List.of("kills.hostile"), 1)),
                title("soul_reaper", 14, new Requirement.StatAtLeast("x", List.of("kills.hostile"), 1),
                        new Requirement.StatAtLeast("y", List.of("pvp.kills"), 1))));
        assertEquals("huntsman", c.resolve("#28").id());
        assertEquals("huntsman", c.resolve("HUNTSMAN").id());
        assertEquals("soul_reaper", c.resolve("14").id());
        assertNull(c.resolve("nope"));
        assertEquals(List.of("soul_reaper", "huntsman", "stonebreaker"),
                c.ordered().stream().map(TitleDefinition::id).toList(), "hardest (#1) first");
        assertEquals(2, c.usingStat("kills.hostile").size());
        assertTrue(c.usingStat("unused").isEmpty());
    }
}
