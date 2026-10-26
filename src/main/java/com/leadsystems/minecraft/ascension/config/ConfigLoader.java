package com.leadsystems.minecraft.ascension.config;

import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveDefinition;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveType;
import com.leadsystems.minecraft.ascension.domain.quest.QuestDefinition;
import com.leadsystems.minecraft.ascension.domain.rank.RankLadder;
import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import com.leadsystems.minecraft.ascension.domain.title.Requirement;
import com.leadsystems.minecraft.ascension.domain.title.TitleCatalog;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityRegistry;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleLevels;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates every config file into one {@link ConfigSnapshot}. Every problem is
 * collected (with file and path) rather than stopping at the first, so an admin can fix them all
 * in one pass. A result with any error has no snapshot and must not be applied.
 */
public final class ConfigLoader {

    /** Files loaded from the plugin folder, in load order. */
    public static final List<String> FILES = List.of("config.yml", "ranks.yml", "quests.yml", "abilities.yml", "titles.yml",
            "title-levels.yml", "messages.yml", "storage.yml", "integrations.yml");

    private static final Set<String> REQUIRED = Set.of("config.yml", "ranks.yml", "quests.yml", "abilities.yml", "titles.yml");
    private static final Set<String> CATEGORIES = Set.of("hostile", "nether", "illager");
    private static final Set<String> BOSSES = Set.of("WARDEN", "WITHER", "ELDER_GUARDIAN", "RAVAGER");
    private static final long MAX_AMOUNT = 1_000_000L;
    private static final Map<String, String> OLD_TITLE_DESCRIPTIONS = Map.ofEntries(
            Map.entry("ascendant", "Right-click the title ability stick to enter a powerful temporary combat form."),
            Map.entry("conqueror", "Right-click the title ability stick to strengthen yourself and weaken nearby enemies."),
            Map.entry("voidwalker", "Right-click the title ability stick to teleport up to 8 safe blocks forward."),
            Map.entry("stormbringer", "Right-click the title ability stick to strike the closest eligible player with lightning."),
            Map.entry("bloodlord", "Heal for 25% of actual PvP damage dealt, capped per hit."),
            Map.entry("ashborn", "While burning, right-click the title ability stick to extinguish and release a fire pulse."),
            Map.entry("witherbound", "While Withered, right-click the title ability stick to purge it with a dark pulse."),
            Map.entry("huntsman", "The first attacked hostile mob is marked and takes increased owner damage for 8 seconds."),
            Map.entry("bulwark", "Absorb the next two hearts of incoming damage; recharge after 35 seconds."),
            Map.entry("new_arrival", "Killing a hostile mob restores half a heart."));
    private static final Map<String, String> OLD_CROUCH_DESCRIPTIONS = Map.of(
            "titan", "Crouching after a fall creates a damaging knockback wave.");

