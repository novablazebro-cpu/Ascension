package com.leadsystems.minecraft.ascension.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test void configuredColorsAreKeptAndPlaceholdersStayUnparsed() {
        var messages = new Messages();
        messages.load(Map.of("prefix", "<gold>[Ascension]</gold> ", "hello", "<red>Hello <name></red>"));
        Component rendered = messages.get("hello", Messages.ph("name", "<green>Alex</green>"));
        // A player name can never inject formatting: the tag is shown literally.
        assertEquals("Hello <green>Alex</green>", Messages.plain(rendered));
        assertEquals(NamedTextColor.RED, rendered.color());
        assertEquals(NamedTextColor.GOLD, messages.prefix().children().getFirst().color());
    }

    @Test void uprightRetainsColorsClickActionsAndNestedContent() {
        Component source = Component.text("Quest", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/rank quest"))
                .append(Component.text(" ready", NamedTextColor.GREEN));
        Component upright = Messages.upright(source);
        assertEquals("Quest ready", Messages.plain(upright));
        assertEquals(source.clickEvent(), upright.clickEvent());
        assertEquals(NamedTextColor.RED, upright.color());
        assertEquals(NamedTextColor.GREEN, upright.children().getFirst().color());
        assertEquals(TextDecoration.State.FALSE, upright.decoration(TextDecoration.ITALIC));
    }
}
