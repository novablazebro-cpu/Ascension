package com.leadsystems.minecraft.ascension.storage;

import com.leadsystems.minecraft.ascension.domain.quest.QuestDelta;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;

/**
 * All player-scoped SQL. Stateless: every method takes the connection, so it can only be called
 * from inside a {@link StorageExecutor} task. Methods that change more than one row run in their
 * own transaction; they must not be nested inside another {@link Tx#inTx} call.
 */
public final class PlayerRepository {

    private final Dialect dialect;

    public PlayerRepository(Dialect dialect) {
        this.dialect = dialect;
    }

    // ---------------------------------------------------------------- players

    /**
     * Loads the player row, creating it (Bronze I, fresh book nonce) on first join, and updates
     * the name, last-seen time and IP hash.
     */
    public PlayerRecord loadOrCreate(Connection c, UUID uuid, String name, String ipHash, long now)
            throws SQLException {
        return Tx.inTx(c, conn -> {
            PlayerRecord existing = find(conn, uuid);
            if (existing == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO players (uuid, last_name, rank_index, mastery, book_nonce, book_pending, "
                                + "first_join, last_seen, last_ip_hash, rank_active, rank_granted_at) VALUES (?, ?, 0, 0, ?, 0, ?, ?, ?, 1, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setString(3, UUID.randomUUID().toString());
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.setString(6, ipHash);
                    ps.setLong(7, now);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET last_name = ?, last_seen = ?, "
                                + "last_ip_hash = COALESCE(?, last_ip_hash) WHERE uuid = ?")) {
                    ps.setString(1, name);
                    ps.setLong(2, now);
                    ps.setString(3, ipHash);
                    ps.setString(4, uuid.toString());
                    ps.executeUpdate();
                }
            }
            return find(conn, uuid);
        });
    }

    public void updateLastSeen(Connection c, UUID uuid, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET last_seen = ? WHERE uuid = ?")) {
            ps.setLong(1, now);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** The player row, or null. */
    public PlayerRecord find(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_name, rank_index, mastery, book_nonce, book_pending, first_join, last_seen, "
                        + "pending_notice, rank_active, rank_granted_at FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PlayerRecord(uuid, rs.getString(1), rs.getInt(2), rs.getInt(3) != 0,
                        rs.getString(4), rs.getInt(5) != 0, rs.getLong(6), rs.getLong(7), rs.getString(8),
                        rs.getInt(9) != 0, rs.getLong(10));
            }
        }
    }

    /** UUID of the most recently seen player with this name (case-insensitive), or null. */
    public UUID findUuidByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid FROM players WHERE lower(last_name) = lower(?) ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    // ---------------------------------------------------------------- quests

    /** The stored quest for a stage; NOT_ACCEPTED with no progress if there is no row. */
    public QuestRecord loadQuest(Connection c, UUID uuid, int rankIndex) throws SQLException {
        QuestState state = QuestState.NOT_ACCEPTED;
        long acceptedAt = 0L;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state, accepted_at FROM quests WHERE uuid = ? AND rank_index = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, rankIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    state = QuestState.valueOf(rs.getString(1));
                    acceptedAt = rs.getLong(2);
                }
            }
        }
        Map<String, Long> amounts = new HashMap<>();
        Map<String, Set<String>> marks = new HashMap<>();
        if (state != QuestState.NOT_ACCEPTED) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT objective_id, amount FROM quest_progress WHERE uuid = ? AND rank_index = ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, rankIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        amounts.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT objective_id, mark FROM quest_marks WHERE uuid = ? AND rank_index = ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, rankIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        marks.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
                    }
                }
            }
        }
        return new QuestRecord(rankIndex, state, acceptedAt, amounts, marks);
    }

    /**
     * Accepts the quest for {@code rankIndex}. Succeeds only if that is the player's current stage,
     * they do not have mastery, and the quest was never accepted before.
     */
    public boolean acceptQuest(Connection c, UUID uuid, int rankIndex, long now) throws SQLException {
        return Tx.inTx(c, conn -> {
            PlayerRecord p = find(conn, uuid);
            if (p == null || p.rankIndex() != rankIndex || p.mastery() || !p.rankActive()
                    || now - p.rankGrantedAt() >= 7L * 24 * 60 * 60 * 1000) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(dialect.insertQuestIgnore())) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, rankIndex);
                ps.setLong(3, now);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Persists progress for an ACTIVE quest (ignored for any other state). */
    public void saveProgress(Connection c, UUID uuid, int rankIndex, QuestDelta delta) throws SQLException {
        Tx.inTx(c, conn -> {
            writeProgressIfActive(conn, uuid, rankIndex, delta);
            return null;
        });
    }

    /**
     * The reward transaction: writes final progress, marks the quest REWARDED and advances the rank
     * (or grants mastery on the last stage) atomically. Idempotent: a repeat returns ALREADY_DONE
     * and changes nothing, so a duplicated event can never grant two rank-ups.
     */
    public CompletionResult completeQuest(Connection c, UUID uuid, int rankIndex, QuestDelta fin,
                                          boolean last, long now) throws SQLException {
        return Tx.inTx(c, conn -> {
            String state = questState(conn, uuid, rankIndex);
            if (state == null) {
                return CompletionResult.NOT_ACTIVE;
            }
            if (QuestState.REWARDED.name().equals(state)) {
                return CompletionResult.ALREADY_DONE;
            }
            PlayerRecord p = find(conn, uuid);
            if (p == null || p.rankIndex() != rankIndex || !p.rankActive()
                    || now - p.rankGrantedAt() >= 7L * 24 * 60 * 60 * 1000) {
                return CompletionResult.RANK_MISMATCH;
            }
            if (last && p.mastery()) {
                return CompletionResult.ALREADY_DONE;
            }
            writeProgressIfActive(conn, uuid, rankIndex, fin);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE quests SET state = 'REWARDED', completed_at = ?, rewarded_at = ? "
                            + "WHERE uuid = ? AND rank_index = ? AND state IN ('ACTIVE', 'COMPLETED')")) {
                ps.setLong(1, now);
                ps.setLong(2, now);
                ps.setString(3, uuid.toString());
                ps.setInt(4, rankIndex);
                requireOne(ps.executeUpdate(), "quest state update");
            }
            String sql = last
                    ? "UPDATE players SET mastery = 1, rank_active = 1, rank_granted_at = ?, pending_notice = ?, row_version = row_version + 1 "
                    + "WHERE uuid = ? AND rank_index = ? AND mastery = 0"
                    : "UPDATE players SET rank_index = rank_index + 1, rank_active = 1, rank_granted_at = ?, pending_notice = ?, "
                    + "row_version = row_version + 1 WHERE uuid = ? AND rank_index = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                ps.setLong(i++, now);
                ps.setString(i++, last ? "mastery" : "rank_up:" + (rankIndex + 1));
                ps.setString(i++, uuid.toString());
                ps.setInt(i, rankIndex);
                requireOne(ps.executeUpdate(), "player rank update");
            }
            return last ? CompletionResult.MASTERED : CompletionResult.ADVANCED;
        });
    }

    /**
     * Admin: sets the rank stage and mastery flag, deleting quests (and progress) at or above the
     * new stage so the player starts that stage fresh. Earlier quest history is kept.
     */
    public void setRank(Connection c, UUID uuid, int rankIndex, boolean mastery) throws SQLException {
        Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET rank_index = ?, mastery = ?, rank_active = 1, rank_granted_at = ?, row_version = row_version + 1 WHERE uuid = ?")) {
                ps.setInt(1, rankIndex);
                ps.setInt(2, mastery ? 1 : 0);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rank_renewals WHERE uuid = ?")) {
                ps.setString(1, uuid.toString()); ps.executeUpdate();
            }
            for (String table : List.of("quests", "quest_progress", "quest_marks")) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + table + " WHERE uuid = ? AND rank_index >= ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, mastery ? rankIndex + 1 : rankIndex);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- book

    public void rotateNonce(Connection c, UUID uuid, String nonce) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE players SET book_nonce = ? WHERE uuid = ?")) {
            ps.setString(1, nonce);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public void setBookPending(Connection c, UUID uuid, boolean pending) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE players SET book_pending = ? WHERE uuid = ?")) {
            ps.setInt(1, pending ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public void clearPendingNotice(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE players SET pending_notice = NULL WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- rank renewal

    public RenewalRecord loadRenewal(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT held_rank_index, branch, objective_type, required, progress, target_uuid, state, assigned_at FROM rank_renewals WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new RenewalRecord(uuid, rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6) == null ? null : UUID.fromString(rs.getString(6)), rs.getString(7), rs.getLong(8)) : null;
            }
        }
    }

    /** Atomically suspends a due rank and persists its 50/50 quest branch exactly once. */
    public RenewalRecord expireRankIfDue(Connection c, UUID uuid, long now, long durationMs, boolean huntBranch,
                                         String objectiveType, int required) throws SQLException {
        return Tx.inTx(c, conn -> {
            PlayerRecord p = find(conn, uuid);
            if (p == null || !p.rankActive() || now - p.rankGrantedAt() < durationMs) return loadRenewal(conn, uuid);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE players SET rank_active = 0, pending_notice = 'rank_expired', row_version = row_version + 1 WHERE uuid = ? AND rank_active = 1 AND rank_granted_at <= ?")) {
                ps.setString(1, uuid.toString()); ps.setLong(2, now - durationMs);
                if (ps.executeUpdate() != 1) return loadRenewal(conn, uuid);
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO rank_renewals (uuid, held_rank_index, branch, objective_type, required, state, assigned_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?) ON CONFLICT(uuid) DO UPDATE SET held_rank_index = excluded.held_rank_index, branch = excluded.branch, objective_type = excluded.objective_type, required = excluded.required, progress = 0, target_uuid = NULL, state = 'ACTIVE', assigned_at = excluded.assigned_at, target_absent_since = 0, completed_at = NULL WHERE rank_renewals.state = 'COMPLETED'")) {
                ps.setString(1, uuid.toString()); ps.setInt(2, p.rankIndex()); ps.setString(3, huntBranch ? "HUNT" : "PVE"); ps.setString(4, objectiveType); ps.setInt(5, required); ps.setLong(6, now); ps.executeUpdate();
            }
            return loadRenewal(conn, uuid);
        });
    }

    /** Compatibility overload retained for existing storage tests. */
    public RenewalRecord expireRankIfDue(Connection c, UUID uuid, long now, long durationMs, boolean huntBranch,
                                         int required) throws SQLException {
        return expireRankIfDue(c, uuid, now, durationMs, huntBranch, huntBranch ? "HUNT" : "HOSTILE", required);
    }

    public boolean assignRenewalTarget(Connection c, UUID uuid, UUID target) throws SQLException {
        return Tx.inTx(c, conn -> {
            if (uuid.equals(target) || sameKnownIp(conn, uuid, target)) return false;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rank_renewals SET target_uuid = ?, target_absent_since = 0 WHERE uuid = ? AND branch = 'HUNT' AND state = 'ACTIVE' AND target_uuid IS NULL")) {
                ps.setString(1, target.toString()); ps.setString(2, uuid.toString()); return ps.executeUpdate() == 1;
            }
        });
    }

    public boolean clearRenewalTarget(Connection c, UUID uuid, UUID expected) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE rank_renewals SET target_uuid = NULL, target_absent_since = 0, assigned_at = ? WHERE uuid = ? AND branch = 'HUNT' AND state = 'ACTIVE' AND target_uuid = ?")) {
            ps.setLong(1, System.currentTimeMillis()); ps.setString(2, uuid.toString()); ps.setString(3, expected.toString()); return ps.executeUpdate() == 1;
        }
    }

    /** Record the target's departure even when the rank holder is offline. */
    public void markRenewalTargetOffline(Connection c, UUID target, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE rank_renewals SET target_absent_since = ? WHERE target_uuid = ? "
                        + "AND branch = 'HUNT' AND state = 'ACTIVE' AND target_absent_since = 0")) {
            ps.setLong(1, now);
            ps.setString(2, target.toString());
            ps.executeUpdate();
        }
    }

    /** An online sighting restarts every renewal hunt's continuous absence clock. */
    public void markRenewalTargetOnline(Connection c, UUID target) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE rank_renewals SET target_absent_since = 0 WHERE target_uuid = ? "
                        + "AND branch = 'HUNT' AND state = 'ACTIVE' AND target_absent_since <> 0")) {
            ps.setString(1, target.toString());
            ps.executeUpdate();
        }
    }

    /** Uses the active renewal hunt while the rank is suspended; normal hunts resume after renewal. */
    public UUID activeTitleHuntTarget(Connection c, UUID hunter) throws SQLException {
        PlayerRecord player = find(c, hunter);
        if (player == null) return null;
        if (!player.rankActive()) {
            RenewalRecord renewal = loadRenewal(c, hunter);
            return renewal != null && renewal.active() && "HUNT".equals(renewal.branch())
                    ? renewal.target() : null;
        }
        HuntRecord hunt = activeHunt(c, hunter);
        return hunt == null ? null : hunt.target();
    }

    /**
     * Atomically observes a hunt target and rerolls after three continuous days of absence.
     * expectedTarget prevents a stale scheduler callback from changing a newer assignment.
     */
    public RenewalRecord observeRenewalHunt(Connection c, UUID uuid, UUID expectedTarget, boolean targetOnline,
                                            long now, long timeoutMs, boolean huntBranch,
                                            String objectiveType, int required) throws SQLException {
        return Tx.inTx(c, conn -> {
            RenewalRecord current = loadRenewal(conn, uuid);
            if (current == null || !current.active() || !"HUNT".equals(current.branch())
                    || !java.util.Objects.equals(current.target(), expectedTarget)) return current;
            PlayerRecord player = find(conn, uuid);
            if (player == null || player.rankActive()) return current;
            long absentSince;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT target_absent_since FROM rank_renewals WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return current;
                    absentSince = rs.getLong(1);
                }
            }
            if (expectedTarget != null && targetOnline) {
                if (absentSince != 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE rank_renewals SET target_absent_since = 0 WHERE uuid = ?")) {
                        ps.setString(1, uuid.toString()); ps.executeUpdate();
                    }
                }
                return current;
            }
            if (expectedTarget != null && absentSince == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rank_renewals SET target_absent_since = ? WHERE uuid = ?")) {
                    ps.setLong(1, now); ps.setString(2, uuid.toString()); ps.executeUpdate();
                }
                return current;
            }
            long start = expectedTarget == null ? current.assignedAt() : absentSince;
            if (now - start < timeoutMs) return current;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE rank_renewals SET branch = ?, objective_type = ?, required = ?, progress = 0, "
                            + "target_uuid = NULL, target_absent_since = 0, assigned_at = ? "
                            + "WHERE uuid = ? AND branch = 'HUNT' AND state = 'ACTIVE'")) {
                ps.setString(1, huntBranch ? "HUNT" : "PVE");
                ps.setString(2, objectiveType);
                ps.setInt(3, required);
                ps.setLong(4, now);
                ps.setString(5, uuid.toString());
                ps.executeUpdate();
            }
            return loadRenewal(conn, uuid);
        });
    }

    public boolean progressRenewalPve(Connection c, UUID uuid, int amount, long now) throws SQLException {
        return Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rank_renewals SET progress = MIN(required, progress + ?) WHERE uuid = ? AND branch = 'PVE' AND state = 'ACTIVE'")) {
                ps.setInt(1, amount); ps.setString(2, uuid.toString()); if (ps.executeUpdate() != 1) return false;
            }
            return completeRenewalIfReady(conn, uuid, now);
        });
    }

    public boolean completeRenewalHunt(Connection c, UUID uuid, UUID victim, long now) throws SQLException {
        return Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rank_renewals SET progress = required WHERE uuid = ? AND branch = 'HUNT' AND state = 'ACTIVE' AND target_uuid = ?")) {
                ps.setString(1, uuid.toString()); ps.setString(2, victim.toString()); if (ps.executeUpdate() != 1) return false;
            }
            return completeRenewalIfReady(conn, uuid, now);
        });
    }

    private boolean completeRenewalIfReady(Connection c, UUID uuid, long now) throws SQLException {
        RenewalRecord r = loadRenewal(c, uuid);
        if (r == null || !r.active() || r.progress() < r.required()) return false;
        try (PreparedStatement done = c.prepareStatement("UPDATE rank_renewals SET state = 'COMPLETED', completed_at = ? WHERE uuid = ? AND state = 'ACTIVE'")) {
            done.setLong(1, now); done.setString(2, uuid.toString()); if (done.executeUpdate() != 1) return false;
        }
        try (PreparedStatement restore = c.prepareStatement("UPDATE players SET rank_active = 1, rank_granted_at = ?, pending_notice = 'rank_renewed', row_version = row_version + 1 WHERE uuid = ? AND rank_active = 0 AND rank_index = ?")) {
            restore.setLong(1, now); restore.setString(2, uuid.toString()); restore.setInt(3, r.heldRankIndex());
            requireOne(restore.executeUpdate(), "renewal rank restore");
            return true;
        }
    }

    // ---------------------------------------------------------------- batched flush

    /**
     * Writes one player's batched changes in a single transaction.
     *
     * @param rankIndex  stage the quest delta belongs to (ignored when {@code quest} is null)
     * @param quest      quest progress delta, or null
     * @param cooldowns  ability key to absolute ready-at time
     * @param statDeltas lifetime statistic key to amount to add
     */
    public void flush(Connection c, UUID uuid, int rankIndex, QuestDelta quest,
                      Map<String, Long> cooldowns, Map<String, Long> statDeltas) throws SQLException {
        Tx.inTx(c, conn -> {
            if (quest != null && !quest.isEmpty() && rankIndex >= 0) {
                writeProgressIfActive(conn, uuid, rankIndex, quest);
            }
            if (!cooldowns.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(dialect.upsertCooldown())) {
                    for (Map.Entry<String, Long> e : cooldowns.entrySet()) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, e.getKey());
                        ps.setLong(3, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!statDeltas.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(dialect.addStat())) {
                    for (Map.Entry<String, Long> e : statDeltas.entrySet()) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, e.getKey());
                        ps.setLong(3, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return null;
        });
    }

    public Map<String, Long> loadCooldowns(Connection c, UUID uuid) throws SQLException {
        return loadLongMap(c, "SELECT ability_key, ready_at FROM cooldowns WHERE uuid = ?", uuid);
    }

    public Map<String, Long> loadStats(Connection c, UUID uuid) throws SQLException {
        return loadLongMap(c, "SELECT stat_key, amount FROM lifetime_stats WHERE uuid = ?", uuid);
    }

    /**
     * Atomically grants one valid PvP credit. Repeated victims and same-IP alternate accounts fail
     * without changing the stored timestamp, so rejected farming cannot extend the cooldown.
     */
    public boolean creditPlayerKill(Connection c, UUID killer, UUID victim, long now,
                                    long cooldownMillis, boolean rejectSameIp) throws SQLException {
        return creditPlayerKill(c, killer, victim, now, cooldownMillis, rejectSameIp, Map.of());
    }

    /** Accepted kill and captured personal title XP are one durable transaction. */
    public boolean creditPlayerKill(Connection c, UUID killer, UUID victim, long now,
                                    long cooldownMillis, boolean rejectSameIp,
                                    Map<String, Long> bonusStats) throws SQLException {
        return Tx.inTx(c, conn -> {
            if (killer.equals(victim)) {
                return false;
            }
            if (rejectSameIp && sameKnownIp(conn, killer, victim)) {
                return false;
            }
            long cutoff = now - cooldownMillis;
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE pvp_kill_credit SET credited_at = ? WHERE killer_uuid = ? AND victim_uuid = ? "
                            + "AND credited_at <= ?")) {
                update.setLong(1, now);
                update.setString(2, killer.toString());
                update.setString(3, victim.toString());
                update.setLong(4, cutoff);
                if (update.executeUpdate() == 1) {
                    addBonusStats(conn, killer, bonusStats);
                    return true;
                }
            }
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO pvp_kill_credit (killer_uuid, victim_uuid, credited_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT (killer_uuid, victim_uuid) DO NOTHING")) {
                insert.setString(1, killer.toString());
                insert.setString(2, victim.toString());
                insert.setLong(3, now);
                boolean credited = insert.executeUpdate() == 1;
                if (credited) addBonusStats(conn, killer, bonusStats);
                return credited;
            }
        });
    }

    private static boolean sameKnownIp(Connection c, UUID a, UUID b) throws SQLException {
        String first = null;
        String second = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, last_ip_hash FROM players WHERE uuid IN (?, ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (a.toString().equals(rs.getString(1))) {
                        first = rs.getString(2);
                    } else {
                        second = rs.getString(2);
                    }
                }
            }
        }
        return first != null && !first.isBlank() && first.equals(second);
    }

    /** Creates the durable journal row before a submission touches the inventory. */
    public boolean beginSubmission(Connection c, SubmissionRecord op, long now) throws SQLException {
        return Tx.inTx(c, conn -> {
            PlayerRecord player = find(conn, op.uuid());
            if (player == null || player.mastery() || player.rankIndex() != op.rankIndex()
                    || !player.rankActive() || now - player.rankGrantedAt() >= 7L * 24 * 60 * 60 * 1000
                    || !QuestState.ACTIVE.name().equals(questState(conn, op.uuid(), op.rankIndex()))) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO item_submissions (operation_id, uuid, rank_index, objective_id, material, "
                            + "amount, before_count, state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)")) {
                ps.setString(1, op.operationId());
                ps.setString(2, op.uuid().toString());
                ps.setInt(3, op.rankIndex());
                ps.setString(4, op.objectiveId());
                ps.setString(5, op.material());
                ps.setInt(6, op.amount());
                ps.setInt(7, op.beforeCount());
                ps.setLong(8, now);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Commits submitted progress and closes the journal in one idempotent transaction. */
    public boolean completeSubmission(Connection c, String operationId, long required, long now) throws SQLException {
        return Tx.inTx(c, conn -> {
            SubmissionRecord op = pendingSubmission(conn, operationId);
            PlayerRecord player = op == null ? null : find(conn, op.uuid());
            if (player == null || player.rankIndex() != op.rankIndex() || !player.rankActive()
                    || now - player.rankGrantedAt() >= 7L * 24 * 60 * 60 * 1000
                    || !QuestState.ACTIVE.name().equals(questState(conn, op.uuid(), op.rankIndex()))) {
                return false;
            }
            long current = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT amount FROM quest_progress WHERE uuid = ? AND rank_index = ? AND objective_id = ?")) {
                ps.setString(1, op.uuid().toString());
                ps.setInt(2, op.rankIndex());
                ps.setString(3, op.objectiveId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getLong(1);
                    }
                }
            }
            long result = Math.min(required, current + op.amount());
            try (PreparedStatement ps = conn.prepareStatement(dialect.upsertProgressMax())) {
                ps.setString(1, op.uuid().toString());
                ps.setInt(2, op.rankIndex());
                ps.setString(3, op.objectiveId());
                ps.setLong(4, result);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE item_submissions SET state = 'COMPLETED', finished_at = ? "
                            + "WHERE operation_id = ? AND state = 'PREPARED'")) {
                ps.setLong(1, now);
                ps.setString(2, operationId);
                requireOne(ps.executeUpdate(), "submission completion");
            }
            try (PreparedStatement ps = conn.prepareStatement(dialect.addStat())) {
                ps.setString(1, op.uuid().toString());
                ps.setString(2, "submitted." + op.material());
                ps.setLong(3, op.amount());
                ps.executeUpdate();
            }
            return true;
        });
    }

    public List<SubmissionRecord> pendingSubmissions(Connection c, UUID uuid) throws SQLException {
        List<SubmissionRecord> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT operation_id, rank_index, objective_id, material, amount, before_count "
                        + "FROM item_submissions WHERE uuid = ? AND state = 'PREPARED' ORDER BY created_at")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SubmissionRecord(rs.getString(1), uuid, rs.getInt(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getInt(6)));
                }
            }
        }
        return out;
    }

    public void markSubmissionRestored(Connection c, String operationId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE item_submissions SET state = 'RESTORED', finished_at = ? "
                        + "WHERE operation_id = ? AND state = 'PREPARED'")) {
            ps.setLong(1, now);
            ps.setString(2, operationId);
            ps.executeUpdate();
        }
    }

    private static SubmissionRecord pendingSubmission(Connection c, String operationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, rank_index, objective_id, material, amount, before_count "
                        + "FROM item_submissions WHERE operation_id = ? AND state = 'PREPARED'")) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new SubmissionRecord(operationId, UUID.fromString(rs.getString(1)), rs.getInt(2),
                        rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6)) : null;
            }
        }
    }

    public HuntRecord activeHunt(Connection c, UUID hunter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT target_uuid, assigned_at, state FROM hunt_assignments "
                        + "WHERE hunter_uuid = ? AND state = 'ACTIVE'")) {
            ps.setString(1, hunter.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new HuntRecord(hunter, UUID.fromString(rs.getString(1)),
                        rs.getLong(2), rs.getString(3)) : null;
            }
        }
    }

    /** Assigns the first acceptable ordered candidate, preserving an existing active assignment. */
    public HuntRecord assignHunt(Connection c, UUID hunter, List<UUID> candidates,
                                 long now, long avoidSince) throws SQLException {
        return Tx.inTx(c, conn -> {
            HuntRecord existing = activeHunt(conn, hunter);
            if (existing != null) {
                return existing;
            }
            UUID target = chooseHuntTarget(conn, hunter, candidates, avoidSince);
            if (target == null) {
                return null;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO hunt_assignments (hunter_uuid, target_uuid, assigned_at, state) "
                            + "VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (hunter_uuid) DO UPDATE SET "
                            + "target_uuid = excluded.target_uuid, assigned_at = excluded.assigned_at, state = 'ACTIVE'")) {
                ps.setString(1, hunter.toString());
                ps.setString(2, target.toString());
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return new HuntRecord(hunter, target, now, "ACTIVE");
        });
    }

    public HuntRecord rerollHunt(Connection c, UUID hunter, List<UUID> candidates, long now,
                                 long offlineBefore, long avoidSince) throws SQLException {
        return rerollHunt(c, hunter, candidates, Set.of(), now, offlineBefore, avoidSince);
    }

    public HuntRecord rerollHunt(Connection c, UUID hunter, List<UUID> candidates, Set<UUID> onlineTargets,
                                 long now, long offlineBefore, long avoidSince) throws SQLException {
        return Tx.inTx(c, conn -> {
            HuntRecord current = activeHunt(conn, hunter);
            if (current == null || onlineTargets.contains(current.target())
                    || lastSeen(conn, current.target()) > offlineBefore) {
                return current;
            }
            List<UUID> filtered = candidates.stream().filter(id -> !id.equals(current.target())).toList();
            UUID target = chooseHuntTarget(conn, hunter, filtered, avoidSince);
            if (target == null) {
                return current;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE hunt_assignments SET target_uuid = ?, assigned_at = ?, state = 'ACTIVE' "
                            + "WHERE hunter_uuid = ?")) {
                ps.setString(1, target.toString());
                ps.setLong(2, now);
                ps.setString(3, hunter.toString());
                ps.executeUpdate();
            }
            return new HuntRecord(hunter, target, now, "ACTIVE");
        });
    }

    public boolean completeHunt(Connection c, UUID hunter, UUID target, long now) throws SQLException {
        return completeHunt(c, hunter, target, now, Map.of());
    }

    /** Accepted hunt and captured personal title XP are one durable transaction. */
    public boolean completeHunt(Connection c, UUID hunter, UUID target, long now,
                                Map<String, Long> bonusStats) throws SQLException {
        return Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE hunt_assignments SET state = 'COMPLETED' "
                            + "WHERE hunter_uuid = ? AND target_uuid = ? AND state = 'ACTIVE'")) {
                ps.setString(1, hunter.toString());
                ps.setString(2, target.toString());
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO hunt_history (hunter_uuid, target_uuid, completed_at) VALUES (?, ?, ?)")) {
                ps.setString(1, hunter.toString());
                ps.setString(2, target.toString());
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            addBonusStats(conn, hunter, bonusStats);
            return true;
        });
    }

    private void addBonusStats(Connection c, UUID uuid, Map<String, Long> stats) throws SQLException {
        if (stats.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(dialect.addStat())) {
            for (Map.Entry<String, Long> entry : stats.entrySet()) {
                if (entry.getValue() <= 0) continue;
                ps.setString(1, uuid.toString());
                ps.setString(2, entry.getKey());
                ps.setLong(3, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public HuntRecord adminSetHunt(Connection c, UUID hunter, UUID target, long now) throws SQLException {
        if (hunter.equals(target)) {
            throw new IllegalArgumentException("hunter and target must be different players");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO hunt_assignments (hunter_uuid, target_uuid, assigned_at, state) "
                        + "VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (hunter_uuid) DO UPDATE SET "
                        + "target_uuid = excluded.target_uuid, assigned_at = excluded.assigned_at, state = 'ACTIVE'")) {
            ps.setString(1, hunter.toString());
            ps.setString(2, target.toString());
            ps.setLong(3, now);
            ps.executeUpdate();
        }
        return new HuntRecord(hunter, target, now, "ACTIVE");
    }

    public boolean adminCancelHunt(Connection c, UUID hunter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE hunt_assignments SET state = 'CANCELLED' WHERE hunter_uuid = ? AND state = 'ACTIVE'")) {
            ps.setString(1, hunter.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public HuntRecord adminRerollHunt(Connection c, UUID hunter, List<UUID> candidates, long now) throws SQLException {
        HuntRecord current = activeHunt(c, hunter);
        List<UUID> filtered = current == null ? candidates
                : candidates.stream().filter(id -> !id.equals(current.target())).toList();
        UUID target = filtered.stream().filter(id -> !id.equals(hunter)).findFirst().orElse(null);
        return target == null ? current : adminSetHunt(c, hunter, target, now);
    }

    private static UUID chooseHuntTarget(Connection c, UUID hunter, List<UUID> candidates, long avoidSince)
            throws SQLException {
        if (candidates.isEmpty()) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM hunt_history WHERE hunter_uuid = ? AND target_uuid = ? "
                        + "AND completed_at >= ? LIMIT 1")) {
            for (UUID candidate : candidates) {
                if (candidate.equals(hunter)) {
                    continue;
                }
                ps.setString(1, hunter.toString());
                ps.setString(2, candidate.toString());
                ps.setLong(3, avoidSince);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
        }
        return candidates.stream().filter(id -> !id.equals(hunter)).findFirst().orElse(null);
    }

    private static long lastSeen(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT last_seen FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public Map<String, UUID> titleOwners(Connection c) throws SQLException {
        Map<String, UUID> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT title_id, owner_uuid FROM title_ownership");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), UUID.fromString(rs.getString(2)));
            }
        }
        return out;
    }

    public Set<String> ownedTitles(Connection c, UUID uuid) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT title_id FROM title_ownership WHERE owner_uuid = ? ORDER BY acquired_at")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    public String equippedTitle(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT equipped_title FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Database-enforced global uniqueness and per-player ownership limit. */
    public boolean claimTitle(Connection c, String titleId, UUID owner, int ownershipLimit, long now)
            throws SQLException {
        return Tx.inTx(c, conn -> {
            try (PreparedStatement count = conn.prepareStatement(
                    "SELECT COUNT(*) FROM title_ownership WHERE owner_uuid = ?")) {
                count.setString(1, owner.toString());
                try (ResultSet rs = count.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= ownershipLimit) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO title_ownership (title_id, owner_uuid, acquired_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT (title_id) DO NOTHING")) {
                ps.setString(1, titleId);
                ps.setString(2, owner.toString());
                ps.setLong(3, now);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public boolean equipTitle(Connection c, UUID owner, String titleId) throws SQLException {
        return Tx.inTx(c, conn -> {
            try (PreparedStatement own = conn.prepareStatement(
                    "SELECT 1 FROM title_ownership WHERE title_id = ? AND owner_uuid = ?")) {
                own.setString(1, titleId);
                own.setString(2, owner.toString());
                try (ResultSet rs = own.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET equipped_title = ? WHERE uuid = ?")) {
                ps.setString(1, titleId);
                ps.setString(2, owner.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Beta testing mode: equips a configured title without creating global ownership. */
    public boolean equipTitleForTesting(Connection c, UUID owner, String titleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET equipped_title = ? WHERE uuid = ?")) {
            ps.setString(1, titleId);
            ps.setString(2, owner.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Beta testing mode: changes the selected stage without deleting saved quest history. */
    public boolean setRankForTesting(Connection c, UUID owner, int rankIndex) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE players SET rank_index = ?, mastery = 0, row_version = row_version + 1 WHERE uuid = ?")) {
            ps.setInt(1, rankIndex);
            ps.setString(2, owner.toString());
            return ps.executeUpdate() == 1;
        }
    }

    public void unequipTitle(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE players SET equipped_title = NULL WHERE uuid = ?")) {
            ps.setString(1, owner.toString());
            ps.executeUpdate();
        }
    }

    public boolean adminGiveTitle(Connection c, String titleId, UUID owner, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO title_ownership (title_id, owner_uuid, acquired_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (title_id) DO NOTHING")) {
            ps.setString(1, titleId);
            ps.setString(2, owner.toString());
            ps.setLong(3, now);
            return ps.executeUpdate() == 1;
        }
    }

    public void adminRevokeTitle(Connection c, String titleId) throws SQLException {
        Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET equipped_title = NULL WHERE equipped_title = ?")) {
                ps.setString(1, titleId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM title_ownership WHERE title_id = ?")) {
                ps.setString(1, titleId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void adminTransferTitle(Connection c, String titleId, UUID owner, long now) throws SQLException {
        Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE players SET equipped_title = NULL WHERE equipped_title = ?")) {
                ps.setString(1, titleId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO title_ownership (title_id, owner_uuid, acquired_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT (title_id) DO UPDATE SET owner_uuid = excluded.owner_uuid, "
                            + "acquired_at = excluded.acquired_at")) {
                ps.setString(1, titleId);
                ps.setString(2, owner.toString());
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void adminResetTitles(Connection c, UUID owner) throws SQLException {
        Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM title_ownership WHERE owner_uuid = ?")) {
                ps.setString(1, owner.toString());
                ps.executeUpdate();
            }
            unequipTitle(conn, owner);
            return null;
        });
    }

    public void adminResetTitleCooldowns(Connection c, UUID owner, String titleId) throws SQLException {
        String pattern = titleId == null ? "title.%" : "title." + titleId + ".%";
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM cooldowns WHERE uuid = ? AND ability_key LIKE ?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, pattern);
            ps.executeUpdate();
        }
    }

    public long addAbilityState(Connection c, UUID owner, String key, long amount, long maximum) throws SQLException {
        return Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ability_state (uuid, state_key, state_value) VALUES (?, ?, ?) "
                            + "ON CONFLICT (uuid, state_key) DO UPDATE SET state_value = "
                            + "MIN(?, CAST(ability_state.state_value AS INTEGER) "
                            + "+ CAST(excluded.state_value AS INTEGER))")) {
                ps.setString(1, owner.toString());
                ps.setString(2, key);
                ps.setLong(3, amount);
                ps.setLong(4, maximum);
                ps.executeUpdate();
            }
            return abilityState(conn, owner, key);
        });
    }

    public boolean consumeAbilityState(Connection c, UUID owner, String key, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ability_state SET state_value = CAST(state_value AS INTEGER) - ? "
                        + "WHERE uuid = ? AND state_key = ? AND CAST(state_value AS INTEGER) >= ?")) {
            ps.setLong(1, amount);
            ps.setString(2, owner.toString());
            ps.setString(3, key);
            ps.setLong(4, amount);
            return ps.executeUpdate() == 1;
        }
    }

    public long abilityState(Connection c, UUID owner, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT CAST(state_value AS INTEGER) FROM ability_state WHERE uuid = ? AND state_key = ?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public boolean creditUniqueLocation(Connection c, UUID owner, String category, UUID world,
                                        String identity, long now) throws SQLException {
        return creditUniqueLocationAndStat(c, owner, category, world, identity, null, 0, now);
    }

    public boolean creditUniqueLocationAndStat(Connection c, UUID owner, String category, UUID world,
                                               String identity, String statKey, long statAmount,
                                               long now) throws SQLException {
        return Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO unique_location_progress (uuid, category, world_uuid, identity, credited_at) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (uuid, category, world_uuid, identity) DO NOTHING")) {
                ps.setString(1, owner.toString());
                ps.setString(2, category);
                ps.setString(3, world.toString());
                ps.setString(4, identity);
                ps.setLong(5, now);
                if (ps.executeUpdate() != 1) {
                    return false;
                }
            }
            if (statKey != null && statAmount > 0) {
                try (PreparedStatement ps = conn.prepareStatement(dialect.addStat())) {
                    ps.setString(1, owner.toString());
                    ps.setString(2, statKey);
                    ps.setLong(3, statAmount);
                    ps.executeUpdate();
                }
            }
            return true;
        });
    }

    /** Adds active seconds and advances a calendar-day streak at most once for this day. */
    public DeathlessUpdate addDailyActivity(Connection c, UUID uuid, LocalDate day, int seconds,
                                            int requiredSeconds, long dayStartMs, long dayEndMs) throws SQLException {
        return Tx.inTx(c, conn -> {
            int current = 0;
            int best = 0;
            LocalDate last = null;
            long lastDeathAt = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT current_days, best_days, last_qualified_day, last_death_at "
                            + "FROM deathless_streaks WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getInt(1);
                        best = rs.getInt(2);
                        String value = rs.getString(3);
                        last = value == null ? null : LocalDate.parse(value);
                        lastDeathAt = rs.getLong(4);
                    }
                }
            }
            if (lastDeathAt >= dayStartMs && lastDeathAt < dayEndMs) {
                return new DeathlessUpdate(uuid, current, best, false);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO daily_activity (uuid, activity_day, active_seconds) VALUES (?, ?, ?) "
                            + "ON CONFLICT (uuid, activity_day) DO UPDATE SET active_seconds = "
                            + "MIN(86400, active_seconds + excluded.active_seconds)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, day.toString());
                ps.setInt(3, seconds);
                ps.executeUpdate();
            }
            int active;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT active_seconds FROM daily_activity WHERE uuid = ? AND activity_day = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, day.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    active = rs.next() ? rs.getInt(1) : 0;
                }
            }
            boolean qualified = active >= requiredSeconds && (last == null || day.isAfter(last));
            if (qualified) {
                current = last != null && last.plusDays(1).equals(day) ? current + 1 : 1;
                best = Math.max(best, current);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO deathless_streaks (uuid, current_days, best_days, last_qualified_day) "
                                + "VALUES (?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET current_days = excluded.current_days, "
                                + "best_days = excluded.best_days, last_qualified_day = excluded.last_qualified_day")) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, current);
                    ps.setInt(3, best);
                    ps.setString(4, day.toString());
                    ps.executeUpdate();
                }
                setStat(conn, uuid, "deathless.current_days", current);
                setStat(conn, uuid, "deathless.best_days", best);
            }
            return new DeathlessUpdate(uuid, current, best, qualified);
        });
    }

    public void resetDeathlessStreak(Connection c, UUID uuid, LocalDate day, long now) throws SQLException {
        Tx.inTx(c, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO deathless_streaks (uuid, current_days, best_days, last_death_at) VALUES (?, 0, 0, ?) "
                            + "ON CONFLICT (uuid) DO UPDATE SET current_days = 0, last_qualified_day = NULL, "
                            + "last_death_at = excluded.last_death_at")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM daily_activity WHERE uuid = ? AND activity_day = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, day.toString());
                ps.executeUpdate();
            }
            setStat(conn, uuid, "deathless.current_days", 0);
            return null;
        });
    }

    private static void setStat(Connection c, UUID uuid, String key, long value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lifetime_stats (uuid, stat_key, amount) VALUES (?, ?, ?) "
                        + "ON CONFLICT (uuid, stat_key) DO UPDATE SET amount = excluded.amount")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, key);
            ps.setLong(3, value);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- audit

    public void audit(Connection c, String actor, String action, String target, String details, long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO admin_audit (actor, action, target, details, at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, target);
            ps.setString(4, details);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public List<AuditEntry> recentAudit(Connection c, int limit) throws SQLException {
        List<AuditEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, actor, action, target, details, at FROM admin_audit ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(6)));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private String questState(Connection c, UUID uuid, int rankIndex) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state FROM quests WHERE uuid = ? AND rank_index = ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, rankIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Writes progress rows only while the quest is ACTIVE or COMPLETED, so a late flush can never
     * resurrect progress for a quest an admin just reset or that was already rewarded.
     */
    private void writeProgressIfActive(Connection c, UUID uuid, int rankIndex, QuestDelta delta)
            throws SQLException {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        String state = questState(c, uuid, rankIndex);
        if (!QuestState.ACTIVE.name().equals(state) && !QuestState.COMPLETED.name().equals(state)) {
            return;
        }
        if (!delta.amounts().isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(dialect.upsertProgressMax())) {
                for (Map.Entry<String, Long> e : delta.amounts().entrySet()) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, rankIndex);
                    ps.setString(3, e.getKey());
                    ps.setLong(4, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (!delta.newMarks().isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(dialect.insertMarkIgnore())) {
                for (Map.Entry<String, Set<String>> e : delta.newMarks().entrySet()) {
                    for (String mark : e.getValue()) {
                        ps.setString(1, uuid.toString());
                        ps.setInt(2, rankIndex);
                        ps.setString(3, e.getKey());
                        ps.setString(4, mark);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
        }
    }

    private static Map<String, Long> loadLongMap(Connection c, String sql, UUID uuid) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

    private static void requireOne(int updated, String what) {
        if (updated != 1) {
            // Rolls back the whole transaction: nothing is granted unless every step applied.
            throw new IllegalStateException(what + " touched " + updated + " rows (expected 1)");
        }
    }
}
