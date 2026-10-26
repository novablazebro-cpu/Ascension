package com.leadsystems.minecraft.ascension.config;

import java.time.ZoneId;
import java.util.Set;

/**
 * Parsed config.yml. Immutable.
 *
 * @param zone                     time zone for daily streak days
 * @param flushIntervalSeconds     batched progress save interval
 * @param bookTitle                Chronicle book title
 * @param bookAuthor               Chronicle book author
 * @param openCooldownMs           minimum time between menu opens
 * @param sweepPlayersPerSecond    safety-net inventory reconcile rate
 * @param ignoreCreative           creative/spectator players make no progress
 * @param countSpawnerMobs         spawner mobs count toward kill objectives
 * @param hostileExtra             extra entity types treated as hostile
 * @param hostileExclude           entity types never treated as hostile
 * @param netherMobs               entity types in the "nether" category
 * @param trackedPlacedBlocks      materials whose placement is remembered (config + quest targets)
 * @param trackedObtainItems       materials whose natural acquisition is counted (quest obtain targets)
 * @param progressActionBar        show objective progress on the action bar
 * @param doubleCrouchWindowMs     double-crouch detection window
 * @param lookDownMinPitch         minimum pitch for look-down-crouch
 * @param combatWindowSeconds      out-of-combat time before COMBAT_ENTER can fire again
 * @param sameVictimCooldownHours  persisted PvP credit cooldown for one killer/victim pair
 * @param minimumParticipation     required share of tracked damage for PvP and boss credit
 * @param rejectSameIp             reject PvP progress between accounts with the same IP hash
 * @param huntRerollOfflineHours   target offline duration before player reroll
 * @param huntRepeatAvoidanceDays  completed-target history window
 * @param deathlessMinutesPerDay   active minutes needed to qualify one calendar day
 * @param activityTimeoutMinutes   inactivity window before online time stops counting
 * @param stormbringerRange        lightning target radius
 * @param stormbringerDamage       final requested lightning damage in health points
 * @param vampireRatio             legacy config value; titles.yml controls live Bloodlord ratio
 * @param vampireMaxHeal           Bloodlord maximum health restored per hit
 * @param undyingHealth            health left by Refuse Death
 * @param undyingImmunitySeconds   post-resurrection no-damage time
 * @param effectParticles          show particles on plugin effects
 * @param activationItemMaterial   material used for the owner-bound title relic
 * @param activationItemDisplayName display name used for the title relic
 * @param activationItemGlint      whether the title relic has an enchantment glint
 */
public record Settings(
        ZoneId zone,
        int flushIntervalSeconds,
        String bookTitle,
        String bookAuthor,
        long openCooldownMs,
        int sweepPlayersPerSecond,
        boolean ignoreCreative,
        boolean countSpawnerMobs,
        Set<String> hostileExtra,
        Set<String> hostileExclude,
        Set<String> netherMobs,
        Set<String> trackedPlacedBlocks,
        Set<String> trackedObtainItems,
        boolean progressActionBar,
        long doubleCrouchWindowMs,
        float lookDownMinPitch,
        int combatWindowSeconds,
        int sameVictimCooldownHours,
        double minimumParticipation,
        boolean rejectSameIp,
        int huntRerollOfflineHours,
        int huntRepeatAvoidanceDays,
        int deathlessMinutesPerDay,
        int activityTimeoutMinutes,
        double stormbringerRange,
        double stormbringerDamage,
        double vampireRatio,
        double vampireMaxHeal,
        double undyingHealth,
        int undyingImmunitySeconds,
        boolean effectParticles,
        String activationItemMaterial,
        String activationItemDisplayName,
        boolean activationItemGlint
) {
    public Settings {
        hostileExtra = Set.copyOf(hostileExtra);
        hostileExclude = Set.copyOf(hostileExclude);
        netherMobs = Set.copyOf(netherMobs);
        trackedPlacedBlocks = Set.copyOf(trackedPlacedBlocks);
        trackedObtainItems = Set.copyOf(trackedObtainItems);
    }
}
