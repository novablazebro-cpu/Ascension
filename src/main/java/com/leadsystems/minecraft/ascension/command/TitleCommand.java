package com.leadsystems.minecraft.ascension.command;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.service.TitleService;
import com.leadsystems.minecraft.ascension.service.AdminService;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Player title claim, equip, unequip, info and owner commands. */
public final class TitleCommand implements TabExecutor {
    private final TitleService titles;
    private final AdminService admin;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;

    public TitleCommand(TitleService titles, AdminService admin, Supplier<ConfigSnapshot> config, Messages messages) {
        this.titles = titles;
        this.admin = admin;
        this.config = config;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length > 0 && adminCommand(sender, args)) {
            return true;
        }
        if (!(sender instanceof Player player) || !sender.hasPermission("ascension.titles")) {
            messages.send(sender, sender instanceof Player ? "no-permission" : "players-only");
            return true;
        }
        if (args.length == 0) {
            messages.send(sender, "usage", Messages.ph("usage", "/title claim|equip|unequip|info|owner <title>"));
            return true;
        }
        String sub = args[0];
        if (Args.is(sub, "unequip")) {
            titles.unequip(player, () -> {});
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "usage", Messages.ph("usage", "/title " + sub + " <title>"));
            return true;
        }
        String ref = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        TitleDefinition title = config.get().titles().resolve(ref);
        if (title == null) {
            messages.send(sender, "title-invalid");
        } else if (Args.is(sub, "claim")) {
            titles.claim(player, title.id(), () -> {});
        } else if (Args.is(sub, "equip")) {
            titles.equip(player, title.id(), () -> {});
        } else if (Args.is(sub, "info")) {
            sender.sendMessage(Messages.upright(net.kyori.adventure.text.Component.text(
                    "#" + title.number() + " " + title.name() + ": " + title.description())));
            title.requirements().forEach(r -> sender.sendMessage(net.kyori.adventure.text.Component.text("- " + r.label())));
        } else if (Args.is(sub, "owner")) {
            UUID owner = titles.owner(title.id());
            messages.send(sender, "title-owner", Messages.ph("title", title.name()),
                    Messages.ph("owner", owner == null ? "unclaimed" : owner.toString()));
        } else {
            messages.send(sender, "usage", Messages.ph("usage", "/title claim|equip|unequip|info|owner <title>"));
        }
        return true;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        String sub = args[0];
        if (Args.is(sub, "give")) {
            if (!allowed(sender, "ascension.admin.title.give") || args.length < 3) return true;
            TitleDefinition title = resolve(args, 2, args.length);
            if (title == null) messages.send(sender, "title-invalid");
            else admin.giveTitle(sender, args[1], title.id());
            return true;
        }
        if (Args.is(sub, "revoke")) {
            if (!allowed(sender, "ascension.admin.title.revoke") || args.length < 2) return true;
            TitleDefinition title = resolve(args, 1, args.length);
            if (title == null) messages.send(sender, "title-invalid");
            else admin.revokeTitle(sender, title.id());
            return true;
        }
        if (Args.is(sub, "transfer")) {
            if (!allowed(sender, "ascension.admin.title.transfer") || args.length < 3) return true;
            TitleDefinition title = resolve(args, 1, args.length - 1);
            if (title == null) messages.send(sender, "title-invalid");
            else admin.transferTitle(sender, title.id(), args[args.length - 1]);
            return true;
        }
        if (Args.is(sub, "reset")) {
            if (!allowed(sender, "ascension.admin.title.reset") || args.length != 2) return true;
            admin.resetTitles(sender, args[1]);
            return true;
        }
        if (Args.is(sub, "cooldown") && args.length >= 3 && Args.is(args[1], "reset")) {
            if (!allowed(sender, "ascension.admin.title.cooldown") || args.length > 4) return true;
            String titleId = null;
            if (args.length == 4) {
                TitleDefinition title = config.get().titles().resolve(args[3]);
                if (title == null) {
                    messages.send(sender, "title-invalid");
                    return true;
                }
                titleId = title.id();
            }
            admin.resetTitleCooldowns(sender, args[2], titleId);
            return true;
        }
        return false;
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        messages.send(sender, "no-permission");
        return false;
    }

    private TitleDefinition resolve(String[] args, int from, int to) {
        return config.get().titles().resolve(String.join(" ", java.util.Arrays.copyOfRange(args, from, to)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> choices = sender.hasPermission("ascension.admin")
                    ? List.of("claim", "equip", "unequip", "info", "owner", "give", "revoke", "transfer", "reset", "cooldown")
                    : List.of("claim", "equip", "unequip", "info", "owner");
            return Args.filter(choices, args[0]);
        }
        if (args.length == 2 && !Args.is(args[0], "unequip")) {
            return Args.filter(config.get().titles().ordered().stream().map(TitleDefinition::id).toList(), args[1]);
        }
        return List.of();
    }
}
