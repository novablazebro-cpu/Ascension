package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.config.Settings;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.progress.MobClassifier;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbilityListenerTest {

    @Test
    void hostileProjectileSourceEntersCombat() {
        RankAbilityService abilities = mock(RankAbilityService.class);
        SessionManager sessions = mock(SessionManager.class);
        MobClassifier mobs = mock(MobClassifier.class);
        AbilityListener listener = new AbilityListener(abilities, sessions, mobs, mock(MainThread.class),
                configWithCombatWindow(15));
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        Player victim = mock(Player.class);
        Entity projectile = mock(Entity.class);
        Entity hostileShooter = mock(Entity.class);
        DamageSource damageSource = mock(DamageSource.class);
        PlayerSession session = mock(PlayerSession.class);

        when(event.getFinalDamage()).thenReturn(2.0);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(projectile);
        when(event.getDamageSource()).thenReturn(damageSource);
        when(damageSource.getCausingEntity()).thenReturn(hostileShooter);
        when(mobs.isHostile(hostileShooter)).thenReturn(true);
        when(sessions.get(victim)).thenReturn(session);
        when(session.usable()).thenReturn(true);
        when(session.abilities()).thenReturn(new AbilityRuntime());
        when(abilities.has(session, RankAbility.Trigger.COMBAT_ENTER)).thenReturn(true);

        listener.onHit(event);

        verify(abilities).trigger(victim, RankAbility.Trigger.COMBAT_ENTER);
    }

    @Test
    void zeroDamageHitDoesNotTriggerAbilities() {
        RankAbilityService abilities = mock(RankAbilityService.class);
        AbilityListener listener = new AbilityListener(abilities, mock(SessionManager.class),
                mock(MobClassifier.class), mock(MainThread.class), configWithCombatWindow(15));
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getFinalDamage()).thenReturn(0.0);

        listener.onHit(event);

        verify(abilities, never()).trigger(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static Supplier<ConfigSnapshot> configWithCombatWindow(int seconds) {
        ConfigSnapshot snapshot = mock(ConfigSnapshot.class);
        Settings settings = mock(Settings.class);
        when(settings.combatWindowSeconds()).thenReturn(seconds);
        when(snapshot.settings()).thenReturn(settings);
        return () -> snapshot;
    }
}
