package com.leadsystems.minecraft.ascension.domain.rank;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankLadderTest {

    static RankLadder ladder() {
        return new RankLadder(List.of(
                new RankStage(0, "bronze_1", "Bronze I", "bronze", 1, "#cd7f32", "bronze_1"),
                new RankStage(1, "bronze_2", "Bronze II", "bronze", 2, "#cd7f32", "bronze_2"),
                new RankStage(2, "silver_1", "Silver I", "silver", 1, "#c0c0c0", "silver_1")));
    }

    @Test
    void lookupByIdAndTierStage() {
        RankLadder l = ladder();
        assertEquals(1, l.byId("bronze_2").index());
        assertEquals(2, l.find("silver", 1).index());
        assertEquals(2, l.find("SILVER", 1).index());
        assertNull(l.find("gold", 1));
    }

    @Test
    void nextIndexStopsAtTheTop() {
        RankLadder l = ladder();
        assertEquals(1, l.next(0));
        assertEquals(-1, l.next(2));
        assertTrue(l.isLast(2));
        assertFalse(l.isLast(1));
    }

    @Test
    void tiersAreListedInOrderWithoutDuplicates() {
        assertEquals(List.of("bronze", "silver"), ladder().tiers());
    }

    @Test
    void indicesMustMatchPositions() {
        assertThrows(IllegalArgumentException.class, () -> new RankLadder(List.of(
                new RankStage(1, "a", "A", "a", 1, "#fff", "a"))));
    }

    @Test
    void emptyLadderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RankLadder(List.of()));
    }
}
