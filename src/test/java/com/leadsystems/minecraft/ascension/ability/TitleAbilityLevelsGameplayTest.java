package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.config.Settings;
import com.leadsystems.minecraft.ascension.domain.cooldown.CooldownBook;
import com.leadsystems.minecraft.ascension.domain.title.TitleCatalog;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleLevels;
import com.leadsystems.minecraft.ascension.progress.MobClassifier;
import com.leadsystems.minecraft.ascension.progress.PlacedBlockTracker;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.SqlTask;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Item;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the listener rather than just checking resolver numbers. */
class TitleAbilityLevelsGameplayTest {
    private final Map<String, Long> xp = new HashMap<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final CooldownBook cooldowns = new CooldownBook();
    private final AtomicReference<Double> health = new AtomicReference<>(10.0);
    private World world;
    private Player owner;
    private PlayerSession session;
    private TitleAbilityListener listener;
    private MainThread main;
    private MobClassifier mobs;
    private AttributeInstance max;
    private StorageExecutor storage;
    private PlayerRepository repo;
    private ConfigSnapshot config;
    private final Map<String,Map<String,Double>> abilitySettings = new HashMap<>();

    @BeforeEach void setup() {
        MockBukkit.mock();
        world = mock(World.class);
        when(world.getGameRuleValue(org.bukkit.GameRules.PVP)).thenReturn(true);
        when(world.getPlayers()).thenReturn(players);
        owner = player("Owner", 0, true);
        players.add(owner);
        max = mock(AttributeInstance.class);
        when(max.getValue()).thenReturn(20.0);
        when(owner.getAttribute(Attribute.MAX_HEALTH)).thenReturn(max);
        when(owner.getHealth()).thenAnswer(call -> health.get());
        doAnswer(call -> { health.set(call.getArgument(0)); return null; }).when(owner).setHealth(anyDouble());
        session = mock(PlayerSession.class);
        when(session.usable()).thenReturn(true);
        when(session.ownedTitles()).thenReturn(com.leadsystems.minecraft.ascension.domain.title.TitleAbilityRegistry.ALL);
        when(session.cooldowns()).thenReturn(cooldowns);
        when(session.stat(anyString())).thenAnswer(call -> xp.getOrDefault(call.getArgument(0), 0L));
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.get(owner)).thenReturn(session);
        when(sessions.get(owner.getUniqueId())).thenReturn(session);
        config = mock(ConfigSnapshot.class);
        Settings settings = mock(Settings.class);
        when(settings.stormbringerRange()).thenReturn(50.0);
        when(settings.vampireMaxHeal()).thenReturn(3.0);
        when(config.settings()).thenReturn(settings);
        when(config.titleLevels()).thenReturn(TitleLevels.defaults());
        TitleCatalog titles = mock(TitleCatalog.class);
        when(config.titles()).thenReturn(titles);
        Map<String, Integer> cds = Map.of("stormbringer",35,"bloodlord",0,"colossus",0,"emberforge",60,"mycelial_drifter",45,"soul_reaper",20,"huntsman",20,"witherbound",45,"stonebreaker",30);
        when(titles.byId(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return new TitleDefinition(id,1,id,"#ffffff","",List.of(),id,"",cds.getOrDefault(id,0),abilitySettings.getOrDefault(id,Map.of()),false);
        });
        main = mock(MainThread.class);
        doAnswer(call -> { delayed.add(call.getArgument(1)); return null; }).when(main).later(anyLong(), any(Runnable.class));
        mobs = mock(MobClassifier.class);
        storage = mock(StorageExecutor.class);
        repo = mock(PlayerRepository.class);
        listener = new TitleAbilityListener(sessions,()->config,mobs,mock(PlacedBlockTracker.class),
                storage,repo,main,mock(Messages.class));
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private Player player(String name, double x, boolean accepted) {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenAnswer(call -> new Location(world,x,64,0));
        when(p.getEyeLocation()).thenAnswer(call -> new Location(world,x,65.6,0));
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(p.isOnline()).thenReturn(true);
        when(p.getScoreboard()).thenReturn(mock(Scoreboard.class));
        doAnswer(call -> {
            if (accepted) listener.onAbilityDamageResolved(damage(p, call.getArgument(0)));
            return null;
        }).when(p).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        return p;
    }
    private EntityDamageByEntityEvent damage(org.bukkit.entity.LivingEntity target, double amount) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(target);
        when(event.getDamager()).thenReturn(owner);
        when(event.getDamage()).thenReturn(amount);
        when(event.getFinalDamage()).thenReturn(amount);
        return event;
    }
    private void equip(String id, long amount) {
        when(session.equippedTitle()).thenReturn(id);
        xp.put("title.xp."+id,amount);
    }

    @Test void newArrivalRestoresConfiguredHealthAndSpeedAtAllThreeLevels() {
        Zombie victim = mock(Zombie.class);
        when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
        when(victim.getKiller()).thenReturn(owner);
        when(mobs.categories(victim)).thenReturn(Set.of("hostile"));
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        long[] amounts = {0, 1000, 3000};
        for (int level = 1; level <= 3; level++) {
            equip("new_arrival", amounts[level - 1]);
            health.set(10.0);
            listener.onMobDeath(event);
            assertEquals(11.0 + level, health.get(), "level " + level);
            int speedTicks = 20 * (level + 1);
            verify(owner).addPotionEffect(argThat(effect -> effect.getType().equals(PotionEffectType.SPEED)
                    && effect.getDuration() == speedTicks && effect.getAmplifier() == 0));
        }
    }

    @Test void wardenSonicWardUsesLeveledReductionAtAllThreeLevels() {
        long[] amounts = {0, 1000, 3000};
        double[] multipliers = {0.2, 0.16, 0.12};
        for (int level = 1; level <= 3; level++) {
            equip("wardens_bane", amounts[level - 1]);
            EntityDamageEvent event = mock(EntityDamageEvent.class);
            when(event.getEntity()).thenReturn(owner);
            when(event.getDamage()).thenReturn(20.0);
            when(event.getFinalDamage()).thenReturn(20.0);
            when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.SONIC_BOOM);
            listener.onDefend(event);
            double expected = multipliers[level - 1] * 20.0;
            verify(event).setDamage(doubleThat(value -> Math.abs(value - expected) < 1e-9));
        }
    }

    @Test void stormLevelOneChoosesNearestWithinFiftyAndStartsOneCooldown() {
        equip("stormbringer",0);
        Player nearest = player("Near",49,true), farther = player("Far",50,true), outside = player("Outside",51,true);
        players.addAll(List.of(farther,outside,nearest));
        listener.activateFromItem(owner,session);
        verify(nearest).damage(26.0,owner);
        verify(farther,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        verify(outside,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        assertFalse(cooldowns.isReady("title.stormbringer.heavens_judgment",System.currentTimeMillis()));
        listener.activateFromItem(owner,session);
        verify(nearest,times(1)).damage(26.0,owner);
    }
    @Test void stormLevelTwoReachesSixtyButStillChoosesOne() {
        equip("stormbringer",1000);
        Player edge = player("Edge",60,true), outside = player("Outside",60.01,true);
        players.addAll(List.of(edge,outside));
        listener.activateFromItem(owner,session);
        verify(edge).damage(26.0,owner);
        verify(outside,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
    }
    @Test void stormLevelThreeHitsEachEligibleTargetOnceAndSkipsTeamsModesAndProtection() {
        equip("stormbringer",3000);
        Player one = player("One",30,true), edge = player("Edge",60,true), teammate = player("Ally",1,true);
        Player creative = player("Creative",2,true), protectedPlayer = player("Protected",3,false), offline = player("Offline",4,true);
        Team team = mock(Team.class);
        when(owner.getScoreboard().getEntryTeam("Owner")).thenReturn(team);
        when(owner.getScoreboard().getEntryTeam("Ally")).thenReturn(team);
        when(creative.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(offline.isOnline()).thenReturn(false);
        players.addAll(List.of(one,one,edge,teammate,creative,protectedPlayer,offline));
        listener.activateFromItem(owner,session);
        verify(one,times(1)).damage(26.0,owner);
        verify(edge,times(1)).damage(26.0,owner);
        verify(protectedPlayer).damage(26.0,owner);
        verify(teammate,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        verify(creative,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        verify(offline,never()).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        verify(world,times(2)).strikeLightningEffect(any(Location.class));
        assertFalse(cooldowns.isReady("title.stormbringer.heavens_judgment",System.currentTimeMillis()));
    }
    @Test void stormProtectedOrDisabledPvpDoesNotSpendCooldown() {
        equip("stormbringer",3000);
        Player p = player("Protected",20,false);
        players.add(p);
        listener.activateFromItem(owner,session);
        assertTrue(cooldowns.isReady("title.stormbringer.heavens_judgment",System.currentTimeMillis()));
        when(world.getGameRuleValue(org.bukkit.GameRules.PVP)).thenReturn(false);
        listener.activateFromItem(owner,session);
        verify(p,times(1)).damage(26.0,owner);
        verify(world,never()).strikeLightningEffect(any());
    }
    @Test void vampireUsesLeveledRatioAndCapWithoutRankEffects() {
        equip("bloodlord",3000);
        Zombie victim = mock(Zombie.class);
        when(victim.getHealth()).thenReturn(100.0);
        when(victim.getLocation()).thenReturn(new Location(world,1,64,0));
        when(victim.getEyeLocation()).thenReturn(new Location(world,1,65.6,0));
        when(victim.getWorld()).thenReturn(world);
        listener.onVampireDamage(damage(victim,40));
        assertEquals(14.0,health.get());
        health.set(10.0);
        listener.onVampireDamage(damage(victim,2));
        assertEquals(10.6,health.get(),1e-9);
        verify(session,never()).addStat(anyString(),anyLong());
    }
    @Test void colossusLevelChangeReplacesKeyWithoutHealingOrResettingCooldown() {
        equip("colossus",0);
        AtomicReference<AttributeModifier> current = new AtomicReference<>();
        when(max.getModifiers()).thenAnswer(call -> current.get() == null ? List.of() : List.of(current.get()));
        doAnswer(call -> { current.set(call.getArgument(0)); return null; }).when(max).addTransientModifier(any());
        doAnswer(call -> { current.set(null); return null; }).when(max).removeModifier(any(AttributeModifier.class));
        cooldowns.start("existing",1000,60000);
        listener.onLoaded(owner,session,false);
        assertEquals(4,current.get().getAmount());
        xp.put("title.xp.colossus",1000L);
        listener.reconcileLevel(owner,session);
        assertEquals(6,current.get().getAmount());
        xp.put("title.xp.colossus",3000L);
        listener.reconcileLevel(owner,session);
        assertEquals(8,current.get().getAmount());
        listener.reconcileLevel(owner,session);
        verify(max,times(3)).addTransientModifier(any());
        verify(owner,never()).setHealth(anyDouble());
        assertEquals(60000,cooldowns.remainingMillis("existing",1000));
        listener.onUnload(owner,session);
        assertNull(current.get());
    }
    @Test void voidUpgradeAddsSlotsWithoutResettingOriginalExpiries() {
        long now=1000;
        for(int slot=1;slot<=3;slot++) cooldowns.start("title.voidwalker.void_step.charge."+slot,now,60000);
        assertNull(TitleAbilityListener.nextVoidStepCharge(cooldowns,now));
        assertEquals("title.voidwalker.void_step.charge.4",TitleAbilityListener.nextVoidStepCharge(cooldowns,now,4));
        assertEquals(2,TitleAbilityListener.availableVoidStepCharges(cooldowns,now,5));
        cooldowns.start("title.voidwalker.void_step.charge.4",now,60000);
        assertEquals("title.voidwalker.void_step.charge.5",TitleAbilityListener.nextVoidStepCharge(cooldowns,now,5));
        assertEquals(0,TitleAbilityListener.availableVoidStepCharges(cooldowns,now));
        assertEquals(60000,cooldowns.remainingMillis("title.voidwalker.void_step.charge.1",now));
    }
    @Test void cloudKeepsCastLevelSettingsAndStopsAfterUnload() {
        equip("mycelial_drifter",1000);
        Player enemy = player("Enemy",4.5,true);
        when(world.getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of(enemy));
        listener.onLoaded(owner,session,false);
        listener.activateFromItem(owner,session);
        xp.put("title.xp.mycelial_drifter",3000L);
        delayed.get(0).run();
        assertEquals(13,health.get());
        verify(world).getNearbyEntities(any(Location.class),eq(5.0),eq(5.0),eq(5.0));
        listener.onUnload(owner,session);
        delayed.get(1).run();
        verify(enemy,times(1)).damage(2.0,owner);
    }
    @Test void cloudDoesNothingAfterDisconnect() {
        equip("mycelial_drifter",3000);
        listener.onLoaded(owner,session,false);
        listener.activateFromItem(owner,session);
        when(owner.isOnline()).thenReturn(false);
        delayed.get(0).run();
        verify(world,never()).getNearbyEntities(any(Location.class),anyDouble(),anyDouble(),anyDouble());
        assertEquals(10,health.get());
    }

    private Zombie hostile() {
        Zombie victim = mock(Zombie.class);
        when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
        when(victim.getWorld()).thenReturn(world);
        when(victim.getLocation()).thenAnswer(call -> new Location(world,1,64,0));
        when(victim.getHealth()).thenReturn(20.0);
        when(mobs.categories(victim)).thenReturn(Set.of("hostile"));
        return victim;
    }
    @Test void moltenBonusRemainsAtCastLevelAfterXpUpgrade() {
        equip("emberforge",1000);
        listener.activateFromItem(owner,session);
        xp.put("title.xp.emberforge",3000L);
        EntityDamageByEntityEvent event = damage(hostile(),4);
        listener.onCombatDamage(event);
        verify(event).setDamage(10.0); // 4 base + 6 captured level-two bonus
    }
    @Test void huntsmanMarkKeepsCastMultiplierAcrossLevelChange() {
        equip("huntsman",1000);
        Zombie victim = hostile();
        EntityDamageByEntityEvent initial = damage(victim,4);
        listener.onCombatDamage(initial);
        xp.put("title.xp.huntsman",3000L);
        listener.onCombatActivationResolved(initial);
        EntityDamageByEntityEvent followup = damage(victim,4);
        listener.onCombatDamage(followup);
        verify(initial).setDamage(6.4);
        verify(followup).setDamage(6.4);
        assertFalse(cooldowns.isReady("title.huntsman.marked_prey",System.currentTimeMillis()));
    }
    @Test void cancelledHuntsmanFirstHitDoesNotCreateMarkOrSpendCooldown() {
        equip("huntsman",3000);
        Zombie victim = hostile();
        EntityDamageByEntityEvent initial = damage(victim,4);
        listener.onCombatDamage(initial);
        when(initial.isCancelled()).thenReturn(true);
        listener.onCombatActivationResolved(initial);
        assertTrue(cooldowns.isReady("title.huntsman.marked_prey",System.currentTimeMillis()));
        xp.put("title.xp.huntsman",0L);
        EntityDamageByEntityEvent later = damage(victim,4);
        listener.onCombatDamage(later);
        verify(later).setDamage(6.0);
    }

    @SuppressWarnings("unchecked")
    private void delayedSoulStorage() throws Exception {
        when(session.tryBegin(anyString())).thenReturn(true);
        UUID ownerId = owner.getUniqueId();
        when(repo.consumeAbilityState(any(),eq(ownerId),eq("soul_reaper.souls"),eq(1L))).thenReturn(true);
        when(storage.submit(anyString(),any(SqlTask.class))).thenAnswer(call ->
                CompletableFuture.completedFuture(((SqlTask<?>)call.getArgument(1)).run(null)));
        doAnswer(call -> {
            CompletableFuture<Object> future = call.getArgument(0);
            BiConsumer<Object,Throwable> callback = call.getArgument(1);
            delayed.add(() -> callback.accept(future.join(),null));
            return null;
        }).when(main).onComplete(any(CompletableFuture.class),any(BiConsumer.class));
    }
    @Test void soulHealUsesCastLevelWhenXpChangesBeforeStorageCallback() throws Exception {
        equip("soul_reaper",1000);
        delayedSoulStorage();
        listener.onLoaded(owner,session,false);
        listener.activateFromItem(owner,session);
        assertEquals(10,health.get());
        xp.put("title.xp.soul_reaper",3000L);
        delayed.get(0).run();
        assertEquals(15,health.get());
        assertFalse(cooldowns.isReady("title.soul_reaper.soul_storage",System.currentTimeMillis()));
    }
    @Test void disconnectedSoulRefundPreservesCastMaximumAfterSessionLevelChanges() throws Exception {
        equip("soul_reaper",3000);
        delayedSoulStorage();
        listener.onLoaded(owner,session,false);
        listener.activateFromItem(owner,session);
        when(owner.isOnline()).thenReturn(false);
        xp.put("title.xp.soul_reaper",0L);
        delayed.get(0).run();
        UUID ownerId = owner.getUniqueId();
        verify(repo).addAbilityState(isNull(),eq(ownerId),eq("soul_reaper.souls"),eq(1L),eq(20L));
        assertEquals(10,health.get());
        assertTrue(cooldowns.isReady("title.soul_reaper.soul_storage",System.currentTimeMillis()));
    }

    @Test void witherboundKillLevelUpKeepsLevelTwoCastCooldown() {
        equip("witherbound",2999);
        Zombie victim = hostile();
        when(owner.getNearbyEntities(anyDouble(),anyDouble(),anyDouble())).thenReturn(List.of(victim));
        doAnswer(call -> {
            xp.put("title.xp.witherbound",3004L);
            listener.onAbilityDamageResolved(damage(victim,call.getArgument(0)));
            return null;
        }).when(victim).damage(anyDouble(),any(org.bukkit.entity.Entity.class));
        long before = System.currentTimeMillis();
        listener.activateFromItem(owner,session);
        long after = System.currentTimeMillis();
        long expiry = cooldowns.drainDirty().get("title.witherbound.corruption_purge");
        assertTrue(expiry >= before+40000 && expiry <= after+40000,
                "the cast began at level two, so its cooldown remains 40 seconds");
    }

    private AtomicReference<ItemStack> shatterOneBlock(Runnable beforeDrops) {
        org.bukkit.Bukkit.addRecipe(new FurnaceRecipe(new NamespacedKey("test","stonebreaker_input"),
                new ItemStack(Material.STONE),Material.COBBLESTONE,0,200));
        Block origin = mock(Block.class), extra = mock(Block.class);
        when(origin.getType()).thenReturn(Material.STONE);
        when(extra.getType()).thenReturn(Material.STONE);
        when(extra.getX()).thenReturn(1);
        when(origin.getRelative(any(BlockFace.class))).thenReturn(extra);
        when(extra.getRelative(any(BlockFace.class))).thenReturn(extra);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.IRON_PICKAXE));
        when(owner.getInventory()).thenReturn(inventory);
        AtomicReference<ItemStack> stack = new AtomicReference<>(new ItemStack(Material.COBBLESTONE,16));
        Item drop = mock(Item.class);
        when(drop.getItemStack()).thenAnswer(call -> stack.get());
        doAnswer(call -> { stack.set(call.getArgument(0)); return null; }).when(drop).setItemStack(any(ItemStack.class));
        BlockDropItemEvent drops = mock(BlockDropItemEvent.class);
        when(drops.getPlayer()).thenReturn(owner);
        when(drops.getItems()).thenReturn(List.of(drop));
        doAnswer(call -> {
            beforeDrops.run();
            listener.onShatterDrops(drops);
            return true;
        }).when(owner).breakBlock(extra);
        listener.onShatter(new BlockBreakEvent(origin,owner));
        return stack;
    }
    @Test void shatterFindsFurnaceRecipeByInputAndKeepsDropAmount() {
        equip("stonebreaker",1000);
        abilitySettings.put("stonebreaker",Map.of("auto-smelt-chance",1.0));
        AtomicReference<ItemStack> drop = shatterOneBlock(() -> {});
        assertEquals(Material.STONE,drop.get().getType());
        assertEquals(16,drop.get().getAmount());
    }
    @Test void shatterKeepsCastSmeltChanceAcrossNestedLevelUp() {
        equip("stonebreaker",1000);
        abilitySettings.put("stonebreaker",Map.of("auto-smelt-chance",0.0));
        TitleLevels defaults = TitleLevels.defaults();
        Map<String,Map<Integer,TitleLevels.Upgrade>> profiles = new HashMap<>(defaults.profiles());
        profiles.put("stonebreaker",Map.of(2,new TitleLevels.Upgrade(0,Map.of("auto-smelt-chance",1.0)),
                3,new TitleLevels.Upgrade(0,Map.of("auto-smelt-chance",0.0))));
        when(config.titleLevels()).thenReturn(new TitleLevels(1000,3000,defaults.rewards(),profiles));
        AtomicReference<ItemStack> drop = shatterOneBlock(() -> xp.put("title.xp.stonebreaker",3000L));
        assertEquals(Material.STONE,drop.get().getType(),"nested break XP cannot replace the cast's smelt chance");
    }
}
