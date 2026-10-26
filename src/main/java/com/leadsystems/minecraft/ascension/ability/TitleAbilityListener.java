package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityResolver;
import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityRegistry;
import com.leadsystems.minecraft.ascension.domain.cooldown.CooldownBook;
import com.leadsystems.minecraft.ascension.progress.Attribution;
import com.leadsystems.minecraft.ascension.progress.MobClassifier;
import com.leadsystems.minecraft.ascension.progress.PlacedBlockTracker;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.NamespacedKey;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.loot.Lootable;
import org.bukkit.loot.LootTables;
import org.bukkit.loot.LootContext;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;

import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Server-authoritative runtime for every configured title ability. */
public final class TitleAbilityListener implements Listener, SessionHook {

    private static final String STORM = "title.stormbringer.heavens_judgment";
    private static final String REFUSE_DEATH = "title.undying.refuse_death";
    private static final String VOID_STEP_CHARGE = "title.voidwalker.void_step.charge.";
    private static final NamespacedKey COLOSSUS_HEALTH = new NamespacedKey("ascension", "colossus_health");
    /* Treasure Pulse reads block entities of loaded chunks only: at most 16 chunks at this radius. */
    private static final int TREASURE_PULSE_MAX_RADIUS = 24;
    private final SessionManager sessions;
    private final Supplier<ConfigSnapshot> config;
    private final TitleAbilityResolver resolver;
    private final MobClassifier mobs;
    private final PlacedBlockTracker placed;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final Messages messages;
    private final AbilityFx fx;
    private final Map<UUID, Long> deepLureUntil = new HashMap<>();
    private final Map<UUID, UUID> deepLureHooks = new HashMap<>();
    private final Map<Block, TreasureOpen> recentTreasureOpens = new HashMap<>();
    private final Map<UUID, EnhancedPotion> enhancedPotions = new HashMap<>();
    private final Map<MarkKey, Mark> marks = new HashMap<>();
    private final Map<UUID, RewindSnapshot> rewindSnapshots = new HashMap<>();
    private final Map<UUID, Long> moltenUntil = new HashMap<>();
    private final Map<UUID, TitleDefinition> moltenDefinitions = new HashMap<>();
    private final Map<UUID, TitleDefinition> lureDefinitions = new HashMap<>();
    private final Map<UUID, Deque<RageDamage>> rage = new HashMap<>();
    private final Map<EntityDamageByEntityEvent, CombatActivation> pendingCombatActivations = new WeakHashMap<>();
    private final Map<UUID, UUID> activeSporeClouds = new HashMap<>();
    /* Invalidates deferred title callbacks after a title/session lifecycle change. */
    private final Map<UUID, Long> runtimeEpochs = new HashMap<>();
    private long nextRuntimeEpoch = 1L;
    private final Set<UUID> shattering = new HashSet<>();
    private final Map<UUID, TitleDefinition> shatterDefinitions = new HashMap<>();
    private Player pendingDamageOwner;
    private LivingEntity pendingDamageTarget;
    private boolean pendingDamageAccepted;

    private record MarkKey(UUID owner, UUID victim) { }
    private record Mark(long expiresAt, double multiplier) { }
    private record CombatActivation(String id, TitleDefinition definition) { }
    private record EnhancedPotion(UUID owner, boolean harmful, double radius) { }
    private record RewindSnapshot(Location location, double health, long expiresAt) { }
    private record RageDamage(double healthLost, long at) { }
    private record TreasureOpen(UUID playerId, long at) { }

