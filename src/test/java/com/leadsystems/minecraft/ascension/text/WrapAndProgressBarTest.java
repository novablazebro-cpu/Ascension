package com.leadsystems.minecraft.ascension.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WrapAndProgressBarTest {

    @Test void wrapsAtWordsAndKeepsEachWordsColor() {
        Component text = Component.text("Strikes the nearest ", NamedTextColor.GRAY)
                .append(Component.text("enemy", NamedTextColor.RED))
                .append(Component.text(" with lightning that chains to nearby targets.", NamedTextColor.GRAY));
        List<Component> lines = Wrap.lines(text, 20);
        assertTrue(lines.size() > 1);
        assertTrue(lines.stream().allMatch(line -> Messages.plain(line).length() <= 20), lines.toString());
        assertEquals(Messages.plain(text), String.join(" ", lines.stream().map(Messages::plain).toList()));
        Component enemy = lines.stream().flatMap(line -> line.children().stream())
                .filter(part -> Messages.plain(part).contains("enemy")).findFirst().orElseThrow();
        assertEquals(NamedTextColor.RED, enemy.color());
    }

    @Test void shortTextAndOverlongWordsStayOnOneLine() {
        assertEquals(1, Wrap.lines(Component.text("Help"), 42).size());
        assertEquals("Supercalifragilistic", Messages.plain(Wrap.lines(Component.text("Supercalifragilistic"), 5).getFirst()));
        assertEquals(1, Wrap.lines(Component.empty(), 42).size());
    }

    @Test void progressBarSpellsOutThePercentAndClamps() {
        assertEquals("||||||||||||||||||||" + " 50%", Messages.plain(ProgressBar.of(5, 10)));
        assertEquals(" 100%", Messages.plain(ProgressBar.of(15, 10)).substring(20));
        assertEquals(" 0%", Messages.plain(ProgressBar.of(-3, 10)).substring(20));
        assertEquals(" 100%", Messages.plain(ProgressBar.of(0, 0)).substring(20));
        assertEquals(NamedTextColor.GREEN, ProgressBar.of(5, 10).children().getFirst().color());
        assertEquals(10, Messages.plain(ProgressBar.of(5, 10).children().getFirst()).length());
    }

    @Test void textOnlyRenderersSeeJustThePercentage() {
        assertEquals("50%", Messages.plain(ProgressBar.textOnly(ProgressBar.of(5, 10))));
        Component other = Component.text("Kill 10 zombies");
        assertSame(other, ProgressBar.textOnly(other));
    }
}
