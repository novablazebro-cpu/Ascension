package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.domain.title.TitleAbilityRegistry;
import com.leadsystems.minecraft.ascension.domain.cooldown.CooldownBook;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleAbilityListenerTest {

    @Test
    void chainMultiplierChangesOnlySecondaryDamage() {
        assertEquals(4.0, TitleAbilityListener.chainDamage(8.0, 0.5));
        assertEquals(16.0, TitleAbilityListener.chainDamage(8.0, 2.0));
    }

    @Test
    void chainLightningRangeIsSphericalForBothHops() {
        World world = mock(World.class);
        Location center = new Location(world, 0, 64, 0);
        assertTrue(TitleAbilityListener.withinRadius(center, new Location(world, 8, 64, 8), 20));
        assertFalse(TitleAbilityListener.withinRadius(center, new Location(world, 15, 64, 15), 20));
        assertTrue(TitleAbilityListener.withinRadius(center, new Location(world, 6, 64, 5), 8));
        assertFalse(TitleAbilityListener.withinRadius(center, new Location(world, 7, 64, 7), 8));
    }

    @Test
    void treasurePulseRangeUsesSphereIncludingAxisBoundary() {
        World world = mock(World.class);
        Location cast = new Location(world, 0.5, 64.5, 0.5);
        assertTrue(TitleAbilityListener.withinRadius(cast, new Location(world, 16.5, 64.5, 0.5), 16));
        assertFalse(TitleAbilityListener.withinRadius(cast, new Location(world, 17.5, 64.5, 0.5), 16));
        assertFalse(TitleAbilityListener.withinRadius(cast, new Location(world, 16.5, 64.5, 16.5), 16));
        assertTrue(TitleAbilityListener.withinRadius(cast, new Location(world, 0.5, 80.5, 0.5), 16));
    }

    @Test
    void sporeCloudHitWindowsBelongToEachCloud() {
        java.util.UUID victim = java.util.UUID.randomUUID();
        Map<java.util.UUID, Long> firstCloud = new java.util.HashMap<>();
        Map<java.util.UUID, Long> secondCloud = new java.util.HashMap<>();
        firstCloud.put(victim, 10_000L);
        assertFalse(TitleAbilityListener.sporeHitReady(firstCloud, victim, 10_001L));
        assertTrue(TitleAbilityListener.sporeHitReady(secondCloud, victim, 10_001L));
        assertTrue(TitleAbilityListener.sporeHitReady(firstCloud, victim, 10_900L));
    }

    @Test
    void harmfulExpandedPotionExcludesTeammatesAndDisabledPvp() {
        World world = mock(World.class);
        Player owner = mock(Player.class);
        Player target = mock(Player.class);
        Scoreboard board = mock(Scoreboard.class);
        org.bukkit.scoreboard.Team team = mock(org.bukkit.scoreboard.Team.class);
        when(owner.getWorld()).thenReturn(world);
        when(owner.getName()).thenReturn("Owner");
        when(target.getName()).thenReturn("Target");
        when(target.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(target.getWorld()).thenReturn(world);
        when(owner.getScoreboard()).thenReturn(board);
        when(target.getScoreboard()).thenReturn(board);
        when(world.getPVP()).thenReturn(false);
        assertFalse(TitleAbilityListener.expandedPotionTargetAllowed(owner, target, true));
        when(world.getPVP()).thenReturn(true);
        when(board.getEntryTeam("Owner")).thenReturn(team);
        when(board.getEntryTeam("Target")).thenReturn(team);
        assertFalse(TitleAbilityListener.expandedPotionTargetAllowed(owner, target, true));
        when(board.getEntryTeam("Target")).thenReturn(null);
        assertTrue(TitleAbilityListener.expandedPotionTargetAllowed(owner, target, true));
        when(target.getWorld()).thenReturn(mock(World.class));
        assertFalse(TitleAbilityListener.expandedPotionTargetAllowed(owner, target, true));
        assertTrue(TitleAbilityListener.expandedPotionTargetAllowed(owner, target, false));
    }

    @Test
    void deepLureStillProvidesTreasureIfTheLootTableReturnsNothing() {
        assertEquals(Material.NAUTILUS_SHELL,
                TitleAbilityListener.treasureOrFallback(java.util.List.of()).getType());
    }

    @Test
    void unrelatedDistantRaidDoesNotSuppressGroupedHostileMode() {
        World world = mock(World.class);
        Location origin = new Location(world, 0, 64, 0);
        var raider = mock(org.bukkit.entity.Raider.class);
        when(raider.getRaid()).thenReturn(mock(org.bukkit.Raid.class));
        when(raider.getLocation()).thenReturn(new Location(world, 90, 64, 0));
        assertFalse(TitleAbilityListener.hasParticipatingRaiders(java.util.List.of(raider), origin, 8));
        when(raider.getLocation()).thenReturn(new Location(world, 3, 64, 0));
        assertTrue(TitleAbilityListener.hasParticipatingRaiders(java.util.List.of(raider), origin, 8));
    }

    @Test
    void naturalChestLootUsesTheActualRecentOpenerOnceEvenWithoutLootEventEntity() {
        var sessions = mock(com.leadsystems.minecraft.ascension.session.SessionManager.class);
        var config = mock(com.leadsystems.minecraft.ascension.config.ConfigSnapshot.class);
        var listener = new TitleAbilityListener(sessions, () -> config, null, null, null, null, null, null);
        Player opener = mock(Player.class);
        var live = mock(com.leadsystems.minecraft.ascension.session.PlayerSession.class);
        var block = mock(Block.class);
        var otherBlock = mock(Block.class);
        var chest = mock(org.bukkit.block.Chest.class);
        var interaction = mock(org.bukkit.event.player.PlayerInteractEvent.class);
        var openerId = java.util.UUID.randomUUID();
        when(sessions.get(opener)).thenReturn(live);
        when(live.usable()).thenReturn(true);
        when(live.equippedTitle()).thenReturn("treasure_whisperer");
        when(live.ownedTitles()).thenReturn(Set.of("treasure_whisperer"));
        when(opener.getUniqueId()).thenReturn(openerId);
        when(interaction.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK);
        when(interaction.getHand()).thenReturn(org.bukkit.inventory.EquipmentSlot.HAND);
        when(interaction.getPlayer()).thenReturn(opener);
        when(interaction.getClickedBlock()).thenReturn(block);
        when(block.getState()).thenReturn(chest);
        when(chest.getLootTable()).thenReturn(mock(org.bukkit.loot.LootTable.class));

        listener.onTreasureOpen(interaction);
        assertEquals(null, listener.consumeTreasureOpen(otherBlock, System.currentTimeMillis()));
        assertEquals(openerId, listener.consumeTreasureOpen(block, System.currentTimeMillis()));
        assertEquals(null, listener.consumeTreasureOpen(block, System.currentTimeMillis()),
                "one natural container can receive at most one bonus roll");

        listener.recordTreasureOpen(block, openerId, 1_000L);
        assertEquals(null, listener.consumeTreasureOpen(block, 6_001L), "stale opens cannot attribute loot");
    }

    @Test
    void itemActivationIsUsedOnlyForManualAbilities() {
        assertEquals(19, TitleAbilityListener.manualTitleIds().size());
        assertEquals(11, TitleAbilityRegistry.AUTOMATIC.size());
        assertEquals(30, TitleAbilityRegistry.ALL.size());
        assertTrue(TitleAbilityListener.isManualTitle("titan"));
        assertTrue(TitleAbilityListener.isManualTitle("stormbringer"));
        assertFalse(TitleAbilityListener.isManualTitle("bloodlord"));
        assertFalse(TitleAbilityListener.isManualTitle("undying"));
    }

    @Test
    void everyBundledTitleHasExactlyOneDocumentedActivationMode() {
        var input = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("titles.yml"));
        var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        Set<String> configured = new HashSet<>();
        for (Map<?, ?> title : yaml.getMapList("titles")) {
            String id = String.valueOf(title.get("id"));
            configured.add(id);
            Map<?, ?> ability = (Map<?, ?>) title.get("ability");
            String description = String.valueOf(ability.get("description")).toLowerCase();
            assertEquals(TitleAbilityRegistry.isManual(id), description.contains("right-click"), id);
        }
        assertEquals(TitleAbilityRegistry.ALL, configured);
    }

    @Test
    void groundSlamAffectsPassiveMobsButNeverItsOwner() {
        Player owner = mock(Player.class);
        Chicken chicken = mock(Chicken.class);

        assertTrue(TitleAbilityListener.validGroundSlamTarget(owner, chicken));
        assertFalse(TitleAbilityListener.validGroundSlamTarget(owner, owner));
        Location center = new Location(null, 0, 64, 0);
        assertTrue(TitleAbilityListener.withinGroundSlamRadius(center, new Location(null, 6, 64, 0), 6));
        assertFalse(TitleAbilityListener.withinGroundSlamRadius(center, new Location(null, 6, 64, 6), 6));
    }

    @Test
    void groundSlamAcceptsAPlayerWhenPvpIsEnabled() {
        Player owner = mock(Player.class);
        Player target = mock(Player.class);
        World world = mock(World.class);
        Scoreboard scoreboard = mock(Scoreboard.class);
        when(owner.getWorld()).thenReturn(world);
        when(world.getPVP()).thenReturn(true);
        when(target.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(owner.getScoreboard()).thenReturn(scoreboard);
        when(target.getScoreboard()).thenReturn(scoreboard);
        when(owner.getName()).thenReturn("Owner");
        when(target.getName()).thenReturn("Target");

        assertTrue(TitleAbilityListener.validGroundSlamTarget(owner, target));
        when(world.getPVP()).thenReturn(false);
        assertFalse(TitleAbilityListener.validGroundSlamTarget(owner, target));
    }
    @Test
    void vampireUsesFinalDamageRatioAndCap() {
        assertEquals(1.0, TitleAbilityListener.vampireHeal(4.0, 0.25, 3.0));
        assertEquals(3.0, TitleAbilityListener.vampireHeal(40.0, 0.25, 3.0));
        assertEquals(0.0, TitleAbilityListener.vampireHeal(-1.0, 0.25, 3.0));
    }

    @Test
    void potionLevelsUseBukkitZeroBasedAmplifiers() {
        assertEquals(600, TitleAbilityListener.effectTicks(30));
        assertEquals(4, TitleAbilityListener.amplifier(5));
        assertEquals(2, TitleAbilityListener.amplifier(3));
        assertEquals(9, TitleAbilityListener.amplifier(10));
        assertEquals(0, TitleAbilityListener.amplifier(1));
    }

    @Test
    void ascendantAppliesExactThirtySecondBuffsEvenWithLegacySettings() {
        Player owner = mock(Player.class);
        TitleDefinition legacy = new TitleDefinition("ascendant", 1, "Ascendant", "#ffffff", "",
                java.util.List.of(), "Ascension", "", 300,
                Map.of("duration-seconds", 15.0, "amplifier", 1.0), false);
        TitleAbilityListener.applyAscensionBuffs(owner, legacy);

        ArgumentCaptor<PotionEffect> effects = ArgumentCaptor.forClass(PotionEffect.class);
        verify(owner, org.mockito.Mockito.times(3)).addPotionEffect(effects.capture());
        Map<PotionEffectType, PotionEffect> byType = effects.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(PotionEffect::getType, effect -> effect));
        assertEquals(4, byType.get(PotionEffectType.STRENGTH).getAmplifier());
        assertEquals(2, byType.get(PotionEffectType.SPEED).getAmplifier());
        assertEquals(3, byType.get(PotionEffectType.RESISTANCE).getAmplifier());
        assertTrue(byType.values().stream().allMatch(effect -> effect.getDuration() == 600));
    }

    @Test
    void livingMetalNeverConsumesMoreThanConfiguredFraction() {
        assertEquals(0, TitleAbilityListener.repairXpSpend(1, 0.25));
        assertEquals(1, TitleAbilityListener.repairXpSpend(4, 0.25));
        assertEquals(2, TitleAbilityListener.repairXpSpend(10, 0.25));
        assertEquals(10, TitleAbilityListener.repairXpSpend(10, 2.0));
        assertEquals(0, TitleAbilityListener.repairXpSpend(10, -1.0));
    }

    @Test
    void awayFromNeverProducesNanEvenWhenTargetAndOwnerOverlap() {
        Player owner = mock(Player.class);
        Chicken target = mock(Chicken.class);
        Location loc = new Location(null, 10, 64, 10, 0f, 90f); // looking straight down
        org.mockito.Mockito.when(owner.getLocation()).thenReturn(loc);
        org.mockito.Mockito.when(target.getLocation()).thenReturn(loc.clone());

        org.bukkit.util.Vector away = TitleAbilityListener.awayFrom(owner, target);
        assertTrue(Double.isFinite(away.getX()));
        assertTrue(Double.isFinite(away.getY()));
        assertTrue(Double.isFinite(away.getZ()));
        assertEquals(1.0, away.length(), 1e-6);
    }

    @Test
    void voidStepRegeneratesEachChargeSixtySecondsAfterItWasSpent() {
        CooldownBook charges = new CooldownBook();
        assertEquals(3, TitleAbilityListener.availableVoidStepCharges(charges, 0));
        String first = TitleAbilityListener.nextVoidStepCharge(charges, 0);
        charges.start(first, 0, 60_000);
        assertEquals(2, TitleAbilityListener.availableVoidStepCharges(charges, 0));
        String second = TitleAbilityListener.nextVoidStepCharge(charges, 10_000);
        charges.start(second, 10_000, 60_000);
        String third = TitleAbilityListener.nextVoidStepCharge(charges, 20_000);
        charges.start(third, 20_000, 60_000);
        assertEquals(0, TitleAbilityListener.availableVoidStepCharges(charges, 20_000));
        assertEquals(null, TitleAbilityListener.nextVoidStepCharge(charges, 59_999));
        assertEquals(1, TitleAbilityListener.availableVoidStepCharges(charges, 60_000));
        assertEquals(first, TitleAbilityListener.nextVoidStepCharge(charges, 60_000));
        assertEquals(2, TitleAbilityListener.availableVoidStepCharges(charges, 70_000));
        assertEquals(3, TitleAbilityListener.availableVoidStepCharges(charges, 80_000));
    }

    @Test
    void voidStepRequiresTwoEmptyBlocksAndAFullSafeFloor() {
        Block feet = mock(Block.class);
        Block head = mock(Block.class);
        Block floor = mock(Block.class);
        when(feet.getRelative(BlockFace.UP)).thenReturn(head);
        when(feet.getRelative(BlockFace.DOWN)).thenReturn(floor);
        when(feet.isEmpty()).thenReturn(true);
        when(head.isEmpty()).thenReturn(true);
        when(floor.getType()).thenReturn(Material.STONE);
        when(floor.getY()).thenReturn(63);
        when(floor.getBoundingBox()).thenReturn(new BoundingBox(0, 63, 0, 1, 64, 1));
        assertTrue(TitleAbilityListener.safeVoidStepFeet(feet));

        when(head.isEmpty()).thenReturn(false);
        assertFalse(TitleAbilityListener.safeVoidStepFeet(feet));
        when(head.isEmpty()).thenReturn(true);
        when(floor.getType()).thenReturn(Material.MAGMA_BLOCK);
        assertFalse(TitleAbilityListener.safeVoidStepFeet(feet));
        when(floor.getType()).thenReturn(Material.STONE);
        when(floor.getBoundingBox()).thenReturn(new BoundingBox(0, 63, 0, 1, 63.5, 1));
        assertFalse(TitleAbilityListener.safeVoidStepFeet(feet));
    }

    @Test
    void rewindWindowIncludesOnlyClicksBeforeExpiry() {
        assertTrue(TitleAbilityListener.rewindSnapshotActive(3_001, 3_000));
        assertFalse(TitleAbilityListener.rewindSnapshotActive(3_000, 3_000));
    }

    @Test
    void deferredAbilityFromEarlierTitleEpochCannotApplyAfterReturningToThatTitle() {
        long activationEpoch = 11;
        long epochAfterAscendantToBToCToAscendant = 14;
        assertFalse(TitleAbilityListener.isCurrentRuntimeEpoch(
                epochAfterAscendantToBToCToAscendant, activationEpoch));
        assertTrue(TitleAbilityListener.isCurrentRuntimeEpoch(
                epochAfterAscendantToBToCToAscendant, epochAfterAscendantToBToCToAscendant));
    }
}