    public TitleAbilityListener(SessionManager sessions, Supplier<ConfigSnapshot> config, MobClassifier mobs,
                                PlacedBlockTracker placed, StorageExecutor storage,
                                PlayerRepository repo, MainThread main, Messages messages) {
        this.sessions = sessions;
        this.config = config;
        this.resolver = new TitleAbilityResolver(config);
        this.mobs = mobs;
        this.placed = placed;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
        this.messages = messages;
        this.fx = new AbilityFx(main);
    }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        clearTemporaryState(player.getUniqueId());
        runtimeEpoch(player.getUniqueId());
        reconcileLevel(player, session);
    }

    @Override
    public void onUnload(Player player, PlayerSession session) {
        clearTemporaryState(player.getUniqueId());
        reconcileColossus(player, 0);
    }

    private void clearTemporaryState(UUID playerId) {
        runtimeEpochs.remove(playerId);
        rewindSnapshots.remove(playerId);
        moltenUntil.remove(playerId);
        moltenDefinitions.remove(playerId);
        lureDefinitions.remove(playerId);
        shatterDefinitions.remove(playerId);
        deepLureUntil.remove(playerId);
        rage.remove(playerId);
        activeSporeClouds.remove(playerId);
        deepLureHooks.remove(playerId);
        recentTreasureOpens.values().removeIf(open -> open.playerId().equals(playerId));
        marks.keySet().removeIf(key -> key.owner().equals(playerId) || key.victim().equals(playerId));
    }

    /** Reconciles permanent level effects without resetting cooldowns or in-flight casts. */
    public void reconcileLevel(Player player, PlayerSession session) {
        TitleDefinition definition = equipped(session, "colossus") ? title(session, "colossus") : null;
        reconcileColossus(player, definition == null ? 0 : definition.abilitySetting("bonus-max-health-points", 4));
    }

    private void reconcileColossus(Player player, double bonus) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) return;
        AttributeModifier existing = max.getModifiers().stream()
                .filter(modifier -> COLOSSUS_HEALTH.equals(modifier.getKey())).findFirst().orElse(null);
        if (existing != null && existing.getAmount() == bonus) return;
        double health = player.getHealth();
        if (existing != null) max.removeModifier(existing);
        if (bonus > 0) max.addTransientModifier(new AttributeModifier(COLOSSUS_HEALTH, bonus,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        if (health > max.getValue()) player.setHealth(max.getValue());
    }

    /** Title XP crossed a level: a splash with the new level and the upgraded ability. */
    public void celebrateLevel(Player player, PlayerSession session) {
        String titleId = session == null ? null : session.equippedTitle();
        TitleDefinition definition = titleId == null ? null : title(session, titleId);
        if (definition == null || !player.isOnline()) return;
        messages.title(player, "title-level-title", "title-level-subtitle",
                Messages.ph("level", resolver.level(session, titleId)), Messages.ph("title", definition.name()),
                Messages.ph("ability", definition.abilityName()));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
        fx.helix(player, 0.8, 2.4, Particle.HAPPY_VILLAGER, null, 20);
    }

    /** Called only after an owner-bound ability item passes validation. */
    public void activateFromItem(Player owner, PlayerSession session) {
        if (session == null || !equipped(session, session.equippedTitle())) {
            return;
        }
        long now = System.currentTimeMillis();
        String title = session.equippedTitle();
        switch (title) {
            case "ascendant" -> activateAscension(owner, session, now);
            case "conqueror" -> activateWarCry(owner, session, now);
            case "stormbringer" -> activateStorm(owner, session, now);
            case "tempest_herald" -> activateChainLightning(owner, session, now);
            case "ashborn" -> activateFlamePurge(owner, session, now);
            case "mycelial_drifter" -> activateSporeCloud(owner, session, now);
            case "witherbound" -> activateCorruptionPurge(owner, session, now);
            case "frostbound" -> activateFrostNova(owner, session, now);
            case "soul_reaper" -> activateSoulHeal(owner, session, now);
            case "bounty_hunter" -> activateHuntersSense(owner, session, now);
            case "raidbreaker" -> activateRepulsionWave(owner, session, now);
            case "treasure_whisperer" -> activateTreasurePulse(owner, session, now);
            case "trailblazer" -> activateDash(owner, session, now, false);
            case "tideborn" -> activateDash(owner, session, now, true);
            case "chronomancer" -> activateRewind(owner, session, now);
            case "voidwalker" -> activateVoidStep(owner, session, now);
            case "titan" -> activateGroundSlam(owner, session, now);
            case "emberforge" -> activateMoltenEdge(owner, session, now);
            case "angler" -> primeDeepLure(owner, session, now);
            default -> showPassive(owner, title);
        }
    }

    static boolean isManualTitle(String titleId) {
        return TitleAbilityRegistry.isManual(titleId);
    }

    static Set<String> manualTitleIds() {
        return TitleAbilityRegistry.MANUAL;
    }

    private void activateRewind(Player owner, PlayerSession session, long now) {
        TitleDefinition definition = title(session, "chronomancer");
        String key = "title.chronomancer.rewind";
        RewindSnapshot snapshot = rewindSnapshots.get(owner.getUniqueId());
        if (snapshot != null && rewindSnapshotActive(snapshot.expiresAt(), now)
                && equipped(session, "chronomancer")) {
            Location departed = owner.getLocation().add(0, 1, 0);
            if (!owner.getWorld().equals(snapshot.location().getWorld())
                    || !safeVoidStepFeet(snapshot.location().getBlock())
                    || !owner.teleport(snapshot.location())) {
                noCondition(owner, "chronomancer");
                return;
            }
            rewindSnapshots.remove(owner.getUniqueId());
            owner.setVelocity(new Vector());
            owner.setFallDistance(0);
            double max = owner.getAttribute(Attribute.MAX_HEALTH).getValue();
            owner.setHealth(Math.min(max, snapshot.health()));
            owner.getWorld().spawnParticle(Particle.REVERSE_PORTAL, owner.getLocation().add(0, 1, 0),
                    32, 0.5, 0.8, 0.5, 0.03);
            fx.beam(departed, owner.getLocation().add(0, 1, 0), Particle.REVERSE_PORTAL, null, 6);
            owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.6f);
            activated(owner, "chronomancer");
            return;
        }
        rewindSnapshots.remove(owner.getUniqueId());
        if (!ready(owner, session, key, now)) return;
        if (!safeVoidStepFeet(owner.getLocation().getBlock())) {
            noCondition(owner, "chronomancer");
            return;
        }
        long window = Math.round(definition.abilitySetting("window-seconds", 3) * 1000);
        rewindSnapshots.put(owner.getUniqueId(), new RewindSnapshot(owner.getLocation().clone(),
                owner.getHealth(), now + Math.max(1, window)));
        start(session, key, cooldown(definition, 120), now);
        owner.getWorld().spawnParticle(Particle.ENCHANT, owner.getLocation().add(0, 1, 0),
                18, 0.4, 0.7, 0.4, 0.02);
        fx.shockwave(owner.getLocation(), 1.2, Particle.DUST, AbilityFx.dust(0x9AA7C7, 1.0f), 4);
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.6f, 1.8f);
        messages.actionBar(owner, "ability-primed", Messages.ph("ability", definition.abilityName()));
    }

    private void activateFrostNova(Player owner, PlayerSession session, long now) {
        String key = "title.frostbound.frost_nova";
        if (!ready(owner, session, key, now)) return;
        TitleDefinition definition = title(session, "frostbound");
        double radius = definition.abilitySetting("radius", 5);
        int hits = 0;
        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity target && validAbilityTarget(owner, target)
                    && target.getLocation().distanceSquared(owner.getLocation()) <= radius * radius
                    && damageAccepted(owner, target, definition.abilitySetting("damage-health-points", 6))) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        effectTicks(definition.abilitySetting("slow-seconds", 4)),
                        amplifier(definition.abilitySetting("slow-level", 2)), false, true, true));
                hits++;
            }
        }
        if (hits == 0) {
            noCondition(owner, "frostbound");
            return;
        }
        owner.getWorld().spawnParticle(Particle.SNOWFLAKE, owner.getLocation().add(0, 1, 0),
                60, radius / 2, 0.5, radius / 2, 0.02);
        fx.shockwave(owner.getLocation(), radius, Particle.SNOWFLAKE, null, 8);
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 0.8f, 1f);
        start(session, key, cooldown(definition, 45), now);
        activated(owner, "frostbound");
    }

    private void activateMoltenEdge(Player owner, PlayerSession session, long now) {
        TitleDefinition definition = title(session, "emberforge");
        String key = "title.emberforge.molten_edge";
        if (!ready(owner, session, key, now)) return;
        moltenDefinitions.put(owner.getUniqueId(), definition);
        moltenUntil.put(owner.getUniqueId(), now + Math.round(
                definition.abilitySetting("duration-seconds", 8) * 1000));
        owner.getWorld().spawnParticle(Particle.FLAME, owner.getLocation().add(0, 1, 0),
                32, 0.45, 0.7, 0.45, 0.03);
        fx.helix(owner, 0.7, 2.0, Particle.FLAME, null, 20);
        owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f);
        start(session, key, cooldown(definition, 60), now);
        activated(owner, "emberforge");
    }

    private void activateSporeCloud(Player owner, PlayerSession session, long now) {
        String key = "title.mycelial_drifter.spore_cloud";
        if (!ready(owner, session, key, now)) return;
        TitleDefinition definition = title(session, "mycelial_drifter");
        double radius = definition.abilitySetting("radius", 4);
        int ticks = Math.max(1, Math.min(8, (int) definition.abilitySetting("ticks", 4)));
        Location center = owner.getLocation().clone();
        UUID cloudId = UUID.randomUUID();
        long runtimeEpoch = runtimeEpoch(owner.getUniqueId());
        activeSporeClouds.put(owner.getUniqueId(), cloudId);
        boolean[] healed = {false};
        Map<UUID, Long> lastHit = new HashMap<>();
        for (int tick = 1; tick <= ticks; tick++) {
            main.later(tick * 20L, () -> {
                if (!isCurrentRuntime(owner.getUniqueId(), runtimeEpoch, "mycelial_drifter")
                        || !owner.isOnline() || owner.isDead()
                        || !cloudId.equals(activeSporeClouds.get(owner.getUniqueId()))) {
                    return;
                }
                center.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR, center.clone().add(0, 1, 0),
                        35, radius / 2, 0.6, radius / 2, 0.01);
                fx.shockwave(center, radius, Particle.DUST, AbilityFx.dust(0x7FB238, 1.2f), 3);
                long pulse = System.currentTimeMillis();
                for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                    if (!(entity instanceof LivingEntity target) || !validAbilityTarget(owner, target)
                            || target.getLocation().distanceSquared(center) > radius * radius
                            || !sporeHitReady(lastHit, target.getUniqueId(), pulse)) continue;
                    if (damageAccepted(owner, target, definition.abilitySetting("damage-per-tick-health-points", 2))) {
                        lastHit.put(target.getUniqueId(), pulse);
                        if (!healed[0]) {
                            double max = owner.getAttribute(Attribute.MAX_HEALTH).getValue();
                            owner.setHealth(Math.min(max, owner.getHealth()
                                    + definition.abilitySetting("heal-health-points", 2)));
                            healed[0] = true;
                        }
                    }
                }
            });
        }
        start(session, key, cooldown(definition, 45), now);
        activated(owner, "mycelial_drifter");
    }

    static boolean sporeHitReady(Map<UUID, Long> cloudHits, UUID target, long now) {
        return now - cloudHits.getOrDefault(target, 0L) >= 900;
    }

    private void activateStorm(Player owner, PlayerSession session, long now) {
        if (!session.cooldowns().isReady(STORM, now)) {
            showCooldown(owner, session, STORM, now);
            return;
        }
        TitleDefinition definition = title(session, "stormbringer");
        double range = definition.abilitySetting("range", 50);
        Location origin = owner.getLocation();
        var targets = owner.getWorld().getPlayers().stream()
                .filter(p -> eligibleOpponent(owner, p))
                .filter(p -> p.isOnline() && !p.isDead())
                .filter(p -> withinRadius(origin, p.getLocation(), range))
                .distinct()
                .sorted(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(origin)))
                .limit(definition.abilitySetting("all-targets", 0) >= 1 ? Long.MAX_VALUE : 1)
                .toList();
        double damage = definition.upgradedAbilitySetting(
                "burst-damage-health-points", "player-damage-health-points", 10, 26);
        int hits = 0;
        for (Player target : targets) {
            if (!target.isOnline() || target.isDead() || !eligibleOpponent(owner, target)
                    || !withinRadius(origin, target.getLocation(), range) || !damageAccepted(owner, target, damage)) continue;
            target.getWorld().strikeLightningEffect(target.getLocation());
            fx.column(target.getLocation(), 6, Particle.ELECTRIC_SPARK, null);
            hits++;
        }
        if (hits == 0) {
            noCondition(owner, "stormbringer");
            return;
        }
        owner.playSound(owner.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
        start(session, STORM, cooldown(definition, 35), now);
        activated(owner, "stormbringer");
    }

    private void activateChainLightning(Player owner, PlayerSession session, long now) {
        String key = "title.tempest_herald.chain_lightning";
        if (!ready(owner, session, key, now)) {
            return;
        }
        TitleDefinition title = title(session, "tempest_herald");
        double range = title.abilitySetting("range", 20);
        Vector look = owner.getEyeLocation().getDirection().normalize();
        LivingEntity primary = owner.getNearbyEntities(range, range, range).stream()
                .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast)
                .filter(target -> validAbilityTarget(owner, target) && owner.hasLineOfSight(target))
                .filter(target -> withinRadius(owner.getLocation(), target.getLocation(), range))
                .filter(target -> {
                    Vector toTarget = target.getEyeLocation().toVector().subtract(owner.getEyeLocation().toVector());
                    return toTarget.lengthSquared() > 0.0001 && look.dot(toTarget.normalize()) > 0.92;
                })
                .min(Comparator.comparingDouble(target -> target.getLocation().distanceSquared(owner.getLocation())))
                .orElse(null);
        if (primary == null) {
            noCondition(owner, "tempest_herald");
            return;
        }
        double damage = title.upgradedAbilitySetting("chain-damage-health-points",
                "combat-damage-health-points", 10, 8);
        if (!strike(owner, primary, damage)) {
            noCondition(owner, "tempest_herald");
            return;
        }
        fx.arc(owner.getEyeLocation(), primary.getEyeLocation(), Particle.ELECTRIC_SPARK, null);
        double chainRange = title.abilitySetting("chain-range", 8);
        double chainedDamage = chainDamage(damage, title.abilitySetting("chain-multiplier", 1.0));
        primary.getNearbyEntities(chainRange, chainRange, chainRange).stream()
                .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast)
                .filter(target -> !target.equals(primary) && validAbilityTarget(owner, target))
                .filter(target -> withinRadius(primary.getLocation(), target.getLocation(), chainRange))
                .sorted(Comparator.comparingDouble(target -> target.getLocation().distanceSquared(primary.getLocation())))
                .limit(Math.max(0, Math.min(16, (int) title.abilitySetting("extra-chain-targets", 2))))
                .forEach(target -> {
                    if (strike(owner, target, chainedDamage)) {
                        fx.arc(primary.getEyeLocation(), target.getEyeLocation(), Particle.ELECTRIC_SPARK, null);
                    }
                });
        start(session, key, cooldown(title, 45), now);
        activated(owner, "tempest_herald");
    }

    private void activateRepulsionWave(Player owner, PlayerSession session, long now) {
        String key = "title.raidbreaker.repulsion_wave";
        if (!ready(owner, session, key, now)) {
            return;
        }
        TitleDefinition title = title(session, "raidbreaker");
        double radius = title.abilitySetting("radius", 8);
        double damage = title.upgradedAbilitySetting("combat-damage-health-points",
                "damage-health-points", 5, 8);
        double knockback = title.abilitySetting("knockback", 1.2);
        int hits = 0;
        var nearby = owner.getNearbyEntities(radius, radius, radius);
        boolean raid = hasParticipatingRaiders(nearby, owner.getLocation(), radius);
        var targets = nearby.stream()
                .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast)
                .filter(target -> raid ? target instanceof Raider : mobs.categories(target).contains("hostile"))
                .filter(target -> target.getLocation().distanceSquared(owner.getLocation()) <= radius * radius)
                .toList();
        var players = nearby.stream()
                .filter(Player.class::isInstance).map(Player.class::cast)
                .filter(target -> eligibleOpponent(owner, target))
                .filter(target -> target.getLocation().distanceSquared(owner.getLocation()) <= radius * radius)
                .toList();
        if (!raid && targets.size() < 2 && players.isEmpty()) {
            noCondition(owner, "raidbreaker");
            return;
        }
        for (LivingEntity target : targets) {
            double amount = target instanceof Raider ? title.abilitySetting("illager-damage-health-points", 12) : damage;
            if (damageAccepted(owner, target, amount)) {
                target.setVelocity(awayFrom(owner, target).multiply(knockback).setY(0.35));
                hits++;
            }
        }
        for (Player target : players) {
            if (damageAccepted(owner, target, damage)) {
                target.setVelocity(awayFrom(owner, target).multiply(knockback).setY(0.35));
                hits++;
            }
        }
        if (hits > 0) {
            if (raid) {
                owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                        effectTicks(title.abilitySetting("raid-strength-seconds", 8)), 0, false, true, true));
            }
            owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.7f);
            owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1f, 0.8f);
            fx.shockwave(owner.getLocation(), radius, Particle.CLOUD, null, 6);
            start(session, key, cooldown(title, 35), now);
            activated(owner, "raidbreaker");
        } else {
            noCondition(owner, "raidbreaker");
        }
    }

    static double chainDamage(double primaryDamage, double multiplier) {
        return primaryDamage * multiplier;
    }

    static boolean withinRadius(Location center, Location target, double radius) {
        return center.distanceSquared(target) <= radius * radius;
    }

    static boolean hasParticipatingRaiders(java.util.Collection<? extends Entity> nearby,
                                            Location origin, double radius) {
        return nearby.stream().anyMatch(entity -> entity instanceof Raider raider
                && raider.getRaid() != null
                && entity.getLocation().distanceSquared(origin) <= radius * radius);
    }

    private void activateSoulHeal(Player owner, PlayerSession session, long now) {
        String key = "title.soul_reaper.soul_storage";
        String operation = key + ".pending";
        double maximumHealth = owner.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        if (!ready(owner, session, key, now) || !session.tryBegin(operation)) return;
        if (owner.getHealth() >= maximumHealth) {
            session.end(operation);
            noCondition(owner, "soul_reaper");
            return;
        }
        UUID ownerId = owner.getUniqueId();
        long runtimeEpoch = runtimeEpoch(ownerId);
        PlayerSession origin = session;
        TitleDefinition definition = title(session, "soul_reaper");
        main.onComplete(storage.submit("soul.consume",
                c -> repo.consumeAbilityState(c, ownerId, "soul_reaper.souls", 1)), (consumed, error) -> {
            PlayerSession live = sessions.get(ownerId);
            origin.end(operation);
            if (error != null || !Boolean.TRUE.equals(consumed)) {
                if (error == null && owner.isOnline() && isCurrentRuntime(ownerId, runtimeEpoch, "soul_reaper")) {
                    noCondition(owner, "soul_reaper");
                }
                return;
            }
            if (!owner.isOnline() || owner.isDead() || !isCurrentRuntime(ownerId, runtimeEpoch, "soul_reaper")) {
                storeSoul(owner, definition);
                return;
            }
            double max = owner.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            double heal = definition.abilitySetting("heal-health-points", 4);
            if (owner.getHealth() >= max) {
                storeSoul(owner, definition);
                return;
            }
            owner.setHealth(Math.min(max, owner.getHealth() + heal));
            owner.getWorld().spawnParticle(Particle.SOUL, owner.getLocation().add(0, 1, 0),
                    18, 0.4, 0.6, 0.4, 0.03);
            fx.helix(owner, 0.8, 2.2, Particle.SOUL_FIRE_FLAME, null, 16);
            owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            start(live, key, cooldown(definition, 20), System.currentTimeMillis());
            activated(owner, "soul_reaper");
        });
    }

    private void activateHuntersSense(Player owner, PlayerSession session, long now) {
        String key = "title.bounty_hunter.hunters_sense";
        String operation = key + ".pending";
        if (!ready(owner, session, key, now) || !session.tryBegin(operation)) return;
        UUID ownerId = owner.getUniqueId();
        long runtimeEpoch = runtimeEpoch(ownerId);
        PlayerSession origin = session;
        TitleDefinition definition = title(session, "bounty_hunter");
        main.onComplete(storage.submit("hunt.sense", c -> repo.activeTitleHuntTarget(c, ownerId)), (targetId, error) -> {
            PlayerSession live = sessions.get(ownerId);
            origin.end(operation);
            if (error != null || targetId == null || !isCurrentRuntime(ownerId, runtimeEpoch, "bounty_hunter") || !owner.isOnline()
                    || owner.isDead()) {
                if (error == null && owner.isOnline() && !owner.isDead()
                        && isCurrentRuntime(ownerId, runtimeEpoch, "bounty_hunter")) {
                    noCondition(owner, "bounty_hunter");
                }
                return;
            }
            Player target = org.bukkit.Bukkit.getPlayer(targetId);
            double range = definition.abilitySetting("range", 50);
            if (target == null || !target.getWorld().equals(owner.getWorld())
                    || target.getLocation().distanceSquared(owner.getLocation()) > range * range
                    || !eligibleOpponent(owner, target)) {
                noCondition(owner, "bounty_hunter");
                return;
            }
            int ticks = (int) (definition.abilitySetting("duration-seconds", 5) * 20);
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, true));
            Location eye = owner.getEyeLocation();
            Vector toward = target.getEyeLocation().toVector().subtract(eye.toVector());
            double length = Math.min(8, toward.length());
            if (length > 0.5) {
                fx.beam(eye, eye.clone().add(toward.normalize().multiply(length)), Particle.DUST,
                        AbilityFx.dust(0xC0392B, 0.9f), 8);
            }
            owner.playSound(owner.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.6f, 0.8f);
            start(live, key, cooldown(definition, 45), System.currentTimeMillis());
            activated(owner, "bounty_hunter");
        });
    }

    private void activateTreasurePulse(Player owner, PlayerSession session, long now) {
        TitleDefinition definition = title(session, "treasure_whisperer");
        String key = "title.treasure_whisperer.treasure_pulse";
        if (!ready(owner, session, key, now)) return;
        int radius = boundedRadius(definition.abilitySetting("range", 16), TREASURE_PULSE_MAX_RADIUS);
        Block nearest = nearestLootContainer(owner.getLocation(), radius);
        if (nearest == null) {
            noCondition(owner, "treasure_whisperer");
            return;
        }
        owner.getWorld().spawnParticle(Particle.END_ROD, nearest.getLocation().add(0.5, 1, 0.5),
                30, 0.45, 0.6, 0.45, 0.02);
        fx.beam(owner.getEyeLocation(), nearest.getLocation().add(0.5, 0.5, 0.5), Particle.END_ROD, null, 10);
        fx.column(nearest.getLocation().add(0.5, 1, 0.5), 3, Particle.WAX_ON, null);
        owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        start(session, key, cooldown(definition, 60), now);
        activated(owner, "treasure_whisperer");
    }

    /** Nearest container that still has a loot table, read from loaded chunks' block entities. */
    static Block nearestLootContainer(Location origin, int radius) {
        World world = origin.getWorld();
        Block nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int chunkX = (origin.getBlockX() - radius) >> 4; chunkX <= (origin.getBlockX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (origin.getBlockZ() - radius) >> 4; chunkZ <= (origin.getBlockZ() + radius) >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
                for (BlockState state : world.getChunkAt(chunkX, chunkZ)
                        .getTileEntities(TitleAbilityListener::isTreasureContainer, false)) {
                    if (!(state instanceof Lootable lootable) || lootable.getLootTable() == null) continue;
                    Location containerCenter = state.getLocation().add(0.5, 0.5, 0.5);
                    double distance = containerCenter.distanceSquared(origin);
                    if (distance <= (double) radius * radius && distance < nearestDistance) {
                        nearest = state.getBlock();
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    private static boolean isTreasureContainer(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || type.name().endsWith("SHULKER_BOX");
    }

    private void storeSoul(Player owner) {
        storeSoul(owner, title(owner, "soul_reaper"));
    }

    private void storeSoul(Player owner, TitleDefinition definition) {
        long maximum = Math.max(1, (long) definition.abilitySetting("maximum-souls", 10));
        UUID ownerId = owner.getUniqueId();
        main.onComplete(storage.submit("soul.store", c -> repo.addAbilityState(c, ownerId,
                "soul_reaper.souls", 1, maximum)), (count, error) -> {
            if (error == null && owner.isOnline() && equipped(sessions.get(ownerId), "soul_reaper")) {
                messages.actionBar(owner, "soul-stored", Messages.ph("count", count),
                        Messages.ph("maximum", maximum));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        marks.keySet().removeIf(key -> key.victim().equals(event.getEntity().getUniqueId()));
        if (event.getEntity() instanceof Player || event.getEntity().getKiller() == null
                || !mobs.categories(event.getEntity()).contains("hostile")) {
            return;
        }
        Player owner = event.getEntity().getKiller();
        PlayerSession session = sessions.get(owner);
        if (equipped(session, "soul_reaper")) {
            storeSoul(owner);
        }
        String key = "title.new_arrival.second_wind";
        long now = System.currentTimeMillis();
        if (!equipped(session, "new_arrival") || !session.cooldowns().isReady(key, now)) {
            return;
        }
        double max = owner.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double heal = title(session, "new_arrival").upgradedAbilitySetting("restorative-heal-health-points",
                "heal-health-points", 1.0, 2.0);
        if (heal > 0 && owner.getHealth() < max) {
            owner.setHealth(Math.min(max, owner.getHealth() + heal));
        }
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                effectTicks(title(session, "new_arrival").abilitySetting("speed-seconds", 2)), 0, false, true, true));
        start(session, key, cooldown(session, "new_arrival", 12), now);
    }

    /* HIGH, before onFatalDamage (HIGHEST): Refuse Death must see damage after Sonic Ward reduces it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDefend(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0) {
            return;
        }
        PlayerSession session = sessions.get(player);
        long now = System.currentTimeMillis();
        if (equipped(session, "end_city_conqueror") && event.getCause() == EntityDamageEvent.DamageCause.FALL
                && player.getFallDistance() >= title(session, "end_city_conqueror").abilitySetting("minimum-fall-distance", 6)) {
            String key = "title.end_city_conqueror.sky_rescue";
            if (session.cooldowns().isReady(key, now)) {
                event.setCancelled(true);
                int ticks = (int) (title(session, "end_city_conqueror").abilitySetting("slow-falling-seconds", 5) * 20);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, ticks, 0, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        effectTicks(title(session, "end_city_conqueror").abilitySetting("landing-speed-seconds", 2)),
                        1, false, true, true));
                start(session, key, cooldown(session, "end_city_conqueror", 45), now);
            }
            return;
        }
        if (equipped(session, "wardens_bane")
                && (event.getCause() == EntityDamageEvent.DamageCause.SONIC_BOOM
                || event.getFinalDamage() >= title(session, "wardens_bane").abilitySetting(
                        "heavy-hit-threshold-health-points", 16))) {
            String key = "title.wardens_bane.sonic_ward";
            if (session.cooldowns().isReady(key, now)) {
                double multiplier = event.getCause() == EntityDamageEvent.DamageCause.SONIC_BOOM
                        ? title(session, "wardens_bane").abilitySetting("damage-multiplier", 0.2)
                        : title(session, "wardens_bane").abilitySetting("heavy-hit-multiplier", 0.5);
                event.setDamage(event.getDamage() * multiplier);
                start(session, key, cooldown(session, "wardens_bane", 60), now);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || event.getFinalDamage() <= 0) {
            return;
        }
        Player attacker = Attribution.player(event.getDamager());
        if (attacker == null && event.getDamageSource().getCausingEntity() instanceof Entity source) {
            attacker = Attribution.player(source);
        }
        if (attacker == null) {
            return;
        }
        if (attacker.equals(pendingDamageOwner)) {
            return;
        }
        boolean hostileMob = !(victim instanceof Player) && mobs.categories(victim).contains("hostile");
        boolean eligiblePlayer = victim instanceof Player player && eligibleOpponent(attacker, player);
        if (!hostileMob && !eligiblePlayer) {
            return;
        }
        PlayerSession session = sessions.get(attacker);
        long now = System.currentTimeMillis();
        if ((hostileMob || eligiblePlayer) && equipped(session, "beastbane")) {
            String key = "title.beastbane.executioner";
            double threshold = title(session, "beastbane").abilitySetting("health-threshold-health-points", 8);
            double executeDamage = title(session, "beastbane").abilitySetting("execute-damage-health-points", 14);
            if (CombatHealth.effectiveHealth(victim) <= threshold && event.getDamage() < executeDamage
                    && session.cooldowns().isReady(key, now)) {
                event.setDamage(executeDamage);
                pendingCombatActivations.put(event, new CombatActivation("beastbane", title(session, "beastbane")));
            }
        }
        if (equipped(session, "huntsman")) {
            MarkKey markKey = new MarkKey(attacker.getUniqueId(), victim.getUniqueId());
            Mark mark = marks.get(markKey);
            if (mark != null && mark.expiresAt() <= now) {
                marks.remove(markKey);
                mark = null;
            }
            String key = "title.huntsman.marked_prey";
            if (mark == null && session.cooldowns().isReady(key, now)) {
                long duration = (long) (title(session, "huntsman").abilitySetting("duration-seconds", 8) * 1000);
                mark = new Mark(now + duration, title(session, "huntsman").abilitySetting("combat-damage-multiplier", 1.5));
                pendingCombatActivations.put(event, new CombatActivation("huntsman", title(session, "huntsman")));
            }
            if (mark != null) {
                event.setDamage(event.getDamage()
                        * mark.multiplier());
            }
        }
        if (equipped(session, "berserker")) {
            Deque<RageDamage> recent = rage.get(attacker.getUniqueId());
            double bonus = recent == null ? 0 : rageBonus(recent, now,
                    title(session, "berserker").abilitySetting("decay-seconds", 10),
                    title(session, "berserker").abilitySetting("health-lost-per-bonus-damage", 2),
                    title(session, "berserker").abilitySetting("maximum-bonus-damage", 6));
            if (bonus > 0) {
                event.setDamage(event.getDamage() + bonus);
            }
        }
        if (equipped(session, "emberforge") && moltenUntil.getOrDefault(attacker.getUniqueId(), 0L) > now) {
            event.setDamage(event.getDamage()
                    + moltenDefinitions.getOrDefault(attacker.getUniqueId(), title(session, "emberforge")).abilitySetting("bonus-damage-health-points", 5));
        }
    }

    static double rageBonus(Deque<RageDamage> recent, long now, double decaySeconds,
                            double lostPerBonus, double maximum) {
        double window = Math.max(1, decaySeconds * 1000);
        while (!recent.isEmpty() && now - recent.peekFirst().at() >= window) {
            recent.removeFirst();
        }
        double weighted = 0;
        for (RageDamage hit : recent) {
            weighted += hit.healthLost() * Math.max(0, 1 - (now - hit.at()) / window);
        }
        return Math.min(maximum, weighted / Math.max(0.01, lostPerBonus));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombatActivationResolved(EntityDamageByEntityEvent event) {
        CombatActivation activation = pendingCombatActivations.remove(event);
        String pending = activation == null ? null : activation.id();
        if (pending == null || event.isCancelled() || event.getFinalDamage() <= 0
                || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player attacker = Attribution.player(event.getDamager());
        if (attacker == null && event.getDamageSource().getCausingEntity() instanceof Entity source) {
            attacker = Attribution.player(source);
        }
        if (attacker == null) {
            return;
        }
        PlayerSession session = sessions.get(attacker);
        if (!equipped(session, pending) || !validAbilityTarget(attacker, victim)) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = "title." + pending + (pending.equals("beastbane") ? ".executioner" : ".marked_prey");
        if (!session.cooldowns().isReady(key, now)) {
            return;
        }
        if (pending.equals("huntsman")) {
            MarkKey markKey = new MarkKey(attacker.getUniqueId(), victim.getUniqueId());
            Mark current = marks.get(markKey);
            if (current != null && current.expiresAt() > now) {
                return;
            }
            long duration = (long) (activation.definition().abilitySetting("duration-seconds", 8) * 1000);
            marks.put(markKey, new Mark(now + duration, activation.definition().abilitySetting("combat-damage-multiplier", 1.5)));
            victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0),
                    15, 0.4, 0.6, 0.4, 0.05);
        }
        start(session, key, cooldown(activation.definition(), pending.equals("beastbane") ? 15 : 20), now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRageDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0
                || !equipped(sessions.get(player), "berserker")) return;
        long now = System.currentTimeMillis();
        rage.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>())
                .addLast(new RageDamage(Math.min(player.getHealth(), event.getFinalDamage()), now));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoltenHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target) || event.getFinalDamage() <= 0) return;
        Player attacker = Attribution.player(event.getDamager());
        if (attacker == null || attacker.equals(pendingDamageOwner)
                || !equipped(sessions.get(attacker), "emberforge")
                || !validAbilityTarget(attacker, target)
                || moltenUntil.getOrDefault(attacker.getUniqueId(), 0L) <= System.currentTimeMillis()) return;
        target.setFireTicks(Math.max(target.getFireTicks(),
                effectTicks(moltenDefinitions.getOrDefault(attacker.getUniqueId(), title(attacker, "emberforge")).abilitySetting("fire-seconds", 8))));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCast(PlayerFishEvent event) {
        Player owner = event.getPlayer();
        PlayerSession session = sessions.get(owner);
        String key = "title.angler.deep_lure";
        long now = System.currentTimeMillis();
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH
                && event.getCaught() instanceof Item caught
                && event.getHook().getUniqueId().equals(deepLureHooks.get(owner.getUniqueId()))
                && equipped(session, "angler")) {
            ItemStack treasure = rollTreasure(event.getHook().getLocation());
            if (treasure != null) {
                caught.setItemStack(treasure);
                deepLureHooks.remove(owner.getUniqueId());
                deepLureUntil.remove(owner.getUniqueId());
                start(session, key, cooldown(lureDefinitions.getOrDefault(owner.getUniqueId(), title(session, "angler")), 20), now);
                lureDefinitions.remove(owner.getUniqueId());
            }
            return;
        }
        if (event.getState() != PlayerFishEvent.State.FISHING) return;
        Long primedUntil = deepLureUntil.get(owner.getUniqueId());
        if (!equipped(session, "angler") || primedUntil == null || primedUntil < now
                || !session.cooldowns().isReady(key, now)) {
            if (primedUntil != null && primedUntil < now) {
                deepLureUntil.remove(owner.getUniqueId());
            }
            return;
        }
        deepLureHooks.put(owner.getUniqueId(), event.getHook().getUniqueId());
        int ticks = Math.max(1, (int) lureDefinitions.getOrDefault(owner.getUniqueId(), title(session, "angler")).abilitySetting("wait-ticks", 60));
        event.getHook().setWaitTime(ticks);
        owner.getWorld().spawnParticle(Particle.SPLASH, event.getHook().getLocation(),
                20, 0.35, 0.15, 0.35, 0.05);
        owner.playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 1.2f);
    }

    private static ItemStack rollTreasure(Location location) {
        var loot = LootTables.FISHING_TREASURE.getLootTable().populateLoot(ThreadLocalRandom.current(),
                new LootContext.Builder(location).build());
        return treasureOrFallback(loot);
    }

    static ItemStack treasureOrFallback(java.util.Collection<ItemStack> loot) {
        return loot.stream().filter(item -> item != null && !item.getType().isAir())
                .findFirst().map(ItemStack::clone).orElseGet(() -> new ItemStack(Material.NAUTILUS_SHELL));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTreasureOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !equipped(sessions.get(event.getPlayer()), "treasure_whisperer")) return;
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Lootable lootable)
                || lootable.getLootTable() == null) return;
        long now = System.currentTimeMillis();
        recentTreasureOpens.entrySet().removeIf(entry -> now - entry.getValue().at() > 5_000L);
        recordTreasureOpen(block, event.getPlayer().getUniqueId(), now);
    }

    void recordTreasureOpen(Block block, UUID playerId, long now) {
        recentTreasureOpens.put(block, new TreasureOpen(playerId, now));
    }

    UUID consumeTreasureOpen(Block block, long now) {
        TreasureOpen open = recentTreasureOpens.remove(block);
        if (open == null || now < open.at() || now - open.at() > 5_000L) return null;
        return open.playerId();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTreasureLoot(LootGenerateEvent event) {
        if (event.isPlugin() || !(event.getInventoryHolder() instanceof BlockState state)
                || !event.getLootTable().getKey().getKey().startsWith("chests/")) return;
        UUID opener = consumeTreasureOpen(state.getBlock(), System.currentTimeMillis());
        Player owner = opener == null ? null : Bukkit.getPlayer(opener);
        if (owner == null || !equipped(sessions.get(owner), "treasure_whisperer")
                || ThreadLocalRandom.current().nextDouble() >= title(owner, "treasure_whisperer").abilitySetting(
                        "bonus-chance", 0.05)) return;
        ItemStack bonus = rollTreasure(state.getLocation());
        if (bonus == null) return;
        bonus.setAmount(1);
        var updated = new java.util.ArrayList<>(event.getLoot());
        updated.add(bonus);
        event.setLoot(updated);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion potion) || !(potion.getShooter() instanceof Player owner)) {
            return;
        }
        PlayerSession session = sessions.get(owner);
        String key = "title.alchemist.catalyst";
        long now = System.currentTimeMillis();
        if (!equipped(session, "alchemist") || !session.cooldowns().isReady(key, now)) {
            return;
        }
        ItemStack item = potion.getItem();
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            return;
        }
        double multiplier = title(session, "alchemist").abilitySetting("duration-multiplier", 1.25);
        var effects = new java.util.ArrayList<PotionEffect>();
        if (meta.getBasePotionType() != null) {
            effects.addAll(meta.getBasePotionType().getPotionEffects());
        }
        effects.addAll(meta.getCustomEffects());
        boolean changed = false;
        for (PotionEffect effect : effects) {
            if (!effect.getType().isInstant() && effect.getDuration() > 0) {
                PotionEffect enhanced = new PotionEffect(effect.getType(),
                        Math.max(effect.getDuration() + 1, (int) (effect.getDuration() * multiplier)),
                        effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
                meta.addCustomEffect(enhanced, true);
                changed = true;
            }
        }
        if (changed || (item.getType() == Material.SPLASH_POTION && !effects.isEmpty())) {
            item.setItemMeta(meta);
            potion.setItem(item);
            boolean harmful = effects.stream().anyMatch(effect ->
                    effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL);
            enhancedPotions.put(potion.getUniqueId(), new EnhancedPotion(owner.getUniqueId(), harmful, title(session, "alchemist").abilitySetting("splash-radius", 5)));
            main.later(200, () -> enhancedPotions.remove(potion.getUniqueId()));
            start(session, key, cooldown(session, "alchemist", 10), now);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnhancedSplash(PotionSplashEvent event) {
        EnhancedPotion enhanced = enhancedPotions.remove(event.getPotion().getUniqueId());
        if (enhanced == null) return;
        Player owner = Bukkit.getPlayer(enhanced.owner());
        double radius = enhanced.radius();
        Location center = event.getPotion().getLocation();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target)
                    || center.distanceSquared(target.getLocation()) > radius * radius) continue;
            if (!expandedPotionTargetAllowed(owner, target, enhanced.harmful())) {
                event.setIntensity(target, 0);
            } else if (event.getIntensity(target) == 0) {
                event.setIntensity(target, 0.5);
            }
        }
    }

    static boolean expandedPotionTargetAllowed(Player owner, LivingEntity target, boolean harmful) {
        return !(target instanceof Player player) || !harmful
                || (owner != null && owner.getWorld().equals(player.getWorld())
                        && eligibleOpponent(owner, player));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnhancedLingering(LingeringPotionSplashEvent event) {
        EnhancedPotion enhanced = enhancedPotions.remove(event.getEntity().getUniqueId());
        if (enhanced != null) {
            event.getAreaEffectCloud().setRadius((float) enhanced.radius());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShatter(BlockBreakEvent event) {
        Block origin = event.getBlock();
        if ((origin.getType() != Material.STONE && origin.getType() != Material.DEEPSLATE)
                || placed.isPlaced(origin) || shattering.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        Player owner = event.getPlayer();
        PlayerSession session = sessions.get(owner);
        String key = "title.stonebreaker.shatter";
        long now = System.currentTimeMillis();
        if (!equipped(session, "stonebreaker") || !session.cooldowns().isReady(key, now)) {
            return;
        }
        TitleDefinition definition = title(session, "stonebreaker");
        int maximum = Math.max(0, (int) definition.abilitySetting("maximum-extra-blocks", 5));
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(origin);
        seen.add(origin.getX() + ":" + origin.getY() + ":" + origin.getZ());
        int broken = 0;
        shatterDefinitions.put(owner.getUniqueId(), definition);
        try {
            while (!queue.isEmpty() && broken < maximum) {
                Block current = queue.removeFirst();
                for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[] {
                        org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
                        org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                        org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                    Block next = current.getRelative(face);
                    String id = next.getX() + ":" + next.getY() + ":" + next.getZ();
                    if (!seen.add(id) || (next.getType() != Material.STONE && next.getType() != Material.DEEPSLATE)
                            || placed.isPlaced(next)) {
                        continue;
                    }
                    shattering.add(owner.getUniqueId());
                    try {
                        if (owner.breakBlock(next)) {
                            queue.addLast(next);
                            broken++;
                            if (broken >= maximum) break;
                        }
                    } finally {
                        shattering.remove(owner.getUniqueId());
                    }
                }
            }
        } finally {
            shatterDefinitions.remove(owner.getUniqueId());
        }
        if (broken > 0) {
            start(session, key, cooldown(definition, 30), now);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShatterDrops(BlockDropItemEvent event) {
        Player owner = event.getPlayer();
        TitleDefinition definition = shatterDefinitions.get(owner.getUniqueId());
        if (definition == null || !shattering.contains(owner.getUniqueId()) || !equipped(sessions.get(owner), "stonebreaker")
                || owner.getInventory().getItemInMainHand().containsEnchantment(
                        org.bukkit.enchantments.Enchantment.SILK_TOUCH)) return;
        double chance = definition.abilitySetting("auto-smelt-chance", 0.1);
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        for (Item drop : event.getItems()) {
            ItemStack original = drop.getItemStack();
            var recipes = Bukkit.recipeIterator();
            while (recipes.hasNext()) {
                if (!(recipes.next() instanceof FurnaceRecipe recipe) || !recipe.getInputChoice().test(original)) continue;
                ItemStack cooked = recipe.getResult().clone();
                cooked.setAmount(Math.min(cooked.getMaxStackSize(), original.getAmount() * cooked.getAmount()));
                drop.setItemStack(cooked);
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        clearTemporaryState(playerId);
        deepLureUntil.remove(playerId);
        shattering.remove(playerId);
        marks.keySet().removeIf(key -> key.owner().equals(playerId) || key.victim().equals(playerId));
        if (event.getPlayer().equals(pendingDamageOwner) || event.getPlayer().equals(pendingDamageTarget)) {
            pendingDamageOwner = null;
            pendingDamageTarget = null;
            pendingDamageAccepted = false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        clearTemporaryState(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        main.later(1, () -> {
            if (player.isOnline()) {
                reconcileLevel(player, sessions.get(player));
            }
        });
    }

    public void onValidPlayerKill(Player owner) {
        PlayerSession session = sessions.get(owner);
        if (equipped(session, "soul_reaper")) {
            storeSoul(owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityDamageResolved(EntityDamageByEntityEvent event) {
        if (pendingDamageOwner == null || pendingDamageTarget == null
                || !event.getEntity().equals(pendingDamageTarget) || event.getFinalDamage() <= 0) {
            return;
        }
        Player attacker = Attribution.player(event.getDamager());
        if (attacker == null && event.getDamageSource().getCausingEntity() instanceof Entity source) {
            attacker = Attribution.player(source);
        }
        if (pendingDamageOwner.equals(attacker)) {
            pendingDamageAccepted = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVampireDamage(EntityDamageByEntityEvent event) {
        Player attacker = Attribution.player(event.getDamager());
        if (attacker == null && event.getDamageSource().getCausingEntity() instanceof Entity source) {
            attacker = Attribution.player(source);
        }
        if (!(event.getEntity() instanceof LivingEntity victim) || attacker == null
                || attacker.equals(victim) || victim.isDead() || event.getFinalDamage() <= 0) {
            return;
        }
        PlayerSession session = sessions.get(attacker);
        if (!equipped(session, "bloodlord")
                || (victim instanceof Player player
                        ? !eligibleOpponent(attacker, player) : !(victim instanceof Mob))) {
            return;
        }
        double max = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double heal = vampireHeal(Math.min(event.getFinalDamage(), victim.getHealth()),
                title(session, "bloodlord").abilitySetting("lifesteal-ratio", 0.20),
                title(session, "bloodlord").abilitySetting("max-heal-health-points", config.get().settings().vampireMaxHeal()));
        double actual = Math.min(heal, max - attacker.getHealth());
        if (actual <= 0 || !Double.isFinite(actual)) {
            return;
        }
        attacker.setHealth(attacker.getHealth() + actual);
        bloodParticles(victim, attacker);
        activated(attacker, "bloodlord");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        // Final damage already excludes absorption, so compare it with red hearts only.
        if (!(event.getEntity() instanceof Player player)
                || player.getHealth() - event.getFinalDamage() > 0) {
            return;
        }
        PlayerSession session = sessions.get(player);
        if (!equipped(session, "undying") || !refuseDeathApplies(event.getCause(),
                player.getInventory().getItemInMainHand(), player.getInventory().getItemInOffHand())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!session.cooldowns().isReady(REFUSE_DEATH, now)) {
            showCooldown(player, session, REFUSE_DEATH, now);
            return;
        }
        event.setCancelled(true);
        double max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.min(max, config.get().settings().undyingHealth()));
        player.setFireTicks(0);
        player.setNoDamageTicks(config.get().settings().undyingImmunitySeconds() * 20);
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0),
                50, 0.5, 0.8, 0.5, 0.1);
        player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
        fx.helix(player, 0.9, 2.4, Particle.TOTEM_OF_UNDYING, null, 24);
        start(session, REFUSE_DEATH, cooldown(session, "undying", 86_400), now);
    }

    /**
     * Refuse Death leaves /kill and the void alone (it cannot save the player there) and yields to a
     * held Totem of Undying, so the 24-hour cooldown is never spent when vanilla would save the player.
     */
    static boolean refuseDeathApplies(EntityDamageEvent.DamageCause cause, ItemStack mainHand, ItemStack offHand) {
        return cause != EntityDamageEvent.DamageCause.KILL && cause != EntityDamageEvent.DamageCause.VOID
                && !isTotem(mainHand) && !isTotem(offHand);
    }

    private static boolean isTotem(ItemStack item) {
        return item != null && item.getType() == Material.TOTEM_OF_UNDYING;
    }

    static double vampireHeal(double finalDamage, double ratio, double cap) {
        return Math.max(0.0, Math.min(cap, finalDamage * ratio));
    }

    private void activateAscension(Player owner, PlayerSession session, long now) {
        String key = "title.ascendant.ascension";
        if (!ready(owner, session, key, now)) {
            return;
        }
        TitleDefinition title = title(session, "ascendant");
        applyAscensionBuffs(owner, title);
        owner.getWorld().spawnParticle(Particle.END_ROD, owner.getLocation().add(0, 1, 0),
                50, 0.8, 1.0, 0.8, 0.05);
        owner.playSound(owner.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 0.7f);
        owner.getWorld().playSound(owner.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
        fx.helix(owner, 1.0, 2.6, Particle.DUST, AbilityFx.dust(0xF5D76E, 1.3f), 30);
        fx.shockwave(owner.getLocation(), 3, Particle.END_ROD, null, 6);
        start(session, key, cooldown(title, 300), now);
        activated(owner, "ascendant");
    }

    static void applyAscensionBuffs(Player owner, TitleDefinition title) {
        int ticks = effectTicks(title.upgradedAbilitySetting("buff-duration-seconds", "duration-seconds", 15, 30));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks,
                amplifier(title.abilitySetting("strength-level", 5)), false, true, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks,
                amplifier(title.upgradedAbilitySetting("balanced-resistance-level", "resistance-level", 10, 4)),
                false, true, true));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks,
                amplifier(title.abilitySetting("speed-level", 3)), false, true, true));
    }

    private void activateGroundSlam(Player owner, PlayerSession session, long now) {
        TitleDefinition definition = title(session, "titan");
        String key = "title.titan.ground_slam";
        if (!ready(owner, session, key, now)) {
            return;
        }
        if (!groundSlam(owner, definition)) {
            noCondition(owner, "titan");
            return;
        }
        start(session, key, cooldown(definition, 30), now);
        messages.actionBar(owner, "ability-activated", Messages.ph("ability", definition.abilityName()));
    }

    private void primeDeepLure(Player owner, PlayerSession session, long now) {
        TitleDefinition definition = title(session, "angler");
        String key = "title.angler.deep_lure";
        if (!ready(owner, session, key, now)) {
            return;
        }
        lureDefinitions.put(owner.getUniqueId(), definition);
        deepLureUntil.put(owner.getUniqueId(), now + 30_000L);
        owner.getWorld().spawnParticle(Particle.BUBBLE, owner.getLocation().add(0, 1, 0),
                18, 0.45, 0.5, 0.45, 0.05);
        owner.playSound(owner.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1f, 1.15f);
        messages.actionBar(owner, "ability-primed", Messages.ph("ability", definition.abilityName()));
    }

    private void showPassive(Player owner, String titleId) {
        TitleDefinition definition = title(owner, titleId);
        if (definition != null) {
            messages.send(owner, "ability-passive", Messages.ph("ability", definition.abilityName()),
                    Messages.ph("description", definition.abilityDescription()));
        }
    }

    private void noCondition(Player owner, String titleId) {
        TitleDefinition definition = title(owner, titleId);
        messages.actionBar(owner, "ability-no-condition",
                Messages.ph("ability", definition == null ? "Ability" : definition.abilityName()));
    }

    private void activated(Player owner, String titleId) {
        messages.actionBar(owner, "ability-activated", Messages.ph("ability", title(owner, titleId).abilityName()));
    }

    static int effectTicks(double seconds) {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, seconds * 20));
    }

    static int amplifier(double level) {
        return Math.max(0, (int) level - 1);
    }

    private static int boundedRadius(double configuredRadius, int maximum) {
        return Math.max(1, Math.min(maximum, (int) configuredRadius));
    }

    private boolean groundSlam(Player owner, TitleDefinition title) {
        double radius = title.upgradedAbilitySetting("slam-radius", "radius", 6, 3);
        double damage = title.upgradedAbilitySetting("slam-damage-health-points",
                "combat-damage-health-points", 12, 26);
        double knockback = title.upgradedAbilitySetting("slam-knockback", "knockback", 1, 1.4);
        Location origin = owner.getLocation();
        int hits = 0;
        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity target) || !validGroundSlamTarget(owner, target)
                    || target.getLocation().distanceSquared(origin) > radius * radius) {
                continue;
            }
            if (!damageAccepted(owner, target, damage)) {
                continue;
            }
            target.setVelocity(awayFrom(owner, target).multiply(knockback).setY(0.4));
            hits++;
        }
        if (hits == 0) return false;
        Location center = origin.clone().add(0, 0.15, 0);
        var world = owner.getWorld();
        world.spawnParticle(Particle.EXPLOSION, center, 5, 0.9, 0.15, 0.9, 0);
        world.spawnParticle(Particle.CLOUD, center, 24, 1.0, 0.1, 1.0, 0.05);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(190, 165, 120), 1.6f);
        long runtimeEpoch = runtimeEpoch(owner.getUniqueId());
        for (int wave = 1; wave <= 3; wave++) {
            final double waveRadius = radius * wave / 3.0;
            main.later((wave - 1L) * 2L + 1L, () -> {
                if (!owner.isOnline() || owner.isDead()
                        || !isCurrentRuntime(owner.getUniqueId(), runtimeEpoch, "titan")) {
                    return;
                }
                for (int point = 0; point < 36; point++) {
                    double angle = Math.PI * 2.0 * point / 36.0;
                    Location particle = center.clone().add(Math.cos(angle) * waveRadius, 0,
                            Math.sin(angle) * waveRadius);
                    world.spawnParticle(Particle.DUST, particle, 1, 0, 0.04, 0, 0, dust);
                }
            });
        }
        world.playSound(owner.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.7f);
        world.playSound(owner.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 0.55f);
        return true;
    }

    static boolean validGroundSlamTarget(Player owner, LivingEntity target) {
        return !target.equals(owner) && (!(target instanceof Player player) || eligibleOpponent(owner, player));
    }

    static boolean withinGroundSlamRadius(Location origin, Location target, double radius) {
        double dx = target.getX() - origin.getX();
        double dz = target.getZ() - origin.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean strike(Player owner, LivingEntity target, double damage) {
        if (!damageAccepted(owner, target, damage)) {
            return false;
        }
        target.getWorld().strikeLightningEffect(target.getLocation());
        return true;
    }

    private boolean damageAccepted(Player owner, LivingEntity target, double damage) {
        Player previousOwner = pendingDamageOwner;
        LivingEntity previousTarget = pendingDamageTarget;
        boolean previousAccepted = pendingDamageAccepted;
        pendingDamageOwner = owner;
        pendingDamageTarget = target;
        pendingDamageAccepted = false;
        try {
            target.damage(damage, owner);
            return pendingDamageAccepted;
        } finally {
            pendingDamageOwner = previousOwner;
            pendingDamageTarget = previousTarget;
            pendingDamageAccepted = previousAccepted;
        }
    }

    private boolean validAbilityTarget(Player owner, LivingEntity target) {
        if (target.isDead()) {
            return false;
        }
        if (target instanceof Player player) {
            return eligibleOpponent(owner, player);
        }
        return mobs.categories(target).contains("hostile");
    }

    private void activateWarCry(Player owner, PlayerSession session, long now) {
        String key = "title.conqueror.war_cry";
        if (!ready(owner, session, key, now)) {
            return;
        }
        TitleDefinition title = title(session, "conqueror");
        double radius = title.abilitySetting("radius", 10);
        int ticks = effectTicks(title.abilitySetting("duration-seconds", 8));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 0, false, true, true));
        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && validAbilityTarget(owner, living)
                    && living.getLocation().distanceSquared(owner.getLocation()) <= radius * radius) {
                living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks,
                        amplifier(title.upgradedAbilitySetting("balanced-weakness-level", "weakness-level", 3, 2)),
                        false, true, true));
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks,
                        amplifier(title.upgradedAbilitySetting("balanced-slowness-level", "slowness-level", 5, 4)),
                        false, true, true));
            }
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.8f);
        fx.shockwave(owner.getLocation(), radius, Particle.DUST, AbilityFx.dust(0xB02E26, 1.4f), 8);
        start(session, key, cooldown(title, 60), now);
        activated(owner, "conqueror");
    }

    private void activateVoidStep(Player owner, PlayerSession session, long now) {
        TitleDefinition definition = title(session, "voidwalker");
        int charges = Math.max(1, Math.min(5, (int) definition.abilitySetting("charges", 3)));
        String charge = nextVoidStepCharge(session.cooldowns(), now, charges);
        if (charge == null) {
            String soonest = VOID_STEP_CHARGE + 1;
            for (int slot = 2; slot <= charges; slot++) {
                String candidate = VOID_STEP_CHARGE + slot;
                if (session.cooldowns().remainingMillis(candidate, now)
                        < session.cooldowns().remainingMillis(soonest, now)) {
                    soonest = candidate;
                }
            }
            showCooldown(owner, session, soonest, now);
            return;
        }
        double range = Math.min(64, Math.max(1,
                definition.upgradedAbilitySetting("aim-range", "range", 8, 32)));
        Location from = owner.getLocation();
        Location eye = owner.getEyeLocation();
        RayTraceResult hit = owner.getWorld().rayTraceBlocks(
                eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Location destination = voidStepDestination(hit, from);
        if (destination == null) {
            noCondition(owner, "voidwalker");
            return;
        }
        if (!owner.teleport(destination)) {
            noCondition(owner, "voidwalker");
            return;
        }
        owner.setVelocity(new Vector());
        owner.setFallDistance(0);
        owner.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0),
                25, 0.4, 0.7, 0.4, 0.1);
        owner.getWorld().spawnParticle(Particle.PORTAL, destination.clone().add(0, 1, 0),
                35, 0.5, 0.8, 0.5, 0.1);
        fx.beam(from.clone().add(0, 1, 0), destination.clone().add(0, 1, 0), Particle.REVERSE_PORTAL, null, 4);
        owner.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.1f);
        long recharge = Math.round(definition.abilitySetting("charge-recharge-seconds", 60));
        session.cooldowns().start(charge, now, TimeUnit.SECONDS.toMillis(Math.max(1, recharge)));
        int remaining = availableVoidStepCharges(session.cooldowns(), now, charges);
        messages.actionBar(owner, "void-step-uses", Messages.ph("uses", remaining));
        sessions.flushNow(session);
    }

    static String nextVoidStepCharge(CooldownBook cooldowns, long now) {
        return nextVoidStepCharge(cooldowns, now, 3);
    }

    static String nextVoidStepCharge(CooldownBook cooldowns, long now, int charges) {
        for (int slot = 1; slot <= charges; slot++) {
            String key = VOID_STEP_CHARGE + slot;
            if (cooldowns.isReady(key, now)) {
                return key;
            }
        }
        return null;
    }

    static int availableVoidStepCharges(CooldownBook cooldowns, long now) {
        return availableVoidStepCharges(cooldowns, now, 3);
    }

    static int availableVoidStepCharges(CooldownBook cooldowns, long now, int charges) {
        int available = 0;
        for (int slot = 1; slot <= charges; slot++) {
            if (cooldowns.isReady(VOID_STEP_CHARGE + slot, now)) {
                available++;
            }
        }
        return available;
    }

    static boolean rewindSnapshotActive(long expiresAt, long now) {
        return expiresAt > now;
    }

    static Location voidStepDestination(RayTraceResult hit, Location origin) {
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            return null;
        }
        Block target = hit.getHitBlock();
        BlockFace face = hit.getHitBlockFace();
        Block adjacent = target.getRelative(face);
        Block feet = null;
        for (int drop = 0; drop <= 2 && feet == null; drop++) {
            Block candidate = adjacent.getRelative(0, -drop, 0);
            if (candidate.getWorld().isChunkLoaded(candidate.getX() >> 4, candidate.getZ() >> 4)
                    && safeVoidStepFeet(candidate)) {
                feet = candidate;
            }
        }
        if (feet == null && face != BlockFace.UP) {
            Block above = target.getRelative(BlockFace.UP);
            if (above.getWorld().isChunkLoaded(above.getX() >> 4, above.getZ() >> 4)
                    && safeVoidStepFeet(above)) {
                feet = above;
            }
        }
        if (feet == null || !feet.getWorld().isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) {
            return null;
        }
        Location destination = new Location(feet.getWorld(), feet.getX() + 0.5, feet.getY(),
                feet.getZ() + 0.5, origin.getYaw(), origin.getPitch());
        return feet.getWorld().getWorldBorder().isInside(destination) ? destination : null;
    }

    static boolean safeVoidStepFeet(Block feet) {
        Block floor = feet.getRelative(BlockFace.DOWN);
        Material floorType = floor.getType();
        return feet.isEmpty() && feet.getRelative(BlockFace.UP).isEmpty()
                && floorType.isSolid() && !floor.isPassable()
                && floor.getBoundingBox().getMaxY() >= floor.getY() + 0.99
                && floorType != Material.MAGMA_BLOCK && floorType != Material.CACTUS
                && floorType != Material.CAMPFIRE && floorType != Material.SOUL_CAMPFIRE;
    }

    private void activateFlamePurge(Player owner, PlayerSession session, long now) {
        String key = "title.ashborn.flame_purge";
        if (!ready(owner, session, key, now)) {
            return;
        }
        TitleDefinition title = title(session, "ashborn");
        boolean extinguished = owner.getFireTicks() > 0;
        owner.setFireTicks(0);
        double radius = title.abilitySetting("radius", 5);
        int hits = 0;
        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity target && validAbilityTarget(owner, target)
                    && target.getLocation().distanceSquared(owner.getLocation()) <= radius * radius
                    && damageAccepted(owner, target,
                            title.upgradedAbilitySetting("combat-damage-health-points", "damage-health-points", 4, 8))) {
                target.setFireTicks(Math.max(target.getFireTicks(),
                        effectTicks(title.abilitySetting("burn-seconds", 4))));
                hits++;
            }
        }
        if (!extinguished && hits == 0) {
            noCondition(owner, "ashborn");
            return;
        }
        owner.getWorld().spawnParticle(Particle.FLAME, owner.getLocation().add(0, 1, 0),
                30, 1.0, 0.4, 1.0, 0.03);
        fx.shockwave(owner.getLocation(), radius, Particle.FLAME, null, 7);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
        start(session, key, cooldown(title, 45), now);
        activated(owner, "ashborn");
    }

    private void activateCorruptionPurge(Player owner, PlayerSession session, long now) {
        String key = "title.witherbound.corruption_purge";
        if (!ready(owner, session, key, now)) {
            return;
        }
        TitleDefinition title = title(session, "witherbound");
        boolean cured = owner.hasPotionEffect(PotionEffectType.WITHER);
        owner.removePotionEffect(PotionEffectType.WITHER);
        int hits = damageEligibleTargets(owner, title.abilitySetting("radius", 5),
                title.upgradedAbilitySetting("combat-damage-health-points", "damage-health-points", 5, 9));
        if (!cured && hits == 0) {
            noCondition(owner, "witherbound");
            return;
        }
        owner.getWorld().spawnParticle(Particle.SQUID_INK, owner.getLocation().add(0, 1, 0),
                35, 1.2, 0.6, 1.2, 0.05);
        fx.shockwave(owner.getLocation(), title.abilitySetting("radius", 5), Particle.SMOKE, null, 7);
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.7f, 0.9f);
        start(session, key, cooldown(title, 45), now);
        activated(owner, "witherbound");
    }

    private void activateDash(Player owner, PlayerSession session, long now, boolean underwater) {
        String id = underwater ? "tideborn" : "trailblazer";
        String key = "title." + id + (underwater ? ".tidal_rush" : ".dash");
        if (!ready(owner, session, key, now)) {
            return;
        }
        TitleDefinition title = title(session, id);
        boolean inWater = owner.isInWater();
        double force = title.abilitySetting("horizontal-force", underwater ? 1.8 : 1.6);
        if (underwater && !inWater) {
            force *= title.abilitySetting("land-force-multiplier", 0.5);
        }
        Vector direction = owner.getLocation().getDirection().normalize()
                .multiply(force);
        direction.setY(Math.max(direction.getY(), title.abilitySetting("vertical-force", 0.25)));
        owner.setVelocity(direction);
        owner.setFallDistance(0);
        if (underwater && inWater) {
            owner.setRemainingAir(owner.getMaximumAir());
            owner.getWorld().spawnParticle(Particle.BUBBLE, owner.getLocation().add(0, 1, 0),
                    24, 0.4, 0.4, 0.4, 0.1);
            fx.trail(owner, Particle.BUBBLE, null, 12);
            owner.getWorld().playSound(owner.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.2f);
        } else {
            owner.getWorld().spawnParticle(Particle.CLOUD, owner.getLocation(), 16, 0.3, 0.1, 0.3, 0.05);
            fx.trail(owner, underwater ? Particle.SPLASH : Particle.FIREWORK, null, 10);
            owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.7f, 1.4f);
        }
        start(session, key, cooldown(title, underwater ? 30 : 25), now);
        activated(owner, id);
    }

    private int damageEligibleTargets(Player owner, double radius, double damage) {
        int hits = 0;
        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && validAbilityTarget(owner, living)
                    && living.getLocation().distanceSquared(owner.getLocation()) <= radius * radius
                    && damageAccepted(owner, living, damage)) {
                hits++;
            }
        }
        return hits;
    }

    static Vector awayFrom(Player owner, LivingEntity target) {
        Vector away = target.getLocation().toVector().subtract(owner.getLocation().toVector());
        if (away.lengthSquared() < 0.0001) {
            away = owner.getLocation().getDirection().setY(0);
        }
        if (away.lengthSquared() < 0.0001) {
            away = new Vector(1, 0, 0);
        }
        return away.normalize();
    }

    private boolean ready(Player player, PlayerSession session, String key, long now) {
        if (session.cooldowns().isReady(key, now)) {
            return true;
        }
        showCooldown(player, session, key, now);
        return false;
    }

    private TitleDefinition title(PlayerSession session, String titleId) {
        return resolver.resolved(session, titleId);
    }

    private TitleDefinition title(Player player, String titleId) {
        return title(sessions.get(player), titleId);
    }

    private long cooldown(PlayerSession session, String titleId, int fallbackSeconds) {
        return cooldown(title(session, titleId), fallbackSeconds);
    }

    private static long cooldown(TitleDefinition definition, int fallbackSeconds) {
        return TimeUnit.SECONDS.toMillis(definition == null ? fallbackSeconds : definition.abilityCooldownSeconds());
    }

    private void start(PlayerSession session, String key, long duration, long now) {
        session.cooldowns().start(key, now, duration);
        sessions.flushNow(session);
    }

    private long runtimeEpoch(UUID playerId) {
        return runtimeEpochs.computeIfAbsent(playerId, ignored -> nextRuntimeEpoch++);
    }

    private boolean isCurrentRuntime(UUID playerId, long expectedEpoch, String titleId) {
        return isCurrentRuntimeEpoch(runtimeEpochs.getOrDefault(playerId, 0L), expectedEpoch)
                && equipped(sessions.get(playerId), titleId);
    }

    static boolean isCurrentRuntimeEpoch(long actualEpoch, long expectedEpoch) {
        return actualEpoch == expectedEpoch;
    }

    private boolean equipped(PlayerSession session, String titleId) {
        return TitleAccess.canUse(session, config.get(), titleId);
    }

    private static boolean eligibleOpponent(Player owner, Player target) {
        GameMode mode = target.getGameMode();
        return !owner.equals(target) && owner.getWorld().getGameRuleValue(GameRules.PVP)
                && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !sameTeam(owner, target);
    }

    private static boolean sameTeam(Player first, Player second) {
        return sameTeamOn(first, second, first) || sameTeamOn(first, second, second);
    }

    private static boolean sameTeamOn(Player first, Player second, Player scoreboardOwner) {
        Team firstTeam = scoreboardOwner.getScoreboard().getEntryTeam(first.getName());
        return firstTeam != null
                && firstTeam.equals(scoreboardOwner.getScoreboard().getEntryTeam(second.getName()));
    }

    private void showCooldown(Player player, PlayerSession session, String key, long now) {
        long seconds = (session.cooldowns().remainingMillis(key, now) + 999) / 1000;
        TitleDefinition definition = title(session, titleIdOf(key));
        messages.actionBar(player, "ability-cooldown",
                Messages.ph("ability", definition == null ? "Ability" : definition.abilityName()),
                Messages.ph("seconds", seconds));
    }

    /** "title.voidwalker.void_step.charge.2" -> "voidwalker". */
    static String titleIdOf(String cooldownKey) {
        String[] parts = cooldownKey.split("\\.");
        return parts.length > 1 ? parts[1] : cooldownKey;
    }

    private void bloodParticles(LivingEntity victim, Player owner) {
        Location from = victim.getEyeLocation().clone();
        Vector path = owner.getEyeLocation().toVector().subtract(from.toVector());
        var world = owner.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 1.0f);
        for (int i = 1; i <= 8; i++) {
            Location point = from.clone().add(path.clone().multiply(i / 8.0));
            main.later(i, () -> world.spawnParticle(Particle.DUST, point, 2, 0.05, 0.05, 0.05, 0, dust));
        }
    }
}
