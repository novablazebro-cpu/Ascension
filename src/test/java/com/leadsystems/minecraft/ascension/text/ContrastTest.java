package com.leadsystems.minecraft.ascension.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ContrastTest {

    private static final Map<Contrast.Surface, Integer> BACKGROUND = Map.of(
            Contrast.Surface.TOOLTIP, 0x100010, Contrast.Surface.CHAT, 0x000000,
            Contrast.Surface.CHEST_TITLE, 0xC6C6C6);
    private static final Map<Contrast.Surface, Double> MINIMUM = Map.of(
            Contrast.Surface.TOOLTIP, 4.5, Contrast.Surface.CHAT, 3.0, Contrast.Surface.CHEST_TITLE, 4.5);

    /** The reported bug: gray "Help"/"Titles" labels vanished on the light chest title bar. */
    @Test void grayChestTitleIsDarkenedUntilReadable() {
        Component title = Contrast.on(Contrast.Surface.CHEST_TITLE, Component.text("Help", NamedTextColor.GRAY));
        assertNotEquals(NamedTextColor.GRAY.value(), title.color().value());
        assertTrue(Contrast.ratio(title.color().value(), 0xC6C6C6) >= 4.5);
    }

    @Test void everyNamedColorIsReadableOnEverySurface() {
        for (NamedTextColor color : NamedTextColor.NAMES.values()) {
            for (Contrast.Surface surface : BACKGROUND.keySet()) {
                TextColor readable = Contrast.readable(color, surface);
                assertTrue(Contrast.ratio(readable.value(), BACKGROUND.get(surface)) >= MINIMUM.get(surface),
                        color + " on " + surface);
            }
        }
    }

    @Test void readableColorsAreLeftAloneAndAdjustedOnesKeepTheirHue() {
        assertEquals(NamedTextColor.WHITE, Contrast.readable(NamedTextColor.WHITE, Contrast.Surface.TOOLTIP));
        assertEquals(NamedTextColor.DARK_BLUE, Contrast.readable(NamedTextColor.DARK_BLUE, Contrast.Surface.CHEST_TITLE));
        TextColor gold = Contrast.readable(NamedTextColor.GOLD, Contrast.Surface.CHEST_TITLE);
        assertTrue(gold.red() > gold.green() && gold.green() > gold.blue(), "darkened gold stays orange: " + gold);
    }

    @Test void nestedColorsAreAdjustedAndTooltipTextDefaultsToWhite() {
        Component line = Component.text("Level ").append(Component.text("3", NamedTextColor.BLACK));
        Component shown = Contrast.on(Contrast.Surface.TOOLTIP, line);
        assertEquals(NamedTextColor.WHITE, shown.color());
        assertTrue(Contrast.ratio(shown.children().getFirst().color().value(), 0x100010) >= 4.5);
        assertNull(Contrast.on(Contrast.Surface.CHEST_TITLE, Component.text("Plain")).color(),
                "chest titles keep the vanilla default color");
    }

    @Test void bedrockButtonsKeepOnlyColorsReadableOnTheLightButton() {
        assertEquals("Help", Contrast.bedrockButton(Component.text("Help", NamedTextColor.GRAY)));
        assertEquals("Titles", Contrast.bedrockButton(Component.text("Titles", TextColor.fromHexString("#f5d76e"))));
        assertEquals("§4Warning§r: read", Contrast.bedrockButton(Component.text()
                .append(Component.text("Warning", NamedTextColor.DARK_RED)).append(Component.text(": read")).build()));
        assertEquals("Confirm", Contrast.bedrockButton(Component.text("Confirm", NamedTextColor.GREEN)
                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)));
    }

    /** Every bundled message stays readable wherever it is drawn. */
    @Test void bundledMessagesAreReadableOnEverySurface() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
        int checked = 0;
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isString(key)) continue;
            Component message = MiniMessage.miniMessage().deserialize(yaml.getString(key)
                    .replaceAll("<(?!/?(?:[a-z_]+|#[0-9a-fA-F]{6})>)[^>]*>", ""));
            for (Contrast.Surface surface : BACKGROUND.keySet()) {
                eachColor(Contrast.on(surface, message), color -> assertTrue(
                        Contrast.ratio(color.value(), BACKGROUND.get(surface)) >= MINIMUM.get(surface),
                        key + " on " + surface));
            }
            String bedrock = Contrast.bedrockButton(message);
            for (char code : new char[] {'7', 'f', 'e', 'a', 'b', 'd', '6'}) {
                assertFalse(bedrock.contains("§" + code), key + " uses a light color on a Bedrock button");
            }
            checked++;
        }
        assertTrue(checked > 100, "messages.yml was read");
    }

    private static void eachColor(Component component, Consumer<TextColor> check) {
        if (component.color() != null) check.accept(component.color());
        component.children().forEach(child -> eachColor(child, check));
    }
}
