package com.leadsystems.minecraft.ascension.ability;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Health thresholds count temporary absorption hearts, but not armor or resistance. */
final class CombatHealth {

    private CombatHealth() { }

    static double effectiveHealth(LivingEntity entity) {
        return Math.max(0.0, entity.getHealth()) + Math.max(0.0, entity.getAbsorptionAmount());
    }

    static double ratio(Player player) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double capacity = max == null ? 20.0 : max.getValue();
        if (!Double.isFinite(capacity) || capacity <= 0) {
            return 1.0;
        }
        return effectiveHealth(player) / capacity;
    }
}
