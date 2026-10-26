package com.leadsystems.minecraft.ascension.domain.title;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.session.PlayerSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared source of effective ability values for gameplay, Java menus and Bedrock forms. */
public final class TitleAbilityResolver {
    private final Supplier<ConfigSnapshot> config;

    public TitleAbilityResolver(Supplier<ConfigSnapshot> config) { this.config = Objects.requireNonNull(config); }
    public static String xpKey(String id) { return "title.xp." + id; }
    public long xp(PlayerSession session, String id) { return session == null ? 0 : Math.max(0, session.stat(xpKey(id))); }
    public int level(PlayerSession session, String id) { return rules(config.get()).level(xp(session, id)); }
    /** Absolute XP needed for the next level, or zero at the maximum level. */
    public long nextLevelXp(PlayerSession session, String id) {
        ConfigSnapshot snapshot = config.get();
        return switch (rules(snapshot).level(xp(session, id))) {
            case 1 -> rules(snapshot).level2Xp();
            case 2 -> rules(snapshot).level3Xp();
            default -> 0;
        };
    }
    public TitleDefinition resolved(PlayerSession session, String id) {
        ConfigSnapshot snapshot = config.get();
        return resolveLevel(snapshot, id, rules(snapshot).level(xp(session, id)));
    }
    public String levelSummary(PlayerSession session, String id) {
        TitleDefinition title = resolved(session, id);
        return title == null ? "" : describe(title.id(), title.abilitySettings()) + cooldownText(title);
    }
    public String nextLevelSummary(PlayerSession session, String id) {
        ConfigSnapshot snapshot = config.get();
        int level = rules(snapshot).level(xp(session, id));
        if (level == 3) return "Maximum level reached.";
        TitleDefinition title = resolveLevel(snapshot, id, level + 1);
        return title == null ? "" : title.abilityDescription() + cooldownText(title);
    }
    private static String cooldownText(TitleDefinition title) {
        int seconds = title.abilityCooldownSeconds();
        if (seconds <= 0) return "";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (seconds >= 3600) parts.add(seconds / 3600 + "h");
        if (seconds % 3600 >= 60) parts.add(seconds % 3600 / 60 + "m");
        if (seconds % 60 > 0) parts.add(seconds % 60 + "s");
        return " Cooldown: " + String.join(" ", parts) + ".";
    }

    private static TitleLevels rules(ConfigSnapshot snapshot) {
        return snapshot.titleLevels() == null ? TitleLevels.defaults() : snapshot.titleLevels();
    }

    /** Resolves against one snapshot so a reload cannot mix rules midway through an ability. */
    public static TitleDefinition resolveLevel(ConfigSnapshot config, String id, int level) {
        TitleDefinition base = config.titles().byId(id);
        if (base == null) return null;
        Map<String, Double> effective = levelOneSettings(config, base);
        int cooldown = base.abilityCooldownSeconds();
        if (level > 1) {
            TitleLevels.Upgrade upgrade = rules(config).profiles().getOrDefault(base.id(), Map.of()).get(level);
            if (upgrade != null) {
                upgrade.settingsDeltas().forEach((key, delta) -> {
                    double baseline = effective.getOrDefault(key, 0.0);
                    double upgraded = baseline + delta;
                    SettingBounds bounds = settingBounds(base.id(), key);
                    if (rules(config).stockSettingDelta(base.id(), level, key) && bounds.valid(baseline)) upgraded = bounds.clamp(upgraded);
                    effective.put(key, upgraded);
                });
                long upgradedCooldown = (long) cooldown + upgrade.cooldownDeltaSeconds();
                if (rules(config).stockCooldownDelta(base.id(), level) && cooldown >= 0 && cooldown <= MAX_COOLDOWN_SECONDS)
                    upgradedCooldown = Math.max(0, Math.min(MAX_COOLDOWN_SECONDS, upgradedCooldown));
                cooldown = Math.toIntExact(upgradedCooldown);
            }
        }
        String description = level <= 1 ? base.abilityDescription() : describe(base.id(), effective);
        return new TitleDefinition(base.id(), base.number(), base.name(), base.color(), base.description(),
                base.requirements(), base.abilityName(), description, cooldown, effective, base.autoAward());
    }

