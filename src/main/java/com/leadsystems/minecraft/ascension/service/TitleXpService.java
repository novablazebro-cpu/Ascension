package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.title.TitleLevels;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Personal title XP. Main-thread captures survive title switches and asynchronous validators. */
public final class TitleXpService implements SessionHook {
    /** Captured at the gameplay action, never at its later storage callback. */
    public record Source(UUID uuid, PlayerSession session, String titleId, TitleLevels levels) {}

    private final SessionManager sessions;
    private final Supplier<ConfigSnapshot> config;
    private BiConsumer<Player, PlayerSession> onLevelChanged = (player, session) -> {};
    private final Set<UUID> reconciling = new HashSet<>();
    private final Set<UUID> pendingReconcile = new HashSet<>();

    public TitleXpService(SessionManager sessions, Supplier<ConfigSnapshot> config) {
        this.sessions = sessions;
        this.config = config;
    }

    public void setOnLevelChanged(BiConsumer<Player, PlayerSession> callback) {
        onLevelChanged = callback == null ? (player, session) -> {} : callback;
    }

    public Source capture(Player player) {
        if (player == null) return null;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return null;
        PlayerSession session = sessions.get(player);
        if (session == null || !session.usable()) return null;
        String title = session.equippedTitle();
        if (title == null || !session.ownedTitles().contains(title)) return null;
        ConfigSnapshot snapshot = config.get();
        if (snapshot.titles().byId(title) == null) return null;
        return new Source(session.uuid(), session, title, snapshot.titleLevels());
    }

    public void record(Player player, Fact fact) {
        Source source = capture(player);
        if (source != null) add(source, fact, false, player);
    }

    /** Immutable deltas for an existing atomic SQL credit transaction. */
    public Map<String, Long> bonus(Source source, Fact fact) {
        long reward = source == null ? 0 : source.levels().reward(fact);
        return reward <= 0 ? Map.of() : Map.of(key(source.titleId()), reward);
    }

    public boolean isCurrent(Source source) {
        return source != null && sessions.get(source.uuid()) == source.session();
    }

    /** Mirror a durable reward only in the original live session; reconnect loads it from SQL. */
    public void committed(Source source, Fact fact) {
        if (source == null || source.levels().reward(fact) <= 0) return;
        PlayerSession current = sessions.get(source.uuid());
        if (current == null) return; // Any future login reads the committed XP.
        if (current != source.session()) {
            reconcile(source.uuid());
            return;
        }
        // Admin mutation reloads already contain this committed reward.
        if (current.frozen()) return;
        Player player = Bukkit.getPlayer(source.uuid());
        add(source, fact, true, player);
    }

    /** A delayed hunt may commit after a replacement session has loaded an earlier snapshot. */
    private void reconcile(UUID uuid) {
        PlayerSession live = sessions.get(uuid);
        if (live == null) { pendingReconcile.remove(uuid); return; }
        if (!live.loaded() || live.frozen() || !reconciling.add(uuid)) {
            pendingReconcile.add(uuid);
            return;
        }
        pendingReconcile.remove(uuid);
        sessions.mutate(uuid, "title XP reconnect reconcile", connection -> null, (ignored, error) -> {
            reconciling.remove(uuid);
            if (error != null) pendingReconcile.add(uuid);
            // Retry failures on the next session hook, rather than looping during storage failure.
            else if (pendingReconcile.remove(uuid)) reconcile(uuid);
        });
    }

    @Override public void onLoaded(Player player, PlayerSession session, boolean reload) {
        if (pendingReconcile.contains(session.uuid()) && !reconciling.contains(session.uuid())) {
            reconcile(session.uuid());
        }
    }

    @Override public void onUnload(Player player, PlayerSession session) {
        pendingReconcile.remove(session.uuid());
    }

    private void add(Source source, Fact fact, boolean committed, Player player) {
        long reward = source.levels().reward(fact);
        if (reward <= 0) return;
        PlayerSession session = source.session();
        String key = key(source.titleId());
        long before = session.stat(key);
        long safeReward = Math.min(reward, Long.MAX_VALUE - Math.max(0, before));
        if (safeReward <= 0) return;
        if (committed) session.addCommittedStat(key, safeReward);
        else session.addStat(key, safeReward);
        TitleLevels levels = config.get().titleLevels();
        if (player != null && levels.level(before) != levels.level(session.stat(key))) {
            onLevelChanged.accept(player, session);
        }
    }

    public static String key(String titleId) { return "title.xp." + titleId; }
}
