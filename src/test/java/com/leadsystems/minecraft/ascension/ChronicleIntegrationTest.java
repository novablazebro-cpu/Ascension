package com.leadsystems.minecraft.ascension;

import com.leadsystems.minecraft.ascension.ability.TitleAbilityItems;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.text.Messages;
import com.leadsystems.minecraft.ascension.storage.PlayerRecord;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.SqliteDialect;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Item;
import org.bukkit.entity.Chicken;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behaviour on a mock server with the real plugin, real SQLite and real listeners.
 */
class ChronicleIntegrationTest {

    ServerMock server;
    AscensionPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(AscensionPlugin.class);
        // This gameplay fixture deliberately selects arbitrary ranks/titles to exercise listeners.
        // Production defaults remain quest-locked; permission/ownership gates have separate tests.
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration testConfig = YamlConfiguration.loadConfiguration(configFile);
        testConfig.set("testing.unlock-all-ranks-and-titles", true);
        testConfig.save(configFile);
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "titles reload"));
        assertTrue(plugin.currentConfig().testingUnlockAll());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Lets queued storage work finish and its main-thread callbacks run (several hops deep). */
    void settle() {
        for (int i = 0; i < 6; i++) {
            plugin.storage().submit("test-sync", c -> null).join();
            server.getScheduler().performTicks(2);
        }
    }

    PlayerMock join(String name) {
        PlayerMock p = server.addPlayer(name);
        p.setOp(true); // Required for this fixture's explicit testing-mode title selection.
        settle();
        return p;
    }

    List<ItemStack> chronicles(PlayerMock p) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack i : p.getInventory().getContents()) {
            if (plugin.books().items().isChronicle(i)) {
                out.add(i);
            }
        }
        return out;
    }

    List<ItemStack> abilityItems(PlayerMock player) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (plugin.abilityItems().isActivator(item)) {
                out.add(item);
            }
        }
        return out;
    }

    ItemStack book(PlayerMock p) {
        List<ItemStack> books = chronicles(p);
        assertEquals(1, books.size(), "exactly one Chronicle expected");
        return books.get(0);
    }

    PlayerRecord dbRecord(UUID uuid) {
        PlayerRepository repo = new PlayerRepository(new SqliteDialect());
        return plugin.storage().submit("test-read", c -> repo.find(c, uuid)).join();
    }

    // ------------------------------------------------------------------ the book

    @Test
    void ascendantCannotRecastAfterCyclingTitlesAndDatabaseResetAppliesImmediately() {
        PlayerMock player = join("CooldownTester");
        assertTrue(player.performCommand("title equip ascendant"));
        settle();
        PlayerSession session = plugin.sessions().get(player);
        var listener = titleListener();
        // Mock only presentation: the real session, SQLite, title commands and buffs remain active.
        PlayerMock caster = Mockito.spy(player);
        Mockito.doReturn(Mockito.mock(player.getWorld().getClass())).when(caster).getWorld();
        String key = "title.ascendant.ascension";
        listener.activateFromItem(caster, session);
        settle();
        assertNotNull(player.getPotionEffect(PotionEffectType.STRENGTH));
        PlayerRepository repository = new PlayerRepository(new SqliteDialect());
        long expiry = plugin.storage().submit("test-cooldown", c ->
                repository.loadCooldowns(c, player.getUniqueId()).get(key)).join();
        assertTrue(expiry - System.currentTimeMillis() > 290_000);

        for (String title : List.of("conqueror", "stormbringer", "ascendant")) {
            assertTrue(player.performCommand("title equip " + title));
            settle();
        }
        player.removePotionEffect(PotionEffectType.STRENGTH);
        listener.activateFromItem(caster, plugin.sessions().get(player));
        assertEquals(null, player.getPotionEffect(PotionEffectType.STRENGTH),
                "A second cast must be refused, not just show an old timer");
        assertEquals(expiry, plugin.storage().submit("test-cooldown-after-switch", c ->
                repository.loadCooldowns(c, player.getUniqueId()).get(key)).join());
        assertFalse(session.cooldowns().isReady(key, System.currentTimeMillis()));

        session.cooldowns().start("title.conqueror.war_cry", System.currentTimeMillis(), 60_000);
        plugin.sessions().mutate(player.getUniqueId(), "test-admin-reset-one", c -> {
            repository.adminResetTitleCooldowns(c, player.getUniqueId(), "ascendant");
            return null;
        }, (value, error) -> assertEquals(null, error));
        settle();
        assertTrue(session.cooldowns().isReady(key, System.currentTimeMillis()));
        assertFalse(session.cooldowns().isReady("title.conqueror.war_cry", System.currentTimeMillis()));
        listener.activateFromItem(caster, session);
        assertNotNull(player.getPotionEffect(PotionEffectType.STRENGTH));
        plugin.sessions().mutate(player.getUniqueId(), "test-admin-reset-all", c -> {
            repository.adminResetTitleCooldowns(c, player.getUniqueId(), null);
            return null;
        }, (value, error) -> assertEquals(null, error));
        settle();
        assertTrue(session.cooldowns().isReady(key, System.currentTimeMillis()));
        assertTrue(session.cooldowns().isReady("title.conqueror.war_cry", System.currentTimeMillis()));
        listener.activateFromItem(caster, session);
        settle();
        server.getPluginManager().callEvent(new PlayerDeathEvent(player, Mockito.mock(DamageSource.class),
                new ArrayList<>(), 0, Component.empty()));
        assertFalse(session.cooldowns().isReady(key, System.currentTimeMillis()));
        // Recreate the session through the real save/load lifecycle, as on reconnect or restart.
        plugin.sessions().quit(player);
        settle();
        plugin.sessions().join(player);
        settle();
        assertFalse(plugin.sessions().get(player).cooldowns().isReady(key, System.currentTimeMillis()));
        PlayerMock other = join("IndependentCooldown");
        assertTrue(plugin.sessions().get(other).cooldowns().isReady(key, System.currentTimeMillis()));
    }

    @Test
    void joiningPlayerReceivesExactlyOneValidChronicle() {
        PlayerMock p = join("Alex");
        PlayerSession s = plugin.sessions().get(p);
        assertNotNull(s);
        assertTrue(s.loaded());
        assertTrue(plugin.books().items().isValidFor(book(p), s));
    }

    @Test
    void bedrockChronicleUsesPlainBookSoUseReachesTheServer() {
        PlayerSession bedrock = new PlayerSession(UUID.randomUUID(), "TabletTester", true);
        Messages messages = new Messages();
        messages.load(plugin.currentConfig().messages());

        ItemStack item = plugin.books().items().create(bedrock, plugin.currentConfig().settings(), messages);

        assertEquals(Material.BOOK, item.getType());
        assertTrue(plugin.books().items().isValidFor(item, bedrock));
    }

    @Test
    void testingModeAllowsAnyRankAndTitleWithNametagSuffix() {
        PlayerMock player = join("BetaTester");

        assertTrue(player.performCommand("rank test platinum 3"));
        settle();
        assertEquals(11, plugin.sessions().get(player).rankIndex());

        assertTrue(player.performCommand("title equip ascendant"));
        settle();
        assertEquals("ascendant", plugin.sessions().get(player).equippedTitle());
        Team team = server.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());
        assertNotNull(team);
        assertTrue(team.getName().startsWith("asc_"));
        assertEquals(" [Ascendant Lv.1]", Messages.plain(team.suffix()));
    }

    @Test
    void equippingReplacesOneOwnerBoundAbilityItemAndUnequippingRemovesIt() {
        PlayerMock player = join("AbilityTester");

        assertTrue(player.performCommand("title equip ascendant"));
        settle();
        assertEquals(1, abilityItems(player).size());
        assertTrue(plugin.abilityItems().isValidFor(abilityItems(player).get(0), plugin.sessions().get(player)));

        assertTrue(player.performCommand("title equip titan"));
        settle();
        assertEquals("titan", plugin.sessions().get(player).equippedTitle());
        assertEquals(1, abilityItems(player).size(), "switching titles must replace rather than duplicate the stick");
        assertTrue(plugin.abilityItems().isValidFor(abilityItems(player).get(0), plugin.sessions().get(player)));
        ItemStack oldTitanStick = abilityItems(player).get(0).clone();

        assertTrue(player.performCommand("title equip bloodlord"));
        settle();
        assertEquals("bloodlord", plugin.sessions().get(player).equippedTitle());
        assertTrue(abilityItems(player).isEmpty(), "automatic abilities must not issue a manual activation stick");
        assertFalse(plugin.abilityItems().isValidFor(oldTitanStick, plugin.sessions().get(player)));

        assertTrue(player.performCommand("title equip titan"));
        settle();
        assertEquals(1, abilityItems(player).size());

        assertTrue(player.performCommand("title unequip"));
        settle();
        assertTrue(abilityItems(player).isEmpty());
    }

    @Test
    void rightClickingManualTitleStickRoutesOneActivation() {
        PlayerMock player = join("StickTester");
        assertTrue(player.performCommand("title equip ascendant"));
        settle();
        ItemStack stick = abilityItems(player).get(0);
        PlayerSession session = plugin.sessions().get(player);
        assertTrue(plugin.abilityItems().isValidFor(stick, session));
        Messages messages = new Messages();
        messages.load(plugin.currentConfig().messages());
        AtomicInteger activations = new AtomicInteger();
        TitleAbilityItems probe = new TitleAbilityItems(plugin, plugin.sessions(), plugin::currentConfig,
                messages, (owner, live) -> {
                    assertEquals(player, owner);
                    assertEquals(session, live);
                    activations.incrementAndGet();
                }, new MainThread(plugin));

        PlayerInteractEvent use = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, stick,
                null, BlockFace.SELF, EquipmentSlot.HAND);
        probe.onUse(use);
        probe.onUse(use);

        assertEquals(1, activations.get());
        assertEquals(Event.Result.DENY, use.useItemInHand());
    }

    @Test
    void conquerorStickDebuffsAnotherPlayer() {
        PlayerMock owner = join("ConquerorTester");
        PlayerMock victim = join("ConquerorTarget");
        owner.getWorld().setPVP(true);
        victim.teleport(owner.getLocation());
        assertTrue(owner.performCommand("title equip conqueror"));
        settle();
        ItemStack stick = abilityItems(owner).get(0);
        server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR, stick,
                null, BlockFace.SELF, EquipmentSlot.HAND));

        assertNotNull(victim.getPotionEffect(PotionEffectType.WEAKNESS));
        assertEquals(1, victim.getPotionEffect(PotionEffectType.WEAKNESS).getAmplifier());
        assertEquals(3, victim.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier());
    }

    @Test
    void warCryDoesNotDebuffPlayersOutsideItsSphericalRadius() {
        PlayerMock owner = join("WarCryOwner");
        PlayerMock victim = join("WarCryCorner");
        owner.getWorld().setPVP(true);
        victim.teleport(owner.getLocation().clone().add(9, 0, 9));
        assertTrue(owner.performCommand("title equip conqueror"));
        settle();

        server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));

        assertFalse(victim.hasPotionEffect(PotionEffectType.WEAKNESS));
        assertFalse(victim.hasPotionEffect(PotionEffectType.SLOWNESS));
    }

    @Test
    void stormbringerStickDamagesAnotherPlayer() {
        PlayerMock owner = join("StormTester");
        PlayerMock victim = join("StormTarget");
        owner.getWorld().setPVP(true);
        victim.teleport(owner.getLocation());
        victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(40);
        victim.setHealth(40);
        assertTrue(owner.performCommand("title equip stormbringer"));
        assertTrue(victim.performCommand("title equip conqueror"));
        settle();
        double before = victim.getHealth();

        server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));

        assertEquals(before - 26, victim.getHealth(), 1.0e-9,
                "lightning ability must deal 13 hearts of raw damage");
    }

    @Test
    void titanStickDamagesAnotherPlayer() {
        PlayerMock owner = join("TitanTester");
        PlayerMock victim = join("TitanTarget");
        owner.getWorld().setPVP(true);
        victim.teleport(owner.getLocation());
        victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(40);
        victim.setHealth(40);
        assertTrue(owner.performCommand("title equip titan"));
        assertTrue(victim.performCommand("title equip conqueror"));
        settle();
        double before = victim.getHealth();

        try {
            server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                    abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) {
            // MockBukkit does not render the slam's follow-up particles.
        }

        assertEquals(before - 26, victim.getHealth(), 1.0e-9,
                "ground slam must deal 13 hearts of raw damage");
    }

    @Test
    void titanGroundSlamAlsoDamagesPassiveMobsInRange() {
        PlayerMock owner = join("TitanFarmTester");
        Chicken chicken = owner.getWorld().spawn(owner.getLocation().clone().add(1, 0, 0), Chicken.class);
        assertTrue(owner.performCommand("title equip titan"));
        settle();
        double before = chicken.getHealth();

        try {
            server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                    abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) {
            // MockBukkit does not render the slam's follow-up particles.
        }

        assertTrue(chicken.getHealth() < before || chicken.isDead(),
                "a passive mob inside Ground Slam's radius should take damage");
    }

    @Test
    void ashbornPulseDamagesAPlayerWithoutRequiringTheOwnerToBurn() {
        assertCombatPulseHitsPlayer("ashborn");
    }

    @Test
    void frostNovaDamagesAnEligiblePlayer() {
        assertCombatPulseHitsPlayer("frostbound");
    }

    @Test
    void colossusIsPassiveAndSwitchesEquipment() {
        PlayerMock player = join("ColossusOwner");
        assertTrue(player.performCommand("title equip colossus"));
        settle();
        assertEquals("colossus", plugin.sessions().get(player).equippedTitle());
        assertTrue(abilityItems(player).isEmpty());

        assertTrue(player.performCommand("title equip bloodlord"));
        settle();
        assertEquals("bloodlord", plugin.sessions().get(player).equippedTitle());
    }

    @Test
    void platinumCrouchAbilityActivatesAndCoolsDown() {
        PlayerMock player = join("PlatinumOwner");
        assertTrue(player.performCommand("rank test platinum 3"));
        settle();

        var down = player.getLocation();
        down.setPitch(90);
        player.teleport(down);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerToggleSneakEvent(player, true));
        assertNotNull(player.getPotionEffect(PotionEffectType.RESISTANCE));
        assertTrue(plugin.sessions().get(player).cooldowns().remainingMillis("rank.platinum_3.1",
                System.currentTimeMillis()) > 0);

        assertTrue(player.performCommand("rank test bronze 1"));
        settle();
        assertEquals(0, plugin.sessions().get(player).rankIndex());
    }

    @Test
    void damageAndLowHealthRankTriggersApplyTheirEffects() {
        PlayerMock player = join("ReactiveRankOwner");
        assertTrue(player.performCommand("rank test bronze 3"));
        settle();
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityDamageEvent(player,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.CUSTOM, 2));
        assertNotNull(player.getPotionEffect(PotionEffectType.RESISTANCE));
        assertTrue(plugin.sessions().get(player).cooldowns().remainingMillis("rank.bronze_3.0",
                System.currentTimeMillis()) > 0);

        assertTrue(player.performCommand("rank test gold 2"));
        settle();
        player.setHealth(9);
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityDamageEvent(player,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.CUSTOM, 1));
        player.setHealth(7);
        server.getScheduler().performTicks(2);
        assertNotNull(player.getPotionEffect(PotionEffectType.REGENERATION));
        assertTrue(plugin.sessions().get(player).cooldowns().remainingMillis("rank.gold_2.0",
                System.currentTimeMillis()) > 0);
    }

    @Test
    void goldLowHealthWaitsUntilAbsorptionHeartsActuallyFallBelowThreshold() {
        PlayerMock player = join("GoldenGoldOwner");
        assertTrue(player.performCommand("rank test gold 2"));
        settle();
        player.setHealth(6);
        player.setAbsorptionAmount(4);

        var listener = rankAbilityListener();
        var absorbedHit = Mockito.mock(org.bukkit.event.entity.EntityDamageEvent.class);
        Mockito.when(absorbedHit.getEntity()).thenReturn(player);
        Mockito.when(absorbedHit.getDamage()).thenReturn(2.0);
        Mockito.when(absorbedHit.getFinalDamage()).thenReturn(0.0);

        listener.onDamaged(absorbedHit);
        server.getScheduler().performTicks(2);
        assertEquals(0, plugin.sessions().get(player).cooldowns().remainingMillis("rank.gold_2.0",
                System.currentTimeMillis()), "a fully blocked hit must not activate the rank");

        listener.onDamaged(absorbedHit);
        player.setAbsorptionAmount(3);
        server.getScheduler().performTicks(2);
        assertEquals(0, plugin.sessions().get(player).cooldowns().remainingMillis("rank.gold_2.0",
                System.currentTimeMillis()), "9/20 effective health is still above the threshold");

        listener.onDamaged(absorbedHit);
        player.setAbsorptionAmount(1);
        server.getScheduler().performTicks(2);
        assertNotNull(player.getPotionEffect(PotionEffectType.REGENERATION));
        assertTrue(plugin.sessions().get(player).cooldowns().remainingMillis("rank.gold_2.0",
                System.currentTimeMillis()) > 0, "7/20 effective health should activate the rank");
    }

    @Test
    void goldLowHealthRechecksWhenGoldenAppleAbsorptionExpires() {
        PlayerMock player = join("ExpiringGoldOwner");
        assertTrue(player.performCommand("rank test gold 2"));
        settle();
        player.setHealth(7);
        player.setAbsorptionAmount(4);
        var removed = Mockito.mock(org.bukkit.event.entity.EntityPotionEffectEvent.class);
        Mockito.when(removed.getEntity()).thenReturn(player);
        Mockito.when(removed.getAction()).thenReturn(
                org.bukkit.event.entity.EntityPotionEffectEvent.Action.REMOVED);
        Mockito.when(removed.getCause()).thenReturn(
                org.bukkit.event.entity.EntityPotionEffectEvent.Cause.EXPIRATION);
        Mockito.when(removed.getOldEffect()).thenReturn(new org.bukkit.potion.PotionEffect(
                PotionEffectType.ABSORPTION, 1, 0));

        rankAbilityListener().onEffectRemoved(removed);
        player.setAbsorptionAmount(0);
        server.getScheduler().performTicks(2);

        assertNotNull(player.getPotionEffect(PotionEffectType.REGENERATION));
        assertTrue(plugin.sessions().get(player).cooldowns().remainingMillis("rank.gold_2.0",
                System.currentTimeMillis()) > 0);
    }

    @Test
    void bloodlordHealsOnEveryHitBelowFullHealthEvenWithAbsorption() {
        PlayerMock owner = join("HealthTitleOwner");
        PlayerMock victim = join("HealthTitleVictim");
        owner.getWorld().setPVP(true);
        var listener = titleListener();

        assertTrue(owner.performCommand("title equip bloodlord"));
        settle();
        owner.setHealth(7);
        owner.setAbsorptionAmount(4);
        var hit = Mockito.mock(EntityDamageByEntityEvent.class);
        Mockito.when(hit.getEntity()).thenReturn(victim);
        Mockito.when(hit.getDamager()).thenReturn(owner);
        Mockito.when(hit.getFinalDamage()).thenReturn(5.0);
        Mockito.when(hit.getDamage()).thenReturn(5.0);
        listener.onVampireDamage(hit);
        assertEquals(8, owner.getHealth(), 1.0e-9);
        assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.bloodlord.vampire", System.currentTimeMillis()));

        owner.setAbsorptionAmount(0);
        listener.onVampireDamage(hit);
        assertEquals(9, owner.getHealth(), 1.0e-9);
        assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.bloodlord.vampire", System.currentTimeMillis()));
    }

    @Test
    void bloodlordHealsFromARealPlayerDamageEvent() {
        PlayerMock owner = join("VampireAttacker");
        PlayerMock victim = join("VampireTarget");
        owner.getWorld().setPVP(true);
        assertTrue(owner.performCommand("title equip bloodlord"));
        settle();
        owner.setHealth(7);

        victim.simulateDamage(5, owner);

        assertTrue(owner.getHealth() > 7, "Bloodlord must heal after an accepted PvP hit");
        assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.bloodlord.vampire", System.currentTimeMillis()));
    }

    @Test
    void bloodlordHealsFromEveryPassiveMobHitWithoutCooldown() {
        PlayerMock owner = join("MobVampire");
        Chicken chicken = owner.getWorld().spawn(owner.getLocation(), Chicken.class);
        assertTrue(owner.performCommand("title equip bloodlord"));
        settle();
        owner.setHealth(15);
        var hit = Mockito.mock(EntityDamageByEntityEvent.class);
        Mockito.when(hit.getEntity()).thenReturn(chicken);
        Mockito.when(hit.getDamager()).thenReturn(owner);
        Mockito.when(hit.getFinalDamage()).thenReturn(2.0);

        var listener = titleListener();
        listener.onVampireDamage(hit);
        listener.onVampireDamage(hit);

        assertEquals(15.8, owner.getHealth(), 1.0e-9);
        assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.bloodlord.vampire", System.currentTimeMillis()));
    }

    @Test
    void bloodlordHealsFromARealMobDamageEvent() {
        PlayerMock owner = join("RealMobVampire");
        Chicken chicken = owner.getWorld().spawn(owner.getLocation(), Chicken.class);
        assertTrue(owner.performCommand("title equip bloodlord"));
        settle();
        owner.setHealth(15);

        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) chicken).simulateDamage(2, owner);

        assertTrue(owner.getHealth() > 15, "a real mob damage event must trigger Vampire");
    }

    @Test
    void bloodlordIgnoresZeroDamageToMobs() {
        PlayerMock owner = join("BlockedMobVampire");
        Chicken chicken = owner.getWorld().spawn(owner.getLocation(), Chicken.class);
        assertTrue(owner.performCommand("title equip bloodlord"));
        settle();
        owner.setHealth(15);
        var hit = Mockito.mock(EntityDamageByEntityEvent.class);
        Mockito.when(hit.getEntity()).thenReturn(chicken);
        Mockito.when(hit.getDamager()).thenReturn(owner);
        Mockito.when(hit.getFinalDamage()).thenReturn(0.0);

        titleListener().onVampireDamage(hit);

        assertEquals(15, owner.getHealth(), 1.0e-9);
    }

    @Test
    void bloodlordCannotHealFromOverkillDamage() {
        PlayerMock owner = join("OverkillOwner");
        PlayerMock victim = join("OverkillVictim");
        owner.getWorld().setPVP(true);
        assertTrue(owner.performCommand("title equip bloodlord"));
        settle();
        owner.setHealth(7);
        victim.setHealth(1);
        var hit = Mockito.mock(EntityDamageByEntityEvent.class);
        Mockito.when(hit.getEntity()).thenReturn(victim);
        Mockito.when(hit.getDamager()).thenReturn(owner);
        Mockito.when(hit.getFinalDamage()).thenReturn(20.0);

        titleListener().onVampireDamage(hit);

        assertEquals(7.2, owner.getHealth(), 1.0e-9);
    }

    @Test
    void beastbaneRespectsAbsorptionHearts() {
        PlayerMock owner = join("FinisherOwner");
        PlayerMock victim = join("FinisherVictim");
        owner.getWorld().setPVP(true);
        assertTrue(owner.performCommand("title equip beastbane"));
        settle();
        var listener = titleListener();
        victim.setHealth(6);
        victim.setAbsorptionAmount(4);
        var execute = Mockito.mock(EntityDamageByEntityEvent.class);
        Mockito.when(execute.getEntity()).thenReturn(victim);
        Mockito.when(execute.getDamager()).thenReturn(owner);
        Mockito.when(execute.getFinalDamage()).thenReturn(20.0);
        Mockito.when(execute.getDamage()).thenReturn(20.0);
        listener.onCombatDamage(execute);
        Mockito.verify(execute, Mockito.never()).setDamage(Mockito.anyDouble());

        victim.setAbsorptionAmount(2);
        listener.onCombatDamage(execute);
        Mockito.verify(execute, Mockito.never()).setDamage(Mockito.anyDouble());
        Mockito.when(execute.getFinalDamage()).thenReturn(2.0);
        Mockito.when(execute.getDamage()).thenReturn(2.0);
        listener.onCombatDamage(execute);
        Mockito.verify(execute).setDamage(14.0);
        Mockito.when(execute.isCancelled()).thenReturn(true);
        listener.onCombatActivationResolved(execute);
        assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.beastbane.executioner", System.currentTimeMillis()));

        Mockito.when(execute.isCancelled()).thenReturn(false);
        listener.onCombatDamage(execute);
        listener.onCombatActivationResolved(execute);
        assertTrue(plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.beastbane.executioner", System.currentTimeMillis()) > 0);
    }

    @Test
    void berserkerGetsNoRageFromFullyAbsorbedDamage() {
        PlayerMock owner = join("ShieldedBerserker");
        PlayerMock victim = join("RageTarget");
        owner.getWorld().setPVP(true);
        assertTrue(owner.performCommand("title equip berserker"));
        settle();
        var listener = titleListener();
        var absorbed = Mockito.mock(org.bukkit.event.entity.EntityDamageEvent.class);
        Mockito.when(absorbed.getEntity()).thenReturn(owner);
        Mockito.when(absorbed.getFinalDamage()).thenReturn(0.0);
        listener.onRageDamage(absorbed);

        var hit = Mockito.mock(EntityDamageByEntityEvent.class);
        Mockito.when(hit.getEntity()).thenReturn(victim);
        Mockito.when(hit.getDamager()).thenReturn(owner);
        Mockito.when(hit.getFinalDamage()).thenReturn(2.0);
        Mockito.when(hit.getDamage()).thenReturn(2.0);
        listener.onCombatDamage(hit);
        Mockito.verify(hit, Mockito.never()).setDamage(Mockito.anyDouble());
    }

    @Test
    void undyingDoesNotSpendRescueWhenAbsorptionPreventsDeath() {
        PlayerMock owner = join("ShieldedUndying");
        assertTrue(owner.performCommand("title equip undying"));
        settle();
        owner.setHealth(4);
        owner.setAbsorptionAmount(4);
        var absorbed = Mockito.mock(org.bukkit.event.entity.EntityDamageEvent.class);
        Mockito.when(absorbed.getEntity()).thenReturn(owner);
        Mockito.when(absorbed.getFinalDamage()).thenReturn(0.0);

        titleListener().onFatalDamage(absorbed);

        Mockito.verify(absorbed, Mockito.never()).setCancelled(true);
        assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.undying.refuse_death", System.currentTimeMillis()));
    }

    private com.leadsystems.minecraft.ascension.ability.TitleAbilityListener titleListener() {
        return new com.leadsystems.minecraft.ascension.ability.TitleAbilityListener(
                plugin.sessions(), plugin::currentConfig,
                new com.leadsystems.minecraft.ascension.progress.MobClassifier(plugin::currentConfig),
                Mockito.mock(com.leadsystems.minecraft.ascension.progress.PlacedBlockTracker.class),
                plugin.storage(), Mockito.mock(PlayerRepository.class), new MainThread(plugin), new Messages());
    }

    private com.leadsystems.minecraft.ascension.ability.AbilityListener rankAbilityListener() {
        Messages messages = new Messages();
        messages.load(plugin.currentConfig().messages());
        var abilities = new com.leadsystems.minecraft.ascension.ability.RankAbilityService(
                plugin, plugin.sessions(), plugin::currentConfig, messages);
        return new com.leadsystems.minecraft.ascension.ability.AbilityListener(
                abilities, plugin.sessions(),
                new com.leadsystems.minecraft.ascension.progress.MobClassifier(plugin::currentConfig),
                new MainThread(plugin), plugin::currentConfig);
    }

    @Test
    void witherboundPulseDamagesAPlayerWithoutRequiringWither() {
        assertCombatPulseHitsPlayer("witherbound");
    }

    @Test
    void emptyPurgeCastsDoNotSpendCooldown() {
        for (String title : List.of("ashborn", "witherbound")) {
            PlayerMock owner = join("Empty" + title);
            assertTrue(owner.performCommand("title equip " + title));
            settle();
            server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                    abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));
            String key = "title." + title + (title.equals("ashborn") ? ".flame_purge" : ".corruption_purge");
            assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(key,
                    System.currentTimeMillis()));
        }
    }

    private void assertCombatPulseHitsPlayer(String title) {
        PlayerMock owner = join("PulseOwner");
        PlayerMock victim = join("PulseTarget");
        owner.getWorld().setPVP(true);
        victim.teleport(owner.getLocation());
        assertTrue(owner.performCommand("title equip " + title));
        assertTrue(victim.performCommand("title equip conqueror"));
        settle();
        double before = victim.getHealth();

        try {
            server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                    abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) {
            // MockBukkit does not render all title particles.
        }

        assertTrue(victim.getHealth() < before, title + " pulse must damage an eligible player");
    }

    @Test
    void raidbreakerWaveDamagesOneEligiblePlayer() {
        PlayerMock owner = join("RaidbreakerOwner");
        PlayerMock victim = join("RaidbreakerVictim");
        owner.getWorld().setPVP(true);
        victim.teleport(owner.getLocation());
        assertTrue(owner.performCommand("title equip raidbreaker"));
        settle();
        double before = victim.getHealth();
        try {
            server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                    abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) {
            // MockBukkit does not implement every particle.
        }
        assertTrue(victim.getHealth() < before);
    }

    @Test
    void raidbreakerWaveExcludesTeammatesAndPvpDisabledWorld() {
        PlayerMock owner = join("RaidbreakerGuard");
        PlayerMock victim = join("RaidbreakerFriend");
        owner.getWorld().setPVP(true);
        victim.teleport(owner.getLocation());
        assertTrue(owner.performCommand("title equip raidbreaker"));
        settle();
        Team ownerOld = owner.getScoreboard().getEntryTeam(owner.getName());
        if (ownerOld != null) ownerOld.removeEntry(owner.getName());
        Team victimOld = owner.getScoreboard().getEntryTeam(victim.getName());
        if (victimOld != null) victimOld.removeEntry(victim.getName());
        Team team = owner.getScoreboard().registerNewTeam("party");
        team.addEntry(owner.getName());
        team.addEntry(victim.getName());
        assertEquals(team, owner.getScoreboard().getEntryTeam(owner.getName()));
        assertEquals(team, owner.getScoreboard().getEntryTeam(victim.getName()));
        double before = victim.getHealth();
        try {
            server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                    abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) { }
        assertEquals(before, victim.getHealth());
        team.unregister();
        owner.getWorld().setPVP(false);
        try {
            server.getPluginManager().callEvent(new PlayerInteractEvent(owner, Action.RIGHT_CLICK_AIR,
                    abilityItems(owner).get(0), null, BlockFace.SELF, EquipmentSlot.HAND));
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException ignored) { }
        assertEquals(before, victim.getHealth());
    }

    @Test
    void twoHuntsmenCanMarkTheSameVictim() {
        PlayerMock first = join("FirstHuntsman");
        PlayerMock second = join("SecondHuntsman");
        first.getWorld().setPVP(true);
        assertTrue(first.performCommand("title equip huntsman"));
        assertTrue(second.performCommand("title equip huntsman"));
        settle();
        org.bukkit.entity.Player victim = Mockito.mock(org.bukkit.entity.Player.class);
        org.bukkit.World effectWorld = Mockito.mock(org.bukkit.World.class);
        Mockito.when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(victim.getName()).thenReturn("SharedTarget");
        Mockito.when(victim.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        Mockito.when(victim.getScoreboard()).thenReturn(first.getScoreboard());
        Mockito.when(victim.getWorld()).thenReturn(effectWorld);
        Mockito.when(victim.getLocation()).thenReturn(first.getLocation());
        var listener = new com.leadsystems.minecraft.ascension.ability.TitleAbilityListener(
                plugin.sessions(), plugin::currentConfig,
                Mockito.mock(com.leadsystems.minecraft.ascension.progress.MobClassifier.class),
                Mockito.mock(com.leadsystems.minecraft.ascension.progress.PlacedBlockTracker.class),
                plugin.storage(), Mockito.mock(PlayerRepository.class), new MainThread(plugin), new Messages());
        for (PlayerMock attacker : List.of(first, second)) {
            EntityDamageByEntityEvent hit = Mockito.mock(EntityDamageByEntityEvent.class);
            Mockito.when(hit.getEntity()).thenReturn(victim);
            Mockito.when(hit.getDamager()).thenReturn(attacker);
            Mockito.when(hit.getFinalDamage()).thenReturn(2.0);
            Mockito.when(hit.getDamage()).thenReturn(2.0);
            listener.onCombatDamage(hit);
            listener.onCombatActivationResolved(hit);
            assertTrue(plugin.sessions().get(attacker).cooldowns().remainingMillis(
                    "title.huntsman.marked_prey", System.currentTimeMillis()) > 0);
        }
    }

    @Test
    void huntsmanMarkIncreasesDamageAgainstAnEligiblePlayer() {
        PlayerMock owner = join("HuntsmanOwner");
        owner.getWorld().setPVP(true);
        assertTrue(owner.performCommand("title equip huntsman"));
        settle();
        org.bukkit.entity.Player victim = Mockito.mock(org.bukkit.entity.Player.class);
        org.bukkit.World effectWorld = Mockito.mock(org.bukkit.World.class);
        Mockito.when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(victim.getName()).thenReturn("HuntsmanTarget");
        Mockito.when(victim.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        Mockito.when(victim.getScoreboard()).thenReturn(owner.getScoreboard());
        Mockito.when(victim.getWorld()).thenReturn(effectWorld);
        Mockito.when(victim.getLocation()).thenReturn(owner.getLocation());
        EntityDamageByEntityEvent hit = Mockito.mock(EntityDamageByEntityEvent.class);
        Mockito.when(hit.getEntity()).thenReturn(victim);
        Mockito.when(hit.getDamager()).thenReturn(owner);
        Mockito.when(hit.getFinalDamage()).thenReturn(2.0);
        Mockito.when(hit.getDamage()).thenReturn(2.0);
        var listener = new com.leadsystems.minecraft.ascension.ability.TitleAbilityListener(
                plugin.sessions(), plugin::currentConfig,
                Mockito.mock(com.leadsystems.minecraft.ascension.progress.MobClassifier.class),
                Mockito.mock(com.leadsystems.minecraft.ascension.progress.PlacedBlockTracker.class),
                plugin.storage(), Mockito.mock(PlayerRepository.class), new MainThread(plugin), new Messages());

        listener.onCombatDamage(hit);

        Mockito.verify(hit).setDamage(3.0);
        Mockito.when(hit.isCancelled()).thenReturn(true);
        listener.onCombatActivationResolved(hit);
        assertEquals(0, plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.huntsman.marked_prey", System.currentTimeMillis()));
        Mockito.verifyNoInteractions(effectWorld);

        Mockito.when(hit.isCancelled()).thenReturn(false);
        listener.onCombatDamage(hit);
        listener.onCombatActivationResolved(hit);
        assertTrue(plugin.sessions().get(owner).cooldowns().remainingMillis(
                "title.huntsman.marked_prey", System.currentTimeMillis()) > 0);
    }


    @Test
    void droppingTheChronicleIsCancelled() {
        PlayerMock p = join("Alex");
        Item entity = Mockito.mock(Item.class);
        Mockito.when(entity.getItemStack()).thenReturn(book(p));
        PlayerDropItemEvent event = new PlayerDropItemEvent(p, entity);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void deathRemovesTheBookFromDropsAndKeepsIt() {
        PlayerMock p = join("Alex");
        ItemStack book = book(p);
        List<ItemStack> drops = new ArrayList<>(List.of(book.clone(), new ItemStack(Material.DIRT)));
        PlayerDeathEvent event = new PlayerDeathEvent(p, Mockito.mock(DamageSource.class), drops, 0, Component.empty());
        server.getPluginManager().callEvent(event);
        assertEquals(1, event.getDrops().size(), "only the dirt drops");
        assertEquals(Material.DIRT, event.getDrops().get(0).getType());
        assertTrue(event.getItemsToKeep().stream().anyMatch(i -> plugin.books().items().isChronicle(i)));
    }

    @Test
    void creativeCloneIsRemovedLeavingOneBook() {
        PlayerMock p = join("Alex");
        ItemStack original = book(p);
        p.getInventory().setItem(20, original.clone());
        assertEquals(2, chronicles(p).size());
        plugin.books().reconcile(p, plugin.sessions().get(p), true);
        assertEquals(1, chronicles(p).size());
    }

    @Test
    void someoneElsesBookIsRemoved() {
        PlayerMock alex = join("Alex");
        PlayerMock bob = join("Bob");
        alex.getInventory().setItem(10, book(bob).clone());
        plugin.books().reconcile(alex, plugin.sessions().get(alex), true);
        List<ItemStack> left = chronicles(alex);
        assertEquals(1, left.size());
        assertTrue(plugin.books().items().isValidFor(left.get(0), plugin.sessions().get(alex)));
    }

    @Test
    void reissueRotatesTheNonceSoOldCopiesStopWorking() {
        PlayerMock p = join("Alex");
        PlayerSession s = plugin.sessions().get(p);
        ItemStack old = book(p).clone();
        String oldNonce = s.bookNonce();
        plugin.books().forceReissue(p, s);
        settle();
        assertFalse(oldNonce.equals(s.bookNonce()));
        assertEquals(s.bookNonce(), dbRecord(p.getUniqueId()).bookNonce(), "session nonce is the committed nonce");
        assertFalse(plugin.books().items().isValidFor(old, s));
        assertTrue(plugin.books().items().isValidFor(book(p), s));
    }

    @Test
    void fullInventoryMakesDeliveryPendingUntilTitlebook() {
        PlayerMock p = join("Alex");
        PlayerSession s = plugin.sessions().get(p);
        p.getInventory().clear();
        for (int slot = 0; slot < 36; slot++) {
            p.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        plugin.books().reconcile(p, s, true);
        settle();
        assertTrue(s.bookPending());
        assertTrue(dbRecord(p.getUniqueId()).bookPending());
        assertTrue(chronicles(p).isEmpty());

        p.getInventory().setItem(7, null);
        p.performCommand("titlebook");
        settle();
        assertFalse(s.bookPending());
        assertTrue(plugin.books().items().isValidFor(book(p), s));
    }

    // ------------------------------------------------------------------ quests

    @Test
    void progressBeforeAcceptingDoesNotCount() {
        PlayerMock p = join("Alex");
        PlayerSession s = plugin.sessions().get(p);
        plugin.progress().record(p, new Fact.LevelsGained(30));
        assertEquals(QuestState.NOT_ACCEPTED, s.quest().state());
        assertEquals(0, s.quest().amount("xp"));
    }

    @Test
    void acceptingThenCompletingEveryObjectiveRanksUpOnce() {
        PlayerMock p = join("Alex");
        PlayerSession s = plugin.sessions().get(p);
        plugin.quests().accept(p, () -> { });
        settle();
        assertEquals(QuestState.ACTIVE, s.quest().state());

        plugin.progress().record(p, new Fact.LevelsGained(30));
        for (int i = 0; i < 10; i++) {
            plugin.progress().record(p, new Fact.MobKilled("ZOMBIE", Set.of("hostile")));
        }
        for (int i = 0; i < 32; i++) {
            plugin.progress().record(p, new Fact.BlockMined("STONE"));
        }
        settle();
        assertEquals(1, s.rankIndex(), "advanced to Bronze II");
        assertEquals(QuestState.NOT_ACCEPTED, s.quest().state(), "next quest must be accepted again");
        assertEquals(1, dbRecord(p.getUniqueId()).rankIndex());

        // Further facts (or duplicate events) must not advance again.
        plugin.progress().record(p, new Fact.LevelsGained(100));
        settle();
        assertEquals(1, s.rankIndex());
    }

    @Test
    void progressSurvivesRelog() {
        PlayerMock p = join("Alex");
        UUID uuid = p.getUniqueId();
        plugin.quests().accept(p, () -> { });
        settle();
        plugin.progress().record(p, new Fact.LevelsGained(12));
        p.disconnect();
        settle();

        PlayerMock again = new PlayerMock(server, "Alex", uuid);
        server.addPlayer(again);
        settle();
        PlayerSession s = plugin.sessions().get(again);
        assertEquals(QuestState.ACTIVE, s.quest().state());
        assertEquals(12, s.quest().amount("xp"));
    }

    @Test
    void degradedStorageRefusesToAcceptQuests() {
        PlayerMock p = join("Alex");
        PlayerSession s = plugin.sessions().get(p);
        plugin.health().markFailed("test", new RuntimeException("simulated outage"));
        plugin.quests().accept(p, () -> { });
        settle();
        assertEquals(QuestState.NOT_ACCEPTED, s.quest().state());
        assertEquals(0, plugin.storage().submit("check", c -> {
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM quests")) {
                var rs = ps.executeQuery();
                rs.next();
                return rs.getInt(1);
            }
        }).join());
    }
}
