package com.leadsystems.minecraft.ascension.config;

import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityResolver;
import com.leadsystems.minecraft.ascension.domain.title.TitleLevels;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TitleLevelsConfigTest {
    @Test
    void legacyCustomSettingsResolveAtExistingGameplayCapsForEveryLevel() {
        var files = ConfigLoaderTest.defaults();
        setSettings(files.get("titles.yml"), "voidwalker", Map.of("aim-range", 100.0, "charges", 10.8));
        setSettings(files.get("titles.yml"), "tempest_herald", Map.of("extra-chain-targets", 20.5));
        setSettings(files.get("titles.yml"), "treasure_whisperer", Map.of("range", 40.5));
        setSettings(files.get("titles.yml"), "mycelial_drifter", Map.of("ticks", 20.5));
        var loaded = ConfigLoaderTest.load(files);
        assertTrue(loaded.ok(), () -> loaded.errors().toString());
        for (int level = 1; level <= 3; level++) {
            assertEquals(64, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "voidwalker", level).abilitySetting("aim-range", 0));
            assertEquals(5, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "voidwalker", level).abilitySetting("charges", 0));
            assertEquals(16, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "tempest_herald", level).abilitySetting("extra-chain-targets", 0));
            assertEquals(24, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "treasure_whisperer", level).abilitySetting("range", 0));
            assertEquals(8, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "mycelial_drifter", level).abilitySetting("ticks", 0));
        }
        assertEquals(100, loaded.snapshot().titles().byId("voidwalker").abilitySetting("aim-range", 0));
        var resolver = new TitleAbilityResolver(loaded::snapshot);
        assertTrue(resolver.levelSummary(null, "voidwalker").contains("64 blocks. 5 charges"));
        assertTrue(resolver.levelSummary(null, "tempest_herald").contains("16 extra targets"));
        assertTrue(resolver.levelSummary(null, "treasure_whisperer").contains("24 blocks"));
        assertTrue(resolver.levelSummary(null, "mycelial_drifter").contains("8 pulses"));
    }

    @Test
    void oldIntegerTruncationAndMinimumsDefineTheRelativeUpgradeBaseline() {
        var files = ConfigLoaderTest.defaults();
        setSettings(files.get("titles.yml"), "voidwalker", Map.of("aim-range", 0.0, "charges", 3.9));
        setSettings(files.get("titles.yml"), "tempest_herald", Map.of("extra-chain-targets", .9));
        setSettings(files.get("titles.yml"), "treasure_whisperer", Map.of("range", 17.9));
        setSettings(files.get("titles.yml"), "mycelial_drifter", Map.of("ticks", .9));
        var loaded = ConfigLoaderTest.load(files);
        assertTrue(loaded.ok(), () -> loaded.errors().toString());
        assertEquals(1, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "voidwalker", 1).abilitySetting("aim-range", 0));
        assertEquals(17, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "voidwalker", 3).abilitySetting("aim-range", 0));
        assertEquals(3, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "voidwalker", 1).abilitySetting("charges", 0));
        assertEquals(5, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "voidwalker", 3).abilitySetting("charges", 0));
        assertEquals(0, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "tempest_herald", 1).abilitySetting("extra-chain-targets", -1));
        assertEquals(2, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "tempest_herald", 3).abilitySetting("extra-chain-targets", -1));
        assertEquals(17, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "treasure_whisperer", 1).abilitySetting("range", 0));
        assertEquals(21, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "treasure_whisperer", 2).abilitySetting("range", 0));
        assertEquals(24, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "treasure_whisperer", 3).abilitySetting("range", 0));
        assertEquals(1, TitleAbilityResolver.resolveLevel(loaded.snapshot(), "mycelial_drifter", 1).abilitySetting("ticks", 0));
    }

    @Test
    void explicitCustomUpgradesCannotDescribeValuesBeyondGameplayCaps() {
        String[][] overrides = {
                {"voidwalker", "charges", "3"}, {"voidwalker", "aim-range", "33"},
                {"tempest_herald", "extra-chain-targets", "15"}, {"treasure_whisperer", "range", "9"},
                {"mycelial_drifter", "ticks", "5"}};
        for (String[] override : overrides) {
            var files = ConfigLoaderTest.defaults();
            files.get("title-levels.yml").set("profiles." + override[0] + ".3.settings-deltas." + override[1], Double.parseDouble(override[2]));
            var loaded = ConfigLoaderTest.load(files);
            assertFalse(loaded.ok(), override[0] + ": " + override[1]);
            assertTrue(loaded.errors().stream().anyMatch(error -> error.contains(override[1]) && error.contains("invalid effective value")));
        }
    }

    @Test
    void stockUpgradeDeltasSaturateNaturalLimitsWithoutRejectingValidCustomLevelOne() {
        var files = ConfigLoaderTest.defaults();
        var titles = files.get("titles.yml").getMapList("titles");
        for (var row : titles) {
            if ("chronomancer".equals(row.get("id"))) {
                @SuppressWarnings("unchecked") var ability = (Map<String, Object>) row.get("ability");
                ability.put("cooldown-seconds", 10);
            }
        }
        files.get("titles.yml").set("titles", titles);
        setSettings(files.get("titles.yml"), "bloodlord", Map.of("lifesteal-ratio", .95));
        var loaded = ConfigLoaderTest.load(files);
        assertTrue(loaded.ok(), () -> "Valid customized level one rejected: " + loaded.errors());
        var config = loaded.snapshot();
        assertEquals(10, TitleAbilityResolver.resolveLevel(config, "chronomancer", 1).abilityCooldownSeconds());
        assertEquals(0, TitleAbilityResolver.resolveLevel(config, "chronomancer", 2).abilityCooldownSeconds());
        assertEquals(0, TitleAbilityResolver.resolveLevel(config, "chronomancer", 3).abilityCooldownSeconds());
        assertEquals(.95, TitleAbilityResolver.resolveLevel(config, "bloodlord", 1).abilitySetting("lifesteal-ratio", 0), 0.000001);
        assertEquals(1, TitleAbilityResolver.resolveLevel(config, "bloodlord", 2).abilitySetting("lifesteal-ratio", 0), 0.000001);
        assertEquals(1, TitleAbilityResolver.resolveLevel(config, "bloodlord", 3).abilitySetting("lifesteal-ratio", 0), 0.000001);
        assertTrue(loaded.warnings().stream().anyMatch(warning -> warning.contains("saturates")));
    }

    @Test
    void customizedUpgradeOverflowAndInvalidBaselinesRemainRejected() {
        var files = ConfigLoaderTest.defaults();
        setSettings(files.get("titles.yml"), "bloodlord", Map.of("lifesteal-ratio", .95));
        files.get("title-levels.yml").set("profiles.bloodlord.3.settings-deltas.lifesteal-ratio", .11);
        var customOverflow = ConfigLoaderTest.load(files);
        assertFalse(customOverflow.ok());
        assertTrue(customOverflow.errors().stream().anyMatch(error -> error.contains("lifesteal-ratio") && error.contains("invalid effective value")));

        files = ConfigLoaderTest.defaults();
        setSettings(files.get("titles.yml"), "bloodlord", Map.of("lifesteal-ratio", 1.2));
        files.get("title-levels.yml").set("profiles.bloodlord.3.settings-deltas.lifesteal-ratio", -.3);
        var invalidBaseline = ConfigLoaderTest.load(files);
        assertFalse(invalidBaseline.ok());
        assertTrue(invalidBaseline.errors().stream().anyMatch(error -> error.contains("lifesteal-ratio") && error.contains("level-one baseline")));
    }

    @Test
    void customizedCooldownUnderflowRemainsRejected() {
        var files = ConfigLoaderTest.defaults();
        files.get("title-levels.yml").set("profiles.chronomancer.3.cooldown-delta-seconds", -121);
        var loaded = ConfigLoaderTest.load(files);
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().stream().anyMatch(error -> error.contains("cooldown-delta-seconds")));
    }

    @Test
    void invalidOriginalCooldownIsNotHiddenByStockSaturation() {
        var files = ConfigLoaderTest.defaults();
        var titles = files.get("titles.yml").getMapList("titles");
        for (var row : titles) {
            if ("chronomancer".equals(row.get("id"))) {
                @SuppressWarnings("unchecked") var ability = (Map<String, Object>) row.get("ability");
                ability.put("cooldown-seconds", -1);
            }
        }
        files.get("titles.yml").set("titles", titles);
        var loaded = ConfigLoaderTest.load(files);
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().stream().anyMatch(error -> error.contains("titles.yml") && error.contains("ability.cooldown-seconds")));
    }

    @Test
    void bundledRulesCoverAllTitlesAndMatchFallbackDefaults() {
        ConfigLoader.LoadResult loaded = ConfigLoaderTest.load(ConfigLoaderTest.defaults());
        assertTrue(loaded.ok(), () -> loaded.errors().toString());
        assertEquals(TitleLevels.defaults(), loaded.snapshot().titleLevels());
        for (var title : loaded.snapshot().titles().ordered()) {
            for (int level = 1; level <= 3; level++) {
                var resolved = TitleAbilityResolver.resolveLevel(loaded.snapshot(), title.id(), level);
                assertEquals(title.id(), resolved.id());
                assertFalse(resolved.abilityDescription().isBlank(), title.id());
                assertTrue(resolved.abilityCooldownSeconds() >= 0, title.id());
            }
        }
    }

    @Test
    void missingNewFileUpgradesOldInstallWithDefaults() {
        var files = ConfigLoaderTest.defaults();
        files.remove("title-levels.yml");
        var loaded = ConfigLoaderTest.load(files);
        assertTrue(loaded.ok(), () -> loaded.errors().toString());
        assertEquals(TitleLevels.defaults(), loaded.snapshot().titleLevels());
    }

    @Test
    void levelsUseIndependentLifetimeXpAndExactThresholds() {
        var config = ConfigLoaderTest.load(ConfigLoaderTest.defaults()).snapshot();
        var resolver = new TitleAbilityResolver(() -> config);
        var player = new PlayerSession(UUID.randomUUID(), "ExistingOwner", false);
        assertEquals(1, resolver.level(player, "voidwalker"));
        assertEquals(1000, resolver.nextLevelXp(player, "voidwalker"));
        player.addStat(TitleAbilityResolver.xpKey("voidwalker"), 999);
        assertEquals(1, resolver.level(player, "voidwalker"));
        player.addStat(TitleAbilityResolver.xpKey("voidwalker"), 1);
        assertEquals(2, resolver.level(player, "voidwalker"));
        assertEquals(40, resolver.resolved(player, "voidwalker").abilitySetting("aim-range", 0));
        assertEquals(4, resolver.resolved(player, "voidwalker").abilitySetting("charges", 0));
        assertEquals(1, resolver.level(player, "ascendant"));
        assertEquals(3000, resolver.nextLevelXp(player, "voidwalker"));
        player.addStat(TitleAbilityResolver.xpKey("voidwalker"), 2000);
        assertEquals(3, resolver.level(player, "voidwalker"));
        assertEquals(48, resolver.resolved(player, "voidwalker").abilitySetting("aim-range", 0));
        assertEquals(5, resolver.resolved(player, "voidwalker").abilitySetting("charges", 0));
        assertEquals(0, resolver.nextLevelXp(player, "voidwalker"));
        assertEquals("Maximum level reached.", resolver.nextLevelSummary(player, "voidwalker"));
        assertTrue(player.drain().statDeltas().containsKey("title.xp.voidwalker"));
    }

    @Test
    void upgradedValuesUseCustomAndLegacyEffectiveLevelOneRatherThanStockAbsoluteValues() {
        var files = ConfigLoaderTest.defaults();
        setSettings(files.get("titles.yml"), "titan", Map.of("radius", 10.0, "combat-damage-health-points", 18.0));
        setSettings(files.get("titles.yml"), "ascendant", Map.of("duration-seconds", 45.0));
        setSettings(files.get("titles.yml"), "new_arrival", Map.of("heal-health-points", 6.0));
        files.get("config.yml").set("title-abilities.stormbringer.range", 65);
        files.get("config.yml").set("title-abilities.bloodlord.max-heal-health-points", 7);
        var config = ConfigLoaderTest.load(files).snapshot();
        assertNotNull(config);
        assertEquals(12, TitleAbilityResolver.resolveLevel(config, "titan", 3).abilitySetting("slam-radius", 0));
        assertEquals(18, TitleAbilityResolver.resolveLevel(config, "titan", 3).abilitySetting("slam-damage-health-points", 0));
        assertEquals(57, TitleAbilityResolver.resolveLevel(config, "ascendant", 3).abilitySetting("buff-duration-seconds", 0));
        assertEquals(8, TitleAbilityResolver.resolveLevel(config, "new_arrival", 3).abilitySetting("restorative-heal-health-points", 0));
        assertEquals(75, TitleAbilityResolver.resolveLevel(config, "stormbringer", 3).abilitySetting("range", 0));
        assertEquals(8, TitleAbilityResolver.resolveLevel(config, "bloodlord", 3).abilitySetting("max-heal-health-points", 0));
    }

    @Test
    void stockLegacyValuesKeepExistingMigrationFallbacks() {
        var files = ConfigLoaderTest.defaults();
        setSettings(files.get("titles.yml"), "titan", Map.of("radius", 6.0));
        setSettings(files.get("titles.yml"), "ascendant", Map.of("duration-seconds", 15.0));
        setSettings(files.get("titles.yml"), "new_arrival", Map.of("heal-health-points", 1.0));
        var config = ConfigLoaderTest.load(files).snapshot();
        assertEquals(3, TitleAbilityResolver.resolveLevel(config, "titan", 1).abilitySetting("slam-radius", 0));
        assertEquals(5, TitleAbilityResolver.resolveLevel(config, "titan", 3).abilitySetting("slam-radius", 0));
        assertEquals(30, TitleAbilityResolver.resolveLevel(config, "ascendant", 1).abilitySetting("buff-duration-seconds", 0));
        assertEquals(2, TitleAbilityResolver.resolveLevel(config, "new_arrival", 1).abilitySetting("restorative-heal-health-points", 0));
    }

    @Test
    void invalidThresholdsRewardsProfilesAndEffectiveValuesAreRejectedTogether() {
        var files = ConfigLoaderTest.defaults();
        var yaml = files.get("title-levels.yml");
        yaml.set("thresholds.level-3", 1000);
        yaml.set("rewards.hostile-mob", -1);
        yaml.set("rewards.boss", 0.5);
        yaml.set("profiles.angler.2", null);
        yaml.set("profiles.bloodlord.3.settings-deltas.lifesteal-ratio", 2.0);
        yaml.set("profiles.voidwalker.3.settings-deltas.charges", 0.5);
        yaml.set("profiles.ascendant.3.settings-deltas.unknown", 1);
        yaml.set("profiles.titan.2.settings-deltas.slam-radius", Double.NaN);
        yaml.set("profiles.chronomancer.2.cooldown-delta-seconds", -500);
        var loaded = ConfigLoaderTest.load(files);
        assertFalse(loaded.ok());
        assertNull(loaded.snapshot());
        for (String key : new String[]{"level-3", "hostile-mob", "rewards.boss", "angler.2", "slam-radius"}) {
            assertTrue(loaded.errors().stream().anyMatch(error -> error.contains(key)), key + ": " + loaded.errors());
        }
        // Validation remains atomic; the current valid snapshot remains usable by the caller.
        assertTrue(ConfigLoaderTest.load(ConfigLoaderTest.defaults()).ok());
    }

    @Test
    void effectiveInvalidAndUnknownSettingsAreRejected() {
        var files = ConfigLoaderTest.defaults();
        var yaml = files.get("title-levels.yml");
        yaml.set("profiles.bloodlord.3.settings-deltas.lifesteal-ratio", 2.0);
        yaml.set("profiles.voidwalker.3.settings-deltas.charges", 0.5);
        yaml.set("profiles.ascendant.3.settings-deltas.unknown", 1);
        yaml.set("profiles.chronomancer.2.cooldown-delta-seconds", -500);
        var loaded = ConfigLoaderTest.load(files);
        assertFalse(loaded.ok());
        for (String key : new String[]{"lifesteal-ratio", "charges", "unknown", "cooldown-delta-seconds"}) {
            assertTrue(loaded.errors().stream().anyMatch(error -> error.contains(key)), key + ": " + loaded.errors());
        }
    }

    @Test
    void rewardsCountOnlyApprovedFactsAndExcludeBossDoubleCredit() {
        var rules = TitleLevels.defaults();
        assertEquals(1, rules.reward(new Fact.BlockMined("STONE")));
        assertEquals(64, rules.reward(new Fact.ItemSmelted("IRON_INGOT", 64)));
        assertEquals(5, rules.reward(new Fact.MobKilled("ZOMBIE", Set.of("hostile"))));
        assertEquals(0, rules.reward(new Fact.MobKilled("COW", Set.of())));
        for (String boss : TitleLevels.BOSSES) {
            assertEquals(0, rules.reward(new Fact.MobKilled(boss, Set.of("hostile"))));
            assertEquals(200, rules.reward(new Fact.BossDefeated(boss, true, false)));
        }
        assertEquals(5, rules.reward(new Fact.Statistic("fish.caught", 1)));
        assertEquals(50, rules.reward(new Fact.PlayerKilled(UUID.randomUUID().toString())));
        assertEquals(100, rules.reward(new Fact.HuntCompleted()));
        assertEquals(100, rules.reward(new Fact.RaidCompleted()));
        assertEquals(0, rules.reward(new Fact.LevelsGained(10)));
        assertEquals(0, rules.reward(new Fact.ItemObtained("DIAMOND", 20)));
    }

    @Test
    void thunderGodAliasesPreserveStableInternalId() {
        var titles = ConfigLoaderTest.load(ConfigLoaderTest.defaults()).snapshot().titles();
        for (String alias : new String[]{"stormbringer", "Thunder God", "thunder_god", "thundergod", "#3"}) {
            assertEquals("stormbringer", titles.resolve(alias).id());
            assertEquals("Thunder God", titles.resolve(alias).name());
        }
    }

    @SuppressWarnings("unchecked")
    private static void setSettings(YamlConfiguration yaml, String id, Map<String, Double> settings) {
        var titles = yaml.getMapList("titles");
        for (var row : titles) {
            if (id.equals(row.get("id"))) {
                ((Map<String, Object>) row.get("ability")).put("settings", new LinkedHashMap<>(settings));
            }
        }
        yaml.set("titles", titles);
    }
}
