package com.leadsystems.minecraft.ascension.domain.cooldown;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownBookTest {

    @Test
    void unknownKeyIsReady() {
        assertTrue(new CooldownBook().isReady("x", 0));
    }

    @Test
    void cooldownExpiresExactlyAtReadyTime() {
        CooldownBook book = new CooldownBook();
        book.start("stormbringer", 1_000, 5_000);
        assertFalse(book.isReady("stormbringer", 5_999));
        assertEquals(1_000, book.remainingMillis("stormbringer", 5_000));
        assertTrue(book.isReady("stormbringer", 6_000));
        assertEquals(0, book.remainingMillis("stormbringer", 7_000));
    }

    @Test
    void resetMakesAbilityReadyAndIsPersisted() {
        CooldownBook book = new CooldownBook();
        book.start("a", 0, 10_000);
        book.drainDirty();
        book.reset("a");
        assertTrue(book.isReady("a", 1));
        assertEquals(Map.of("a", 0L), book.drainDirty());
    }

    @Test
    void loadedCooldownsAreNotDirty() {
        CooldownBook book = new CooldownBook();
        book.load(Map.of("refuse_death", 99_000L));
        assertFalse(book.isReady("refuse_death", 50_000));
        assertTrue(book.drainDirty().isEmpty());
    }

    @Test
    void startMarksDirtyOnceUntilDrained() {
        CooldownBook book = new CooldownBook();
        book.start("a", 100, 1_000);
        assertEquals(Map.of("a", 1_100L), book.drainDirty());
        assertTrue(book.drainDirty().isEmpty());
    }

    @Test
    void startSaturatesWhenExpiryWouldOverflow() {
        CooldownBook book = new CooldownBook();
        book.start("a", Long.MAX_VALUE - 5, 10);
        assertFalse(book.isReady("a", Long.MAX_VALUE - 1));
        assertEquals(Map.of("a", Long.MAX_VALUE), book.drainDirty());
    }

    @Test
    void failedFlushCanBeRequeued() {
        CooldownBook book = new CooldownBook();
        book.start("a", 100, 1_000);
        Map<String, Long> dirty = book.drainDirty();
        book.restoreDirty(dirty);
        assertEquals(dirty, book.drainDirty());
    }
}
