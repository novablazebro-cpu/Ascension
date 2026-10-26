package com.leadsystems.minecraft.ascension.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;

import java.util.ArrayList;
import java.util.List;

/** Word-wraps a component into lines, keeping each word's style (tooltips do not wrap by themselves). */
public final class Wrap {

    private record Segment(String text, Style style) { }

    private Wrap() {}

    public static List<Component> lines(Component component, int width) {
        List<Segment> segments = new ArrayList<>();
        flatten(component, Style.empty(), segments);
        List<Component> lines = new ArrayList<>();
        List<Segment> line = new ArrayList<>();
        int length = 0;
        for (Segment segment : segments) {
            String[] words = segment.text().split("(?<= )");
            for (String word : words) {
                if (length > 0 && length + word.stripTrailing().length() > width) {
                    lines.add(build(line));
                    line = new ArrayList<>();
                    length = 0;
                    word = word.stripLeading();
                }
                if (!word.isEmpty()) {
                    line.add(new Segment(word, segment.style()));
                    length += word.length();
                }
            }
        }
        if (!line.isEmpty() || lines.isEmpty()) {
            lines.add(build(line));
        }
        return lines;
    }

    private static void flatten(Component component, Style inherited, List<Segment> out) {
        Style own = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            out.add(new Segment(text.content(), own));
        }
        for (Component child : component.children()) {
            flatten(child, own, out);
        }
    }

    private static Component build(List<Segment> segments) {
        var builder = Component.text();
        for (int i = 0; i < segments.size(); i++) {
            String text = i == segments.size() - 1 ? segments.get(i).text().stripTrailing() : segments.get(i).text();
            builder.append(Component.text(text, segments.get(i).style()));
        }
        return builder.build();
    }
}
