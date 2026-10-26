package com.leadsystems.minecraft.ascension.domain.ability;

import java.util.List;
import java.util.Locale;

/**
 * A rank stage's ability package: one or more components. Immutable, parsed from abilities.yml.
 *
 * @param id          key referenced by ranks.yml
 * @param description player-facing summary shown in the book
 * @param components  what the package does
 */
public record RankAbility(String id, String description, List<Component> components) {

    public RankAbility {
        components = List.copyOf(components);
    }

    /**
     * An effect with a 0-based amplifier (config uses 1-based levels: {@code haste:1} = Haste I).
     *
     * @param effectKey lower-case namespaced key without namespace, e.g. {@code haste}
     */
    public record EffectSpec(String effectKey, int amplifier) {
        /** Parses {@code haste:1} / {@code night_vision}; returns null if malformed. */
        public static EffectSpec parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.trim().toLowerCase(Locale.ROOT).split(":");
            String key = parts[0].startsWith("minecraft.") ? parts[0].substring(10) : parts[0];
            int level = 1;
            if (parts.length > 1) {
                try {
                    level = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (parts.length > 2 || level < 1 || level > 255 || key.isEmpty()) {
                return null;
            }
            return new EffectSpec(key, level - 1);
        }
    }

    /** What triggers a {@link TriggeredEffect}. */
    public enum Trigger {
        /** The player takes damage. */
        DAMAGE_TAKEN,
        /** The player damages a hostile mob. */
        ATTACK_MOB,
        /** The player's health drops below {@code threshold} of max health. */
        LOW_HEALTH,
        /** The player enters combat (hostile mob or player) after being out of combat. */
        COMBAT_ENTER,
        /** The player makes a valid player kill. */
        PVP_KILL,
        /** The player crouches while looking straight down (works on Java and Bedrock). */
        LOOK_DOWN_CROUCH;

        public static Trigger parse(String raw) {
            if (raw == null) {
                return null;
            }
            String k = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Trigger t : values()) {
                if (t.name().equals(k)) {
                    return t;
                }
            }
            return null;
        }
    }

    /** One part of a package. */
    public sealed interface Component permits EffectPulse, PermanentEffect, TriggeredEffect, HealthBonus {}

    /** Applies effects for {@code durationSeconds} once every {@code intervalSeconds}. */
    public record EffectPulse(List<EffectSpec> effects, int durationSeconds, int intervalSeconds)
            implements Component {
        public EffectPulse {
            effects = List.copyOf(effects);
        }
    }

    /** Effects kept on permanently while the package is active (re-applied after milk, death, totems). */
    public record PermanentEffect(List<EffectSpec> effects) implements Component {
        public PermanentEffect {
            effects = List.copyOf(effects);
        }
    }

    /**
     * Effects applied when {@code trigger} fires, then a cooldown.
     *
     * @param threshold LOW_HEALTH: fraction of max health (e.g. 0.4); ignored otherwise
     */
    public record TriggeredEffect(Trigger trigger, List<EffectSpec> effects, int durationSeconds,
                                  int cooldownSeconds, double threshold) implements Component {
        public TriggeredEffect {
            effects = List.copyOf(effects);
        }
    }

    /** Extra max health (in health points; 2.0 = one heart) while the package is active. */
    public record HealthBonus(double amount) implements Component {}
}
