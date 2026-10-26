package com.leadsystems.minecraft.ascension.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.Map;
import java.util.Set;
import com.leadsystems.minecraft.ascension.storage.PlayerRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerSessionTest {

    private SessionSnapshot snapshot(PlayerSession session, String title, Map<String, Long> cooldowns) {
        return new SessionSnapshot(new PlayerRecord(session.uuid(), "Tester", 0, false, "nonce",
                false, 0, 0, null), null, cooldowns, Map.of(), Set.of(), title);
    }

    @Test
    void titleCyclingAndNewSessionPreserveSeparateExpiryTimes() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Tester", false);
        Map<String, Long> stored = Map.of("title.ascendant.ascension", 301_000L,
                "title.conqueror.war_cry", 62_000L, "title.stormbringer.heavens_judgment", 38_000L);
        for (String title : new String[] {"ascendant", "conqueror", "stormbringer", "ascendant"}) {
            session.apply(snapshot(session, title, stored), rank -> null);
            assertEquals(297_000L, session.cooldowns().remainingMillis("title.ascendant.ascension", 4_000));
            assertFalse(session.cooldowns().isReady("title.ascendant.ascension", 4_000));
        }
        PlayerSession rejoined = new PlayerSession(session.uuid(), "Tester", false);
        rejoined.apply(snapshot(rejoined, "ascendant", stored), rank -> null);
        assertEquals(297_000L, rejoined.cooldowns().remainingMillis("title.ascendant.ascension", 4_000));
        assertTrue(new PlayerSession(UUID.randomUUID(), "Other", false).cooldowns()
                .isReady("title.ascendant.ascension", 4_000));
    }

    @Test
    void authoritativeResetRemovesOneThenAllCooldownsWithoutResavingDeletedKeys() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Tester", false);
        session.apply(snapshot(session, "ascendant", Map.of("a", 90_000L, "b", 80_000L)), r -> null);
        session.apply(snapshot(session, "ascendant", Map.of("b", 80_000L)), r -> null);
        assertTrue(session.cooldowns().isReady("a", 1));
        assertFalse(session.cooldowns().isReady("b", 1));
        session.apply(snapshot(session, "ascendant", Map.of()), r -> null);
        assertTrue(session.cooldowns().isReady("b", 1));
        assertTrue(session.drain().cooldowns().isEmpty());
    }

    @Test
    void failedSaveRecoveryMergesWithoutDeletingOtherTimers() {
        PlayerSession old = new PlayerSession(UUID.randomUUID(), "Tester", false);
        old.cooldowns().start("a", 1_000, 300_000);
        FlushBatch failed = old.drain();
        PlayerSession live = new PlayerSession(old.uuid(), "Tester", false);
        live.apply(snapshot(live, "ascendant", Map.of("b", 80_000L)), r -> null);
        live.restore(failed, true);
        assertEquals(300_000, live.cooldowns().remainingMillis("a", 1_000));
        assertEquals(79_000, live.cooldowns().remainingMillis("b", 1_000));
        assertEquals(Map.of("a", 301_000L), live.drain().cooldowns());
        live.cooldowns().start("a", 2_000, 300_000);
        live.restore(failed, false);
        assertEquals(Map.of("a", 302_000L), live.drain().cooldowns());
    }

    @Test
    void committedIncrementKeepsUnflushedDelta() {
        PlayerSession session = new PlayerSession(UUID.randomUUID(), "Tester", false);
        session.addStat("loot.end_city.ELYTRA", 2);
        session.addCommittedStat("loot.end_city.ELYTRA", 1);

        assertEquals(3, session.stat("loot.end_city.ELYTRA"));
        assertEquals(2, session.drain().statDeltas().get("loot.end_city.ELYTRA"));
    }
}
