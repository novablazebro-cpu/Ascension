package com.leadsystems.minecraft.ascension.storage;

import com.leadsystems.minecraft.ascension.domain.quest.QuestDelta;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRepositoryTest {

    static final UUID ALEX = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path dir;

    SqlDatabase db;
    PlayerRepository repo;
    Connection c;

    @BeforeEach
    void setUp() throws Exception {
        db = SqlDatabase.sqlite(dir.resolve("ascension.db"));
        new MigrationRunner(db, dir.resolve("backups"), Logger.getLogger("test")).migrate();
        repo = new PlayerRepository(db.dialect());
        c = db.connection();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void sqliteRunsInWalMode() throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase());
        }
    }

    @Test
    void migrationsAreRecordedAndIdempotent() throws Exception {
        int again = new MigrationRunner(db, dir.resolve("backups"), Logger.getLogger("test")).migrate();
        assertEquals(0, again, "no migration applied twice");
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 1);
        }
    }

    @Test
    void dueRankSuspendsOnceAndRenewalRestoresTheHeldRankOnce() throws Exception {
        long granted = 1_000L;
        repo.loadOrCreate(c, ALEX, "Alex", null, granted);
        long due = granted + 7L * 24 * 60 * 60 * 1000;
        RenewalRecord renewal = repo.expireRankIfDue(c, ALEX, due, 7L * 24 * 60 * 60 * 1000, false, 3);
        assertEquals("PVE", renewal.branch());
        assertFalse(repo.find(c, ALEX).rankActive());
        assertEquals(renewal, repo.expireRankIfDue(c, ALEX, due + 1, 7L * 24 * 60 * 60 * 1000, true, 1));
        assertFalse(repo.progressRenewalPve(c, ALEX, 2, due));
        assertTrue(repo.progressRenewalPve(c, ALEX, 1, due + 1));
        assertTrue(repo.find(c, ALEX).rankActive());
        assertFalse(repo.progressRenewalPve(c, ALEX, 1, due + 2), "duplicate completion cannot renew twice");
        RenewalRecord next = repo.expireRankIfDue(c, ALEX, due + 1 + 7L * 24 * 60 * 60 * 1000,
                7L * 24 * 60 * 60 * 1000, false, "ZOMBIE", 2);
        assertTrue(next.active(), "a completed renewal row must be reused for the next weekly cycle");
        assertEquals("ZOMBIE", next.objectiveType());
        assertEquals(0, next.progress());
    }

    @Test
    void renewalHuntRejectsSameIpTarget() throws Exception {
        UUID target = UUID.randomUUID();
        repo.loadOrCreate(c, ALEX, "Alex", "same-ip", 1_000L);
        repo.loadOrCreate(c, target, "Target", "same-ip", 1_000L);
        long due = 1_000L + 7L * 24 * 60 * 60 * 1000;
        repo.expireRankIfDue(c, ALEX, due, 7L * 24 * 60 * 60 * 1000, true, "HUNT", 1);
        assertFalse(repo.assignRenewalTarget(c, ALEX, target));
        assertNull(repo.loadRenewal(c, ALEX).target());
    }

    @Test
    void renewalHuntRestoresOnlyForTheAssignedValidatedTarget() throws Exception {
        UUID target = UUID.randomUUID();
        repo.loadOrCreate(c, ALEX, "Alex", "first", 1_000L);
        repo.loadOrCreate(c, target, "Target", "second", 1_000L);
        long due = 1_000L + 7L * 24 * 60 * 60 * 1000;
        repo.expireRankIfDue(c, ALEX, due, 7L * 24 * 60 * 60 * 1000, true, "HUNT", 1);
        assertTrue(repo.assignRenewalTarget(c, ALEX, target));
        assertFalse(repo.completeRenewalHunt(c, ALEX, UUID.randomUUID(), due));
        assertFalse(repo.find(c, ALEX).rankActive());
        assertTrue(repo.completeRenewalHunt(c, ALEX, target, due + 1));
        assertTrue(repo.find(c, ALEX).rankActive());
    }

    @Test
    void offlineRenewalTargetCanBeClearedForReroll() throws Exception {
        UUID target = UUID.randomUUID();
        repo.loadOrCreate(c, ALEX, "Alex", "one", 1_000L);
        repo.loadOrCreate(c, target, "Target", "two", 1_000L);
        long due = 1_000L + 7L * 24 * 60 * 60 * 1000;
        repo.expireRankIfDue(c, ALEX, due, 7L * 24 * 60 * 60 * 1000, true, "HUNT", 1);
        assertTrue(repo.assignRenewalTarget(c, ALEX, target));
        assertTrue(repo.clearRenewalTarget(c, ALEX, target));
        assertNull(repo.loadRenewal(c, ALEX).target());
    }

    @Test
    void renewalHuntAbsenceClockPersistsAndResetsWhenTargetReturns() throws Exception {
        UUID target = UUID.randomUUID();
        repo.loadOrCreate(c, ALEX, "Alex", "one", 1_000L);
        repo.loadOrCreate(c, target, "Target", "two", 1_000L);
        long week = 7L * 24 * 60 * 60 * 1000;
        long threeDays = 3L * 24 * 60 * 60 * 1000;
        long assigned = 1_000L + week;
        repo.expireRankIfDue(c, ALEX, assigned, week, true, "HUNT", 1);
        assertTrue(repo.assignRenewalTarget(c, ALEX, target));
        repo.markRenewalTargetOffline(c, target, assigned + 1_000);
        assertEquals("HUNT", repo.observeRenewalHunt(c, ALEX, target, false, assigned + 1_000,
                threeDays, false, "HOSTILE", 15).branch());
        assertEquals("HUNT", repo.observeRenewalHunt(c, ALEX, target, false, assigned + 1_000 + threeDays - 1,
                threeDays, false, "HOSTILE", 15).branch());
        db.close();
        db = SqlDatabase.sqlite(dir.resolve("ascension.db"));
        repo = new PlayerRepository(db.dialect());
        c = db.connection();
        repo.markRenewalTargetOnline(c, target);
        repo.observeRenewalHunt(c, ALEX, target, true, assigned + 1_000 + threeDays,
                threeDays, false, "HOSTILE", 15);
        repo.markRenewalTargetOffline(c, target, assigned + 1_000 + threeDays + 1);
        assertEquals("HUNT", repo.observeRenewalHunt(c, ALEX, target, false, assigned + 1_000 + threeDays + 1,
                threeDays, false, "HOSTILE", 15).branch());
        RenewalRecord rerolled = repo.observeRenewalHunt(c, ALEX, target, false,
                assigned + 1_000 + 2 * threeDays + 1, threeDays, false, "HOSTILE", 15);
        assertEquals("PVE", rerolled.branch());
        assertEquals("HOSTILE", rerolled.objectiveType());
        assertEquals(15, rerolled.required());
        assertTrue(repo.progressRenewalPve(c, ALEX, 15, assigned + 1_000 + 2 * threeDays + 2));
        assertTrue(repo.find(c, ALEX).rankActive());
    }

    @Test
    void renewalWithoutAvailableTargetRerollsAtThreeDaysAndCanBecomeAnotherHunt() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 1_000L);
        try (var ps = c.prepareStatement("UPDATE players SET rank_index = 4 WHERE uuid = ?")) {
            ps.setString(1, ALEX.toString());
            ps.executeUpdate();
        }
        long week = 7L * 24 * 60 * 60 * 1000;
        long threeDays = 3L * 24 * 60 * 60 * 1000;
        long assigned = 1_000L + week;
        repo.expireRankIfDue(c, ALEX, assigned, week, true, "HUNT", 1);
        assertEquals("HUNT", repo.observeRenewalHunt(c, ALEX, null, false, assigned + threeDays - 1,
                threeDays, false, "ZOMBIE", 12).branch());
        RenewalRecord rerolled = repo.observeRenewalHunt(c, ALEX, null, false, assigned + threeDays,
                threeDays, true, "HUNT", 1);
        assertEquals("HUNT", rerolled.branch());
        assertEquals(assigned + threeDays, rerolled.assignedAt());
        assertEquals("HUNT", repo.observeRenewalHunt(c, ALEX, null, false, assigned + threeDays + 1,
                threeDays, false, "ZOMBIE", 12).branch());
        UUID replacement = UUID.randomUUID();
        repo.loadOrCreate(c, replacement, "Replacement", null, 1_000L);
        assertTrue(repo.assignRenewalTarget(c, ALEX, replacement));
        assertTrue(repo.completeRenewalHunt(c, ALEX, replacement, assigned + threeDays + 2));
        assertTrue(repo.find(c, ALEX).rankActive());
        assertEquals(4, repo.find(c, ALEX).rankIndex());
    }

    @Test
    void bountyHunterUsesRenewalTargetDuringInactiveHunt() throws Exception {
        UUID target = UUID.randomUUID();
        repo.loadOrCreate(c, ALEX, "Alex", "one", 1_000L);
        repo.loadOrCreate(c, target, "Target", "two", 1_000L);
        long week = 7L * 24 * 60 * 60 * 1000;
        repo.expireRankIfDue(c, ALEX, 1_000L + week, week, true, "HUNT", 1);
        assertTrue(repo.assignRenewalTarget(c, ALEX, target));
        assertEquals(target, repo.activeTitleHuntTarget(c, ALEX));
        assertTrue(repo.clearRenewalTarget(c, ALEX, target));
        assertNull(repo.activeTitleHuntTarget(c, ALEX));
    }

    @Test
    void normalQuestAcceptanceStopsAtTheSevenDayBoundary() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 1_000L);
        long week = 7L * 24 * 60 * 60 * 1000;
        assertTrue(repo.acceptQuest(c, ALEX, 0, 1_000L + week - 1));
        UUID other = UUID.randomUUID();
        repo.loadOrCreate(c, other, "Other", null, 1_000L);
        assertFalse(repo.acceptQuest(c, other, 0, 1_000L + week));
    }

    @Test
    void titleRebalanceMigratesOwnershipAndEquippedTitlesOnce() throws Exception {
        String[] oldIds = {"phantom", "echo_walker", "forgeheart", "duelist", "bulwark", "harvester"};
        String[] newIds = {"chronomancer", "frostbound", "emberforge", "berserker", "colossus", "mycelial_drifter"};
        for (int i = 0; i < oldIds.length; i++) {
            UUID owner = new UUID(0, i + 1);
            repo.loadOrCreate(c, owner, "Owner" + i, null, 100);
            assertTrue(repo.claimTitle(c, oldIds[i], owner, 1, 200));
            assertTrue(repo.equipTitle(c, owner, oldIds[i]));
        }
        String sql;
        try (var input = PlayerRepositoryTest.class.getClassLoader().getResourceAsStream("db/sqlite/V8__title_rebalance.sql")) {
            assertTrue(input != null);
            sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String statement : MigrationRunner.splitStatements(sql)) {
            try (Statement st = c.createStatement()) { st.executeUpdate(statement); }
        }
        // The data migration itself is safe when replayed, which is the property V8 relies on.
        for (String statement : MigrationRunner.splitStatements(sql)) {
            try (Statement st = c.createStatement()) { st.executeUpdate(statement); }
        }
        for (int i = 0; i < oldIds.length; i++) {
            UUID owner = new UUID(0, i + 1);
            assertEquals(Set.of(newIds[i]), repo.ownedTitles(c, owner));
            assertEquals(newIds[i], repo.equippedTitle(c, owner));
        }
        assertEquals(6, repo.titleOwners(c).size());
    }

    @Test
    void firstLoadCreatesBronzeOneWithNonce() throws Exception {
        PlayerRecord p = repo.loadOrCreate(c, ALEX, "Alex", "iphash", 1_000);
        assertEquals(0, p.rankIndex());
        assertFalse(p.mastery());
        assertNotEquals("", p.bookNonce());
        PlayerRecord again = repo.loadOrCreate(c, ALEX, "Alex2", "iphash", 2_000);
        assertEquals(p.bookNonce(), again.bookNonce());
        assertEquals("Alex2", again.name());
        assertEquals(1_000, again.firstJoin());
    }

    @Test
    void acceptQuestOnlyOnceAndOnlyForCurrentRank() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        assertFalse(repo.acceptQuest(c, ALEX, 1, 10), "not the current rank");
        assertTrue(repo.acceptQuest(c, ALEX, 0, 10));
        assertFalse(repo.acceptQuest(c, ALEX, 0, 20), "already accepted");
        QuestRecord q = repo.loadQuest(c, ALEX, 0);
        assertEquals(QuestState.ACTIVE, q.state());
        assertEquals(10, q.acceptedAt());
    }

    @Test
    void progressRoundTripsAndNeverDecreases() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        repo.saveProgress(c, ALEX, 0, new QuestDelta(Map.of("xp", 12L), Map.of("pk", Set.of("v1"))));
        repo.saveProgress(c, ALEX, 0, new QuestDelta(Map.of("xp", 5L), Map.of("pk", Set.of("v1", "v2"))));
        QuestRecord q = repo.loadQuest(c, ALEX, 0);
        assertEquals(12L, q.amounts().get("xp"), "a stale lower amount must not overwrite");
        assertEquals(Set.of("v1", "v2"), q.marks().get("pk"));
    }

    @Test
    void unacceptedQuestLoadsAsNotAccepted() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        QuestRecord q = repo.loadQuest(c, ALEX, 0);
        assertEquals(QuestState.NOT_ACCEPTED, q.state());
        assertTrue(q.amounts().isEmpty());
    }

    @Test
    void completingAdvancesRankExactlyOnce() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        QuestDelta fin = new QuestDelta(Map.of("xp", 30L), Map.of());
        assertEquals(CompletionResult.ADVANCED, repo.completeQuest(c, ALEX, 0, fin, false, 50));
        assertEquals(CompletionResult.ALREADY_DONE, repo.completeQuest(c, ALEX, 0, fin, false, 60));
        PlayerRecord p = repo.find(c, ALEX);
        assertEquals(1, p.rankIndex());
        assertEquals("rank_up:1", p.pendingNotice());
        assertEquals(QuestState.REWARDED, repo.loadQuest(c, ALEX, 0).state());
        assertEquals(30L, repo.loadQuest(c, ALEX, 0).amounts().get("xp"));
    }

    @Test
    void completingLastStageGrantsMasteryNotAnotherRank() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.setRank(c, ALEX, 11, false);
        repo.acceptQuest(c, ALEX, 11, 10);
        assertEquals(CompletionResult.MASTERED, repo.completeQuest(c, ALEX, 11, new QuestDelta(Map.of(), Map.of()), true, 50));
        PlayerRecord p = repo.find(c, ALEX);
        assertEquals(11, p.rankIndex());
        assertTrue(p.mastery());
    }

    @Test
    void completingAnUnacceptedQuestChangesNothing() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        assertEquals(CompletionResult.NOT_ACTIVE, repo.completeQuest(c, ALEX, 0, new QuestDelta(Map.of(), Map.of()), false, 50));
        assertEquals(0, repo.find(c, ALEX).rankIndex());
    }

    @Test
    void concurrentCompletionsRewardOnce() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        StorageHealth health = new StorageHealth(Logger.getLogger("test"));
        try (StorageExecutor exec = new StorageExecutor(db, health, Logger.getLogger("test"))) {
            List<CompletableFuture<CompletionResult>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(exec.submit("complete", conn ->
                        repo.completeQuest(conn, ALEX, 0, new QuestDelta(Map.of(), Map.of()), false, 99)));
            }
            long advanced = futures.stream().map(CompletableFuture::join)
                    .filter(r -> r == CompletionResult.ADVANCED).count();
            assertEquals(1, advanced);
        }
        assertEquals(1, repo.find(c, ALEX).rankIndex());
    }

    @Test
    void adminSetRankClearsLaterQuests() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        repo.completeQuest(c, ALEX, 0, new QuestDelta(Map.of(), Map.of()), false, 20);
        repo.acceptQuest(c, ALEX, 1, 30);
        repo.saveProgress(c, ALEX, 1, new QuestDelta(Map.of("xp", 5L), Map.of()));
        repo.setRank(c, ALEX, 1, false);
        assertEquals(QuestState.NOT_ACCEPTED, repo.loadQuest(c, ALEX, 1).state());
        assertTrue(repo.loadQuest(c, ALEX, 1).amounts().isEmpty());
        assertEquals(QuestState.REWARDED, repo.loadQuest(c, ALEX, 0).state(), "earlier history kept");
    }

    @Test
    void nonceRotationAndPendingDelivery() throws Exception {
        PlayerRecord p = repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.rotateNonce(c, ALEX, "n2");
        repo.setBookPending(c, ALEX, true);
        PlayerRecord q = repo.find(c, ALEX);
        assertEquals("n2", q.bookNonce());
        assertNotEquals(p.bookNonce(), q.bookNonce());
        assertTrue(q.bookPending());
    }

    @Test
    void flushWritesCooldownsAndAddsStats() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.flush(c, ALEX, -1, null, Map.of("stormbringer", 5_000L), Map.of("kills.hostile", 3L));
        repo.flush(c, ALEX, -1, null, Map.of("stormbringer", 9_000L), Map.of("kills.hostile", 2L));
        assertEquals(Map.of("stormbringer", 9_000L), repo.loadCooldowns(c, ALEX));
        assertEquals(5L, repo.loadStats(c, ALEX).get("kills.hostile"));
    }

    @Test
    void pendingNoticeCanBeCleared() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        repo.completeQuest(c, ALEX, 0, new QuestDelta(Map.of(), Map.of()), false, 20);
        repo.clearPendingNotice(c, ALEX);
        assertNull(repo.find(c, ALEX).pendingNotice());
    }

    @Test
    void lookupByNameIsCaseInsensitive() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        assertEquals(ALEX, repo.findUuidByName(c, "alex"));
        assertNull(repo.findUuidByName(c, "nobody"));
    }

    @Test
    void auditRowsAreReturnedNewestFirst() throws Exception {
        repo.audit(c, "console", "rank.set", "Alex", "gold 2", 10);
        repo.audit(c, "console", "rank.reset", "Alex", "", 20);
        List<AuditEntry> rows = repo.recentAudit(c, 10);
        assertEquals("rank.reset", rows.get(0).action());
        assertEquals(2, rows.size());
    }

    @Test
    void pvpCreditSurvivesRestartWindowAndRejectsSameIp() throws Exception {
        UUID victim = UUID.fromString("22222222-2222-2222-2222-222222222222");
        repo.loadOrCreate(c, ALEX, "Alex", "one", 0);
        repo.loadOrCreate(c, victim, "Steve", "two", 0);
        assertTrue(repo.creditPlayerKill(c, ALEX, victim, 100_000, 86_400_000, true));
        assertFalse(repo.creditPlayerKill(c, ALEX, victim, 100_001, 86_400_000, true));
        assertTrue(repo.creditPlayerKill(c, ALEX, victim, 86_500_000, 86_400_000, true));

        UUID alt = UUID.fromString("33333333-3333-3333-3333-333333333333");
        repo.loadOrCreate(c, alt, "Alt", "one", 0);
        assertFalse(repo.creditPlayerKill(c, ALEX, alt, 200_000, 86_400_000, true));
    }

    @Test
    void submissionJournalCompletesProgressExactlyOnce() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        SubmissionRecord op = new SubmissionRecord("op-1", ALEX, 0, "head", "DRAGON_HEAD", 2, 2);
        assertTrue(repo.beginSubmission(c, op, 20));
        assertTrue(repo.completeSubmission(c, "op-1", 2, 30));
        assertFalse(repo.completeSubmission(c, "op-1", 2, 40));
        assertEquals(2L, repo.loadQuest(c, ALEX, 0).amounts().get("head"));
        assertTrue(repo.pendingSubmissions(c, ALEX).isEmpty());
    }

    @Test
    void itemSubmissionsStopAtTheSevenDayRankDeadline() throws Exception {
        long deadline = 7L * 24 * 60 * 60 * 1000;
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        SubmissionRecord early = new SubmissionRecord("before-deadline", ALEX, 0, "head", "DRAGON_HEAD", 1, 1);
        SubmissionRecord late = new SubmissionRecord("at-deadline", ALEX, 0, "head", "DRAGON_HEAD", 1, 1);
        assertTrue(repo.beginSubmission(c, early, deadline - 1));
        assertFalse(repo.beginSubmission(c, late, deadline));
        assertFalse(repo.completeSubmission(c, early.operationId(), 1, deadline));
        assertTrue(repo.loadQuest(c, ALEX, 0).amounts().isEmpty());
    }

    @Test
    void preparedSubmissionCanBeRecoveredWithoutProgress() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.acceptQuest(c, ALEX, 0, 10);
        SubmissionRecord op = new SubmissionRecord("op-2", ALEX, 0, "head", "DRAGON_HEAD", 1, 1);
        assertTrue(repo.beginSubmission(c, op, 20));
        assertEquals(List.of(op), repo.pendingSubmissions(c, ALEX));
        repo.markSubmissionRestored(c, "op-2", 30);
        assertTrue(repo.pendingSubmissions(c, ALEX).isEmpty());
        assertTrue(repo.loadQuest(c, ALEX, 0).amounts().isEmpty());
    }

    @Test
    void huntAssignmentPersistsAndCompletesOnce() throws Exception {
        UUID first = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID second = UUID.fromString("55555555-5555-5555-5555-555555555555");
        repo.loadOrCreate(c, ALEX, "Alex", null, 100);
        repo.loadOrCreate(c, first, "First", null, 100);
        repo.loadOrCreate(c, second, "Second", null, 100);
        HuntRecord assigned = repo.assignHunt(c, ALEX, List.of(first, second), 200, 0);
        assertEquals(first, assigned.target());
        assertEquals(assigned, repo.assignHunt(c, ALEX, List.of(second), 300, 0));
        assertTrue(repo.completeHunt(c, ALEX, first, 400));
        assertFalse(repo.completeHunt(c, ALEX, first, 500));
        assertNull(repo.activeHunt(c, ALEX));
        assertEquals(second, repo.assignHunt(c, ALEX, List.of(first, second), 600, 1).target());
        assertEquals(first, repo.adminSetHunt(c, ALEX, first, 700).target());
        assertEquals(second, repo.adminRerollHunt(c, ALEX, List.of(first, second), 800).target());
        assertTrue(repo.adminCancelHunt(c, ALEX));
        assertNull(repo.activeHunt(c, ALEX));
    }

    @Test
    void globalTitleClaimIsUniqueAndEquipRequiresOwnership() throws Exception {
        UUID other = UUID.fromString("66666666-6666-6666-6666-666666666666");
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.loadOrCreate(c, other, "Other", null, 0);
        assertTrue(repo.claimTitle(c, "stormbringer", ALEX, 5, 10));
        assertFalse(repo.claimTitle(c, "stormbringer", other, 5, 11));
        assertTrue(repo.equipTitle(c, ALEX, "stormbringer"));
        assertFalse(repo.equipTitle(c, other, "stormbringer"));
        assertEquals("stormbringer", repo.equippedTitle(c, ALEX));
        assertEquals(Set.of("stormbringer"), repo.ownedTitles(c, ALEX));
        assertEquals(ALEX, repo.titleOwners(c).get("stormbringer"));
        repo.unequipTitle(c, ALEX);
        assertNull(repo.equippedTitle(c, ALEX));
    }

    @Test
    void adminTitleRecoveryMutationsRemainConsistent() throws Exception {
        UUID other = UUID.fromString("77777777-7777-7777-7777-777777777777");
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        repo.loadOrCreate(c, other, "Other", null, 0);

        assertTrue(repo.adminGiveTitle(c, "stormbringer", ALEX, 10));
        assertFalse(repo.adminGiveTitle(c, "stormbringer", other, 11));
        assertTrue(repo.equipTitle(c, ALEX, "stormbringer"));

        repo.adminTransferTitle(c, "stormbringer", other, 12);
        assertEquals(other, repo.titleOwners(c).get("stormbringer"));
        assertNull(repo.equippedTitle(c, ALEX));

        repo.flush(c, other, 0, null,
                Map.of("title.stormbringer.heavens_judgment", 100L, "rank.bronze", 200L), Map.of());
        repo.adminResetTitleCooldowns(c, other, "stormbringer");
        assertFalse(repo.loadCooldowns(c, other).containsKey("title.stormbringer.heavens_judgment"));
        assertEquals(200L, repo.loadCooldowns(c, other).get("rank.bronze"));

        repo.adminResetTitles(c, other);
        assertTrue(repo.ownedTitles(c, other).isEmpty());
        repo.adminGiveTitle(c, "stormbringer", ALEX, 13);
        repo.adminRevokeTitle(c, "stormbringer");
        assertFalse(repo.titleOwners(c).containsKey("stormbringer"));
    }

    @Test
    void abilityStateIsCappedConsumedAtomicallyAndLocationsAreUnique() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        assertEquals(1, repo.addAbilityState(c, ALEX, "soul_reaper.souls", 1, 3));
        assertEquals(3, repo.addAbilityState(c, ALEX, "soul_reaper.souls", 5, 3));
        assertTrue(repo.consumeAbilityState(c, ALEX, "soul_reaper.souls", 1));
        assertEquals(2, repo.abilityState(c, ALEX, "soul_reaper.souls"));
        assertFalse(repo.consumeAbilityState(c, ALEX, "soul_reaper.souls", 3));

        UUID world = UUID.fromString("88888888-8888-8888-8888-888888888888");
        assertTrue(repo.creditUniqueLocation(c, ALEX, "end_city", world, "100:64:200", 10));
        assertFalse(repo.creditUniqueLocation(c, ALEX, "end_city", world, "100:64:200", 11));
        assertTrue(repo.creditUniqueLocation(c, ALEX, "end_city", world, "300:70:400", 12));
    }

    @Test
    void uniqueLocationAndStatAreCommittedAtomically() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        UUID world = UUID.fromString("99999999-9999-9999-9999-999999999999");
        assertTrue(repo.creditUniqueLocationAndStat(c, ALEX, "conduit", world, "10:20:30", "conduits.activated", 1, 100));
        assertEquals(1L, repo.loadStats(c, ALEX).get("conduits.activated"));

        // Duplicate location is rejected and does not increment stat
        assertFalse(repo.creditUniqueLocationAndStat(c, ALEX, "conduit", world, "10:20:30", "conduits.activated", 1, 200));
        assertEquals(1L, repo.loadStats(c, ALEX).get("conduits.activated"));

        // Different location commits and increments stat atomically
        assertTrue(repo.creditUniqueLocationAndStat(c, ALEX, "conduit", world, "40:50:60", "conduits.activated", 1, 300));
        assertEquals(2L, repo.loadStats(c, ALEX).get("conduits.activated"));
    }

    @Test
    void deathlessDaysRequireActivityAndConsecutiveDates() throws Exception {
        repo.loadOrCreate(c, ALEX, "Alex", null, 0);
        LocalDate first = LocalDate.of(2026, 9, 1);
        assertFalse(addActivity(first, 1_799, 1_800).newlyQualified());
        DeathlessUpdate day1 = addActivity(first, 1, 1_800);
        assertTrue(day1.newlyQualified());
        assertEquals(1, day1.currentDays());
        assertFalse(addActivity(first, 60, 1_800).newlyQualified());
        DeathlessUpdate day2 = addActivity(first.plusDays(1), 1_800, 1_800);
        assertEquals(2, day2.currentDays());
        DeathlessUpdate afterGap = addActivity(first.plusDays(3), 1_800, 1_800);
        assertEquals(1, afterGap.currentDays());
        assertEquals(2, afterGap.bestDays());
        long deathAt = startOf(first.plusDays(3)) + 1_000;
        repo.resetDeathlessStreak(c, ALEX, first.plusDays(3), deathAt);
        assertEquals(0L, repo.loadStats(c, ALEX).get("deathless.current_days"));
        assertEquals(2L, repo.loadStats(c, ALEX).get("deathless.best_days"));
        DeathlessUpdate afterDeath = addActivity(first.plusDays(3), 1_800, 1_800);
        assertFalse(afterDeath.newlyQualified());
        assertEquals(0, afterDeath.currentDays());
    }

    private DeathlessUpdate addActivity(LocalDate day, int seconds, int required) throws Exception {
        return repo.addDailyActivity(c, ALEX, day, seconds, required, startOf(day), startOf(day.plusDays(1)));
    }

    private static long startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
