package com.leadsystems.minecraft.ascension.progress;

import com.leadsystems.minecraft.ascension.ability.RankAbilityService;
import com.leadsystems.minecraft.ascension.ability.TitleAbilityListener;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.service.HuntService;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CombatProgressListenerTest {

    @Test
    void creditedPlayerKillTriggersRankAndTitleAbilitiesAndHunt() {
        ProgressTracker progress = mock(ProgressTracker.class);
        HuntService hunts = mock(HuntService.class);
        TitleAbilityListener titleAbilities = mock(TitleAbilityListener.class);
        RankAbilityService rankAbilities = mock(RankAbilityService.class);
        CombatProgressListener listener = new CombatProgressListener(Logger.getAnonymousLogger(), progress,
                mock(StorageExecutor.class), mock(PlayerRepository.class), mock(MainThread.class),
                mockConfig(), hunts, titleAbilities, rankAbilities);
        Player killer = mock(Player.class);
        UUID victim = UUID.randomUUID();

        listener.onCreditedPlayerKill(killer, victim, false, false);

        verify(rankAbilities).trigger(killer, RankAbility.Trigger.PVP_KILL);
        verify(titleAbilities).onValidPlayerKill(killer);
        verify(hunts).onValidKill(killer, victim);
    }

    @Test
    void attributionResolvesPlayerAndIndirectSources() {
        Player player = mock(Player.class);
        org.junit.jupiter.api.Assertions.assertEquals(player, Attribution.player(player));

        org.bukkit.entity.Arrow arrow = mock(org.bukkit.entity.Arrow.class);
        org.mockito.Mockito.when(arrow.getShooter()).thenReturn(player);
        org.junit.jupiter.api.Assertions.assertEquals(player, Attribution.player(arrow));

        org.bukkit.entity.TNTPrimed tnt = mock(org.bukkit.entity.TNTPrimed.class);
        org.mockito.Mockito.when(tnt.getSource()).thenReturn(player);
        org.junit.jupiter.api.Assertions.assertEquals(player, Attribution.player(tnt));

        org.bukkit.entity.Wolf wolf = mock(org.bukkit.entity.Wolf.class);
        org.mockito.Mockito.when(wolf.isTamed()).thenReturn(true);
        org.mockito.Mockito.when(wolf.getOwner()).thenReturn(player);
        org.junit.jupiter.api.Assertions.assertEquals(player, Attribution.player(wolf));

        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        org.junit.jupiter.api.Assertions.assertNull(Attribution.player(zombie));
        org.junit.jupiter.api.Assertions.assertNull(Attribution.player(null));
    }

    @SuppressWarnings("unchecked")
    private static Supplier<ConfigSnapshot> mockConfig() {
        return mock(Supplier.class);
    }
}
