package com.leadsystems.minecraft.ascension.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps colored text readable on the surface the client draws it on. Gray text vanishes on the
 * light chest title bar and on Bedrock form buttons, and dark text vanishes in item tooltips; this
 * moves each configured color toward white or black until it meets a WCAG contrast ratio.
 */
public final class Contrast {

    /** Where text is drawn: background color, minimum contrast ratio and default text color. */
    public enum Surface {
        /** Item names and lore: the near-black tooltip. */
        TOOLTIP(0x100010, 4.5, NamedTextColor.WHITE),
        /** Chat and action bar: translucent black over the world. */
        CHAT(0x000000, 3.0, null),
        /** Chest window titles: the light gray panel (vanilla draws default text in #404040). */
        CHEST_TITLE(0xC6C6C6, 4.5, null),
        /** Bedrock form buttons: light gray. Only the 16 named colors survive the legacy format. */
        BEDROCK_BUTTON(0xC6C6C6, 3.0, null);

        private final int background;
        private final double minimumRatio;
        private final TextColor defaultColor;

        Surface(int background, double minimumRatio, TextColor defaultColor) {
            this.background = background;
            this.minimumRatio = minimumRatio;
            this.defaultColor = defaultColor;
        }

        boolean dark() {
            return luminance(background) < 0.5;
        }
    }

    private Contrast() {}

    /** Returns the component with every explicit color made readable on the surface. */
    public static Component on(Surface surface, Component component) {
        Component root = component.color() == null && surface.defaultColor != null
                ? component.color(surface.defaultColor) : component;
        return adjust(root, surface);
    }

    private static Component adjust(Component component, Surface surface) {
        Component adjusted = component.color() == null ? component
                : component.color(readable(component.color(), surface));
        List<Component> children = new ArrayList<>(adjusted.children().size());
        for (Component child : adjusted.children()) {
            children.add(adjust(child, surface));
        }
        return adjusted.children(children);
    }

    /** The color itself when it already contrasts enough, otherwise the nearest color that does. */
    public static TextColor readable(TextColor color, Surface surface) {
        int rgb = color.value();
        if (ratio(rgb, surface.background) >= surface.minimumRatio) {
            return color;
        }
        int target = surface.dark() ? 0xFFFFFF : 0x000000;
        double low = 0;
        double high = 1;
        for (int i = 0; i < 16; i++) {
            double mid = (low + high) / 2;
            if (ratio(mix(rgb, target, mid), surface.background) >= surface.minimumRatio) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return TextColor.color(mix(rgb, target, high));
    }

    /**
     * Legacy section-sign text for a Bedrock form button. Each color becomes its nearest named color
     * and is dropped (client default) unless that named color is readable on the light button.
     * Decorations are dropped: Bedrock reads some Java format codes as material colors.
     */
    public static String bedrockButton(Component component) {
        StringBuilder out = new StringBuilder();
        flatten(component, null, out, new NamedTextColor[1]);
        return out.toString();
    }

    private static void flatten(Component component, NamedTextColor inherited, StringBuilder out,
                                NamedTextColor[] emitted) {
        NamedTextColor color = inherited;
        if (component.color() != null) {
            NamedTextColor named = NamedTextColor.nearestTo(component.color());
            color = ratio(named.value(), Surface.BEDROCK_BUTTON.background) >= Surface.BEDROCK_BUTTON.minimumRatio
                    ? named : null;
        }
        String text = component instanceof TextComponent t ? t.content()
                : PlainTextComponentSerializer.plainText().serialize(component.children(List.of()));
        if (!text.isEmpty()) {
            if (color != emitted[0]) {
                out.append(color == null ? "§r" : "§" + legacyCode(color));
                emitted[0] = color;
            }
            out.append(text);
        }
        for (Component child : component.children()) {
            flatten(child, color, out, emitted);
        }
    }

    private static char legacyCode(NamedTextColor color) {
        String codes = "0123456789abcdef";
        List<NamedTextColor> order = List.of(NamedTextColor.BLACK, NamedTextColor.DARK_BLUE,
                NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA, NamedTextColor.DARK_RED,
                NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY, NamedTextColor.DARK_GRAY,
                NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA, NamedTextColor.RED,
                NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE);
        return codes.charAt(order.indexOf(color));
    }

    /** WCAG contrast ratio between two sRGB colors (1 to 21). */
    public static double ratio(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** WCAG relative luminance of an sRGB color. */
    static double luminance(int rgb) {
        return 0.2126 * channel(rgb >> 16) + 0.7152 * channel(rgb >> 8) + 0.0722 * channel(rgb);
    }

    private static double channel(int value) {
        double c = (value & 0xFF) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int mix(int from, int to, double amount) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * amount));
        int g = (int) Math.round(((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * amount));
        int b = (int) Math.round((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * amount));
        return (r << 16) | (g << 8) | b;
    }
}
