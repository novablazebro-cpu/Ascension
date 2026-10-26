package com.leadsystems.minecraft.ascension.command;

import com.leadsystems.minecraft.ascension.ability.AbilityRuntime;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.gui.MenuService;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.AuditEntry;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code /titles} opens the titles page; admin sub-commands {@code reload}, {@code debug <player>}
 * and {@code audit [count]}.
 */
public final class TitlesCommand implements TabExecutor {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final MenuService menus;
    private final SessionManager sessions;
    private final Messages messages;
    private final Supplier<List<String>> reloader;
    private final StorageExecutor storage;
    private final StorageHealth health;
    private final PlayerRepository repo;
    private final MainThread main;

    public TitlesCommand(MenuService menus, SessionManager sessions, Messages messages, Supplier<List<String>> reloader,
                         StorageExecutor storage, StorageHealth health, PlayerRepository repo, MainThread main) {
        this.menus = menus;
        this.sessions = sessions;
        this.messages = messages;
        this.reloader = reloader;
        this.storage = storage;
        this.health = health;
        this.repo = repo;
        this.main = main;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                messages.send(sender, "usage", Messages.ph("usage", "/titles reload | debug <player> | audit [count]"));
            } else if (!sender.hasPermission("ascension.titles")) {
                messages.send(sender, "no-permission");
            } else {
                menus.open(p, "titles");
            }
            return true;
        }
        String sub = args[0];
        if (Args.is(sub, "reload")) {
            if (!perm(sender, "ascension.admin.reload")) {
                return true;
            }
            List<String> problems = reloader.get();
            if (problems.isEmpty()) {
                messages.send(sender, "reload-ok");
            } else {
                messages.send(sender, "reload-failed", Messages.ph("count", problems.size()));
                for (String p : problems) {
                    sender.sendMessage(messages.get("reload-problem", Messages.ph("problem", p)));
                }
            }
        } else if (Args.is(sub, "debug")) {
            if (!perm(sender, "ascension.admin.debug")) {
                return true;
            }
            if (args.length < 2) {
                messages.send(sender, "usage", Messages.ph("usage", "/titles debug <player>"));
                return true;
            }
            debug(sender, args[1]);
        } else if (Args.is(sub, "audit")) {
            if (!perm(sender, "ascension.admin.audit")) {
                return true;
            }
            int count = 10;
            if (args.length >= 2) {
                try {
                    count = Math.max(1, Math.min(100, Integer.parseInt(args[1])));
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            final int limit = count;
            main.onComplete(storage.submit("audit.read", c -> repo.recentAudit(c, limit)), (rows, error) -> {
                if (error != null) {
                    messages.send(sender, "admin-failed", Messages.ph("reason", String.valueOf(error.getMessage())));
                    return;
                }
                sender.sendMessage(Component.text("Recent Ascension audit entries (" + rows.size() + "):", NamedTextColor.WHITE));
                for (AuditEntry r : rows) {
                    sender.sendMessage(Component.text(TIME.format(Instant.ofEpochMilli(r.at())) + " ", NamedTextColor.WHITE)
                            .append(Component.text(r.actor() + " ", NamedTextColor.WHITE))
                            .append(Component.text(r.action() + " ", NamedTextColor.WHITE))
                            .append(Component.text(String.valueOf(r.target()) + " ", NamedTextColor.WHITE))
                            .append(Component.text(r.details() == null ? "" : r.details(), NamedTextColor.WHITE)));
                }
            });
        } else {
            messages.send(sender, "usage", Messages.ph("usage", "/titles [reload|debug|audit]"));
        }
        return true;
    }

    private void debug(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        PlayerSession s = target == null ? null : sessions.get(target);
        List<String> lines = new ArrayList<>();
        lines.add("storage healthy=" + health.isHealthy() + (health.isHealthy() ? "" : " last error: " + health.lastError()));
        if (s == null) {
            lines.add(name + " is not online (debug shows live sessions only)");
        } else {
            lines.add("player=" + s.name() + " uuid=" + s.uuid() + " bedrock=" + s.bedrock());
            lines.add("loaded=" + s.loaded() + " loading=" + s.loading() + " frozen=" + s.frozen());
            lines.add("rankIndex=" + s.rankIndex() + " mastery=" + s.mastery());
            lines.add("bookNonce=" + abbreviate(s.bookNonce()) + " pending=" + s.bookPending() + " issuing=" + s.bookIssuing());
            if (s.quest() != null) {
                StringBuilder sb = new StringBuilder("quest state=" + s.quest().state() + " progress:");
                s.quest().definition().objectives().forEach(o ->
                        sb.append(' ').append(o.id()).append('=').append(s.quest().amount(o.id())).append('/').append(o.amount()));
                lines.add(sb.toString());
            }
            AbilityRuntime rt = s.abilities();
            List<String> pkgs = new ArrayList<>();
            for (RankAbility a : rt.active()) {
                pkgs.add(a.id());
            }
            lines.add("abilities generation=" + rt.generation() + " active=" + pkgs);
            List<String> effects = new ArrayList<>();
            for (Map.Entry<PotionEffectType, AbilityRuntime.AppliedEffect> e : rt.applied().entrySet()) {
                effects.add(e.getKey().getKey().getKey() + ":" + (e.getValue().amplifier() + 1));
            }
            lines.add("applied effects=" + effects);
            lines.add("cooldown keys=" + s.cooldowns().keys() + " stats=" + s.statsView().size() + " keys");
        }
        for (String l : lines) {
            sender.sendMessage(Component.text(l, NamedTextColor.WHITE));
        }
    }

    private static String abbreviate(String nonce) {
        return nonce == null || nonce.length() < 8 ? String.valueOf(nonce) : nonce.substring(0, 8) + "...";
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
            if (sender.hasPermission("ascension.admin.reload")) {
                subs.add("reload");
            }
            if (sender.hasPermission("ascension.admin.debug")) {
                subs.add("debug");
            }
            if (sender.hasPermission("ascension.admin.audit")) {
                subs.add("audit");
            }
            return Args.filter(subs, args[0]);
        }
        if (args.length == 2 && Args.is(args[0], "debug") && sender.hasPermission("ascension.admin.debug")) {
            return Args.players(sender, args[1]);
        }
        return List.of();
    }
}
