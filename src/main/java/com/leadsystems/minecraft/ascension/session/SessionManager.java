package com.leadsystems.minecraft.ascension.session;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.integration.PlatformDetector;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.storage.PlayerRecord;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.QuestRecord;
import com.leadsystems.minecraft.ascension.storage.SqlTask;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the player-data lifecycle:
 * <ol>
 *   <li><b>Pre-login</b> (async login thread): wait for any pending quit-save of the same UUID,
 *       then load or create the player's data. Blocking is allowed on this thread.</li>
 *   <li><b>Join</b> (main): build the {@link PlayerSession} and run {@link SessionHook}s. If the
 *       load failed the player still joins; the session stays unloaded (degraded) and loading is
 *       retried in the background.</li>
 *   <li><b>Play</b>: counters are batch-saved every {@code flush-interval-seconds}.</li>
 *   <li><b>Quit</b>: a final save is queued and remembered, so a fast rejoin waits for it.</li>
 * </ol>
 * All maps except the two concurrent ones are main-thread only.
 */
public final class SessionManager {

    private final Logger log;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;
    private final PlatformDetector platform;
    private final IpHasher ipHasher;

    private final Map<UUID, PlayerSession> sessions = new HashMap<>();
    private final Map<UUID, FlushBatch> orphaned = new HashMap<>();
    private final List<SessionHook> hooks = new ArrayList<>();
    private final ConcurrentHashMap<UUID, Object> preloaded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<?>> pendingSaves = new ConcurrentHashMap<>();

    public SessionManager(Logger log, StorageExecutor storage, PlayerRepository repo, MainThread main,
                          Supplier<ConfigSnapshot> config, PlatformDetector platform, IpHasher ipHasher) {
        this.log = log;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
        this.config = config;
        this.platform = platform;
        this.ipHasher = ipHasher;
    }

    public void addHook(SessionHook hook) {
        hooks.add(hook);
    }

    /** The online player's session, or null. */
    public PlayerSession get(UUID uuid) {
        return sessions.get(uuid);
    }

    public PlayerSession get(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public Collection<PlayerSession> all() {
        return List.copyOf(sessions.values());
    }

    // ------------------------------------------------------------------ pre-login (async)

    /** Called on the async login thread. Blocks for at most ~15 s. */
    public void preLogin(UUID uuid, String name, InetAddress address) {
        CompletableFuture<?> pending = pendingSaves.get(uuid);
        if (pending != null) {
            try {
                pending.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warning("Previous save for " + name + " did not finish in time; loading anyway.");
            }
        }
        String ipHash = ipHasher.hash(address);
        long now = System.currentTimeMillis();
        try {
            SessionSnapshot snap = storage.submit("load " + name, c -> load(c, uuid, name, ipHash, now))
                    .get(10, TimeUnit.SECONDS);
            preloaded.put(uuid, snap);
        } catch (Exception e) {
            preloaded.put(uuid, e);
            log.log(Level.WARNING, "Could not load Ascension data for " + name + "; joining in degraded mode", e);
        }
    }

    private SessionSnapshot load(Connection c, UUID uuid, String name, String ipHash, long now) throws SQLException {
        PlayerRecord p = repo.loadOrCreate(c, uuid, name, ipHash, now);
        QuestRecord q = p.mastery() || !p.rankActive() ? null : repo.loadQuest(c, uuid, p.rankIndex());
        return new SessionSnapshot(p, q, repo.loadRenewal(c, uuid), repo.loadCooldowns(c, uuid), repo.loadStats(c, uuid),
                repo.ownedTitles(c, uuid), repo.equippedTitle(c, uuid));
    }

    // ------------------------------------------------------------------ join / quit (main)

    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = new PlayerSession(uuid, player.getName(), platform.isBedrock(uuid));
        sessions.put(uuid, session);
        Object pre = preloaded.remove(uuid);
        if (pre instanceof SessionSnapshot snap) {
            activate(player, session, snap, false);
        } else {
            loadAsync(player, session);
        }
    }