    /** Canonicalizes old stock settings with exactly the same fallback rules as live level one. */
    public static Map<String, Double> levelOneSettings(ConfigSnapshot config, TitleDefinition title) {
        Map<String, Double> result = new LinkedHashMap<>(fallbacks(title.id()));
        result.putAll(title.abilitySettings());
        switch (title.id()) {
            case "ascendant" -> {
                canonical(result, title, "buff-duration-seconds", "duration-seconds", 15, 30);
                canonical(result, title, "balanced-resistance-level", "resistance-level", 10, 4);
            }
            case "conqueror" -> {
                canonical(result, title, "balanced-weakness-level", "weakness-level", 3, 2);
                canonical(result, title, "balanced-slowness-level", "slowness-level", 5, 4);
            }
            case "stormbringer" -> {
                result.putIfAbsent("range", config.settings().stormbringerRange());
                canonical(result, title, "burst-damage-health-points", "player-damage-health-points", 10, 26);
            }
            case "bloodlord" -> result.putIfAbsent("max-heal-health-points", config.settings().vampireMaxHeal());
            case "tempest_herald" -> canonical(result, title, "chain-damage-health-points", "combat-damage-health-points", 10, 8);
            case "voidwalker" -> canonical(result, title, "aim-range", "range", 8, 32);
            case "titan" -> {
                canonical(result, title, "slam-radius", "radius", 6, 3);
                canonical(result, title, "slam-damage-health-points", "combat-damage-health-points", 12, 26);
                canonical(result, title, "slam-knockback", "knockback", 1, 1.4);
            }
            case "raidbreaker" -> canonical(result, title, "combat-damage-health-points", "damage-health-points", 5, 8);
            case "ashborn" -> canonical(result, title, "combat-damage-health-points", "damage-health-points", 4, 8);
            case "witherbound" -> canonical(result, title, "combat-damage-health-points", "damage-health-points", 5, 9);
            case "huntsman" -> canonical(result, title, "combat-damage-multiplier", "damage-multiplier", 1.25, 1.5);
            case "new_arrival" -> canonical(result, title, "restorative-heal-health-points", "heal-health-points", 1, 2);
            default -> { }
        }
        switch (title.id()) {
            case "voidwalker" -> {
                normalizeGameplaySetting(result, title.id(), "charges");
                normalizeGameplaySetting(result, title.id(), "aim-range");
            }
            case "tempest_herald" -> normalizeGameplaySetting(result, title.id(), "extra-chain-targets");
            case "treasure_whisperer" -> normalizeGameplaySetting(result, title.id(), "range");
            case "mycelial_drifter" -> normalizeGameplaySetting(result, title.id(), "ticks");
            default -> { }
        }
        return result;
    }
    /** Preserve the actual level-one behavior of legacy settings already capped by gameplay. */
    private static void normalizeGameplaySetting(Map<String, Double> settings, String id, String key) {
        double configured = settings.get(key);
        if (!Double.isFinite(configured)) return;
        SettingBounds bounds = settingBounds(id, key);
        double effective = bounds.integer() ? (int) configured : configured;
        settings.put(key, bounds.clamp(effective));
    }
    private static void canonical(Map<String, Double> result, TitleDefinition title, String key, String legacy, double oldDefault, double fallback) {
        result.put(key, title.upgradedAbilitySetting(key, legacy, oldDefault, fallback));
    }

