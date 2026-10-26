package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.config.ConfigLoader;
import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.config.NameResolver;
import com.leadsystems.minecraft.ascension.domain.progress.Fact;
import com.leadsystems.minecraft.ascension.progress.ProgressTracker;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.session.SessionSnapshot;
import com.leadsystems.minecraft.ascension.storage.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TitleXpServiceTest {
    private final UUID uuid = UUID.randomUUID();
    private final SessionManager sessions = mock(SessionManager.class);
    private final Player player = mock(Player.class);
    private ConfigSnapshot config;
    private PlayerSession session;
    private TitleXpService xp;

    @BeforeEach void setUp() {
        Map<String, YamlConfiguration> files = new HashMap<>();
        for (String file : ConfigLoader.FILES) {
            var input = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(file));
            files.put(file, YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8)));
        }
        NameResolver names = new NameResolver() {
            public boolean isMaterial(String name) { return true; }
            public boolean isEntityType(String name) { return true; }
            public boolean isEffect(String name) { return true; }
        };
        var loaded = new ConfigLoader(names).load(files);
        assertTrue(loaded.ok(), loaded.errors().toString());
        config = loaded.snapshot();
        session = loadedSession("stormbringer", Map.of(), Set.of("stormbringer", "bloodlord"));
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(sessions.get(player)).thenReturn(session);
        when(sessions.get(uuid)).thenReturn(session);
        xp = new TitleXpService(sessions, () -> config);
    }

    @Test void exactThresholdsAndOneNotificationPerCrossing() {
        session.addStat("title.xp.stormbringer", 999);
        session.drain();
        AtomicInteger changes = new AtomicInteger();
        xp.setOnLevelChanged((p, s) -> changes.incrementAndGet());
        xp.record(player, new Fact.BlockMined("STONE"));
        assertEquals(1000, session.stat("title.xp.stormbringer"));
        assertEquals(2, config.titleLevels().level(1000));
        xp.record(player, new Fact.ItemSmelted("IRON_INGOT", 1999));
        assertEquals(2, config.titleLevels().level(session.stat("title.xp.stormbringer")));
        xp.record(player, new Fact.BlockMined("STONE"));
        assertEquals(3000, session.stat("title.xp.stormbringer"));
        assertEquals(3, config.titleLevels().level(3000));
        assertEquals(2, changes.get());
    }

    @Test void rewardsOnlyAllowedFactsAndBossIsNotAlsoHostileXp() {
        xp.record(player, new Fact.BlockMined("STONE"));
        xp.record(player, new Fact.ItemSmelted("IRON_INGOT", 3));
        xp.record(player, new Fact.MobKilled("ZOMBIE", Set.of("hostile")));
        xp.record(player, new Fact.Statistic("fish.caught", 1));
        xp.record(player, new Fact.PlayerKilled(UUID.randomUUID().toString()));
        xp.record(player, new Fact.HuntCompleted());
        xp.record(player, new Fact.RaidCompleted());
        for (String boss : new String[] {"WARDEN", "WITHER", "ELDER_GUARDIAN", "RAVAGER"}) {
            xp.record(player, new Fact.MobKilled(boss, Set.of("hostile")));
            xp.record(player, new Fact.BossDefeated(boss, true, false));
        }
        assertEquals(1064, session.stat("title.xp.stormbringer"));
        xp.record(player, new Fact.MobKilled("COW", Set.of()));
        xp.record(player, new Fact.ItemObtained("DIAMOND", 64));
        xp.record(player, new Fact.ItemSubmitted("DIAMOND", 64));
        xp.record(player, new Fact.LevelsGained(20));
        xp.record(player, new Fact.Statistic("travel.blocks", 100));
        assertEquals(1064, session.stat("title.xp.stormbringer"));
    }

    @Test void capturedSourceStillCreditsOriginalTitleAfterSwitch() {
        TitleXpService.Source source = xp.capture(player);
        session.apply(snapshot("bloodlord", Map.of(), Set.of("stormbringer", "bloodlord")), rank -> null);
        Fact.PlayerKilled fact = new Fact.PlayerKilled(UUID.randomUUID().toString());
        assertEquals(Map.of("title.xp.stormbringer", 50L), xp.bonus(source, fact));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            xp.committed(source, fact);
        }
        assertEquals(50, session.stat("title.xp.stormbringer"));
        assertEquals(0, session.stat("title.xp.bloodlord"));
        assertTrue(session.drain().statDeltas().isEmpty(), "already durable XP must not flush twice");
    }

    @Test void reconnectReconcilesInsteadOfAddingToReplacementSession() {
        TitleXpService.Source source = xp.capture(player);
        PlayerSession replacement = loadedSession("bloodlord", Map.of(), Set.of("bloodlord"));
        when(sessions.get(uuid)).thenReturn(replacement);
        xp.committed(source, new Fact.HuntCompleted());
        assertEquals(0, replacement.stat("title.xp.bloodlord"));
        assertEquals(0, replacement.stat("title.xp.stormbringer"));
        verify(sessions).mutate(eq(uuid), eq("title XP reconnect reconcile"), any(), any());
    }

    @Test void offlineRewardDoesNotTouchDetachedSession() {
        TitleXpService.Source source = xp.capture(player);
        when(sessions.get(uuid)).thenReturn(null);
        xp.committed(source, new Fact.PlayerKilled("victim"));
        assertEquals(0, session.stat("title.xp.stormbringer"));
        assertEquals(Map.of("title.xp.stormbringer", 50L), xp.bonus(source, new Fact.PlayerKilled("victim")));
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
    }

    @Test void loadingReplacementReconcilesOnlyAfterLoadCompletes() {
        TitleXpService.Source source = xp.capture(player);
        PlayerSession replacement = new PlayerSession(uuid, "Tester", false);
        when(sessions.get(uuid)).thenReturn(replacement);
        xp.committed(source, new Fact.HuntCompleted());
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
        replacement.apply(snapshot("bloodlord", Map.of(), Set.of("bloodlord")), rank -> null);
        xp.onLoaded(player, replacement, false);
        verify(sessions).mutate(eq(uuid), eq("title XP reconnect reconcile"), any(), any());
    }

    @Test void frozenAdminReloadDoesNotMirrorAlreadyDurableReward() {
        TitleXpService.Source source = xp.capture(player);
        session.setFrozen(true);
        xp.committed(source, new Fact.PlayerKilled("victim"));
        assertEquals(0, session.stat("title.xp.stormbringer"));
        assertTrue(session.drain().statDeltas().isEmpty());
    }

    @Test void ordinaryTitleXpUsesExistingFailedFlushRecovery() {
        xp.record(player, new Fact.ItemSmelted("IRON_INGOT", 1000));
        var failed = session.drain();
        assertEquals(1000L, failed.statDeltas().get("title.xp.stormbringer"));
        PlayerSession replacement = loadedSession("stormbringer", Map.of(), Set.of("stormbringer"));
        replacement.restore(failed, true);
        assertEquals(1000, replacement.stat("title.xp.stormbringer"));
        assertEquals(1000L, replacement.drain().statDeltas().get("title.xp.stormbringer"));
    }

    @Test void survivalOwnershipAndUsableSessionAreAlwaysRequired() {
        for (GameMode mode : new GameMode[] {GameMode.CREATIVE, GameMode.SPECTATOR}) {
            when(player.getGameMode()).thenReturn(mode);
            xp.record(player, new Fact.BlockMined("STONE"));
        }
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        session.setFrozen(true);
        xp.record(player, new Fact.BlockMined("STONE"));
        session.setFrozen(false);
        session.apply(snapshot("stormbringer", Map.of(), Set.of()), rank -> null);
        xp.record(player, new Fact.BlockMined("STONE"));
        assertEquals(0, session.stat("title.xp.stormbringer"));
        assertNull(xp.capture(player));
    }

    @Test void titleXpSurvivesDeathUnequipAndPersonalTransfer() {
        xp.record(player, new Fact.ItemSmelted("IRON_INGOT", 1000));
        Map<String, Long> persisted = session.statsView();
        session.apply(snapshot(null, persisted, Set.of("stormbringer")), rank -> null);
        xp.record(player, new Fact.BlockMined("STONE"));
        assertEquals(1000, session.stat("title.xp.stormbringer"));
        PlayerSession rejoined = loadedSession("stormbringer", persisted, Set.of("stormbringer"));
        assertEquals(2, config.titleLevels().level(rejoined.stat("title.xp.stormbringer")));
        PlayerSession otherOwner = new PlayerSession(UUID.randomUUID(), "Other", false);
        assertEquals(0, otherOwner.stat("title.xp.stormbringer"));
    }

    @Test void titleXpIsIndependentOfWeeklyRankExpiry() {
        session.setRankActive(false);
        ProgressTracker tracker = new ProgressTracker(sessions, mock(QuestService.class), () -> config);
        tracker.setTitleXp(xp);
        tracker.record(player, new Fact.BlockMined("STONE"));
        assertEquals(1, session.stat("title.xp.stormbringer"));
    }

    @Test void alreadyCommittedFactDoesNotAwardTwice() {
        ProgressTracker tracker = new ProgressTracker(sessions, mock(QuestService.class), () -> config);
        tracker.setTitleXp(xp);
        tracker.recordAfterTitleXpCommit(player, new Fact.PlayerKilled("victim"));
        assertEquals(0, session.stat("title.xp.stormbringer"));
        assertEquals(1, session.stat("pvp.kills"));
    }

    private PlayerSession loadedSession(String title, Map<String, Long> stats, Set<String> owned) {
        PlayerSession result = new PlayerSession(uuid, "Tester", false);
        result.apply(snapshot(title, stats, owned), rank -> null);
        return result;
    }

    private SessionSnapshot snapshot(String title, Map<String, Long> stats, Set<String> owned) {
        return new SessionSnapshot(new PlayerRecord(uuid, "Tester", 0, false, "nonce", false,
                0, 0, null), null, Map.of(), stats, owned, title);
    }
}
