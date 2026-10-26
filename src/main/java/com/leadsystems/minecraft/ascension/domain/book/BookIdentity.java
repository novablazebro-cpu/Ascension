package com.leadsystems.minecraft.ascension.domain.book;

import java.util.UUID;

/**
 * Identity data stored in a Chronicle's PersistentDataContainer. Only this data is trusted;
 * the item name and lore are cosmetic.
 *
 * @param owner  owning player
 * @param nonce  per-issue nonce; must equal the DB-committed nonce for the book to work
 * @param schema item schema version; older schemas are replaced on reconcile
 */
public record BookIdentity(UUID owner, String nonce, int schema) {
    /** Bump when the book item format changes; older copies are then replaced automatically. */
    public static final int CURRENT_SCHEMA = 2;
}