    private static Map<String, Double> fallbacks(String id) {
        Map<String, Double> values = new LinkedHashMap<>();
        String spec = switch (id) {
            case "ascendant" -> "buff-duration-seconds=30,strength-level=5,speed-level=3,balanced-resistance-level=4";
            case "conqueror" -> "radius=10,duration-seconds=8,balanced-weakness-level=2,balanced-slowness-level=4";
            case "stormbringer" -> "all-targets=0,burst-damage-health-points=26";
            case "tempest_herald" -> "range=20,chain-range=8,extra-chain-targets=2,chain-damage-health-points=8,chain-multiplier=1";
            case "voidwalker" -> "aim-range=32,charges=3,charge-recharge-seconds=60";
            case "bloodlord" -> "lifesteal-ratio=0.2";
            case "chronomancer" -> "window-seconds=3";
            case "titan" -> "slam-radius=3,slam-damage-health-points=26,slam-knockback=1.4";
            case "undying" -> "";
            case "end_city_conqueror" -> "minimum-fall-distance=6,slow-falling-seconds=5,landing-speed-seconds=2";
            case "wardens_bane" -> "damage-multiplier=0.2,heavy-hit-threshold-health-points=16,heavy-hit-multiplier=0.5";
            case "frostbound" -> "radius=5,damage-health-points=6,slow-level=2,slow-seconds=4";
            case "witherbound" -> "radius=5,combat-damage-health-points=9";
            case "soul_reaper" -> "maximum-souls=10,heal-health-points=4";
            case "bounty_hunter" -> "range=50,duration-seconds=5";
            case "raidbreaker" -> "radius=8,combat-damage-health-points=8,illager-damage-health-points=12,knockback=1.2,raid-strength-seconds=8";
            case "tideborn" -> "horizontal-force=1.8,land-force-multiplier=0.5,vertical-force=0.25";
            case "beastbane" -> "health-threshold-health-points=8,execute-damage-health-points=14";
            case "emberforge" -> "duration-seconds=8,bonus-damage-health-points=5,fire-seconds=8";
            case "alchemist" -> "duration-multiplier=1.25,splash-radius=5";
            case "treasure_whisperer" -> "range=16,bonus-chance=0.05";
            case "berserker" -> "maximum-bonus-damage=6,decay-seconds=10,health-lost-per-bonus-damage=2";
            case "colossus" -> "bonus-max-health-points=4";
            case "ashborn" -> "radius=5,combat-damage-health-points=8,burn-seconds=4";
            case "trailblazer" -> "horizontal-force=1.6,vertical-force=0.25";
            case "angler" -> "wait-ticks=60";
            case "mycelial_drifter" -> "radius=4,ticks=4,heal-health-points=2,damage-per-tick-health-points=2";
            case "huntsman" -> "duration-seconds=8,combat-damage-multiplier=1.5";
            case "stonebreaker" -> "maximum-extra-blocks=5,auto-smelt-chance=0.1";
            case "new_arrival" -> "restorative-heal-health-points=2,speed-seconds=2";
            default -> "";
        };
        if (!spec.isEmpty()) for (String entry : spec.split(",")) {
            String[] pair = entry.split("="); values.put(pair[0], Double.parseDouble(pair[1]));
        }
        return values;
    }

    public static boolean knownSetting(String titleId, String key) {
        return fallbacks(titleId).containsKey(key) || titleId.equals("stormbringer") && key.equals("range")
                || titleId.equals("bloodlord") && key.equals("max-heal-health-points");
    }

    public static final int MAX_COOLDOWN_SECONDS = 604800;

    /** Shared supported limits. Stock relative upgrades saturate here; custom overflow is invalid. */
    public record SettingBounds(double minimum, double maximum, boolean integer) {
        public boolean valid(double value) {
            return Double.isFinite(value) && value >= minimum && value <= maximum && (!integer || value == Math.rint(value));
        }
        public double clamp(double value) { return Math.max(minimum, Math.min(maximum, value)); }
    }

