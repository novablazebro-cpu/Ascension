package com.leadsystems.minecraft.ascension.progress;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.domain.progress.StatKeys;
import com.leadsystems.minecraft.ascension.domain.quest.ActiveQuest;
import com.leadsystems.minecraft.ascension.service.QuestService;
import com.leadsystems.minecraft.ascension.service.RenewalService;
import com.leadsystems.minecraft.ascension.service.TitleXpService;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The single entry point for gameplay progress. Listeners produce {@link Fact}s only after their
 * own validation; this class applies the global rules (session loaded, not frozen by an admin
 * operation, no creative/spectator progress) and feeds lifetime statistics and the active quest.
 * Purely in-memory: no database access on this path.
 */
public final class ProgressTracker {

    private final SessionManager sessions;
    private final QuestService quests;
    private final Supplier<ConfigSnapshot> config;
    private RenewalService renewals;
    private TitleXpService titleXp;

    public ProgressTracker(SessionManager sessions, QuestService quests, Supplier<ConfigSnapshot> config) {
        this.sessions = sessions;
        this.quests = quests;
        this.config = config;
    }
    public void setRenewals(RenewalService renewals) { this.renewals = renewals; }
    public void setTitleXp(TitleXpService titleXp) { this.titleXp = titleXp; }
    public PlayerSession captureSession(Player player) { return sessions.get(player); }
    public boolean isCurrentSession(Player player, PlayerSession captured) {
        return captured != null && sessions.get(player) == captured;
    }

    public TitleXpService.Source captureTitleSource(Player player) {
        return titleXp == null ? null : titleXp.capture(player);
    }

    public Map<String, Long> titleXpBonus(TitleXpService.Source source, Fact fact) {
        return titleXp == null ? Map.of() : titleXp.bonus(source, fact);
    }

    public void applyCommittedTitleXp(TitleXpService.Source source, Fact fact) {
        if (titleXp != null) titleXp.committed(source, fact);
    }

    public boolean isCurrentTitleSource(TitleXpService.Source source) {
        return source == null || titleXp == null || titleXp.isCurrent(source);
    }

    public void record(Player player, Fact fact) {
        record(player, fact, true);
    }

    /** Other progress still needs recording, but the title XP was committed by its validator. */
    public void recordAfterTitleXpCommit(Player player, Fact fact) {
        record(player, fact, false);
    }

    private void record(Player player, Fact fact, boolean awardTitleXp) {
        if (!canRecord(player)) return;
        PlayerSession s = sessions.get(player);
        if (awardTitleXp && titleXp != null) titleXp.record(player, fact);
        for (Map.Entry<String, Long> e : StatKeys.of(fact).entrySet()) {
            s.addStat(e.getKey(), e.getValue());
        }
        if (!s.rankUsable(System.currentTimeMillis())) {
            if (renewals != null) renewals.onFact(player, fact);
            return;
        }
        ActiveQuest quest = s.quest();
        if (quest != null && quest.apply(fact)) {
            quests.onProgress(player, s, fact);
        }
    }

    /**
     * Records a fact whose statistics have ALREADY been atomically committed to durable storage
     * (e.g. unique End City/conduit location transactions). Updates in-memory session statistics
     * without queueing an additive delta flush, and advances active quest objectives.
     */
    public void recordCommitted(Player player, Fact fact) {
        PlayerSession s = sessions.get(player);
        if (s == null || !s.usable()) {
            return;
        }
        for (Map.Entry<String, Long> e : StatKeys.of(fact).entrySet()) {
            s.addCommittedStat(e.getKey(), e.getValue());
        }
        if (!s.rankUsable(System.currentTimeMillis())) {
            if (renewals != null) renewals.onFact(player, fact);
            return;
        }
        ActiveQuest quest = s.quest();
        if (quest != null && quest.apply(fact)) {
            quests.onProgress(player, s, fact);
        }
    }

    boolean canRecord(Player player) {
        PlayerSession s = sessions.get(player);
        if (s == null || !s.usable()) return false;
        if (!config.get().settings().ignoreCreative()) return true;
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }
}
