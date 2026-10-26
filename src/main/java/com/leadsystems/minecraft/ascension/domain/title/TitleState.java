package com.leadsystems.minecraft.ascension.domain.title;

import java.util.UUID;

/**
 * A title as seen by one player: {@code UNAVAILABLE -> ELIGIBLE -> CLAIMING -> OWNED}, or TAKEN when
 * someone else owns it. Ownership always wins over eligibility.
 */
public enum TitleState {
    UNAVAILABLE,
    ELIGIBLE,
    CLAIMING,
    OWNED,
    TAKEN;

    public static TitleState of(boolean eligible, UUID owner, UUID me, boolean claiming) {
        if (owner != null) {
            return owner.equals(me) ? OWNED : TAKEN;
        }
        if (claiming) {
            return CLAIMING;
        }
        return eligible ? ELIGIBLE : UNAVAILABLE;
    }
}
