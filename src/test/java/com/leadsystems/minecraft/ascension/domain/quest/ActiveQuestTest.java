package com.leadsystems.minecraft.ascension.domain.quest;

import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveQuestTest {

    static ObjectiveDefinition obj(String id, ObjectiveType type, long amount, Set<String> targets, Set<String> categories) {
        return new ObjectiveDefinition(id, type, amount, 0, targets, categories, false, id, false, false);
    }

    static QuestDefinition quest(ObjectiveDefinition... objectives) {
        return new QuestDefinition("bronze_1", 0, List.of(objectives));
    }

    static ActiveQuest active(QuestDefinition def) {
        return ActiveQuest.fresh(def, QuestState.ACTIVE, 1000L);
    }

    @Test
    void levelsCountTowardGainLevelsObjective() {
        ActiveQuest q = active(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of())));
        assertTrue(q.apply(new Fact.LevelsGained(5)));
        assertEquals(5, q.amount("xp"));
    }

    @Test
    void progressIsClampedAtTheRequiredAmount() {
        ActiveQuest q = active(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of())));
        q.apply(new Fact.LevelsGained(25));
        q.apply(new Fact.LevelsGained(25));
        assertEquals(30, q.amount("xp"));
        assertFalse(q.apply(new Fact.LevelsGained(1)), "no change once complete");
    }

    @Test
    void targetedKillsOnlyCountMatchingTypes() {
        ActiveQuest q = active(quest(obj("z", ObjectiveType.KILL_MOBS, 10, Set.of("ZOMBIE"), Set.of())));
        q.apply(new Fact.MobKilled("SKELETON", Set.of("hostile")));
        q.apply(new Fact.MobKilled("ZOMBIE", Set.of("hostile")));
        assertEquals(1, q.amount("z"));
    }

    @Test
    void categoryKillsCountAnyMobInTheCategory() {
        ActiveQuest q = active(quest(obj("h", ObjectiveType.KILL_MOBS, 10, Set.of(), Set.of("hostile"))));
        q.apply(new Fact.MobKilled("CREEPER", Set.of("hostile")));
        q.apply(new Fact.MobKilled("COW", Set.of()));
        assertEquals(1, q.amount("h"));
    }

    @Test
    void distinctKillsCountEachTypeOnce() {
        ActiveQuest q = active(quest(obj("d", ObjectiveType.KILL_DISTINCT_MOBS, 6, Set.of(), Set.of("hostile"))));
        q.apply(new Fact.MobKilled("ZOMBIE", Set.of("hostile")));
        q.apply(new Fact.MobKilled("ZOMBIE", Set.of("hostile")));
        q.apply(new Fact.MobKilled("SKELETON", Set.of("hostile")));
        assertEquals(2, q.amount("d"));
    }

    @Test
    void uniqueVictimRequirementNeedsDistinctMarks() {
        ObjectiveDefinition pk = new ObjectiveDefinition("pk", ObjectiveType.PLAYER_KILLS, 3, 2,
                Set.of(), Set.of(), false, "pk", false, false);
        ActiveQuest q = active(quest(pk));
        q.apply(new Fact.PlayerKilled("a"));
        q.apply(new Fact.PlayerKilled("a"));
        q.apply(new Fact.PlayerKilled("a"));
        assertEquals(3, q.amount("pk"));
        assertFalse(q.status(pk).complete(), "3 kills but only 1 unique victim");
        q.apply(new Fact.PlayerKilled("b"));
        assertTrue(q.status(pk).complete());
        assertEquals(2, q.status(pk).marks());
    }

    @Test
    void progressIsIgnoredUnlessActive() {
        ActiveQuest q = ActiveQuest.fresh(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of())),
                QuestState.NOT_ACCEPTED, 0L);
        assertFalse(q.apply(new Fact.LevelsGained(5)));
        assertEquals(0, q.amount("xp"));
    }

    @Test
    void allCompleteRequiresEveryObjective() {
        ActiveQuest q = active(quest(
                obj("xp", ObjectiveType.GAIN_LEVELS, 2, Set.of(), Set.of()),
                obj("m", ObjectiveType.MINE_BLOCKS, 1, Set.of("STONE"), Set.of())));
        q.apply(new Fact.LevelsGained(2));
        assertFalse(q.allComplete());
        q.apply(new Fact.BlockMined("STONE"));
        assertTrue(q.allComplete());
    }

    @Test
    void beginCompletionIsOneShot() {
        ActiveQuest q = active(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 1, Set.of(), Set.of())));
        assertFalse(q.beginCompletion(), "not complete yet");
        q.apply(new Fact.LevelsGained(1));
        assertTrue(q.beginCompletion());
        assertEquals(QuestState.COMPLETED, q.state());
        assertFalse(q.beginCompletion(), "second call must not start another completion");
    }

    @Test
    void failedCompletionRevertsToActive() {
        ActiveQuest q = active(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 1, Set.of(), Set.of())));
        q.apply(new Fact.LevelsGained(1));
        q.beginCompletion();
        q.revertCompletion();
        assertEquals(QuestState.ACTIVE, q.state());
        assertTrue(q.beginCompletion(), "can retry after revert");
    }

    @Test
    void dirtyProgressDrainsOnceAndCanBeRestored() {
        ObjectiveDefinition pk = new ObjectiveDefinition("pk", ObjectiveType.PLAYER_KILLS, 5, 2,
                Set.of(), Set.of(), false, "pk", false, false);
        ActiveQuest q = active(quest(pk, obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of())));
        q.apply(new Fact.PlayerKilled("a"));
        q.apply(new Fact.LevelsGained(3));

        QuestDelta delta = q.drainDirty();
        assertEquals(1L, delta.amounts().get("pk"));
        assertEquals(3L, delta.amounts().get("xp"));
        assertEquals(Set.of("a"), delta.newMarks().get("pk"));
        assertTrue(q.drainDirty().isEmpty());

        q.restoreDirty(delta);
        assertEquals(delta, q.drainDirty());
    }

    @Test
    void adminCanForceAnObjectiveComplete() {
        ObjectiveDefinition pk = new ObjectiveDefinition("pk", ObjectiveType.PLAYER_KILLS, 5, 2,
                Set.of(), Set.of(), false, "pk", false, false);
        ActiveQuest q = active(quest(pk));
        assertTrue(q.forceComplete("pk"));
        assertTrue(q.status(pk).complete());
        assertFalse(q.forceComplete("missing"));
    }

    @Test
    void acceptOnlyFromNotAccepted() {
        ActiveQuest q = ActiveQuest.fresh(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of())),
                QuestState.NOT_ACCEPTED, 0L);
        assertTrue(q.accept(500L));
        assertEquals(QuestState.ACTIVE, q.state());
        assertEquals(500L, q.acceptedAt());
        assertFalse(q.accept(600L), "a second accept is a no-op");
        assertEquals(500L, q.acceptedAt());
    }

    @Test
    void restoreClampsAmountsAndDropsRemovedObjectives() {
        QuestDefinition def = quest(
                obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of()),
                obj("d", ObjectiveType.KILL_DISTINCT_MOBS, 6, Set.of(), Set.of("hostile")));
        ActiveQuest q = ActiveQuest.restore(def, QuestState.ACTIVE, 10L,
                java.util.Map.of("xp", 99L, "gone", 5L, "d", 0L),
                java.util.Map.of("d", Set.of("ZOMBIE", "SKELETON")));
        assertEquals(30, q.amount("xp"));
        assertEquals(0, q.amount("gone"));
        assertEquals(2, q.amount("d"), "distinct amount is derived from marks");
        assertTrue(q.drainDirty().isEmpty(), "restored progress is not dirty");
    }

    @Test
    void mergeDeltaRaisesValuesAndMarksThemDirty() {
        ObjectiveDefinition pk = new ObjectiveDefinition("pk", ObjectiveType.PLAYER_KILLS, 5, 2,
                Set.of(), Set.of(), false, "pk", false, false);
        ActiveQuest q = active(quest(pk, obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of())));
        q.apply(new Fact.LevelsGained(10));
        q.drainDirty();
        q.mergeDelta(new QuestDelta(java.util.Map.of("xp", 4L, "pk", 2L), java.util.Map.of("pk", Set.of("v1"))));
        assertEquals(10, q.amount("xp"), "never lowers");
        assertEquals(2, q.amount("pk"));
        assertEquals(1, q.status(pk).marks());
        QuestDelta d = q.drainDirty();
        assertTrue(d.amounts().containsKey("xp"));
        assertTrue(d.amounts().containsKey("pk"));
    }

    @Test
    void rebindKeepsProgressUnderANewDefinition() {
        ActiveQuest q = active(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 30, Set.of(), Set.of())));
        q.apply(new Fact.LevelsGained(20));
        ActiveQuest rebound = q.rebind(quest(obj("xp", ObjectiveType.GAIN_LEVELS, 10, Set.of(), Set.of())));
        assertEquals(10, rebound.amount("xp"), "clamped to the new requirement");
        assertEquals(QuestState.ACTIVE, rebound.state());
        assertTrue(rebound.drainDirty().amounts().containsKey("xp"), "unsaved progress stays dirty");
    }

    @Test
    void bossObjectiveCanRequireFinalHitAndSummon() {
        ObjectiveDefinition wither = new ObjectiveDefinition("w", ObjectiveType.DEFEAT_BOSS, 1, 0,
                Set.of("WITHER"), Set.of(), false, "w", true, true);
        ActiveQuest q = active(quest(wither));
        q.apply(new Fact.BossDefeated("WITHER", false, true));
        q.apply(new Fact.BossDefeated("WITHER", true, false));
        assertEquals(0, q.amount("w"));
        q.apply(new Fact.BossDefeated("WITHER", true, true));
        assertEquals(1, q.amount("w"));
    }
}
