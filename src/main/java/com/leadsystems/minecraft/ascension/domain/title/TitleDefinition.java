package com.leadsystems.minecraft.ascension.domain.title;

import java.util.List;
import java.util.Map;

/**
 * One globally exclusive title. Immutable; parsed from titles.yml.
 *
 * @param id                     config key, e.g. {@code stormbringer}
 * @param number                 1 = hardest ... 30 = easiest
 * @param name                   display name
 * @param color                  hex color
 * @param description            player-facing description
 * @param requirements           all must be met
 * @param abilityName            the title ability's name
 * @param abilityDescription     what the ability does
 * @param abilityCooldownSeconds ability cooldown (0 = passive / none)
 * @param autoAward              awarded automatically to the first eligible player (Undying)
 */
public record TitleDefinition(String id, int number, String name, String color, String description,
                              List<Requirement> requirements, String abilityName, String abilityDescription,
                              int abilityCooldownSeconds, Map<String, Double> abilitySettings, boolean autoAward) {
    public TitleDefinition {
        requirements = List.copyOf(requirements);
        abilitySettings = Map.copyOf(abilitySettings);
    }

    public TitleDefinition(String id, int number, String name, String color, String description,
                           List<Requirement> requirements, String abilityName, String abilityDescription,
                           int abilityCooldownSeconds, boolean autoAward) {
        this(id, number, name, color, description, requirements, abilityName, abilityDescription,
                abilityCooldownSeconds, Map.of(), autoAward);
    }

    public double abilitySetting(String key, double fallback) {
        return abilitySettings.getOrDefault(key, fallback);
    }

    public double upgradedAbilitySetting(String key, String legacyKey, double legacyDefault, double upgradedDefault) {
        Double current = abilitySettings.get(key);
        if (current != null) {
            return current;
        }
        Double legacy = abilitySettings.get(legacyKey);
        return legacy != null && Double.compare(legacy, legacyDefault) != 0 ? legacy : upgradedDefault;
    }
}