    public static SettingBounds settingBounds(String id, String key) {
        if (id.equals("voidwalker") && key.equals("charges")) return new SettingBounds(1, 5, true);
        if (id.equals("voidwalker") && key.equals("aim-range")) return new SettingBounds(1, 64, false);
        if (id.equals("tempest_herald") && key.equals("extra-chain-targets")) return new SettingBounds(0, 16, true);
        if (id.equals("treasure_whisperer") && key.equals("range")) return new SettingBounds(1, 24, true);
        if (id.equals("mycelial_drifter") && key.equals("ticks")) return new SettingBounds(1, 8, true);
        double maximum = key.endsWith("chance") || key.equals("lifesteal-ratio") || key.equals("all-targets")
                || key.equals("heavy-hit-multiplier") || (id.equals("wardens_bane") && key.equals("damage-multiplier")) ? 1
                : key.contains("radius") || key.endsWith("range") ? 128
                : key.equals("charges") ? 16
                : key.endsWith("level") ? 255
                : key.contains("health-points") || key.equals("maximum-bonus-damage") ? 1024
                : key.endsWith("force") || key.endsWith("knockback") ? 10
                : key.endsWith("seconds") ? 86400
                : key.equals("maximum-souls") ? 1000
                : key.equals("maximum-extra-blocks") || key.equals("extra-chain-targets") ? 64 : 1_000_000;
        boolean integer = key.equals("charges") || key.equals("all-targets") || key.endsWith("level")
                || key.equals("maximum-souls") || key.equals("maximum-extra-blocks")
                || key.equals("extra-chain-targets") || key.equals("wait-ticks") || key.equals("ticks");
        double minimum = key.equals("charges") || key.equals("wait-ticks") || key.equals("ticks")
                || key.equals("maximum-souls") || key.endsWith("level") ? 1 : 0;
        return new SettingBounds(minimum, maximum, integer);
    }

