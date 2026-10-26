package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.gui.MenuService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class MarketCommand implements CommandExecutor, TabCompleter {
    private final MarketService market;
    private final MenuService menus;
    public MarketCommand(MarketService market, MenuService menus) { this.market = market; this.menus = menus; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        if (List.of("pending", "recover", "resolve").contains(action)) {
            if (!sender.hasPermission("ascension.admin.market")) { sender.sendMessage("No market administration permission."); return true; }
            if (action.equals("pending")) { market.pending(sender); return true; }
            if (action.equals("recover") && args.length == 2) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) sender.sendMessage("That player must be online.");
                else { market.recover(target); sender.sendMessage("Recovery queued."); }
                return true;
            }
            if (action.equals("resolve") && args.length == 3 && List.of("commit", "abort").contains(args[2])) {
                market.resolve(sender, args[1], args[2].equals("commit")); return true;
            }
            sender.sendMessage("/auction pending | recover <online player> | resolve <operation id> <commit|abort>");
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players open the market using /auction."); return true; }
        if (!player.hasPermission("ascension.market")) { sender.sendMessage("No market permission."); return true; }
        String screen = switch (action) {
            case "buy" -> "market_browse:0";
            case "sell" -> "market_sell:0";
            case "mine" -> "market_mine:0";
            case "collect" -> "market_deliveries:0";
            case "shop" -> "market_shop";
            case "exchange" -> "market_exchange";
            default -> "market";
        };
        menus.open(player, screen);
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> values = sender.hasPermission("ascension.admin.market")
                ? List.of("buy", "sell", "mine", "collect", "shop", "exchange", "pending", "recover", "resolve")
                : List.of("buy", "sell", "mine", "collect", "shop", "exchange");
        return values.stream().filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
