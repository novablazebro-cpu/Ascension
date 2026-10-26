package com.leadsystems.minecraft.ascension.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Small helpers shared by the command classes.
 */
final class Args {

    private Args() {}

    /** Options starting with the typed prefix (case-insensitive), for tab completion. */
    static List<String> filter(Collection<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    /** Online player names visible to the sender. */
    static List<String> players(CommandSender sender, String prefix) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!(sender instanceof Player viewer) || viewer.canSee(p)) {
                names.add(p.getName());
            }
        }
        return filter(names, prefix);
    }

    static boolean is(String arg, String word) {
        return arg.equalsIgnoreCase(word);
    }
}
