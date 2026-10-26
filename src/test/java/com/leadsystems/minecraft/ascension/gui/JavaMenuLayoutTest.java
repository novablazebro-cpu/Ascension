package com.leadsystems.minecraft.ascension.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaMenuLayoutTest {

    private static Menu.Screen screen(int body, int buttons) {
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < body; i++) lines.add(Component.text("line " + i));
        List<Menu.Button> list = new ArrayList<>();
        for (int i = 0; i < buttons; i++) {
            list.add(new Menu.Button(Component.text("b" + i), List.of(), Material.STONE, Menu.Action.OPEN, "x", false));
        }
        list.add(new Menu.Button(Component.text("Back"), List.of(), Material.ARROW, Menu.Action.OPEN, "main", true));
        return new Menu.Screen("s", Component.text("S"), lines, List.of(), list, false);
    }

    @Test void mainMenuIsFramedWithBodyAndButtonsOnSeparateCenteredRows() {
        var layout = JavaMenuRenderer.layout(screen(2, 9));
        assertTrue(layout.framed());
        assertArrayEquals(new int[] {12, 14}, layout.body());
        assertArrayEquals(new int[] {19, 20, 21, 22, 23, 24, 25, 30, 32}, layout.items());
    }

    @Test void smallRowsSpreadSymmetricallyAroundTheCenter() {
        assertArrayEquals(new int[] {13}, JavaMenuRenderer.layout(screen(0, 1)).items());
        assertArrayEquals(new int[] {11, 13, 15}, JavaMenuRenderer.layout(screen(0, 3)).items());
        assertArrayEquals(new int[] {10, 12, 14, 16}, JavaMenuRenderer.layout(screen(0, 4)).items());
    }

    @Test void largeScreensUseTheFullWidthAndNeverOverlap() {
        for (int body = 0; body <= 6; body++) {
            for (int buttons = 0; buttons <= 40; buttons++) {
                var layout = JavaMenuRenderer.layout(screen(body, buttons));
                int[] all = new int[layout.body().length + layout.items().length];
                System.arraycopy(layout.body(), 0, all, 0, layout.body().length);
                System.arraycopy(layout.items(), 0, all, layout.body().length, layout.items().length);
                var unique = new HashSet<Integer>();
                for (int slot : all) {
                    assertTrue(slot >= 9 && slot < 45, "content row: " + slot);
                    if (layout.framed()) assertTrue(slot % 9 != 0 && slot % 9 != 8, "inside frame: " + slot);
                    assertTrue(unique.add(slot), "overlap at " + slot + " for " + body + "/" + buttons);
                }
                assertEquals(Math.min(36, body + buttons), all.length, body + "/" + buttons + " " + Arrays.toString(all));
            }
        }
    }

    @Test void confirmationsCenterTheQuestionAboveTheTwoChoices() {
        var confirm = new Menu.Screen("c", Component.text("Accept?"), List.of(Component.text("Only new progress counts")),
                List.of(), List.of(
                new Menu.Button(Component.text("Yes"), List.of(), Material.LIME_CONCRETE, Menu.Action.CONFIRM_ACCEPT, "0", false),
                new Menu.Button(Component.text("No"), List.of(), Material.RED_CONCRETE, Menu.Action.OPEN, "main", false)), true);
        assertArrayEquals(new int[] {13}, JavaMenuRenderer.layout(confirm).body());
    }
}