    /** Loads a session that has no pre-login data (reload, or a failed pre-login load). */
    private void loadAsync(Player player, PlayerSession session) {
        if (session.loading()) {
            return;
        }
        session.setLoading(true);
        UUID uuid = session.uuid();
        String name = session.name();
        String ipHash = player.getAddress() == null ? null : ipHasher.hash(player.getAddress().getAddress());
        long now = System.currentTimeMillis();
        main.onComplete(storage.submit("load " + name, c -> load(c, uuid, name, ipHash, now)), (snap, error) -> {
            session.setLoading(false);
            Player online = Bukkit.getPlayer(uuid);
            if (sessions.get(uuid) != session || online == null) {
                return;
            }
            if (error != null) {
                log.warning("Loading Ascension data for " + name + " failed again: " + error);
                return;
            }
            activate(online, session, snap, false);
        });
    }

    private void activate(Player player, PlayerSession session, SessionSnapshot snap, boolean reload) {
        session.apply(snap, config.get()::questFor);
        FlushBatch orphan = orphaned.remove(session.uuid());
        if (orphan != null) {
            session.restore(orphan, true);
        }
        runHooks(player, session, reload);
    }

    private void runHooks(Player player, PlayerSession session, boolean reload) {
        for (SessionHook hook : hooks) {
            try {
                hook.onLoaded(player, session, reload);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Session hook failed for " + player.getName(), t);
            }
        }
    }

