package com.leadsystems.minecraft.ascension.progress;

import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;

/**
 * Resolves which player is responsible for a damaging entity: the player themselves, the shooter
 * of a projectile, the owner of a tamed pet, the igniter of TNT, or the source of a lingering cloud.
 */
public final class Attribution {

    private Attribution() {}

    /** The responsible online player, or null. */
    public static Player player(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        if (damager instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player p) {
            return p;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) {
            return p;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) {
            return p;
        }
        return null;
    }
}
