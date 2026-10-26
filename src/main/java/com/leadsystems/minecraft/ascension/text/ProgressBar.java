package com.leadsystems.minecraft.ascension.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** A compact colored bar with the numbers spelled out, so plain-text Bedrock forms stay meaningful. */
public final class ProgressBar {

    private static final int SEGMENTS = 20;
    /** Tags a bar so text-only renderers can show just its percentage. */
    private static final String MARKER = "ascension:progress-bar";

    private ProgressBar() {}

    public static Component of(long current, long required) {
        long clamped = Math.max(0, Math.min(current, required));
        int filled = required <= 0 ? SEGMENTS : (int) (clamped * SEGMENTS / required);
        int percent = required <= 0 ? 100 : (int) (clamped * 100 / required);
        return Component.text()
                .insertion(MARKER)
                .append(Component.text("|".repeat(filled), NamedTextColor.GREEN))
                .append(Component.text("|".repeat(SEGMENTS - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + percent + "%", percent >= 100 ? NamedTextColor.GREEN : NamedTextColor.GRAY))
                .build();
    }

    /** Bedrock forms cannot color the bar, so a bar becomes its percentage; other text is unchanged. */
    public static Component textOnly(Component component) {
        if (!MARKER.equals(component.insertion()) || component.children().isEmpty()) return component;
        return Component.text(PlainTextComponentSerializer.plainText().serialize(component.children().getLast()).trim());
    }
}
