package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.progress.Attribution;
import com.leadsystems.minecraft.ascension.progress.MobClassifier;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.function.Supplier;

/**
 * Turns game events into rank-ability triggers. Every handler first checks, in O(1), whether the
 * player's active package reacts to that trigger, so players without such abilities cost nothing.
 */
public final class AbilityListener implements Listener {

    private final RankAbilityService abilities;
    private final SessionManager sessions;
    private final MobClassifier mobs;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;

    public AbilityListener(RankAbilityService abilities, SessionManager sessions, MobClassifier mobs,
                           MainThread main, Supplier<ConfigSnapshot> config) {
        this.abilities = abilities;
        this.sessions = sessions;
        this.mobs = mobs;
        this.main = main;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getDamage() <= 0) {
            return;
        }
        PlayerSession s = sessions.get(p);
        if (s == null || !s.usable()) {
            return;
        }
        if (e.getFinalDamage() > 0 && abilities.has(s, RankAbility.Trigger.DAMAGE_TAKEN)) {
            abilities.trigger(p, RankAbility.Trigger.DAMAGE_TAKEN);
        }
        if (abilities.has(s, RankAbility.Trigger.LOW_HEALTH)) {
            double before = CombatHealth.effectiveHealth(p);
            // Health and absorption are reduced after MONITOR handlers run.
            main.later(1, () -> {
                if (p.isOnline() && CombatHealth.effectiveHealth(p) < before - 1.0e-6) {
                    abilities.trigger(p, RankAbility.Trigger.LOW_HEALTH);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getFinalDamage() <= 0) {
            return;
        }
        Entity victim = e.getEntity();
        Player attacker = Attribution.player(e.getDamager());
        if (attacker != null && attacker != victim) {
            boolean hostileVictim = mobs.isHostile(victim);
            if (hostileVictim) {
                fire(attacker, RankAbility.Trigger.ATTACK_MOB);
            }
            if (hostileVictim || victim instanceof Player) {
                combat(attacker);
            }
        }
        Entity source = e.getDamageSource().getCausingEntity() instanceof Entity causing
                ? causing : e.getDamager();
        if (victim instanceof Player hurt && (attacker != null || mobs.isHostile(source))) {
            combat(hurt);
        }
    }

    private void combat(Player player) {
        PlayerSession s = sessions.get(player);
        if (s == null || !s.usable()) {
            return;
        }
        long now = System.currentTimeMillis();
        long window = config.get().settings().combatWindowSeconds() * 1000L;
        boolean entering = now - s.abilities().lastCombatAtMs() > window;
        s.abilities().setLastCombatAtMs(now);
        if (entering && abilities.has(s, RankAbility.Trigger.COMBAT_ENTER)) {
            abilities.trigger(player, RankAbility.Trigger.COMBAT_ENTER);
        }
    }

    private void fire(Player player, RankAbility.Trigger trigger) {
        PlayerSession s = sessions.get(player);
        if (s != null && s.usable() && abilities.has(s, trigger)) {
            abilities.trigger(player, trigger);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) {
            return;
        }
        Player p = e.getPlayer();
        if (p.getLocation().getPitch() >= config.get().settings().lookDownMinPitch()) {
            fire(p, RankAbility.Trigger.LOOK_DOWN_CROUCH);
        }
    }

    /** Milk, totems and other plugins can strip permanent effects; put them back next tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffectRemoved(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        EntityPotionEffectEvent.Action a = e.getAction();
        if (a != EntityPotionEffectEvent.Action.REMOVED && a != EntityPotionEffectEvent.Action.CLEARED) {
            return;
        }
        PlayerSession current = sessions.get(p);
        if (e.getCause() != EntityPotionEffectEvent.Cause.DEATH
                && e.getOldEffect() != null && e.getOldEffect().getType() == PotionEffectType.ABSORPTION
                && current != null && current.usable() && abilities.has(current, RankAbility.Trigger.LOW_HEALTH)) {
            main.later(1, () -> {
                if (p.isOnline() && !p.isDead()) {
                    abilities.trigger(p, RankAbility.Trigger.LOW_HEALTH);
                }
            });
        }
        if (e.getCause() == EntityPotionEffectEvent.Cause.PLUGIN || e.getCause() == EntityPotionEffectEvent.Cause.DEATH) {
            return; // our own removals, and death (handled on respawn)
        }
        main.later(1, () -> {
            PlayerSession s = sessions.get(p);
            if (s != null && s.usable() && p.isOnline() && !p.isDead()) {
                abilities.ensurePermanent(p, s);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        main.later(1, () -> {
            PlayerSession s = sessions.get(p);
            if (s != null && s.usable() && p.isOnline()) {
                abilities.apply(p, s);
            }
        });
    }
}
