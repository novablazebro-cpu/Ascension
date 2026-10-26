package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.audit.AuditLog;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRecord;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import org.bukkit.scoreboard.Team;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Administrative rank mutations. Each goes through {@link SessionManager#mutate}, so an online
 * target's unsaved progress is saved first, the change is one transaction, and the session is
 * reloaded from the database afterwards. Every mutation is written to the audit log.
 */
public final class AdminService {

    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final AuditLog audit;
    private final TitleService titles;

    public AdminService(SessionManager sessions, StorageExecutor storage, PlayerRepository repo, MainThread main,
                        Supplier<ConfigSnapshot> config, Messages messages, AuditLog audit, TitleService titles) {
        this.sessions = sessions;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
        this.config = config;
        this.messages = messages;
        this.audit = audit;
        this.titles = titles;
    }

    /** Resolves a player name (online first, then the database) and runs {@code then} on the main thread. */
    public void resolve(CommandSender sender, String name, BiConsumer<UUID, String> then) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(online.getUniqueId(), online.getName());
            return;
        }
        main.onComplete(storage.submit("resolve", c -> repo.findUuidByName(c, name)), (uuid, error) -> {
            if (error != null || uuid == null) {
                messages.send(sender, "player-not-found", Messages.ph("name", name));
                return;
            }
            then.accept(uuid, name);
        });
    }

    /** /rank set: jump to a stage (clears that stage's and later quests). */
    public void setRank(CommandSender sender, String targetName, int rankIndex) {
        resolve(sender, targetName, (uuid, name) -> sessions.mutate(uuid, "rank.set", c -> {
            repo.setRank(c, uuid, rankIndex, false);
            return null;
        }, report(sender, "rank.set", name, config.get().ladder().get(rankIndex).name())));
    }

    /** /rank advance: move up one stage, or grant mastery on the last stage. */
    public void advance(CommandSender sender, String targetName) {
        int size = config.get().ladder().size();
        resolve(sender, targetName, (uuid, name) -> sessions.mutate(uuid, "rank.advance", c -> {
            PlayerRecord p = repo.find(c, uuid);
            if (p == null) {
                throw new IllegalStateException("no data for " + name);
            }
            if (p.mastery()) {
                throw new IllegalStateException(name + " already has mastery");
            }
            int next = p.rankIndex() + 1;
            if (next >= size) {
                repo.setRank(c, uuid, p.rankIndex(), true);
                return "mastery";
            }
            repo.setRank(c, uuid, next, false);
            return "stage " + (next + 1);
        }, report(sender, "rank.advance", name, "")));
    }

    /** /rank reset: back to the first stage, all quests cleared. */
    public void reset(CommandSender sender, String targetName) {
        resolve(sender, targetName, (uuid, name) -> sessions.mutate(uuid, "rank.reset", c -> {
            repo.setRank(c, uuid, 0, false);
            return null;
        }, report(sender, "rank.reset", name, "")));
    }

    public void giveTitle(CommandSender sender, String targetName, String titleId) {
        resolve(sender, targetName, (uuid, name) -> sessions.mutate(uuid, "title.give",
                c -> repo.adminGiveTitle(c, titleId, uuid, System.currentTimeMillis()),
                titleReport(sender, "title.give", name, titleId)));
    }

    public void revokeTitle(CommandSender sender, String titleId) {
        UUID owner = titles.owner(titleId);
        if (owner == null) {
            messages.send(sender, "admin-failed", Messages.ph("reason", "title is not owned"));
            return;
        }
        sessions.mutate(owner, "title.revoke", c -> {
            repo.adminRevokeTitle(c, titleId);
            return null;
        }, titleReport(sender, "title.revoke", owner.toString(), titleId));
    }

    public void transferTitle(CommandSender sender, String titleId, String targetName) {
        UUID previous = titles.owner(titleId);
        resolve(sender, targetName, (uuid, name) -> sessions.mutate(uuid, "title.transfer", c -> {
            repo.adminTransferTitle(c, titleId, uuid, System.currentTimeMillis());
            return null;
        }, (result, error) -> {
            if (error == null && previous != null && !previous.equals(uuid)) {
                sessions.mutate(previous, "title.transfer.refresh", c -> null,
                        (ignored, refreshError) -> titleReport(sender, "title.transfer", name, titleId)
                                .accept(result, refreshError));
            } else {
                titleReport(sender, "title.transfer", name, titleId).accept(result, error);
            }
        }));
    }

    public void resetTitles(CommandSender sender, String targetName) {
        resolve(sender, targetName, (uuid, name) -> sessions.mutate(uuid, "title.reset", c -> {
            repo.adminResetTitles(c, uuid);
            return null;
        }, titleReport(sender, "title.reset", name, "")));
    }

    public void resetTitleCooldowns(CommandSender sender, String targetName, String titleId) {
        resolve(sender, targetName, (uuid, name) -> sessions.mutate(uuid, "title.cooldown.reset", c -> {
            repo.adminResetTitleCooldowns(c, uuid, titleId);
            return null;
        }, titleReport(sender, "title.cooldown.reset", name, titleId == null ? "all" : titleId)));
    }

    public void setHunt(CommandSender sender, String hunterName, String targetName) {
        resolve(sender, hunterName, (hunter, resolvedHunter) -> resolve(sender, targetName, (target, resolvedTarget) ->
                sessions.mutate(hunter, "hunt.set", c ->
                                repo.adminSetHunt(c, hunter, target, System.currentTimeMillis()),
                        report(sender, "hunt.set", resolvedHunter, resolvedTarget))));
    }

    public void cancelHunt(CommandSender sender, String hunterName) {
        resolve(sender, hunterName, (hunter, resolved) -> sessions.mutate(hunter, "hunt.cancel",
                c -> repo.adminCancelHunt(c, hunter), report(sender, "hunt.cancel", resolved, "")));
    }

    public void rerollHunt(CommandSender sender, String hunterName) {
        resolve(sender, hunterName, (hunter, resolved) -> {
            int rank = sessions.get(hunter) == null ? 0 : sessions.get(hunter).rankIndex();
            Player onlineHunter = Bukkit.getPlayer(hunter);
            List<UUID> candidates = Bukkit.getOnlinePlayers().stream()
                    .filter(player -> !player.getUniqueId().equals(hunter))
                    .filter(player -> player.getGameMode() == GameMode.SURVIVAL
                            || player.getGameMode() == GameMode.ADVENTURE)
                    .filter(player -> onlineHunter == null || !sameTeam(onlineHunter, player))
                    .sorted(Comparator.comparingInt(player -> {
                        var session = sessions.get(player);
                        return session == null ? Integer.MAX_VALUE : Math.abs(session.rankIndex() - rank);
                    }))
                    .map(Player::getUniqueId).toList();
            sessions.mutate(hunter, "hunt.reroll.admin",
                    c -> repo.adminRerollHunt(c, hunter, candidates, System.currentTimeMillis()),
                    report(sender, "hunt.reroll", resolved, ""));
        });
    }

    private static boolean sameTeam(Player first, Player second) {
        return sameTeamOn(first, second, first) || sameTeamOn(first, second, second);
    }

    private static boolean sameTeamOn(Player first, Player second, Player scoreboardOwner) {
        Team firstTeam = scoreboardOwner.getScoreboard().getEntryTeam(first.getName());
        return firstTeam != null
                && firstTeam.equals(scoreboardOwner.getScoreboard().getEntryTeam(second.getName()));
    }

    private <T> BiConsumer<T, Throwable> titleReport(CommandSender sender, String action,
                                                      String target, String details) {
        BiConsumer<T, Throwable> report = report(sender, action, target, details);
        return (result, error) -> {
            titles.refreshOwners();
            if (error == null && result instanceof Boolean changed && !changed) {
                messages.send(sender, "admin-failed", Messages.ph("reason", "title is already owned"));
                return;
            }
            report.accept(result, error);
        };
    }

    private <T> BiConsumer<T, Throwable> report(CommandSender sender, String action, String target, String details) {
        return (result, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                messages.send(sender, "admin-failed", Messages.ph("reason", String.valueOf(cause.getMessage())));
                return;
            }
            String extra = result == null ? details : (details.isEmpty() ? result.toString() : details + " " + result);
            audit.record(sender.getName(), action, target, extra);
            messages.send(sender, "admin-done", Messages.ph("action", action + " " + target + (extra.isEmpty() ? "" : " (" + extra + ")")));
        };
    }

    public AuditLog audit() {
        return audit;
    }
}
