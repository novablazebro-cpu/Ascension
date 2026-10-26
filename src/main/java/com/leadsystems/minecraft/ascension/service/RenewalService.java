package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.ability.RankAbilityService;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.RenewalRecord;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Seven-day rank suspension and its persisted, idempotent renewal quest. */
public final class RenewalService implements SessionHook {
    private record PveTemplate(String objective, int required) {}
    static final long RENEWAL_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;
    static final long TARGET_ABSENCE_MS = 3L * 24 * 60 * 60 * 1000;
    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final RankAbilityService abilities;
    private final Messages messages;

    public RenewalService(SessionManager sessions, StorageExecutor storage, PlayerRepository repo, MainThread main,
                          RankAbilityService abilities, Messages messages) {
        this.sessions = sessions; this.storage = storage; this.repo = repo; this.main = main;
        this.abilities = abilities; this.messages = messages;
    }

    @Override public void onLoaded(Player player, PlayerSession session, boolean reload) {
        storage.submit("renewal.target.online", c -> {
            repo.markRenewalTargetOnline(c, player.getUniqueId());
            return null;
        });
        if (session.rankActive()) check(player, session);
        else observeHunt(player, session);
    }

    @Override public void onUnload(Player player, PlayerSession session) {
        long now = System.currentTimeMillis();
        storage.submit("renewal.target.offline", c -> {
            repo.markRenewalTargetOffline(c, player.getUniqueId(), now);
            return null;
        });
    }

    /** Runs from the scheduler and only performs a transaction for ranks that are actually due. */
    public void checkOnline() {
        for (PlayerSession s : sessions.all()) {
            Player p = Bukkit.getPlayer(s.uuid());
            if (p != null && s.usable() && s.rankActive()) check(p, s);
            else if (p != null && s.usable()) observeHunt(p, s);
        }
    }

    private void observeHunt(Player player, PlayerSession s) {
        RenewalRecord r = s.renewal();
        if (r == null || !r.active() || !"HUNT".equals(r.branch())) return;
        String operation = "renewal.observe";
        if (!s.tryBegin(operation)) return;
        UUID expected = r.target();
        Player target = expected == null ? null : Bukkit.getPlayer(expected);
        boolean online = target != null && target.isOnline();
        boolean hunt = ThreadLocalRandom.current().nextBoolean();
        PveTemplate template = hunt ? null : pveObjective(r.heldRankIndex());
        long now = System.currentTimeMillis();
        main.onComplete(storage.submit(operation, c -> repo.observeRenewalHunt(c, s.uuid(), expected, online,
                now, TARGET_ABSENCE_MS, hunt, hunt ? "HUNT" : template.objective(),
                hunt ? 1 : template.required())), (updated, error) -> {
            s.end(operation);
            if (error != null || sessions.get(s.uuid()) != s || updated == null || s.rankActive()) return;
            if (updated.assignedAt() != r.assignedAt() || !updated.branch().equals(r.branch())) {
                s.setRenewal(updated);
                messages.send(player, "renewal-auto-reroll");
                if ("HUNT".equals(updated.branch())) assignTargetIfNeeded(player, s, false);
                else messages.send(player, "renewal-progress-pve",
                        Messages.ph("objective", updated.objectiveType().toLowerCase().replace('_', ' ')),
                        Messages.ph("current", updated.progress()), Messages.ph("required", updated.required()));
            } else if (updated.target() == null) {
                assignTargetIfNeeded(player, s, false);
            }
        });
    }

    private void check(Player player, PlayerSession s) {
        if (!s.rankActive() || System.currentTimeMillis() - s.rankGrantedAt() < RENEWAL_INTERVAL_MS) return;
        boolean hunt = ThreadLocalRandom.current().nextBoolean(); // stored inside the one successful expiry transaction
        PveTemplate template = hunt ? null : pveObjective(s.rankIndex());
        String objective = hunt ? "HUNT" : template.objective();
        int required = hunt ? 1 : template.required();
        main.onComplete(storage.submit("rank.expire", c -> repo.expireRankIfDue(c, s.uuid(), System.currentTimeMillis(),
                RENEWAL_INTERVAL_MS, hunt, objective, required)), (renewal, error) -> {
            if (error != null || sessions.get(s.uuid()) != s || renewal == null) return;
            if (!s.rankActive()) return;
            s.setRankActive(false); s.setRenewal(renewal);
            abilities.clear(player, s);
            messages.send(player, "rank-expired");
            assignTargetIfNeeded(player, s);
        });
    }

    public void onFact(Player player, Fact fact) {
        PlayerSession s = sessions.get(player);
        RenewalRecord r = s == null ? null : s.renewal();
        if (s == null || s.rankActive() || r == null || !r.active() || !"PVE".equals(r.branch()) || !matches(r, fact)) return;
        main.onComplete(storage.submit("renewal.pve", c -> {
                    boolean completed = repo.progressRenewalPve(c, s.uuid(), 1, System.currentTimeMillis());
                    return new Object[] {completed, repo.loadRenewal(c, s.uuid())};
                }), (result, error) -> {
                    if (error != null || sessions.get(s.uuid()) != s || result == null) return;
                    boolean completed = (Boolean) result[0];
                    RenewalRecord updated = (RenewalRecord) result[1];
                    if (completed) restored(player, s);
                    else if (updated != null && updated.active() && r.assignedAt() == updated.assignedAt()) s.setRenewal(updated);
                });
    }

