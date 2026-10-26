package com.leadsystems.minecraft.ascension.session;

import com.leadsystems.minecraft.ascension.ability.AbilityRuntime;
import com.leadsystems.minecraft.ascension.domain.cooldown.CooldownBook;
import com.leadsystems.minecraft.ascension.domain.quest.ActiveQuest;
import com.leadsystems.minecraft.ascension.domain.quest.QuestDefinition;
import com.leadsystems.minecraft.ascension.domain.quest.QuestDelta;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import com.leadsystems.minecraft.ascension.storage.QuestRecord;
import com.leadsystems.minecraft.ascension.storage.RenewalRecord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

/**
 * In-memory state of one online player. <b>Main-thread only</b>: listeners, commands and menus
 * mutate it; the storage thread only ever receives immutable {@link FlushBatch} snapshots.
 * <p>
 * The book nonce held here is always the <i>DB-committed</i> nonce: it is only replaced after the
 * rotation transaction succeeds, so a book is never considered valid before its nonce is durable.
 */
public final class PlayerSession {

    private final UUID uuid;
    private final String name;
    private final boolean bedrock;

    private boolean loaded;
    private boolean loading;
    private boolean frozen;

    private int rankIndex;
    private boolean mastery;
    private boolean rankActive = true;
    private long rankGrantedAt;
    private RenewalRecord renewal;
    private String bookNonce = "";
    private boolean bookPending;
    private boolean bookIssuing;
    private String pendingNotice;
    private ActiveQuest quest;

    private final CooldownBook cooldowns = new CooldownBook();
    private final Map<String, Long> stats = new HashMap<>();
    private final Map<String, Long> statDeltas = new HashMap<>();
    private final Set<String> operations = new HashSet<>();
    private final Set<String> ownedTitles = new HashSet<>();
    private String equippedTitle;
    private final AbilityRuntime abilities = new AbilityRuntime();

    private long menuToken;
    private boolean menuTokenLive;
    private long lastMenuOpenMs;
    private boolean reconcileQueued;
    private long lastBookWarningMs;

    public PlayerSession(UUID uuid, String name, boolean bedrock) {
        this.uuid = uuid;
        this.name = name;
        this.bedrock = bedrock;
    }

    /** Applies freshly loaded storage state (initial load or admin reload). */
    public void apply(SessionSnapshot snapshot, IntFunction<QuestDefinition> questDefs) {
        this.rankIndex = snapshot.player().rankIndex();
        this.mastery = snapshot.player().mastery();
        this.rankActive = snapshot.player().rankActive();
        this.rankGrantedAt = snapshot.player().rankGrantedAt();
        this.renewal = snapshot.renewal();
        this.bookNonce = snapshot.player().bookNonce();
        this.bookPending = snapshot.player().bookPending();
        this.pendingNotice = snapshot.player().pendingNotice();
        QuestRecord q = snapshot.quest();
        QuestDefinition def = mastery || !rankActive ? null : questDefs.apply(rankIndex);
        if (def == null) {
            this.quest = null;
        } else if (q == null) {
            this.quest = ActiveQuest.fresh(def, QuestState.NOT_ACCEPTED, 0L);
        } else {
            this.quest = ActiveQuest.restore(def, q.state(), q.acceptedAt(), q.amounts(), q.marks());
        }
        cooldowns.replace(snapshot.cooldowns());
        stats.clear();
        stats.putAll(snapshot.stats());
        statDeltas.clear();
        ownedTitles.clear();
        ownedTitles.addAll(snapshot.ownedTitles());
        equippedTitle = snapshot.equippedTitle();
        this.loaded = true;
        this.loading = false;
    }

    // ------------------------------------------------------------------ flushing

    /** Drains every unsaved change into an immutable batch. */
    public FlushBatch drain() {
        QuestDelta delta = quest == null ? null : quest.drainDirty();
        Map<String, Long> cd = cooldowns.drainDirty();
        Map<String, Long> st = Map.copyOf(statDeltas);
        statDeltas.clear();
        return new FlushBatch(uuid, quest == null ? -1 : rankIndex, delta, cd, st);
    }

    /**
     * Puts a failed batch back so it is retried on the next flush.
     *
     * @param valuesMissing true if the batch's values are not reflected in this session yet
     *                      (an orphan from a failed quit-save merged into a new session)
     */
    public void restore(FlushBatch batch, boolean valuesMissing) {
        if (quest != null && batch.quest() != null && batch.rankIndex() == rankIndex) {
            quest.mergeDelta(batch.quest());
        }
        cooldowns.restoreDirty(batch.cooldowns());
        if (valuesMissing) {
            cooldowns.load(batch.cooldowns());
        }
        batch.statDeltas().forEach((k, v) -> {
            statDeltas.merge(k, v, Long::sum);
            if (valuesMissing) {
                stats.merge(k, v, Long::sum);
            }
        });
    }

