package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.domain.rank.RankLadder;
import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import com.leadsystems.minecraft.ascension.domain.title.TitleCatalog;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthModifierLifecycleTest {

    @Test
    void colossusAddsFourHealthAndRemovesModifierOnUnload() {
        Player player = mock(Player.class);
        AttributeInstance max = mock(AttributeInstance.class);
        PlayerSession session = mock(PlayerSession.class);
        ConfigSnapshot config = mock(ConfigSnapshot.class);
        TitleCatalog titles = mock(TitleCatalog.class);
        AtomicReference<AttributeModifier> current = new AtomicReference<>();
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(max);
        when(max.getValue()).thenReturn(20.0);
        when(max.getModifiers()).thenAnswer(call -> current.get() == null ? List.of() : List.of(current.get()));
        doAnswer(call -> { current.set(call.getArgument(0)); return null; })
                .when(max).addTransientModifier(any(AttributeModifier.class));
        doAnswer(call -> { current.set(null); return null; })
                .when(max).removeModifier(any(AttributeModifier.class));
        when(session.usable()).thenReturn(true);
        when(session.equippedTitle()).thenReturn("colossus");
        when(session.ownedTitles()).thenReturn(java.util.Set.of("colossus"));
        when(config.titles()).thenReturn(titles);
        when(titles.byId("colossus")).thenReturn(new TitleDefinition("colossus", 23, "Colossus", "#ffffff",
                "", List.of(), "Mass", "", 0, Map.of("bonus-max-health-points", 4.0), false));
        TitleAbilityListener listener = new TitleAbilityListener(mock(SessionManager.class), () -> config,
                mock(com.leadsystems.minecraft.ascension.progress.MobClassifier.class),
                mock(com.leadsystems.minecraft.ascension.progress.PlacedBlockTracker.class),
                mock(com.leadsystems.minecraft.ascension.storage.StorageExecutor.class),
                mock(com.leadsystems.minecraft.ascension.storage.PlayerRepository.class),
                mock(com.leadsystems.minecraft.ascension.scheduler.MainThread.class), new Messages());

        listener.onLoaded(player, session, false);
        assertEquals(4.0, current.get().getAmount());
        assertEquals(new NamespacedKey("ascension", "colossus_health"), current.get().getKey());
        listener.onUnload(player, session);
        assertNull(current.get());
    }

    @Test
    void platinumRankAddsTwoHealthAndClearsIt() {
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            Player player = mock(Player.class);
            AttributeInstance max = mock(AttributeInstance.class);
            PlayerSession session = mock(PlayerSession.class);
            ConfigSnapshot config = mock(ConfigSnapshot.class);
            RankLadder ladder = mock(RankLadder.class);
            Plugin plugin = mock(Plugin.class);
            AbilityRuntime runtime = new AbilityRuntime();
            RankStage stage = new RankStage(0, "platinum_3", "Platinum III", "platinum", 3,
                    "#ffffff", "platinum_3");
            when(plugin.getName()).thenReturn("Ascension");
            when(plugin.namespace()).thenReturn("ascension"); // Paper 26.3 NamespacedKey(Plugin, String) reads namespace()
            when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(max);
            when(max.getValue()).thenReturn(20.0);
            when(session.loaded()).thenReturn(true);
            when(session.rankActive()).thenReturn(true);
            when(session.rankGrantedAt()).thenReturn(System.currentTimeMillis());
            when(session.rankUsable(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
            when(session.abilities()).thenReturn(runtime);
            when(session.rankIndex()).thenReturn(0);
            when(config.ladder()).thenReturn(ladder);
            when(ladder.size()).thenReturn(1);
            when(ladder.get(0)).thenReturn(stage);
            when(config.abilityFor(stage)).thenReturn(new RankAbility("platinum_3", "Extra heart",
                    List.of(new RankAbility.HealthBonus(2.0))));
            RankAbilityService service = new RankAbilityService(plugin, mock(SessionManager.class),
                    () -> config, new Messages());

            service.apply(player, session);
            org.mockito.ArgumentCaptor<AttributeModifier> modifier = org.mockito.ArgumentCaptor
                    .forClass(AttributeModifier.class);
            verify(max).addTransientModifier(modifier.capture());
            assertEquals(2.0, modifier.getValue().getAmount());
            assertTrue(runtime.healthBonusApplied());
            service.clear(player, session);
            verify(max).removeModifier(new NamespacedKey("ascension", "rank_health"));
            assertFalse(runtime.healthBonusApplied());
        } finally {
            org.mockbukkit.mockbukkit.MockBukkit.unmock();
        }
    }
}
