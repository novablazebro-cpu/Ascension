package com.leadsystems.minecraft.ascension.domain.title;

import java.util.HashSet;
import java.util.Set;

/** Defines whether each built-in title ability is manually or automatically activated. */
public final class TitleAbilityRegistry {

    public static final Set<String> MANUAL = Set.of(
            "ascendant", "conqueror", "stormbringer", "tempest_herald", "titan", "chronomancer",
            "voidwalker", "witherbound", "frostbound", "soul_reaper", "bounty_hunter",
            "raidbreaker", "tideborn", "treasure_whisperer", "ashborn", "trailblazer",
            "mycelial_drifter", "emberforge", "angler");

    public static final Set<String> AUTOMATIC = Set.of(
            "bloodlord", "undying", "end_city_conqueror", "wardens_bane", "beastbane",
            "berserker", "alchemist", "colossus", "huntsman", "stonebreaker", "new_arrival");

    public static final Set<String> ALL;

    static {
        Set<String> all = new HashSet<>(MANUAL);
        all.addAll(AUTOMATIC);
        ALL = Set.copyOf(all);
        if (ALL.size() != MANUAL.size() + AUTOMATIC.size()) {
            throw new IllegalStateException("A title cannot be both manual and automatic");
        }
    }

    private TitleAbilityRegistry() { }

    public static boolean isManual(String titleId) {
        return MANUAL.contains(titleId);
    }
}
