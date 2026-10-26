package com.leadsystems.minecraft.ascension.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test void configuredMessagesRemoveNestedColorsAndKeepUnparsedPlaceholders() {
        var messages = new Messages();
        messages.load(Map.of("prefix", "<gold>[Ascension]</gold> ", "hello", "<red>Hello <name></red>"));
        Component rendered = messages.get("hello", Messages.ph("name", "<green>Alex</green>"));
        assertEquals("Hello <green>Alex</green>", Messages.plain(rendered));
        assertEquals(NamedTextColor.WHITE, rendered.color());
        assertNeutralChildren(rendered);
        assertEquals(NamedTextColor.WHITE, messages.prefix().color());
    }

    @Test void neutralColorRetainsClickActionsAndNestedContent() {
        Component source = Component.text("Quest", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/rank quest"))
                .append(Component.text(" ready", NamedTextColor.GREEN));
        Component neutral = Messages.upright(source);
        assertEquals("Quest ready", Messages.plain(neutral));
        assertEquals(source.clickEvent(), neutral.clickEvent());
        assertEquals(NamedTextColor.WHITE, neutral.color());
        assertNeutralChildren(neutral);
    }

    private static void assertNeutralChildren(Component component) {
        component.children().forEach(child -> {
            assertNull(child.color());
            assertNeutralChildren(child);
        });
    }
}