    public void quit(Player player) {
        PlayerSession session = sessions.remove(player.getUniqueId());
        preloaded.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        unloadHooks(player, session);
        if (session.loaded()) {
            FlushBatch batch = session.drain();
            if (!batch.isEmpty()) {
                save(batch, true);
            }
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        storage.submit("player.last-seen", c -> {
            repo.updateLastSeen(c, uuid, now);
            return null;
        });
    }

    private void unloadHooks(Player player, PlayerSession session) {
        for (SessionHook hook : hooks) {
            try {
                hook.onUnload(player, session);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Session unload hook failed for " + player.getName(), t);
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private CompletableFuture<Void> save(FlushBatch batch, boolean quitting) {
        UUID uuid = batch.uuid();
        CompletableFuture<Void> future = storage.submit("save", c -> {
            repo.flush(c, uuid, batch.rankIndex(), batch.quest(), batch.cooldowns(), batch.statDeltas());
            return null;
        });
        if (quitting) {
            pendingSaves.put(uuid, future);
            future.whenComplete((v, e) -> pendingSaves.remove(uuid, future));
        }
        main.onComplete(future, (v, error) -> {
            if (error == null) {
                return;
            }
            PlayerSession live = sessions.get(uuid);
            if (live != null && live.loaded() && !quitting) {
                live.restore(batch, false);
            } else {
                orphaned.merge(uuid, batch, FlushBatch::merge);
            }
        });
        return future;
    }

    /** Periodic batched save of every online player. */
    public void flushAll() {
        for (PlayerSession s : sessions.values()) {
            if (!s.loaded()) {
                continue;
            }
            FlushBatch batch = s.drain();
            if (!batch.isEmpty()) {
                save(batch, false);
            }
        }
    }

    /** Immediately saves one player's pending changes (e.g. after a long cooldown starts). */
    public void flushNow(PlayerSession session) {
        if (!session.loaded()) {
            return;
        }
        FlushBatch batch = session.drain();
        if (!batch.isEmpty()) {
            save(batch, false);
        }
    }

    /** Retries saves of players who left while storage was failing. */
    public void retryOrphans() {
        if (orphaned.isEmpty()) {
            return;
        }
        List<FlushBatch> batches = new ArrayList<>(orphaned.values());
        orphaned.clear();
        for (FlushBatch b : batches) {
            save(b, false);
        }
    }

    /** Retries loading sessions whose data never arrived. */
    public void retryLoads() {
        for (PlayerSession s : sessions.values()) {
            if (!s.loaded() && !s.loading()) {
                Player p = Bukkit.getPlayer(s.uuid());
                if (p != null) {
                    loadAsync(p, s);
                }
            }
        }
    }

    // ------------------------------------------------------------------ admin mutations

    /**
     * Runs an admin mutation safely: for an online player the session is frozen, its unsaved
     * progress saved, the mutation applied and the session reloaded from the database - all on
     * the storage thread in that order - so the database stays the single source of truth.
     *
     * @param done called on the main thread with the mutation's result or error
     */
    public <T> void mutate(UUID uuid, String label, SqlTask<T> mutation, BiConsumer<T, Throwable> done) {
        PlayerSession session = sessions.get(uuid);
        FlushBatch batch = null;
        if (session != null && session.loaded()) {
            session.setFrozen(true);
            batch = session.drain();
        }
        final FlushBatch toSave = batch;
        final String name = session == null ? uuid.toString() : session.name();
        CompletableFuture<Object[]> future = storage.submit(label, c -> {
            if (toSave != null && !toSave.isEmpty()) {
                repo.flush(c, uuid, toSave.rankIndex(), toSave.quest(), toSave.cooldowns(), toSave.statDeltas());
            }
            T result = mutation.run(c);
            PlayerRecord p = repo.find(c, uuid);
            SessionSnapshot snap = null;
            if (p != null && session != null) {
                snap = load(c, uuid, name, null, System.currentTimeMillis());
            }
            return new Object[] {result, snap};
        });
        main.onComplete(future, (pair, error) -> {
            PlayerSession live = sessions.get(uuid);
            if (live != null) {
                live.setFrozen(false);
            }
            if (error != null) {
                if (live != null && toSave != null) {
                    live.restore(toSave, false);
                }
                done.accept(null, error);
                return;
            }
            @SuppressWarnings("unchecked")
            T result = (T) pair[0];
            SessionSnapshot snap = (SessionSnapshot) pair[1];
            Player online = Bukkit.getPlayer(uuid);
            if (live != null && live == session && snap != null && online != null) {
                live.apply(snap, config.get()::questFor);
                runHooks(online, live, true);
            }
            done.accept(result, null);
        });
    }

    /** Re-binds every session's quest to the current config (after /titles reload). */
    public void rebindAll() {
        ConfigSnapshot cfg = config.get();
        for (PlayerSession s : sessions.values()) {
            if (!s.loaded()) {
                continue;
            }
            if (s.quest() != null) {
                var def = cfg.questFor(s.rankIndex());
                if (def != null) {
                    s.setQuest(s.quest().rebind(def));
                }
            }
            Player p = Bukkit.getPlayer(s.uuid());
            if (p != null) {
                runHooks(p, s, true);
            }
        }
    }

    // ------------------------------------------------------------------ enable / disable

    /** Loads sessions for players already online (plugin enabled while players are connected). */
    public void adoptOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!sessions.containsKey(p.getUniqueId())) {
                join(p);
            }
        }
    }

    /** Disable: undo applied effects, queue a final save for everyone. Storage is closed after. */
    public void shutdown() {
        for (PlayerSession s : List.copyOf(sessions.values())) {
            Player p = Bukkit.getPlayer(s.uuid());
            if (p != null) {
                unloadHooks(p, s);
            }
            if (s.loaded()) {
                FlushBatch batch = s.drain();
                if (!batch.isEmpty()) {
                    UUID uuid = batch.uuid();
                    storage.submit("shutdown save", c -> {
                        repo.flush(c, uuid, batch.rankIndex(), batch.quest(), batch.cooldowns(), batch.statDeltas());
                        return null;
                    });
                }
            }
        }
        for (FlushBatch b : orphaned.values()) {
            storage.submit("shutdown orphan save", c -> {
                repo.flush(c, b.uuid(), b.rankIndex(), b.quest(), b.cooldowns(), b.statDeltas());
                return null;
            });
        }
        orphaned.clear();
        sessions.clear();
    }
}
