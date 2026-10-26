package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.config.ConfigSnapshot;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TitleAccessTest {
    @Test void persistedTestingSelectionNeedsBothExplicitTestingAndLiveAdminPermission() {
        PlayerSession session = mock(PlayerSession.class);
        ConfigSnapshot config = mock(ConfigSnapshot.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(session.uuid()).thenReturn(uuid);
        when(session.usable()).thenReturn(true);
        when(session.equippedTitle()).thenReturn("ascendant");
        when(session.ownedTitles()).thenReturn(Set.of());
        assertFalse(TitleAccess.canUse(session, config, "ascendant"));
        when(config.testingUnlockAll()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            assertFalse(TitleAccess.canUse(session, config, "ascendant"));
            when(player.hasPermission("ascension.admin")).thenReturn(true);
            assertTrue(TitleAccess.canUse(session, config, "ascendant"));
            when(config.testingUnlockAll()).thenReturn(false);
            assertFalse(TitleAccess.canUse(session, config, "ascendant"));
            when(session.ownedTitles()).thenReturn(Set.of("ascendant"));
            assertTrue(TitleAccess.canUse(session, config, "ascendant"));
            assertFalse(TitleAccess.canUse(session, config, "bloodlord"));
        }
    }
}
