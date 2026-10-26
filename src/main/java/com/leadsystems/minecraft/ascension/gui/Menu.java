package com.leadsystems.minecraft.ascension.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Platform-neutral menu model. {@code MenuService} builds screens once; the Java renderer turns
 * them into chest GUIs and the Bedrock renderer into Cumulus forms, so both platforms always show
 * the same information and actions.
 */
public final class Menu {

    private Menu() {}

    /** What pressing a button does. */
    public enum Action {
        /** Open another screen ({@code arg} = screen id). */
        OPEN,
        /** Show the accept confirmation for the current quest. */
        ACCEPT_QUEST,
        /** Accept the quest ({@code arg} = rank index, re-validated server-side). */
        CONFIRM_ACCEPT,
        /** Submit the remaining items for one submission objective. */
        SUBMIT_ITEM,
        TITLE_CLAIM,
        TITLE_EQUIP,
        TITLE_UNEQUIP,
        MARKET_BUY,
        MARKET_CANCEL,
        MARKET_SELL,
        MARKET_CLAIM,
        MARKET_EXCHANGE,
        MARKET_SHOP_BUY,
        /** Close the menu. */
        CLOSE
    }

    /**
     * A clickable action.
     *
     * @param nav true for navigation (Back/Close/Cancel): placed on the bottom row in chest menus
     */
    public record Button(Component label, List<Component> lore, Material icon, Action action, String arg,
                         boolean nav, Map<Integer, Countdown> countdowns) {
        public Button(Component label, List<Component> lore, Material icon, Action action, String arg, boolean nav) {
            this(label, lore, icon, action, arg, nav, Map.of());
        }
        public Button {
            lore = List.copyOf(lore);
            countdowns = Map.copyOf(countdowns);
        }
    }

    /** A read-only line (e.g. one quest objective): an item in chest menus, text in forms. */
    public record Entry(Component label, List<Component> lore, Material icon) {
        public Entry {
            lore = List.copyOf(lore);
        }
    }

    /**
     * One screen.
     *
     * @param confirm a yes/no question: exactly two buttons (confirm, cancel); Bedrock shows a ModalForm
     */
    public record Screen(String id, Component title, List<Component> body, List<Entry> entries,
                         List<Button> buttons, boolean confirm, Map<Integer, Countdown> countdowns) {
        public Screen(String id, Component title, List<Component> body, List<Entry> entries,
                      List<Button> buttons, boolean confirm) {
            this(id, title, body, entries, buttons, confirm, Map.of());
        }
        public Screen {
            body = List.copyOf(body);
            entries = List.copyOf(entries);
            buttons = List.copyOf(buttons);
            countdowns = Map.copyOf(countdowns);
        }
    }

    /** Timestamp metadata lets Java update decoration without replacing the menu or its actions. */
    public record Countdown(String prefix, long deadline, boolean reserved) {
        public Component at(long now) {
            String state = reserved ? "Purchase completing…" : deadline <= now ? "Restocking…" : format(deadline - now);
            return Component.text(prefix + state, net.kyori.adventure.text.format.NamedTextColor.WHITE);
        }

        public static String format(long remainingMillis) {
            long positive = Math.max(0, remainingMillis);
            long seconds = positive / 1000 + (positive % 1000 == 0 ? 0 : 1);
            if (seconds >= 3600) {
                return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
                        (seconds / 60) % 60, seconds % 60);
            }
            return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        }
    }
}
