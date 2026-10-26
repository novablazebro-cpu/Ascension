package com.leadsystems.minecraft.ascension.gui;

import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class MenuListenerTest {
    @Test void deferredClickCannotExecuteAfterClosingOrRefreshingTheChest() {
        Player player = mock(Player.class);
        UUID viewer = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(viewer);
        when(player.isOnline()).thenReturn(true);
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);
        Inventory chest = mock(Inventory.class);
        Inventory closed = mock(Inventory.class);
        AtomicReference<Inventory> current = new AtomicReference<>(chest);
        when(view.getTopInventory()).thenAnswer(invocation -> current.get());
        when(chest.getSize()).thenReturn(54);
        Menu.Button button = new Menu.Button(Component.text("Buy"), List.of(), Material.EMERALD_BLOCK,
                Menu.Action.MARKET_SHOP_BUY, "quote", false);
        Menu.Screen screen = new Menu.Screen("market_shop", Component.text("Shop"), List.of(), List.of(),
                List.of(button), false);
        MenuHolder holder = new MenuHolder(viewer, 1, Map.of(9, button));
        holder.attach(chest);
        holder.update(screen, 1, Map.of(9, button));
        // MockBukkit does not implement Paper's getHolder(false); provide this boundary explicitly.
        when(chest.getHolder(false)).thenReturn(holder);
        var menus = mock(MenuService.class);
        var main = mock(MainThread.class);
        AtomicReference<Runnable> nextTick = new AtomicReference<>();
        doAnswer(invocation -> { nextTick.set(invocation.getArgument(1)); return null; })
                .when(main).later(anyLong(), any(Runnable.class));
        var listener = new MenuListener(menus, main);

        var click = click(player);
        listener.onClick(click);
        assertTrue(click.isCancelled());
        current.set(closed);
        nextTick.get().run();
        verifyNoInteractions(menus);

        current.set(chest);
        holder.update(screen, 2, Map.of(9, button));
        listener.onClick(click(player));
        holder.update(screen, 3, Map.of(9, button));
        nextTick.get().run();
        verifyNoInteractions(menus);

        listener.onClick(click(player));
        nextTick.get().run();
        verify(menus).handle(player, 3, button);
    }

    private static InventoryClickEvent click(org.bukkit.entity.Player player) {
        return new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 9,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }
}
