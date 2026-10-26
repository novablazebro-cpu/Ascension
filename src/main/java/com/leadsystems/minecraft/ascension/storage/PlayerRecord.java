package com.leadsystems.minecraft.ascension.storage;

import java.util.UUID;

/** Row of the {@code players} table. */
public record PlayerRecord(UUID uuid, String name, int rankIndex, boolean mastery, String bookNonce,
                           boolean bookPending, long firstJoin, long lastSeen, String pendingNotice,
                           boolean rankActive, long rankGrantedAt) {
    /** Source-compatible constructor for tests and integrations built before rank renewal. */
    public PlayerRecord(UUID uuid, String name, int rankIndex, boolean mastery, String bookNonce,
                        boolean bookPending, long firstJoin, long lastSeen, String pendingNotice) {
        this(uuid, name, rankIndex, mastery, bookNonce, bookPending, firstJoin, lastSeen, pendingNotice, true, firstJoin);
    }
}
