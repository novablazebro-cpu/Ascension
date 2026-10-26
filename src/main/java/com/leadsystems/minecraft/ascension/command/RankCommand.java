package com.leadsystems.minecraft.ascension.command;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.quest.ActiveQuest;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveDefinition;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveStatus;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import com.leadsystems.minecraft.ascension.gui.MenuService;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.service.AdminService;
import com.leadsystems.minecraft.ascension.service.QuestService;
import com.leadsystems.minecraft.ascension.service.HuntService;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRecord;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.QuestRecord;
import com.leadsystems.minecraft.ascension.storage.RenewalRecord;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /rank} - player and admin forms. Player forms: {@code /rank}, {@code progress},
 * {@code quests}, {@code target [reroll]}, and beta {@code test <tier> <stage>}. Admin forms:
 * {@code set}, {@code advance}, {@code reset},
 * {@code progress <player>}, {@code quest complete <player> <objective>}. Each branch checks its own
 * permission node.
 */
public final class RankCommand implements TabExecutor {

    private final SessionManager sessions;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final MenuService menus;
    private final QuestService quests;
    private final HuntService hunts;
    private final AdminService admin;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;

    public RankCommand(SessionManager sessions, Supplier<ConfigSnapshot> config, Messages messages, MenuService menus,
                       QuestService quests, HuntService hunts, AdminService admin, StorageExecutor storage, PlayerRepository repo,
                       MainThread main) {
        this.sessions = sessions;
        this.config = config;
        this.messages = messages;
        this.menus = menus;
        this.quests = quests;
        this.hunts = hunts;
        this.admin = admin;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                messages.send(sender, "usage", Messages.ph("usage", "/rank progress <player> | set | advance | reset"));
                return true;
            }
            if (perm(sender, "ascension.rank")) {
                menus.open(p, MenuService.MAIN);
            }
            return true;
        }
        String sub = args[0];
        if (Args.is(sub, "progress")) {
            if (args.length >= 2) {
                if (perm(sender, "ascension.admin.rank.progress")) {
                    progressOf(sender, args[1]);
                }
            } else if (sender instanceof Player p && perm(sender, "ascension.rank")) {
                PlayerSession s = sessions.get(p);
                if (s == null || !s.usable()) {
                    messages.send(p, "data-unavailable");
                } else if (!s.rankUsable(System.currentTimeMillis())) {
                    printRenewal(sender, s);
                } else {
                    printQuest(sender, s.rankIndex(), s.mastery(), s.quest());
                }
            }
        } else if (Args.is(sub, "quests")) {
            if (sender instanceof Player p && perm(sender, "ascension.rank")) {
                PlayerSession s = sessions.get(p);
                if (s == null || !s.usable()) {
                    messages.send(p, "data-unavailable");
                } else {
                    printLadder(sender, s);
                }
            }
        } else if (Args.is(sub, "test") && sender instanceof Player player
                && config.get().testingUnlockAll()) {
            if (perm(sender, "ascension.admin.rank.set")) testRank(player, args);
        } else if (Args.is(sub, "target")) {
            if (args.length >= 2 && Args.is(args[1], "set")) {
                if (perm(sender, "ascension.admin.rank.target") && args.length == 4) {
                    admin.setHunt(sender, args[2], args[3]);
                }
            } else if (args.length >= 2 && Args.is(args[1], "cancel")) {
                if (perm(sender, "ascension.admin.rank.target") && args.length == 3) {
                    admin.cancelHunt(sender, args[2]);
                }
            } else if (args.length == 3 && Args.is(args[1], "reroll")) {
                if (perm(sender, "ascension.admin.rank.target")) {
                    admin.rerollHunt(sender, args[2]);
                }
            } else if (perm(sender, "ascension.rank.target")) {
                if (sender instanceof Player player) {
                    hunts.showOrAssign(player, args.length >= 2 && Args.is(args[1], "reroll"));
                } else {
                    messages.send(sender, "usage", Messages.ph("usage", "/rank target [reroll]"));
                }
            }
        } else if (Args.is(sub, "set")) {
            if (!perm(sender, "ascension.admin.rank.set")) {
                return true;
            }
            if (args.length < 4) {
                messages.send(sender, "usage", Messages.ph("usage", "/rank set <player> <rank> <stage>"));
                return true;
            }
            int stageNo;
            try {
                stageNo = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                stageNo = -1;
            }
            RankStage stage = config.get().ladder().find(args[2], stageNo);
            if (stage == null) {
                messages.send(sender, "admin-failed", Messages.ph("reason", "unknown rank/stage " + args[2] + " " + args[3]));
                return true;
            }
            admin.setRank(sender, args[1], stage.index());
        } else if (Args.is(sub, "advance")) {
            if (perm(sender, "ascension.admin.rank.advance")) {
                if (args.length < 2) {
                    messages.send(sender, "usage", Messages.ph("usage", "/rank advance <player>"));
                } else {
                    admin.advance(sender, args[1]);
                }
            }
        } else if (Args.is(sub, "reset")) {
            if (perm(sender, "ascension.admin.rank.reset")) {
                if (args.length < 2) {
                    messages.send(sender, "usage", Messages.ph("usage", "/rank reset <player>"));
                } else {
                    admin.reset(sender, args[1]);
                }
            }
        } else if (Args.is(sub, "quest")) {
            if (perm(sender, "ascension.admin.rank.quest")) {
                questComplete(sender, args);
            }
        } else {
            messages.send(sender, "usage", Messages.ph("usage", "/rank [progress|quests|target]"));
        }
        return true;
    }

    private void testRank(Player player, String[] args) {
        if (args.length != 3) {
            messages.send(player, "usage", Messages.ph("usage", "/rank test <tier> <stage>"));
            return;
        }
        int stageNumber;
        try {
            stageNumber = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            stageNumber = -1;
        }
        RankStage stage = config.get().ladder().find(args[1], stageNumber);
        PlayerSession session = sessions.get(player);
        if (stage == null || session == null || !session.usable()) {
            messages.send(player, stage == null ? "testing-rank-invalid" : "data-unavailable");
            return;
        }
        sessions.mutate(player.getUniqueId(), "rank.test", c ->
                repo.setRankForTesting(c, player.getUniqueId(), stage.index()), (changed, error) -> {
            if (error != null || !Boolean.TRUE.equals(changed)) {
                messages.send(player, "storage-degraded");
                return;
            }
            messages.send(player, "testing-rank-set", Messages.ph("stage", Messages.rank(stage)));
            menus.open(player, MenuService.MAIN);
        });
    }

    private void questComplete(CommandSender sender, String[] args) {
        if (args.length < 4 || !Args.is(args[1], "complete")) {
            messages.send(sender, "usage", Messages.ph("usage", "/rank quest complete <player> <objective>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            messages.send(sender, "admin-failed", Messages.ph("reason", "the player must be online"));
            return;
        }
        if (quests.forceObjective(target, args[3])) {
            admin.audit().record(sender.getName(), "rank.quest.complete", target.getName(), args[3]);
            messages.send(sender, "admin-done", Messages.ph("action", "quest complete " + target.getName() + " " + args[3]));
        } else {
            messages.send(sender, "admin-failed", Messages.ph("reason", "no active quest or unknown objective '" + args[3] + "'"));
        }
    }

    private void progressOf(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            PlayerSession s = sessions.get(online);
            if (s != null && s.usable()) {
                sender.sendMessage(Component.text(online.getName() + ":", NamedTextColor.WHITE));
                if (s.rankUsable(System.currentTimeMillis())) printQuest(sender, s.rankIndex(), s.mastery(), s.quest());
                else printRenewal(sender, s);
                return;
            }
        }
        admin.resolve(sender, name, (uuid, resolved) ->
                main.onComplete(storage.submit("progress", c -> {
                    PlayerRecord p = repo.find(c, uuid);
                    QuestRecord q = p == null || p.mastery() || !p.rankActive() ? null : repo.loadQuest(c, uuid, p.rankIndex());
                    RenewalRecord renewal = p == null ? null : repo.loadRenewal(c, uuid);
                    return new Object[] {p, q, renewal};
                }), (pair, error) -> {
                    if (error != null || pair[0] == null) {
                        messages.send(sender, "player-not-found", Messages.ph("name", name));
                        return;
                    }
                    PlayerRecord p = (PlayerRecord) pair[0];
                    QuestRecord q = (QuestRecord) pair[1];
                    ActiveQuest quest = q == null ? null
                            : ActiveQuest.restore(config.get().questFor(p.rankIndex()), q.state(), q.acceptedAt(), q.amounts(), q.marks());
                    sender.sendMessage(Component.text(resolved + ":", NamedTextColor.WHITE));
                    if (p.rankActive() && System.currentTimeMillis() - p.rankGrantedAt() < 7L * 24 * 60 * 60 * 1000)
                        printQuest(sender, p.rankIndex(), p.mastery(), quest);
                    else printRenewal(sender, (RenewalRecord) pair[2]);
                }));
    }

    private void printQuest(CommandSender to, int rankIndex, boolean mastery, ActiveQuest quest) {
        ConfigSnapshot cfg = config.get();
        RankStage stage = cfg.ladder().get(rankIndex);
        if (mastery || quest == null) {
            messages.send(to, "quest-none");
            return;
        }
        String state = switch (quest.state()) {
            case NOT_ACCEPTED -> "not accepted";
            case ACTIVE -> "in progress";
            case COMPLETED -> "completing";
            case REWARDED -> "complete";
        };
        to.sendMessage(messages.prefix().append(messages.get("progress-header",
                Messages.ph("stage", Messages.rank(stage)), Messages.ph("state", state))));
        for (ObjectiveDefinition o : quest.definition().objectives()) {
            ObjectiveStatus st = quest.status(o);
            Component status = st.complete()
                    ? Component.text("✔", NamedTextColor.WHITE) : Component.text("✘", NamedTextColor.WHITE);
            if (o.unique() > 0) {
                to.sendMessage(messages.get("progress-line-unique", Messages.ph("status", status),
                        Messages.ph("description", o.description()), Messages.ph("current", st.current()),
                        Messages.ph("required", o.amount()), Messages.ph("marks", st.marks()),
                        Messages.ph("unique", o.unique())));
            } else {
                to.sendMessage(messages.get("progress-line", Messages.ph("status", status),
                        Messages.ph("description", o.description()), Messages.ph("current", st.current()),
                        Messages.ph("required", o.amount())));
            }
        }
        if (quest.state() == QuestState.NOT_ACCEPTED) {
            to.sendMessage(messages.get("menu.active-none"));
        }
    }

    private void printRenewal(CommandSender to, PlayerSession s) {
        printRenewal(to, s.renewal());
    }

    private void printRenewal(CommandSender to, RenewalRecord r) {
        if (r == null) { messages.send(to, "rank-expired"); return; }
        if ("HUNT".equals(r.branch())) {
            messages.send(to, "renewal-progress-hunt", Messages.ph("target", r.target() == null ? "waiting" : r.target()),
                    Messages.ph("current", r.progress()), Messages.ph("required", r.required()));
        } else {
            messages.send(to, "renewal-progress-pve", Messages.ph("objective", r.objectiveType().toLowerCase().replace('_', ' ')),
                    Messages.ph("current", r.progress()), Messages.ph("required", r.required()));
        }
    }

    private void printLadder(CommandSender to, PlayerSession s) {
        ConfigSnapshot cfg = config.get();
        for (RankStage stage : cfg.ladder().stages()) {
            Component status;
            if (stage.index() < s.rankIndex() || s.mastery()) {
                status = Component.text("✔", NamedTextColor.WHITE);
            } else if (stage.index() == s.rankIndex()) {
                status = Component.text("▶", NamedTextColor.WHITE);
            } else {
                status = Component.text("✘", NamedTextColor.WHITE);
            }
            to.sendMessage(messages.get("rank-line", Messages.ph("status", status), Messages.ph("rank", Messages.rank(stage))));
        }
    }

    private boolean perm(CommandSender sender, String node) {
        if (sender.hasPermission(node)) {
            return true;
        }
        messages.send(sender, "no-permission");
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("ascension.rank")) {
                subs.add("progress");
                subs.add("quests");
                if (config.get().testingUnlockAll()) {
                    subs.add("test");
                }
            }
            if (sender.hasPermission("ascension.rank.target")) {
                subs.add("target");
            }
            if (sender.hasPermission("ascension.admin.rank.set")) {
                subs.add("set");
            }
            if (sender.hasPermission("ascension.admin.rank.advance")) {
                subs.add("advance");
            }
            if (sender.hasPermission("ascension.admin.rank.reset")) {
                subs.add("reset");
            }
            if (sender.hasPermission("ascension.admin.rank.quest")) {
                subs.add("quest");
            }
            return Args.filter(subs, args[0]);
        }
        String sub = args[0];
        if (args.length == 2) {
            if (Args.is(sub, "test") && config.get().testingUnlockAll()) {
                return Args.filter(config.get().ladder().tiers(), args[1]);
            }
            if ((Args.is(sub, "progress") && sender.hasPermission("ascension.admin.rank.progress"))
                    || (Args.is(sub, "set") && sender.hasPermission("ascension.admin.rank.set"))
                    || (Args.is(sub, "advance") && sender.hasPermission("ascension.admin.rank.advance"))
                    || (Args.is(sub, "reset") && sender.hasPermission("ascension.admin.rank.reset"))) {
                return Args.players(sender, args[1]);
            }
            if (Args.is(sub, "quest") && sender.hasPermission("ascension.admin.rank.quest")) {
                return Args.filter(List.of("complete"), args[1]);
            }
            if (Args.is(sub, "target") && sender.hasPermission("ascension.rank.target")) {
                List<String> targetSubs = sender.hasPermission("ascension.admin.rank.target")
                        ? List.of("reroll", "set", "cancel") : List.of("reroll");
                return Args.filter(targetSubs, args[1]);
            }
        }
        if (Args.is(sub, "test") && config.get().testingUnlockAll() && args.length == 3) {
            List<String> stages = new ArrayList<>();
            for (RankStage stage : config.get().ladder().stages()) {
                if (stage.tier().equalsIgnoreCase(args[1])) {
                    stages.add(Integer.toString(stage.stage()));
                }
            }
            return Args.filter(stages, args[2]);
        }
        if (Args.is(sub, "target") && sender.hasPermission("ascension.admin.rank.target")) {
            if (args.length == 3 && (Args.is(args[1], "reroll") || Args.is(args[1], "set")
                    || Args.is(args[1], "cancel"))) {
                return Args.players(sender, args[2]);
            }
            if (args.length == 4 && Args.is(args[1], "set")) {
                return Args.players(sender, args[3]);
            }
        }
        if (Args.is(sub, "set") && sender.hasPermission("ascension.admin.rank.set")) {
            if (args.length == 3) {
                return Args.filter(config.get().ladder().tiers(), args[2]);
            }
            if (args.length == 4) {
                List<String> stages = new ArrayList<>();
                for (RankStage st : config.get().ladder().stages()) {
                    if (st.tier().equalsIgnoreCase(args[2])) {
                        stages.add(Integer.toString(st.stage()));
                    }
                }
                return Args.filter(stages, args[3]);
            }
        }
        if (Args.is(sub, "quest") && sender.hasPermission("ascension.admin.rank.quest")) {
            if (args.length == 3) {
                return Args.players(sender, args[2]);
            }
            if (args.length == 4) {
                Player target = Bukkit.getPlayerExact(args[2]);
                PlayerSession s = target == null ? null : sessions.get(target);
                if (s != null && s.quest() != null) {
                    List<String> ids = new ArrayList<>();
                    for (ObjectiveDefinition o : s.quest().definition().objectives()) {
                        ids.add(o.id());
                    }
                    return Args.filter(ids, args[3]);
                }
            }
        }
        return List.of();
    }
}
