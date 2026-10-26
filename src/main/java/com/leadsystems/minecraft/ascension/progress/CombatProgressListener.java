package com.leadsystems.minecraft.ascension.progress;

import com.leadsystems.minecraft.ascension.ability.TitleAbilityListener;
import com.leadsystems.minecraft.ascension.ability.RankAbilityService;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.ability.RankAbility;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.service.HuntService;
import com.leadsystems.minecraft.ascension.service.RenewalService;
import com.leadsystems.minecraft.ascension.service.TitleXpService;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Validates PvP, raid and boss progression before producing quest facts. Main-thread only. */
public final class CombatProgressListener implements Listener {

    private static final long FIGHT_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long PRUNE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long SUMMON_TTL_MS = TimeUnit.SECONDS.toMillis(10);

    private static final class Fight {
        final Map<UUID, Double> damage = new HashMap<>();
        long lastAt;
    }

    private record PendingSummon(UUID player, UUID world, double x, double y, double z, long at) {}

    private final Logger log;
    private final ProgressTracker progress;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final MainThread main;
    private final Supplier<ConfigSnapshot> config;
    private final HuntService hunts;
    private final RenewalService renewals;
    private final TitleAbilityListener titleAbilities;
    private final RankAbilityService rankAbilities;
    private final Map<UUID, Fight> fights = new HashMap<>();
    private final Map<UUID, UUID> witherSummoners = new HashMap<>();
    private PendingSummon pendingSummon;
    private long lastPruneAt;

    public CombatProgressListener(Logger log, ProgressTracker progress, StorageExecutor storage,
                                  PlayerRepository repo, MainThread main,
                                  Supplier<ConfigSnapshot> config, HuntService hunts, RenewalService renewals,
                                  TitleAbilityListener titleAbilities, RankAbilityService rankAbilities) {
        this.log = log;
        this.progress = progress;
        this.storage = storage;
        this.repo = repo;
        this.main = main;
        this.config = config;
        this.hunts = hunts;
        this.renewals = renewals;
        this.titleAbilities = titleAbilities;
        this.rankAbilities = rankAbilities;
    }

