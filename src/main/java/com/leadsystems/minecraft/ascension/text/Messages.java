package com.leadsystems.minecraft.ascension.text;

import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Renders messages.yml (MiniMessage). Placeholder values are inserted as unparsed text, so player
 * names can never inject formatting or click events.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private volatile Map<String, String> templates = Map.of();

    /** Replaces the templates (config reload). */
    public void load(Map<String, String> newTemplates) {
        this.templates = Map.copyOf(newTemplates);
    }

    /** A message without the prefix. Unknown keys render as a visible marker instead of failing. */
    public Component get(String key, TagResolver... placeholders) {
        String template = templates.get(key);
        if (template == null) {
            return Component.text("[missing message: " + key + "]", NamedTextColor.WHITE);
        }
        return neutral(MM.deserialize(template, placeholders));
    }

    /** Sends a prefixed chat message. */
    public void send(CommandSender to, String key, TagResolver... placeholders) {
        to.sendMessage(prefix().append(get(key, placeholders)));
    }

    /** Shows a message on the action bar. */
    public void actionBar(Player to, String key, TagResolver... placeholders) {
        to.sendActionBar(get(key, placeholders));
    }

    public Component prefix() {
        String p = templates.get("prefix");
        return p == null ? Component.empty() : neutral(MM.deserialize(p));
    }

    /** Parses an arbitrary MiniMessage string (menu labels). */
    public static Component parse(String miniMessage, TagResolver... placeholders) {
        return neutral(MM.deserialize(miniMessage, placeholders));
    }

    /** A plain-text placeholder. */
    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** A component placeholder. */
    public static TagResolver ph(String name, Component value) {
        return Placeholder.component(name, value);
    }

    /** A rank stage name in the plugin's neutral text style. */
    public static Component rank(RankStage stage) {
        return Component.text(stage.name(), NamedTextColor.WHITE);
    }

    /** Legacy section-sign string, for Bedrock forms (Cumulus takes plain strings). */
    public static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Removes text colors, including nested labels, without changing content or actions. */
    public static Component withoutColor(Component component) {
        return component.color(null).children(component.children().stream().map(Messages::withoutColor).toList());
    }

    /** Retains content and actions while applying the same neutral color to nested text. */
    public static Component neutral(Component component) {
        return withoutColor(component).color(NamedTextColor.WHITE);
    }

    /** Item names/lore are italic by default; menus want them upright. */
    public static Component upright(Component component) {
        return neutral(component).decoration(TextDecoration.ITALIC, false);
    }
}