    private static boolean matches(RenewalRecord r, Fact fact) {
        return switch (r.objectiveType()) {
            case "ZOMBIE" -> fact instanceof Fact.MobKilled mob && "ZOMBIE".equals(mob.entityType());
            case "HOSTILE", "NETHER", "ILLAGER" -> fact instanceof Fact.MobKilled mob
                    && mob.categories().contains(r.objectiveType().toLowerCase());
            case "RAID" -> fact instanceof Fact.RaidCompleted;
            case "BOSS_ELDER_GUARDIAN" -> fact instanceof Fact.BossDefeated boss && "ELDER_GUARDIAN".equals(boss.boss());
            case "BOSS_WITHER" -> fact instanceof Fact.BossDefeated boss && "WITHER".equals(boss.boss());
            default -> false;
        };
    }

    private static PveTemplate pveObjective(int rank) {
        PveTemplate[] choices = switch (rank) {
            case 0 -> new PveTemplate[] {new PveTemplate("HOSTILE", 15), new PveTemplate("ZOMBIE", 12)};
            case 1, 2 -> new PveTemplate[] {new PveTemplate("HOSTILE", 20), new PveTemplate("ZOMBIE", 18)};
            case 3, 4 -> new PveTemplate[] {new PveTemplate("NETHER", 18), new PveTemplate("ILLAGER", 10)};
            case 5 -> new PveTemplate[] {new PveTemplate("RAID", 1), new PveTemplate("ILLAGER", 14)};
            case 6, 7, 8 -> new PveTemplate[] {new PveTemplate("RAID", 1), new PveTemplate("BOSS_ELDER_GUARDIAN", 1), new PveTemplate("ILLAGER", 16)};
            default -> new PveTemplate[] {new PveTemplate("BOSS_WITHER", 1), new PveTemplate("RAID", 2)};
        };
        return choices[ThreadLocalRandom.current().nextInt(choices.length)];
    }

    /** Called only after CombatProgressListener's normal anti-farm PvP validation and cooldown. */
    public void onValidPlayerKill(Player player, UUID victim) {
        PlayerSession s = sessions.get(player);
        RenewalRecord r = s == null ? null : s.renewal();
        if (s == null || s.rankActive() || r == null || !r.active() || !"HUNT".equals(r.branch()) || !victim.equals(r.target())) return;
        main.onComplete(storage.submit("renewal.hunt", c -> repo.completeRenewalHunt(c, s.uuid(), victim, System.currentTimeMillis())),
                (done, error) -> { if (error == null && Boolean.TRUE.equals(done)) restored(player, s); });
    }

    public void assignTargetIfNeeded(Player player, PlayerSession s) {
        assignTargetIfNeeded(player, s, true);
    }

    private void assignTargetIfNeeded(Player player, PlayerSession s, boolean notifyWhenWaiting) {
        RenewalRecord r = s.renewal();
        if (r == null || !r.active() || !"HUNT".equals(r.branch()) || r.target() != null) return;
        List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream().filter(p -> !p.getUniqueId().equals(s.uuid()))
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                .filter(p -> !sameTeam(player, p))
                .toList();
        Player target = candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        if (target == null) {
            if (notifyWhenWaiting) messages.send(player, "renewal-hunt-waiting");
            return;
        }
        main.onComplete(storage.submit("renewal.target", c -> repo.assignRenewalTarget(c, s.uuid(), target.getUniqueId())),
                (assigned, error) -> {
                    if (error == null && Boolean.TRUE.equals(assigned) && sessions.get(s.uuid()) == s) {
                        s.setRenewal(new RenewalRecord(s.uuid(), r.heldRankIndex(), r.branch(), r.objectiveType(), r.required(), r.progress(), target.getUniqueId(), r.state(), r.assignedAt()));
                        messages.send(player, "renewal-hunt-target", Messages.ph("target", target.getName()));
                    }
                });
    }

    /** Player-facing renewal target status and offline-target reroll. */
    public boolean showOrRerollTarget(Player player, boolean reroll) {
        PlayerSession s = sessions.get(player);
        RenewalRecord r = s == null ? null : s.renewal();
        if (s == null || s.rankUsable(System.currentTimeMillis())) return false;
        if (r == null) { messages.send(player, "rank-expired"); return true; }
        if (!r.active() || !"HUNT".equals(r.branch())) {
            messages.send(player, "renewal-progress-pve",
                    Messages.ph("objective", r.objectiveType().toLowerCase().replace('_', ' ')),
                    Messages.ph("current", r.progress()), Messages.ph("required", r.required()));
            return true;
        }
        if (r.target() == null) { assignTargetIfNeeded(player, s); return true; }
        Player target = Bukkit.getPlayer(r.target());
        if (!reroll || target != null) {
            messages.send(player, "renewal-hunt-target", Messages.ph("target", target == null ? r.target() : target.getName()));
            return true;
        }
        main.onComplete(storage.submit("renewal.target.reroll", c -> {
                    boolean cleared = repo.clearRenewalTarget(c, s.uuid(), r.target());
                    return cleared ? repo.loadRenewal(c, s.uuid()) : null;
                }), (updated, error) -> {
                    if (error == null && updated != null && sessions.get(s.uuid()) == s) {
                        s.setRenewal(updated);
                        assignTargetIfNeeded(player, s);
                    }
                });
        return true;
    }

    private static boolean sameTeam(Player a, Player b) {
        Team first = a.getScoreboard().getEntryTeam(a.getName());
        return first != null && first.equals(a.getScoreboard().getEntryTeam(b.getName()));
    }

    private void restored(Player player, PlayerSession s) {
        if (sessions.get(s.uuid()) != s) return;
        // Reload the normal quest as well as the restored rank. This prevents an expired session
        // (which intentionally loaded no normal quest) from losing its previous quest progress.
        sessions.mutate(s.uuid(), "rank.renewed.reload", c -> null, (ignored, error) -> {
            if (error == null && player.isOnline()) messages.send(player, "rank-renewed");
        });
    }
}
