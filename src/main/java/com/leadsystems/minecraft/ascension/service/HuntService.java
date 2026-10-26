package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.domain.quest.ActiveQuest;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveType;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import com.leadsystems.minecraft.ascension.progress.ProgressTracker;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.HuntRecord;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Persistent assignment, reroll and completion for assigned-player hunts. */
public final class HuntService {

    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final ProgressTracker progress;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private RenewalService renewals;

    public HuntService(SessionManager sessions, StorageExecutor storage, PlayerRepository repo, MainThread main,
                       ProgressTracker progress, Supplier<ConfigSnapshot> config, Messages messages) {
        this.sessions = sessions;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
        this.progress = progress;
        this.config = config;
        this.messages = messages;
    }

    public void setRenewals(RenewalService renewals) { this.renewals = renewals; }

    public void showOrAssign(Player hunter, boolean reroll) {
        if (renewals != null && renewals.showOrRerollTarget(hunter, reroll)) return;
        PlayerSession session = sessions.get(hunter);
        if (!needsHunt(session)) {
            messages.send(hunter, "hunt-not-needed");
            return;
        }
        List<UUID> candidates = candidates(hunter, session.rankIndex());
        long now = System.currentTimeMillis();
        long avoidSince = now - TimeUnit.DAYS.toMillis(config.get().settings().huntRepeatAvoidanceDays());
        long offlineBefore = now - TimeUnit.HOURS.toMillis(config.get().settings().huntRerollOfflineHours());
        var onlineTargets = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var future = storage.submit(reroll ? "hunt.reroll" : "hunt.assign", c -> reroll
                ? repo.rerollHunt(c, session.uuid(), candidates, onlineTargets, now, offlineBefore, avoidSince)
                : repo.assignHunt(c, session.uuid(), candidates, now, avoidSince));
        main.onComplete(future, (hunt, error) -> {
            if (error != null) {
                messages.send(hunter, "storage-degraded");
                return;
            }
            if (hunt == null) {
                messages.send(hunter, "hunt-no-target");
                return;
            }
            Player target = Bukkit.getPlayer(hunt.target());
            String name = target == null ? hunt.target().toString() : target.getName();
            messages.send(hunter, reroll ? "hunt-reroll-result" : "hunt-target",
                    Messages.ph("target", name));
        });
    }

    /** Called only after the common PvP validator and persistent victim cooldown accept the kill. */
    public void onValidKill(Player hunter, UUID victim) {
        onValidKill(hunter.getUniqueId(), victim, progress.captureTitleSource(hunter), sessions.get(hunter));
    }

    /** Source is captured before asynchronous PvP validation, not when this callback is reached. */
    public void onValidKill(UUID hunterId, UUID victim, TitleXpService.Source source,
                            PlayerSession originalSession) {
        Fact.HuntCompleted fact = new Fact.HuntCompleted();
        var bonus = progress.titleXpBonus(source, fact);
        long completedAt = System.currentTimeMillis();
        main.onComplete(storage.submit("hunt.complete",
                c -> repo.completeHunt(c, hunterId, victim, completedAt, bonus)), (completed, error) -> {
            if (error == null && Boolean.TRUE.equals(completed)) {
                progress.applyCommittedTitleXp(source, fact);
                Player hunter = Bukkit.getPlayer(hunterId);
                if (hunter != null && originalSession != null && sessions.get(hunterId) == originalSession) {
                    progress.recordAfterTitleXpCommit(hunter, fact);
                    messages.send(hunter, "hunt-complete");
                }
            }
        });
    }

    private List<UUID> candidates(Player hunter, int hunterRank) {
        List<Player> players = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (!candidate.equals(hunter) && valid(candidate) && !sameTeam(hunter, candidate)) {
                players.add(candidate);
            }
        }
        players.sort(Comparator.comparingInt(p -> {
            PlayerSession s = sessions.get(p);
            return s == null || !s.loaded() ? Integer.MAX_VALUE : Math.abs(s.rankIndex() - hunterRank);
        }));
        return players.stream().map(Player::getUniqueId).toList();
    }

    private static boolean needsHunt(PlayerSession session) {
        ActiveQuest quest = session == null ? null : session.quest();
        if (session == null || !session.usable() || quest == null || quest.state() != QuestState.ACTIVE) {
            return false;
        }
        return quest.definition().objectives().stream().anyMatch(o -> o.type() == ObjectiveType.HUNTS
                && !quest.status(o).complete());
    }

    private static boolean valid(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private static boolean sameTeam(Player a, Player b) {
        return sameTeamOn(a, b, a) || sameTeamOn(a, b, b);
    }

    private static boolean sameTeamOn(Player first, Player second, Player scoreboardOwner) {
        Team firstTeam = scoreboardOwner.getScoreboard().getEntryTeam(first.getName());
        return firstTeam != null
                && firstTeam.equals(scoreboardOwner.getScoreboard().getEntryTeam(second.getName()));
    }
}
