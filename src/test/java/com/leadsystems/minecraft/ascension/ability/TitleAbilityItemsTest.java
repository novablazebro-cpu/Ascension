package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.AscensionPlugin;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the owner-bound Ascension Relic lifecycle through the real plugin runtime. */
class TitleAbilityItemsTest {

    private ServerMock server;
    private AscensionPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(AscensionPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void taggedLegacyStickMigratesToGlowingAmethystRelic() {
        PlayerMock player = join("RelicMigration");
        equipManualTitle(player);
        ItemStack legacy = relic(player).clone();
        legacy.setType(Material.STICK);
        legacy.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "title_ability_schema"), PersistentDataType.INTEGER, 1));
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (plugin.abilityItems().isActivator(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
        player.getInventory().setItem(0, legacy);

        plugin.abilityItems().onLoaded(player, plugin.sessions().get(player), false);

        ItemStack relic = relic(player);
        assertEquals(Material.AMETHYST_SHARD, relic.getType());
        assertTrue(relic.getItemMeta().hasEnchantmentGlintOverride());
        assertEquals(2, relic.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(plugin, "title_ability_schema"), PersistentDataType.INTEGER));
    }

    @Test
    void invalidOwnerAndStackedRelicsCannotActivate() {
        PlayerMock player = join("RelicOwner");
        equipManualTitle(player);
        PlayerSession session = plugin.sessions().get(player);
        ItemStack wrongOwner = relic(player).clone();
        wrongOwner.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "title_ability_owner"), PersistentDataType.STRING, "wrong-owner"));
        ItemStack stacked = relic(player).clone();
        stacked.setAmount(2);

        assertFalse(plugin.abilityItems().isValidFor(wrongOwner, session));
        assertFalse(plugin.abilityItems().isValidFor(stacked, session));
    }

    @Test
    void fullInventoryRetriesIssuingTheRelicWhenSpaceReturns() {
        PlayerMock player = join("RelicRetry");
        equipManualTitle(player);
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.COBBLESTONE, 64));
        }

        plugin.abilityItems().onLoaded(player, plugin.sessions().get(player), false);
        assertFalse(hasRelic(player));

        player.getInventory().setItem(0, null);
        plugin.abilityItems().onLoaded(player, plugin.sessions().get(player), false);
        assertTrue(hasRelic(player));
    }

    @Test
    void duplicateInteractEventsProduceOneActivation() {
        PlayerMock player = join("RelicDupe");
        equipManualTitle(player);
        PlayerSession session = plugin.sessions().get(player);
        AtomicInteger activations = new AtomicInteger();
        Messages messages = new Messages();
        messages.load(plugin.currentConfig().messages());
        TitleAbilityItems probe = new TitleAbilityItems(plugin, plugin.sessions(), plugin::currentConfig,
                messages, (owner, live) -> {
                    assertEquals(player, owner);
                    assertEquals(session, live);
                    activations.incrementAndGet();
                }, new MainThread(plugin));
        ItemStack boundRelic = relic(player);
        player.getInventory().setItemInMainHand(boundRelic);
        player.getInventory().setItemInOffHand(boundRelic.clone());
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, boundRelic,
                null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND);
        PlayerInteractEvent offhand = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInOffHand(), null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.OFF_HAND);

        probe.onUse(event);
        probe.onUse(offhand);
        probe.onEntityInteract(new org.bukkit.event.player.PlayerInteractEntityEvent(player, mock(Entity.class), EquipmentSlot.HAND));
        probe.onUse(event);

        assertEquals(1, activations.get());
        assertEquals(Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void ordinaryShardWithTheRelicNameIsNeverRemovedOrAccepted() {
        PlayerMock player = join("OrdinaryShard");
        equipManualTitle(player);
        ItemStack ordinary = new ItemStack(Material.AMETHYST_SHARD);
        ordinary.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Ascension Relic")));
        player.getInventory().setItem(10, ordinary);

        plugin.abilityItems().onLoaded(player, plugin.sessions().get(player), false);

        assertEquals(ordinary, player.getInventory().getItem(10));
        assertFalse(plugin.abilityItems().isActivator(ordinary));
    }

    @Test
    void relicNameAndEveryLoreLineStayReadableOnTheTooltip() {
        PlayerMock player = join("PlainRelic");
        equipManualTitle(player);
        var meta = relic(player).getItemMeta();
        java.util.List<net.kyori.adventure.text.Component> lines = new java.util.ArrayList<>(meta.lore());
        lines.add(meta.displayName());
        for (var line : lines) {
            assertNotNull(line.color(), "every relic line has an explicit color");
            assertTrue(com.leadsystems.minecraft.ascension.text.Contrast.ratio(line.color().value(), 0x100010) >= 4.5,
                    "unreadable relic line: " + com.leadsystems.minecraft.ascension.text.Messages.plain(line));
            assertEquals(net.kyori.adventure.text.format.TextDecoration.State.FALSE,
                    line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC));
        }
    }

    @Test
    void titleSwitchClearsTaggedRelicFromCursorAndOpenContainer() {
        PlayerMock player = join("RelicCursor");
        equipManualTitle(player);
        ItemStack oldRelic = relic(player).clone();
        PlayerMock visiblePlayer = org.mockito.Mockito.spy(player);
        var view = mock(org.bukkit.inventory.InventoryView.class);
        var top = mock(org.bukkit.inventory.Inventory.class);
        org.mockito.Mockito.doReturn(view).when(visiblePlayer).getOpenInventory();
        org.mockito.Mockito.when(view.getCursor()).thenReturn(oldRelic);
        org.mockito.Mockito.when(view.getTopInventory()).thenReturn(top);
        org.mockito.Mockito.when(top.getType()).thenReturn(org.bukkit.event.inventory.InventoryType.CHEST);
        org.mockito.Mockito.when(top.getContents()).thenReturn(new ItemStack[] {oldRelic.clone()});

        plugin.abilityItems().onLoaded(visiblePlayer, plugin.sessions().get(player), true);

        org.mockito.Mockito.verify(view).setCursor(null);
        org.mockito.Mockito.verify(top).setItem(0, null);
        assertTrue(hasRelic(player), "the current title should still receive one valid relic");
    }

    private PlayerMock join(String name) {
        PlayerMock player = server.addPlayer(name);
        for (int i = 0; i < 6; i++) {
            plugin.storage().submit("test-sync", connection -> null).join();
            server.getScheduler().performTicks(2);
        }
        return player;
    }

    private void equipManualTitle(PlayerMock player) {
        player.setOp(true);
        assertTrue(player.performCommand("title give " + player.getName() + " ascendant"));
        settle();
        assertTrue(player.performCommand("title equip ascendant"));
        settle();
        assertEquals("ascendant", plugin.sessions().get(player).equippedTitle());
        assertTrue(hasRelic(player));
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            plugin.storage().submit("test-sync", connection -> null).join();
            server.getScheduler().performTicks(2);
        }
    }

    private boolean hasRelic(PlayerMock player) {
        return player.getInventory().all(Material.AMETHYST_SHARD).values().stream()
                .anyMatch(plugin.abilityItems()::isActivator);
    }

    private ItemStack relic(PlayerMock player) {
        return player.getInventory().all(Material.AMETHYST_SHARD).values().stream()
                .filter(plugin.abilityItems()::isActivator)
                .findFirst().orElseThrow();
    }
}
