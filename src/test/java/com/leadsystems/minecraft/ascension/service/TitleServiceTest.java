package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.domain.title.Requirement;
import com.leadsystems.minecraft.ascension.domain.title.TitleCatalog;
import com.leadsystems.minecraft.ascension.domain.title.TitleDefinition;
import com.leadsystems.minecraft.ascension.domain.title.TitleState;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.SqlTask;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regressions for quest gates at the service boundary, including automatic and testing paths. */
class TitleServiceTest {
    private final UUID uuid = UUID.randomUUID();
    private final SessionManager sessions = mock(SessionManager.class);
    private final PlayerSession session = mock(PlayerSession.class);
    private final Player player = mock(Player.class);
    private final ConfigSnapshot config = mock(ConfigSnapshot.class);
    private final TitleCatalog catalog = mock(TitleCatalog.class);
    private final StorageHealth health = mock(StorageHealth.class);
    private final PlayerRepository repo = mock(PlayerRepository.class);
    private final Messages messages = mock(Messages.class);
    private final StorageExecutor storage = mock(StorageExecutor.class);
    private final MainThread main = mock(MainThread.class);
    private TitleService titles;
    private TitleDefinition title;

    @BeforeEach void setup() {
        title = definition("undying", true, new Requirement.StreakAtLeast("25 deathless days", 25));
        when(sessions.get(player)).thenReturn(session);
        when(session.uuid()).thenReturn(uuid);
        when(session.usable()).thenReturn(true);
        when(session.tryBegin(anyString())).thenReturn(true);
        when(config.titles()).thenReturn(catalog);
        when(config.titleOwnershipLimit()).thenReturn(5);
        when(catalog.byId(anyString())).thenAnswer(call -> title);
        when(catalog.resolve(anyString())).thenAnswer(call -> title);
        when(health.isHealthy()).thenReturn(true);
        titles = new TitleService(sessions, storage, repo, health, main, () -> config, messages);
    }

    private static TitleDefinition definition(String id, boolean auto, Requirement requirement) {
        return new TitleDefinition(id, 1, id, "#ffffff", "", List.of(requirement), "ability", "", 0, auto);
    }

    @Test void automaticAwardCannotBypassUnfinishedDeathlessQuest() {
        when(session.stat("deathless.best_days")).thenReturn(24L);
        titles.autoAward(player, "undying");
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
        verify(session, never()).tryBegin(anyString());
    }

    @Test void automaticAwardClaimsAtExactQuestThresholdAndHonorsOwnershipLimit() throws Exception {
        when(session.stat("deathless.best_days")).thenReturn(25L);
        Connection connection = mock(Connection.class);
        doAnswer(call -> {
            SqlTask<Boolean> mutation = call.getArgument(2);
            mutation.run(connection);
            return null;
        }).when(sessions).mutate(eq(uuid), eq("title.auto-award"), any(), any());
        titles.autoAward(player, "undying");
        verify(repo).claimTitle(eq(connection), eq("undying"), eq(uuid), eq(5), anyLong());
    }

    @Test void automaticAwardOfConfiguredNewArrivalStillRequiresThirtyLevels() {
        title = definition("new_arrival", true, new Requirement.StatAtLeast("30 levels", List.of("levels"), 30));
        when(session.stat("levels")).thenReturn(29L);
        titles.autoAward(player, "new_arrival");
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
        when(session.stat("levels")).thenReturn(30L);
        titles.autoAward(player, "new_arrival");
        verify(sessions).mutate(eq(uuid), eq("title.auto-award"), any(), any());
    }

    @Test void ordinaryClaimCannotBypassQuestEvenInTestingConfig() {
        when(config.testingUnlockAll()).thenReturn(true);
        when(player.hasPermission("ascension.admin")).thenReturn(true);
        titles.claim(player, "undying", () -> fail("locked quest claimed"));
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
        verify(messages).send(eq(player), eq("title-not-eligible"));
    }

    @Test void testingConfigDoesNotUnlockTitlesForOrdinaryPlayers() {
        when(config.testingUnlockAll()).thenReturn(true);
        assertEquals(TitleState.UNAVAILABLE, titles.state(player, session, title));
    }

    @Test void explicitAdminTestingModeCanPreviewTitles() {
        when(config.testingUnlockAll()).thenReturn(true);
        when(player.hasPermission("ascension.admin")).thenReturn(true);
        assertEquals(TitleState.OWNED, titles.state(player, session, title));
    }

    @Test void testingEquipOfOrdinaryPlayerStillUsesOwnershipCheckedRepository() throws Exception {
        when(config.testingUnlockAll()).thenReturn(true);
        Connection connection = mock(Connection.class);
        doAnswer(call -> {
            SqlTask<Boolean> mutation = call.getArgument(2);
            mutation.run(connection);
            return null;
        }).when(sessions).mutate(eq(uuid), eq("title.equip"), any(), any());
        titles.equip(player, "undying", () -> {});
        verify(repo).equipTitle(connection, uuid, "undying");
        verify(repo, never()).equipTitleForTesting(any(), any(), anyString());
    }

    @Test void unhealthyStoragePreventsAutomaticClaim() {
        when(session.stat("deathless.best_days")).thenReturn(25L);
        when(health.isHealthy()).thenReturn(false);
        titles.autoAward(player, "undying");
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
    }

    @Test void alreadyOwnedAutomaticTitleRemainsTakenEvenWhenAnotherPlayerQualifies() {
        UUID other = UUID.randomUUID();
        when(storage.<Map<String, UUID>>submit(eq("titles.owners"), any())).thenReturn(
                CompletableFuture.completedFuture(Map.of("undying", other)));
        doAnswer(call -> {
            CompletableFuture<Map<String, UUID>> future = call.getArgument(0);
            BiConsumer<Map<String, UUID>, Throwable> completed = call.getArgument(1);
            completed.accept(future.join(), null);
            return null;
        }).when(main).onComplete(any(), any());
        titles.refreshOwners();
        when(session.stat("deathless.best_days")).thenReturn(25L);
        assertEquals(other, titles.owner("undying"));
        assertEquals(TitleState.TAKEN, titles.state(player, session, title));
        titles.autoAward(player, "undying");
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
    }

    @Test void unhealthyStoragePreventsEquipAndUnequip() {
        when(health.isHealthy()).thenReturn(false);
        titles.equip(player, "undying", () -> fail("unhealthy equip"));
        titles.unequip(player, () -> fail("unhealthy unequip"));
        verify(sessions, never()).mutate(any(), anyString(), any(), any());
    }
}
