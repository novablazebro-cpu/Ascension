package com.leadsystems.minecraft.ascension.domain.book;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides which Chronicle copies in a player's inventory survive. Pure function over a slot model.
 * <p>
 * Invariant enforced: after applying a plan the player holds <b>at most one</b> Chronicle, and only
 * if it carries their UUID, the DB-committed nonce and the current schema, in an allowed location
 * (storage/hotbar slots 0-35, offhand 40, or the cursor while an inventory is open). A creative clone
 * carries the same nonce, so it is caught as a duplicate; a crash-restored copy carries an old nonce,
 * so it is caught as stale.
 */
public final class BookReconciler {

    /** Pseudo-slot for the item on the player's cursor. */
    public static final int CURSOR = -1;
    /** PlayerInventory slot index of the offhand. */
    public static final int OFFHAND = 40;

    /**
     * A marked item found in the inventory.
     *
     * @param slot         PlayerInventory slot, {@link #CURSOR}, or the slot of the bundle containing it
     * @param insideBundle true if the item sits inside a bundle at {@code slot}
     * @param identity     identity read from the item's PDC
     */
    public record Found(int slot, boolean insideBundle, BookIdentity identity) {}

    /**
     * Result of reconciliation.
     *
     * @param keep       the surviving copy, or null
     * @param remove     copies to delete
     * @param needIssue  true if no valid copy survives and a new book must be issued
     * @param foreign    copies owned by another player
     * @param stale      copies with an old nonce or schema
     * @param misplaced  valid copies in a forbidden location (armor slot, inside a bundle)
     * @param duplicates extra valid copies
     */
    public record Plan(Found keep, List<Found> remove, boolean needIssue,
                       int foreign, int stale, int misplaced, int duplicates) {
        public Plan {
            remove = List.copyOf(remove);
        }

        /** True if nothing needs to change. */
        public boolean clean() {
            return remove.isEmpty() && !needIssue;
        }
    }

    private BookReconciler() {}

    /**
     * Builds the reconciliation plan.
     *
     * @param found  every marked item found (any order)
     * @param owner  the player being reconciled
     * @param nonce  the player's DB-committed nonce
     * @param schema the current item schema
     */
    public static Plan plan(List<Found> found, UUID owner, String nonce, int schema) {
        List<Found> remove = new ArrayList<>();
        Found keep = null;
        int foreign = 0;
        int stale = 0;
        int misplaced = 0;
        int duplicates = 0;

        // Inventory slots before the cursor so a real slot copy wins; lower slot first for determinism.
        List<Found> ordered = new ArrayList<>(found);
        ordered.sort((a, b) -> Integer.compare(order(a), order(b)));

        for (Found f : ordered) {
            BookIdentity id = f.identity();
            if (!owner.equals(id.owner())) {
                foreign++;
                remove.add(f);
            } else if (!nonce.equals(id.nonce()) || id.schema() != schema) {
                stale++;
                remove.add(f);
            } else if (!allowed(f)) {
                misplaced++;
                remove.add(f);
            } else if (keep == null) {
                keep = f;
            } else {
                duplicates++;
                remove.add(f);
            }
        }
        return new Plan(keep, remove, keep == null, foreign, stale, misplaced, duplicates);
    }

    private static int order(Found f) {
        return f.slot() == CURSOR ? Integer.MAX_VALUE : f.slot();
    }

    private static boolean allowed(Found f) {
        if (f.insideBundle()) {
            return false;
        }
        int s = f.slot();
        return s == CURSOR || s == OFFHAND || (s >= 0 && s <= 35);
    }
}
