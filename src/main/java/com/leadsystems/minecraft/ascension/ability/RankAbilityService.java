package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.domain.rank.RankStage;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Applies rank ability packages.
 * <ul>
 *   <li>Pulses ("Haste I for 5 s every 25 s") live in one priority queue driven by the plugin's
 *       single scheduler - no per-player task. Re-applying bumps the player's generation, which
 *       silently invalidates stale queue entries.</li>
 *   <li>Triggered effects use the session's persisted cooldowns (key {@code rank.<id>.<n>}), so
 *       relogging or restarting never resets them. Cooldowns of 60 s or more are saved at once.</li>
 *   <li>Health bonuses are <i>transient</i> attribute modifiers: never written to player data, so
 *       a crash can never leave a stale bonus behind.</li>
 *   <li>Effects never override a stronger/longer effect the player already has, and only effects
 *       Ascension applied are removed later.</li>
 * </ul>
 */
public final class RankAbilityService implements SessionHook {

    private record Pulse(long dueMs, UUID uuid, int generation, RankAbility.EffectPulse pulse) {}

    private final SessionManager sessions;
    private final Supplier<ConfigSnapshot> config;
    private final Messages messages;
    private final NamespacedKey healthKey;
    private final PriorityQueue<Pulse> pulses = new PriorityQueue<>(Comparator.comparingLong(Pulse::dueMs));

