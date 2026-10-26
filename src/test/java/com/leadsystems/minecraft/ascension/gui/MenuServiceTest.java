package com.leadsystems.minecraft.ascension.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuServiceTest {

    @Test
    void bedrockButtonsIncludeTheirStatusOrDescriptionLore() {
        Menu.Button button = new Menu.Button(Component.text("#8 Titan"), List.of(Component.text("wearable")),
                Material.NAME_TAG, Menu.Action.OPEN, "title:titan", false);

        String rendered = BedrockFormRenderer.buttonText(button);

        assertTrue(rendered.contains("#8 Titan"));
        assertTrue(rendered.contains("\n"));
        assertTrue(rendered.contains("wearable"));
    }

    @Test
    void bedrockButtonsShowPlainTextWithoutColorCodes() {
        Menu.Button help = new Menu.Button(Component.text("Help", NamedTextColor.GRAY), List.of(),
                Material.BOOK, Menu.Action.OPEN, "help", false);
        Menu.Button back = new Menu.Button(Component.text("\u00ab Back", NamedTextColor.GRAY), List.of(),
                Material.ARROW, Menu.Action.OPEN, "main", true);
        Menu.Button chronomancer = new Menu.Button(
                Component.text("#7 Chronomancer", TextColor.fromHexString("#9aa7c7")),
                List.of(Component.text("wearable", NamedTextColor.GRAY)), Material.NAME_TAG,
                Menu.Action.OPEN, "title:chronomancer", false);

        assertEquals("Help", BedrockFormRenderer.buttonText(help));
        assertEquals("\u00ab Back", BedrockFormRenderer.buttonText(back));
        String rendered = BedrockFormRenderer.buttonText(chronomancer);
        assertEquals("#7 Chronomancer\nwearable", rendered);
        assertFalse(rendered.contains("\u00a7"), "Form labels must not contain formatting codes");
    }

    @Test
    void bedrockDoesNotEmitJavaStrikethroughOrUnderlineAsUnexpectedMaterialColors() {
        Component styled = Component.text("Confirm", NamedTextColor.GREEN)
                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                .decorate(net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH);
        assertEquals("Confirm", BedrockFormRenderer.formatted(styled));
    }

    @Test
    void bedrockTitleFormContainsAllThirtyTitlesInOneScrollingList() {
        MenuService.TitleRange range = MenuService.titleRange(30, 0, true);

        assertEquals(0, range.from());
        assertEquals(30, range.to());
        assertFalse(range.previous());
        assertFalse(range.next());
    }

    @Test
    void javaTitleInventoryKeepsTenItemPages() {
        MenuService.TitleRange first = MenuService.titleRange(30, 0, false);
        MenuService.TitleRange middle = MenuService.titleRange(30, 1, false);
        MenuService.TitleRange last = MenuService.titleRange(30, 2, false);

        assertEquals(10, first.to() - first.from());
        assertFalse(first.previous());
        assertTrue(first.next());
        assertEquals(10, middle.to() - middle.from());
        assertTrue(middle.previous());
        assertTrue(middle.next());
        assertEquals(10, last.to() - last.from());
        assertTrue(last.previous());
        assertFalse(last.next());
    }

    @Test
    void javaTitleInventorySupportsArbitraryPagesBeyondThirty() {
        MenuService.TitleRange page3 = MenuService.titleRange(35, 3, false);
        assertEquals(30, page3.from());
        assertEquals(35, page3.to());
        assertTrue(page3.previous());
        assertFalse(page3.next());
    }

    @Test
    void titlePagesClampUntrustedLargeAndNegativePageNumbers() {
        MenuService.TitleRange oversized = MenuService.titleRange(35, Integer.MAX_VALUE, false);
        assertEquals(30, oversized.from());
        assertEquals(35, oversized.to());
        assertFalse(oversized.next());
        assertEquals(0, MenuService.titleRange(35, -10, false).from());
    }

    @Test
    void bedrockRefreshRecomputesCountdownsFromTheDeadline() {
        var timer = new Menu.Countdown("Restock: ", 10_000, false);
        var button = new Menu.Button(Component.text("Stone"), List.of(timer.at(0)), Material.STONE,
                Menu.Action.OPEN, "market_stock:stone", false, java.util.Map.of(0, timer));
        var screen = new Menu.Screen("market_shop", Component.text("Shop"), List.of(timer.at(0)),
                List.of(), List.of(button), false, java.util.Map.of(0, timer));
        assertEquals("Stone\nRestock: 00:03", BedrockFormRenderer.buttonText(button, 7_000));
        assertEquals("Restock: 00:03", BedrockFormRenderer.content(screen, 7_000));
        assertEquals("Restock: Restocking…", BedrockFormRenderer.content(screen, 10_000));
    }

    @Test
    void javaHelpAndTitleDetailsRenderBodyAsVisibleItems() {
        var server = MockBukkit.mock();
        try {
            var player = server.addPlayer();
            Menu.Screen help = new Menu.Screen("help", Component.text("Help"),
                    List.of(Component.text("Accept a quest"), Component.text("Use the ability stick")),
                    List.of(), List.of(new Menu.Button(Component.text("Back"), List.of(), Material.ARROW,
                    Menu.Action.OPEN, "main", true)), false);
            new JavaMenuRenderer().render(player, help, 1);
            var inventory = player.getOpenInventory().getTopInventory();
            // Two body lines sit centered with a gap on the first content row of the framed layout.
            assertEquals(Material.PAPER, inventory.getItem(12).getType());
            assertEquals(Material.PAPER, inventory.getItem(14).getType());
            assertEquals("Accept a quest", com.leadsystems.minecraft.ascension.text.Messages.plain(
                    inventory.getItem(12).getItemMeta().displayName()));
            assertEquals("Use the ability stick", com.leadsystems.minecraft.ascension.text.Messages.plain(
                    inventory.getItem(14).getItemMeta().displayName()));
            assertEquals(Material.ARROW, inventory.getItem(49).getType());
            assertEquals(Material.BLACK_STAINED_GLASS_PANE, inventory.getItem(9).getType(), "framed side");

            Menu.Screen confirm = new Menu.Screen("confirm", Component.text("Accept?"),
                    List.of(Component.text("Only new progress counts")), List.of(), List.of(
                    new Menu.Button(Component.text("Accept"), List.of(), Material.LIME_CONCRETE,
                            Menu.Action.CONFIRM_ACCEPT, "0", false),
                    new Menu.Button(Component.text("Cancel"), List.of(), Material.RED_CONCRETE,
                            Menu.Action.OPEN, "main", false)), true);
            new JavaMenuRenderer().render(player, confirm, 2);
            inventory = player.getOpenInventory().getTopInventory();
            assertEquals("Only new progress counts", com.leadsystems.minecraft.ascension.text.Messages.plain(
                    inventory.getItem(13).getItemMeta().displayName()));
            assertEquals(Material.LIME_CONCRETE, inventory.getItem(21).getType());
            assertEquals(Material.RED_CONCRETE, inventory.getItem(23).getType());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void bedrockFormFallbackChestKeepsAllThirtyTitleButtons() {
        var server = MockBukkit.mock();
        try {
            var player = server.addPlayer();
            List<Menu.Button> buttons = new java.util.ArrayList<>();
            for (int number = 1; number <= 30; number++) {
                buttons.add(new Menu.Button(Component.text("Title " + number), List.of(), Material.NAME_TAG,
                        Menu.Action.OPEN, "title:" + number, false));
            }
            buttons.add(new Menu.Button(Component.text("Back"), List.of(), Material.ARROW,
                    Menu.Action.OPEN, "main", true));
            new JavaMenuRenderer().render(player, new Menu.Screen("titles", Component.text("Titles"),
                    List.of(), List.of(), buttons, false), 1);
            var inventory = player.getOpenInventory().getTopInventory();
            // 30 buttons exceed the 28-slot frame, so the full width is used: 27 in rows 1-3, 3 centered in row 4.
            for (int slot = 9; slot < 36; slot++) {
                assertEquals(Material.NAME_TAG, inventory.getItem(slot).getType(), "missing title at slot " + slot);
            }
            for (int slot : new int[] {38, 40, 42}) {
                assertEquals(Material.NAME_TAG, inventory.getItem(slot).getType(), "missing title at slot " + slot);
            }
            long titles = java.util.Arrays.stream(inventory.getContents())
                    .filter(item -> item != null && item.getType() == Material.NAME_TAG).count();
            assertEquals(30, titles);
            assertEquals(Material.ARROW, inventory.getItem(49).getType());
        } finally {
            MockBukkit.unmock();
        }
    }
}
