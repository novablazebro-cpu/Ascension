package com.leadsystems.minecraft.ascension.book;

import com.leadsystems.minecraft.ascension.config.Settings;
import com.leadsystems.minecraft.ascension.domain.book.BookIdentity;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.text.Messages;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Creates and identifies Chronicle items. Identity lives only in the PersistentDataContainer
 * (marker, schema, owner, nonce); names and lore are cosmetic and never trusted.
 */
public final class BookItems {

    private static final UUID NOBODY = new UUID(0L, 0L);

    private final NamespacedKey marker;
    private final NamespacedKey owner;
    private final NamespacedKey nonce;
    private final NamespacedKey schema;

    public BookItems(Plugin plugin) {
        this.marker = new NamespacedKey(plugin, "chronicle");
        this.owner = new NamespacedKey(plugin, "owner");
        this.nonce = new NamespacedKey(plugin, "nonce");
        this.schema = new NamespacedKey(plugin, "schema");
    }

    /** A new Chronicle for the session's owner carrying the session's committed nonce. */
    @SuppressWarnings("deprecation") // addPage(String): retained for the Java written-book version
    public ItemStack create(PlayerSession session, Settings settings, Messages messages) {
        // Bedrock opens written books client-side before Geyser can reliably forward the use event.
        // A plain book has no client-side screen, so its use reaches BookListener and opens a form.
        ItemStack book = new ItemStack(session.bedrock() ? Material.BOOK : Material.WRITTEN_BOOK);
        book.editMeta(meta -> {
            if (meta instanceof BookMeta bookMeta) {
                bookMeta.setTitle(truncate(settings.bookTitle(), 32));
                bookMeta.setAuthor(settings.bookAuthor());
                bookMeta.setGeneration(BookMeta.Generation.COPY_OF_COPY);
                bookMeta.addPage(Messages.plain(messages.get("book-page")));
            }
            meta.displayName(Messages.upright(Component.text(settings.bookTitle(), NamedTextColor.WHITE)));
            meta.lore(List.of(
                    Messages.upright(Component.text("Bound to " + session.name(), NamedTextColor.WHITE)),
                    Messages.upright(Component.text("Use to open the Ascension menu", NamedTextColor.WHITE))));
            meta.setEnchantmentGlintOverride(true);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(marker, PersistentDataType.BYTE, (byte) 1);
            pdc.set(schema, PersistentDataType.INTEGER, BookIdentity.CURRENT_SCHEMA);
            pdc.set(owner, PersistentDataType.STRING, session.uuid().toString());
            pdc.set(nonce, PersistentDataType.STRING, session.bookNonce());
        });
        return book;
    }

    /** Restyles an existing valid book without rotating its nonce or changing its identity. */
    @SuppressWarnings("deprecation") // same legacy page API used when creating Java written books
    public ItemStack restyle(ItemStack item, PlayerSession session, Settings settings) {
        if (!isValidFor(item, session)) return item;
        var meta = item.getItemMeta();
        Component name = Messages.upright(Component.text(settings.bookTitle(), NamedTextColor.WHITE));
        List<Component> lore = List.of(
                Messages.upright(Component.text("Bound to " + session.name(), NamedTextColor.WHITE)),
                Messages.upright(Component.text("Use to open the Ascension menu", NamedTextColor.WHITE)));
        List<String> pages = meta instanceof BookMeta bookMeta
                ? bookMeta.getPages().stream()
                        .map(page -> Messages.plain(LegacyComponentSerializer.legacySection().deserialize(page)))
                        .toList() : List.of();
        boolean plainPages = !(meta instanceof BookMeta bookMeta) || pages.equals(bookMeta.getPages());
        if (meta.hasEnchantmentGlintOverride() && meta.getEnchantmentGlintOverride()
                && name.equals(meta.displayName()) && lore.equals(meta.lore()) && plainPages) return item;
        ItemStack styled = item.clone();
        styled.editMeta(updated -> {
            updated.displayName(name);
            updated.lore(lore);
            updated.setEnchantmentGlintOverride(true);
            if (updated instanceof BookMeta bookMeta && !plainPages) bookMeta.setPages(pages);
        });
        // Paper can omit a redundant true override on a written book that already glints.
        // Retain the original stack when applying the cosmetics makes no effective change.
        return styled.equals(item) ? item : styled;
    }

    /** True for any item carrying the Chronicle marker (valid or not). Cheap: no meta clone. */
    public boolean isChronicle(ItemStack item) {
        if (item == null || (item.getType() != Material.WRITTEN_BOOK && item.getType() != Material.BOOK)) {
            return false;
        }
        return item.getPersistentDataContainer().has(marker);
    }

    /** The identity stored on the item, or null if it is not a Chronicle. */
    public BookIdentity read(ItemStack item) {
        if (!isChronicle(item)) {
            return null;
        }
        PersistentDataContainerView pdc = item.getPersistentDataContainer();
        String rawOwner = pdc.get(owner, PersistentDataType.STRING);
        UUID ownerId;
        try {
            ownerId = rawOwner == null ? NOBODY : UUID.fromString(rawOwner);
        } catch (IllegalArgumentException e) {
            ownerId = NOBODY; // forged/corrupt: treated as foreign and removed
        }
        String n = pdc.get(nonce, PersistentDataType.STRING);
        Integer s = pdc.get(schema, PersistentDataType.INTEGER);
        return new BookIdentity(ownerId, n == null ? "" : n, s == null ? 0 : s);
    }

    /** True if the item is this session's currently valid Chronicle. */
    public boolean isValidFor(ItemStack item, PlayerSession session) {
        BookIdentity id = read(item);
        return id != null
                && item.getType() == (session.bedrock() ? Material.BOOK : Material.WRITTEN_BOOK)
                && id.owner().equals(session.uuid())
                && id.nonce().equals(session.bookNonce())
                && id.schema() == BookIdentity.CURRENT_SCHEMA;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