    private static String describe(String id, Map<String, Double> s) {
        java.util.function.Function<String, String> v = key -> format(s.getOrDefault(key, 0.0));
        return switch (id) {
            case "ascendant" -> "Strength " + v.apply("strength-level") + ", Speed " + v.apply("speed-level") + " and Resistance " + v.apply("balanced-resistance-level") + " for " + v.apply("buff-duration-seconds") + " seconds.";
            case "conqueror" -> "Gain Strength; inflict Weakness " + v.apply("balanced-weakness-level") + " and Slowness " + v.apply("balanced-slowness-level") + " within " + v.apply("radius") + " blocks for " + v.apply("duration-seconds") + " seconds.";
            case "stormbringer" -> "Strike " + (s.get("all-targets") >= 1 ? "all eligible players" : "the closest eligible player") + " within " + v.apply("range") + " blocks for " + v.apply("burst-damage-health-points") + " raw damage. Armor and protection apply.";
            case "tempest_herald" -> "Aim within " + v.apply("range") + " blocks; lightning deals " + v.apply("chain-damage-health-points") + " damage and chains to " + v.apply("extra-chain-targets") + " extra targets within " + v.apply("chain-range") + " blocks.";
            case "voidwalker" -> "Teleport to a safe aimed spot within " + v.apply("aim-range") + " blocks. " + v.apply("charges") + " charges, each recharging in " + v.apply("charge-recharge-seconds") + " seconds.";
            case "bloodlord" -> "Every valid damaging hit heals " + format(s.get("lifesteal-ratio") * 100) + "% of final damage, up to " + v.apply("max-heal-health-points") + " HP per hit.";
            case "chronomancer" -> "Save your position and health; reactivate within " + v.apply("window-seconds") + " seconds to rewind.";
            case "titan" -> "Slam targets within " + v.apply("slam-radius") + " blocks for " + v.apply("slam-damage-health-points") + " raw damage and knockback. Armor and protection apply.";
            case "undying" -> "Survive one otherwise fatal attack.";
            case "end_city_conqueror" -> "Prevent dangerous falls, gain Slow Falling for " + v.apply("slow-falling-seconds") + " seconds and Speed II for " + v.apply("landing-speed-seconds") + " seconds on landing.";
            case "wardens_bane" -> "Take " + format(s.get("damage-multiplier") * 100) + "% sonic damage or " + format(s.get("heavy-hit-multiplier") * 100) + "% damage from one hit of at least " + v.apply("heavy-hit-threshold-health-points") + " HP.";
            case "frostbound" -> "Deal " + v.apply("damage-health-points") + " damage within " + v.apply("radius") + " blocks; apply Slowness " + v.apply("slow-level") + " for " + v.apply("slow-seconds") + " seconds.";
            case "witherbound" -> "Clear Wither and pulse " + v.apply("combat-damage-health-points") + " damage within " + v.apply("radius") + " blocks.";
            case "soul_reaper" -> "Store up to " + v.apply("maximum-souls") + " souls from valid kills; consume one to heal " + v.apply("heal-health-points") + " HP.";
            case "bounty_hunter" -> "Reveal your assigned target within " + v.apply("range") + " blocks for " + v.apply("duration-seconds") + " seconds.";
            case "raidbreaker" -> "Repel eligible targets within " + v.apply("radius") + " blocks; gain Strength for " + v.apply("raid-strength-seconds") + " seconds during a raid.";
            case "tideborn" -> "Rush underwater with " + v.apply("horizontal-force") + " force and restore oxygen; land force is " + format(s.get("horizontal-force") * s.get("land-force-multiplier")) + ".";
            case "beastbane" -> "Automatically execute an eligible target at " + v.apply("health-threshold-health-points") + " effective HP or less for " + v.apply("execute-damage-health-points") + " damage.";
            case "emberforge" -> "Gain " + v.apply("bonus-damage-health-points") + " bonus attack damage for " + v.apply("duration-seconds") + " seconds; ignite hits for " + v.apply("fire-seconds") + " seconds.";
            case "alchemist" -> "Thrown potion durations are multiplied by " + v.apply("duration-multiplier") + "; splash radius is " + v.apply("splash-radius") + " blocks.";
            case "treasure_whisperer" -> "Highlight treasure within " + v.apply("range") + " blocks; eligible natural containers have a " + format(s.get("bonus-chance") * 100) + "% bonus roll chance.";
            case "berserker" -> "Recent health loss grants up to " + v.apply("maximum-bonus-damage") + " bonus damage, fading over " + v.apply("decay-seconds") + " seconds.";
            case "colossus" -> "While equipped, gain " + v.apply("bonus-max-health-points") + " maximum HP.";
            case "ashborn" -> "Extinguish yourself, pulse " + v.apply("combat-damage-health-points") + " damage within " + v.apply("radius") + " blocks and burn enemies for " + v.apply("burn-seconds") + " seconds.";
            case "trailblazer" -> "Dash safely forward with " + v.apply("horizontal-force") + " horizontal force.";
            case "angler" -> "Fish within 30 seconds for a " + v.apply("wait-ticks") + " tick wait and guaranteed treasure catch.";
            case "mycelial_drifter" -> "Create a cloud within " + v.apply("radius") + " blocks for " + v.apply("ticks") + " pulses of " + v.apply("damage-per-tick-health-points") + " damage; heal once for " + v.apply("heal-health-points") + " HP.";
            case "huntsman" -> "Mark the first eligible target for " + v.apply("duration-seconds") + " seconds and multiply your damage by " + v.apply("combat-damage-multiplier") + ".";
            case "stonebreaker" -> "Break up to " + v.apply("maximum-extra-blocks") + " extra connected stone blocks; each has a " + format(s.get("auto-smelt-chance") * 100) + "% auto-smelt chance.";
            case "new_arrival" -> "Hostile kills heal " + v.apply("restorative-heal-health-points") + " HP and grant Speed I for " + v.apply("speed-seconds") + " seconds.";
            default -> "";
        };
    }
    private static String format(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
