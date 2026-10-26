package com.leadsystems.minecraft.ascension.command;

import com.leadsystems.minecraft.ascension.audit.AuditLog;
import com.leadsystems.minecraft.ascension.book.BookService;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code /titlebook} delivers a missing or pending Chronicle; {@code /titlebook give <player>}
 * (admin) invalidates every existing copy and issues a fresh one.
 */
public final class TitleBookCommand implements TabExecutor {

    private final BookService books;
    private final SessionManager sessions;
    private final Messages messages;
    private final AuditLog audit;

    public TitleBookCommand(BookService books, SessionManager sessions, Messages messages, AuditLog audit) {
        this.books = books;
        this.sessions = sessions;
        this.messages = messages;
        this.audit = audit;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length >= 1 && Args.is(args[0], "give")) {
            if (!sender.hasPermission("ascension.admin.titlebook.give")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length < 2) {
                messages.send(sender, "usage", Messages.ph("usage", "/titlebook give <player>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            PlayerSession s = target == null ? null : sessions.get(target);
            if (s == null || !s.usable()) {
                messages.send(sender, "admin-failed", Messages.ph("reason", "the player must be online with loaded data"));
                return true;
            }
            books.forceReissue(target, s);
            audit.record(sender.getName(), "titlebook.give", target.getName(), "nonce rotated");
            messages.send(sender, "admin-done", Messages.ph("action", "titlebook give " + target.getName()));
            return true;
        }
        if (!(sender instanceof Player p)) {
            messages.send(sender, "players-only");
            return true;
        }
        if (!sender.hasPermission("ascension.titlebook")) {
            messages.send(sender, "no-permission");
            return true;
        }
        PlayerSession s = sessions.get(p);
        if (s == null) {
            messages.send(p, "data-unavailable");
            return true;
        }
        books.deliver(p, s);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (!sender.hasPermission("ascension.admin.titlebook.give")) {
            return List.of();
        }
        if (args.length == 1) {
            return Args.filter(List.of("give"), args[0]);
        }
        if (args.length == 2 && Args.is(args[0], "give")) {
            return Args.players(sender, args[1]);
        }
        return List.of();
    }
}