    public RankAbilityService(Plugin plugin, SessionManager sessions, Supplier<ConfigSnapshot> config, Messages messages) {
        this.sessions = sessions;
        this.config = config;
        this.messages = messages;
        this.healthKey = new NamespacedKey(plugin, "rank_health");
    }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        apply(player, session);
    }

    @Override
    public void onUnload(Player player, PlayerSession session) {
        clear(player, session);
    }

    // ------------------------------------------------------------------ apply / clear

    /** (Re)applies the player's current package(s). Safe to call repeatedly. */
    public void apply(Player player, PlayerSession session) {
        clear(player, session);
        if (!session.loaded() || !session.rankUsable(System.currentTimeMillis())) {
            return;
        }
        int generation = session.abilities().nextGeneration();
        List<RankAbility> packages = packagesFor(session);
        session.abilities().setActive(packages);
        long now = System.currentTimeMillis();
        double bonus = 0;
        for (RankAbility pkg : packages) {
            for (RankAbility.Component c : pkg.components()) {
                switch (c) {
                    case RankAbility.PermanentEffect pe -> applyEffects(player, session, pe.effects(), PotionEffect.INFINITE_DURATION);
                    case RankAbility.EffectPulse ep -> pulses.add(new Pulse(now + 1000L, session.uuid(), generation, ep));
                    case RankAbility.HealthBonus hb -> bonus += hb.amount();
                    case RankAbility.TriggeredEffect te -> {
                        // event-driven; see trigger()
                    }
                }
            }
        }
        if (bonus > 0) {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                // clear() above removed any previous modifier we applied, so adding cannot collide.
                max.addTransientModifier(new AttributeModifier(healthKey, bonus,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                session.abilities().setHealthBonusApplied(true);
            }
        }
    }

    /** Removes everything Ascension applied (rank change, quit, disable). */
    public void clear(Player player, PlayerSession session) {
        AbilityRuntime rt = session.abilities();
        rt.nextGeneration();
        int tick = Bukkit.getCurrentTick();
        for (Map.Entry<PotionEffectType, AbilityRuntime.AppliedEffect> e : rt.applied().entrySet()) {
            PotionEffect current = player.getPotionEffect(e.getKey());
            AbilityRuntime.AppliedEffect ours = e.getValue();
            if (current == null || current.getAmplifier() != ours.amplifier()) {
                continue; // gone, or replaced by someone else's effect
            }
            boolean stillOurs = ours.infinite()
                    ? current.isInfinite()
                    : !current.isInfinite() && current.getDuration() <= ours.endTick() - tick + 2;
            if (stillOurs) {
                player.removePotionEffect(e.getKey());
            }
        }
        rt.applied().clear();
        rt.setActive(List.of());
        if (rt.healthBonusApplied()) {
            rt.setHealthBonusApplied(false);
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                max.removeModifier(healthKey);
                if (player.getHealth() > max.getValue()) {
                    player.setHealth(max.getValue());
                }
            }
        }
    }

    private List<RankAbility> packagesFor(PlayerSession session) {
        ConfigSnapshot cfg = config.get();
        List<RankAbility> out = new ArrayList<>();
        int top = Math.min(session.rankIndex(), cfg.ladder().size() - 1);
        int from = cfg.stackPreviousAbilities() ? 0 : top;
        for (int i = from; i <= top; i++) {
            RankStage stage = cfg.ladder().get(i);
            RankAbility pkg = cfg.abilityFor(stage);
            if (pkg != null) {
                out.add(pkg);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ pulses

    /** Scheduler job (every second): applies due pulses. Only due entries are touched. */
    public void tickPulses() {
        long now = System.currentTimeMillis();
        List<Pulse> again = new ArrayList<>();
        while (!pulses.isEmpty() && pulses.peek().dueMs() <= now) {
            Pulse pulse = pulses.poll();
            PlayerSession s = sessions.get(pulse.uuid());
            if (s == null || !s.rankUsable(now) || s.abilities().generation() != pulse.generation()) {
                continue; // stale: player left or abilities were re-applied
            }
            Player p = Bukkit.getPlayer(pulse.uuid());
            if (p == null) {
                continue;
            }
            if (!p.isDead()) {
                applyEffects(p, s, pulse.pulse().effects(), pulse.pulse().durationSeconds() * 20);
            }
            long next = pulse.dueMs() + pulse.pulse().intervalSeconds() * 1000L;
            if (next <= now) {
                next = now + pulse.pulse().intervalSeconds() * 1000L; // server lagged: don't burst
            }
            again.add(new Pulse(next, pulse.uuid(), pulse.generation(), pulse.pulse()));
        }
        pulses.addAll(again);
    }

    // ------------------------------------------------------------------ triggers

    /** True if the player's active packages react to this trigger (cheap pre-check). */
    public boolean has(PlayerSession session, RankAbility.Trigger trigger) {
        if (!session.rankUsable(System.currentTimeMillis())) return false;
        for (RankAbility pkg : session.abilities().active()) {
            for (RankAbility.Component c : pkg.components()) {
                if (c instanceof RankAbility.TriggeredEffect te && te.trigger() == trigger) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Fires matching triggered effects that are off cooldown. */
    public void trigger(Player player, RankAbility.Trigger trigger) {
        PlayerSession s = sessions.get(player);
        if (s == null || !s.usable() || !s.rankUsable(System.currentTimeMillis()) || player.isDead()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (RankAbility pkg : s.abilities().active()) {
            List<RankAbility.Component> comps = pkg.components();
            for (int i = 0; i < comps.size(); i++) {
                if (!(comps.get(i) instanceof RankAbility.TriggeredEffect te) || te.trigger() != trigger) {
                    continue;
                }
                if (trigger == RankAbility.Trigger.LOW_HEALTH && CombatHealth.ratio(player) >= te.threshold()) {
                    continue;
                }
                String key = "rank." + pkg.id() + "." + i;
                if (!s.cooldowns().isReady(key, now)) {
                    if (trigger == RankAbility.Trigger.LOOK_DOWN_CROUCH) {
                        messages.actionBar(player, "ability-cooldown",
                                Messages.ph("ability", pkg.description()),
                                Messages.ph("seconds", (s.cooldowns().remainingMillis(key, now) + 999) / 1000));
                    }
                    continue;
                }
                applyEffects(player, s, te.effects(), te.durationSeconds() * 20);
                s.cooldowns().start(key, now, te.cooldownSeconds() * 1000L);
                if (te.cooldownSeconds() >= 60) {
                    sessions.flushNow(s); // long cooldowns must survive a crash right away
                }
                if (trigger == RankAbility.Trigger.LOOK_DOWN_CROUCH) {
                    messages.actionBar(player, "ability-activated", Messages.ph("ability", pkg.description()));
                }
            }
        }
    }

    /** Re-applies permanent effects if something removed them (milk, totem, other plugins). */
    public void ensurePermanent(Player player, PlayerSession session) {
        if (!session.rankUsable(System.currentTimeMillis())) return;
        for (RankAbility pkg : session.abilities().active()) {
            for (RankAbility.Component c : pkg.components()) {
                if (c instanceof RankAbility.PermanentEffect pe) {
                    applyEffects(player, session, pe.effects(), PotionEffect.INFINITE_DURATION);
                }
            }
        }
    }

    // ------------------------------------------------------------------ effects

    private void applyEffects(Player player, PlayerSession session, List<RankAbility.EffectSpec> specs, int ticks) {
        boolean particles = config.get().settings().effectParticles();
        int tick = Bukkit.getCurrentTick();
        for (RankAbility.EffectSpec spec : specs) {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(spec.effectKey()));
            if (type == null) {
                continue;
            }
            boolean infinite = ticks == PotionEffect.INFINITE_DURATION;
            PotionEffect existing = player.getPotionEffect(type);
            if (existing != null) {
                if (existing.getAmplifier() > spec.amplifier()) {
                    continue; // never downgrade a stronger effect
                }
                if (existing.getAmplifier() == spec.amplifier()
                        && (existing.isInfinite() || (!infinite && existing.getDuration() >= ticks))) {
                    continue; // already at least as good
                }
            }
            player.addPotionEffect(new PotionEffect(type, ticks, spec.amplifier(), true, particles, true));
            session.abilities().applied().put(type,
                    new AbilityRuntime.AppliedEffect(spec.amplifier(), infinite, infinite ? 0 : tick + ticks));
        }
    }

    /** Drops queue entries for players who are gone (defensive; stale entries also expire on poll). */
    public void prune() {
        Iterator<Pulse> it = pulses.iterator();
        while (it.hasNext()) {
            Pulse p = it.next();
            PlayerSession s = sessions.get(p.uuid());
            if (s == null || s.abilities().generation() != p.generation()) {
                it.remove();
            }
        }
    }
}