    /** Compatibility constructor for integrations that do not enable rank renewal. */
    public CombatProgressListener(Logger log, ProgressTracker progress, StorageExecutor storage,
                                  PlayerRepository repo, MainThread main, Supplier<ConfigSnapshot> config,
                                  HuntService hunts, TitleAbilityListener titleAbilities, RankAbilityService rankAbilities) {
        this(log, progress, storage, repo, main, config, hunts, null, titleAbilities, rankAbilities);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || event.getFinalDamage() <= 0) {
            return;
        }
        Player attacker = Attribution.player(event.getDamager());
        if (attacker == null && event.getDamageSource().getCausingEntity() instanceof Entity source) {
            attacker = Attribution.player(source);
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        double meaningful = Math.min(event.getFinalDamage(), Math.max(0.0, victim.getHealth()));
        if (meaningful <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Fight fight = fights.computeIfAbsent(victim.getUniqueId(), ignored -> new Fight());
        fight.damage.merge(attacker.getUniqueId(), meaningful, Double::sum);
        fight.lastAt = now;
        if (now - lastPruneAt >= PRUNE_INTERVAL_MS) {
            prune(now);
            lastPruneAt = now;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Fight fight = fights.remove(victim.getUniqueId());
        Player killer = victim.getKiller();
        if (killer == null) {
            killer = Attribution.player(event.getDamageSource().getCausingEntity());
        }
        if (killer == null || !validPlayer(killer) || !validPlayer(victim) || !victim.getWorld().getPVP()
                || sameTeam(killer, victim)) {
            return;
        }
        double dealt = fight == null ? 0.0 : fight.damage.getOrDefault(killer.getUniqueId(), 0.0);
        double needed = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                * config.get().settings().minimumParticipation();
        if (dealt + 1.0e-9 < needed) {
            return;
        }
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();
        long now = System.currentTimeMillis();
        boolean nighttime = victim.getWorld().getTime() >= 13_000 && victim.getWorld().getTime() < 23_000;
        boolean storming = victim.getWorld().hasStorm() || victim.getWorld().isThundering();
        long cooldown = TimeUnit.HOURS.toMillis(config.get().settings().sameVictimCooldownHours());
        boolean rejectSameIp = config.get().settings().rejectSameIp();
        PlayerSession sourceSession = progress.captureSession(killer);
        TitleXpService.Source titleSource = progress.captureTitleSource(killer);
        Fact.PlayerKilled killFact = new Fact.PlayerKilled(victimId.toString());
        Map<String, Long> xpBonus = progress.titleXpBonus(titleSource, killFact);
        main.onComplete(storage.submit("pvp credit", c -> repo.creditPlayerKill(c, killerId, victimId, now,
                cooldown, rejectSameIp, xpBonus)), (credited, error) -> {
            Player online = Bukkit.getPlayer(killerId);
            if (error != null) {
                log.warning("PvP credit failed for " + killerId + ": " + error);
            } else if (Boolean.TRUE.equals(credited)) {
                progress.applyCommittedTitleXp(titleSource, killFact);
                // Hunt XP retains the title equipped at this kill, even if the hunter has left.
                hunts.onValidKill(killerId, victimId, titleSource, sourceSession);
                if (online != null && progress.isCurrentSession(online, sourceSession)) {
                    progress.recordAfterTitleXpCommit(online, killFact);
                    afterCreditedPlayerKill(online, victimId, nighttime, storming);
                }
            }
        });
    }

    void onCreditedPlayerKill(Player killer, UUID victimId, boolean nighttime, boolean storming) {
        progress.record(killer, new Fact.PlayerKilled(victimId.toString()));
        afterCreditedPlayerKill(killer, victimId, nighttime, storming);
        hunts.onValidKill(killer, victimId);
    }

    private void afterCreditedPlayerKill(Player killer, UUID victimId, boolean nighttime, boolean storming) {
        if (nighttime) {
            progress.record(killer, new Fact.Statistic("pvp.nighttime_kills", 1));
        }
        if (storming) {
            progress.record(killer, new Fact.Statistic("pvp.storm_kills", 1));
        }
        rankAbilities.trigger(killer, RankAbility.Trigger.PVP_KILL);
        titleAbilities.onValidPlayerKill(killer);
        if (renewals != null) renewals.onValidPlayerKill(killer, victimId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaidFinish(RaidFinishEvent event) {
        for (Player winner : event.getWinners()) {
            if (validPlayer(winner)) {
                progress.record(winner, new Fact.RaidCompleted());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Fight fight = fights.remove(dead.getUniqueId());
        if (!boss(dead.getType())) {
            return;
        }
        if (fight == null) {
            witherSummoners.remove(dead.getUniqueId());
            return;
        }
        Player killer = dead.getKiller();
        if (killer == null) {
            killer = Attribution.player(event.getDamageSource().getCausingEntity());
        }
        UUID killerId = killer == null ? null : killer.getUniqueId();
        UUID summoner = witherSummoners.remove(dead.getUniqueId());
        double needed = dead.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                * config.get().settings().minimumParticipation();
        for (Map.Entry<UUID, Double> contribution : fight.damage.entrySet()) {
            if (contribution.getValue() + 1.0e-9 < needed) {
                continue;
            }
            Player player = Bukkit.getPlayer(contribution.getKey());
            if (player != null && validPlayer(player)) {
                progress.record(player, new Fact.BossDefeated(dead.getType().name(),
                        contribution.getKey().equals(killerId), contribution.getKey().equals(summoner)));
            }
        }
    }

    /** Paper exposes no Wither summoner, so associate a recent skull placement with the spawn. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWitherSkullPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (type == Material.WITHER_SKELETON_SKULL || type == Material.WITHER_SKELETON_WALL_SKULL) {
            var loc = event.getBlockPlaced().getLocation();
            pendingSummon = new PendingSummon(event.getPlayer().getUniqueId(), loc.getWorld().getUID(),
                    loc.getX(), loc.getY(), loc.getZ(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWitherSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.WITHER || !event.getSpawnReason().name().equals("BUILD_WITHER")) {
            return;
        }
        PendingSummon p = pendingSummon;
        pendingSummon = null;
        if (p == null || System.currentTimeMillis() - p.at() > SUMMON_TTL_MS
                || !p.world().equals(event.getLocation().getWorld().getUID())) {
            return;
        }
        double dx = p.x() - event.getLocation().getX();
        double dy = p.y() - event.getLocation().getY();
        double dz = p.z() - event.getLocation().getZ();
        if (dx * dx + dy * dy + dz * dz <= 256.0) {
            witherSummoners.put(event.getEntity().getUniqueId(), p.player());
        }
    }

    private void prune(long now) {
        Iterator<Map.Entry<UUID, Fight>> it = fights.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastAt > FIGHT_TTL_MS) {
                it.remove();
            }
        }
        if (pendingSummon != null && now - pendingSummon.at() > SUMMON_TTL_MS) {
            pendingSummon = null;
        }
    }

    private static boolean validPlayer(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private static boolean sameTeam(Player first, Player second) {
        return sameTeamOn(first, second, first) || sameTeamOn(first, second, second);
    }

    private static boolean sameTeamOn(Player first, Player second, Player scoreboardOwner) {
        Team firstTeam = scoreboardOwner.getScoreboard().getEntryTeam(first.getName());
        return firstTeam != null
                && firstTeam.equals(scoreboardOwner.getScoreboard().getEntryTeam(second.getName()));
    }

    private static boolean boss(EntityType type) {
        return type == EntityType.WARDEN || type == EntityType.WITHER
                || type == EntityType.ELDER_GUARDIAN || type == EntityType.RAVAGER;
    }
}
