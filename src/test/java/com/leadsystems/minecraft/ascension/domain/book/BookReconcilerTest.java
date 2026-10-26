package com.leadsystems.minecraft.ascension.domain.book;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookReconcilerTest {

    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final String NONCE = "nonce-current";
    static final int SCHEMA = BookIdentity.CURRENT_SCHEMA;

    static BookReconciler.Found at(int slot, UUID owner, String nonce, int schema) {
        return new BookReconciler.Found(slot, false, new BookIdentity(owner, nonce, schema));
    }

    static BookReconciler.Found valid(int slot) {
        return at(slot, OWNER, NONCE, SCHEMA);
    }

    static BookReconciler.Plan plan(BookReconciler.Found... found) {
        return BookReconciler.plan(List.of(found), OWNER, NONCE, SCHEMA);
    }

    @Test
    void singleValidBookIsKeptAsIs() {
        BookReconciler.Plan p = plan(valid(3));
        assertEquals(3, p.keep().slot());
        assertTrue(p.remove().isEmpty());
        assertFalse(p.needIssue());
    }

    @Test
    void noBookNeedsIssue() {
        BookReconciler.Plan p = plan();
        assertNull(p.keep());
        assertTrue(p.needIssue());
    }

    @Test
    void duplicatesAreRemovedKeepingTheFirstSlot() {
        BookReconciler.Plan p = plan(valid(10), valid(0));
        assertEquals(0, p.keep().slot());
        assertEquals(1, p.remove().size());
        assertEquals(10, p.remove().get(0).slot());
        assertEquals(1, p.duplicates());
    }

    @Test
    void someoneElsesBookIsRemoved() {
        BookReconciler.Plan p = plan(at(2, OTHER, NONCE, SCHEMA));
        assertEquals(1, p.foreign());
        assertEquals(1, p.remove().size());
        assertTrue(p.needIssue());
    }

    @Test
    void staleNonceIsRemoved() {
        BookReconciler.Plan p = plan(at(2, OWNER, "old-nonce", SCHEMA));
        assertEquals(1, p.stale());
        assertTrue(p.needIssue());
    }

    @Test
    void outdatedSchemaIsRemoved() {
        BookReconciler.Plan p = plan(at(2, OWNER, NONCE, SCHEMA - 1));
        assertEquals(1, p.stale());
        assertTrue(p.needIssue());
    }

    @Test
    void armorSlotIsNotAnAllowedLocation() {
        BookReconciler.Plan p = plan(valid(39));
        assertEquals(1, p.misplaced());
        assertTrue(p.needIssue());
    }

    @Test
    void bookInsideABundleIsRemoved() {
        BookReconciler.Plan p = plan(new BookReconciler.Found(4, true, new BookIdentity(OWNER, NONCE, SCHEMA)));
        assertEquals(1, p.misplaced());
        assertTrue(p.needIssue());
    }

    @Test
    void inventoryCopyWinsOverCursorCopy() {
        BookReconciler.Plan p = plan(valid(BookReconciler.CURSOR), valid(5));
        assertEquals(5, p.keep().slot());
        assertEquals(BookReconciler.CURSOR, p.remove().get(0).slot());
    }

    @Test
    void cursorOnlyCopyIsKept() {
        BookReconciler.Plan p = plan(valid(BookReconciler.CURSOR));
        assertEquals(BookReconciler.CURSOR, p.keep().slot());
        assertFalse(p.needIssue());
    }

    @Test
    void offhandIsAllowed() {
        BookReconciler.Plan p = plan(valid(BookReconciler.OFFHAND));
        assertEquals(BookReconciler.OFFHAND, p.keep().slot());
    }
}
