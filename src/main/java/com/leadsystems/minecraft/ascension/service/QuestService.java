package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.ability.RankAbilityService;
import com.leadsystems.minecraft.ascension.api.event.QuestAcceptedEvent;
import com.leadsystems.minecraft.ascension.api.event.RankAdvancedEvent;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.domain.quest.ActiveQuest;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveDefinition;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveMatcher;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveStatus;
import com.leadsystems.minecraft.ascension.domain.quest.QuestDelta;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.CompletionResult;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.text.Contrast;
import com.leadsystems.minecraft.ascension.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Quest acceptance and completion.
 * <p>
 * Completion is guarded twice: {@link ActiveQuest#beginCompletion()} lets only one completion
 * start per quest in memory, and the reward transaction's conditional updates let only one commit
 * in the database. The player is told and the rank is applied only after the commit; a crash in
 * between is covered by {@code pending_notice}, shown on the next join.
 */
public final class QuestService implements SessionHook {

    private static final String OP_ACCEPT = "quest.accept";
    private static final String OP_COMPLETE = "quest.complete";

    private final Logger log;
    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final StorageHealth health;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final RankAbilityService abilities;

    public QuestService(Logger log, SessionManager sessions, StorageExecutor storage, PlayerRepository repo,
                        StorageHealth health, MainThread main, Supplier<ConfigSnapshot> config, Messages messages,
                        RankAbilityService abilities) {
        this.log = log;
        this.sessions = sessions;
        this.storage = storage;
        this.repo = repo;
        this.health = health;
        this.main = main;
        this.config = config;
        this.messages = messages;
        this.abilities = abilities;
    }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        String notice = session.pendingNotice();
        if (notice != null && !reload) {
            showNotice(player, session, notice);
            clearNotice(session.uuid());
            session.setPendingNotice(null);
        }
        tryComplete(player, session); // e.g. an admin lowered a requirement while the player was away
    }

    // ------------------------------------------------------------------ accept

    /**
     * Accepts the player's current quest. Validated server-side regardless of what the menu showed.
     *
     * @param after called on the main thread after a successful accept (e.g. reopen the menu)
     */
    public void accept(Player player, Runnable after) {
        PlayerSession s = sessions.get(player);
        if (s == null || !s.usable() || !s.rankUsable(System.currentTimeMillis())) {
            messages.send(player, "data-unavailable");
            return;
        }
        ActiveQuest quest = s.quest();
        if (quest == null) {
            messages.send(player, "quest-none");
            return;
        }
        if (quest.state() != QuestState.NOT_ACCEPTED) {
            messages.send(player, "quest-already-accepted");
            return;
        }
        if (!health.isHealthy()) {
            messages.send(player, "storage-degraded");
            return;
        }
        if (!s.tryBegin(OP_ACCEPT)) {
            messages.send(player, "quest-busy");
            return;
        }
        UUID uuid = s.uuid();
        int rankIndex = s.rankIndex();
        long now = System.currentTimeMillis();
        main.onComplete(storage.submit("quest.accept", c -> repo.acceptQuest(c, uuid, rankIndex, now)), (ok, error) -> {
            s.end(OP_ACCEPT);
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || sessions.get(uuid) != s) {
                return; // saved (or not); the next join loads whatever the database holds
            }
            if (error != null) {
                messages.send(online, "storage-degraded");
                return;
            }
            if (ok && s.rankIndex() == rankIndex && s.quest() == quest && quest.accept(now)) {
                RankStage stage = config.get().ladder().get(rankIndex);
                messages.send(online, "quest-accepted", Messages.ph("stage", Messages.rank(stage)));
                online.playSound(online.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
                Bukkit.getPluginManager().callEvent(new QuestAcceptedEvent(online, rankIndex, stage.id()));
                after.run();
            } else {
                messages.send(online, "quest-accept-failed");
                resync(uuid);
            }
        });
    }

    // ------------------------------------------------------------------ progress & completion

    /** Called by the progress tracker after a fact changed quest progress. */
    public void onProgress(Player player, PlayerSession session, Fact fact) {
        ActiveQuest quest = session.quest();
        if (quest == null) {
            return;
        }
        if (config.get().settings().progressActionBar()) {
            for (ObjectiveDefinition o : quest.definition().objectives()) {
                if (ObjectiveMatcher.match(o, fact) != null) {
                    ObjectiveStatus st = quest.status(o);
                    player.sendActionBar(Component.text(o.description() + "  ", NamedTextColor.WHITE)
                            .append(Component.text(st.current() + "/" + o.amount(),
                                    st.complete() ? NamedTextColor.WHITE : NamedTextColor.WHITE)));
                    break;
                }
            }
        }
        if (quest.allComplete()) {
            tryComplete(player, session);
        }
    }

    /** Starts the reward transaction if every objective is met and none is running. */
    public void tryComplete(Player player, PlayerSession s) {
        ActiveQuest quest = s.quest();
        if (quest == null || !s.usable() || !s.rankUsable(System.currentTimeMillis()) || !health.isHealthy() || s.inFlight(OP_COMPLETE)) {
            return;
        }
        if (!quest.beginCompletion()) {
            return;
        }
        s.tryBegin(OP_COMPLETE);
        UUID uuid = s.uuid();
        int rankIndex = s.rankIndex();
        boolean last = config.get().ladder().isLast(rankIndex);
        QuestDelta fin = quest.drainDirty();
        long now = System.currentTimeMillis();
        main.onComplete(storage.submit("quest.complete",
                c -> repo.completeQuest(c, uuid, rankIndex, fin, last, now)), (result, error) -> {
            s.end(OP_COMPLETE);
            if (sessions.get(uuid) != s) {
                return; // player left; pending_notice announces it on the next join
            }
            Player online = Bukkit.getPlayer(uuid);
            if (error != null) {
                quest.restoreDirty(fin);
                quest.revertCompletion(); // retried by retryCompletions()
                log.warning("Quest completion for " + s.name() + " failed, will retry: " + error);
                return;
            }
            switch (result) {
                case ADVANCED -> {
                    s.setRankIndex(rankIndex + 1);
                    s.setQuest(ActiveQuest.fresh(config.get().questFor(rankIndex + 1), QuestState.NOT_ACCEPTED, 0L));
                    afterReward(online, s, rankIndex, rankIndex + 1, false);
                }
                case MASTERED -> {
                    s.setMastery(true);
                    s.setQuest(null);
                    afterReward(online, s, rankIndex, rankIndex, true);
                }
                default -> {
                    log.warning("Quest completion for " + s.name() + " returned " + result + "; resyncing");
                    resync(uuid);
                }
            }
        });
    }

    private void afterReward(Player online, PlayerSession s, int from, int to, boolean mastery) {
        clearNotice(s.uuid());
        s.setPendingNotice(null);
        if (online == null) {
            return;
        }
        announce(online, to, mastery);
        abilities.apply(online, s);
        Bukkit.getPluginManager().callEvent(new RankAdvancedEvent(online, from, to, mastery));
    }

    private void announce(Player player, int toIndex, boolean mastery) {
        ConfigSnapshot cfg = config.get();
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0),
                60, 0.6, 1.0, 0.6, 0.25);
        if (mastery) {
            messages.send(player, "mastery-self", Messages.ph("mastery", cfg.masteryName()));
            messages.title(player, "mastery-title", "mastery-subtitle", Messages.ph("mastery", cfg.masteryName()));
            if (cfg.announceRankUps()) {
                Bukkit.broadcast(Contrast.on(Contrast.Surface.CHAT, messages.prefix().append(messages.get("mastery-broadcast",
                        Messages.ph("player", player.getName()), Messages.ph("mastery", cfg.masteryName())))));
            }
            return;
        }
        Component rank = Messages.rank(cfg.ladder().get(toIndex));
        messages.send(player, "rank-up-self", Messages.ph("rank", rank));
        messages.title(player, "rank-up-title", "rank-up-subtitle", Messages.ph("rank", rank));
        if (cfg.announceRankUps()) {
            Bukkit.broadcast(Contrast.on(Contrast.Surface.CHAT, messages.prefix().append(messages.get("rank-up-broadcast",
                    Messages.ph("player", player.getName()), Messages.ph("rank", rank)))));
        }
    }

    private void showNotice(Player player, PlayerSession session, String notice) {
        ConfigSnapshot cfg = config.get();
        if (notice.equals("mastery")) {
            messages.send(player, "mastery-self", Messages.ph("mastery", cfg.masteryName()));
        } else if (notice.startsWith("rank_up:")) {
            try {
                int idx = Integer.parseInt(notice.substring("rank_up:".length()));
                if (idx >= 0 && idx < cfg.ladder().size()) {
                    messages.send(player, "rank-up-self", Messages.ph("rank", Messages.rank(cfg.ladder().get(idx))));
                }
            } catch (NumberFormatException ignored) {
                // malformed notice: nothing to show
            }
        }
    }

    private void clearNotice(UUID uuid) {
        storage.submit("notice.clear", c -> {
            repo.clearPendingNotice(c, uuid);
            return null;
        });
    }

    /** Retries completions that failed while storage was down (scheduler, every few seconds). */
    public void retryCompletions() {
        if (!health.isHealthy()) {
            return;
        }
        for (PlayerSession s : sessions.all()) {
            ActiveQuest q = s.quest();
            if (q != null && q.state() == QuestState.ACTIVE && s.usable() && !s.inFlight(OP_COMPLETE) && q.allComplete()) {
                Player p = Bukkit.getPlayer(s.uuid());
                if (p != null) {
                    tryComplete(p, s);
                }
            }
        }
    }

    /** Admin: marks one objective of an online player's active quest complete. */
    public boolean forceObjective(Player target, String objectiveId) {
        PlayerSession s = sessions.get(target);
        if (s == null || !s.usable() || s.quest() == null || s.quest().state() != QuestState.ACTIVE) {
            return false;
        }
        if (!s.quest().forceComplete(objectiveId)) {
            return false;
        }
        if (s.quest().allComplete()) {
            tryComplete(target, s);
        }
        return true;
    }

    private void resync(UUID uuid) {
        sessions.mutate(uuid, "resync", c -> null, (v, e) -> {
            if (e != null) {
                log.warning("Resync failed for " + uuid + ": " + e);
            }
        });
    }
}
