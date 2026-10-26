package com.leadsystems.minecraft.ascension.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TitleXpRepositoryTest {
    @TempDir Path dir;
    SqlDatabase db;
    PlayerRepository repo;
    Connection connection;
    UUID hunter = UUID.randomUUID();
    UUID victim = UUID.randomUUID();

    @BeforeEach void setUp() throws Exception {
        db = SqlDatabase.sqlite(dir.resolve("xp.db"));
        new MigrationRunner(db, dir.resolve("backups"), Logger.getAnonymousLogger()).migrate();
        connection = db.connection();
        repo = new PlayerRepository(db.dialect());
        repo.loadOrCreate(connection, hunter, "Hunter", "one", 1);
        repo.loadOrCreate(connection, victim, "Victim", "two", 1);
    }

    @AfterEach void close() { db.close(); }

    @Test void acceptedPvpWritesCapturedXpExactlyOnceAndRejectedCreditsWriteNone() throws Exception {
        Map<String, Long> xp = Map.of("title.xp.stormbringer", 50L);
        assertTrue(repo.creditPlayerKill(connection, hunter, victim, 1000, 10000, true, xp));
        assertFalse(repo.creditPlayerKill(connection, hunter, victim, 1001, 10000, true, xp));
        assertFalse(repo.creditPlayerKill(connection, hunter, hunter, 1002, 10000, true, xp));
        assertEquals(50L, repo.loadStats(connection, hunter).get("title.xp.stormbringer"));
        assertFalse(repo.loadStats(connection, hunter).containsKey("title.xp.bloodlord"));
    }

    @Test void acceptedHuntWritesCapturedXpExactlyOnceAndPersistsAcrossReopen() throws Exception {
        repo.adminSetHunt(connection, hunter, victim, 100);
        Map<String, Long> xp = Map.of("title.xp.stormbringer", 100L);
        assertFalse(repo.completeHunt(connection, hunter, UUID.randomUUID(), 200, xp));
        assertTrue(repo.completeHunt(connection, hunter, victim, 201, xp));
        assertFalse(repo.completeHunt(connection, hunter, victim, 202, xp));
        assertEquals(100L, repo.loadStats(connection, hunter).get("title.xp.stormbringer"));
        db.close();
        db = SqlDatabase.sqlite(dir.resolve("xp.db"));
        repo = new PlayerRepository(db.dialect());
        assertEquals(100L, repo.loadStats(db.connection(), hunter).get("title.xp.stormbringer"));
    }

    @Test void xpWriteFailureRollsBackValidationSoRetryRemainsPossible() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_xp BEFORE INSERT ON lifetime_stats "
                    + "BEGIN SELECT RAISE(ABORT, 'XP storage failure'); END");
        }
        Map<String, Long> xp = Map.of("title.xp.stormbringer", 50L);
        assertThrows(SQLException.class,
                () -> repo.creditPlayerKill(connection, hunter, victim, 1000, 10000, true, xp));
        assertTrue(repo.loadStats(connection, hunter).isEmpty());
        try (var statement = connection.createStatement()) { statement.execute("DROP TRIGGER fail_xp"); }
        assertTrue(repo.creditPlayerKill(connection, hunter, victim, 1000, 10000, true, xp));
        assertEquals(50L, repo.loadStats(connection, hunter).get("title.xp.stormbringer"));
    }
}
