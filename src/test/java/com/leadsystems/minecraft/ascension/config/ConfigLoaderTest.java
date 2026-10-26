package com.leadsystems.minecraft.ascension.config;

import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveDefinition;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveType;
import com.leadsystems.minecraft.ascension.domain.quest.QuestDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityRegistry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    /** Accepts every name except ones starting with NOT_. */
    static final NameResolver PERMISSIVE = new NameResolver() {
        @Override public boolean isMaterial(String name) { return !name.startsWith("NOT_"); }
        @Override public boolean isEntityType(String name) { return !name.startsWith("NOT_"); }
        @Override public boolean isEffect(String key) { return !key.startsWith("not_"); }
    };

    static Map<String, YamlConfiguration> defaults() {
        Map<String, YamlConfiguration> files = new HashMap<>();
        for (String name : ConfigLoader.FILES) {
            var in = Objects.requireNonNull(ConfigLoaderTest.class.getClassLoader().getResourceAsStream(name), name);
            files.put(name, YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        return files;
    }

    static ConfigLoader.LoadResult load(Map<String, YamlConfiguration> files) {
        return new ConfigLoader(PERMISSIVE).load(new HashMap<>(files));
    }

    @Test
    void bundledDefaultsAreValid() {
        ConfigLoader.LoadResult r = load(defaults());
        assertTrue(r.errors().isEmpty(), () -> "errors: " + r.errors());
        ConfigSnapshot s = r.snapshot();
        assertEquals(12, s.ladder().size());
        assertEquals("Platinum III", s.ladder().get(11).name());
        assertEquals(12, s.quests().size());
        assertEquals(TitleAbilityRegistry.ALL,
                s.titles().ordered().stream().map(t -> t.id()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(s.titles().ordered().stream().allMatch(t -> !t.abilityName().isBlank()
                && !t.abilityDescription().isBlank()));
        assertEquals(ZoneId.systemDefault(), s.settings().zone());
        assertFalse(s.testingUnlockAll());
        assertEquals("AMETHYST_SHARD", s.settings().activationItemMaterial());
        assertEquals("Ascension Relic", s.settings().activationItemDisplayName());
        assertTrue(s.settings().activationItemGlint());
    }

    @Test
    void missingTestingSettingKeepsProductionLocks() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("config.yml").set("testing", null);
        assertFalse(load(files).snapshot().testingUnlockAll());
    }

    @Test
    void titlesCannotOmitTheirQuestRequirements() {
        Map<String, YamlConfiguration> files = defaults();
        var rows = files.get("titles.yml").getMapList("titles");
        var first = new java.util.LinkedHashMap<String, Object>();
        rows.getFirst().forEach((key, value) -> first.put(String.valueOf(key), value));
        first.put("requirements", java.util.List.of());
        rows.set(0, first);
        files.get("titles.yml").set("titles", rows);
        ConfigLoader.LoadResult result = load(files);
        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("title quest requirement")));
    }

    @Test
    void activationRelicRequiresKnownNonAirMaterialAndName() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("config.yml").set("activation-item.material", "NOT_A_MATERIAL");
        files.get("config.yml").set("activation-item.display-name", " ");
        files.get("config.yml").set("activation-item.glint", "yes");

        ConfigLoader.LoadResult result = load(files);

        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("activation-item.material")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("activation-item.display-name")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("activation-item.glint")));
    }

    @Test
    void retiredTitleRowsUpgradeWithoutChangingOtherCustomRows() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = configured.getMapList("titles");
        String[] retired = {"phantom", "echo_walker", "forgeheart", "duelist", "bulwark", "harvester"};
        int[] slots = {6, 11, 18, 21, 22, 26};
        for (int i = 0; i < slots.length; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) rows.get(slots[i]);
            row.put("id", retired[i]);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> custom = (Map<String, Object>) rows.get(2).get("ability");
        custom.put("cooldown-seconds", 99);
        configured.set("titles", rows);

        assertEquals(6, ConfigLoader.migrateRetiredTitles(configured, bundled));
        assertEquals(0, ConfigLoader.migrateRetiredTitles(configured, bundled));
        assertEquals(99, ((Map<?, ?>) configured.getMapList("titles").get(2).get("ability"))
                .get("cooldown-seconds"));
        Map<String, YamlConfiguration> files = defaults();
        files.put("titles.yml", configured);
        assertTrue(load(files).ok());
    }

    @Test
    void currentBundledTitlesNeedNoLegacyMigration() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        assertEquals(0, ConfigLoader.migrateRetiredTitles(configured, bundled));
        assertEquals(0, ConfigLoader.mergeLegacyTitleDescriptions(configured, bundled));
        for (Map<?, ?> row : configured.getMapList("titles")) {
            YamlConfiguration single = new YamlConfiguration();
            single.set("titles", java.util.List.of(row));
            assertEquals(0, ConfigLoader.migrateTitleBalance(single, bundled), String.valueOf(row.get("id")));
        }
    }

    @Test
    void healthDescriptionsUpgradeOnceButCustomDescriptionsRemainUntouched() {
        YamlConfiguration abilities = defaults().get("abilities.yml");
        assertFalse(ConfigLoader.migrateGoldHealthDescription(abilities));
        abilities.set("rank-abilities.gold_2.description",
                "Regeneration I for 5 seconds when below 40% health (45 s cooldown)");
        assertTrue(ConfigLoader.migrateGoldHealthDescription(abilities));
        assertFalse(ConfigLoader.migrateGoldHealthDescription(abilities));
        abilities.set("rank-abilities.gold_2.description", "My custom Gold ability");
        assertFalse(ConfigLoader.migrateGoldHealthDescription(abilities));

        YamlConfiguration titles = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = titles.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> bloodlord = (Map<String, Object>) rows.get(5).get("ability");
        bloodlord.put("description",
                "Below half health, automatically heal 20% of valid PvP damage dealt, up to 3 HP. No stick required.");
        @SuppressWarnings("unchecked")
        Map<String, Object> beastbane = (Map<String, Object>) rows.get(17).get("ability");
        beastbane.put("description",
                "Automatically strike a hostile mob or eligible player at 8 HP or less for 14 damage.");
        titles.set("titles", rows);
        assertEquals(2, ConfigLoader.migrateTitleBalance(titles, bundled));
        assertEquals(0, ConfigLoader.migrateTitleBalance(titles, bundled));
        assertEquals("My custom Gold ability", abilities.getString("rank-abilities.gold_2.description"));
    }

    @Test
    void unsupportedBuiltInTitleIdIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        java.util.List<Map<?, ?>> titles = files.get("titles.yml").getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> changed = (Map<String, Object>) titles.get(0);
        changed.put("id", "custom_title");
        files.get("titles.yml").set("titles", titles);

        ConfigLoader.LoadResult result = load(files);

        assertFalse(result.ok());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("built-in title ids")));
    }

    @Test
    void everyChronicleMessageKeyIsBundled() {
        ConfigSnapshot snapshot = load(defaults()).snapshot();
        for (String key : java.util.List.of(
                "menu.main-help", "menu.help-title", "menu.help-1", "menu.help-6",
                "menu.previous", "menu.next", "menu.title-page", "menu.title-ability",
                "menu.title-requirement", "menu.title-requirement-done", "menu.title-claim",
                "menu.title-equip", "menu.title-unequip", "menu.submit", "menu.submit-lore",
                "menu.help-7", "ability-item-received", "ability-item-pending",
                "ability-item-cannot-move", "ability-no-condition", "ability-passive")) {
            assertTrue(snapshot.messages().containsKey(key), () -> "missing bundled message: " + key);
        }
    }

    @Test
    void missingUpgradeMessagesUseBundledDefaultsWithoutReplacingCustomText() {
        YamlConfiguration configured = new YamlConfiguration();
        configured.set("menu.main-help", "My custom help");
        configured.set("menu.title-equip", "   ");
        YamlConfiguration bundled = defaults().get("messages.yml");

        int added = ConfigLoader.mergeMissingStrings(configured, bundled);

        assertTrue(added > 0);
        assertEquals("My custom help", configured.getString("menu.main-help"));
        assertEquals("<yellow>Next", configured.getString("menu.next"));
        assertEquals("<green>Equip title", configured.getString("menu.title-equip"));
    }

    @Test
    void legacyCrouchInstructionsUpgradeWithoutOverwritingCustomAbilityText() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = configured.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> titanAbility = (Map<String, Object>) rows.get(7).get("ability");
        titanAbility.put("description", "Crouching after a fall creates a damaging knockback wave.");
        @SuppressWarnings("unchecked")
        Map<String, Object> ascendantAbility = (Map<String, Object>) rows.get(0).get("ability");
        ascendantAbility.put("description", "My custom combat form description.");
        configured.set("titles", rows);

        assertEquals(1, ConfigLoader.mergeLegacyTitleDescriptions(configured, bundled));
        java.util.List<Map<?, ?>> updated = configured.getMapList("titles");
        assertEquals("Right-click the Ascension Relic to slam eligible targets within 3 blocks for 13 hearts of raw damage and knockback. Armor and protection still apply.",
                ((Map<?, ?>) updated.get(7).get("ability")).get("description"));
        assertEquals("My custom combat form description.",
                ((Map<?, ?>) updated.get(0).get("ability")).get("description"));
    }

    @Test
    void stockStickDescriptionsBecomeRelicInstructionsButCustomWordingIsPreserved() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        var rows = configured.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> stock = (Map<String, Object>) rows.get(0).get("ability");
        stock.put("description", "Right-click the title ability stick for Strength V, Speed III and Resistance IV for 30 seconds.");
        @SuppressWarnings("unchecked")
        Map<String, Object> custom = (Map<String, Object>) rows.get(1).get("ability");
        custom.put("description", "Bring your own title ability stick to this custom ritual.");
        configured.set("titles", rows);

        assertEquals(1, ConfigLoader.mergeLegacyTitleDescriptions(configured, bundled));
        assertTrue(String.valueOf(((Map<?, ?>) configured.getMapList("titles").get(0).get("ability"))
                .get("description")).contains("Ascension Relic"));
        assertEquals("Bring your own title ability stick to this custom ritual.",
                ((Map<?, ?>) configured.getMapList("titles").get(1).get("ability")).get("description"));
    }

    @Test
    void existingBloodlordTitleUpgradesToEveryHitWithoutCooldown() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        var rows = configured.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> bloodlord = (Map<String, Object>) rows.get(5).get("ability");
        bloodlord.put("description",
                "Below half effective health (red plus yellow hearts), automatically heal 20% of valid PvP damage dealt, up to 3 HP. No stick required.");
        bloodlord.put("cooldown-seconds", 4);
        configured.set("titles", rows);

        assertEquals(2, ConfigLoader.migrateTitleBalance(configured, bundled));
        assertEquals(0, ConfigLoader.migrateTitleBalance(configured, bundled));
        Map<?, ?> upgraded = (Map<?, ?>) configured.getMapList("titles").get(5).get("ability");
        assertEquals(0, upgraded.get("cooldown-seconds"));
        assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(5).get("ability")).get("description"),
                upgraded.get("description"));
    }

    @Test
    void priorStormAndTitanDefaultsUpgradeOnceWhileCustomDamageSurvives() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = configured.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> storm = (Map<String, Object>) rows.get(2).get("ability");
        storm.put("description", "Right-click the title ability stick to strike the closest eligible player within 50 blocks with lightning.");
        storm.put("settings", Map.of("player-damage-health-points", 12.0));
        @SuppressWarnings("unchecked")
        Map<String, Object> titan = (Map<String, Object>) rows.get(7).get("ability");
        titan.put("description", "Right-click the title ability stick to create a damaging ground-slam wave.");
        titan.put("settings", Map.of("radius", 3.0, "combat-damage-health-points", 9.0, "knockback", 1.4));
        configured.set("titles", rows);

        assertEquals(4, ConfigLoader.migrateTitleBalance(configured, bundled));
        assertEquals(0, ConfigLoader.migrateTitleBalance(configured, bundled));
        var updated = configured.getMapList("titles");
        assertEquals(26.0, ((Map<?, ?>) ((Map<?, ?>) updated.get(2).get("ability")).get("settings"))
                .get("player-damage-health-points"));
        assertEquals(26.0, ((Map<?, ?>) ((Map<?, ?>) updated.get(7).get("ability")).get("settings"))
                .get("combat-damage-health-points"));
        assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(2).get("ability")).get("description"),
                ((Map<?, ?>) updated.get(2).get("ability")).get("description"));
        assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(7).get("ability")).get("description"),
                ((Map<?, ?>) updated.get(7).get("ability")).get("description"));

        YamlConfiguration custom = defaults().get("titles.yml");
        var customRows = custom.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> customStorm = (Map<String, Object>) customRows.get(2).get("ability");
        customStorm.put("settings", Map.of("player-damage-health-points", 18.0));
        customStorm.put("description", "Custom storm text");
        custom.set("titles", customRows);
        assertEquals(0, ConfigLoader.migrateTitleBalance(custom, bundled));
        assertEquals(18.0, ((Map<?, ?>) ((Map<?, ?>) custom.getMapList("titles").get(2).get("ability"))
                .get("settings")).get("player-damage-health-points"));
        assertEquals("Custom storm text", ((Map<?, ?>) custom.getMapList("titles").get(2).get("ability"))
                .get("description"));
    }

    @Test
    void oldCombatDescriptionsUpgradeWithoutReplacingCustomText() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = configured.getMapList("titles");
        String[] old = {
                "Right-click the title ability stick to enter a powerful temporary combat form.",
                "Right-click the title ability stick to strengthen yourself and weaken nearby enemies.",
                "Right-click the title ability stick to strike the closest eligible player with lightning."};
        for (int i = 0; i < old.length; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ability = (Map<String, Object>) rows.get(i).get("ability");
            ability.put("description", old[i]);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> bloodlord = (Map<String, Object>) rows.get(5).get("ability");
        bloodlord.put("description", "Heal for 25% of actual PvP damage dealt, capped per hit.");
        configured.set("titles", rows);

        assertEquals(4, ConfigLoader.mergeLegacyTitleDescriptions(configured, bundled));
        java.util.List<Map<?, ?>> updated = configured.getMapList("titles");
        for (int i = 0; i < old.length; i++) {
            assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(i).get("ability")).get("description"),
                    ((Map<?, ?>) updated.get(i).get("ability")).get("description"));
        }
        assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(5).get("ability")).get("description"),
                ((Map<?, ?>) updated.get(5).get("ability")).get("description"));
    }

    @Test
    void legacyVoidwalkerConfigurationGetsAimedTeleportDefaults() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = configured.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> ability = (Map<String, Object>) rows.get(4).get("ability");
        ability.put("description", "Right-click the title ability stick to teleport up to 8 safe blocks forward.");
        ability.put("cooldown-seconds", 30);
        ability.put("settings", Map.of("range", 8.0));
        configured.set("titles", rows);

        assertEquals(4, ConfigLoader.mergeLegacyTitleDescriptions(configured, bundled));
        Map<?, ?> updated = (Map<?, ?>) configured.getMapList("titles").get(4).get("ability");
        assertEquals(60, updated.get("cooldown-seconds"));
        assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(4).get("ability")).get("description"),
                updated.get("description"));
        Map<?, ?> settings = (Map<?, ?>) updated.get("settings");
        assertEquals(32.0, settings.get("aim-range"));
        assertEquals(60.0, settings.get("charge-recharge-seconds"));
    }

    @Test
    void priorThreeCastVoidwalkerConfigMigratesToRollingCharges() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = configured.getMapList("titles");
        @SuppressWarnings("unchecked")
        Map<String, Object> ability = (Map<String, Object>) rows.get(4).get("ability");
        ability.put("description", "Point at a safe landing spot and right-click the title ability stick to teleport. Three uses, then a 100-second recharge.");
        ability.put("cooldown-seconds", 100);
        ability.put("settings", Map.of("aim-range", 32.0, "recharge-seconds", 100.0));
        configured.set("titles", rows);

        assertEquals(3, ConfigLoader.mergeLegacyTitleDescriptions(configured, bundled));
        Map<?, ?> updated = (Map<?, ?>) configured.getMapList("titles").get(4).get("ability");
        assertEquals(60, updated.get("cooldown-seconds"));
        assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(4).get("ability")).get("description"),
                updated.get("description"));
        Map<?, ?> settings = (Map<?, ?>) updated.get("settings");
        assertEquals(60.0, settings.get("charge-recharge-seconds"));
        assertFalse(settings.containsKey("recharge-seconds"));
    }

    @Test
    void revisedLowerTitleDescriptionsUpgradeInMemory() {
        YamlConfiguration configured = defaults().get("titles.yml");
        YamlConfiguration bundled = defaults().get("titles.yml");
        java.util.List<Map<?, ?>> rows = configured.getMapList("titles");
        Map<Integer, String> old = Map.of(
                12, "While Withered, right-click the title ability stick to purge it with a dark pulse.",
                23, "While burning, right-click the title ability stick to extinguish and release a fire pulse.",
                27, "The first attacked hostile mob is marked and takes increased owner damage for 8 seconds.",
                29, "Killing a hostile mob restores half a heart.");
        for (Map.Entry<Integer, String> entry : old.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ability = (Map<String, Object>) rows.get(entry.getKey()).get("ability");
            ability.put("description", entry.getValue());
        }
        configured.set("titles", rows);

        assertEquals(old.size(), ConfigLoader.mergeLegacyTitleDescriptions(configured, bundled));
        java.util.List<Map<?, ?>> updated = configured.getMapList("titles");
        for (int index : old.keySet()) {
            assertEquals(((Map<?, ?>) bundled.getMapList("titles").get(index).get("ability")).get("description"),
                    ((Map<?, ?>) updated.get(index).get("ability")).get("description"));
        }
    }

    @Test
    void specDefaultsAreEncoded() {
        ConfigSnapshot s = load(defaults()).snapshot();
        QuestDefinition bronze1 = s.quests().get(0);
        assertEquals(3, bronze1.objectives().size());
        assertEquals(ObjectiveType.GAIN_LEVELS, bronze1.objective("xp").type());
        assertEquals(30, bronze1.objective("xp").amount());

        ObjectiveDefinition pvp = s.quests().get(10).objective("pvp");
        assertEquals(20, pvp.amount());
        assertEquals(10, pvp.unique(), "Platinum II follows the master requirement");

        assertEquals(750, s.quests().get(11).objective("xp").amount());
        assertTrue(s.quests().get(9).objective("wither").requireSummon());
        assertTrue(s.quests().get(8).objective("head").targets().contains("DRAGON_HEAD"));
    }

    @Test
    void noDefaultQuestMentionsTheEnderDragon() {
        ConfigSnapshot s = load(defaults()).snapshot();
        for (QuestDefinition q : s.quests().values()) {
            for (ObjectiveDefinition o : q.objectives()) {
                assertFalse(o.targets().contains("ENDER_DRAGON"), q.stageId() + "/" + o.id());
            }
        }
    }

    @Test
    void rankAbilitiesParseComponents() {
        ConfigSnapshot s = load(defaults()).snapshot();
        RankAbility p3 = s.rankAbilities().get("platinum_3");
        assertEquals(2, p3.components().size());
        assertEquals(new RankAbility.HealthBonus(2.0), p3.components().get(0));
        RankAbility.TriggeredEffect t = (RankAbility.TriggeredEffect) p3.components().get(1);
        assertEquals(RankAbility.Trigger.LOOK_DOWN_CROUCH, t.trigger());
        assertEquals(new RankAbility.EffectSpec("resistance", 0), t.effects().get(0));

        RankAbility.EffectPulse pulse = (RankAbility.EffectPulse) s.rankAbilities().get("bronze_1").components().get(0);
        assertEquals(5, pulse.durationSeconds());
        assertEquals(25, pulse.intervalSeconds());
    }

    @Test
    void questMineTargetsAreAddedToPlacedBlockTracking() {
        ConfigSnapshot s = load(defaults()).snapshot();
        assertTrue(s.settings().trackedPlacedBlocks().contains("ANCIENT_DEBRIS"));
        assertTrue(s.settings().trackedPlacedBlocks().contains("STONE"));
    }

    @Test
    void unknownObjectiveTypeIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("quests.yml").set("bronze_1.objectives.xp.type", "gain-happiness");
        ConfigLoader.LoadResult r = load(files);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("quests.yml") && e.contains("bronze_1.objectives.xp")),
                () -> r.errors().toString());
    }

    @Test
    void nonPositiveAmountIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("quests.yml").set("bronze_1.objectives.xp.amount", 0);
        assertFalse(load(files).ok());
    }

    @Test
    void uniqueAboveAmountIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("quests.yml").set("platinum_2.objectives.pvp.unique", 25);
        assertFalse(load(files).ok());
    }

    @Test
    void everyStageNeedsAQuest() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("quests.yml").set("gold_2", null);
        ConfigLoader.LoadResult r = load(files);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("gold_2")), () -> r.errors().toString());
    }

    @Test
    void unknownAbilityReferenceIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("abilities.yml").set("rank-abilities.gold_1", null);
        assertFalse(load(files).ok());
    }

    @Test
    void unknownMaterialIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("quests.yml").set("bronze_1.objectives.stone.targets", java.util.List.of("NOT_A_BLOCK"));
        assertFalse(load(files).ok());
    }

    @Test
    void unknownEffectIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("abilities.yml").set("rank-abilities.bronze_1.components",
                java.util.List.of(Map.of("type", "effect-pulse", "effects", java.util.List.of("not_real:1"),
                        "duration-seconds", 5, "interval-seconds", 25)));
        assertFalse(load(files).ok());
    }

    @Test
    void pulseLongerThanIntervalIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("abilities.yml").set("rank-abilities.bronze_1.components",
                java.util.List.of(Map.of("type", "effect-pulse", "effects", java.util.List.of("haste:1"),
                        "duration-seconds", 30, "interval-seconds", 25)));
        assertFalse(load(files).ok());
    }

    @Test
    void duplicateStageIdIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        var stages = new java.util.ArrayList<>(files.get("ranks.yml").getMapList("stages"));
        stages.add(stages.get(0));
        files.get("ranks.yml").set("stages", stages);
        assertFalse(load(files).ok());
    }

    @Test
    void badTimezoneIsRejected() {
        Map<String, YamlConfiguration> files = defaults();
        files.get("config.yml").set("timezone", "Mars/Olympus");
        assertFalse(load(files).ok());
    }

    @Test
    void missingFileIsReported() {
        Map<String, YamlConfiguration> files = defaults();
        files.remove("quests.yml");
        ConfigLoader.LoadResult r = load(files);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("quests.yml")));
    }
}
