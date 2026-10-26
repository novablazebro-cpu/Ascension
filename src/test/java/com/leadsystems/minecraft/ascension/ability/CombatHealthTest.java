package com.leadsystems.minecraft.ascension.ability;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CombatHealthTest {

    @Test
    void goldenAppleHeartsCountTowardLowHealthThreshold() {
        Player player = player(20, 7, 4);
        assertEquals(0.55, CombatHealth.ratio(player), 1.0e-9);
        when(player.getAbsorptionAmount()).thenReturn(0.0);
        assertEquals(0.35, CombatHealth.ratio(player), 1.0e-9);
    }

    @Test
    void maxHealthBonusesChangeTheFractionButNotTheYellowHeartValue() {
        Player player = player(24, 9, 2);
        assertEquals(11.0 / 24.0, CombatHealth.ratio(player), 1.0e-9);
        when(player.getAbsorptionAmount()).thenReturn(0.0);
        assertEquals(0.375, CombatHealth.ratio(player), 1.0e-9);
    }

    @Test
    void absorptionProtectsLowHealthTargetsFromFinisher() {
        LivingEntity victim = mock(LivingEntity.class);
        when(victim.getHealth()).thenReturn(6.0);
        when(victim.getAbsorptionAmount()).thenReturn(4.0);
        assertEquals(10.0, CombatHealth.effectiveHealth(victim));
        when(victim.getAbsorptionAmount()).thenReturn(2.0);
        assertEquals(8.0, CombatHealth.effectiveHealth(victim));
    }

    private static Player player(double maxHealth, double health, double absorption) {
        Player player = mock(Player.class);
        AttributeInstance max = mock(AttributeInstance.class);
        when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(max);
        when(max.getValue()).thenReturn(maxHealth);
        when(player.getHealth()).thenReturn(health);
        when(player.getAbsorptionAmount()).thenReturn(absorption);
        return player;
    }
}
