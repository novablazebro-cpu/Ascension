package com.leadsystems.minecraft.ascension.progress;

import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressListenerTest {

    private final MainThread main = mock(MainThread.class);
    private final ProgressListener listener = new ProgressListener(mock(ProgressTracker.class),
            mock(PlacedBlockTracker.class), mock(MobClassifier.class), () -> null,
            mock(StorageExecutor.class), mock(PlayerRepository.class), main);

    @Test
    void removingElytraFromNaturalFrameSchedulesAcquisitionCheck() {
        ItemFrame frame = frame(Set.of());
        PlayerItemFrameChangeEvent event = removal(frame);

        listener.onItemFrameChange(event);

        verify(main).later(eq(1L), any(Runnable.class));
    }

    @Test
    void removingElytraFromModifiedFrameDoesNotCount() {
        ItemFrame frame = frame(Set.of("ascension.placed_end_frame"));

        listener.onItemFrameChange(removal(frame));

        verify(main, never()).later(eq(1L), any(Runnable.class));
    }

    private static ItemFrame frame(Set<String> tags) {
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getScoreboardTags()).thenReturn(tags);
        when(frame.getItem()).thenReturn(new ItemStack(Material.ELYTRA));
        when(frame.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        when(frame.getUniqueId()).thenReturn(UUID.randomUUID());
        return frame;
    }

    private static PlayerItemFrameChangeEvent removal(ItemFrame frame) {
        PlayerItemFrameChangeEvent event = mock(PlayerItemFrameChangeEvent.class);
        when(event.getAction()).thenReturn(PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE);
        when(event.getItemFrame()).thenReturn(frame);
        when(event.getItemStack()).thenReturn(new ItemStack(Material.ELYTRA));
        when(event.getPlayer()).thenReturn(mock(Player.class));
        return event;
    }
}