    /**
     * Outcome of a load.
     *
     * @param snapshot the validated config, or null if there were errors
     * @param errors   problems that block applying the config
     * @param warnings problems that were tolerated
     */
    public record LoadResult(ConfigSnapshot snapshot, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final NameResolver names;
    private List<String> errors;
    private List<String> warnings;

    public ConfigLoader(NameResolver names) {
        this.names = names;
    }

    /** Reads every file from the folder. YAML syntax errors become load errors. */
    public static Map<String, YamlConfiguration> readFolder(File folder, List<String> syntaxErrors) {
        Map<String, YamlConfiguration> out = new HashMap<>();
        for (String name : FILES) {
            File f = new File(folder, name);
            if (!f.isFile()) {
                continue;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(f);
                out.put(name, yaml);
            } catch (IOException | InvalidConfigurationException e) {
                syntaxErrors.add(name + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** Validates and builds a snapshot. Not thread-safe (use one loader per load). */
    public synchronized LoadResult load(Map<String, YamlConfiguration> files) {
        errors = new ArrayList<>();
        warnings = new ArrayList<>();
        for (String name : FILES) {
            if (!files.containsKey(name)) {
                if (REQUIRED.contains(name)) {
                    errors.add(name + ": file is missing");
                } else {
                    warnings.add(name + ": file is missing, using built-in defaults");
                    files.put(name, new YamlConfiguration());
                }
            }
        }
        if (!errors.isEmpty()) {
            return new LoadResult(null, List.copyOf(errors), List.copyOf(warnings));
        }

        YamlConfiguration cfg = files.get("config.yml");
        YamlConfiguration ranks = files.get("ranks.yml");
        YamlConfiguration quests = files.get("quests.yml");
        YamlConfiguration abilities = files.get("abilities.yml");

        Map<String, RankAbility> rankAbilities = parseRankAbilities(abilities);
        RankLadder ladder = parseLadder(ranks, rankAbilities);
        Map<Integer, QuestDefinition> questMap = ladder == null ? Map.of() : parseQuests(quests, ladder);
        TitleCatalog titles = parseTitles(files.get("titles.yml"));
        Settings settings = parseSettings(cfg, questMap);
        TitleLevels titleLevels = parseTitleLevels(files.get("title-levels.yml"));

        Map<String, String> messages = new LinkedHashMap<>();
        YamlConfiguration msg = files.get("messages.yml");
        for (String key : msg.getKeys(true)) {
            if (msg.isString(key)) {
                messages.put(key, msg.getString(key));
            }
        }

        YamlConfiguration storage = files.get("storage.yml");
        String type = storage.getString("type", "sqlite").toLowerCase(Locale.ROOT);
        if (!type.equals("sqlite")) {
            errors.add("storage.yml: type '" + type + "' is not supported yet (use sqlite)");
        }
        ConfigSnapshot.StorageSettings storageSettings = new ConfigSnapshot.StorageSettings(type,
                storage.getString("sqlite.file", "data/ascension.db"),
                storage.getString("backups.directory", "backups"));

        boolean bedrockForms = files.get("integrations.yml").getBoolean("floodgate.bedrock-forms", true);

        if (!errors.isEmpty() || ladder == null || settings == null) {
            return new LoadResult(null, List.copyOf(errors), List.copyOf(warnings));
        }
        ConfigSnapshot snapshot = new ConfigSnapshot(settings, ladder, questMap, rankAbilities, titles,
                Math.max(1, files.get("titles.yml").getInt("ownership-limit", 5)),
                ranks.getBoolean("stack-previous-abilities", false),
                ranks.getBoolean("announce-rank-ups", true),
                ranks.getString("mastery-name", "Mastery"),
                messages, storageSettings, bedrockForms,
                cfg.getBoolean("testing.unlock-all-ranks-and-titles", false), titleLevels);
        validateTitleLevels(snapshot);
        if (!errors.isEmpty()) return new LoadResult(null, List.copyOf(errors), List.copyOf(warnings));
        return new LoadResult(snapshot, List.copyOf(errors), List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ title-levels.yml

    private TitleLevels parseTitleLevels(YamlConfiguration yaml) {
        TitleLevels defaults = TitleLevels.defaults();
        if (yaml.getKeys(false).isEmpty()) return defaults;
        rejectUnknownKeys(yaml, Set.of("thresholds", "rewards", "profiles"), "title-levels.yml");
        ConfigurationSection thresholds = yaml.getConfigurationSection("thresholds");
        if (yaml.contains("thresholds") && thresholds == null) errors.add("title-levels.yml: thresholds must be a section");
        if (thresholds != null) rejectUnknownKeys(thresholds, Set.of("level-2", "level-3"), "title-levels.yml: thresholds");
        long second = checkedLong(yaml, "thresholds.level-2", defaults.level2Xp(), 1, 1_000_000_000_000L);
        long third = checkedLong(yaml, "thresholds.level-3", defaults.level3Xp(), 1, 1_000_000_000_000L);
        if (third <= second) errors.add("title-levels.yml: thresholds.level-3 must exceed level-2");
        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (yaml.contains("rewards") && rewards == null) errors.add("title-levels.yml: rewards must be a section");
        if (rewards != null) rejectUnknownKeys(rewards, Set.of("natural-block", "smelted-item", "hostile-mob", "fishing-catch", "player-kill", "hunt", "raid", "boss"), "title-levels.yml: rewards");
        TitleLevels.RewardWeights weights = new TitleLevels.RewardWeights(
                checkedLong(yaml, "rewards.natural-block", 1, 0, 1_000_000),
                checkedLong(yaml, "rewards.smelted-item", 1, 0, 1_000_000),
                checkedLong(yaml, "rewards.hostile-mob", 5, 0, 1_000_000),
                checkedLong(yaml, "rewards.fishing-catch", 5, 0, 1_000_000),
                checkedLong(yaml, "rewards.player-kill", 50, 0, 1_000_000),
                checkedLong(yaml, "rewards.hunt", 100, 0, 1_000_000),
                checkedLong(yaml, "rewards.raid", 100, 0, 1_000_000),
                checkedLong(yaml, "rewards.boss", 200, 0, 1_000_000));
        Map<String, Map<Integer, TitleLevels.Upgrade>> profiles = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("profiles");
        if (root == null) {
            errors.add("title-levels.yml: profiles must contain all 30 title profiles");
            return defaults;
        }
        rejectUnknownKeys(root, TitleAbilityRegistry.ALL, "title-levels.yml: profiles");
        for (String id : TitleAbilityRegistry.ALL) {
            ConfigurationSection profile = root.getConfigurationSection(id);
            if (profile == null) {
                errors.add("title-levels.yml: profiles." + id + " is required");
                continue;
            }
            rejectUnknownKeys(profile, Set.of("2", "3"), "title-levels.yml: profiles." + id);
            Map<Integer, TitleLevels.Upgrade> levels = new LinkedHashMap<>();
            for (int level = 2; level <= 3; level++) {
                String path = "profiles." + id + "." + level;
                ConfigurationSection upgrade = yaml.getConfigurationSection(path);
                if (upgrade == null) {
                    errors.add("title-levels.yml: " + path + " is required");
                    continue;
                }
                rejectUnknownKeys(upgrade, Set.of("cooldown-delta-seconds", "settings-deltas"), "title-levels.yml: " + path);
                int cooldown = (int) checkedLong(yaml, path + ".cooldown-delta-seconds", 0, -86400, 86400);
                Map<String, Double> deltas = new LinkedHashMap<>();
                ConfigurationSection settings = upgrade.getConfigurationSection("settings-deltas");
                if (settings == null && upgrade.contains("settings-deltas")) {
                    errors.add("title-levels.yml: " + path + ".settings-deltas must be a section");
                } else if (settings != null) {
                    for (String key : settings.getKeys(false)) {
                        Object raw = settings.get(key);
                        double value = raw instanceof Number number ? number.doubleValue() : Double.NaN;
                        if (!Double.isFinite(value) || Math.abs(value) > 1_000_000) {
                            errors.add("title-levels.yml: " + path + ".settings-deltas." + key + " must be a finite numeric delta between -1000000 and 1000000");
                        } else deltas.put(key, value);
                    }
                }
                levels.put(level, new TitleLevels.Upgrade(cooldown, deltas));
            }
            profiles.put(id, levels);
        }
        return new TitleLevels(second, third > second ? third : second + 1, weights, profiles);
    }

    private void rejectUnknownKeys(ConfigurationSection section, Set<String> known, String where) {
        for (String key : section.getKeys(false)) {
            if (!known.contains(key)) errors.add(where + "." + key + ": unknown key");
        }
    }

    private long checkedLong(YamlConfiguration yaml, String path, long fallback, long minimum, long maximum) {
        if (!yaml.contains(path)) return fallback;
        Object raw = yaml.get(path);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.longValue() || number.longValue() < minimum || number.longValue() > maximum) {
            errors.add("title-levels.yml: " + path + " must be an integer between " + minimum + " and " + maximum);
            return fallback;
        }
        return number.longValue();
    }

    private void validateTitleLevels(ConfigSnapshot snapshot) {
        for (TitleDefinition title : snapshot.titles().ordered()) {
            Map<String, Double> baseline = TitleAbilityResolver.levelOneSettings(snapshot, title);
            for (int level = 2; level <= 3; level++) {
                TitleLevels.Upgrade upgrade = snapshot.titleLevels().profiles().getOrDefault(title.id(), Map.of()).get(level);
                if (upgrade == null) continue;
                String where = "title-levels.yml: profiles." + title.id() + "." + level;
                long cooldown = (long) title.abilityCooldownSeconds() + upgrade.cooldownDeltaSeconds();
                if (title.abilityCooldownSeconds() < 0 || title.abilityCooldownSeconds() > TitleAbilityResolver.MAX_COOLDOWN_SECONDS) {
                    errors.add("titles.yml: titles." + title.id() + ".ability.cooldown-seconds is outside supported 0..604800 seconds");
                } else if (cooldown < 0 || cooldown > TitleAbilityResolver.MAX_COOLDOWN_SECONDS) {
                    if (snapshot.titleLevels().stockCooldownDelta(title.id(), level)) {
                        warnings.add(where + ".cooldown-delta-seconds: stock upgrade saturates at supported cooldown limit; level one unchanged");
                    } else errors.add(where + ".cooldown-delta-seconds produces a cooldown outside 0..604800 seconds");
                }
                for (Map.Entry<String, Double> entry : upgrade.settingsDeltas().entrySet()) {
                    String key = entry.getKey();
                    if (!TitleAbilityResolver.knownSetting(title.id(), key)) {
                        errors.add(where + ".settings-deltas." + key + ": unknown ability setting");
                        continue;
                    }
                    double base = baseline.get(key);
                    double value = base + entry.getValue();
                    TitleAbilityResolver.SettingBounds bounds = TitleAbilityResolver.settingBounds(title.id(), key);
                    if (!bounds.valid(base)) {
                        errors.add("titles.yml: titles." + title.id() + ".ability.settings." + key + ": invalid effective level-one baseline");
                    } else if (!bounds.valid(value)) {
                        if (snapshot.titleLevels().stockSettingDelta(title.id(), level, key)) {
                            warnings.add(where + ".settings-deltas." + key + ": stock upgrade saturates at supported limit; level one unchanged");
                        } else errors.add(where + ".settings-deltas." + key + " produces an invalid effective value (allowed " + bounds.minimum() + ".." + bounds.maximum() + (bounds.integer() ? ", integer" : "") + ")");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ titles.yml

    private TitleCatalog parseTitles(YamlConfiguration yaml) {
        List<Map<?, ?>> rows = yaml.getMapList("titles");
        if (rows.isEmpty()) {
            errors.add("titles.yml: titles must be a non-empty list");
            return new TitleCatalog(List.of());
        }
        List<TitleDefinition> out = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int row = 0; row < rows.size(); row++) {
            Map<?, ?> section = rows.get(row);
            String id = str(section, "id");
            String where = "titles.yml: titles." + id;
            if (id == null || id.isBlank() || !ids.add(id.toLowerCase(Locale.ROOT))) {
                errors.add("titles.yml: titles[" + row + "]: id must be present and unique");
                continue;
            }
            int number = intVal(section.get("number"), -1);
            if (number < 1 || number > 30 || !numbers.add(number)) {
                errors.add(where + ": number must be unique and between 1 and 30");
                continue;
            }
            List<Requirement> requirements = parseRequirements(mapList(section.get("requirements")), where);
            if (requirements.isEmpty()) errors.add(where + ".requirements must contain at least one title quest requirement");
            Map<?, ?> ability = section.get("ability") instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Double> abilitySettings = new LinkedHashMap<>();
            if (ability.get("settings") instanceof Map<?, ?> configured) {
                for (Map.Entry<?, ?> entry : configured.entrySet()) {
                    double value = doubleVal(entry.getValue(), Double.NaN);
                    if (Double.isFinite(value) && value >= 0) {
                        abilitySettings.put(String.valueOf(entry.getKey()), value);
                    } else {
                        errors.add(where + ".ability.settings." + entry.getKey() + ": must be a non-negative number");
                    }
                }
            }
            String abilityName = value(ability, "name", "");
            String abilityDescription = value(ability, "description", "");
            if (abilityName.isBlank() || abilityDescription.isBlank()) {
                errors.add(where + ".ability: needs non-blank name and description");
            }
            double configuredCooldown = doubleVal(ability.get("cooldown-seconds"), ability.containsKey("cooldown-seconds") ? Double.NaN : 0);
            if (!Double.isFinite(configuredCooldown) || configuredCooldown < 0
                    || configuredCooldown > TitleAbilityResolver.MAX_COOLDOWN_SECONDS || configuredCooldown != Math.rint(configuredCooldown)) {
                errors.add(where + ".ability.cooldown-seconds: must be an integer between 0 and 604800");
            }
            out.add(new TitleDefinition(id.toLowerCase(Locale.ROOT), number,
                    id.equals("stormbringer") && value(section, "name", id).equals("Stormbringer")
                            ? "Thunder God" : value(section, "name", id), value(section, "color", "#ffffff"),
                    value(section, "description", ""), requirements,
                    abilityName, abilityDescription,
                    Math.max(0, intVal(ability.get("cooldown-seconds"), 0)),
                    abilitySettings,
                    Boolean.parseBoolean(value(section, "auto-award", "false"))));
        }
        if (out.size() != 30 || numbers.size() != 30) {
            errors.add("titles.yml: exactly 30 uniquely numbered titles are required");
        }
        if (!ids.equals(TitleAbilityRegistry.ALL)) {
            Set<String> missing = new HashSet<>(TitleAbilityRegistry.ALL);
            missing.removeAll(ids);
            Set<String> unsupported = new HashSet<>(ids);
            unsupported.removeAll(TitleAbilityRegistry.ALL);
            errors.add("titles.yml: built-in title ids must match; missing=" + missing
                    + ", unsupported=" + unsupported);
        }
        return new TitleCatalog(out);
    }

    private List<Requirement> parseRequirements(List<Map<?, ?>> raw, String where) {
        List<Requirement> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> map = raw.get(i);
            String at = where + ".requirements[" + i + "]";
            String type = str(map, "type");
            String label = str(map, "label");
            if (type == null || label == null || label.isBlank()) {
                errors.add(at + ": needs type and label");
                continue;
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "stat" -> {
                    List<String> keys = stringList(map.get("keys"));
                    long amount = longVal(map.get("amount"), 0);
                    if (keys.isEmpty() || amount <= 0) {
                        errors.add(at + ": stat needs keys and amount > 0");
                    } else {
                        out.add(new Requirement.StatAtLeast(label, keys, amount));
                    }
                }
                case "rank" -> out.add(new Requirement.RankAtLeast(label,
                        intVal(map.get("rank-index"), 0), Boolean.parseBoolean(String.valueOf(map.get("mastery")))));
                case "possess" -> {
                    String material = str(map, "material");
                    if (material == null || !names.isMaterial(material.toUpperCase(Locale.ROOT))) {
                        errors.add(at + ": possess has unknown material '" + material + "'");
                    } else {
                        out.add(new Requirement.Possess(label, material.toUpperCase(Locale.ROOT), str(map, "enchantment")));
                    }
                }
                case "streak" -> {
                    int days = intVal(map.get("days"), 0);
                    if (days < 1) {
                        errors.add(at + ": streak days must be positive");
                    } else {
                        out.add(new Requirement.StreakAtLeast(label, days));
                    }
                }
                default -> errors.add(at + ": unknown requirement type '" + type + "'");
            }
        }
        if (out.isEmpty()) {
            errors.add(where + ": needs at least one valid requirement");
        }
        return out;
    }

    /** Replaces outdated crouch instructions in memory while preserving other customized text. */
    public static int mergeLegacyTitleDescriptions(YamlConfiguration configured, YamlConfiguration bundled) {
        Map<String, String> newDescriptions = new HashMap<>();
        for (Map<?, ?> row : bundled.getMapList("titles")) {
            if (row.get("ability") instanceof Map<?, ?> ability) {
                newDescriptions.put(String.valueOf(row.get("id")), String.valueOf(ability.get("description")));
            }
        }
        int updated = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<?, ?> row : configured.getMapList("titles")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((key, value) -> copy.put(String.valueOf(key), value));
            String id = String.valueOf(row.get("id"));
            if ((TitleAbilityRegistry.isManual(id) || OLD_TITLE_DESCRIPTIONS.containsKey(id))
                    && row.get("ability") instanceof Map<?, ?> ability) {
                String description = String.valueOf(ability.get("description"));
                String replacement = newDescriptions.get(id);
                Map<String, Object> updatedAbility = new LinkedHashMap<>();
                ability.forEach((key, value) -> updatedAbility.put(String.valueOf(key), value));
                // The exact reverse of a bundled relic instruction is an old stock stick
                // instruction. Do not rewrite custom wording which merely mentions a stick.
                String priorStockRelicDescription = replacement == null || !replacement.contains("Ascension Relic") ? null
                        : replacement.replace("Ascension Relic", "title ability stick");
                if (replacement != null && (description.equals(OLD_CROUCH_DESCRIPTIONS.get(id))
                        || description.equals(OLD_TITLE_DESCRIPTIONS.get(id))
                        || description.equals(priorStockRelicDescription)
                        || ("voidwalker".equals(id) && description.equals(
                            "Point at a safe landing spot and right-click the title ability stick to teleport. Three uses, then a 100-second recharge.")))) {
                    updatedAbility.put("description", replacement);
                    updated++;
                }
                if ("voidwalker".equals(id)) {
                    int oldCooldown = intVal(ability.get("cooldown-seconds"), 0);
                    if (oldCooldown == 30 || oldCooldown == 100) {
                        updatedAbility.put("cooldown-seconds", 60);
                        updated++;
                    }
                    Map<String, Object> settings = new LinkedHashMap<>();
                    if (ability.get("settings") instanceof Map<?, ?> oldSettings) {
                        oldSettings.forEach((key, value) -> settings.put(String.valueOf(key), value));
                    }
                    if (!settings.containsKey("aim-range") && doubleVal(settings.get("range"), 8) == 8) {
                        settings.put("aim-range", 32.0);
                        updated++;
                    }
                    if (!settings.containsKey("charge-recharge-seconds")) {
                        settings.put("charge-recharge-seconds", 60.0);
                        updated++;
                    }
                    if (doubleVal(settings.get("recharge-seconds"), -1) == 100) {
                        settings.remove("recharge-seconds");
                    }
                    updatedAbility.put("settings", settings);
                }
                copy.put("ability", updatedAbility);
            }
            rows.add(copy);
        }
        if (updated > 0) {
            configured.set("titles", rows);
        }
        return updated;
    }

    /** Clarifies the built-in Gold II text without replacing an administrator's custom description. */
    public static boolean migrateGoldHealthDescription(YamlConfiguration configured) {
        String path = "rank-abilities.gold_2.description";
        if (!"Regeneration I for 5 seconds when below 40% health (45 s cooldown)".equals(
                configured.getString(path))) {
            return false;
        }
        configured.set(path, "Regeneration I for 5 seconds below 40% effective health (45 s cooldown)");
        return true;
    }

    /** Replaces the six retired built-in rows while preserving every other configured title. */
    public static int migrateRetiredTitles(YamlConfiguration configured, YamlConfiguration bundled) {
        Map<String, Map<String, Object>> replacements = new HashMap<>();
        Map<String, String> retired = Map.of(
                "phantom", "chronomancer", "echo_walker", "frostbound",
                "forgeheart", "emberforge", "duelist", "berserker",
                "bulwark", "colossus", "harvester", "mycelial_drifter");
        for (Map<?, ?> row : bundled.getMapList("titles")) {
            String id = String.valueOf(row.get("id"));
            if (retired.containsValue(id)) {
                Map<String, Object> copy = new LinkedHashMap<>();
                row.forEach((key, value) -> copy.put(String.valueOf(key), value));
                replacements.put(id, copy);
            }
        }
        int changed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<?, ?> row : configured.getMapList("titles")) {
            String replacement = retired.get(String.valueOf(row.get("id")));
            if (replacement != null && replacements.containsKey(replacement)) {
                rows.add(replacements.get(replacement));
                changed++;
            } else {
                Map<String, Object> copy = new LinkedHashMap<>();
                row.forEach((key, value) -> copy.put(String.valueOf(key), value));
                rows.add(copy);
            }
        }
        if (changed > 0) {
            configured.set("titles", rows);
        }
        return changed;
    }

    /** Upgrades known 1.1.5 defaults without replacing administrator-customized values. */
    public static int migrateTitleBalance(YamlConfiguration configured, YamlConfiguration bundled) {
        Map<String, Map<?, ?>> defaults = new HashMap<>();
        for (Map<?, ?> row : bundled.getMapList("titles")) {
            defaults.put(String.valueOf(row.get("id")), row);
        }
        int changed = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<?, ?> row : configured.getMapList("titles")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((key, value) -> copy.put(String.valueOf(key), value));
            String id = String.valueOf(row.get("id"));
            if (!(row.get("ability") instanceof Map<?, ?> ability) || !defaults.containsKey(id)) {
                rows.add(copy);
                continue;
            }
            Map<String, Object> updated = new LinkedHashMap<>();
            ability.forEach((key, value) -> updated.put(String.valueOf(key), value));
            Map<String, Object> settings = new LinkedHashMap<>();
            if (ability.get("settings") instanceof Map<?, ?> oldSettings) {
                oldSettings.forEach((key, value) -> settings.put(String.valueOf(key), value));
            }
            String oldDescription = switch (id) {
                case "ascendant" -> "Right-click the title ability stick for Strength V, Speed III and Resistance X for 30 seconds.";
                case "conqueror" -> "Right-click the title ability stick to gain Strength and give nearby players and hostile mobs Weakness III and Slowness V.";
                case "bloodlord" -> "Automatically drain life on a valid PvP hit; healing needs missing hearts. No stick required.";
                case "end_city_conqueror" -> "Prevent dangerous fall damage and temporarily grant Slow Falling.";
                case "wardens_bane" -> "Greatly reduce one incoming sonic-boom attack.";
                case "raidbreaker" -> "During a raid, right-click the title ability stick to repel nearby illagers.";
                case "tideborn" -> "Underwater, right-click the title ability stick to rush forward and restore oxygen.";
                case "beastbane" -> "Hostile mobs below a configurable health percentage receive a finishing strike.";
                case "alchemist" -> "Thrown potions receive a configurable improvement without item duplication.";
                case "treasure_whisperer" -> "Right-click the title ability stick to highlight the nearest eligible loot container.";
                case "ashborn" -> "Right-click the title ability stick to extinguish yourself and pulse damage to nearby eligible players and hostile mobs.";
                case "angler" -> "Right-click the title ability stick, then cast within 30 seconds for a shorter wait.";
                case "stonebreaker" -> "Periodically break a small connected stone cluster using validated block drops.";
                case "new_arrival" -> "Killing a hostile mob restores one heart.";
                default -> null;
            };
            String priorDefaultDescription = switch (id) {
                case "bloodlord" -> "Below half health, automatically heal 20% of valid PvP damage dealt, up to 3 HP. No stick required.";
                case "beastbane" -> "Automatically strike a hostile mob or eligible player at 8 HP or less for 14 damage.";
                case "stormbringer" -> "Right-click the title ability stick to strike the closest eligible player within 50 blocks with lightning.";
                case "titan" -> "Right-click the title ability stick to create a damaging ground-slam wave.";
                default -> null;
            };
            if ((oldDescription != null && oldDescription.equals(ability.get("description")))
                    || (priorDefaultDescription != null && priorDefaultDescription.equals(ability.get("description")))) {
                Map<?, ?> bundledAbility = (Map<?, ?>) defaults.get(id).get("ability");
                updated.put("description", bundledAbility.get("description"));
                changed++;
            }
            switch (id) {
                case "ascendant" -> changed += upgradeSetting(settings, "resistance-level", 10, 4);
                case "conqueror" -> {
                    changed += upgradeSetting(settings, "weakness-level", 3, 2);
                    changed += upgradeSetting(settings, "slowness-level", 5, 4);
                }
                case "stormbringer" -> {
                    changed += upgradeCooldown(updated, 25, 35);
                    changed += upgradeSetting(settings, "player-damage-health-points", 10, 12);
                    changed += upgradeSetting(settings, "player-damage-health-points", 12, 26);
                }
                case "tempest_herald" -> {
                    changed += upgradeSetting(settings, "combat-damage-health-points", 10, 8);
                    changed += upgradeSetting(settings, "chain-multiplier", 0.6, 1);
                }
                case "bloodlord" -> {
                    if ("Below half effective health (red plus yellow hearts), automatically heal 20% of valid PvP damage dealt, up to 3 HP. No stick required."
                            .equals(updated.get("description"))) {
                        Map<?, ?> bundledAbility = (Map<?, ?>) defaults.get(id).get("ability");
                        updated.put("description", bundledAbility.get("description"));
                        changed++;
                    }
                    // Vampire has no cooldown even if an older installation retained a custom value.
                    if (intVal(updated.get("cooldown-seconds"), -1) != 0) {
                        updated.put("cooldown-seconds", 0);
                        changed++;
                    }
                    changed += addSetting(settings, "lifesteal-ratio", 0.2);
                }
                case "titan" -> {
                    changed += upgradeSetting(settings, "radius", 6, 3);
                    changed += upgradeSetting(settings, "combat-damage-health-points", 12, 9);
                    changed += upgradeSetting(settings, "combat-damage-health-points", 9, 26);
                    changed += upgradeSetting(settings, "knockback", 1, 1.4);
                }
                case "end_city_conqueror" -> {
                    changed += upgradeCooldown(updated, 60, 45);
                    changed += addSetting(settings, "landing-speed-seconds", 2);
                }
                case "wardens_bane" -> {
                    changed += upgradeCooldown(updated, 90, 60);
                    changed += addSetting(settings, "heavy-hit-threshold-health-points", 16);
                    changed += addSetting(settings, "heavy-hit-multiplier", 0.5);
                }
                case "raidbreaker" -> {
                    changed += addSetting(settings, "illager-damage-health-points", 12);
                    changed += addSetting(settings, "raid-strength-seconds", 8);
                }
                case "tideborn" -> changed += addSetting(settings, "land-force-multiplier", 0.5);
                case "beastbane" -> {
                    if (!settings.containsKey("health-threshold-health-points")) {
                        double old = doubleVal(settings.get("health-threshold"), 0.2);
                        settings.put("health-threshold-health-points", old == 0.2 ? 8.0 : old * 20);
                        changed++;
                    }
                    changed += addSetting(settings, "execute-damage-health-points", 14);
                }
                case "alchemist" -> changed += addSetting(settings, "splash-radius", 5);
                case "treasure_whisperer" -> changed += addSetting(settings, "bonus-chance", 0.05);
                case "ashborn" -> changed += addSetting(settings, "burn-seconds", 4);
                case "angler" -> changed += upgradeCooldown(updated, 30, 20);
                case "stonebreaker" -> changed += addSetting(settings, "auto-smelt-chance", 0.1);
                case "new_arrival" -> {
                    changed += upgradeCooldown(updated, 15, 12);
                    changed += addSetting(settings, "speed-seconds", 2);
                }
                default -> { }
            }
            updated.put("settings", settings);
            copy.put("ability", updated);
            rows.add(copy);
        }
        if (changed > 0) configured.set("titles", rows);
        return changed;
    }

    private static int upgradeCooldown(Map<String, Object> ability, int oldValue, int newValue) {
        if (intVal(ability.get("cooldown-seconds"), -1) != oldValue) return 0;
        ability.put("cooldown-seconds", newValue);
        return 1;
    }

    private static int upgradeSetting(Map<String, Object> settings, String key, double oldValue, double newValue) {
        if (doubleVal(settings.get(key), Double.NaN) != oldValue) return 0;
        settings.put(key, newValue);
        return 1;
    }

    private static int addSetting(Map<String, Object> settings, String key, double value) {
        if (settings.containsKey(key)) return 0;
        settings.put(key, value);
        return 1;
    }

    /** Adds newly bundled string keys without replacing an administrator's existing messages. */
    public static int mergeMissingStrings(YamlConfiguration configured, YamlConfiguration bundled) {
        int added = 0;
        for (String key : bundled.getKeys(true)) {
            String current = configured.getString(key);
            String replacement = bundled.getString(key);
            String oldItemName = switch (key) {
                case "ability-item-received" -> "activation stick";
                case "ability-item-pending", "ability-item-cannot-move" -> "title ability stick";
                default -> "ability stick";
            };
            boolean legacyRelicText = replacement != null && replacement.contains("Ascension Relic")
                    && replacement.replace("Ascension Relic", oldItemName).equals(current);
            if (bundled.isString(key) && (!configured.isString(key) || current == null || current.isBlank()
                    || legacyRelicText)) {
                configured.set(key, bundled.getString(key));
                added++;
            }
        }
        return added;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> mapList(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                out.add(map);
            }
        }
        return out;
    }

    private static String value(Map<?, ?> map, String key, String fallback) {
        String value = str(map, key);
        return value == null ? fallback : value;
    }

    // ------------------------------------------------------------------ config.yml

    private Settings parseSettings(YamlConfiguration c, Map<Integer, QuestDefinition> quests) {
        ZoneId zone = ZoneId.systemDefault();
        String tz = c.getString("timezone", "server");
        if (!tz.equalsIgnoreCase("server")) {
            try {
                zone = ZoneId.of(tz);
            } catch (DateTimeException e) {
                errors.add("config.yml: timezone '" + tz + "' is not a valid time zone id");
            }
        }
        int flush = c.getInt("flush-interval-seconds", 30);
        if (flush < 5 || flush > 600) {
            errors.add("config.yml: flush-interval-seconds must be between 5 and 600");
        }
        int sweep = c.getInt("book.sweep-players-per-second", 2);
        if (sweep < 0 || sweep > 100) {
            errors.add("config.yml: book.sweep-players-per-second must be between 0 and 100");
        }
        Set<String> tracked = upperSet(c.getStringList("progress.tracked-placed-blocks"),
                "config.yml: progress.tracked-placed-blocks", true);
        Set<String> obtain = upperSet(c.getStringList("progress.tracked-obtain-items"),
                "config.yml: progress.tracked-obtain-items", true);
        // Required by bundled title eligibility, including upgrades from configs predating this key.
        obtain.add("ELYTRA");
        obtain.add("DRAGON_HEAD");
        for (QuestDefinition q : quests.values()) {
            for (ObjectiveDefinition o : q.objectives()) {
                if (o.type() == ObjectiveType.MINE_BLOCKS) {
                    tracked.addAll(o.targets());
                } else if (o.type() == ObjectiveType.OBTAIN_ITEMS) {
                    obtain.addAll(o.targets());
                }
            }
        }
        float pitch = (float) c.getDouble("gestures.look-down-min-pitch", 75.0);
        if (pitch < 30 || pitch > 90) {
            errors.add("config.yml: gestures.look-down-min-pitch must be between 30 and 90");
        }
        double participation = c.getDouble("combat.minimum-participation", 0.25);
        if (participation <= 0 || participation > 1) {
            errors.add("config.yml: combat.minimum-participation must be greater than 0 and at most 1");
        }
        String configuredRelicMaterial = c.getString("activation-item.material", "AMETHYST_SHARD");
        String relicMaterial = configuredRelicMaterial == null ? "AMETHYST_SHARD"
                : configuredRelicMaterial.trim().toUpperCase(Locale.ROOT);
        if (!names.isItemMaterial(relicMaterial)) {
            errors.add("config.yml: activation-item.material must be a non-air item material");
        }
        String relicName = c.getString("activation-item.display-name", "Ascension Relic");
        if (relicName == null || relicName.isBlank()) {
            errors.add("config.yml: activation-item.display-name must be non-blank");
        }
        Object configuredGlint = c.get("activation-item.glint");
        if (configuredGlint != null && !(configuredGlint instanceof Boolean)) {
            errors.add("config.yml: activation-item.glint must be true or false");
        }
        return new Settings(
                zone,
                flush,
                c.getString("book.title", "Chronicle of Ascension"),
                c.getString("book.author", "Ascension"),
                Math.max(0, c.getLong("book.open-cooldown-ms", 400)),
                sweep,
                c.getBoolean("progress.ignore-creative-and-spectator", true),
                c.getBoolean("progress.count-spawner-mobs", true),
                upperSet(c.getStringList("progress.hostile-extra"), "config.yml: progress.hostile-extra", false),
                upperSet(c.getStringList("progress.hostile-exclude"), "config.yml: progress.hostile-exclude", false),
                upperSet(c.getStringList("progress.nether-mobs"), "config.yml: progress.nether-mobs", false),
                tracked,
                obtain,
                c.getBoolean("progress.action-bar-updates", true),
                Math.max(100, c.getLong("gestures.double-crouch-window-ms", 400)),
                pitch,
                Math.max(1, c.getInt("combat.window-seconds", 10)),
                Math.max(1, c.getInt("combat.same-victim-cooldown-hours", 24)),
                participation,
                c.getBoolean("combat.reject-same-ip", true),
                Math.max(1, c.getInt("hunts.reroll-offline-hours", 24)),
                Math.max(1, c.getInt("hunts.repeat-avoidance-days", 30)),
                Math.max(1, c.getInt("deathless.required-active-minutes-per-day", 30)),
                Math.max(1, c.getInt("deathless.activity-timeout-minutes", 5)),
                positive(c.getDouble("title-abilities.stormbringer.range", 50.0), 50.0),
                positive(c.getDouble("title-abilities.stormbringer.damage-health-points", 6.0), 6.0),
                fraction(c.getDouble("title-abilities.bloodlord.lifesteal-ratio", 0.20), 0.20),
                positive(c.getDouble("title-abilities.bloodlord.max-heal-health-points", 3.0), 3.0),
                positive(c.getDouble("title-abilities.undying.surviving-health-points", 1.0), 1.0),
                Math.max(1, c.getInt("title-abilities.undying.immunity-seconds", 3)),
                c.getBoolean("effects.particles", false),
                relicMaterial,
                relicName,
                c.getBoolean("activation-item.glint", true));
    }

    /** Upper-cases and validates names as materials ({@code materials}) or entity types. */
    private Set<String> upperSet(List<String> raw, String where, boolean materials) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            String n = s.trim().toUpperCase(Locale.ROOT);
            boolean valid = materials ? names.isMaterial(n) : names.isEntityType(n);
            if (!valid) {
                errors.add(where + ": unknown " + (materials ? "material" : "entity type") + " '" + s + "'");
            } else {
                out.add(n);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ranks.yml

    private RankLadder parseLadder(YamlConfiguration r, Map<String, RankAbility> abilities) {
        List<Map<?, ?>> raw = r.getMapList("stages");
        if (raw.isEmpty()) {
            errors.add("ranks.yml: stages must list at least one stage");
            return null;
        }
        List<RankStage> stages = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> tierStages = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> m = raw.get(i);
            String where = "ranks.yml: stages[" + i + "]";
            String id = str(m, "id");
            if (id == null || id.isBlank()) {
                errors.add(where + ": missing id");
                continue;
            }
            id = id.toLowerCase(Locale.ROOT);
            if (!ids.add(id)) {
                errors.add(where + ": duplicate stage id '" + id + "'");
            }
            String tier = str(m, "tier");
            int stage = intVal(m.get("stage"), -1);
            if (tier == null || stage < 1) {
                errors.add(where + " (" + id + "): needs tier and stage >= 1");
                continue;
            }
            if (!tierStages.add(tier.toLowerCase(Locale.ROOT) + "#" + stage)) {
                errors.add(where + " (" + id + "): duplicate tier/stage " + tier + " " + stage);
            }
            String ability = str(m, "ability");
            ability = ability == null ? id : ability;
            if (!abilities.containsKey(ability)) {
                errors.add(where + " (" + id + "): unknown ability '" + ability + "' (not in abilities.yml rank-abilities)");
            }
            String name = str(m, "name");
            String color = str(m, "color");
            stages.add(new RankStage(stages.size(), id, name == null ? id : name, tier.toLowerCase(Locale.ROOT),
                    stage, color == null ? "#ffffff" : color, ability));
        }
        if (stages.isEmpty()) {
            return null;
        }
        return new RankLadder(stages);
    }

    // ------------------------------------------------------------------ quests.yml

    private Map<Integer, QuestDefinition> parseQuests(YamlConfiguration q, RankLadder ladder) {
        Map<Integer, QuestDefinition> out = new HashMap<>();
        Set<String> stageIds = new HashSet<>();
        for (RankStage stage : ladder.stages()) {
            stageIds.add(stage.id());
            ConfigurationSection objectives = q.getConfigurationSection(stage.id() + ".objectives");
            if (objectives == null || objectives.getKeys(false).isEmpty()) {
                errors.add("quests.yml: stage '" + stage.id() + "' has no quest objectives");
                continue;
            }
            List<ObjectiveDefinition> list = new ArrayList<>();
            for (String key : objectives.getKeys(false)) {
                ConfigurationSection o = objectives.getConfigurationSection(key);
                String where = "quests.yml: " + stage.id() + ".objectives." + key;
                if (o == null) {
                    errors.add(where + ": must be a section");
                    continue;
                }
                ObjectiveDefinition def = parseObjective(key, o, where);
                if (def != null) {
                    list.add(def);
                }
            }
            out.put(stage.index(), new QuestDefinition(stage.id(), stage.index(), list));
        }
        for (String key : q.getKeys(false)) {
            if (!stageIds.contains(key)) {
                warnings.add("quests.yml: '" + key + "' does not match any stage in ranks.yml and is ignored");
            }
        }
        return out;
    }

    private ObjectiveDefinition parseObjective(String id, ConfigurationSection o, String where) {
        ObjectiveType type = ObjectiveType.parse(o.getString("type"));
        if (type == null) {
            errors.add(where + ": unknown type '" + o.getString("type") + "'");
            return null;
        }
        long amount = o.getLong("amount", 0);
        if (amount <= 0 || amount > MAX_AMOUNT) {
            errors.add(where + ": amount must be between 1 and " + MAX_AMOUNT);
            return null;
        }
        int unique = o.getInt("unique", 0);
        if (unique < 0 || unique > amount) {
            errors.add(where + ": unique must be between 0 and amount (" + amount + ")");
            return null;
        }
        if (unique > 0 && type != ObjectiveType.PLAYER_KILLS) {
            warnings.add(where + ": 'unique' only applies to player-kills and is ignored");
            unique = 0;
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String t : o.getStringList("targets")) {
            targets.add(t.trim().toUpperCase(Locale.ROOT));
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String c : o.getStringList("categories")) {
            String cat = c.trim().toLowerCase(Locale.ROOT);
            if (!CATEGORIES.contains(cat)) {
                errors.add(where + ": unknown category '" + c + "' (use " + CATEGORIES + ")");
            }
            categories.add(cat);
        }
        if (targets.contains("ENDER_DRAGON")) {
            errors.add(where + ": quests may not require killing the Ender Dragon");
            return null;
        }
        switch (type) {
            case KILL_MOBS, KILL_DISTINCT_MOBS -> {
                if (targets.isEmpty() && categories.isEmpty()) {
                    errors.add(where + ": needs targets (entity types) or categories");
                }
                for (String t : targets) {
                    if (!names.isEntityType(t)) {
                        errors.add(where + ": unknown entity type '" + t + "'");
                    }
                }
            }
            case MINE_BLOCKS, SMELT_ITEMS, OBTAIN_ITEMS, SUBMIT_ITEMS -> {
                if (targets.isEmpty()) {
                    errors.add(where + ": needs targets (materials)");
                }
                for (String t : targets) {
                    if (!names.isMaterial(t)) {
                        errors.add(where + ": unknown material '" + t + "'");
                    }
                }
            }
            case DEFEAT_BOSS -> {
                if (targets.isEmpty()) {
                    errors.add(where + ": needs targets (one of " + BOSSES + ")");
                }
                for (String t : targets) {
                    if (!BOSSES.contains(t)) {
                        errors.add(where + ": '" + t + "' is not a supported boss " + BOSSES);
                    }
                }
            }
            default -> {
                // gain-levels, player-kills, hunts, raids take no targets
            }
        }
        String description = o.getString("description");
        if (description == null || description.isBlank()) {
            description = type.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " x" + amount;
        }
        return new ObjectiveDefinition(id, type, amount, unique, targets, categories,
                type == ObjectiveType.SUBMIT_ITEMS, description,
                o.getBoolean("final-hit", false), o.getBoolean("summoned", false));
    }

    // ------------------------------------------------------------------ abilities.yml

    private Map<String, RankAbility> parseRankAbilities(YamlConfiguration a) {
        Map<String, RankAbility> out = new HashMap<>();
        ConfigurationSection root = a.getConfigurationSection("rank-abilities");
        if (root == null) {
            errors.add("abilities.yml: missing rank-abilities section");
            return out;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            String where = "abilities.yml: rank-abilities." + id;
            if (s == null) {
                errors.add(where + ": must be a section");
                continue;
            }
            List<Map<?, ?>> raw = s.getMapList("components");
            if (raw.isEmpty()) {
                errors.add(where + ": needs at least one component");
                continue;
            }
            List<RankAbility.Component> components = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                RankAbility.Component c = parseComponent(raw.get(i), where + ".components[" + i + "]");
                if (c != null) {
                    components.add(c);
                }
            }
            out.put(id, new RankAbility(id, s.getString("description", ""), components));
        }
        return out;
    }

    private RankAbility.Component parseComponent(Map<?, ?> m, String where) {
        String type = str(m, "type");
        if (type == null) {
            errors.add(where + ": missing type");
            return null;
        }
        switch (type.toLowerCase(Locale.ROOT)) {
            case "effect-pulse" -> {
                List<RankAbility.EffectSpec> effects = effects(m, where);
                int duration = intVal(m.get("duration-seconds"), 0);
                int interval = intVal(m.get("interval-seconds"), 0);
                if (duration < 1 || interval < duration) {
                    errors.add(where + ": needs duration-seconds >= 1 and interval-seconds >= duration-seconds");
                    return null;
                }
                return effects == null ? null : new RankAbility.EffectPulse(effects, duration, interval);
            }
            case "permanent-effect" -> {
                List<RankAbility.EffectSpec> effects = effects(m, where);
                return effects == null ? null : new RankAbility.PermanentEffect(effects);
            }
            case "triggered-effect" -> {
                RankAbility.Trigger trigger = RankAbility.Trigger.parse(str(m, "trigger"));
                if (trigger == null) {
                    errors.add(where + ": unknown trigger '" + str(m, "trigger") + "'");
                    return null;
                }
                List<RankAbility.EffectSpec> effects = effects(m, where);
                int duration = intVal(m.get("duration-seconds"), 0);
                int cooldown = intVal(m.get("cooldown-seconds"), 0);
                double threshold = doubleVal(m.get("threshold"), 0.4);
                if (duration < 1 || cooldown < 0) {
                    errors.add(where + ": needs duration-seconds >= 1 and cooldown-seconds >= 0");
                    return null;
                }
                if (trigger == RankAbility.Trigger.LOW_HEALTH && (threshold <= 0 || threshold >= 1)) {
                    errors.add(where + ": threshold must be between 0 and 1 (fraction of max health)");
                    return null;
                }
                return effects == null ? null
                        : new RankAbility.TriggeredEffect(trigger, effects, duration, cooldown, threshold);
            }
            case "health-bonus" -> {
                double amount = doubleVal(m.get("amount"), 0);
                if (amount <= 0 || amount > 40) {
                    errors.add(where + ": amount must be between 0 and 40 health points");
                    return null;
                }
                return new RankAbility.HealthBonus(amount);
            }
            default -> {
                errors.add(where + ": unknown component type '" + type + "'");
                return null;
            }
        }
    }

    private List<RankAbility.EffectSpec> effects(Map<?, ?> m, String where) {
        Object raw = m.get("effects");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            errors.add(where + ": needs a non-empty effects list");
            return null;
        }
        List<RankAbility.EffectSpec> out = new ArrayList<>();
        for (Object o : list) {
            RankAbility.EffectSpec spec = RankAbility.EffectSpec.parse(String.valueOf(o));
            if (spec == null) {
                errors.add(where + ": bad effect '" + o + "' (use name:level, e.g. haste:1)");
                return null;
            }
            if (!names.isEffect(spec.effectKey())) {
                errors.add(where + ": unknown effect '" + spec.effectKey() + "'");
                return null;
            }
            out.add(spec);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static long longVal(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static double doubleVal(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private static double fraction(double value, double fallback) {
        return Double.isFinite(value) && value > 0 && value <= 1 ? value : fallback;
    }
}