    /** Adds to a lifetime statistic. */
    public void addStat(String key, long amount) {
        if (amount <= 0) {
            return;
        }
        stats.merge(key, amount, Long::sum);
        statDeltas.merge(key, amount, Long::sum);
    }

    public long stat(String key) {
        return stats.getOrDefault(key, 0L);
    }

    /** Adds a statistic already committed by a separate transaction without losing pending deltas. */
    public void addCommittedStat(String key, long amount) {
        if (amount > 0) {
            stats.merge(key, amount, Long::sum);
        }
    }

    /** Applies an already committed absolute statistic without marking it for additive flush. */
    public void setCommittedStat(String key, long value) {
        stats.put(key, value);
        statDeltas.remove(key);
    }

    // ------------------------------------------------------------------ operation gate

    /**
     * Starts a named operation (accept, claim, submit...). Returns false if one with that name is
     * already in flight, so double-clicks and replayed form responses cannot run it twice.
     */
    public boolean tryBegin(String operation) {
        return operations.add(operation);
    }

    public void end(String operation) {
        operations.remove(operation);
    }

    public boolean inFlight(String operation) {
        return operations.contains(operation);
    }

    // ------------------------------------------------------------------ menu tokens

    /** Issues a new one-time menu token, invalidating any previously opened menu. */
    public long newMenuToken() {
        long t;
        do {
            t = ThreadLocalRandom.current().nextLong();
        } while (t == 0L || t == menuToken);
        menuToken = t;
        menuTokenLive = true;
        return t;
    }

    /** Consumes the token; true only for the current, unused token. */
    public boolean consumeMenuToken(long token) {
        if (!menuTokenLive || token != menuToken) {
            return false;
        }
        menuTokenLive = false;
        return true;
    }

    // ------------------------------------------------------------------ accessors

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public boolean bedrock() {
        return bedrock;
    }

    public boolean loaded() {
        return loaded;
    }

    /** Loaded and not frozen by an admin operation: gameplay may read and change it. */
    public boolean usable() {
        return loaded && !frozen;
    }

    public boolean loading() {
        return loading;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public boolean frozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public int rankIndex() {
        return rankIndex;
    }

    public void setRankIndex(int rankIndex) {
        this.rankIndex = rankIndex;
    }

    public boolean mastery() {
        return mastery;
    }

    /** Rank index remains the held rank while inactive; callers must gate powers on this flag. */
    public boolean rankActive() { return rankActive; }
    public boolean rankUsable(long now) { return rankActive && now - rankGrantedAt < 7L * 24 * 60 * 60 * 1000; }
    public void setRankActive(boolean rankActive) { this.rankActive = rankActive; }
    public long rankGrantedAt() { return rankGrantedAt; }
    public void setRankGrantedAt(long value) { this.rankGrantedAt = value; }
    public RenewalRecord renewal() { return renewal; }
    public void setRenewal(RenewalRecord renewal) { this.renewal = renewal; }

    public void setMastery(boolean mastery) {
        this.mastery = mastery;
    }

    public String bookNonce() {
        return bookNonce;
    }

    public void setBookNonce(String bookNonce) {
        this.bookNonce = bookNonce;
    }

    public boolean bookPending() {
        return bookPending;
    }

    public void setBookPending(boolean bookPending) {
        this.bookPending = bookPending;
    }

    public boolean bookIssuing() {
        return bookIssuing;
    }

    public void setBookIssuing(boolean bookIssuing) {
        this.bookIssuing = bookIssuing;
    }

    public String pendingNotice() {
        return pendingNotice;
    }

    public void setPendingNotice(String pendingNotice) {
        this.pendingNotice = pendingNotice;
    }

    /** The current stage's quest, or null with mastery. */
    public ActiveQuest quest() {
        return quest;
    }

    public void setQuest(ActiveQuest quest) {
        this.quest = quest;
    }

    public CooldownBook cooldowns() {
        return cooldowns;
    }

    public Map<String, Long> statsView() {
        return Map.copyOf(stats);
    }

    public AbilityRuntime abilities() {
        return abilities;
    }

    public Set<String> ownedTitles() {
        return Set.copyOf(ownedTitles);
    }

    public String equippedTitle() {
        return equippedTitle;
    }

    public long lastMenuOpenMs() {
        return lastMenuOpenMs;
    }

    public void setLastMenuOpenMs(long ms) {
        this.lastMenuOpenMs = ms;
    }

    public boolean reconcileQueued() {
        return reconcileQueued;
    }

    public void setReconcileQueued(boolean queued) {
        this.reconcileQueued = queued;
    }

    public long lastBookWarningMs() {
        return lastBookWarningMs;
    }

    public void setLastBookWarningMs(long ms) {
        this.lastBookWarningMs = ms;
    }
}
